@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.eberhard.inotes.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.format.DateUtils
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.eberhard.inotes.data.Block
import com.eberhard.inotes.data.Folder
import com.eberhard.inotes.data.Note
import com.eberhard.inotes.data.NoteAttachment
import com.eberhard.inotes.data.NoteSummary
import com.eberhard.inotes.data.Notes
import com.eberhard.inotes.data.Span
import com.eberhard.inotes.data.Status
import kotlinx.coroutines.launch

private val Yellow = Color(0xFFE0A800)

@Composable
fun NotesTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) darkColorScheme(primary = Color(0xFFF5C518)) else lightColorScheme(primary = Yellow)
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun NotesApp() {
    var showSetup by rememberSaveable { mutableStateOf(!Notes.prefs.isConfigured) }
    var folder by rememberSaveable { mutableStateOf<String?>(null) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val folders by Notes.folders.collectAsStateWithLifecycle()
    val status by Notes.status.collectAsStateWithLifecycle()

    if (showSetup) {
        SetupScreen(onDone = { showSetup = false }, canCancel = Notes.prefs.isConfigured)
        return
    }

    val wide = LocalConfiguration.current.screenWidthDp >= 600
    val noteOpen = !wide && selected != null
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (noteOpen) Text("")
                    else if (searching) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text("Search titles and text") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else FolderPicker(folders, folder, onPick = { folder = it; selected = null })
                },
                navigationIcon = {
                    if (noteOpen) {
                        IconButton(onClick = { selected = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    }
                },
                actions = {
                    if (!noteOpen) {
                        IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                            Icon(if (searching) Icons.Default.Close else Icons.Default.Search, if (searching) "Close search" else "Search")
                        }
                    }
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "Menu") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Connection settings") }, onClick = { menuOpen = false; showSetup = true })
                        DropdownMenuItem(text = { Text("Clear offline copies") }, onClick = { menuOpen = false; Notes.forgetCache() })
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            StatusBanner(status)
            val list = @Composable { NoteList(folder, query, selected, onOpen = { selected = it }) }
            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(0.38f)) { list() }
                    VerticalDivider()
                    Box(Modifier.weight(0.62f)) {
                        val id = selected
                        if (id == null) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("Pick a note", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } else NoteView(id)
                    }
                }
            } else {
                val id = selected
                if (id == null) list() else {
                    BackHandler { selected = null }
                    NoteView(id)
                }
            }
        }
    }
}

@Composable
private fun FolderPicker(folders: List<Folder>, current: String?, onPick: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val name = folders.firstOrNull { it.id == current }?.name ?: "All notes"
    Box {
        Row(Modifier.clickable { open = true }, verticalAlignment = Alignment.CenterVertically) {
            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Default.ArrowDropDown, contentDescription = "Folders")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("All notes") }, onClick = { open = false; onPick(null) })
            HorizontalDivider()
            for ((f, depth) in ordered(folders)) {
                DropdownMenuItem(
                    text = {
                        Row(Modifier.padding(start = (depth * 16).dp)) {
                            Text(f.name.ifEmpty { "Untitled folder" }, modifier = Modifier.weight(1f, fill = false))
                            Spacer(Modifier.width(12.dp))
                            Text("${f.count}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    onClick = { open = false; onPick(f.id) },
                )
            }
        }
    }
}

/** Folders in tree order with their depth, so nested folders sit under their parent. */
private fun ordered(folders: List<Folder>): List<Pair<Folder, Int>> {
    val ids = folders.map { it.id }.toSet()
    val children = folders.groupBy { if (it.parent in ids) it.parent else "" }
    val out = mutableListOf<Pair<Folder, Int>>()
    fun walk(parent: String, depth: Int) {
        for (f in children[parent].orEmpty()) {
            out += f to depth
            if (depth < 8) walk(f.id, depth + 1)
        }
    }
    walk("", 0)
    return out
}

@Composable
private fun StatusBanner(status: Status) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val text = when (status) {
        Status.Idle -> return
        Status.Loading -> null
        Status.Offline -> "Can't reach the Mac. It may be asleep or offline. Showing the notes saved on this phone."
        Status.TailscaleOff -> "Tailscale is off. Turn it on to reach the Mac."
        Status.BadToken -> "The Mac rejected the token. Check Connection settings."
        is Status.Problem -> status.detail
    }
    if (text == null) {
        Box(Modifier.fillMaxWidth().padding(4.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        }
        return
    }
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            if (status == Status.TailscaleOff) {
                TextButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text("VPN settings") }
            } else {
                TextButton(onClick = { scope.launch { Notes.refresh(force = true) } }) { Text("Retry") }
            }
        }
    }
}

private fun dateLabel(ms: Long): String =
    if (ms == 0L) "" else DateUtils.getRelativeTimeSpanString(ms, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()

@Composable
private fun NoteList(folder: String?, query: String, selected: String?, onOpen: (String) -> Unit) {
    val all by Notes.notes.collectAsStateWithLifecycle()
    val status by Notes.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val q = query.trim()
    val shown = all.asSequence()
        .filter { folder == null || it.folder == folder }
        .filter { q.isEmpty() || it.title.contains(q, true) || (!it.locked && it.snippet.contains(q, true)) }
        .sortedWith(compareByDescending<NoteSummary> { it.pinned }.thenByDescending { it.modified })
        .toList()

    PullToRefreshBox(
        isRefreshing = status == Status.Loading,
        onRefresh = { scope.launch { Notes.refresh(force = true) } },
        modifier = Modifier.fillMaxSize(),
    ) {
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
                Text(
                    when {
                        all.isEmpty() && status == Status.Loading -> "Loading notes from the Mac…"
                        all.isEmpty() -> "No notes yet. Pull down to load them from the Mac."
                        q.isNotEmpty() -> "No notes match \"$q\"."
                        else -> "This folder is empty."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(32.dp),
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(shown, key = { it.id }) { n ->
                    Column(
                        Modifier.fillMaxWidth()
                            .background(if (n.id == selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                            .clickable { onOpen(n.id) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (n.pinned) Icon(Icons.Default.PushPin, "Pinned", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                            if (n.locked) Icon(Icons.Default.Lock, "Locked", Modifier.size(14.dp))
                            if (n.pinned || n.locked) Spacer(Modifier.width(4.dp))
                            Text(n.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        }
                        Row {
                            Text(dateLabel(n.modified), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (n.locked) "Locked" else n.snippet.ifEmpty { "No additional text" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (n.hasAttachments && !n.locked) Icon(Icons.Default.AttachFile, "Has attachments", Modifier.size(14.dp))
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun NoteView(id: String) {
    val opened by Notes.opened.collectAsStateWithLifecycle()
    val summary = Notes.notes.collectAsStateWithLifecycle().value.firstOrNull { it.id == id }
    var loading by remember(id) { mutableStateOf(true) }
    LaunchedEffect(id) {
        loading = true
        Notes.open(id)
        loading = false
    }
    val note = opened[id]
    Box(Modifier.fillMaxSize()) {
        when {
            note == null && loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            note == null -> Text(
                "This note isn't saved on the phone yet, and the Mac can't be reached.",
                Modifier.align(Alignment.Center).padding(32.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            note.locked || summary?.locked == true -> Column(
                Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Default.Lock, null, Modifier.size(40.dp))
                Spacer(Modifier.size(12.dp))
                Text(note.title, style = MaterialTheme.typography.titleMedium)
                Text("This note is locked. Open it on your iPhone or Mac.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> NoteBody(note)
        }
    }
}

@Composable
private fun NoteBody(note: Note) {
    val numbers = remember(note) { listNumbers(note.blocks) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp)) {
        item {
            Text(
                listOfNotNull(dateLabel(note.modified).takeIf { it.isNotEmpty() }?.let { "Edited $it" }).joinToString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
        }
        items(note.blocks.size) { i -> BlockView(note.blocks[i], numbers[i]) }
    }
}

/** Numbers for numbered-list paragraphs, counting per indent level. */
private fun listNumbers(blocks: List<Block>): List<Int> {
    val counters = IntArray(16)
    return blocks.map { b ->
        if (b.type == "number") {
            val level = b.indent.coerceIn(0, 15)
            counters[level]++
            for (l in level + 1 until 16) counters[l] = 0
            counters[level]
        } else {
            if (b.type != "attachment" && !(b.spans.isEmpty() && b.type == "text")) counters.fill(0)
            0
        }
    }
}

private fun annotated(spans: List<Span>, linkColor: Color): AnnotatedString = buildAnnotatedString {
    for (s in spans) {
        val style = SpanStyle(
            fontWeight = if (s.bold) FontWeight.Bold else null,
            fontStyle = if (s.italic) FontStyle.Italic else null,
            textDecoration = when {
                s.underline && s.strike -> TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
                s.underline -> TextDecoration.Underline
                s.strike -> TextDecoration.LineThrough
                else -> null
            },
        )
        val url = s.link.takeIf { it.startsWith("http://") || it.startsWith("https://") || it.startsWith("mailto:") || it.startsWith("tel:") }
        if (url != null) {
            withLink(LinkAnnotation.Url(url)) {
                withStyle(style.merge(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))) { append(s.text) }
            }
        } else withStyle(style) { append(s.text) }
    }
}

@Composable
private fun BlockView(b: Block, number: Int) {
    val type = MaterialTheme.typography
    val indent = (b.indent * 20).dp
    if (b.type == "attachment") {
        b.attachment?.let { AttachmentView(it) }
        return
    }
    val text = annotated(b.spans, MaterialTheme.colorScheme.primary)
    val style = when (b.type) {
        "title" -> type.headlineSmall.copy(fontWeight = FontWeight.Bold)
        "heading" -> type.titleLarge.copy(fontWeight = FontWeight.SemiBold)
        "subheading" -> type.titleMedium.copy(fontWeight = FontWeight.SemiBold)
        "mono" -> type.bodyMedium.copy(fontFamily = FontFamily.Monospace)
        else -> type.bodyLarge
    }
    val mod = Modifier.fillMaxWidth().padding(start = indent, top = if (b.type in setOf("title", "heading", "subheading")) 6.dp else 1.dp, bottom = 1.dp)
    val quoteMod = if (b.quote) Modifier.background(MaterialTheme.colorScheme.surfaceVariant).padding(start = 8.dp) else Modifier
    val marker: String? = when (b.type) {
        "bullet" -> "•"
        "dash" -> "–"
        "number" -> "$number."
        else -> null
    }
    Row(mod.then(quoteMod), verticalAlignment = Alignment.Top) {
        when {
            b.type == "check" -> {
                Icon(
                    if (b.done) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                    contentDescription = if (b.done) "Done" else "Not done",
                    tint = if (b.done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp).padding(top = 2.dp),
                )
                Spacer(Modifier.width(8.dp))
            }
            marker != null -> Text(marker, style = style, modifier = Modifier.widthIn(min = 22.dp))
        }
        val shown = if (b.type == "check" && b.done) style.copy(color = MaterialTheme.colorScheme.onSurfaceVariant) else style
        Text(text, style = shown, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun AttachmentView(att: NoteAttachment) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun openExternally() {
        if (att.kind == "link") {
            if (att.url.startsWith("http")) context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(att.url)))
            return
        }
        busy = true
        error = null
        scope.launch {
            try {
                val file = Notes.download(att)
                val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
                val ext = file.extension.lowercase()
                val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                    ?: if (att.kind == "pdf") "application/pdf" else if (att.kind == "image") "image/*" else "*/*"
                val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                try {
                    context.startActivity(intent)
                } catch (e: ActivityNotFoundException) {
                    error = "No app on this phone can open this file."
                }
            } catch (e: Exception) {
                error = e.message ?: "Couldn't get the file from the Mac."
            } finally {
                busy = false
            }
        }
    }

    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        if (att.kind == "image") {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(Notes.attachmentUrl(att.id))
                    .addHeader("Authorization", Notes.authHeader())
                    .crossfade(true)
                    .build(),
                contentDescription = att.name.ifEmpty { "Image" },
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { openExternally() },
            )
        } else {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth().clickable(enabled = att.kind in setOf("pdf", "file", "link")) { openExternally() },
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.AttachFile, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(att.name.ifEmpty { att.url.ifEmpty { att.label } }, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            when (att.kind) {
                                "pdf" -> "PDF · tap to open"
                                "file" -> "File · tap to open"
                                "link" -> "Link · tap to open"
                                else -> att.label
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun SetupScreen(onDone: () -> Unit, canCancel: Boolean) {
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(Notes.prefs.baseUrl) }
    var token by remember { mutableStateOf(Notes.prefs.token) }
    var result by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }

    fun test() {
        checking = true
        result = null
        scope.launch {
            Notes.refresh(force = true)
            val s = Notes.status.value
            result = when (s) {
                Status.Idle -> "Connected. ${Notes.notes.value.size} notes loaded."
                Status.TailscaleOff -> "Tailscale is off on this phone."
                Status.BadToken -> "The Mac rejected this token."
                Status.Offline -> "Can't reach ${Notes.prefs.baseUrl}. Is the Mac awake and the bridge running?"
                is Status.Problem -> s.detail
                Status.Loading -> null
            }
            checking = false
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Connect to the Mac") }) }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "iNotes shows your Apple Notes from the Mac, read-only, through the same imsg-bridge as the iMessage app. " +
                    "If the iMessage app is set up, one tap copies its connection.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = {
                if (Notes.borrowFromIMessage()) {
                    url = Notes.prefs.baseUrl
                    token = Notes.prefs.token
                    test()
                } else {
                    result = "The iMessage app isn't installed or isn't connected yet. Enter the address and token below."
                }
            }) { Text("Use the iMessage app's connection") }
            HorizontalDivider()
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Bridge address") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("Token") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !checking && url.isNotBlank() && token.isNotBlank(),
                    onClick = {
                        Notes.prefs.baseUrl = url
                        Notes.prefs.token = token
                        test()
                    },
                ) { Text("Test and save") }
                if (canCancel || result?.startsWith("Connected") == true) {
                    OutlinedButton(onClick = onDone) { Text("Done") }
                }
            }
            if (checking) CircularProgressIndicator(Modifier.size(24.dp))
            result?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}
