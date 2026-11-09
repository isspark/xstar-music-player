package life.xstar.music.player.ui.player

import android.animation.ValueAnimator
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import life.xstar.music.player.data.preferences.FloatingButtonStyle
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

val FloatingButtonStyle.isSculptedFloatingStyle: Boolean
    get() = this == FloatingButtonStyle.IceSnowflake || this == FloatingButtonStyle.StardustWreath ||
        this == FloatingButtonStyle.MiniWormhole || this == FloatingButtonStyle.VinylKnob

/** The same renderer is used by the settings preview and the draggable navigation handle. */
@Composable
fun SculptedFloatingButton(
    style: FloatingButtonStyle,
    playing: Boolean,
    modifier: Modifier = Modifier,
    clickEffect: Int = 0
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val enabled = ValueAnimator.areAnimatorsEnabled()
    val time = remember(style) { mutableFloatStateOf(0f) }
    val burst = remember(style) { mutableFloatStateOf(0f) }
    LaunchedEffect(style, playing, enabled, lifecycle) {
        if (playing && enabled) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var previous = SystemClock.uptimeMillis()
            while (isActive) {
                val now = SystemClock.uptimeMillis()
                time.floatValue += (now - previous).coerceAtMost(100) / 1000f
                previous = now
                delay(33)
            }
        }
    }
    LaunchedEffect(clickEffect, style, enabled) {
        if (clickEffect > 0 && enabled) {
            val start = SystemClock.uptimeMillis()
            do {
                val fraction = ((SystemClock.uptimeMillis() - start) / 720f).coerceIn(0f, 1f)
                burst.floatValue = sin(fraction * PI).toFloat()
                delay(16)
            } while (fraction < 1f)
            burst.floatValue = 0f
        }
    }
    Canvas(modifier) {
        val unit = size.minDimension / 56f
        val t = time.floatValue
        val pop = burst.floatValue
        when (style) {
            FloatingButtonStyle.IceSnowflake -> {
                val ice = Color(0xFFB7F3FF)
                val violet = Color(0xFFC8B7FF)
                drawCircle(Brush.radialGradient(listOf(ice.copy(alpha = .12f), Color.Transparent)), 26 * unit)
                rotate(t * 7f) {
                    repeat(6) { arm -> rotate(arm * 60f) {
                        val tip = 22 * unit * (1 + pop * .13f)
                        val start = center + Offset(0f, -3 * unit)
                        val end = center + Offset(0f, -tip)
                        drawLine(ice.copy(alpha = .16f), start, end, 4 * unit, StrokeCap.Round)
                        drawLine(ice, start, end, 1.35f * unit, StrokeCap.Round)
                        listOf(10f, 16f).forEach { y ->
                            val origin = center + Offset(0f, -y * unit)
                            for (side in listOf(-1, 1)) {
                                drawLine(violet, origin, origin + Offset(side * (5 + pop * 2) * unit, -5 * unit), unit, StrokeCap.Round)
                            }
                        }
                        drawCircle(Color.White.copy(alpha = .9f), 1.25f * unit, end)
                    } }
                }
                drawCircle(ice, 2.4f * unit)
            }
            FloatingButtonStyle.StardustWreath -> {
                val colors = listOf(Color(0xFFFFE5A0), Color(0xFFFF9CCE), Color(0xFF94EFFF))
                repeat(32) { i ->
                    val angle = i * PI * 2 / 32 + t * (.22 + (i % 3) * .045)
                    val radius = (20 + sin(t * .7 + i) * 1.7 + pop * (3 + i % 5)) * unit
                    val point = center + Offset(cos(angle).toFloat(), sin(angle).toFloat()) * radius.toFloat()
                    val alpha = (.42 + .58 * (.5 + .5 * sin(t * 1.6 + i))).toFloat()
                    val color = colors[i % colors.size]
                    drawCircle(color.copy(alpha = alpha * .09f), (3.3f + i % 3 * .3f) * unit, point)
                    drawCircle(color.copy(alpha = alpha), (.65f + i % 4 * .17f) * unit, point)
                    if (i % 8 == 0) {
                        drawLine(color.copy(alpha = alpha), point - Offset(2.3f * unit, 0f), point + Offset(2.3f * unit, 0f), .6f * unit)
                        drawLine(color.copy(alpha = alpha), point - Offset(0f, 2.3f * unit), point + Offset(0f, 2.3f * unit), .6f * unit)
                    }
                }
                navigationGlyph(Color(0xFFEEE2FF), unit)
            }
            FloatingButtonStyle.MiniWormhole -> {
                val purple = Color(0xFFAB8AFF)
                val cyan = Color(0xFF63E4E9)
                drawCircle(Brush.radialGradient(listOf(purple.copy(alpha = .2f), Color.Transparent)), 27 * unit)
                repeat(3) { layer ->
                    val radius = (16 + layer * 3 + sin(t * 2 + layer) * 1.6f + pop * 3) * unit
                    val spin = t * (if (layer % 2 == 0) 55 else -42) + layer * 47
                    val topLeft = center - Offset(radius, radius)
                    val bounds = androidx.compose.ui.geometry.Size(radius * 2, radius * 2)
                    drawArc(purple.copy(alpha = .08f), spin, 300f, false, topLeft, bounds, style = Stroke(5 * unit))
                    drawArc(purple.copy(alpha = .9f - layer * .14f), spin, 114f, false, topLeft, bounds, style = Stroke((2.6f - layer * .5f) * unit, cap = StrokeCap.Round))
                    drawArc(cyan.copy(alpha = .85f - layer * .15f), spin + 180f, 95f, false, topLeft, bounds, style = Stroke(1.8f * unit, cap = StrokeCap.Round))
                }
                drawCircle(Color(0xFF080B13), (11 - pop * 3) * unit)
                navigationGlyph(Color(0xFFE5D9FF), unit * (1 - pop * .2f))
            }
            FloatingButtonStyle.VinylKnob -> {
                val radius = 23 * unit
                drawCircle(Color.Black.copy(alpha = .15f), radius + 2 * unit, center + Offset(0f, 2 * unit))
                drawCircle(Color(0xFF202029), radius)
                repeat(9) { groove ->
                    drawCircle(if (groove % 2 == 0) Color(0xFF413A49) else Color(0xFF2A2632), (10 + groove * 1.45f) * unit, style = Stroke(.65f * unit))
                }
                drawCircle(Color(0xFF65596F), radius, style = Stroke(.65f * unit))
                rotate(t * 30f) {
                    drawArc(Color.White.copy(alpha = .10f), -65f, 36f, false, center - Offset(radius, radius), androidx.compose.ui.geometry.Size(radius * 2, radius * 2), style = Stroke(6 * unit))
                    drawCircle(Color(0xFFE8B09D), (8.5f + pop) * unit)
                    navigationGlyph(Color(0xFF593D45), unit * .63f)
                }
                drawCircle(Color(0xFF29232C), 1.1f * unit)
            }
            else -> Unit
        }
    }
}

private fun DrawScope.navigationGlyph(color: Color, unit: Float) {
    listOf(-4f, 0f, 4f).forEachIndexed { i, y ->
        drawLine(color, center + Offset(-5 * unit, y * unit), center + Offset((if (i == 2) 1 else 5) * unit, y * unit), 1.25f * unit, StrokeCap.Round)
    }
}
