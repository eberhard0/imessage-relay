@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package com.eberhard.relay.ui

import android.content.Intent
import android.provider.Settings
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.eberhard.relay.data.Chat
import com.eberhard.relay.data.Message
import com.eberhard.relay.data.Relay
import com.eberhard.relay.data.Status
import com.eberhard.relay.service.Notifier
import com.eberhard.relay.service.RelayService
import kotlinx.coroutines.launch

private val Blue = Color(0xFF1F6FE0)

@Composable
fun RelayTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = Color(0xFF6EA4FF))
    } else {
        lightColorScheme(primary = Blue)
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun RelayApp(openChat: String?, onChatOpened: () -> Unit) {
    val status by Relay.status.collectAsStateWithLifecycle()
    var showSetup by rememberSaveable { mutableStateOf(!Relay.prefs.isConfigured) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var showHideOld by remember { mutableStateOf(false) }
    var showHidden by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(openChat) {
        if (openChat != null) {
            selected = openChat
            onChatOpened()
        }
    }

    if (showSetup || status == Status.NotConfigured) {
        SetupScreen(onDone = { showSetup = false }, canCancel = Relay.prefs.isConfigured)
        return
    }

    if (showHidden) {
        BackHandler { showHidden = false }
        HiddenChatsScreen(onBack = { showHidden = false })
        return
    }
    if (showHideOld) HideOldDialog(onDismiss = { showHideOld = false })

    val wide = LocalConfiguration.current.screenWidthDp >= 600
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (!wide && selected != null) Relay.chatTitle(selected!!) else "Relay") },
                navigationIcon = {
                    if (!wide && selected != null) {
                        IconButton(onClick = { selected = null }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Hide old chats…") }, onClick = { menuOpen = false; showHideOld = true })
                        DropdownMenuItem(text = { Text("Hidden chats") }, onClick = { menuOpen = false; showHidden = true })
                        DropdownMenuItem(
                            text = { Text("Connection settings") },
                            leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                            onClick = { menuOpen = false; showSetup = true },
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            StatusBanner(status)
            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(0.38f)) { ChatList(selected, onOpen = { selected = it }) }
                    VerticalDivider()
                    Box(Modifier.weight(0.62f)) {
                        val chat = selected
                        if (chat == null) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("Pick a conversation", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } else {
                            Thread(chat, showTitle = true)
                        }
                    }
                }
            } else {
                val chat = selected
                if (chat == null) {
                    ChatList(null, onOpen = { selected = it })
                } else {
                    BackHandler { selected = null }
                    Thread(chat, showTitle = false)
                }
            }
        }
    }
}

@Composable
private fun StatusBanner(status: Status) {
    val context = LocalContext.current
    val (text, action) = when (status) {
        Status.Connected, Status.NotConfigured -> return
        Status.Connecting -> "Connecting to the Mac…" to null
        Status.TailscaleOff -> "Tailscale is off. Turn it on to reach the Mac." to "Open VPN settings"
        Status.MacUnreachable -> "Can't reach the Mac. It may be asleep (lid closed on battery), offline, or the bridge isn't running." to "Retry"
        Status.BadToken -> "The Mac rejected the token. Check it in settings." to null
        is Status.MacProblem -> status.detail to "Retry"
    }
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            when (action) {
                "Retry" -> TextButton(onClick = { RelayService.start(context, reconnect = true) }) { Text(action) }
                "Open VPN settings" -> TextButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text("VPN settings") }
            }
        }
    }
}

@Composable
private fun SetupScreen(onDone: () -> Unit, canCancel: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(Relay.prefs.baseUrl) }
    var token by remember { mutableStateOf(Relay.prefs.token) }
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }

    Scaffold(topBar = { TopAppBar(title = { Text("Connect to the Mac") }) }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Relay gets your iMessages from imsg-bridge on your Mac, over Tailscale. On the Mac, " +
                    "`imsg-bridge token` prints the token.",
                style = MaterialTheme.typography.bodyMedium,
            )
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
                        Relay.prefs.baseUrl = url
                        Relay.prefs.token = token
                        checking = true
                        result = null
                        scope.launch {
                            result = try {
                                val h = Relay.refreshHealth()
                                buildString {
                                    append("Connected to imsg-bridge ${h.version}.\n")
                                    append(if (h.dbReadable) "✓ Reads Messages\n" else "✗ Can't read Messages: give imsg-bridge Full Disk Access on the Mac.\n")
                                    append(
                                        when (h.automation) {
                                            "ok" -> "✓ Can send through Messages\n"
                                            "denied" -> "✗ Can't send: allow it under Privacy & Security › Automation on the Mac.\n"
                                            "pending" -> "… Waiting for you to approve the Automation prompt on the Mac.\n"
                                            else -> "? Sending not checked yet; the first send will tell.\n"
                                        },
                                    )
                                    append("Contacts: ${h.contacts}")
                                }.also {
                                    RelayService.start(context, reconnect = true)
                                }
                            } catch (e: Exception) {
                                when (Relay.statusFor(e)) {
                                    Status.TailscaleOff -> "Tailscale is off on this phone."
                                    Status.BadToken -> "The Mac rejected this token."
                                    Status.MacUnreachable -> "Can't reach ${Relay.prefs.baseUrl}. Is the Mac awake and the bridge running?"
                                    else -> e.message ?: "Failed"
                                }
                            } finally {
                                checking = false
                            }
                        }
                    },
                ) { Text("Test and save") }
                if (canCancel || result?.startsWith("Connected") == true) {
                    OutlinedButton(onClick = onDone) { Text("Done") }
                }
            }
            if (checking) CircularProgressIndicator(Modifier.size(24.dp))
            result?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            HorizontalDivider()
            Text(
                "For messages to arrive reliably, set Tailscale as the always-on VPN: " +
                    "Settings › Connections › More connection settings › VPN › Tailscale › Always-on VPN.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }) { Text("Open VPN settings") }
        }
    }
}

private fun timeLabel(ms: Long): String =
    DateUtils.getRelativeTimeSpanString(ms, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()

@Composable
private fun ChatList(selected: String?, onOpen: (String) -> Unit) {
    val all by Relay.chats.collectAsStateWithLifecycle()
    val hidden by Relay.hidden.collectAsStateWithLifecycle()
    val favorites by Relay.favorites.collectAsStateWithLifecycle()
    val visible = all.filterNot { Relay.isHidden(it, hidden) }
    val favChats = visible.filter { it.guid in favorites }
    val chats = visible.filter { it.guid !in favorites }
    var actionsFor by remember { mutableStateOf<Chat?>(null) }
    var confirmHide by remember { mutableStateOf<Chat?>(null) }
    val context = LocalContext.current
    val status by Relay.status.collectAsStateWithLifecycle()
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(status) {
        if (status == Status.Connected) {
            error = runCatching { Relay.refreshChats() }.exceptionOrNull()?.message
        }
    }

    if (visible.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(error ?: "No conversations yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    actionsFor?.let { chat ->
        val isFavorite = chat.guid in favorites
        AlertDialog(
            onDismissRequest = { actionsFor = null },
            title = { Text(chat.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    TextButton(onClick = {
                        Relay.setFavorite(chat.guid, !isFavorite)
                        actionsFor = null
                    }) { Text(if (isFavorite) "Remove from favorites" else "Add to favorites") }
                    TextButton(onClick = {
                        actionsFor = null
                        confirmHide = chat
                    }) { Text("Hide chat") }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { actionsFor = null }) { Text("Cancel") } },
        )
    }
    confirmHide?.let { chat ->
        AlertDialog(
            onDismissRequest = { confirmHide = null },
            title = { Text("Hide ${chat.title}?") },
            text = {
                Text(
                    "It disappears from Relay only. Nothing is deleted on your Mac, iPhone or iCloud. " +
                        "It comes back if a new message arrives, or from Menu › Hidden chats.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    Relay.hide(listOf(chat.guid))
                    Notifier.clear(context, chat.guid)
                    confirmHide = null
                }) { Text("Hide") }
            },
            dismissButton = { TextButton(onClick = { confirmHide = null }) { Text("Cancel") } },
        )
    }
    LazyColumn(Modifier.fillMaxSize()) {
        if (favChats.isNotEmpty()) {
            item(key = "h-fav") { SectionLabel("Favorites") }
            items(favChats, key = { "f-" + it.guid }) { chat ->
                ChatRow(chat, chat.guid == selected, favorite = true, onLongClick = { actionsFor = chat }) { onOpen(chat.guid) }
                HorizontalDivider()
            }
            if (chats.isNotEmpty()) item(key = "h-all") { SectionLabel("All chats") }
        }
        items(chats, key = { it.guid }) { chat ->
            ChatRow(chat, chat.guid == selected, favorite = false, onLongClick = { actionsFor = chat }) { onOpen(chat.guid) }
            HorizontalDivider()
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun ChatRow(chat: Chat, selected: Boolean, favorite: Boolean, onLongClick: () -> Unit, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).background(Blue.copy(alpha = 0.15f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(chat.title.take(1).uppercase(), color = Blue, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (favorite) {
                    Icon(Icons.Default.Star, contentDescription = "Favorite", tint = Color(0xFFF2B01E), modifier = Modifier.size(16.dp).padding(end = 2.dp))
                }
                Text(
                    chat.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (chat.unread > 0) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                chat.lastMessage?.let {
                    Text(timeLabel(it.date), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    chat.lastMessage?.let { (if (it.fromMe) "You: " else "") + it.preview }.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (chat.unread > 0) {
                    Box(
                        Modifier.padding(start = 6.dp).background(Blue, CircleShape).padding(horizontal = 7.dp, vertical = 1.dp),
                    ) {
                        Text("${chat.unread}", color = Color.White, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun Thread(chatGuid: String, showTitle: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val threads by Relay.threads.collectAsStateWithLifecycle()
    val chats by Relay.chats.collectAsStateWithLifecycle()
    val messages = threads[chatGuid].orEmpty()
    val chat = chats.firstOrNull { it.guid == chatGuid }
    var loadError by remember(chatGuid) { mutableStateOf<String?>(null) }
    var hasOlder by remember(chatGuid) { mutableStateOf(true) }
    var draft by rememberSaveable(chatGuid) { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var sendError by remember(chatGuid) { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    // While the thread is on screen its notification is cleared and none are posted.
    DisposableEffect(chatGuid) {
        Relay.visibleChat = chatGuid
        Notifier.clear(context, chatGuid)
        Relay.markSeen(chatGuid)
        onDispose { if (Relay.visibleChat == chatGuid) Relay.visibleChat = null }
    }
    LaunchedEffect(chatGuid) {
        loadError = runCatching { Relay.loadThread(chatGuid) }.exceptionOrNull()?.message
    }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.scrollToItem(0)
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        if (showTitle) {
            Text(
                chat?.title ?: Relay.chatTitle(chatGuid),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
            HorizontalDivider()
        }
        // Newest at the bottom: the list is reversed so it opens scrolled to the end.
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            reverseLayout = true,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(messages.asReversed(), key = { it.id }) { m -> Bubble(m, chat?.isGroup == true || m.isGroup) }
            item {
                when {
                    loadError != null -> Text(loadError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    hasOlder && messages.isNotEmpty() -> TextButton(onClick = {
                        scope.launch { hasOlder = runCatching { Relay.loadOlder(chatGuid) }.getOrDefault(true) }
                    }) { Text("Load earlier messages") }
                }
            }
        }
        sendError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
        }
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text("iMessage") },
                modifier = Modifier.weight(1f).heightIn(max = 160.dp),
            )
            IconButton(
                enabled = draft.isNotBlank() && !sending,
                onClick = {
                    val text = draft.trim()
                    sending = true
                    sendError = null
                    scope.launch {
                        try {
                            val r = Relay.send(chatGuid, text)
                            draft = ""
                            if (!r.confirmed) sendError = "Handed to Messages on the Mac; not confirmed yet."
                        } catch (e: Exception) {
                            sendError = "Not sent: " + when (Relay.statusFor(e)) {
                                Status.TailscaleOff -> "Tailscale is off."
                                Status.MacUnreachable -> "can't reach the Mac."
                                Status.BadToken -> "the Mac rejected the token."
                                else -> e.message
                            }
                        } finally {
                            sending = false
                        }
                    }
                },
            ) {
                if (sending) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = if (draft.isNotBlank()) Blue else Color.Gray)
            }
        }
    }
}

@Composable
private fun Bubble(m: Message, group: Boolean) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (m.fromMe) Alignment.End else Alignment.Start,
    ) {
        if (group && !m.fromMe) {
            Text(m.senderLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 10.dp))
        }
        Surface(
            color = if (m.fromMe) Blue else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (m.fromMe) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                m.attachments.forEach { a ->
                    if (a.isImage) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(Relay.api.attachmentUrl(a.id))
                                .addHeader("Authorization", Relay.api.authHeader)
                                .crossfade(true)
                                .build(),
                            contentDescription = a.name,
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier.widthIn(max = 296.dp).heightIn(max = 360.dp).padding(bottom = 4.dp),
                        )
                    } else {
                        Text("📎 ${a.name.ifEmpty { "Attachment" }}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (m.text.isNotEmpty()) Text(m.text, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Text(
            timeLabel(m.date) + if (m.fromMe && m.error != 0) " · not delivered" else "",
            style = MaterialTheme.typography.labelSmall,
            color = if (m.error != 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp),
        )
    }
}

private val AGE_CHOICES = listOf(1 to "1 month", 3 to "3 months", 6 to "6 months", 12 to "1 year")

/** Hides, in Relay only, every chat whose last message is older than the chosen age. */
@Composable
private fun HideOldDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val chats by Relay.chats.collectAsStateWithLifecycle()
    val hidden by Relay.hidden.collectAsStateWithLifecycle()
    var months by remember { mutableStateOf(3) }
    val cutoff = System.currentTimeMillis() - months * 30L * DateUtils.DAY_IN_MILLIS
    val favorites by Relay.favorites.collectAsStateWithLifecycle()
    val old = chats.filter {
        it.guid !in favorites && !Relay.isHidden(it, hidden) && (it.lastMessage?.date ?: 0) < cutoff
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Hide old chats") },
        text = {
            Column {
                Text("Hide chats with no messages in the last:")
                AGE_CHOICES.forEach { (m, label) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { months = m },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = months == m, onClick = { months = m })
                        Text(label)
                    }
                }
                Spacer(Modifier.size(8.dp))
                Text(
                    "${old.size} chat${if (old.size == 1) "" else "s"} will be hidden in Relay (favorites are kept). " +
                        "Nothing is deleted on your Mac, iPhone or iCloud.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = old.isNotEmpty(), onClick = {
                Relay.hide(old.map { it.guid })
                old.forEach { Notifier.clear(context, it.guid) }
                onDismiss()
            }) { Text("Hide ${old.size}") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun HiddenChatsScreen(onBack: () -> Unit) {
    val chats by Relay.chats.collectAsStateWithLifecycle()
    val hidden by Relay.hidden.collectAsStateWithLifecycle()
    val list = chats.filter { Relay.isHidden(it, hidden) }
    // Hidden chats no longer in the bridge's recent list still count, by GUID.
    val unknown = hidden.keys - chats.map { it.guid }.toSet()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Hidden chats") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (list.isEmpty() && unknown.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No hidden chats", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Scaffold
        }
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            items(list, key = { it.guid }) { chat ->
                HiddenRow(chat.title, chat.lastMessage?.let { "Last message " + timeLabel(it.date) }) { Relay.unhide(chat.guid) }
                HorizontalDivider()
            }
            if (unknown.isNotEmpty()) {
                item {
                    HiddenRow(
                        "${unknown.size} older chat${if (unknown.size == 1) "" else "s"}",
                        "Not in the recent list any more",
                    ) { unknown.forEach(Relay::unhide) }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun HiddenRow(title: String, subtitle: String?, onUnhide: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        TextButton(onClick = onUnhide) { Text("Unhide") }
    }
}
