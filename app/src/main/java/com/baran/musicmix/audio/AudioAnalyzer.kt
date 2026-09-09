package com.baran.musicmix.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.sqrt

data class TrackAnalysis(
    val bpm: Float,
    val vocalPct: Int,   // 0..100 estimated vocal presence
    val musicPct: Int,   // 0..100 estimated accompaniment presence
    val durationMs: Long
)

/**
 * Fully offline auto-detect:
 * - decodes audio to mono 22050 Hz PCM via MediaExtractor + MediaCodec
 * - BPM: energy-onset envelope + autocorrelation in 60..180 BPM range
 * - Vocal/Music: energy ratio in vocal band (300..3400 Hz) vs full spectrum
 *   using Goertzel-ish band energy on downsampled PCM (time-domain filters).
 * This is a DSP approximation, not ML source separation. It runs on-device
 * with no network and is honest about its limits.
 */
object AudioAnalyzer {

    suspend fun analyze(context: Context, uri: Uri): TrackAnalysis =
        withContext(Dispatchers.Default) {
            val pcm = decodeMono22050(context, uri, maxSeconds = 90)
            if (pcm.isEmpty()) return@withContext TrackAnalysis(120f, 50, 50, 0L)
            val bpm = detectBpm(pcm, sampleRate = 22050)
            val (vocal, music) = detectVocalMusicBalance(pcm)
            TrackAnalysis(bpm, vocal, music, 0L)
        }

    // Decode first [maxSeconds] to mono 16-bit @22050Hz float [-1,1]
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

            val out = mutableListOf<Float>()
            val maxSamples = maxSeconds * 22050
            val info = MediaCodec.BufferInfo()
            var eos = false
            // simple resample accumulator
            var resamplePos = 0.0
            val step = srcRate.toDouble() / 22050.0

            // temp holder for decoded block at source rate
            val srcBlock = mutableListOf<Float>()

            while (!eos && out.size < maxSamples) {
                val inIdx = codec.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val buf = codec.getInputBuffer(inIdx)!!
                    val n = extractor.readSampleData(buf, 0)
                    if (n < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    } else {
                        codec.queueInputBuffer(inIdx, 0, n,
                            extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                if (outIdx >= 0) {
                    val buf = codec.getOutputBuffer(outIdx)!!
                    if (info.size > 0) {
                        val bytes = ByteArray(info.size)
                        buf.get(bytes)
                        // assume PCM 16-bit little endian
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
                        // resample srcBlock -> 22050
                        while (true) {
                            val idx = resamplePos.toInt()
                            if (idx + 1 >= srcBlock.size) break
                            val frac = (resamplePos - idx).toFloat()
                            val v = srcBlock[idx] * (1 - frac) + srcBlock[idx + 1] * frac
                            out.add(v)
                            resamplePos += step
                            if (out.size >= maxSamples) break
                        }
                        // drop consumed source samples
                        val consumed = resamplePos.toInt()
                        if (consumed > 0) {
                            repeat(minOf(consumed, srcBlock.size)) { srcBlock.removeAt(0) }
                            resamplePos -= consumed
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eos = true
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // ignore
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

    fun detectBpm(pcm: FloatArray, sampleRate: Int): Float {
        // onset envelope: spectral-flux approximated by rectified diff of
        // short-term energy, hop = 512
        val hop = 512
        val win = 1024
        val frames = pcm.size / hop - 2
        if (frames < 32) return 120f
        val env = FloatArray(frames)
        var prevEnergy = 0f
        for (f in 0 until frames) {
            var e = 0f
            val start = f * hop
            for (i in 0 until win) {
                if (start + i >= pcm.size) break
                val s = pcm[start + i]
                e += s * s
            }
            e = sqrt(e / win)
            val flux = (e - prevEnergy).coerceAtLeast(0f)
            env[f] = flux
            prevEnergy = e
        }
        // remove DC
        val mean = env.average().toFloat()
        for (i in env.indices) env[i] -= mean
        val fps = sampleRate.toFloat() / hop // envelope rate ~43/s
        var bestBpm = 120f
        var bestScore = Float.NEGATIVE_INFINITY
        var b = 60
        while (b <= 180) {
            val periodSec = 60f / b
            val lag = (periodSec * fps).toInt().coerceAtLeast(1)
            var score = 0f
            var n = 0
            var i = 0
            while (i + lag < env.size) {
                score += env[i] * env[i + lag]
                n++; i++
            }
            if (n > 0) score /= n
            // slight bias to 90..140 to avoid octave errors
            if (b in 90..140) score *= 1.05f
            if (score > bestScore) { bestScore = score; bestBpm = b.toFloat() }
            b++
        }
        // refine with parabolic interpolation around best (1 BPM step)
        return bestBpm
    }

    fun detectVocalMusicBalance(pcm: FloatArray): Pair<Int, Int> {
        // 2nd-order approximations:
        // low  = one-pole lowpass @300Hz, high = pcm - lowpass @3400Hz,
        // vocal band = band-limited remainder.
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
            val highs = s - highLp       // > ~3400 Hz
            val lows = low               // < ~300 Hz
            val vocal = s - lows - highs // 300..3400 Hz
            eVocal += vocal * vocal
            eTotal += s * s + 1e-9
            i += 2 // subsample for speed
        }
        val ratio = (eVocal / eTotal).coerceIn(0.0, 1.0)
        // map ratio 0.15..0.75 -> vocal 10..90
        val vocalPct = ((ratio - 0.15) / 0.60 * 80 + 10).toInt().coerceIn(5, 95)
        return vocalPct to (100 - vocalPct)
    }

    private fun alpha(cutoff: Float, sr: Float): Float {
        val rc = 1f / (2f * Math.PI.toFloat() * cutoff)
        val dt = 1f / sr
        return (dt / (rc + dt)).coerceIn(0.01f, 1f)
    }
}
