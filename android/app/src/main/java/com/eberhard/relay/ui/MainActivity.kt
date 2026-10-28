package com.eberhard.relay.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import com.eberhard.relay.data.Relay
import com.eberhard.relay.service.Notifier
import com.eberhard.relay.service.RelayService

class MainActivity : ComponentActivity() {

    /** Chat to open, from a tapped notification. */
    private val openChat = mutableStateOf<String?>(null)

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Relay.init(this)
        Notifier.createChannels(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Relay.prefs.isConfigured) RelayService.start(this)
        openChat.value = intent.getStringExtra(Notifier.EXTRA_CHAT)

        setContent {
            RelayTheme {
                RelayApp(openChat = openChat.value, onChatOpened = { openChat.value = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(Notifier.EXTRA_CHAT)?.let { openChat.value = it }
    }

    override fun onResume() {
        super.onResume()
        // Coming back is a good moment to retry a dropped link straight away.
        if (Relay.prefs.isConfigured) RelayService.start(this, reconnect = true)
    }
}
