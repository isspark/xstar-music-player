package life.xstar.music.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import life.xstar.music.player.data.local.SongEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

private object LibrarySortKeys {
    private val cache = ConcurrentHashMap<String, String>()
    private val lock = Mutex()
    private var restored = false
    suspend fun load(context: Context, names: List<String>): Map<String, String> = lock.withLock {
        withContext(Dispatchers.Default) {
        val file = AtomicFile(File(context.filesDir, "library-sort-keys-v1.json"))
        if (!restored) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val json = file.openRead().bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
                    json.keys().forEach { name -> cache[name] = json.getString(name) }
                }
            }
            restored = true
        }
        // ICU loads a large rule set on first use; never initialize it on the UI thread.
        val missing = names.filterNot(cache::containsKey)
        if (missing.isNotEmpty()) {
            val transliterator = if (android.os.Build.VERSION.SDK_INT >= 29) android.icu.text.Transliterator.getInstance("Han-Latin; Latin-ASCII; Upper") else null
            missing.forEach { name ->
                ensureActive()
                cache[name] = if (android.os.Build.VERSION.SDK_INT >= 29) transliterator!!.transliterate(name.trim()).trim() else name.trim().uppercase()
            }
            // Persist before publishing the finished index so process death cannot lose it.
            withContext(Dispatchers.IO) {
                val bytes = JSONObject(cache.toMap()).toString().toByteArray(Charsets.UTF_8)
                var output: java.io.FileOutputStream? = null
                try {
                    output = file.startWrite()
                    output.write(bytes)
                    file.finishWrite(output)
                } catch (_: Exception) {
                    output?.let { file.failWrite(it) }
                }
            }
        }
        names.associateWith { cache[it].orEmpty() }
        }
    }
}

private data class LibraryIndex(
    val songs: List<SongEntity> = emptyList(),
    val albums: Map<String, List<SongEntity>> = emptyMap(),
    val artists: Map<String, List<SongEntity>> = emptyMap(),
    val keys: Map<String, String> = emptyMap()
)

private object LibraryIndexCache {
    var source: List<SongEntity>? = null
    var query: String = ""
    var index = LibraryIndex()
}

private fun libraryInitial(key: String?): String = key?.firstOrNull()?.takeIf { it in 'A'..'Z' }?.toString() ?: "#"

@Composable
internal fun LibraryEditorialContent(
    songs: List<SongEntity>, query: String, onQueryChange: (String) -> Unit,
    scanState: ScanState, scanEnabled: Boolean, currentSongId: Long?, playing: Boolean,
    openMenu: () -> Unit, addFolder: () -> Unit, onScan: () -> Unit,
    onPlay: (SongEntity, List<SongEntity>) -> Unit, onFavorite: (Long) -> Unit,
    onAlbum: (String, List<SongEntity>) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current.applicationContext
    val nasRepository = (context as XstarApplication).nasRepository
    val nasStatus by nasRepository.status.collectAsState()
    var group by rememberSaveable { mutableIntStateOf(0) }
    var artist by rememberSaveable { mutableStateOf<String?>(null) }
    var album by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val index by produceState(
        if (LibraryIndexCache.source === songs && LibraryIndexCache.query == query) LibraryIndexCache.index else LibraryIndex(), songs, query
    ) {
        value = withContext(Dispatchers.Default) {
            if (LibraryIndexCache.source === songs && LibraryIndexCache.query == query) return@withContext LibraryIndexCache.index
            val keys = LibrarySortKeys.load(context, songs.flatMap { listOf(it.title, it.album, it.artist) }.distinct())
            val filtered = songs.filter { query.isBlank() || "${it.title} ${it.artist} ${it.album} ${it.fileName.orEmpty()}".contains(query, true) }
                .sortedWith(compareBy<SongEntity> { keys[it.title]?.firstOrNull() !in 'A'..'Z' }.thenBy { keys[it.title] })
            fun groups(key: (SongEntity) -> String) = filtered.groupBy(key).toSortedMap(
                compareBy<String> { keys[it]?.firstOrNull() !in 'A'..'Z' }.thenBy { keys[it] }.thenBy { it })
            LibraryIndex(filtered, groups { it.album }, groups { it.artist }, keys)
        }
        LibraryIndexCache.source = songs
        LibraryIndexCache.query = query
        LibraryIndexCache.index = value
    }
    val sortKeys = index.keys
    val filtered = index.songs
    val albums = index.albums
    val artists = index.artists
    val showingSongs = group == 0 || artist != null || album != null
    val visibleSongs = when {
        group == 2 && artist != null -> artists[artist].orEmpty()
        group == 1 && album != null -> albums[album].orEmpty()
        else -> filtered
    }
    val entries = remember(index, group) {
        (if (group == 1) albums else artists).entries.toList()
    }
    val names = remember(visibleSongs, entries, showingSongs) {
        if (showingSongs) visibleSongs.map { it.title } else entries.map { it.key }
    }
    val sectionPositions by produceState<Map<String, Int>>(emptyMap(), names, sortKeys) {
        value = withContext(Dispatchers.Default) {
            buildMap { names.forEachIndexed { position, name -> putIfAbsent(libraryInitial(sortKeys[name]), position) } }
        }
    }
    val headerCount = if (scanState.running || scanState.message != null) 1 else 0
    var touchedLetter by remember { mutableStateOf<String?>(null) }
    val currentLetter by remember(names, headerCount, sortKeys) { derivedStateOf {
        names.getOrNull((listState.firstVisibleItemIndex - headerCount).coerceAtLeast(0))?.let { libraryInitial(sortKeys[it]) }
    } }
    LaunchedEffect(group, artist, album, query) { listState.scrollToItem(0) }
    androidx.activity.compose.BackHandler(artist != null || album != null) { artist = null; album = null }
    CompositionLocalProvider(LocalContentColor provides colors.onBackground) {
        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
            PageTopBar(artist ?: album ?: "音乐库", { if (artist != null || album != null) { artist = null; album = null } else openMenu() },
                back = artist != null || album != null) {
                IconButton(addFolder, enabled = !scanState.running) { Icon(Icons.Default.CreateNewFolder, "添加音乐文件夹") }
                IconButton(onScan, enabled = scanEnabled) { Icon(Icons.Default.Refresh, "扫描音乐库", tint = if (scanEnabled) colors.primary else colors.onSurfaceVariant.copy(alpha = .4f)) }
            }
            PageSearchField(query, { onQueryChange(it) })
            NasSyncBanner(nasStatus,
                { life.xstar.music.player.data.nas.NasSyncService.pause(context) },
                { life.xstar.music.player.data.nas.NasSyncService.start(context) })
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("歌曲" to filtered.size, "专辑" to albums.size, "歌手" to artists.size).forEachIndexed { position, (label, count) ->
                    FilterChip(group == position, { group = position; artist = null; album = null }, label = { Text("$label $count") })
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(22.dp, 14.dp, 34.dp, 32.dp)) {
                if (scanState.running || scanState.message != null) item {
                    Column(Modifier.padding(bottom = 18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (scanState.failed) Icons.Default.ErrorOutline else Icons.Default.Radar, null, Modifier.size(16.dp),
                                tint = if (scanState.failed) colors.error else colors.primary)
                            Text(if (scanState.running) "正在扫描 · ${scanState.discovered} 首" else scanState.message.orEmpty(),
                                Modifier.padding(start = 8.dp), color = if (scanState.failed) colors.error else colors.onSurfaceVariant,
                                fontSize = 12.sp)
                        }
                        if (scanState.running) {
                            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 10.dp).height(2.dp))
                            scanState.currentFile?.let { Text(it, Modifier.padding(top = 6.dp), color = colors.onSurfaceVariant,
                                fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                }
                if (filtered.isEmpty()) item {
                    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp)) {
                        val preparing = songs.isNotEmpty() && index.keys.isEmpty()
                        Text(if (preparing) "正在整理音乐索引" else if (scanState.running) "正在建立音乐库" else if (query.isNotBlank()) "没有找到相关音乐" else "你的音乐故事，从这里开始", fontSize = 19.sp)
                        Text(if (preparing) "歌曲列表即将呈现，页面可正常操作。" else if (query.isNotBlank()) "试试其他歌名、歌手或专辑。" else "添加音乐文件夹，把喜欢的声音收进来。",
                            color = colors.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
                        if (query.isBlank() && !preparing) TextButton(addFolder, enabled = !scanState.running, contentPadding = PaddingValues(vertical = 12.dp)) {
                            Text("添加音乐文件夹"); Spacer(Modifier.width(8.dp)); Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp))
                        }
                    }
                } else if (showingSongs) {
                    itemsIndexed(visibleSongs, key = { _, song -> song.mediaStoreId }) { position, song ->
                        LibrarySongRow(song, position + 1, song.mediaStoreId == currentSongId, playing,
                            onPlay = { onPlay(song, visibleSongs) }, onFavorite = { onFavorite(song.mediaStoreId) })
                    }
                } else {
                    itemsIndexed(entries, key = { _, entry -> entry.key }) { _, entry ->
                        LibraryGroupRow(entry.key,
                            if (group == 1) "${entry.value.size} 首歌曲 · ${entry.value.map { it.artist }.distinct().joinToString(" / ")}" else "${entry.value.size} 首歌曲 · ${entry.value.map { it.album }.distinct().size} 张专辑",
                            artwork = entry.value.firstOrNull { !it.albumArtUri.isNullOrBlank() }?.albumArtUri,
                            onOpen = { if (group == 1) album = entry.key else artist = entry.key },
                            onPlay = { onPlay(entry.value.first(), entry.value) })
                    }
                }
                item { HorizontalDivider(color = colors.outlineVariant.copy(alpha = .6f)) }
            }
            if (names.isNotEmpty() && sortKeys.isNotEmpty()) {
                LibraryAlphabetRail(sectionPositions.keys, touchedLetter ?: currentLetter,
                    Modifier.align(Alignment.CenterEnd).padding(vertical = 8.dp),
                    onSelect = { letter ->
                        touchedLetter = letter
                        sectionPositions[letter]?.let { listState.requestScrollToItem(headerCount + it) }
                    }, onRelease = { touchedLetter = null })
                touchedLetter?.let { letter ->
                    Surface(Modifier.align(Alignment.Center).size(64.dp), shape = RoundedCornerShape(12.dp),
                        color = colors.primary, contentColor = colors.onPrimary) {
                        Box(contentAlignment = Alignment.Center) { Text(letter, fontSize = 32.sp, fontWeight = FontWeight.Medium) }
                    }
                }
            }
            }
        }
    }
}

@Composable
private fun LibraryAlphabetRail(available: Set<String>, selected: String?, modifier: Modifier,
    onSelect: (String) -> Unit, onRelease: () -> Unit) {
    val letters = remember { ('A'..'Z').map { it.toString() } + "#" }
    val colors = MaterialTheme.colorScheme
    var railHeight by remember { mutableIntStateOf(1) }
    val select by rememberUpdatedState(onSelect)
    val release by rememberUpdatedState(onRelease)
    val active by rememberUpdatedState(available)
    Column(modifier.width(28.dp).fillMaxHeight(.92f).heightIn(max = 486.dp)
        .onSizeChanged { railHeight = it.height.coerceAtLeast(1) }
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                fun locate(y: Float) {
                    val letter = letters[(y / railHeight * letters.size).toInt().coerceIn(0, letters.lastIndex)]
                    if (letter in active) select(letter)
                }
                down.consume()
                locate(down.position.y)
                try {
                    do {
                        val event = awaitPointerEvent()
                        val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                        pointer.consume()
                        if (pointer.pressed) locate(pointer.position.y)
                    } while (event.changes.any { it.id == down.id && it.pressed })
                } finally { release() }
            }
        }, horizontalAlignment = Alignment.CenterHorizontally) {
        letters.forEach { letter ->
            Box(Modifier.weight(1f).fillMaxWidth().semantics { contentDescription = "快速定位 $letter" }
                .clickable(enabled = letter in available) { onSelect(letter); onRelease() }, contentAlignment = Alignment.Center) {
                Text(letter, fontSize = 10.sp, lineHeight = 11.sp,
                    fontWeight = if (selected == letter) FontWeight.Bold else FontWeight.Normal,
                    color = when {
                        selected == letter -> colors.primary
                        letter in available -> colors.onSurfaceVariant
                        else -> colors.onSurfaceVariant.copy(alpha = .22f)
                    })
            }
        }
    }
}

@Composable
private fun EditorialLibraryRow(title: String, subtitle: String, artwork: String?, avatar: Boolean = false, selected: Boolean = false,
    playing: Boolean = false, onClick: () -> Unit, badge: @Composable () -> Unit = {}, action: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column {
        HorizontalDivider(color = colors.outlineVariant.copy(alpha = .6f))
        Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(if (avatar) CircleShape else RoundedCornerShape(8.dp))
                .background(colors.primary.copy(alpha = .09f)), contentAlignment = Alignment.Center) {
                val fallback: @Composable () -> Unit = {
                    Icon(if (avatar) Icons.Default.Person else Icons.Default.Album, null, Modifier.size(24.dp), tint = colors.primary.copy(alpha = .7f))
                }
                if (artwork.isNullOrBlank()) fallback()
                else {
                    fallback()
                    AsyncImage(model = if (artwork.contains("://")) artwork else java.io.File(artwork),
                        contentDescription = "${title}封面", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
                if (selected && playing) Box(Modifier.fillMaxSize().background(colors.background.copy(alpha = .55f)),
                    contentAlignment = Alignment.Center) { Icon(Icons.Default.GraphicEq, "正在播放", Modifier.size(22.dp), tint = colors.primary) }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f, fill = false), color = if (selected) colors.primary else colors.onBackground,
                        fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.width(6.dp)); badge()
                }
                Text(subtitle, color = colors.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 5.dp))
            }
            action()
        }
    }
}
