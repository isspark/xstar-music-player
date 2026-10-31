package life.xstar.music.player.ui.player

import android.animation.ValueAnimator
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import life.xstar.music.player.data.lyrics.LyricLine
import java.text.BreakIterator
import kotlin.math.*
import kotlin.random.Random

/** Shared across player appearances. Only drawing is invalidated by the 30 fps clock. */
@Composable
fun NeonStormLyrics(
    lines: List<LyricLine>, current: Int, playing: Boolean,
    accent: Color, ink: Color, modifier: Modifier = Modifier,
    compact: Boolean = false, expanded: Boolean = false, onLine: (Int) -> Unit = {}
) {
    val index = current.coerceIn(0, (lines.size - 1).coerceAtLeast(0))
    val text = lines.getOrNull(index)?.text?.ifBlank { "♪" } ?: return
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val motionEnabled = ValueAnimator.areAnimatorsEnabled()
    val clock = remember { mutableFloatStateOf(0f) }
    val latestPlaying by rememberUpdatedState(playing)
    val phase = remember(text, index) { Random.nextFloat() * 6.28f }
    var touch by remember(text, index) { mutableStateOf<Offset?>(null) }
    val latestOnLine by rememberUpdatedState(onLine)
    LaunchedEffect(lifecycle, motionEnabled) {
        if (motionEnabled) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var last = android.os.SystemClock.uptimeMillis()
            while (isActive) {
                val now = android.os.SystemClock.uptimeMillis()
                if (latestPlaying) clock.floatValue += (now - last).coerceAtMost(100) / 1000f
                last = now
                delay(if (latestPlaying) 33 else 150)
            }
        }
    }
    BoxWithConstraints(modifier.semantics {
        contentDescription = text
        if (lines[index].timeMs != null) onClick("播放这一句") { latestOnLine(index); true }
    }) {
        val width = with(density) { (maxWidth - 24.dp).roundToPx().coerceAtLeast(1) }
        val height = with(density) { maxHeight.toPx() }
        val fontSize = if (compact) 16.sp else 28.sp
        val style = TextStyle(fontSize = fontSize, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
            lineHeight = if (compact) 23.sp else 38.sp)
        val layout = remember(text, width, style, density.fontScale, height) {
            measurer.measure(text, style, overflow = TextOverflow.Ellipsis,
                maxLines = if (compact) 2 else if (height < with(density) { 190.dp.toPx() }) 2 else 3,
                constraints = Constraints(maxWidth = width))
        }
        // Grapheme bounds preserve shaping, emoji and combining marks when moving each glyph.
        val bounds = remember(layout, text) {
            val iterator = BreakIterator.getCharacterInstance().apply { setText(text) }
            buildList {
                var start = iterator.first()
                var end = iterator.next()
                while (end != BreakIterator.DONE) {
                    val row = layout.getLineForOffset(start)
                    if (row < layout.lineCount && start < layout.getLineEnd(row, visibleEnd = true) && !text.substring(start, end).isBlank()) {
                        var rect = layout.getBoundingBox(start)
                        for (offset in start + 1 until end) {
                            val next = layout.getBoundingBox(offset)
                            rect = Rect(min(rect.left, next.left), min(rect.top, next.top), max(rect.right, next.right), max(rect.bottom, next.bottom))
                        }
                        add(rect)
                    }
                    start = end; end = iterator.next()
                }
            }
        }
        val neighborOffsets = if (expanded) listOf(-2, -1, 1, 2) else listOf(-1, 1)
        val neighbors = remember(lines, index, width, compact, expanded, density.fontScale) {
            neighborOffsets.map { offset ->
                val neighbor = index + offset
                lines.getOrNull(neighbor)?.text?.let {
                    measurer.measure(it, TextStyle(fontSize = if (compact) 11.sp else 14.sp, textAlign = TextAlign.Center),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, constraints = Constraints(maxWidth = width))
                }
            }
        }
        Canvas(Modifier.fillMaxSize().pointerInput(index, text, motionEnabled) {
            awaitEachGesture {
                val down = awaitFirstDown()
                down.consume()
                val start = down.position
                var dragged = false
                try {
                    if (motionEnabled) touch = start
                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if ((change.position - start).getDistance() > viewConfiguration.touchSlop) dragged = true
                        if (motionEnabled) touch = change.position
                        change.consume()
                        if (!change.pressed) {
                            if (!dragged) latestOnLine(index)
                            break
                        }
                    } while (event.changes.any { it.pressed })
                } finally { touch = null }
            }
        }) {
            val time = clock.floatValue
            val secondary = lerp(accent, ink, .45f)
            val origin = Offset((size.width - layout.size.width) / 2f, (size.height - layout.size.height) / 2f)
            val amplitude = (if (compact) 2.5f else 7f).dp.toPx()
            val points = bounds.mapIndexed { i, rect ->
                val center = origin + rect.center
                val force = touch?.let { p ->
                    val delta = p - center
                    delta * (.26f * (1f - delta.getDistance() / size.width).coerceIn(0f, 1f))
                } ?: Offset.Zero
                center + Offset(if (motionEnabled) sin(time * 1.7f + i + phase) * amplitude * .25f else 0f,
                    if (motionEnabled) sin(time * 2.2f + i * .75f + phase) * amplitude else 0f) + force
            }
            val centerY = size.height / 2
            if (motionEnabled) {
                repeat(if (compact) 1 else 3) { ring ->
                    val path = Path()
                    for (step in 0..60) {
                        val x = size.width * step / 60
                        val y = centerY + sin(step * .12f + time * 2 + ring + phase) * amplitude * 2 + (ring - 1) * amplitude
                        if (step == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path, accent.copy(alpha = .10f), style = androidx.compose.ui.graphics.drawscope.Stroke(.8.dp.toPx()))
                }
                points.zipWithNext().forEachIndexed { i, (a, b) ->
                    if (abs(a.y - b.y) < layout.getLineBottom(0) && (a - b).getDistance() < size.width * .4f) {
                        val arc = Path().apply {
                            moveTo(a.x, a.y)
                            for (segment in 1..5) {
                                val f = segment / 5f
                                val p = a + (b - a) * f
                                lineTo(p.x, p.y + if (segment == 5) 0f else sin(time * 14 + i * 3 + segment * 4) * amplitude * .6f)
                            }
                        }
                        drawPath(arc, accent.copy(alpha = .10f), style = androidx.compose.ui.graphics.drawscope.Stroke(4.dp.toPx()))
                        drawPath(arc, secondary.copy(alpha = .45f), style = androidx.compose.ui.graphics.drawscope.Stroke(.8.dp.toPx()))
                    }
                }
            }
            bounds.forEachIndexed { i, rect ->
                val displacement = points[i] - (origin + rect.center)
                translate(origin.x + displacement.x, origin.y + displacement.y) {
                    clipRect(rect.left - .5f, rect.top, rect.right + .5f, rect.bottom) {
                        drawText(layout, color = if (i % 3 == 0) secondary else accent,
                            shadow = if (motionEnabled) Shadow(accent.copy(alpha = .55f), Offset.Zero, 9.dp.toPx()) else null)
                    }
                }
            }
            neighbors.forEachIndexed { i, neighbor ->
                neighbor?.let {
                    val offset = neighborOffsets[i]
                    val gap = (if (compact) 6 else 22).dp.toPx()
                    val extra = (abs(offset) - 1) * (it.size.height + 22.dp.toPx())
                    val y = if (offset < 0) origin.y - it.size.height - gap - extra
                        else origin.y + layout.size.height + gap + extra
                    if (y >= 0 && y + it.size.height <= size.height) drawText(it, color = ink.copy(alpha = if (expanded) { if (abs(offset) == 1) .55f else .34f } else .30f),
                        topLeft = Offset((size.width - it.size.width) / 2f, y))
                }
            }
        }
    }
}
