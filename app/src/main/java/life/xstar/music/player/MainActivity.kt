package life.xstar.music.player

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import life.xstar.music.player.data.local.*
import life.xstar.music.player.data.repository.MusicRepository
import life.xstar.music.player.player.*

class MainActivity : ComponentActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val app = application as XstarApplication
        val controller = PlayerController(this)
        val vm = ViewModelProvider(this, MusicViewModel.factory(app.repository, controller, app.playbackPreferences))[MusicViewModel::class.java]
        setContent { XstarTheme { XstarApp(vm) } }
    }
}

class MusicViewModel(private val repo: MusicRepository, val player: PlayerController, private val preferences: life.xstar.music.player.data.preferences.PlaybackPreferences) : ViewModel() {
    val songs = repo.songs().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val favorites = repo.favorites().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val playlists = repo.playlists().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val playback = player.state
    val query = MutableStateFlow("")
    val scanning = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            playback.collect { state ->
                if (state.queue.isNotEmpty()) preferences.save(state.queue.map { it.mediaStoreId }, state.index, state.position)
            }
        }
        viewModelScope.launch {
            val saved = preferences.read()
            if (saved.queue.isNotEmpty()) player.restoreQueue(repo.findByIds(saved.queue), saved.index, saved.position)
        }
    }

    fun scan() { viewModelScope.launch { scanning.value = true; runCatching { repo.scan() }; scanning.value = false } }
    fun play(song: SongEntity, source: List<SongEntity> = songs.value) = player.playQueue(source.ifEmpty { listOf(song) }, source.indexOf(song).coerceAtLeast(0))
    fun toggle() = player.toggle()
    fun next() = player.next()
    fun previous() = player.previous()
    fun seek(position: Long) = player.seek(position)
    fun favorite(id: Long) = viewModelScope.launch { repo.toggleFavorite(id) }
    fun createPlaylist(name: String) = viewModelScope.launch { if (name.isNotBlank()) repo.createPlaylist(name.trim()) }
    fun deletePlaylist(item: PlaylistEntity) = viewModelScope.launch { repo.deletePlaylist(item) }
    fun addToPlaylist(playlistId: Long, songId: Long) = viewModelScope.launch { repo.addToPlaylist(playlistId, songId) }
    fun playlistSongs(id: Long) = repo.playlistSongs(id)
    fun removeFromPlaylist(playlistId: Long, songId: Long) = viewModelScope.launch { repo.removeFromPlaylist(playlistId, songId) }
    fun renamePlaylist(id: Long, name: String) = viewModelScope.launch { if (name.isNotBlank()) repo.renamePlaylist(id, name) }
    override fun onCleared() { player.release() }

    companion object { fun factory(repo: MusicRepository, player: PlayerController, preferences: life.xstar.music.player.data.preferences.PlaybackPreferences) = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(type: Class<T>): T = MusicViewModel(repo, player, preferences) as T
    } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun XstarApp(vm: MusicViewModel) {
    var tab by remember { mutableIntStateOf(0) }
    var showPlayer by remember { mutableStateOf(false) }
    var selectedPlaylist by remember { mutableStateOf<PlaylistEntity?>(null) }
    val playback by vm.playback.collectAsStateWithLifecycle()
    Scaffold(bottomBar = { NavigationBar {
        listOf("音乐库" to Icons.Default.LibraryMusic, "收藏" to Icons.Default.Favorite, "播放列表" to Icons.Default.QueueMusic, "设置" to Icons.Default.Settings).forEachIndexed { i, item ->
            NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(item.second, item.first) }, label = { Text(item.first) })
        }
    } }, topBar = { TopAppBar(title = { Text("XSTAR") }, actions = { IconButton(onClick = vm::scan) { Icon(Icons.Default.Refresh, "重新扫描") } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when (tab) { 0 -> LibraryPage(vm); 1 -> SongList(vm, vm.favorites.collectAsStateWithLifecycle().value, "还没有收藏歌曲"); 2 -> PlaylistPage(vm) { selectedPlaylist = it }; 3 -> SettingsPage(vm) }
            if (playback.song != null) MiniPlayer(playback, { showPlayer = true }, vm::toggle)
        }
    }
    if (showPlayer) PlayerPage(playback, vm) { showPlayer = false }
    selectedPlaylist?.let { playlist -> PlaylistDetail(vm, playlist) { selectedPlaylist = null } }
}

@Composable private fun LibraryPage(vm: MusicViewModel) {
    val context = LocalContext.current
    val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val songs by vm.songs.collectAsStateWithLifecycle()
    val loading by vm.scanning.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    LaunchedEffect(granted) { if (granted && songs.isEmpty()) vm.scan() }
    var mode by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(query, { vm.query.value = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("搜索歌曲、艺术家或专辑") })
        if (!granted) EmptyState("需要音乐权限", "XSTAR 只读取设备媒体库，不会上传文件。", "授权并扫描") { request.launch(permission) }
        else if (loading) Box(Modifier.fillMaxWidth().padding(40.dp), Alignment.Center) { CircularProgressIndicator() }
        else {
            TabRow(selectedTabIndex = mode) { listOf("歌曲", "专辑", "艺术家").forEachIndexed { index, label -> Tab(selected = mode == index, onClick = { mode = index }, text = { Text(label) }) } }
            val filtered = songs.filter { query.isBlank() || listOf(it.title, it.artist, it.album).any { value -> value.contains(query, true) } }
            when (mode) {
                0 -> SongList(vm, filtered, "还没有本地音乐")
                1 -> GroupList(filtered.groupBy { it.album }, vm)
                else -> GroupList(filtered.groupBy { it.artist }, vm)
            }
        }
    }
}

@Composable private fun SongList(vm: MusicViewModel, songs: List<SongEntity>, emptyText: String) {
    if (songs.isEmpty()) EmptyState(emptyText, "扫描设备中的本地音乐后开始播放。", "重新扫描", vm::scan)
    else LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 8.dp)) { items(songs, key = { it.mediaStoreId }) { song -> SongRow(song, { vm.play(song, songs) }, { vm.favorite(song.mediaStoreId) }) } }
}

@Composable private fun GroupList(groups: Map<String, List<SongEntity>>, vm: MusicViewModel) {
    if (groups.isEmpty()) EmptyState("暂无内容", "扫描音乐库后这里会显示分类。", "重新扫描", vm::scan)
    else LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 8.dp)) { groups.toList().forEach { (name, songs) -> item(key = name) { ListItem(headlineContent = { Text(name) }, supportingContent = { Text("${songs.size} 首歌曲") }, trailingContent = { IconButton(onClick = { vm.play(songs.first(), songs) }) { Icon(Icons.Default.PlayArrow, "播放") } }) } } }
}

@Composable private fun SongRow(song: SongEntity, onPlay: () -> Unit, onFavorite: () -> Unit) { Row(Modifier.fillMaxWidth().clickable(onClick = onPlay).padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium); Text("${song.artist} · ${song.album}", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) }; Text(duration(song.durationMs)); IconButton(onClick = onFavorite) { Icon(if (song.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "收藏") } } }

@Composable private fun MiniPlayer(state: PlayerState, open: () -> Unit, toggle: () -> Unit) { Row(Modifier.fillMaxWidth().clickable(onClick = open).padding(10.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(state.song?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis); Text(state.song?.artist.orEmpty(), style = MaterialTheme.typography.bodySmall) }; IconButton(onClick = toggle) { Icon(if (state.playing) Icons.Default.Pause else Icons.Default.PlayArrow, "播放") } } }

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun PlayerPage(state: PlayerState, vm: MusicViewModel, close: () -> Unit) { ModalBottomSheet(onDismissRequest = close) { Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text(state.song?.title ?: "未播放", style = MaterialTheme.typography.headlineSmall); Text(state.song?.artist.orEmpty()); Slider(value = if (state.duration > 0) state.position.toFloat() / state.duration else 0f, onValueChange = { vm.seek((it * state.duration).toLong()) }); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) { IconButton(onClick = vm::previous) { Icon(Icons.Default.SkipPrevious, "上一首") }; FilledIconButton(onClick = vm::toggle) { Icon(if (state.playing) Icons.Default.Pause else Icons.Default.PlayArrow, "播放") }; IconButton(onClick = vm::next) { Icon(Icons.Default.SkipNext, "下一首") } }; Text("${duration(state.position)} / ${duration(state.duration)}"); Text("播放队列", style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.Start).padding(top = 20.dp)); LazyColumn(Modifier.heightIn(max = 180.dp)) { items(state.queue, key = { it.mediaStoreId }) { song -> ListItem(headlineContent = { Text(song.title) }, supportingContent = { Text(song.artist) }, trailingContent = { IconButton(onClick = { vm.player.remove(state.queue.indexOf(song)) }) { Icon(Icons.Default.Close, "从队列移除") } }) } } } } }

@Composable private fun PlaylistPage(vm: MusicViewModel, open: (PlaylistEntity) -> Unit) { val lists by vm.playlists.collectAsStateWithLifecycle(); var name by remember { mutableStateOf("") }; Column(Modifier.padding(16.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { OutlinedTextField(name, { name = it }, Modifier.weight(1f), label = { Text("新播放列表") }); IconButton(onClick = { vm.createPlaylist(name); name = "" }) { Icon(Icons.Default.Add, "创建") } }; LazyColumn { items(lists, key = { it.id }) { list -> ListItem(modifier = Modifier.clickable { open(list) }, headlineContent = { Text(list.name) }, supportingContent = { Text("点击查看歌曲") }, trailingContent = { IconButton(onClick = { vm.deletePlaylist(list) }) { Icon(Icons.Default.Delete, "删除") } }) } } } }

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun PlaylistDetail(vm: MusicViewModel, playlist: PlaylistEntity, close: () -> Unit) {
    val entries by vm.playlistSongs(playlist.id).collectAsStateWithLifecycle(emptyList())
    val songs by vm.songs.collectAsStateWithLifecycle()
    val items = entries.mapNotNull { entry -> songs.find { it.mediaStoreId == entry.songId } }
    var newName by remember { mutableStateOf(playlist.name) }
    var picking by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = close) { Column(Modifier.fillMaxWidth().padding(16.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { OutlinedTextField(newName, { newName = it }, Modifier.weight(1f), label = { Text("播放列表名称") }); IconButton(onClick = { vm.renamePlaylist(playlist.id, newName) }) { Icon(Icons.Default.Check, "保存") } }; OutlinedButton(onClick = { picking = true }) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("添加歌曲") }; if (items.isEmpty()) Text("播放列表还是空的，请从歌曲操作中加入音乐。", Modifier.padding(vertical = 20.dp)) else LazyColumn { items(items, key = { it.mediaStoreId }) { song -> ListItem(headlineContent = { Text(song.title) }, supportingContent = { Text(song.artist) }, trailingContent = { IconButton(onClick = { vm.removeFromPlaylist(playlist.id, song.mediaStoreId) }) { Icon(Icons.Default.RemoveCircleOutline, "移除") } }, modifier = Modifier.clickable { vm.play(song, items) }) } } } }
    if (picking) AlertDialog(onDismissRequest = { picking = false }, title = { Text("选择歌曲") }, text = { LazyColumn { items(songs.filterNot { item -> items.any { it.mediaStoreId == item.mediaStoreId } }, key = { it.mediaStoreId }) { song -> ListItem(headlineContent = { Text(song.title) }, supportingContent = { Text(song.artist) }, modifier = Modifier.clickable { vm.addToPlaylist(playlist.id, song.mediaStoreId); picking = false }) } } }, confirmButton = { TextButton(onClick = { picking = false }) { Text("关闭") } })

@Composable private fun SettingsPage(vm: MusicViewModel) { Column(Modifier.padding(16.dp)) { Text("设置", style = MaterialTheme.typography.headlineMedium); Spacer(Modifier.height(16.dp)); ListItem(headlineContent = { Text("重新扫描音乐库") }, supportingContent = { Text("从 Android MediaStore 更新本地索引") }, trailingContent = { IconButton(onClick = vm::scan) { Icon(Icons.Default.Refresh, "扫描") } }); ListItem(headlineContent = { Text("关于 XSTAR") }, supportingContent = { Text("本地音乐播放器 · 0.1.0") }) } }

@Composable private fun EmptyState(title: String, body: String, action: String, onClick: () -> Unit) { Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text(title, style = MaterialTheme.typography.titleLarge); Text(body, Modifier.padding(vertical = 12.dp)); Button(onClick) { Text(action) } } }
private fun duration(ms: Long) = "%d:%02d".format(ms / 60000, (ms / 1000) % 60)
@Composable private fun XstarTheme(content: @Composable () -> Unit) { MaterialTheme(content = content) }
