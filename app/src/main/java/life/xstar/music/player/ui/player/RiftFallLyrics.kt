package life.xstar.music.player.ui.player

import android.animation.ValueAnimator
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import life.xstar.music.player.data.lyrics.LyricLine
import java.text.BreakIterator
import kotlin.math.max
import kotlin.math.min

/** Characters travel right, then lose support as they cross the central rift. */
@Composable
fun RiftFallLyrics(
    lines: List<LyricLine>, current: Int, position: Long, playing: Boolean,
    accent: Color, ink: Color, modifier: Modifier = Modifier, compact: Boolean = false,
    onLine: (Int) -> Unit = {}
) {
    val index = current.coerceIn(0, (lines.size - 1).coerceAtLeast(0))
    val line = lines.getOrNull(index) ?: return
    val text = line.text.ifBlank { "♪" }
    val next = lines.drop(index + 1).firstOrNull { it.timeMs != null }?.timeMs
    val duration = ((next ?: ((line.timeMs ?: position) + 4_000L)) - (line.timeMs ?: position)).coerceAtLeast(600L)
    val motionEnabled = ValueAnimator.areAnimatorsEnabled()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latestPlaying by rememberUpdatedState(playing)
    val sampleTime = remember(position, index, text, playing) { SystemClock.uptimeMillis() }
    val clock = remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    val untimedElapsed = remember(index, text) { mutableLongStateOf(0L) }
    LaunchedEffect(lifecycle, motionEnabled, untimedElapsed) {
        if (motionEnabled) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var last = SystemClock.uptimeMillis()
            while (true) {
                withFrameNanos {
                    val now = SystemClock.uptimeMillis()
                    if (latestPlaying) {
                        clock.longValue = now
                        untimedElapsed.longValue += (now - last).coerceIn(0L, 64L)
                    }
                    last = now
                }
            }
        }
    }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(modifier.clipToBounds().semantics { contentDescription = text }
        .clickable(enabled = line.timeMs != null, onClickLabel = "播放这一句") { onLine(index) }) {
        val width = constraints.maxWidth.coerceAtLeast(1)
        val style = TextStyle(fontSize = if (compact) 19.sp else 30.sp, fontWeight = FontWeight.SemiBold)
        val ribbon = remember(text, style, density) { measurer.measure(text, style, softWrap = false, maxLines = 1) }
        // Slice the shaped line to preserve spaces, kerning and combining characters.
        val glyphs = remember(text, ribbon) {
            val iterator = BreakIterator.getCharacterInstance().apply { setText(text) }
            buildList {
                var start = iterator.first()
                var end = iterator.next()
                while (end != BreakIterator.DONE) {
                    if (!text.substring(start, end).isBlank()) {
                        var rect = ribbon.getBoundingBox(start)
                        for (offset in start + 1 until end) {
                            val b = ribbon.getBoundingBox(offset)
                            rect = Rect(min(rect.left, b.left), min(rect.top, b.top), max(rect.right, b.right), max(rect.bottom, b.bottom))
                        }
                        add(rect)
                    }
                    start = end
                    end = iterator.next()
                }
            }
        }
        val headerStyle = TextStyle(fontSize = if (compact) 11.sp else 16.sp, textAlign = TextAlign.Center)
        val header = remember(text, width, headerStyle, density) {
            measurer.measure(text, headerStyle, maxLines = if (compact) 1 else 2,
                overflow = TextOverflow.Ellipsis, constraints = Constraints(maxWidth = (width - with(density) { 24.dp.roundToPx() }).coerceAtLeast(1)))
        }
        Canvas(Modifier.fillMaxSize()) {
            if (!motionEnabled) {
                drawText(header, color = ink, topLeft = Offset((size.width - header.size.width) / 2f, (size.height - header.size.height) / 2f))
                return@Canvas
            }
            val interpolation = if (playing) (clock.longValue - sampleTime).coerceIn(0L, 500L) else 0L
            val elapsed = line.timeMs?.let { position - it + interpolation } ?: untimedElapsed.longValue
            val progress = (elapsed.toFloat() / duration).coerceIn(0f, 1f)
            val seamX = size.width * .5f
            val baseline = size.height * (if (compact) .32f else .40f)
            val glyphHeight = ribbon.size.height.toFloat()
            val crackTop = baseline - 4.dp.toPx()
            val crackBottom = size.height * .98f
            drawFineRift(seamX, crackTop, crackBottom, accent, ink)
            drawText(header, color = ink.copy(alpha = .6f), topLeft = Offset((size.width - header.size.width) / 2f,
                (baseline - header.size.height - 10.dp.toPx()).coerceAtLeast(0f)))
            val travel = max(ribbon.size.width.toFloat(), size.width * .6f)
            val speed = travel / .66f
            glyphs.forEachIndexed { i, rect ->
                val crossing = .12f + .66f * i / glyphs.size.coerceAtLeast(1)
                val after = progress - crossing
                val fall = (after / .22f).coerceIn(0f, 1f)
                val x = seamX + if (after <= 0f) after * speed else after * min(speed, size.width * 1.1f)
                val y = baseline + fall * fall * (size.height - baseline + glyphHeight)
                val alpha = if (after <= 0f) 1f else (1f - fall * fall).coerceIn(0f, 1f)
                if (alpha > 0f && x + rect.width > 0f && x - rect.width < size.width) {
                    val pivot = Offset(x, y + glyphHeight / 2f)
                    withTransform({
                        rotate(fall * (35f + i % 4 * 11f), pivot)
                        translate(x - rect.center.x, y)
                    }) {
                        clipRect(rect.left, rect.top, rect.right, rect.bottom) {
                            drawText(ribbon, color = (if (after >= 0f) accent else ink).copy(alpha = alpha))
                        }
                    }
                }
            }
        }
    }
}
