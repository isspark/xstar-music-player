package life.xstar.music.player.ui.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import life.xstar.music.player.data.preferences.FloatingButtonStyle
import kotlin.math.roundToInt

@Composable fun FloatingNavigationHandle(
    style: FloatingButtonStyle = FloatingButtonStyle.Breathing,
    playing: Boolean = false,
    onOpen: () -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val sculpted = style.isSculptedFloatingStyle
        val buttonSize = if (sculpted) 56.dp else 46.dp
        val diameter = with(density) { buttonSize.toPx() }
        val width = with(density) { maxWidth.toPx() }
        val height = with(density) { maxHeight.toPx() }
        val margin = with(density) { 16.dp.toPx() }
        var xFraction by rememberSaveable { mutableFloatStateOf(.9f) }
        var yFraction by rememberSaveable { mutableFloatStateOf(.65f) }
        var docked by rememberSaveable { mutableStateOf(false) }
        var leftEdge by rememberSaveable { mutableStateOf(false) }
        var verticalEdge by rememberSaveable { mutableIntStateOf(0) }
        var dragging by remember { mutableStateOf(false) }
        var clickEffect by remember { mutableIntStateOf(0) }
        val limitX = (width - diameter).coerceAtLeast(0f)
        val limitY = (height - diameter).coerceAtLeast(0f)
        val targetX = if (docked && verticalEdge == 0) { if (leftEdge) -diameter * .68f else width - diameter * .32f } else xFraction * limitX
        val animatedX by animateFloatAsState(targetX, tween(if (dragging) 0 else 260), label = "handleMagnet")
        val targetY = if (docked && verticalEdge != 0) { if (verticalEdge == -1) -diameter * .68f else height - diameter * .32f }
            else (yFraction * limitY).coerceIn(margin.coerceAtMost(limitY), (limitY - margin).coerceAtLeast(margin.coerceAtMost(limitY)))
        val animatedY by animateFloatAsState(targetY, tween(if (dragging) 0 else 200), label = "handleHeight")
            val transition = rememberInfiniteTransition(label = "floatingButtonMotion")
            val phase by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1900), RepeatMode.Reverse), label = "floatingButtonBreath")
            val pulse = if (playing) phase else 0f
            val scale by animateFloatAsState(if (dragging) 1.08f else 1f, tween(160), label = "floatingButtonScale")
            Box(Modifier.offset { IntOffset(animatedX.roundToInt(), animatedY.roundToInt()) }.size(buttonSize)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .then(if (docked || !sculpted) Modifier
                    .clip(if (docked) RoundedCornerShape(15.dp) else CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = (if (docked) .45f else .58f) + pulse * .08f)) else Modifier)
                .semantics { contentDescription = if (docked) "展开导航悬浮按钮" else "打开右侧导航，可拖动" }
                .pointerInput(width, height, diameter) {
                    detectDragGestures(
                    onDragStart = { dragging = true; if (docked) { if (verticalEdge == 0) xFraction = if (leftEdge) 0f else 1f; else yFraction = if (verticalEdge == -1) 0f else 1f; docked = false } },
                    onDragEnd = {
                        dragging = false
                        val x = xFraction * limitX
                        val y = yFraction * limitY
                        val horizontalDistance = minOf(x, limitX - x)
                        val verticalDistance = minOf(y, limitY - y)
                        if (minOf(horizontalDistance, verticalDistance) < diameter) {
                            if (horizontalDistance <= verticalDistance) { leftEdge = x < width / 2; verticalEdge = 0 }
                            else verticalEdge = if (y < height / 2) -1 else 1
                            docked = true
                        }
                    },
                    onDragCancel = { dragging = false },
                    onDrag = { change, delta ->
                        change.consume()
                        xFraction = if (limitX > 0) ((xFraction * limitX + delta.x) / limitX).coerceIn(0f, 1f) else 0f
                        yFraction = if (limitY > 0) ((yFraction * limitY + delta.y) / limitY).coerceIn(0f, 1f) else 0f
                    }
                    )
                }.clickable {
                    if (docked) {
                        docked = false
                        if (verticalEdge == 0) xFraction = if (leftEdge) margin / limitX.coerceAtLeast(1f) else 1f - margin / limitX.coerceAtLeast(1f)
                        else yFraction = if (verticalEdge == -1) margin / limitY.coerceAtLeast(1f) else 1f - margin / limitY.coerceAtLeast(1f)
                    } else { clickEffect++; onOpen() }
                }, contentAlignment = Alignment.Center) {
                if (!docked) {
                    if (sculpted) SculptedFloatingButton(style, playing, Modifier.fillMaxSize(), clickEffect)
                    else FloatingButtonVisual(style, playing, pulse, MaterialTheme.colorScheme.primary)
                }
                else Box(Modifier.align(when(verticalEdge) { -1 -> Alignment.BottomCenter; 1 -> Alignment.TopCenter; else -> if (leftEdge) Alignment.CenterEnd else Alignment.CenterStart })
                    .padding(5.dp).size(if (verticalEdge == 0) 2.dp else 17.dp, if (verticalEdge == 0) 17.dp else 2.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = .8f), CircleShape))
            }
    }
}

@Composable
private fun FloatingButtonVisual(style: FloatingButtonStyle, playing: Boolean, pulse: Float, accent: Color) {
    val icon = if (style == FloatingButtonStyle.Playback && playing) Icons.Default.Pause else Icons.Default.MusicNote
    Box(contentAlignment = Alignment.Center) {
        when (style) {
            FloatingButtonStyle.Breathing -> Canvas(Modifier.size(42.dp)) {
                drawCircle(accent.copy(alpha = .12f + pulse * .22f), radius = size.minDimension * (.38f + pulse * .14f), style = Stroke(2.dp.toPx()))
            }
            FloatingButtonStyle.Spectrum -> Canvas(Modifier.size(44.dp)) {
                repeat(16) { i ->
                    val angle = i * 22.5f
                    val length = 3.dp.toPx() + pulse * 5.dp.toPx() + (i % 3) * 1.5.dp.toPx()
                    val center = androidx.compose.ui.geometry.Offset(size.width / 2, size.height / 2)
                    rotate(angle, center) { drawLine(accent.copy(alpha = .65f), androidx.compose.ui.geometry.Offset(center.x, 3.dp.toPx()), androidx.compose.ui.geometry.Offset(center.x, 3.dp.toPx() + length), 1.5.dp.toPx(), androidx.compose.ui.graphics.StrokeCap.Round) }
                }
            }
            FloatingButtonStyle.Liquid -> Canvas(Modifier.size(43.dp)) {
                drawCircle(Brush.sweepGradient(listOf(accent.copy(alpha = .7f), Color.White.copy(alpha = .55f), accent.copy(alpha = .7f))), size.minDimension * (.39f + pulse * .05f), style = Stroke(2.dp.toPx()))
            }
            FloatingButtonStyle.Magnetic -> Canvas(Modifier.size(43.dp)) {
                drawRoundRect(accent.copy(alpha = .7f), style = Stroke(2.dp.toPx()), cornerRadius = androidx.compose.ui.geometry.CornerRadius(10.dp.toPx()))
            }
            else -> Unit
        }
        Icon(icon, null, tint = accent.copy(alpha = .9f), modifier = Modifier.size(20.dp))
    }
}
