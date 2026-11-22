package life.xstar.music.player.update

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

@Composable
fun AppUpdateDialog(
    info: AppUpdateInfo,
    downloading: Boolean,
    progress: Float,
    error: String?,
    onUpdate: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!info.forceUpdate && !downloading) onDismiss() },
        title = { Text(info.title.ifBlank { "发现新版本 ${info.versionName}" }) },
        text = {
            Column {
                info.changelog.forEach { Text("• $it") }
                if (downloading) {
                    Spacer(Modifier.height(12.dp))
                    Text(if (progress > 0f) "正在下载 ${(progress * 100).toInt()}%" else "正在下载…")
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text("更新失败：$it")
                }
            }
        },
        confirmButton = {
            Button(enabled = !downloading, onClick = onUpdate) {
                Text(if (error == null) "立即更新" else "重试")
            }
        },
        dismissButton = {
            if (!info.forceUpdate && !downloading) TextButton(onClick = onDismiss) { Text("稍后再说") }
        }
    )
}
