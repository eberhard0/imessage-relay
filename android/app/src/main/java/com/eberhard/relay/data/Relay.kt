package com.eberhard.relay.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Connection state, phrased for the banner at the top of the app. */
sealed interface Status {
    data object NotConfigured : Status
    data object Connecting : Status
    data object Connected : Status
    data object TailscaleOff : Status
    data object MacUnreachable : Status
    data object BadToken : Status
    data class MacProblem(val detail: String) : Status
}

/**
 * App-wide state shared by the service, the notification reply and the UI.
 * Message text from the Mac is only ever displayed; nothing in it drives an action.
 */
object Relay {

    lateinit var prefs: Prefs
        private set
    lateinit var api: Api
        private set
    private lateinit var appContext: Context

    private val _status = MutableStateFlow<Status>(Status.NotConfigured)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _health = MutableStateFlow<Health?>(null)
    val health: StateFlow<Health?> = _health.asStateFlow()

    private val _chats = MutableStateFlow<List<Chat>>(emptyList())
    val chats: StateFlow<List<Chat>> = _chats.asStateFlow()

    /** Loaded history per chat GUID, oldest first. */
    private val _threads = MutableStateFlow<Map<String, List<Message>>>(emptyMap())
    val threads: StateFlow<Map<String, List<Message>>> = _threads.asStateFlow()

    /** Chats hidden in Relay: GUID → when. A message after that time brings the chat back. */
    private val _hidden = MutableStateFlow<Map<String, Long>>(emptyMap())
    val hidden: StateFlow<Map<String, Long>> = _hidden.asStateFlow()

    fun isHidden(chat: Chat, hidden: Map<String, Long> = _hidden.value): Boolean {
        val at = hidden[chat.guid] ?: return false
        return (chat.lastMessage?.date ?: 0) <= at
    }

    private val _favorites = MutableStateFlow<Set<String>>(emptySet())
    val favorites: StateFlow<Set<String>> = _favorites.asStateFlow()

    fun setFavorite(guid: String, favorite: Boolean) {
        _favorites.update { if (favorite) it + guid else it - guid }
        prefs.favoriteChats = _favorites.value
    }

    fun hide(guids: Collection<String>) {
        val now = System.currentTimeMillis()
        _hidden.update { it + guids.associateWith { now } }
        prefs.hiddenChats = _hidden.value
        if (guids.any { it in _favorites.value }) {
            _favorites.update { it - guids.toSet() }
            prefs.favoriteChats = _favorites.value
        }
    }

    fun unhide(guid: String) {
        _hidden.update { it - guid }
        prefs.hiddenChats = _hidden.value
    }

    /** The thread on screen while the app is in front: no notification for it. */
    @Volatile var visibleChat: String? = null

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        appContext = context.applicationContext
        prefs = Prefs(appContext)
        api = Api(prefs)
        _hidden.value = prefs.hiddenChats
        _favorites.value = prefs.favoriteChats
        _status.value = if (prefs.isConfigured) Status.Connecting else Status.NotConfigured
    }

    fun setStatus(status: Status) {
        _status.value = status
    }

    /** True when some VPN (on this phone, Tailscale) is up. */
    fun vpnActive(): Boolean {
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return false
        @Suppress("DEPRECATION")
        return cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
    }

    /** Maps a failure to the status the banner should show. */
    fun statusFor(e: Throwable): Status = when (e) {
        is BridgeException.Unauthorized -> Status.BadToken
        is BridgeException.Server -> when (e.code) {
            "db_unreadable" -> Status.MacProblem("The Mac can't read Messages: give imsg-bridge Full Disk Access.")
            "automation_denied" -> Status.MacProblem("The Mac won't let the bridge send: allow it under Privacy & Security › Automation.")
            else -> Status.MacProblem(e.message ?: e.code)
        }
        else -> if (vpnActive()) Status.MacUnreachable else Status.TailscaleOff
    }

    suspend fun refreshHealth(): Health {
        val h = api.health()
        _health.value = h
        return h
    }

    suspend fun refreshChats() {
        _chats.value = api.chats()
    }

    suspend fun loadThread(chatGuid: String) {
        val msgs = api.messages(chatGuid)
        _threads.update { it + (chatGuid to mergeMessages(it[chatGuid].orEmpty(), msgs)) }
    }

    /** Older page above what's loaded. Returns false when there is nothing older. */
    suspend fun loadOlder(chatGuid: String): Boolean {
        val oldest = _threads.value[chatGuid]?.firstOrNull() ?: return false
        val msgs = api.messages(chatGuid, before = oldest.id)
        if (msgs.isEmpty()) return false
        _threads.update { it + (chatGuid to mergeMessages(it[chatGuid].orEmpty(), msgs)) }
        return true
    }

    private fun mergeMessages(a: List<Message>, b: List<Message>): List<Message> =
        (a + b).associateBy { it.id }.values.sortedWith(compareBy({ it.date }, { it.id }))

    /** A message from the event stream (either direction): update the thread and the chat list. */
    fun onEvent(m: Message, fromStream: Boolean = true) {
        // Only the stream moves the catch-up cursor: a just-sent reply can have a
        // higher ROWID than an incoming message the stream hasn't delivered yet.
        if (fromStream && m.id > prefs.lastEventId) prefs.lastEventId = m.id
        _threads.update { threads ->
            val existing = threads[m.chatGuid] ?: return@update threads // not loaded: fetched when opened
            threads + (m.chatGuid to mergeMessages(existing, listOf(m)))
        }
        _chats.update { chats ->
            val current = chats.firstOrNull { it.guid == m.chatGuid }
            val updated = (current ?: Chat(guid = m.chatGuid, name = m.chatName.ifEmpty { m.senderLabel }, isGroup = m.isGroup))
                .copy(
                    lastMessage = m,
                    unread = when {
                        m.fromMe || visibleChat == m.chatGuid -> 0
                        else -> (current?.unread ?: 0) + 1
                    },
                )
            listOf(updated) + chats.filter { it.guid != m.chatGuid }
        }
    }

    fun markSeen(chatGuid: String) {
        _chats.update { chats -> chats.map { if (it.guid == chatGuid) it.copy(unread = 0) else it } }
    }

    suspend fun send(chatGuid: String, text: String): SendResponse {
        val result = api.send(chatGuid, text)
        result.message?.let { onEvent(it, fromStream = false) }
        return result
    }

    fun chatTitle(chatGuid: String): String =
        _chats.value.firstOrNull { it.guid == chatGuid }?.title ?: chatGuid
}
