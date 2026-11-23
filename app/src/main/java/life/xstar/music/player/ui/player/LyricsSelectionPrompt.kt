package life.xstar.music.player.ui.player

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import life.xstar.music.player.data.lyrics.LyricsState

@Composable
internal fun LyricsSelectionPrompt(state: LyricsState, onSelect: () -> Unit, modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary) {
    val loading = state == LyricsState.Loading
    val candidates = state is LyricsState.Candidates
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(when {
            loading -> "正在查找歌词…"
            candidates -> "找到多个歌词版本，请选择"
            state is LyricsState.Error -> "歌词获取失败，可点击重新查找"
            else -> "暂无歌词，可手动查找并选择"
        }, color = color.copy(alpha = .8f), fontSize = 12.sp)
        OutlinedButton(onClick = onSelect, enabled = !loading,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = color)) {
            if (loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = color)
            else Icon(Icons.Default.Search, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (loading) "查找中" else if (candidates) "点击选择歌词" else "点击查找 / 选择歌词")
            if (!loading) Icon(Icons.Default.ChevronRight, null, Modifier.size(18.dp))
        }
    }
}
