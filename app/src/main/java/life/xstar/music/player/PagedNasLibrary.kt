package life.xstar.music.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.*
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import life.xstar.music.player.data.nas.NasStatus

/** All large-library filtering/grouping stays in SQLite; only a few pages reach the UI. */
@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Composable
internal fun PagedNasLibrary(vm: MusicViewModel, total: Int, openMenu: () -> Unit, addFolder: () -> Unit) {
    val dao = (LocalContext.current.applicationContext as XstarApplication).database.songDao()
    var query by rememberSaveable { mutableStateOf("") }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var selectedArtist by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedAlbum by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val songFlow = remember(selectedArtist, selectedAlbum) {
        snapshotFlow { query }.debounce(300).distinctUntilChanged().flatMapLatest { text ->
            Pager(PagingConfig(pageSize = 100, initialLoadSize = 100, maxSize = 500)) {
                dao.pagedSongs(text.trim(), selectedArtist, selectedAlbum)
            }.flow
        }.cachedIn(scope)
    }
    val groupFlow = remember(tab) {
        snapshotFlow { query }.debounce(300).distinctUntilChanged().flatMapLatest { text ->
            Pager(PagingConfig(pageSize = 60, initialLoadSize = 60, maxSize = 300)) {
                if (tab == 1) dao.pagedAlbums(text.trim()) else dao.pagedArtists(text.trim())
            }.flow
        }.cachedIn(scope)
    }
    val songs = songFlow.collectAsLazyPagingItems()
    val groups = groupFlow.collectAsLazyPagingItems()
    val status by vm.nasStatus.collectAsStateWithLifecycle()
    val config by vm.nasConfig.collectAsStateWithLifecycle()
    val playback by vm.playback.collectAsStateWithLifecycle()
    val showingSongs = tab == 0 || selectedArtist != null || selectedAlbum != null
    fun back() { selectedArtist = null; selectedAlbum = null }
    androidx.activity.compose.BackHandler(selectedArtist != null || selectedAlbum != null) { back() }
    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
        PageTopBar(selectedArtist ?: selectedAlbum ?: "音乐库", if (selectedArtist != null || selectedAlbum != null) ({ back() }) else openMenu,
            back = selectedArtist != null || selectedAlbum != null) {
            IconButton(addFolder) { Icon(Icons.Default.CreateNewFolder, "添加音乐文件夹") }
            IconButton({ vm.syncNas(config) }, enabled = !status.busy && config.enabled && config.address.isNotBlank()) {
                Icon(Icons.Default.Sync, "同步 NAS")
            }
        }
        NasSyncBanner(status, vm::pauseNasSync, { vm.syncNas(config) })
        PageSearchField(query, { query = it })
        PageSubtitle("共 $total 首歌曲")
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("歌曲", "专辑", "歌手").forEachIndexed { index, name ->
                FilterChip(tab == index, { tab = index; back() }, label = { Text(name) })
            }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            if (showingSongs) {
                items(songs.itemCount, key = songs.itemKey { it.mediaStoreId }) { index ->
                    val song = songs[index]
                    if (song == null) Text("正在加载…", Modifier.padding(16.dp)) else {
                        LibrarySongRow(song, index + 1, playback.song?.mediaStoreId == song.mediaStoreId, playback.playing,
                            onPlay = {
                                if (selectedArtist != null || selectedAlbum != null) vm.playLibraryGroup(selectedArtist, selectedAlbum, query, song)
                                else vm.play(song, songs.itemSnapshotList.items)
                            }, onFavorite = { vm.favorite(song.mediaStoreId) })
                    }
                }
            } else {
                items(groups.itemCount, key = groups.itemKey { it.name }) { index ->
                    groups[index]?.let { group ->
                        LibraryGroupRow(group.name, "${group.count} 首歌曲", group.artwork,
                            onOpen = { if (tab == 1) selectedAlbum = group.name else selectedArtist = group.name },
                            onPlay = { vm.playLibraryGroup(artist = if (tab == 2) group.name else null, album = if (tab == 1) group.name else null, query = query) })
                    }
                }
            }
            val load = if (showingSongs) songs.loadState.refresh else groups.loadState.refresh
            if (load is LoadState.Loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (load is LoadState.Error) item { TextButton({ if (showingSongs) songs.retry() else groups.retry() }) { Text("加载失败，点击重试") } }
            if (load is LoadState.NotLoading && (if (showingSongs) songs.itemCount else groups.itemCount) == 0) item {
                Text(if (status.busy) "曲库正在后台建立，歌曲入库后会自动显示" else "没有找到相关音乐", Modifier.padding(20.dp))
            }
        }
    }
}

@Composable
internal fun NasSyncBanner(status: NasStatus, pause: () -> Unit, resume: () -> Unit) {
    if (status.message.isBlank() || (!status.busy && !status.resumable)) return
    Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(status.message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                if (status.busy) TextButton(pause) { Text("暂停") }
                else if (status.resumable) TextButton(resume) { Text("继续") }
            }
            if (status.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}
