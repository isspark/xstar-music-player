package life.xstar.music.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import life.xstar.music.player.data.online.OnlineResolvedAudio
import life.xstar.music.player.data.local.SongEntity
import life.xstar.music.player.data.online.OnlineDownloadState
import life.xstar.music.player.data.online.OnlineTrack
import life.xstar.music.player.data.online.platformLabel

@Composable
internal fun OnlineMusicContent(
    enabled: Boolean,
    state: OnlineSearchUiState,
    downloads: Map<String, OnlineDownloadState>,
    openMenu: () -> Unit,
    onSearch: (String) -> Unit,
    onClearSearch: () -> Unit,
    audioInfo: suspend (OnlineTrack) -> OnlineResolvedAudio?,
    onDownload: (OnlineTrack) -> Unit,
    onCancel: (String) -> Unit,
    onPlay: (OnlineTrack) -> Unit,
    onPlayNas: (SongEntity) -> Unit,
    onDownloadNas: (SongEntity) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    var query by rememberSaveable { mutableStateOf((state as? OnlineSearchUiState.Results)?.query.orEmpty()) }
    val keyboard = LocalSoftwareKeyboardController.current
    var searchFocused by remember { mutableStateOf(false) }
    fun search(keyword: String = query) {
        if (!enabled || keyword.isBlank()) return
        query = keyword
        keyboard?.hide()
        onSearch(keyword.trim())
    }
    val activeDownloads = downloads.values.count { it is OnlineDownloadState.DownloadingPercent }
    CompositionLocalProvider(LocalContentColor provides colors.onBackground) {
        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(60.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(openMenu) { Icon(Icons.Default.Menu, "菜单") }
                Text("在线音乐", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
                Surface(shape = CircleShape, color = colors.primary.copy(alpha = .1f)) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Icon(if (activeDownloads > 0) Icons.Default.Downloading else Icons.Default.GraphicEq,
                            null, Modifier.size(14.dp), tint = colors.primary)
                        Text(if (activeDownloads > 0) "$activeDownloads 首下载中" else "随心听见", color = colors.primary,
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
                Spacer(Modifier.width(8.dp))
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(top = 10.dp, bottom = 8.dp)) {
                Text("SEARCH / 探索音乐", color = colors.onSurfaceVariant, fontSize = 9.sp, letterSpacing = 1.5.sp)
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Search, null, Modifier.size(20.dp), tint = colors.primary)
                    Spacer(Modifier.width(12.dp))
                    BasicTextField(
                value = query,
                onValueChange = { query = it; if (it.isBlank()) onClearSearch() },
                enabled = enabled,
                modifier = Modifier.weight(1f).onFocusChanged { searchFocused = it.isFocused },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = if (enabled) colors.onBackground else colors.onSurfaceVariant),
                cursorBrush = SolidColor(colors.primary),
                decorationBox = { input ->
                    Box {
                        if (query.isEmpty()) Text("搜索歌曲、歌手或专辑", color = colors.onSurfaceVariant.copy(alpha = .65f),
                            style = MaterialTheme.typography.bodyMedium)
                        input()
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search() })
                    )
                        if (query.isNotEmpty()) IconButton({ query = ""; onClearSearch() }) {
                            Icon(Icons.Default.Close, "清空搜索", Modifier.size(18.dp))
                        }
                        IconButton({ search() }, enabled = enabled && query.isNotBlank()) {
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, "搜索")
                        }
                }
                HorizontalDivider(color = if (searchFocused) colors.primary.copy(alpha = .8f)
                    else colors.outlineVariant, thickness = if (searchFocused) 1.5.dp else 1.dp)
            }
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 14.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                when {
                    !enabled -> item {
                        OnlineMessage(Icons.Default.CloudOff, "让好音乐，再次相遇", "在线搜歌已关闭，可在设置中开启后搜索与下载。")
                    }
                    state == OnlineSearchUiState.Idle -> {
                        item { OnlineDiscoveryHero() }
                        item {
                            Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.Bottom) {
                                Column(Modifier.weight(1f)) {
                                    Text("ARTIST INDEX", color = colors.primary, fontSize = 10.sp,
                                        letterSpacing = 2.sp, fontWeight = FontWeight.SemiBold)
                                    Text("从熟悉的声音开始", fontSize = 19.sp, fontWeight = FontWeight.Medium,
                                        modifier = Modifier.padding(top = 7.dp))
                                }
                                Text("01 — 06", color = colors.onSurfaceVariant, fontSize = 10.sp, letterSpacing = 1.sp)
                            }
                        }
                        val artists = listOf("周杰伦" to "JAY CHOU", "邓紫棋" to "G.E.M.",
                            "陈奕迅" to "EASON CHAN", "林俊杰" to "JJ LIN",
                            "五月天" to "MAYDAY", "孙燕姿" to "STEFANIE SUN")
                        items(artists.size, key = { "artist:$it" }) { index ->
                            val (name, english) = artists[index]
                            Column {
                                HorizontalDivider(color = colors.outlineVariant.copy(alpha = .6f))
                                Row(Modifier.fillMaxWidth().clickable { search(name) }
                                    .padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("0${index + 1}", color = colors.primary, fontSize = 11.sp,
                                        letterSpacing = 1.sp, modifier = Modifier.width(34.dp))
                                    Text(name, fontSize = 19.sp, fontWeight = FontWeight.Medium,
                                        modifier = Modifier.weight(1f))
                                    Text(english, fontSize = 9.sp, letterSpacing = 1.sp,
                                        color = colors.onSurfaceVariant, modifier = Modifier.padding(end = 12.dp))
                                    Icon(Icons.AutoMirrored.Filled.ArrowForward, "搜索$name",
                                        Modifier.size(18.dp), tint = colors.primary)
                                }
                            }
                        }
                        item {
                            Column {
                                HorizontalDivider(color = colors.outlineVariant.copy(alpha = .6f))
                                Row(Modifier.fillMaxWidth().padding(top = 22.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("XSTAR / MUSIC FOR LIFE", color = colors.onSurfaceVariant,
                                        fontSize = 9.sp, letterSpacing = 1.sp)
                                    Text("即搜即听 · 离线随行", color = colors.onSurfaceVariant, fontSize = 10.sp)
                                }
                            }
                        }
                    }
                    state == OnlineSearchUiState.Searching -> item {
                        Column(Modifier.fillMaxWidth().padding(vertical = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 2.dp)
                            Text("正在寻找好声音", modifier = Modifier.padding(top = 20.dp), fontWeight = FontWeight.Medium)
                            Text("搜索在线音乐与 NAS 曲库", color = colors.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                    state is OnlineSearchUiState.Error -> item {
                        OnlineMessage(Icons.Default.SearchOff, "暂时没有找到好声音", state.message) {
                            FilledTonalButton(onClick = { search() }, enabled = query.isNotBlank()) {
                                Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("重新搜索")
                            }
                        }
                    }
                    state is OnlineSearchUiState.Results -> {
                        val tracks = state.tracks
                        val nasSongs = state.nasSongs
                        item {
                            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("TRACK INDEX", color = colors.primary, fontSize = 10.sp,
                                        letterSpacing = 2.sp, fontWeight = FontWeight.SemiBold)
                                    Text("“${state.query}”", maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        fontSize = 26.sp, fontWeight = FontWeight.Medium,
                                        modifier = Modifier.padding(top = 9.dp, bottom = 10.dp))
                                }
                                Text("${nasSongs.size + tracks.size} 首", color = colors.primary,
                                    fontSize = 11.sp, letterSpacing = 1.sp)
                            }
                        }
                        itemsIndexed(nasSongs, key = { _, song -> "nas:${song.mediaStoreId}" }) { index, song ->
                            OnlineSongRow(song.title, song.artist, song.album, "NAS", number = index + 1,
                                quality = songQualityLabel(song), onPlay = { onPlayNas(song) }) {
                                IconButton({ onDownloadNas(song) }) {
                                    Icon(Icons.Default.Download, "下载到手机", tint = colors.primary)
                                }
                            }
                        }
                        itemsIndexed(tracks, key = { _, track -> "online:${track.onlineId}" }) { index, track ->
                            val download = downloads[track.onlineId]
                            val quality by produceState("音质获取中", track.onlineId) {
                                value = try {
                                    audioInfo(track)?.let(::onlineQualityLabel) ?: "音质待确认"
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (_: Exception) {
                                    "音质待确认"
                                }
                            }
                            OnlineSongRow(track.title, track.artist, track.album, platformLabel(track.platform),
                                number = nasSongs.size + index + 1,
                                quality = quality, download = download, onPlay = { onPlay(track) }) {
                                when (download) {
                                    is OnlineDownloadState.DownloadingPercent -> {
                                        Box(contentAlignment = Alignment.Center) {
                                            if (download.percent == null) CircularProgressIndicator(Modifier.size(38.dp), strokeWidth = 2.dp)
                                            else CircularProgressIndicator(progress = { download.percent.coerceIn(0, 100) / 100f },
                                                modifier = Modifier.size(38.dp), strokeWidth = 2.dp)
                                            IconButton({ onCancel(track.onlineId) }) {
                                                Icon(Icons.Default.Close, "取消下载", Modifier.size(17.dp), tint = colors.primary)
                                            }
                                        }
                                    }
                                    OnlineDownloadState.Done -> IconButton({ onPlay(track) }) {
                                        Icon(Icons.Default.CheckCircle, "已下载，点击播放", tint = colors.primary)
                                    }
                                    is OnlineDownloadState.Failed -> IconButton({ onDownload(track) }) {
                                        Icon(Icons.Default.Refresh, "下载失败，点击重试", tint = colors.error)
                                    }
                                    null -> IconButton({ onDownload(track) }) {
                                        Icon(Icons.Default.Download, "下载", tint = colors.primary)
                                    }
                                }
                            }
                        }
                        item { HorizontalDivider(color = colors.outlineVariant.copy(alpha = .6f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun OnlineDiscoveryHero() {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth().clipToBounds()) {
        Canvas(Modifier.matchParentSize()) {
            val radius = size.width * .48f
            val center = Offset(size.width * 1.12f, size.height * .48f)
            drawCircle(colors.primary.copy(alpha = .055f), radius, center)
            repeat(12) { index ->
                drawCircle(colors.primary.copy(alpha = .12f), radius * (.42f + index * .048f),
                    center, style = Stroke(if (index == 11) 2.dp.toPx() else .7.dp.toPx()))
            }
            drawCircle(colors.primary.copy(alpha = .16f), radius * .3f, center)
            drawCircle(colors.background, radius * .055f, center)
        }
        Column(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Box(Modifier.width(22.dp).height(2.dp).background(colors.primary))
                Text("THE SOUND EDIT", color = colors.primary, fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp)
            }
            Text("此刻，\n听见喜欢。", modifier = Modifier.padding(top = 23.dp),
                fontSize = 40.sp, lineHeight = 53.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp)
            Text("给生活一点旋律，\n让熟悉的声音陪你片刻。", modifier = Modifier.padding(top = 18.dp),
                color = colors.onSurfaceVariant, fontSize = 12.sp, lineHeight = 21.sp)
            Text("ONLINE MUSIC / YOUR NAS", modifier = Modifier.padding(top = 25.dp),
                color = colors.onSurfaceVariant.copy(alpha = .65f), fontSize = 9.sp, letterSpacing = 1.5.sp)
        }
    }
}

@Composable
private fun OnlineSongRow(
    title: String,
    artist: String,
    album: String,
    source: String,
    quality: String,
    number: Int,
    download: OnlineDownloadState? = null,
    onPlay: () -> Unit,
    action: @Composable () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Column {
        HorizontalDivider(color = colors.outlineVariant.copy(alpha = .6f))
        Column(Modifier.fillMaxWidth().clickable(onClick = onPlay).padding(vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(number.toString().padStart(2, '0'), color = colors.primary, fontSize = 11.sp,
                    letterSpacing = 1.sp, modifier = Modifier.width(34.dp))
                Column(Modifier.weight(1f).padding(end = 2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(title, fontWeight = FontWeight.Medium, fontSize = 16.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        Spacer(Modifier.width(4.dp))
                        OnlineInfoIcon(
                            icon = when {
                                quality.startsWith("Hi-Res") -> Icons.Default.AutoAwesome
                                quality.startsWith("无损") -> Icons.Default.Verified
                                quality.startsWith("高品质") -> Icons.Default.HighQuality
                                quality.startsWith("标准") -> Icons.Default.Sd
                                quality == "音质获取中" -> Icons.Default.HourglassEmpty
                                else -> Icons.Default.HelpOutline
                            },
                            description = quality
                        )
                        OnlineInfoIcon(
                            icon = when (source) {
                                "NAS" -> Icons.Default.Storage
                                "网易云" -> Icons.Default.Album
                                "QQ音乐" -> Icons.Default.MusicNote
                                "酷狗" -> Icons.Default.Headphones
                                "酷我" -> Icons.Default.GraphicEq
                                "咪咕" -> Icons.Default.Radio
                                else -> Icons.Default.CloudQueue
                            },
                            description = "来源：$source"
                        )
                    }
                    Text(listOf(artist, album).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                }
                action()
            }
            if (download is OnlineDownloadState.Failed) Text(download.message, color = colors.error,
                style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OnlineInfoIcon(icon: ImageVector, description: String) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(description) } },
        state = rememberTooltipState()
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            Icon(icon, description, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = .8f))
        }
    }
}

private fun onlineQualityLabel(audio: OnlineResolvedAudio): String {
    val format = audio.mimeType.substringAfter('/').lowercase()
    val label = when {
        format in setOf("flac", "alac", "ape", "wav", "x-wav", "pcm") -> "无损"
        audio.br >= 256 -> "高品质"
        audio.br > 0 -> "标准"
        else -> return "音质待确认"
    }
    return if (audio.br > 0) "$label · ${audio.br} kbps" else label
}

private fun songQualityLabel(song: SongEntity): String {
    if (song.bitrate == null && song.codec == null && song.sampleRate == null && song.bitDepth == null &&
        song.audioQuality == "Standard") return "音质待确认"
    val label = when (song.audioQuality) {
        "HiRes" -> "Hi-Res"
        "Lossless" -> "无损"
        "High" -> "高品质"
        else -> "标准"
    }
    return song.bitrate?.takeIf { it > 0 }?.let { "$label · ${it / 1000} kbps" } ?: label
}

@Composable
private fun OnlineMessage(icon: ImageVector, title: String, message: String, action: @Composable () -> Unit = {}) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Surface(shape = RoundedCornerShape(24.dp), color = colors.primary.copy(alpha = .09f)) {
            Icon(icon, null, Modifier.padding(22.dp).size(32.dp), tint = colors.primary)
        }
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
        action()
    }
}
