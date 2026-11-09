package com.eberhard.iphotos.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import com.eberhard.iphotos.data.Item
import com.eberhard.iphotos.data.PhotosApplication
import com.eberhard.iphotos.work.Backup
import java.io.File

/**
 * "Send to iCloud" from any app's share sheet. The shared files are copied into the
 * app's own folder (share grants are temporary), queued in the ledger and sent by the
 * same worker as the automatic backup; the copy is deleted once the Mac has it.
 */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            else -> emptyList()
        }
        val app = PhotosApplication.of(this)
        val dir = File(filesDir, "shared").apply { mkdirs() }
        var queued = 0
        val now = System.currentTimeMillis()
        val items = ArrayList<Item>()
        uris.forEachIndexed { i, uri ->
            val name = displayName(uri) ?: "shared-$now-$i.jpg"
            val copy = File(dir, "$now-$i-$name")
            val ok = runCatching {
                contentResolver.openInputStream(uri)?.use { input -> copy.outputStream().use { input.copyTo(it) } } != null
            }.getOrDefault(false)
            if (!ok) return@forEachIndexed
            // Negative, time-based ids keep shared files apart from MediaStore ids.
            items += Item(
                id = -(now + i), uri = Uri.fromFile(copy).toString(), name = name, size = copy.length(),
                dateAdded = now, state = "pending", error = "", updated = 0, source = "share",
            )
            queued++
        }
        app.ledger.addPending(items)
        if (queued > 0) {
            Backup.now(this)
            Toast.makeText(this, if (queued == 1) "Sending to iCloud" else "Sending $queued to iCloud", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Nothing to send", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    private fun displayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
}
