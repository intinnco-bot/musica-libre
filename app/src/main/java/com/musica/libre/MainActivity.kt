package com.musica.libre

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.delay

data class AudioTrack(val name: String, val uri: Uri)

private val DarkBg = Color(0xFF0F172A)
private val CardBg = Color(0xFF1E293B)
private val AccentBlue = Color(0xFF38BDF8)
private val AccentGreen = Color(0xFF10B981)
private val TextPrimary = Color(0xFFF8FAFC)
private val TextSecondary = Color(0xFF94A3B8)

fun formatTime(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val sessionToken = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()

        setContent {
            var player by remember { mutableStateOf<MediaController?>(null) }
            var tracks by remember { mutableStateOf<List<AudioTrack>>(emptyList()) }
            var searchQuery by remember { mutableStateOf("") }
            var isPlaying by remember { mutableStateOf(false) }
            var currentTitle by remember { mutableStateOf("Elige una canción") }
            var currentUri by remember { mutableStateOf<Uri?>(null) }
            var shuffleOn by remember { mutableStateOf(false) }
            var repeatMode by remember { mutableStateOf(Player.REPEAT_MODE_OFF) }
            var currentPos by remember { mutableStateOf(0L) }
            var duration by remember { mutableStateOf(0L) }
            var isDragging by remember { mutableStateOf(false) }

            // Estado del tema de color (guardado en preferencias)
            var isGreenTheme by remember { mutableStateOf(prefs.getBoolean("is_green_theme", true)) }
            val activeAccent = if (isGreenTheme) AccentGreen else AccentBlue

            DisposableEffect(Unit) {
                controllerFuture.addListener({
                    val mc = controllerFuture.get()
                    player = mc
                    isPlaying = mc.isPlaying
                    shuffleOn = mc.shuffleModeEnabled
                    repeatMode = mc.repeatMode
                    currentTitle = mc.currentMediaItem?.mediaMetadata?.title?.toString() ?: currentTitle
                    currentUri = mc.currentMediaItem?.localConfiguration?.uri

                    mc.addListener(object : Player.Listener {
                        override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
                        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                            currentTitle = mediaItem?.mediaMetadata?.title?.toString() ?: "Sin título"
                            currentUri = mediaItem?.localConfiguration?.uri
                        }
                        override fun onShuffleModeEnabledChanged(enabled: Boolean) { shuffleOn = enabled }
                        override fun onRepeatModeChanged(mode: Int) { repeatMode = mode }
                    })
                }, MoreExecutors.directExecutor())
                onDispose { MediaController.releaseFuture(controllerFuture) }
            }

            // Actualiza la barra de progreso cada medio segundo
            LaunchedEffect(player, isPlaying) {
                while (true) {
                    player?.let { mc ->
                        if (!isDragging) {
                            currentPos = mc.currentPosition.coerceAtLeast(0L)
                            duration = mc.duration.coerceAtLeast(0L)
                        }
                    }
                    delay(500)
                }
            }

            fun loadFolder(treeUri: Uri) {
                val dir = DocumentFile.fromTreeUri(this, treeUri) ?: return
                val found = dir.listFiles()
                    .filter { it.isFile && (it.name?.endsWith(".mp3", true) == true || it.name?.endsWith(".mp4", true) == true) }
                    .sortedBy { it.name?.lowercase() }
                    .map { AudioTrack(it.name ?: "Desconocido", it.uri) }
                tracks = found
            }

            LaunchedEffect(Unit) {
                val saved = prefs.getString("folder_uri", null)
                if (saved != null) loadFolder(Uri.parse(saved))
            }

            val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                if (uri != null) {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    prefs.edit().putString("folder_uri", uri.toString()).apply()
                    loadFolder(uri)
                }
            }

            val filteredTracks = remember(tracks, searchQuery) {
                if (searchQuery.isBlank()) tracks
                else tracks.filter { it.name.contains(searchQuery, ignoreCase = true) }
            }

            val customColors = darkColorScheme(
                primary = activeAccent,
                onPrimary = DarkBg,
                background = DarkBg,
                surface = CardBg,
                onBackground = TextPrimary,
                onSurface = TextPrimary
            )

            MaterialTheme(colorScheme = customColors) {
                Scaffold(
                    containerColor = DarkBg,
                    topBar = {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .background(DarkBg)
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "Intinnco Music",
                                        fontSize = 23.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        "${tracks.size} pistas disponibles",
                                        fontSize = 13.sp,
                                        color = TextSecondary
                                    )
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    // Botón para cambiar entre Azul y Verde
                                    IconButton(
                                        onClick = {
                                            isGreenTheme = !isGreenTheme
                                            prefs.edit().putBoolean("is_green_theme", isGreenTheme).apply()
                                        },
                                        modifier = Modifier
                                            .size(42.dp)
                                            .clip(CircleShape)
                                            .background(CardBg)
                                    ) {
                                        Icon(
                                            Icons.Rounded.Palette,
                                            contentDescription = "Cambiar color",
                                            tint = activeAccent,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }

                                    FilledTonalButton(
                                        onClick = { folderPicker.launch(null) },
                                        colors = ButtonDefaults.filledTonalButtonColors(
                                            containerColor = CardBg,
                                            contentColor = activeAccent
                                        ),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                                    ) {
                                        Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(20.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Carpeta")
                                    }
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                placeholder = { Text("Buscar canción...", color = TextSecondary) },
                                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = activeAccent) },
                                trailingIcon = {
                                    if (searchQuery.isNotEmpty()) {
                                        IconButton(onClick = { searchQuery = "" }) {
                                            Icon(Icons.Rounded.Close, contentDescription = "Limpiar", tint = TextSecondary)
                                        }
                                    }
                                },
                                singleLine = true,
                                shape = RoundedCornerShape(14.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = CardBg,
                                    unfocusedContainerColor = CardBg,
                                    focusedBorderColor = activeAccent,
                                    unfocusedBorderColor = Color.Transparent
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    },
                    bottomBar = {
                        Surface(
                            color = CardBg,
                            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                            tonalElevation = 12.dp
                        ) {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 16.dp)
                            ) {
                                Text(
                                    text = currentTitle,
                                    color = TextPrimary,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(4.dp))

                                // Barra de progreso
                                Slider(
                                    value = if (duration > 0L) currentPos.toFloat() else 0f,
                                    onValueChange = {
                                        isDragging = true
                                        currentPos = it.toLong()
                                    },
                                    onValueChangeFinished = {
                                        player?.seekTo(currentPos)
                                        isDragging = false
                                    },
                                    valueRange = 0f..(duration.coerceAtLeast(1L).toFloat()),
                                    colors = SliderDefaults.colors(
                                        thumbColor = activeAccent,
                                        activeTrackColor = activeAccent,
                                        inactiveTrackColor = DarkBg
                                    )
                                )
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(formatTime(currentPos), fontSize = 12.sp, color = TextSecondary)
                                    Text(formatTime(duration), fontSize = 12.sp, color = TextSecondary)
                                }

                                Spacer(Modifier.height(8.dp))

                                // Botones grandes de control
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceEvenly,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconButton(
                                        onClick = { player?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } },
                                        modifier = Modifier.size(48.dp)
                                    ) {
                                        Icon(
                                            Icons.Rounded.Shuffle,
                                            contentDescription = "Aleatorio",
                                            tint = if (shuffleOn) activeAccent else TextSecondary,
                                            modifier = Modifier.size(28.dp)
                                        )
                                    }

                                    IconButton(
                                        onClick = { player?.seekToPrevious() },
                                        modifier = Modifier.size(56.dp)
                                    ) {
                                        Icon(
                                            Icons.Rounded.SkipPrevious,
                                            contentDescription = "Anterior",
                                            tint = TextPrimary,
                                            modifier = Modifier.size(40.dp)
                                        )
                                    }

                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .size(72.dp)
                                            .clip(CircleShape)
                                            .background(activeAccent)
                                            .clickable {
                                                player?.let { if (it.isPlaying) it.pause() else it.play() }
                                            }
                                    ) {
                                        Icon(
                                            imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                            contentDescription = "Play o Pausa",
                                            tint = DarkBg,
                                            modifier = Modifier.size(44.dp)
                                        )
                                    }

                                    IconButton(
                                        onClick = { player?.seekToNext() },
                                        modifier = Modifier.size(56.dp)
                                    ) {
                                        Icon(
                                            Icons.Rounded.SkipNext,
                                            contentDescription = "Siguiente",
                                            tint = TextPrimary,
                                            modifier = Modifier.size(40.dp)
                                        )
                                    }

                                    IconButton(
                                        onClick = {
                                            player?.let {
                                                it.repeatMode = when (it.repeatMode) {
                                                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                                                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                                                    else -> Player.REPEAT_MODE_OFF
                                                }
                                            }
                                        },
                                        modifier = Modifier.size(48.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                                            contentDescription = "Repetir",
                                            tint = if (repeatMode != Player.REPEAT_MODE_OFF) activeAccent else TextSecondary,
                                            modifier = Modifier.size(28.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                ) { padding ->
                    if (filteredTracks.isEmpty()) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .padding(padding),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                if (tracks.isEmpty()) "Toca 'Carpeta' arriba para cargar tus MP3 y MP4"
                                else "No se encontraron canciones con ese nombre",
                                color = TextSecondary
                            )
                        }
                    } else {
                        LazyColumn(
                            Modifier
                                .fillMaxSize()
                                .padding(padding),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            itemsIndexed(filteredTracks) { index, track ->
                                val isCurrent = track.uri == currentUri
                                Surface(
                                    color = if (isCurrent) activeAccent.copy(alpha = 0.15f) else CardBg,
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            player?.let { mc ->
                                                val items = filteredTracks.map { t ->
                                                    MediaItem.Builder()
                                                        .setUri(t.uri)
                                                        .setMediaMetadata(MediaMetadata.Builder().setTitle(t.name).build())
                                                        .build()
                                                }
                                                mc.setMediaItems(items, index, 0L)
                                                mc.prepare()
                                                mc.play()
                                            }
                                        }
                                ) {
                                    Row(
                                        Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Rounded.MusicNote,
                                            contentDescription = null,
                                            tint = if (isCurrent) activeAccent else TextSecondary,
                                            modifier = Modifier.size(24.dp)
                                        )
                                        Spacer(Modifier.width(12.dp))
                                        Text(
                                            text = track.name,
                                            color = if (isCurrent) activeAccent else TextPrimary,
                                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                            fontSize = 15.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
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
}
