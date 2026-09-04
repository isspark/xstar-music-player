package life.xstar.music.player.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import java.io.File
import androidx.core.os.bundleOf
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import life.xstar.music.player.data.local.SongEntity

class PlayerController(context: Context) {
    private val executor = ContextCompat.getMainExecutor(context)
    private val scope = CoroutineScope(Dispatchers.Main.immediate)
    private var progressJob: Job? = null
    private var listenerAttached = false
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()
    private val _equalizerState = MutableStateFlow(EqualizerState())
    val equalizerState: StateFlow<EqualizerState> = _equalizerState.asStateFlow()
    private val _karaokeState = MutableStateFlow(KaraokeState())
    val karaokeState: StateFlow<KaraokeState> = _karaokeState.asStateFlow()
    private val _spectrum = MutableStateFlow(FloatArray(0))
    val spectrum: StateFlow<FloatArray> = _spectrum.asStateFlow()
    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) { _state.value = _state.value.copy(playing = isPlaying) }
        override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
            val index = controllerFuture.get().currentMediaItemIndex
            _state.value = _state.value.copy(song = _state.value.queue.getOrNull(index), index = index)
        }
        override fun onPlaybackStateChanged(playbackState: Int) {
            val controller = controllerFuture.get()
            _state.value = _state.value.copy(position = controller.currentPosition, duration = controller.duration.coerceAtLeast(0))
        }
        override fun onPlayerError(error: PlaybackException) {
            val controller = controllerFuture.get()
            _state.value = _state.value.copy(error = "无法播放当前歌曲")
            if (controller.hasNextMediaItem()) controller.seekToNextMediaItem()
        }
    }
    private val controllerListener = object : MediaController.Listener {
        override fun onCustomCommand(controller: MediaController, command: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
            when (command.customAction) {
                EqualizerCommands.STATE_CHANGED -> _equalizerState.value = equalizerStateFromBundle(args)
                KaraokeCommands.STATE_CHANGED -> _karaokeState.value = karaokeStateFromBundle(args)
                SpectrumCommands.STATE_CHANGED -> _spectrum.value = spectrumFromBundle(args)
            }
            return com.google.common.util.concurrent.Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }
    private val controllerFuture: ListenableFuture<MediaController> =
        MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java)))
            .setListener(controllerListener)
            .buildAsync()

    init {
        controllerFuture.addListener({
            val controller = runCatching { controllerFuture.get() }.getOrNull() ?: return@addListener
            attachListener(controller)
            refreshEqualizer()
            refreshKaraoke()
        }, executor)
    }

    fun play(song: SongEntity) = playQueue(listOf(song), 0)

    fun restoreQueue(
        songs: List<SongEntity>,
        index: Int,
        position: Long,
        mode: PlaybackMode = PlaybackMode.Sequential,
        playing: Boolean = false,
        fmMode: Boolean = false
    ) {
        if (songs.isNotEmpty()) {
            controllerFuture.addListener({
                val controller = controllerFuture.get()
                val restoredIndex = index.coerceIn(0, songs.lastIndex)
                attachListener(controller)
                controller.setMediaItems(songs.map { it.toMediaItem() }, restoredIndex, position)
                applyPlaybackMode(controller, mode, fmMode)
                controller.prepare()
                if (playing) controller.play()
                _state.value = PlayerState(songs[restoredIndex], playing, position, queue = songs, index = restoredIndex, mode = mode)
                startProgressUpdates(controller)
            }, executor)
        }
    }

    fun playQueue(songs: List<SongEntity>, startIndex: Int, mode: PlaybackMode = _state.value.mode) {
        if (songs.isEmpty()) return
        controllerFuture.addListener({
            val controller = runCatching { controllerFuture.get() }.getOrNull() ?: return@addListener
            val safeIndex = startIndex.coerceIn(0, songs.lastIndex)
            attachListener(controller)
            controller.setMediaItems(songs.map { it.toMediaItem() }, safeIndex, 0)
            applyPlaybackMode(controller, mode, fmMode = false)
            controller.prepare()
            controller.play()
            _state.value = PlayerState(songs[safeIndex], true, queue = songs, index = safeIndex, mode = mode)
            startProgressUpdates(controller)
        }, executor)
    }

    fun playFmQueue(songs: List<SongEntity>, startIndex: Int = 0, position: Long = 0, playing: Boolean = true) =
        restoreQueue(songs, startIndex, position, PlaybackMode.Sequential, playing, fmMode = true)

    fun randomSong() = controllerFuture.addListener({
        val controller = runCatching { controllerFuture.get() }.getOrNull() ?: return@addListener
        if (controller.mediaItemCount <= 1) return@addListener
        var target = kotlin.random.Random.nextInt(controller.mediaItemCount)
        while (target == controller.currentMediaItemIndex) target = kotlin.random.Random.nextInt(controller.mediaItemCount)
        controller.seekTo(target, 0)
        controller.play()
    }, executor)

    fun toggle() {
        controllerFuture.addListener({
            val controller = runCatching { controllerFuture.get() }.getOrNull() ?: return@addListener
            if (controller.isPlaying) controller.pause() else controller.play()
            _state.value = _state.value.copy(playing = controller.isPlaying)
        }, executor)
    }

    fun pause() = controllerFuture.addListener({
        val controller = runCatching { controllerFuture.get() }.getOrNull() ?: return@addListener
        controller.pause()
        _state.value = _state.value.copy(playing = false)
    }, executor)

    fun next() = controllerFuture.addListener({ controllerFuture.get().seekToNextMediaItem() }, executor)
    fun previous() = controllerFuture.addListener({ controllerFuture.get().seekToPreviousMediaItem() }, executor)
    fun seek(position: Long) = controllerFuture.addListener({ controllerFuture.get().seekTo(position) }, executor)
    fun updateSong(song: SongEntity) {
        if (_state.value.song?.mediaStoreId != song.mediaStoreId) return
        _state.value = _state.value.copy(song = song, queue = _state.value.queue.map { if (it.mediaStoreId == song.mediaStoreId) song else it })
    }
    fun remove(index: Int) = controllerFuture.addListener({
        val controller = controllerFuture.get()
        if (index in 0 until controller.mediaItemCount) {
            controller.removeMediaItem(index)
            val queue = _state.value.queue.toMutableList().apply { removeAt(index) }
            val currentIndex = controller.currentMediaItemIndex.coerceAtMost(queue.lastIndex)
            _state.value = if (queue.isEmpty()) PlayerState(mode = _state.value.mode) else _state.value.copy(
                queue = queue,
                index = currentIndex,
                song = queue.getOrNull(currentIndex)
            )
        }
    }, executor)
    fun clear() = controllerFuture.addListener({ controllerFuture.get().clearMediaItems(); _state.value = PlayerState() }, executor)
    fun setPlaybackMode(mode: PlaybackMode) = controllerFuture.addListener({
        val controller = controllerFuture.get()
        applyPlaybackMode(controller, mode, fmMode = false)
        _state.value = _state.value.copy(mode = mode)
    }, executor)

    fun refreshEqualizer() = sendEqualizerCommand(EqualizerCommands.GET_STATE)
    fun setEqualizerEnabled(enabled: Boolean) = sendEqualizerCommand(EqualizerCommands.SET_ENABLED, bundleOf(EqualizerCommands.KEY_ENABLED to enabled))
    fun useEqualizerPreset(index: Int) = sendEqualizerCommand(EqualizerCommands.USE_PRESET, bundleOf(EqualizerCommands.KEY_PRESET to index))
    fun setEqualizerBand(index: Int, levelMb: Int) = sendEqualizerCommand(
        EqualizerCommands.SET_BAND,
        bundleOf(EqualizerCommands.KEY_BAND to index, EqualizerCommands.KEY_LEVEL to levelMb)
    )
    fun resetEqualizer() = sendEqualizerCommand(EqualizerCommands.RESET)

    fun refreshKaraoke() = sendKaraokeCommand(KaraokeCommands.GET_STATE)
    fun setKaraokeEnabled(enabled: Boolean) = sendKaraokeCommand(KaraokeCommands.SET_ENABLED, bundleOf(KaraokeCommands.KEY_ENABLED to enabled))
    fun setKaraokeVocal(value: Float) = sendKaraokeCommand(KaraokeCommands.SET_VOCAL, bundleOf(KaraokeCommands.KEY_VOCAL to value))
    fun setKaraokePitch(semi: Int) = sendKaraokeCommand(KaraokeCommands.SET_PITCH, bundleOf(KaraokeCommands.KEY_PITCH to semi))

    fun release() { progressJob?.cancel(); scope.cancel(); if (controllerFuture.isDone) controllerFuture.get().removeListener(listener); MediaController.releaseFuture(controllerFuture) }

    private fun attachListener(controller: MediaController) {
        if (!listenerAttached) {
            controller.addListener(listener)
            listenerAttached = true
        }
    }

    private fun applyPlaybackMode(controller: MediaController, mode: PlaybackMode, fmMode: Boolean) {
        controller.shuffleModeEnabled = mode == PlaybackMode.Shuffle
        controller.repeatMode = when {
            fmMode -> Player.REPEAT_MODE_ALL
            mode == PlaybackMode.RepeatOne -> Player.REPEAT_MODE_ONE
            mode == PlaybackMode.Shuffle -> Player.REPEAT_MODE_ALL
            else -> Player.REPEAT_MODE_OFF
        }
    }

    private fun startProgressUpdates(controller: MediaController) {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                _state.value = _state.value.copy(playing = controller.isPlaying, position = controller.currentPosition, duration = controller.duration.coerceAtLeast(0))
                delay(500)
            }
        }
    }

    private fun sendKaraokeCommand(action: String, args: Bundle = Bundle.EMPTY) {
        controllerFuture.addListener({
            val controller = runCatching { controllerFuture.get() }.getOrNull() ?: return@addListener
            val command = SessionCommand(action, Bundle.EMPTY)
            if (!controller.isSessionCommandAvailable(command)) return@addListener
            val result = controller.sendCustomCommand(command, args)
            result.addListener({
                val response = runCatching { result.get() }.getOrNull() ?: return@addListener
                if (response.resultCode == SessionResult.RESULT_SUCCESS) _karaokeState.value = karaokeStateFromBundle(response.extras)
                else _karaokeState.value = _karaokeState.value.copy(error = "卡拉OK音效操作失败")
            }, executor)
        }, executor)
    }

    private fun sendEqualizerCommand(action: String, args: Bundle = Bundle.EMPTY) {
        controllerFuture.addListener({
            val controller = runCatching { controllerFuture.get() }.getOrNull() ?: return@addListener
            val command = SessionCommand(action, Bundle.EMPTY)
            if (!controller.isSessionCommandAvailable(command)) return@addListener
            val result = controller.sendCustomCommand(command, args)
            result.addListener({
                val response = runCatching { result.get() }.getOrNull() ?: return@addListener
                if (response.resultCode == SessionResult.RESULT_SUCCESS) _equalizerState.value = equalizerStateFromBundle(response.extras)
                else _equalizerState.value = _equalizerState.value.copy(error = "均衡器操作失败")
            }, executor)
        }, executor)
    }

    private fun SongEntity.toMediaItem() = MediaItem.Builder()
        .setMediaId(mediaStoreId.toString())
        .setUri(uri)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(artist).setAlbumTitle(album).apply {
            albumArtUri?.let { setArtworkUri(if (it.startsWith("content://") || it.startsWith("file://")) Uri.parse(it) else Uri.fromFile(File(it))) }
        }.build())
        .build()
}

data class PlayerState(
    val song: SongEntity? = null,
    val playing: Boolean = false,
    val position: Long = 0,
    val duration: Long = 0,
    val queue: List<SongEntity> = emptyList(),
    val index: Int = -1,
    val mode: PlaybackMode = PlaybackMode.Sequential,
    val error: String? = null
)

enum class PlaybackMode { Sequential, Shuffle, RepeatOne }
