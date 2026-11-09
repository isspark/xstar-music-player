package life.xstar.music.player.ui.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import life.xstar.music.player.player.PlaybackMode

@Composable
fun DrawerPlaybackModeSelector(
    mode: PlaybackMode,
    fmMode: Boolean,
    enabled: Boolean,
    status: String?,
    onSelect: (PlaybackMode) -> Unit
) {
    val accent = MaterialTheme.colorScheme.primary
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("播放模式", color = Color(0xFFADB6C5), fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        if (fmMode) {
            Text(
                "FM 自动续播", color = accent, fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .background(accent.copy(alpha = .10f)).padding(16.dp)
            )
        } else {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = .045f)).padding(4.dp).selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                PlaybackMode.entries.forEach { option ->
                    val selected = option == mode
                    val background by animateColorAsState(
                        if (selected) accent.copy(alpha = if (enabled) .18f else .08f) else Color.Transparent,
                        tween(180), label = "drawerModeBackground"
                    )
                    val foreground by animateColorAsState(
                        if (!enabled) Color(0xFF727C8B) else if (selected) accent else Color(0xFFADB6C5),
                        tween(180), label = "drawerModeForeground"
                    )
                    val label = when (option) {
                        PlaybackMode.Sequential -> "顺序"
                        PlaybackMode.Shuffle -> "随机"
                        PlaybackMode.RepeatOne -> "单曲"
                    }
                    Row(
                        Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                            .background(background)
                            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton,
                                onClick = { if (!selected) onSelect(option) })
                            .semantics {
                                contentDescription = when (option) {
                                    PlaybackMode.Sequential -> "顺序播放"
                                    PlaybackMode.Shuffle -> "随机播放"
                                    PlaybackMode.RepeatOne -> "单曲循环"
                                }
                            }.padding(horizontal = 4.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            when (option) {
                                PlaybackMode.Sequential -> Icons.Default.FormatListNumbered
                                PlaybackMode.Shuffle -> Icons.Default.Shuffle
                                PlaybackMode.RepeatOne -> Icons.Default.RepeatOne
                            }, null, tint = foreground, modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(label, color = foreground, fontSize = 13.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
            }
            if (status != null) {
                Text(status, color = Color(0xFF8C97A8), fontSize = 11.sp,
                    modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}
