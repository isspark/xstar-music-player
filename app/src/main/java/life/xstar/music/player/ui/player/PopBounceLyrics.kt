package life.xstar.music.player.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import life.xstar.music.player.data.lyrics.LyricLine
import java.text.BreakIterator
import kotlin.math.cos
import kotlin.math.PI
import kotlin.math.sin

/** Karaoke treatment where the highlight belongs only to the character currently popping in. */
@Composable
fun PopBounceLyrics(
    lines: List<LyricLine>, current: Int, position: Long, playing: Boolean,
    accent: Color, ink: Color, modifier: Modifier = Modifier, compact: Boolean = false,
    onLine: (Int) -> Unit = {}
) {
    val index = current.coerceIn(0, (lines.size - 1).coerceAtLeast(0))
    val line = lines.getOrNull(index) ?: return
    val text = line.text.ifBlank { "♪" }
    val next = lines.drop(index + 1).firstOrNull { it.timeMs != null }?.timeMs
    val lineDuration = ((next ?: (line.timeMs ?: position) + 3_200L) - (line.timeMs ?: position)).coerceIn(1_200L, 8_000L)
    var animationElapsed by remember(index) { mutableFloatStateOf(0f) }
    val latestPlaying by androidx.compose.runtime.rememberUpdatedState(playing)
    LaunchedEffect(index) {
        var last = android.os.SystemClock.uptimeMillis()
        while (true) {
            val now = android.os.SystemClock.uptimeMillis()
            val dt = (now - last).coerceIn(0L, 32L)
            last = now
            if (latestPlaying) animationElapsed += dt / 1000f
            kotlinx.coroutines.delay(16L)
        }
    }
    val elapsed = animationElapsed.coerceIn(0f, lineDuration / 1000f)
    val measurer = rememberTextMeasurer()
    val style = TextStyle(
        fontSize = if (compact) 17.sp else 30.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        lineHeight = if (compact) 23.sp else 38.sp
    )
    val glyphs = remember(text) { graphemes(text) }
    val layouts = remember(text, style, measurer) {
        glyphs.map { measurer.measure(it, style, maxLines = 1, overflow = TextOverflow.Clip) }
    }
    BoxWithConstraints(modifier) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        Canvas(Modifier.fillMaxSize()) {
            val gap = if (compact) 4.dp.toPx() else 7.dp.toPx()
            val horizontalPadding = if (compact) 18.dp.toPx() else 28.dp.toPx()
            val widths = layouts.map { it.size.width.toFloat() }
            // Build rows from measured glyph widths instead of squeezing one long line. The
            // larger gap keeps the thrown-in characters visually separate on a phone.
            val rows = mutableListOf<MutableList<Int>>()
            var row = mutableListOf<Int>()
            var rowWidth = 0f
            widths.forEachIndexed { i, glyphWidth ->
                val proposed = rowWidth + if (row.isEmpty()) 0f else gap + glyphWidth
                if (row.isNotEmpty() && proposed > width - horizontalPadding * 2f) {
                    rows += row
                    row = mutableListOf()
                    rowWidth = 0f
                }
                row += i
                rowWidth += if (row.size == 1) glyphWidth else gap + glyphWidth
            }
            if (row.isNotEmpty()) rows += row
            val rowHeight = layouts.firstOrNull()?.size?.height?.toFloat() ?: 1f
            val rowGap = if (compact) 8.dp.toPx() else 14.dp.toPx()
            val contentHeight = rows.size * rowHeight + (rows.size - 1).coerceAtLeast(0) * rowGap
            val firstRowY = (height - contentHeight) / 2f
            val placement = Array(widths.size) { Offset.Zero }
            var rowTop = firstRowY
            rows.forEach { indices ->
                val rowWidthTotal = indices.sumOf { widths[it].toDouble() }.toFloat() + gap * (indices.size - 1).coerceAtLeast(0)
                var cursor = (width - rowWidthTotal) / 2f
                indices.forEach { glyphIndex ->
                    placement[glyphIndex] = Offset(cursor + widths[glyphIndex] / 2f, rowTop + rowHeight / 2f)
                    cursor += widths[glyphIndex] + gap
                }
                rowTop += rowHeight + rowGap
            }
            val duration = lineDuration / 1000f
            // The available lyric time controls the stagger. A short line interval causes
            // several characters to fly in together; a long line remains more legible.
            val stagger = (duration / glyphs.size.coerceAtLeast(1).toFloat() * .72f).coerceIn(.045f, .16f)
            layouts.forEachIndexed { glyphIndex, layout ->
                val charStart = glyphIndex * stagger
                // Keep the pop long enough to read on a phone; the active glyph is visually
                // separated from the settled glyphs instead of flashing past in one frame.
                val raw = if (playing) ((elapsed - charStart) / 1.02f) else 1f
                val p = raw.coerceIn(0f, 1f)
                val isPopping = raw > 0f && raw < 1f
                // The character is thrown onto the stage: tiny and offset at first, then
                // quickly grows past its resting size and settles into place.
                val t = raw.coerceIn(0f, 1f)
                val throwProgress = 1f - (1f - t) * (1f - t) * (1f - t)
                val overshoot = sin(t * 3.1415926f).coerceAtLeast(0f)
                val seed = textSeed(text)
                val throwX = cos((glyphIndex + 1) * 2.31f + seed) * 120.dp.toPx()
                val throwY = sin((glyphIndex + 1) * 1.87f + seed * .4f) * 78.dp.toPx()
                val travel = 1f - throwProgress
                val entranceX = throwX * travel
                val entranceY = throwY * travel
                val squash = 0f
                // x is the glyph center because the draw transform uses a centered origin.
                val x = placement[glyphIndex].x
                val y = placement[glyphIndex].y + entranceY
                val settle = if (raw <= 0f) 0f else (raw * 1.25f).coerceIn(0f, 1f)
                val impactScale = if (squash > 0f) 1f + squash * .16f else 1f
                val scale = if (raw <= 0f) .16f else (.16f + throwProgress * .84f + overshoot * .14f)
                val alpha = if (raw <= 0f) 0f else settle
                // The only active-state treatment is the theme color itself. Once the glyph
                // settles, it returns to the normal lyric color without glow or decoration.
                val color = if (isPopping) accent else ink
                // Keep the variation deterministic for a lyric line: it feels designed rather
                // than jittery, while each glyph still has its own small personality.
                val tilt = sin((glyphIndex + 1) * 17.37f + textSeed(text)) * 5.5f
                val sizeVariation = 1f + sin((glyphIndex + 1) * 9.13f + textSeed(text) * .7f) * .055f
                val glyphCenter = Offset(layout.size.width / 2f, layout.size.height / 2f)
                withTransform({
                    translate(x + entranceX, y)
                    rotate(tilt + sin((glyphIndex + 1) * 3.2f + seed) * 18f * travel, glyphCenter)
                    scale(scale * sizeVariation * (if (squash > 0f) 1.10f else 1f),
                        scale * sizeVariation * (if (squash > 0f) .86f else 1f), glyphCenter)
                }) {
                    drawText(layout, color = color.copy(alpha = alpha), topLeft = -glyphCenter)
                }
            }
        }
    }
}

private fun textSeed(text: String): Float = text.fold(0f) { value, char -> value * 1.17f + char.code * .013f }

private fun graphemes(value: String): List<String> {
    val iterator = BreakIterator.getCharacterInstance().apply { setText(value) }
    val result = mutableListOf<String>()
    var start = iterator.first()
    var end = iterator.next()
    while (end != BreakIterator.DONE) {
        val part = value.substring(start, end)
        if (!part.isBlank()) result += part
        start = end
        end = iterator.next()
    }
    return result.ifEmpty { listOf("♪") }
}
