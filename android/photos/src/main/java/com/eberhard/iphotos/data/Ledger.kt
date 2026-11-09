package com.eberhard.iphotos.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** One photo or video the app knows about, and what happened to it. */
data class Item(
    val id: Long,
    val uri: String,
    val name: String,
    val size: Long,
    val dateAdded: Long,
    val state: String,      // pending | done | failed | skipped
    val error: String,
    val updated: Long,
    val source: String,     // media | share
)

/**
 * The upload ledger: every media item seen, so nothing is sent twice and nothing is
 * forgotten while the Mac is out of reach. Plain SQLite, no ORM.
 */
class Ledger(context: Context) : SQLiteOpenHelper(context.applicationContext, "ledger.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE items (
                id INTEGER PRIMARY KEY,
                uri TEXT NOT NULL,
                name TEXT NOT NULL,
                size INTEGER NOT NULL,
                date_added INTEGER NOT NULL,
                state TEXT NOT NULL,
                error TEXT NOT NULL DEFAULT '',
                updated INTEGER NOT NULL,
                source TEXT NOT NULL DEFAULT 'media',
                sha TEXT NOT NULL DEFAULT ''
            )""",
        )
        db.execSQL("CREATE INDEX items_state ON items(state, date_added)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    /** Adds unseen items as pending; ids are MediaStore ids (media) or negative, time-based (share). */
    fun addPending(items: List<Item>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (it in items) {
                val v = ContentValues().apply {
                    put("id", it.id); put("uri", it.uri); put("name", it.name); put("size", it.size)
                    put("date_added", it.dateAdded); put("state", "pending"); put("error", "")
                    put("updated", System.currentTimeMillis()); put("source", it.source)
                }
                db.insertWithOnConflict("items", null, v, SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun knownIds(): Set<Long> = readableDatabase.rawQuery("SELECT id FROM items", null).use { c ->
        val out = HashSet<Long>(c.count)
        while (c.moveToNext()) out += c.getLong(0)
        out
    }

    fun pending(limit: Int = 500): List<Item> = query("state = 'pending'", "date_added ASC", limit)
    fun recent(limit: Int = 40): List<Item> = query(null, "updated DESC", limit)
    fun failed(limit: Int = 200): List<Item> = query("state = 'failed'", "updated DESC", limit)

    fun counts(): Map<String, Int> = readableDatabase.rawQuery("SELECT state, COUNT(*) FROM items GROUP BY state", null).use { c ->
        val m = HashMap<String, Int>()
        while (c.moveToNext()) m[c.getString(0)] = c.getInt(1)
        m
    }

    fun setState(id: Long, state: String, error: String = "", sha: String? = null) {
        val v = ContentValues().apply {
            put("state", state); put("error", error.take(300)); put("updated", System.currentTimeMillis())
            if (sha != null) put("sha", sha)
        }
        writableDatabase.update("items", v, "id = ?", arrayOf(id.toString()))
    }

    fun retryFailed() {
        writableDatabase.execSQL("UPDATE items SET state = 'pending', error = '' WHERE state = 'failed'")
    }

    fun remove(id: Long) {
        writableDatabase.delete("items", "id = ?", arrayOf(id.toString()))
    }

    private fun query(where: String?, order: String, limit: Int): List<Item> =
        readableDatabase.query("items", null, where, null, null, null, order, limit.toString()).use { c ->
            val out = ArrayList<Item>(c.count)
            while (c.moveToNext()) out += Item(
                id = c.getLong(c.getColumnIndexOrThrow("id")),
                uri = c.getString(c.getColumnIndexOrThrow("uri")),
                name = c.getString(c.getColumnIndexOrThrow("name")),
                size = c.getLong(c.getColumnIndexOrThrow("size")),
                dateAdded = c.getLong(c.getColumnIndexOrThrow("date_added")),
                state = c.getString(c.getColumnIndexOrThrow("state")),
                error = c.getString(c.getColumnIndexOrThrow("error")),
                updated = c.getLong(c.getColumnIndexOrThrow("updated")),
                source = c.getString(c.getColumnIndexOrThrow("source")),
            )
            out
        }
}
