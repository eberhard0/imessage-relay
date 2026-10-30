package com.eberhard.relay.data

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

/**
 * Hands the bridge address and token to iNotes, so it needs no setup of its own. Guarded by the
 * signature permission BRIDGE_CONNECTION: only apps signed with this app's key may call it.
 */
class ConnectionProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != "connection") return null
        val ctx = context ?: return null
        // Android doesn't apply the provider's permission to call(), so check it here.
        if (ctx.checkCallingPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("needs $PERMISSION")
        }
        val prefs = Prefs(ctx)
        if (!prefs.isConfigured) return Bundle()
        return Bundle().apply {
            putString("base_url", prefs.baseUrl)
            putString("token", prefs.token)
        }
    }

    companion object {
        const val PERMISSION = "com.eberhard.permission.BRIDGE_CONNECTION"
    }

    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, s: String?, a: Array<out String>?): Int = 0
}
