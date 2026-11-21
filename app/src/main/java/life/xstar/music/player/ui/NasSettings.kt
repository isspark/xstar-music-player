package life.xstar.music.player.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import life.xstar.music.player.MusicViewModel

@Composable
fun NasSettings(vm: MusicViewModel) {
    val saved by vm.nasConfig.collectAsStateWithLifecycle()
    val status by vm.nasStatus.collectAsStateWithLifecycle()
    var draft by remember(saved) { mutableStateOf(saved) }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    var showConnectionResult by remember { mutableStateOf(false) }
    var detailedSync by remember { mutableStateOf(false) }
    if (saved.managed) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("内置 NAS 曲库", style = MaterialTheme.typography.titleMedium)
            Text("连接信息已加密保存，内置服务地址与账户不提供查看。", style = MaterialTheme.typography.bodySmall)
            if (status.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (status.message.isNotBlank()) Text(status.message)
            Button({ vm.syncNas(saved) }, enabled = !status.busy, modifier = Modifier.fillMaxWidth()) {
                Text(if (status.busy) "正在同步…" else if (status.resumable) "继续同步" else "同步 NAS 曲库")
            }
            if (status.busy) OutlinedButton(vm::pauseNasSync) { Text("暂停同步") }
            if (saved.syncedAt > 0) Text("上次同步：${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(saved.syncedAt))}",
                style = MaterialTheme.typography.bodySmall)
        }
        return
    }
    if (showConnectionResult) {
        AlertDialog(
            onDismissRequest = { if (!status.busy) showConnectionResult = false },
            title = { Text(if (status.busy) "正在测试 NAS 连接" else "NAS 连接测试结果") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (status.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(status.message.ifBlank { "正在准备连接…" })
                    if (status.busy) Text("正在检查配置、账户及服务连接，请稍候。网络请求最长约 40 秒。", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton({ showConnectionResult = false }, enabled = !status.busy) { Text(if (status.busy) "请稍候…" else "知道了") }
            }
        )
    }
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("启用 NAS 音乐源")
            Switch(draft.enabled, { draft = draft.copy(enabled = it) }, enabled = !status.busy)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("WebDAV", "Navidrome").forEach { type ->
                FilterChip(draft.type == type, { draft = draft.copy(type = type) }, label = { Text(type) }, enabled = !status.busy)
            }
        }
        Text(if (draft.type == "WebDAV") "填写音乐目录的完整 WebDAV URL；同步后可按歌曲、歌手和专辑搜索。文件名建议为“歌手 - 歌名”。" else "填写 Navidrome 服务根地址；支持服务器搜索与曲库同步。", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(draft.address, { draft = draft.copy(address = it) }, label = { Text("服务地址") }, placeholder = { Text("https://nas.example.com/music/") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !status.busy)
        OutlinedTextField(draft.username, { draft = draft.copy(username = it) }, label = { Text("用户名") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !status.busy)
        OutlinedTextField(draft.password, { draft = draft.copy(password = it) }, label = { Text("密码") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !status.busy)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("详细同步（读取标签和封面，较慢）", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            Switch(detailedSync, { detailedSync = it }, enabled = !status.busy)
        }
        Text("歌曲分批入库后即可播放；中断后从断点继续。详细同步先建立索引，再补全标签与封面。", style = MaterialTheme.typography.bodySmall)
        if (status.busy) OutlinedButton(vm::pauseNasSync) { Text("暂停同步") }
        if (status.message.isNotBlank()) Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(status.message, style = MaterialTheme.typography.bodyMedium)
                if (status.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        Button({ keyboard?.hide(); vm.testNas(draft); showConnectionResult = true }, modifier = Modifier.fillMaxWidth(), enabled = !status.busy) { Text(if (status.busy) "正在处理…" else "测试连接") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ keyboard?.hide(); vm.saveNas(draft) }, modifier = Modifier.weight(1f), enabled = !status.busy) { Text("保存") }
            OutlinedButton({ keyboard?.hide(); vm.syncNas(draft, detailedSync) }, modifier = Modifier.weight(1f), enabled = !status.busy && draft.enabled && draft.address.isNotBlank()) { Text(if (status.resumable) "继续同步" else if (detailedSync) "详细同步" else "快速同步") }
        }
        if (saved.syncedAt > 0) Text("上次同步：${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(saved.syncedAt))}", style = MaterialTheme.typography.bodySmall)
        Text("NAS 歌曲点击直接播放；下载按钮可保存到手机。断线保留历史索引，封面和歌词默认仅缓存在手机。", style = MaterialTheme.typography.bodySmall)
    }
}
