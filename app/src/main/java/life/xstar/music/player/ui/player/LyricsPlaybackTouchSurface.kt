package life.xstar.music.player.ui.player

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** Topmost sibling, so visualizers cannot also seek, toggle or consume the same gesture. */
@Composable
fun LyricsPlaybackTouchSurface(
    playing: Boolean, onToggle: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    val toggle by rememberUpdatedState(onToggle)
    val previous by rememberUpdatedState(onPrevious)
    val next by rememberUpdatedState(onNext)
    Box(modifier.semantics {
        contentDescription = "歌词播放区域，点击播放或暂停，左滑下一首，右滑上一首"
        onClick(if (playing) "暂停" else "播放") { toggle(); true }
        customActions = listOf(
            CustomAccessibilityAction("上一首") { previous(); true },
            CustomAccessibilityAction("下一首") { next(); true }
        )
    }.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(pass = PointerEventPass.Initial)
            down.consume()
            val start = down.position
            var dragged = false
            var multiTouch = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.count { it.pressed } > 1) multiTouch = true
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                val delta = change.position - start
                if (delta.getDistance() > viewConfiguration.touchSlop) dragged = true
                event.changes.forEach { it.consume() }
                if (!change.pressed) {
                    if (!multiTouch) {
                        if (dragged && abs(delta.x) > 55.dp.toPx() && abs(delta.x) > abs(delta.y)) {
                            if (delta.x < 0) next() else previous()
                        } else if (!dragged) toggle()
                    }
                    break
                }
            }
        }
    })
}
