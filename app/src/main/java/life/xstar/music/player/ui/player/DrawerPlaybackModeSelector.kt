package life.xstar.music.player.ui.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
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
import androidx.compose.runtime.remember
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import life.xstar.music.player.player.PlaybackMode
import life.xstar.music.player.data.preferences.PlayerTheme

@Composable
fun DrawerPlaybackModeSelector(
    mode: PlaybackMode,
    theme: PlayerTheme,
    fmMode: Boolean,
    enabled: Boolean,
    status: String?,
    onSelect: (PlaybackMode) -> Unit
) {
    val accent = MaterialTheme.colorScheme.primary
    val themeAccent = when (theme) {
        PlayerTheme.Classic -> Color(0xFF78A9FF)
        PlayerTheme.Orbital -> Color(0xFFC4B4FF)
        PlayerTheme.Theatre -> Color(0xFFEDCF95)
        PlayerTheme.Liquid -> Color(0xFFE5BCD7)
        PlayerTheme.Tape -> Color(0xFFA77533)
    }
    val modeAccent = if (theme == PlayerTheme.Classic) accent else themeAccent
    val transition = rememberInfiniteTransition(label = "playbackModeThemeMotion")
    val pulse by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1800), RepeatMode.Reverse), label = "playbackModePulse")
    val expanded = remember { androidx.compose.runtime.mutableStateOf(false) }
    val themeName = when (theme) {
        PlayerTheme.Classic -> "CLASSIC / PLAYBACK"
        PlayerTheme.Orbital -> "ORBITAL / PLAYBACK"
        PlayerTheme.Theatre -> "THEATRE / PLAYBACK"
        PlayerTheme.Liquid -> "LIQUID / PLAYBACK"
        PlayerTheme.Tape -> "TAPE / PLAYBACK"
    }
    val modeLabel = when (mode) {
        PlaybackMode.Sequential -> "顺序播放"
        PlaybackMode.Shuffle -> "随机播放"
        PlaybackMode.RepeatOne -> "单曲循环"
    }
    val modeHint = when (mode) {
        PlaybackMode.Sequential -> "按队列依次播放"
        PlaybackMode.Shuffle -> "随机选择下一首"
        PlaybackMode.RepeatOne -> "当前歌曲循环播放"
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("播放引擎", color = Color(0xFFADB6C5), fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(7.dp))
        if (fmMode) {
            EngineCard(modeAccent, theme, "FM 自动续播", "RADIO / AUTO CONTINUE", "电台正在接管播放队列")
        } else {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                .background(Brush.linearGradient(listOf(modeAccent.copy(alpha = .11f + pulse * .025f), Color.White.copy(alpha = .025f))))
                .border(1.dp, modeAccent.copy(alpha = .22f + pulse * .08f), RoundedCornerShape(18.dp))
                .clickable { expanded.value = !expanded.value }.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ModeGlyph(mode, modeAccent, theme, pulse, Modifier.size(34.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(themeName, color = modeAccent.copy(alpha = .72f), fontSize = 9.sp, letterSpacing = 1.2.sp)
                        Text(modeLabel, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(modeHint, color = Color(0xFF9EA8B8), fontSize = 11.sp)
                    }
                    Text(if (expanded.value) "收起" else "切换", color = modeAccent, fontSize = 11.sp)
                }
            }
            if (expanded.value) Spacer(Modifier.height(7.dp))
            if (expanded.value) Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = .045f))
                    .padding(4.dp).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                PlaybackMode.entries.forEach { option ->
                    val selected = option == mode
                    val background by animateColorAsState(
                        if (selected) modeAccent.copy(alpha = if (enabled) .20f else .08f) else Color.Transparent,
                        tween(180), label = "drawerModeBackground"
                    )
                    val foreground by animateColorAsState(
                        if (!enabled) Color(0xFF727C8B) else if (selected) modeAccent else Color(0xFFADB6C5),
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

@Composable
private fun EngineCard(accent: Color, theme: PlayerTheme, title: String, eyebrow: String, hint: String) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(accent.copy(alpha = .10f))
        .border(1.dp, accent.copy(alpha = .25f), RoundedCornerShape(18.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        ModeGlyph(PlaybackMode.Sequential, accent, theme, .5f, Modifier.size(34.dp))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(eyebrow, color = accent.copy(alpha = .72f), fontSize = 9.sp, letterSpacing = 1.2.sp)
            Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(hint, color = Color(0xFF9EA8B8), fontSize = 11.sp)
        }
    }
}

@Composable
private fun ModeGlyph(mode: PlaybackMode, accent: Color, theme: PlayerTheme, pulse: Float, modifier: Modifier) {
    Canvas(modifier) {
        val center = androidx.compose.ui.geometry.Offset(size.width / 2, size.height / 2)
        val stroke = 1.8.dp.toPx()
        when (theme) {
            PlayerTheme.Orbital -> {
                rotate(pulse * 18f, center) { drawOval(accent.copy(alpha = .65f), style = Stroke(stroke)) }
                drawCircle(accent, 3.dp.toPx(), center)
            }
            PlayerTheme.Theatre -> {
                drawArc(accent.copy(alpha = .8f), 200f, 140f, false, style = Stroke(stroke, cap = StrokeCap.Round))
                drawArc(accent.copy(alpha = .45f), 340f, 140f, false, style = Stroke(stroke, cap = StrokeCap.Round))
                drawCircle(accent, 2.dp.toPx(), center)
            }
            PlayerTheme.Liquid -> {
                drawCircle(accent.copy(alpha = .24f), size.minDimension * (.38f + pulse * .04f))
                drawCircle(accent, size.minDimension * .30f, style = Stroke(stroke))
            }
            PlayerTheme.Tape -> {
                drawRoundRect(accent, style = Stroke(stroke), cornerRadius = androidx.compose.ui.geometry.CornerRadius(5.dp.toPx()))
                drawCircle(accent, 3.dp.toPx(), center)
            }
            PlayerTheme.Classic -> drawCircle(accent, size.minDimension * .30f, style = Stroke(stroke))
        }
        if (mode == PlaybackMode.Shuffle) {
            drawLine(accent, Offset(size.width * .28f, size.height * .35f), Offset(size.width * .72f, size.height * .65f), stroke)
        } else if (mode == PlaybackMode.RepeatOne) {
            drawArc(accent, -45f, 250f, false, style = Stroke(stroke, cap = StrokeCap.Round))
        } else {
            drawLine(accent, Offset(size.width * .25f, size.height * .35f), Offset(size.width * .72f, size.height * .35f), stroke)
            drawLine(accent, Offset(size.width * .25f, size.height * .55f), Offset(size.width * .72f, size.height * .55f), stroke)
        }
    }
}
