package life.xstar.music.player.player

import android.content.Context
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
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

@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var equalizer: PlaybackEqualizer
    private lateinit var karaoke: KaraokeDspController
    private lateinit var spectrum: SpectrumCapturer
    private var equalizerPersistenceJob: Job? = null
    private var karaokePersistenceJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        karaoke = KaraokeDspController(::broadcastKaraokeState)
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink = karaoke.createAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams)
        }
        val player = ExoPlayer.Builder(this, renderersFactory)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(), true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        karaoke.bind(player)
        equalizer = PlaybackEqualizer(::broadcastEqualizerState)
        spectrum = SpectrumCapturer(::broadcastSpectrum)
        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                equalizer.attach(audioSessionId)
                spectrum.attach(audioSessionId)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                spectrum.ensureSession(player.audioSessionId)
                spectrum.setEnabled(isPlaying)
            }

            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                spectrum.ensureSession(player.audioSessionId)
            }
        })
        mediaSession = MediaSession.Builder(this, player).setCallback(sessionCallback).build()
        scope.launch {
            equalizer.updateSettings((application as XstarApplication).playbackPreferences.readEqualizer())
            karaoke.updateSettings((application as XstarApplication).playbackPreferences.readKaraoke())
            val session = player.audioSessionId
            if (session != C.AUDIO_SESSION_ID_UNSET) {
                equalizer.attach(session)
                spectrum.attach(session)
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        equalizer.release()
        karaoke.release()
        spectrum.release()
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
            KaraokeCommands.controllerCommands.forEach(commands::add)
            commands.add(SpectrumCommands.stateChanged)
            return MediaSession.ConnectionResult.accept(commands.build(), defaults.availablePlayerCommands)
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            if (controller.packageName != packageName) return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
            val response: Bundle = when (customCommand.customAction) {
                EqualizerCommands.GET_STATE -> equalizer.state.toBundle()
                EqualizerCommands.SET_ENABLED -> {
                    val settings = equalizer.setEnabled(args.getBoolean(EqualizerCommands.KEY_ENABLED))
                    persistEqualizer(settings)
                    equalizer.state.toBundle()
                }
                EqualizerCommands.USE_PRESET -> {
                    val settings = equalizer.usePreset(args.getInt(EqualizerCommands.KEY_PRESET, -1))
                    persistEqualizer(settings)
                    equalizer.state.toBundle()
                }
                EqualizerCommands.SET_BAND -> {
                    val settings = equalizer.setBand(args.getInt(EqualizerCommands.KEY_BAND, -1), args.getInt(EqualizerCommands.KEY_LEVEL))
                    persistEqualizer(settings)
                    equalizer.state.toBundle()
                }
                EqualizerCommands.RESET -> {
                    val settings = equalizer.reset()
                    persistEqualizer(settings)
                    equalizer.state.toBundle()
                }
                KaraokeCommands.GET_STATE -> karaoke.state.toBundle()
                KaraokeCommands.SET_ENABLED -> {
                    val settings = karaoke.setEnabled(args.getBoolean(KaraokeCommands.KEY_ENABLED))
                    persistKaraoke(settings)
                    karaoke.state.toBundle()
                }
                KaraokeCommands.SET_VOCAL -> {
                    val settings = karaoke.setVocal(args.getFloat(KaraokeCommands.KEY_VOCAL, 1f))
                    persistKaraoke(settings)
                    karaoke.state.toBundle()
                }
                KaraokeCommands.SET_PITCH -> {
                    val settings = karaoke.setPitchSemitone(args.getInt(KaraokeCommands.KEY_PITCH, 0))
                    persistKaraoke(settings)
                    karaoke.state.toBundle()
                }
                else -> return super.onCustomCommand(session, controller, customCommand, args)
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, response))
        }
    }

    private fun broadcastEqualizerState(state: EqualizerState) {
        val session = mediaSession ?: return
        session.connectedControllers.filter { it.packageName == packageName }.forEach { controller ->
            session.sendCustomCommand(controller, EqualizerCommands.stateChanged, state.toBundle())
        }
    }

    private fun broadcastKaraokeState(state: KaraokeState) {
        val session = mediaSession ?: return
        session.connectedControllers.filter { it.packageName == packageName }.forEach { controller ->
            session.sendCustomCommand(controller, KaraokeCommands.stateChanged, state.toBundle())
        }
    }

    private fun broadcastSpectrum(levels: FloatArray) {
        val session = mediaSession ?: return
        session.connectedControllers.filter { it.packageName == packageName }.forEach { controller ->
            session.sendCustomCommand(controller, SpectrumCommands.stateChanged, spectrumToBundle(levels))
        }
    }

    private fun persistEqualizer(settings: EqualizerSettings) {
        equalizerPersistenceJob?.cancel()
        equalizerPersistenceJob = scope.launch {
            delay(300)
            (application as XstarApplication).playbackPreferences.saveEqualizer(settings)
        }
    }

    private fun persistKaraoke(settings: KaraokeSettings) {
        karaokePersistenceJob?.cancel()
        karaokePersistenceJob = scope.launch {
            delay(300)
            (application as XstarApplication).playbackPreferences.saveKaraoke(settings)
        }
    }
}
