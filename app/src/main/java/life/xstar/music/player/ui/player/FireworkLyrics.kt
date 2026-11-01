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
            // Use most of the lyric interval: a real firework has a long rising phase,
            // a bright ignition, several seconds of trails, and a slow gravity fade.
            val cycle = duration.toFloat().coerceAtLeast(if (compact) 1_800f else 2_600f)
            val t = if (motion) (elapsed / cycle).coerceIn(0f, 1f) else 1f
            val center = Offset(size.width / 2f, size.height * .33f)
            val launch = Offset(size.width * .5f, size.height + 8.dp.toPx())
            val slide = 1f - (1f - t).pow(3)
            val launchEnd = .27f
            val burstStart = .22f
            val burstEnd = .86f
            if (motion && t < launchEnd) {
                val p = (t / launchEnd).coerceIn(0f, 1f)
                val head = launch + (center - launch) * (1f - (1f - p).pow(2))
                val tailLength = (if (compact) 30 else 72).dp.toPx() * (1f - p * .35f)
                drawLine(Brush.linearGradient(listOf(accent, Color.Transparent), head, head + Offset(0f, tailLength)),
                    head, head + Offset(0f, tailLength), (if (compact) 1.4f else 2.4f).dp.toPx(), StrokeCap.Round)
                drawCircle(Color.White, (if (compact) 1.6f else 2.5f).dp.toPx(), head)
                drawCircle(accent.copy(alpha = .18f), (if (compact) 7 else 13).dp.toPx(), head)
                // Hot exhaust sparks separate from the main rocket trail.
                repeat(if (compact) 4 else 9) { spark ->
                    val wobble = sin(p * 19f + spark * 2.1f) * (spark + 1) * .7f
                    val at = head + Offset(wobble.dp.toPx(), tailLength * (.35f + spark / 16f))
                    drawCircle(accent.copy(alpha = .55f * (1f - p)), (1f + spark % 2).dp.toPx(), at)
                }
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
            if (!motion || t >= burstEnd) {
                drawText(layout, color = ink, topLeft = origin)
            } else if (t >= burstStart) {
                val burst = ((t - burstStart) / (burstEnd - burstStart)).coerceIn(0f, 1f)
                val expansion = 1f - (1f - burst).pow(2)
                val fade = if (burst < .68f) 1f else ((1f - burst) / .32f).pow(.72f)
                // The flash is brief and intense, followed by a softer expanding halo.
                if (burst < .16f) {
                    val flash = (1f - burst / .16f).pow(2)
                    drawCircle(Color.White.copy(alpha = flash * .75f),
                        (if (compact) 16 else 32).dp.toPx() * (1f + burst * 1.8f), center)
                    drawCircle(accent.copy(alpha = flash * .45f),
                        (if (compact) 25 else 52).dp.toPx() * (1f + burst), center)
                }
                bounds.getValue(index).forEachIndexed { g, rect ->
                    val angle = g * 2.39996f + index * .7f + sin(g * 4.1f) * .18f
                    val radius = (if (compact) 22 else 72).dp.toPx() * (.65f + (g % 5) * .09f)
                    val scatter = Offset(cos(angle), sin(angle)) * radius
                    fun point(p: Float): Offset {
                        val ease = 1f - (1f - p).pow(3)
                        return center + (origin + rect.center - center) * ease + scatter * expansion * sin(p * PI).toFloat() +
                            Offset(0f, p * p * (if (compact) 10 else 25).dp.toPx())
                    }
                    val head = point(burst)
                    val tail = point((burst - (if (compact) .15f else .22f)).coerceAtLeast(0f))
                    val glow = fade.coerceIn(0f, 1f)
                    if ((head - tail).getDistance() > 1f) drawLine(
                        Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = glow * .22f), accent.copy(alpha = glow * .9f)), tail, head),
                        tail, head, (if (compact) 1.1f else 1.8f).dp.toPx(), StrokeCap.Round)
                    drawCircle(Color.White.copy(alpha = glow * .82f), (if (compact) .8f else 1.25f).dp.toPx(), head)
                    withTransform({
                        rotate(sin(burst * PI).toFloat() * sin(angle) * 34f, head)
                        translate(head.x - rect.center.x, head.y - rect.center.y)
                    }) {
                        clipRect(rect.left, rect.top, rect.right, rect.bottom) {
                            drawText(layout, color = androidx.compose.ui.graphics.lerp(accent, ink, burst), alpha = (burst * 7).coerceIn(0f, 1f) * fade)
                        }
                    }
                }
                // Independent embers make the explosion read as a volume of sparks, not a
                // single ring. Each ember has its own speed, gravity and fading tail.
                repeat(if (compact) 34 else 92) { particle ->
                    val seed = particle * 3.731f + index * 1.91f
                    val angle = seed * 2.39996f
                    val speed = (if (compact) 42 else 128).dp.toPx() * (.62f + (sin(seed * 2.7f) + 1f) * .22f)
                    val gravity = (if (compact) 22 else 70).dp.toPx()
                    fun ember(at: Float): Offset {
                        val eased = 1f - (1f - at).pow(1.6f)
                        return center + Offset(cos(angle), sin(angle)) * speed * eased + Offset(0f, gravity * at * at)
                    }
                    val p = ember(expansion)
                    val trail = ember((expansion - .13f).coerceAtLeast(0f))
                    drawLine(Brush.linearGradient(listOf(Color.Transparent, accent.copy(alpha = fade * .7f)), trail, p),
                        trail, p, (if (compact) .8f else 1.25f).dp.toPx(), StrokeCap.Round)
                    drawCircle(Color.White.copy(alpha = fade * .72f), (if (compact) .8f else 1.15f).dp.toPx(), p)
                }
                // Secondary micro-bursts bloom after the main flash.
                if (burst > .32f && burst < .92f) repeat(if (compact) 3 else 7) { burstIndex ->
                    val sub = (burst * 1.9f + burstIndex * .17f) % 1f
                    val subCenter = center + Offset(cos(burstIndex * 2.8f) * (if (compact) 18 else 48).dp.toPx(),
                        sin(burstIndex * 1.9f) * (if (compact) 14 else 36).dp.toPx())
                    val subAlpha = (1f - sub).pow(2) * fade
                    drawCircle(accent.copy(alpha = subAlpha * .5f), (if (compact) 4 else 9).dp.toPx() * sub, subCenter)
                    repeat(if (compact) 5 else 10) { ray ->
                        val a = ray * PI.toFloat() * 2f / 5f + burstIndex
                        val end = subCenter + Offset(cos(a), sin(a)) * (if (compact) 9 else 22).dp.toPx() * sub
                        drawLine(accent.copy(alpha = subAlpha), subCenter, end, (if (compact) .6f else 1f).dp.toPx(), StrokeCap.Round)
                    }
                }
            }
        }
    }
}
