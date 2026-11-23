package life.xstar.music.player

import android.content.Intent
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import life.xstar.music.player.data.font.*
import life.xstar.music.player.data.lyrics.LyricLine
import life.xstar.music.player.data.preferences.*
import life.xstar.music.player.ui.LocalXstarFontFamily
import life.xstar.music.player.ui.downloadedFontFamily
import life.xstar.music.player.ui.toFontFamily
import life.xstar.music.player.ui.player.*
import kotlin.math.roundToInt

@Composable
private fun AppearanceNavigationDrawer(right: Boolean, select: (Boolean) -> Unit, preview: () -> Unit) {
    AppearanceCard {
        Text("导航栏划出方向", style = MaterialTheme.typography.titleMedium)
        Text("点击页面菜单按钮或播放页快捷操作中的导航栏时，从所选一侧展开。", fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.fillMaxWidth().height(130.dp).clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.align(if (right) Alignment.CenterEnd else Alignment.CenterStart)
                .fillMaxHeight().fillMaxWidth(.52f).background(MaterialTheme.colorScheme.primaryContainer).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("XSTAR", color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold)
                repeat(3) { Box(Modifier.fillMaxWidth().height(5.dp).background(MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .25f), RoundedCornerShape(3.dp))) }
            }
        }
    }
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(false to "从左侧划出", true to "从右侧划出").forEach { (value, title) ->
            Surface(shape = RoundedCornerShape(16.dp)) {
                ListItem(headlineContent = { Text(title) }, trailingContent = {
                    RadioButton(selected = right == value, onClick = null)
                }, modifier = Modifier.selectable(selected = right == value, role = Role.RadioButton, onClick = { select(value) }))
            }
        }
    }
    OutlinedButton(onClick = preview, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.Menu, null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("预览导航栏")
    }
}

private enum class AppearancePage(val title: String, val icon: ImageVector) {
    Colors("主题与颜色", Icons.Default.Palette),
    Fonts("字体", Icons.Default.TextFields),
    Player("播放界面", Icons.Default.Album),
    Playlists("播放列表外观", Icons.Default.QueueMusic),
    Lyrics("歌词显示", Icons.AutoMirrored.Filled.Notes),
    FloatingButton("悬浮按钮", Icons.Default.TouchApp),
    BottomNavigation("底部导航", Icons.Default.ViewWeek),
    NavigationDrawer("侧边导航", Icons.Default.Menu),
    Effects("动态效果", Icons.Default.GraphicEq),
    Library("音乐库视图", Icons.Default.AutoAwesome)
}

@Composable
internal fun AppearanceSettingsScreen(vm: MusicViewModel, openMenu: () -> Unit) {
    var page by rememberSaveable { mutableStateOf<AppearancePage?>(null) }
    var reset by remember { mutableStateOf(false) }
    val homeScroll = rememberScrollState()
    val theme by vm.theme.collectAsStateWithLifecycle()
    val font by vm.font.collectAsStateWithLifecycle()
    val fontId by vm.downloadedFont.collectAsStateWithLifecycle()
    val scene by vm.playerTheme.collectAsStateWithLifecycle()
    val lyrics by vm.selectedLyricsMode.collectAsStateWithLifecycle()
    val floatingButton by vm.floatingButtonStyle.collectAsStateWithLifecycle()
    val bottomNavigation by vm.bottomNavigation.collectAsStateWithLifecycle()
    val navigationDrawerRight by vm.navigationDrawerRight.collectAsStateWithLifecycle()
    val image by vm.playerBackground.collectAsStateWithLifecycle()
    val album by vm.albumArtworkBackground.collectAsStateWithLifecycle()
    val disc by vm.playerDiscVisible.collectAsStateWithLifecycle()
    val opacity by vm.playerDiscOpacity.collectAsStateWithLifecycle()
    val beat by vm.beatEffect.collectAsStateWithLifecycle()
    val spectrum by vm.bottomEffect.collectAsStateWithLifecycle()
    val libraryViewMode by vm.libraryViewMode.collectAsStateWithLifecycle()
    val playlistStyle by vm.playlistStyle.collectAsStateWithLifecycle()
    val backgroundName = if (album) "专辑封面" else if (image.isNotBlank()) "自定义图片" else "默认背景"
    val availableFonts by vm.fontCatalog.fonts.collectAsStateWithLifecycle()
    val fontName = availableFonts.firstOrNull { it.id == fontId }?.name ?: font.title
    BackHandler(page != null) { page = null }
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(horizontal = 18.dp)) {
        PageTopBar(page?.title ?: "外观", { if (page == null) openMenu() else page = null }, back = page != null, parentInset = 18) {
            if (page != null) IconButton({ reset = true }) {
                Icon(Icons.Default.RestartAlt, "恢复本页默认", tint = MaterialTheme.colorScheme.onBackground)
            }
        }
        PageSubtitle("选择后即时生效，自动保存")
        if (page == null) {
            Column(Modifier.weight(1f).verticalScroll(homeScroll), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AppearanceCard {
                    Text("当前外观 · 布局示意", style = MaterialTheme.typography.labelMedium)
                    PlayerAppearancePreview(scene, Modifier.fillMaxWidth().height(190.dp), image, disc, opacity)
                    Text("音乐让生活更有节奏", color = MaterialTheme.colorScheme.primary, fontSize = 18.sp)
                    Text("${scene.title} · $fontName", fontWeight = FontWeight.Medium)
                    Text("$backgroundName · ${lyrics.title}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
                AppearancePage.entries.forEach { entry ->
                    val summary = when (entry) {
                        AppearancePage.Colors -> "${if (theme.name == "Custom") "自定义" else "主题色"} · #${"%06X".format(theme.argb and 0xFFFFFF)}"
                        AppearancePage.Fonts -> fontName
                        AppearancePage.Player -> "${scene.title} · $backgroundName"
                        AppearancePage.Playlists -> playlistStyle.title
                        AppearancePage.Lyrics -> lyrics.title
                        AppearancePage.FloatingButton -> floatingButton.title
                        AppearancePage.BottomNavigation -> "${if (bottomNavigation.enabled) "已开启" else "已关闭"} · ${bottomNavigation.count} 个按钮 · 时光刻度"
                        AppearancePage.NavigationDrawer -> if (navigationDrawerRight) "从右侧划出" else "从左侧划出"
                        AppearancePage.Effects -> "节拍${if (beat) "已开启" else "已关闭"} · 频谱${if (spectrum) "已开启" else "已关闭"}"
                        AppearancePage.Library -> libraryViewMode.title
                    }
                    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
                        ListItem(headlineContent = { Text(entry.title) }, supportingContent = { Text(summary) },
                            leadingContent = { Icon(entry.icon, null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = { Icon(Icons.Default.ChevronRight, null) },
                            modifier = Modifier.clickable { page = entry })
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        } else key(page) {
            when (page) {
                AppearancePage.Fonts -> AppearanceFonts(vm)
                AppearancePage.Lyrics -> AppearanceLyrics(lyrics, vm::setLyricsMode)
                AppearancePage.FloatingButton -> AppearanceFloatingButton(floatingButton, vm::setFloatingButtonStyle, vm)
                AppearancePage.BottomNavigation -> AppearanceBottomNavigation(vm)
                AppearancePage.Playlists -> AppearancePlaylists(playlistStyle, vm::setPlaylistStyle)
                else -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    when (page) {
                        AppearancePage.Colors -> AppearanceColors(theme, vm)
                        AppearancePage.Player -> AppearancePlayer(vm, scene, image, album, disc, opacity)
                        AppearancePage.Effects -> {
                            AppearanceCard {
                                Text("律动示意", style = MaterialTheme.typography.labelMedium)
                                Canvas(Modifier.fillMaxWidth().height(90.dp)) {
                                    val color = Color(theme.argb)
                                    repeat(28) { i ->
                                        val h = size.height * (.18f + (i * 7 % 13) / 16f)
                                        drawRoundRect(color.copy(alpha = if (spectrum) .8f else .15f),
                                            Offset(i * size.width / 28, size.height - h), Size(size.width / 42, h))
                                    }
                                }
                                Text("播放时呈现节拍与频谱效果", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                            }
                            AppearanceToggle("音乐节拍动态效果", "播放时切换背景颜色并轻微缩放封面", beat, vm::setBeatEffect)
                            AppearanceToggle("底部频谱特效", "播放时显示实时频谱律动与氛围粒子", spectrum, vm::setBottomEffect)
                        }
                         AppearancePage.Library -> AppearanceLibraryView(libraryViewMode, vm::setLibraryViewMode)
                         AppearancePage.NavigationDrawer -> AppearanceNavigationDrawer(navigationDrawerRight, vm::setNavigationDrawerRight, openMenu)
                        else -> Unit
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
    }
    if (reset) AlertDialog(onDismissRequest = { reset = false }, title = { Text("恢复${page?.title}默认设置？") },
        text = { Text("将重置本分类的外观选项，已下载的字体仍会保留。") },
        confirmButton = { TextButton({
            when (page) {
                AppearancePage.Colors -> vm.setTheme(ThemeAccent.Blue)
                AppearancePage.Fonts -> vm.setFont(AppFont.Default)
                AppearancePage.Player -> {
                    vm.setPlayerTheme(PlayerTheme.Classic)
                    vm.setAppearanceBackground(false, null)
                    vm.setPlayerDiscVisible(true)
                    vm.setPlayerDiscOpacity(.42f)
                }
                AppearancePage.Lyrics -> vm.setLyricsMode(LyricsMode.Normal)
                AppearancePage.FloatingButton -> { vm.setFloatingButtonStyle(FloatingButtonStyle.Breathing); vm.setPlayerShortcuts(PlayerShortcut.defaults) }
                AppearancePage.BottomNavigation -> vm.resetBottomNavigation()
                AppearancePage.NavigationDrawer -> vm.setNavigationDrawerRight(true)
                AppearancePage.Effects -> { vm.setBeatEffect(true); vm.setBottomEffect(true) }
                AppearancePage.Library -> vm.setLibraryViewMode(LibraryViewMode.List)
                AppearancePage.Playlists -> vm.setPlaylistStyle(PlaylistStyle.Tape)
                null -> Unit
            }
            reset = false
        }) { Text("恢复默认") } }, dismissButton = { TextButton({ reset = false }) { Text("取消") } })
}

@Composable
private fun AppearancePlaylists(style: PlaylistStyle, select: (PlaylistStyle) -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize().selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item {
            AppearanceCard {
                Text("用喜欢的方式，收纳声音", style = MaterialTheme.typography.titleMedium)
                Text("预览跟随当前主题色。选择后即时应用到歌单首页、详情和播放队列，自动保存。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
        }
        items(PlaylistStyle.entries, key = { it.name }) { candidate ->
            val selected = style == candidate
            Surface(
                Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = { select(candidate) }),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(if (selected) 2.dp else 1.dp,
                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    life.xstar.music.player.ui.playlist.PlaylistAppearancePreview(candidate, Modifier.fillMaxWidth().height(190.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(candidate.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                            Text(candidate.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
                                modifier = Modifier.padding(top = 5.dp))
                        }
                        RadioButton(selected, onClick = null)
                    }
                }
            }
        }
    }
}

@Composable
private fun AppearanceFloatingButton(style: FloatingButtonStyle, select: (FloatingButtonStyle) -> Unit, vm: MusicViewModel) {
    val shortcuts by vm.playerShortcuts.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Int?>(null) }
    editing?.let { slot ->
        AlertDialog(onDismissRequest = { editing = null }, title = { Text("快捷打开 ${slot + 1}") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                PlayerShortcut.entries.forEach { action ->
                    val used = action in shortcuts && shortcuts[slot] != action
                    TextButton(enabled = !used, onClick = {
                        vm.setPlayerShortcuts(shortcuts.toMutableList().also { it[slot] = action }); editing = null
                    }, modifier = Modifier.fillMaxWidth()) {
                        Icon(action.icon(), null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp)); Text(action.title + if (used) " · 已使用" else "")
                    }
                }
            } }, confirmButton = { TextButton({ editing = null }) { Text("关闭") } })
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).selectableGroup()
    ) {
        AppearanceCard {
            Text("快捷打开设置", style = MaterialTheme.typography.titleMedium)
            Text("播放页点击展开五个花瓣，导航栏入口固定，其余四个可自定义。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ListItem(headlineContent = { Text("导航栏 · 固定") }, leadingContent = { Icon(Icons.Default.Menu, null) })
            shortcuts.forEachIndexed { index, action ->
                ListItem(headlineContent = { Text("快捷 ${index + 1} · ${action.title}") },
                    leadingContent = { Icon(action.icon(), null) }, trailingContent = { Icon(Icons.Default.ChevronRight, null) },
                    modifier = Modifier.clickable { editing = index })
            }
            TextButton({ vm.setPlayerShortcuts(PlayerShortcut.defaults) }) { Text("恢复默认快捷操作") }
        }
        AppearanceCard {
            Text("悬浮按钮样式", style = MaterialTheme.typography.titleMedium)
            Text("播放页与音乐库共享样式，支持拖动和点击展开快捷操作。选择后立即生效。", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            FloatingButtonStyle.entries.forEach { candidate ->
                Surface(
                    Modifier.fillMaxWidth().selectable(style == candidate, role = Role.RadioButton, onClick = { select(candidate) }),
                    shape = RoundedCornerShape(16.dp),
                    color = if (style == candidate) MaterialTheme.colorScheme.primary.copy(alpha = .12f) else Color.Transparent,
                    border = androidx.compose.foundation.BorderStroke(if (style == candidate) 1.5.dp else 1.dp,
                        if (style == candidate) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        FloatingButtonStylePreview(candidate, Modifier.size(52.dp))
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(candidate.title, fontWeight = FontWeight.Medium)
                            Text(candidate.subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (style == candidate) Icon(Icons.Default.CheckCircle, "使用中", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
private fun AppearanceLibraryView(mode: LibraryViewMode, select: (LibraryViewMode) -> Unit) {
    Column(Modifier.fillMaxSize().selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AppearanceCard {
            Text("音乐库首页", style = MaterialTheme.typography.labelMedium)
            Text("选择你探索音乐的方式。切换后立即应用并自动保存。", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
        LibraryViewMode.entries.forEach { candidate ->
            val selected = candidate == mode
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .55f) else MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = { select(candidate) })
            ) {
                ListItem(
                    headlineContent = { Text(candidate.title) },
                    supportingContent = { Text(candidate.subtitle) },
                    leadingContent = {
                        Icon(if (candidate == LibraryViewMode.Galaxy) Icons.Default.AutoAwesome else Icons.Default.ViewList, null,
                            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    trailingContent = { if (selected) Icon(Icons.Default.Check, "已选择", tint = MaterialTheme.colorScheme.primary) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }
        }
    }
}

@Composable
private fun FloatingButtonStylePreview(style: FloatingButtonStyle, modifier: Modifier = Modifier) {
    if (style.isSculptedFloatingStyle) {
        SculptedFloatingButton(style, playing = true, modifier = modifier)
        return
    }
    val accent = MaterialTheme.colorScheme.primary
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val center = center
            when (style) {
                FloatingButtonStyle.Spectrum -> repeat(12) { i ->
                    val angle = i * 30f
                    rotate(angle, center) { drawLine(accent.copy(alpha = .7f), Offset(center.x, 3.dp.toPx()), Offset(center.x, 8.dp.toPx()), 2.dp.toPx()) }
                }
                FloatingButtonStyle.Breathing -> drawCircle(accent.copy(alpha = .25f), size.minDimension * .42f, style = Stroke(2.dp.toPx()))
                FloatingButtonStyle.Liquid -> drawCircle(Brush.sweepGradient(listOf(accent, Color.White, accent)), size.minDimension * .38f, style = Stroke(3.dp.toPx()))
                FloatingButtonStyle.Magnetic -> drawRoundRect(accent.copy(alpha = .7f), style = Stroke(2.dp.toPx()), cornerRadius = androidx.compose.ui.geometry.CornerRadius(10.dp.toPx()))
                else -> Unit
            }
        }
        Icon(if (style == FloatingButtonStyle.Playback) Icons.Default.Pause else Icons.Default.MusicNote, null, tint = accent, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun AppearanceCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun AppearanceToggle(title: String, description: String, checked: Boolean, change: (Boolean) -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
        ListItem(headlineContent = { Text(title) }, supportingContent = { Text(description) },
            trailingContent = { Switch(checked, change) }, modifier = Modifier.clickable { change(!checked) })
    }
}

@Composable
private fun AppearanceColors(theme: ThemeAccent, vm: MusicViewModel) {
    var custom by remember { mutableStateOf(false) }
    AppearanceCard {
        Text("主题预览", color = MaterialTheme.colorScheme.primary)
        Text("音乐让生活更有节奏", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        LinearProgressIndicator(progress = { .65f }, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledIconButton({}) { Icon(Icons.Default.PlayArrow, "主题按钮示意") }
            Text("XSTAR · 你的音乐空间", Modifier.padding(start = 12.dp))
        }
    }
    AppearanceCard {
        Text("预设主题色")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            ThemeAccent.entries.forEach { accent -> ThemeSwatch(accent, theme == accent) { vm.setTheme(accent) } }
        }
        ListItem(headlineContent = { Text("自定义颜色") }, supportingContent = { Text("#${"%06X".format(theme.argb and 0xFFFFFF)} · RGB / HEX") },
            leadingContent = { Icon(Icons.Default.Palette, null) }, trailingContent = { Icon(Icons.Default.ChevronRight, null) },
            modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { custom = true })
    }
    if (custom) AppearanceColorSheet(Color(theme.argb), vm::setCustomThemeColor) { custom = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppearanceColorSheet(initial: Color, apply: (Long) -> Unit, close: () -> Unit) {
    var hex by rememberSaveable { mutableStateOf("%06X".format(initial.toArgb() and 0xFFFFFF)) }
    val rgb = hex.takeIf { it.length == 6 }?.toLongOrNull(16)
    val color = rgb?.let { Color(it or 0xFF000000L) } ?: initial
    ModalBottomSheet(onDismissRequest = close) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("自定义主题颜色", style = MaterialTheme.typography.titleLarge)
            Box(Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(16.dp)).background(color))
            OutlinedTextField(hex, { value -> hex = value.removePrefix("#").filter { it in "0123456789abcdefABCDEF" }.take(6).uppercase() },
                modifier = Modifier.fillMaxWidth(), label = { Text("HEX 色值") }, prefix = { Text("#") },
                supportingText = { Text("输入 6 位色值，或拖动 RGB 滑块") }, isError = rgb == null, singleLine = true)
            listOf("红 R" to 16, "绿 G" to 8, "蓝 B" to 0).forEach { (label, shift) ->
                val channel = (color.toArgb() shr shift) and 0xFF
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, Modifier.width(44.dp))
                    Slider(channel.toFloat(), { value ->
                        val updated = (color.toArgb() and (0xFF shl shift).inv()) or (value.roundToInt() shl shift)
                        hex = "%06X".format(updated and 0xFFFFFF)
                    }, valueRange = 0f..255f, modifier = Modifier.weight(1f))
                    Text(channel.toString(), Modifier.width(32.dp))
                }
            }
            Button({ rgb?.let { apply(it or 0xFF000000L) }; close() }, Modifier.fillMaxWidth(), enabled = rgb != null) { Text("应用颜色") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AppearancePlayer(vm: MusicViewModel, scene: PlayerTheme, image: String, album: Boolean, disc: Boolean, opacity: Float) {
    val context = LocalContext.current
    var pickedAlbum by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            vm.setAppearanceBackground(pickedAlbum, uri.toString())
        }
    }
    AppearanceCard {
        Text("当前布局预览 · ${scene.title}")
        PlayerAppearancePreview(scene, Modifier.fillMaxWidth().height(210.dp), image, disc, opacity)
        Text(if (album) "专辑封面随歌曲切换，此处仅示意布局" else "配色与布局示意，实际效果以播放页面为准", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PlayerTheme.entries.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { candidate ->
                    Surface(Modifier.weight(1f).selectable(scene == candidate, role = Role.RadioButton, onClick = { vm.setPlayerTheme(candidate) }),
                        shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface,
                        border = androidx.compose.foundation.BorderStroke(if (scene == candidate) 2.dp else 1.dp,
                            if (scene == candidate) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            PlayerAppearancePreview(candidate, Modifier.fillMaxWidth().height(115.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(candidate.title, Modifier.weight(1f), fontSize = 13.sp)
                                if (candidate == scene) Icon(Icons.Default.CheckCircle, "已选择", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                            }
                            Text(candidate.subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
    AppearanceCard {
        Text("背景来源")
        val source = if (album) 1 else if (image.isNotBlank()) 2 else 0
        Column(Modifier.selectableGroup()) {
            listOf("默认背景", "专辑封面", "自定义图片").forEachIndexed { index, label ->
                Row(Modifier.fillMaxWidth().selectable(source == index, role = Role.RadioButton, onClick = {
                    when (index) {
                        0 -> vm.setAppearanceBackground(false, null)
                        1 -> vm.setAppearanceBackground(true, image)
                        2 -> if (image.isBlank()) { pickedAlbum = false; picker.launch(arrayOf("image/*")) } else vm.setAppearanceBackground(false, image)
                    }
                }).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(source == index, null)
                    Text(label, Modifier.padding(start = 8.dp))
                }
            }
        }
        if (source != 0) {
            Text(if (album) "无专辑封面时的备用图片" else "背景图片", fontWeight = FontWeight.Medium)
            if (image.isNotBlank()) AsyncImage(image, "已选择的背景图片", Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
            Row {
                OutlinedButton({ pickedAlbum = album; picker.launch(arrayOf("image/*")) }) { Text(if (image.isBlank()) "选择图片" else "更换图片") }
                if (image.isNotBlank()) TextButton({ vm.setAppearanceBackground(album, null) }) { Text("移除") }
            }
            if (album) Text("无封面时使用备用图片，未设置则使用默认背景", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (scene == PlayerTheme.Classic) {
        AppearanceToggle("显示专辑光碟", "在经典播放器中央显示光碟", disc, vm::setPlayerDiscVisible)
        if (disc) AppearanceCard {
            Text("光碟透明度 · ${(opacity * 100).roundToInt()}%")
            Slider(opacity, vm::setPlayerDiscOpacity, valueRange = .05f..1f)
        }
    }
}

/** Lightweight schematic rather than mounting five live player scenes. */
@Composable
private fun PlayerAppearancePreview(scene: PlayerTheme, modifier: Modifier, image: String = "", disc: Boolean = true, opacity: Float = 1f) {
    val accent = MaterialTheme.colorScheme.primary
    Box(modifier.clip(RoundedCornerShape(14.dp)).background(Brush.linearGradient(listOf(Color(0xFF101523), accent.copy(alpha = .18f))))) {
        if (image.isNotBlank()) AsyncImage(image, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop, alpha = .45f)
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width * .5f, size.height * .40f)
            val radius = size.minDimension * .24f
            when (scene) {
                PlayerTheme.Classic, PlayerTheme.Liquid -> if (disc || scene != PlayerTheme.Classic) {
                    val alpha = if (scene == PlayerTheme.Classic) opacity else 1f
                    drawCircle(Brush.sweepGradient(listOf(accent, Color(0xFFDBA9D1), accent), center), radius, center, alpha = alpha)
                    drawCircle(Color(0xFF101523), radius * .17f, center)
                    drawCircle(Color.White.copy(alpha = .35f * alpha), radius * .75f, center, style = Stroke(1.dp.toPx()))
                }
                PlayerTheme.Orbital -> {
                    drawCircle(accent.copy(alpha = .5f), radius * 1.4f, center, style = Stroke(1.dp.toPx()))
                    drawCircle(accent, radius * .65f, center)
                    drawCircle(Color.White, radius * .14f, center + Offset(radius * 1.4f, 0f))
                }
                PlayerTheme.Tape -> {
                    drawRoundRect(Color(0xFFEAE4D9), Offset(size.width * .17f, size.height * .13f), Size(size.width * .66f, size.height * .49f))
                    listOf(.35f, .65f).forEach { x ->
                        drawCircle(Color(0xFF303838), radius * .55f, Offset(size.width * x, center.y))
                        drawCircle(accent, radius * .18f, Offset(size.width * x, center.y))
                    }
                }
                PlayerTheme.Theatre -> repeat(4) { i ->
                    val half = size.width * (if (i == 1) .29f else .21f)
                    val y = size.height * (.20f + i * .12f)
                    drawLine(if (i == 1) accent else Color.White.copy(alpha = .25f), Offset(center.x - half, y), Offset(center.x + half, y), if (i == 1) 4.dp.toPx() else 2.dp.toPx())
                }
            }
            drawLine(Color.White.copy(alpha = .35f), Offset(size.width * .15f, size.height * .76f), Offset(size.width * .85f, size.height * .76f), 2.dp.toPx())
            drawLine(accent, Offset(size.width * .15f, size.height * .76f), Offset(size.width * .48f, size.height * .76f), 2.dp.toPx())
        }
        Icon(Icons.Default.PlayCircle, null, Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp).size(28.dp), tint = accent)
    }
}

@Composable
private fun AppearanceFonts(vm: MusicViewModel) {
    val context = LocalContext.current
    val font by vm.font.collectAsStateWithLifecycle()
    val id by vm.downloadedFont.collectAsStateWithLifecycle()
    val version by vm.fontCatalogVersion.collectAsStateWithLifecycle()
    val downloadableFonts by vm.fontCatalog.fonts.collectAsStateWithLifecycle()
    val refreshing by vm.fontCatalog.refreshing.collectAsStateWithLifecycle()
    val catalogError by vm.fontCatalog.catalogError.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refreshFonts() }
    val progress by vm.fontDownloadProgress.collectAsStateWithLifecycle()
    var online by rememberSaveable { mutableStateOf(false) }
    val downloaded = remember(version, downloadableFonts) { downloadableFonts.filter(vm.fontCatalog::isDownloaded) }
    val current = downloaded.firstOrNull { it.id == id }
    val family = downloadedFontFamily(context, current?.let { vm.fontCatalog.file(it).absolutePath }.orEmpty(), font.toFontFamily())
    var delete by remember { mutableStateOf<DownloadableFont?>(null) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AppearanceCard {
            Text("当前字体 · ${current?.name ?: font.title}", style = MaterialTheme.typography.labelMedium)
            Text("音乐让生活更有节奏", fontFamily = family, fontSize = 22.sp)
            Text("春风十里 · Aa 123 XSTAR", fontFamily = family, color = MaterialTheme.colorScheme.primary)
        }
        PrimaryTabRow(if (online) 1 else 0) {
            Tab(!online, { online = false }, text = { Text("系统 / 经典") })
            Tab(online, { online = true }, text = { Text("在线字体 · ${downloaded.size} 已下载") })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("NAS 字体目录", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            TextButton({ vm.refreshFonts() }, enabled = !refreshing) { Text(if (refreshing) "同步中…" else "刷新") }
        }
        catalogError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        if (!online) {
            Text("经典字体也从 NAS 按需下载；未下载时使用系统字体。点击字体可下载或重试。", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (progress.id != null && progress.error == null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(if (progress.total > 0) "字体下载中 · ${(progress.fraction * 100).roundToInt()}%" else "正在连接 NAS 下载字体…", fontSize = 12.sp)
            }
            progress.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        }
        LazyColumn(Modifier.weight(1f).selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (!online) item {
                TextButton({ vm.useSystemFont() }) { Text("恢复默认字体") }
            }
            if (online && downloadableFonts.isEmpty()) item {
                Text(if (refreshing) "正在读取 NAS 字体目录…" else "暂无可用字体，请向 NAS /App/Font 上传 TTF 或 OTF 文件后刷新。")
            }
            if (!online) items(AppFont.systemOptions, key = { it.name }) { candidate ->
                val selected = id.isBlank() && font == candidate
                Surface(shape = RoundedCornerShape(16.dp)) {
                    ListItem(headlineContent = { Text(candidate.title, fontFamily = candidate.toFontFamily()) },
                        supportingContent = { Column {
                            Text(candidate.subtitle, fontSize = 12.sp)
                            Text("春风十里 · Aa 123", fontFamily = candidate.toFontFamily(), fontSize = 17.sp, modifier = Modifier.padding(top = 8.dp))
                        } }, trailingContent = { if (selected) Icon(Icons.Default.CheckCircle, "使用中", tint = MaterialTheme.colorScheme.primary) },
                        modifier = Modifier.selectable(selected, role = Role.RadioButton, onClick = { vm.setFont(candidate) }))
                }
            } else {
                item { Text("从 NAS /App/Font 按需下载，下载后可离线使用", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) }
                val ordered = downloaded + downloadableFonts.filterNot { it in downloaded }
                items(ordered, key = { it.id }) { candidate ->
                    val installed = candidate in downloaded
                    val loading = progress.id == candidate.id && progress.error == null
                    val busy = progress.id != null && progress.error == null
                    var menu by remember { mutableStateOf(false) }
                    val sampleFamily = downloadedFontFamily(context, if (installed) vm.fontCatalog.file(candidate).absolutePath else "", AppFont.Default.toFontFamily())
                    Surface(modifier = if (installed) Modifier.selectable(id == candidate.id, role = Role.RadioButton, onClick = { vm.enableDownloadedFont(candidate) }) else Modifier,
                        shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(candidate.name, fontWeight = FontWeight.Medium)
                                    Text(candidate.description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (id == candidate.id) Icon(Icons.Default.CheckCircle, "使用中", tint = MaterialTheme.colorScheme.primary)
                                else TextButton({ vm.enableDownloadedFont(candidate) }, enabled = !busy) { Text(if (installed) "启用" else if (loading) "下载中" else if (progress.id == candidate.id) "重试" else "下载") }
                                if (installed) Box {
                                    IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "字体管理") }
                                    DropdownMenu(menu, { menu = false }) {
                                        DropdownMenuItem(text = { Text("删除字体") }, onClick = { menu = false; delete = candidate })
                                    }
                                }
                            }
                            Text("春风十里 · Aa 123", fontFamily = sampleFamily, fontSize = 18.sp,
                                modifier = if (installed) Modifier.fillMaxWidth().clickable { vm.enableDownloadedFont(candidate) } else Modifier)
                            if (!installed) Text("下载后展示真实字形，当前为默认字体示例", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (loading) {
                                if (progress.total > 0) LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth())
                                else LinearProgressIndicator(Modifier.fillMaxWidth())
                                Text(if (progress.total > 0) "${(progress.fraction * 100).roundToInt()}%" else "正在下载…", fontSize = 12.sp)
                            }
                            if (progress.id == candidate.id) progress.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                        }
                    }
                }
            }
        }
    }
    delete?.let { target ->
        AlertDialog(onDismissRequest = { delete = null }, title = { Text("删除${target.name}？") },
            text = { Text(if (id == target.id) "当前字体将切换为经典字体（未下载时使用系统字体），之后可重新下载。" else "之后可重新下载此字体。") },
            confirmButton = { TextButton({ vm.removeDownloadedFont(target); delete = null }) { Text("删除") } },
            dismissButton = { TextButton({ delete = null }) { Text("取消") } })
    }
}

@Composable
private fun AppearanceLyrics(mode: LyricsMode, select: (LyricsMode) -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AppearanceCard {
            Text("${mode.title} · 示例预览", style = MaterialTheme.typography.labelMedium)
            AppearanceLyricsPreview(mode, Modifier.fillMaxWidth().height(185.dp))
            Text(if (mode == LyricsMode.Random) "随机模式将在歌曲切换时选择效果，下方轮播示意" else "适用于播放页面、电台与卡拉 OK", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
        LazyColumn(Modifier.weight(1f).selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(LyricsMode.entries, key = { it.name }) { candidate ->
                Surface(shape = RoundedCornerShape(16.dp)) {
                    ListItem(headlineContent = { Text(candidate.title, color = if (mode == candidate) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) },
                        supportingContent = { Text(candidate.subtitle) }, trailingContent = { RadioButton(mode == candidate, null) },
                        modifier = Modifier.selectable(mode == candidate, role = Role.RadioButton, onClick = { select(candidate) }))
                }
            }
        }
    }
}

@Composable
private fun AppearanceLyricsPreview(mode: LyricsMode, modifier: Modifier) {
    val lines = remember { listOf("让晚风轻轻吹过", "音乐让生活更有节奏", "把星光唱成一首歌", "这一刻听见自由").mapIndexed { i, text -> LyricLine(i * 4000L, text) } }
    var elapsed by remember(mode) { mutableLongStateOf(0L) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(mode, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val start = SystemClock.uptimeMillis() - elapsed
            while (true) { elapsed = SystemClock.uptimeMillis() - start; delay(50) }
        }
    }
    val position = elapsed % 16000L
    val current = (position / 4000L).toInt()
    val effect = if (mode == LyricsMode.Random) LyricsMode.entries.filter { it != LyricsMode.Random }[((elapsed / 8000L) % (LyricsMode.entries.size - 1)).toInt()] else mode
    val accent = MaterialTheme.colorScheme.primary
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFF10141E))) {
        key(effect) {
            val fill = Modifier.fillMaxSize()
            when {
                effect == LyricsMode.Terminal -> TerminalLyrics(lines, current, position, true, fill, compact = true)
                effect == LyricsMode.DensityMap -> WholeLyricsStage(lines, current, effect, true, position,
                    ScenePalette(Color(0xFF10141E), Color.White, accent), fill, onLine = {})
                effect == LyricsMode.LyricRain || effect == LyricsMode.BambooScroll -> VerticalLyrics(lines, current, position, true, effect, accent, Color.White, fill)
                effect.isSpatial -> SpatialLyrics(lines, current, position, true, effect, accent, Color.White, fill)
                effect == LyricsMode.LightTrail -> LightTrailLyrics(lines, current, position, true, accent, Color.White, fill)
                effect == LyricsMode.Sakura -> SakuraLyrics(lines, current, position, true, accent, Color.White, fill)
                effect == LyricsMode.Firework -> FireworkLyrics(lines, current, position, true, accent, Color.White, fill)
                effect == LyricsMode.RiftFall -> RiftFallLyrics(lines, current, position, true, accent, Color.White, fill, onLine = {})
                effect == LyricsMode.PopBounce -> PopBounceLyrics(lines, current, position, true, accent, Color.White, fill, onLine = {})
                effect == LyricsMode.NeonStorm -> NeonStormLyrics(lines, current, true, accent, Color.White, fill, onLine = {})
                else -> Column(fill.padding(16.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    lines.forEachIndexed { i, line -> Text(line.text, color = if (current == i) accent else Color.White.copy(alpha = .35f),
                        fontSize = if (current == i) 20.sp else 14.sp, modifier = Modifier.padding(vertical = 7.dp), fontFamily = LocalXstarFontFamily.current) }
                }
            }
        }
    }
}
