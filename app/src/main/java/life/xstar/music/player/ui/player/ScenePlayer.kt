package life.xstar.music.player.ui.player

import android.animation.ValueAnimator
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import life.xstar.music.player.data.local.SongEntity
import life.xstar.music.player.data.lyrics.LyricsState
import life.xstar.music.player.data.lyrics.LyricLine
import life.xstar.music.player.data.preferences.PlayerTheme
import life.xstar.music.player.data.preferences.LyricsMode
import life.xstar.music.player.data.preferences.isSpatial
import life.xstar.music.player.player.PlaybackMode
import kotlin.math.*
import kotlin.random.Random

internal data class ScenePalette(val background: Color, val ink: Color, val accent: Color)
private fun PlayerTheme.palette() = when (this) {
    PlayerTheme.Orbital -> ScenePalette(Color(0xFF100D1C), Color(0xFFF1EAFF), Color(0xFFC4B4FF))
    PlayerTheme.Theatre -> ScenePalette(Color(0xFF171512), Color(0xFFF7EDDB), Color(0xFFEDCF95))
    PlayerTheme.Liquid -> ScenePalette(Color(0xFF161329), Color(0xFFF7EAF4), Color(0xFFE5BCD7))
    PlayerTheme.Tape -> ScenePalette(Color(0xFFEAE4D9), Color(0xFF3B4038), Color(0xFFA77533))
    PlayerTheme.Classic -> ScenePalette(Color(0xFF090B10), Color.White, Color(0xFF78A9FF))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerThemePicker(selected: PlayerTheme, onSelect: (PlayerTheme) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color(0xFF171B23)) {
        Text("选择听歌空间", color = Color.White, fontSize = 23.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp))
        Text("即时切换，音乐继续播放", color = Color(0xFF969FAE), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 24.dp))
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 510.dp), contentPadding = PaddingValues(12.dp)) {
            items(PlayerTheme.entries, key = { it.name }) { theme ->
                val palette = theme.palette()
                ListItem(
                    headlineContent = { Text(theme.title, color = if (selected == theme) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) },
                    supportingContent = { Text(theme.subtitle, color = Color(0xFF969FAE), fontSize = 12.sp) },
                    leadingContent = {
                        Canvas(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(palette.background)) {
                            drawCircle(palette.accent.copy(alpha = .25f), size.minDimension * .34f)
                            drawCircle(palette.accent, size.minDimension * .25f, style = Stroke(1.5.dp.toPx()))
                            if (theme == PlayerTheme.Liquid) drawCircle(palette.background, 3.dp.toPx())
                        }
                    },
                    trailingContent = { if (selected == theme) Icon(Icons.Default.Check, "已选择", tint = MaterialTheme.colorScheme.primary) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable { onSelect(theme); onDismiss() }
                )
            }
        }
    }
}

private data class TouchEffect(val kind: String, val origin: Offset, val born: Long, val seed: Int = 0)
private fun nowMs() = android.os.SystemClock.uptimeMillis()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScenePlayer(
    theme: PlayerTheme, song: SongEntity?, lyrics: LyricsState, playing: Boolean,
    position: Long, duration: Long, favorite: Boolean, playbackMode: PlaybackMode,
    error: String?, connectionLabel: String?, onConnection: () -> Unit,
    onMenu: () -> Unit, onTheme: (PlayerTheme) -> Unit, onToggle: () -> Unit,
    onPrevious: () -> Unit, onNext: () -> Unit, onSeek: (Long) -> Unit,
    onFavorite: () -> Unit, onQueue: () -> Unit, onMode: () -> Unit,
    onDetails: () -> Unit, onFindLyrics: () -> Unit, onLyrics: () -> Unit,
    lyricsMode: LyricsMode = LyricsMode.Normal,
    backgroundContent: @Composable () -> Unit = {}
) {
    val palette = theme.palette().copy(accent = MaterialTheme.colorScheme.primary)
    val background by animateColorAsState(palette.background, tween(500), label = "sceneBackground")
    val motionEnabled = ValueAnimator.areAnimatorsEnabled()
    val effects = remember(theme) { mutableStateListOf<TouchEffect>() }
    var clock by remember { mutableLongStateOf(nowMs()) }
    var motionTime by remember { mutableFloatStateOf(0f) }
    val latestPlaying by rememberUpdatedState(playing)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(theme, motionEnabled) {
      lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
        var last = nowMs()
        while (isActive) {
            val now = nowMs()
            if (latestPlaying && motionEnabled) motionTime += (now - last) / 1000f
            clock = now
            effects.removeAll { now - it.born > 3200 }
            last = now
            delay(if (motionEnabled && (latestPlaying || effects.isNotEmpty())) 33 else 150)
        }
      }
    }
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(playing, motionEnabled) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (playing && motionEnabled) rotation.animateTo(rotation.value + 360f, tween(22_000, easing = LinearEasing))
        }
    }
    var sideB by rememberSaveable { mutableStateOf(false) }
    var captionIndex by remember { mutableIntStateOf(0) }
    var captionVisible by remember { mutableStateOf(false) }
    var metadataPulse by remember { mutableIntStateOf(0) }
    val titleBounce = remember { Animatable(0f) }
    var localSeek by remember(theme, song?.mediaStoreId) { mutableStateOf<Float?>(null) }
    val fraction = localSeek ?: (if (duration > 0) position.toFloat() / duration else 0f).coerceIn(0f, 1f)
    val lines = (lyrics as? LyricsState.Loaded)?.document?.lines.orEmpty()
    val timed = lines.any { it.timeMs != null }
    val currentLine = if (timed) lines.indexOfLast { (it.timeMs ?: Long.MAX_VALUE) <= position }.coerceAtLeast(0) else 0
    var browseLine by remember(song?.mediaStoreId, lyrics) { mutableStateOf<Int?>(null) }
    val previewLine = if (theme == PlayerTheme.Theatre && localSeek != null && timed)
        lines.indexOfLast { (it.timeMs ?: Long.MAX_VALUE) <= (fraction * duration).toLong() }.coerceAtLeast(0) else null
    val focusedLine = previewLine ?: browseLine ?: currentLine
    LaunchedEffect(browseLine) { if (browseLine != null) { delay(4500); browseLine = null } }
    LaunchedEffect(theme, focusedLine) {
        if (lyricsMode == LyricsMode.Normal && theme == PlayerTheme.Theatre && lines.isNotEmpty() && motionEnabled) effects.add(TouchEffect("lyrics", Offset(.5f, .44f), nowMs(), focusedLine))
    }
    LaunchedEffect(metadataPulse, song?.mediaStoreId) {
        if (motionEnabled) { titleBounce.snapTo(1f); titleBounce.animateTo(0f, tween(850)) }
        if (captionVisible) { delay(3500); captionVisible = false }
    }
    fun addEffect(kind: String, point: Offset) {
        if (effects.size > 120) effects.removeAt(0)
        effects.add(TouchEffect(kind, point, nowMs(), Random.nextInt()))
    }
    fun browse(direction: Int) {
        if (lines.isEmpty()) return
        val index = (focusedLine + direction).coerceIn(0, lines.lastIndex)
        browseLine = index
        lines[index].timeMs?.let(onSeek)
    }
    val currentToggle by rememberUpdatedState(onToggle)
    val currentNext by rememberUpdatedState(onNext)
    val currentPrevious by rememberUpdatedState(onPrevious)
    val currentSeek by rememberUpdatedState(onSeek)
    val currentBrowse by rememberUpdatedState<(Int) -> Unit>(::browse)
    val currentDuration by rememberUpdatedState(duration)
    BoxWithConstraints(Modifier.fillMaxSize().background(background)) {
        backgroundContent()
        val compact = maxHeight < 650.dp
        val pageHeight = maxHeight
        // Anchors stay independent of metadata length, missing lyrics and lyric changes.
        val sceneHeight = maxHeight * if (theme == PlayerTheme.Theatre) .67f else .50f
        val fullScreenLight = lyricsMode == LyricsMode.ForestLight && lines.isNotEmpty()
        Canvas(Modifier.fillMaxSize()) {
            if (theme == PlayerTheme.Orbital || theme == PlayerTheme.Liquid || theme == PlayerTheme.Theatre) {
                drawRect(Brush.radialGradient(listOf(palette.accent.copy(alpha = if (theme == PlayerTheme.Theatre) .09f else .18f), Color.Transparent), Offset(size.width * .65f, size.height * .48f), size.width))
            }
            if (theme == PlayerTheme.Orbital) repeat(50) { i ->
                val x = ((i * 137.3f) % size.width)
                val y = ((i * 83.7f + motionTime * 1.4f) % size.height)
                drawCircle(palette.ink.copy(alpha = .15f + (i % 4) * .1f), if (i % 5 == 0) 1.4f else .8f, Offset(x, y))
            }
        }
            Box(
                Modifier.offset(y = if (fullScreenLight) 0.dp else pageHeight * .23f)
                    .fillMaxWidth().height(if (fullScreenLight) pageHeight else sceneHeight).semantics {
                    contentDescription = "${theme.title}场景，轻触播放或暂停，左右滑动切歌，长按体验互动"
                    onClick(label = if (playing) "暂停" else "播放") { currentToggle(); true }
                    progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                    setProgress { value -> if (currentDuration > 0) currentSeek((value.coerceIn(0f, 1f) * currentDuration).toLong()); true }
                }.pointerInput(theme, song?.mediaStoreId, lyricsMode) {
                    // Non-normal modes use the topmost unified playback surface below.
                    if (lyricsMode != LyricsMode.Normal) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val start = down.position
                        var point = start
                        var longPress = false
                        var cancelled = false
                        val contour = sceneContour(theme, Size(size.width.toFloat(), size.height.toFloat()))
                        val nearest = nearestOnContour(contour, point)
                        val seekGesture = theme != PlayerTheme.Theatre && nearest.second < 20.dp.toPx() && currentDuration > 0
                        var lastTrail = 0L
                        fun normalize(p: Offset) = Offset(p.x / size.width, p.y / size.height)
                        if (seekGesture) { down.consume(); localSeek = nearest.first }
                        while (true) {
                            val event = withTimeoutOrNull(45L) { awaitPointerEvent() }
                            val change = event?.changes?.firstOrNull { it.id == down.id }
                            if (change != null) point = change.position
                            if (seekGesture) {
                                localSeek = nearestOnContour(contour, point).first
                                change?.consume()
                                if (nowMs() - lastTrail > 70 && motionEnabled) { addEffect("trail", normalize(point)); lastTrail = nowMs() }
                            } else if (!longPress && nowMs() - down.uptimeMillis > 550 && (point - start).getDistance() < viewConfiguration.touchSlop * 1.5f) {
                                longPress = true
                                change?.consume()
                                when (theme) {
                                    PlayerTheme.Orbital -> addEffect("meteor", normalize(point))
                                    PlayerTheme.Theatre -> addEffect("lyrics", normalize(point))
                                    PlayerTheme.Liquid -> addEffect("ink", normalize(point))
                                    PlayerTheme.Tape -> { sideB = !sideB; addEffect("flip", normalize(point)) }
                                    else -> Unit
                                }
                            } else if (longPress && nowMs() - lastTrail > 40) {
                                change?.consume()
                                if (theme == PlayerTheme.Liquid) addEffect("ink", normalize(point))
                                lastTrail = nowMs()
                            }
                            if (change != null && !change.pressed) {
                                if (seekGesture) { localSeek?.let { currentSeek((it * currentDuration).toLong()) }; localSeek = null }
                                else if (!longPress) {
                                    val delta = point - start
                                    when {
                                        abs(delta.x) > 55.dp.toPx() && abs(delta.x) > abs(delta.y) -> { change.consume(); if (delta.x < 0) currentNext() else currentPrevious() }
                                        theme == PlayerTheme.Theatre && abs(delta.y) > 35.dp.toPx() -> { change.consume(); currentBrowse(if (delta.y < 0) 1 else -1) }
                                        delta.getDistance() < viewConfiguration.touchSlop -> { change.consume(); addEffect("tap", normalize(point)); if (theme == PlayerTheme.Liquid) addEffect("ink", normalize(point)); currentToggle() }
                                    }
                                }
                                break
                            }
                            if (event != null && event.changes.none { it.pressed }) { cancelled = true; break }
                        }
                        if (cancelled) localSeek = null
                    }
                }, contentAlignment = Alignment.Center
            ) {
                if (lyricsMode == LyricsMode.ForestLight && lines.isNotEmpty()) {
                    SpatialLyrics(lines, currentLine, position, playing, lyricsMode, palette.accent, palette.ink,
                        Modifier.fillMaxSize())
                } else SceneArtwork(theme, song?.albumArtUri, palette, playing, motionTime, rotation.value, sideB, effects.any { it.kind == "feed" && clock - it.born < 850 }, effects.any { it.kind == "flip" && clock - it.born < 600 })
                if (theme == PlayerTheme.Theatre && (lyricsMode != LyricsMode.ForestLight || lines.isEmpty())) {
                    if (lines.isNotEmpty()) {
                        if (lyricsMode == LyricsMode.DensityMap) {
                            WholeLyricsStage(lines, focusedLine, LyricsMode.DensityMap, playing, position, palette,
                                Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 8.dp),
                                onLine = { index -> browseLine = index; lines[index].timeMs?.let(onSeek) })
                        } else if (lyricsMode.isSpatial) {
                            SpatialLyrics(lines, focusedLine, position, playing, lyricsMode, palette.accent, palette.ink,
                                Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 8.dp))
                        } else if (lyricsMode == LyricsMode.Firework) {
                            FireworkLyrics(lines, focusedLine, position, playing, palette.accent, palette.ink,
                                Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 8.dp).pointerInput(Unit) {
                                    awaitEachGesture {
                                        val down = awaitFirstDown(pass = PointerEventPass.Initial)
                                        down.consume()
                                        val start = down.position
                                        var dragged = false
                                        while (true) {
                                            val event = awaitPointerEvent(PointerEventPass.Initial)
                                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                             val delta = change.position - start
                                            if (delta.getDistance() > viewConfiguration.touchSlop) dragged = true
                                            change.consume()
                                            if (!change.pressed) {
                                                when {
                                                    dragged && abs(delta.x) > 55.dp.toPx() && abs(delta.x) > abs(delta.y) ->
                                                        if (delta.x < 0) currentNext() else currentPrevious()
                                                    !dragged -> currentToggle()
                                                }
                                                break
                                            }
                                        }
                                    }
                                })
                        } else if (lyricsMode == LyricsMode.RiftFall) {
                            RiftTheatreLyrics(lines, focusedLine, position, playing, palette.accent, palette.ink,
                                Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 8.dp),
                                onLine = { index -> browseLine = index; lines[index].timeMs?.let(onSeek) },
                                onToggle = onToggle, onPrevious = onPrevious, onNext = onNext)
                        } else if (lyricsMode == LyricsMode.PopBounce) {
                            PopBounceLyrics(lines, focusedLine, position, playing, palette.accent, palette.ink,
                                Modifier.fillMaxSize().padding(horizontal = 30.dp, vertical = 8.dp),
                                onLine = { index -> browseLine = index; lines[index].timeMs?.let(onSeek) })
                        } else TheatreLyricsStage(lines, focusedLine, playing, lyricsMode, palette,
                            Modifier.fillMaxSize().padding(horizontal = 30.dp, vertical = 8.dp),
                            onLine = { index -> browseLine = index; lines[index].timeMs?.let(onSeek) },
                            onToggle = onToggle, onPrevious = onPrevious, onNext = onNext)
                    } else Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                        Text(when (lyrics) { LyricsState.Loading -> "正在寻找这首歌的文字…"; is LyricsState.Error -> lyrics.message; is LyricsState.Candidates -> "找到歌词，选择一个版本"; else -> "让每一句，都有光。" }, color = palette.accent, fontSize = 22.sp, textAlign = TextAlign.Center)
                        Text(if (lyrics == LyricsState.Loading) "" else "暂未找到歌词", color = palette.accent.copy(alpha = .4f), fontSize = 12.sp, modifier = Modifier.padding(top = 16.dp))
                    }
                }
                if (theme != PlayerTheme.Theatre && lyricsMode != LyricsMode.ForestLight) Canvas(Modifier.fillMaxSize()) { drawSceneProgress(theme, palette.accent, fraction) }
                Canvas(Modifier.fillMaxSize()) { effects.forEach { drawTouchEffect(it, clock, palette.accent, motionEnabled) } }
                androidx.compose.animation.AnimatedVisibility(visible = localSeek != null && theme != PlayerTheme.Theatre,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp)) {
                    Text("${formatSceneTime((fraction * duration).toLong())}  /  ${formatSceneTime(duration)}", color = palette.ink.copy(alpha = .75f), fontSize = 10.sp, letterSpacing = 1.sp,
                        modifier = Modifier.semantics { contentDescription = "播放进度 ${formatSceneTime(position)}，总时长 ${formatSceneTime(duration)}" })
                }
                if (lyricsMode != LyricsMode.Normal) LyricsPlaybackTouchSurface(
                    playing, onToggle, onPrevious, onNext, Modifier.matchParentSize())
            }
            if (theme != PlayerTheme.Theatre && (lyricsMode != LyricsMode.ForestLight || lines.isEmpty())) {
              Box(Modifier.offset(y = pageHeight * .78f - 48.dp).fillMaxWidth().height(96.dp), contentAlignment = Alignment.Center) {
                   if (lines.isNotEmpty() && lyricsMode.isSpatial) SpatialLyrics(lines, currentLine, position, playing, lyricsMode, palette.accent, palette.ink,
                       Modifier.fillMaxSize().padding(horizontal = 20.dp), compact = true,
                       onToggle = onToggle, onPrevious = onPrevious, onNext = onNext)
                   else if (lines.isNotEmpty() && lyricsMode == LyricsMode.Firework) FireworkLyrics(lines, currentLine, position, playing, palette.accent, palette.ink,
                       Modifier.fillMaxSize().padding(horizontal = 20.dp), compact = true)
                   else if (lines.isNotEmpty() && lyricsMode == LyricsMode.RiftFall) RiftFallLyrics(lines, currentLine, position, playing, palette.accent, palette.ink,
                      Modifier.fillMaxSize().padding(horizontal = 20.dp), compact = true, onLine = { index -> lines[index].timeMs?.let(onSeek) })
                  else if (lines.isNotEmpty() && lyricsMode == LyricsMode.PopBounce) PopBounceLyrics(lines, currentLine, position, playing, palette.accent, palette.ink,
                     Modifier.fillMaxSize().padding(horizontal = 20.dp), compact = true, onLine = { index -> lines[index].timeMs?.let(onSeek) })
                 else if (lines.isNotEmpty() && lyricsMode == LyricsMode.NeonStorm) NeonStormLyrics(lines, currentLine, playing, palette.accent, palette.ink,
                    Modifier.fillMaxSize().padding(horizontal = 20.dp), compact = true, onLine = { index -> lines[index].timeMs?.let(onSeek) })
                else if (lines.isNotEmpty()) ThreeLineLyrics(lines, currentLine, palette, theatre = false, motionEnabled = motionEnabled,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp), onLine = { index -> lines[index].timeMs?.let(onSeek) })
                else Box(Modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.Center) {
                    Text(when (lyrics) { LyricsState.Loading -> "正在加载歌词…"; is LyricsState.Candidates -> "点击选择歌词版本"; else -> "暂无歌词 · 点击查找" },
                        color = palette.ink.copy(alpha = .35f), fontSize = 12.sp,
                        modifier = Modifier.clickable { if (lyrics is LyricsState.Candidates) onLyrics() else onFindLyrics() }.padding(12.dp))
                 }
                 if (lyricsMode != LyricsMode.Normal && lines.isNotEmpty()) LyricsPlaybackTouchSurface(
                     playing, onToggle, onPrevious, onNext, Modifier.matchParentSize())
               }
            }
            if (theme == PlayerTheme.Theatre) {
                TheatreTitleProgress(
                    title = song?.title ?: "选择一首歌曲",
                    artist = song?.let { listOf(it.artist, it.album).filter(String::isNotBlank).joinToString(" · ") } ?: "从音乐库开始播放",
                    fraction = fraction, position = (fraction * duration).toLong(), duration = duration,
                    scrubbing = localSeek != null, palette = palette, compact = compact,
                    motionEnabled = motionEnabled, onPreview = { localSeek = it },
                    onSeek = { browseLine = null; onSeek(it) }, onDetails = onDetails,
                    modifier = Modifier.offset(y = pageHeight * .15f - 27.dp).fillMaxWidth().padding(horizontal = 32.dp)
                )
            } else Column(Modifier.offset(y = pageHeight * .15f - 27.dp).fillMaxWidth().padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(song?.title ?: "选择一首歌曲", color = palette.ink.copy(alpha = .9f), fontSize = if (compact) 21.sp else 23.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                        modifier = Modifier.graphicsLayer { translationY = -sin(titleBounce.value * PI).toFloat() * 10f; rotationZ = sin(titleBounce.value * PI * 2).toFloat() * 2f }.clickable {
                            captionIndex++; captionVisible = true; metadataPulse++; addEffect("love", Offset(.5f, .8f))
                        })
                    Text(song?.let { listOf(it.artist, it.album).filter { value -> value.isNotBlank() }.joinToString(" · ") } ?: "从音乐库开始播放", color = palette.ink.copy(alpha = .4f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp).clickable(onClick = onDetails))
                    Canvas(Modifier.padding(top = 5.dp).size(14.dp, 7.dp)) {
                        repeat(3) { i ->
                            val level = if (playing && motionEnabled) .3f + .7f * abs(sin(motionTime * 3f + i)) else .2f
                            val x = size.width * (i + 1) / 4
                            drawLine(palette.accent.copy(alpha = .5f), Offset(x, size.height * (.5f - level / 2)), Offset(x, size.height * (.5f + level / 2)), 1.dp.toPx(), StrokeCap.Round)
                        }
                    }
            Box(Modifier.height(32.dp), contentAlignment = Alignment.Center) {
                androidx.compose.animation.AnimatedVisibility(visible = captionVisible) { Text(mood(theme, captionIndex), color = palette.accent.copy(alpha = .8f), fontSize = 10.sp, letterSpacing = 1.sp) }
            }
            }
            connectionLabel?.let {
                Text(it, color = palette.accent, fontSize = 11.sp, modifier = Modifier.align(Alignment.TopCenter).padding(top = 24.dp).clip(CircleShape).clickable(onClick = onConnection).padding(8.dp))
            }
            error?.let { Text(it, color = Color(0xFFE68D9F), fontSize = 11.sp, maxLines = 2, modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 32.dp, vertical = 24.dp)) }
    }
}

private fun formatSceneTime(value: Long): String { val seconds = value.coerceAtLeast(0) / 1000; return "%d:%02d".format(seconds / 60, seconds % 60) }

@Composable
private fun TheatreLyricsStage(
    lines: List<LyricLine>, focused: Int, playing: Boolean, lyricsMode: LyricsMode,
    palette: ScenePalette, modifier: Modifier = Modifier, onLine: (Int) -> Unit,
    onToggle: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit
) {
    val latestFocused by rememberUpdatedState(focused)
    val latestLine by rememberUpdatedState(onLine)
    val latestToggle by rememberUpdatedState(onToggle)
    val latestPrevious by rememberUpdatedState(onPrevious)
    val latestNext by rememberUpdatedState(onNext)
    val first = (focused - 4).coerceIn(0, (lines.size - 10).coerceAtLeast(0))
    BoxWithConstraints(modifier.clip(RoundedCornerShape(8.dp)).pointerInput(lines) {
        awaitEachGesture {
            // Own the entire stage gesture before the scene and lyric effects see it.
            val down = awaitFirstDown(pass = PointerEventPass.Initial)
            down.consume()
            val start = down.position
            val initialLine = latestFocused
            var lastLine = initialLine
            var direction = 0
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    val delta = change.position - start
                    if (direction == 0 && delta.getDistance() > viewConfiguration.touchSlop) {
                        direction = if (abs(delta.y) >= abs(delta.x)) 1 else 2
                    }
                    change.consume()
                    if (direction == 1) {
                        val rowHeight = minOf(size.height / 11f, 34.dp.toPx()).coerceAtLeast(1f)
                        val target = (initialLine - (delta.y / rowHeight).roundToInt()).coerceIn(0, lines.lastIndex)
                        if (target != lastLine) { lastLine = target; latestLine(target) }
                    }
                    if (!change.pressed) {
                        if (direction == 0) latestToggle()
                        else if (direction == 2 && abs(delta.x) > 55.dp.toPx()) {
                            if (delta.x < 0) latestNext() else latestPrevious()
                        }
                        break
                    }
                }
            } finally {
                // Playback already follows each crossed lyric; cancellation needs no seek.
            }
        }
    }) {
        val rowHeight = minOf(maxHeight / 11, 34.dp)
        Column(Modifier.fillMaxWidth().align(Alignment.Center)) {
            (first until minOf(first + 10, lines.size)).forEach { index ->
                key(index) {
                    val distance = abs(index - focused)
                    val opacity = when (distance) { 0 -> 1f; 1 -> .75f; 2 -> .60f; 3 -> .48f; else -> .36f }
                    Box(Modifier.fillMaxWidth().height(if (index == focused) rowHeight * 2 else rowHeight).semantics {
                        contentDescription = lines[index].text
                        if (lines[index].timeMs != null) onClick("播放这一句") { latestLine(index); true }
                    }, contentAlignment = Alignment.Center) {
                        if (index == focused) {
                            // Flip a second rendering vertically: the gradient fades away from the mirror edge.
                            Text(lines[index].text.ifBlank { "♪" },
                                fontSize = 29.sp, fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = TextStyle(brush = Brush.verticalGradient(listOf(Color.Transparent, palette.accent.copy(alpha = .25f)))),
                                modifier = Modifier.fillMaxWidth().offset(y = 22.dp).graphicsLayer { scaleY = -1f })
                        }
                        if (index == focused && lyricsMode == LyricsMode.NeonStorm) {
                             NeonStormLyrics(listOf(lines[index]), 0, playing, palette.accent, palette.ink,
                                Modifier.fillMaxWidth().height(rowHeight * 1.4f).offset(y = (-8).dp), compact = false)
                        } else Text(lines[index].text.ifBlank { "♪" },
                            color = if (index == focused) palette.accent else palette.ink.copy(alpha = opacity),
                            fontSize = if (index == focused) 29.sp else 15.sp,
                            fontWeight = if (index == focused) FontWeight.SemiBold else FontWeight.Normal,
                            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().offset(y = if (index == focused) (-8).dp else 0.dp),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** The song's name is the timeline: a warm spotlight reveals it as playback advances. */
@Composable
private fun TheatreTitleProgress(
    title: String, artist: String, fraction: Float, position: Long, duration: Long,
    scrubbing: Boolean, palette: ScenePalette, compact: Boolean, motionEnabled: Boolean,
    onPreview: (Float?) -> Unit, onSeek: (Long) -> Unit, onDetails: () -> Unit,
    modifier: Modifier = Modifier
) {
    var titleWidth by remember(title) { mutableFloatStateOf(0f) }
    var hint by remember(title) { mutableStateOf(false) }
    val density = LocalDensity.current
    val trackWidth = maxOf(with(density) { 180.dp.toPx() }, titleWidth)
    val latestPreview by rememberUpdatedState(onPreview)
    val latestSeek by rememberUpdatedState(onSeek)
    val latestTrackWidth by rememberUpdatedState(trackWidth)
    val emphasis by animateFloatAsState(if (scrubbing) 1f else 0f, tween(if (motionEnabled) 200 else 0), label = "titleScrub")
    val gold = palette.accent
    LaunchedEffect(hint) { if (hint) { delay(2800); hint = false } }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            contentDescription = "$title，滑动歌名调整进度"
            stateDescription = "${formatSceneTime(position)}，总时长 ${formatSceneTime(duration)}"
            progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
            if (duration > 0) setProgress { value -> latestSeek((value.coerceIn(0f, 1f) * duration).toLong()); true }
            else disabled()
        }.pointerInput(title, duration) {
            if (duration <= 0) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown()
                down.consume()
                var dragging = false
                fun fractionAt(x: Float): Float {
                    val width = latestTrackWidth.coerceAtMost(size.width.toFloat()).coerceAtLeast(1f)
                    return ((x - (size.width - width) / 2) / width).coerceIn(0f, 1f)
                }
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (abs(change.position.x - down.position.x) > viewConfiguration.touchSlop) dragging = true
                        if (dragging) { hint = false; latestPreview(fractionAt(change.position.x)) }
                        change.consume()
                        if (!change.pressed) {
                            if (dragging) latestSeek((fractionAt(change.position.x) * duration).toLong()) else hint = true
                            break
                        }
                    }
                } finally {
                    latestPreview(null)
                }
            }
        }, horizontalAlignment = Alignment.CenterHorizontally) {
            val edge = fraction.coerceIn(0f, 1f)
            Text(title, fontSize = if (compact) 23.sp else 26.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                onTextLayout = { titleWidth = it.size.width.toFloat() },
                style = TextStyle(brush = Brush.horizontalGradient(
                    0f to gold, maxOf(0f, edge - .035f) to gold,
                    edge to androidx.compose.ui.graphics.lerp(gold, Color.White, .65f),
                    minOf(1f, edge + .035f) to palette.ink.copy(alpha = .48f),
                    1f to palette.ink.copy(alpha = .48f), endX = titleWidth.coerceAtLeast(1f))),
                 modifier = Modifier.padding(top = 8.dp).clickable(onClick = onDetails))
            Canvas(Modifier.width(with(density) { trackWidth.toDp() }).height(22.dp)) {
                val head = Offset(size.width * fraction, center.y)
                drawLine(gold.copy(alpha = .10f), Offset(0f, center.y), Offset(size.width, center.y), .6.dp.toPx(), StrokeCap.Round)
                if (fraction > 0f) drawLine(gold.copy(alpha = .45f + emphasis * .35f), Offset(0f, center.y), head,
                    (.9f + emphasis).dp.toPx(), StrokeCap.Round)
                val radius = (8f + emphasis * 6f).dp.toPx()
                drawCircle(Brush.radialGradient(listOf(gold.copy(alpha = .22f + emphasis * .15f), Color.Transparent), head, radius), radius, head)
                drawCircle(gold.copy(alpha = .6f + emphasis * .4f), (1.2f + emphasis * 1.8f).dp.toPx(), head)
            }
        }
        Box(Modifier.fillMaxWidth().height(20.dp), contentAlignment = Alignment.Center) {
            Text(if (scrubbing) "${formatSceneTime(position)}  /  ${formatSceneTime(duration)}" else artist,
                color = if (scrubbing) gold else palette.ink.copy(alpha = .4f), fontSize = 11.sp,
                 maxLines = 1, overflow = TextOverflow.Ellipsis, letterSpacing = if (scrubbing) 1.sp else 0.sp,
                 modifier = Modifier.clickable(onClick = onDetails))
        }
        Box(Modifier.height(24.dp), contentAlignment = Alignment.Center) {
            Text(if (hint) "左右滑动歌名，调整播放进度" else if (scrubbing) "松手，唱到这一句" else "${formatSceneTime(position)}  /  ${formatSceneTime(duration)}",
                color = gold.copy(alpha = if (hint || scrubbing) .7f else .32f), fontSize = 9.sp, letterSpacing = .8.sp)
        }
    }
}
/** Keyed lines keep their identity as focus advances; the theatre opens a five-row stage. */
@Composable private fun ThreeLineLyrics(lines: List<LyricLine>, focused: Int, palette: ScenePalette, theatre: Boolean,
    motionEnabled: Boolean, playing: Boolean = false, animationTime: Float = 0f,
    modifier: Modifier = Modifier, onLine: (Int) -> Unit) {
    val rowHeight = if (theatre) 56.dp else 32.dp
    val visibleRadius = if (theatre) 2 else 1
    val density = LocalDensity.current
    Box(modifier.height(rowHeight * (visibleRadius * 2 + 1)).clip(RoundedCornerShape(8.dp))) {
        // One extra item on each side allows outgoing lines to animate beyond the clip.
        (maxOf(0, focused - visibleRadius - 1)..minOf(lines.lastIndex, focused + visibleRadius + 1)).forEach { index ->
            key(index) {
                val distance = index - focused
                val offset by animateFloatAsState(distance.toFloat(), tween(if (motionEnabled) 550 else 0), label = "threeLineOffset")
                val opacity by animateFloatAsState(when (abs(distance)) { 0 -> 1f; 1 -> if (theatre) .55f else .36f; 2 -> if (theatre) .34f else 0f; else -> 0f }, tween(if (motionEnabled) 450 else 0), label = "threeLineOpacity")
                val scale by animateFloatAsState(if (distance == 0) 1f else if (theatre) { if (abs(distance) == 1) .78f else .66f } else .92f, tween(if (motionEnabled) 600 else 0), label = "threeLineScale")
                val focus by animateFloatAsState(if (distance == 0) 1f else 0f, tween(if (motionEnabled) 600 else 0), label = "lyricFlameFocus")
                var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
                val burning = theatre && playing && motionEnabled
                val flameStrength by animateFloatAsState(if (burning) focus else 0f, tween(450), label = "flameStrength")
                val glow = if (burning) .65f + .25f * sin(animationTime * 6f) else .2f
                Text(lines[index].text.ifBlank { "♪" }, color = if (theatre && distance == 0) Color.Unspecified else if (distance == 0) palette.accent else palette.ink,
                    fontSize = if (theatre) 29.sp else 15.sp, fontWeight = if (theatre) FontWeight.SemiBold else if (distance == 0) FontWeight.Medium else FontWeight.Normal,
                    textAlign = if (theatre) TextAlign.Start else TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = if (theatre && distance == 0) TextStyle(
                        brush = Brush.linearGradient(listOf(palette.accent, androidx.compose.ui.graphics.lerp(palette.accent, Color.White, .65f), palette.accent),
                            start = Offset(-180f + (animationTime * 65f % 500f), 0f), end = Offset(240f + (animationTime * 65f % 500f), 65f)),
                        shadow = Shadow(palette.accent.copy(alpha = glow * focus), Offset.Zero, 16f + glow * 12f)
                    ) else TextStyle.Default,
                    onTextLayout = { layout = it },
                    modifier = Modifier.align(Alignment.Center).fillMaxWidth().height(rowHeight).graphicsLayer {
                        translationY = with(density) { rowHeight.toPx() } * offset
                        alpha = opacity; scaleX = scale; scaleY = scale
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(if (theatre) 0f else .5f, .5f)
                    }.semantics { if (abs(distance) > visibleRadius) hideFromAccessibility() }
                        .clickable(enabled = abs(distance) <= visibleRadius && lines[index].timeMs != null) { onLine(index) }
                        .padding(top = if (theatre) 14.dp else 5.dp, bottom = 5.dp)
                        .drawBehind {
                            if (flameStrength > .01f) layout?.let { text ->
                                val visibleEnd = text.getLineEnd(0, visibleEnd = true)
                                for (char in 0 until minOf(visibleEnd, 80)) {
                                    if (lines[index].text.getOrNull(char)?.isWhitespace() == true) continue
                                    val rect = text.getBoundingBox(char)
                                    if (rect.left >= size.width || rect.width < 1f) continue
                                    val x = rect.center.x
                                    val base = rect.top + rect.height * .45f
                                    val phase = animationTime * 5f + char * 1.7f
                                    val height = (10f + 13f * (.5f + .5f * sin(phase))) * this.density * flameStrength
                                    val width = minOf(rect.width * .27f, 5.dp.toPx())
                                    val sway = sin(phase * .8f) * 4.dp.toPx()
                                    val flame = Path().apply {
                                        moveTo(x - width, base)
                                        cubicTo(x - width * 1.6f, base - height * .4f, x + sway - width, base - height * .65f, x + sway, base - height)
                                        cubicTo(x + sway + width * .2f, base - height * .55f, x + width * 1.8f, base - height * .3f, x + width, base)
                                        close()
                                    }
                                    drawPath(flame, Brush.verticalGradient(listOf(Color.Transparent, palette.accent.copy(alpha = .5f * flameStrength), androidx.compose.ui.graphics.lerp(palette.accent, Color.White, .65f).copy(alpha = .7f * flameStrength)), base - height, base))
                                    val rise = (animationTime * .65f + char * .137f) % 1f
                                    drawCircle(palette.accent.copy(alpha = (1f - rise) * .7f * flameStrength), (1f - rise * .6f) * this.density,
                                        Offset(x + sin(phase) * 5.dp.toPx(), base - rise * 35.dp.toPx()))
                                }
                            }
                        })
            }
        }
    }
}
private fun mood(theme: PlayerTheme, index: Int): String {
    val values = when (theme) {
        PlayerTheme.Orbital -> listOf("正在逃离地心引力", "给今天留一颗星", "宇宙很大，这首刚好")
        PlayerTheme.Theatre -> listOf("这一句，替你说了", "把心情留在字里", "唱到这里，刚好想起你")
        PlayerTheme.Liquid -> listOf("这一圈，把时间放慢", "把声音刻进颜色", "旋转的是光，也是心情")
        PlayerTheme.Tape -> listOf("私人珍藏 · 请勿倒带青春", "这一面，留给好心情", "磁带会旧，喜欢不会")
        else -> listOf("下一站，暂时不用想", "窗外是城市，耳边是你", "陪你晚一点回家")
    }
    return values[index % values.size]
}

@Composable private fun SceneArtwork(theme: PlayerTheme, artwork: String?, palette: ScenePalette, playing: Boolean, time: Float, rotation: Float, sideB: Boolean, happy: Boolean, flip: Boolean) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val diameter = minOf(maxWidth * .70f, maxHeight * .76f)
        val phase = time * 2.7f
        when (theme) {
            PlayerTheme.Orbital -> Box(Modifier.size(diameter).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFFC5A4C9), Color(0xFF86759F), Color(0xFF282440))))) {
                Box(Modifier.fillMaxSize().graphicsLayer { rotationZ = time * 2.5f; scaleX = 1.45f; scaleY = 1.45f }) {
                    AsyncImage(artwork, null, Modifier.fillMaxSize().graphicsLayer { alpha = .45f }, contentScale = ContentScale.Crop)
                    Canvas(Modifier.fillMaxSize()) {
                        rotate(-25f) { repeat(13) { i -> drawOval(Color(0xFFEDC4DD).copy(alpha = if (i % 3 == 0) .17f else .06f), Offset(-size.width * .4f, size.height * (i / 13f) - size.height * .1f), Size(size.width * 1.8f, size.height * .18f)) } }
                    }
                }
                Canvas(Modifier.fillMaxSize()) {
                    drawCircle(Brush.radialGradient(listOf(Color.Transparent, Color(0xFF080615).copy(alpha = .86f)), Offset(size.width * .25f, size.height * .23f), size.width * .9f))
                }
            }
            PlayerTheme.Liquid -> Box(Modifier.size(diameter).graphicsLayer { rotationZ = rotation }.clip(CircleShape)
                .background(Brush.sweepGradient(listOf(Color(0xFF8374A6), Color(0xFFE6BCA4), Color(0xFF9EA9CA), Color(0xFF625982), Color(0xFFD7ACCC), Color(0xFF8374A6))))) {
                AsyncImage(artwork, null, Modifier.fillMaxSize().graphicsLayer { alpha = .38f }, contentScale = ContentScale.Crop)
                Canvas(Modifier.fillMaxSize()) {
                    for (i in 4..45) drawCircle(Color.White.copy(alpha = .055f), size.minDimension / 2 * i / 46, style = Stroke(.7f))
                    drawCircle(Color(0xFFB9AEC9).copy(alpha = .8f), size.minDimension * .12f)
                    drawCircle(palette.background, size.minDimension * .052f)
                    drawCircle(Color.White.copy(alpha = .4f), size.minDimension * .12f, style = Stroke(1f))
                    drawCircle(Color.White.copy(alpha = .2f), size.minDimension / 2 - 1f, style = Stroke(1f))
                }
            }
            else -> Canvas(Modifier.fillMaxSize()) {
                val w = size.width; val h = size.height
                when (theme) {
                    PlayerTheme.Tape -> {
                        val tw = w * .79f; val th = min(h * .55f, tw * .69f); val origin=Offset((w-tw)/2,h*.50f-th/2)
                        rotate(if(flip) 6f else -5f,Offset(w/2,h*.50f)) {
                            drawRoundRect(Color(0xFFE4A348),origin,Size(tw,th),CornerRadius(12.dp.toPx()))
                            drawRoundRect(Color(0xFFF0E8D5),origin+Offset(tw*.07f,th*.12f),Size(tw*.86f,th*.65f),CornerRadius(7.dp.toPx()))
                            val reelY=origin.y+th*.55f
                            drawRoundRect(Color(0xFF444B41),Offset(origin.x+tw*.12f,reelY-th*.13f),Size(tw*.76f,th*.26f),CornerRadius(th*.13f))
                            listOf(.23f,.77f).forEach { x -> val c=Offset(origin.x+tw*x,reelY);drawCircle(Color(0xFFDAD4C3),th*.1f,c);rotate(time*60,c) { repeat(8) { i -> val a=i*PI/4;drawLine(Color(0xFF66685B),c+Offset(cos(a).toFloat(),sin(a).toFloat())*th*.04f,c+Offset(cos(a).toFloat(),sin(a).toFloat())*th*.085f,3.dp.toPx()) } };drawCircle(Color(0xFF51584B),th*.028f,c) }
                            drawRoundRect(Color(0xFFAA7430),Offset(origin.x+tw*.25f,origin.y+th*.84f),Size(tw*.5f,th*.1f),CornerRadius(5.dp.toPx()))
                            listOf(.04f,.96f).forEach { x ->drawCircle(Color(0xFF8A612B),2.dp.toPx(),origin+Offset(tw*x,th*.06f)) }
                        }
                    }
                    else -> Unit
                }
            }
        }
        if(theme==PlayerTheme.Tape) Column(Modifier.offset(y=(-maxHeight*.075f)).graphicsLayer { rotationZ=-5f }.width(maxWidth*.58f)) {
            Text("SIDE ${if(sideB) "B" else "A"} / PERSONAL MIX",color=Color(0xFF6A6558),fontSize=8.sp,letterSpacing=1.sp)
            Text(if(sideB) "One more time." else "Good old days.",color=Color(0xFF363D36),fontSize=21.sp,fontWeight=FontWeight.Medium)
        }
    }
}

/** Shared geometry for drawing and hit testing: the visible contour is the seek target. */
internal fun sceneContour(theme: PlayerTheme, size: Size): List<Offset> {
    val w=size.width;val h=size.height
    return (0..240).map { i ->
        val t=i/240f; val angle=-PI/2+t*2*PI
        when(theme) {
            PlayerTheme.Orbital -> { val a=t*2*PI;val x=cos(a).toFloat()*w*.40f;val y=sin(a).toFloat()*h*.21f;val tilt=-.42f;Offset(w*.5f+x*cos(tilt)-y*sin(tilt),h*.50f+x*sin(tilt)+y*cos(tilt)) }
            PlayerTheme.Liquid -> { val r=min(w*.35f,h*.38f)+min(w,h)*.025f;Offset(w*.5f+cos(angle).toFloat()*r,h*.50f+sin(angle).toFloat()*r) }
            PlayerTheme.Theatre -> Offset(w*(.09f+.82f*t),h*.79f)
            PlayerTheme.Tape -> Offset(w*(.17f+.66f*t),h*(.63f-.035f*t))
            else -> Offset(w*t,h*.8f)
        }
    }
}
internal fun nearestOnContour(points: List<Offset>, point: Offset): Pair<Float, Float> {
    val index=points.indices.minByOrNull { (points[it]-point).getDistanceSquared() } ?: 0
    return index.toFloat()/(points.size-1).coerceAtLeast(1) to (points.getOrElse(index){Offset.Zero}-point).getDistance()
}
private fun DrawScope.drawSceneProgress(theme: PlayerTheme, color: Color, fraction: Float) {
    val points=sceneContour(theme,size)
    fun path(end: Int) = Path().apply { points.take(end+1).forEachIndexed { i,p -> if(i==0)moveTo(p.x,p.y) else lineTo(p.x,p.y) } }
    drawPath(path(points.lastIndex),color.copy(alpha=.18f),style=Stroke(.7.dp.toPx(),cap=StrokeCap.Round))
    val end=(fraction.coerceIn(0f,1f)*points.lastIndex).roundToInt()
    if(end>0)drawPath(path(end),color.copy(alpha=.75f),style=Stroke(1.1.dp.toPx(),cap=StrokeCap.Round))
    drawCircle(color.copy(alpha=.12f),7.dp.toPx(),points[end]);drawCircle(color,2.5.dp.toPx(),points[end])
}
private fun DrawScope.drawTouchEffect(effect: TouchEffect, now: Long, color: Color, motion: Boolean) {
    val age=(now-effect.born)/1000f;val p=Offset(effect.origin.x*size.width,effect.origin.y*size.height)
    val fade=(1-age/2.5f).coerceIn(0f,1f)
    if(!motion){if(age<.8f)drawCircle(color.copy(alpha=.15f),18.dp.toPx(),p);return}
    when(effect.kind) {
        "fog" -> drawCircle(Color(0xFFC6E5E0).copy(alpha=.22f*fade),9.dp.toPx(),p)
        "ink" -> drawCircle(Brush.radialGradient(listOf(Color(0xFFD8A7DE).copy(alpha=.45f*fade),Color(0xFFACB3EF).copy(alpha=.15f*fade),Color.Transparent),p,15.dp.toPx()+age*65.dp.toPx()),15.dp.toPx()+age*65.dp.toPx(),p)
        "meteor" -> repeat(7) { i -> val t=(age-i*.11f).coerceAtLeast(0f);if(t in 0f..1.3f){val head=Offset(size.width*(.1f+(i*.137f)% .75f)+t*130.dp.toPx(),size.height*(.65f-(i*.11f)% .5f)-t*95.dp.toPx());drawLine(Brush.linearGradient(listOf(Color.Transparent,color.copy(alpha=(1-t/1.3f).coerceIn(0f,1f))),head-Offset(65.dp.toPx(),-45.dp.toPx()),head),head-Offset(65.dp.toPx(),-45.dp.toPx()),head,1.2.dp.toPx())} }
        "lyrics" -> { repeat(12){i ->val x=size.width*(.1f+(i*.073f)% .8f);val y=size.height*.45f-age*25.dp.toPx()+sin(i.toFloat())*15.dp.toPx();drawCircle(color.copy(alpha=.65f*fade),1.dp.toPx(),Offset(x,y))};drawOval(Brush.radialGradient(listOf(color.copy(alpha=.1f*fade),Color.Transparent),p,size.width*.6f),Offset(0f,size.height*.35f),Size(size.width,size.height*.2f)) }
        "love","feed","tap" -> drawRandomBurst(effect, age, p, color)
        "trail" -> drawCircle(color.copy(alpha=fade*.4f),3.dp.toPx()+age*12.dp.toPx(),p,style=Stroke(.7.dp.toPx()))
    }
}

/** Seed is sampled at interaction time; particles remain stable between animation frames. */
private fun DrawScope.drawRandomBurst(effect: TouchEffect, age: Float, origin: Offset, accent: Color) {
    val random = Random(effect.seed)
    val behavior = random.nextInt(5)
    val mixed = random.nextBoolean()
    val primaryShape = random.nextInt(5)
    val count = random.nextInt(9, 16)
    repeat(count) { i ->
        val angle = i * 2f * PI.toFloat() / count + random.nextFloat() * .5f
        val speed = (35f + random.nextFloat() * 50f).dp.toPx()
        val lifetime = 1.1f + random.nextFloat() * .8f
        val t = (age / lifetime).coerceIn(0f, 1f)
        val radius = (3f + random.nextFloat() * 4f).dp.toPx()
        val spin = (random.nextFloat() - .5f) * 300f
        val shape = if (mixed) random.nextInt(5) else primaryShape
        val tint = when (random.nextInt(4)) {
            0 -> accent
            1 -> Color(0xFFEBA2BD)
            2 -> Color(0xFFF1CE86)
            else -> Color(0xFFAFB9EE)
        }.copy(alpha = (1f - t).pow(1.4f) * .9f)
        if (t >= 1f) return@repeat
        val delta = when (behavior) {
            0 -> Offset(cos(angle), sin(angle)) * speed * (1f - (1f - t).pow(3))
            1 -> Offset(cos(angle + t * 3f), sin(angle + t * 3f)) * speed * t
            2 -> Offset(cos(angle) * speed * t * .6f, -speed * t - sin(t * PI).toFloat() * 18.dp.toPx())
            3 -> Offset(cos(angle) * speed * t, -sin(t * PI).toFloat() * speed + t * 20.dp.toPx())
            else -> Offset(cos(angle) * speed * t * 1.3f, sin(angle) * speed * t + 45.dp.toPx() * t * t)
        }
        val center = origin + delta
        val r = radius * minOf(1f, t * 9f) * (1f - t * .3f)
        rotate(spin * t, center) {
            if (shape == 3) {
                val tail = center - Offset(cos(angle), sin(angle)) * r * 5f
                drawLine(Brush.linearGradient(listOf(Color.Transparent, tint), tail, center), tail, center, r * .45f, StrokeCap.Round)
                drawCircle(tint, r * .6f, center)
            } else {
                val path = Path()
                when (shape) {
                    0 -> {
                        path.moveTo(center.x, center.y + r)
                        path.cubicTo(center.x - r * 2, center.y - r * .1f, center.x - r, center.y - r * 1.6f, center.x, center.y - r * .5f)
                        path.cubicTo(center.x + r, center.y - r * 1.6f, center.x + r * 2, center.y - r * .1f, center.x, center.y + r)
                    }
                    1, 2 -> {
                        val vertices = if (shape == 1) 10 else 3
                        repeat(vertices) { vertex ->
                            val a = -PI / 2 + vertex * 2 * PI / vertices
                            val rr = if (shape == 1 && vertex % 2 == 1) r * .44f else r
                            val point = center + Offset(cos(a).toFloat(), sin(a).toFloat()) * rr
                            if (vertex == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
                        }
                    }
                    else -> {
                        path.moveTo(center.x, center.y - r * 1.3f)
                        path.lineTo(center.x + r * .6f, center.y)
                        path.lineTo(center.x, center.y + r * 1.3f)
                        path.lineTo(center.x - r * .6f, center.y)
                    }
                }
                path.close()
                if (shape == 2 && i % 2 == 0) drawPath(path, tint, style = Stroke(1.dp.toPx())) else drawPath(path, tint)
            }
        }
    }
}
