package life.xstar.music.player

import android.animation.ValueAnimator
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import life.xstar.music.player.data.preferences.*
import life.xstar.music.player.navigation.*
import life.xstar.music.player.ui.player.palette

internal fun BottomDestination.route(): NavKey = when (this) {
    BottomDestination.Online -> OnlineRoute
    BottomDestination.Library -> LibraryRoute
    BottomDestination.Player -> PlayerRoute
    BottomDestination.Appearance -> AppearanceRoute
    BottomDestination.Settings -> SettingsRoute
    BottomDestination.Favorites -> FavoritesRoute
    BottomDestination.Playlists -> PlaylistsRoute
    BottomDestination.Equalizer -> EqualizerRoute
    BottomDestination.SingingHistory -> SingingHistoryRoute
}

internal fun BottomDestination.icon(): ImageVector = when (this) {
    BottomDestination.Online -> Icons.Default.Search
    BottomDestination.Library -> Icons.Default.LibraryMusic
    BottomDestination.Player -> Icons.Default.MusicNote
    BottomDestination.Appearance -> Icons.Default.Palette
    BottomDestination.Settings -> Icons.Default.Settings
    BottomDestination.Favorites -> Icons.Default.FavoriteBorder
    BottomDestination.Playlists -> Icons.Default.QueueMusic
    BottomDestination.Equalizer -> Icons.Default.Equalizer
    BottomDestination.SingingHistory -> Icons.Default.Mic
}

@Composable
internal fun MusicBottomNavigation(
    vm: MusicViewModel, settings: BottomNavigationSettings, theme: PlayerTheme,
    currentRoute: NavKey, navigate: (NavKey) -> Unit
) {
    val state by vm.playback.collectAsStateWithLifecycle()
    val spectrum by vm.player.spectrum.collectAsStateWithLifecycle()
    val effects by vm.beatEffect.collectAsStateWithLifecycle()
    val motion = effects && ValueAnimator.areAnimatorsEnabled() && state.playing && state.song != null
    val energy = if (motion && spectrum.isNotEmpty()) spectrum.take(12).average().toFloat().coerceIn(0f, 1f) else 0f
    val beat by animateFloatAsState(energy, tween(if (energy > 0f) 100 else 350), label = "navigationBeat")
    val palette = theme.palette()
    val accent = MaterialTheme.colorScheme.primary
    val base = if (theme == PlayerTheme.Classic) MaterialTheme.colorScheme.background else palette.background
    val ink = if (theme == PlayerTheme.Classic) MaterialTheme.colorScheme.onBackground else palette.ink
    val light = theme == PlayerTheme.Tape
    val surface = lerp(base, if (light) Color.White else ink, if (light) .32f else .045f)
    val surfaceTop = lerp(surface, accent, if (light) .035f else .065f)
    val progress = if (state.duration > 0) (state.position.toFloat() / state.duration).coerceIn(0f, 1f) else 0f
    Box(Modifier.fillMaxWidth()) {
        // The transparent crown overlaps the page; only the lower base reserves
        // content space in XstarApp. Keep the entire raised button hit-testable.
        Column(Modifier.matchParentSize().padding(top = 22.dp)
            .background(Brush.verticalGradient(listOf(surfaceTop, surface)))) {
        Canvas(Modifier.fillMaxWidth().height(1.dp)) {
            drawRect(accent.copy(alpha = .12f))
            drawRect(accent.copy(alpha = .85f), size = Size(size.width * progress, size.height))
        }
        }
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(80.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            settings.slots.forEachIndexed { index, page ->
                val primary = index == settings.count / 2
                val player = page == BottomDestination.Player
                val active = currentRoute == page.route()
                val color = if (active) accent else ink.copy(alpha = .55f)
                Box(Modifier.weight(1f).fillMaxHeight().clip(MaterialTheme.shapes.medium)
                    .semantics { contentDescription = if (player) "播放页，已播放${state.position / 60000}分${state.position / 1000 % 60}秒" else page.title; selected = active }
                    .clickable(role = Role.Tab) { navigate(page.route()) }, contentAlignment = Alignment.Center) {
                    if (primary) {
                        Box(Modifier.align(Alignment.TopCenter).size(68.dp), contentAlignment = Alignment.Center) {
                            Canvas(Modifier.fillMaxSize()) {
                                val r = 25.dp.toPx()
                                if (player && beat > .001f) {
                                    drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = .12f + beat * .14f), Color.Transparent), radius = r * 1.42f), r * 1.42f)
                                }
                                drawCircle(base, r + 5.dp.toPx())
                                drawCircle(lerp(base, accent, if (light) .035f else .055f), r - 2.dp.toPx())
                                repeat(4) { groove -> drawCircle(accent.copy(alpha = .055f), r - (5 + groove * 4).dp.toPx(), style = Stroke(1.dp.toPx())) }
                                drawCircle(accent.copy(alpha = .16f), r, style = Stroke(3.dp.toPx()))
                                if (player) {
                                    drawArc(accent, -90f, progress * 360f, false,
                                        topLeft = center - Offset(r, r), size = Size(r * 2, r * 2),
                                        style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
                                    drawCircle(accent.copy(alpha = .12f + beat * .34f), r + (4 + beat * 4).dp.toPx(), style = Stroke(1.dp.toPx()))
                                }
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(page.icon(), null, tint = color, modifier = Modifier.size(if (player) 18.dp else 24.dp))
                                if (player) Text(if (state.song == null) "--:--" else "${state.position / 60000}:${(state.position / 1000 % 60).toString().padStart(2, '0')}",
                                    color = ink, fontSize = 10.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                            }
                        }
                    } else Column(Modifier.padding(top = 22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(page.icon(), null, tint = color, modifier = Modifier.size(20.dp))
                        if (!player) Text(page.title, color = color, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
internal fun AppearanceBottomNavigation(vm: MusicViewModel) {
    val settings by vm.bottomNavigation.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(settings.count) { editing = null }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("时光刻度 · 黑胶呼吸", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                Text("进度环与时间留在中央，外圈随音乐低频轻轻呼吸。播放入口不显示文字，点击仅进入播放页。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                MusicBottomNavigation(vm, settings, vm.playerTheme.collectAsStateWithLifecycle().value, PlayerRoute) { }
            }
        }
        ListItem(headlineContent = { Text("显示底部导航") }, supportingContent = { Text("默认关闭 · 开启后固定在页面底部") },
            trailingContent = { Switch(settings.enabled, vm::setBottomNavigationEnabled) })
        Text("按钮数量", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(3, 5).forEach { count ->
                FilterChip(selected = settings.count == count, onClick = { vm.setBottomNavigationCount(count) }, label = { Text("$count 个按钮") })
            }
        }
        Text("自定义入口", fontWeight = FontWeight.SemiBold)
        Text("3 个与 5 个按钮分别保存。中间为视觉主位，默认播放页；所有入口均可替换，不能重复。",
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        settings.slots.forEachIndexed { index, page ->
            ListItem(headlineContent = { Text(page.title) }, supportingContent = { Text("入口 ${index + 1}${if (index == settings.count / 2) " · 中央主位" else ""}") },
                leadingContent = { Icon(page.icon(), null, tint = MaterialTheme.colorScheme.primary) },
                trailingContent = { Icon(Icons.Default.ChevronRight, null) }, modifier = Modifier.clickable { editing = index })
        }
        Text("呼吸效果跟随「动态效果 → 音乐节拍动态效果」开关；暂停播放或系统关闭动画时保持静止。",
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        TextButton(onClick = { vm.resetBottomNavigation() }) { Text("恢复底部导航默认设置") }
        Spacer(Modifier.height(24.dp))
    }
    editing?.takeIf { it in settings.slots.indices }?.let { slot ->
        AlertDialog(onDismissRequest = { editing = null }, title = { Text("入口 ${slot + 1} · 选择页面") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                BottomDestination.entries.forEach { page ->
                    val used = page in settings.slots && settings.slots[slot] != page
                    TextButton(enabled = !used, onClick = {
                        vm.setBottomNavigationSlots(settings.slots.toMutableList().also { it[slot] = page }); editing = null
                    }, modifier = Modifier.fillMaxWidth()) {
                        Icon(page.icon(), null, Modifier.size(20.dp)); Spacer(Modifier.width(10.dp))
                        Text(page.title + if (used) " · 已使用" else "", modifier = Modifier.weight(1f))
                        if (settings.slots[slot] == page) Icon(Icons.Default.Check, "已选择")
                    }
                }
            } }, confirmButton = { TextButton(onClick = { editing = null }) { Text("关闭") } })
    }
}
