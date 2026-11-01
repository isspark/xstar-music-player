package life.xstar.music.player.ui.player

import android.animation.ValueAnimator
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import life.xstar.music.player.data.lyrics.LyricLine
import life.xstar.music.player.data.preferences.LyricsMode
import kotlin.math.*

/** Spatial treatments sharing shaped text, playback time and bounded history. */
@Composable
fun SpatialLyrics(
    lines: List<LyricLine>, current: Int, position: Long, playing: Boolean, mode: LyricsMode,
    accent: Color, ink: Color, modifier: Modifier = Modifier, compact: Boolean = false,
    onToggle: (() -> Unit)? = null, onPrevious: () -> Unit = {}, onNext: () -> Unit = {}
) {
    val index = current.coerceIn(0, (lines.size - 1).coerceAtLeast(0))
    val line = lines.getOrNull(index) ?: return
    val motion = ValueAnimator.areAnimatorsEnabled()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latestPlaying by rememberUpdatedState(playing)
    val latestToggle by rememberUpdatedState(onToggle)
    val latestPrevious by rememberUpdatedState(onPrevious)
    val latestNext by rememberUpdatedState(onNext)
    val sampled = remember(position, playing, index, lines) { SystemClock.uptimeMillis() }
    val clock = remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    val ambient = remember { mutableFloatStateOf(0f) }
    val untimed = remember(index, lines) { mutableLongStateOf(0L) }
    LaunchedEffect(lifecycle, motion, untimed) {
        if (motion) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var last = SystemClock.uptimeMillis()
            while (true) withFrameNanos {
                val now = SystemClock.uptimeMillis()
                if (latestPlaying) {
                    val dt = (now - last).coerceIn(0, 64)
                    clock.longValue = now
                    ambient.floatValue += dt / 1000f
                    untimed.longValue += dt
                }
                last = now
            }
        }
    }
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val interaction = if (onToggle == null) Modifier else Modifier.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(pass = PointerEventPass.Initial)
            down.consume()
            val start = down.position
            var dragged = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (change.isConsumed) break
                val delta = change.position - start
                if (delta.getDistance() > viewConfiguration.touchSlop) dragged = true
                change.consume()
                if (!change.pressed) {
                    if (dragged && abs(delta.x) > 55.dp.toPx() && abs(delta.x) > abs(delta.y)) {
                        if (delta.x < 0) latestNext() else latestPrevious()
                    } else if (!dragged) latestToggle?.invoke()
                    break
                }
            }
        }
    }
    BoxWithConstraints(modifier.clipToBounds().then(interaction).semantics {
        contentDescription = "${mode.title}：${line.text}"
        if (onToggle != null) onClick(if (playing) "暂停" else "播放") { latestToggle?.invoke(); true }
    }) {
        if (mode == LyricsMode.ForestLight) {
            ForestLightLyrics(lines, index, accent, ink, compact, motion,
                time = { ambient.floatValue }, elapsed = {
                    line.timeMs?.let { position - it + if (playing) (clock.longValue - sampled).coerceIn(0, 500) else 0 }
                        ?: untimed.longValue
                })
            return@BoxWithConstraints
        }
        if (mode == LyricsMode.DeepSea) {
            BubbleOceanLyrics(lines, index, accent, ink, compact, motion,
                time = { ambient.floatValue }, elapsed = {
                    line.timeMs?.let { position - it + if (playing) (clock.longValue - sampled).coerceIn(0, 500) else 0 }
                        ?: untimed.longValue
                })
            return@BoxWithConstraints
        }
        val width = (constraints.maxWidth * .82f).toInt().coerceAtLeast(1)
        val height = constraints.maxHeight
        val layouts = remember(lines, index, width, height, compact, density) {
            (max(0, index - 3)..min(lines.lastIndex, index + 3)).associateWith { n ->
                var font = if (compact) 17f else 27f
                fun measure() = measurer.measure(lines[n].text.ifBlank { "♪" }, TextStyle(
                    fontSize = font.sp, lineHeight = (font * 1.5f).sp,
                    fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center),
                    constraints = Constraints(maxWidth = width))
                var result = measure()
                while (result.size.height > height * .38f && font > 10f) { font -= 1; result = measure() }
                result
            }
        }
        val glyphs = remember(layouts) { layouts.mapValues { riftGlyphBounds(it.value) } }
        val dust = remember(layouts, mode) {
            if (mode != LyricsMode.Stardust) emptyList() else {
                // Read actual rasterized letter coverage: particles form the glyph strokes, not rectangles.
                val layout = layouts.getValue(index)
                val bitmap = android.graphics.Bitmap.createBitmap(layout.size.width.coerceAtLeast(1), layout.size.height.coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888)
                val canvas = Canvas(android.graphics.Canvas(bitmap))
                androidx.compose.ui.text.TextPainter.paint(canvas, layout)
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                val step = max(3, sqrt(pixels.size / 1800f).toInt())
                val points = buildList {
                    for (y in 0 until bitmap.height step step) for (x in 0 until bitmap.width step step) {
                        if ((pixels[y * bitmap.width + x] ushr 24) > 80) add(Offset(x.toFloat(), y.toFloat()))
                    }
                }
                bitmap.recycle()
                points
            }
        }
        Canvas(Modifier.fillMaxSize()) {
            val next = lines.drop(index + 1).firstOrNull { it.timeMs != null }?.timeMs
            val duration = ((next ?: ((line.timeMs ?: position) + 4_000L)) - (line.timeMs ?: position)).coerceAtLeast(400)
            val elapsed = line.timeMs?.let { position - it + if (playing) (clock.longValue - sampled).coerceIn(0, 500) else 0 } ?: untimed.longValue
            val p = if (motion) (elapsed / min(1_600f, duration * .55f)).coerceIn(0f, 1f) else 1f
            val eased = 1f - (1f - p).pow(3)
            val time = ambient.floatValue
            val layout = layouts.getValue(index)
            val center = Offset(size.width / 2, size.height * .40f)
            val origin = center - Offset(layout.size.width / 2f, layout.size.height / 2f)
            fun history(alpha: Float = .3f) {
                layouts[index - 1]?.let { old ->
                    val y = center.y + max(layout.size.height, old.size.height) / 2f + (if (compact) 12 else 42).dp.toPx()
                    drawText(old, color = ink.copy(alpha = alpha), topLeft = Offset((size.width - old.size.width) / 2f, y))
                }
            }
            when (mode) {
                LyricsMode.TypeMachine -> {
                    val currentGlyphs = glyphs.getValue(index)
                    val previous = layouts[index - 1]
                    val previousGlyphs = glyphs[index - 1].orEmpty()
                    val previousOrigin = previous?.let { center - Offset(it.size.width / 2f, it.size.height / 2f) }
                    val count = max(currentGlyphs.size, previousGlyphs.size).coerceAtLeast(1)
                    val stagger = (duration / 1000f * .62f / count).coerceIn(.045f, .18f)
                    val latest = if (motion) floor(elapsed / 1000f / stagger).toInt().coerceIn(0, count - 1) else count - 1
                    repeat(count) { g ->
                        val t = if (motion) ((elapsed / 1000f - g * stagger) / .72f).coerceIn(0f, 1f) else 1f
                        // Each old slot stays intact until its own leaf turns; longer old lines
                        // also turn their surplus characters away instead of disappearing at once.
                        val oldRect = previousGlyphs.getOrNull(g)
                        if (previous != null && previousOrigin != null && oldRect != null && t < .5f) {
                            val oldPoint = previousOrigin + oldRect.center
                            val projection = cos(t * PI.toFloat()).coerceAtLeast(0f)
                            withTransform({ scale(1f, projection, oldPoint); translate(previousOrigin.x, previousOrigin.y) }) {
                                clipRect(oldRect.left, oldRect.top, oldRect.right, oldRect.bottom) {
                                    drawText(previous, color = ink)
                                }
                            }
                        }
                        val rect = currentGlyphs.getOrNull(g) ?: return@repeat
                        val point = origin + rect.center
                        if (t > .5f || previous == null) {
                            val reveal = if (previous == null) t else (t - .5f) * 2f
                            val projection = sin(reveal * PI.toFloat() / 2) + sin(reveal * PI.toFloat()) * .08f
                            withTransform({ scale(1f, projection, point); translate(origin.x, origin.y) }) {
                                clipRect(rect.left, rect.top, rect.right, rect.bottom) {
                                    drawText(layout, color = if (g == latest) accent else ink,
                                        alpha = (.25f + reveal * .75f).coerceIn(0f, 1f))
                                }
                            }
                        }
                    }
                    layouts[index + 1]?.let { upcoming ->
                        val reflectionTop = origin.y + layout.size.height + (if (compact) 4 else 9).dp.toPx()
                        val reflectionHeight = upcoming.size.height * .70f
                        // Screen-space strips fade the vertically reflected NEXT sentence away from the surface.
                        repeat(32) { strip ->
                            val from = strip / 32f
                            val to = (strip + 1) / 32f
                            clipRect(top = reflectionTop + reflectionHeight * from,
                                bottom = reflectionTop + reflectionHeight * to) {
                                withTransform({
                                    translate((size.width - upcoming.size.width) / 2f, reflectionTop + reflectionHeight)
                                    scale(1f, -.70f, Offset.Zero)
                                }) {
                                    drawText(upcoming, color = lerp(ink, accent, .25f), alpha = .32f * (1f - from).pow(2))
                                }
                            }
                        }
                    }
                }
                LyricsMode.Stardust -> {
                    val burst = ((p - .20f) / .80f).coerceIn(0f, 1f)
                    val converge = 1f - (1f - burst).pow(3)
                    val launch = Offset(size.width / 2, size.height + 8.dp.toPx())
                    val detonation = center + Offset(0f, -size.height * .14f)
                    dust.forEachIndexed { i, target ->
                        val angle = i * 2.39996f
                        val z = hash(i + 67)
                        val scatter = Offset(cos(angle) * size.width * .5f, sin(angle) * size.height * .4f) * (.4f + z)
                        val point = if (p < .20f) launch + (detonation - launch) * (p / .20f) else
                            detonation + (origin + target - detonation) * converge + scatter * sin(burst * PI).toFloat()
                        val perspective = 1f / (1f + (1 - burst) * (z - .5f) * .9f)
                        val radius = (if (compact) .7f else 1.1f).dp.toPx() * perspective
                        drawCircle(lerp(accent, ink, burst).copy(alpha = if (p > .9f) ((1 - p) * 10).coerceIn(0f, 1f) else .85f), radius, point)
                    }
                    if (p > .83f) drawText(layout, color = ink, alpha = ((p - .83f) / .17f).coerceIn(0f, 1f), topLeft = origin)
                    repeat(30) { i ->
                        val x = size.width * hash(i + 102)
                        val y = size.height * ((hash(i + 89) + time * .025f) % 1f)
                        drawCircle(accent.copy(alpha = .12f + hash(i) * .18f), (.5f + hash(i) * .8f).dp.toPx(), Offset(x, y))
                    }
                    history(.18f)
                }
                else -> drawText(layout, color = ink, topLeft = origin)
            }
        }
    }
}

private fun hash(seed: Int): Float = ((sin(seed * 127.1 + 311.7) * 43758.5453) % 1.0).let { if (it < 0) it + 1 else it }.toFloat()
