package life.xstar.music.player.ui.player

import android.animation.ValueAnimator
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import life.xstar.music.player.data.lyrics.LyricLine
import life.xstar.music.player.data.preferences.LyricsMode
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** The complete lyric document remains visible; the playing line is a focus within the composition. */
@Composable
internal fun WholeLyricsStage(
    lines: List<LyricLine>, focused: Int, mode: LyricsMode, playing: Boolean,
    position: Long, palette: ScenePalette, modifier: Modifier = Modifier, onLine: (Int) -> Unit
) {
    val motionEnabled = ValueAnimator.areAnimatorsEnabled()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var time by remember(lines, mode) { mutableFloatStateOf(0f) }
    var lyricEntryTime by remember(lines, mode) { mutableFloatStateOf(0f) }
    var lastFocusedIndex by remember(lines, mode) { mutableIntStateOf(focused) }
    val latestPlaying by rememberUpdatedState(playing)
    LaunchedEffect(focused) {
        if (focused != lastFocusedIndex) {
            lyricEntryTime = time
            lastFocusedIndex = focused
        }
    }
    LaunchedEffect(lifecycle, motionEnabled) {
        if (motionEnabled) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var last = android.os.SystemClock.uptimeMillis()
            while (isActive) {
                val now = android.os.SystemClock.uptimeMillis()
                if (latestPlaying) time += (now - last).coerceAtMost(100) / 1000f
                last = now
                delay(if (latestPlaying) 33 else 150)
            }
        }
    }
    BoxWithConstraints(modifier) {
        val count = lines.size.coerceAtLeast(1)
        val focusedIndex = focused.coerceIn(0, count - 1)
        val density = androidx.compose.ui.platform.LocalDensity.current
        val canvasWidth = constraints.maxWidth.toFloat()
        val canvasHeight = constraints.maxHeight.toFloat()
        // Pick a new focus point only when the lyric index changes. The margins keep the
        // current line readable while making each lyric composition feel freshly arranged.
        val focusSeed = focusedIndex * 1.917f + lines.getOrNull(focusedIndex)?.text.orEmpty().length * .37f
        val focusX = canvasWidth * (.30f + ((sin(focusSeed) + 1f) / 2f) * .40f)
        val focusY = canvasHeight * (.30f + ((sin(focusSeed * 1.47f + 2f) + 1f) / 2f) * .40f)
        val rowGap = (canvasHeight / 5.8f).coerceIn(
            with(density) { 38.dp.toPx() }, with(density) { 72.dp.toPx() })
        // The focus rail is deliberately drawn behind the current line. It makes the
        // center position obvious even when the surrounding composition is moving.
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val pulse = if (motionEnabled) (sin(time * 3.1f) + 1f) / 2f else 0f
            drawLine(palette.accent.copy(alpha = .10f + pulse * .10f), Offset(18.dp.toPx(), center.y),
                Offset(size.width - 18.dp.toPx(), center.y), (1f + pulse).dp.toPx())
            drawCircle(Brush.radialGradient(listOf(palette.accent.copy(alpha = .13f + pulse * .08f), Color.Transparent), center, 86.dp.toPx()),
                86.dp.toPx(), center)
            drawCircle(palette.accent.copy(alpha = .25f + pulse * .18f), 7.dp.toPx() + pulse * 3.dp.toPx(), center, style = Stroke(1.dp.toPx()))
        }
        val windowRadius = 4
        val windowStart = (focusedIndex - windowRadius).coerceAtLeast(0)
        val windowEnd = (focusedIndex + windowRadius).coerceAtMost(lines.lastIndex)
        val currentTime = lines.getOrNull(focusedIndex)?.timeMs ?: position
        val nextTime = lines.drop(focusedIndex + 1).firstOrNull { it.timeMs != null }?.timeMs
        val currentProgress = if (nextTime != null && nextTime > currentTime) {
            ((position - currentTime).toFloat() / (nextTime - currentTime).toFloat()).coerceIn(0f, 1f)
        } else 0f
        lines.forEachIndexed { index, line ->
            if (index !in windowStart..windowEnd) return@forEachIndexed
            val distance = index - focusedIndex
            val isCurrent = index == focusedIndex
            val proximity = (1f - abs(distance).toFloat() / (windowRadius + 1f)).coerceIn(0f, 1f)
            // Give each lyric its own slow phase. The deterministic seed keeps the motion
            // stable across recompositions while still making nearby lines appear and leave
            // at different moments, like particles in a living map.
            val randomPhase = index * 2.417f + (index % 5) * .63f
            val randomWave = (sin(time * (.72f + (index % 4) * .11f) + randomPhase) + 1f) / 2f
            val randomPresence = if (isCurrent) 1f else .68f + randomWave * .32f
            // DensityMap is laid out relative to the focused line. This is important: when
            // focus advances, the whole composition becomes a continuous carousel instead of
            // replacing one arbitrary cloud with another. The small time drift keeps the map
            // alive while the larger distance keeps neighbouring lines apart.
            val densityDistance = abs(distance).coerceAtMost(windowRadius).toFloat()
            val densityAngle = distance * .92f + index * .41f + time * .16f
            val densityRadiusX = canvasWidth * (.25f + min(densityDistance, 3f) * .095f)
            val densityRadiusY = canvasHeight * (.20f + min(densityDistance, 3f) * .085f)
            val densityX = canvasWidth / 2f + cos(densityAngle) * densityRadiusX
            val densityY = canvasHeight / 2f + sin(densityAngle) * densityRadiusY
            val leadIn = if (index > focusedIndex && line.timeMs != null && nextTime != null && nextTime > position) {
                (1f - ((line.timeMs - position).toFloat() / (nextTime - position).toFloat()).coerceIn(0f, 1f))
            } else 0f
            val targetX = if (isCurrent) canvasWidth / 2f else if (mode == LyricsMode.DensityMap) {
                // Future lyrics spiral inward as their timestamp approaches. This gives the
                // next line a visible entrance rather than a jump at the exact beat.
                densityX + (canvasWidth / 2f - densityX) * leadIn * .72f
            } else densityX
            val centerY = canvasHeight / 2f
            val baseTargetY = if (isCurrent) centerY else if (mode == LyricsMode.DensityMap) {
                densityY
            } else densityY
            // Future lines are pulled toward the focus rail as their timestamp approaches.
            // The current line gets a small breathing offset, while the previous line exits.
            val targetY = when {
                isCurrent -> focusY + sin(time * 1.5f) * 7f
                index > focusedIndex -> baseTargetY + (focusY - baseTargetY) * leadIn * .72f
                else -> baseTargetY
            }
            val resolvedTargetX = if (isCurrent) focusX else if (index > focusedIndex) {
                targetX + (focusX - targetX) * leadIn * .72f
            } else targetX
            val currentColor = if (isCurrent) {
                lerp(palette.accent, Color.White, .16f + ((sin(time * .85f) + 1f) / 2f) * .24f)
            } else palette.ink
            val scale by animateFloatAsState(.62f + proximity * .40f, tween(if (motionEnabled) 500 else 0), label = "wholeLyricScale")
            val animatedX by animateFloatAsState(resolvedTargetX, tween(if (motionEnabled) 520 else 0), label = "wholeLyricX")
            val animatedY by animateFloatAsState(targetY, tween(if (motionEnabled) 560 else 0), label = "wholeLyricY")
            val alpha by animateFloatAsState(
                (.12f + proximity * .88f) * randomPresence,
                tween(if (motionEnabled) 450 else 0), label = "wholeLyricAlpha")
            Box(
                modifier = Modifier
                    .fillMaxWidth(.68f)
                    .graphicsLayer {
                        translationX = animatedX - size.width / 2f
                        translationY = animatedY - size.height / 2f
                        this.alpha = alpha
                         val breath = if (motionEnabled) sin(time * 3.1f + index * .2f) * .025f * proximity else 0f
                         val appearanceScale = if (mode == LyricsMode.DensityMap && !isCurrent) .92f + randomPresence * .08f else 1f
                         val appearanceLift = if (mode == LyricsMode.DensityMap && !isCurrent) (1f - randomPresence) * 8f else 0f
                         scaleX = scale * appearanceScale * (1f + breath)
                         scaleY = scale * appearanceScale * (1f + breath)
                         translationY += appearanceLift
                         rotationZ = sin(time * .2f + index) * 3f * (1f - proximity)
                    }
                    .clickable(enabled = line.timeMs != null) { onLine(index) },
                contentAlignment = Alignment.Center
            ) {
                if (proximity > .68f) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 3.dp)) {
                         Text("◆", color = palette.accent.copy(alpha = .35f + proximity * .45f), fontSize = (5f + proximity * 2f).sp,
                            modifier = Modifier.align(Alignment.CenterStart).graphicsLayer { this.alpha = .65f + pulse(time) * .35f })
                         Text("◆", color = palette.accent.copy(alpha = .35f + proximity * .45f), fontSize = (5f + proximity * 2f).sp,
                            modifier = Modifier.align(Alignment.CenterEnd).graphicsLayer { this.alpha = .65f + pulse(time + 1.3f) * .35f })
                        if (isCurrent) CurrentLyricText(
                            line.text.ifBlank { "♪" }, currentColor, proximity,
                            time - lyricEntryTime, focusedIndex
                        )
                        else Text(
                    line.text.ifBlank { "♪" },
                    color = currentColor,
                 fontSize = if (mode == LyricsMode.DensityMap) (9f + proximity * 13f).sp else (11f + proximity * 17f).sp,
                fontWeight = if (proximity > .82f) FontWeight.Bold else if (proximity > .45f) FontWeight.Medium else FontWeight.Normal,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp)
                        )
                    }
                } else Text(
                    line.text.ifBlank { "♪" }, color = palette.ink.copy(alpha = alpha),
                     fontSize = if (mode == LyricsMode.DensityMap) (9f + proximity * 13f).sp else (11f + proximity * 17f).sp,
                    fontWeight = if (proximity > .45f) FontWeight.Medium else FontWeight.Normal, textAlign = TextAlign.Center,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp)
                )
            }
        }
        Box(Modifier.fillMaxSize())
    }
}

@Composable
private fun CurrentLyricText(text: String, color: Color, proximity: Float, time: Float, lineIndex: Int) {
    // This clock is reset by the parent whenever the focused lyric changes. It deliberately
    // does not wrap, so the character entrance happens once and then remains settled.
    val entry = (time * 1.35f).coerceIn(0f, 1f)
    val fontSize = (9f + proximity * 13f).sp
    val vertical = ((lineIndex * 37 + text.length * 11) % 5) >= 3
    val characters = @Composable {
        text.forEachIndexed { charIndex, char ->
            val seed = charIndex * 1.73f + text.length * .37f + lineIndex * .29f
            val stagger = ((sin(seed) + 1f) / 2f) * .42f
            val progress = ((entry - stagger) / .58f).coerceIn(0f, 1f)
            val eased = progress * progress * (3f - 2f * progress)
            Text(
                text = char.toString(),
                color = color.copy(alpha = .25f + eased * .75f),
                fontSize = fontSize,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.graphicsLayer {
                    translationX = cos(seed) * (1f - eased) * 34f
                    translationY = sin(seed * 1.7f) * (1f - eased) * 28f
                    scaleX = .72f + eased * .28f
                    scaleY = .72f + eased * .28f
                }
            )
        }
    }
    if (vertical) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) { characters() }
            Text(text, color = color.copy(alpha = .20f), fontSize = fontSize, fontWeight = FontWeight.Bold,
                modifier = Modifier.graphicsLayer { rotationZ = 180f; alpha = .22f })
        }
    } else Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.Center) { characters() }
        Text(
            text = text,
            color = color.copy(alpha = .22f),
            fontSize = fontSize,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.graphicsLayer {
                scaleY = -.72f
                alpha = .24f
                translationY = 3f
            }
        )
    }
    /*
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.Center) {
            text.forEachIndexed { charIndex, char ->
                val seed = charIndex * 1.73f + text.length * .37f
                val stagger = ((sin(seed) + 1f) / 2f) * .42f
                val progress = ((entry - stagger) / .58f).coerceIn(0f, 1f)
                val eased = progress * progress * (3f - 2f * progress)
                Text(
                    text = char.toString(),
                    color = color.copy(alpha = .25f + eased * .75f),
                    fontSize = fontSize,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.graphicsLayer {
                        translationX = cos(seed) * (1f - eased) * 34f
                        translationY = sin(seed * 1.7f) * (1f - eased) * 28f
                        scaleX = .72f + eased * .28f
                        scaleY = .72f + eased * .28f
                    }
                )
            }
        }
        Text(
            text = text,
            color = color.copy(alpha = .22f),
            fontSize = fontSize,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .graphicsLayer {
                    scaleY = -.72f
                    alpha = .24f
                    translationY = 3f
                }
                .fillMaxWidth()
        )
    }
    */
}

private fun pulse(time: Float): Float = (sin(time * 3.1f) + 1f) / 2f
