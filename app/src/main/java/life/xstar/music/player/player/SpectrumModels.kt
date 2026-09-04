package life.xstar.music.player.player

import android.os.Bundle
import androidx.core.os.bundleOf
import androidx.media3.session.SessionCommand

const val SPECTRUM_BAND_COUNT = 48

object SpectrumCommands {
    const val STATE_CHANGED = "life.xstar.music.player.SP_STATE_CHANGED"
    const val KEY_LEVELS = "levels"

    val stateChanged = SessionCommand(STATE_CHANGED, Bundle.EMPTY)
}

fun spectrumToBundle(levels: FloatArray): Bundle = bundleOf(
    SpectrumCommands.KEY_LEVELS to levels
)

fun spectrumFromBundle(bundle: Bundle): FloatArray =
    bundle.getFloatArray(SpectrumCommands.KEY_LEVELS) ?: FloatArray(0)
