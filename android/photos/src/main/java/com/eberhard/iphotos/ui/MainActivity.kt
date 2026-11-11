package com.eberhard.iphotos.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eberhard.iphotos.data.Item
import com.eberhard.iphotos.data.PhotosApplication
import com.eberhard.iphotos.work.Backup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) { Root() }
        }
    }
}

/** Two tabs: the backup (phone → iCloud) and the library (iCloud → phone, read-only). */
@Composable
private fun Root() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            NavigationBar {
                NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Default.CloudUpload, null) }, label = { Text("Backup") })
                NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.Default.PhotoLibrary, null) }, label = { Text("Library") })
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) { if (tab == 0) PhotosScreen() else LibraryScreen() }
    }
}

private fun mediaPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED, Manifest.permission.POST_NOTIFICATIONS)
    Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.POST_NOTIFICATIONS)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

@Composable
private fun PhotosScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val app = PhotosApplication.of(context)
    val prefs = app.prefs
    var tick by remember { mutableIntStateOf(0) }
    val running by Backup.running.collectAsStateWithLifecycle()
    val progress by Backup.progress.collectAsStateWithLifecycle()
    var counts by remember { mutableStateOf(mapOf<String, Int>()) }
    var recent by remember { mutableStateOf(listOf<Item>()) }
    var bridgeLine by remember { mutableStateOf("Checking the Mac…") }
    var hasMedia by remember { mutableStateOf(hasMediaAccess(context)) }
    var autoBackup by remember { mutableStateOf(prefs.autoBackup) }
    var wifiOnly by remember { mutableStateOf(prefs.wifiOnly) }
    var chargingOnly by remember { mutableStateOf(prefs.chargingOnly) }
    var cameraOnly by remember { mutableStateOf(prefs.cameraOnly) }

    val askPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasMedia = hasMediaAccess(context)
        if (hasMedia) Backup.now(context)
        tick++
    }
    LaunchedEffect(Unit) { if (!hasMedia) askPermissions.launch(mediaPermissions()) }

    // Refresh the numbers while the screen is open, and re-check the Mac now and then.
    LaunchedEffect(tick, running) {
        while (true) {
            counts = withContext(Dispatchers.IO) { app.ledger.counts() }
            recent = withContext(Dispatchers.IO) { app.ledger.recent(30) }
            delay(2000)
        }
    }
    LaunchedEffect(tick) {
        bridgeLine = withContext(Dispatchers.IO) {
            if (!prefs.isConfigured && !app.bridge.borrowFromIMessage()) "No bridge connection yet: open the iMessage app once, then come back."
            else runCatching { app.bridge.health() }.fold(
                { "Mac reachable · bridge ${it.version} · ${it.photos_imported} imported so far" },
                { if (app.bridge.tailscaleUp()) "Mac not answering (bridge down, or asleep)" else "Tailscale is off on this phone; uploads wait until it's back" },
            )
        }
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("iPhotos", style = MaterialTheme.typography.headlineMedium)
        Text("New photos and videos from this phone go to the Mac, which puts them in Photos and iCloud. Nothing is uploaded twice; what can't be sent now waits.", style = MaterialTheme.typography.bodyMedium)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(bridgeLine, style = MaterialTheme.typography.bodyMedium)
                val pending = counts["pending"] ?: 0
                val done = counts["done"] ?: 0
                val failed = counts["failed"] ?: 0
                Text("$done sent · $pending waiting" + if (failed > 0) " · $failed failed" else "", style = MaterialTheme.typography.titleMedium)
                if (running) Text(progress, style = MaterialTheme.typography.bodySmall)
                else if (prefs.lastRunMs > 0) Text("Last run ${time(prefs.lastRunMs)}: ${prefs.lastRunNote}", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { Backup.now(context); tick++ }, enabled = !running) { Text("Sync now") }
                    if (failed > 0) OutlinedButton(onClick = { app.ledger.retryFailed(); Backup.now(context); tick++ }) { Text("Retry failed") }
                }
            }
        }

        if (!hasMedia) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("iPhotos can't see your photos yet.", style = MaterialTheme.typography.titleSmall)
                    Text("Allow access to all photos and videos so the backup can find new ones.", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { askPermissions.launch(mediaPermissions()) }) { Text("Allow") }
                }
            }
        }

        Text("Settings", style = MaterialTheme.typography.titleMedium)
        SwitchRow("Back up new photos automatically", autoBackup) { autoBackup = it; prefs.autoBackup = it; Backup.schedule(context) }
        SwitchRow("Wi-Fi only (off = any connection, mobile data too)", wifiOnly) { wifiOnly = it; prefs.wifiOnly = it; Backup.schedule(context) }
        SwitchRow("Only while charging", chargingOnly) { chargingOnly = it; prefs.chargingOnly = it; Backup.schedule(context) }
        SwitchRow("Camera folder only (off = every photo and video)", cameraOnly) { cameraOnly = it; prefs.cameraOnly = it }
        Text(
            if (prefs.sinceMs == 0L) "Backing up everything on the phone, oldest first."
            else "Backing up what's taken from ${time(prefs.sinceMs)} on. Older photos are left alone.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (prefs.sinceMs != 0L) TextButton(onClick = { prefs.sinceMs = 0L; Backup.now(context); tick++ }) { Text("Include older photos too") }

        Spacer(Modifier.height(4.dp))
        Text("Recent", style = MaterialTheme.typography.titleMedium)
        if (recent.isEmpty()) Text("Nothing yet.", style = MaterialTheme.typography.bodySmall)
        recent.forEach { item ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(item.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    Text(
                        when (item.state) {
                            "done" -> "Sent ${time(item.updated)}" + if (item.error == "duplicate") " (already on the Mac)" else ""
                            "pending" -> "Waiting"
                            "failed" -> "Failed: ${item.error}"
                            else -> item.error.ifEmpty { item.state }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (item.state == "failed") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (item.state == "failed" || item.state == "pending") TextButton(onClick = { app.ledger.remove(item.id); tick++ }) { Text("Skip") }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("Tip: in any app, Share → \"Send to iCloud\" uploads a photo even when automatic backup is off.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun hasMediaAccess(context: android.content.Context): Boolean {
    val granted = { p: String -> ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED }
    return when {
        Build.VERSION.SDK_INT >= 34 -> granted(Manifest.permission.READ_MEDIA_IMAGES) || granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= 33 -> granted(Manifest.permission.READ_MEDIA_IMAGES)
        else -> granted(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}

private fun time(ms: Long): String = SimpleDateFormat("d MMM HH:mm", Locale.US).format(Date(ms))
