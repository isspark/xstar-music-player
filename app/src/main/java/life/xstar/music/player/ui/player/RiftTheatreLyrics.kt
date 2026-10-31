package life.xstar.music.player.ui.player

import android.animation.ValueAnimator
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import life.xstar.music.player.data.lyrics.LyricLine
import java.text.BreakIterator
import kotlin.math.*

/** Bounded, seek-reconstructible history: only the three preceding lines form the pile. */
@Composable
fun RiftTheatreLyrics(
    lines: List<LyricLine>, current: Int, position: Long, playing: Boolean,
    accent: Color, ink: Color, modifier: Modifier = Modifier, onLine: (Int) -> Unit = {},
    onToggle: () -> Unit = {}, onPrevious: () -> Unit = {}, onNext: () -> Unit = {}
) {
    val index = current.coerceIn(0, (lines.size - 1).coerceAtLeast(0))
    val line = lines.getOrNull(index) ?: return
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val motion = ValueAnimator.areAnimatorsEnabled()
    val latestPlaying by rememberUpdatedState(playing)
    val sampled = remember(position, playing, index, lines) { SystemClock.uptimeMillis() }
    val clock = remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    val untimed = remember(index, lines) { mutableLongStateOf(0L) }
    LaunchedEffect(lifecycle, motion, untimed) {
        if (motion) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var last = SystemClock.uptimeMillis()
            while (true) withFrameNanos {
                val now = SystemClock.uptimeMillis()
                if (latestPlaying) {
                    clock.longValue = now
                    untimed.longValue += (now - last).coerceIn(0, 64)
                }
                last = now
            }
        }
    }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    Box(modifier, contentAlignment = Alignment.Center) {
    BoxWithConstraints(Modifier.fillMaxWidth().fillMaxHeight(.74f).clipToBounds().semantics { contentDescription = line.text }) {
        val leftWidth = (constraints.maxWidth * .46f).toInt().coerceAtLeast(1)
        val style = TextStyle(fontSize = 19.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium)
        val layouts = remember(lines, index, leftWidth, density) {
            (max(0, index - 3)..min(lines.lastIndex, index + 5)).associateWith { n ->
                measurer.measure(lines[n].text.ifBlank { "♪" }, style, constraints = Constraints(maxWidth = leftWidth))
            }
        }
        val bounds = remember(layouts) { layouts.mapValues { (_, layout) -> riftGlyphBounds(layout) } }
        val hits = remember { mutableListOf<Pair<Int, Rect>>() }
        val latestOnLine by rememberUpdatedState(onLine)
        val latestOnToggle by rememberUpdatedState(onToggle)
        val latestPrevious by rememberUpdatedState(onPrevious)
        val latestNext by rememberUpdatedState(onNext)
        Canvas(Modifier.fillMaxSize().pointerInput(lines, index) {
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
                        if (dragged && abs(delta.x) > 55.dp.toPx() && abs(delta.x) > abs(delta.y)) {
                            if (delta.x < 0) latestNext() else latestPrevious()
                        } else if (!dragged) {
                            val target = hits.firstOrNull { it.second.contains(change.position) }?.first
                            if (target != null && target != index) latestOnLine(target) else latestOnToggle()
                        }
                        break
                    }
                }
            }
        }) {
            val seam = size.width * .47f
            val startX = seam + 12.dp.toPx()
            val top = 16.dp.toPx()
            val currentLayout = layouts.getValue(index)
            val next = lines.drop(index + 1).firstOrNull { it.timeMs != null }?.timeMs
            val duration = ((next ?: ((line.timeMs ?: position) + 4_000)) - (line.timeMs ?: position)).coerceAtLeast(600)
            val elapsed = line.timeMs?.let { position - it + if (playing) (clock.longValue - sampled).coerceIn(0, 500) else 0 } ?: untimed.longValue
            val progress = if (motion) (elapsed.toFloat() / duration).coerceIn(0f, 1f) else 0f
            val travel = startX + leftWidth - seam
            val shift = ((progress - .13f) / .64f).coerceIn(0f, 1.5f) * travel
            hits.clear()
            var queueY = top + currentLayout.size.height + 16.dp.toPx()
            clipRect(left = seam + 12.dp.toPx()) {
                for (n in index + 1..min(lines.lastIndex, index + 5)) {
                    val layout = layouts.getValue(n)
                    if (queueY + layout.size.height > size.height - 12.dp.toPx()) break
                    drawText(layout, color = ink.copy(alpha = (.48f - (n - index - 1) * .075f).coerceAtLeast(.14f)), topLeft = Offset(startX, queueY))
                    hits += n to Rect(startX, queueY, size.width, queueY + layout.size.height)
                    queueY += layout.size.height + 16.dp.toPx()
                }
            }
            drawFineRift(seam, 8.dp.toPx(), size.height - 8.dp.toPx(), accent, ink)
            // Oldest first: newer words naturally sit on top of the previous lyric debris.
            for (n in max(0, index - 3)..index) {
                val layout = layouts.getValue(n)
                val glyphs = bounds.getValue(n)
                val age = index - n
                val pileAlpha = when (age) { 0 -> 1f; 1 -> .78f; 2 -> .48f; else -> .25f * (1f - progress) }
                glyphs.forEachIndexed { g, rect ->
                    val originalX = startX + rect.center.x
                    val crossing = .13f + .64f * (originalX - seam) / travel
                    val fall = if (age > 0) 1f else ((progress - crossing) / .21f).coerceIn(0f, 1f)
                    val seed = n * 137 + g * 71
                    val random = ((seed * 0.618034f) % 1f).let { if (it < 0) it + 1 else it }
                    val pileWidth = (seam - 30.dp.toPx()).coerceAtLeast(1f)
                    val landingX = seam - 18.dp.toPx() - random * pileWidth
                    val layer = (g / 7).coerceAtMost(4)
                    val landingY = size.height - 32.dp.toPx() - (layer * 7 + (3 - age.coerceAtMost(3)) * 12).dp.toPx()
                    val sourceY = top + rect.center.y
                    val x = if (age == 0 && progress < crossing) originalX - shift else seam + (landingX - seam) * fall
                    val bounce = if (fall > .8f && fall < 1f) sin((fall - .8f) / .2f * PI).toFloat() * 5.dp.toPx() else 0f
                    val y = sourceY + (landingY - sourceY) * fall * fall - bounce
                    val tilt = fall * ((random - .5f) * 62f)
                    val color = if (age == 0 && progress < crossing) ink else accent
                    if (age == 0 && fall > 0f && fall < 1f) {
                        // The trail follows the ballistic tangent and fades before impact.
                        val velocity = Offset(landingX - seam, 2f * fall * (landingY - sourceY))
                        val direction = velocity / velocity.getDistance().coerceAtLeast(1f)
                        val head = Offset(x, y)
                        val tail = head - direction * (22f + 26f * fall).dp.toPx()
                        val glow = sin(fall * PI).toFloat() * .65f
                        drawLine(Brush.linearGradient(listOf(Color.Transparent, accent.copy(alpha = glow * .18f)), tail, head),
                            tail, head, 7.dp.toPx(), StrokeCap.Round)
                        drawLine(Brush.linearGradient(listOf(Color.Transparent, accent.copy(alpha = glow)), tail, head),
                            tail, head, 1.3.dp.toPx(), StrokeCap.Round)
                        drawCircle(accent.copy(alpha = glow * .8f), 1.5.dp.toPx(), head)
                    }
                    withTransform({ rotate(tilt, Offset(x, y)); translate(x - rect.center.x, y - rect.center.y) }) {
                        clipRect(rect.left, rect.top, rect.right, rect.bottom) {
                            drawText(layout, color = color.copy(alpha = pileAlpha))
                        }
                    }
                }
            }
            hits += index to Rect(seam, top, size.width, top + currentLayout.size.height)
        }
    }
}
}

internal fun riftGlyphBounds(layout: TextLayoutResult): List<Rect> {
    val text = layout.layoutInput.text.text
    val iterator = BreakIterator.getCharacterInstance().apply { setText(text) }
    return buildList {
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            if (text.substring(start, end).isNotBlank()) {
                var box = layout.getBoundingBox(start)
                for (offset in start + 1 until end) {
                    val b = layout.getBoundingBox(offset)
                    box = Rect(min(box.left, b.left), min(box.top, b.top), max(box.right, b.right), max(box.bottom, b.bottom))
                }
                add(box)
            }
            start = end
            end = iterator.next()
        }
    }
}

/** Irregular tapered cavity, shaded fracture walls and hairline secondary fractures. */
internal fun DrawScope.drawFineRift(x: Float, top: Float, bottom: Float, accent: Color, ink: Color) {
    val count = 72
    val left = ArrayList<Offset>(count + 1)
    val right = ArrayList<Offset>(count + 1)
    for (i in 0..count) {
        val t = i.toFloat() / count
        val taper = sin(t * PI).toFloat().coerceAtLeast(0f).pow(.55f)
        val bend = (sin(i * .48f) * 2.4f + sin(i * 1.73f) * .65f).dp.toPx() * taper
        val width = (1.1f + abs(sin(i * 2.17f)) * 1.5f).dp.toPx() * taper
        val y = top + (bottom - top) * t
        left += Offset(x + bend - width, y)
        right += Offset(x + bend + width * .7f, y)
    }
    fun edge(points: List<Offset>) = Path().apply {
        moveTo(points.first().x, points.first().y)
        points.drop(1).forEach { lineTo(it.x, it.y) }
    }
    val cavity = Path().apply {
        moveTo(left.first().x, left.first().y)
        left.drop(1).forEach { lineTo(it.x, it.y) }
        right.asReversed().forEach { lineTo(it.x, it.y) }
        close()
    }
    drawPath(edge(left), Color.Black.copy(alpha = .12f), style = Stroke(9.dp.toPx()))
    drawPath(cavity, Brush.horizontalGradient(listOf(Color.Black.copy(alpha = .6f), Color.Black.copy(alpha = .94f), accent.copy(alpha = .15f)), x - 4.dp.toPx(), x + 4.dp.toPx()))
    drawPath(edge(left), ink.copy(alpha = .16f), style = Stroke(.65.dp.toPx()))
    drawPath(edge(right), accent.copy(alpha = .50f), style = Stroke(.7.dp.toPx()))
    for (i in 7 until count - 4 step 9) {
        val side = if (i % 2 == 0) -1f else 1f
        val origin = if (side < 0) left[i] else right[i]
        val branch = Path().apply {
            moveTo(origin.x, origin.y)
            relativeLineTo(side * 4.dp.toPx(), -3.dp.toPx())
            relativeLineTo(side * 2.dp.toPx(), -6.dp.toPx())
            relativeLineTo(side * 5.dp.toPx(), -4.dp.toPx())
        }
        drawPath(branch, ink.copy(alpha = .12f), style = Stroke(.45.dp.toPx()))
        drawLine(accent.copy(alpha = .28f), right[i], right[i + 2], .9.dp.toPx())
    }
}
