package life.xstar.music.player.ui.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import life.xstar.music.player.data.preferences.PlayerShortcut
import kotlin.math.*

internal fun PlayerShortcut.icon(): ImageVector = when (this) {
    PlayerShortcut.Library -> Icons.Default.LibraryMusic
    PlayerShortcut.Favorites -> Icons.Default.Favorite
    PlayerShortcut.Playlists -> Icons.Default.QueueMusic
    PlayerShortcut.Settings -> Icons.Default.Settings
    PlayerShortcut.PlaybackMode -> Icons.Default.Repeat
    PlayerShortcut.Appearance -> Icons.Default.Palette
    PlayerShortcut.SleepTimer -> Icons.Default.Alarm
    PlayerShortcut.Equalizer -> Icons.Default.Equalizer
    PlayerShortcut.Online -> Icons.Default.Search
    PlayerShortcut.Share -> Icons.Default.Share
}

@Composable
internal fun PlayerShortcutPetals(expanded: Boolean, actions: List<PlayerShortcut>, onMenu: () -> Unit, onAction: (PlayerShortcut) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val reveal by animateFloatAsState(if (expanded) 1f else 0f, tween(260), label = "playerPetalOrbit")
    Box(Modifier.size(210.dp), contentAlignment = Alignment.Center) {
        if (reveal > .001f) Canvas(Modifier.fillMaxSize()) {
            drawCircle(accent.copy(alpha = .12f * reveal), 60.dp.toPx() * reveal, style = Stroke(.7.dp.toPx()))
            repeat(5) { i ->
                val a = (-90 - i * 72) * PI.toFloat() / 180
                val d = Offset(cos(a), sin(a))
                drawLine(accent.copy(alpha = .27f * reveal), center + d * 29.dp.toPx(), center + d * 60.dp.toPx() * reveal, .8.dp.toPx())
            }
        }
        repeat(5) { i ->
            val progress by animateFloatAsState(if (expanded) 1f else 0f, tween(300, if (expanded) i * 32 else 0), label = "playerPetal$i")
            if (progress > .001f) {
                val a = (-90 - i * 72) * PI.toFloat() / 180
                val action = actions.getOrNull(i - 1)
                Column(Modifier.offset((cos(a) * 60 * progress).dp, (sin(a) * 60 * progress).dp)
                    .graphicsLayer { alpha = progress; scaleX = .65f + .35f * progress; scaleY = scaleX; rotationZ = -16 * (1 - progress) }
                    .size(48.dp, 52.dp).clip(RoundedCornerShape(17.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF222C40), Color(0xFF0D1422))))
                    .border(1.dp, Brush.linearGradient(listOf(accent.copy(alpha = .5f), accent.copy(alpha = .06f))), RoundedCornerShape(17.dp))
                    .clickable(enabled = expanded) { if (i == 0) onMenu() else action?.let(onAction) },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(if (i == 0) Icons.Default.Menu else action?.icon() ?: Icons.Default.Settings, if (i == 0) "导航栏" else action?.title, tint = Color(0xFFC4CDDE), modifier = Modifier.size(19.dp))
                    Text(if (i == 0) "导航栏" else action?.title.orEmpty(), color = Color(0xFFC4CDDE), fontSize = 9.sp, maxLines = 1)
                }
            }
        }
    }
}
