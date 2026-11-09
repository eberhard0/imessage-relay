package com.eberhard.iphotos.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** The bridge connection (encrypted) and the backup settings (plain). */
class Prefs(context: Context) {
    private val secure: SharedPreferences = EncryptedSharedPreferences.create(
        context, "bridge", MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
    private val plain: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = secure.getString("base_url", "").orEmpty()
        set(v) = secure.edit().putString("base_url", v).apply()
    var token: String
        get() = secure.getString("token", "").orEmpty()
        set(v) = secure.edit().putString("token", v).apply()
    val isConfigured: Boolean get() = baseUrl.isNotEmpty() && token.isNotEmpty()

    /** Automatic backup of new camera photos and videos. */
    var autoBackup: Boolean
        get() = plain.getBoolean("auto", true)
        set(v) = plain.edit().putBoolean("auto", v).apply()
    /** Upload only on Wi-Fi (unmetered); otherwise any connection, mobile data included. */
    var wifiOnly: Boolean
        get() = plain.getBoolean("wifi_only", false)
        set(v) = plain.edit().putBoolean("wifi_only", v).apply()
    var chargingOnly: Boolean
        get() = plain.getBoolean("charging_only", false)
        set(v) = plain.edit().putBoolean("charging_only", v).apply()
    /** Only DCIM/Camera (the camera app), or every photo and video on the phone. */
    var cameraOnly: Boolean
        get() = plain.getBoolean("camera_only", true)
        set(v) = plain.edit().putBoolean("camera_only", v).apply()
    /** Photos added before this moment are left alone (set at first start; 0 = back up everything). */
    var sinceMs: Long
        get() = plain.getLong("since_ms", -1L)
        set(v) = plain.edit().putLong("since_ms", v).apply()
    var lastRunMs: Long
        get() = plain.getLong("last_run", 0L)
        set(v) = plain.edit().putLong("last_run", v).apply()
    var lastRunNote: String
        get() = plain.getString("last_note", "").orEmpty()
        set(v) = plain.edit().putString("last_note", v).apply()
}
