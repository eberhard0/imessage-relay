package com.eberhard.inotes.data

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

// ---- wire types (bridge/notes.go) --------------------------------------------

@Serializable
data class Folder(val id: String, val name: String, val parent: String = "", val count: Int = 0)

@Serializable
data class NoteSummary(
    val id: String,
    val title: String = "",
    val snippet: String = "",
    val folder: String = "",
    /** Unix milliseconds. */
    val created: Long = 0,
    val modified: Long = 0,
    val pinned: Boolean = false,
    val locked: Boolean = false,
    @SerialName("has_attachments") val hasAttachments: Boolean = false,
)

@Serializable
data class Span(
    val text: String = "",
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val link: String = "",
)

@Serializable
data class NoteAttachment(
    val id: String,
    /** image, pdf, file, table, link or other. */
    val kind: String = "other",
    val uti: String = "",
    val name: String = "",
    val url: String = "",
    val label: String = "Attachment",
)

@Serializable
data class Block(
    /** title, heading, subheading, text, mono, bullet, dash, number, check or attachment. */
    val type: String = "text",
    val spans: List<Span> = emptyList(),
    val indent: Int = 0,
    val done: Boolean = false,
    val quote: Boolean = false,
    val attachment: NoteAttachment? = null,
)

@Serializable
data class Note(
    val id: String,
    val title: String = "",
    val folder: String = "",
    val created: Long = 0,
    val modified: Long = 0,
    val locked: Boolean = false,
    val blocks: List<Block> = emptyList(),
    /** The Mac allows adding text: no checklists, attachments or lock. */
    val appendable: Boolean = false,
)

@Serializable private data class WriteRequest(val folder: String = "", val text: String)
@Serializable private data class WriteResponse(val ok: Boolean = false, val id: String = "")

@Serializable private data class FoldersResponse(val folders: List<Folder> = emptyList())
@Serializable private data class NotesResponse(val notes: List<NoteSummary> = emptyList())
@Serializable private data class Changes(val etag: String = "")
@Serializable private data class ErrorResponse(val error: String = "", val message: String = "")

/** Everything the list screens need, kept on disk for offline use. */
@Serializable
private data class Library(val etag: String = "", val folders: List<Folder> = emptyList(), val notes: List<NoteSummary> = emptyList())

// ---- connection --------------------------------------------------------------

/** Bridge address and token, encrypted with a key in the Android Keystore. */
class Prefs(context: Context) {
    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        "inotes",
        MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
        context,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    var baseUrl: String
        get() = prefs.getString("base_url", DEFAULT_URL) ?: DEFAULT_URL
        set(value) = prefs.edit().putString("base_url", value.trim().trimEnd('/')).apply()

    var token: String
        get() = prefs.getString("token", "") ?: ""
        set(value) = prefs.edit().putString("token", value.trim()).apply()

    val isConfigured: Boolean get() = token.isNotEmpty() && baseUrl.isNotEmpty()

    companion object {
        const val DEFAULT_URL = "http://100.90.106.50:8765"
    }
}

sealed class BridgeException(message: String) : IOException(message) {
    class Unreachable(cause: Throwable) : BridgeException(cause.message ?: "unreachable")
    class Unauthorized : BridgeException("The Mac rejected the token")
    class Server(val code: String, message: String) : BridgeException(message)
}

sealed interface Status {
    data object Idle : Status
    data object Loading : Status
    data object Offline : Status
    data object TailscaleOff : Status
    data object BadToken : Status
    data class Problem(val detail: String) : Status
}

class NotesApplication : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        Notes.init(this)
    }

    // Pictures come from the Mac with the bridge token and are kept on disk, so a note
    // opened once still shows its images offline.
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient(Notes.http)
        .respectCacheHeaders(false)
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("images")).maxSizeBytes(300L shl 20).build() }
        .build()
}

/**
 * App-wide state. The only changes sent to the Mac are a new note and text added to the end
 * of a plain note, each from an explicit tap.
 */
object Notes {
    lateinit var prefs: Prefs
        private set
    private lateinit var appContext: Context
    private val json = Json { ignoreUnknownKeys = true }

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _folders = MutableStateFlow<List<Folder>>(emptyList())
    val folders: StateFlow<List<Folder>> = _folders.asStateFlow()

    private val _notes = MutableStateFlow<List<NoteSummary>>(emptyList())
    val notes: StateFlow<List<NoteSummary>> = _notes.asStateFlow()

    private val _opened = MutableStateFlow<Map<String, Note>>(emptyMap())
    val opened: StateFlow<Map<String, Note>> = _opened.asStateFlow()

    private var etag = ""

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        appContext = context.applicationContext
        prefs = Prefs(appContext)
        runCatching { json.decodeFromString<Library>(libraryFile.readText()) }.getOrNull()?.let {
            etag = it.etag
            _folders.value = it.folders
            _notes.value = it.notes
        }
    }

    private val libraryFile get() = File(appContext.filesDir, "library.json")
    private fun noteFile(id: String) = File(File(appContext.filesDir, "notes").apply { mkdirs() }, "$id.json")

    /** Takes the bridge connection from the iMessage app (same signing key). True if it had one. */
    fun borrowFromIMessage(): Boolean {
        val bundle = runCatching {
            appContext.contentResolver.call(Uri.parse("content://com.eberhard.relay.connection"), "connection", null, null)
        }.getOrNull() ?: return false
        val url = bundle.getString("base_url").orEmpty()
        val token = bundle.getString("token").orEmpty()
        if (url.isEmpty() || token.isEmpty()) return false
        prefs.baseUrl = url
        prefs.token = token
        return true
    }

    fun authHeader(): String = "Bearer ${prefs.token}"
    fun attachmentUrl(id: String): String = prefs.baseUrl + "/v1/notes/attachments/" + Uri.encode(id)

    private suspend fun get(path: String): String = call(Request.Builder().url(prefs.baseUrl + path))

    private suspend fun post(path: String, body: WriteRequest): String = call(
        Request.Builder().url(prefs.baseUrl + path)
            .post(json.encodeToString(body).toRequestBody("application/json".toMediaType())),
    )

    private suspend fun call(builder: Request.Builder): String = withContext(Dispatchers.IO) {
        val request = builder.header("Authorization", authHeader()).build()
        val response = try {
            http.newCall(request).execute()
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

    private fun statusFor(e: Exception): Status = when (e) {
        is BridgeException.Unauthorized -> Status.BadToken
        is BridgeException.Unreachable -> if (tailscaleOn()) Status.Offline else Status.TailscaleOff
        is BridgeException.Server ->
            if (e.code == "notes_unreadable") Status.Problem("The Mac can't read Notes: give imsg-bridge Full Disk Access on the Mac.")
            else Status.Problem(e.message ?: "The Mac answered with an error")
        else -> Status.Problem(e.message ?: "Something went wrong")
    }

    private fun tailscaleOn(): Boolean {
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return true
        return cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
    }

    /** Reloads folders and the note list; skipped when the Mac reports no change, unless forced. */
    suspend fun refresh(force: Boolean = false) {
        if (!prefs.isConfigured) return
        _status.value = Status.Loading
        try {
            val tag = json.decodeFromString<Changes>(get("/v1/notes/changes")).etag
            if (force || tag != etag || _notes.value.isEmpty()) {
                val folders = json.decodeFromString<FoldersResponse>(get("/v1/notes/folders")).folders
                val notes = json.decodeFromString<NotesResponse>(get("/v1/notes?limit=5000")).notes
                _folders.value = folders
                _notes.value = notes
                etag = tag
                withContext(Dispatchers.IO) { libraryFile.writeText(json.encodeToString(Library(tag, folders, notes))) }
            }
            _status.value = Status.Idle
        } catch (e: Exception) {
            _status.value = statusFor(e)
        }
    }

    /** The cached copy right away (if any), then the Mac's current one. */
    suspend fun open(id: String): Note? {
        val cached = _opened.value[id] ?: withContext(Dispatchers.IO) {
            runCatching { json.decodeFromString<Note>(noteFile(id).readText()) }.getOrNull()
        }
        if (cached != null) _opened.update { it + (id to cached) }
        return try {
            val body = get("/v1/notes/" + Uri.encode(id))
            val note = json.decodeFromString<Note>(body)
            _opened.update { it + (id to note) }
            withContext(Dispatchers.IO) { noteFile(id).writeText(body) }
            note
        } catch (e: Exception) {
            if (cached == null) _status.value = statusFor(e)
            cached
        }
    }

    /**
     * Adds [text] to the end of a plain note. Only ever called from an explicit tap; the Mac
     * refuses notes whose checklists or attachments a rewrite could damage.
     */
    suspend fun append(id: String, text: String) {
        post("/v1/notes/" + Uri.encode(id) + "/append", WriteRequest(text = text))
        open(id)
        refresh(force = true)
    }

    /** Creates a note (first line = title) and returns its id, or "" if the Mac hasn't named it yet. */
    suspend fun create(folder: String?, text: String): String {
        val id = json.decodeFromString<WriteResponse>(post("/v1/notes", WriteRequest(folder = folder.orEmpty(), text = text))).id
        refresh(force = true)
        return id
    }

    /** Downloads an attachment into the app's cache so another app can open it. */
    suspend fun download(att: NoteAttachment): File = withContext(Dispatchers.IO) {
        val dir = File(appContext.cacheDir, "open").apply { mkdirs() }
        val safe = att.name.ifEmpty { att.id }.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(80)
        val out = File(dir, "${att.id.take(8)}-$safe")
        if (out.length() > 0) return@withContext out
        val request = Request.Builder().url(attachmentUrl(att.id)).header("Authorization", authHeader()).build()
        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw BridgeException.Unreachable(e)
        }
        response.use {
            if (!it.isSuccessful) throw BridgeException.Server("http_${it.code}", "The Mac couldn't send this file (${it.code})")
            out.outputStream().use { o -> it.body!!.byteStream().copyTo(o) }
        }
        out
    }

    fun forgetCache() {
        libraryFile.delete()
        File(appContext.filesDir, "notes").deleteRecursively()
        File(appContext.cacheDir, "open").deleteRecursively()
        etag = ""
        _opened.value = emptyMap()
    }
}
