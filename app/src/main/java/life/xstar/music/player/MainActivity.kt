package life.xstar.music.player

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
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
import coil.compose.AsyncImage
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import life.xstar.music.player.data.local.*
import life.xstar.music.player.data.preferences.PlaybackPreferences
import life.xstar.music.player.data.preferences.ThemeAccent
import life.xstar.music.player.data.repository.MusicRepository
import life.xstar.music.player.player.*

class MainActivity : ComponentActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        enableEdgeToEdge()
        val app = application as XstarApplication
        val vm = ViewModelProvider(this, MusicViewModel.factory(app.repository, PlayerController(this), app.playbackPreferences))[MusicViewModel::class.java]
        setContent { val theme by vm.theme.collectAsStateWithLifecycle(); XstarTheme(theme) { XstarApp(vm) } }
    }
}

@OptIn(kotlinx.coroutines.FlowPreview::class)
class MusicViewModel(private val repo: MusicRepository, val player: PlayerController, private val preferences: PlaybackPreferences) : ViewModel() {
    val songs = repo.songs().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val favorites = repo.favorites().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val playlists = repo.playlists().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val playback = player.state
    val scanning = MutableStateFlow(false)
    val folders = preferences.folders.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val theme = preferences.theme.stateIn(viewModelScope, SharingStarted.Eagerly, ThemeAccent.Blue)

    init {
        viewModelScope.launch { playback.debounce(1_000).collect { state -> if (state.queue.isNotEmpty()) preferences.save(state.queue.map { it.mediaStoreId }, state.index, state.position) } }
        viewModelScope.launch { preferences.read().let { saved -> if (saved.queue.isNotEmpty()) player.restoreQueue(repo.findByIds(saved.queue), saved.index, saved.position) } }
    }

    fun scan() = viewModelScope.launch { scanning.value = true; runCatching { repo.scan(folders.value) }; scanning.value = false }
    fun addFolder(uri: String) = viewModelScope.launch { preferences.addFolder(uri); repo.scan(folders.value + uri) }
    fun removeFolder(uri: String) = viewModelScope.launch { preferences.removeFolder(uri); repo.scan(folders.value - uri) }
    fun setTheme(theme: ThemeAccent) = viewModelScope.launch { preferences.setTheme(theme) }
    fun play(song: SongEntity, source: List<SongEntity> = songs.value) = player.playQueue(source.ifEmpty { listOf(song) }, source.indexOf(song).coerceAtLeast(0))
    fun favorite(id: Long) = viewModelScope.launch { repo.toggleFavorite(id) }
    fun createPlaylist(name: String) = viewModelScope.launch { if (name.isNotBlank()) repo.createPlaylist(name.trim()) }
    fun deletePlaylist(item: PlaylistEntity) = viewModelScope.launch { repo.deletePlaylist(item) }
    fun renamePlaylist(id: Long, name: String) = viewModelScope.launch { if (name.isNotBlank()) repo.renamePlaylist(id, name) }
    fun playlistSongs(id: Long) = repo.playlistSongs(id)
    fun addToPlaylist(playlistId: Long, songId: Long) = viewModelScope.launch { repo.addToPlaylist(playlistId, songId) }
    fun removeFromPlaylist(playlistId: Long, songId: Long) = viewModelScope.launch { repo.removeFromPlaylist(playlistId, songId) }
    override fun onCleared() { player.release() }

    companion object { fun factory(repo: MusicRepository, player: PlayerController, preferences: PlaybackPreferences) = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(type: Class<T>): T = MusicViewModel(repo, player, preferences) as T
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
                Screen.Favorites -> CollectionScreen("我的收藏", vm.favorites.collectAsStateWithLifecycle().value, vm) { scope.launch { drawerState.open() } }
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
    val song = state.song
    Box(Modifier.fillMaxSize()) {
    IconButton(openMenu, Modifier.statusBarsPadding().padding(10.dp).align(Alignment.TopStart)) { Icon(Icons.Default.Menu, "菜单", tint = Color.White) }
    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(.45f))
        Surface(Modifier.fillMaxWidth().aspectRatio(1f).padding(horizontal = 18.dp), shape = RoundedCornerShape(34.dp), tonalElevation = 12.dp, shadowElevation = 16.dp, color = Color(0xFF242B37)) {
            if (song?.albumArtUri != null) AsyncImage(song.albumArtUri, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF34425A), Color(0xFF171C25)))), Alignment.Center) { Icon(Icons.Default.MusicNote, null, Modifier.size(90.dp), tint = Color(0xFF8FA7C7)) }
        }
        Spacer(Modifier.height(30.dp))
        Text(song?.title ?: "选择一首歌曲", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(6.dp)); Text(song?.artist ?: "从音乐库开始播放", color = Color(0xFFAAB2C0), fontSize = 16.sp)
        if (song != null) Text(formatName(song.mimeType), color = Color(0xFF71809A), fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(22.dp))
        Slider(value = if (state.duration > 0) state.position.toFloat() / state.duration else 0f, onValueChange = { vm.player.seek((it * state.duration).toLong()) }, colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color(0xFF38404D)))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(time(state.position), color = Color(0xFF8A94A5), fontSize = 12.sp); Text(time(state.duration), color = Color(0xFF8A94A5), fontSize = 12.sp) }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth().height(76.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
            PlaybackModeButton(state.mode) { vm.player.setPlaybackMode(state.mode.next()) }
            IconButton(onClick = vm.player::previous, Modifier.size(52.dp)) { Icon(Icons.Default.SkipPrevious, "上一首", Modifier.size(32.dp), tint = Color.White) }
            FilledIconButton(onClick = vm.player::toggle, Modifier.size(64.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White, contentColor = Color(0xFF0D1016))) { Icon(if (state.playing) Icons.Default.Pause else Icons.Default.PlayArrow, "播放或暂停", Modifier.size(34.dp)) }
            IconButton(onClick = vm.player::next, Modifier.size(52.dp)) { Icon(Icons.Default.SkipNext, "下一首", Modifier.size(32.dp), tint = Color.White) }
            IconButton(onClick = { showQueue = true }, Modifier.size(52.dp)) { Icon(Icons.Default.QueueMusic, "播放列表", tint = Color(0xFFB3BDCC)) }
        }
        state.error?.let { Text(it, color = Color(0xFFFF8A96), fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.weight(.5f))
    }
    }
    if (showQueue) QueueSheet(state, vm) { showQueue = false }
}

@Composable private fun PlaybackModeButton(mode: PlaybackMode, onClick: () -> Unit) {
    val label = when (mode) { PlaybackMode.Shuffle -> "随机"; PlaybackMode.RepeatOne -> "单曲"; PlaybackMode.Sequential -> "顺序" }
    IconButton(onClick, Modifier.size(52.dp)) { Icon(when (mode) { PlaybackMode.Shuffle -> Icons.Default.Shuffle; PlaybackMode.RepeatOne -> Icons.Default.RepeatOne; PlaybackMode.Sequential -> Icons.Default.FormatListNumbered }, label, tint = if (mode == PlaybackMode.Sequential) Color(0xFFB3BDCC) else MaterialTheme.colorScheme.primary) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun QueueSheet(state: PlayerState, vm: MusicViewModel, close: () -> Unit) { ModalBottomSheet(onDismissRequest = close, containerColor = Color(0xFF171B23)) { Column(Modifier.fillMaxWidth().padding(20.dp)) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Column { Text("播放列表", fontSize = 22.sp, fontWeight = FontWeight.Bold); Text("${state.queue.size} 首歌曲", color = Color.Gray) }; TextButton(onClick = vm.player::clear) { Text("清空") } }; LazyColumn(Modifier.heightIn(max = 520.dp)) { items(state.queue, key = { it.mediaStoreId }) { song -> val index = state.queue.indexOf(song); ListItem(colors = ListItemDefaults.colors(containerColor = Color.Transparent), headlineContent = { Text(song.title, color = if (index == state.index) Color(0xFF78A9FF) else Color.White) }, supportingContent = { Text(song.artist, color = Color.Gray) }, leadingContent = { Icon(if (index == state.index) Icons.Default.GraphicEq else Icons.Default.MusicNote, null) }, trailingContent = { IconButton(onClick = { vm.player.remove(index) }) { Icon(Icons.Default.Close, "移除") } }, modifier = Modifier.clickable { vm.play(song, state.queue) }) } }; Spacer(Modifier.height(20.dp)) } } }

@Composable private fun PageHeader(title: String, openMenu: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) { Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) { IconButton(openMenu) { Icon(Icons.Default.Menu, "菜单", tint = Color.White) }; Text(title, Modifier.weight(1f), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 18.sp); actions() } }

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
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 18.dp)) { PageHeader("音乐库", openMenu, { IconButton({ folderPicker.launch(null) }) { Icon(Icons.Default.CreateNewFolder, "添加文件夹", tint = Color.White) }; IconButton(vm::scan) { Icon(Icons.Default.Refresh, "扫描", tint = Color.White) } }); OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), leadingIcon = { Icon(Icons.Default.Search, null) }, placeholder = { Text("搜索歌曲、艺术家或专辑") }, singleLine = true, shape = RoundedCornerShape(18.dp)); Spacer(Modifier.height(12.dp)); if (folders.isEmpty()) EmptyState("选择音乐文件夹", "可以连续选择多个目录，XSTAR 只扫描这些目录中的音频。", "选择文件夹") { folderPicker.launch(null) } else if (scanning) Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() } else { val filtered = songs.filter { query.isBlank() || "${it.title}${it.artist}${it.album}".contains(query, true) }; TabRow(group, containerColor = Color.Transparent) { listOf("歌曲", "专辑", "艺术家").forEachIndexed { i, text -> Tab(group == i, { group = i }, text = { Text(text) }) } }; if (group == 0) SongsList(filtered, vm) else GroupsList(filtered.groupBy { if (group == 1) it.album else it.artist }, vm) } }
}

@Composable private fun CollectionScreen(title: String, songs: List<SongEntity>, vm: MusicViewModel, openMenu: () -> Unit) { Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 18.dp)) { PageHeader(title, openMenu); if (songs.isEmpty()) EmptyState("还没有收藏", "在歌曲右侧点击爱心即可收藏。", "前往音乐库") {} else SongsList(songs, vm) } }
@Composable private fun SongsList(songs: List<SongEntity>, vm: MusicViewModel) { LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 10.dp)) { items(songs, key = { it.mediaStoreId }) { song -> ListItem(colors = ListItemDefaults.colors(containerColor = Color.Transparent), headlineContent = { Text(song.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis) }, supportingContent = { Text("${song.artist} · ${formatName(song.mimeType)}", color = Color(0xFF8F99A9)) }, leadingContent = { Surface(Modifier.size(48.dp), RoundedCornerShape(13.dp), color = Color(0xFF252C38)) { AsyncImage(song.albumArtUri, null, contentScale = ContentScale.Crop) } }, trailingContent = { IconButton({ vm.favorite(song.mediaStoreId) }) { Icon(if (song.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "收藏", tint = if (song.isFavorite) Color(0xFFFF708C) else Color.Gray) } }, modifier = Modifier.clickable { vm.play(song, songs) }) } } }
@Composable private fun GroupsList(groups: Map<String, List<SongEntity>>, vm: MusicViewModel) { LazyColumn(Modifier.fillMaxSize()) { groups.forEach { (name, songs) -> item(name) { ListItem(colors = ListItemDefaults.colors(containerColor = Color.Transparent), headlineContent = { Text(name, color = Color.White) }, supportingContent = { Text("${songs.size} 首歌曲", color = Color.Gray) }, trailingContent = { IconButton({ vm.play(songs.first(), songs) }) { Icon(Icons.Default.PlayArrow, "播放") } }) } } } }

@Composable private fun PlaylistsScreen(vm: MusicViewModel, openMenu: () -> Unit) { val lists by vm.playlists.collectAsStateWithLifecycle(); var name by remember { mutableStateOf("") }; var selected by remember { mutableStateOf<PlaylistEntity?>(null) }; Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 18.dp)) { PageHeader("播放列表", openMenu); Row(verticalAlignment = Alignment.CenterVertically) { OutlinedTextField(name, { name = it }, Modifier.weight(1f), label = { Text("新建播放列表") }, singleLine = true); IconButton({ vm.createPlaylist(name); name = "" }) { Icon(Icons.Default.Add, "创建", tint = Color.White) } }; LazyColumn { items(lists, key = { it.id }) { item -> ListItem(colors = ListItemDefaults.colors(containerColor = Color.Transparent), headlineContent = { Text(item.name, color = Color.White) }, leadingContent = { Icon(Icons.Default.QueueMusic, null) }, trailingContent = { IconButton({ vm.deletePlaylist(item) }) { Icon(Icons.Default.DeleteOutline, "删除") } }, modifier = Modifier.clickable { selected = item }) } } }; selected?.let { PlaylistSheet(vm, it) { selected = null } } }

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun PlaylistSheet(vm: MusicViewModel, playlist: PlaylistEntity, close: () -> Unit) { val links by vm.playlistSongs(playlist.id).collectAsStateWithLifecycle(emptyList()); val all by vm.songs.collectAsStateWithLifecycle(); val current = links.mapNotNull { link -> all.find { it.mediaStoreId == link.songId } }; var name by remember { mutableStateOf(playlist.name) }; var add by remember { mutableStateOf(false) }; ModalBottomSheet(onDismissRequest = close, containerColor = Color(0xFF171B23)) { Column(Modifier.padding(20.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { OutlinedTextField(name, { name = it }, Modifier.weight(1f), label = { Text("名称") }); IconButton({ vm.renamePlaylist(playlist.id, name) }) { Icon(Icons.Default.Check, "保存") } }; TextButton({ add = true }) { Icon(Icons.Default.Add, null); Text("添加歌曲") }; SongsList(current, vm) } }; if (add) AlertDialog(onDismissRequest = { add = false }, title = { Text("添加歌曲") }, text = { LazyColumn { items(all.filterNot { it in current }, key = { it.mediaStoreId }) { song -> ListItem(headlineContent = { Text(song.title) }, modifier = Modifier.clickable { vm.addToPlaylist(playlist.id, song.mediaStoreId); add = false }) } } }, confirmButton = { TextButton({ add = false }) { Text("关闭") } }) }

@Composable private fun SettingsScreen(vm: MusicViewModel, openMenu: () -> Unit) { val context = LocalContext.current; val folders by vm.folders.collectAsStateWithLifecycle(); val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let { runCatching { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }; vm.addFolder(it.toString()) } }; Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 18.dp)) { PageHeader("设置", openMenu); Text("音乐文件夹", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp)); folders.forEach { uri -> ListItem(colors = ListItemDefaults.colors(containerColor = Color.Transparent), headlineContent = { Text(folderName(uri), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis) }, supportingContent = { Text(uri, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis) }, leadingContent = { Icon(Icons.Default.Folder, null) }, trailingContent = { IconButton({ vm.removeFolder(uri) }) { Icon(Icons.Default.Close, "移除") } }) }; OutlinedButton({ picker.launch(null) }, Modifier.padding(horizontal = 16.dp)) { Icon(Icons.Default.CreateNewFolder, null); Spacer(Modifier.width(8.dp)); Text("添加文件夹") }; ListItem(colors = ListItemDefaults.colors(containerColor = Color.Transparent), headlineContent = { Text("重新扫描音乐库", color = Color.White) }, supportingContent = { Text("仅同步已选择文件夹中的音频", color = Color.Gray) }, leadingContent = { Icon(Icons.Default.Refresh, null) }, modifier = Modifier.clickable { vm.scan() }); ListItem(colors = ListItemDefaults.colors(containerColor = Color.Transparent), headlineContent = { Text("XSTAR", color = Color.White) }, supportingContent = { Text("本地音乐播放器 · 0.1.0", color = Color.Gray) }, leadingContent = { Icon(Icons.Default.Info, null) }) } }
@Composable private fun EmptyState(title: String, text: String, action: String, click: () -> Unit) { Column(Modifier.fillMaxWidth().padding(42.dp), horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Default.LibraryMusic, null, Modifier.size(58.dp), tint = Color(0xFF65738A)); Spacer(Modifier.height(18.dp)); Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold); Text(text, color = Color.Gray, modifier = Modifier.padding(vertical = 12.dp)); Button(click) { Text(action) } } }
private fun PlaybackMode.next() = when (this) { PlaybackMode.Sequential -> PlaybackMode.Shuffle; PlaybackMode.Shuffle -> PlaybackMode.RepeatOne; PlaybackMode.RepeatOne -> PlaybackMode.Sequential }
private fun time(ms: Long) = "%d:%02d".format(ms.coerceAtLeast(0) / 60_000, (ms.coerceAtLeast(0) / 1_000) % 60)
private fun formatName(mime: String?) = mime?.substringAfter('/')?.uppercase()?.replace("MPEG", "MP3") ?: "AUDIO"
private fun folderName(uri: String) = Uri.parse(uri).lastPathSegment?.substringAfterLast(':')?.ifBlank { "音乐文件夹" } ?: "音乐文件夹"
private fun ThemeAccent.color() = when (this) { ThemeAccent.Blue -> Color(0xFF78A9FF); ThemeAccent.Violet -> Color(0xFFAE8CFF); ThemeAccent.Rose -> Color(0xFFFF7998); ThemeAccent.Emerald -> Color(0xFF55D6A9); ThemeAccent.Amber -> Color(0xFFFFBE5C) }
@Composable private fun XstarTheme(accent: ThemeAccent, content: @Composable () -> Unit) { MaterialTheme(colorScheme = darkColorScheme(primary = accent.color(), background = Color(0xFF090B10), surface = Color(0xFF171B23)), content = content) }
