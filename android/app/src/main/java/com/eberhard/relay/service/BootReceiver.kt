package com.eberhard.relay.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.eberhard.relay.data.Relay

/** Reopens the link to the Mac after a reboot or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Relay.init(context)
        if (Relay.prefs.isConfigured) RelayService.start(context)
    }
}
