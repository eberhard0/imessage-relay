package com.eberhard.iphotos.data

import android.app.Application
import com.eberhard.iphotos.work.Backup

class PhotosApplication : Application() {
    lateinit var prefs: Prefs
    lateinit var ledger: Ledger
    lateinit var bridge: Bridge

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        ledger = Ledger(this)
        bridge = Bridge(this, prefs)
        if (!prefs.isConfigured) bridge.borrowFromIMessage()
        // First start: only what is taken from now on, unless the user asks for everything.
        if (prefs.sinceMs < 0) prefs.sinceMs = System.currentTimeMillis()
        Backup.schedule(this)
    }

    companion object {
        fun of(context: android.content.Context): PhotosApplication = context.applicationContext as PhotosApplication
    }
}
