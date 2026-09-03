package life.xstar.music.player.player

import android.os.Bundle
import androidx.core.os.bundleOf
import androidx.media3.session.SessionCommand

data class KaraokeSettings(
    val enabled: Boolean = false,
    val vocal: Float = 1f,
    val pitchSemi: Int = 0
)

data class KaraokeState(
    val available: Boolean = true,
    val enabled: Boolean = false,
    val vocal: Float = 1f,
    val pitchSemi: Int = 0,
    val error: String? = null
)

object KaraokeCommands {
    const val GET_STATE = "life.xstar.music.player.KT_GET_STATE"
    const val SET_ENABLED = "life.xstar.music.player.KT_SET_ENABLED"
    const val SET_VOCAL = "life.xstar.music.player.KT_SET_VOCAL"
    const val SET_PITCH = "life.xstar.music.player.KT_SET_PITCH"
    const val STATE_CHANGED = "life.xstar.music.player.KT_STATE_CHANGED"

    const val KEY_ENABLED = "enabled"
    const val KEY_VOCAL = "vocal"
    const val KEY_PITCH = "pitch"

    val controllerCommands = listOf(GET_STATE, SET_ENABLED, SET_VOCAL, SET_PITCH)
        .map { SessionCommand(it, Bundle.EMPTY) }
    val stateChanged = SessionCommand(STATE_CHANGED, Bundle.EMPTY)
}

fun KaraokeState.toBundle(): Bundle = bundleOf(
    "available" to available,
    "enabled" to enabled,
    "vocal" to vocal,
    "pitch_semi" to pitchSemi,
    "error" to error
)

fun karaokeStateFromBundle(bundle: Bundle): KaraokeState = KaraokeState(
    available = bundle.getBoolean("available", true),
    enabled = bundle.getBoolean("enabled"),
    vocal = bundle.getFloat("vocal", 1f),
    pitchSemi = bundle.getInt("pitch_semi"),
    error = bundle.getString("error")
)
