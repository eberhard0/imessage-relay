package com.eberhard.iphotos.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

sealed class BridgeException(message: String) : IOException(message) {
    class Unreachable(cause: IOException) : BridgeException(cause.message ?: "unreachable")
    class Unauthorized : BridgeException("The Mac rejected the token")
    class Server(val code: String, message: String) : BridgeException(message)
}

@Serializable data class UploadReply(val status: String = "", val name: String = "", val imported_at: Long = 0)
@Serializable data class ErrorReply(val error: String = "", val message: String = "")
@Serializable data class HaveRequest(val sha256: List<String>)
@Serializable data class HaveReply(val have: List<String> = emptyList(), val total: Int = 0)
@Serializable data class Health(val ok: Boolean = false, val version: String = "", val photos_imported: Int = 0)
@Serializable data class Album(val id: String, val title: String = "", val kind: String = "album", val path: String = "", val count: Int = 0, val cover: String = "", val owner: String = "")
@Serializable data class AlbumsReply(val albums: List<Album> = emptyList())
@Serializable data class MediaItem(val uuid: String, val name: String = "", val video: Boolean = false, val date: Long = 0, val w: Int = 0, val h: Int = 0, val dur: Double = 0.0, val fav: Boolean = false)
@Serializable data class ItemsReply(val items: List<MediaItem> = emptyList(), val total: Int = 0, val offset: Int = 0)

/** Talks to imsg-bridge's /v1/photos endpoints on the Mac. */
class Bridge(private val context: Context, private val prefs: Prefs) {
    private val json = Json { ignoreUnknownKeys = true }
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.MINUTES)   // Photos.app import of a long video
        .writeTimeout(10, TimeUnit.MINUTES)
        .retryOnConnectionFailure(false)
        .build()

    /** Takes the bridge connection from the iMessage app (same signing key). True if it had one. */
    fun borrowFromIMessage(): Boolean {
        val bundle = runCatching {
            context.contentResolver.call(Uri.parse("content://com.eberhard.relay.connection"), "connection", null, null)
        }.getOrNull() ?: return false
        val url = bundle.getString("base_url").orEmpty()
        val token = bundle.getString("token").orEmpty()
        if (url.isEmpty() || token.isEmpty()) return false
        prefs.baseUrl = url
        prefs.token = token
        return true
    }

    fun health(): Health = json.decodeFromString(call(Request.Builder().url(prefs.baseUrl + "/v1/health")))

    // The Mac's Photos library (bridge 0.5.0+): albums, items, and the files themselves.
    fun albums(): List<Album> = json.decodeFromString<AlbumsReply>(call(Request.Builder().url(prefs.baseUrl + "/v1/library/albums"))).albums
    fun items(album: String, offset: Int, limit: Int = 300): ItemsReply =
        json.decodeFromString(call(Request.Builder().url(prefs.baseUrl + "/v1/library/items?album=" + Uri.encode(album) + "&offset=$offset&limit=$limit")))
    fun mediaUrl(uuid: String, size: String) = prefs.baseUrl + "/v1/library/media/$uuid?size=$size"
    val authHeader: String get() = "Bearer ${prefs.token}"

    fun have(shas: List<String>): HaveReply = json.decodeFromString(
        call(Request.Builder().url(prefs.baseUrl + "/v1/photos/have").post(json.encodeToString(HaveRequest.serializer(), HaveRequest(shas)).toRequestBody("application/json".toMediaType()))),
    )

    /** SHA-256 of a content URI's bytes, streamed. */
    fun sha256(uri: Uri): String {
        val md = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        } ?: throw IOException("can't open $uri")
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Streams the file to the Mac; returns "imported" or "duplicate". */
    fun upload(uri: Uri, name: String, size: Long, sha: String): String {
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength(): Long = if (size > 0) size else -1
            override fun writeTo(sink: BufferedSink) {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        sink.write(buf, 0, n)
                    }
                } ?: throw IOException("can't open $uri")
            }
        }
        val reply: UploadReply = json.decodeFromString(
            call(
                Request.Builder().url(prefs.baseUrl + "/v1/photos")
                    .header("X-Photo-Name", name.ifBlank { "photo" })
                    .header("X-Photo-Sha256", sha)
                    .post(body),
            ),
        )
        return reply.status
    }

    private fun call(builder: Request.Builder): String {
        val request = builder.header("Authorization", "Bearer ${prefs.token}").build()
        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw BridgeException.Unreachable(e)
        }
        response.use {
            val text = it.body?.string().orEmpty()
            return when {
                it.code == 401 -> throw BridgeException.Unauthorized()
                !it.isSuccessful -> {
                    val err = runCatching { json.decodeFromString<ErrorReply>(text) }.getOrNull()
                    throw BridgeException.Server(err?.error ?: "http_${it.code}", err?.message ?: "HTTP ${it.code}")
                }
                else -> text
            }
        }
    }

    /** Whether the phone currently has a Tailscale network (the bridge lives on 100.x). */
    fun tailscaleUp(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.allNetworks.any { n ->
            cm.getLinkProperties(n)?.linkAddresses?.any { it.address.hostAddress?.startsWith("100.") == true } == true
        }
    }
}
