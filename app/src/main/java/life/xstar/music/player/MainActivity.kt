package life.xstar.music.player

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import android.os.Bundle
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import life.xstar.music.player.ui.player.FloatingNavigationHandle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.lifecycle.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import androidx.palette.graphics.Palette
import coil.compose.SubcomposeAsyncImage
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.*
import kotlin.random.Random
import kotlin.math.abs
import kotlin.math.roundToInt
import life.xstar.music.player.data.local.*
import life.xstar.music.player.data.audio.AudioQuality
import life.xstar.music.player.data.preferences.PlaybackPreferences
import life.xstar.music.player.data.preferences.ThemeAccent
import life.xstar.music.player.data.preferences.PlayerTheme
import life.xstar.music.player.ui.player.ScenePlayer
import life.xstar.music.player.ui.player.PlayerThemePicker
import life.xstar.music.player.data.repository.MusicRepository
import life.xstar.music.player.data.repository.ArtworkCandidate
import life.xstar.music.player.data.repository.ScanProgress
import life.xstar.music.player.data.sing.*
import life.xstar.music.player.data.lyrics.LyricsRepository
import life.xstar.music.player.data.lyrics.LyricsState
import life.xstar.music.player.data.lyrics.LyricsCandidate
import life.xstar.music.player.cast.CastSessionController
import life.xstar.music.player.cast.CastSessionState
import life.xstar.music.player.cast.CastDevice
import life.xstar.music.player.cast.CastSessionService
import life.xstar.music.player.together.*
import life.xstar.music.player.data.online.OnlineMusicRepository
import life.xstar.music.player.data.online.OnlineTrack
import life.xstar.music.player.data.online.OnlineDownloadState
import life.xstar.music.player.data.online.supportedOnlinePlatforms
import life.xstar.music.player.data.online.platformLabel
import life.xstar.music.player.navigation.*
import life.xstar.music.player.player.*

class MainActivity : ComponentActivity() {
    private lateinit var musicViewModel: MusicViewModel

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        enableEdgeToEdge()
        val app = application as XstarApplication
        val vm = ViewModelProvider(this, MusicViewModel.factory(app.repository, PlayerController(this), app.playbackPreferences, LyricsRepository(this), app.castController, app.onlineMusicRepository, app.togetherController, app.applicationContext))[MusicViewModel::class.java]
        musicViewModel = vm
        setContent { val theme by vm.theme.collectAsStateWithLifecycle(); XstarTheme(theme) { XstarApp(vm) } }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == ACTION_OPEN_PLAYER) {
            window.decorView.post { musicViewModel.openPlayer.tryEmit(Unit) }
            intent.action = null
        }
    }

    companion object {
        const val ACTION_OPEN_PLAYER = "life.xstar.music.player.action.OPEN_PLAYER"
    }
}

@OptIn(kotlinx.coroutines.FlowPreview::class)
class MusicViewModel(private val repo: MusicRepository, val player: PlayerController, private val preferences: PlaybackPreferences, private val lyricsRepository: LyricsRepository, val castController: CastSessionController, private val onlineRepository: OnlineMusicRepository, val togetherController: TogetherSessionController, private val appContext: Context) : ViewModel() {
    val songs = repo.songs().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val favorites = repo.favorites().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val playlists = repo.playlists().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val singingRecords = repo.singingRecords().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val playback = player.state
    val castState: StateFlow<CastSessionState> = castController.state
    val togetherState: StateFlow<TogetherSessionState> = togetherController.state
    val equalizerState = player.equalizerState
    private var castResumeAfterStop = false
    private val _scanState = MutableStateFlow(ScanState())
    val scanState = _scanState.asStateFlow()
    private var scanJob: Job? = null
    private val scanGeneration = AtomicInteger()
    private val _sleepTimerRemaining = MutableStateFlow<Long?>(null)
    val sleepTimerRemaining = _sleepTimerRemaining.asStateFlow()
    private var sleepTimerJob: Job? = null
    private var suppressPlaybackPersistence = false
    val folders = preferences.folders.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val theme = preferences.theme.stateIn(viewModelScope, SharingStarted.Eagerly, ThemeAccent.Blue)
    val playerTheme = preferences.playerTheme.stateIn(viewModelScope, SharingStarted.Eagerly, PlayerTheme.Classic)
    val playerBackground = preferences.playerBackground.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val playerDiscOpacity = preferences.playerDiscOpacity.stateIn(viewModelScope, SharingStarted.Eagerly, .42f)
    val playerDiscVisible = preferences.playerDiscVisible.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val beatEffect = preferences.beatEffect.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val bottomEffect = preferences.bottomEffect.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val onlineArtworkEnabled = preferences.onlineArtworkEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val onlineLyricsEnabled = preferences.onlineLyricsEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val fmModeEnabled = preferences.fmModeEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val karaokeModeEnabled = preferences.karaokeModeEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val onlineSearchEnabled = preferences.onlineSearchEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val onlinePlatform = preferences.onlinePlatform.stateIn(viewModelScope, SharingStarted.Eagerly, "netease")
    val onlineApiBase = preferences.onlineApiBase.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val downloadFolder = preferences.downloadFolder.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val togetherConnectMode = preferences.togetherConnectMode.stateIn(viewModelScope, SharingStarted.Eagerly, TogetherConnectMode.MultiPath)
    val togetherServerEnabled = preferences.togetherServerEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val togetherServerAddress = preferences.togetherServerAddress.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    private val deviceNickname: String = android.os.Build.MODEL.ifBlank { "手机" }
    val openPlayer = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val lyrics = MutableStateFlow<LyricsState>(LyricsState.Idle)
    val onlineDownloads = onlineRepository.downloads
    private val _onlineSearch = MutableStateFlow<OnlineSearchUiState>(OnlineSearchUiState.Idle)
    val onlineSearch = _onlineSearch.asStateFlow()
    private val _vocalReference = MutableStateFlow<VocalReferenceState>(VocalReferenceState.Idle)
    val vocalReference = _vocalReference.asStateFlow()
    private var vocalReferenceJob: Job? = null
    private var vocalReferenceSongId: Long? = null

    init {
        viewModelScope.launch {
            playback.debounce(1_000).collect { state ->
                if (suppressPlaybackPersistence || state.queue.isEmpty()) return@collect
                if (togetherState.value.inSession) return@collect
                if (preferences.isFmModeEnabled()) preferences.saveFm(state.queue.map { it.mediaStoreId }, state.index, state.position, state.playing)
                else preferences.save(state.queue.map { it.mediaStoreId }, state.index, state.position, state.mode.name, state.playing)
            }
        }
        viewModelScope.launch {
            togetherController.state.map { it.connection to it.role }.distinctUntilChanged().collect { (connection, role) ->
                if (connection == TogetherConnection.Connected && role == TogetherRole.Member) player.ensureProgressUpdates()
            }
        }
        viewModelScope.launch {
            if (preferences.isFmModeEnabled()) {
                val saved = preferences.readFm()
                val restored = restoreSongs(saved)
                val queue = if (restored.songs.isNotEmpty()) restored.songs else repo.songs().first().shuffled()
                if (queue.isEmpty()) preferences.setFmModeEnabled(false)
                else {
                    val index = if (restored.songs.isNotEmpty()) restored.index else 0
                    player.playFmQueue(queue, index, if (restored.songs.isNotEmpty()) saved.position else 0, saved.playing)
                }
            } else {
                val saved = preferences.read()
                val restored = restoreSongs(saved)
                if (restored.songs.isNotEmpty()) player.restoreQueue(restored.songs, restored.index, saved.position, saved.mode.toPlaybackMode(), playing = false)
            }
        }
        viewModelScope.launch {
            combine(songs, playback) { library, state ->
                state.song?.mediaStoreId?.let { id -> library.firstOrNull { it.mediaStoreId == id } }
            }.filterNotNull().distinctUntilChanged().collect(player::updateSong)
        }
        val activeSong = combine(castState, playback, togetherState) { cast, local, together ->
            when {
                cast.connection == CastSessionController.Connection.Connected -> cast.song
                together.connection == TogetherConnection.Connected && together.isMember -> togetherController.currentSharedSongEntity()
                else -> local.song
            }
        }.distinctUntilChangedBy { it?.mediaStoreId }
        val activeTrackId = activeSong.map { it?.mediaStoreId }.distinctUntilChanged()
        viewModelScope.launch {
            combine(activeSong, onlineLyricsEnabled) { song, online -> song to online }
                .distinctUntilChanged().collectLatest { (song, online) ->
                lyrics.value = if (song == null) LyricsState.Idle else LyricsState.Loading
                if (song != null) {
                    val local = runCatching { lyricsRepository.loadLocalOrEmbedded(song) }.getOrNull()
                    currentCoroutineContext().ensureActive()
                    if (local != null && local.lines.isNotEmpty()) {
                        lyrics.value = LyricsState.Loaded(local)
                    } else if (online) {
                        val candidate = runCatching { lyricsRepository.findOnline(song).firstOrNull() }.getOrNull()
                        val document = candidate?.let { runCatching { lyricsRepository.save(it, song) }.getOrNull() }
                        currentCoroutineContext().ensureActive()
                        lyrics.value = document?.let { LyricsState.Loaded(it) } ?: LyricsState.NotFound
                    } else {
                        lyrics.value = LyricsState.NotFound
                    }
                }
            }
        }
        viewModelScope.launch {
            combine(activeTrackId, fmModeEnabled, onlineArtworkEnabled) { id, fm, online -> Triple(id, fm, online) }
                .distinctUntilChanged().collect { (id, fm, online) ->
                val song = id?.let { repo.getSong(it) } ?: return@collect
                if (fm && online && song.albumArtUri.isNullOrBlank()) {
                    runCatching { repo.findArtworkCandidates(song.artist, song.album).firstOrNull() }
                        .getOrNull()?.let { repo.saveArtworkForAlbum(song.artist, song.album, it) }
                }
            }
        }
    }

    fun scan() = startScan(folders.value)
    fun addFolder(uri: String) = viewModelScope.launch {
        preferences.addFolder(uri)
        startScan(folders.value + uri)
    }
    fun removeFolder(uri: String) = viewModelScope.launch {
        preferences.removeFolder(uri)
        startScan(folders.value - uri)
    }
    private fun startScan(folderUris: Set<String>) {
        scanJob?.cancel()
        val generation = scanGeneration.incrementAndGet()
        val effectiveFolders = folderUris + listOfNotNull(downloadFolder.value.takeIf { it.isNotBlank() })
        scanJob = viewModelScope.launch {
            _scanState.value = ScanState(running = true)
            val result = runCatching {
                repo.scan(effectiveFolders) { progress: ScanProgress ->
                    if (scanGeneration.get() == generation) {
                        _scanState.value = ScanState(running = true, currentFile = progress.fileName, discovered = progress.discovered)
                    }
                }
            }
            result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            val finished = result.fold(
                onSuccess = { ScanState(discovered = it, message = "扫描完成，共发现 $it 首歌曲") },
                onFailure = { ScanState(discovered = _scanState.value.discovered, message = "扫描失败，请重试", failed = true) }
            )
            if (scanGeneration.get() != generation) return@launch
            _scanState.value = finished
            delay(3_000)
            if (_scanState.value == finished) _scanState.value = ScanState()
        }
    }
    fun setTheme(theme: ThemeAccent) = viewModelScope.launch { preferences.setTheme(theme) }
    fun setPlayerTheme(theme: PlayerTheme) = viewModelScope.launch { preferences.setPlayerTheme(theme) }
    fun setPlayerBackground(uri: String?) = viewModelScope.launch { preferences.setPlayerBackground(uri) }
    fun setPlayerDiscOpacity(opacity: Float) = viewModelScope.launch { preferences.setPlayerDiscOpacity(opacity) }
    fun setPlayerDiscVisible(visible: Boolean) = viewModelScope.launch { preferences.setPlayerDiscVisible(visible) }
    fun setBeatEffect(enabled: Boolean) = viewModelScope.launch { preferences.setBeatEffect(enabled) }
    fun setBottomEffect(enabled: Boolean) = viewModelScope.launch { preferences.setBottomEffect(enabled) }
    fun setOnlineArtworkEnabled(enabled: Boolean) = viewModelScope.launch { preferences.setOnlineArtworkEnabled(enabled) }
    fun setOnlineLyricsEnabled(enabled: Boolean) = viewModelScope.launch { preferences.setOnlineLyricsEnabled(enabled) }
    fun artworkCandidates(artist: String, album: String) = viewModelScope.async { repo.findArtworkCandidates(artist, album) }
    fun saveArtwork(artist: String, album: String, candidate: ArtworkCandidate) = viewModelScope.launch { repo.saveArtworkForAlbum(artist, album, candidate) }
    fun findLyrics(songOverride: SongEntity? = null) = viewModelScope.launch {
        val song = songOverride ?: when {
            castState.value.connection == CastSessionController.Connection.Connected -> castState.value.song
            togetherState.value.connection == TogetherConnection.Connected && togetherState.value.isMember -> togetherController.currentSharedSongEntity()
            else -> playback.value.song
        } ?: return@launch
        if (!onlineLyricsEnabled.value) {
            lyrics.value = LyricsState.Error("在线歌词获取已在设置中关闭")
            return@launch
        }
        lyrics.value = LyricsState.Loading
        val candidates = runCatching { lyricsRepository.findOnline(song) }.getOrDefault(emptyList())
        val document = candidates.firstOrNull()?.let { runCatching { lyricsRepository.save(it, song) }.getOrNull() }
        val current = when {
            castState.value.connection == CastSessionController.Connection.Connected -> castState.value.song
            togetherState.value.connection == TogetherConnection.Connected && togetherState.value.isMember -> togetherController.currentSharedSongEntity()
            else -> playback.value.song
        }
        if (current?.mediaStoreId == song.mediaStoreId) lyrics.value = document?.let { LyricsState.Loaded(it) } ?: LyricsState.NotFound
    }
    fun saveLyrics(candidate: life.xstar.music.player.data.lyrics.LyricsCandidate) = viewModelScope.launch {
        val candidateSong = (lyrics.value as? LyricsState.Candidates)?.song
        val focusedSong = if (castState.value.connection == CastSessionController.Connection.Connected) castState.value.song else playback.value.song
        val song = candidateSong ?: focusedSong ?: return@launch
        lyrics.value = lyricsRepository.save(candidate, song)?.let { LyricsState.Loaded(it) } ?: LyricsState.Error("歌词保存失败")
    }
    fun play(song: SongEntity, source: List<SongEntity> = songs.value) {
        viewModelScope.launch {
            val togetherSnap = togetherState.value
            if (togetherSnap.inSession && togetherSnap.isMember) return@launch
            val queue = source.ifEmpty { listOf(song) }
            val castSnap = castState.value
            if (castSnap.connection == CastSessionController.Connection.Connected && !preferences.isFmModeEnabled()) {
                castController.playQueueOnCast(queue, queue.indexOf(song).coerceAtLeast(0))
                openPlayer.emit(Unit)
                return@launch
            }
            val wasFmMode = preferences.isFmModeEnabled()
            val mode = if (wasFmMode) preferences.read().mode.toPlaybackMode() else playback.value.mode
            if (wasFmMode) {
                suppressPlaybackPersistence = true
                preferences.setFmModeEnabled(false)
            }
            player.playQueue(queue, queue.indexOf(song).coerceAtLeast(0), mode)
            withTimeoutOrNull(3_000) { playback.first { it.song?.mediaStoreId == song.mediaStoreId } }
            suppressPlaybackPersistence = false
            openPlayer.emit(Unit)
        }
    }
    fun discoverCastDevices() = viewModelScope.async { castController.discoverDevices() }
    fun connectCast(device: CastDevice) {
        if (fmModeEnabled.value || karaokeModeEnabled.value) return
        val snap = playback.value
        if (snap.queue.isEmpty() || snap.index !in snap.queue.indices) return
        castResumeAfterStop = snap.playing
        player.pause()
        castController.connect(device, snap.queue, snap.index.coerceAtLeast(0), snap.mode, playing = true)
    }
    fun stopCastAndResume() {
        viewModelScope.launch {
            castController.disconnect()
            withTimeoutOrNull(2_000) { castController.state.first { it.connection == CastSessionController.Connection.Disconnected } }
            if (castResumeAfterStop) {
                if (!playback.value.playing) player.toggle()
                castResumeAfterStop = false
            }
        }
    }
    fun castTogglePlayPause() = castController.togglePlayPause()
    fun castNext() = castController.next()
    fun castPrevious() = castController.previous()
    fun castSeek(ms: Long) = castController.seekTo(ms)
    fun setTogetherConnectMode(mode: TogetherConnectMode) = viewModelScope.launch { preferences.setTogetherConnectMode(mode) }
    fun createTogetherSession() {
        val together = togetherState.value
        if (together.connection != TogetherConnection.Idle) return
        if (fmModeEnabled.value || karaokeModeEnabled.value) return
        if (castState.value.connection != CastSessionController.Connection.Disconnected) return
        val snap = playback.value
        if (snap.queue.isEmpty() || snap.index !in snap.queue.indices) return
        TogetherService.start(appContext)
        viewModelScope.launch {
            configureTogetherRelay()
            togetherController.setNickname(deviceNickname)
            togetherController.startSession(togetherConnectMode.value)
        }
    }
    fun joinTogetherCode(code: String) {
        if (togetherState.value.connection != TogetherConnection.Idle) return
        if (fmModeEnabled.value || karaokeModeEnabled.value) return
        if (castState.value.connection != CastSessionController.Connection.Disconnected) return
        TogetherService.start(appContext)
        viewModelScope.launch {
            configureTogetherRelay()
            togetherController.setNickname(deviceNickname)
            togetherController.joinByCode(code, togetherConnectMode.value)
        }
    }
    fun joinTogetherInviteText(text: String) {
        if (togetherState.value.connection != TogetherConnection.Idle) return
        if (fmModeEnabled.value || karaokeModeEnabled.value) return
        if (castState.value.connection != CastSessionController.Connection.Disconnected) return
        TogetherService.start(appContext)
        viewModelScope.launch {
            configureTogetherRelay()
            togetherController.setNickname(deviceNickname)
            togetherController.joinByInviteText(text, togetherConnectMode.value)
        }
    }
    private suspend fun configureTogetherRelay() {
        val enabled = preferences.isTogetherServerEnabled()
        val address = if (enabled) preferences.currentTogetherServerAddress() else ""
        togetherController.setRelayServer(address)
    }
    fun setTogetherServerEnabled(enabled: Boolean) = viewModelScope.launch { preferences.setTogetherServerEnabled(enabled) }
    fun setTogetherServerAddress(address: String) = viewModelScope.launch { preferences.setTogetherServerAddress(address) }
    fun leaveTogether() {
        togetherController.endSession()
    }
    fun togetherTogglePlayPause() {
        val t = togetherState.value
        when {
            t.connection == TogetherConnection.Connected && t.isMember -> togetherController.sendControl("toggle")
            t.connection == TogetherConnection.Connected && t.isHost -> { player.toggle(); togetherController.notifyHostLocalChange() }
        }
    }
    fun togetherNext() {
        val t = togetherState.value
        when {
            t.connection == TogetherConnection.Connected && t.isMember -> togetherController.sendControl("next")
            t.connection == TogetherConnection.Connected && t.isHost -> { player.next(); togetherController.notifyHostLocalChange() }
        }
    }
    fun togetherPrevious() {
        val t = togetherState.value
        when {
            t.connection == TogetherConnection.Connected && t.isMember -> togetherController.sendControl("previous")
            t.connection == TogetherConnection.Connected && t.isHost -> { player.previous(); togetherController.notifyHostLocalChange() }
        }
    }
    fun togetherSeek(ms: Long) {
        val t = togetherState.value
        when {
            t.connection == TogetherConnection.Connected && t.isMember -> togetherController.sendControl("seek", positionMs = ms)
            t.connection == TogetherConnection.Connected && t.isHost -> { player.seek(ms); togetherController.notifyHostLocalChange() }
        }
    }
    fun togetherPlayIndex(index: Int) {
        val t = togetherState.value
        when {
            t.connection == TogetherConnection.Connected && t.isMember -> togetherController.sendControl("playIndex", index = index)
            t.connection == TogetherConnection.Connected && t.isHost -> { player.jumpTo(index); togetherController.notifyHostLocalChange() }
        }
    }
    fun togetherSetMode(mode: PlaybackMode) {
        val t = togetherState.value
        when {
            t.connection == TogetherConnection.Connected && t.isMember -> togetherController.sendControl("setMode", mode = mode.name)
            t.connection == TogetherConnection.Connected && t.isHost -> { player.setPlaybackMode(mode); togetherController.notifyHostLocalChange() }
        }
    }
    fun togetherSendReaction(reaction: TogetherReaction) = togetherController.sendReaction(reaction)
    fun togetherSendMessage(text: String) = togetherController.sendMessage(text)
    fun togetherCurrentSharedSong(): SongEntity? = togetherController.currentSharedSongEntity()
    fun setFmMode(enabled: Boolean) = viewModelScope.launch {
        if (enabled == preferences.isFmModeEnabled()) return@launch
        if (enabled) {
            if (preferences.isKaraokeModeEnabled()) disableKaraokeEffect()
            if (castState.value.connection != CastSessionController.Connection.Disconnected) castController.disconnect()
            castResumeAfterStop = false
            val library = songs.value
            if (library.isEmpty()) return@launch
            suppressPlaybackPersistence = true
            val state = playback.value
            preferences.save(state.queue.map { it.mediaStoreId }, state.index, state.position, state.mode.name, state.playing)
            preferences.setFmModeEnabled(true)
            val queue = library.shuffled()
            preferences.saveFm(queue.map { it.mediaStoreId }, 0, 0, playing = true)
            player.playFmQueue(queue, 0, playing = true)
            withTimeoutOrNull(3_000) { playback.first { it.song?.mediaStoreId == queue.first().mediaStoreId } }
            suppressPlaybackPersistence = false
            openPlayer.emit(Unit)
        } else {
            disableFmContext()
        }
    }
    fun setKaraokeMode(enabled: Boolean) = viewModelScope.launch {
        if (enabled == preferences.isKaraokeModeEnabled()) return@launch
        if (enabled) {
            if (castState.value.connection == CastSessionController.Connection.Connected) return@launch
            if (preferences.isFmModeEnabled()) disableFmContext()
            suppressPlaybackPersistence = true
            val settings = preferences.readKaraoke().copy(enabled = true)
            preferences.saveKaraoke(settings)
            player.setKaraokeEnabled(true)
            suppressPlaybackPersistence = false
            openPlayer.emit(Unit)
        } else {
            disableKaraokeEffect()
            openPlayer.emit(Unit)
        }
    }
    private suspend fun disableFmContext() {
        suppressPlaybackPersistence = true
        preferences.setFmModeEnabled(false)
        val saved = preferences.read()
        val restored = restoreSongs(saved)
        val expectedId = restored.songs.getOrNull(restored.index)?.mediaStoreId
        if (restored.songs.isEmpty()) {
            player.clear()
            withTimeoutOrNull(3_000) { playback.first { it.queue.isEmpty() } }
        } else {
            player.restoreQueue(restored.songs, restored.index, saved.position, saved.mode.toPlaybackMode(), saved.playing)
            withTimeoutOrNull(3_000) { playback.first { it.song?.mediaStoreId == expectedId } }
        }
        suppressPlaybackPersistence = false
        openPlayer.emit(Unit)
    }
    private suspend fun disableKaraokeEffect() {
        suppressPlaybackPersistence = true
        val settings = preferences.readKaraoke().copy(enabled = false)
        preferences.saveKaraoke(settings)
        player.setKaraokeEnabled(false)
        suppressPlaybackPersistence = false
    }
    fun fmRandomSong() = player.randomSong()
    fun favorite(id: Long) = viewModelScope.launch { repo.toggleFavorite(id) }
    fun createPlaylist(name: String) = viewModelScope.launch { if (name.isNotBlank()) repo.createPlaylist(name.trim()) }
    fun deletePlaylist(item: PlaylistEntity) = viewModelScope.launch { repo.deletePlaylist(item) }
    fun renamePlaylist(id: Long, name: String) = viewModelScope.launch { if (name.isNotBlank()) repo.renamePlaylist(id, name) }
    fun playlistSongs(id: Long) = repo.playlistSongs(id)
    fun addToPlaylist(playlistId: Long, songId: Long) = viewModelScope.launch { repo.addToPlaylist(playlistId, songId) }
    fun removeFromPlaylist(playlistId: Long, songId: Long) = viewModelScope.launch { repo.removeFromPlaylist(playlistId, songId) }
    fun onlineSearch(query: String) {
        if (!onlineSearchEnabled.value) {
            _onlineSearch.value = OnlineSearchUiState.Error("在线搜歌下载已在设置中关闭")
            return
        }
        val keyword = query.trim()
        if (keyword.isEmpty()) return
        _onlineSearch.value = OnlineSearchUiState.Searching
        viewModelScope.launch {
            val result = runCatching { onlineRepository.search(keyword) }
            val next = result.fold(
                onSuccess = { tracks -> if (tracks.isEmpty()) OnlineSearchUiState.Error("未找到相关歌曲，可切换平台或更换关键词") else OnlineSearchUiState.Results(keyword, tracks) },
                onFailure = { OnlineSearchUiState.Error(it.message ?: "搜索失败，请稍后重试") }
            )
            _onlineSearch.value = next
            if (next is OnlineSearchUiState.Results) onlineRepository.markDownloadedIfAny(next.tracks)
        }
    }
    fun startOnlineDownload(track: OnlineTrack) = onlineRepository.startDownload(track)
    fun cancelOnlineDownload(onlineId: String) = onlineRepository.cancel(onlineId)
    fun playOnlineDownloaded(onlineId: String) = viewModelScope.launch {
        val song = onlineRepository.findByOnlineId(onlineId) ?: return@launch
        play(song)
    }
    fun setOnlineSearchEnabled(enabled: Boolean) = viewModelScope.launch { preferences.setOnlineSearchEnabled(enabled) }
    fun setOnlinePlatform(platform: String) = viewModelScope.launch { preferences.setOnlinePlatform(platform) }
    fun setOnlineApiBase(base: String) = viewModelScope.launch { preferences.setOnlineApiBase(base) }
    fun setDownloadFolder(uri: String?) = viewModelScope.launch { preferences.setDownloadFolder(uri) }
    fun setDownloadFolderAndStart(uri: String, track: OnlineTrack) = viewModelScope.launch {
        preferences.setDownloadFolder(uri)
        onlineRepository.startDownload(track)
    }
    fun setSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()
        sleepTimerJob = viewModelScope.launch {
            val endAt = SystemClock.elapsedRealtime() + minutes * 60_000L
            while (true) {
                val remaining = (endAt - SystemClock.elapsedRealtime()).coerceAtLeast(0)
                _sleepTimerRemaining.value = remaining
                if (remaining == 0L) break
                delay(1_000)
            }
            player.pause()
            _sleepTimerRemaining.value = null
        }
    }
    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        _sleepTimerRemaining.value = null
    }
    fun refreshEqualizer() = player.refreshEqualizer()
    fun setEqualizerEnabled(enabled: Boolean) = player.setEqualizerEnabled(enabled)
    fun useEqualizerPreset(index: Int) = player.useEqualizerPreset(index)
    fun setEqualizerBand(index: Int, levelMb: Int) = player.setEqualizerBand(index, levelMb)
    fun resetEqualizer() = player.resetEqualizer()
    fun requestVocalReference(song: SongEntity) {
        val currentSongId = vocalReferenceSongId
        if (currentSongId == song.mediaStoreId && (_vocalReference.value is VocalReferenceState.Ready || _vocalReference.value is VocalReferenceState.Computing)) return
        vocalReferenceJob?.cancel()
        val cached = VocalPitchAnalyzer.load(appContext, song.mediaStoreId)
        if (cached != null) {
            vocalReferenceSongId = song.mediaStoreId
            _vocalReference.value = VocalReferenceState.Ready(cached)
            return
        }
        vocalReferenceJob = viewModelScope.launch {
            vocalReferenceSongId = song.mediaStoreId
            _vocalReference.value = VocalReferenceState.Computing
            val targetSongId = song.mediaStoreId
            val context = currentCoroutineContext()
            val frames = withContext(Dispatchers.IO) { VocalPitchAnalyzer.analyze(appContext, song.uri) { !context.isActive } }
            if (vocalReferenceSongId != targetSongId) return@launch
            if (frames.isNullOrEmpty()) {
                _vocalReference.value = VocalReferenceState.Unavailable("未能提取原唱音高,本次使用轻量评分")
            } else {
                VocalPitchAnalyzer.save(appContext, song.mediaStoreId, frames)
                _vocalReference.value = VocalReferenceState.Ready(frames)
            }
        }
    }
    fun cancelVocalAnalysis() {
        vocalReferenceJob?.cancel()
        vocalReferenceJob = null
        vocalReferenceSongId = null
        _vocalReference.value = VocalReferenceState.Idle
    }
    private suspend fun restoreSongs(saved: life.xstar.music.player.data.preferences.SavedPlayback): RestoredQueue {
        val songs = repo.findByIds(saved.queue)
        val currentId = saved.queue.getOrNull(saved.index)
        return RestoredQueue(songs, songs.indexOfFirst { it.mediaStoreId == currentId }.coerceAtLeast(0))
    }

    suspend fun persistSingingRecord(song: SongEntity, sourceFile: File, score: SingingScore, durationMs: Long): Long = withContext(Dispatchers.IO) {
        val directory = File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "singing").apply { mkdirs() }
        val createdAt = System.currentTimeMillis()
        val target = File(directory, "sing_${song.mediaStoreId}_${createdAt}.wav")
        sourceFile.inputStream().use { input -> target.outputStream().use { output -> input.copyTo(output) } }
        repo.saveSingingRecord(
            SingingRecordEntity(
                songId = song.mediaStoreId,
                songTitle = song.title,
                songArtist = song.artist,
                albumArtUri = song.albumArtUri,
                filePath = target.absolutePath,
                total = score.total,
                pitch = score.pitch,
                activity = score.activity,
                lightweight = score.lightweight,
                durationMs = durationMs,
                createdAt = createdAt
            )
        )
    }
    fun updateSingingRecordScore(id: Long, score: SingingScore) = viewModelScope.launch {
        repo.updateSingingRecordScore(id, score.total, score.pitch, score.activity, score.lightweight)
    }
    fun deleteSingingRecord(record: SingingRecordEntity) = viewModelScope.launch(Dispatchers.IO) {
        runCatching { File(record.filePath).delete() }
        repo.deleteSingingRecord(record)
    }
    override fun onCleared() {
        vocalReferenceJob?.cancel()
        player.release()
    }

    companion object { fun factory(repo: MusicRepository, player: PlayerController, preferences: PlaybackPreferences, lyrics: LyricsRepository, cast: CastSessionController, online: OnlineMusicRepository, together: TogetherSessionController, appContext: Context) = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(type: Class<T>): T = MusicViewModel(repo, player, preferences, lyrics, cast, online, together, appContext) as T
    } }
}

private data class RestoredQueue(val songs: List<SongEntity>, val index: Int)
private fun String.toPlaybackMode() = runCatching { PlaybackMode.valueOf(this) }.getOrDefault(PlaybackMode.Sequential)

data class ScanState(
    val running: Boolean = false,
    val currentFile: String? = null,
    val discovered: Int = 0,
    val message: String? = null,
    val failed: Boolean = false
)

sealed class OnlineSearchUiState {
    data object Idle : OnlineSearchUiState()
    data object Searching : OnlineSearchUiState()
    data class Results(val query: String, val tracks: List<OnlineTrack>) : OnlineSearchUiState()
    data class Error(val message: String) : OnlineSearchUiState()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun XstarApp(vm: MusicViewModel) {
    val navigationState = rememberNavigationState(startRoute, topLevelRoutes)
    val navigator = remember { Navigator(navigationState) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val theme by vm.theme.collectAsStateWithLifecycle()
    val sleepTimerRemaining by vm.sleepTimerRemaining.collectAsStateWithLifecycle()
    val fmMode by vm.fmModeEnabled.collectAsStateWithLifecycle()
    val karaokeMode by vm.karaokeModeEnabled.collectAsStateWithLifecycle()
    val castState by vm.castState.collectAsStateWithLifecycle()
    val togetherState by vm.togetherState.collectAsStateWithLifecycle()
    val songs by vm.songs.collectAsStateWithLifecycle()
    val selectedPlayerTheme by vm.playerTheme.collectAsStateWithLifecycle()
    val immersivePlayer = navigationState.topLevelRoute == PlayerRoute && selectedPlayerTheme != PlayerTheme.Classic && !fmMode && !karaokeMode
    val activity = LocalActivity.current
    DisposableEffect(activity, immersivePlayer) {
        val controller = activity?.window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        if (immersivePlayer) {
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        } else controller?.show(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    var showCastSheet by remember { mutableStateOf(false) }
    var showTogetherSheet by remember { mutableStateOf(false) }
    val accent = theme.color()
    LaunchedEffect(vm) { vm.openPlayer.collect { navigator.navigate(PlayerRoute); drawerState.close() } }
    val openMenu: () -> Unit = { scope.launch { drawerState.open() } }
    val entryProvider = entryProvider<NavKey> {
        entry<PlayerRoute> { PlayerScreen(vm, openMenu, onOpenCastSheet = { showCastSheet = true }, onOpenTogetherSheet = { showTogetherSheet = true }) }
        entry<LibraryRoute> { LibraryScreen(vm, openMenu) }
        entry<FavoritesRoute> { CollectionScreen(vm.favorites.collectAsStateWithLifecycle().value, vm, openMenu, goToLibrary = { navigator.navigate(LibraryRoute) }) }
        entry<PlaylistsRoute> { PlaylistsScreen(vm, openMenu) }
        entry<OnlineRoute> { OnlineScreen(vm, openMenu) }
        entry<EqualizerRoute> { EqualizerScreen(vm, openMenu) }
        entry<SettingsRoute> { SettingsScreen(vm, openMenu) }
        entry<SingingHistoryRoute> { SingingHistoryScreen(vm, openMenu, goKaraoke = { vm.setKaraokeMode(true) }) }
    }
    val drawerRoutes = listOf(
        PlayerRoute to (Icons.Default.Headphones to "正在播放"),
        LibraryRoute to (Icons.Default.LibraryMusic to "音乐库"),
        FavoritesRoute to (Icons.Default.Favorite to "我的收藏"),
        PlaylistsRoute to (Icons.Default.QueueMusic to "播放列表"),
        SingingHistoryRoute to (Icons.Default.Mic to "我的演唱"),
        OnlineRoute to (Icons.Default.CloudDownload to "在线搜歌"),
        EqualizerRoute to (Icons.Default.Equalizer to "均衡器"),
        SettingsRoute to (Icons.Default.Settings to "设置")
    )
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
    ModalNavigationDrawer(drawerState = drawerState, gesturesEnabled = drawerState.isOpen, drawerContent = {
      CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        ModalDrawerSheet(drawerContainerColor = Color(0xFF10131A)) {
            Column(Modifier.fillMaxHeight().width(300.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                Spacer(Modifier.height(24.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("XSTAR", fontSize = 30.sp, fontWeight = FontWeight.Black, color = Color.White, modifier = Modifier.weight(1f))
                    IconButton(onClick = { scope.launch { drawerState.close() } }) {
                        Icon(Icons.Default.Close, "关闭菜单", tint = Color(0xFFADB6C5))
                    }
                }
                Text("YOUR MUSIC. YOUR SPACE.", color = Color(0xFF7E8A9A), fontSize = 11.sp)
                Spacer(Modifier.height(34.dp))
                drawerRoutes.forEach { (route, item) ->
                    NavigationDrawerItem(label = { Text(item.second) }, icon = { Icon(item.first, null) }, selected = route == navigationState.topLevelRoute, onClick = { navigator.navigate(route); scope.launch { drawerState.close() } }, modifier = Modifier.padding(vertical = 3.dp))
                }
                Spacer(Modifier.height(16.dp))
                val castActive = castState.connection != CastSessionController.Connection.Disconnected
                val togetherBusy = togetherState.connection != TogetherConnection.Idle
                val castCardEnabled = !fmMode && !karaokeMode && !togetherBusy
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = Color.White.copy(alpha = if (castCardEnabled) .06f else .03f)) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 10.dp).clickable(enabled = castCardEnabled) {
                            showCastSheet = true
                            scope.launch { drawerState.close() }
                        },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Cast, null, Modifier.size(21.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("投屏", color = if (castCardEnabled) Color.White else Color.White.copy(alpha = .5f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                when {
                                    !castCardEnabled -> if (togetherBusy) "一起听中不可用" else if (fmMode) "FM 模式不可用" else "卡拉OK 模式不可用"
                                    castActive -> "正在投到 ${castState.device?.friendlyName ?: "设备"}"
                                    else -> "搜索同一 Wi-Fi 的投影设备"
                                },
                                color = Color(0xFF818C9C),
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Icon(
                            Icons.Default.ArrowForwardIos, null, Modifier.size(14.dp),
                            tint = if (castActive) MaterialTheme.colorScheme.primary else Color(0xFF5B6577)
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                val togetherEnabled = !fmMode && !karaokeMode && !castActive
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = Color.White.copy(alpha = if (togetherEnabled) .06f else .03f)) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 10.dp).clickable(enabled = togetherEnabled) {
                            showTogetherSheet = true
                            scope.launch { drawerState.close() }
                        },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.People, null, Modifier.size(21.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("一起听", color = if (togetherEnabled) Color.White else Color.White.copy(alpha = .5f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                when {
                                    !togetherEnabled -> if (fmMode) "FM 模式不可用" else if (karaokeMode) "卡拉OK 模式不可用" else "投屏中不可用"
                                    togetherState.connection == TogetherConnection.Connected && togetherState.isHost -> "一起听中 · 与 ${togetherState.peer?.nickname ?: "好友"}"
                                    togetherState.connection == TogetherConnection.Connected && togetherState.isMember -> "一起听中 · 房主 ${togetherState.peer?.nickname ?: ""}"
                                    togetherState.connection == TogetherConnection.Listening -> "等待好友加入 · 邀请码 ${togetherState.code ?: ""}"
                                    else -> "邀请好友一起听同一首歌"
                                },
                                color = Color(0xFF818C9C),
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Icon(
                            Icons.Default.ArrowForwardIos, null, Modifier.size(14.dp),
                            tint = if (togetherState.connection != TogetherConnection.Idle) MaterialTheme.colorScheme.primary else Color(0xFF5B6577)
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = Color.White.copy(alpha = if (fmMode || !castActive || togetherBusy) .06f else .03f)) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Radio, null, Modifier.size(21.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("FM 模式", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                when {
                                    songs.isEmpty() -> "请先扫描音乐"
                                    togetherBusy -> "一起听中不可用"
                                    castActive -> "投屏中不可用"
                                    else -> "随机电台 · 极简手势"
                                },
                                color = Color(0xFF818C9C),
                                fontSize = 11.sp
                            )
                        }
                        Switch(
                            checked = fmMode,
                            enabled = (songs.isNotEmpty() || fmMode) && !castActive && !togetherBusy,
                            onCheckedChange = {
                                vm.setFmMode(it)
                                if (it) scope.launch { drawerState.close() }
                            }
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = Color.White.copy(alpha = if (karaokeMode || !castActive || togetherBusy) .06f else .03f)) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Mic, null, Modifier.size(21.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("卡拉OK 模式", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                when {
                                    songs.isEmpty() -> "请先扫描音乐"
                                    togetherBusy -> "一起听中不可用"
                                    castActive -> "投屏中不可用"
                                    else -> "伴奏跟唱 · 原唱/伴奏 · 变调"
                                },
                                color = Color(0xFF818C9C),
                                fontSize = 11.sp
                            )
                        }
                        Switch(
                            checked = karaokeMode,
                            enabled = (songs.isNotEmpty() || karaokeMode) && !castActive && !togetherBusy,
                            onCheckedChange = {
                                vm.setKaraokeMode(it)
                                if (it) scope.launch { drawerState.close() }
                            }
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("定时关闭", color = Color(0xFF9BA5B5), fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = Color.White.copy(alpha = .06f)) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Bedtime, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text(sleepTimerRemaining?.let { "剩余 ${sleepTimerText(it)}" } ?: "播放一段时间后暂停", color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            if (sleepTimerRemaining != null) TextButton(vm::cancelSleepTimer, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("取消", fontSize = 12.sp) }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            listOf(10 to "10分", 20 to "20分", 30 to "30分", 60 to "1小时").forEach { (minutes, label) ->
                                AssistChip(
                                    onClick = { vm.setSleepTimer(minutes) },
                                    label = { Text(label, fontSize = 11.sp) },
                                    modifier = Modifier.weight(1f),
                                    border = null
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
                Text("主题颜色", color = Color(0xFF9BA5B5), fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    ThemeAccent.entries.forEach { accent -> ThemeSwatch(accent, theme == accent) { vm.setTheme(accent) } }
                }
                val playerTheme by vm.playerTheme.collectAsStateWithLifecycle()
                Text("播放界面", color = Color(0xFF9BA5B5), fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp))
                PlayerTheme.entries.forEach { scene ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { vm.setPlayerTheme(scene) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(scene.title, color = if (scene == playerTheme) accent else Color.White, fontSize = 14.sp)
                            Text(scene.subtitle, color = Color(0xFF808B9B), fontSize = 11.sp)
                        }
                        if (scene == playerTheme) Icon(Icons.Default.Check, "已选择", tint = accent, modifier = Modifier.size(18.dp))
                    }
                }
                Text("点击悬浮按钮打开导航，可拖动到屏幕边缘收起", color = Color(0xFF697382), fontSize = 12.sp, modifier = Modifier.padding(12.dp)); Spacer(Modifier.height(20.dp))
            }
        }
      }
    }) {
      CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(accent.copy(alpha = .22f), Color(0xFF090B10)), endY = 1100f))) {
            NavDisplay(
                entries = navigationState.toDecoratedEntries(entryProvider),
                onBack = { navigator.goBack() },
                modifier = Modifier.fillMaxSize()
            )
            BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }
            if (immersivePlayer) FloatingNavigationHandle(onOpen = openMenu)
            TogetherBarrageOverlay(vm, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 84.dp).widthIn(max = 340.dp))
            if (showCastSheet) CastControlSheet(vm) { showCastSheet = false }
            if (showTogetherSheet) TogetherSheet(vm, togetherState) { showTogetherSheet = false }
        }
      }
    }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun CastControlSheet(vm: MusicViewModel, close: () -> Unit) {
    val context = LocalContext.current
    val castState by vm.castState.collectAsStateWithLifecycle()
    val playback by vm.playback.collectAsStateWithLifecycle()
    val fmMode by vm.fmModeEnabled.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<CastDevice>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var scanError by remember { mutableStateOf<String?>(null) }

    fun scanDevices() {
        if (scanning) return
        scanning = true
        scanError = null
        scope.launch {
            val result = runCatching { vm.discoverCastDevices().await() }
            devices = result.getOrDefault(emptyList())
            if (devices.isEmpty() && scanError == null) scanError = "未发现设备，请确认手机与投影仪在同一 Wi-Fi"
            scanning = false
        }
    }

    LaunchedEffect(Unit) {
        if (castState.connection == CastSessionController.Connection.Disconnected) scanDevices()
    }

    ModalBottomSheet(onDismissRequest = close, containerColor = Color(0xFF171B23)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("投屏", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    Text(
                        when {
                            fmMode -> "FM 模式下不可用"
                            castState.connection == CastSessionController.Connection.Connected -> "正在投到 ${castState.device?.friendlyName ?: "设备"}"
                            castState.connection == CastSessionController.Connection.Connecting -> "正在连接设备…"
                            else -> "选择要播放的 DLNA 设备"
                        },
                        color = Color(0xFF8F99A9), fontSize = 12.sp
                    )
                }
                if (castState.connection == CastSessionController.Connection.Connected) {
                    TextButton(onClick = {
                        CastSessionService.stop(context)
                        vm.stopCastAndResume()
                        close()
                    }) { Text("断开投屏", color = Color(0xFFFF8A96)) }
                }
            }
            when {
                fmMode -> Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                    Text("请先退出 FM 模式再投屏", color = Color.Gray)
                }
                playback.queue.isEmpty() && castState.connection == CastSessionController.Connection.Disconnected -> Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                    Text("先在播放器选择一首歌曲，再开始投屏", color = Color(0xFF8F99A9), textAlign = TextAlign.Center)
                }
                castState.connection == CastSessionController.Connection.Connected -> CastSessionBody(vm, castState)
                else -> Column {
                    scanError?.let { Text(it, color = Color(0xFFFF8A96), fontSize = 12.sp, modifier = Modifier.padding(bottom = 10.dp)) }
                    if (scanning) Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 3.dp)
                    }
                    LazyColumn(Modifier.heightIn(max = 380.dp)) {
                        items(devices, key = { it.id }) { device ->
                            ListItem(
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                headlineContent = { Text(device.friendlyName, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                supportingContent = { Text(device.modelName?.ifBlank { null } ?: "DLNA 渲染器", color = Color(0xFF8F99A9), maxLines = 1) },
                                leadingContent = { Icon(Icons.Default.Cast, null, tint = MaterialTheme.colorScheme.primary) },
                                modifier = Modifier.clickable {
                                    CastSessionService.start(context)
                                    vm.connectCast(device)
                                }
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(onClick = { scanDevices() }, enabled = !scanning, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Refresh, null, Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (scanning) "正在搜索…" else "重新搜索设备")
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}

@Composable private fun CastSessionBody(vm: MusicViewModel, castState: CastSessionState) {
    val lyrics by vm.lyrics.collectAsStateWithLifecycle()
    val song = castState.song
    var dragMs by remember { mutableStateOf<Float?>(null) }
    val duration = castState.durationMs.coerceAtLeast(1).toFloat()
    val progressValue = ((dragMs ?: castState.positionMs.toFloat()) / duration).coerceIn(0f, 1f)

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        castState.error?.let { error -> Text(error, color = Color(0xFFFF8A96), fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) }
        if (song != null) {
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(Modifier.size(56.dp), RoundedCornerShape(14.dp), color = Color(0xFF252C38)) { ArtworkImage(song.albumArtUri, Color(0xFF252C38)) }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(song.title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(song.artist, color = Color(0xFF9AA3B2), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        val lyricLine = (lyrics as? LyricsState.Loaded)?.document?.lines
            ?.let { lines -> lines.lastOrNull { it.timeMs?.let(castState.positionMs::compareTo) ?: -1 >= 0 } }
        when {
            lyricLine != null && lyricLine.text.isNotBlank() -> Text(
                lyricLine.text,
                color = Color.White.copy(alpha = .85f),
                fontSize = 15.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
            )
            (lyrics is LyricsState.NotFound || lyrics is LyricsState.Idle || lyrics is LyricsState.Error) && song != null -> TextButton(onClick = { vm.findLyrics(song) }) {
                Text("获取歌词")
            }
        }
        Slider(
            value = progressValue,
            onValueChange = { dragMs = it * duration },
            onValueChangeFinished = {
                dragMs?.let { vm.castSeek(it.toLong()) }
                dragMs = null
            },
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = MaterialTheme.colorScheme.primary, inactiveTrackColor = Color.White.copy(alpha = .18f))
        )
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(time(castState.positionMs), color = Color(0xFF8A94A5), fontSize = 11.sp)
            Text(time(castState.durationMs), color = Color(0xFF8A94A5), fontSize = 11.sp)
        }
        Row(Modifier.fillMaxWidth().height(72.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
            IconButton(onClick = vm::castPrevious, Modifier.size(52.dp)) { Icon(Icons.Default.SkipPrevious, "上一首", Modifier.size(30.dp), tint = Color.White) }
            IconButton(onClick = vm::castTogglePlayPause, Modifier.size(64.dp)) {
                Icon(if (castState.playing) Icons.Default.Pause else Icons.Default.PlayArrow, "播放或暂停", Modifier.size(38.dp), tint = Color.White)
            }
            IconButton(onClick = vm::castNext, Modifier.size(52.dp)) { Icon(Icons.Default.SkipNext, "下一首", Modifier.size(30.dp), tint = Color.White) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun TogetherSheet(vm: MusicViewModel, together: TogetherSessionState, close: () -> Unit) {
    val context = LocalContext.current
    val playback by vm.playback.collectAsStateWithLifecycle()
    var codeText by remember { mutableStateOf("") }
    val connectMode by vm.togetherConnectMode.collectAsStateWithLifecycle()

    fun pasteFromClipboard() {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = manager.primaryClip ?: return
        val text = clip.getItemAt(0).coerceToText(context)?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        if (text.length <= 24) {
            codeText = text
            vm.joinTogetherCode(text)
        } else {
            vm.joinTogetherInviteText(text)
        }
    }

    ModalBottomSheet(onDismissRequest = close, containerColor = Color(0xFF171B23)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 20.dp, vertical = 6.dp)
        ) {
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("一起听", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    Text(statusTogether(together), color = Color(0xFF8F99A9), fontSize = 12.sp)
                }
                if (together.connection != TogetherConnection.Idle) {
                    TextButton(onClick = { vm.leaveTogether(); close() }) { Text("结束", color = Color(0xFFFF8A96)) }
                }
            }
            together.error?.let { Text(it, color = Color(0xFFFF8A96), fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) }
            when (together.connection) {
                TogetherConnection.Idle -> {
                    Text("连接方式", color = Color(0xFF9BA5B5), fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp))
                    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(TogetherConnectMode.Lan to "仅局域网", TogetherConnectMode.MultiPath to "跨网多路径(默认)").forEach { (mode, label) ->
                            FilterChip(
                                selected = connectMode == mode,
                                onClick = { vm.setTogetherConnectMode(mode) },
                                label = { Text(label, fontSize = 12.sp) }
                            )
                        }
                    }
                    if (playback.queue.isNotEmpty()) {
                        Button(onClick = { vm.createTogetherSession(); }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.People, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("创建房间（我是房主）")
                        }
                    } else {
                        Text("先在播放器选择一首歌曲，再作为房主开房", color = Color(0xFFFFBE5C), fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp))
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = codeText,
                        onValueChange = { codeText = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("输入对方邀请码", color = Color(0xFF8F99A9)) },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp)
                    )
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { if (codeText.isNotBlank()) vm.joinTogetherCode(codeText) }, enabled = codeText.isNotBlank(), modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Login, null, Modifier.size(17.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("加入房间")
                        }
                        OutlinedButton(onClick = ::pasteFromClipboard, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.ContentPaste, null, Modifier.size(17.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("粘贴二维码/邀请")
                        }
                    }
                }
                TogetherConnection.Listening -> {
                    Text("邀请好友加入：", color = Color(0xFF9BA5B5), fontSize = 13.sp)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Surface(shape = RoundedCornerShape(14.dp), color = Color.White.copy(alpha = .1f)) {
                            Text(together.code ?: "-", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp).clickable {
                                val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                manager.setPrimaryClip(android.content.ClipData.newPlainText("invite", together.code))
                            })
                        }
                        Spacer(Modifier.width(4.dp))
                        Column(Modifier.weight(1f)) {
                            Text("邀请码已复制", color = Color(0xFF8F99A9), fontSize = 12.sp)
                            Text(if (together.connectMode == TogetherConnectMode.MultiPath) "多路径：局域网直连 + 服务器/公网候选" else "仅局域网直连", color = Color(0xFF5B6577), fontSize = 11.sp)
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    together.inviteText?.let {
                        Surface(shape = RoundedCornerShape(18.dp), color = Color.White.copy(alpha = .08f)) {
                            Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("扫描二维码加入", color = Color(0xFF9BA5B5), fontSize = 13.sp)
                                Spacer(Modifier.height(10.dp))
                                TogetherQrCode(it, Modifier.size(190.dp))
                                Spacer(Modifier.height(6.dp))
                                OutlinedButton(onClick = {
                                    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    manager.setPrimaryClip(android.content.ClipData.newPlainText("invite", it))
                                }) { Text("复制完整邀请") }
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("等待好友加入，双方将同步播放同一首歌", color = Color(0xFF5B6577), fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                }
                TogetherConnection.Connecting -> Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 3.dp)
                        Spacer(Modifier.height(12.dp))
                        Text("正在直连房主…", color = Color(0xFF8F99A9), fontSize = 12.sp)
                    }
                }
                TogetherConnection.Connected -> {
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(Modifier.size(40.dp), CircleShape, color = MaterialTheme.colorScheme.primary.copy(alpha = .16f)) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.People, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (together.isHost) "与 ${together.peer?.nickname ?: "好友"} 一起听中"
                                else "房主 ${together.peer?.nickname ?: ""} 的房间",
                                color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold
                            )
                            Text(if (together.connectMode == TogetherConnectMode.MultiPath) "跨网多路径直连" else "局域网直连", color = Color(0xFF8F99A9), fontSize = 11.sp)
                        }
                    }
                    val currentSong: SongEntity? = if (together.isHost) playback.song else vm.togetherCurrentSharedSong()
                    currentSong?.let { song ->
                        Surface(Modifier.fillMaxWidth().padding(top = 4.dp), RoundedCornerShape(16.dp), color = Color(0xFF202836)) {
                            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Surface(Modifier.size(46.dp), RoundedCornerShape(12.dp), color = Color(0xFF2A3342)) { ArtworkImage(song.albumArtUri, Color(0xFF2A3342)) }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(song.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(song.artist.ifBlank { "未知歌手" }, color = Color(0xFF8F99A9), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                if (together.isMember) Text("同步播放", color = MaterialTheme.colorScheme.primary, fontSize = 10.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("共享队列", color = Color(0xFF9BA5B5), fontSize = 13.sp, modifier = Modifier.padding(vertical = 2.dp))
                    if (together.sharedQueue.isEmpty()) {
                        Text("暂无共享队列", color = Color(0xFF5B6577), fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
                    } else {
                        LazyColumn(Modifier.heightIn(max = 150.dp)) {
                            itemsIndexed(together.sharedQueue) { index, entry ->
                                ListItem(
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                    headlineContent = { Text(entry.title, color = if (index == together.sharedIndex) MaterialTheme.colorScheme.primary else Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    supportingContent = { Text(entry.artist.ifBlank { "未知歌手" }, color = Color(0xFF8F99A9), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    leadingContent = { Icon(if (index == together.sharedIndex) Icons.Default.PlayArrow else Icons.Default.MusicNote, null, tint = if (index == together.sharedIndex) MaterialTheme.colorScheme.primary else Color(0xFF5B6577)) },
                                    modifier = Modifier.clickable { vm.togetherPlayIndex(index) }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("聊天 · 表情", color = Color(0xFF9BA5B5), fontSize = 13.sp, modifier = Modifier.padding(vertical = 2.dp))
                    TogetherEmojiBar(vm, Modifier.fillMaxWidth().padding(vertical = 4.dp), compact = true)
                    TogetherChatPanel(vm, together.messages)
                }
                TogetherConnection.Error -> {
                    Box(Modifier.fillMaxWidth().padding(vertical = 20.dp), contentAlignment = Alignment.Center) {
                        Text(together.error ?: "发生错误", color = Color(0xFFFF8A96), fontSize = 13.sp, textAlign = TextAlign.Center)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable private fun TogetherQrCode(content: String, modifier: Modifier = Modifier) {
    val bitmap = remember(content) { qrBitmap(content) }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "一起听邀请二维码",
            modifier = modifier
        )
    } else {
        Box(modifier.background(Color.White.copy(alpha = .08f), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            Text("二维码不可用", color = Color(0xFF8F99A9), fontSize = 12.sp)
        }
    }
}

@Composable private fun TogetherEmojiBar(vm: MusicViewModel, modifier: Modifier = Modifier, compact: Boolean = false) {
    Row(modifier, horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        TogetherReaction.entries.forEach { reaction ->
            Surface(
                shape = CircleShape,
                color = Color.White.copy(alpha = .06f),
                modifier = Modifier.size(if (compact) 36.dp else 42.dp).clickable { vm.togetherSendReaction(reaction) },
                contentColor = Color.White
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(reaction.symbol, fontSize = if (compact) 18.sp else 21.sp)
                }
            }
        }
    }
}

private fun reactionLabel(symbol: String): String =
    TogetherReaction.entries.firstOrNull { it.symbol == symbol }?.label ?: "互动"

@Composable private fun TogetherMessagePill(msg: TogetherChatMessage, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(50.dp), color = Color(0xE61A212D), shadowElevation = 5.dp) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (msg.fromSelf) "我" else msg.nickname,
                color = if (msg.fromSelf) Color(0xFF9DB8FF) else Color(0xFFFFCF8F),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.width(8.dp))
            when (msg.kind) {
                TogetherChatKind.Reaction -> Text(msg.text + " " + reactionLabel(msg.text), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                TogetherChatKind.Text -> Text(msg.text, color = Color.White, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** 悬浮弹幕：把最近的表情互动和聊天以弹幕形式短暂展示在任意页面上方。 */
@Composable private fun TogetherBarrageOverlay(vm: MusicViewModel, modifier: Modifier = Modifier) {
    val t by vm.togetherState.collectAsStateWithLifecycle()
    if (t.connection != TogetherConnection.Connected) return
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(400)
            now = System.currentTimeMillis()
        }
    }
    val items = t.messages.filter { msg -> now - msg.at in 0..3_800 }.takeLast(3)
    if (items.isEmpty()) return
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { msg -> TogetherMessagePill(msg) }
    }
}

/** 一起听底部弹窗内的聊天区：发送文字消息，也把表情互动作为消息展示出来。 */
@Composable private fun TogetherChatPanel(vm: MusicViewModel, messages: List<TogetherChatMessage>, modifier: Modifier = Modifier) {
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem((messages.size - 1).coerceAtLeast(0))
    }
    Column(modifier) {
        if (messages.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(54.dp), contentAlignment = Alignment.Center) {
                Text("还没有互动，发个表情或说句话吧", color = Color(0xFF5B6577), fontSize = 12.sp)
            }
        } else {
            LazyColumn(state = listState, modifier = Modifier.heightIn(max = 190.dp)) {
                items(messages) { msg ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        horizontalArrangement = if (msg.fromSelf) Arrangement.End else Arrangement.Start
                    ) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = if (msg.fromSelf) Color(0xFF2E3A52) else Color(0xFF232B38),
                            modifier = Modifier.widthIn(max = 260.dp)
                        ) {
                            Column(Modifier.padding(horizontal = 12.dp, vertical = 7.dp)) {
                                Text(
                                    if (msg.fromSelf) "我" else msg.nickname,
                                    color = if (msg.fromSelf) Color(0xFF9DB8FF) else Color(0xFFFFCF8F),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                when (msg.kind) {
                                    TogetherChatKind.Reaction -> Text(msg.text + " " + reactionLabel(msg.text), color = Color.White, fontSize = 16.sp)
                                    TogetherChatKind.Text -> Text(msg.text, color = Color.White, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("说点什么…", color = Color(0xFF6A7486), fontSize = 13.sp) },
                singleLine = true,
                shape = RoundedCornerShape(24.dp)
            )
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = {
                if (draft.isNotBlank()) {
                    vm.togetherSendMessage(draft)
                    draft = ""
                }
            }, enabled = draft.isNotBlank()) {
                Icon(Icons.Filled.Send, "发送", tint = if (draft.isNotBlank()) MaterialTheme.colorScheme.primary else Color(0xFF5B6577))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun TogetherQueueSheet(together: TogetherSessionState, vm: MusicViewModel, close: () -> Unit) {
    ModalBottomSheet(onDismissRequest = close, containerColor = Color(0xFF171B23)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("一起听 · 共享队列", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Text("点击切歌（双方同步）", color = Color(0xFF8F99A9), fontSize = 12.sp)
                }
                TextButton(close) { Text("关闭") }
            }
            if (together.sharedQueue.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(vertical = 30.dp), contentAlignment = Alignment.Center) { Text("队列为空", color = Color(0xFF5B6577)) }
            } else {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    itemsIndexed(together.sharedQueue) { index, entry ->
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = { Text(entry.title, color = if (index == together.sharedIndex) MaterialTheme.colorScheme.primary else Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(entry.artist.ifBlank { "未知歌手" }, color = Color(0xFF8F99A9), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingContent = { Surface(Modifier.size(44.dp), RoundedCornerShape(12.dp), color = Color(0xFF252C38)) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Icons.Default.MusicNote, null, tint = if (index == together.sharedIndex) MaterialTheme.colorScheme.primary else Color(0xFF5B6577)) } } },
                            modifier = Modifier.clickable { vm.togetherPlayIndex(index) }
                        )
                    }
                }
            }
        }
    }
}

private fun qrBitmap(content: String): Bitmap? = runCatching {
    val size = 512
    val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(content, com.google.zxing.BarcodeFormat.QR_CODE, size, size)
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        for (x in 0 until size) {
            pixels[y * size + x] = if (matrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }
    }
    Bitmap.createBitmap(pixels, size, size, Bitmap.Config.RGB_565)
}.getOrNull()

private fun statusTogether(together: TogetherSessionState): String = when (together.connection) {
    TogetherConnection.Idle -> "创建房间邀请好友，或输入对方邀请码加入"
    TogetherConnection.Listening -> "已开房 · 等待好友加入（对等控制 + 表情互动）"
    TogetherConnection.Connecting -> "正在连接房主…"
    TogetherConnection.Connected -> if (together.isHost) "已连接 · 与 ${together.peer?.nickname ?: "好友"} 一起听" else "已加入房主 ${together.peer?.nickname ?: ""} 一起听"
    TogetherConnection.Error -> together.error ?: "会话出错"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun PlayerScreen(vm: MusicViewModel, openMenu: () -> Unit, onOpenCastSheet: () -> Unit, onOpenTogetherSheet: () -> Unit) {
    val fmMode by vm.fmModeEnabled.collectAsStateWithLifecycle()
    val karaokeMode by vm.karaokeModeEnabled.collectAsStateWithLifecycle()
    val playerTheme by vm.playerTheme.collectAsStateWithLifecycle()
    val display = when {
        karaokeMode -> "karaoke"
        fmMode -> "fm"
        else -> "classic"
    }
    AnimatedContent(display, label = "playerMode") { mode ->
        when (mode) {
            "karaoke" -> KaraokePlayerScreen(vm, openMenu)
            "fm" -> FmPlayerScreen(vm, openMenu)
            else -> if (playerTheme == PlayerTheme.Classic) TraditionalPlayerScreen(vm, openMenu, onOpenCastSheet, onOpenTogetherSheet)
                else ScenePlayerScreen(vm, playerTheme, openMenu, onOpenCastSheet, onOpenTogetherSheet)
        }
    }
}

@Composable private fun ScenePlayerScreen(vm: MusicViewModel, theme: PlayerTheme, openMenu: () -> Unit, openCast: () -> Unit, openTogether: () -> Unit) {
    val state by vm.playback.collectAsStateWithLifecycle()
    val cast by vm.castState.collectAsStateWithLifecycle()
    val together by vm.togetherState.collectAsStateWithLifecycle()
    val lyrics by vm.lyrics.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val castConnected = cast.connection == CastSessionController.Connection.Connected
    val session = together.connection == TogetherConnection.Connected && together.inSession
    val sharedSong = remember(together.connection, together.role, together.sharedSong) {
        if (together.isMember && together.connection == TogetherConnection.Connected) vm.togetherCurrentSharedSong() else null
    }
    val song = sharedSong ?: state.song
    var showQueue by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }
    var showArtwork by remember { mutableStateOf(false) }
    var showLyrics by remember { mutableStateOf(false) }
    val mode = if (session) runCatching { PlaybackMode.valueOf(together.sharedMode) }.getOrDefault(state.mode) else state.mode
    ScenePlayer(
        theme = theme, song = song, lyrics = lyrics,
        playing = if (castConnected) cast.playing else state.playing,
        position = if (castConnected) cast.positionMs else state.position,
        duration = if (castConnected) cast.durationMs else state.duration,
        favorite = favorites.any { it.mediaStoreId == song?.mediaStoreId },
        playbackMode = mode, error = state.error,
        connectionLabel = when { castConnected -> cast.device?.friendlyName ?: "投屏中"; session -> "一起听 · ${together.peer?.nickname ?: "好友"}"; else -> null },
        onConnection = { if (castConnected) openCast() else openTogether() },
        onMenu = openMenu, onTheme = vm::setPlayerTheme,
        onToggle = { when { castConnected -> vm.castTogglePlayPause(); session -> vm.togetherTogglePlayPause(); else -> vm.player.toggle() } },
        onPrevious = { when { castConnected -> vm.castPrevious(); session -> vm.togetherPrevious(); else -> vm.player.previous() } },
        onNext = { when { castConnected -> vm.castNext(); session -> vm.togetherNext(); else -> vm.player.next() } },
        onSeek = { target -> when { castConnected -> vm.castSeek(target); session -> vm.togetherSeek(target); else -> vm.player.seek(target) } },
        onFavorite = { song?.let { vm.favorite(it.mediaStoreId) } },
        onQueue = { if (castConnected) openCast() else showQueue = true },
        onMode = { if (!castConnected) { if (session) vm.togetherSetMode(mode.next()) else vm.player.setPlaybackMode(mode.next()) } },
        onDetails = { showDetails = true }, onFindLyrics = { vm.findLyrics() }, onLyrics = { showLyrics = true }
    )
    if (showQueue) { if (session) TogetherQueueSheet(together, vm) { showQueue = false } else QueueSheet(state, vm) { showQueue = false } }
    if (showLyrics) {
        @OptIn(ExperimentalMaterial3Api::class)
        ModalBottomSheet(onDismissRequest = { showLyrics = false }, containerColor = Color(0xFF171B23)) { LyricsPanel(vm, Modifier.fillMaxWidth().height(460.dp)) { showLyrics = false } }
    }
    if (showArtwork && song != null) AlbumArtworkSheet(song.album, listOf(song), vm, allowReplace = true) { showArtwork = false }
    if (showDetails && song != null) SongDetailsSheet(song, getArtwork = { showDetails = false; showArtwork = true }, getLyrics = { showDetails = false; showLyrics = true; vm.findLyrics() }, close = { showDetails = false })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun TraditionalPlayerScreen(vm: MusicViewModel, openMenu: () -> Unit, onOpenCastSheet: () -> Unit, onOpenTogetherSheet: () -> Unit) {
    val state by vm.playback.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showQueue by remember { mutableStateOf(false) }
    var showThemePicker by remember { mutableStateOf(false) }
    val theme by vm.theme.collectAsStateWithLifecycle()
    val beatEffect by vm.beatEffect.collectAsStateWithLifecycle()
    val bottomEffect by vm.bottomEffect.collectAsStateWithLifecycle()
    val playerBackground by vm.playerBackground.collectAsStateWithLifecycle()
    val playerDiscOpacity by vm.playerDiscOpacity.collectAsStateWithLifecycle()
    val playerDiscVisible by vm.playerDiscVisible.collectAsStateWithLifecycle()
    val castState by vm.castState.collectAsStateWithLifecycle()
    val togetherState by vm.togetherState.collectAsStateWithLifecycle()
    val sessionSharedSong = remember(togetherState.connection, togetherState.role, togetherState.sharedSong) {
        if (togetherState.isMember && togetherState.connection == TogetherConnection.Connected) vm.togetherCurrentSharedSong() else null
    }
    val song = sessionSharedSong ?: state.song
    val artworkColors by produceState(initialValue = ArtworkColors.fallback(theme), song?.albumArtUri, theme) {
        value = loadArtworkColors(context, song?.albumArtUri, theme)
    }
    val pulseColors = artworkColors.pulseColors
    var ambientTarget by remember { mutableStateOf(artworkColors.primary) }
    var pulseScale by remember { mutableFloatStateOf(1f) }
    val discRotation = remember { Animatable(0f) }
    LaunchedEffect(state.playing, state.song?.mediaStoreId, artworkColors, beatEffect) {
        if (!state.playing || !beatEffect) {
            ambientTarget = artworkColors.primary
            pulseScale = 1f
            return@LaunchedEffect
        }
        while (true) {
            ambientTarget = pulseColors.random()
            pulseScale = if (pulseScale > 1f) 1f else Random.nextDouble(1.012, 1.035).toFloat()
            kotlinx.coroutines.delay(Random.nextLong(380, 900))
        }
    }
    LaunchedEffect(state.playing, state.song?.mediaStoreId) {
        while (state.playing) {
            discRotation.animateTo(
                discRotation.value + 360f,
                animationSpec = tween(18_000, easing = LinearEasing)
            )
        }
    }
    val ambient by animateColorAsState(ambientTarget, tween(650), label = "ambient")
    val artworkScale by animateFloatAsState(pulseScale, tween(420), label = "artworkPulse")
    val castConnected = castState.connection == CastSessionController.Connection.Connected
    val togetherConnected = togetherState.connection == TogetherConnection.Connected
    val sessionActive = togetherConnected && togetherState.inSession
    val displayPlaying = if (castConnected) castState.playing else state.playing
    val sessionMode = if (sessionActive) runCatching { PlaybackMode.valueOf(togetherState.sharedMode) }.getOrDefault(state.mode) else state.mode
    var showArtworkPicker by remember { mutableStateOf(false) }
    var showSongDetails by remember { mutableStateOf(false) }
    var showLyrics by remember { mutableStateOf(false) }
    var showPlayerControls by remember { mutableStateOf(false) }
    var controlInteraction by remember { mutableIntStateOf(0) }
    var showSessionQueue by remember { mutableStateOf(false) }
    LaunchedEffect(song?.mediaStoreId) { showLyrics = false }
    BoxWithConstraints(
        Modifier.fillMaxSize()
            .background(artworkColors.dark)
            .background(Brush.radialGradient(listOf(artworkColors.light.copy(alpha = .42f), Color.Transparent), center = Offset(120f, 80f), radius = 820f))
            .background(Brush.radialGradient(listOf(ambient.copy(alpha = .48f), Color.Transparent), center = Offset(920f, 620f), radius = 1050f))
            .background(Brush.radialGradient(listOf(artworkColors.primary.copy(alpha = .24f), Color.Transparent, artworkColors.dark.copy(alpha = .72f)), center = Offset(520f, 430f), radius = 1300f))
    ) {
    PlayerBackground(playerBackground)
    IconButton(openMenu, Modifier.statusBarsPadding().padding(10.dp).align(Alignment.TopStart)) { Icon(Icons.Default.Menu, "菜单", tint = Color.White) }
    IconButton({ showThemePicker = true }, Modifier.statusBarsPadding().padding(top = 10.dp, end = 10.dp).align(Alignment.TopEnd)) { Icon(Icons.Default.Palette, "切换播放主题", tint = Color.White) }
    val castConnection = castState.connection
    if (castConnection != CastSessionController.Connection.Disconnected) {
        Surface(
            Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 12.dp, end = 12.dp).clickable(onClick = onOpenCastSheet),
            shape = RoundedCornerShape(50.dp),
            color = Color(0xFF1E2531).copy(alpha = .94f),
            shadowElevation = 6.dp
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Cast, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(7.dp))
                Text(
                    if (castConnection == CastSessionController.Connection.Connected) (castState.device?.friendlyName ?: "投屏中") else "正在连接",
                    color = Color.White,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 150.dp)
                )
            }
        }
    }
    if (togetherState.connection != TogetherConnection.Idle && !castConnected) {
        val togetherLabel = when {
            togetherState.connection == TogetherConnection.Connected && togetherState.isHost -> "与 ${togetherState.peer?.nickname ?: "好友"} 一起听"
            togetherState.connection == TogetherConnection.Connected && togetherState.isMember -> "房主 ${togetherState.peer?.nickname ?: ""}"
            togetherState.connection == TogetherConnection.Listening -> "等待加入 ${togetherState.code ?: ""}"
            else -> "一起听"
        }
        Surface(
            Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 56.dp, end = 12.dp).clickable(onClick = onOpenTogetherSheet),
            shape = RoundedCornerShape(50.dp),
            color = Color(0xFF1E2531).copy(alpha = .94f),
            shadowElevation = 6.dp
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.People, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(7.dp))
                Text(
                    togetherLabel,
                    color = Color.White,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 170.dp)
                )
            }
        }
    }
    if (bottomEffect && !showLyrics && !castConnected) {
        BottomSpectrumStage(
            levelsFlow = vm.player.spectrum,
            playing = state.playing,
            colors = artworkColors,
            modifier = Modifier.fillMaxWidth().height(BOTTOM_STAGE_HEIGHT).navigationBarsPadding().align(Alignment.BottomCenter)
        )
    }
    LaunchedEffect(showPlayerControls, controlInteraction) {
        if (showPlayerControls) {
            delay(4_000)
            showPlayerControls = false
        }
    }
    if (!showPlayerControls) {
        Box(
            Modifier.align(Alignment.BottomCenter).zIndex(1f).navigationBarsPadding().padding(bottom = 4.dp)
                .fillMaxWidth().height(58.dp).clickable { showPlayerControls = true; controlInteraction++ }
        )
    }
    AnimatedVisibility(
        visible = showPlayerControls,
        modifier = Modifier.align(Alignment.BottomCenter).zIndex(2f).navigationBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp),
        enter = fadeIn(), exit = fadeOut()
    ) {
        Surface(Modifier.fillMaxWidth().widthIn(max = 420.dp), shape = RoundedCornerShape(28.dp), color = Color(0xFF171C25).copy(alpha = .94f), shadowElevation = 12.dp, border = BorderStroke(1.dp, theme.color().copy(alpha = .4f))) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 5.dp).clickable { controlInteraction++ }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
                    PlaybackModeButton(sessionMode, enabled = !castConnected) {
                        if (!castConnected) {
                            val nextMode = sessionMode.next()
                            if (sessionActive) vm.togetherSetMode(nextMode) else vm.player.setPlaybackMode(nextMode)
                        }
                    }
                    IconButton(onClick = {
                        when {
                            castConnected -> vm.castPrevious()
                            sessionActive -> vm.togetherPrevious()
                            else -> vm.player.previous()
                        }
                    }) { Icon(Icons.Default.SkipPrevious, "上一首", tint = Color.White) }
                    IconButton(onClick = {
                        when {
                            castConnected -> vm.castTogglePlayPause()
                            sessionActive -> vm.togetherTogglePlayPause()
                            else -> vm.player.toggle()
                        }
                    }) { Icon(if (displayPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "播放或暂停", tint = Color.White) }
                    IconButton(onClick = {
                        when {
                            castConnected -> vm.castNext()
                            sessionActive -> vm.togetherNext()
                            else -> vm.player.next()
                        }
                    }) { Icon(Icons.Default.SkipNext, "下一首", tint = Color.White) }
                    IconButton(onClick = { if (!castConnected) { if (sessionActive) showSessionQueue = true else showQueue = true } }, enabled = !castConnected) {
                        Icon(Icons.Default.QueueMusic, "播放列表", tint = if (castConnected) Color(0xFF5B6577) else Color.White)
                    }
                    IconButton(onClick = { showLyrics = !showLyrics }) {
                        Text("词", color = if (showLyrics) theme.color() else Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    }
            }
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(if (showLyrics) .12f else .45f))
        BoxWithConstraints(
            Modifier.fillMaxWidth().then(if (showLyrics) Modifier.weight(1f) else Modifier),
            contentAlignment = Alignment.Center
        ) {
            val artworkSize = minOf(maxWidth - 48.dp, 330.dp)
            if (showLyrics) LyricsPanel(vm, Modifier.fillMaxSize()) { showLyrics = false } else if (playerDiscVisible) Box(
                Modifier.size(artworkSize).scale(artworkScale).graphicsLayer { rotationZ = discRotation.value; alpha = playerDiscOpacity }, contentAlignment = Alignment.Center
            ) {
                Surface(Modifier.fillMaxSize().clip(CircleShape), shape = CircleShape, tonalElevation = 12.dp, shadowElevation = 18.dp, color = Color(0xFF242B37)) {
                    Box(Modifier.fillMaxSize()) {
                        ArtworkImage(song?.albumArtUri, ambient)
                        if (!song?.albumArtUri.isNullOrBlank()) {
                            Box(Modifier.fillMaxSize().background(ambient.copy(alpha = .18f)))
                        }
                    }
                }
                Box(Modifier.fillMaxSize().padding(10.dp).border(1.dp, Color.White.copy(alpha = .22f), CircleShape))
                Surface(Modifier.size(34.dp), shape = CircleShape, color = Color(0xFF111722), tonalElevation = 3.dp) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF7C8798))) }
                }
            }
        }
        Spacer(Modifier.height(if (showLyrics) 12.dp else 30.dp))
        Column(Modifier.fillMaxWidth().heightIn(min = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                if (song != null) {
                    IconButton(onClick = { showSongDetails = true }, Modifier.size(30.dp)) {
                        Icon(Icons.Default.Info, "歌曲详情", Modifier.size(17.dp), tint = Color(0xFFBEC8D7))
                    }
                    Spacer(Modifier.width(3.dp))
                }
                Text(song?.title ?: "选择一首歌曲", Modifier.widthIn(max = 290.dp), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(7.dp))
            if (song == null) {
                Text("从音乐库开始播放", color = Color(0xFFADB6C5), fontSize = 14.sp, modifier = Modifier.height(22.dp))
            } else {
                Row(Modifier.height(22.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    Text(song.artist, color = Color(0xFFC5CDDA), fontSize = 14.sp, lineHeight = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.width(8.dp))
                    QualityBadge(song.audioQuality)
                }
            }
        }
        Spacer(Modifier.height(22.dp))
        val sliderDuration = if (castConnected) castState.durationMs else state.duration
        val sliderPosition = if (castConnected) castState.positionMs else state.position
        val waveform = remember(song?.mediaStoreId, sliderDuration) { List(56) { index -> .18f + ((index * 37) % 83) / 100f } }
        Slider(
            value = if (sliderDuration > 0) sliderPosition.toFloat() / sliderDuration else 0f,
            onValueChange = {
                val target = (it * sliderDuration).toLong()
                when {
                    castConnected -> vm.castSeek(target)
                    sessionActive -> vm.togetherSeek(target)
                    else -> vm.player.seek(target)
                }
            },
            modifier = Modifier.height(20.dp),
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White.copy(alpha = .9f),
                inactiveTrackColor = Color.White.copy(alpha = .18f)
            ),
            thumb = { },
            track = { sliderPositions ->
                Canvas(Modifier.fillMaxWidth().height(36.dp)) {
                    val gap = size.width / waveform.size
                    waveform.forEachIndexed { index, level ->
                        val x = gap * index + gap / 2f
                        val barHeight = size.height * level.coerceIn(.16f, 1f)
                        val played = x / size.width <= sliderPositions.value
                        drawRoundRect(
                            color = if (played) artworkColors.light.copy(alpha = .9f) else Color.White.copy(alpha = .2f),
                            topLeft = Offset(x - 1.5f, (size.height - barHeight) / 2f),
                            size = Size(3f, barHeight),
                            cornerRadius = CornerRadius(3f, 3f)
                        )
                    }
                }
            }
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(time(sliderPosition), color = Color(0xFF8A94A5), fontSize = 12.sp); Text(time(sliderDuration), color = Color(0xFF8A94A5), fontSize = 12.sp) }
        if (sessionActive) {
            Spacer(Modifier.height(6.dp))
            TogetherEmojiBar(vm, Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(16.dp))
         Spacer(Modifier.height(12.dp))
        state.error?.let { Text(it, color = Color(0xFFFF8A96), fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.weight(if (showLyrics) .08f else .5f))
    }
    }
    if (showQueue) QueueSheet(state, vm) { showQueue = false }
    if (showThemePicker) PlayerThemePicker(PlayerTheme.Classic, vm::setPlayerTheme) { showThemePicker = false }
    if (showSessionQueue) TogetherQueueSheet(togetherState, vm) { showSessionQueue = false }
    if (showArtworkPicker && song != null) AlbumArtworkSheet(song.album, listOf(song), vm, allowReplace = true) { showArtworkPicker = false }
    if (showSongDetails && song != null) SongDetailsSheet(
        song = song,
        getArtwork = { showSongDetails = false; showArtworkPicker = true },
        getLyrics = { showSongDetails = false; showLyrics = true; vm.findLyrics() },
        close = { showSongDetails = false }
    )
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable private fun FmPlayerScreen(vm: MusicViewModel, openMenu: () -> Unit) {
    val state by vm.playback.collectAsStateWithLifecycle()
    val theme by vm.theme.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val playerBackground by vm.playerBackground.collectAsStateWithLifecycle()
    val artworkColors by produceState(initialValue = ArtworkColors.fallback(theme), state.song?.albumArtUri, theme) {
        value = loadArtworkColors(context, state.song?.albumArtUri, theme)
    }
    val discRotation = remember { Animatable(0f) }
    var horizontalDrag by remember { mutableFloatStateOf(0f) }
    var showDetails by remember { mutableStateOf(false) }
    var showArtworkPicker by remember { mutableStateOf(false) }
    val song = state.song
    val progress = if (state.duration > 0) (state.position.toFloat() / state.duration).coerceIn(0f, 1f) else 0f

    LaunchedEffect(state.playing, song?.mediaStoreId) {
        while (state.playing) {
            discRotation.animateTo(discRotation.value + 360f, tween(22_000, easing = LinearEasing))
        }
    }
    LaunchedEffect(song?.mediaStoreId) {
        horizontalDrag = 0f
    }

    Box(
        Modifier.fillMaxSize()
            .background(artworkColors.dark)
            .background(Brush.radialGradient(listOf(artworkColors.primary.copy(alpha = .56f), Color.Transparent), center = Offset(860f, 360f), radius = 1150f))
            .background(Brush.radialGradient(listOf(artworkColors.light.copy(alpha = .28f), Color.Transparent, Color(0xFF05070A).copy(alpha = .78f)), center = Offset(80f, 80f), radius = 1350f))
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = vm.player::toggle,
                onLongClick = { if (song != null) showDetails = true }
            )
            .pointerInput(song?.mediaStoreId) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (abs(horizontalDrag) > 80f) vm.fmRandomSong()
                        horizontalDrag = 0f
                    },
                    onDragCancel = { horizontalDrag = 0f }
                ) { change, amount ->
                    change.consume()
                    horizontalDrag += amount
                }
            }
    ) {
        PlayerBackground(playerBackground)
        IconButton(openMenu, Modifier.statusBarsPadding().padding(10.dp).align(Alignment.TopStart).zIndex(2f)) {
            Icon(Icons.Default.Menu, "菜单", tint = Color.White)
        }
        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(42.dp))
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                val artworkSize = minOf(maxWidth * .92f, maxHeight * .94f, 430.dp)
                FmVinylRecord(
                    artworkUri = song?.albumArtUri,
                    colors = artworkColors,
                    progress = progress,
                    rotation = discRotation.value,
                    horizontalDrag = horizontalDrag,
                    playing = state.playing,
                    modifier = Modifier.size(artworkSize)
                )
            }
            Spacer(Modifier.height(10.dp))
            FmLyricsPreview(vm, Modifier.fillMaxWidth().height(106.dp))
            Spacer(Modifier.height(10.dp))
            Text(
                song?.title ?: "FM",
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(7.dp))
            Text(song?.artist ?: "从你的音乐库随机播放", color = Color.White.copy(alpha = .62f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(12.dp))
            Text("${time(state.position)}  /  ${time(state.duration)}", color = Color.White.copy(alpha = .4f), fontSize = 11.sp)
            state.error?.let { Text(it, color = Color(0xFFFF8A96), fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)) }
            Spacer(Modifier.height(8.dp))
        }
    }
    if (showDetails && song != null) SongDetailsSheet(
        song,
        getArtwork = { showDetails = false; showArtworkPicker = true },
        getLyrics = { showDetails = false; vm.findLyrics() },
        close = { showDetails = false }
    )
    if (showArtworkPicker && song != null) AlbumArtworkSheet(song.album, listOf(song), vm, allowReplace = true) { showArtworkPicker = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun KaraokePlayerScreen(vm: MusicViewModel, openMenu: () -> Unit) {
    val state by vm.playback.collectAsStateWithLifecycle()
    val karaokeState by vm.player.karaokeState.collectAsStateWithLifecycle()
    val theme by vm.theme.collectAsStateWithLifecycle()
    val playerBackground by vm.playerBackground.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var showScore by remember { mutableStateOf(false) }
    val song = state.song
    val mono = song?.channelCount == 1
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(theme.color().copy(alpha = .18f), Color(0xFF0B0E14))))) {
        PlayerBackground(playerBackground)
        Column(Modifier.fillMaxSize().systemBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(openMenu) { Icon(Icons.Default.Menu, "菜单", tint = Color.White) }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text("卡拉OK模式", color = Color(0xFFB8C1CF), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 3.sp)
                }
                IconButton({ expanded = !expanded }, enabled = song != null) { Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.Tune, if (expanded) "收起控制" else "展开跟唱控制", tint = if (song != null) Color.White else Color(0xFF5B6577)) }
            }
            if (song != null) {
                Column(Modifier.padding(horizontal = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(song.title, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(3.dp))
                    Text(song.artist, color = Color(0xFF9AA5B6), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (mono) {
                    Spacer(Modifier.height(5.dp))
                    Text("单声道音轨 · 去除人声不可用", color = Color(0xFFFFBE5C).copy(alpha = .85f), fontSize = 11.sp)
                }
            } else {
                Spacer(Modifier.height(10.dp))
            }
            Spacer(Modifier.height(4.dp))
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (song == null) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.MusicNote, null, Modifier.size(54.dp), tint = Color(0xFF65738A))
                        Spacer(Modifier.height(14.dp))
                        Text("从音乐库选择一首歌开始跟唱", color = Color(0xFF9AA5B6), fontSize = 15.sp, textAlign = TextAlign.Center)
                        Text("使用下方按钮控制播放", color = Color(0xFF5B6577), fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                    }
                } else {
                    KaraokeLyricsStage(vm, Modifier.fillMaxSize())
                }
            }
            AnimatedVisibility(
                visible = expanded && song != null,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                KaraokeControlSections(vm, karaokeState, mono, openScore = { showScore = true }, onCollapse = { expanded = false })
            }
            KaraokeProgressRail(state, vm, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp))
            KaraokeTransportRow(state, vm, openQueue = { showQueue = true }, modifier = Modifier.fillMaxWidth().height(62.dp))
            Spacer(Modifier.height(6.dp))
        }
    }
    if (showQueue) QueueSheet(state, vm) { showQueue = false }
    if (showScore && song != null) KaraokeScoreSheet(vm, song) { showScore = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun KaraokeLyricsStage(vm: MusicViewModel, modifier: Modifier = Modifier) {
    val lyricsState by vm.lyrics.collectAsStateWithLifecycle()
    val playback by vm.playback.collectAsStateWithLifecycle()
    val lines = (lyricsState as? LyricsState.Loaded)?.document?.lines.orEmpty()
    when {
        lyricsState == LyricsState.Loading -> Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.5.dp, color = Color.White.copy(alpha = .5f)) }
        lines.isEmpty() -> Box(modifier, contentAlignment = Alignment.Center) {
            Column(Modifier.padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Notes, null, Modifier.size(42.dp), tint = Color(0xFF5B6577))
                Spacer(Modifier.height(12.dp))
                Text(
                    if (lyricsState is LyricsState.Error) (lyricsState as LyricsState.Error).message else "纯音乐或暂无歌词",
                    color = Color(0xFF7E8A9A),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
                Text("可切到原唱继续演唱", color = Color(0xFF5B6577), fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
        else -> BoxWithConstraints(modifier) {
            val listState = rememberLazyListState()
            val current = lines.indexOfLast { line -> line.timeMs?.let(playback.position::compareTo) ?: -1 >= 0 }.coerceAtLeast(0)
            val verticalPadding = if (maxHeight.value > 56f) ((maxHeight.value - 56f) / 2f).dp else 0.dp
            LaunchedEffect(current, playback.song?.mediaStoreId) {
                if (!listState.isScrollInProgress) listState.animateScrollToItem(current)
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                contentPadding = PaddingValues(vertical = verticalPadding)
            ) {
                itemsIndexed(lines) { index, line ->
                    val isCurrent = index == current
                    Text(
                        line.text,
                        color = if (isCurrent) Color.White else Color.White.copy(alpha = .3f),
                        fontSize = if (isCurrent) 26.sp else 15.sp,
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                        lineHeight = if (isCurrent) 34.sp else 22.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = if (isCurrent) 8.dp else 6.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun KaraokeProgressRail(state: PlayerState, vm: MusicViewModel, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(time(state.position), color = Color(0xFF7E8A9A), fontSize = 11.sp)
            Text(time(state.duration), color = Color(0xFF7E8A9A), fontSize = 11.sp)
        }
        Slider(
            value = if (state.duration > 0) state.position.toFloat() / state.duration else 0f,
            onValueChange = { vm.player.seek((it * state.duration).toLong()) },
            modifier = Modifier.fillMaxWidth().height(18.dp),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = Color.White.copy(alpha = .55f),
                inactiveTrackColor = Color.White.copy(alpha = .14f)
            ),
            thumb = { Box(Modifier.size(7.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)) },
            track = { sliderPositions ->
                Box(Modifier.fillMaxWidth().height(2.5.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = .14f))) {
                    Box(Modifier.fillMaxWidth(sliderPositions.value).fillMaxHeight().clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = .55f)))
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun KaraokeControlSections(
    vm: MusicViewModel,
    karaokeState: KaraokeState,
    mono: Boolean,
    openScore: () -> Unit,
    onCollapse: () -> Unit
) {
    val vocalLabel = when {
        mono -> "不可用"
        karaokeState.vocal < .06f -> "纯伴奏"
        karaokeState.vocal > .94f -> "原唱"
        else -> "人声 ${(karaokeState.vocal * 100).roundToInt()}%"
    }
    Surface(Modifier.fillMaxWidth().padding(horizontal = 20.dp), shape = RoundedCornerShape(18.dp), color = Color(0xFF151A23)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            Row(Modifier.fillMaxWidth().height(34.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("跟唱控制", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(vocalLabel, color = if (mono) Color(0xFF9A5B00) else MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                IconButton(onCollapse, Modifier.size(30.dp)) { Icon(Icons.Default.ExpandLess, "收起控制", tint = Color.White) }
            }
            karaokeState.error?.let {
                Text(it, color = Color(0xFFFF8A96), fontSize = 11.sp, modifier = Modifier.padding(bottom = 4.dp))
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("伴奏", color = Color(0xFF8F99A9), fontSize = 12.sp)
                Slider(
                    value = karaokeState.vocal,
                    onValueChange = { vm.player.setKaraokeVocal(it) },
                    enabled = !mono,
                    modifier = Modifier.weight(1f).padding(horizontal = 10.dp).height(18.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = .8f),
                        inactiveTrackColor = Color.White.copy(alpha = .16f)
                    )
                )
                Text("原唱", color = Color(0xFF8F99A9), fontSize = 12.sp)
            }
            if (mono) Text("当前为单声道音轨，中心声道抵消无效，请在原唱下演唱", color = Color(0xFFFFBE5C).copy(alpha = .85f), fontSize = 11.sp, modifier = Modifier.padding(vertical = 2.dp))
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("变调", color = Color(0xFF8F99A9), fontSize = 12.sp, modifier = Modifier.padding(end = 10.dp))
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (-7..7).forEach { semi ->
                        FilterChip(
                            selected = karaokeState.pitchSemi == semi,
                            onClick = { vm.player.setKaraokePitch(semi) },
                            enabled = !mono || semi == 0,
                            label = { Text(if (semi > 0) "+$semi" else semi.toString(), fontSize = 12.sp) },
                            shape = CircleShape,
                            border = null
                        )
                    }
                }
                if (karaokeState.pitchSemi != 0) TextButton(onClick = { vm.player.setKaraokePitch(0) }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("归零", fontSize = 11.sp) }
            }
            HorizontalDivider(color = Color.White.copy(alpha = .06f), modifier = Modifier.padding(vertical = 6.dp))
            Surface(Modifier.fillMaxWidth().height(40.dp), shape = RoundedCornerShape(13.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = .14f)) {
                Row(Modifier.fillMaxWidth().height(40.dp).clickable(onClick = openScore).padding(horizontal = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Mic, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(9.dp))
                    Text("演唱评分 · 跟唱录音", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Icon(Icons.Default.ChevronRight, null, Modifier.size(17.dp), tint = Color(0xFF8F99A9))
                }
            }
        }
    }
}

@Composable private fun KaraokeTransportRow(state: PlayerState, vm: MusicViewModel, openQueue: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
        PlaybackModeButton(state.mode) { vm.player.setPlaybackMode(state.mode.next()) }
        IconButton(onClick = vm.player::previous, Modifier.size(52.dp)) { Icon(Icons.Default.SkipPrevious, "上一首", Modifier.size(30.dp), tint = Color.White) }
        Surface(Modifier.size(54.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
            Box(Modifier.fillMaxSize().clickable(onClick = vm.player::toggle), contentAlignment = Alignment.Center) {
                Icon(if (state.playing) Icons.Default.Pause else Icons.Default.PlayArrow, "播放或暂停", Modifier.size(32.dp), tint = Color(0xFF090B10))
            }
        }
        IconButton(onClick = vm.player::next, Modifier.size(52.dp)) { Icon(Icons.Default.SkipNext, "下一首", Modifier.size(30.dp), tint = Color.White) }
        IconButton(onClick = openQueue, Modifier.size(52.dp)) { Icon(Icons.Default.QueueMusic, "播放列表", tint = Color(0xFFB3BDCC)) }
    }
}

private data class PendingTake(val takeId: Int, val startMs: Long, val notes: List<MicNote>, val recordId: Long? = null)

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun KaraokeScoreSheet(vm: MusicViewModel, song: SongEntity, close: () -> Unit) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val playback by vm.playback.collectAsStateWithLifecycle()
    val reference by vm.vocalReference.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val recorder = remember { KaraokeMicRecorder() }
    val playerRef = remember { arrayOfNulls<MediaPlayer>(1) }
    var recording by remember { mutableStateOf(false) }
    var processing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<SingingScore?>(null) }
    var previewPath by remember { mutableStateOf<String?>(null) }
    var recordStartMs by remember { mutableStateOf(0L) }
    var previewing by remember { mutableStateOf(false) }
    var resumePlayback by remember { mutableStateOf(false) }
    var takeId by remember { mutableStateOf(0) }
    var pendingTake by remember { mutableStateOf<PendingTake?>(null) }
    var persisted by remember { mutableStateOf(false) }
    var permissionGranted by remember { mutableStateOf(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> permissionGranted = granted }
    val live by recorder.live.collectAsStateWithLifecycle()

    LaunchedEffect(song.mediaStoreId) { vm.requestVocalReference(song) }

    LaunchedEffect(reference) {
        val state = reference
        if (state !is VocalReferenceState.Ready) return@LaunchedEffect
        val take = pendingTake ?: return@LaunchedEffect
        if (take.takeId != takeId) return@LaunchedEffect
        val score = withContext(Dispatchers.Default) {
            VocalPitchAnalyzer.computeScore(state.frames, take.notes, take.startMs)
        }
        if (pendingTake?.takeId == take.takeId) {
            result = score
            pendingTake?.recordId?.let { id -> vm.updateSingingRecordScore(id, score) }
            pendingTake = null
        }
    }

    fun resumeSongAfterPreview() {
        if (resumePlayback) {
            resumePlayback = false
            vm.player.toggle()
        }
    }

    fun pauseSongForPreview() {
        if (playback.playing) {
            resumePlayback = true
            vm.player.pause()
        } else {
            resumePlayback = false
        }
    }

    fun stopPreview() {
        val player = playerRef[0] ?: return
        playerRef[0] = null
        runCatching { player.stop() }
        runCatching { player.release() }
        if (previewing) {
            previewing = false
            resumeSongAfterPreview()
        }
    }

    fun deleteCurrentRecording() {
        previewPath?.let { runCatching { File(it).delete() } }
        previewPath = null
    }

    DisposableEffect(Unit) {
        onDispose {
            recorder.discard()
            stopPreview()
            resumeSongAfterPreview()
            deleteCurrentRecording()
        }
    }

    fun finishRecording() {
        if (!recording || processing) return
        recording = false
        processing = true
        val thisTake = takeId
        val startMs = recordStartMs
        scope.launch {
            val captured = withContext(Dispatchers.IO) { recorder.stop() }
            if (captured == null) {
                processing = false
                return@launch
            }
            val refState = vm.vocalReference.value
            val refAtFinish = (refState as? VocalReferenceState.Ready)?.frames
            val score = withContext(Dispatchers.Default) {
                VocalPitchAnalyzer.computeScore(refAtFinish, captured.notes, startMs)
            }
            val path = withContext(Dispatchers.IO) {
                val file = File(appContext.cacheDir, "karaoke_${System.currentTimeMillis()}.wav")
                if (writeWav16Mono(file, captured.samples, captured.sampleRate)) file.absolutePath else null
            }
            val savedId = if (path != null) {
                val durationMs = captured.samples.size * 1000L / captured.sampleRate
                withContext(Dispatchers.IO) { runCatching { vm.persistSingingRecord(song, File(path), score, durationMs) }.getOrNull() }
            } else null
            result = score
            persisted = savedId != null
            deleteCurrentRecording()
            previewPath = path
            pendingTake = if (refAtFinish == null && (refState is VocalReferenceState.Computing || refState is VocalReferenceState.Idle)) {
                PendingTake(thisTake, startMs, captured.notes, savedId)
            } else {
                null
            }
            processing = false
        }
    }

    fun startRecording() {
        if (recording || processing) return
        stopPreview()
        deleteCurrentRecording()
        if (!permissionGranted) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (!recorder.start()) return
        takeId += 1
        pendingTake = null
        persisted = false
        result = null
        recordStartMs = playback.position
        recording = true
    }

    fun togglePreview() {
        val current = playerRef[0]
        if (current?.isPlaying == true) {
            stopPreview()
            return
        }
        if (current != null) {
            playerRef[0] = null
            runCatching { current.release() }
            if (previewing) {
                previewing = false
                resumeSongAfterPreview()
            }
        }
        val path = previewPath ?: return
        val player = MediaPlayer()
        runCatching {
            player.setDataSource(path)
            player.setOnCompletionListener {
                runCatching { it.release() }
                if (playerRef[0] === it) playerRef[0] = null
                if (previewing) {
                    previewing = false
                    resumeSongAfterPreview()
                }
            }
            player.prepare()
            pauseSongForPreview()
            player.start()
            playerRef[0] = player
            previewing = true
        }.onFailure {
            runCatching { player.release() }
            if (resumePlayback) resumeSongAfterPreview()
        }
    }

    ModalBottomSheet(onDismissRequest = { if (!recording && !processing) close() }, containerColor = Color(0xFF171B23)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("演唱评分", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    Text(song.title, color = Color(0xFF8F99A9), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                when {
                    processing -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                    recording -> TextButton(onClick = ::finishRecording) { Text("结束", color = Color(0xFFFF8A96)) }
                    else -> IconButton(close) { Icon(Icons.Default.Close, "关闭", tint = Color.White) }
                }
            }
            Spacer(Modifier.height(8.dp))
            when (val state = reference) {
                is VocalReferenceState.Computing -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("正在分析原唱音高轮廓(仅首次,请稍候)", color = Color(0xFF9AA5B6), fontSize = 12.sp)
                }
                is VocalReferenceState.Ready -> Text("参考音高轮廓已就绪 · ${state.frames.size} 帧 · 纯伴奏下演唱更准", color = Color(0xFF55D6A9), fontSize = 12.sp)
                is VocalReferenceState.Unavailable -> Text(state.message, color = Color(0xFFFFBE5C), fontSize = 12.sp)
                VocalReferenceState.Idle -> Text("准备分析原唱音高…", color = Color(0xFF9AA5B6), fontSize = 12.sp)
            }
            Spacer(Modifier.height(14.dp))

            val score = result
            when {
                score != null -> {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(86.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = .18f)), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(gradeLabel(score.total), color = MaterialTheme.colorScheme.primary, fontSize = 30.sp, fontWeight = FontWeight.Black)
                                Text("${score.total}", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        Spacer(Modifier.width(18.dp))
                        Column(Modifier.weight(1f)) {
                            Text(if (score.lightweight) "活跃度评分(轻量)" else "综合评分", color = Color(0xFFC5CDDA), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(5.dp))
                            Row {
                                ScoreChip("音准", score.pitch)
                                Spacer(Modifier.width(8.dp))
                                ScoreChip("活跃度", score.activity)
                            }
                            if (score.lightweight) Text("未获得参考音高,按演唱时长与音量评估", color = Color(0xFF818C9C), fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp))
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = {
                            stopPreview()
                            result = null
                        }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Refresh, null, Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text("再唱一次") }
                        Button(onClick = ::togglePreview, enabled = previewPath != null && !processing, modifier = Modifier.weight(1f)) {
                            Icon(if (previewing) Icons.Default.Stop else Icons.Default.PlayArrow, null, Modifier.size(17.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (previewing) "停止试听" else "试听演唱")
                        }
                    }
                    if (persisted) {
                        Spacer(Modifier.height(8.dp))
                        Text("已保存到「我的演唱」", color = Color(0xFF55D6A9), fontSize = 11.sp)
                    }
                }
                processing -> Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = Color.White.copy(alpha = .06f)) {
                    Row(Modifier.padding(horizontal = 18.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("正在生成评分与试听…", color = Color(0xFFC5CDDA), fontSize = 14.sp)
                    }
                }
                recording -> Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = Color(0xFFFF5F74).copy(alpha = .08f)) {
                    Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFFFF5F74)))
                            Spacer(Modifier.width(8.dp))
                            Text("正在录音演唱…", color = Color(0xFFFF8A96), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.height(14.dp))
                        LinearProgressIndicator(progress = { live.level.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape), color = MaterialTheme.colorScheme.primary, trackColor = Color.White.copy(alpha = .1f))
                        Spacer(Modifier.height(10.dp))
                        Text(
                            live.frequency?.let { "音高 %.0f Hz".format(it) } ?: "未检测到音高(请靠近麦克风演唱)",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text("点击上方「结束」完成演唱与评分", color = Color(0xFF818C9C), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }
                else -> Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = Color.White.copy(alpha = .06f)) {
                    Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Mic, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(10.dp))
                        Text("点击开始,跟随伴奏演唱并录音", color = Color(0xFFC5CDDA), fontSize = 14.sp, textAlign = TextAlign.Center)
                        Text("结束后给出音准与活跃度评分,并支持试听", color = Color(0xFF818C9C), fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = ::startRecording,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (permissionGranted) "开始演唱" else "授权麦克风后开始") }
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}

@Composable private fun ScoreChip(label: String, value: Int?) {
    Surface(shape = RoundedCornerShape(50), color = Color.White.copy(alpha = .08f)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Color(0xFF8F99A9), fontSize = 11.sp)
            Spacer(Modifier.width(6.dp))
            Text(if (value == null) "--" else "$value", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable private fun FmVinylRecord(
    artworkUri: String?,
    colors: ArtworkColors,
    progress: Float,
    rotation: Float,
    horizontalDrag: Float,
    playing: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier.offset { IntOffset(horizontalDrag.roundToInt(), 0) }.graphicsLayer {
            val travel = abs(horizontalDrag) / 600f
            scaleX = (1f - travel).coerceAtLeast(.92f)
            scaleY = scaleX
            alpha = (1f - travel * .5f).coerceAtLeast(.72f)
        },
        contentAlignment = Alignment.Center
    ) {
        Surface(Modifier.fillMaxSize().padding(8.dp), shape = CircleShape, shadowElevation = 28.dp, color = Color(0xFF080A0E)) {
            Box(Modifier.fillMaxSize().graphicsLayer { rotationZ = rotation }, contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    drawCircle(Brush.radialGradient(listOf(Color(0xFF24272D), Color(0xFF090B0E), Color.Black)))
                    val grooveColor = Color.White.copy(alpha = .075f)
                    repeat(9) { index ->
                        val fraction = .58f + index * .045f
                        drawCircle(grooveColor, radius = size.minDimension * fraction / 2f, style = Stroke(width = 1f))
                    }
                    drawArc(
                        Brush.sweepGradient(listOf(Color.Transparent, Color.White.copy(alpha = .2f), Color.Transparent, Color.White.copy(alpha = .08f), Color.Transparent)),
                        startAngle = 204f,
                        sweepAngle = 128f,
                        useCenter = false,
                        style = Stroke(width = size.minDimension * .018f, cap = StrokeCap.Round)
                    )
                }
                Surface(
                    Modifier.fillMaxSize(.57f).border(1.dp, Color.White.copy(alpha = .18f), CircleShape),
                    shape = CircleShape,
                    color = colors.primary
                ) {
                    ArtworkImage(artworkUri, colors.primary)
                }
                Box(Modifier.size(32.dp).clip(CircleShape).background(Color(0xFF0A0C10)).border(1.dp, Color.White.copy(alpha = .2f), CircleShape), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(Color.White.copy(alpha = .52f)))
                }
            }
        }
        Canvas(Modifier.fillMaxSize()) {
            val stroke = size.minDimension * .012f
            val inset = stroke / 2f + 1f
            drawArc(
                color = Color.White.copy(alpha = .13f),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2),
                style = Stroke(stroke, cap = StrokeCap.Round)
            )
            drawArc(
                brush = Brush.sweepGradient(listOf(colors.light, Color.White, colors.primary, colors.light)),
                startAngle = -90f,
                sweepAngle = 360f * progress,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2),
                style = Stroke(stroke, cap = StrokeCap.Round)
            )
        }
        if (!playing) {
            Surface(Modifier.size(76.dp), shape = CircleShape, color = Color.Black.copy(alpha = .34f)) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Pause, "已暂停，点击继续播放", Modifier.size(38.dp), tint = Color.White.copy(alpha = .72f))
                }
            }
        }
    }
}

@Composable private fun FmLyricsPreview(vm: MusicViewModel, modifier: Modifier = Modifier) {
    val lyrics by vm.lyrics.collectAsStateWithLifecycle()
    val playback by vm.playback.collectAsStateWithLifecycle()
    val document = (lyrics as? LyricsState.Loaded)?.document
    val lines = document?.lines.orEmpty()
    val current = lines.indexOfLast { it.timeMs?.let(playback.position::compareTo) ?: -1 >= 0 }.coerceAtLeast(0)
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(current, playback.song?.mediaStoreId) {
        if (lines.isNotEmpty() && !listState.isScrollInProgress) listState.animateScrollToItem(current)
    }
    when {
        lyrics == LyricsState.Loading -> Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White.copy(alpha = .5f)) }
        lines.isEmpty() -> Box(modifier, contentAlignment = Alignment.Center) { Text("纯音乐 / 暂无歌词", color = Color.White.copy(alpha = .32f), fontSize = 14.sp) }
        else -> LazyColumn(
            state = listState,
            modifier = modifier,
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(vertical = 38.dp)
        ) {
            itemsIndexed(lines) { index, line ->
                Text(
                    line.text,
                    color = Color.White.copy(alpha = if (index == current) .88f else .3f),
                    fontSize = if (index == current) 17.sp else 14.sp,
                    fontWeight = if (index == current) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 5.dp)
                )
            }
        }
    }
}

@Composable private fun QualityBadge(value: String) {
    val quality = runCatching { AudioQuality.valueOf(value) }.getOrDefault(AudioQuality.Standard)
    val (label, color, icon) = when (quality) {
        AudioQuality.Standard -> Triple("普通", Color(0xFF8290A5), Icons.Default.GraphicEq)
        AudioQuality.High -> Triple("高清", Color(0xFF78A9FF), Icons.Default.HighQuality)
        AudioQuality.Lossless -> Triple("无损", Color(0xFFB18CFF), Icons.Default.Verified)
        AudioQuality.HiRes -> Triple("Hi-Res", Color(0xFFFFBE5C), Icons.Default.WorkspacePremium)
    }
    Surface(Modifier.height(22.dp), shape = RoundedCornerShape(50), color = color.copy(alpha = .18f)) { Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, label, Modifier.size(11.dp), tint = color); Spacer(Modifier.width(3.dp)); Text(label, color = color, fontSize = 9.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold) } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun SongDetailsSheet(song: SongEntity, getArtwork: () -> Unit, getLyrics: () -> Unit, close: () -> Unit) {
    ModalBottomSheet(onDismissRequest = close, containerColor = Color(0xFF171B23)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("歌曲详情", color = Color.White, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = getArtwork) {
                    Icon(Icons.Default.Image, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(if (song.albumArtUri.isNullOrBlank()) "获取封面" else "更换封面")
                }
                TextButton(onClick = getLyrics) {
                    Icon(Icons.Default.QueueMusic, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("获取歌词")
                }
            }
            Spacer(Modifier.height(12.dp))
            DetailRow("歌手", song.artist); DetailRow("歌曲", song.title); DetailRow("专辑", song.album)
            DetailRow("格式", formatName(song.mimeType)); DetailRow("音质", qualityLabel(song.audioQuality))
            DetailRow("编码", song.codec ?: "未知"); DetailRow("采样率", song.sampleRate?.let { "${it / 1000.0} kHz" } ?: "未知")
            DetailRow("位深", song.bitDepth?.let { "$it bit" } ?: "未知"); DetailRow("码率", song.bitrate?.let { "${it / 1000} kbps" } ?: "无损/未知")
            DetailRow("声道", song.channelCount?.let { if (it == 1) "单声道" else "${it} 声道" } ?: "未知"); DetailRow("时长", time(song.durationMs)); DetailRow("文件", song.fileName ?: "未知")
            Spacer(Modifier.height(18.dp))
        }
    }
}

@Composable private fun DetailRow(label: String, value: String) { Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text(label, color = Color(0xFF8F99A9)); Text(value, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 20.dp)) } }

@Composable private fun LyricsPanel(vm: MusicViewModel, modifier: Modifier = Modifier, close: () -> Unit) {
    val state by vm.lyrics.collectAsStateWithLifecycle()
    val playback by vm.playback.collectAsStateWithLifecycle()
    Box(modifier, contentAlignment = Alignment.Center) {
        when (state) {
            LyricsState.Loading -> CircularProgressIndicator()
            LyricsState.NotFound -> LyricsEmpty("未找到本地歌词", vm::findLyrics, close)
            is LyricsState.Error -> LyricsEmpty((state as LyricsState.Error).message, vm::findLyrics, close)
            is LyricsState.Candidates -> LyricsCandidates((state as LyricsState.Candidates).items, vm)
            is LyricsState.Loaded -> LyricsScroll((state as LyricsState.Loaded).document.lines, playback.position, close)
            LyricsState.Idle -> LyricsEmpty("点击获取歌词", vm::findLyrics, close)
        }
    }
}

@Composable private fun LyricsEmpty(message: String, fetch: () -> Unit, close: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(24.dp).clickable(onClick = close), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.QueueMusic, null, Modifier.size(44.dp), tint = Color(0xFF8491A6))
        Text(message, color = Color(0xFFB8C1CF), modifier = Modifier.padding(vertical = 12.dp))
        Button(onClick = fetch) { Text("获取歌词") }
    }
}

@Composable private fun LyricsCandidates(items: List<LyricsCandidate>, vm: MusicViewModel) {
    LazyColumn(Modifier.fillMaxSize()) {
        item { Text("选择歌词", color = Color.White, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp)) }
        items(items, key = { it.id }) { candidate ->
            ListItem(headlineContent = { Text(candidate.trackName.ifBlank { "未知歌曲" }) }, supportingContent = { Text("${candidate.artistName} · ${if (candidate.isSynced) "同步歌词" else "纯文本"}") }, modifier = Modifier.clickable { vm.saveLyrics(candidate) })
        }
    }
}

@Composable private fun LyricsScroll(lines: List<life.xstar.music.player.data.lyrics.LyricLine>, position: Long, close: () -> Unit) {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val current = lines.indexOfLast { it.timeMs?.let(position::compareTo) ?: -1 >= 0 }
    LaunchedEffect(current) { if (current >= 0) listState.animateScrollToItem(current) }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().clickable(onClick = close), horizontalAlignment = Alignment.CenterHorizontally, contentPadding = PaddingValues(vertical = 110.dp)) {
        items(lines) { line -> Text(line.text, color = if (lines.indexOf(line) == current) Color.White else Color.White.copy(alpha = .38f), fontSize = if (lines.indexOf(line) == current) 21.sp else 16.sp, fontWeight = if (lines.indexOf(line) == current) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.padding(vertical = 9.dp, horizontal = 16.dp)) }
    }
}

@Composable private fun PlaybackModeButton(mode: PlaybackMode, enabled: Boolean = true, onClick: () -> Unit) {
    val label = when (mode) { PlaybackMode.Shuffle -> "随机"; PlaybackMode.RepeatOne -> "单曲"; PlaybackMode.Sequential -> "顺序" }
    val tint = if (!enabled) Color(0xFF4B5464) else if (mode == PlaybackMode.Sequential) Color(0xFFB3BDCC) else MaterialTheme.colorScheme.primary
    IconButton(onClick = onClick, modifier = Modifier.size(52.dp), enabled = enabled) { Icon(when (mode) { PlaybackMode.Shuffle -> Icons.Default.Shuffle; PlaybackMode.RepeatOne -> Icons.Default.RepeatOne; PlaybackMode.Sequential -> Icons.Default.FormatListNumbered }, label, tint = tint) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun SingingHistoryScreen(vm: MusicViewModel, openMenu: () -> Unit, goKaraoke: () -> Unit) {
    val records by vm.singingRecords.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<SingingRecordEntity?>(null) }
    var pendingDelete by remember { mutableStateOf<SingingRecordEntity?>(null) }
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 18.dp)) {
        PageHeader(openMenu)
        Text("我的演唱", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(if (records.isEmpty()) "卡拉OK每次跟唱结束都会自动保存" else "共 ${records.size} 条演唱记录 · 点击可播放与查看评分", color = Color(0xFF818C9C), fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp, bottom = 6.dp))
        if (records.isEmpty()) {
            EmptyState("还没有演唱记录", "进入卡拉OK模式，点开「演唱评分」录完一遍后，录音和评分会自动保存到这里。", "去卡拉OK跟唱", goKaraoke)
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 6.dp)) {
                items(records, key = { it.id }) { record ->
                    val fileExists = remember(record.id, record.filePath) { File(record.filePath).exists() }
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = { Text(record.songTitle, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Column {
                                if (record.songArtist.isNotBlank()) Text(record.songArtist, color = Color(0xFF8F99A9), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${dateTimeText(record.createdAt)} · ${time(record.durationMs)}${if (fileExists) "" else " · 音频已丢失"} · 音准 ${record.pitch ?: "--"} · 活跃度 ${record.activity}",
                                    color = if (fileExists) Color(0xFF8F99A9) else Color(0xFFFF8A96),
                                    fontSize = 12.sp
                                )
                            }
                        },
                        leadingContent = {
                            Surface(Modifier.size(48.dp), RoundedCornerShape(13.dp), color = Color(0xFF252C38)) {
                                if (record.albumArtUri.isNullOrBlank()) Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Mic, null, tint = Color(0xFF8F99A9), modifier = Modifier.size(22.dp)) }
                                else ArtworkImage(record.albumArtUri, Color(0xFF252C38))
                            }
                        },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(gradeLabel(record.total), color = gradeColor(record.total), fontSize = 17.sp, fontWeight = FontWeight.Black)
                                Text(" ${record.total}", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                IconButton(onClick = { pendingDelete = record }) {
                                    Icon(Icons.Default.DeleteOutline, "删除", tint = Color(0xFFFF8A96))
                                }
                            }
                        },
                        modifier = Modifier.clickable(enabled = fileExists) { selected = record }
                    )
                }
            }
        }
    }
    selected?.let { SingingRecordSheet(it, vm) { selected = null } }
    pendingDelete?.let { record ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这条演唱记录？") },
            text = { Text("录音文件和评分记录都会一并删除，且无法恢复。") },
            confirmButton = { TextButton({
                vm.deleteSingingRecord(record)
                pendingDelete = null
            }) { Text("删除", color = Color(0xFFFF8A96)) } },
            dismissButton = { TextButton({ pendingDelete = null }) { Text("取消") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun SingingRecordSheet(record: SingingRecordEntity, vm: MusicViewModel, close: () -> Unit) {
    val playerRef = remember { arrayOfNulls<MediaPlayer>(1) }
    var playing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun releasePlayer() {
        val player = playerRef[0] ?: return
        playerRef[0] = null
        runCatching { player.stop() }
        runCatching { player.release() }
        playing = false
    }

    DisposableEffect(Unit) {
        onDispose { releasePlayer() }
    }

    fun togglePlay() {
        val current = playerRef[0]
        if (current?.isPlaying == true) {
            releasePlayer()
            return
        }
        releasePlayer()
        runCatching {
            val player = MediaPlayer()
            player.setDataSource(record.filePath)
            player.setOnCompletionListener {
                runCatching { it.release() }
                if (playerRef[0] === it) playerRef[0] = null
                playing = false
            }
            player.prepare()
            player.start()
            playerRef[0] = player
            playing = true
        }
    }

    ModalBottomSheet(onDismissRequest = { if (!confirmDelete) close() }, containerColor = Color(0xFF171B23)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(record.songTitle, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (record.songArtist.isNotBlank()) Text(record.songArtist, color = Color(0xFF8F99A9), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(close) { Icon(Icons.Default.Close, "关闭", tint = Color.White) }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(72.dp).clip(CircleShape).background(gradeColor(record.total).copy(alpha = .16f)), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(gradeLabel(record.total), color = gradeColor(record.total), fontSize = 25.sp, fontWeight = FontWeight.Black)
                        Text("${record.total}", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.width(18.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (record.lightweight) "活跃度评分(轻量)" else "综合评分", color = Color(0xFFC5CDDA), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(5.dp))
                    Row {
                        ScoreChip("音准", record.pitch)
                        Spacer(Modifier.width(8.dp))
                        ScoreChip("活跃度", record.activity)
                    }
                    if (record.lightweight) Text("该遍录制时参考音高尚未就绪", color = Color(0xFF818C9C), fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp))
                }
            }
            Spacer(Modifier.height(14.dp))
            Text("演唱于 ${dateTimeText(record.createdAt)} · 录音时长 ${time(record.durationMs)}", color = Color(0xFF818C9C), fontSize = 12.sp)
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.DeleteOutline, null, Modifier.size(17.dp), tint = Color(0xFFFF8A96))
                    Spacer(Modifier.width(6.dp))
                    Text("删除", color = Color(0xFFFF8A96))
                }
                Button(onClick = ::togglePlay, modifier = Modifier.weight(1f)) {
                    Icon(if (playing) Icons.Default.Stop else Icons.Default.PlayArrow, null, Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (playing) "停止播放" else "播放演唱")
                }
            }
            Spacer(Modifier.height(18.dp))
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("删除这条演唱记录？") },
        text = { Text("录音文件和评分记录都会删除，且无法恢复。") },
        confirmButton = { TextButton({
            releasePlayer()
            vm.deleteSingingRecord(record)
            confirmDelete = false
            close()
        }) { Text("删除", color = Color(0xFFFF8A96)) } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("取消") } }
    )
}

private fun gradeColor(score: Int): Color = when {
    score >= 90 -> Color(0xFFFFC53D)
    score >= 80 -> Color(0xFF4FC3F7)
    score >= 70 -> Color(0xFF81C784)
    else -> Color(0xFFB0BEC5)
}

private fun dateTimeText(ms: Long): String {
    val calendar = java.util.Calendar.getInstance().apply { timeInMillis = ms }
    return "%04d-%02d-%02d %02d:%02d".format(calendar.get(java.util.Calendar.YEAR), calendar.get(java.util.Calendar.MONTH) + 1, calendar.get(java.util.Calendar.DAY_OF_MONTH), calendar.get(java.util.Calendar.HOUR_OF_DAY), calendar.get(java.util.Calendar.MINUTE))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun QueueSheet(state: PlayerState, vm: MusicViewModel, close: () -> Unit) { ModalBottomSheet(onDismissRequest = close, containerColor = Color(0xFF171B23)) { Column(Modifier.fillMaxWidth().padding(20.dp)) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Column { Text("播放列表", fontSize = 22.sp, fontWeight = FontWeight.Bold); Text("${state.queue.size} 首歌曲", color = Color.Gray) }; TextButton(onClick = vm.player::clear) { Text("清空") } }; LazyColumn(Modifier.heightIn(max = 520.dp)) { items(state.queue, key = { it.mediaStoreId }) { song -> val index = state.queue.indexOf(song); ListItem(colors = ListItemDefaults.colors(containerColor = Color.Transparent), headlineContent = { Text(song.title, color = if (index == state.index) Color(0xFF78A9FF) else Color.White) }, supportingContent = { Text(song.artist, color = Color.Gray) }, leadingContent = { Icon(if (index == state.index) Icons.Default.GraphicEq else Icons.Default.MusicNote, null) }, trailingContent = { IconButton(onClick = { vm.player.remove(index) }) { Icon(Icons.Default.Close, "移除") } }, modifier = Modifier.clickable { vm.play(song, state.queue) }) } }; Spacer(Modifier.height(20.dp)) } } }

@Composable private fun PageHeader(openMenu: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) { Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) { IconButton(openMenu) { Icon(Icons.Default.Menu, "菜单", tint = Color.White) }; Spacer(Modifier.weight(1f)); actions() } }

@Composable private fun ThemeSwatch(accent: ThemeAccent, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(if (selected) 38.dp else 32.dp).clip(CircleShape)
            .background(if (selected) Color.White else Color.Transparent).padding(3.dp)
            .clip(CircleShape).background(accent.color()).clickable(onClick = onClick)
    )
}

@Composable private fun LibraryScreen(vm: MusicViewModel, openMenu: () -> Unit) {
    val context = LocalContext.current
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let { runCatching { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }; vm.addFolder(it.toString()) } }
    val songs by vm.songs.collectAsStateWithLifecycle()
    val folders by vm.folders.collectAsStateWithLifecycle()
    val downloadFolder by vm.downloadFolder.collectAsStateWithLifecycle()
    val scanState by vm.scanState.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var group by remember { mutableIntStateOf(0) }
    var selectedAlbum by remember { mutableStateOf<Pair<String, List<SongEntity>>?>(null) }
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 18.dp)) {
        PageHeader(openMenu, {
            IconButton({ folderPicker.launch(null) }, enabled = !scanState.running) { Icon(Icons.Default.CreateNewFolder, "添加文件夹", tint = Color.White) }
            IconButton(vm::scan, enabled = !scanState.running && (folders.isNotEmpty() || downloadFolder.isNotBlank())) { Icon(Icons.Default.Refresh, "扫描", tint = Color.White) }
        })
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), leadingIcon = { Icon(Icons.Default.Search, null) }, placeholder = { Text("搜索歌曲、艺术家或专辑") }, singleLine = true, shape = RoundedCornerShape(18.dp))
        if (scanState.running || scanState.message != null) {
            Spacer(Modifier.height(12.dp))
            ScanStatusCard(scanState)
        }
        Spacer(Modifier.height(12.dp))
        if (folders.isEmpty() && songs.isEmpty() && !scanState.running) {
            EmptyState("选择音乐文件夹", "可以连续选择多个目录，XSTAR 只扫描这些目录中的音频。也可以使用在线搜歌下载音乐。", "选择文件夹") { folderPicker.launch(null) }
        } else {
            val filtered = songs.filter { query.isBlank() || "${it.title}${it.artist}${it.album}".contains(query, true) }
            TabRow(group, containerColor = Color.Transparent) {
                listOf("歌曲", "专辑", "艺术家").forEachIndexed { i, text -> Tab(group == i, { group = i }, text = { Text(text) }) }
            }
            if (filtered.isEmpty() && scanState.running) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.LibraryMusic, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = .72f))
                        Spacer(Modifier.height(12.dp))
                        Text("正在建立音乐库", color = Color(0xFFB8C1CF))
                    }
                }
            } else if (group == 0) {
                SongsList(filtered, vm)
            } else {
                GroupsList(filtered.groupBy { if (group == 1) it.album else it.artist }, vm) { name, entries -> if (group == 1) selectedAlbum = name to entries }
            }
        }
    }
    selectedAlbum?.let { (name, entries) -> AlbumArtworkSheet(name, entries, vm) { selectedAlbum = null } }
}

@Composable private fun ScanStatusCard(state: ScanState) {
    val accent = if (state.failed) Color(0xFFFF8A96) else MaterialTheme.colorScheme.primary
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = Color.White.copy(alpha = .07f)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (state.failed) Icons.Default.ErrorOutline else if (state.running) Icons.Default.Radar else Icons.Default.CheckCircle, null, Modifier.size(20.dp), tint = accent)
                Spacer(Modifier.width(9.dp))
                Text(if (state.running) "正在扫描音乐库" else state.message.orEmpty(), color = if (state.failed) accent else Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (state.running) Text("${state.discovered} 首", color = accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            if (state.running) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape), color = accent, trackColor = Color.White.copy(alpha = .1f))
                state.currentFile?.takeIf { it.isNotBlank() }?.let { fileName ->
                    Text("正在读取：$fileName", color = Color(0xFF8F99A9), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
    }
}

@Composable private fun CollectionScreen(songs: List<SongEntity>, vm: MusicViewModel, openMenu: () -> Unit, goToLibrary: () -> Unit) { Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 18.dp)) { PageHeader(openMenu); if (songs.isEmpty()) EmptyState("还没有收藏", "在歌曲右侧点击爱心即可收藏。", "前往音乐库", goToLibrary) else SongsList(songs, vm) } }
@Composable private fun SongsList(songs: List<SongEntity>, vm: MusicViewModel) { LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 10.dp)) { items(songs, key = { it.mediaStoreId }) { song -> ListItem(colors = ListItemDefaults.colors(containerColor = Color.Transparent), headlineContent = { Text(song.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis) }, supportingContent = { Text("${song.artist} · ${formatName(song.mimeType)}", color = Color(0xFF8F99A9)) }, leadingContent = { Surface(Modifier.size(48.dp), RoundedCornerShape(13.dp), color = Color(0xFF252C38)) { ArtworkImage(song.albumArtUri, Color(0xFF252C38)) } }, trailingContent = { IconButton({ vm.favorite(song.mediaStoreId) }) { Icon(if (song.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "收藏", tint = if (song.isFavorite) Color(0xFFFF708C) else Color.Gray) } }, modifier = Modifier.clickable { vm.play(song, songs) }) } } }

@Composable private fun ArtworkImage(uri: String?, tint: Color) {
    if (uri.isNullOrBlank()) DefaultArtwork(tint)
    else {
        val model: Any = when {
            uri.startsWith("http://") || uri.startsWith("https://") -> uri
            uri.startsWith("content://") || uri.startsWith("file://") -> Uri.parse(uri)
            else -> Uri.fromFile(File(uri))
        }
        SubcomposeAsyncImage(model = model, contentDescription = "专辑封面", modifier = Modifier.fillMaxSize().clip(CircleShape), contentScale = ContentScale.Crop, error = { DefaultArtwork(tint) }, loading = { DefaultArtwork(tint) })
    }
}

@Composable private fun DefaultArtwork(tint: Color) { Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(tint.copy(alpha = .85f), Color(0xFF171C25)))), Alignment.Center) { Icon(Icons.Default.MusicNote, null, Modifier.size(34.dp), tint = Color.White.copy(alpha = .82f)) } }
@Composable private fun GroupsList(groups: Map<String, List<SongEntity>>, vm: MusicViewModel, onOpen: (String, List<SongEntity>) -> Unit = { _, _ -> }) { LazyColumn(Modifier.fillMaxSize()) { groups.forEach { (name, songs) -> item(name) { ListItem(modifier = Modifier.clickable { onOpen(name, songs) }, colors = ListItemDefaults.colors(containerColor = Color.Transparent), headlineContent = { Text(name, color = Color.White) }, supportingContent = { Text("${songs.size} 首歌曲 · 点击查看专辑", color = Color.Gray) }, trailingContent = { IconButton({ vm.play(songs.first(), songs) }) { Icon(Icons.Default.PlayArrow, "播放") } }) } } } }

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun AlbumArtworkSheet(name: String, songs: List<SongEntity>, vm: MusicViewModel, allowReplace: Boolean = false, close: () -> Unit) {
    val enabled by vm.onlineArtworkEnabled.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var candidates by remember { mutableStateOf<List<ArtworkCandidate>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    val existing = songs.firstOrNull { !it.albumArtUri.isNullOrBlank() }?.albumArtUri
    ModalBottomSheet(onDismissRequest = close, containerColor = Color(0xFF171B23)) {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(name, color = Color.White, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(18.dp))
            if (existing != null && !allowReplace) SubcomposeAsyncImage(model = existing, contentDescription = "专辑封面", modifier = Modifier.size(220.dp).clip(RoundedCornerShape(20.dp)), contentScale = ContentScale.Crop)
            else if (existing != null && candidates.isEmpty()) {
                SubcomposeAsyncImage(model = existing, contentDescription = "当前专辑封面", modifier = Modifier.size(220.dp).clip(RoundedCornerShape(20.dp)), contentScale = ContentScale.Crop)
                TextButton(onClick = { candidates = listOf(); searchError = "点击下方按钮查找新的封面" }) { Text("查找新的封面") }
                if (searchError != null) Button(enabled = !loading, onClick = {
                    loading = true; searchError = null
                    scope.launch {
                        val result = runCatching { vm.artworkCandidates(songs.first().artist, name).await() }
                        candidates = result.getOrDefault(emptyList())
                        if (candidates.isEmpty()) searchError = "未找到可用封面，请稍后重试"
                        loading = false
                    }
                }) { Text(if (loading) "正在查找..." else "获取候选封面") }
            }
            else if (!enabled) Text("在线封面获取已在设置中关闭", color = Color.Gray, modifier = Modifier.padding(30.dp))
            else if (candidates.isEmpty()) {
                searchError?.let { Text(it, color = Color(0xFFFF8A96), modifier = Modifier.padding(bottom = 10.dp)) }
                Button(enabled = !loading, onClick = {
                    loading = true
                    searchError = null
                    scope.launch {
                        val result = runCatching { vm.artworkCandidates(songs.first().artist, name).await() }
                        candidates = result.getOrDefault(emptyList())
                        if (candidates.isEmpty()) searchError = "未找到可用封面，请检查艺术家和专辑标签或稍后重试"
                        loading = false
                    }
                }) { Text(if (loading) "正在查找..." else "获取封面") }
            }
            else { Text("选择一个封面", color = Color.Gray); LazyColumn(Modifier.heightIn(max = 420.dp)) { items(candidates, key = { it.url }) { candidate -> ListItem(headlineContent = { Text(candidate.label) }, leadingContent = { SubcomposeAsyncImage(model = candidate.url, contentDescription = "候选封面", modifier = Modifier.size(64.dp), contentScale = ContentScale.Crop) }, modifier = Modifier.clickable { vm.saveArtwork(songs.first().artist, name, candidate); close() }) } } }
            Spacer(Modifier.height(20.dp)); Text("${songs.size} 首歌曲", color = Color.Gray)
        }
    }
}

@Composable private fun PlaylistsScreen(vm: MusicViewModel, openMenu: () -> Unit) { val lists by vm.playlists.collectAsStateWithLifecycle(); var name by remember { mutableStateOf("") }; var selected by remember { mutableStateOf<PlaylistEntity?>(null) }; Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 18.dp)) { PageHeader(openMenu); Row(verticalAlignment = Alignment.CenterVertically) { OutlinedTextField(name, { name = it }, Modifier.weight(1f), label = { Text("新建播放列表") }, singleLine = true); IconButton({ vm.createPlaylist(name); name = "" }) { Icon(Icons.Default.Add, "创建", tint = Color.White) } }; LazyColumn { items(lists, key = { it.id }) { item -> ListItem(colors = ListItemDefaults.colors(containerColor = Color.Transparent), headlineContent = { Text(item.name, color = Color.White) }, leadingContent = { Icon(Icons.Default.QueueMusic, null) }, trailingContent = { IconButton({ vm.deletePlaylist(item) }) { Icon(Icons.Default.DeleteOutline, "删除") } }, modifier = Modifier.clickable { selected = item }) } } }; selected?.let { PlaylistSheet(vm, it) { selected = null } } }

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun PlaylistSheet(vm: MusicViewModel, playlist: PlaylistEntity, close: () -> Unit) { val links by vm.playlistSongs(playlist.id).collectAsStateWithLifecycle(emptyList()); val all by vm.songs.collectAsStateWithLifecycle(); val current = links.mapNotNull { link -> all.find { it.mediaStoreId == link.songId } }; var name by remember { mutableStateOf(playlist.name) }; var add by remember { mutableStateOf(false) }; ModalBottomSheet(onDismissRequest = close, containerColor = Color(0xFF171B23)) { Column(Modifier.padding(20.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { OutlinedTextField(name, { name = it }, Modifier.weight(1f), label = { Text("名称") }); IconButton({ vm.renamePlaylist(playlist.id, name) }) { Icon(Icons.Default.Check, "保存") } }; TextButton({ add = true }) { Icon(Icons.Default.Add, null); Text("添加歌曲") }; SongsList(current, vm) } }; if (add) AlertDialog(onDismissRequest = { add = false }, title = { Text("添加歌曲") }, text = { LazyColumn { items(all.filterNot { it in current }, key = { it.mediaStoreId }) { song -> ListItem(headlineContent = { Text(song.title) }, modifier = Modifier.clickable { vm.addToPlaylist(playlist.id, song.mediaStoreId); add = false }) } } }, confirmButton = { TextButton({ add = false }) { Text("关闭") } }) }

@Composable private fun OnlineScreen(vm: MusicViewModel, openMenu: () -> Unit) {
    val context = LocalContext.current
    val enabled by vm.onlineSearchEnabled.collectAsStateWithLifecycle()
    val platform by vm.onlinePlatform.collectAsStateWithLifecycle()
    val downloadFolder by vm.downloadFolder.collectAsStateWithLifecycle()
    val searchState by vm.onlineSearch.collectAsStateWithLifecycle()
    val downloads by vm.onlineDownloads.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var pendingTrack by remember { mutableStateOf<OnlineTrack?>(null) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            pendingTrack?.let { pending ->
                vm.setDownloadFolderAndStart(uri.toString(), pending)
                pendingTrack = null
            }
        } else {
            pendingTrack = null
        }
    }
    fun ensureDownloadFolder(track: OnlineTrack) {
        if (downloadFolder.isBlank()) {
            pendingTrack = track
            folderPicker.launch(null)
        } else {
            vm.startOnlineDownload(track)
        }
    }
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 18.dp)) {
        PageHeader(openMenu)
        Text("在线搜歌", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("搜索并下载到本地音乐库，只有你主动搜索时才联网", color = Color(0xFF8F99A9), fontSize = 12.sp)
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                if (query.isNotBlank()) IconButton(onClick = { vm.onlineSearch(query) }, enabled = enabled) {
                    Icon(Icons.Default.ArrowForward, "搜索")
                }
            },
            placeholder = { Text(if (enabled) "输入歌名、歌手或关键词" else "在线搜歌已在设置中关闭", color = Color(0xFF5B6577)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.onlineSearch(query) }),
            shape = RoundedCornerShape(18.dp)
        )
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            supportedOnlinePlatforms.forEach { item ->
                FilterChip(
                    selected = platform == item.id,
                    enabled = enabled,
                    onClick = {
                        vm.setOnlinePlatform(item.id)
                        if (searchState is OnlineSearchUiState.Results && query.isNotBlank()) vm.onlineSearch(query)
                    },
                    label = { Text(item.label) }
                )
            }
        }
        when (val state = searchState) {
            OnlineSearchUiState.Idle -> if (!enabled) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("在线搜歌下载已在设置中关闭", color = Color(0xFF8F99A9))
                }
            } else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(bottom = 60.dp)) {
                    Icon(Icons.Default.CloudDownload, null, Modifier.size(56.dp), tint = Color(0xFF65738A))
                    Spacer(Modifier.height(14.dp))
                    Text("搜索你喜欢的歌曲", color = Color(0xFFB8C1CF))
                    Text("下载完成后会自动出现在音乐库", color = Color(0xFF65738A), fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }
            OnlineSearchUiState.Searching -> if (!enabled) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("在线搜歌下载已在设置中关闭", color = Color(0xFF8F99A9))
                }
            } else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            is OnlineSearchUiState.Error -> if (!enabled) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("在线搜歌下载已在设置中关闭", color = Color(0xFF8F99A9))
                }
            } else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(28.dp)) {
                    Text(state.message, color = Color(0xFFFF8A96), textAlign = TextAlign.Center)
                    Spacer(Modifier.height(14.dp))
                    Button(onClick = { vm.onlineSearch(query) }, enabled = query.isNotBlank()) { Text("重试") }
                }
            }
            is OnlineSearchUiState.Results -> if (!enabled) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("在线搜歌下载已在设置中关闭", color = Color(0xFF8F99A9))
                }
            } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                items(state.tracks, key = { it.onlineId }) { track ->
                    val downloadState = downloads[track.onlineId]
                    OnlineTrackRow(
                        track = track,
                        downloadState = downloadState,
                        onDownload = { ensureDownloadFolder(track) },
                        onCancel = { vm.cancelOnlineDownload(track.onlineId) },
                        onPlay = { vm.playOnlineDownloaded(track.onlineId) }
                    )
                }
            }
        }
    }
}

@Composable private fun OnlineTrackRow(
    track: OnlineTrack,
    downloadState: OnlineDownloadState?,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onPlay: () -> Unit
) {
    val downloading = downloadState is OnlineDownloadState.DownloadingPercent
    val failed = downloadState is OnlineDownloadState.Failed
    val done = downloadState == OnlineDownloadState.Done
    val percent = (downloadState as? OnlineDownloadState.DownloadingPercent)?.percent
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(track.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (done) Spacer(Modifier.width(6.dp))
            }
        },
        supportingContent = {
            if (failed) Text((downloadState as OnlineDownloadState.Failed).message, color = Color(0xFFFF8A96), maxLines = 2)
            else Text("${platformLabel(track.platform)} · ${track.artist}", color = Color(0xFF8F99A9), maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        leadingContent = {
            Surface(Modifier.size(48.dp), RoundedCornerShape(13.dp), color = Color(0xFF252C38)) {
                DefaultArtwork(Color(0xFF2E3649))
            }
        },
        trailingContent = {
            when {
                downloading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(percent?.let { "$it%" } ?: "…", color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    IconButton(onClick = onCancel, Modifier.size(34.dp)) { Icon(Icons.Default.Close, "取消下载", tint = Color(0xFFB3BDCC), modifier = Modifier.size(18.dp)) }
                }
                done -> IconButton(onClick = onPlay) { Icon(Icons.Default.CheckCircle, "已下载，点击播放", tint = Color(0xFF55D6A9)) }
                failed -> IconButton(onClick = onDownload) { Icon(Icons.Default.Refresh, "下载失败，点击重试", tint = Color(0xFFFF8A96)) }
                else -> IconButton(onClick = onDownload) { Icon(Icons.Default.Download, "下载", tint = Color(0xFFB3BDCC)) }
            }
        },
        modifier = Modifier.clickable {
            when {
                done -> onPlay()
                downloading -> onCancel()
                else -> onDownload()
            }
        }
    )
}

@Composable private fun EqualizerScreen(vm: MusicViewModel, openMenu: () -> Unit) {
    val state by vm.equalizerState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refreshEqualizer() }
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 18.dp)) {
        PageHeader(openMenu)
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("均衡器", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text(
                    when {
                        !state.available -> "当前设备不支持"
                        !state.ready -> "播放歌曲后自动初始化"
                        else -> "${state.bands.size} 段 · ${formatDb(state.bands.firstOrNull()?.minLevelMb ?: 0)} 至 ${formatDb(state.bands.firstOrNull()?.maxLevelMb ?: 0)}"
                    },
                    color = Color(0xFF8F99A9),
                    fontSize = 13.sp
                )
            }
            Switch(checked = state.enabled, enabled = state.ready, onCheckedChange = vm::setEqualizerEnabled)
        }
        state.error?.let {
            Surface(Modifier.fillMaxWidth().padding(vertical = 10.dp), shape = RoundedCornerShape(14.dp), color = Color(0xFFFF8A96).copy(alpha = .12f)) {
                Text(it, color = Color(0xFFFFA5AE), modifier = Modifier.padding(14.dp))
            }
        }
        if (!state.ready) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Equalizer, null, Modifier.size(64.dp), tint = Color(0xFF65738A))
                    Spacer(Modifier.height(18.dp))
                    Text(if (state.available) "请先播放一首歌曲" else "无法使用系统均衡器", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Text(if (state.available) "音频 Session 建立后即可调整音效" else "此设备或当前音频输出不提供均衡器", color = Color.Gray, modifier = Modifier.padding(top = 8.dp))
                    TextButton(onClick = vm::refreshEqualizer, modifier = Modifier.padding(top = 8.dp)) { Text("重新检测") }
                }
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 18.dp)) {
                Text("预设", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 8.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = state.selectedPreset == null, onClick = vm::resetEqualizer, enabled = state.enabled, label = { Text("自定义") })
                    state.presets.forEach { preset ->
                        FilterChip(selected = state.selectedPreset == preset.index, onClick = { vm.useEqualizerPreset(preset.index) }, enabled = state.enabled, label = { Text(preset.name) })
                    }
                }
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = Color.White.copy(alpha = .06f)) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        state.bands.forEach { band ->
                            EqualizerBandControl(band, state.enabled) { vm.setEqualizerBand(band.index, it) }
                            if (band != state.bands.last()) HorizontalDivider(color = Color.White.copy(alpha = .07f))
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("提升多个频段可能造成失真", color = Color(0xFF818C9C), fontSize = 12.sp)
                    TextButton(onClick = vm::resetEqualizer, enabled = state.enabled) { Icon(Icons.Default.RestartAlt, null, Modifier.size(18.dp)); Spacer(Modifier.width(5.dp)); Text("重置") }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable private fun EqualizerBandControl(band: EqualizerBand, enabled: Boolean, onLevelChange: (Int) -> Unit) {
    var level by remember(band.index, band.levelMb) { mutableFloatStateOf(band.levelMb.toFloat()) }
    Column(Modifier.fillMaxWidth().padding(vertical = 11.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatFrequency(band.centerFrequencyHz), color = Color.White, fontWeight = FontWeight.SemiBold)
            Text(formatDb(level.roundToInt()), color = if (level > 600) Color(0xFFFFBE5C) else MaterialTheme.colorScheme.primary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = level,
            onValueChange = { level = it; onLevelChange(it.roundToInt()) },
            valueRange = band.minLevelMb.toFloat()..band.maxLevelMb.toFloat(),
            enabled = enabled,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable private fun SettingsScreen(vm: MusicViewModel, openMenu: () -> Unit) {
    val context = LocalContext.current
    val playerBackground by vm.playerBackground.collectAsStateWithLifecycle()
    val playerDiscVisible by vm.playerDiscVisible.collectAsStateWithLifecycle()
    val playerDiscOpacity by vm.playerDiscOpacity.collectAsStateWithLifecycle()
    val folders by vm.folders.collectAsStateWithLifecycle()
    val beatEffect by vm.beatEffect.collectAsStateWithLifecycle()
    val bottomEffect by vm.bottomEffect.collectAsStateWithLifecycle()
    val onlineArtwork by vm.onlineArtworkEnabled.collectAsStateWithLifecycle()
    val onlineLyrics by vm.onlineLyricsEnabled.collectAsStateWithLifecycle()
    val onlineSearch by vm.onlineSearchEnabled.collectAsStateWithLifecycle()
    val onlinePlatform by vm.onlinePlatform.collectAsStateWithLifecycle()
    val onlineApiBase by vm.onlineApiBase.collectAsStateWithLifecycle()
    val downloadFolder by vm.downloadFolder.collectAsStateWithLifecycle()
    val scanState by vm.scanState.collectAsStateWithLifecycle()
    val togetherMode by vm.togetherConnectMode.collectAsStateWithLifecycle()
    val serverEnabled by vm.togetherServerEnabled.collectAsStateWithLifecycle()
    val serverAddress by vm.togetherServerAddress.collectAsStateWithLifecycle()
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { runCatching { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }; vm.addFolder(it.toString()) }
    }
    val downloadPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            vm.setDownloadFolder(uri.toString())
        }
    }
    val backgroundPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            vm.setPlayerBackground(uri.toString())
        }
    }
    val canScan = folders.isNotEmpty() || downloadFolder.isNotBlank()
    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(horizontal = 18.dp)) {
        PageHeader(openMenu)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        SettingsSection("播放与显示", "调整播放器的动态视觉效果") {
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                headlineContent = { Text("播放页面背景图片") },
                supportingContent = { Text(if (playerBackground.isBlank()) "未设置，使用专辑封面氛围背景" else "已设置自定义背景图片", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                leadingContent = { Icon(Icons.Default.Wallpaper, null, tint = MaterialTheme.colorScheme.primary) },
                trailingContent = { if (playerBackground.isNotBlank()) IconButton({ vm.setPlayerBackground(null) }) { Icon(Icons.Default.Close, "清除背景图片") } else Icon(Icons.Default.ChevronRight, null) },
                modifier = Modifier.clickable { backgroundPicker.launch(arrayOf("image/*")) }
            )
            SettingsDivider()
            SettingsToggle("普通模式显示光碟", "关闭后隐藏播放页面中央的专辑光碟", Icons.Default.Album, playerDiscVisible, vm::setPlayerDiscVisible)
            SettingsDivider()
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("光碟透明度")
                        Text("背景图片设置后调整专辑光碟的透明程度", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                    Text("${(playerDiscOpacity * 100).roundToInt()}%", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
                }
                Slider(
                    value = playerDiscOpacity,
                    onValueChange = vm::setPlayerDiscOpacity,
                    valueRange = .05f..1f,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            SettingsDivider()
            SettingsToggle("音乐节拍动态效果", "播放时切换背景颜色并轻微缩放封面", Icons.Default.GraphicEq, beatEffect, vm::setBeatEffect)
            SettingsDivider()
            SettingsToggle("底部频谱特效", "播放时显示实时频谱律动与氛围粒子", Icons.Default.Equalizer, bottomEffect, vm::setBottomEffect)
        }
        SettingsSection("音乐库", "管理本地音乐文件夹与扫描") {
            if (folders.isEmpty()) {
                Text("还没有添加音乐文件夹", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, modifier = Modifier.padding(16.dp))
            }
            folders.forEach { uri ->
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text(folderName(uri), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(uri, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingContent = { Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary) },
                    trailingContent = { IconButton({ vm.removeFolder(uri) }, enabled = !scanState.running) { Icon(Icons.Default.Close, "移除文件夹") } }
                )
            }
            OutlinedButton({ picker.launch(null) }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), enabled = !scanState.running) {
                Icon(Icons.Default.CreateNewFolder, null)
                Spacer(Modifier.width(8.dp))
                Text("添加音乐文件夹")
            }
            SettingsDivider()
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                headlineContent = { Text(if (scanState.running) "正在扫描音乐库" else "重新扫描音乐库") },
                supportingContent = {
                    Text(
                        when {
                            scanState.running && !scanState.currentFile.isNullOrBlank() -> "已发现 ${scanState.discovered} 首 · ${scanState.currentFile}"
                            scanState.message != null -> scanState.message.orEmpty()
                            !canScan -> "请先添加音乐文件夹或设置下载目录"
                            else -> "同步音乐文件夹与下载目录中的音频"
                        },
                        color = if (scanState.failed) Color(0xFFFF8A96) else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                },
                leadingContent = { Icon(if (scanState.running) Icons.Default.Radar else Icons.Default.Refresh, null, tint = MaterialTheme.colorScheme.primary) },
                trailingContent = { if (scanState.running) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) },
                modifier = Modifier.clickable(enabled = !scanState.running && canScan) { vm.scan() }
            )
        }
        SettingsSection("在线服务", "在线搜歌、下载、封面与歌词") {
        SettingsToggle("在线搜歌与下载", "仅在主动搜索时将关键词发送到公共音乐聚合服务", Icons.Default.CloudDownload, onlineSearch, vm::setOnlineSearchEnabled)
        Text("搜索平台", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(start = 16.dp, top = 4.dp))
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            supportedOnlinePlatforms.forEach { platform ->
                FilterChip(
                    selected = onlinePlatform == platform.id,
                    enabled = onlineSearch,
                    onClick = { vm.setOnlinePlatform(platform.id) },
                    label = { Text(platform.label) }
                )
            }
        }
        SettingsDivider()
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            headlineContent = { Text("下载目录", color = Color.White) },
            supportingContent = {
                Text(
                    if (downloadFolder.isBlank()) "未设置，下载前请选择一个文件夹" else folderName(downloadFolder),
                    color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            },
            leadingContent = { Icon(Icons.Default.Save, null, tint = MaterialTheme.colorScheme.primary) },
            trailingContent = {
                if (downloadFolder.isNotBlank()) IconButton({ vm.setDownloadFolder(null) }) { Icon(Icons.Default.Close, "清除下载目录") }
                else Icon(Icons.Default.ChevronRight, null)
            },
            modifier = Modifier.clickable { downloadPicker.launch(null) }
        )
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            headlineContent = { Text("自定义搜索服务") },
            supportingContent = { Text(if (onlineApiBase.isBlank()) "使用公共实例 · 可选高级设置" else "已配置自定义服务") },
            leadingContent = { Icon(Icons.Default.Tune, null, tint = MaterialTheme.colorScheme.primary) },
            trailingContent = { Icon(if (showAdvanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore, if (showAdvanced) "收起" else "展开") },
            modifier = Modifier.clickable { showAdvanced = !showAdvanced }
        )
        AnimatedVisibility(showAdvanced) {
        OutlinedTextField(
            value = onlineApiBase,
            onValueChange = vm::setOnlineApiBase,
            enabled = onlineSearch,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            label = { Text("自定义服务地址（可选）", color = Color(0xFF8F99A9)) },
            placeholder = { Text("留空使用公共实例", color = Color(0xFF5B6577)) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp)
        )
        }
        SettingsDivider()
        SettingsToggle("在线专辑封面", "开启后可手动查询 iTunes 专辑封面", Icons.Default.Album, onlineArtwork, vm::setOnlineArtworkEnabled)
        SettingsDivider()
        SettingsToggle("在线歌词", "优先使用本地 LRC 歌词", Icons.Default.Notes, onlineLyrics, vm::setOnlineLyricsEnabled)
        }
        SettingsSection("一起听", "管理设备连接与跨网中转") {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            headlineContent = { Text("连接方式", color = Color.White) },
            supportingContent = {
                Text(
                    when (togetherMode) {
                        TogetherConnectMode.Lan -> "仅局域网直连：不发起公网探测"
                        TogetherConnectMode.MultiPath -> "跨网多路径直连探测：优先局域网，失败自动尝试公网直连"
                    },
                    color = Color.Gray
                )
            },
            leadingContent = { Icon(Icons.Default.Public, null, tint = MaterialTheme.colorScheme.primary) }
        )
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                TogetherConnectMode.Lan to "仅局域网",
                TogetherConnectMode.MultiPath to "跨网多路径（默认）"
            ).forEach { (mode, label) ->
                FilterChip(
                    selected = togetherMode == mode,
                    onClick = { vm.setTogetherConnectMode(mode) },
                    label = { Text(label, fontSize = 12.sp) }
                )
            }
        }
        SettingsDivider()
        SettingsToggle("公网中转服务器", "可选：跨网多路径时通过自建服务器中转音频与控制", Icons.Default.Dns, serverEnabled, vm::setTogetherServerEnabled)
        if (serverEnabled) {
            OutlinedTextField(
                value = serverAddress,
                onValueChange = vm::setTogetherServerAddress,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                label = { Text("服务器地址（IP:端口）", color = Color(0xFF8F99A9)) },
                placeholder = { Text("例如 1.2.3.4:9301", color = Color(0xFF5B6577)) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp)
            )
            Text("服务器仅转发数据、不保存音乐；不可达时仅提示，不自动回退到 UPnP 通道", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp))
        }
        }
        SettingsSection("关于", "应用信息") {
        ListItem(colors = ListItemDefaults.colors(containerColor = Color.Transparent), headlineContent = { Text("XSTAR", color = Color.White) }, supportingContent = { Text("本地音乐播放器 · 0.1.0", color = Color.Gray) }, leadingContent = { Icon(Icons.Default.Info, null) })
        }
        Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun PlayerBackground(uri: String) {
    if (uri.isBlank()) return
    SubcomposeAsyncImage(
        model = Uri.parse(uri), contentDescription = null,
        modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
        alpha = .34f, error = {}, loading = {}
    )
    Box(Modifier.fillMaxSize().background(Color(0xFF070A10).copy(alpha = .48f)))
}

@Composable
private fun SettingsSection(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = MaterialTheme.colorScheme.primary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), content = content)
        }
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(color = Color.White.copy(alpha = .07f), modifier = Modifier.padding(horizontal = 16.dp))
}

@Composable
private fun SettingsToggle(title: String, description: String, icon: androidx.compose.ui.graphics.vector.ImageVector, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(title) },
        supportingContent = { Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
    )
}
@Composable private fun EmptyState(title: String, text: String, action: String, click: () -> Unit) { Column(Modifier.fillMaxWidth().padding(42.dp), horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Default.LibraryMusic, null, Modifier.size(58.dp), tint = Color(0xFF65738A)); Spacer(Modifier.height(18.dp)); Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold); Text(text, color = Color.Gray, modifier = Modifier.padding(vertical = 12.dp)); Button(click) { Text(action) } } }
private fun PlaybackMode.next() = when (this) { PlaybackMode.Sequential -> PlaybackMode.Shuffle; PlaybackMode.Shuffle -> PlaybackMode.RepeatOne; PlaybackMode.RepeatOne -> PlaybackMode.Sequential }
private fun PlaybackMode.label() = when (this) { PlaybackMode.Sequential -> "顺序"; PlaybackMode.Shuffle -> "随机"; PlaybackMode.RepeatOne -> "单曲循环" }
private fun time(ms: Long) = "%d:%02d".format(ms.coerceAtLeast(0) / 60_000, (ms.coerceAtLeast(0) / 1_000) % 60)
private fun formatDb(levelMb: Int): String = if (levelMb == 0) "0 dB" else "%+.1f dB".format(levelMb / 100f)
private fun formatFrequency(frequencyHz: Int): String = if (frequencyHz >= 1_000) {
    val kiloHertz = frequencyHz / 1_000f
    if (kiloHertz % 1f == 0f) "${kiloHertz.toInt()} kHz" else "%.1f kHz".format(kiloHertz)
} else "$frequencyHz Hz"
private fun qualityLabel(value: String): String = runCatching { AudioQuality.valueOf(value) }.getOrDefault(AudioQuality.Standard).let { quality -> when (quality) { AudioQuality.Standard -> "普通"; AudioQuality.High -> "高清"; AudioQuality.Lossless -> "无损"; AudioQuality.HiRes -> "Hi-Res" } }
private fun formatName(mime: String?) = mime?.substringAfter('/')?.uppercase()?.replace("MPEG", "MP3") ?: "AUDIO"
private fun folderName(uri: String) = Uri.parse(uri).lastPathSegment?.substringAfterLast(':')?.ifBlank { "音乐文件夹" } ?: "音乐文件夹"
private fun sleepTimerText(milliseconds: Long): String {
    val totalSeconds = (milliseconds.coerceAtLeast(0) + 999) / 1_000
    return if (totalSeconds >= 3_600) "%d:%02d:%02d".format(totalSeconds / 3_600, totalSeconds / 60 % 60, totalSeconds % 60)
    else "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

private data class ArtworkColors(val primary: Color, val light: Color, val dark: Color) {
    val pulseColors: List<Color> get() = listOf(primary, light, lerp(primary, light, .42f), lerp(primary, dark, .2f))

    companion object {
        fun fallback(theme: ThemeAccent): ArtworkColors {
            val primary = theme.color()
            return ArtworkColors(primary, lerp(primary, Color.White, .24f), lerp(primary, Color(0xFF090B10), .74f))
        }
    }
}

private suspend fun loadArtworkColors(context: android.content.Context, artworkUri: String?, theme: ThemeAccent): ArtworkColors = withContext(Dispatchers.IO) {
    if (artworkUri.isNullOrBlank()) return@withContext ArtworkColors.fallback(theme)
    fun openArtwork(): java.io.InputStream? = when {
        artworkUri.startsWith("http://") || artworkUri.startsWith("https://") ->
            runCatching {
                val connection = (java.net.URL(artworkUri).openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 6_000
                    readTimeout = 8_000
                    requestMethod = "GET"
                }
                if (connection.responseCode in 200..299) connection.inputStream else null
            }.getOrNull()
        artworkUri.startsWith("content://") || artworkUri.startsWith("file://") ->
            context.contentResolver.openInputStream(android.net.Uri.parse(artworkUri))
        else -> runCatching { java.io.File(artworkUri).inputStream() }.getOrNull()
    }
    runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openArtwork().use { BitmapFactory.decodeStream(it, null, bounds) }
        var sampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > 256) sampleSize *= 2
        val bitmap = openArtwork().use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sampleSize }) }
            ?: return@runCatching ArtworkColors.fallback(theme)
        val palette = Palette.from(bitmap).maximumColorCount(24).generate()
        bitmap.recycle()
        val fallback = theme.color()
        val primary = Color(palette.vibrantSwatch?.rgb ?: palette.dominantSwatch?.rgb ?: palette.mutedSwatch?.rgb ?: fallback.toArgb())
        val lightBase = Color(palette.lightVibrantSwatch?.rgb ?: palette.lightMutedSwatch?.rgb ?: primary.toArgb())
        val darkBase = Color(palette.darkMutedSwatch?.rgb ?: palette.darkVibrantSwatch?.rgb ?: primary.toArgb())
        ArtworkColors(primary, lerp(lightBase, Color.White, .12f), lerp(darkBase, Color(0xFF07090D), .68f))
    }.getOrElse { ArtworkColors.fallback(theme) }
}
private fun ThemeAccent.color() = when (this) { ThemeAccent.Blue -> Color(0xFF78A9FF); ThemeAccent.Violet -> Color(0xFFAE8CFF); ThemeAccent.Rose -> Color(0xFFFF7998); ThemeAccent.Emerald -> Color(0xFF55D6A9); ThemeAccent.Amber -> Color(0xFFFFBE5C) }
@Composable private fun XstarTheme(accent: ThemeAccent, content: @Composable () -> Unit) { MaterialTheme(colorScheme = darkColorScheme(primary = accent.color(), background = Color(0xFF090B10), surface = Color(0xFF171B23)), content = content) }

private val BOTTOM_STAGE_HEIGHT = 132.dp

private const val SPECTRUM_ATTACK_RATE = 12f
private const val SPECTRUM_RELEASE_RATE = 10f
private const val SPECTRUM_PAUSE_RELEASE_RATE = 8f
private const val SPECTRUM_PARTICLE_COUNT = 20

@Composable
private fun BottomSpectrumStage(
    levelsFlow: StateFlow<FloatArray>,
    playing: Boolean,
    colors: ArtworkColors,
    modifier: Modifier = Modifier
) {
    val levels by levelsFlow.collectAsStateWithLifecycle()
    val now = remember { mutableFloatStateOf(0f) }
    val prevDrawT = remember { floatArrayOf(-1f) }
    val smooth = remember { FloatArray(SPECTRUM_BAND_COUNT) }
    var animActive by remember { mutableStateOf(false) }
    val frameOrigin = remember { longArrayOf(0L) }

    LaunchedEffect(playing) {
        animActive = true
        prevDrawT[0] = -1f
        if (playing) {
            smooth.fill(0f)
            while (true) withFrameNanos {
                if (frameOrigin[0] == 0L) frameOrigin[0] = it
                now.floatValue = (it - frameOrigin[0]) / 1_000_000_000f
            }
        } else {
            val end = SystemClock.uptimeMillis() + 700L
            while (SystemClock.uptimeMillis() < end) withFrameNanos {
                if (frameOrigin[0] == 0L) frameOrigin[0] = it
                now.floatValue = (it - frameOrigin[0]) / 1_000_000_000f
            }
            animActive = false
        }
    }

    Canvas(modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        val t = now.floatValue
        val primary = colors.primary
        val light = colors.light

        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.Transparent,
                0.3f to primary.copy(alpha = 0.14f),
                1f to primary.copy(alpha = 0.5f)
            )
        )
        drawRoundRect(
            brush = Brush.horizontalGradient(
                listOf(Color.Transparent, light.copy(alpha = 0.5f), primary.copy(alpha = 0.22f), light.copy(alpha = 0.5f), Color.Transparent)
            ),
            topLeft = Offset(0f, h - 2.dp.toPx()),
            size = Size(w, 2.dp.toPx()),
            cornerRadius = CornerRadius(1.dp.toPx(), 1.dp.toPx())
        )
        if (!animActive) return@Canvas

        if (playing) {
            val particleCount = SPECTRUM_PARTICLE_COUNT
            for (p in 0 until particleCount) {
                val seed = p * 0.61803398875f
                val rise = t * (0.05f + (p % 6) * 0.014f) + seed
                val yNorm = (rise % 1f)
                val sway = kotlin.math.sin(t * 1.3f + seed * 17f) * 20.dp.toPx() * yNorm
                val x = (((seed * 13f) % 1f) * w + sway).coerceIn(0f, w)
                val twinkle = 0.5f + 0.5f * kotlin.math.sin(t * (1.6f + p % 3) + seed * 40f)
                val alpha = twinkle * 0.6f * (1f - yNorm * 0.5f)
                if (alpha <= 0.01f) continue
                val r = (1.1f + (p % 4) * 0.9f).dp.toPx() * (1f - yNorm * 0.5f)
                drawCircle(
                    color = lerp(light, primary, seed % 1f).copy(alpha = alpha),
                    radius = r,
                    center = Offset(x, h - 4.dp.toPx() - yNorm * (h - 8.dp.toPx()))
                )
            }
        }

        val dt = if (prevDrawT[0] >= 0f) (t - prevDrawT[0]).coerceIn(0f, 0.1f) else 0.016f
        prevDrawT[0] = t
        val attack = 1f - kotlin.math.exp(-dt * SPECTRUM_ATTACK_RATE)
        val release = 1f - kotlin.math.exp(-dt * if (playing) SPECTRUM_RELEASE_RATE else SPECTRUM_PAUSE_RELEASE_RATE)

        val slot = w / SPECTRUM_BAND_COUNT
        val barW = (slot * 0.52f).coerceAtLeast(1.dp.toPx())
        val maxH = h * 0.82f
        val minBar = 1.5.dp.toPx()
        for (i in 0 until SPECTRUM_BAND_COUNT) {
            val target = if (playing) levels.getOrElse(i) { 0f } else 0f
            val current = smooth[i]
            val k = if (target > current) attack else release
            smooth[i] = current + (target - current) * k
            val level = smooth[i].coerceAtLeast(0f)
            val barH = if (level <= 0.015f) 0f else (level * maxH).coerceAtLeast(minBar)
            if (barH <= 0f) continue
            val x = i * slot + (slot - barW) / 2f
            val vivid = lerp(light, primary, i.toFloat() / SPECTRUM_BAND_COUNT)
            val brighter = lerp(vivid, Color.White, 0.24f)
            val color = brighter.copy(alpha = (0.7f + 0.3f * level).coerceIn(0f, 1f))
            drawRoundRect(
                color = color,
                topLeft = Offset(x, h - barH),
                size = Size(barW, barH),
                cornerRadius = CornerRadius(barW / 2f, barW / 2f)
            )
            if (barH > minBar * 2f) {
                drawCircle(
                    color = Color.White.copy(alpha = 0.12f + 0.35f * level),
                    radius = barW * 0.30f,
                    center = Offset(x + barW / 2f, h - barH)
                )
            }
        }
    }
}
