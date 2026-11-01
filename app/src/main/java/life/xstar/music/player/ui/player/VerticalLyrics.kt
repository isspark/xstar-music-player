package life.xstar.music.player.ui.player

import android.animation.ValueAnimator
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import life.xstar.music.player.ui.LocalXstarFontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import life.xstar.music.player.data.lyrics.LyricLine
import life.xstar.music.player.data.preferences.LyricsMode
import java.text.BreakIterator
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor

/** Transparent, theme-colored vertical worlds. Long verses continue in adjacent columns. */
@Composable
fun VerticalLyrics(
    lines: List<LyricLine>, current: Int, position: Long, playing: Boolean,
    mode: LyricsMode, accent: Color, ink: Color,
    modifier: Modifier = Modifier, compact: Boolean = false
) {
    if (lines.isEmpty()) return
    val index = current.coerceIn(lines.indices)
    val bamboo = mode == LyricsMode.BambooScroll
    val motion = ValueAnimator.areAnimatorsEnabled()
    val focus by animateFloatAsState(index.toFloat(), tween(if (motion) 850 else 0), label = "verticalLyricsFocus")
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var clock by remember(lines, mode) { mutableFloatStateOf(0f) }
    LaunchedEffect(lifecycle, playing, motion) {
        if (playing && motion) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var last = 0L
            while (true) withFrameNanos { frame ->
                if (last != 0L) clock += ((frame - last) / 1_000_000_000f).coerceAtMost(.05f)
                last = frame
            }
        }
    }
    val glyphs = remember(lines) {
        lines.map { line ->
            val text = line.text.ifBlank { "♪" }
            val iterator = BreakIterator.getCharacterInstance().apply { setText(text) }
            buildList {
                var start = iterator.first()
                var end = iterator.next()
                while (end != BreakIterator.DONE) {
                    add(text.substring(start, end))
                    start = end
                    end = iterator.next()
                }
            }
        }
    }
    val measurer = rememberTextMeasurer(cacheSize = 256)
    val density = LocalDensity.current
    BoxWithConstraints(modifier.clipToBounds()) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val fontPx = with(density) {
            minOf((if (compact) 15.sp else 25.sp).toPx(), width * .065f, height * .19f).coerceAtLeast(1f)
        }
        val step = fontPx * (if (bamboo) 1.3f else 1.38f)
        val capacity = floor(height * .72f / step).toInt().coerceAtLeast(1)
        val spacing = fontPx * (if (bamboo) 2.2f else 2.5f)
        val counts = remember(glyphs, capacity) { glyphs.map { (it.size + capacity - 1) / capacity } }
        val centers = remember(counts) {
            var cursor = 0f
            counts.map { count -> (cursor + (count - 1) / 2f).also { cursor += count + .65f } }
        }
        val appFont = LocalXstarFontFamily.current
        val style = TextStyle(
            fontSize = with(density) { fontPx.toSp() },
            fontFamily = if (bamboo) appFont else appFont,
            fontWeight = if (bamboo) FontWeight.Medium else FontWeight.Normal
        )
        // Only nearby verses are measured and drawn, even for very long lyric documents.
        val from = (minOf(index, floor(focus).toInt()) - 9).coerceAtLeast(0)
        val to = (maxOf(index, floor(focus).toInt()) + 9).coerceAtMost(lines.lastIndex)
        val layouts = remember(glyphs, from, to, style, measurer) {
            (from..to).associateWith { row -> glyphs[row].map { measurer.measure(it, style) } }
        }
        Canvas(Modifier.fillMaxSize()) {
            if (size.minDimension < 2f) return@Canvas
            val lower = floor(focus).toInt().coerceIn(lines.indices)
            val upper = (lower + 1).coerceAtMost(lines.lastIndex)
            val anchor = centers[lower] + (centers[upper] - centers[lower]) * (focus - lower)
            val start = lines[index].timeMs
            val end = lines.drop(index + 1).firstOrNull { it.timeMs != null && it.timeMs > (start ?: -1) }?.timeMs
                ?: ((start ?: position) + 4_000L)
            val progress = if (start == null) 1f else
                ((position - start).toFloat() / (end - start).coerceAtLeast(1L)).coerceIn(0f, 1f)
            val direction = if (bamboo) -1f else 1f
            for (row in from..to) {
                val distance = abs(row - focus)
                val presence = exp(-distance * .38f)
                val opacity = if (row == index) 1f else (.15f + presence * .42f) * if (row < index) .7f else 1f
                val centerX = size.width / 2f + direction * (centers[row] - anchor) * spacing
                val halfWidth = counts[row] * spacing / 2f
                if (centerX + halfWidth < 0 || centerX - halfWidth > size.width) continue
                val rowLayouts = layouts[row] ?: continue
                val drift = if (bamboo) 0f else {
                    // Each stream falls independently; the focus stream is caught in the reading band.
                    val cycle = (clock * (.045f + row % 4 * .009f) + row * .271f) % 1f
                    (cycle - .5f) * size.height * .3f * (1f - presence)
                }
                val top = size.height * (if (bamboo) .14f + row % 3 * .025f else .12f + row % 4 * .035f) + drift
                repeat(counts[row]) { column ->
                    val x = centerX + direction * (column - (counts[row] - 1) / 2f) * spacing
                    val length = minOf(capacity, rowLayouts.size - column * capacity)
                    if (bamboo) {
                        // Fine edges and binding cords suggest bamboo without painting a surface.
                        val edge = ink.copy(alpha = opacity * .2f)
                        drawLine(edge, Offset(x + fontPx * .85f, size.height * .08f),
                            Offset(x + fontPx * .85f, size.height * .92f), 1.dp.toPx())
                        for (y in listOf(size.height * .1f, size.height * .9f)) {
                            drawLine(edge, Offset(x - spacing * .5f, y), Offset(x + spacing * .5f, y), 1.dp.toPx())
                        }
                    }
                    repeat(length) { cell ->
                        val glyphIndex = column * capacity + cell
                        val glyph = rowLayouts[glyphIndex]
                        val y = top + cell * step
                        val point = Offset(x - glyph.size.width / 2f, y)
                        val color = lerp(ink, accent, if (row == index) .25f else .08f).copy(alpha = opacity)
                        drawText(glyph, color = color, topLeft = point)
                        if (row == index) {
                            val fill = (progress * rowLayouts.size - glyphIndex).coerceIn(0f, 1f)
                            if (fill > 0f) clipRect(point.x, y, point.x + glyph.size.width, y + glyph.size.height * fill) {
                                drawText(glyph, color = accent, topLeft = point,
                                    shadow = if (!bamboo && motion) Shadow(accent.copy(alpha = .5f), Offset.Zero, fontPx * .45f) else null)
                            }
                        }
                    }
                }
            }
        }
    }
}
