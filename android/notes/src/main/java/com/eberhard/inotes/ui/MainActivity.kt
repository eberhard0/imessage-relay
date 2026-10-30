package com.eberhard.inotes.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.eberhard.inotes.data.Notes
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Notes.init(this)
        // First start: take the connection the iMessage app already has.
        if (!Notes.prefs.isConfigured) Notes.borrowFromIMessage()
        setContent { NotesTheme { NotesApp() } }
    }

    override fun onResume() {
        super.onResume()
        // Refresh on open; cheap when nothing changed on the Mac.
        lifecycleScope.launch { Notes.refresh() }
    }
}
