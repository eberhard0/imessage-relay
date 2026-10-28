package com.eberhard.relay.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Wire types of the Mac bridge (bridge/db.go). */

@Serializable
data class Attachment(
    val id: Long,
    val name: String = "",
    val mime: String = "",
    val size: Long = 0,
) {
    val isImage: Boolean get() = mime.startsWith("image/")
}

@Serializable
data class Message(
    val id: Long,
    val guid: String = "",
    @SerialName("chat_guid") val chatGuid: String,
    @SerialName("chat_name") val chatName: String = "",
    @SerialName("is_group") val isGroup: Boolean = false,
    /** Unix milliseconds. */
    val date: Long,
    @SerialName("from_me") val fromMe: Boolean,
    val sender: String = "",
    @SerialName("sender_name") val senderName: String = "",
    val text: String = "",
    val error: Int = 0,
    val attachments: List<Attachment> = emptyList(),
) {
    val senderLabel: String get() = senderName.ifEmpty { sender }

    /** One line for lists and notifications. */
    val preview: String
        get() = text.ifEmpty {
            when {
                attachments.any { it.isImage } -> "📷 Photo"
                attachments.isNotEmpty() -> "📎 ${attachments.first().name.ifEmpty { "Attachment" }}"
                else -> ""
            }
        }
}

@Serializable
data class Participant(val handle: String, val name: String = "") {
    val label: String get() = name.ifEmpty { handle }
}

@Serializable
data class Chat(
    val guid: String,
    val name: String = "",
    @SerialName("is_group") val isGroup: Boolean = false,
    val participants: List<Participant> = emptyList(),
    @SerialName("last_message") val lastMessage: Message? = null,
    val unread: Int = 0,
) {
    val title: String get() = name.ifEmpty { participants.joinToString { it.label }.ifEmpty { guid } }
}

@Serializable
data class ChatsResponse(val chats: List<Chat> = emptyList())

@Serializable
data class MessagesResponse(val messages: List<Message> = emptyList())

@Serializable
data class Health(
    val ok: Boolean = false,
    val version: String = "",
    @SerialName("db_readable") val dbReadable: Boolean = false,
    @SerialName("db_error") val dbError: String = "",
    /** "ok", "denied", "pending" or "unknown". */
    val automation: String = "unknown",
    val contacts: Int = 0,
)

@Serializable
data class SendRequest(
    @SerialName("chat_guid") val chatGuid: String? = null,
    val handle: String? = null,
    val text: String,
)

@Serializable
data class SendResponse(
    val ok: Boolean = false,
    val confirmed: Boolean = false,
    val message: Message? = null,
)

@Serializable
data class ErrorResponse(val error: String = "", val message: String = "")
