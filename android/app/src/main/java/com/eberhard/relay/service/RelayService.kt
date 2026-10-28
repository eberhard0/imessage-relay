package com.eberhard.relay.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.eberhard.relay.data.BridgeException
import com.eberhard.relay.data.Message
import com.eberhard.relay.data.Relay
import com.eberhard.relay.data.Status
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.decodeFromString
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

/**
 * Keeps the event stream from the Mac open while the app is configured, and
 * turns incoming messages into notifications. Reconnects with backoff, and
 * right away when the network comes back.
 */
class RelayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Relay.init(this)
        Notifier.createChannels(this)
        ServiceCompat.startForeground(
            this,
            Notifier.STATUS_ID,
            Notifier.statusNotification(this, "Connecting to the Mac…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING,
        )
        val cm = getSystemService(ConnectivityManager::class.java)
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                wake.trySend(Unit)
            }
        }.also { cm.registerDefaultNetworkCallback(it) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Relay.prefs.isConfigured) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_RECONNECT) wake.trySend(Unit)
        if (loop?.isActive != true) loop = scope.launch { runLoop() }
        return START_STICKY
    }

    override fun onDestroy() {
        networkCallback?.let { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        scope.cancel()
        super.onDestroy()
    }

    private fun setStatus(status: Status) {
        Relay.setStatus(status)
        Notifier.updateStatus(
            this,
            when (status) {
                Status.Connected -> "Connected to the Mac"
                Status.Connecting -> "Connecting to the Mac…"
                Status.TailscaleOff -> "Tailscale is off — turn it on to get messages"
                Status.MacUnreachable -> "Can't reach the Mac (asleep, offline, or bridge not running)"
                Status.BadToken -> "The Mac rejected the token — check it in Relay"
                is Status.MacProblem -> status.detail
                Status.NotConfigured -> "Not set up"
            },
        )
    }

    private suspend fun runLoop() {
        var backoffMs = 2_000L
        while (true) {
            setStatus(Status.Connecting)
            val started = System.currentTimeMillis()
            val failure = try {
                // Health first, so a permission problem on the Mac is named, not just "disconnected".
                val health = Relay.refreshHealth()
                if (!health.dbReadable) {
                    BridgeException.Server("db_unreadable", health.dbError)
                } else {
                    runCatching { Relay.refreshChats() }
                    stream()
                }
            } catch (e: Exception) {
                e
            }
            setStatus(failure?.let { Relay.statusFor(it) } ?: Status.MacUnreachable)
            // A stream that stayed up a while resets the backoff.
            if (System.currentTimeMillis() - started > 60_000) backoffMs = 2_000L
            withTimeoutOrNull(backoffMs) { wake.receive() }
            backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
        }
    }

    /** Runs one event stream until it closes; returns why it ended (null: closed cleanly). */
    private suspend fun stream(): Exception? {
        val done = Channel<Exception?>(1)
        val request = Relay.api.request("/v1/events?since=${Relay.prefs.lastEventId}")
            .header("Accept", "text/event-stream")
            .build()
        val listener = object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                setStatus(Status.Connected)
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (type != "message") return
                val m = runCatching { Relay.api.json.decodeFromString<Message>(data) }.getOrNull() ?: return
                handle(m)
            }

            override fun onClosed(eventSource: EventSource) {
                done.trySend(null)
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                done.trySend(
                    when (response?.code) {
                        401 -> BridgeException.Unauthorized()
                        null -> BridgeException.Unreachable(t ?: Exception("stream failed"))
                        else -> BridgeException.Server("http_${response.code}", "HTTP ${response.code}")
                    },
                )
            }
        }
        val source = EventSources.createFactory(Relay.api.streamClient).newEventSource(request, listener)
        return try {
            done.receive()
        } finally {
            source.cancel()
        }
    }

    private fun handle(m: Message) {
        val alreadySeen = m.id <= Relay.prefs.lastEventId
        Relay.onEvent(m)
        if (m.fromMe || alreadySeen) return
        if (Relay.visibleChat == m.chatGuid) return // the thread is on screen
        Notifier.show(this, m)
    }

    companion object {
        const val ACTION_RECONNECT = "com.eberhard.relay.RECONNECT"

        fun start(context: Context, reconnect: Boolean = false) {
            val intent = Intent(context, RelayService::class.java)
            if (reconnect) intent.action = ACTION_RECONNECT
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RelayService::class.java))
        }
    }
}
