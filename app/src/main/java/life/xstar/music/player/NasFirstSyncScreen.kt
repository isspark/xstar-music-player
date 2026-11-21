package life.xstar.music.player

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import life.xstar.music.player.data.nas.NasConfig

@Composable
internal fun NasFirstSyncScreen(vm: MusicViewModel, initializing: Boolean) {
    val status by vm.nasStatus.collectAsStateWithLifecycle()
    val scan by vm.scanState.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    var page by rememberSaveable { mutableStateOf("choice") }
    var address by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf("WebDAV") }
    var error by remember { mutableStateOf<String?>(null) }
    val busy = status.busy || scan.running
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                error = null
                vm.startLocalSetup(uri.toString())
            } catch (_: SecurityException) { error = "未获得目录访问权限，请重新选择" }
        }
    }
    BackHandler(page != "choice" && !busy) { page = "choice"; error = null }
    Column(Modifier.fillMaxSize().background(colors.background).systemBarsPadding().imePadding()
        .verticalScroll(rememberScrollState()).padding(28.dp)) {
        if (page != "choice") PageTopBar("音乐来源", { if (!busy) { page = "choice"; error = null } }, back = true, parentInset = 28)
        else Spacer(Modifier.height(24.dp))
        Text("WELCOME / XSTAR", color = colors.primary, fontSize = 10.sp, letterSpacing = 2.sp,
            modifier = Modifier.padding(top = 26.dp))
        Text(when (page) { "local" -> "听见身边的音乐"; "nas" -> "连接你的 NAS"; "developer" -> "开发者入口"; else -> "音乐，从哪里开始？" },
            color = colors.onBackground, fontSize = 28.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 16.dp, bottom = 12.dp))
        Text(when (page) {
            "local" -> "选择音乐目录，扫描完成后进入播放器。"
            "nas" -> "连接成功即可进入播放器，曲库在后台分批同步，边同步边听。"
            "developer" -> "输入开发者口令，使用内置加密连接同步曲库。"
            else -> "选择你的音乐来源，准备专属曲库。"
        }, color = colors.onSurfaceVariant, fontSize = 13.sp, lineHeight = 22.sp)
        Spacer(Modifier.height(28.dp))
        if (initializing) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            if (status.message.isNotBlank()) TextButton({ vm.retryInitialSetup() }) { Text("重试初始化") }
        } else when (page) {
            "choice" -> {
                SetupChoice("01", "本地音乐播放器", "选择手机上的音乐目录", Icons.Default.PhoneAndroid) { page = "local" }
                SetupChoice("02", "NAS", "连接自己的 WebDAV / Navidrome", Icons.Default.Storage) { page = "nas" }
                SetupChoice("03", "我是开发者本人", "口令验证后使用内置曲库", Icons.Default.Code) { page = "developer" }
            }
            "local" -> {
                Button({ picker.launch(null) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(if (scan.running) "正在扫描…" else "选择音乐目录并扫描")
                }
                if (scan.running || scan.message != null) {
                    if (scan.running) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 20.dp))
                    Text(scan.message ?: "正在扫描，已发现 ${scan.discovered} 首", color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(top = 14.dp))
                }
            }
            "nas" -> {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf("WebDAV", "Navidrome").forEach { candidate ->
                        FilterChip(type == candidate, { type = candidate }, enabled = !busy, label = { Text(candidate) })
                    }
                }
                OutlinedTextField(address, { address = it }, label = { Text("服务地址") }, singleLine = true,
                    enabled = !busy, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(username, { username = it }, label = { Text("用户名") }, singleLine = true,
                    enabled = !busy, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(password, { password = it }, label = { Text("密码") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), enabled = !busy, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(22.dp))
                Button({ vm.syncNas(NasConfig(type = type, address = address, username = username, password = password)) },
                    enabled = !busy && address.isNotBlank() && username.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                    Text(if (busy) "正在连接…" else "连接并进入播放器")
                }
            }
            "developer" -> {
                OutlinedTextField(passphrase, { passphrase = it; error = null }, label = { Text("开发者口令") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(), enabled = !busy,
                    isError = error != null, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(22.dp))
                Button({
                    if (passphrase.trim().equals("XSTAR13", ignoreCase = true)) vm.startDeveloperSetup(passphrase)
                    else error = "口令不正确，请重试"
                }, enabled = !busy && passphrase.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                    Text(if (busy) "正在同步…" else "验证并同步曲库")
                }
            }
        }
        error?.let { Text(it, color = colors.error, modifier = Modifier.padding(top = 14.dp)) }
        if (page in listOf("nas", "developer")) {
            if (status.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 24.dp))
            if (status.message.isNotBlank()) Text(status.message, color = colors.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 16.dp))
        }
    }
}

@Composable
private fun SetupChoice(number: String, title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    HorizontalDivider(color = colors.outlineVariant)
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(number, color = colors.primary, fontSize = 11.sp, modifier = Modifier.width(30.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(19.dp), tint = colors.primary)
                Text(title, color = colors.onBackground, fontSize = 18.sp, modifier = Modifier.padding(start = 10.dp))
            }
            Text(subtitle, color = colors.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
        }
        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp), tint = colors.primary)
    }
}
