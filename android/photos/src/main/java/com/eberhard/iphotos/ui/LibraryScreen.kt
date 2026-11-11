package com.eberhard.iphotos.ui

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.eberhard.iphotos.data.Album
import com.eberhard.iphotos.data.Bridge
import com.eberhard.iphotos.data.BridgeException
import com.eberhard.iphotos.data.MediaItem
import com.eberhard.iphotos.data.PhotosApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Browse the Mac's Photos library: albums → grid → full-screen viewer with Save. */
@Composable
fun LibraryScreen() {
    val context = LocalContext.current
    val bridge = PhotosApplication.of(context).bridge
    // Coil fetches thumbnails straight from the bridge; the token rides along as a header.
    val loader = remember {
        ImageLoader.Builder(context)
            .okHttpClient { bridge.http.newBuilder().addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("Authorization", bridge.authHeader).build()) }.build() }
            .crossfade(true)
            .build()
    }
    var album by remember { mutableStateOf<Album?>(null) }
    var viewing by remember { mutableStateOf<Int?>(null) } // index into the open album's items
    var items by remember { mutableStateOf(listOf<MediaItem>()) }

    when {
        album == null -> AlbumList(bridge, loader) { album = it; items = emptyList(); viewing = null }
        viewing != null -> Viewer(bridge, loader, items, viewing!!, onClose = { viewing = null })
        else -> AlbumGrid(bridge, loader, album!!, items, onItems = { items = it }, onOpen = { viewing = it }, onBack = { album = null })
    }
}

@Composable
private fun AlbumList(bridge: Bridge, loader: ImageLoader, onOpen: (Album) -> Unit) {
    var albums by remember { mutableStateOf<List<Album>?>(null) }
    var error by remember { mutableStateOf("") }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) {
        error = ""
        albums = null
        albums = withContext(Dispatchers.IO) {
            try { bridge.albums() } catch (e: BridgeException) { error = describe(e); emptyList() } catch (e: Exception) { error = e.message ?: "failed"; emptyList() }
        }
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("iCloud Photos", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = { reload++ }) { Icon(Icons.Default.Refresh, "Reload") }
        }
        Text("The Mac's Photos library, which is your iCloud Photos. Tap an album, then a photo; Save puts a copy in this phone's gallery.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp))
        when {
            albums == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            error.isNotBlank() -> Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
            else -> {
                val list = albums!!
                val sections = listOf("Library" to list.filter { it.kind == "smart" }, "Albums" to list.filter { it.kind == "album" }, "Shared Albums" to list.filter { it.kind == "shared" })
                LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    for ((title, group) in sections) {
                        item(key = "h-$title") { Text(title, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 6.dp)) }
                        if (group.isEmpty()) item(key = "e-$title") {
                            Text(
                                if (title == "Shared Albums") "None synced to the Mac. On the Mac: Photos → Settings → iCloud → tick Shared Albums; they appear here once downloaded." else "None",
                                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp),
                            )
                        }
                        items(group, key = { it.id }) { a ->
                            Row(Modifier.fillMaxWidth().clickable { onOpen(a) }.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(Color.DarkGray)) {
                                    if (a.cover.isNotBlank()) Thumb(bridge, loader, a.cover, Modifier.fillMaxSize())
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(a.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        listOf(a.path, if (a.owner.isNotBlank()) "by ${a.owner}" else "", "${a.count} items").filter { it.isNotBlank() }.joinToString(" · "),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Thumb(bridge: Bridge, loader: ImageLoader, uuid: String, modifier: Modifier) {
    val context = LocalContext.current
    AsyncImage(
        model = ImageRequest.Builder(context).data(bridge.mediaUrl(uuid, "thumb")).build(),
        imageLoader = loader, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier,
    )
}

@Composable
private fun AlbumGrid(bridge: Bridge, loader: ImageLoader, album: Album, items: List<MediaItem>, onItems: (List<MediaItem>) -> Unit, onOpen: (Int) -> Unit, onBack: () -> Unit) {
    var total by remember(album.id) { mutableIntStateOf(-1) }
    var loading by remember(album.id) { mutableStateOf(false) }
    var error by remember(album.id) { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val grid = rememberLazyGridState()
    BackHandler(onBack = onBack)

    fun loadMore() {
        if (loading || (total >= 0 && items.size >= total)) return
        loading = true
        scope.launch {
            val reply = withContext(Dispatchers.IO) {
                try { bridge.items(album.id, items.size) } catch (e: BridgeException) { error = describe(e); null } catch (e: Exception) { error = e.message ?: "failed"; null }
            }
            if (reply != null) { total = reply.total; onItems(items + reply.items) }
            loading = false
        }
    }
    LaunchedEffect(album.id) { if (items.isEmpty()) loadMore() }
    LaunchedEffect(grid) {
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.distinctUntilChanged().collect { last ->
            if (last >= items.size - 40) loadMore()
        }
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Column(Modifier.weight(1f)) {
                Text(album.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (total < 0) "loading…" else "${items.size} of $total", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
            if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
        LazyVerticalGrid(columns = GridCells.Adaptive(110.dp), state = grid, horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.fillMaxSize()) {
            items(items.size, key = { items[it].uuid }) { i ->
                val it = items[i]
                Box(Modifier.aspectRatio(1f).background(Color.DarkGray).clickable { onOpen(i) }) {
                    Thumb(bridge, loader, it.uuid, Modifier.fillMaxSize())
                    if (it.video) Icon(Icons.Default.PlayCircle, null, tint = Color.White, modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).size(20.dp))
                }
            }
        }
    }
}

@Composable
private fun Viewer(bridge: Bridge, loader: ImageLoader, items: List<MediaItem>, start: Int, onClose: () -> Unit) {
    val context = LocalContext.current
    val pager = rememberPagerState(initialPage = start) { items.size }
    BackHandler(onBack = onClose)
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), beyondViewportPageCount = 1) { page ->
            val it = items[page]
            if (it.video) {
                if (pager.currentPage == page) VideoPlayer(bridge.mediaUrl(it.uuid, "original"), bridge.authHeader)
                else Thumb(bridge, loader, it.uuid, Modifier.fillMaxSize())
            } else {
                AsyncImage(
                    model = ImageRequest.Builder(context).data(bridge.mediaUrl(it.uuid, "preview")).build(),
                    imageLoader = loader, contentDescription = it.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize(),
                )
            }
        }
        val current = items.getOrNull(pager.currentPage)
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.5f)), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text(current?.let { time(it.date) } ?: "", color = Color.White, fontSize = 14.sp)
                    Text(current?.name ?: "", color = Color.LightGray, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { current?.let { save(context, bridge, it) } }) { Icon(Icons.Default.Download, "Save to phone", tint = Color.White) }
            }
            Spacer(Modifier.weight(1f))
            Text("${pager.currentPage + 1} / ${items.size}", color = Color.White, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(8.dp))
        }
    }
}

/** Plain VideoView with the auth header; the bridge honours range requests so seeking works. */
@Composable
private fun VideoPlayer(url: String, auth: String) {
    AndroidView(
        factory = { ctx ->
            VideoView(ctx).apply {
                setVideoURI(Uri.parse(url), mapOf("Authorization" to auth))
                setMediaController(MediaController(ctx).also { it.setAnchorView(this) })
                setOnPreparedListener { it.isLooping = false; start() }
                setOnErrorListener { _, _, _ -> Toast.makeText(ctx, "Can't play: the video may only be in iCloud, not on the Mac", Toast.LENGTH_LONG).show(); true }
            }
        },
        modifier = Modifier.fillMaxSize(),
        onRelease = { it.stopPlayback() },
    )
}

/** Downloads the original (or the Mac's full-size preview when the original is only in iCloud) into the gallery. */
private fun save(context: Context, bridge: Bridge, it: MediaItem) {
    val name = it.name.ifBlank { it.uuid + if (it.video) ".mp4" else ".jpg" }
    val request = DownloadManager.Request(Uri.parse(bridge.mediaUrl(it.uuid, "original")))
        .addRequestHeader("Authorization", bridge.authHeader)
        .setTitle(name)
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setDestinationInExternalPublicDir(if (it.video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES, "iPhotos/$name")
    (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
    Toast.makeText(context, "Saving to ${if (it.video) "Movies" else "Pictures"}/iPhotos", Toast.LENGTH_SHORT).show()
}

private fun describe(e: BridgeException): String = when (e) {
    is BridgeException.Unreachable -> "Mac not reachable: is Tailscale on, and the Mac awake?"
    is BridgeException.Unauthorized -> "The Mac rejected the token; open the iMessage app once to refresh it."
    is BridgeException.Server -> if (e.code == "http_404") "The Mac's bridge is older than 0.5.0; update it." else e.message ?: "failed"
}

private fun time(ms: Long): String = SimpleDateFormat("EEE d MMM yyyy, HH:mm", Locale.US).format(Date(ms))
