package life.xstar.music.player.ui.player

import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import life.xstar.music.player.ui.LocalXstarFontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import life.xstar.music.player.data.lyrics.LyricLine
import java.text.BreakIterator
import kotlinx.coroutines.delay

/** A live terminal session: submitted lyrics become history, while the prompt types to audio time. */
@Composable
fun TerminalLyrics(
    lines: List<LyricLine>, current: Int, position: Long, playing: Boolean,
    modifier: Modifier = Modifier, compact: Boolean = false
) {
    val index = current.coerceIn(0, (lines.size - 1).coerceAtLeast(0))
    val line = lines.getOrNull(index) ?: return
    val accent = MaterialTheme.colorScheme.primary
    val dim = accent.copy(alpha = .55f)
    val bright = lerp(accent, Color.White, .35f)
    val motion = ValueAnimator.areAnimatorsEnabled()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var elapsed by remember(lines, index) { mutableLongStateOf(0L) }
    var cursorOn by remember { mutableStateOf(true) }
    LaunchedEffect(lines, index, position, playing, motion) {
        val anchor = (position - (line.timeMs ?: position)).coerceAtLeast(0)
        elapsed = anchor
        cursorOn = true
        if (playing) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val start = android.os.SystemClock.uptimeMillis()
            while (true) {
                val delta = android.os.SystemClock.uptimeMillis() - start
                elapsed = anchor + delta
                cursorOn = !motion || android.os.SystemClock.uptimeMillis() % 1000 < 650
                delay(40)
            }
        }
    }
    val entry = remember(lines) { Animatable(1f) }
    LaunchedEffect(lines, index, motion) {
        entry.snapTo(if (motion) 0f else 1f)
        entry.animateTo(1f, tween(if (motion) 420 else 0))
    }
    val text = line.text.ifBlank { "♪" }
    val boundaries = remember(text) {
        val iterator = BreakIterator.getCharacterInstance().apply { setText(text) }
        buildList {
            add(0)
            var end = iterator.next()
            while (end != BreakIterator.DONE) { add(end); end = iterator.next() }
        }
    }
    val span = remember(lines, index) {
        val from = line.timeMs ?: 0L
        ((lines.asSequence().drop(index + 1).mapNotNull { it.timeMs }.firstOrNull { it > from }
            ?: (from + 4000)) - from).coerceAtLeast(300)
    }
    val count = if (line.timeMs == null) boundaries.lastIndex else
        ((elapsed.toFloat() / (span * .86f)).coerceIn(0f, 1f) * boundaries.lastIndex).toInt()
    val typed = "> " + text.substring(0, boundaries[count])
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier.clipToBounds().semantics {
        contentDescription = "终端心跳，${if (playing) "播放中" else "已暂停"}，${line.text}"
    }) {
        val small = compact || maxHeight < 180.dp
        val margin = if (small) 12.dp else 25.dp
        val density = androidx.compose.ui.platform.LocalDensity.current
        val inset = with(density) { margin.toPx() }
        val available = (constraints.maxWidth - inset.toInt() * 2).coerceAtLeast(1)
        val appFont = LocalXstarFontFamily.current
        val metaStyle = TextStyle(fontFamily = appFont, fontSize = if (small) 8.sp else 10.sp, color = dim)
        val historyStyle = TextStyle(fontFamily = appFont, fontSize = if (small) 11.sp else 14.sp,
            lineHeight = if (small) 17.sp else 24.sp, color = dim)
        val activeStyle = TextStyle(fontFamily = appFont, fontSize = if (small) 18.sp else 27.sp,
            lineHeight = if (small) 27.sp else 43.sp, color = bright,
            shadow = Shadow(accent.copy(alpha = .35f), Offset.Zero, 9f))
        val active = measurer.measure(typed, activeStyle, constraints = Constraints(maxWidth = available - 20.coerceAtMost(available - 1)))
        val history = remember(lines, index, historyStyle, available) {
            (maxOf(0, index - 4) until index).map { row ->
                measurer.measure("[ARCHIVE ${terminalTime(lines[row].timeMs)}] ${lines[row].text}", historyStyle,
                    constraints = Constraints(maxWidth = available), maxLines = 2)
            }
        }
        Canvas(Modifier.fillMaxSize()) {
            val headerY = if (small) 0f else inset
            val header = measurer.measure(if (small) "HEART://LIVE  /  UTF-8" else "USER@HEART : ~/memories", metaStyle)
            drawText(header, accent, Offset(inset, headerY))
            if (!small) {
                val state = measurer.measure(if (playing) "LIVE ●" else "PAUSED II", metaStyle)
                drawText(state, accent, Offset(size.width - inset - state.size.width, headerY))
                drawLine(dim.copy(alpha = .45f), Offset(inset, inset + 26.dp.toPx()),
                    Offset(size.width - inset, inset + 26.dp.toPx()), 1.dp.toPx())
            }
            val top = if (small) 18.dp.toPx() else inset + 65.dp.toPx()
            val currentY = if (small) (size.height - active.size.height - 5.dp.toPx()).coerceAtLeast(top)
                else (size.height * .52f).coerceAtMost((size.height - active.size.height - 80.dp.toPx()).coerceAtLeast(top))
            val shift = (1f - entry.value) * (if (small) 20.dp else 44.dp).toPx()
            var historyY = currentY - 16.dp.toPx()
            history.asReversed().forEachIndexed { age, layout ->
                historyY -= layout.size.height
                if (historyY >= top) drawText(layout, dim.copy(alpha = dim.alpha * (1f - age * .18f).coerceAtLeast(.3f)),
                    Offset(inset, historyY + shift))
                historyY -= 8.dp.toPx()
            }
            if (!small && historyY > top + 28.dp.toPx()) {
                drawText(measurer.measure("[SYSTEM] connection established\n[AUDIO] decoding emotional signal...", historyStyle),
                    topLeft = Offset(inset, top))
            }
            drawText(active, topLeft = Offset(inset, currentY + shift))
            if (cursorOn) {
                val cursor = active.getCursorRect(typed.length)
                drawRect(bright, Offset(inset + cursor.left + 3.dp.toPx(), currentY + shift + cursor.top + 5.dp.toPx()),
                    Size(if (small) 8.dp.toPx() else 13.dp.toPx(), (cursor.height - 10.dp.toPx()).coerceAtLeast(4.dp.toPx())))
            }
            if (!small) {
                val next = lines.getOrNull(index + 1)?.text ?: "end_of_stream"
                val meta = measurer.measure("STREAM ${(index + 1).toString().padStart(2, '0')} / UTF-8 / ${if (playing) "HEARTBEAT STABLE" else "SESSION PAUSED"}\nnext_packet: $next",
                    metaStyle.copy(lineHeight = 20.sp), constraints = Constraints(maxWidth = available), maxLines = 3)
                drawText(meta, topLeft = Offset(inset, (currentY + active.size.height + 26.dp.toPx()).coerceAtMost(size.height - meta.size.height - inset)))
            }
        }
    }
}

private fun terminalTime(time: Long?): String = time?.let {
    val seconds = it.coerceAtLeast(0) / 1000
    "%02d:%02d".format(seconds / 60, seconds % 60)
} ?: "TEXT"
