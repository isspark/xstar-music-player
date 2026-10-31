package life.xstar.music.player.ui.player

import android.animation.ValueAnimator
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import kotlin.math.*

/** One rocket rises, bursts into shaped characters, then settles into a readable sentence. */
@Composable
fun FireworkLyrics(
    lines: List<LyricLine>, current: Int, position: Long, playing: Boolean,
    accent: Color, ink: Color, modifier: Modifier = Modifier, compact: Boolean = false
) {
    val index = current.coerceIn(0, (lines.size - 1).coerceAtLeast(0))
    val line = lines.getOrNull(index) ?: return
    val motion = ValueAnimator.areAnimatorsEnabled()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latestPlaying by rememberUpdatedState(playing)
    val clock = remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    val sampled = remember(position, playing, index, lines) { SystemClock.uptimeMillis() }
    val untimed = remember(index, lines) { mutableLongStateOf(0) }
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
    BoxWithConstraints(modifier.clipToBounds().semantics { contentDescription = line.text }) {
        val availableWidth = (constraints.maxWidth - with(density) { 24.dp.roundToPx() }).coerceAtLeast(1)
        val availableHeight = constraints.maxHeight
        val layouts = remember(lines, index, availableWidth, availableHeight, compact, density) {
            (max(0, index - 2)..index).associateWith { n ->
                var font = if (compact) 17f else 29f
                var layout = measurer.measure(lines[n].text.ifBlank { "♪" }, TextStyle(fontSize = font.sp,
                    lineHeight = (font * 1.4f).sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center),
                    constraints = Constraints(maxWidth = availableWidth))
                while (layout.size.height > availableHeight * .43f && font > 10f) {
                    font -= 1f
                    layout = measurer.measure(lines[n].text.ifBlank { "♪" }, TextStyle(fontSize = font.sp,
                        lineHeight = (font * 1.4f).sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center),
                        constraints = Constraints(maxWidth = availableWidth))
                }
                layout
            }
        }
        val bounds = remember(layouts) { layouts.mapValues { riftGlyphBounds(it.value) } }
        Canvas(Modifier.fillMaxSize()) {
            val next = lines.drop(index + 1).firstOrNull { it.timeMs != null }?.timeMs
            val duration = ((next ?: ((line.timeMs ?: position) + 4_000L)) - (line.timeMs ?: position)).coerceAtLeast(400)
            val elapsed = line.timeMs?.let { position - it + if (playing) (clock.longValue - sampled).coerceIn(0, 500) else 0 } ?: untimed.longValue
            // Finish the burst early enough to leave most of the interval for reading.
            val entrance = min(1_300f, duration * .52f).coerceAtLeast(200f)
            val t = if (motion) (elapsed / entrance).coerceIn(0f, 1f) else 1f
            val center = Offset(size.width / 2f, size.height * .33f)
            val launch = Offset(size.width * .5f, size.height + 8.dp.toPx())
            val slide = 1f - (1f - t).pow(3)
            if (motion && t < .36f) {
                val p = (t / .36f).coerceIn(0f, 1f)
                val head = launch + (center - launch) * (1f - (1f - p).pow(2))
                val tail = head + Offset(0f, (if (compact) 18 else 45).dp.toPx())
                drawLine(Brush.linearGradient(listOf(accent, Color.Transparent), head, tail), head, tail, 2.dp.toPx(), StrokeCap.Round)
                drawCircle(ink, 2.dp.toPx(), head)
                drawCircle(accent.copy(alpha = .15f), 7.dp.toPx(), head)
            }
            for (n in max(0, index - 2) until index) {
                val old = layouts.getValue(n)
                val age = index - n
                val y = center.y + (age - 1 + slide) * (if (compact) 25.dp.toPx() else 65.dp.toPx())
                val alpha = (if (age == 1) .55f else .22f) * (1f - if (age == 2) slide else slide * .3f)
                drawText(old, color = ink.copy(alpha = alpha.coerceIn(0f, 1f)),
                    topLeft = Offset((size.width - old.size.width) / 2f, y - old.size.height / 2f))
            }
            val layout = layouts.getValue(index)
            val origin = Offset((size.width - layout.size.width) / 2f, center.y - layout.size.height / 2f)
            if (!motion || t >= 1f) {
                drawText(layout, color = ink, topLeft = origin)
            } else if (t >= .30f) {
                val burst = ((t - .30f) / .70f).coerceIn(0f, 1f)
                bounds.getValue(index).forEachIndexed { g, rect ->
                    val angle = g * 2.39996f + index * .7f
                    val radius = (if (compact) 22 else 72).dp.toPx() * (.65f + (g % 5) * .09f)
                    val scatter = Offset(cos(angle), sin(angle)) * radius
                    fun point(p: Float): Offset {
                        val ease = 1f - (1f - p).pow(3)
                        return center + (origin + rect.center - center) * ease + scatter * sin(p * PI).toFloat()
                    }
                    val head = point(burst)
                    val tail = point((burst - .12f).coerceAtLeast(0f))
                    val glow = (1f - burst).coerceIn(0f, 1f)
                    if ((head - tail).getDistance() > 1f) drawLine(
                        Brush.linearGradient(listOf(Color.Transparent, accent.copy(alpha = glow * .65f)), tail, head),
                        tail, head, 1.2.dp.toPx(), StrokeCap.Round)
                    withTransform({
                        rotate(sin(burst * PI).toFloat() * sin(angle) * 24f, head)
                        translate(head.x - rect.center.x, head.y - rect.center.y)
                    }) {
                        clipRect(rect.left, rect.top, rect.right, rect.bottom) {
                            drawText(layout, color = androidx.compose.ui.graphics.lerp(accent, ink, burst), alpha = (burst * 7).coerceIn(0f, 1f))
                        }
                    }
                }
                repeat(if (compact) 12 else 28) { particle ->
                    val angle = particle * 2.39996f + index
                    val distance = (if (compact) 38 else 115).dp.toPx() * burst
                    val p = center + Offset(cos(angle), sin(angle)) * distance + Offset(0f, burst * burst * 22.dp.toPx())
                    drawCircle(accent.copy(alpha = (1f - burst).pow(2) * .65f), (1.3f - burst * .7f).dp.toPx(), p)
                }
            }
        }
    }
}
