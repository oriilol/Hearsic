package com.clio.hearsic

import android.Manifest
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil.compose.AsyncImage
import com.clio.hearsic.ui.theme.HearsicTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            var darkThemeSetting by remember { mutableStateOf("Sistema") }
            var languageSetting by remember { mutableStateOf("Sistema") }

            val currentLanguage = if (languageSetting == "Sistema") {
                when (Locale.getDefault().language) {
                    "en" -> "English"; "fr" -> "Français"; "de" -> "Deutsch";
                    "it" -> "Italiano"; "pt" -> "Português"; "ja" -> "日本語";
                    "ru" -> "Русский"; "zh" -> "中文"; else -> "Español"
                }
            } else languageSetting

            val s = getAppStrings(currentLanguage)
            val useDarkTheme = when (darkThemeSetting) { "Oscuro" -> true; "Claro" -> false; else -> isSystemInDarkTheme() }

            // Saludo dinámico según la hora
            val currentHour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
            val greetingText = when (currentHour) {
                in 6..11 -> "☀️ ${s.goodMorning}"
                in 12..19 -> "🌤️ ${s.goodAfternoon}"
                else -> "🌙 ${s.goodEvening}"
            }

            HearsicTheme(darkTheme = useDarkTheme) {
                val scope = rememberCoroutineScope()
                var songList by remember { mutableStateOf<List<Song>>(emptyList()) }
                var selectedArtist by remember { mutableStateOf<String?>(null) }
                var selectedPlaylist by remember { mutableStateOf<String?>(null) }
                var selectedTab by remember { mutableIntStateOf(1) }
                var showFullScreenPlayer by remember { mutableStateOf(false) }

                val playlists = remember { mutableStateListOf<String>().apply { addAll(getPlaylists(context)) } }
                var showCreatePlaylistDialog by remember { mutableStateOf(false) }
                var newPlaylistName by remember { mutableStateOf("") }

                var exoPlayer by remember { mutableStateOf<Player?>(null) }
                var currentMediaId by remember { mutableStateOf<String?>(null) }
                val currentSong = songList.find { it.id.toString() == currentMediaId }

                var title by remember { mutableStateOf<String?>(null) }
                var artist by remember { mutableStateOf<String?>(null) }
                var isPlaying by remember { mutableStateOf(false) }
                var playbackPosition by remember { mutableLongStateOf(0L) }
                var songDuration by remember { mutableLongStateOf(0L) }

                LaunchedEffect(Unit) {
                    try {
                        val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
                        val controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
                        controllerFuture.addListener({
                            val controller = controllerFuture.get()
                            exoPlayer = controller
                            controller.addListener(object : Player.Listener {
                                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                                    currentMediaId = mediaItem?.mediaId
                                }
                                override fun onMediaMetadataChanged(m: MediaMetadata) {
                                    val rT = m.title?.toString()
                                    val rA = m.artist?.toString()
                                    title = if (rT.isNullOrBlank() || rT.contains("<unknown>", true)) null else rT
                                    artist = if (rA.isNullOrBlank() || rA.contains("<unknown>", true)) null else rA
                                }
                                override fun onIsPlayingChanged(p: Boolean) { isPlaying = p; if (p) songDuration = controller.duration }
                            })
                        }, ContextCompat.getMainExecutor(context))
                    } catch (e: Exception) {}
                }

                LaunchedEffect(isPlaying) { while (isPlaying) { playbackPosition = exoPlayer?.currentPosition ?: 0L; delay(1000) } }

                val pReq = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
                val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) songList = queryMusic(context, s) }
                LaunchedEffect(Unit) {
                    if (ContextCompat.checkSelfPermission(context, pReq) == PackageManager.PERMISSION_GRANTED) songList = queryMusic(context, s)
                    else launcher.launch(pReq)
                }

                if (showCreatePlaylistDialog) {
                    AlertDialog(
                        onDismissRequest = { showCreatePlaylistDialog = false },
                        title = { Text(s.newPlaylist) },
                        text = { OutlinedTextField(value = newPlaylistName, onValueChange = { newPlaylistName = it }, label = { Text("Nombre") }) },
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

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(icon = { Icon(Icons.AutoMirrored.Filled.PlaylistPlay, null) }, label = { Text(s.playlists) }, selected = selectedTab == 0, onClick = { selectedTab = 0; selectedPlaylist = null })
                            NavigationBarItem(icon = { Icon(Icons.Default.Audiotrack, null) }, label = { Text(s.songs) }, selected = selectedTab == 1, onClick = { selectedTab = 1 })
                            NavigationBarItem(icon = { Icon(Icons.Default.Person, null) }, label = { Text(s.artists) }, selected = selectedTab == 2, onClick = { selectedTab = 2; selectedArtist = null })
                            NavigationBarItem(icon = { Icon(Icons.Default.Settings, null) }, label = { Text(s.settings) }, selected = selectedTab == 3, onClick = { selectedTab = 3 })
                        }
                    }
                ) { pV ->
                    Column(modifier = Modifier.fillMaxSize().padding(pV)) {
                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            Crossfade(targetState = selectedTab, label = "Tab") { tab ->
                                when (tab) {
                                    0 -> { // PLAYLISTS
                                        AnimatedContent(targetState = selectedPlaylist, label = "PlaylistAnim") { target ->
                                            if (target == null) {
                                                Column(Modifier.fillMaxSize()) {
                                                    Button(onClick = { showCreatePlaylistDialog = true }, modifier = Modifier.fillMaxWidth().padding(16.dp)) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text(s.newPlaylist) }
                                                    if (playlists.isEmpty()) Box(Modifier.fillMaxSize(), Alignment.Center) { Text(s.noPlaylists, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                                    else LazyColumn(Modifier.fillMaxSize()) { items(playlists.size) { index ->
                                                        val p = playlists[index]
                                                        Row(modifier = Modifier.fillMaxWidth().clickable { selectedPlaylist = p }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                                            Box(Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.secondaryContainer), Alignment.Center) { Icon(Icons.AutoMirrored.Filled.PlaylistPlay, null) }
                                                            Spacer(Modifier.width(16.dp)); Text(p, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                                        }
                                                    }}
                                                }
                                            } else {
                                                BackHandler { selectedPlaylist = null }
                                                val playlistSongs = getSongsForPlaylist(context, target, songList)
                                                Column {
                                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(16.dp)) {
                                                        IconButton(onClick = { selectedPlaylist = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
                                                        Text(target, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                                        if (playlistSongs.isNotEmpty()) {
                                                            FilledTonalButton(onClick = { playQueue(playlistSongs, 0, exoPlayer, true) }) { Icon(Icons.Default.PlayArrow, null); Text(" Mix") }
                                                        }
                                                    }
                                                    if (playlistSongs.isEmpty()) Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Vacía", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                                    else LazyColumn {
                                                        items(playlistSongs.size) { index ->
                                                            val song = playlistSongs[index]
                                                            SongRowSafe(song, s, currentMediaId == song.id.toString()) { playQueue(playlistSongs, index, exoPlayer, false) }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    1 -> { // CANCIONES CON SALUDO
                                        if (songList.isEmpty()) EmptyState(s) { scope.launch { forceMediaScan(context) { songList = queryMusic(context, s) } } }
                                        else {
                                            Column(Modifier.fillMaxSize()) {
                                                Text(
                                                    text = greetingText,
                                                    style = MaterialTheme.typography.headlineMedium,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    modifier = Modifier.padding(20.dp, 20.dp, 20.dp, 8.dp)
                                                )
                                                LazyColumn(Modifier.fillMaxSize()) {
                                                    items(songList.size) { index ->
                                                        val song = songList[index]
                                                        SongRowSafe(song, s, currentMediaId == song.id.toString()) { playQueue(songList, index, exoPlayer, false) }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    2 -> { // ARTISTAS
                                        AnimatedContent(targetState = selectedArtist, label = "ArtistAnim") { target ->
                                            if (target == null) {
                                                LazyColumn(Modifier.fillMaxSize()) { items(songList.map { it.artist }.distinct().size) { index ->
                                                    val a = songList.map { it.artist }.distinct()[index]
                                                    ArtistRow(a) { selectedArtist = a }
                                                }}
                                            } else {
                                                BackHandler { selectedArtist = null }
                                                val artistSongs = songList.filter { it.artist == target }
                                                Column {
                                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(16.dp)) {
                                                        IconButton(onClick = { selectedArtist = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
                                                        Text(target, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                                        if (artistSongs.isNotEmpty()) {
                                                            FilledTonalButton(onClick = { playQueue(artistSongs, 0, exoPlayer, true) }) { Icon(Icons.Default.PlayArrow, null); Text(" Mix") }
                                                        }
                                                    }
                                                    LazyColumn {
                                                        items(artistSongs.size) { index ->
                                                            val song = artistSongs[index]
                                                            SongRowSafe(song, s, currentMediaId == song.id.toString()) { playQueue(artistSongs, index, exoPlayer, false) }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    3 -> SettingsScreen(s, darkThemeSetting, { darkThemeSetting = it }, languageSetting, { languageSetting = it })
                                }
                            }
                        }

                        AnimatedVisibility(visible = currentSong != null, enter = slideInVertically { it } + fadeIn()) {
                            MiniPlayer(currentSong, isPlaying, { showFullScreenPlayer = true }, { if (isPlaying) exoPlayer?.pause() else exoPlayer?.play() }, (if (songDuration > 0) playbackPosition.toFloat() / songDuration.toFloat() else 0f), s)
                        }
                    }
                }

                AnimatedVisibility(visible = showFullScreenPlayer, enter = slideInVertically(initialOffsetY = { it }) + fadeIn(), exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()) {
                    FullScreenPlayer(currentSong, isPlaying, playbackPosition, songDuration, { showFullScreenPlayer = false }, { if (isPlaying) exoPlayer?.pause() else exoPlayer?.play() }, { exoPlayer?.seekTo(it) }, exoPlayer, playlists, s)
                }
            }
        }
    }
}

fun playQueue(songs: List<Song>, startIndex: Int, player: Player?, shuffle: Boolean) {
    try {
        val mediaItems = songs.map { song ->
            MediaItem.Builder()
                .setUri(song.uri)
                .setMediaId(song.id.toString())
                .setMediaMetadata(MediaMetadata.Builder().setTitle(song.title).setArtist(song.artist).setArtworkUri(song.artworkUri).build())
                .build()
        }
        player?.setMediaItems(mediaItems, startIndex, 0L)
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

@Composable
fun SettingsScreen(s: HStrings, darkT: String, onT: (String) -> Unit, lang: String, onL: (String) -> Unit) {
    val uriH = LocalUriHandler.current
    Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState())) {
        Text(s.settings, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold); Spacer(Modifier.height(32.dp))
        Text(s.theme, fontWeight = FontWeight.Bold)
        Row(Modifier.padding(vertical = 8.dp)) { listOf("Sistema", "Claro", "Oscuro").forEach { t -> FilterChip(selected = darkT == t, onClick = { onT(t) }, label = { Text(t) }, modifier = Modifier.padding(end = 8.dp)) } }
        Spacer(Modifier.height(24.dp)); Text(s.language, fontWeight = FontWeight.Bold)
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(Modifier.padding(vertical = 8.dp)) {
            listOf("Sistema", "Español", "English", "Français", "Deutsch", "Italiano", "Português", "日本語", "Русский", "中文").forEach { l -> FilterChip(selected = lang == l, onClick = { onL(l) }, label = { Text(l) }, modifier = Modifier.padding(end = 8.dp, bottom = 8.dp)) }
        }
        Spacer(Modifier.weight(1f))
        Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("made with love ❤️🇪🇸", style = MaterialTheme.typography.bodySmall)
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
    Column {
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(2.dp))
        Row(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceColorAtElevation(4.dp)).clickable { onOpen() }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            val fallbackIcon = rememberVectorPainter(image = Icons.Default.MusicNote)
            AsyncImage(model = song?.artworkUri, contentDescription = null, modifier = Modifier.size(50.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.secondaryContainer), contentScale = ContentScale.Crop, error = fallbackIcon)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(song?.title ?: s.unknownTitle, fontWeight = FontWeight.Bold, maxLines = 1, fontSize = 14.sp)
                Text(song?.artist ?: s.unknownArtist, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
            IconButton(onClick = onPlayPause) { Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, Modifier.size(32.dp)) }
        }
    }
}

@Composable
fun FullScreenPlayer(song: Song?, isPlaying: Boolean, pos: Long, dur: Long, onClose: () -> Unit, onPlayPause: () -> Unit, onSeek: (Long) -> Unit, player: Player?, playlists: List<String>, s: HStrings) {
    BackHandler { onClose() }
    val context = LocalContext.current
    var showInfo by remember { mutableStateOf(false) }
    var showLyrics by remember { mutableStateOf(false) }
    var showAddPlaylist by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(1f) }
    val prefs = context.getSharedPreferences("lyrics_db", Context.MODE_PRIVATE)
    var lyricsText by remember(song) { mutableStateOf(prefs.getString("lyrics_${song?.id}", "") ?: "") }

    if (showInfo && song != null) {
        AlertDialog(onDismissRequest = { showInfo = false }, title = { Text("ℹ️ Info") }, text = { Column { Text("Formato: ${song.mimeType}"); Text("Tamaño: ${formatSize(song.size)}") } }, confirmButton = { TextButton(onClick = { showInfo = false }) { Text("OK") } })
    }

    if (showAddPlaylist && song != null) {
        AlertDialog(
            onDismissRequest = { showAddPlaylist = false },
            title = { Text(s.addToPlaylist) },
            text = {
                if (playlists.isEmpty()) Text(s.noPlaylists)
                else LazyColumn { items(playlists.size) { index ->
                    val p = playlists[index]
                    Text(p, Modifier.fillMaxWidth().clickable { addSongToPlaylist(context, p, song.id); showAddPlaylist = false; Toast.makeText(context, "Añadida", Toast.LENGTH_SHORT).show() }.padding(16.dp))
                }}
            },
            confirmButton = { TextButton(onClick = { showAddPlaylist = false }) { Text(s.cancel) } }
        )
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Default.KeyboardArrowDown, null, Modifier.size(36.dp)) }
                Row {
                    IconButton(onClick = { showAddPlaylist = true }) { Icon(Icons.Default.PlaylistAdd, null) }
                    IconButton(onClick = { showInfo = true }) { Icon(Icons.Default.Info, null) }
                }
            }
            Spacer(Modifier.weight(1f))
            Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                if (showLyrics) OutlinedTextField(value = lyricsText, onValueChange = { lyricsText = it; prefs.edit().putString("lyrics_${song?.id}", it).apply() }, modifier = Modifier.fillMaxSize().padding(8.dp))
                else AsyncImage(model = song?.artworkUri, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, error = rememberVectorPainter(Icons.Default.MusicNote))
            }
            Spacer(Modifier.height(48.dp))
            Text(song?.title ?: s.unknownTitle, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center, maxLines = 2)
            Text(song?.artist ?: s.unknownArtist, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary, maxLines = 1)
            Spacer(Modifier.height(48.dp))
            Slider(value = if (dur > 0) pos.toFloat() else 0f, onValueChange = { onSeek(it.toLong()) }, valueRange = 0f..(if (dur > 0) dur.toFloat() else 1f))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(formatTime(pos), style = MaterialTheme.typography.bodySmall); Text(formatTime(dur), style = MaterialTheme.typography.bodySmall) }
            Spacer(Modifier.height(24.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { speed = when (speed) { 1f -> 1.5f; 1.5f -> 2f; 2f -> 0.5f; else -> 1f }; player?.setPlaybackSpeed(speed) }) { Text("${speed}x", fontWeight = FontWeight.Bold, fontSize = 18.sp) }
                IconButton(onClick = { player?.seekToPreviousMediaItem() }) { Icon(Icons.Default.SkipPrevious, null, Modifier.size(36.dp)) }
                IconButton(onClick = onPlayPause, modifier = Modifier.size(80.dp).background(MaterialTheme.colorScheme.primary, CircleShape)) { Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onPrimary) }
                IconButton(onClick = { player?.seekToNextMediaItem() }) { Icon(Icons.Default.SkipNext, null, Modifier.size(36.dp)) }
                IconButton(onClick = { showLyrics = !showLyrics }) { Icon(if (showLyrics) Icons.Default.Image else Icons.Default.Notes, null, Modifier.size(28.dp), tint = if (showLyrics) MaterialTheme.colorScheme.primary else LocalContentColor.current) }
            }
            Spacer(Modifier.weight(1.2f))
        }
    }
}

fun formatTime(ms: Long): String { val s = ms / 1000; return "%02d:%02d".format(s / 60, s % 60) }
fun formatSize(bytes: Long): String { return "%.2f MB".format(bytes / (1024f * 1024f)) }

@Composable
fun EmptyState(s: HStrings, onScan: () -> Unit) {
    Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
        Icon(Icons.Default.MusicOff, null, Modifier.size(100.dp), tint = MaterialTheme.colorScheme.primary.copy(0.1f))
        Text(s.noMusic, Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onScan) { Text(s.update) }
    }
}

data class Song(val id: Long, val title: String, val artist: String, val uri: Uri, val artworkUri: Uri?, val size: Long, val mimeType: String)
fun queryMusic(c: Context, s: HStrings): List<Song> {
    val sL = mutableListOf<Song>()
    val col = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    val proj = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.MIME_TYPE)
    try {
        c.contentResolver.query(col, proj, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, null)?.use { cur ->
            val iC = cur.getColumnIndexOrThrow(MediaStore.Audio.Media._ID); val tC = cur.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val aC = cur.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST); val albC = cur.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val szC = cur.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE); val mmC = cur.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            while (cur.moveToNext()) {
                val id = cur.getLong(iC); val rT = cur.getString(tC); val rA = cur.getString(aC)
                val size = cur.getLong(szC); val mime = cur.getString(mmC) ?: "audio/unknown"
                val artUri = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), cur.getLong(albC))
                sL.add(Song(id, rT ?: s.unknownTitle, rA ?: s.unknownArtist, ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id), artUri, size, mime))
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
    val goodMorning: String, val goodAfternoon: String, val goodEvening: String
)

fun getAppStrings(l: String): HStrings = when(l) {
    "English" -> HStrings("Playlists", "Songs", "Artists", "Albums", "Settings", "Theme", "Language", "Soon", "No music", "Update Library", "Unknown", "Unknown Title", "Unknown Artist", "New Playlist", "No playlists yet", "Create", "Cancel", "Add to Playlist", "Good morning", "Good afternoon", "Good evening")
    "Français" -> HStrings("Playlists", "Chansons", "Artistes", "Albums", "Paramètres", "Thème", "Langue", "Bientôt", "Aucune musique", "Mettre à jour", "Inconnu", "Titre inconnu", "Artiste inconnu", "Nouvelle Playlist", "Aucune playlist", "Créer", "Annuler", "Ajouter à la playlist", "Bonjour", "Bon après-midi", "Bonsoir")
    "Deutsch" -> HStrings("Playlists", "Lieder", "Künstler", "Alben", "Einstellungen", "Thema", "Sprache", "Bald", "Keine Musik", "Bibliothek aktualisieren", "Unbekannt", "Unbekannter Titel", "Unbekannter Künstler", "Neue Playlist", "Noch keine Playlists", "Erstellen", "Abbrechen", "Zur Playlist hinzufügen", "Guten Morgen", "Guten Tag", "Guten Abend")
    "Italiano" -> HStrings("Playlist", "Canzoni", "Artisti", "Album", "Impostazioni", "Tema", "Lingua", "Presto", "Nessuna musica", "Aggiorna libreria", "Sconosciuto", "Titolo sconosciuto", "Artista sconosciuto", "Nuova Playlist", "Nessuna playlist", "Creare", "Annulla", "Aggiungi alla playlist", "Buongiorno", "Buon pomeriggio", "Buonasera")
    "Português" -> HStrings("Playlists", "Músicas", "Artistas", "Álbuns", "Configurações", "Tema", "Idioma", "Em breve", "Nenhuma música", "Atualizar Biblioteca", "Desconhecido", "Título desconhecido", "Artista desconhecido", "Nova Playlist", "Nenhuma playlist ainda", "Criar", "Cancelar", "Adicionar à playlist", "Bom dia", "Boa tarde", "Boa noite")
    "日本語" -> HStrings("プレイリスト", "曲", "アーティスト", "アルバム", "設定", "テーマ", "言語", "もうすぐ", "音楽がありません", "ライブラリを更新", "不明", "不明なタイトル", "不明なアーティスト", "新しいプレイリスト", "プレイリストがありません", "作成", "キャンセル", "プレイリストに追加", "おはようございます", "こんにちは", "こんばんは")
    "Русский" -> HStrings("Плейлисты", "Песни", "Исполнители", "Альбомы", "Настройки", "Тема", "Язык", "Скоро", "Нет музыки", "Обновить библиотеку", "Неизвестно", "Неизвестное название", "Неизвестный исполнитель", "Новый плейлист", "Нет плейлистов", "Создать", "Отмена", "Добавить в плейлист", "Доброе утро", "Добрый день", "Добрый вечер")
    "中文" -> HStrings("播放列表", "歌曲", "艺术家", "专辑", "设置", "主题", "语言", "即将推出", "没有音乐", "更新库", "未知", "未知标题", "未知艺术家", "新播放列表", "暂无播放列表", "创建", "取消", "添加到播放列表", "早上好", "下午好", "晚上好")
    else -> HStrings("Playlists", "Canciones", "Artistas", "Álbumes", "Ajustes", "Tema", "Idioma", "Próximamente", "No hay música", "Actualizar Biblioteca", "Desconocido", "Título desconocido", "Artista desconocido", "Nueva Playlist", "No hay playlists todavía", "Crear", "Cancelar", "Añadir a la Playlist", "Buenos días", "Buenas tardes", "Buenas noches")
}