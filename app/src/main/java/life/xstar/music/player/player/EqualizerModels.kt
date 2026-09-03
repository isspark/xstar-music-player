package life.xstar.music.player.player

import android.os.Bundle
import androidx.core.os.bundleOf
import androidx.media3.session.SessionCommand

data class EqualizerBand(
    val index: Int,
    val centerFrequencyHz: Int,
    val levelMb: Int,
    val minLevelMb: Int,
    val maxLevelMb: Int
)

data class EqualizerPreset(val index: Int, val name: String)

data class EqualizerState(
    val available: Boolean = true,
    val ready: Boolean = false,
    val enabled: Boolean = false,
    val bands: List<EqualizerBand> = emptyList(),
    val presets: List<EqualizerPreset> = emptyList(),
    val selectedPreset: Int? = null,
    val error: String? = null
)

data class EqualizerSettings(
    val enabled: Boolean = false,
    val preset: Int? = null,
    val levelsByFrequency: Map<Int, Int> = emptyMap()
)

object EqualizerCommands {
    const val GET_STATE = "life.xstar.music.player.EQ_GET_STATE"
    const val SET_ENABLED = "life.xstar.music.player.EQ_SET_ENABLED"
    const val USE_PRESET = "life.xstar.music.player.EQ_USE_PRESET"
    const val SET_BAND = "life.xstar.music.player.EQ_SET_BAND"
    const val RESET = "life.xstar.music.player.EQ_RESET"
    const val STATE_CHANGED = "life.xstar.music.player.EQ_STATE_CHANGED"

    const val KEY_ENABLED = "enabled"
    const val KEY_PRESET = "preset"
    const val KEY_BAND = "band"
    const val KEY_LEVEL = "level"

    val controllerCommands = listOf(GET_STATE, SET_ENABLED, USE_PRESET, SET_BAND, RESET)
        .map { SessionCommand(it, Bundle.EMPTY) }
    val stateChanged = SessionCommand(STATE_CHANGED, Bundle.EMPTY)
}

fun EqualizerState.toBundle(): Bundle = bundleOf(
    "available" to available,
    "ready" to ready,
    "enabled" to enabled,
    "band_indexes" to bands.map { it.index }.toIntArray(),
    "band_frequencies" to bands.map { it.centerFrequencyHz }.toIntArray(),
    "band_levels" to bands.map { it.levelMb }.toIntArray(),
    "band_min_levels" to bands.map { it.minLevelMb }.toIntArray(),
    "band_max_levels" to bands.map { it.maxLevelMb }.toIntArray(),
    "preset_indexes" to presets.map { it.index }.toIntArray(),
    "preset_names" to presets.map { it.name }.toTypedArray(),
    "selected_preset" to (selectedPreset ?: -1),
    "error" to error
)

fun equalizerStateFromBundle(bundle: Bundle): EqualizerState {
    val indexes = bundle.getIntArray("band_indexes") ?: intArrayOf()
    val frequencies = bundle.getIntArray("band_frequencies") ?: intArrayOf()
    val levels = bundle.getIntArray("band_levels") ?: intArrayOf()
    val minLevels = bundle.getIntArray("band_min_levels") ?: intArrayOf()
    val maxLevels = bundle.getIntArray("band_max_levels") ?: intArrayOf()
    val presetIndexes = bundle.getIntArray("preset_indexes") ?: intArrayOf()
    val presetNames = bundle.getStringArray("preset_names") ?: emptyArray()
    return EqualizerState(
        available = bundle.getBoolean("available", true),
        ready = bundle.getBoolean("ready"),
        enabled = bundle.getBoolean("enabled"),
        bands = indexes.indices.mapNotNull { index ->
            EqualizerBand(
                indexes[index],
                frequencies.getOrNull(index) ?: return@mapNotNull null,
                levels.getOrNull(index) ?: 0,
                minLevels.getOrNull(index) ?: 0,
                maxLevels.getOrNull(index) ?: 0
            )
        },
        presets = presetIndexes.indices.mapNotNull { index ->
            EqualizerPreset(presetIndexes[index], presetNames.getOrNull(index) ?: return@mapNotNull null)
        },
        selectedPreset = bundle.getInt("selected_preset", -1).takeIf { it >= 0 },
        error = bundle.getString("error")
    )
}
