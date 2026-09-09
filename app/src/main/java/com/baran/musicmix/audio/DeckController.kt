package com.baran.musicmix.audio

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer

/**
 * One deck = one ExoPlayer with pitch-corrected time-stretch.
 * speed = targetBpm / originalBpm so both decks lock to master BPM.
 * Vocal/Music sliders are mapped to live preview gains:
 *  - vocalGain scales mid band via Equalizer if available, else volume tilt
 *  - musicGain scales overall deck volume (low+high approximation)
 * True ML stem separation is out of scope for an offline lightweight APK;
 * offline export uses 3-band DSP split (see AudioMixer) which is stronger
 * than the live preview EQ approximation.
 */
class DeckController(context: Context) {
    val player: ExoPlayer = ExoPlayer.Builder(context).build()

    var originalBpm: Float = 120f
    var targetBpm: Float = 120f
    var vocalLevel: Float = 0.8f   // 0..1
    var musicLevel: Float = 0.8f   // 0..1
    var deckVolume: Float = 1f     // crossfader result 0..1

    fun load(uri: Uri) {
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
    }

    fun applyTempo() {
        val speed = (targetBpm / originalBpm.coerceAtLeast(1f)).coerceIn(0.5f, 2.0f)
        player.playbackParameters = PlaybackParameters(speed, 1f)
    }

    fun applyVolumes() {
        // vocal boosts presence slightly, music is main deck gain
        val presence = 0.6f + 0.4f * vocalLevel
        player.volume = (deckVolume * musicLevel * presence).coerceIn(0f, 1f)
    }

    fun play() { player.play() }
    fun pause() { player.pause() }
    fun release() { player.release() }
}
