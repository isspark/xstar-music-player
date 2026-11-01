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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import life.xstar.music.player.data.lyrics.LyricLine
import java.text.BreakIterator
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** Readable short phrases written along broad arcs, joined by a continuous light ribbon. */
@Composable
fun LightTrailLyrics(
    lines: List<LyricLine>, current: Int, position: Long, playing: Boolean,
    accent: Color, ink: Color, modifier: Modifier = Modifier, compact: Boolean = false
) {
    if (lines.isEmpty()) return
    val index = current.coerceIn(lines.indices)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(modifier.clipToBounds()) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val fontSize = with(density) {
            minOf((if (compact) 22.sp else 35.sp).toPx(), height * .27f, width * .115f).coerceAtLeast(1f).toSp()
        }
        val style = TextStyle(fontSize = fontSize, fontWeight = FontWeight.Bold)
        val gap = with(density) { (if (compact) 3.dp else 5.dp).toPx() }
        val phrases = remember(lines, width, height, style, measurer, gap) {
            val result = mutableListOf<WritingPhrase>()
            val random = Random(lines.hashCode())
            var fallback = 0f
            lines.forEachIndexed { row, line ->
                val start = line.timeMs?.toFloat() ?: fallback
                val end = lines.drop(row + 1).firstOrNull { (it.timeMs ?: -1L) > start }?.timeMs?.toFloat()
                    ?: (start + 4_000f)
                val span = (end - start).coerceAtLeast(300f)
                fallback = start + span
                // Measure the final varied sizes so larger characters get their own space.
                // A stable seed keeps the composition still during animation and after seeking.
                val typeRandom = Random(line.text.hashCode() xor row)
                val glyphs = writingGraphemes(line.text.ifBlank { "♪" }).map {
                    val variation = .86f + typeRandom.nextFloat() * .32f
                    measurer.measure(it, style.copy(fontSize = (fontSize.value * variation).sp))
                }
                val groups = mutableListOf<List<TextLayoutResult>>()
                var group = mutableListOf<TextLayoutResult>()
                var used = 0f
                glyphs.forEach { glyph ->
                    if (group.isNotEmpty() && (group.size >= 7 || used + glyph.size.width + gap > width * .76f)) {
                        groups += group
                        group = mutableListOf()
                        used = 0f
                    }
                    group += glyph
                    used += glyph.size.width + gap
                }
                if (group.isNotEmpty()) groups += group
                var consumed = 0
                groups.forEach { layouts ->
                    val begin = start + span * consumed / glyphs.size
                    consumed += layouts.size
                    val finish = start + span * consumed / glyphs.size
                    val textWidth = layouts.sumOf { it.size.width.toDouble() }.toFloat() + gap * (layouts.size - 1)
                    val left = width * .1f + random.nextFloat() * (width * .8f - textWidth).coerceAtLeast(0f)
                    val band = result.size % 3
                    val y = height * (if (compact) .46f + band * .17f else .25f + band * .25f) +
                        (random.nextFloat() - .5f) * height * .05f
                    val slope = (random.nextFloat() - .5f) * minOf(height * .12f, textWidth * .13f)
                    val bend = (random.nextFloat() - .5f) * minOf(height * .08f, textWidth * .1f)
                    var cursor = 0f
                    val centers = layouts.map { glyph ->
                        (cursor + glyph.size.width / 2f).also { cursor += glyph.size.width + gap }
                    }
                    result += WritingPhrase(row, begin, finish, layouts, centers, left, y, textWidth, slope, bend)
                }
            }
            result
        }
        var now by remember(lines) { mutableFloatStateOf(position.toFloat()) }
        LaunchedEffect(lines, position, playing, index) {
            val anchor = if (lines[index].timeMs != null) position.toFloat()
                else phrases.firstOrNull { it.row == index }?.start ?: position.toFloat()
            now = anchor
            if (playing) {
                val origin = withFrameNanos { it }
                while (true) withFrameNanos { frame -> now = anchor + (frame - origin) / 1_000_000f }
            }
        }
        Canvas(Modifier.fillMaxSize()) {
            if (phrases.isEmpty() || size.minDimension < 2f) return@Canvas
            val active = phrases.indexOfLast { it.start <= now }.coerceAtLeast(0)
            val bright = lerp(accent, Color.White, .6f)
            val currentPhrase = phrases[active]
            for (phraseIndex in (active - 1).coerceAtLeast(0)..active) {
                val phrase = phrases[phraseIndex]
                if (now < phrase.start) continue
                val outgoing = if (phraseIndex < active) ((now - currentPhrase.start) / 650f).coerceIn(0f, 1f)
                    else ((now - phrase.end - 350f) / 700f).coerceIn(0f, 1f)
                val fade = 1f - outgoing
                if (fade <= 0f) continue
                val writeTime = (phrase.end - phrase.start) * .8f
                phrase.glyphs.forEachIndexed { i, glyph ->
                    val fraction = phrase.centers[i] / phrase.width.coerceAtLeast(1f)
                    val age = now - phrase.start - fraction * writeTime
                    if (age <= 0f) return@forEachIndexed
                    val p = (age / 280f).coerceIn(0f, 1f)
                    val ease = 1f - (1f - p) * (1f - p) * (1f - p)
                    val scale = .88f + ease * .12f + sin(p * PI.toFloat()) * .06f
                    val stagger = sin(i * 2.17f + phrase.start * .001f) * glyph.size.height * .1f
                    val center = phrase.point(fraction) - Offset(0f,
                        glyph.size.height * .58f + stagger + outgoing * 8.dp.toPx())
                    val lift = (1f - ease) * 9.dp.toPx()
                    withTransform({
                        translate(center.x, center.y + lift)
                        scale(scale, scale, Offset.Zero)
                    }) {
                        drawText(glyph, color = lerp(bright, ink, ease).copy(alpha = (p * 5f).coerceIn(0f, 1f) * fade),
                            topLeft = Offset(-glyph.size.width / 2f, -glyph.size.height / 2f))
                    }
                }
            }
            // Sample the same timeline for head and tail: phrase transitions never teleport.
            fun headAt(time: Float): Offset {
                val at = phrases.indexOfLast { it.start <= time }.coerceAtLeast(0)
                val phrase = phrases[at]
                val p = ((time - phrase.start) / (phrase.end - phrase.start).coerceAtLeast(1f)).coerceIn(0f, 1f)
                if (p <= .8f) return phrase.point(p / .8f)
                val next = phrases.getOrNull(at + 1) ?: return phrase.point(1f)
                val t = smoothWriting((p - .8f) / .2f)
                val from = phrase.point(1f)
                val to = next.point(0f)
                // Broad cubic connector with horizontal tangents, separate from stable text.
                val reach = minOf(width * .12f, (to - from).getDistance() * .25f)
                val c1 = from + Offset(reach, 0f)
                val c2 = to - Offset(reach, 0f)
                val u = 1f - t
                return from * (u * u * u) + c1 * (3f * u * u * t) + c2 * (3f * u * t * t) + to * (t * t * t)
            }
            val glowAlpha = if (now < phrases.first().start) 0f else
                (1f - ((now - phrases.last().end) / 900f).coerceIn(0f, 1f))
            val tailTime = minOf(650f, (currentPhrase.end - currentPhrase.start) * .55f)
            repeat(44) { segment ->
                val t = segment / 44f
                val from = headAt(now - tailTime * (1f - t))
                val to = headAt(now - tailTime * (1f - (segment + 1f) / 44f))
                val color = lerp(accent, bright, t * t)
                drawLine(color.copy(alpha = t * .1f * glowAlpha), from, to, 10.dp.toPx(), StrokeCap.Round)
                drawLine(color.copy(alpha = t * .8f * glowAlpha), from, to, 1.8.dp.toPx(), StrokeCap.Round)
            }
            val head = headAt(now)
            drawCircle(Brush.radialGradient(listOf(bright.copy(alpha = .4f * glowAlpha), Color.Transparent), head, 12.dp.toPx()),
                12.dp.toPx(), head)
            drawCircle(bright.copy(alpha = glowAlpha), 2.dp.toPx(), head)
        }
    }
}

private data class WritingPhrase(
    val row: Int, val start: Float, val end: Float, val glyphs: List<TextLayoutResult>,
    val centers: List<Float>, val left: Float, val y: Float, val width: Float, val slope: Float, val bend: Float
) {
    fun point(t: Float): Offset = Offset(left + width * t, y + slope * (t - .5f) + sin(t * PI.toFloat()) * bend)
}

private fun smoothWriting(t: Float): Float = t * t * (3f - 2f * t)

private fun writingGraphemes(text: String): List<String> {
    val iterator = BreakIterator.getCharacterInstance().apply { setText(text) }
    return buildList {
        var from = iterator.first()
        var to = iterator.next()
        while (to != BreakIterator.DONE) {
            add(text.substring(from, to))
            from = to
            to = iterator.next()
        }
    }
}
