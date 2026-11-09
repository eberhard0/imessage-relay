package com.eberhard.iphotos.data

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore

/** Finds photos and videos on the phone that the ledger hasn't seen yet. */
object Scanner {
    /** New items since [sinceMs] (seconds granularity in MediaStore), camera folder only when [cameraOnly]. */
    fun newItems(context: Context, ledger: Ledger, sinceMs: Long, cameraOnly: Boolean): List<Item> {
        val known = ledger.knownIds()
        val out = ArrayList<Item>()
        val uri = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.DISPLAY_NAME, MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_ADDED, MediaStore.Files.FileColumns.MEDIA_TYPE, MediaStore.Files.FileColumns.RELATIVE_PATH,
        )
        val where = StringBuilder(
            "(${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE} OR " +
                "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})" +
                " AND ${MediaStore.Files.FileColumns.DATE_ADDED} >= ?",
        )
        val args = arrayListOf((sinceMs / 1000).toString())
        if (cameraOnly) {
            where.append(" AND ${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ?")
            args += "DCIM/Camera%"
        }
        context.contentResolver.query(uri, projection, where.toString(), args.toTypedArray(), "${MediaStore.Files.FileColumns.DATE_ADDED} ASC")?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                if (id in known) continue
                val mediaType = c.getInt(4)
                val itemUri = ContentUris.withAppendedId(
                    if (mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    id,
                )
                out += Item(
                    id = id, uri = itemUri.toString(), name = c.getString(1) ?: "photo", size = c.getLong(2),
                    dateAdded = c.getLong(3) * 1000, state = "pending", error = "", updated = 0, source = "media",
                )
            }
        }
        return out
    }
}
