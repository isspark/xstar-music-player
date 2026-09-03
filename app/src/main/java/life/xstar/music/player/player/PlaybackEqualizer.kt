package life.xstar.music.player.player

import android.media.audiofx.Equalizer
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi

@OptIn(UnstableApi::class)
class PlaybackEqualizer(private val onStateChanged: (EqualizerState) -> Unit) {
    private var effect: Equalizer? = null
    private var settings = EqualizerSettings()
    var state = EqualizerState()
        private set

    fun updateSettings(value: EqualizerSettings) {
        settings = value
        effect?.let(::applySettings)
        publishState()
    }

    fun attach(audioSessionId: Int) {
        effect?.release()
        effect = null
        if (audioSessionId == C.AUDIO_SESSION_ID_UNSET) {
            updateState(EqualizerState(ready = false, enabled = settings.enabled))
            return
        }
        val created = runCatching { Equalizer(0, audioSessionId) }.getOrElse {
            updateState(EqualizerState(available = false, error = "当前设备无法初始化均衡器"))
            return
        }
        effect = created
        runCatching { applySettings(created) }.onFailure {
            created.release()
            effect = null
            updateState(EqualizerState(available = false, error = "均衡器初始化失败"))
        }
    }

    fun setEnabled(enabled: Boolean): EqualizerSettings {
        settings = settings.copy(enabled = enabled)
        runCatching {
            effect?.enabled = enabled
            publishState()
        }.onFailure { publishError("无法切换均衡器") }
        return settings
    }

    fun usePreset(index: Int): EqualizerSettings {
        val equalizer = effect ?: return settings
        if (index !in 0 until equalizer.numberOfPresets.toInt()) return settings
        runCatching {
            equalizer.usePreset(index.toShort())
            settings = settings.copy(preset = index)
            publishState()
        }.onFailure { publishError("无法应用此预设") }
        return settings
    }

    fun setBand(index: Int, levelMb: Int): EqualizerSettings {
        val equalizer = effect ?: return settings
        if (index !in 0 until equalizer.numberOfBands.toInt()) return settings
        val range = equalizer.bandLevelRange
        val safeLevel = levelMb.coerceIn(range[0].toInt(), range[1].toInt())
        runCatching {
            equalizer.setBandLevel(index.toShort(), safeLevel.toShort())
            val levels = (0 until equalizer.numberOfBands.toInt()).associate { bandIndex ->
                val band = bandIndex.toShort()
                equalizer.getCenterFreq(band) / 1_000 to equalizer.getBandLevel(band).toInt()
            }
            settings = settings.copy(preset = null, levelsByFrequency = levels)
            publishState()
        }.onFailure { publishError("无法调整此频段") }
        return settings
    }

    fun reset(): EqualizerSettings {
        val equalizer = effect
        runCatching {
            if (equalizer != null) repeat(equalizer.numberOfBands.toInt()) { equalizer.setBandLevel(it.toShort(), 0) }
            settings = settings.copy(preset = null, levelsByFrequency = emptyMap())
            publishState()
        }.onFailure { publishError("无法重置均衡器") }
        return settings
    }

    fun release() {
        effect?.release()
        effect = null
    }

    private fun applySettings(equalizer: Equalizer) {
        equalizer.enabled = settings.enabled
        val preset = settings.preset
        if (preset != null && preset in 0 until equalizer.numberOfPresets.toInt()) {
            equalizer.usePreset(preset.toShort())
        } else {
            val range = equalizer.bandLevelRange
            repeat(equalizer.numberOfBands.toInt()) { index ->
                val band = index.toShort()
                val frequency = equalizer.getCenterFreq(band) / 1_000
                val saved = settings.levelsByFrequency.minByOrNull { (savedFrequency, _) -> kotlin.math.abs(savedFrequency - frequency) }?.value ?: 0
                equalizer.setBandLevel(band, saved.coerceIn(range[0].toInt(), range[1].toInt()).toShort())
            }
        }
        publishState()
    }

    private fun publishState() {
        val equalizer = effect ?: return
        val range = equalizer.bandLevelRange
        val bands = (0 until equalizer.numberOfBands.toInt()).map { index ->
            val band = index.toShort()
            EqualizerBand(index, equalizer.getCenterFreq(band) / 1_000, equalizer.getBandLevel(band).toInt(), range[0].toInt(), range[1].toInt())
        }
        val presets = (0 until equalizer.numberOfPresets.toInt()).map { EqualizerPreset(it, equalizer.getPresetName(it.toShort())) }
        updateState(EqualizerState(available = true, ready = true, enabled = equalizer.enabled, bands = bands, presets = presets, selectedPreset = settings.preset))
    }

    private fun updateState(value: EqualizerState) {
        state = value
        onStateChanged(value)
    }

    private fun publishError(message: String) {
        updateState(state.copy(error = message))
    }
}
