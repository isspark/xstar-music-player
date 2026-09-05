package life.xstar.music.player.together

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import life.xstar.music.player.cast.LocalMusicServer
import life.xstar.music.player.data.local.SongEntity
import life.xstar.music.player.data.repository.MusicRepository
import life.xstar.music.player.player.PlaybackService
import kotlin.coroutines.resume

class TogetherSessionController(
    private val context: Context,
    private val repository: MusicRepository
) {
    companion object {
        private const val TAG = "TogetherSessionController"
        private const val SNAPSHOT_INTERVAL_MS = 1_000L
        private const val HEARTBEAT_INTERVAL_MS = 8_000L
        private const val WATCHDOG_INTERVAL_MS = 5_000L
        private const val WATCHDOG_TIMEOUT_MS = 45_000L
        private const val MAX_MESSAGES = 200
        private fun generateDeviceId(): String =
            "peer-" + java.lang.Long.toHexString(System.currentTimeMillis()) + "-" + Integer.toHexString((Math.random() * 0xFFFFFF).toInt())
    }

    private val executor = ContextCompat.getMainExecutor(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val server = LocalMusicServer(context)
    private val nat = NatProbe(context)

    private val _state = MutableStateFlow(TogetherSessionState())
    val state: StateFlow<TogetherSessionState> = _state.asStateFlow()

    private var listener: TogetherListener? = null
    private var link: TogetherLink? = null
    private var linkReaderJob: Job? = null
    private var acceptJob: Job? = null
    private var hostPublishJob: Job? = null
    private var heartbeatJob: Job? = null
    private var watchdogJob: Job? = null
    private var advertiseThread: Thread? = null
    private var natJob: Job? = null

    private val deviceId: String = generateDeviceId()
    private var nickname: String = android.os.Build.MODEL.ifBlank { "手机" }
    private var roomInvite: TogetherInvite? = null

    private var relayHost: String = ""
    private var relayPort: Int = 0
    private var serverLink: TogetherLink? = null
    private var tunnelThread: Thread? = null
    private var serverRetryScheduled = false

    private var activeMediaBase: String = ""
    private var appliedSourceId: Long = -1L

    private val tokenCache = mutableMapOf<Long, Pair<String, String>>()
    private var hostQueueCache: List<TogetherQueueEntry> = emptyList()
    private var hostQueueCacheAt = 0L
    private var snapshotSeq = 0L

    private val controllerFuture: ListenableFuture<MediaController> =
        MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java)))
            .buildAsync()

    fun setNickname(value: String) {
        nickname = value.trim().ifBlank { android.os.Build.MODEL.ifBlank { "手机" } }
    }

    /** Configures the optional public relay server ("host:port"); blank disables it. */
    fun setRelayServer(address: String) {
        val parsed = splitHostPort(address)
        if (parsed == null) {
            relayHost = ""
            relayPort = 0
        } else {
            relayHost = parsed.first
            relayPort = parsed.second
        }
    }

    val relayEnabled: Boolean get() = relayHost.isNotBlank() && relayPort > 0

    // ---------------- Host ----------------

    fun startSession(mode: TogetherConnectMode) {
        if (_state.value.connection != TogetherConnection.Idle) return
        val code = TogetherProtocol.newCode()
        _state.value = TogetherSessionState(
            role = TogetherRole.Host,
            connection = TogetherConnection.Listening,
            code = code,
            me = TogetherPeer(deviceId, nickname),
            connectMode = mode,
            roomOpen = true
        )
        scope.launch {
            val started = withContext(Dispatchers.IO) { server.start() }
            if (!started) {
                fail("无法启动本地音频服务，请检查网络")
                return@launch
            }
            tokenCache.clear()
            listener = TogetherListener()
            val invite = buildInvite(code)
            roomInvite = invite
            _state.value = _state.value.copy(inviteText = TogetherProtocol.encodeInvite(invite))
            startAdvertise(invite)
            startAcceptLoop()
            if (mode == TogetherConnectMode.MultiPath) {
                if (relayEnabled) openServerRoom(code) else resolvePublicCandidate(code)
            }
            startHeartbeatAndWatchdog()
        }
    }

    private fun openServerRoom(code: String) {
        tunnelThread?.let { runCatching { it.interrupt() } }
        tunnelThread = null
        scope.launch {
            val host = relayHost
            val port = relayPort
            if (host.isBlank() || port <= 0) return@launch
            val line = "CTRL $code host $nickname"
            val ctrl = withContext(Dispatchers.IO) {
                connectServerControl(host, port, line)
            }
            if (ctrl == null) {
                _state.value = _state.value.copy(error = "无法连接中转服务器 $host:$port")
                return@launch
            }
            serverLink?.let { runCatching { it.close() } }
            serverLink = ctrl
            tunnelThread = startServerTunnelPump(host, port, code, server.port) { !_state.value.roomOpen }
            val invite = roomInvite
            if (invite != null) {
                val updated = invite.copy(
                    serverControlHost = "$host:$port",
                    serverHttpBase = "http://$host:$port/room/$code"
                )
                roomInvite = updated
                _state.value = _state.value.copy(inviteText = TogetherProtocol.encodeInvite(updated))
            }
            scope.launch { runLinkReader(ctrl) }
        }
    }

    private fun scheduleServerReopen(code: String?) {
        if (code.isNullOrBlank() || serverRetryScheduled || !relayEnabled) return
        serverRetryScheduled = true
        scope.launch {
            delay(4_000)
            serverRetryScheduled = false
            if (_state.value.isHost && _state.value.connection == TogetherConnection.Listening && _state.value.roomOpen) {
                openServerRoom(code)
            }
        }
    }

    private suspend fun buildInvite(code: String): TogetherInvite {
        val ip = localIp()
        val current = _state.value
        return TogetherInvite(
            code = code,
            nickname = nickname,
            mode = current.connectMode.name,
            controlHost = "$ip:${listener?.port ?: 0}",
            streamHost = ip,
            streamPort = server.port,
            ts = System.currentTimeMillis()
        )
    }

    private suspend fun resolvePublicCandidate(code: String) {
        natJob?.cancel()
        natJob = scope.launch(Dispatchers.IO) {
            val endpoint = nat.discoverEndpoint() ?: return@launch
            val ip = localIp()
            val controlOk = nat.addMapping(endpoint, "TCP", listener?.port ?: 0, ip, listener?.port ?: 0)
            val streamOk = nat.addMapping(endpoint, "TCP", server.port, ip, server.port)
            val port = listener?.port ?: 0
            if (!controlOk || !streamOk || endpoint.externalIp.isBlank() || port == 0) return@launch
            val base = roomInvite ?: return@launch
            val updated = base.copy(
                extControlHost = "${endpoint.externalIp}:$port",
                extStreamHost = endpoint.externalIp,
                extStreamPort = server.port,
                ts = System.currentTimeMillis()
            )
            withContext(Dispatchers.Main) {
                roomInvite = updated
                _state.value = _state.value.copy(inviteText = TogetherProtocol.encodeInvite(updated))
            }
        }
    }

    private fun startAdvertise(invite: TogetherInvite) {
        stopAdvertise()
        advertiseThread = TogetherDiscovery.advertiseAsync(context, invite) {
            _state.value.connection != TogetherConnection.Listening || !_state.value.isHost
        }
    }

    private fun stopAdvertise() {
        advertiseThread?.interrupt()
        advertiseThread = null
    }

    private fun startAcceptLoop() {
        acceptJob?.cancel()
        acceptJob = scope.launch(Dispatchers.IO) {
            val server = listener ?: return@launch
            while (isActive && _state.value.roomOpen) {
            val accepted = server.acceptBlocking() ?: continue
            Log.i(TAG, "host accepted connection from ${accepted.remote}")
            withContext(Dispatchers.Main) {
                    if (!_state.value.roomOpen || _state.value.connection == TogetherConnection.Connected) {
                        accepted.close()
                        return@withContext
                    }
                    stopAdvertise()
                    link?.close()
                    link = accepted
                    _state.value = _state.value.copy(
                        connection = TogetherConnection.Connected,
                        memberCount = 1,
                        peer = null,
                        error = null
                    )
                    linkReaderJob?.cancel()
                    linkReaderJob = scope.launch { runLinkReader(accepted) }
                    startHostPublishIfNeeded()
                }
                if (!_state.value.roomOpen) break
            }
        }
    }

    private fun startHostPublishIfNeeded() {
        if (hostPublishJob?.isActive == true) return
        hostPublishJob = scope.launch {
            while (isActive && _state.value.isHost && _state.value.connection == TogetherConnection.Connected) {
                pushSnapshot()
                delay(SNAPSHOT_INTERVAL_MS)
            }
        }
    }

    private suspend fun pushSnapshot() {
        val current = link ?: return
        val controller = controllerOrNull() ?: return
        if (!_state.value.isHost || _state.value.connection != TogetherConnection.Connected) return
        val playing = controller.isPlaying
        val position = controller.currentPosition
        val duration = controller.duration.coerceAtLeast(0)
        val index = controller.currentMediaItemIndex
        val mode = mediaMode(controller)
        val mediaId = controller.currentMediaItem?.mediaId?.toLongOrNull()
        val song = mediaId?.let { snapshotSongFor(it, controller) }
        val now = System.currentTimeMillis()
        if (now - hostQueueCacheAt > 12_000L || hostQueueCache.isEmpty()) {
            hostQueueCache = buildQueue(controller)
            hostQueueCacheAt = now
        }
        snapshotSeq++
        current.write(
            TogetherMessage.Snapshot(
                positionMs = position,
                durationMs = duration,
                playing = playing,
                song = song,
                queue = hostQueueCache,
                index = index,
                mode = mode,
                seq = snapshotSeq
            )
        )
        _state.value = _state.value.copy(sharedQueue = hostQueueCache, sharedIndex = index, sharedMode = mode)
    }

    private suspend fun snapshotSongFor(sourceId: Long, controller: MediaController): TogetherSong? {
        val entity = withContext(Dispatchers.IO) { repository.getSong(sourceId) }
        val meta = controller.currentMediaItem?.mediaMetadata
        val (audioToken, artToken) = tokensFor(entity, sourceId)
        val duration = entity?.durationMs ?: controller.duration.coerceAtLeast(0)
        return TogetherSong(
            sourceId = sourceId,
            title = entity?.title ?: meta?.title?.toString().orEmpty(),
            artist = entity?.artist ?: meta?.artist?.toString().orEmpty(),
            album = entity?.album ?: meta?.albumTitle?.toString().orEmpty(),
            durationMs = duration,
            quality = entity?.audioQuality ?: "Standard",
            token = audioToken,
            artToken = artToken
        )
    }

    private suspend fun tokensFor(entity: SongEntity?, sourceId: Long): Pair<String, String> {
        tokenCache[sourceId]?.let { return it }
        if (entity == null) {
            tokenCache[sourceId] = "" to ""
            return "" to ""
        }
        val audio = withContext(Dispatchers.IO) {
            server.registerForToken(LocalMusicServer.StreamSpec(entity.mediaStoreId, entity.uri, entity.mimeType, entity.fileSize))
        }
        val art = withContext(Dispatchers.IO) { server.registerArtForToken(entity.albumArtUri) }
        val result = (audio ?: "") to (art ?: "")
        tokenCache[sourceId] = result
        return result
    }

    private fun buildQueue(controller: MediaController): List<TogetherQueueEntry> {
        val result = ArrayList<TogetherQueueEntry>()
        val count = runCatching { controller.mediaItemCount }.getOrDefault(0)
        for (i in 0 until count) {
            val item = runCatching { controller.getMediaItemAt(i) }.getOrNull() ?: continue
            val id = item.mediaId.toLongOrNull() ?: continue
            val meta = item.mediaMetadata
            result += TogetherQueueEntry(id, meta.title?.toString().orEmpty(), meta.artist?.toString().orEmpty(), meta.albumTitle?.toString().orEmpty())
        }
        return result
    }

    private fun mediaMode(controller: MediaController): String = when {
        controller.shuffleModeEnabled -> "Shuffle"
        controller.repeatMode == Player.REPEAT_MODE_ONE -> "RepeatOne"
        else -> "Sequential"
    }

    private suspend fun applyMode(controller: MediaController, mode: String) {
        controller.shuffleModeEnabled = mode == "Shuffle"
        controller.repeatMode = when (mode) {
            "Shuffle" -> Player.REPEAT_MODE_ALL
            "RepeatOne" -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    // ---------------- Member ----------------

    fun joinByCode(code: String, mode: TogetherConnectMode) {
        if (_state.value.connection != TogetherConnection.Idle) return
        _state.value = TogetherSessionState(
            role = TogetherRole.Member,
            connection = TogetherConnection.Connecting,
            me = TogetherPeer(deviceId, nickname),
            connectMode = mode
        )
        scope.launch {
            val normalized = code.trim().uppercase()
            if (normalized.isEmpty()) {
                fail("请输入有效的邀请码")
                return@launch
            }
            val invites = runCatching { TogetherDiscovery.locateInvites(context, normalized) }.getOrDefault(emptyList())
            val invite = invites.firstOrNull()
            if (invite == null) {
                if (mode == TogetherConnectMode.MultiPath && relayEnabled) {
                    join(TogetherInvite(code = normalized, nickname = nickname, mode = mode.name, controlHost = "", streamHost = "", streamPort = 0))
                } else {
                    fail("未在局域网发现房间 $normalized\n请确认双方在同一 Wi-Fi/热点，或让房主提供二维码")
                }
                return@launch
            }
            join(invite)
        }
    }

    fun joinByInviteText(text: String, mode: TogetherConnectMode) {
        val invite = TogetherProtocol.decodeInvite(text)
        if (invite == null) {
            _state.value = TogetherSessionState(connectMode = mode, error = "邀请二维码内容无效")
            return
        }
        if (_state.value.connection != TogetherConnection.Idle) return
        _state.value = TogetherSessionState(
            role = TogetherRole.Member,
            connection = TogetherConnection.Connecting,
            me = TogetherPeer(deviceId, nickname),
            connectMode = mode
        )
        scope.launch { join(invite) }
    }

    private data class JoinCandidate(
        val host: String,
        val port: Int,
        val mediaBase: String,
        val viaServer: Boolean,
        val timeoutMs: Int
    )

    private fun buildCandidates(invite: TogetherInvite): List<JoinCandidate> {
        val mode = _state.value.connectMode
        val result = mutableListOf<JoinCandidate>()

        val lanHost = splitHostPort(invite.controlHost)
        if (lanHost != null && lanHost.second > 0 && invite.streamPort > 0 && invite.streamHost.isNotBlank()) {
            result += JoinCandidate(lanHost.first, lanHost.second, "http://${invite.streamHost}:${invite.streamPort}", viaServer = false, timeoutMs = 2_000)
        }
        if (mode == TogetherConnectMode.MultiPath) {
            val inviteServer = splitHostPort(invite.serverControlHost)
            if (inviteServer != null && inviteServer.second > 0 && invite.serverHttpBase.isNotBlank()) {
                result += JoinCandidate(inviteServer.first, inviteServer.second, invite.serverHttpBase, viaServer = true, timeoutMs = 6_000)
            }
            if (relayEnabled) {
                val ownAlready = inviteServer != null && inviteServer.second > 0 &&
                    invite.serverControlHost == "$relayHost:$relayPort"
                if (!ownAlready) {
                    result += JoinCandidate(relayHost, relayPort, "http://$relayHost:$relayPort/room/${invite.code}", viaServer = true, timeoutMs = 6_000)
                }
            } else {
                // No relay configured: keep the classic UPnP public-mapping candidate.
                val extHost = splitHostPort(invite.extControlHost)
                if (extHost != null && extHost.second > 0 && invite.extStreamPort > 0 && invite.extStreamHost.isNotBlank()) {
                    result += JoinCandidate(extHost.first, extHost.second, "http://${invite.extStreamHost}:${invite.extStreamPort}", viaServer = false, timeoutMs = 6_000)
                }
            }
        }
        return result
    }

    private suspend fun join(invite: TogetherInvite) {
        val candidates = buildCandidates(invite)
        Log.i(TAG, "join code=${invite.code} mode=${_state.value.connectMode} candidates=${candidates.joinToString { "${it.host}:${it.port} viaServer=${it.viaServer}" }}")
        if (candidates.isEmpty()) {
            fail("无法解析任何连接地址，请重新获取邀请二维码")
            return
        }
        for (candidate in candidates) {
            Log.i(TAG, "join trying ${candidate.host}:${candidate.port} viaServer=${candidate.viaServer}")
            val established = withContext(Dispatchers.IO) {
                if (candidate.viaServer) {
                    connectServerControl(candidate.host, candidate.port, "CTRL ${invite.code} member $nickname", candidate.timeoutMs)
                } else {
                    TogetherLink.connect(candidate.host, candidate.port, candidate.timeoutMs)
                }
            }
            Log.i(TAG, "join candidate ${candidate.host}:${candidate.port} result=${established != null}")
            if (established != null) {
                activeMediaBase = candidate.mediaBase
                memberConnected(established, invite)
                return
            }
        }
        val mode = _state.value.connectMode
        fail(
            if (mode == TogetherConnectMode.Lan) "无法连接到房主\n请确认两台设备在同一 Wi-Fi 或热点下"
            else "所有直连通道均失败\n请确认中转服务器可达，或房主已开启路由器 UPnP/端口转发"
        )
    }

    private suspend fun memberConnected(established: TogetherLink, invite: TogetherInvite) {
        val ok = established.write(
            TogetherMessage.Hello(TogetherProtocol.VERSION, "member", nickname, _state.value.connectMode.name)
        )
        Log.i(TAG, "memberConnected hello sent=$ok")
        if (!ok) {
            established.close()
            fail("连接失败，请重试")
            return
        }
        val welcome = withContext(Dispatchers.IO) { established.read() }
        Log.i(TAG, "memberConnected welcome=$welcome")
        if (welcome !is TogetherMessage.Welcome) {
            established.close()
            fail("房主未正确响应")
            return
        }
        link = established
        _state.value = _state.value.copy(
            connection = TogetherConnection.Connected,
            code = invite.code,
            peer = welcome.host,
            error = null
        )
        linkReaderJob?.cancel()
        linkReaderJob = scope.launch { runLinkReader(established) }
        startHeartbeatAndWatchdog()
    }

    // ---------------- Link reading (shared) ----------------

    private suspend fun runLinkReader(reader: TogetherLink) {
        while (currentCoroutineContext().isActive) {
            val msg = withContext(Dispatchers.IO) { reader.read() }
            Log.i(TAG, "link reader got=$msg")
            if (msg == null) break
            when (msg) {
                is TogetherMessage.Hello -> onHostGotHello(reader, msg)
                is TogetherMessage.Welcome -> Unit
                is TogetherMessage.Snapshot -> if (_state.value.isMember) applySnapshot(msg)
                is TogetherMessage.Reaction -> emitReaction(msg)
                is TogetherMessage.Chat -> appendIncomingChat(msg)
                is TogetherMessage.Control -> if (_state.value.isHost) applyRemoteControl(msg)
                is TogetherMessage.Ping -> reader.write(TogetherMessage.Pong(msg.t))
                is TogetherMessage.Pong -> Unit
                is TogetherMessage.Bye -> {
                    endSessionInternal(msg.reason)
                    return
                }
                else -> Unit
            }
        }
        onLinkLost(reader)
    }

    private suspend fun onHostGotHello(reader: TogetherLink, hello: TogetherMessage.Hello) {
        Log.i(TAG, "host got hello role=${hello.role} nickname=${hello.nickname} version=${hello.version}")
        val current = link
        if (current != null && current !== reader) {
            reader.write(TogetherMessage.Bye("房间已有成员"))
            reader.close()
            if (reader === serverLink) serverLink = null
            return
        }
        link = reader
        stopAdvertise()
        _state.value = _state.value.copy(
            connection = TogetherConnection.Connected,
            memberCount = 1,
            peer = TogetherPeer(generateDeviceId(), hello.nickname),
            error = null
        )
        startHostPublishIfNeeded()
        val host = _state.value.me ?: TogetherPeer(deviceId, nickname)
        reader.write(TogetherMessage.Welcome(host, TogetherPeer(generateDeviceId(), hello.nickname)))
        pushSnapshot()
    }

    private suspend fun onLinkLost(reader: TogetherLink) {
        if (_state.value.isHost && reader === link && _state.value.connection == TogetherConnection.Connected) {
            val lostServer = link === serverLink
            val roomCode = _state.value.code
            _state.value = _state.value.copy(connection = TogetherConnection.Listening, memberCount = 0, peer = null)
            link = null
            if (lostServer) serverLink = null
            hostPublishJob?.cancel()
            hostPublishJob = null
            linkReaderJob = null
            val invite = roomInvite
            if (invite != null && _state.value.roomOpen) startAdvertise(invite)
            if (lostServer) scheduleServerReopen(roomCode)
        } else if (_state.value.isHost && reader === serverLink) {
            serverLink = null
            val roomCode = _state.value.code
            if (_state.value.roomOpen && _state.value.connection == TogetherConnection.Listening && relayEnabled) {
                scheduleServerReopen(roomCode)
            }
        } else if (_state.value.isMember && _state.value.connection == TogetherConnection.Connected && reader === link) {
            endSessionInternal("与房主断开连接")
        }
    }

    private suspend fun applyRemoteControl(msg: TogetherMessage.Control) {
        val controller = controllerOrNull() ?: return
        when (msg.action) {
            "toggle" -> if (controller.isPlaying) controller.pause() else controller.play()
            "pause" -> controller.pause()
            "play" -> controller.play()
            "next" -> if (controller.hasNextMediaItem()) controller.seekToNextMediaItem()
            "previous" -> if (controller.hasPreviousMediaItem()) controller.seekToPreviousMediaItem()
            "seek" -> if (msg.positionMs >= 0) controller.seekTo(msg.positionMs)
            "playIndex" -> if (msg.index in 0 until controller.mediaItemCount) {
                controller.seekTo(msg.index, 0)
                controller.play()
            }
            "setMode" -> if (msg.mode.isNotBlank()) applyMode(controller, msg.mode)
        }
        pushSnapshot()
    }

    private suspend fun applySnapshot(snap: TogetherMessage.Snapshot) {
        val song = snap.song
        val controller = controllerOrNull() ?: return
        if (song == null || song.token.isBlank()) {
            if (controller.isPlaying) controller.pause()
            _state.value = _state.value.copy(
                sharedQueue = snap.queue,
                sharedIndex = snap.index,
                sharedMode = snap.mode,
                sharedSong = null
            )
            return
        }
        val entity = togetherSongToEntity(song)
        if (appliedSourceId != song.sourceId || controller.mediaItemCount == 0) {
            controller.setMediaItems(listOf(entity.toMediaItem()), 0, 0)
            controller.prepare()
            controller.seekTo(snap.positionMs.coerceAtLeast(0))
            if (snap.playing) controller.play() else controller.pause()
            appliedSourceId = song.sourceId
        } else {
            if (snap.playing && !controller.isPlaying) controller.play()
            else if (!snap.playing && controller.isPlaying) controller.pause()
            if (snap.durationMs > 0 && kotlin.math.abs(snap.positionMs - controller.currentPosition) > 800) {
                controller.seekTo(snap.positionMs.coerceIn(0, snap.durationMs - 1))
            }
        }
        _state.value = _state.value.copy(
            sharedQueue = snap.queue,
            sharedIndex = snap.index,
            sharedMode = snap.mode,
            sharedSong = song
        )
    }

    private fun togetherSongToEntity(song: TogetherSong): SongEntity {
        val base = activeMediaBase.trim().trimEnd('/')
        val url = "$base/stream?t=${song.token}"
        val artUrl = if (song.artToken.isNotBlank()) "$base/art?t=${song.artToken}" else null
        return SongEntity(
            mediaStoreId = song.sourceId,
            title = song.title,
            artist = song.artist,
            album = song.album,
            durationMs = song.durationMs,
            uri = url,
            mimeType = null,
            audioQuality = song.quality,
            albumArtUri = artUrl,
            sourceFolderUri = "together",
            dateAdded = 0
        )
    }

    fun currentSharedSongEntity(): SongEntity? = _state.value.sharedSong?.let { togetherSongToEntity(it) }

    // ---------------- Public actions ----------------

    fun sendReaction(reaction: TogetherReaction) {
        if (_state.value.connection != TogetherConnection.Connected) return
        val msg = TogetherMessage.Reaction(reaction.name, nickname)
        scope.launch {
            val sent = link?.write(msg) == true
            if (sent) appendMessage(TogetherChatKind.Reaction, nickname, reaction.symbol, fromSelf = true)
        }
    }

    fun sendMessage(text: String) {
        if (_state.value.connection != TogetherConnection.Connected) return
        val trimmed = text.trim().take(300)
        if (trimmed.isEmpty()) return
        val msg = TogetherMessage.Chat(nickname, trimmed, System.currentTimeMillis())
        scope.launch {
            val sent = link?.write(msg) == true
            if (sent) appendMessage(TogetherChatKind.Text, nickname, trimmed, fromSelf = true)
        }
    }

    fun sendControl(action: String, index: Int = -1, positionMs: Long = -1, mode: String = "") {
        if (_state.value.role == TogetherRole.Member && _state.value.connection == TogetherConnection.Connected) {
            scope.launch { link?.write(TogetherMessage.Control(action, index, positionMs, mode)) }
        }
    }

    fun notifyHostLocalChange() {
        if (_state.value.isHost && _state.value.connection == TogetherConnection.Connected) {
            scope.launch { pushSnapshot() }
        }
    }

    fun endSession() {
        endSessionInternal("我离开了会话")
    }

    private fun endSessionInternal(reason: String) {
        val byeLink = link
        link = null
        if (byeLink != null) {
            scope.launch { runCatching { byeLink.write(TogetherMessage.Bye(reason)) }; runCatching { byeLink.close() } }
        }
        linkReaderJob?.cancel()
        linkReaderJob = null
        acceptJob?.cancel()
        acceptJob = null
        hostPublishJob?.cancel()
        hostPublishJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        watchdogJob?.cancel()
        watchdogJob = null
        natJob?.cancel()
        natJob = null
        stopAdvertise()
        listener?.close()
        listener = null
        serverLink?.let { runCatching { it.close() } }
        serverLink = null
        tunnelThread?.let { runCatching { it.interrupt() } }
        tunnelThread = null
        serverRetryScheduled = false
        appliedSourceId = -1L
        val connectMode = _state.value.connectMode
        val wasMember = _state.value.isMember
        scope.launch { server.stop() }
        if (wasMember) {
            scope.launch {
                val c = controllerOrNull()
                if (c != null) {
                    c.pause()
                    c.clearMediaItems()
                }
            }
        }
        _state.value = TogetherSessionState(connectMode = connectMode)
    }

    private fun startHeartbeatAndWatchdog() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                if (_state.value.connection == TogetherConnection.Connected) {
                    link?.write(TogetherMessage.Ping(System.currentTimeMillis()))
                }
                delay(HEARTBEAT_INTERVAL_MS)
            }
        }
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (isActive) {
                delay(WATCHDOG_INTERVAL_MS)
                if (_state.value.connection == TogetherConnection.Connected) {
                    link?.let { l ->
                        if (l.closed || System.currentTimeMillis() - l.lastActivity > WATCHDOG_TIMEOUT_MS) {
                            endSessionInternal("连接超时")
                        }
                    }
                }
            }
        }
    }

    private fun emitReaction(msg: TogetherMessage.Reaction) {
        val reaction = runCatching { TogetherReaction.valueOf(msg.reaction) }.getOrNull() ?: return
        appendMessage(TogetherChatKind.Reaction, msg.nickname, reaction.symbol, fromSelf = false)
    }

    private fun appendIncomingChat(msg: TogetherMessage.Chat) {
        appendMessage(TogetherChatKind.Text, msg.nickname, msg.text, fromSelf = false)
    }

    private fun appendMessage(kind: TogetherChatKind, fromNickname: String, text: String, fromSelf: Boolean) {
        val time = System.currentTimeMillis()
        _state.update { current ->
            current.copy(
                messages = (current.messages + TogetherChatMessage(kind, fromNickname, text, fromSelf, time, time)).takeLast(MAX_MESSAGES)
            )
        }
    }

    private suspend fun controllerOrNull(): MediaController? = suspendCancellableCoroutine { cont ->
        if (controllerFuture.isDone) {
            val c = runCatching { controllerFuture.get() }.getOrNull()
            if (!cont.isCancelled) cont.resume(c)
        } else {
            controllerFuture.addListener({
                val c = runCatching { controllerFuture.get() }.getOrNull()
                if (!cont.isCancelled) cont.resume(c)
            }, executor)
        }
    }

    private suspend fun localIp(): String = withContext(Dispatchers.IO) {
        val ip = preferredLanIpV4() ?: localIpV4Fallback() ?: "127.0.0.1"
        Log.i(TAG, "localIp=$ip")
        ip
    }

    private fun fail(message: String) {
        stopBackground()
        val connectMode = _state.value.connectMode
        _state.value = TogetherSessionState(connectMode = connectMode, error = message)
    }

    private fun stopBackground() {
        linkReaderJob?.cancel()
        linkReaderJob = null
        acceptJob?.cancel()
        acceptJob = null
        hostPublishJob?.cancel()
        hostPublishJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        watchdogJob?.cancel()
        watchdogJob = null
        natJob?.cancel()
        natJob = null
        stopAdvertise()
        runCatching { link?.close() }
        link = null
        serverLink?.let { runCatching { it.close() } }
        serverLink = null
        tunnelThread?.let { runCatching { it.interrupt() } }
        tunnelThread = null
        serverRetryScheduled = false
        listener?.close()
        listener = null
    }

    fun release() {
        stopBackground()
        runCatching { if (controllerFuture.isDone) controllerFuture.get().release() }
        scope.cancel()
    }
}

private fun splitHostPort(address: String): Pair<String, Int>? {
    val trimmed = address.trim()
    if (trimmed.isBlank()) return null
    val idx = trimmed.lastIndexOf(':')
    if (idx <= 0 || idx == trimmed.length - 1) return null
    val host = trimmed.substring(0, idx).trim().removePrefix("[").removeSuffix("]")
    val port = trimmed.substring(idx + 1).toIntOrNull() ?: return null
    if (host.isBlank() || port <= 0 || port > 65535) return null
    return host to port
}

private fun SongEntity.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(mediaStoreId.toString())
    .setUri(uri)
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title).setArtist(artist).setAlbumTitle(album)
            .apply { albumArtUri?.let { setArtworkUri(android.net.Uri.parse(it)) } }
            .build()
    )
    .build()
