package life.xstar.music.player.player

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.session.MediaController
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
    private val controllerFuture: ListenableFuture<MediaController> =
        MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()

    fun play(song: SongEntity) = playQueue(listOf(song), 0)

    fun restoreQueue(songs: List<SongEntity>, index: Int, position: Long) {
        if (songs.isNotEmpty()) {
            controllerFuture.addListener({
                val controller = controllerFuture.get()
                val restoredIndex = index.coerceIn(0, songs.lastIndex)
                attachListener(controller)
                controller.setMediaItems(songs.map { it.toMediaItem() }, restoredIndex, position)
                controller.prepare()
                _state.value = PlayerState(songs[restoredIndex], false, position, queue = songs, index = restoredIndex)
            }, executor)
        }
    }

    fun playQueue(songs: List<SongEntity>, startIndex: Int) {
        controllerFuture.addListener({
            val controller = controllerFuture.get()
            attachListener(controller)
            controller.setMediaItems(songs.map { it.toMediaItem() }, startIndex, 0)
            controller.prepare()
            controller.play()
            _state.value = PlayerState(songs.getOrNull(startIndex), true, queue = songs, index = startIndex, mode = _state.value.mode)
            progressJob?.cancel()
            progressJob = scope.launch {
                while (isActive) {
                    _state.value = _state.value.copy(playing = controller.isPlaying, position = controller.currentPosition, duration = controller.duration.coerceAtLeast(0))
                    delay(500)
                }
            }
        }, executor)
    }

    fun toggle() {
        controllerFuture.addListener({
            val controller = controllerFuture.get()
            if (controller.isPlaying) controller.pause() else controller.play()
            _state.value = _state.value.copy(playing = controller.isPlaying)
        }, executor)
    }

    fun next() = controllerFuture.addListener({ controllerFuture.get().seekToNextMediaItem() }, executor)
    fun previous() = controllerFuture.addListener({ controllerFuture.get().seekToPreviousMediaItem() }, executor)
    fun seek(position: Long) = controllerFuture.addListener({ controllerFuture.get().seekTo(position) }, executor)
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
        controller.shuffleModeEnabled = mode == PlaybackMode.Shuffle
        controller.repeatMode = when (mode) {
            PlaybackMode.RepeatOne -> Player.REPEAT_MODE_ONE
            PlaybackMode.Shuffle -> Player.REPEAT_MODE_ALL
            PlaybackMode.Sequential -> Player.REPEAT_MODE_OFF
        }
        _state.value = _state.value.copy(mode = mode)
    }, executor)

    fun release() { progressJob?.cancel(); scope.cancel(); if (controllerFuture.isDone) controllerFuture.get().removeListener(listener); MediaController.releaseFuture(controllerFuture) }

    private fun attachListener(controller: MediaController) {
        if (!listenerAttached) {
            controller.addListener(listener)
            listenerAttached = true
        }
    }

    private fun SongEntity.toMediaItem() = MediaItem.Builder()
        .setMediaId(mediaStoreId.toString())
        .setUri(uri)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(artist).setAlbumTitle(album).build())
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
