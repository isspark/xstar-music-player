package life.xstar.music.player.player

import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import life.xstar.music.player.XstarApplication

class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var equalizer: PlaybackEqualizer
    private var equalizerPersistenceJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(), true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        equalizer = PlaybackEqualizer(::broadcastEqualizerState)
        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) = equalizer.attach(audioSessionId)
        })
        mediaSession = MediaSession.Builder(this, player).setCallback(sessionCallback).build()
        scope.launch {
            equalizer.updateSettings((application as XstarApplication).playbackPreferences.readEqualizer())
            if (player.audioSessionId != C.AUDIO_SESSION_ID_UNSET) equalizer.attach(player.audioSessionId)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        equalizer.release()
        scope.cancel()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    private val sessionCallback = object : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val defaults = super.onConnect(session, controller)
            if (controller.packageName != packageName) return defaults
            val commands = defaults.availableSessionCommands.buildUpon()
            EqualizerCommands.controllerCommands.forEach(commands::add)
            return MediaSession.ConnectionResult.accept(commands.build(), defaults.availablePlayerCommands)
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            if (controller.packageName != packageName) return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_PERMISSION_DENIED))
            val settings = when (customCommand.customAction) {
                EqualizerCommands.GET_STATE -> null
                EqualizerCommands.SET_ENABLED -> equalizer.setEnabled(args.getBoolean(EqualizerCommands.KEY_ENABLED))
                EqualizerCommands.USE_PRESET -> equalizer.usePreset(args.getInt(EqualizerCommands.KEY_PRESET, -1))
                EqualizerCommands.SET_BAND -> equalizer.setBand(args.getInt(EqualizerCommands.KEY_BAND, -1), args.getInt(EqualizerCommands.KEY_LEVEL))
                EqualizerCommands.RESET -> equalizer.reset()
                else -> return super.onCustomCommand(session, controller, customCommand, args)
            }
            if (settings != null) persistEqualizer(settings)
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, equalizer.state.toBundle()))
        }
    }

    private fun broadcastEqualizerState(state: EqualizerState) {
        val session = mediaSession ?: return
        session.connectedControllers.filter { it.packageName == packageName }.forEach { controller ->
            session.sendCustomCommand(controller, EqualizerCommands.stateChanged, state.toBundle())
        }
    }

    private fun persistEqualizer(settings: EqualizerSettings) {
        equalizerPersistenceJob?.cancel()
        equalizerPersistenceJob = scope.launch {
            delay(300)
            (application as XstarApplication).playbackPreferences.saveEqualizer(settings)
        }
    }
}
