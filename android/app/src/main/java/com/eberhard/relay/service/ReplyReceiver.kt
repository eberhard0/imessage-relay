package com.eberhard.relay.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.eberhard.relay.data.BridgeException
import com.eberhard.relay.data.Message
import com.eberhard.relay.data.Relay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Inline reply from a notification: sends exactly the typed text to that chat. */
class ReplyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Relay.init(context)
        val chatGuid = intent.getStringExtra(Notifier.EXTRA_CHAT) ?: return
        val text = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(Notifier.KEY_REPLY)?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = Relay.send(chatGuid, text)
                // Show the reply under the conversation so the notification settles.
                val sent = result.message ?: Message(
                    id = -System.currentTimeMillis(),
                    chatGuid = chatGuid,
                    date = System.currentTimeMillis(),
                    fromMe = true,
                    text = text,
                )
                Notifier.show(context, sent, alert = false)
            } catch (e: Exception) {
                val reason = when (e) {
                    is BridgeException.Unreachable -> "Couldn't reach the Mac. Your reply was not sent."
                    is BridgeException.Unauthorized -> "The Mac rejected the token. Your reply was not sent."
                    else -> "${e.message}. Your reply was not sent."
                }
                Notifier.replyFailed(context, chatGuid, reason)
            } finally {
                pending.finish()
            }
        }
    }
}
