package life.xstar.music.player.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import kotlin.math.pow

@OptIn(UnstableApi::class)
class KaraokeDspController(private val onStateChanged: (KaraokeState) -> Unit) {
    val vocalProcessor = VocalMixAudioProcessor()
    private var player: ExoPlayer? = null
    private var settings = KaraokeSettings()
    var state = KaraokeState()
        private set

    fun createAudioSink(
        context: Context,
        enableAudioTrackPlaybackParams: Boolean,
        spectrumProcessor: SpectrumAudioProcessor
    ): AudioSink = DefaultAudioSink.Builder(context)
        .setAudioProcessorChain(DefaultAudioSink.DefaultAudioProcessorChain(vocalProcessor, spectrumProcessor))
        // The custom processors require 16-bit PCM; float output bypasses this chain.
        .setEnableFloatOutput(false)
        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
        .build()

    fun bind(player: ExoPlayer) {
        this.player = player
        apply()
        publishState()
    }

    fun updateSettings(value: KaraokeSettings) {
        settings = value
        apply()
        publishState()
    }

    fun setEnabled(enabled: Boolean): KaraokeSettings {
        settings = settings.copy(enabled = enabled)
        apply()
        publishState()
        return settings
    }

    fun setVocal(value: Float): KaraokeSettings {
        settings = settings.copy(vocal = value.coerceIn(0f, 1f))
        apply()
        publishState()
        return settings
    }

    fun setPitchSemitone(value: Int): KaraokeSettings {
        settings = settings.copy(pitchSemi = value.coerceIn(-7, 7))
        apply()
        publishState()
        return settings
    }

    fun release() {
        player = null
    }

    private fun apply() {
        vocalProcessor.enabled = settings.enabled
        vocalProcessor.vocal = settings.vocal
        val targetPitch = if (settings.enabled) pitchFactor(settings.pitchSemi) else 1f
        val current = player?.playbackParameters
        if (current != null && (current.pitch != targetPitch || current.speed != 1f)) {
            player?.setPlaybackParameters(PlaybackParameters(1f, targetPitch))
        }
    }

    private fun publishState() {
        state = KaraokeState(enabled = settings.enabled, vocal = settings.vocal, pitchSemi = settings.pitchSemi)
        onStateChanged(state)
    }

    private fun pitchFactor(semi: Int): Float = 2f.pow(semi / 12f)
}
