package com.eberhard.relay.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/** Bridge address and token, encrypted with a key in the Android Keystore. */
class Prefs(context: Context) {

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        "relay",
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

    /** The newest message the app has seen, so a reconnect can catch up. */
    var lastEventId: Long
        get() = prefs.getLong("last_event_id", 0)
        set(value) = prefs.edit().putLong("last_event_id", value).apply()

    /** Chats hidden in Relay only: GUID → when hidden (Unix ms). Nothing changes on the Mac. */
    var hiddenChats: Map<String, Long>
        get() = prefs.getString("hidden_chats", "").orEmpty().split('\n').mapNotNull { line ->
            val i = line.lastIndexOf('\t')
            if (i <= 0) null else line.substring(i + 1).toLongOrNull()?.let { line.substring(0, i) to it }
        }.toMap()
        set(value) = prefs.edit()
            .putString("hidden_chats", value.entries.joinToString("\n") { "${it.key}\t${it.value}" })
            .apply()

    /** Chats pinned to the top of the list, by GUID. Relay only. */
    var favoriteChats: Set<String>
        get() = prefs.getString("favorite_chats", "").orEmpty().split('\n').filter { it.isNotEmpty() }.toSet()
        set(value) = prefs.edit().putString("favorite_chats", value.joinToString("\n")).apply()

    /** When each chat was last opened in Relay (GUID → Unix ms), for the unread badge. */
    var seenChats: Map<String, Long>
        get() = prefs.getString("seen_chats", "").orEmpty().split('\n').mapNotNull { line ->
            val i = line.lastIndexOf('\t')
            if (i <= 0) null else line.substring(i + 1).toLongOrNull()?.let { line.substring(0, i) to it }
        }.toMap()
        set(value) = prefs.edit()
            .putString("seen_chats", value.entries.joinToString("\n") { "${it.key}\t${it.value}" })
            .apply()

    val isConfigured: Boolean get() = token.isNotEmpty() && baseUrl.isNotEmpty()

    companion object {
        const val DEFAULT_URL = "http://100.90.106.50:8765"
    }
}
