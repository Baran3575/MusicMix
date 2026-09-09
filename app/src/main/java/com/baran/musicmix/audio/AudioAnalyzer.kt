package com.baran.musicmix.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

data class TrackAnalysis(
    val bpm: Float,
    val bpmConfidence: Float, // 0..1
    val vocalPct: Int,
    val musicPct: Int,
    val preview: FloatArray,  // 120 waveform bars 0..1
    val durationMs: Long = 0L
)

/**
 * Offline auto-detect v2.
 * - Decodes up to 150 s to mono 22050 Hz.
 * - Analysis window skips the intro/outro (15 s .. 135 s) where beats are unstable.
 * - BPM: multi-band log-spectral-flux onset envelope (1024 FFT, hop 256, ~86 fps)
 *   + normalized autocorrelation + comb weighting + octave correction +
 *   parabolic sub-BPM refinement. Returns fractional BPM + confidence.
 * - Vocal/Music: 300..3400 Hz band-energy ratio (DSP approximation, not ML stems).
 */
object AudioAnalyzer {

    suspend fun analyze(context: Context, uri: Uri): TrackAnalysis =
        withContext(Dispatchers.Default) {
            val pcm = decodeMono22050(context, uri, maxSeconds = 150)
            if (pcm.size < 22050 * 5) return@withContext TrackAnalysis(
                120f, 0f, 50, 50, previewBars(pcm)
            )
            // stable middle window: skip intro first 15 s, cap at 135 s
            val sr = 22050
            val start = minOf(15 * sr, pcm.size / 3)
            val end = minOf(pcm.size, 135 * sr)
            val window = if (end - start > sr * 20) pcm.copyOfRange(start, end) else pcm
            val (bpm, conf) = detectBpm(window, sr)
            val (vocal, music) = detectVocalMusicBalance(window)
            TrackAnalysis(bpm, conf, vocal, music, previewBars(pcm))
        }

    fun decodeMono22050(context: Context, uri: Uri, maxSeconds: Int): FloatArray {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            var trackIdx = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) { trackIdx = i; format = f; break }
            }
            if (trackIdx < 0 || format == null) return FloatArray(0)
            extractor.selectTrack(trackIdx)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            val srcRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE))
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            val srcCh = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2

            val out = ArrayList<Float>(22050 * 30)
            val maxSamples = maxSeconds * 22050
            val info = MediaCodec.BufferInfo()
            var eos = false
            var resamplePos = 0.0
            val step = srcRate.toDouble() / 22050.0
            val srcBlock = ArrayList<Float>(8192)

            while (!eos && out.size < maxSamples) {
                val inIdx = codec.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val buf = codec.getInputBuffer(inIdx)!!
                    val n = extractor.readSampleData(buf, 0)
                    if (n < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    } else {
                        codec.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                if (outIdx >= 0) {
                    val buf = codec.getOutputBuffer(outIdx)!!
                    if (info.size > 0) {
                        val bytes = ByteArray(info.size)
                        buf.get(bytes)
                        var i = 0
                        while (i + 1 < bytes.size) {
                            var chSum = 0
                            var chCount = 0
                            var c = 0
                            while (c < srcCh && i + 1 < bytes.size) {
                                val lo = bytes[i].toInt() and 0xFF
                                val hi = bytes[i + 1].toInt()
                                val s = (hi shl 8) or lo
                                chSum += s
                                chCount++
                                i += 2; c++
                            }
                            if (chCount > 0) srcBlock.add((chSum / chCount) / 32768f)
                        }
                        while (true) {
                            val idx = resamplePos.toInt()
                            if (idx + 1 >= srcBlock.size) break
                            val frac = (resamplePos - idx).toFloat()
                            out.add(srcBlock[idx] * (1 - frac) + srcBlock[idx + 1] * frac)
                            resamplePos += step
                            if (out.size >= maxSamples) break
                        }
                        val consumed = resamplePos.toInt()
                        if (consumed > 0) {
                            val drop = minOf(consumed, srcBlock.size)
                            repeat(drop) { srcBlock.removeAt(0) }
                            resamplePos -= consumed
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eos = true
                }
            }
            codec.stop(); codec.release()
            return out.toFloatArray()
        } catch (_: Exception) {
            return FloatArray(0)
        } finally {
            extractor.release()
        }
    }

    /** Returns (bpm, confidence). Range 60..200, fractional. */
    fun detectBpm(pcm: FloatArray, sampleRate: Int): Pair<Float, Float> {
        val win = 1024
        val hop = 256
        val fps = sampleRate.toFloat() / hop
        val nFrames = (pcm.size - win) / hop
        if (nFrames < 200) return 120f to 0f

        // Hann window
        val hann = FloatArray(win) { i ->
            (0.5 * (1 - cos(2 * Math.PI * i / (win - 1)))).toFloat()
        }
        // frequency weights: emphasize 80..5000 Hz onset content
        val binHz = FloatArray(win / 2) { i -> i * sampleRate.toFloat() / win }
        val weight = FloatArray(win / 2) { i ->
            val f = binHz[i]
            when {
                f < 60 -> 0.15f
                f < 12000 -> 1f
                else -> 0.4f
            }.let { w ->
                // extra kick emphasis 50..150 Hz
                if (f in 50f..150f) w * 1.6f else w
            }
        }

        val re = FloatArray(win)
        val im = FloatArray(win)
        var prev = FloatArray(win / 2)
        var hasPrev = false
        val flux = FloatArray(nFrames)
        val mag = FloatArray(win / 2)

        for (f in 0 until nFrames) {
            val base = f * hop
            for (i in 0 until win) {
                re[i] = pcm[base + i] * hann[i]
                im[i] = 0f
            }
            fft(re, im)
            for (i in 0 until win / 2) {
                mag[i] = sqrt(re[i] * re[i] + im[i] * im[i])
            }
            if (hasPrev) {
                var s = 0f
                for (i in 1 until win / 2) {
                    val d = ln(1 + 60 * mag[i]) - ln(1 + 60 * prev[i])
                    if (d > 0) s += d * weight[i]
                }
                flux[f] = s
            }
            System.arraycopy(mag, 0, prev, 0, win / 2)
            hasPrev = true
        }
        flux[0] = 0f

        // normalize envelope: mean removal
        var mean = 0.0
        for (v in flux) mean += v
        mean /= flux.size
        var variance = 0.0
        for (i in flux.indices) {
            flux[i] = (flux[i] - mean).toFloat()
            variance += flux[i] * flux[i]
        }
        variance /= flux.size
        if (variance < 1e-12) return 120f to 0f
        val std = sqrt(variance).toFloat()
        for (i in flux.indices) flux[i] /= std

        // normalized autocorrelation for lag range of 60..200 BPM
        val lagMin = (60f * fps / 200f).toInt().coerceAtLeast(4)
        val lagMax = (60f * fps / 60f).toInt()
        val maxLag2 = (lagMax * 2 + 4).coerceAtMost(flux.size - 2)
        val ac = FloatArray(maxLag2 + 1)
        for (lag in 0..maxLag2) {
            var s = 0.0
            var n = 0
            var i = 0
            while (i + lag < flux.size) {
                s += flux[i] * flux[i + lag]
                n++; i++
            }
            ac[lag] = if (n > 0) (s / n).toFloat() else 0f
        }

        fun acAt(lagF: Float): Float {
            if (lagF <= 0 || lagF >= maxLag2) return 0f
            val lo = lagF.toInt()
            val frac = lagF - lo
            return ac[lo] * (1 - frac) + ac[lo + 1] * frac
        }

        // score BPM grid 60..200 step 0.5 with comb weighting
        val bpmLo = 60f
        val bpmHi = 200f
        val stepBpm = 0.5f
        val n = ((bpmHi - bpmLo) / stepBpm).toInt() + 1
        val scores = FloatArray(n)
        for (k in 0 until n) {
            val bpm = bpmLo + k * stepBpm
            val lag = 60f * fps / bpm
            // comb: fundamental + half weight on double period (octave guard)
            scores[k] = acAt(lag) + 0.5f * acAt(lag * 2f)
        }
        var best = 0
        for (k in 1 until n) if (scores[k] > scores[best]) best = k
        var bestBpm = bpmLo + best * stepBpm

        // octave correction:
        // half-tempo trap (hip-hop ~70 detected, true ~140): if 2x scores well, go up
        val scoreAt: (Float) -> Float = { bpm ->
            val k = ((bpm - bpmLo) / stepBpm).toInt().coerceIn(0, n - 1)
            scores[k]
        }
        val bestScore = scores[best]
        if (bestBpm < 100) {
            val dbl = bestBpm * 2
            if (dbl <= 200 && scoreAt(dbl) > bestScore * 0.72f) bestBpm = dbl
        } else if (bestBpm > 155) {
            val half = bestBpm / 2
            if (half >= 60 && scoreAt(half) > bestScore * 0.85f) bestBpm = half
        }

        // parabolic refinement on grid
        val kb = ((bestBpm - bpmLo) / stepBpm).toInt().coerceIn(1, n - 2)
        val y0 = scores[kb - 1]; val y1 = scores[kb]; val y2 = scores[kb + 1]
        val denom = (y0 - 2 * y1 + y2)
        val shift = if (denom != 0f) 0.5f * (y0 - y2) / denom else 0f
        bestBpm = (bpmLo + (kb + shift.coerceIn(-1f, 1f)) * stepBpm)
            .coerceIn(60f, 200f)

        // confidence from peak prominence
        val sorted = scores.sorted()
        val median = sorted[sorted.size / 2]
        var peak = scores.max()!!
        val conf = ((peak - median) / (peak - median + 2.0f)).coerceIn(0f, 1f)
            .let { (it * 1.4f).coerceIn(0f, 1f) }

        // round to 0.1
        bestBpm = (bestBpm * 10).toInt() / 10f
        return bestBpm to conf
    }

    fun detectVocalMusicBalance(pcm: FloatArray): Pair<Int, Int> {
        val sr = 22050f
        var low = 0f
        var highLp = 0f
        val aLow = alpha(300f, sr)
        val aHigh = alpha(3400f, sr)
        var eVocal = 0.0
        var eTotal = 0.0
        var i = 0
        while (i < pcm.size) {
            val s = pcm[i]
            low += aLow * (s - low)
            highLp += aHigh * (s - highLp)
            val vocal = s - low - (s - highLp)
            eVocal += vocal * vocal
            eTotal += s * s + 1e-9
            i += 2
        }
        val ratio = (eVocal / eTotal).toFloat().coerceIn(0f, 1f)
        val vocalPct = ((ratio - 0.15f) / 0.60f * 80 + 10).toInt().coerceIn(5, 95)
        return vocalPct to (100 - vocalPct)
    }

    fun previewBars(pcm: FloatArray, bars: Int = 120): FloatArray {
        if (pcm.isEmpty()) return FloatArray(bars)
        val out = FloatArray(bars)
        val per = pcm.size / bars
        for (b in 0 until bars) {
            var peak = 0f
            val s = b * per
            val e = minOf(s + per, pcm.size)
            var i = s
            while (i < e) {
                val v = kotlin.math.abs(pcm[i])
                if (v > peak) peak = v
                i += 7
            }
            out[b] = peak.coerceIn(0f, 1f)
        }
        // normalize
        val mx = out.max()!!.coerceAtLeast(0.05f)
        for (b in out.indices) out[b] = (out[b] / mx).coerceIn(0.03f, 1f)
        return out
    }

    private fun alpha(cutoff: Float, sr: Float): Float {
        val rc = 1f / (2f * Math.PI.toFloat() * cutoff)
        val dt = 1f / sr
        return (dt / (rc + dt)).coerceIn(0.01f, 1f)
    }

    /** In-place radix-2 FFT (Float). n must be power of two. */
    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * Math.PI / len
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var wR = 1.0; var wI = 0.0
                for (k in 0 until len / 2) {
                    val uR = re[i + k].toDouble()
                    val uI = im[i + k].toDouble()
                    val vR = re[i + k + len / 2] * wR - im[i + k + len / 2] * wI
                    val vI = re[i + k + len / 2] * wI + im[i + k + len / 2] * wR
                    re[i + k] = (uR + vR).toFloat()
                    im[i + k] = (uI + vI).toFloat()
                    re[i + k + len / 2] = (uR - vR).toFloat()
                    im[i + k + len / 2] = (uI - vI).toFloat()
                    val nWR = wR * wr - wI * wi
                    wI = wR * wi + wI * wr
                    wR = nWR
                }
                i += len
            }
            len = len shl 1
        }
    }
}
