package com.eberhard.relay.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.eberhard.relay.R
import com.eberhard.relay.data.Message
import com.eberhard.relay.data.Relay
import com.eberhard.relay.ui.MainActivity

/** One MessagingStyle notification per conversation, with an inline reply. */
object Notifier {

    const val CHANNEL_MESSAGES = "messages"
    const val CHANNEL_STATUS = "status"
    const val STATUS_ID = 1
    const val KEY_REPLY = "reply"
    const val EXTRA_CHAT = "chat_guid"

    private val me = Person.Builder().setName("Me").setKey("me").build()

    /** Recent lines per chat, so a notification shows the last few messages. */
    private val recent = mutableMapOf<String, MutableList<Message>>()

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "New iMessages from the Mac"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, "Connection", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Keeps the link to the Mac open"
                setShowBadge(false)
            },
        )
    }

    private fun notificationId(chatGuid: String) = chatGuid.hashCode() and 0x7fffffff or 0x100

    fun openChatIntent(context: Context, chatGuid: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply { chatGuid?.let { putExtra(EXTRA_CHAT, it) } }
        return PendingIntent.getActivity(
            context,
            chatGuid?.hashCode() ?: 0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun statusNotification(context: Context, text: String) =
        NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("iMessage")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openChatIntent(context, null))
            .build()

    fun updateStatus(context: Context, text: String) {
        if (!canPost(context)) return
        NotificationManagerCompat.from(context).notify(STATUS_ID, statusNotification(context, text))
    }

    private fun canPost(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /** An incoming message, or the user's own reply added to an existing notification. */
    fun show(context: Context, m: Message, alert: Boolean = true) {
        if (!canPost(context)) return
        val lines = synchronized(recent) {
            recent.getOrPut(m.chatGuid) { mutableListOf() }.apply {
                if (none { it.id == m.id }) add(m)
                while (size > 8) removeAt(0)
            }.toList()
        }
        val title = Relay.chatTitle(m.chatGuid).takeIf { it != m.chatGuid } ?: m.chatName.ifEmpty { m.senderLabel }

        val style = NotificationCompat.MessagingStyle(me)
            .setConversationTitle(if (m.isGroup) title else null)
            .setGroupConversation(m.isGroup)
        lines.forEach { line ->
            val person = if (line.fromMe) null else
                Person.Builder().setName(line.senderLabel.ifEmpty { title }).setKey(line.sender).build()
            style.addMessage(line.preview, line.date, person)
        }

        val replyIntent = Intent(context, ReplyReceiver::class.java).putExtra(EXTRA_CHAT, m.chatGuid)
        val replyPending = PendingIntent.getBroadcast(
            context,
            notificationId(m.chatGuid),
            replyIntent,
            // RemoteInput must be able to fill in the typed text.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val reply = NotificationCompat.Action.Builder(
            R.drawable.ic_notify,
            "Reply",
            replyPending,
        ).addRemoteInput(RemoteInput.Builder(KEY_REPLY).setLabel("Reply to $title").build())
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .build()

        val n = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notify)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(openChatIntent(context, m.chatGuid))
            .addAction(reply)
            .setAutoCancel(true)
            .setOnlyAlertOnce(!alert)
            .setSilent(!alert)
            .build()
        NotificationManagerCompat.from(context).notify(notificationId(m.chatGuid), n)
    }

    /** Shown in place of the conversation when an inline reply fails. */
    fun replyFailed(context: Context, chatGuid: String, reason: String) {
        if (!canPost(context)) return
        val n = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Not sent to ${Relay.chatTitle(chatGuid)}")
            .setContentText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
            .setContentIntent(openChatIntent(context, chatGuid))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(notificationId(chatGuid), n)
    }

    fun clear(context: Context, chatGuid: String) {
        synchronized(recent) { recent.remove(chatGuid) }
        NotificationManagerCompat.from(context).cancel(notificationId(chatGuid))
    }
}
