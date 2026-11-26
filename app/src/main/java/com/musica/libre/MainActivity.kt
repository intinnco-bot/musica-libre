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
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import kotlinx.coroutines.launch

data class AudioTrack(val name: String, val uri: Uri)

data class AppThemePalette(
    val id: String,
    val label: String,
    val bg: Color,
    val card: Color,
    val accent: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val onAccent: Color,
    val isLight: Boolean = false
)

private val AppThemes = listOf(
    AppThemePalette("verde", "Verde", Color(0xFF0F172A), Color(0xFF1E293B), Color(0xFF10B981), Color(0xFFF8FAFC), Color(0xFF94A3B8), Color(0xFF0F172A)),
    AppThemePalette("azul", "Azul", Color(0xFF0F172A), Color(0xFF1E293B), Color(0xFF38BDF8), Color(0xFFF8FAFC), Color(0xFF94A3B8), Color(0xFF0F172A)),
    AppThemePalette("negro", "Negro", Color(0xFF000000), Color(0xFF161616), Color(0xFFE2E8F0), Color(0xFFFFFFFF), Color(0xFF9CA3AF), Color(0xFF000000)),
    AppThemePalette("rosado", "Rosado", Color(0xFF1A0F17), Color(0xFF2D1B27), Color(0xFFF472B6), Color(0xFFFDF2F8), Color(0xFFC084A5), Color(0xFF1A0F17)),
    AppThemePalette("vinotinto", "Vinotinto", Color(0xFF1A090D), Color(0xFF2E1219), Color(0xFF9F1239), Color(0xFFFFF1F2), Color(0xFFD69BA6), Color(0xFFFFFFFF)),
    AppThemePalette("fucsia", "Fucsia", Color(0xFF190A1F), Color(0xFF2D1438), Color(0xFFD946EF), Color(0xFFFAE8FF), Color(0xFFC084FC), Color(0xFF190A1F)),
    AppThemePalette("blanco", "Blanco", Color(0xFFF8FAFC), Color(0xFFFFFFFF), Color(0xFF2563EB), Color(0xFF0F172A), Color(0xFF64748B), Color(0xFFFFFFFF), isLight = true)
)

fun formatTime(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

@Composable
fun AnimatedEqualizer(
    isPlaying: Boolean,
    accentColor: Color,
    barCount: Int = 20,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "eq")
    val barFactors = List(barCount) { index ->
        val duration = 350 + (index * 73) % 450
        val minVal = 0.12f + (index % 3) * 0.05f
        val maxVal = 0.55f + ((index * 17) % 45) / 100f
        infiniteTransition.animateFloat(
            initialValue = minVal,
            targetValue = maxVal,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = duration, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "bar_$index"
        )
    }

    Row(
        modifier = modifier.height(90.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        barFactors.forEachIndexed { i, animState ->
            val fraction = if (isPlaying) animState.value else 0.12f
            val isCenter = i in (barCount / 3)..(2 * barCount / 3)
            Box(
                modifier = Modifier
                    .width(6.dp)
                    .fillMaxHeight(fraction)
                    .clip(RoundedCornerShape(50))
                    .background(if (isCenter) accentColor else accentColor.copy(alpha = 0.65f))
            )
        }
    }
}

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalFoundationApi::class)
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

            var selectedThemeId by remember { mutableStateOf(prefs.getString("theme_id", "verde") ?: "verde") }
            var showThemeDialog by remember { mutableStateOf(false) }
            val theme = AppThemes.find { it.id == selectedThemeId } ?: AppThemes.first()

            val pagerState = rememberPagerState(pageCount = { 2 })
            val coroutineScope = rememberCoroutineScope()

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

            val colorScheme = if (theme.isLight) {
                lightColorScheme(
                    primary = theme.accent,
                    onPrimary = theme.onAccent,
                    background = theme.bg,
                    surface = theme.card,
                    onBackground = theme.textPrimary,
                    onSurface = theme.textPrimary
                )
            } else {
                darkColorScheme(
                    primary = theme.accent,
                    onPrimary = theme.onAccent,
                    background = theme.bg,
                    surface = theme.card,
                    onBackground = theme.textPrimary,
                    onSurface = theme.textPrimary
                )
            }

            MaterialTheme(colorScheme = colorScheme) {
                Scaffold(
                    containerColor = theme.bg,
                    topBar = {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(theme.bg)
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Logo intinn co en lugar del nombre
                            Surface(
                                color = Color.White,
                                shape = RoundedCornerShape(10.dp),
                                shadowElevation = 2.dp
                            ) {
                                Image(
                                    painter = painterResource(id = R.drawable.logo_intinn),
                                    contentDescription = "Intinn co",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier
                                        .height(38.dp)
                                        .widthIn(max = 125.dp)
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                IconButton(
                                    onClick = { showThemeDialog = true },
                                    modifier = Modifier
                                        .size(42.dp)
                                        .clip(CircleShape)
                                        .background(theme.card)
                                ) {
                                    Icon(
                                        Icons.Rounded.Palette,
                                        contentDescription = "Cambiar color",
                                        tint = theme.accent,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }

                                FilledTonalButton(
                                    onClick = { folderPicker.launch(null) },
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = theme.card,
                                        contentColor = theme.accent
                                    ),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                                ) {
                                    Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Carpeta")
                                }
                            }
                        }
                    },
                    bottomBar = {
                        // Barra de navegación inferior para alternar entre Biblioteca y Reproductor
                        NavigationBar(containerColor = theme.card) {
                            NavigationBarItem(
                                selected = pagerState.currentPage == 0,
                                onClick = { coroutineScope.launch { pagerState.animateScrollToPage(0) } },
                                icon = { Icon(Icons.Rounded.QueueMusic, contentDescription = "Biblioteca") },
                                label = { Text("Biblioteca") },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = theme.onAccent,
                                    selectedTextColor = theme.accent,
                                    indicatorColor = theme.accent,
                                    unselectedIconColor = theme.textSecondary,
                                    unselectedTextColor = theme.textSecondary
                                )
                            )
                            NavigationBarItem(
                                selected = pagerState.currentPage == 1,
                                onClick = { coroutineScope.launch { pagerState.animateScrollToPage(1) } },
                                icon = { Icon(Icons.Rounded.GraphicEq, contentDescription = "Reproductor") },
                                label = { Text("Reproductor") },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = theme.onAccent,
                                    selectedTextColor = theme.accent,
                                    indicatorColor = theme.accent,
                                    unselectedIconColor = theme.textSecondary,
                                    unselectedTextColor = theme.textSecondary
                                )
                            )
                        }
                    }
                ) { padding ->
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                    ) { page ->
                        if (page == 0) {
                            // PANTALLA 1: BIBLIOTECA Y BUSCADOR
                            Column(Modifier.fillMaxSize()) {
                                OutlinedTextField(
                                    value = searchQuery,
                                    onValueChange = { searchQuery = it },
                                    placeholder = { Text("Buscar canción (${tracks.size} pistas)...", color = theme.textSecondary) },
                                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = theme.accent) },
                                    trailingIcon = {
                                        if (searchQuery.isNotEmpty()) {
                                            IconButton(onClick = { searchQuery = "" }) {
                                                Icon(Icons.Rounded.Close, contentDescription = "Limpiar", tint = theme.textSecondary)
                                            }
                                        }
                                    },
                                    singleLine = true,
                                    shape = RoundedCornerShape(14.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedContainerColor = theme.card,
                                        unfocusedContainerColor = theme.card,
                                        focusedBorderColor = theme.accent,
                                        unfocusedBorderColor = Color.Transparent,
                                        focusedTextColor = theme.textPrimary,
                                        unfocusedTextColor = theme.textPrimary
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 6.dp)
                                )

                                if (filteredTracks.isEmpty()) {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text(
                                            if (tracks.isEmpty()) "Toca 'Carpeta' arriba para cargar tus MP3 y MP4"
                                            else "No se encontraron canciones",
                                            color = theme.textSecondary
                                        )
                                    }
                                } else {
                                    LazyColumn(
                                        Modifier.fillMaxSize(),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        itemsIndexed(filteredTracks) { index, track ->
                                            val isCurrent = track.uri == currentUri
                                            Surface(
                                                color = if (isCurrent) theme.accent.copy(alpha = 0.16f) else theme.card,
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
                                                            coroutineScope.launch { pagerState.animateScrollToPage(1) }
                                                        }
                                                    }
                                            ) {
                                                Row(
                                                    Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Icon(
                                                        if (isCurrent && isPlaying) Icons.Rounded.GraphicEq else Icons.Rounded.MusicNote,
                                                        contentDescription = null,
                                                        tint = if (isCurrent) theme.accent else theme.textSecondary,
                                                        modifier = Modifier.size(24.dp)
                                                    )
                                                    Spacer(Modifier.width(12.dp))
                                                    Text(
                                                        text = track.name,
                                                        color = if (isCurrent) theme.accent else theme.textPrimary,
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
                        } else {
                            // PANTALLA 2: REPRODUCTOR CON ECUALIZADOR EN MOVIMIENTO
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 24.dp, vertical = 16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.SpaceBetween
                            ) {
                                // Disco visual superior
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(160.dp)
                                        .clip(CircleShape)
                                        .background(theme.card)
                                        .border(3.dp, theme.accent.copy(alpha = 0.6f), CircleShape)
                                ) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .size(56.dp)
                                            .clip(CircleShape)
                                            .background(theme.bg)
                                            .border(2.dp, theme.accent, CircleShape)
                                    ) {
                                        Icon(
                                            Icons.Rounded.MusicNote,
                                            contentDescription = null,
                                            tint = theme.accent,
                                            modifier = Modifier.size(28.dp)
                                        )
                                    }
                                }

                                // Ecualizador en movimiento en la mitad de la pantalla
                                Surface(
                                    color = theme.card,
                                    shape = RoundedCornerShape(24.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 20.dp, horizontal = 12.dp)
                                    ) {
                                        AnimatedEqualizer(
                                            isPlaying = isPlaying,
                                            accentColor = theme.accent
                                        )
                                    }
                                }

                                // Título, barra de progreso y botones grandes
                                Column(
                                    Modifier.fillMaxWidth(),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = currentTitle,
                                        color = theme.textPrimary,
                                        fontSize = 19.sp,
                                        fontWeight = FontWeight.Bold,
                                        textAlign = TextAlign.Center,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )

                                    Spacer(Modifier.height(12.dp))

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
                                            thumbColor = theme.accent,
                                            activeTrackColor = theme.accent,
                                            inactiveTrackColor = theme.card
                                        )
                                    )
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(formatTime(currentPos), fontSize = 12.sp, color = theme.textSecondary)
                                        Text(formatTime(duration), fontSize = 12.sp, color = theme.textSecondary)
                                    }

                                    Spacer(Modifier.height(16.dp))

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
                                                tint = if (shuffleOn) theme.accent else theme.textSecondary,
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
                                                tint = theme.textPrimary,
                                                modifier = Modifier.size(42.dp)
                                            )
                                        }

                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier
                                                .size(76.dp)
                                                .clip(CircleShape)
                                                .background(theme.accent)
                                                .clickable {
                                                    player?.let { if (it.isPlaying) it.pause() else it.play() }
                                                }
                                        ) {
                                            Icon(
                                                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                                contentDescription = "Play o Pausa",
                                                tint = theme.onAccent,
                                                modifier = Modifier.size(46.dp)
                                            )
                                        }

                                        IconButton(
                                            onClick = { player?.seekToNext() },
                                            modifier = Modifier.size(56.dp)
                                        ) {
                                            Icon(
                                                Icons.Rounded.SkipNext,
                                                contentDescription = "Siguiente",
                                                tint = theme.textPrimary,
                                                modifier = Modifier.size(42.dp)
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
                                                tint = if (repeatMode != Player.REPEAT_MODE_OFF) theme.accent else theme.textSecondary,
                                                modifier = Modifier.size(28.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Diálogo selector de los 7 colores
                if (showThemeDialog) {
                    AlertDialog(
                        onDismissRequest = { showThemeDialog = false },
                        containerColor = theme.card,
                        title = { Text("Elige un color", color = theme.textPrimary, fontWeight = FontWeight.Bold) },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                AppThemes.forEach { option ->
                                    val isSelected = option.id == selectedThemeId
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(if (isSelected) option.accent.copy(alpha = 0.2f) else theme.bg)
                                            .clickable {
                                                selectedThemeId = option.id
                                                prefs.edit().putString("theme_id", option.id).apply()
                                                showThemeDialog = false
                                            }
                                            .padding(horizontal = 14.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(
                                                modifier = Modifier
                                                    .size(24.dp)
                                                    .clip(CircleShape)
                                                    .background(if (option.id == "blanco") Color.White else if (option.id == "negro") Color.Black else option.accent)
                                                    .border(1.5.dp, theme.textSecondary, CircleShape)
                                            )
                                            Spacer(Modifier.width(12.dp))
                                            Text(option.label, color = theme.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                        }
                                        if (isSelected) {
                                            Icon(Icons.Rounded.Check, contentDescription = null, tint = option.accent)
                                        }
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showThemeDialog = false }) {
                                Text("Cerrar", color = theme.accent)
                            }
                        }
                    )
                }
            }
        }
    }
}
