package life.xstar.music.player

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.lifecycle.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.palette.graphics.Palette
import coil.compose.SubcomposeAsyncImage
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.*
import kotlin.random.Random
import kotlin.math.abs
import kotlin.math.roundToInt
import life.xstar.music.player.data.local.*
import life.xstar.music.player.data.audio.AudioQuality
import life.xstar.music.player.data.preferences.PlaybackPreferences
import life.xstar.music.player.data.preferences.ThemeAccent
import life.xstar.music.player.data.repository.MusicRepository
import life.xstar.music.player.data.repository.ArtworkCandidate
import life.xstar.music.player.data.repository.ScanProgress
import life.xstar.music.player.data.lyrics.LyricsRepository
import life.xstar.music.player.data.lyrics.LyricsState
import life.xstar.music.player.data.lyrics.LyricsCandidate
import life.xstar.music.player.player.*

class MainActivity : ComponentActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        enableEdgeToEdge()
        val app = application as XstarApplication
        val vm = ViewModelProvider(this, MusicViewModel.factory(app.repository, PlayerController(this), app.playbackPreferences, LyricsRepository(this)))[MusicViewModel::class.java]
        setContent { val theme by vm.theme.collectAsStateWithLifecycle(); XstarTheme(theme) { XstarApp(vm) } }
    }
}

@OptIn(kotlinx.coroutines.FlowPreview::class)
class MusicViewModel(private val repo: MusicRepository, val player: PlayerController, private val preferences: PlaybackPreferences, private val lyricsRepository: LyricsRepository) : ViewModel() {
    val songs = repo.songs().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val favorites = repo.favorites().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val playlists = repo.playlists().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val playback = player.state
    val equalizerState = player.equalizerState
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
    val beatEffect = preferences.beatEffect.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val onlineArtworkEnabled = preferences.onlineArtworkEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val onlineLyricsEnabled = preferences.onlineLyricsEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val fmModeEnabled = preferences.fmModeEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val openPlayer = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val lyrics = MutableStateFlow<LyricsState>(LyricsState.Idle)

    init {
        viewModelScope.launch {
            playback.debounce(1_000).collect { state ->
                if (suppressPlaybackPersistence || state.queue.isEmpty()) return@collect
                if (preferences.isFmModeEnabled()) preferences.saveFm(state.queue.map { it.mediaStoreId }, state.index, state.position, state.playing)
                else preferences.save(state.queue.map { it.mediaStoreId }, state.index, state.position, state.mode.name, state.playing)
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
        viewModelScope.launch {
            combine(playback.map { it.song?.mediaStoreId }, fmModeEnabled, onlineLyricsEnabled) { id, fm, online -> Triple(id, fm, online) }
                .distinctUntilChanged().collect { (id, fm, online) ->
                val song = id?.let { repo.getSong(it) }
                lyrics.value = if (song == null) LyricsState.Idle else LyricsState.Loading
                if (song != null) {
                    val local = runCatching { lyricsRepository.loadLocalOrEmbedded(song) }.getOrNull()
                    if (local != null) {
                        lyrics.value = LyricsState.Loaded(local)
                    } else if (fm && online) {
                        val candidate = runCatching { lyricsRepository.findOnline(song).firstOrNull() }.getOrNull()
                        val document = candidate?.let { lyricsRepository.save(it, song) }
                        if (playback.value.song?.mediaStoreId == song.mediaStoreId) {
                            lyrics.value = document?.let { LyricsState.Loaded(it) } ?: LyricsState.NotFound
                        }
                    } else {
                        lyrics.value = LyricsState.NotFound
                    }
                }
            }
        }
        viewModelScope.launch {
            combine(playback.map { it.song?.mediaStoreId }, fmModeEnabled, onlineArtworkEnabled) { id, fm, online -> Triple(id, fm, online) }
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
        scanJob = viewModelScope.launch {
            _scanState.value = ScanState(running = true)
            val result = runCatching {
                repo.scan(folderUris) { progress: ScanProgress ->
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
    fun setBeatEffect(enabled: Boolean) = viewModelScope.launch { preferences.setBeatEffect(enabled) }
    fun setOnlineArtworkEnabled(enabled: Boolean) = viewModelScope.launch { preferences.setOnlineArtworkEnabled(enabled) }
    fun setOnlineLyricsEnabled(enabled: Boolean) = viewModelScope.launch { preferences.setOnlineLyricsEnabled(enabled) }
    fun artworkCandidates(artist: String, album: String) = viewModelScope.async { repo.findArtworkCandidates(artist, album) }
    fun saveArtwork(artist: String, album: String, candidate: ArtworkCandidate) = viewModelScope.launch { repo.saveArtworkForAlbum(artist, album, candidate) }
    fun findLyrics() = viewModelScope.launch {
        val song = playback.value.song ?: return@launch
        if (!onlineLyricsEnabled.value) {
            lyrics.value = LyricsState.Error("在线歌词获取已在设置中关闭")
            return@launch
        }
        lyrics.value = LyricsState.Loading
        val candidates = runCatching { lyricsRepository.findOnline(song) }.getOrDefault(emptyList())
        lyrics.value = if (candidates.isEmpty()) LyricsState.Error("未找到歌词，请检查歌曲标签或稍后重试") else LyricsState.Candidates(song, candidates)
    }
    fun saveLyrics(candidate: life.xstar.music.player.data.lyrics.LyricsCandidate) = viewModelScope.launch {
        val song = playback.value.song ?: return@launch
        lyrics.value = lyricsRepository.save(candidate, song)?.let { LyricsState.Loaded(it) } ?: LyricsState.Error("歌词保存失败")
    }
    fun play(song: SongEntity, source: List<SongEntity> = songs.value) {
        viewModelScope.launch {
            val queue = source.ifEmpty { listOf(song) }
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
    fun setFmMode(enabled: Boolean) = viewModelScope.launch {
        if (enabled == preferences.isFmModeEnabled()) return@launch
        if (enabled) {
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
    }
    fun fmRandomSong() = player.randomSong()
    fun favorite(id: Long) = viewModelScope.launch { repo.toggleFavorite(id) }
    fun createPlaylist(name: String) = viewModelScope.launch { if (name.isNotBlank()) repo.createPlaylist(name.trim()) }
    fun deletePlaylist(item: PlaylistEntity) = viewModelScope.launch { repo.deletePlaylist(item) }
    fun renamePlaylist(id: Long, name: String) = viewModelScope.launch { if (name.isNotBlank()) repo.renamePlaylist(id, name) }
    fun playlistSongs(id: Long) = repo.playlistSongs(id)
    fun addToPlaylist(playlistId: Long, songId: Long) = viewModelScope.launch { repo.addToPlaylist(playlistId, songId) }
    fun removeFromPlaylist(playlistId: Long, songId: Long) = viewModelScope.launch { repo.removeFromPlaylist(playlistId, songId) }
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
    private suspend fun restoreSongs(saved: life.xstar.music.player.data.preferences.SavedPlayback): RestoredQueue {
        val songs = repo.findByIds(saved.queue)
        val currentId = saved.queue.getOrNull(saved.index)
        return RestoredQueue(songs, songs.indexOfFirst { it.mediaStoreId == currentId }.coerceAtLeast(0))
    }
    override fun onCleared() { player.release() }

    companion object { fun factory(repo: MusicRepository, player: PlayerController, preferences: PlaybackPreferences, lyrics: LyricsRepository) = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(type: Class<T>): T = MusicViewModel(repo, player, preferences, lyrics) as T
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

private enum class Screen(val title: String) { Player("正在播放"), Library("音乐库"), Favorites("我的收藏"), Playlists("播放列表"), Equalizer("均衡器"), Settings("设置") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun XstarApp(vm: MusicViewModel) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var screen by remember { mutableStateOf(Screen.Player) }
    val theme by vm.theme.collectAsStateWithLifecycle()
    val sleepTimerRemaining by vm.sleepTimerRemaining.collectAsStateWithLifecycle()
    val fmMode by vm.fmModeEnabled.collectAsStateWithLifecycle()
    val songs by vm.songs.collectAsStateWithLifecycle()
    val accent = theme.color()
    LaunchedEffect(vm) { vm.openPlayer.collect { screen = Screen.Player; drawerState.close() } }
    ModalNavigationDrawer(drawerState = drawerState, gesturesEnabled = true, drawerContent = {
        ModalDrawerSheet(drawerContainerColor = Color(0xFF10131A)) {
            Column(Modifier.fillMaxHeight().width(300.dp).padding(horizontal = 16.dp)) {
                Spacer(Modifier.height(44.dp)); Text("XSTAR", fontSize = 30.sp, fontWeight = FontWeight.Black, color = Color.White); Text("YOUR MUSIC. YOUR SPACE.", color = Color(0xFF7E8A9A), fontSize = 11.sp); Spacer(Modifier.height(34.dp))
                listOf(Screen.Player to Icons.Default.Headphones, Screen.Library to Icons.Default.LibraryMusic, Screen.Favorites to Icons.Default.Favorite, Screen.Playlists to Icons.Default.QueueMusic, Screen.Equalizer to Icons.Default.Equalizer, Screen.Settings to Icons.Default.Settings).forEach { item ->
                    NavigationDrawerItem(label = { Text(item.first.title) }, icon = { Icon(item.second, null) }, selected = screen == item.first, onClick = { screen = item.first; scope.launch { drawerState.close() } }, modifier = Modifier.padding(vertical = 3.dp))
                }
                Spacer(Modifier.height(16.dp))
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = Color.White.copy(alpha = .06f)) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Radio, null, Modifier.size(21.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("FM 模式", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text(if (songs.isEmpty()) "请先扫描音乐" else "随机电台 · 极简手势", color = Color(0xFF818C9C), fontSize = 11.sp)
                        }
                        Switch(
                            checked = fmMode,
                            enabled = songs.isNotEmpty() || fmMode,
                            onCheckedChange = {
                                vm.setFmMode(it)
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
                Spacer(Modifier.weight(1f))
                Text("主题颜色", color = Color(0xFF9BA5B5), fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    ThemeAccent.entries.forEach { accent -> ThemeSwatch(accent, theme == accent) { vm.setTheme(accent) } }
                }
                Text("从屏幕左侧向右滑动打开菜单", color = Color(0xFF697382), fontSize = 12.sp, modifier = Modifier.padding(12.dp)); Spacer(Modifier.height(20.dp))
            }
        }
    }) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(accent.copy(alpha = .22f), Color(0xFF090B10)), endY = 1100f))) {
            AnimatedContent(screen, label = "screen") { destination -> when (destination) {
                Screen.Player -> PlayerScreen(vm) { scope.launch { drawerState.open() } }
                Screen.Library -> LibraryScreen(vm) { scope.launch { drawerState.open() } }
                Screen.Favorites -> CollectionScreen(vm.favorites.collectAsStateWithLifecycle().value, vm) { scope.launch { drawerState.open() } }
                Screen.Playlists -> PlaylistsScreen(vm) { scope.launch { drawerState.open() } }
                Screen.Equalizer -> EqualizerScreen(vm) { scope.launch { drawerState.open() } }
                Screen.Settings -> SettingsScreen(vm) { scope.launch { drawerState.open() } }
            } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun PlayerScreen(vm: MusicViewModel, openMenu: () -> Unit) {
    val fmMode by vm.fmModeEnabled.collectAsStateWithLifecycle()
    AnimatedContent(fmMode, label = "playerMode") { fm ->
        if (fm) FmPlayerScreen(vm, openMenu) else TraditionalPlayerScreen(vm, openMenu)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun TraditionalPlayerScreen(vm: MusicViewModel, openMenu: () -> Unit) {
    val state by vm.playback.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showQueue by remember { mutableStateOf(false) }
    val theme by vm.theme.collectAsStateWithLifecycle()
    val beatEffect by vm.beatEffect.collectAsStateWithLifecycle()
    val artworkColors by produceState(initialValue = ArtworkColors.fallback(theme), state.song?.albumArtUri, theme) {
        value = loadArtworkColors(context, state.song?.albumArtUri, theme)
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
    val song = state.song
    var showArtworkPicker by remember { mutableStateOf(false) }
    var showSongDetails by remember { mutableStateOf(false) }
    var showLyrics by remember { mutableStateOf(false) }
    LaunchedEffect(song?.mediaStoreId) { showLyrics = false }
    Box(
        Modifier.fillMaxSize()
            .background(artworkColors.dark)
            .background(Brush.radialGradient(listOf(artworkColors.light.copy(alpha = .42f), Color.Transparent), center = Offset(120f, 80f), radius = 820f))
            .background(Brush.radialGradient(listOf(ambient.copy(alpha = .48f), Color.Transparent), center = Offset(920f, 620f), radius = 1050f))
            .background(Brush.radialGradient(listOf(artworkColors.primary.copy(alpha = .24f), Color.Transparent, artworkColors.dark.copy(alpha = .72f)), center = Offset(520f, 430f), radius = 1300f))
    ) {
    IconButton(openMenu, Modifier.statusBarsPadding().padding(10.dp).align(Alignment.TopStart)) { Icon(Icons.Default.Menu, "菜单", tint = Color.White) }
    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(if (showLyrics) .12f else .45f))
        BoxWithConstraints(
            Modifier.fillMaxWidth().then(if (showLyrics) Modifier.weight(1f) else Modifier),
            contentAlignment = Alignment.Center
        ) {
            val artworkSize = minOf(maxWidth - 48.dp, 330.dp)
            if (showLyrics) LyricsPanel(vm, Modifier.fillMaxSize()) { showLyrics = false } else Box(
                Modifier.size(artworkSize).scale(artworkScale).graphicsLayer { rotationZ = discRotation.value }.clickable { showLyrics = true },
                contentAlignment = Alignment.Center
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
        Slider(
            value = if (state.duration > 0) state.position.toFloat() / state.duration else 0f,
            onValueChange = { vm.player.seek((it * state.duration).toLong()) },
            modifier = Modifier.height(20.dp),
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White.copy(alpha = .9f),
                inactiveTrackColor = Color.White.copy(alpha = .18f)
            ),
            thumb = { Box(Modifier.size(8.dp).clip(CircleShape).background(Color.White)) },
            track = { sliderPositions ->
                Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = .18f))) {
                    Box(Modifier.fillMaxWidth(sliderPositions.value).fillMaxHeight().clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = .9f)))
                }
            }
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(time(state.position), color = Color(0xFF8A94A5), fontSize = 12.sp); Text(time(state.duration), color = Color(0xFF8A94A5), fontSize = 12.sp) }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth().height(76.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
            PlaybackModeButton(state.mode) { vm.player.setPlaybackMode(state.mode.next()) }
            IconButton(onClick = vm.player::previous, Modifier.size(52.dp)) { Icon(Icons.Default.SkipPrevious, "上一首", Modifier.size(32.dp), tint = Color.White) }
            IconButton(onClick = vm.player::toggle, Modifier.size(64.dp)) { Icon(if (state.playing) Icons.Default.Pause else Icons.Default.PlayArrow, "播放或暂停", Modifier.size(38.dp), tint = Color.White) }
            IconButton(onClick = vm.player::next, Modifier.size(52.dp)) { Icon(Icons.Default.SkipNext, "下一首", Modifier.size(32.dp), tint = Color.White) }
            IconButton(onClick = { showQueue = true }, Modifier.size(52.dp)) { Icon(Icons.Default.QueueMusic, "播放列表", tint = Color(0xFFB3BDCC)) }
        }
        state.error?.let { Text(it, color = Color(0xFFFF8A96), fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.weight(if (showLyrics) .08f else .5f))
    }
    }
    if (showQueue) QueueSheet(state, vm) { showQueue = false }
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

@Composable private fun PlaybackModeButton(mode: PlaybackMode, onClick: () -> Unit) {
    val label = when (mode) { PlaybackMode.Shuffle -> "随机"; PlaybackMode.RepeatOne -> "单曲"; PlaybackMode.Sequential -> "顺序" }
    IconButton(onClick, Modifier.size(52.dp)) { Icon(when (mode) { PlaybackMode.Shuffle -> Icons.Default.Shuffle; PlaybackMode.RepeatOne -> Icons.Default.RepeatOne; PlaybackMode.Sequential -> Icons.Default.FormatListNumbered }, label, tint = if (mode == PlaybackMode.Sequential) Color(0xFFB3BDCC) else MaterialTheme.colorScheme.primary) }
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
    val scanState by vm.scanState.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var group by remember { mutableIntStateOf(0) }
    var selectedAlbum by remember { mutableStateOf<Pair<String, List<SongEntity>>?>(null) }
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 18.dp)) {
        PageHeader(openMenu, {
            IconButton({ folderPicker.launch(null) }, enabled = !scanState.running) { Icon(Icons.Default.CreateNewFolder, "添加文件夹", tint = Color.White) }
            IconButton(vm::scan, enabled = !scanState.running && folders.isNotEmpty()) { Icon(Icons.Default.Refresh, "扫描", tint = Color.White) }
        })
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), leadingIcon = { Icon(Icons.Default.Search, null) }, placeholder = { Text("搜索歌曲、艺术家或专辑") }, singleLine = true, shape = RoundedCornerShape(18.dp))
        if (scanState.running || scanState.message != null) {
            Spacer(Modifier.height(12.dp))
            ScanStatusCard(scanState)
        }
        Spacer(Modifier.height(12.dp))
        if (folders.isEmpty() && !scanState.running) {
            EmptyState("选择音乐文件夹", "可以连续选择多个目录，XSTAR 只扫描这些目录中的音频。", "选择文件夹") { folderPicker.launch(null) }
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

@Composable private fun CollectionScreen(songs: List<SongEntity>, vm: MusicViewModel, openMenu: () -> Unit) { Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 18.dp)) { PageHeader(openMenu); if (songs.isEmpty()) EmptyState("还没有收藏", "在歌曲右侧点击爱心即可收藏。", "前往音乐库") {} else SongsList(songs, vm) } }
@Composable private fun SongsList(songs: List<SongEntity>, vm: MusicViewModel) { LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 10.dp)) { items(songs, key = { it.mediaStoreId }) { song -> ListItem(colors = ListItemDefaults.colors(containerColor = Color.Transparent), headlineContent = { Text(song.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis) }, supportingContent = { Text("${song.artist} · ${formatName(song.mimeType)}", color = Color(0xFF8F99A9)) }, leadingContent = { Surface(Modifier.size(48.dp), RoundedCornerShape(13.dp), color = Color(0xFF252C38)) { ArtworkImage(song.albumArtUri, Color(0xFF252C38)) } }, trailingContent = { IconButton({ vm.favorite(song.mediaStoreId) }) { Icon(if (song.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "收藏", tint = if (song.isFavorite) Color(0xFFFF708C) else Color.Gray) } }, modifier = Modifier.clickable { vm.play(song, songs) }) } } }

@Composable private fun ArtworkImage(uri: String?, tint: Color) {
    if (uri.isNullOrBlank()) DefaultArtwork(tint)
    else {
        val model = if (uri.startsWith("content://") || uri.startsWith("file://")) Uri.parse(uri) else Uri.fromFile(File(uri))
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
    val folders by vm.folders.collectAsStateWithLifecycle()
    val beatEffect by vm.beatEffect.collectAsStateWithLifecycle()
    val onlineArtwork by vm.onlineArtworkEnabled.collectAsStateWithLifecycle()
    val scanState by vm.scanState.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { runCatching { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }; vm.addFolder(it.toString()) }
    }
    Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        PageHeader(openMenu)
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            headlineContent = { Text("允许获取在线专辑封面", color = Color.White) },
            supportingContent = { Text("开启后可在专辑页面手动查询 iTunes 专辑封面", color = Color.Gray) },
            leadingContent = { Icon(Icons.Default.CloudDownload, null) },
            trailingContent = { Switch(checked = onlineArtwork, onCheckedChange = vm::setOnlineArtworkEnabled) }
        )
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            headlineContent = { Text("允许获取在线歌词", color = Color.White) },
            supportingContent = { Text("本地 LRC 仍会优先使用", color = Color.Gray) },
            leadingContent = { Icon(Icons.Default.Notes, null) },
            trailingContent = { Switch(checked = vm.onlineLyricsEnabled.collectAsStateWithLifecycle().value, onCheckedChange = vm::setOnlineLyricsEnabled) }
        )
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            headlineContent = { Text("音乐节拍动态效果", color = Color.White) },
            supportingContent = { Text("播放时切换背景颜色并轻微缩放封面", color = Color.Gray) },
            leadingContent = { Icon(Icons.Default.GraphicEq, null) },
            trailingContent = { Switch(checked = beatEffect, onCheckedChange = vm::setBeatEffect) }
        )
        Text("音乐文件夹", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp))
        folders.forEach { uri ->
            ListItem(colors = ListItemDefaults.colors(containerColor = Color.Transparent), headlineContent = { Text(folderName(uri), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis) }, supportingContent = { Text(uri, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis) }, leadingContent = { Icon(Icons.Default.Folder, null) }, trailingContent = { IconButton({ vm.removeFolder(uri) }, enabled = !scanState.running) { Icon(Icons.Default.Close, "移除") } })
        }
        OutlinedButton({ picker.launch(null) }, Modifier.padding(horizontal = 16.dp), enabled = !scanState.running) { Icon(Icons.Default.CreateNewFolder, null); Spacer(Modifier.width(8.dp)); Text("添加文件夹") }
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            headlineContent = { Text(if (scanState.running) "正在扫描音乐库" else "重新扫描音乐库", color = Color.White) },
            supportingContent = {
                Text(
                    when {
                        scanState.running && !scanState.currentFile.isNullOrBlank() -> "已发现 ${scanState.discovered} 首 · ${scanState.currentFile}"
                        scanState.message != null -> scanState.message.orEmpty()
                        else -> "仅同步已选择文件夹中的音频"
                    },
                    color = if (scanState.failed) Color(0xFFFF8A96) else Color.Gray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            leadingContent = { Icon(if (scanState.running) Icons.Default.Radar else Icons.Default.Refresh, null) },
            trailingContent = { if (scanState.running) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) },
            modifier = Modifier.clickable(enabled = !scanState.running && folders.isNotEmpty()) { vm.scan() }
        )
        ListItem(colors = ListItemDefaults.colors(containerColor = Color.Transparent), headlineContent = { Text("XSTAR", color = Color.White) }, supportingContent = { Text("本地音乐播放器 · 0.1.0", color = Color.Gray) }, leadingContent = { Icon(Icons.Default.Info, null) })
    }
}
@Composable private fun EmptyState(title: String, text: String, action: String, click: () -> Unit) { Column(Modifier.fillMaxWidth().padding(42.dp), horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Default.LibraryMusic, null, Modifier.size(58.dp), tint = Color(0xFF65738A)); Spacer(Modifier.height(18.dp)); Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold); Text(text, color = Color.Gray, modifier = Modifier.padding(vertical = 12.dp)); Button(click) { Text(action) } } }
private fun PlaybackMode.next() = when (this) { PlaybackMode.Sequential -> PlaybackMode.Shuffle; PlaybackMode.Shuffle -> PlaybackMode.RepeatOne; PlaybackMode.RepeatOne -> PlaybackMode.Sequential }
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
    val openArtwork = {
        if (artworkUri.startsWith("content://") || artworkUri.startsWith("file://")) context.contentResolver.openInputStream(Uri.parse(artworkUri))
        else File(artworkUri).inputStream()
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
