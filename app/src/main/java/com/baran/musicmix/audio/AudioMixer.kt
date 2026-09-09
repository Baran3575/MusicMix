package com.baran.musicmix.audio

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class MixSettings(
    val bpmA: Float, val bpmB: Float, val masterBpm: Float,
    val vocalA: Float, val musicA: Float,
    val vocalB: Float, val musicB: Float,
    val crossfade: Float, // 0 = full A, 1 = full B, 0.5 = equal
    val volumeA: Float = 1f, val volumeB: Float = 1f
)

/**
 * Offline mixdown -> WAV (44.1kHz stereo 16-bit).
 * Steps per deck: decode @22050 mono -> tempo-resample to master BPM ->
 * 3-band split (low <300, vocal 300..3400, high >3400) -> apply
 * music gain to low+high, vocal gain to vocal band -> upsample to 44100 ->
 * crossfade + sum -> write WAV to Music/MusicMix/.
 */
object AudioMixer {

    suspend fun exportMix(
        context: Context,
        uriA: Uri?, uriB: Uri?,
        settings: MixSettings
    ): File = withContext(Dispatchers.Default) {
        val srOut = 44100

        val pcmA = if (uriA != null)
            AudioAnalyzer.decodeMono22050(context, uriA, maxSeconds = 600)
        else FloatArray(0)
        val pcmB = if (uriB != null)
            AudioAnalyzer.decodeMono22050(context, uriB, maxSeconds = 600)
        else FloatArray(0)

        val rateA = (settings.masterBpm / settings.bpmA.coerceAtLeast(1f))
            .coerceIn(0.5f, 2.0f)
        val rateB = (settings.masterBpm / settings.bpmB.coerceAtLeast(1f))
            .coerceIn(0.5f, 2.0f)

        val procA = processDeck(pcmA, rateA, settings.vocalA, settings.musicA)
        val procB = processDeck(pcmB, rateB, settings.vocalB, settings.musicB)

        val len = maxOf(procA.size, procB.size)
        // equal-power crossfade
        val fadeA = kotlin.math.cos(settings.crossfade * Math.PI.toFloat() / 2f)
        val fadeB = kotlin.math.sin(settings.crossfade * Math.PI.toFloat() / 2f)

        val stereo = FloatArray(len * 2)
        for (i in 0 until len) {
            val a = if (i < procA.size) procA[i] * fadeA * settings.volumeA else 0f
            val b = if (i < procB.size) procB[i] * fadeB * settings.volumeB else 0f
            val m = (a + b).coerceIn(-1f, 1f)
            stereo[i * 2] = m
            stereo[i * 2 + 1] = m
        }
        // upsample 22050 -> 44100 by duplication (processed at 22050)
        val up = FloatArray(stereo.size * 2)
        for (i in stereo.indices) {
            up[i * 2] = stereo[i]
            up[i * 2 + 1] = stereo[i]
        }

        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            "MusicMix"
        )
        dir.mkdirs()
        val out = File(dir, "musicmix_${System.currentTimeMillis()}.wav")
        writeWav(out, up, srOut, 2)

        MediaScannerConnection.scanFile(context, arrayOf(out.absolutePath), null, null)
        out
    }

    // tempo resample (rate>1 = faster/shorter) + 3-band vocal/music balance
    private fun processDeck(
        pcm: FloatArray, rate: Float, vocal: Float, music: Float
    ): FloatArray {
        if (pcm.isEmpty()) return FloatArray(0)
        // 1) tempo resample by linear interpolation
        val newLen = (pcm.size / rate).toInt().coerceAtLeast(1)
        val stretched = FloatArray(newLen)
        for (i in 0 until newLen) {
            val pos = i * rate
            val idx = pos.toInt()
            val frac = pos - idx
            val a = pcm[idx.coerceIn(0, pcm.size - 1)]
            val b = pcm[(idx + 1).coerceIn(0, pcm.size - 1)]
            stretched[i] = a * (1 - frac) + b * frac
        }
        // 2) 3-band split with one-pole filters @22050Hz
        val sr = 22050f
        var low = 0f
        var midLp = 0f
        val aLow = alpha(300f, sr)
        val aMid = alpha(3400f, sr)
        val out = FloatArray(stretched.size)
        for (i in stretched.indices) {
            val s = stretched[i]
            low += aLow * (s - low)
            midLp += aMid * (s - midLp)
            val highs = s - midLp
            val lows = low
            val vocalBand = midLp - low
            out[i] = lows * music + vocalBand * vocal * 1.2f + highs * music
        }
        // 3) soft normalize to -3dB headroom
        var peak = 0.01f
        for (v in out) peak = maxOf(peak, kotlin.math.abs(v))
        val norm = (0.7f / peak).coerceAtMost(1.5f)
        for (i in out.indices) out[i] *= norm
        return out
    }

    private fun alpha(cutoff: Float, sr: Float): Float {
        val rc = 1f / (2f * Math.PI.toFloat() * cutoff)
        val dt = 1f / sr
        return (dt / (rc + dt)).coerceIn(0.01f, 1f)
    }

    private fun writeWav(file: File, samples: FloatArray, sampleRate: Int, channels: Int) {
        FileOutputStream(file).use { fos ->
            val dataSize = samples.size * 2
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray())
            header.putInt(36 + dataSize)
            header.put("WAVE".toByteArray())
            header.put("fmt ".toByteArray())
            header.putInt(16)
            header.putShort(1) // PCM
            header.putShort(channels.toShort())
            header.putInt(sampleRate)
            header.putInt(sampleRate * channels * 2)
            header.putShort((channels * 2).toShort())
            header.putShort(16)
            header.put("data".toByteArray())
            header.putInt(dataSize)
            fos.write(header.array())
            val buf = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (s in samples) {
                val v = (s.coerceIn(-1f, 1f) * 32767).toInt()
                buf.putShort(v.toShort())
            }
            fos.write(buf.array())
        }
    }
}
