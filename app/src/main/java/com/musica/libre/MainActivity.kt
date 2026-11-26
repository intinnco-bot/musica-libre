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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors

data class AudioTrack(val name: String, val uri: Uri)

class MainActivity : ComponentActivity() {
    private var controller: MediaController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sessionToken = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()

        setContent {
            var player by remember { mutableStateOf<MediaController?>(null) }
            var tracks by remember { mutableStateOf<List<AudioTrack>>(emptyList()) }
            var isPlaying by remember { mutableStateOf(false) }
            var currentTitle by remember { mutableStateOf("Ninguna pista seleccionada") }
            var shuffleOn by remember { mutableStateOf(false) }

            DisposableEffect(Unit) {
                controllerFuture.addListener({
                    val mc = controllerFuture.get()
                    controller = mc
                    player = mc
                    isPlaying = mc.isPlaying
                    shuffleOn = mc.shuffleModeEnabled
                    currentTitle = mc.currentMediaItem?.mediaMetadata?.title?.toString() ?: currentTitle

                    mc.addListener(object : Player.Listener {
                        override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
                        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                            currentTitle = mediaItem?.mediaMetadata?.title?.toString() ?: "Sin título"
                        }
                        override fun onShuffleModeEnabledChanged(enabled: Boolean) { shuffleOn = enabled }
                    })
                }, MoreExecutors.directExecutor())
                onDispose { MediaController.releaseFuture(controllerFuture) }
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
                val saved = getSharedPreferences("prefs", Context.MODE_PRIVATE).getString("folder_uri", null)
                if (saved != null) loadFolder(Uri.parse(saved))
            }

            val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                if (uri != null) {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    getSharedPreferences("prefs", Context.MODE_PRIVATE).edit().putString("folder_uri", uri.toString()).apply()
                    loadFolder(uri)
                }
            }

            MaterialTheme(colorScheme = darkColorScheme()) {
                Scaffold(
                    topBar = {
                        Row(
                            Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Música Libre", style = MaterialTheme.typography.titleLarge)
                            Button(onClick = { folderPicker.launch(null) }) {
                                Text("Carpeta")
                            }
                        }
                    },
                    bottomBar = {
                        Surface(tonalElevation = 6.dp) {
                            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                Text(currentTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(8.dp))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                    TextButton(onClick = { player?.seekToPrevious() }) { Text("⏮ Ant") }
                                    Button(onClick = {
                                        player?.let { if (it.isPlaying) it.pause() else it.play() }
                                    }) { Text(if (isPlaying) "⏸ Pausa" else "▶ Play") }
                                    TextButton(onClick = { player?.seekToNext() }) { Text("Sig ⏭") }
                                    TextButton(onClick = {
                                        player?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
                                    }) { Text(if (shuffleOn) "🔀 ON" else "🔀 OFF") }
                                }
                            }
                        }
                    }
                ) { padding ->
                    if (tracks.isEmpty()) {
                        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                            Text("Toca 'Carpeta' para elegir dónde guardas tus MP3 y MP4.")
                        }
                    } else {
                        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                            itemsIndexed(tracks) { index, track ->
                                ListItem(
                                    headlineContent = { Text(track.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    modifier = Modifier.clickable {
                                        player?.let { mc ->
                                            val items = tracks.map { t ->
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
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }
}
