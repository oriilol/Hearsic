package com.clio.hearsic

import android.Manifest
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.util.Consumer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil.compose.AsyncImage
import com.clio.hearsic.ui.theme.HearsicTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val uriHandler = LocalUriHandler.current
            val prefs = context.getSharedPreferences("hearsic_settings", Context.MODE_PRIVATE)

            var darkThemeSetting by remember { mutableStateOf(prefs.getString("theme", "System") ?: "System") }
            var languageSetting by remember { mutableStateOf(prefs.getString("language", "System") ?: "System") }

            val currentLanguage = if (languageSetting == "System") {
                when (Locale.getDefault().language) {
                    "en" -> "English"; "fr" -> "Français"; "de" -> "Deutsch";
                    "it" -> "Italiano"; "pt" -> "Português"; "ja" -> "日本語";
                    "ru" -> "Русский"; "zh" -> "中文"; "ko" -> "한국어";
                    "ar" -> "العربية"; "hi" -> "हिन्दी"; else -> "Español"
                }
            } else languageSetting

            val s = getAppStrings(currentLanguage)
            val useDarkTheme = when (darkThemeSetting) { "Dark" -> true; "Light" -> false; else -> isSystemInDarkTheme() }

            val currentHour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
            val greetingText = when (currentHour) {
                in 6..11 -> "☀️ ${s.goodMorning}"
                in 12..19 -> "🌤️ ${s.goodAfternoon}"
                else -> "🌙 ${s.goodEvening}"
            }

            var updateUrl by remember { mutableStateOf<String?>(null) }
            var updateVersion by remember { mutableStateOf<String?>(null) }
            var versionStatus by remember { mutableIntStateOf(0) }
            val currentAppVersion = "v1.2.1"

            LaunchedEffect(Unit) {
                withContext(Dispatchers.IO) {
                    try {
                        val url = java.net.URL("https://api.github.com/repos/oriilol/hearsic/releases/latest")
                        val connection = url.openConnection() as java.net.HttpURLConnection
                        connection.requestMethod = "GET"
                        val response = connection.inputStream.bufferedReader().readText()
                        val tagMatch = Regex("\"tag_name\":\"(.*?)\"").find(response)
                        val urlMatch = Regex("\"html_url\":\"(.*?)\"").find(response)
                        if (tagMatch != null && urlMatch != null) {
                            val latestVersion = tagMatch.groupValues[1]
                            val cP = currentAppVersion.replace("v", "").split(".").map { it.toIntOrNull() ?: 0 }
                            val lP = latestVersion.replace("v", "").split(".").map { it.toIntOrNull() ?: 0 }
                            var isNewer = false
                            var isOlder = false
                            for (i in 0 until maxOf(cP.size, lP.size)) {
                                val c = cP.getOrElse(i) { 0 }
                                val l = lP.getOrElse(i) { 0 }
                                if (l > c) { isNewer = true; break }
                                if (l < c) { isOlder = true; break }
                            }
                            if (isNewer) {
                                versionStatus = 2
                                updateVersion = latestVersion
                                updateUrl = urlMatch.groupValues[1]
                            } else if (isOlder) {
                                versionStatus = 3
                            } else {
                                versionStatus = 1
                            }
                        }
                    } catch (e: Exception) {}
                }
            }

            HearsicTheme(darkTheme = useDarkTheme) {
                val scope = rememberCoroutineScope()
                var songList by remember { mutableStateOf<List<Song>>(emptyList()) }
                var selectedArtist by remember { mutableStateOf<String?>(null) }
                var selectedPlaylist by remember { mutableStateOf<String?>(null) }
                var selectedTab by rememberSaveable { mutableIntStateOf(1) }

                var showFullScreenPlayer by rememberSaveable { mutableStateOf((context as? ComponentActivity)?.intent?.action == "OPEN_PLAYER") }

                DisposableEffect(context) {
                    val activity = context as ComponentActivity
                    val listener = Consumer<Intent> { intent ->
                        if (intent.action == "OPEN_PLAYER") showFullScreenPlayer = true
                    }
                    activity.addOnNewIntentListener(listener)
                    onDispose { activity.removeOnNewIntentListener(listener) }
                }

                val playlists = remember { mutableStateListOf<String>().apply { addAll(getPlaylists(context)) } }
                var showCreatePlaylistDialog by remember { mutableStateOf(false) }
                var newPlaylistName by remember { mutableStateOf("") }
                var playlistCoverTrigger by remember { mutableIntStateOf(0) }

                var playlistOptionsFor by remember { mutableStateOf<String?>(null) }
                var playlistToRename by remember { mutableStateOf<String?>(null) }
                var showRenameDialog by remember { mutableStateOf(false) }
                var renamePlaylistText by remember { mutableStateOf("") }

                var exoPlayer by remember { mutableStateOf<Player?>(null) }
                var currentMediaId by rememberSaveable { mutableStateOf<String?>(null) }
                val currentSong = songList.find { it.id.toString() == currentMediaId }

                var title by remember { mutableStateOf<String?>(null) }
                var artist by remember { mutableStateOf<String?>(null) }
                var isPlaying by remember { mutableStateOf(false) }
                var playbackPosition by remember { mutableLongStateOf(0L) }
                var songDuration by remember { mutableLongStateOf(0L) }
                var hasNext by remember { mutableStateOf(false) }
                var hasPrev by remember { mutableStateOf(false) }

                val coverPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
                    if (uri != null && selectedPlaylist != null) {
                        try {
                            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        } catch (e: Exception) {}
                        context.getSharedPreferences("hearsic_pl", Context.MODE_PRIVATE).edit().putString("pl_cover_${selectedPlaylist}", uri.toString()).apply()
                        playlistCoverTrigger++
                    }
                }

                LaunchedEffect(Unit) {
                    try {
                        val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
                        val controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
                        controllerFuture.addListener({
                            val controller = controllerFuture.get()
                            exoPlayer = controller
                            currentMediaId = controller.currentMediaItem?.mediaId
                            isPlaying = controller.isPlaying
                            songDuration = if (controller.duration < 0) 0L else controller.duration
                            title = controller.mediaMetadata.title?.toString()
                            artist = controller.mediaMetadata.artist?.toString()
                            hasNext = controller.hasNextMediaItem()
                            hasPrev = controller.hasPreviousMediaItem()

                            controller.addListener(object : Player.Listener {
                                override fun onEvents(player: Player, events: Player.Events) {
                                    hasNext = player.hasNextMediaItem()
                                    hasPrev = player.hasPreviousMediaItem()
                                }
                                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                                    currentMediaId = mediaItem?.mediaId
                                }
                                override fun onMediaMetadataChanged(m: MediaMetadata) {
                                    val rT = m.title?.toString()
                                    val rA = m.artist?.toString()
                                    title = if (rT.isNullOrBlank() || rT.contains("<unknown>", true)) null else rT
                                    artist = if (rA.isNullOrBlank() || rA.contains("<unknown>", true)) null else rA
                                }
                                override fun onIsPlayingChanged(p: Boolean) {
                                    isPlaying = p
                                    if (p) songDuration = if (controller.duration < 0) 0L else controller.duration
                                }
                            })
                        }, ContextCompat.getMainExecutor(context))
                    } catch (e: Exception) {}
                }

                LaunchedEffect(isPlaying) {
                    while (isPlaying) {
                        val p = exoPlayer?.currentPosition ?: 0L
                        playbackPosition = if (p < 0) 0L else p
                        delay(500)
                    }
                }

                val pReq = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
                val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) songList = queryMusic(context, s) }
                LaunchedEffect(Unit) {
                    if (ContextCompat.checkSelfPermission(context, pReq) == PackageManager.PERMISSION_GRANTED) songList = queryMusic(context, s)
                    else launcher.launch(pReq)
                }

                if (updateUrl != null) {
                    AlertDialog(
                        onDismissRequest = { updateUrl = null },
                        title = { Text(s.updateAvailable) },
                        text = { Text("${s.newVersion} $updateVersion") },
                        confirmButton = { TextButton(onClick = { uriHandler.openUri(updateUrl!!); updateUrl = null }) { Text(s.update) } },
                        dismissButton = { TextButton(onClick = { updateUrl = null }) { Text(s.cancel) } }
                    )
                }

                if (showCreatePlaylistDialog) {
                    AlertDialog(
                        onDismissRequest = { showCreatePlaylistDialog = false },
                        title = { Text(s.newPlaylist) },
                        text = { OutlinedTextField(value = newPlaylistName, onValueChange = { newPlaylistName = it }, label = { Text(s.newPlaylist) }) },
                        confirmButton = {
                            TextButton(onClick = {
                                if (newPlaylistName.isNotBlank()) {
                                    createPlaylist(context, newPlaylistName)
                                    if (!playlists.contains(newPlaylistName)) playlists.add(newPlaylistName)
                                    showCreatePlaylistDialog = false; newPlaylistName = ""
                                }
                            }) { Text(s.create) }
                        },
                        dismissButton = { TextButton(onClick = { showCreatePlaylistDialog = false }) { Text(s.cancel) } }
                    )
                }

                if (playlistOptionsFor != null) {
                    AlertDialog(
                        onDismissRequest = { playlistOptionsFor = null },
                        title = { Text(playlistOptionsFor!!) },
                        text = {
                            Column {
                                Text(s.rename, Modifier.clickable {
                                    playlistToRename = playlistOptionsFor
                                    renamePlaylistText = playlistOptionsFor!!
                                    showRenameDialog = true
                                    playlistOptionsFor = null
                                }.fillMaxWidth().padding(16.dp))
                                Text(s.delete, Modifier.clickable {
                                    deletePlaylist(context, playlistOptionsFor!!)
                                    playlists.remove(playlistOptionsFor!!)
                                    playlistOptionsFor = null
                                }.fillMaxWidth().padding(16.dp), color = MaterialTheme.colorScheme.error)
                            }
                        },
                        confirmButton = { TextButton(onClick = { playlistOptionsFor = null }) { Text(s.cancel) } }
                    )
                }

                if (showRenameDialog && playlistToRename != null) {
                    AlertDialog(
                        onDismissRequest = { showRenameDialog = false; playlistToRename = null },
                        title = { Text(s.rename) },
                        text = { OutlinedTextField(value = renamePlaylistText, onValueChange = { renamePlaylistText = it }) },
                        confirmButton = {
                            TextButton(onClick = {
                                if (renamePlaylistText.isNotBlank() && renamePlaylistText != playlistToRename) {
                                    renamePlaylist(context, playlistToRename!!, renamePlaylistText)
                                    playlists.clear()
                                    playlists.addAll(getPlaylists(context))
                                }
                                showRenameDialog = false
                                playlistToRename = null
                            }) { Text(s.rename) }
                        },
                        dismissButton = { TextButton(onClick = { showRenameDialog = false; playlistToRename = null }) { Text(s.cancel) } }
                    )
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        if (!showFullScreenPlayer) {
                            NavigationBar {
                                NavigationBarItem(icon = { Icon(Icons.AutoMirrored.Filled.PlaylistPlay, null) }, label = { Text(s.playlists) }, selected = selectedTab == 0, onClick = { selectedTab = 0; selectedPlaylist = null })
                                NavigationBarItem(icon = { Icon(Icons.Default.Audiotrack, null) }, label = { Text(s.songs) }, selected = selectedTab == 1, onClick = { selectedTab = 1 })
                                NavigationBarItem(icon = { Icon(Icons.Default.Person, null) }, label = { Text(s.artists) }, selected = selectedTab == 2, onClick = { selectedTab = 2; selectedArtist = null })
                                NavigationBarItem(icon = { Icon(Icons.Default.Settings, null) }, label = { Text(s.settings) }, selected = selectedTab == 3, onClick = { selectedTab = 3 })
                            }
                        }
                    }
                ) { pV ->
                    Column(modifier = Modifier.fillMaxSize().padding(pV)) {
                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            Crossfade(targetState = selectedTab, label = "Tab") { tab ->
                                when (tab) {
                                    0 -> {
                                        AnimatedContent(targetState = selectedPlaylist, label = "PlaylistAnim", modifier = Modifier.fillMaxSize()) { target ->
                                            if (target == null) {
                                                Column(Modifier.fillMaxSize()) {
                                                    Button(onClick = { showCreatePlaylistDialog = true }, modifier = Modifier.fillMaxWidth().padding(16.dp)) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text(s.newPlaylist) }
                                                    if (playlists.isEmpty()) Box(Modifier.fillMaxSize(), Alignment.Center) { Text(s.noPlaylists, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                                    else LazyColumn(Modifier.fillMaxSize()) {
                                                        items(playlists, key = { it }) { p ->
                                                            val coverUri = context.getSharedPreferences("hearsic_pl", Context.MODE_PRIVATE).getString("pl_cover_$p", null)
                                                            val forceRecompose = playlistCoverTrigger
                                                            Row(modifier = Modifier.fillMaxWidth().pointerInput(Unit) { detectTapGestures(onTap = { selectedPlaylist = p }, onLongPress = { playlistOptionsFor = p }) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                                                Box(Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.secondaryContainer), Alignment.Center) {
                                                                    if (coverUri != null && forceRecompose >= 0) {
                                                                        AsyncImage(model = Uri.parse(coverUri), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                                                    } else {
                                                                        Icon(Icons.AutoMirrored.Filled.PlaylistPlay, null)
                                                                    }
                                                                }
                                                                Spacer(Modifier.width(16.dp)); Text(p, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                                            }
                                                        }
                                                    }
                                                }
                                            } else {
                                                BackHandler { selectedPlaylist = null }
                                                val playlistSongs = getSongsForPlaylist(context, target, songList)
                                                Column(Modifier.fillMaxSize()) {
                                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(16.dp)) {
                                                        IconButton(onClick = { selectedPlaylist = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
                                                        Text(target, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                                        IconButton(onClick = { coverPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Icon(Icons.Default.AddPhotoAlternate, null) }
                                                        if (playlistSongs.isNotEmpty()) {
                                                            FilledTonalButton(onClick = { playQueue(playlistSongs, 0, exoPlayer, true) }) { Icon(Icons.Default.PlayArrow, null); Text(" Mix") }
                                                        }
                                                    }
                                                    if (playlistSongs.isEmpty()) Box(Modifier.fillMaxSize(), Alignment.Center) { Text(s.noMusic, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                                    else LazyColumn(Modifier.fillMaxSize()) {
                                                        items(playlistSongs, key = { it.id }) { song ->
                                                            val index = playlistSongs.indexOf(song)
                                                            SongRowSafe(song, s, currentMediaId == song.id.toString()) { playQueue(playlistSongs, index, exoPlayer, false) }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    1 -> {
                                        if (songList.isEmpty()) EmptyState(s) { scope.launch { forceMediaScan(context) { songList = queryMusic(context, s) } } }
                                        else {
                                            Column(Modifier.fillMaxSize()) {
                                                Text(text = greetingText, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(20.dp, 20.dp, 20.dp, 8.dp))
                                                LazyColumn(Modifier.fillMaxSize()) {
                                                    items(songList, key = { it.id }) { song ->
                                                        val index = songList.indexOf(song)
                                                        SongRowSafe(song, s, currentMediaId == song.id.toString()) { playQueue(songList, index, exoPlayer, false) }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    2 -> {
                                        AnimatedContent(targetState = selectedArtist, label = "ArtistAnim", modifier = Modifier.fillMaxSize()) { target ->
                                            if (target == null) {
                                                val distinctArtists = songList.map { it.artist }.distinct()
                                                LazyColumn(Modifier.fillMaxSize()) {
                                                    items(distinctArtists, key = { it }) { a ->
                                                        ArtistRow(a) { selectedArtist = a }
                                                    }
                                                }
                                            } else {
                                                BackHandler { selectedArtist = null }
                                                val artistSongs = songList.filter { it.artist == target }
                                                Column(Modifier.fillMaxSize()) {
                                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(16.dp)) {
                                                        IconButton(onClick = { selectedArtist = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
                                                        Text(target, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                                        if (artistSongs.isNotEmpty()) {
                                                            FilledTonalButton(onClick = { playQueue(artistSongs, 0, exoPlayer, true) }) { Icon(Icons.Default.PlayArrow, null); Text(" Mix") }
                                                        }
                                                    }
                                                    LazyColumn(Modifier.fillMaxSize()) {
                                                        items(artistSongs, key = { it.id }) { song ->
                                                            val index = artistSongs.indexOf(song)
                                                            SongRowSafe(song, s, currentMediaId == song.id.toString()) { playQueue(artistSongs, index, exoPlayer, false) }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    3 -> SettingsScreen(
                                        s,
                                        darkThemeSetting,
                                        { darkThemeSetting = it; prefs.edit().putString("theme", it).apply() },
                                        languageSetting,
                                        { languageSetting = it; prefs.edit().putString("language", it).apply() },
                                        versionStatus,
                                        currentAppVersion
                                    )
                                }
                            }
                        }

                        AnimatedVisibility(visible = currentSong != null && !showFullScreenPlayer, enter = slideInVertically { it } + fadeIn()) {
                            MiniPlayer(currentSong, isPlaying, { showFullScreenPlayer = true }, { if (isPlaying) exoPlayer?.pause() else exoPlayer?.play() }, (if (songDuration > 0) playbackPosition.toFloat() / songDuration.toFloat() else 0f), s)
                        }
                    }
                }

                AnimatedVisibility(visible = showFullScreenPlayer, enter = slideInVertically(initialOffsetY = { it }) + fadeIn(), exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()) {
                    FullScreenPlayer(currentSong, isPlaying, playbackPosition, songDuration, hasNext, hasPrev, { showFullScreenPlayer = false }, { if (isPlaying) exoPlayer?.pause() else exoPlayer?.play() }, { exoPlayer?.seekTo(it) }, exoPlayer, playlists, s)
                }
            }
        }
    }
}

fun playQueue(songs: List<Song>, startIndex: Int, player: Player?, shuffle: Boolean) {
    try {
        val mediaItems = songs.map { song ->
            MediaItem.Builder().setUri(song.uri).setMediaId(song.id.toString()).setMediaMetadata(MediaMetadata.Builder().setTitle(song.title).setArtist(song.artist).setArtworkUri(song.artworkUri).build()).build()
        }
        val actualStartIndex = if (shuffle && songs.isNotEmpty()) songs.indices.random() else startIndex
        player?.setMediaItems(mediaItems, actualStartIndex, 0L)
        player?.shuffleModeEnabled = shuffle
        player?.setPlaybackSpeed(1f)
        player?.prepare()
        player?.play()
    } catch (e: Exception) {}
}

fun getPlaylists(ctx: Context): List<String> = ctx.getSharedPreferences("hearsic_pl", Context.MODE_PRIVATE).getStringSet("all_pl", emptySet())?.toList()?.sorted() ?: emptyList()
fun createPlaylist(ctx: Context, name: String) {
    val prefs = ctx.getSharedPreferences("hearsic_pl", Context.MODE_PRIVATE)
    val names = prefs.getStringSet("all_pl", emptySet())?.toMutableSet() ?: mutableSetOf()
    names.add(name)
    prefs.edit().putStringSet("all_pl", names).apply()
}
fun addSongToPlaylist(ctx: Context, plName: String, songId: Long) {
    val prefs = ctx.getSharedPreferences("hearsic_pl", Context.MODE_PRIVATE)
    val songs = prefs.getStringSet("pl_songs_$plName", emptySet())?.toMutableSet() ?: mutableSetOf()
    songs.add(songId.toString())
    prefs.edit().putStringSet("pl_songs_$plName", songs).apply()
}
fun getSongsForPlaylist(ctx: Context, plName: String, allSongs: List<Song>): List<Song> {
    val songIds = ctx.getSharedPreferences("hearsic_pl", Context.MODE_PRIVATE).getStringSet("pl_songs_$plName", emptySet()) ?: emptySet()
    return allSongs.filter { songIds.contains(it.id.toString()) }
}
fun deletePlaylist(ctx: Context, name: String) {
    val prefs = ctx.getSharedPreferences("hearsic_pl", Context.MODE_PRIVATE)
    val names = prefs.getStringSet("all_pl", emptySet())?.toMutableSet() ?: mutableSetOf()
    names.remove(name)
    prefs.edit().putStringSet("all_pl", names).remove("pl_songs_$name").remove("pl_cover_$name").apply()
}
fun renamePlaylist(ctx: Context, oldName: String, newName: String) {
    val prefs = ctx.getSharedPreferences("hearsic_pl", Context.MODE_PRIVATE)
    val names = prefs.getStringSet("all_pl", emptySet())?.toMutableSet() ?: mutableSetOf()
    names.remove(oldName)
    names.add(newName)
    val songs = prefs.getStringSet("pl_songs_$oldName", emptySet())
    val cover = prefs.getString("pl_cover_$oldName", null)
    val editor = prefs.edit().putStringSet("all_pl", names).remove("pl_songs_$oldName").remove("pl_cover_$oldName")
    if (songs != null) editor.putStringSet("pl_songs_$newName", songs)
    if (cover != null) editor.putString("pl_cover_$newName", cover)
    editor.apply()
}

data class LrcLine(val timeMs: Long, val text: String)
fun parseLrc(lrc: String): List<LrcLine> {
    val regex = Regex("""\[(\d{2}):(\d{2})\.(\d{2,3})\](.*)""")
    return lrc.lines().mapNotNull { line ->
        val match = regex.find(line)
        if (match != null) {
            val min = match.groupValues[1].toLong()
            val sec = match.groupValues[2].toLong()
            val ms = match.groupValues[3].padEnd(3, '0').toLong()
            val text = match.groupValues[4].trim()
            LrcLine(min * 60000 + sec * 1000 + ms, text)
        } else null
    }
}

@Composable
fun SettingsScreen(s: HStrings, darkT: String, onT: (String) -> Unit, lang: String, onL: (String) -> Unit, vStatus: Int, curVer: String) {
    val uriH = LocalUriHandler.current
    Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState())) {
        Text(s.settings, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(32.dp))
        Text(s.theme, fontWeight = FontWeight.Bold)
        Row(Modifier.padding(vertical = 8.dp)) {
            val themeKeys = listOf("System", "Light", "Dark")
            val themeLabels = listOf(s.system, s.light, s.dark)
            themeKeys.forEachIndexed { i, t ->
                FilterChip(selected = darkT == t, onClick = { onT(t) }, label = { Text(themeLabels[i]) }, modifier = Modifier.padding(end = 8.dp))
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(s.language, fontWeight = FontWeight.Bold)
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(Modifier.padding(vertical = 8.dp)) {
            val langKeys = listOf("System", "Español", "English", "Français", "Deutsch", "Italiano", "Português", "日本語", "Русский", "中文", "한국어", "العربية", "हिन्दी")
            langKeys.forEach { l ->
                val labelText = if (l == "System") s.system else l
                FilterChip(selected = lang == l, onClick = { onL(l) }, label = { Text(labelText) }, modifier = Modifier.padding(end = 8.dp, bottom = 8.dp))
            }
        }
        Spacer(Modifier.weight(1f))
        Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Hearsic $curVer", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            if (vStatus != 0) {
                val statusText = when (vStatus) {
                    1 -> s.upToDate
                    2 -> s.updateAvailableSetting
                    3 -> s.unreleasedVersion
                    else -> ""
                }
                Text(statusText, style = MaterialTheme.typography.bodySmall, color = if (vStatus == 2) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            Text("made w/ love from spain 🇪🇸 <3", style = MaterialTheme.typography.bodySmall)
            Text("github.com/oriilol", Modifier.clickable { uriH.openUri("https://github.com/oriilol") }.padding(8.dp), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun SongRowSafe(song: Song, s: HStrings, isPlaying: Boolean, onClick: () -> Unit) {
    val bgColor = if (isPlaying) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else Color.Transparent
    val txtColor = if (isPlaying) MaterialTheme.colorScheme.primary else Color.Unspecified
    Row(modifier = Modifier.fillMaxWidth().background(bgColor).clickable { onClick() }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        val fallbackIcon = rememberVectorPainter(image = Icons.Default.MusicNote)
        AsyncImage(model = song.artworkUri, contentDescription = null, modifier = Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentScale = ContentScale.Crop, error = fallbackIcon)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(if (song.title == "Desconocido") s.unknownTitle else song.title, fontWeight = FontWeight.Bold, maxLines = 1, fontSize = 16.sp, color = txtColor)
            Text(if (song.artist == "Desconocido") s.unknownArtist else song.artist, style = MaterialTheme.typography.bodySmall, color = if (isPlaying) txtColor else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (isPlaying) Icon(Icons.Default.PlayCircle, null, tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
fun ArtistRow(name: String, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) { Icon(Icons.Default.Person, null) }
        Spacer(Modifier.width(20.dp)); Text(name, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
    }
}

@Composable
fun MiniPlayer(song: Song?, isPlaying: Boolean, onOpen: () -> Unit, onPlayPause: () -> Unit, progress: Float, s: HStrings) {
    val isUnsupported = song?.dataPath?.endsWith(".wma", true) == true
    Column {
        if (!isUnsupported) {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(2.dp))
        }
        Row(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceColorAtElevation(4.dp)).clickable { onOpen() }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            val fallbackIcon = rememberVectorPainter(image = Icons.Default.MusicNote)
            AsyncImage(model = song?.artworkUri, contentDescription = null, modifier = Modifier.size(50.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.secondaryContainer), contentScale = ContentScale.Crop, error = fallbackIcon)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(song?.title ?: s.unknownTitle, fontWeight = FontWeight.Bold, maxLines = 1, fontSize = 14.sp)
                Text(song?.artist ?: s.unknownArtist, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
            if (isUnsupported) {
                Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(32.dp))
            } else {
                IconButton(onClick = onPlayPause) {
                    Crossfade(targetState = isPlaying, label = "MiniPlayPause") { playing ->
                        Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, Modifier.size(32.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun LrcViewer(parsedLyrics: List<LrcLine>, currentPos: Long, onSeek: (Long) -> Unit) {
    val listState = rememberLazyListState()
    val activeIndex = parsedLyrics.indexOfLast { it.timeMs <= currentPos }.coerceAtLeast(0)

    LaunchedEffect(activeIndex) {
        if (activeIndex >= 0 && parsedLyrics.isNotEmpty()) {
            listState.animateScrollToItem(activeIndex.coerceAtLeast(0), scrollOffset = -300)
        }
    }

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        items(parsedLyrics.size) { index ->
            val line = parsedLyrics[index]
            val isActive = index == activeIndex
            Text(
                text = line.text,
                color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.Normal,
                fontSize = if (isActive) 24.sp else 18.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 12.dp).clickable { onSeek(line.timeMs) }
            )
        }
    }
}

@Composable
fun FullScreenPlayer(song: Song?, isPlaying: Boolean, pos: Long, dur: Long, hasNext: Boolean, hasPrev: Boolean, onClose: () -> Unit, onPlayPause: () -> Unit, onSeek: (Long) -> Unit, player: Player?, playlists: List<String>, s: HStrings) {
    BackHandler { onClose() }
    val context = LocalContext.current
    var showInfo by remember { mutableStateOf(false) }
    var showWhyUnsupported by remember { mutableStateOf(false) }
    var showLyrics by rememberSaveable { mutableStateOf(false) }
    var isEditingLyrics by rememberSaveable { mutableStateOf(false) }
    var showAddPlaylist by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(1f) }
    var sliderPos by remember { mutableStateOf<Float?>(null) }

    val isUnsupported = song?.dataPath?.endsWith(".wma", true) == true

    val prefs = context.getSharedPreferences("lyrics_db", Context.MODE_PRIVATE)
    var lyricsText by remember(song) { mutableStateOf(prefs.getString("lyrics_${song?.id}", "") ?: "") }
    val parsedLyrics = remember(lyricsText) { parseLrc(lyricsText) }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val currentDisplayPos = sliderPos?.toLong() ?: pos

    if (showInfo && song != null) {
        AlertDialog(onDismissRequest = { showInfo = false }, title = { Text("ℹ️ Info") }, text = { Column { Text("${s.format}: ${song.mimeType}"); Text("${s.size}: ${formatSize(song.size)}") } }, confirmButton = { TextButton(onClick = { showInfo = false }) { Text("OK") } })
    }

    if (showWhyUnsupported) {
        AlertDialog(onDismissRequest = { showWhyUnsupported = false }, title = { Text(s.cannotPlay) }, text = { Text(s.unsupportedFormatMsg) }, confirmButton = { TextButton(onClick = { showWhyUnsupported = false }) { Text("OK") } })
    }

    if (showAddPlaylist && song != null) {
        AlertDialog(
            onDismissRequest = { showAddPlaylist = false },
            title = { Text(s.addToPlaylist) },
            text = {
                if (playlists.isEmpty()) Text(s.noPlaylists)
                else LazyColumn { items(playlists.size) { index ->
                    val p = playlists[index]
                    Text(p, Modifier.fillMaxWidth().clickable { addSongToPlaylist(context, p, song.id); showAddPlaylist = false; Toast.makeText(context, s.added, Toast.LENGTH_SHORT).show() }.padding(16.dp))
                }}
            },
            confirmButton = { TextButton(onClick = { showAddPlaylist = false }) { Text(s.cancel) } }
        )
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (isLandscape) {
            Row(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                AnimatedContent(targetState = song, transitionSpec = { slideInHorizontally { it } + fadeIn() togetherWith slideOutHorizontally { -it } + fadeOut() }, label = "ArtworkLand", modifier = Modifier.weight(1f).fillMaxHeight()) { animSong ->
                    Box(modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                        val isUnsuppAnim = animSong?.dataPath?.endsWith(".wma", true) == true
                        Crossfade(targetState = showLyrics && !isUnsuppAnim, label = "LyricsFade") { showL ->
                            if (showL) {
                                if (isEditingLyrics) OutlinedTextField(value = lyricsText, onValueChange = { lyricsText = it; prefs.edit().putString("lyrics_${animSong?.id}", it).apply() }, modifier = Modifier.fillMaxSize().padding(8.dp))
                                else if (parsedLyrics.isNotEmpty()) LrcViewer(parsedLyrics, currentDisplayPos, onSeek)
                                else if (lyricsText.isNotBlank()) Text(lyricsText, modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState()), textAlign = TextAlign.Center)
                                else OutlinedTextField(value = lyricsText, onValueChange = { lyricsText = it; prefs.edit().putString("lyrics_${animSong?.id}", it).apply() }, modifier = Modifier.fillMaxSize().padding(8.dp), placeholder = { Text(s.pasteLyrics) })
                            } else AsyncImage(model = animSong?.artworkUri, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, error = rememberVectorPainter(Icons.Default.MusicNote))
                        }
                    }
                }
                Spacer(Modifier.width(24.dp))
                Column(modifier = Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        IconButton(onClick = onClose) { Icon(Icons.Default.KeyboardArrowDown, null, Modifier.size(36.dp)) }
                        Row {
                            if (showLyrics && !isUnsupported) IconButton(onClick = { isEditingLyrics = !isEditingLyrics }) { Icon(Icons.Default.Edit, null) }
                            IconButton(onClick = { showAddPlaylist = true }) { Icon(Icons.Default.PlaylistAdd, null) }
                            IconButton(onClick = { showInfo = true }) { Icon(Icons.Default.Info, null) }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    AnimatedContent(targetState = song, transitionSpec = { slideInHorizontally { it } + fadeIn() togetherWith slideOutHorizontally { -it } + fadeOut() }, label = "InfoLand") { animSong ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(animSong?.title ?: s.unknownTitle, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center, maxLines = 2)
                                if (animSong?.mimeType?.contains("flac", true) == true || animSong?.mimeType?.contains("wav", true) == true) {
                                    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(8.dp), modifier = Modifier.padding(start = 8.dp)) { Text(s.hq, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), color = MaterialTheme.colorScheme.onTertiaryContainer) }
                                }
                            }
                            Text(animSong?.artist ?: s.unknownArtist, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                        }
                    }

                    if (isUnsupported) {
                        Spacer(Modifier.height(48.dp))
                        Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(16.dp))
                        Text(s.cannotPlay, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        TextButton(onClick = { showWhyUnsupported = true }) { Text(s.why, color = MaterialTheme.colorScheme.primary) }
                        Spacer(Modifier.height(24.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { player?.seekToPreviousMediaItem() }, enabled = hasPrev) { Icon(Icons.Default.SkipPrevious, null, Modifier.size(36.dp)) }
                            IconButton(onClick = { player?.seekToNextMediaItem() }, enabled = hasNext) { Icon(Icons.Default.SkipNext, null, Modifier.size(36.dp)) }
                        }
                    } else {
                        Spacer(Modifier.height(24.dp))
                        Slider(value = sliderPos ?: if (dur > 0) pos.toFloat() else 0f, onValueChange = { sliderPos = it }, onValueChangeFinished = { sliderPos?.let { onSeek(it.toLong()) }; sliderPos = null }, valueRange = 0f..(if (dur > 0) dur.toFloat() else 1f))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(formatTime(currentDisplayPos), style = MaterialTheme.typography.bodySmall); Text(formatTime(dur), style = MaterialTheme.typography.bodySmall) }
                        Spacer(Modifier.height(24.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { speed = when (speed) { 1f -> 1.5f; 1.5f -> 2f; 2f -> 0.5f; else -> 1f }; player?.setPlaybackSpeed(speed) }) { Text("${speed}x", fontWeight = FontWeight.Bold, fontSize = 18.sp) }
                            IconButton(onClick = { player?.seekToPreviousMediaItem() }, enabled = hasPrev) { Icon(Icons.Default.SkipPrevious, null, Modifier.size(36.dp)) }
                            IconButton(onClick = onPlayPause, modifier = Modifier.size(80.dp).background(MaterialTheme.colorScheme.primary, CircleShape)) {
                                Crossfade(targetState = isPlaying, label = "FullPlayPause") { playing ->
                                    Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onPrimary)
                                }
                            }
                            IconButton(onClick = { player?.seekToNextMediaItem() }, enabled = hasNext) { Icon(Icons.Default.SkipNext, null, Modifier.size(36.dp)) }
                            IconButton(onClick = { showLyrics = !showLyrics }) { Icon(if (showLyrics) Icons.Default.Image else Icons.Default.Notes, null, Modifier.size(28.dp), tint = if (showLyrics) MaterialTheme.colorScheme.primary else LocalContentColor.current) }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Default.KeyboardArrowDown, null, Modifier.size(36.dp)) }
                    Row {
                        if (showLyrics && !isUnsupported) IconButton(onClick = { isEditingLyrics = !isEditingLyrics }) { Icon(Icons.Default.Edit, null) }
                        IconButton(onClick = { showAddPlaylist = true }) { Icon(Icons.Default.PlaylistAdd, null) }
                        IconButton(onClick = { showInfo = true }) { Icon(Icons.Default.Info, null) }
                    }
                }
                Spacer(Modifier.weight(1f))

                AnimatedContent(targetState = song, transitionSpec = { slideInHorizontally { it } + fadeIn() togetherWith slideOutHorizontally { -it } + fadeOut() }, label = "ArtworkAndInfoPort") { animSong ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                            val isUnsuppAnim = animSong?.dataPath?.endsWith(".wma", true) == true
                            Crossfade(targetState = showLyrics && !isUnsuppAnim, label = "LyricsFade") { showL ->
                                if (showL) {
                                    if (isEditingLyrics) OutlinedTextField(value = lyricsText, onValueChange = { lyricsText = it; prefs.edit().putString("lyrics_${animSong?.id}", it).apply() }, modifier = Modifier.fillMaxSize().padding(8.dp))
                                    else if (parsedLyrics.isNotEmpty()) LrcViewer(parsedLyrics, currentDisplayPos, onSeek)
                                    else if (lyricsText.isNotBlank()) Text(lyricsText, modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState()), textAlign = TextAlign.Center)
                                    else OutlinedTextField(value = lyricsText, onValueChange = { lyricsText = it; prefs.edit().putString("lyrics_${animSong?.id}", it).apply() }, modifier = Modifier.fillMaxSize().padding(8.dp), placeholder = { Text(s.pasteLyrics) })
                                } else AsyncImage(model = animSong?.artworkUri, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, error = rememberVectorPainter(Icons.Default.MusicNote))
                            }
                        }
                        Spacer(Modifier.height(48.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(animSong?.title ?: s.unknownTitle, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center, maxLines = 2)
                            if (animSong?.mimeType?.contains("flac", true) == true || animSong?.mimeType?.contains("wav", true) == true) {
                                Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(8.dp), modifier = Modifier.padding(start = 8.dp)) { Text(s.hq, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), color = MaterialTheme.colorScheme.onTertiaryContainer) }
                            }
                        }
                        Text(animSong?.artist ?: s.unknownArtist, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                    }
                }

                if (isUnsupported) {
                    Spacer(Modifier.height(64.dp))
                    Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(64.dp))
                    Spacer(Modifier.height(16.dp))
                    Text(s.cannotPlay, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    TextButton(onClick = { showWhyUnsupported = true }) { Text(s.why, color = MaterialTheme.colorScheme.primary) }
                    Spacer(Modifier.height(24.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { player?.seekToPreviousMediaItem() }, enabled = hasPrev) { Icon(Icons.Default.SkipPrevious, null, Modifier.size(36.dp)) }
                        IconButton(onClick = { player?.seekToNextMediaItem() }, enabled = hasNext) { Icon(Icons.Default.SkipNext, null, Modifier.size(36.dp)) }
                    }
                } else {
                    Spacer(Modifier.height(48.dp))
                    Slider(value = sliderPos ?: if (dur > 0) pos.toFloat() else 0f, onValueChange = { sliderPos = it }, onValueChangeFinished = { sliderPos?.let { onSeek(it.toLong()) }; sliderPos = null }, valueRange = 0f..(if (dur > 0) dur.toFloat() else 1f))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(formatTime(currentDisplayPos), style = MaterialTheme.typography.bodySmall); Text(formatTime(dur), style = MaterialTheme.typography.bodySmall) }
                    Spacer(Modifier.height(24.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { speed = when (speed) { 1f -> 1.5f; 1.5f -> 2f; 2f -> 0.5f; else -> 1f }; player?.setPlaybackSpeed(speed) }) { Text("${speed}x", fontWeight = FontWeight.Bold, fontSize = 18.sp) }
                        IconButton(onClick = { player?.seekToPreviousMediaItem() }, enabled = hasPrev) { Icon(Icons.Default.SkipPrevious, null, Modifier.size(36.dp)) }
                        IconButton(onClick = onPlayPause, modifier = Modifier.size(80.dp).background(MaterialTheme.colorScheme.primary, CircleShape)) {
                            Crossfade(targetState = isPlaying, label = "FullPlayPause") { playing ->
                                Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onPrimary)
                            }
                        }
                        IconButton(onClick = { player?.seekToNextMediaItem() }, enabled = hasNext) { Icon(Icons.Default.SkipNext, null, Modifier.size(36.dp)) }
                        IconButton(onClick = { showLyrics = !showLyrics }) { Icon(if (showLyrics) Icons.Default.Image else Icons.Default.Notes, null, Modifier.size(28.dp), tint = if (showLyrics) MaterialTheme.colorScheme.primary else LocalContentColor.current) }
                    }
                }
                Spacer(Modifier.weight(1.2f))
            }
        }
    }
}

fun formatTime(ms: Long): String {
    if (ms < 0) return "00:00"
    val s = ms / 1000
    return "%02d:%02d".format(s / 60, s % 60)
}
fun formatSize(bytes: Long): String { return "%.2f MB".format(bytes / (1024f * 1024f)) }

@Composable
fun EmptyState(s: HStrings, onScan: () -> Unit) {
    Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
        Icon(Icons.Default.MusicOff, null, Modifier.size(100.dp), tint = MaterialTheme.colorScheme.primary.copy(0.1f))
        Text(s.noMusic, Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onScan) { Text(s.update) }
    }
}

data class Song(val id: Long, val title: String, val artist: String, val uri: Uri, val artworkUri: Uri?, val size: Long, val mimeType: String, val dataPath: String)

fun queryMusic(c: Context, s: HStrings): List<Song> {
    val sL = mutableListOf<Song>()
    val col = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    val proj = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.MIME_TYPE, MediaStore.Audio.Media.DATA)
    val sel = "${MediaStore.Audio.Media.IS_MUSIC} != 0 OR ${MediaStore.Audio.Media.DATA} LIKE '%.wma'"
    try {
        c.contentResolver.query(col, proj, sel, null, null)?.use { cur ->
            val iC = cur.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val tC = cur.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val aC = cur.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albC = cur.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val szC = cur.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val mmC = cur.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            val dataC = cur.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)

            while (cur.moveToNext()) {
                val path = cur.getString(dataC) ?: ""
                val fName = path.substringAfterLast("/")
                if (!path.contains("Recordings", true) && !path.contains("Call", true) && !path.contains("Voice", true) && !path.contains("WhatsApp Audio", true) && !fName.startsWith("AUD-", true) && !fName.startsWith("PTT-", true)) {
                    val id = cur.getLong(iC)
                    var rT = cur.getString(tC)
                    if (rT.isNullOrBlank() || rT == "<unknown>") rT = fName.substringBeforeLast(".")
                    var rA = cur.getString(aC)
                    if (rA.isNullOrBlank() || rA == "<unknown>") rA = s.unknownArtist
                    val size = cur.getLong(szC)
                    val mime = cur.getString(mmC) ?: "audio/unknown"
                    val artUri = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), cur.getLong(albC))
                    sL.add(Song(id, rT, rA, ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id), artUri, size, mime, path))
                }
            }
        }
    } catch (e: Exception) { return emptyList() }
    return sL.sortedBy { it.title }
}

fun forceMediaScan(c: Context, onUpdate: () -> Unit) {
    try {
        val paths = mutableListOf<String>()
        listOf(Environment.DIRECTORY_MUSIC, Environment.DIRECTORY_DOWNLOADS).forEach { dir -> Environment.getExternalStoragePublicDirectory(dir).listFiles()?.forEach { if (it.isFile) paths.add(it.absolutePath) } }
        if (paths.isNotEmpty()) MediaScannerConnection.scanFile(c, paths.toTypedArray(), null) { _, _ -> onUpdate() } else onUpdate()
    } catch (e: Exception) {}
}

data class HStrings(
    val playlists: String, val songs: String, val artists: String, val albums: String, val settings: String,
    val theme: String, val language: String, val soon: String, val noMusic: String, val update: String,
    val unknown: String, val unknownTitle: String, val unknownArtist: String, val newPlaylist: String,
    val noPlaylists: String, val create: String, val cancel: String, val addToPlaylist: String,
    val goodMorning: String, val goodAfternoon: String, val goodEvening: String, val hq: String,
    val system: String, val light: String, val dark: String, val updateAvailable: String, val newVersion: String,
    val rename: String, val delete: String, val upToDate: String, val updateAvailableSetting: String, val unreleasedVersion: String,
    val added: String, val format: String, val size: String, val pasteLyrics: String, val cannotPlay: String, val why: String, val unsupportedFormatMsg: String
)

fun getAppStrings(l: String): HStrings = when(l) {
    "English" -> HStrings("Playlists", "Songs", "Artists", "Albums", "Settings", "Theme", "Language", "Soon", "No music", "Update Library", "Unknown", "Unknown Title", "Unknown Artist", "New Playlist", "No playlists yet", "Create", "Cancel", "Add to Playlist", "Good morning", "Good afternoon", "Good evening", "HQ", "System", "Light", "Dark", "Update available", "New version found:", "Rename", "Delete", "You're up to date", "New update available!", "You are using an unreleased version", "Added", "Format", "Size", "Paste lyrics or LRC file here...", "Cannot play this file", "Why?", "The WMA format is proprietary to Microsoft. Google's native player on Android cannot read this file without advanced external decoders. Please convert the file to MP3 or FLAC.")
    "Français" -> HStrings("Playlists", "Chansons", "Artistes", "Albums", "Paramètres", "Thème", "Langue", "Bientôt", "Aucune musique", "Mettre à jour", "Inconnu", "Titre inconnu", "Artiste inconnu", "Nouvelle Playlist", "Aucune playlist", "Créer", "Annuler", "Ajouter à la playlist", "Bonjour", "Bon après-midi", "Bonsoir", "HQ", "Système", "Clair", "Sombre", "Mise à jour disponible", "Nouvelle version:", "Renommer", "Supprimer", "Vous êtes à jour", "Nouvelle mise à jour disponible !", "Vous utilisez une version non publiée", "Ajouté", "Format", "Taille", "Collez les paroles ou le fichier LRC ici...", "Impossible de lire ce fichier", "Pourquoi ?", "Le format WMA est la propriété de Microsoft. Le lecteur natif de Google sur Android ne peut pas lire ce fichier sans décodeurs externes avancés. Veuillez convertir le fichier en MP3 ou FLAC.")
    "Deutsch" -> HStrings("Playlists", "Lieder", "Künstler", "Alben", "Einstellungen", "Thema", "Sprache", "Bald", "Keine Musik", "Bibliothek aktualisieren", "Unbekannt", "Unbekannter Titel", "Unbekannter Künstler", "Neue Playlist", "Noch keine Playlists", "Erstellen", "Abbrechen", "Zur Playlist hinzufügen", "Guten Morgen", "Guten Tag", "Guten Abend", "HQ", "System", "Hell", "Dunkel", "Update verfügbar", "Neue Version:", "Umbenennen", "Löschen", "Sie sind auf dem neuesten Stand", "Neues Update verfügbar!", "Sie verwenden eine unveröffentlichte Version", "Hinzugefügt", "Format", "Größe", "Fügen Sie hier Liedtexte oder LRC-Dateien ein...", "Kann diese Datei nicht abspielen", "Warum?", "Das WMA-Format ist Eigentum von Microsoft. Der native Player von Google auf Android kann diese Datei ohne erweiterte externe Decoder nicht lesen. Bitte konvertieren Sie die Datei in MP3 oder FLAC.")
    "Italiano" -> HStrings("Playlist", "Canzoni", "Artisti", "Album", "Impostazioni", "Tema", "Lingua", "Presto", "Nessuna musica", "Aggiorna libreria", "Sconosciuto", "Titolo sconosciuto", "Artista sconosciuto", "Nuova Playlist", "Nessuna playlist", "Creare", "Annulla", "Aggiungi alla playlist", "Buongiorno", "Buon pomeriggio", "Buonasera", "HQ", "Sistema", "Chiaro", "Scuro", "Aggiornamento disponibile", "Nuova versione:", "Rinomina", "Elimina", "Sei aggiornato", "Nuovo aggiornamento disponibile!", "Stai utilizzando una versione non pubblicata", "Aggiunto", "Formato", "Dimensione", "Incolla qui il testo o il file LRC...", "Impossibile riprodurre", "Perché?", "Il formato WMA è di proprietà di Microsoft. Il lettore nativo di Google su Android non può leggere questo file senza decodificatori esterni avanzati. Si prega di convertire il file in MP3 o FLAC.")
    "Português" -> HStrings("Playlists", "Músicas", "Artistas", "Álbuns", "Configurações", "Tema", "Idioma", "Em breve", "Nenhuma música", "Atualizar Biblioteca", "Desconhecido", "Título desconhecido", "Artista desconhecido", "Nova Playlist", "Nenhuma playlist ainda", "Criar", "Cancelar", "Adicionar à playlist", "Bom dia", "Boa tarde", "Boa noite", "HQ", "Sistema", "Claro", "Escuro", "Atualização disponível", "Nova versão:", "Renomear", "Excluir", "Você está atualizado", "Nova atualização disponível!", "Você está usando uma versão não lançada", "Adicionado", "Formato", "Tamanho", "Cole as letras ou o arquivo LRC aqui...", "Não é possível reproduzir", "Por quê?", "O formato WMA é propriedade da Microsoft. O reprodutor nativo do Google no Android não pode ler este arquivo sem decodificadores externos avançados. Por favor, converta o arquivo para MP3 ou FLAC.")
    "日本語" -> HStrings("プレイリスト", "曲", "アーティスト", "アルバム", "設定", "テーマ", "言語", "もうすぐ", "音楽がありません", "ライブラリを更新", "不明", "不明なタイトル", "不明なアーティスト", "新しいプレイリスト", "プレイリストがありません", "作成", "キャンセル", "プレイリストに追加", "おはようございます", "こんにちは", "こんばんは", "高音質", "システム", "ライト", "ダーク", "利用可能なアップデート", "新しいバージョン：", "名前を変更", "削除", "最新の状態です", "新しいアップデートがあります！", "未リリースのバージョンを使用しています", "追加しました", "フォーマット", "サイズ", "ここに歌詞またはLRCファイルを貼り付けます...", "再生できません", "なぜですか？", "WMA形式はMicrosoftの独自仕様です。Android上のGoogleの標準プレーヤーでは、高度な外部デコーダーなしではこのファイルを読み取ることができません。MP3またはFLACに変換してください。")
    "Русский" -> HStrings("Плейлисты", "Песни", "Исполнители", "Альбомы", "Настройки", "Тема", "Язык", "Скоро", "Нет музыки", "Обновить библиотеку", "Неизвестно", "Неизвестное название", "Неизвестный исполнитель", "Новый плейлист", "Нет плейлистов", "Создать", "Отмена", "Добавить в плейлист", "Доброе утро", "Добрый день", "Добрый вечер", "HQ", "Система", "Светлая", "Темная", "Доступно обновление", "Новая версия:", "Переименовать", "Удалить", "У вас последняя версия", "Доступно новое обновление!", "Вы используете невыпущенную версию", "Добавлено", "Формат", "Размер", "Вставьте сюда текст или файл LRC...", "Невозможно воспроизвести", "Почему?", "Формат WMA является собственностью Microsoft. Стандартный плеер Google на Android не может прочитать этот файл без дополнительных внешних декодеров. Пожалуйста, конвертируйте файл в MP3 или FLAC.")
    "中文" -> HStrings("播放列表", "歌曲", "艺术家", "专辑", "设置", "主题", "语言", "即将推出", "没有音乐", "更新库", "未知", "未知标题", "未知艺术家", "新播放列表", "暂无播放列表", "创建", "取消", "添加到播放列表", "早上好", "下午好", "晚上好", "高音质", "系统", "浅色", "深色", "可用更新", "新版本：", "重命名", "删除", "您已是最新版本", "有新更新可用！", "您正在使用未发布的版本", "已添加", "格式", "大小", "在此处粘贴歌词或 LRC 文件...", "无法播放", "为什么？", "WMA 格式是 Microsoft 的专有格式。如果没有高级外部解码器，Android 上的 Google 原生播放器无法读取此文件。请将文件转换为 MP3 或 FLAC。")
    "한국어" -> HStrings("플레이리스트", "노래", "아티스트", "앨범", "설정", "테마", "언어", "곧", "음악 없음", "라이브러리 업데이트", "알 수 없음", "알 수 없는 제목", "알 수 없는 아티스트", "새 플레이리스트", "플레이리스트 없음", "만들기", "취소", "플레이리스트에 추가", "좋은 아침입니다", "좋은 오후입니다", "좋은 저녁입니다", "HQ", "시스템", "밝게", "어둡게", "업데이트 가능", "새 버전:", "이름 바꾸기", "삭제", "최신 버전입니다", "새로운 업데이트가 있습니다!", "출시되지 않은 버전을 사용 중입니다", "추가됨", "형식", "크기", "여기에 가사 또는 LRC 파일 붙여넣기...", "재생할 수 없음", "왜요?", "WMA 형식은 Microsoft의 독점 형식입니다. Android의 Google 기본 플레이어는 고급 외부 디코더 없이는 이 파일을 읽을 수 없습니다. 파일을 MP3 또는 FLAC로 변환하세요.")
    "العربية" -> HStrings("قوائم التشغيل", "أغاني", "فنانون", "ألبومات", "إعدادات", "السمة", "اللغة", "قريبًا", "لا توجد موسيقى", "تحديث المكتبة", "غير معروف", "عنوان غير معروف", "فنان غير معروف", "قائمة تشغيل جديدة", "لا توجد قوائم تشغيل", "إنشاء", "إلغاء", "إضافة إلى قائمة التشغيل", "صباح الخير", "مساء الخير", "مساء الخير", "جودة عالية", "النظام", "فاتح", "داكن", "تحديث متاح", "إصدار جديد:", "إعادة تسمية", "حذف", "أنت على أحدث إصدار", "تحديث جديد متاح!", "أنت تستخدم إصدار غير منشور", "تمت الإضافة", "التنسيق", "الحجم", "الصق الكلمات أو ملف LRC هنا...", "لا يمكن تشغيل الملف", "لماذا؟", "تنسيق WMA مملوك لشركة Microsoft. لا يمكن لمشغل Google الأساسي على Android قراءة هذا الملف بدون وحدات فك ترميز خارجية متقدمة. يرجى تحويل الملف إلى MP3 أو FLAC.")
    "हिन्दी" -> HStrings("प्लेलिस्ट", "गाने", "कलाकार", "एल्बम", "सेटिंग्स", "थीम", "भाषा", "जल्द ही", "कोई संगीत नहीं", "लाइब्रेरी अपडेट करें", "अज्ञात", "अज्ञात शीर्षक", "अज्ञात कलाकार", "नई प्लेलिस्ट", "कोई प्लेलिस्ट नहीं", "बनाएं", "रद्द करें", "प्लेलिस्ट में जोड़ें", "सुप्रभात", "शुभ दोपहर", "शुभ संध्या", "HQ", "सिस्टम", "लाइट", "डार्क", "अपडेट उपलब्ध", "नया संस्करण:", "नाम बदलें", "हटाएं", "आप अप टू डेट हैं", "नया अपडेट उपलब्ध है!", "आप एक अप्रकाशित संस्करण का उपयोग कर रहे हैं", "जोड़ा गया", "प्रारूप", "आकार", "यहां गीत या LRC फ़ाइल चिपकाएं...", "यह फ़ाइल नहीं चला सकते", "क्यों?", "WMA प्रारूप Microsoft के स्वामित्व में है। Android पर Google का मूल प्लेयर उन्नत बाहरी डिकोडर के बिना इस फ़ाइल को नहीं पढ़ सकता है। कृपया फ़ाइल को MP3 या FLAC में बदलें।")
    else -> HStrings("Playlists", "Canciones", "Artistas", "Álbumes", "Ajustes", "Tema", "Idioma", "Próximamente", "No hay música", "Actualizar Biblioteca", "Desconocido", "Título desconocido", "Artista desconocido", "Nueva Playlist", "No hay playlists todavía", "Crear", "Cancelar", "Añadir a la Playlist", "Buenos días", "Buenas tardes", "Buenas noches", "Alta calidad", "Sistema", "Claro", "Oscuro", "Actualización disponible", "Nueva versión encontrada:", "Renombrar", "Borrar", "Estás al día", "¡Nueva actualización disponible!", "Estás usando una versión no publicada", "Añadida", "Formato", "Tamaño", "Pega la letra o el archivo LRC aquí...", "Este archivo no se puede reproducir", "¿Por qué?", "El formato WMA está patentado por Microsoft. El reproductor nativo de Google en Android no puede leer este archivo por motivos legales. Te recomendamos convertirlo a formato MP3 o FLAC.")
}