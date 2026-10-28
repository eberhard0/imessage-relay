package com.eberhard.relay.data

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Why a call to the bridge failed, in terms the UI can explain. */
sealed class BridgeException(message: String) : IOException(message) {
    class Unreachable(cause: Throwable) : BridgeException(cause.message ?: "unreachable")
    class Unauthorized : BridgeException("The Mac rejected the token")
    class Server(val code: String, message: String) : BridgeException(message)
}

class Api(private val prefs: Prefs) {

    val json = Json { ignoreUnknownKeys = true }

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        // Sends wait for Messages.app plus up to 8 s of confirmation on the Mac.
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    /** For the event stream: the bridge pings every 15 s, so 45 s of silence means it's gone. */
    val streamClient: OkHttpClient = client.newBuilder()
        .readTimeout(45, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    val authHeader: String get() = "Bearer ${prefs.token}"

    fun url(path: String): String = prefs.baseUrl + path

    fun attachmentUrl(id: Long): String = url("/v1/attachments/$id")

    fun request(path: String): Request.Builder =
        Request.Builder().url(url(path)).header("Authorization", authHeader)

    private suspend fun call(builder: Request.Builder): String = withContext(Dispatchers.IO) {
        val response = try {
            client.newCall(builder.build()).execute()
        } catch (e: IOException) {
            throw BridgeException.Unreachable(e)
        }
        response.use {
            val body = it.body?.string().orEmpty()
            when {
                it.code == 401 -> throw BridgeException.Unauthorized()
                !it.isSuccessful -> {
                    val err = runCatching { json.decodeFromString<ErrorResponse>(body) }.getOrNull()
                    throw BridgeException.Server(err?.error ?: "http_${it.code}", err?.message ?: "HTTP ${it.code}")
                }
                else -> body
            }
        }
    }

    suspend fun health(): Health = json.decodeFromString(call(request("/v1/health")))

    suspend fun chats(): List<Chat> = json.decodeFromString<ChatsResponse>(call(request("/v1/chats?limit=60"))).chats

    suspend fun messages(chatGuid: String, before: Long = 0, limit: Int = 50): List<Message> {
        val path = "/v1/chats/${Uri.encode(chatGuid)}/messages?limit=$limit" + if (before > 0) "&before=$before" else ""
        return json.decodeFromString<MessagesResponse>(call(request(path))).messages
    }

    /** Sends exactly [text] to the chat. Only ever called from an explicit user action. */
    suspend fun send(chatGuid: String, text: String): SendResponse {
        val body = json.encodeToString(SendRequest(chatGuid = chatGuid, text = text))
            .toRequestBody("application/json".toMediaType())
        return json.decodeFromString(call(request("/v1/send").post(body)))
    }
}
