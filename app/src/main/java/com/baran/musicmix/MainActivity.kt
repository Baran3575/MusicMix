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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.baran.musicmix.audio.AudioAnalyzer
import com.baran.musicmix.audio.AudioMixer
import com.baran.musicmix.audio.DeckController
import com.baran.musicmix.audio.MixSettings
import kotlinx.coroutines.launch

private val Bg = Color(0xFF0B0E1A)
private val CardBg = Color(0xFF151B30)
private val AccA = Color(0xFF00E5FF)
private val AccB = Color(0xFFFF2E63)
private val Muted = Color(0xFF8B93B0)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MusicMixApp() }
    }
}

private fun tapBpm(taps: List<Long>): Float? {
    if (taps.size < 2) return null
    val recent = taps.takeLast(8)
    val intervals = recent.zipWithNext { a, b -> b - a }.filter { it in 200..2000 }
    if (intervals.size < 2) return null
    val sorted = intervals.sorted()
    val median = sorted[sorted.size / 2]
    return (60000f / median).coerceIn(60f, 200f)
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
    var nameA by remember { mutableStateOf("No track loaded") }
    var nameB by remember { mutableStateOf("No track loaded") }

    var bpmA by remember { mutableStateOf(120f) }
    var bpmB by remember { mutableStateOf(120f) }
    var confA by remember { mutableStateOf<Float?>(null) }
    var confB by remember { mutableStateOf<Float?>(null) }
    var vocalA by remember { mutableStateOf(0.8f) }
    var musicA by remember { mutableStateOf(0.8f) }
    var vocalB by remember { mutableStateOf(0.8f) }
    var musicB by remember { mutableStateOf(0.8f) }
    var vocalPctA by remember { mutableStateOf<Int?>(null) }
    var vocalPctB by remember { mutableStateOf<Int?>(null) }
    var waveA by remember { mutableStateOf<List<Float>>(emptyList()) }
    var waveB by remember { mutableStateOf<List<Float>>(emptyList()) }
    var playingA by remember { mutableStateOf(false) }
    var playingB by remember { mutableStateOf(false) }
    var busyA by remember { mutableStateOf(false) }
    var busyB by remember { mutableStateOf(false) }

    var crossfade by remember { mutableStateOf(0.5f) }
    var masterBpm by remember { mutableStateOf(124f) }
    var exporting by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Load two tracks to start mixing") }

    val tapsA = remember { mutableStateListOf<Long>() }
    val tapsB = remember { mutableStateListOf<Long>() }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    fun ensureAudioPerm() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) permLauncher.launch(Manifest.permission.READ_MEDIA_AUDIO)
    }

    fun analyzeDeck(which: String, uri: Uri) {
        scope.launch {
            if (which == "A") busyA = true else busyB = true
            status = "Analyzing $which… spectral flux + groove lock"
            try {
                val res = AudioAnalyzer.analyze(context, uri)
                if (which == "A") {
                    bpmA = res.bpm
                    deckA.originalBpm = res.bpm
                    confA = res.bpmConfidence
                    vocalPctA = res.vocalPct
                    vocalA = (res.vocalPct / 100f).coerceIn(0.2f, 1f)
                    waveA = res.preview.toList()
                } else {
                    bpmB = res.bpm
                    deckB.originalBpm = res.bpm
                    confB = res.bpmConfidence
                    vocalPctB = res.vocalPct
                    vocalB = (res.vocalPct / 100f).coerceIn(0.2f, 1f)
                    waveB = res.preview.toList()
                }
                if (uriA != null && uriB != null) masterBpm = (bpmA + bpmB) / 2f
                else masterBpm = if (which == "A") bpmA else bpmB
                val confPct = (res.bpmConfidence * 100).toInt()
                status = "$which locked: ${"%.1f".format(res.bpm)} BPM ($confPct%) · vox ${res.vocalPct}%"
            } catch (e: Exception) {
                status = "Analyze failed ($which): ${e.message}"
            }
            if (which == "A") busyA = false else busyB = false
        }
    }

    val pickA = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            uriA = uri
            nameA = uri.lastPathSegment?.takeLast(32) ?: "Track A"
            deckA.load(uri)
            playingA = false
            tapsA.clear()
            analyzeDeck("A", uri)
        }
    }
    val pickB = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            uriB = uri
            nameB = uri.lastPathSegment?.takeLast(32) ?: "Track B"
            deckB.load(uri)
            playingB = false
            tapsB.clear()
            analyzeDeck("B", uri)
        }
    }

    LaunchedEffect(masterBpm) {
        deckA.targetBpm = masterBpm; deckB.targetBpm = masterBpm
        deckA.applyTempo(); deckB.applyTempo()
    }
    LaunchedEffect(vocalA, musicA, crossfade) {
        deckA.vocalLevel = vocalA; deckA.musicLevel = musicA
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

    val scheme = darkColorScheme(
        background = Bg, surface = CardBg, surfaceVariant = Color(0xFF1E2542),
        primary = AccA, secondary = AccB, onBackground = Color.White,
        onSurface = Color.White
    )
    MaterialTheme(colorScheme = scheme) {
        Surface(Modifier.fillMaxSize(), color = Bg) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // header
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("MIXLAB", fontSize = 22.sp, fontWeight = FontWeight.Black,
                            letterSpacing = 2.sp)
                        Text("offline dj mixer", color = Muted, fontSize = 12.sp)
                    }
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(
                                when {
                                    exporting -> "EXPORTING…"
                                    busyA || busyB -> "ANALYZING…"
                                    else -> "READY"
                                }, fontSize = 11.sp, fontWeight = FontWeight.Bold
                            )
                        }
                    )
                }
                if (busyA || busyB || exporting) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(status, color = Muted, fontSize = 12.sp, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)

                DeckPanel(
                    tag = "A", accent = AccA, trackName = nameA, busy = busyA,
                    bpm = bpmA, conf = confA, wave = waveA,
                    vocalPct = vocalPctA, vocal = vocalA, music = musicA,
                    playing = playingA, masterBpm = masterBpm,
                    onPick = { ensureAudioPerm(); pickA.launch("audio/*") },
                    onPlayPause = {
                        if (playingA) { deckA.pause(); playingA = false }
                        else { deckA.play(); playingA = true }
                    },
                    onBpm = { bpmA = it; deckA.originalBpm = it },
                    onDouble = { bpmA = (bpmA * 2).coerceIn(60f, 200f); deckA.originalBpm = bpmA },
                    onHalf = { bpmA = (bpmA / 2).coerceIn(60f, 200f); deckA.originalBpm = bpmA },
                    onTap = {
                        tapsA.add(System.currentTimeMillis())
                        tapBpm(tapsA)?.let { bpmA = it; deckA.originalBpm = it }
                    },
                    onReanalyze = { uriA?.let { analyzeDeck("A", it) } },
                    onVocal = { vocalA = it }, onMusic = { musicA = it }
                )

                DeckPanel(
                    tag = "B", accent = AccB, trackName = nameB, busy = busyB,
                    bpm = bpmB, conf = confB, wave = waveB,
                    vocalPct = vocalPctB, vocal = vocalB, music = musicB,
                    playing = playingB, masterBpm = masterBpm,
                    onPick = { ensureAudioPerm(); pickB.launch("audio/*") },
                    onPlayPause = {
                        if (playingB) { deckB.pause(); playingB = false }
                        else { deckB.play(); playingB = true }
                    },
                    onBpm = { bpmB = it; deckB.originalBpm = it },
                    onDouble = { bpmB = (bpmB * 2).coerceIn(60f, 200f); deckB.originalBpm = bpmB },
                    onHalf = { bpmB = (bpmB / 2).coerceIn(60f, 200f); deckB.originalBpm = bpmB },
                    onTap = {
                        tapsB.add(System.currentTimeMillis())
                        tapBpm(tapsB)?.let { bpmB = it; deckB.originalBpm = it }
                    },
                    onReanalyze = { uriB?.let { analyzeDeck("B", it) } },
                    onVocal = { vocalB = it }, onMusic = { musicB = it }
                )

                // master / transition
                Card(colors = CardDefaults.cardColors(containerColor = CardBg),
                    shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("TRANSITION", fontWeight = FontWeight.Black,
                            letterSpacing = 2.sp, fontSize = 13.sp, color = Muted)
                        Row(Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("MASTER", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text("${"%.1f".format(masterBpm)} BPM", fontSize = 20.sp,
                                fontWeight = FontWeight.Black)
                        }
                        Slider(masterBpm, { masterBpm = it }, valueRange = 60f..200f)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { masterBpm = bpmA },
                                modifier = Modifier.weight(1f)) { Text("A ${"%.0f".format(bpmA)}") }
                            OutlinedButton(onClick = { masterBpm = (bpmA + bpmB) / 2 },
                                modifier = Modifier.weight(1f)) { Text("Split") }
                            OutlinedButton(onClick = { masterBpm = bpmB },
                                modifier = Modifier.weight(1f)) { Text("B ${"%.0f".format(bpmB)}") }
                        }
                        Row(Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("A", color = AccA, fontWeight = FontWeight.Black)
                            Text(
                                when {
                                    crossfade < 0.35 -> "◀ A HEAVY"
                                    crossfade > 0.65 -> "B HEAVY ▶"
                                    else -> "CENTER"
                                }, color = Muted, fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text("B", color = AccB, fontWeight = FontWeight.Black)
                        }
                        Slider(crossfade, { crossfade = it }, valueRange = 0f..1f)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                deckA.play(); deckB.play()
                                playingA = true; playingB = true
                            }, modifier = Modifier.weight(1f)) { Text("▶ Both") }
                            OutlinedButton(onClick = { crossfade = 0.5f },
                                modifier = Modifier.weight(1f)) { Text("Center") }
                            OutlinedButton(onClick = {
                                deckA.pause(); deckB.pause()
                                playingA = false; playingB = false
                            }, modifier = Modifier.weight(1f)) { Text("❚❚ Stop") }
                        }
                    }
                }

                Button(
                    onClick = {
                        if (uriA == null && uriB == null) {
                            Toast.makeText(context, "Load at least one track first",
                                Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        scope.launch {
                            exporting = true
                            status = "Rendering mix at ${"%.1f".format(masterBpm)} BPM…"
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
                                status = "Saved ✓ ${file.name} → Music/MusicMix"
                                Toast.makeText(context, "Saved: ${file.name}",
                                    Toast.LENGTH_LONG).show()
                            } catch (e: Exception) {
                                status = "Export failed: ${e.message}"
                            }
                            exporting = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    enabled = !exporting,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(if (exporting) "RENDERING…" else "⬇  SAVE MIX",
                        fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                }
                Text(
                    "BPM engine: log-spectral-flux onset → autocorrelation + comb " +
                        "(60–200, ±octave guard) on the track's stable middle. " +
                        "Wrong? Tap tempo or ×2 ÷2 fixes it — sliders are the truth.",
                    color = Muted, fontSize = 11.sp
                )
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
fun DeckPanel(
    tag: String, accent: Color, trackName: String, busy: Boolean,
    bpm: Float, conf: Float?, wave: List<Float>,
    vocalPct: Int?, vocal: Float, music: Float,
    playing: Boolean, masterBpm: Float,
    onPick: () -> Unit, onPlayPause: () -> Unit,
    onBpm: (Float) -> Unit, onDouble: () -> Unit, onHalf: () -> Unit,
    onTap: () -> Unit, onReanalyze: () -> Unit,
    onVocal: (Float) -> Unit, onMusic: (Float) -> Unit
) {
    val stretch = (masterBpm / bpm.coerceAtLeast(1f) - 1) * 100
    Card(colors = CardDefaults.cardColors(containerColor = CardBg),
        shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(color = accent, shape = RoundedCornerShape(8.dp)) {
                    Text(" $tag ", color = Color.Black, fontWeight = FontWeight.Black)
                }
                Text(trackName, modifier = Modifier.weight(1f), maxLines = 1,
                    overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                OutlinedButton(onClick = onPick) { Text("Load") }
            }

            Waveform(wave = wave, accent = accent,
                modifier = Modifier.fillMaxWidth().height(64.dp))

            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPlayPause, modifier = Modifier.weight(1f)) {
                    Text(if (playing) "❚❚ Pause" else "▶ Play")
                }
                Surface(color = Color(0xFF0B0E1A), shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${"%.1f".format(bpm)}",
                            fontWeight = FontWeight.Black, fontSize = 18.sp)
                        Text(
                            "BPM" + (conf?.let { " · ${(it * 100).toInt()}%" } ?: ""),
                            color = Muted, fontSize = 10.sp
                        )
                    }
                }
            }

            // tempo correction row
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = onHalf, modifier = Modifier.weight(1f)) { Text("÷2") }
                OutlinedButton(onClick = onTap, modifier = Modifier.weight(1f)) { Text("TAP") }
                OutlinedButton(onClick = onDouble, modifier = Modifier.weight(1f)) { Text("×2") }
                OutlinedButton(onClick = onReanalyze, modifier = Modifier.weight(1f),
                    enabled = !busy) { Text(if (busy) "…" else "↻") }
            }

            Column {
                Row(Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("TEMPO", color = Muted, fontSize = 11.sp,
                        fontWeight = FontWeight.Bold)
                    Text(
                        "${if (stretch >= 0) "+" else ""}${"%.1f".format(stretch)}% → ${"%.1f".format(masterBpm)}",
                        fontSize = 11.sp, color = Muted
                    )
                }
                Slider(bpm, onBpm, valueRange = 60f..200f)
            }
            MixRow("VOX", "vocal", vocalPct?.let { "$it% in track" } ?: "auto",
                vocal, accent, onVocal)
            MixRow("MUS", "music", "${(music * 100).toInt()}%",
                music, accent, onMusic)
        }
    }
}

@Composable
fun MixRow(
    code: String, label: String, value: String,
    level: Float, accent: Color, onLevel: (Float) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(color = accent.copy(alpha = 0.15f),
            shape = RoundedCornerShape(6.dp)) {
            Text(" $code ", color = accent, fontSize = 11.sp,
                fontWeight = FontWeight.Black)
        }
        Text(label, modifier = Modifier.width(44.dp), color = Muted, fontSize = 11.sp)
        Slider(level, onLevel, valueRange = 0f..1f,
            modifier = Modifier.weight(1f))
        Text(value, fontSize = 11.sp, modifier = Modifier.width(64.dp),
            color = Muted)
    }
}

@Composable
fun Waveform(wave: List<Float>, accent: Color, modifier: Modifier = Modifier) {
    Surface(color = Color(0xFF0B0E1A), shape = RoundedCornerShape(12.dp),
        modifier = modifier) {
        if (wave.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("waveform appears after analysis", color = Muted, fontSize = 11.sp)
            }
        } else {
            Canvas(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 6.dp)) {
                val n = wave.size
                val gap = size.width / n
                val barW = (gap * 0.62f).coerceAtLeast(1f)
                for (i in 0 until n) {
                    val h = (wave[i] * size.height).coerceAtLeast(2f)
                    drawRoundRect(
                        color = accent.copy(alpha = 0.85f),
                        topLeft = androidx.compose.ui.geometry.Offset(
                            i * gap, (size.height - h) / 2
                        ),
                        size = androidx.compose.ui.geometry.Size(barW, h),
                        cornerRadius = CornerRadius(2f, 2f)
                    )
                }
            }
        }
    }
}
