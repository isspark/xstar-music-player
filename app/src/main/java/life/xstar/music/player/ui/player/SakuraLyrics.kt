package life.xstar.music.player.ui.player

import android.graphics.Bitmap
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import life.xstar.music.player.data.lyrics.LyricLine
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Gently falling maple leaves settle directly into the strokes of the current lyric. */
@Composable
fun SakuraLyrics(
    lines: List<LyricLine>, current: Int, position: Long, playing: Boolean,
    accent: Color, ink: Color, modifier: Modifier = Modifier, compact: Boolean = false
) {
    val index = current.coerceIn(0, (lines.size - 1).coerceAtLeast(0))
    val line = lines.getOrNull(index) ?: return
    val density = LocalDensity.current
    var clock by remember(lines) { mutableFloatStateOf(0f) }
    var elapsed by remember(lines, index) { mutableFloatStateOf(0f) }
    LaunchedEffect(playing, lines, index) {
        if (playing) {
            var last = withFrameNanos { it }
            while (true) withFrameNanos { now ->
                val dt = ((now - last) / 1_000_000_000f).coerceIn(0f, .05f)
                clock += dt
                elapsed += dt
                last = now
            }
        }
    }
    val duration = ((lines.drop(index + 1).firstOrNull { it.timeMs != null }?.timeMs
        ?: ((line.timeMs ?: position) + 5_000L)) - (line.timeMs ?: position)).coerceAtLeast(800L) / 1000f
    val formationTime = minOf(2.8f, duration * .65f).coerceAtLeast(.6f)
    // A paused first render still shows the lyric; pausing an animation preserves its pose.
    val phase = if (!playing && elapsed == 0f) 1f else (elapsed / formationTime).coerceIn(0f, 1f)
    BoxWithConstraints(modifier) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val fontSize = with(density) { (if (compact) 17.dp else 28.dp).toPx() }
        val targets = remember(line.text, width, height, fontSize, compact) {
            sakuraLetterPoints(line.text.ifBlank { "♪" }, width, height, fontSize, compact)
        }
        val history = remember(index, lines, width, height, fontSize, compact) {
            (1..2).map { age ->
                lines.getOrNull(index - age)?.let {
                    sakuraLetterPoints(it.text.ifBlank { "♪" }, width, height, fontSize, compact)
                }.orEmpty()
            }
        }
        val seeds = remember { List(2400) { i -> Random(i * 7919 + 43).nextFloat() } }
        val leaf = remember { mapleLeafPath() }
        Canvas(Modifier.fillMaxSize().clipToBounds()) {
            val amber = lerp(Color(0xFFEBA04B), accent, .08f)
            val crimson = lerp(Color(0xFFDA5945), accent, .08f)
            val unit = minOf(size.width / 360f, size.height / if (compact) 100f else 440f)
            // Loose leaves drift across the whole surface on the same paused clock as the lyrics.
            for (i in 0 until if (compact) 15 else 48) {
                val fall = (clock * (.025f + seeds[i + 500] * .02f) + seeds[i + 200]) % 1f
                val x = size.width * (.04f + seeds[i] * .92f) + sin(clock * .55f + i) * 20f * unit
                val y = -12f * unit + (size.height + 24f * unit) * fall
                val fade = (fall * 10f).coerceAtMost(1f) * ((1f - fall) * 6f).coerceAtMost(1f)
                mapleLeaf(leaf, Offset(x, y), (4f + seeds[i + 100] * 3f) * unit,
                    clock * 16f + sin(clock * .7f + i) * 24f + i * 47f,
                    lerp(amber, crimson, seeds[i + 300]).copy(alpha = .5f * fade))
            }
            val slide = (elapsed / .9f).coerceIn(0f, 1f).let {
                if (!playing && elapsed == 0f) 1f else it * it * (3f - 2f * it)
            }
            val rowSpacing = size.height * .19f
            history.forEachIndexed { row, points ->
                val age = row + 1
                val offset = Offset(0f, (row + slide) * rowSpacing)
                points.forEachIndexed { i, point ->
                    val autumn = lerp(amber, crimson, seeds[i % seeds.size])
                    mapleLeaf(leaf, point + offset, targetsRadius(fontSize), 0f,
                        lerp(autumn, ink, .32f).copy(alpha = if (age == 1) .66f else .4f))
                }
            }
            targets.forEachIndexed { i, target ->
                val seed = seeds[i % seeds.size]
                val delay = seed * .30f
                val p = ((phase - delay) / (1f - delay)).coerceIn(0f, 1f)
                val smooth = p * p * (3f - 2f * p)
                val angle = seeds[(i + 311) % seeds.size] * 2f * PI.toFloat()
                val distance = fontSize * (.6f + seeds[(i + 733) % seeds.size] * 1.2f)
                val origin = target + Offset(cos(angle), sin(angle)) * distance
                val wind = sin(p * PI.toFloat()) * sin(i * 1.73f + p * 4f) * fontSize * .25f
                val center = origin + (target - origin) * smooth + Offset(wind, 0f)
                val autumn = lerp(amber, crimson, seed)
                val color = lerp(autumn, lerp(autumn, ink, .32f), smooth)
                mapleLeaf(leaf, center, targetsRadius(fontSize) * (1f + (1f - p) * 2f),
                    (i * 71f + clock * 22f) * (1f - smooth), color.copy(alpha = .9f))
            }
        }
    }
}

private fun targetsRadius(fontSize: Float) = (fontSize / 22f).coerceAtLeast(.85f)

private fun DrawScope.mapleLeaf(leaf: Path, center: Offset, radius: Float, angle: Float, color: Color) {
    withTransform({
        translate(center.x, center.y)
        rotate(angle, Offset.Zero)
        scale(radius, radius, Offset.Zero)
    }) {
        drawPath(leaf, color)
    }
}

/** Five pointed lobes, serrated edges and a short stem, reused for every leaf. */
private fun mapleLeafPath() = Path().apply {
    moveTo(0f, -1f)
    lineTo(.18f, -.58f)
    lineTo(.32f, -.68f)
    lineTo(.28f, -.22f)
    lineTo(.72f, -.62f)
    lineTo(.68f, -.30f)
    lineTo(.94f, -.28f)
    lineTo(.66f, .06f)
    lineTo(.98f, .14f)
    lineTo(.76f, .30f)
    lineTo(.80f, .46f)
    lineTo(.28f, .43f)
    lineTo(.10f, .57f)
    lineTo(.07f, .92f)
    lineTo(-.04f, .92f)
    lineTo(-.02f, .56f)
    lineTo(-.28f, .43f)
    lineTo(-.80f, .46f)
    lineTo(-.76f, .30f)
    lineTo(-.98f, .14f)
    lineTo(-.66f, .06f)
    lineTo(-.94f, -.28f)
    lineTo(-.68f, -.30f)
    lineTo(-.72f, -.62f)
    lineTo(-.28f, -.22f)
    lineTo(-.32f, -.68f)
    lineTo(-.18f, -.58f)
    close()
}

/** Rasterize wrapped text once per line, then sample its strokes as leaf destinations. */
private fun sakuraLetterPoints(text: String, width: Float, height: Float, fontSize: Float, compact: Boolean): List<Offset> {
    if (width < 2 || height < 2) return emptyList()
    val textWidth = (width * .84f).toInt().coerceAtLeast(1)
    val paint = TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = fontSize
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    var layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, textWidth)
        .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false).build()
    // Reserve a separate slot for each of the three sentences, including wrapped text.
    val maxHeight = height * .15f
    if (layout.height > maxHeight) {
        paint.textSize *= maxHeight / layout.height
        layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, textWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false).build()
    }
    val bitmap = Bitmap.createBitmap(textWidth, layout.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    layout.draw(android.graphics.Canvas(bitmap))
    val points = mutableListOf<Offset>()
    val step = (paint.textSize / 16f).toInt().coerceAtLeast(2)
    val left = (width - textWidth) / 2f
    val top = height * .42f - layout.height / 2f
    // Rotate sampled glyph strokes as a unit, with a stable angle for each character.
    val glyphs = mutableListOf<Pair<Int, Int>>()
    var start = 0
    while (start < text.length) {
        val end = start + Character.charCount(text.codePointAt(start))
        glyphs += start to end
        start = end
    }
    val pivots = glyphs.map { (from, to) ->
        val lineIndex = layout.getLineForOffset(from)
        Offset(layout.getPrimaryHorizontal(from) + paint.measureText(text, from, to) / 2f,
            (layout.getLineTop(lineIndex) + layout.getLineBottom(lineIndex)) / 2f)
    }
    val angles = glyphs.map { (from, _) ->
        (Random(text.hashCode() * 31 + from * 7919).nextFloat() - .5f) * .16f
    }
    val glyphForOffset = IntArray(text.length)
    glyphs.forEachIndexed { i, (from, to) ->
        for (offset in from until to) glyphForOffset[offset] = i
    }
    for (y in 0 until bitmap.height step step) for (x in 0 until bitmap.width step step) {
        if (android.graphics.Color.alpha(bitmap.getPixel(x, y)) > 100) {
            val lineIndex = layout.getLineForVertical(y)
            val offset = layout.getOffsetForHorizontal(lineIndex, x.toFloat()).coerceIn(0, text.lastIndex)
            // Offset lookup returns the nearest caret; choose the glyph on its left when needed.
            val glyph = glyphForOffset[if (layout.getPrimaryHorizontal(offset) > x &&
                offset > layout.getLineStart(lineIndex)) offset - 1 else offset]
            val pivot = pivots[glyph]
            val angle = angles[glyph]
            val dx = x - pivot.x
            val dy = y - pivot.y
            points += Offset(left + pivot.x + dx * cos(angle) - dy * sin(angle),
                top + pivot.y + dx * sin(angle) + dy * cos(angle))
        }
    }
    bitmap.recycle()
    // Bound drawing cost even for unusually long lyrics.
    val stride = ((points.size + 2199) / 2200).coerceAtLeast(1)
    return points.filterIndexed { i, _ -> i % stride == 0 }
}
