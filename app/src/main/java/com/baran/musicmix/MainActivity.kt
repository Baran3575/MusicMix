package com.baran.musicmix

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.baran.musicmix.audio.AudioAnalyzer
import com.baran.musicmix.audio.AudioMixer
import com.baran.musicmix.audio.DeckController
import com.baran.musicmix.audio.MixSettings
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MusicMixApp() }
    }
}

@Composable
fun MusicMixApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val deckA = remember { DeckController(context) }
    val deckB = remember { DeckController(context) }
    DisposableEffect(Unit) { onDispose { deckA.release(); deckB.release() } }

    var uriA by remember { mutableStateOf<Uri?>(null) }
    var uriB by remember { mutableStateOf<Uri?>(null) }
    var nameA by remember { mutableStateOf("Deck A — tap Pick") }
    var nameB by remember { mutableStateOf("Deck B — tap Pick") }

    var bpmA by remember { mutableStateOf(120f) }
    var bpmB by remember { mutableStateOf(120f) }
    var origA by remember { mutableStateOf(120f) }
    var origB by remember { mutableStateOf(120f) }
    var vocalA by remember { mutableStateOf(0.8f) }
    var musicA by remember { mutableStateOf(0.8f) }
    var vocalB by remember { mutableStateOf(0.8f) }
    var musicB by remember { mutableStateOf(0.8f) }
    var vocalPctA by remember { mutableStateOf<Int?>(null) }
    var vocalPctB by remember { mutableStateOf<Int?>(null) }
    var crossfade by remember { mutableStateOf(0.5f) }
    var masterBpm by remember { mutableStateOf(120f) }
    var analyzing by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Pick 2 songs from device storage.") }

    // permission (Android 13+)
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(
                    context, Manifest.permission.READ_MEDIA_AUDIO
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                // request is handled via launcher below on first pick; just note it
            }
        }
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    fun analyzeUri(which: String, uri: Uri, name: String?) {
        scope.launch {
            analyzing = true
            status = "Analyzing $which… (BPM + vocal detect)"
            try {
                val res = AudioAnalyzer.analyze(context, uri)
                if (which == "A") {
                    origA = res.bpm; bpmA = res.bpm
                    deckA.originalBpm = res.bpm
                    vocalPctA = res.vocalPct
                    vocalA = (res.vocalPct / 100f).coerceIn(0.2f, 1f)
                } else {
                    origB = res.bpm; bpmB = res.bpm
                    deckB.originalBpm = res.bpm
                    vocalPctB = res.vocalPct
                    vocalB = (res.vocalPct / 100f).coerceIn(0.2f, 1f)
                }
                masterBpm = (bpmA + bpmB) / 2f
                status = "$which: ${"%.0f".format(res.bpm)} BPM · vocal ${res.vocalPct}% / music ${res.musicPct}%"
            } catch (e: Exception) {
                status = "Analyze failed for $which: ${e.message}"
            }
            analyzing = false
        }
    }

    val pickA = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            uriA = uri
            nameA = uri.lastPathSegment?.takeLast(28) ?: "Track A"
            deckA.load(uri)
            analyzeUri("A", uri, nameA)
        }
    }
    val pickB = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            uriB = uri
            nameB = uri.lastPathSegment?.takeLast(28) ?: "Track B"
            deckB.load(uri)
            analyzeUri("B", uri, nameB)
        }
    }

    // apply live params whenever sliders move
    LaunchedEffect(bpmA, bpmB, masterBpm) {
        deckA.targetBpm = masterBpm; deckB.targetBpm = masterBpm
        deckA.applyTempo(); deckB.applyTempo()
    }
    LaunchedEffect(vocalA, musicA, crossfade) {
        deckA.vocalLevel = vocalA; deckA.musicLevel = musicA
        deckA.deckVolume = 1f
        deckA.player.volume =
            (kotlin.math.cos(crossfade * Math.PI.toFloat() / 2f) * musicA * (0.6f + 0.4f * vocalA))
                .coerceIn(0f, 1f)
    }
    LaunchedEffect(vocalB, musicB, crossfade) {
        deckB.vocalLevel = vocalB; deckB.musicLevel = musicB
        deckB.player.volume =
            (kotlin.math.sin(crossfade * Math.PI.toFloat() / 2f) * musicB * (0.6f + 0.4f * vocalB))
                .coerceIn(0f, 1f)
    }

    MaterialTheme {
        Column(
            Modifier.fillMaxSize().padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("MusicMix — offline DJ mixer", style = MaterialTheme.typography.headlineSmall)
            Text(status, style = MaterialTheme.typography.bodyMedium)
            if (analyzing) LinearProgressIndicator(Modifier.fillMaxWidth())

            DeckCard(
                title = "Deck A", trackName = nameA,
                detected = "orig ${"%.0f".format(origA)} BPM" +
                    (vocalPctA?.let { " · vocal $it% / music ${100 - it}%" } ?: ""),
                bpm = bpmA, onBpm = { bpmA = it },
                vocal = vocalA, onVocal = { vocalA = it },
                music = musicA, onMusic = { musicA = it },
                onPick = {
                    if (Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO)
                        != PackageManager.PERMISSION_GRANTED
                    ) permLauncher.launch(Manifest.permission.READ_MEDIA_AUDIO)
                    pickA.launch("audio/*")
                },
                onPlayPause = { if (deckA.player.isPlaying) deckA.pause() else deckA.play() },
                isPlaying = deckA.player.isPlaying
            )

            DeckCard(
                title = "Deck B", trackName = nameB,
                detected = "orig ${"%.0f".format(origB)} BPM" +
                    (vocalPctB?.let { " · vocal $it% / music ${100 - it}%" } ?: ""),
                bpm = bpmB, onBpm = { bpmB = it },
                vocal = vocalB, onVocal = { vocalB = it },
                music = musicB, onMusic = { musicB = it },
                onPick = {
                    if (Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO)
                        != PackageManager.PERMISSION_GRANTED
                    ) permLauncher.launch(Manifest.permission.READ_MEDIA_AUDIO)
                    pickB.launch("audio/*")
                },
                onPlayPause = { if (deckB.player.isPlaying) deckB.pause() else deckB.play() },
                isPlaying = deckB.player.isPlaying
            )

            Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Master / Crossfader")
                    Text("Master tempo: ${"%.0f".format(masterBpm)} BPM (both decks time-stretch to this)")
                    Slider(masterBpm, { masterBpm = it }, valueRange = 60f..180f)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { masterBpm = bpmA }) { Text("Use A") }
                        Button(onClick = { masterBpm = bpmB }) { Text("Use B") }
                        Button(onClick = { masterBpm = (bpmA + bpmB) / 2 }) { Text("Avg") }
                    }
                    Text("Crossfade: ${if (crossfade < 0.4) "more A" else if (crossfade > 0.6) "more B" else "center"}")
                    Slider(crossfade, { crossfade = it }, valueRange = 0f..1f)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            deckA.play(); deckB.play()
                        }) { Text("Play both") }
                        Button(onClick = {
                            deckA.pause(); deckB.pause()
                        }) { Text("Stop both") }
                    }
                }
            }

            Button(
                onClick = {
                    if (uriA == null && uriB == null) {
                        Toast.makeText(context, "Pick at least one song first", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    scope.launch {
                        exporting = true
                        status = "Exporting mix…"
                        try {
                            val file = AudioMixer.exportMix(
                                context, uriA, uriB,
                                MixSettings(
                                    bpmA = bpmA, bpmB = bpmB, masterBpm = masterBpm,
                                    vocalA = vocalA, musicA = musicA,
                                    vocalB = vocalB, musicB = musicB,
                                    crossfade = crossfade
                                )
                            )
                            status = "Saved: ${file.absolutePath}"
                            Toast.makeText(context, "Saved: ${file.name}", Toast.LENGTH_LONG).show()
                        } catch (e: Exception) {
                            status = "Export failed: ${e.message}"
                        }
                        exporting = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !exporting
            ) {
                Text(if (exporting) "Saving…" else "Save Mix (WAV)")
            }
            Text(
                "How it works (offline): BPM = onset-autocorrelation 60–180. " +
                    "Vocal/Music = 300–3400 Hz band-energy ratio. " +
                    "Live sliders tilt preview volume; Save re-renders with 3-band split.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
fun DeckCard(
    title: String, trackName: String, detected: String,
    bpm: Float, onBpm: (Float) -> Unit,
    vocal: Float, onVocal: (Float) -> Unit,
    music: Float, onMusic: (Float) -> Unit,
    onPick: () -> Unit, onPlayPause: () -> Unit, isPlaying: Boolean
) {
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("$title — $trackName", style = MaterialTheme.typography.titleMedium)
            Text(detected, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPick) { Text("Pick song") }
                Button(onClick = onPlayPause) { Text(if (isPlaying) "Pause" else "Play") }
            }
            Text("BPM (tempo): ${"%.0f".format(bpm)}")
            Slider(bpm, onBpm, valueRange = 60f..180f)
            Text("Vocal volume: ${(vocal * 100).toInt()}%")
            Slider(vocal, onVocal, valueRange = 0f..1f)
            Text("Music volume: ${(music * 100).toInt()}%")
            Slider(music, onMusic, valueRange = 0f..1f)
        }
    }
}
