package life.xstar.music.player.cast

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import life.xstar.music.player.data.local.SongEntity
import life.xstar.music.player.player.PlaybackMode

class CastSessionController(context: Context) {
    enum class Connection { Disconnected, Connecting, Connected }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val server = LocalMusicServer(context)
    private val discovery = SsdpDiscovery(context)

    private val _state = MutableStateFlow(CastSessionState())
    val state: StateFlow<CastSessionState> = _state.asStateFlow()

    private var renderer: RendererClient? = null
    private var pollJob: Job? = null

    fun release() {
        stopPolling()
        server.stop()
        scope.cancel()
    }

    suspend fun discoverDevices(): List<CastDevice> = discovery.discover()

    fun connect(device: CastDevice, queue: List<SongEntity>, index: Int, mode: PlaybackMode = PlaybackMode.Sequential, playing: Boolean = true) {
        if (queue.isEmpty() || index !in queue.indices) {
            _state.value = _state.value.copy(connection = Connection.Disconnected, error = "没有可投屏的歌曲")
            return
        }
        stopPolling()
        _state.value = CastSessionState(
            connection = Connection.Connecting,
            device = device,
            queue = queue,
            index = index,
            song = queue[index],
            durationMs = queue[index].durationMs,
            mode = mode,
            playing = false
        )
        scope.launch {
            val current = _state.value.song ?: return@launch
            val started = server.start()
            if (!started) {
                fail("无法启动本地音频服务，请检查网络")
                return@launch
            }
            val url = server.register(
                LocalMusicServer.StreamSpec(current.mediaStoreId, current.uri, current.mimeType, current.fileSize)
            )
            if (url == null) {
                fail("无法获取局域网地址，请检查 Wi-Fi 连接")
                return@launch
            }
            val client = RendererClient(device)
            val ok = client.setTransportUri(url)
            if (!ok) {
                fail("设备 ${device.friendlyName} 没有正确响应")
                return@launch
            }
            renderer = client
            _state.value = _state.value.copy(connection = Connection.Connected, streamingUrl = url)
            if (playing) playInternal()
            startPolling()
        }
    }

    fun togglePlayPause() {
        val snapshot = _state.value
        if (snapshot.connection != Connection.Connected || snapshot.song == null) return
        val client = renderer ?: return
        scope.launch {
            if (snapshot.playing) {
                client.pause()
                _state.value = _state.value.copy(playing = false)
            } else {
                playInternal()
            }
        }
    }

    fun playQueueOnCast(queue: List<SongEntity>, index: Int, playing: Boolean = true) {
        if (queue.isEmpty() || index !in queue.indices) return
        val safeIndex = index.coerceIn(0, queue.lastIndex)
        _state.value = _state.value.copy(queue = queue, index = safeIndex, song = queue[safeIndex], positionMs = 0, durationMs = queue[safeIndex].durationMs, error = null)
        pushTrack(safeIndex)
    }

    fun next() {
        val snapshot = _state.value
        if (snapshot.connection != Connection.Connected || snapshot.queue.isEmpty()) return
        val nextIndex = when (snapshot.mode) {
            PlaybackMode.RepeatOne -> snapshot.index
            PlaybackMode.Shuffle -> {
                if (snapshot.queue.size <= 1) snapshot.index
                else snapshot.queue.indices.filter { it != snapshot.index }.random()
            }
            PlaybackMode.Sequential -> if (snapshot.index < snapshot.queue.lastIndex) snapshot.index + 1 else return
        }
        if (nextIndex == snapshot.index && snapshot.queue.size <= 1) return
        pushTrack(nextIndex)
    }

    fun previous() {
        val snapshot = _state.value
        if (snapshot.connection != Connection.Connected || snapshot.queue.isEmpty()) return
        val prevIndex = if (snapshot.index > 0) snapshot.index - 1 else 0
        pushTrack(prevIndex)
    }

    fun seekTo(ms: Long) {
        val snapshot = _state.value
        if (snapshot.connection != Connection.Connected) return
        val client = renderer ?: return
        _state.value = snapshot.copy(positionMs = ms.coerceIn(0, snapshot.durationMs.coerceAtLeast(0)))
        scope.launch { client.seek((ms.coerceAtLeast(0) / 1000)) }
    }

    fun disconnect() {
        stopPolling()
        val snapshot = _state.value
        scope.launch {
            runCatching { renderer?.stop() }
            renderer = null
            server.stop()
            _state.value = CastSessionState(mode = snapshot.mode)
        }
    }

    fun error(): String? = _state.value.error

    private fun pushTrack(index: Int) {
        val snapshot = _state.value
        val song = snapshot.queue.getOrNull(index) ?: return
        val client = renderer ?: return
        _state.value = snapshot.copy(index = index, song = song, playing = false, positionMs = 0, durationMs = song.durationMs, error = null)
        scope.launch {
            val url = server.register(LocalMusicServer.StreamSpec(song.mediaStoreId, song.uri, song.mimeType, song.fileSize)) ?: return@launch
            val ok = client.setTransportUri(url)
            _state.value = _state.value.copy(streamingUrl = url)
            if (!ok) {
                _state.value = _state.value.copy(error = "无法在设备上播放 ${song.title}")
            } else {
                playInternal()
            }
        }
    }

    private suspend fun playInternal() {
        val client = renderer ?: return
        val ok = client.play()
        _state.value = _state.value.copy(playing = ok)
        if (!ok) _state.value = _state.value.copy(error = "设备无法开始播放")
    }

    private fun startPolling() {
        stopPolling()
        pollJob = scope.launch {
            var lastTransport: RendererClient.Companion.TransportState? = null
            while (isActive) {
                delay(2_500)
                val client = renderer ?: break
                val transport = client.transportState()
                val info = client.positionInfo()
                val snapshot = _state.value
                val wasPlaying = snapshot.playing
                val playing = transport == RendererClient.Companion.TransportState.Playing
                _state.value = snapshot.copy(
                    playing = playing,
                    positionMs = if (playing) info.positionMs else snapshot.positionMs,
                    durationMs = info.durationMs.takeIf { it > 0 } ?: snapshot.durationMs
                )
                val reachedEnd = lastTransport == RendererClient.Companion.TransportState.Playing &&
                    transport == RendererClient.Companion.TransportState.Stopped && wasPlaying
                if (reachedEnd) {
                    lastTransport = null
                    if (!advanceAfterEnd()) break
                    continue
                }
                lastTransport = transport
            }
        }
    }

    private fun advanceAfterEnd(): Boolean {
        val snapshot = _state.value
        if (snapshot.connection != Connection.Connected || snapshot.queue.isEmpty()) return false
        return when (snapshot.mode) {
            PlaybackMode.RepeatOne -> {
                pushTrack(snapshot.index)
                true
            }
            PlaybackMode.Shuffle -> {
                pushTrack(if (snapshot.queue.size <= 1) snapshot.index else snapshot.queue.indices.filter { it != snapshot.index }.random())
                true
            }
            PlaybackMode.Sequential -> {
                if (snapshot.index < snapshot.queue.lastIndex) {
                    pushTrack(snapshot.index + 1)
                    true
                } else {
                    stopPolling()
                    _state.value = snapshot.copy(playing = false, error = null)
                    false
                }
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private fun fail(message: String) {
        stopPolling()
        val snapshot = _state.value
        _state.value = snapshot.copy(connection = Connection.Disconnected, error = message)
        server.stop()
        renderer = null
    }
}

data class CastSessionState(
    val connection: CastSessionController.Connection = CastSessionController.Connection.Disconnected,
    val device: CastDevice? = null,
    val queue: List<SongEntity> = emptyList(),
    val index: Int = -1,
    val song: SongEntity? = null,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val mode: PlaybackMode = PlaybackMode.Sequential,
    val streamingUrl: String? = null,
    val error: String? = null
)
