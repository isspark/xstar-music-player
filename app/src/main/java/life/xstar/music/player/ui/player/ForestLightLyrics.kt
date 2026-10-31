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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import life.xstar.music.player.data.lyrics.LyricLine
import java.text.BreakIterator
import kotlin.math.*
import kotlin.random.Random

private data class LightLetter(
    val layout: TextLayoutResult, val target: Offset, val pile: Offset,
    val angle: Float, val pileAngle: Float,
    val waitingX: Float = 0f, val waitingScale: Float = 1f
)

private data class LightSentence(
    val letters: List<LightLetter>, val fit: Float, val retreat: Offset,
    val halfWidth: Float, val halfHeight: Float
)
private data class LightDust(val home: Offset, val depth: Float, val phase: Float)

/** Transparent typography stage: only light and letters are painted over the player's background. */
@Composable
internal fun ForestLightLyrics(
    lines: List<LyricLine>, index: Int, accent: Color, ink: Color, compact: Boolean, motion: Boolean,
    time: () -> Float, elapsed: () -> Long
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val songSeed = remember(lines) { lines.hashCode() }
    val dust = remember(songSeed) {
        val random = Random(songSeed xor 0x71A3)
        List(110) { LightDust(Offset(random.nextFloat(), random.nextFloat()),
            random.nextFloat(), random.nextFloat() * 6.283f) }
    }
    val nextTime = remember(lines, index) {
        val start = lines[index].timeMs
        lines.asSequence().drop(index + 1).mapNotNull { it.timeMs }
            .firstOrNull { start == null || it > start }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val height = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        // Retain only the previous sentence and one upcoming pile.
        val arrangements = remember(lines, index, width, height, compact, density, measurer) {
            (max(0, index - 1)..min(lines.lastIndex, index + 1)).associateWith { n ->
                val random = Random(songSeed xor (n * 7919))
                val text = lines[n].text.ifBlank { "♪" }
                val iterator = BreakIterator.getCharacterInstance().apply { setText(text) }
                val glyphs = buildList {
                    var start = iterator.first()
                    var end = iterator.next()
                    while (end != BreakIterator.DONE) {
                        add(text.substring(start, end))
                        start = end
                        end = iterator.next()
                    }
                }
                val emphasis = random.nextInt(4)
                val layouts = glyphs.mapIndexed { i, glyph ->
                    // Repeat a deliberate editorial rhythm: hero, small connector, medium and large.
                    val factor = when ((i + emphasis) % 4) {
                        0 -> 1.52f
                        1 -> .67f
                        2 -> 1.02f
                        else -> 1.24f
                    } * (.94f + random.nextFloat() * .12f)
                    measurer.measure(glyph, TextStyle(fontSize = ((if (compact) 19 else 29) * factor).sp,
                        fontWeight = if (factor > 1.2f) FontWeight.Bold else FontWeight.Medium), softWrap = false)
                }
                val style = random.nextInt(2) // straight rows or columns, with a shared sentence tilt
                val tilt = random.nextFloat() * 20f - 10f
                val cell = layouts.maxOf { max(it.size.width, it.size.height) }.toFloat().coerceAtLeast(1f)
                val perRun = if (style == 1) {
                    (height * .45f / cell).toInt().coerceIn(3, 9)
                } else (width * .77f / cell).toInt().coerceIn(4, 16)
                val runs = ceil(layouts.size.toFloat() / perRun).toInt()
                val gap = cell * .09f
                val runExtents = (0 until runs).map { run ->
                    val part = layouts.subList(run * perRun, min(layouts.size, (run + 1) * perRun))
                    part.sumOf { (if (style == 1) it.size.height else it.size.width).toDouble() }.toFloat() +
                        (part.size - 1) * gap
                }
                val cursors = runExtents.map { -it / 2f }.toMutableList()
                val letters = layouts.mapIndexed { i, layout ->
                    val run = i / perRun
                    val extent = (if (style == 1) layout.size.height else layout.size.width).toFloat()
                    val axis = cursors[run] + extent / 2f
                    cursors[run] += extent + gap
                    val target = when (style) {
                        1 -> Offset(((runs - 1) / 2f - run) * cell * 1.18f, axis)
                        else -> Offset(axis, (run - (runs - 1) / 2f) * cell * 1.24f)
                    }
                    val radians = tilt * PI.toFloat() / 180f
                    val rotated = Offset(target.x * cos(radians) - target.y * sin(radians),
                        target.x * sin(radians) + target.y * cos(radians))
                    LightLetter(layout, rotated,
                        Offset((random.nextFloat() - .5f) * width * .28f,
                            (random.nextFloat() - .5f) * height * .17f),
                        tilt,
                        random.nextFloat() * 150f - 75f)
                }
                // Include rotated glyph corners so long lyrics and large accessibility fonts stay inside the stage.
                val halfWidth = letters.maxOf { abs(it.target.x) + (it.layout.size.width + it.layout.size.height) * .55f }
                val halfHeight = letters.maxOf { abs(it.target.y) + (it.layout.size.width + it.layout.size.height) * .55f }
                val fit = min(1f, min(width * .41f / halfWidth, height * .23f / halfHeight))
                val radians = tilt * PI.toFloat() / 180f
                val side = if (random.nextBoolean()) 1f else -1f
                // Retreat perpendicular to the reading axis, including the sentence's tilt.
                val retreat = if (style == 1) Offset(cos(radians), sin(radians)) * side
                    else Offset(-sin(radians), cos(radians)) * side
                val waitingHeight = with(density) { (if (compact) 11 else 14).sp.toPx() }
                val waitingScales = letters.map { waitingHeight / it.layout.size.height.coerceAtLeast(1) }
                val waitingGap = waitingHeight * .10f
                val waitingWidth = letters.indices.sumOf { i ->
                    (letters[i].layout.size.width * waitingScales[i] + waitingGap).toDouble()
                }.toFloat() - waitingGap
                val waitingFit = min(1f, width * .84f / waitingWidth.coerceAtLeast(1f))
                var waitingX = -waitingWidth * waitingFit / 2f
                val ordered = letters.mapIndexed { i, letter ->
                    val letterWidth = letter.layout.size.width * waitingScales[i] * waitingFit
                    val placed = letter.copy(waitingX = waitingX + letterWidth / 2f,
                        waitingScale = waitingScales[i] * waitingFit)
                    waitingX += letterWidth + waitingGap * waitingFit
                    placed
                }
                LightSentence(ordered, fit, retreat, halfWidth, halfHeight)
            }
        }
        val beamPath = remember { Path() }
        Canvas(Modifier.fillMaxSize()) {
            val t = if (motion) time() else 0f
            val age = elapsed().coerceAtLeast(0).toFloat()
            val duration = lines[index].timeMs?.let { start -> nextTime?.let { (it - start).coerceAtLeast(400) } } ?: 5000L
            val progress = if (motion) (age / duration).coerceIn(0f, 1f) else 1f
            val formationDuration = min(1700f, duration * .36f)
            val staggerDuration = min(2200f, duration * .42f)
            val transition = if (motion) lightEase(age / formationDuration) else 1f
            // Seeded random keyframes are smoothly interpolated; never choose a new angle every frame.
            // A sun-like arc: side-lit dawn, overhead daylight, then side-lit dusk.
            // The arc reverses gently at the horizon, avoiding a teleport between sunset and sunrise.
            val sunAngle = PI.toFloat() * (.5f - .5f * cos(t * .085f))
            val elevation = sin(sunAngle).coerceIn(0f, 1f)
            val intensity = ((.30f + elevation * .70f) *
                (.80f + lightNoise(t / 4.7f, songSeed + 19) * .20f)).coerceIn(.16f, 1f)
            val aim = Offset(width * (.5f + lightNoise(t / 7f, songSeed + 71) * .16f), height * .52f)
            val sun = Offset(-cos(sunAngle), -elevation)
            // Intersect the sun direction with the viewport edge: top light starts at the
            // actual top edge, not at the boundary of the former lyric-only rectangle.
            val edgeX = if (abs(sun.x) < .0001f) Float.POSITIVE_INFINITY
                else (if (sun.x < 0f) aim.x else width - aim.x) / abs(sun.x)
            val edgeY = if (sun.y >= -.0001f) Float.POSITIVE_INFINITY else aim.y / -sun.y
            val lamp = aim + sun * min(edgeX, edgeY)
            val ray = aim - lamp
            val direction = ray / ray.getDistance().coerceAtLeast(1f)
            val normal = Offset(direction.y, -direction.x)
            val length = hypot(width, height) * 1.7f
            val daylight = lerp(Color(0xFFFFCE98), Color.White, elevation)
            val beamColor = lerp(accent, daylight, .72f)
            val diffusion = .95f + lightNoise(t / 9f, songSeed + 137) * .12f
            val sourceWidth = min(width, height) * .075f
            // Broad overlapping penumbrae, with progressively weaker outer edges.
            // A wide source avoids the sharp, flashlight-like point of the old narrow cone.
            for (layer in 27 downTo 0) {
                val fraction = layer / 27f
                val spread = (.10f + fraction * .38f) * diffusion
                val bend = sin(t * .16f + fraction * 2.2f) * .025f
                val end = lamp + direction * length + normal * (length * bend)
                val sourceSpread = sourceWidth * (.4f + fraction * .6f)
                beamPath.reset()
                beamPath.moveTo(lamp.x - normal.x * sourceSpread, lamp.y - normal.y * sourceSpread)
                beamPath.lineTo(lamp.x + normal.x * sourceSpread, lamp.y + normal.y * sourceSpread)
                beamPath.lineTo(end.x + normal.x * length * spread, end.y + normal.y * length * spread)
                beamPath.lineTo(end.x - normal.x * length * spread, end.y - normal.y * length * spread)
                beamPath.close()
                val feather = (1f - fraction).pow(1.7f)
                val opacity = .009f * feather * intensity
                drawPath(beamPath, Brush.linearGradient(listOf(beamColor.copy(alpha = opacity),
                    beamColor.copy(alpha = opacity * .55f), Color.Transparent), lamp, end))
            }
            // Soft volumes of airborne haze break up the otherwise uniform cone.
            repeat(3) { n ->
                val along = length * (.15f + n * .14f)
                val point = lamp + direction * along + normal *
                    (sin(t * .17f + n * 2.3f) * along * .09f)
                val radius = (sourceWidth + along * .34f) * diffusion
                drawCircle(Brush.radialGradient(listOf(beamColor.copy(alpha = intensity * .018f),
                    beamColor.copy(alpha = intensity * .006f), Color.Transparent), point, radius), radius, point)
            }
            fun exposure(point: Offset): Float {
                val delta = point - lamp
                val along = delta.x * direction.x + delta.y * direction.y
                if (along <= 0f) return 0f
                val across = abs(delta.x * normal.x + delta.y * normal.y)
                val spread = sourceWidth + along * .27f * diffusion
                return (exp(-(across / spread).pow(2) * 1.35f) *
                    (1f - along / length).coerceIn(0f, 1f) * intensity).toFloat()
            }
            // Dust is almost invisible outside the light. Depth changes drift, size and scattering.
            dust.forEach { mote ->
                val depth = .35f + mote.depth * .65f
                val point = Offset(
                    ((mote.home.x + t * .002f * depth) % 1f) * width + sin(t * .24f + mote.phase) * width * .018f,
                    ((mote.home.y - t * .003f * depth) % 1f + 1f) % 1f * height +
                        cos(t * .19f + mote.phase) * height * .016f)
                val lit = exposure(point)
                val opacity = (.012f + lit * .65f) * (.42f + mote.depth * .58f)
                val radius = (.35f + mote.depth * 1.25f) * density.density
                if (lit > .08f && mote.depth > .55f) {
                    drawCircle(beamColor.copy(alpha = opacity * .14f), radius * 3.3f, point)
                }
                drawCircle(beamColor.copy(alpha = opacity), radius, point)
            }
            val center = Offset(width * .5f, height * .50f)
            val waitingAnchor = Offset(width * .5f, height * .94f)
            fun waitingPoint(letter: LightLetter) = waitingAnchor + Offset(letter.waitingX, 0f)
            fun drawSentence(n: Int, distance: Float, opacity: Float, entering: Boolean) {
                val sentence = arrangements[n] ?: return
                val scale = sentence.fit * (1f - distance * .17f).coerceAtLeast(.38f)
                val displacement = Offset(sentence.retreat.x * width * .30f,
                    sentence.retreat.y * height * .24f) * distance
                val safeX = (width * .47f - sentence.halfWidth * scale).coerceAtLeast(0f)
                val safeY = (height * .46f - sentence.halfHeight * scale).coerceAtLeast(0f)
                val retreat = Offset(displacement.x.coerceIn(-safeX, safeX),
                    displacement.y.coerceIn(-safeY, safeY))
                val floatAmount = .65f + min(distance, 1f) * .35f
                val anchor = center + retreat + Offset(sin(t * .42f + n * 1.7f) * width * .016f * floatAmount,
                    sin(t * .53f + n) * height * .012f * floatAmount)
                sentence.letters.forEachIndexed { i, letter ->
                    val formation = if (!entering || !motion) 1f else {
                        val delay = i.toFloat() / sentence.letters.size * staggerDuration
                        lightEase((age - delay) / formationDuration)
                    }
                    val destination = anchor + letter.target * scale
                    // Every glyph owns its full bottom-to-focus flight. Delayed glyphs stay
                    // at exactly the same location and scale as the upcoming ordered sentence.
                    val launch = waitingPoint(letter)
                    val arc = sin(formation * PI.toFloat()) * letter.pile.x * .25f
                    val point = launch * (1f - formation) + destination * formation + Offset(arc, 0f)
                    val glyphScale = letter.waitingScale * (1f - formation) + scale * formation
                    val lit = exposure(point)
                    val sung = if (!entering) 1f else (progress * sentence.letters.size - i).coerceIn(0f, 1f)
                    val color = lerp(ink.copy(alpha = .38f), accent, sung)
                    val illuminated = lerp(color, lerp(accent, Color.White, .82f), lit * .87f)
                    val alpha = opacity * (if (entering) .22f + formation * .78f else 1f)
                    val glow = max(sung * .38f, lit * .9f) * opacity
                    withTransform({
                        translate(point.x, point.y)
                        rotate(letter.angle * formation, Offset.Zero)
                        scale(glyphScale, glyphScale, Offset.Zero)
                    }) {
                        drawText(letter.layout, color = illuminated.copy(alpha = illuminated.alpha * alpha),
                            topLeft = Offset(-letter.layout.size.width / 2f, -letter.layout.size.height / 2f),
                            shadow = Shadow(accent.copy(alpha = glow), blurRadius = 5f + lit * 17f))
                    }
                }
            }
            // Previous sentence begins exactly at its old reading position, then is pushed back by the incoming one.
            if (index > 0) drawSentence(index - 1, transition, 1f - transition * .56f, false)
            // Upcoming lyrics remain on the bottom edge until their own timestamp is reached.
            arrangements[index + 1]?.let { upcoming ->
                upcoming.letters.forEach { letter ->
                    val point = waitingPoint(letter)
                    withTransform({ translate(point.x, point.y); scale(letter.waitingScale, letter.waitingScale, Offset.Zero) }) {
                        drawText(letter.layout, ink.copy(alpha = .10f + exposure(point) * .2f),
                            Offset(-letter.layout.size.width / 2f, -letter.layout.size.height / 2f))
                    }
                }
            }
            drawSentence(index, 0f, 1f, true)
        }
    }
}

private fun lightEase(value: Float): Float {
    val p = value.coerceIn(0f, 1f)
    return p * p * (3f - 2f * p)
}

private fun lightNoise(time: Float, seed: Int): Float {
    val step = floor(time).toInt()
    val blend = lightEase(time - step)
    fun sample(n: Int) = Random(seed xor (n * 104729)).nextFloat() * 2f - 1f
    return sample(step) * (1f - blend) + sample(step + 1) * blend
}
