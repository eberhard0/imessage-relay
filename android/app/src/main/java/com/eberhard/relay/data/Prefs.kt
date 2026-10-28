package com.eberhard.relay.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Bridge address and token, encrypted with a key in the Android Keystore. */
class Prefs(context: Context) {

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "relay",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
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

    val isConfigured: Boolean get() = token.isNotEmpty() && baseUrl.isNotEmpty()

    companion object {
        const val DEFAULT_URL = "http://100.90.106.50:8765"
    }
}
