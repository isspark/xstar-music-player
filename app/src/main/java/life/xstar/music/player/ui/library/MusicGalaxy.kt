package life.xstar.music.player.ui.library

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.text.style.TextOverflow
import life.xstar.music.player.data.local.SongEntity
import life.xstar.music.player.PageTopBar
import life.xstar.music.player.PageSearchField
import life.xstar.music.player.data.preferences.PlayerTheme
import life.xstar.music.player.data.preferences.FloatingButtonStyle
import life.xstar.music.player.ui.player.FloatingButtonVisual
import life.xstar.music.player.ui.player.FloatingButtonFace
import life.xstar.music.player.ui.player.SculptedFloatingButton
import life.xstar.music.player.ui.player.isSculptedFloatingStyle
import kotlin.math.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private data class GalaxyPoint(
    val song: SongEntity,
    val theta: Float,
    val phi: Float,
    val popularity: Float
)

private fun stableScatter(seed: Long): Float {
    var value = seed xor (seed ushr 33)
    value *= -49064778989728563L
    value = value xor (value ushr 33)
    value *= -4265267296055464877L
    value = value xor (value ushr 33)
    return ((value ushr 11) / (1L shl 53).toDouble()).toFloat()
}

@Composable
fun MusicGalaxy(
    songs: List<SongEntity>,
    theme: PlayerTheme,
    onPlay: (SongEntity) -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    onMenu: () -> Unit,
    onScan: () -> Unit,
    scanEnabled: Boolean,
    floatingStyle: FloatingButtonStyle,
    playing: Boolean,
    onRandomPlay: (List<SongEntity>) -> Unit,
    modifier: Modifier = Modifier
) {
    var filter by rememberSaveable { mutableStateOf("全部") }
    val visibleSongs = remember(songs, query, filter) {
        songs.filter { query.isBlank() || "${it.title}${it.artist}${it.album}".contains(query, true) }
            .let { values -> when (filter) {
                "歌曲" -> values
                "专辑" -> values.distinctBy { it.album }
                "高频" -> values.sortedByDescending { it.playCount }
                else -> values
            } }
    }
    val points = remember(visibleSongs) {
        val golden = Math.PI.toFloat() * (3f - sqrt(5f))
        val songsToShow = visibleSongs.take(90)
        val albumPlayCounts = songsToShow.groupingBy { it.album }.fold(0) { total, song -> total + song.playCount }
        val maxPopularity = songsToShow.maxOfOrNull { song ->
            max(song.playCount.toFloat(), albumPlayCounts[song.album]?.toFloat() ?: 0f)
        }?.coerceAtLeast(1f) ?: 1f
        songsToShow.mapIndexed { index, song ->
            // Keep the golden-angle spacing as a base, then add stable per-song
            // jitter so the galaxy feels organic without jumping on recomposition.
            val seed = song.mediaStoreId * 31L + index * 997L
            val jitterTheta = (stableScatter(seed) - .5f) * .12f
            val jitterPhi = (stableScatter(seed + 17L) - .5f) * .42f
            val baseTheta = acos(1f - 2f * (index + .7f) / max(1, songsToShow.size))
            val albumPlays = albumPlayCounts[song.album] ?: 0
            val rawPopularity = max(song.playCount.toFloat(), albumPlays.toFloat())
            val popularity = (ln(1f + rawPopularity) / ln(1f + maxPopularity)).coerceIn(0f, 1f)
            GalaxyPoint(
                song,
                (baseTheta + jitterTheta).coerceIn(.04f, (PI - .04).toFloat()),
                index * golden + jitterPhi,
                popularity
            )
        }
    }
    var yaw by remember { mutableFloatStateOf(-.45f) }
    var pitch by remember { mutableFloatStateOf(.15f) }
    var dragging by remember { mutableStateOf(false) }
    var velocityX by remember { mutableFloatStateOf(0f) }
    var velocityY by remember { mutableFloatStateOf(0f) }
    var controlsOpen by rememberSaveable { mutableStateOf(false) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var buttonClickEffect by remember { mutableIntStateOf(0) }
    BackHandler(controlsOpen || searchOpen) { controlsOpen = false; searchOpen = false }
    val density = LocalDensity.current
    val accent = when (theme) {
        PlayerTheme.Classic -> Color(0xFF78A9FF)
        PlayerTheme.Orbital -> Color(0xFFC4B4FF)
        PlayerTheme.Theatre -> Color(0xFFEDCF95)
        PlayerTheme.Liquid -> Color(0xFFE5BCD7)
        PlayerTheme.Tape -> Color(0xFFA77533)
    }
    LaunchedEffect(dragging, points) {
        if (!dragging) {
            while (isActive) {
                delay(32)
                yaw += .00075f + velocityX
                pitch = (pitch + velocityY).coerceIn(-1.12f, 1.12f)
                velocityX *= .94f
                velocityY *= .94f
            }
        }
    }
    BoxWithConstraints(
        modifier.fillMaxSize().background(Color(0xFF050711)).pointerInput(points) {
            detectDragGestures(
                onDragStart = { dragging = true; velocityX = 0f; velocityY = 0f },
                onDragEnd = { dragging = false },
                onDragCancel = { dragging = false },
                onDrag = { change, dragAmount ->
                    change.consume()
                    velocityX = dragAmount.x * .00022f
                    velocityY = dragAmount.y * .00022f
                    yaw += velocityX
                    pitch = (pitch + velocityY).coerceIn(-1.12f, 1.12f)
                }
            )
        }
    ) {
        val w = constraints.maxWidth.toFloat(); val h = constraints.maxHeight.toFloat()
        val cx = w / 2f; val cy = h * .51f
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Brush.radialGradient(listOf(accent.copy(alpha = .12f), Color.Transparent), Offset(cx, cy), max(w, h) * .62f))
            repeat(115) { index ->
                val x = ((index * 83.7f + 27f) % w); val y = ((index * 47.3f + 41f) % h)
                val size = if (index % 9 == 0) 1.8f else if (index % 3 == 0) 1.1f else .65f
                drawCircle(Color.White.copy(alpha = if (index % 7 == 0) .28f else .12f), size, Offset(x, y))
            }
            drawOval(accent.copy(alpha = .055f), Offset(cx - w * .58f, cy - h * .12f), androidx.compose.ui.geometry.Size(w * 1.16f, h * .25f), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
            drawOval(Color(0xFF69E9D8).copy(alpha = .035f), Offset(cx - w * .72f, cy - h * .18f), androidx.compose.ui.geometry.Size(w * 1.44f, h * .35f), style = androidx.compose.ui.graphics.drawscope.Stroke(18.dp.toPx()))
        }
        points.forEach { point ->
            val sx = sin(point.theta) * cos(point.phi); val sy = sin(point.theta) * sin(point.phi); val sz = cos(point.theta)
            val cyaw = cos(yaw); val syaw = sin(yaw); val x1 = sx * cyaw - sz * syaw; val z1 = sx * syaw + sz * cyaw
            val cp = cos(pitch); val sp = sin(pitch); val y1 = sy * cp - z1 * sp; val z2 = sy * sp + z1 * cp
            val scale = (1f + z2 * .42f).coerceIn(.55f, 1.34f)
             val size = (20f + point.popularity * 30f) * scale
             val x = cx + x1 * w * .55f; val y = cy + y1 * h * .40f
             val color = if (point.popularity > .72f) Color(0xFFFFD19A) else if (point.song.albumArtUri != null) accent else Color(0xFF69E9D8)
             Box(
                 Modifier.offset { IntOffset((x - size / 2).roundToInt(), (y - size / 2).roundToInt()) }
                     .size(with(density) { size.toDp() }).graphicsLayer {
                         alpha = (.30f + point.popularity * .48f + scale * .14f).coerceIn(.34f, .92f)
                         scaleX = scale; scaleY = scale
                     }
                    .shadow((4 + scale * 10).dp, CircleShape, ambientColor = color, spotColor = color)
                    .clip(CircleShape).background(Brush.radialGradient(listOf(Color.White.copy(alpha = .75f), color, Color(0xFF090B18))))
                    .clickable { onPlay(point.song) }, contentAlignment = Alignment.Center
            ) {
                if (!point.song.albumArtUri.isNullOrBlank()) AsyncImage(point.song.albumArtUri, point.song.title, Modifier.fillMaxSize().clip(CircleShape), contentScale = ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color.White.copy(alpha = .25f), Color.Transparent))))
            }
        }
        // Offset is not automatically saveable; keeping it in composition avoids a
        // crash when the galaxy screen is first opened on devices restoring state.
        var buttonOffset by remember { mutableStateOf(Offset.Zero) }
        var buttonDragging by remember { mutableStateOf(false) }
        Box(Modifier.fillMaxSize()) {
          Box(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 24.dp)
              .offset { IntOffset(buttonOffset.x.roundToInt(), buttonOffset.y.roundToInt()) }.size(210.dp)) {
             GalaxyPetalMenu(controlsOpen, filter, MaterialTheme.colorScheme.primary,
                 onSearch = { controlsOpen = false; searchOpen = true },
                 onFilter = { filter = it; controlsOpen = false })
            Box(Modifier.align(Alignment.Center).size(if (floatingStyle.isSculptedFloatingStyle) 56.dp else 46.dp)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { buttonDragging = true },
                        onDragEnd = { buttonDragging = false },
                        onDragCancel = { buttonDragging = false },
                        onDrag = { change, dragAmount -> change.consume(); buttonOffset += dragAmount }
                    )
                }
                .clickable(enabled = !buttonDragging) { buttonClickEffect++; controlsOpen = !controlsOpen; if (controlsOpen) searchOpen = false }) {
                FloatingButtonFace(floatingStyle, playing, Modifier.fillMaxSize(), buttonClickEffect, Icons.Default.Settings)
            }
          }
        }
        if (searchOpen) {
            Column(Modifier.align(Alignment.BottomCenter).imePadding().navigationBarsPadding()
                .padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF101523).copy(alpha = .97f))
                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .28f), RoundedCornerShape(24.dp))
                .padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("探索银河", color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    IconButton({ searchOpen = false }) { Icon(Icons.Default.Close, "关闭搜索", tint = Color.White.copy(alpha = .6f)) }
                }
                OutlinedTextField(
                    value = query, onValueChange = onQueryChange, singleLine = true,
                    placeholder = { Text("歌曲、专辑或艺术家", fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = { if (query.isNotBlank()) IconButton({ onQueryChange("") }) { Icon(Icons.Default.Close, "清除搜索") } },
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = Color.White.copy(alpha = .15f),
                        focusedContainerColor = Color.White.copy(alpha = .035f), unfocusedContainerColor = Color.White.copy(alpha = .035f))
                )
                Text("${visibleSongs.size} 颗音乐星体", color = Color.White.copy(alpha = .4f), fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp))
            }
        }
        if (scanEnabled) IconButton(onClick = onScan, modifier = Modifier.align(Alignment.BottomEnd).padding(18.dp)) {
            Icon(Icons.Default.Refresh, "扫描音乐库", tint = Color.White.copy(alpha = .55f))
        }
        if (visibleSongs.isEmpty()) {
            Text("没有找到匹配的音乐", color = Color.White.copy(alpha = .65f), modifier = Modifier.align(Alignment.Center))
        }
        Column(Modifier.align(Alignment.TopCenter).statusBarsPadding()) {
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides Color.White) {
            PageTopBar("音乐库", onMenu, immersive = true) {
                androidx.compose.material3.TextButton(
                    onClick = { onRandomPlay(visibleSongs) },
                    enabled = visibleSongs.isNotEmpty(),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Icon(Icons.Default.Shuffle, "随心听", Modifier.size(17.dp))
                    Spacer(Modifier.width(3.dp))
                    Text("随心听", fontSize = 12.sp)
                }
                IconButton({ searchOpen = !searchOpen }) { Icon(Icons.Default.Search, "搜索音乐库") }
                IconButton(onScan, enabled = scanEnabled) { Icon(Icons.Default.Refresh, "扫描音乐库") }
            }
            }
        }
    }
}

private data class GalaxyPetal(val label: String, val icon: ImageVector)

/** A compact five-petal constellation; the center remains the shared floating face. */
@Composable
private fun BoxScope.GalaxyPetalMenu(
    expanded: Boolean, selected: String, accent: Color,
    onSearch: () -> Unit, onFilter: (String) -> Unit
) {
    val petals = remember { listOf(
        GalaxyPetal("搜索", Icons.Default.Search),
        GalaxyPetal("全部", Icons.Default.AutoAwesome),
        GalaxyPetal("歌曲", Icons.Default.MusicNote),
        GalaxyPetal("专辑", Icons.Default.Album),
        GalaxyPetal("高频", Icons.Default.LocalFireDepartment)
    ) }
    val reveal by animateFloatAsState(if (expanded) 1f else 0f, tween(260), label = "constellationReveal")
    if (reveal > .001f) Canvas(Modifier.fillMaxSize()) {
        val radius = 60.dp.toPx() * reveal
        drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = .09f * reveal), Color.Transparent)), 94.dp.toPx())
        drawCircle(accent.copy(alpha = .12f * reveal), radius, style = androidx.compose.ui.graphics.drawscope.Stroke(.7.dp.toPx()))
        repeat(5) { index ->
            val angle = (-90f - index * 72f) * PI.toFloat() / 180f
            val direction = Offset(cos(angle), sin(angle))
            drawLine(accent.copy(alpha = .27f * reveal), center + direction * 29.dp.toPx(), center + direction * radius,
                .8.dp.toPx(), StrokeCap.Round)
        }
    }
    petals.forEachIndexed { index, petal ->
        val progress by animateFloatAsState(if (expanded) 1f else 0f,
            tween(if (expanded) 340 else 180, delayMillis = if (expanded) index * 32 else 0, easing = FastOutSlowInEasing), label = "petal$index")
        if (progress > .001f) {
            val angle = (-90f - index * 72f) * PI.toFloat() / 180f
            val active = selected == petal.label
            val tint = if (active) accent else Color(0xFFC4CDDE)
            Column(
                Modifier.align(Alignment.Center).offset(x = (cos(angle) * 60f * progress).dp, y = (sin(angle) * 60f * progress).dp)
                    .graphicsLayer { alpha = progress; scaleX = .65f + .35f * progress; scaleY = scaleX; rotationZ = (1f - progress) * -16f }
                    .size(width = 48.dp, height = 52.dp)
                    .shadow(if (active) 12.dp else 5.dp, RoundedCornerShape(17.dp), ambientColor = accent, spotColor = accent)
                    .clip(RoundedCornerShape(17.dp))
                    .background(Brush.linearGradient(listOf(if (active) lerp(Color(0xFF151C2C), accent, .24f) else Color(0xFF222C40), Color(0xFF0D1422))))
                    .border(1.dp, Brush.linearGradient(listOf(tint.copy(alpha = if (active) .8f else .30f), tint.copy(alpha = .06f))), RoundedCornerShape(17.dp))
                    .clickable(enabled = expanded, role = Role.Button) { if (index == 0) onSearch() else onFilter(petal.label) }
                    .semantics { contentDescription = if (active) "${petal.label}，当前筛选" else petal.label },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
            ) {
                Icon(petal.icon, null, tint = tint, modifier = Modifier.size(19.dp))
                Spacer(Modifier.height(3.dp))
                Text(petal.label, color = tint, fontSize = 10.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}
