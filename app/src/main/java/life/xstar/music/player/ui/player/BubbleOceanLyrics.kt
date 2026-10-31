package life.xstar.music.player.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import life.xstar.music.player.data.lyrics.LyricLine
import java.text.BreakIterator
import kotlin.math.*

/** Every sentence keeps its own circular home, including after its turn has finished. */
@Composable
internal fun BubbleOceanLyrics(
    lines: List<LyricLine>, index: Int, accent: Color, ink: Color, compact: Boolean,
    motion: Boolean, time: () -> Float, elapsed: () -> Long
) {
    val measurer = rememberTextMeasurer()
    val characters = remember(lines) {
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
    val style = TextStyle(fontSize = if (compact) 16.sp else 24.sp, fontWeight = FontWeight.Medium)
    val layouts = remember(characters, style, measurer) {
        characters.map { sentence -> sentence.map { measurer.measure(it, style, softWrap = false) } }
    }
    val field = remember(lines) {
        val random = kotlin.random.Random(lines.hashCode())
        val points = mutableListOf<Offset>()
        lines.indices.map {
            // Best-candidate sampling spreads points across the field without a visible grid.
            val point = List(28) { Offset(random.nextFloat(), random.nextFloat()) }.maxBy { candidate ->
                points.minOfOrNull { (candidate - it).getDistanceSquared() } ?: 1f
            }
            points += point
            Triple(point, random.nextFloat().pow(1.4f), random.nextFloat() * 6.283f)
        }
    }
    val depthOrder = remember(field) { field.indices.sortedBy { field[it].second } }
    val previewIndices = remember(characters) {
        val random = kotlin.random.Random(lines.hashCode() xor 0x51A7)
        characters.map { sentence ->
            val candidates = sentence.indices.filter { i -> sentence[i].any { it.isLetterOrDigit() } }
                .ifEmpty { sentence.indices.filter { sentence[it].isNotBlank() } }
            candidates.randomOrNull(random) ?: 0
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val seconds = if (motion) time() else 0f
            val age = elapsed().coerceAtLeast(0L)
            val opening = if (motion) (age / 1_050f).coerceIn(0f, 1f) else 1f
            val restore = if (motion) (age / 1_700f).coerceIn(0f, 1f) else 1f
            fun random(n: Int): Float = ((sin(n * 127.1 + 311.7) * 43758.5453) % 1.0)
                .let { if (it < 0) it + 1 else it }.toFloat()
            // Smooth seeded noise gives each bubble independent, non-periodic wandering.
            fun wander(t: Float, seed: Int): Float {
                val step = floor(t).toInt()
                val f = t - floor(t)
                val blend = f * f * f * (f * (f * 6f - 15f) + 10f)
                val a = random(seed + step * 173) * 2f - 1f
                val b = random(seed + (step + 1) * 173) * 2f - 1f
                return a + (b - a) * blend
            }
            fun home(n: Int): Offset {
                val (position, depth, phase) = field[n]
                val t = seconds * (.16f + depth * .13f) + phase
                val dx = wander(t, n * 719 + 31) + wander(t * 2.1f, n * 719 + 83) * .22f
                val dy = wander(t, n * 719 + 197) + wander(t * 1.9f, n * 719 + 251) * .22f
                return Offset(
                    size.width * (.08f + .84f * position.x) + dx * size.width * (.018f + depth * .035f),
                    size.height * (.10f + .80f * position.y) + dy * size.height * (.014f + depth * .026f))
            }
            fun radius(n: Int) = (if (compact) 10f else 16f).dp.toPx() * (.45f + field[n].second * 1.05f)
            fun opacity(n: Int) = .08f + field[n].second.pow(1.3f) * .48f
            fun bubble(n: Int, alpha: Float) {
                if (alpha <= 0f) return
                val point = home(n)
                val r = radius(n)
                drawCircle(Brush.radialGradient(listOf(Color.Transparent, accent.copy(alpha = alpha * .12f), ink.copy(alpha = alpha * .10f)),
                    point - Offset(r * .2f, r * .22f), r * 1.3f), r, point)
                drawCircle(accent.copy(alpha = alpha * .48f), r, point, style = Stroke(.65.dp.toPx()))
                drawArc(ink.copy(alpha = alpha * .65f), 205f, 65f, false, point - Offset(r * .85f, r * .85f),
                    androidx.compose.ui.geometry.Size(r * 1.7f, r * 1.7f), style = Stroke(.9.dp.toPx()))
                val first = layouts[n][previewIndices[n]]
                val scale = min(1f, r * 1.15f / max(first.size.width, first.size.height).coerceAtLeast(1))
                withTransform({ scale(scale, scale, point) }) {
                    drawText(first, color = ink.copy(alpha = alpha), topLeft = point - Offset(first.size.width / 2f, first.size.height / 2f))
                }
            }
            fun sentence(n: Int, expand: Float, alpha: Float) {
                val glyphs = layouts[n]
                val point = home(n)
                val gap = (if (compact) 1f else 2f).dp.toPx()
                val width = glyphs.sumOf { it.size.width.toDouble() }.toFloat() + gap * (glyphs.size - 1)
                val margin = (if (compact) 7f else 15f).dp.toPx()
                // Preserve the bubble's position; choose the axis before adjusting only the text bounds.
                val horizontal = width <= size.width - margin * 2
                val rawHeight = glyphs.sumOf { it.size.height.toDouble() }.toFloat() + gap * (glyphs.size - 1)
                val scale = if (horizontal) 1f else min(1f, (size.height - margin * 2) / rawHeight.coerceAtLeast(1f))
                val contentWidth = if (horizontal) width else glyphs.maxOf { it.size.width }.toFloat() * scale
                val contentHeight = if (horizontal) glyphs.maxOf { it.size.height }.toFloat() else rawHeight * scale
                val left = (point.x - contentWidth / 2).coerceIn(margin, max(margin, size.width - contentWidth - margin))
                val top = (point.y - contentHeight / 2).coerceIn(margin, max(margin, size.height - contentHeight - margin))
                var cursor = 0f
                val ease = 1f - (1f - expand).pow(3)
                val next = lines.drop(n + 1).firstOrNull { it.timeMs != null }?.timeMs
                val duration = ((next ?: ((lines[n].timeMs ?: 0L) + 3_200L)) - (lines[n].timeMs ?: 0L)).coerceIn(1_200L, 8_000L)
                val stagger = (duration / 1000f / glyphs.size.coerceAtLeast(1) * .72f).coerceIn(.045f, .16f)
                val seed = lines[n].text.fold(0f) { value, char -> value * 1.17f + char.code * .013f }
                glyphs.forEachIndexed { g, glyph ->
                    val target = if (horizontal) Offset(left + cursor + glyph.size.width / 2f, top + contentHeight / 2f)
                        else Offset(left + contentWidth / 2f, top + cursor + glyph.size.height * scale / 2f)
                    val raw = if (n == index && motion) (age / 1000f - .13f - g * stagger) / 1.02f else 1f
                    val pop = raw.coerceIn(0f, 1f)
                    val travel = (1f - pop).pow(3)
                    val throwOffset = Offset(cos((g + 1) * 2.31f + seed) * (if (compact) 40f else 120f).dp.toPx(),
                        sin((g + 1) * 1.87f + seed * .4f) * (if (compact) 26f else 78f).dp.toPx())
                    val center = point + (target - point) * ease + throwOffset * travel
                    val popScale = .16f + (1f - travel) * .84f + sin(pop * PI).toFloat() * .14f
                    val tilt = sin((g + 1) * 17.37f + seed) * 5.5f + sin((g + 1) * 3.2f + seed) * 18f * travel
                    val variation = 1f + sin((g + 1) * 9.13f + seed * .7f) * .055f
                    withTransform({ rotate(tilt, center); scale(scale * popScale * variation, scale * popScale * variation, center) }) {
                        drawText(glyph, color = (if (raw > 0f && raw < 1f) accent else ink).copy(alpha = alpha * (raw * 1.25f).coerceIn(0f, 1f)), topLeft = center - Offset(glyph.size.width / 2f, glyph.size.height / 2f))
                    }
                    cursor += (if (horizontal) glyph.size.width.toFloat() else glyph.size.height * scale) + gap * scale
                }
            }
            // Tiny empty bubbles form a quiet star field behind the lyric-bearing spheres.
            repeat(if (compact) 24 else 64) { dot ->
                val depth = random(dot * 13 + 901)
                val phase = random(dot * 17 + 1103) * 6.283f
                val t = seconds * (.10f + depth * .12f) + phase
                val point = Offset(
                    size.width * (.025f + .95f * random(dot * 23 + 1301)) + wander(t, dot * 37 + 1501) * 5.dp.toPx(),
                    size.height * (.025f + .95f * random(dot * 29 + 1709)) + wander(t, dot * 41 + 1901) * 5.dp.toPx())
                val r = (.55f + depth * 1.25f).dp.toPx()
                val alpha = (.07f + depth * .16f) * (.85f + .15f * sin(seconds * .5f + phase))
                drawCircle(accent.copy(alpha = alpha), r, point, style = Stroke(.4.dp.toPx()))
                if (depth > .72f) drawCircle(ink.copy(alpha = alpha * .65f), .35.dp.toPx(),
                    point - Offset(r * .3f, r * .35f))
            }
            // Old sentences return as bubbles rather than vanishing from the ocean forever.
            for (n in depthOrder) {
                if (n == index || n == index - 1) continue
                bubble(n, opacity(n))
            }
            if (index > 0) {
                val fade = 1f - restore * restore
                sentence(index - 1, 1f, fade * .82f)
                bubble(index - 1, opacity(index - 1) * restore.pow(2))
            }
            val point = home(index)
            val fracture = (opening / .42f).coerceIn(0f, 1f)
            bubble(index, (1f - fracture) * .75f)
            if (motion && fracture > 0f && fracture < 1f) {
                val fade = (1f - fracture).pow(2)
                repeat(20) { shard ->
                    val angle = shard * 2.39996f + index
                    val direction = Offset(cos(angle), sin(angle))
                    val distance = radius(index) * (1f + fracture * (1.5f + random(shard)))
                    val head = point + direction * distance + Offset(0f, fracture * fracture * 12.dp.toPx())
                    val tail = head - direction * (2f + fracture * 5f).dp.toPx()
                    drawLine(accent.copy(alpha = fade * .8f), tail, head, .8.dp.toPx())
                    drawCircle(ink.copy(alpha = fade * .6f), .9.dp.toPx(), head)
                }
                drawCircle(accent.copy(alpha = fade * .4f), radius(index) * (1f + fracture), point, style = Stroke(.6.dp.toPx()))
            }
            val expand = ((opening - .12f) / .88f).coerceIn(0f, 1f)
            sentence(index, expand, (expand * 4f).coerceIn(0f, 1f))
        }
    }
}
