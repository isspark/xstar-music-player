package life.xstar.music.player.ui.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable fun FloatingNavigationHandle(onOpen: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val diameter = with(density) { 46.dp.toPx() }
        val width = with(density) { maxWidth.toPx() }
        val height = with(density) { maxHeight.toPx() }
        val margin = with(density) { 16.dp.toPx() }
        var xFraction by rememberSaveable { mutableFloatStateOf(.9f) }
        var yFraction by rememberSaveable { mutableFloatStateOf(.65f) }
        var docked by rememberSaveable { mutableStateOf(false) }
        var leftEdge by rememberSaveable { mutableStateOf(false) }
        var verticalEdge by rememberSaveable { mutableIntStateOf(0) }
        var dragging by remember { mutableStateOf(false) }
        val limitX = (width - diameter).coerceAtLeast(0f)
        val limitY = (height - diameter).coerceAtLeast(0f)
        val targetX = if (docked && verticalEdge == 0) { if (leftEdge) -diameter * .68f else width - diameter * .32f } else xFraction * limitX
        val animatedX by animateFloatAsState(targetX, tween(if (dragging) 0 else 260), label = "handleMagnet")
        val targetY = if (docked && verticalEdge != 0) { if (verticalEdge == -1) -diameter * .68f else height - diameter * .32f }
            else (yFraction * limitY).coerceIn(margin.coerceAtMost(limitY), (limitY - margin).coerceAtLeast(margin.coerceAtMost(limitY)))
        val animatedY by animateFloatAsState(targetY, tween(if (dragging) 0 else 200), label = "handleHeight")
        Box(Modifier.offset { IntOffset(animatedX.roundToInt(), animatedY.roundToInt()) }.size(46.dp)
            .clip(if (docked) RoundedCornerShape(15.dp) else CircleShape)
            .background(Color(0xFF474353).copy(alpha = if (docked) .55f else .7f))
            .semantics { contentDescription = if (docked) "展开导航悬浮按钮" else "打开右侧导航，可拖动" }
            .pointerInput(width, height) {
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
                }
                else onOpen()
            }, contentAlignment = Alignment.Center) {
            if (!docked) Icon(Icons.Default.Menu, null, tint = Color.White.copy(alpha = .85f), modifier = Modifier.size(20.dp))
            else Box(Modifier.align(when(verticalEdge) { -1 -> Alignment.BottomCenter; 1 -> Alignment.TopCenter; else -> if (leftEdge) Alignment.CenterEnd else Alignment.CenterStart })
                .padding(5.dp).size(if (verticalEdge == 0) 2.dp else 17.dp, if (verticalEdge == 0) 17.dp else 2.dp).background(Color.White.copy(alpha = .7f), CircleShape))
        }
    }
}
