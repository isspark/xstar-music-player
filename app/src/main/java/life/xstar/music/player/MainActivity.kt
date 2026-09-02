package life.xstar.music.player

import android.content.Intent
import android.net.Uri
import java.io.File
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.SubcomposeAsyncImage
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.*
import kotlin.random.Random
import life.xstar.music.player.data.local.*
import life.xstar.music.player.data.preferences.PlaybackPreferences
import life.xstar.music.player.data.preferences.ThemeAccent
import life.xstar.music.player.data.repository.MusicRepository
import life.xstar.music.player.data.repository.ArtworkCandidate
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
    val scanning = MutableStateFlow(false)
    val folders = preferences.folders.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val theme = preferences.theme.stateIn(viewModelScope, SharingStarted.Eagerly, ThemeAccent.Blue)
    val beatEffect = preferences.beatEffect.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val onlineArtworkEnabled = preferences.onlineArtworkEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val onlineLyricsEnabled = preferences.onlineLyricsEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val openPlayer = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val lyrics = MutableStateFlow<LyricsState>(LyricsState.Idle)

    init {
        viewModelScope.launch { playback.debounce(1_000).collect { state -> if (state.queue.isNotEmpty()) preferences.save(state.queue.map { it.mediaStoreId }, state.index, state.position) } }
        viewModelScope.launch { preferences.read().let { saved -> if (saved.queue.isNotEmpty()) player.restoreQueue(repo.findByIds(saved.queue), saved.index, saved.position) } }
        viewModelScope.launch {
            combine(songs, playback) { library, state ->
                state.song?.mediaStoreId?.let { id -> library.firstOrNull { it.mediaStoreId == id } }
            }.filterNotNull().distinctUntilChanged().collect(player::updateSong)
        }
        viewModelScope.launch {
            playback.map { it.song?.mediaStoreId }.distinctUntilChanged().collect { id ->
                val song = id?.let { repo.getSong(it) }
                lyrics.value = if (song == null) LyricsState.Idle else LyricsState.Loading
                if (song != null) lyrics.value = runCatching { lyricsRepository.loadLocalOrEmbedded(song) }
                    .getOrNull()?.let { LyricsState.Loaded(it) } ?: LyricsState.NotFound
            }
        }
    }

    fun scan() = viewModelScope.launch {
        scanning.value = true
        runCatching { repo.scan(folders.value) }
        scanning.value = false
    }
    fun addFolder(uri: String) = viewModelScope.launch {
        preferences.addFolder(uri)
        repo.scan(folders.value + uri)
    }
    fun removeFolder(uri: String) = viewModelScope.launch {
        preferences.removeFolder(uri)
        repo.scan(folders.value - uri)
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
        player.playQueue(source.ifEmpty { listOf(song) }, source.indexOf(song).coerceAtLeast(0))
        openPlayer.tryEmit(Unit)
    }
    fun favorite(id: Long) = viewModelScope.launch { repo.toggleFavorite(id) }
    fun createPlaylist(name: String) = viewModelScope.launch { if (name.isNotBlank()) repo.createPlaylist(name.trim()) }
    fun deletePlaylist(item: PlaylistEntity) = viewModelScope.launch { repo.deletePlaylist(item) }
    fun renamePlaylist(id: Long, name: String) = viewModelScope.launch { if (name.isNotBlank()) repo.renamePlaylist(id, name) }
    fun playlistSongs(id: Long) = repo.playlistSongs(id)
    fun addToPlaylist(playlistId: Long, songId: Long) = viewModelScope.launch { repo.addToPlaylist(playlistId, songId) }
    fun removeFromPlaylist(playlistId: Long, songId: Long) = viewModelScope.launch { repo.removeFromPlaylist(playlistId, songId) }
    override fun onCleared() { player.release() }

    companion object { fun factory(repo: MusicRepository, player: PlayerController, preferences: PlaybackPreferences, lyrics: LyricsRepository) = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(type: Class<T>): T = MusicViewModel(repo, player, preferences, lyrics) as T
    } }
}

private enum class Screen(val title: String) { Player("正在播放"), Library("音乐库"), Favorites("我的收藏"), Playlists("播放列表"), Settings("设置") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun XstarApp(vm: MusicViewModel) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var screen by remember { mutableStateOf(Screen.Player) }
    val theme by vm.theme.collectAsStateWithLifecycle()
    val accent = theme.color()
    LaunchedEffect(vm) { vm.openPlayer.collect { screen = Screen.Player; drawerState.close() } }
    ModalNavigationDrawer(drawerState = drawerState, gesturesEnabled = true, drawerContent = {
        ModalDrawerSheet(drawerContainerColor = Color(0xFF10131A)) {
            Column(Modifier.fillMaxHeight().width(300.dp).padding(horizontal = 16.dp)) {
                Spacer(Modifier.height(44.dp)); Text("XSTAR", fontSize = 30.sp, fontWeight = FontWeight.Black, color = Color.White); Text("YOUR MUSIC. YOUR SPACE.", color = Color(0xFF7E8A9A), fontSize = 11.sp); Spacer(Modifier.height(34.dp))
                listOf(Screen.Player to Icons.Default.Headphones, Screen.Library to Icons.Default.LibraryMusic, Screen.Favorites to Icons.Default.Favorite, Screen.Playlists to Icons.Default.QueueMusic, Screen.Settings to Icons.Default.Settings).forEach { item ->
                    NavigationDrawerItem(label = { Text(item.first.title) }, icon = { Icon(item.second, null) }, selected = screen == item.first, onClick = { screen = item.first; scope.launch { drawerState.close() } }, modifier = Modifier.padding(vertical = 3.dp))
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
                Screen.Settings -> SettingsScreen(vm) { scope.launch { drawerState.open() } }
            } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun PlayerScreen(vm: MusicViewModel, openMenu: () -> Unit) {
    val state by vm.playback.collectAsStateWithLifecycle()
    var showQueue by remember { mutableStateOf(false) }
    val theme by vm.theme.collectAsStateWithLifecycle()
    val beatEffect by vm.beatEffect.collectAsStateWithLifecycle()
    val pulseColors = remember(theme) { theme.pulseColors() }
    var ambientTarget by remember(theme) { mutableStateOf(pulseColors.first()) }
    var pulseScale by remember { mutableFloatStateOf(1f) }
    val discRotation = remember { Animatable(0f) }
    LaunchedEffect(state.playing, state.song?.mediaStoreId, theme, beatEffect) {
        if (!state.playing || !beatEffect) {
            ambientTarget = theme.color()
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
    var showLyrics by remember { mutableStateOf(false) }
    LaunchedEffect(song?.mediaStoreId) { showLyrics = false }
    Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(ambient.copy(alpha = .55f), ambient.copy(alpha = .13f), Color(0xFF090B10)), radius = 1250f))) {
    IconButton(openMenu, Modifier.statusBarsPadding().padding(10.dp).align(Alignment.TopStart)) { Icon(Icons.Default.Menu, "菜单", tint = Color.White) }
    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(.45f))
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val artworkSize = minOf(maxWidth - 48.dp, 330.dp)
            if (showLyrics) LyricsPanel(vm, artworkSize) { showLyrics = false } else Box(
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
        Spacer(Modifier.height(30.dp))
        Text(song?.title ?: "选择一首歌曲", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(6.dp)); Text(song?.artist ?: "从音乐库开始播放", color = Color(0xFFAAB2C0), fontSize = 16.sp)
        if (song != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatName(song.mimeType), color = Color(0xFF71809A), fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                TextButton(onClick = { showArtworkPicker = true }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Icon(Icons.Default.Image, "获取封面", Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (song.albumArtUri.isNullOrBlank()) "获取封面" else "更换封面", fontSize = 12.sp)
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
        Spacer(Modifier.weight(.5f))
    }
    }
    if (showQueue) QueueSheet(state, vm) { showQueue = false }
    if (showArtworkPicker && song != null) AlbumArtworkSheet(song.album, listOf(song), vm, allowReplace = true) { showArtworkPicker = false }
}

@Composable private fun LyricsPanel(vm: MusicViewModel, size: androidx.compose.ui.unit.Dp, close: () -> Unit) {
    val state by vm.lyrics.collectAsStateWithLifecycle()
    val playback by vm.playback.collectAsStateWithLifecycle()
    when (state) {
        LyricsState.Loading -> CircularProgressIndicator()
        LyricsState.NotFound -> LyricsEmpty("未找到本地歌词", vm::findLyrics, close)
        is LyricsState.Error -> LyricsEmpty((state as LyricsState.Error).message, vm::findLyrics, close)
        is LyricsState.Candidates -> LyricsCandidates((state as LyricsState.Candidates).items, vm, close)
        is LyricsState.Loaded -> LyricsScroll((state as LyricsState.Loaded).document.lines, playback.position, close)
        LyricsState.Idle -> LyricsEmpty("点击获取歌词", vm::findLyrics, close)
    }
}

@Composable private fun LyricsEmpty(message: String, fetch: () -> Unit, close: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(24.dp).clickable(onClick = close), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.QueueMusic, null, Modifier.size(44.dp), tint = Color(0xFF8491A6))
        Text(message, color = Color(0xFFB8C1CF), modifier = Modifier.padding(vertical = 12.dp))
        Button(onClick = fetch) { Text("获取歌词") }
    }
}

@Composable private fun LyricsCandidates(items: List<LyricsCandidate>, vm: MusicViewModel, close: () -> Unit) {
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 330.dp)) {
        item { Text("选择歌词", color = Color.White, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp)) }
        items(items, key = { it.id }) { candidate ->
            ListItem(headlineContent = { Text(candidate.trackName.ifBlank { "未知歌曲" }) }, supportingContent = { Text("${candidate.artistName} · ${if (candidate.isSynced) "同步歌词" else "纯文本"}") }, modifier = Modifier.clickable { vm.saveLyrics(candidate); close() })
        }
    }
}

@Composable private fun LyricsScroll(lines: List<life.xstar.music.player.data.lyrics.LyricLine>, position: Long, close: () -> Unit) {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val current = lines.indexOfLast { it.timeMs?.let(position::compareTo) ?: -1 >= 0 }
    LaunchedEffect(current) { if (current >= 0) listState.animateScrollToItem(current) }
    LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().heightIn(max = 370.dp).clickable(onClick = close), horizontalAlignment = Alignment.CenterHorizontally, contentPadding = PaddingValues(vertical = 90.dp)) {
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
    val songs by vm.songs.collectAsStateWithLifecycle(); val folders by vm.folders.collectAsStateWithLifecycle(); val scanning by vm.scanning.collectAsStateWithLifecycle(); var query by remember { mutableStateOf("") }; var group by remember { mutableIntStateOf(0) }
    var selectedAlbum by remember { mutableStateOf<Pair<String, List<SongEntity>>?>(null) }
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 18.dp)) { PageHeader(openMenu, { IconButton({ folderPicker.launch(null) }) { Icon(Icons.Default.CreateNewFolder, "添加文件夹", tint = Color.White) }; IconButton(vm::scan) { Icon(Icons.Default.Refresh, "扫描", tint = Color.White) } }); OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), leadingIcon = { Icon(Icons.Default.Search, null) }, placeholder = { Text("搜索歌曲、艺术家或专辑") }, singleLine = true, shape = RoundedCornerShape(18.dp)); Spacer(Modifier.height(12.dp)); if (folders.isEmpty()) EmptyState("选择音乐文件夹", "可以连续选择多个目录，XSTAR 只扫描这些目录中的音频。", "选择文件夹") { folderPicker.launch(null) } else if (scanning) Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() } else { val filtered = songs.filter { query.isBlank() || "${it.title}${it.artist}${it.album}".contains(query, true) }; TabRow(group, containerColor = Color.Transparent) { listOf("歌曲", "专辑", "艺术家").forEachIndexed { i, text -> Tab(group == i, { group = i }, text = { Text(text) }) } }; if (group == 0) SongsList(filtered, vm) else GroupsList(filtered.groupBy { if (group == 1) it.album else it.artist }, vm) { name, entries -> if (group == 1) selectedAlbum = name to entries } } }
    selectedAlbum?.let { (name, entries) -> AlbumArtworkSheet(name, entries, vm) { selectedAlbum = null } }
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

@Composable private fun SettingsScreen(vm: MusicViewModel, openMenu: () -> Unit) {
    val context = LocalContext.current
    val folders by vm.folders.collectAsStateWithLifecycle()
    val beatEffect by vm.beatEffect.collectAsStateWithLifecycle()
    val onlineArtwork by vm.onlineArtworkEnabled.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { runCatching { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }; vm.addFolder(it.toString()) }
    }
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 18.dp)) {
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
            ListItem(colors = ListItemDefaults.colors(containerColor = Color.Transparent), headlineContent = { Text(folderName(uri), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis) }, supportingContent = { Text(uri, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis) }, leadingContent = { Icon(Icons.Default.Folder, null) }, trailingContent = { IconButton({ vm.removeFolder(uri) }) { Icon(Icons.Default.Close, "移除") } })
        }
        OutlinedButton({ picker.launch(null) }, Modifier.padding(horizontal = 16.dp)) { Icon(Icons.Default.CreateNewFolder, null); Spacer(Modifier.width(8.dp)); Text("添加文件夹") }
        ListItem(colors = ListItemDefaults.colors(containerColor = Color.Transparent), headlineContent = { Text("重新扫描音乐库", color = Color.White) }, supportingContent = { Text("仅同步已选择文件夹中的音频", color = Color.Gray) }, leadingContent = { Icon(Icons.Default.Refresh, null) }, modifier = Modifier.clickable { vm.scan() })
        ListItem(colors = ListItemDefaults.colors(containerColor = Color.Transparent), headlineContent = { Text("XSTAR", color = Color.White) }, supportingContent = { Text("本地音乐播放器 · 0.1.0", color = Color.Gray) }, leadingContent = { Icon(Icons.Default.Info, null) })
    }
}
@Composable private fun EmptyState(title: String, text: String, action: String, click: () -> Unit) { Column(Modifier.fillMaxWidth().padding(42.dp), horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Default.LibraryMusic, null, Modifier.size(58.dp), tint = Color(0xFF65738A)); Spacer(Modifier.height(18.dp)); Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold); Text(text, color = Color.Gray, modifier = Modifier.padding(vertical = 12.dp)); Button(click) { Text(action) } } }
private fun PlaybackMode.next() = when (this) { PlaybackMode.Sequential -> PlaybackMode.Shuffle; PlaybackMode.Shuffle -> PlaybackMode.RepeatOne; PlaybackMode.RepeatOne -> PlaybackMode.Sequential }
private fun time(ms: Long) = "%d:%02d".format(ms.coerceAtLeast(0) / 60_000, (ms.coerceAtLeast(0) / 1_000) % 60)
private fun formatName(mime: String?) = mime?.substringAfter('/')?.uppercase()?.replace("MPEG", "MP3") ?: "AUDIO"
private fun folderName(uri: String) = Uri.parse(uri).lastPathSegment?.substringAfterLast(':')?.ifBlank { "音乐文件夹" } ?: "音乐文件夹"
private fun ThemeAccent.color() = when (this) { ThemeAccent.Blue -> Color(0xFF78A9FF); ThemeAccent.Violet -> Color(0xFFAE8CFF); ThemeAccent.Rose -> Color(0xFFFF7998); ThemeAccent.Emerald -> Color(0xFF55D6A9); ThemeAccent.Amber -> Color(0xFFFFBE5C) }
private fun ThemeAccent.pulseColors(): List<Color> = when (this) {
    ThemeAccent.Blue -> listOf(Color(0xFF2676D9), Color(0xFF6E4BD8), Color(0xFF1696A7), Color(0xFF2752A8))
    ThemeAccent.Violet -> listOf(Color(0xFF7547D8), Color(0xFFB43FA1), Color(0xFF4452C9), Color(0xFF8A45C2))
    ThemeAccent.Rose -> listOf(Color(0xFFD93663), Color(0xFFC849A5), Color(0xFFD25A3A), Color(0xFF9D3E77))
    ThemeAccent.Emerald -> listOf(Color(0xFF15956B), Color(0xFF168E9B), Color(0xFF3C8D4C), Color(0xFF237E70))
    ThemeAccent.Amber -> listOf(Color(0xFFD18425), Color(0xFFC95833), Color(0xFFA79324), Color(0xFFB66B2B))
}
@Composable private fun XstarTheme(accent: ThemeAccent, content: @Composable () -> Unit) { MaterialTheme(colorScheme = darkColorScheme(primary = accent.color(), background = Color(0xFF090B10), surface = Color(0xFF171B23)), content = content) }
