package life.xstar.music.player.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

class AppUpdateManager(private val context: Context) {
    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }
    private val credentials = UpdateCredentialStore(context)

    private val baseUrl = NasAppResources.BASE_URL
    private val manifestUrl = "$baseUrl/App/latest.json"

    suspend fun fetchLatest(): AppUpdateInfo = withContext(Dispatchers.IO) {
        val request = authorized(manifestUrl).build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "更新信息请求失败：HTTP ${response.code}" }
            val body = response.body?.string() ?: error("更新信息为空")
            val remote = json.decodeFromString<AppUpdateInfo>(body)
            remote
        }
    }

    suspend fun check(): AppUpdateInfo? {
        val remote = fetchLatest()
        return remote.takeIf { it.versionCode > currentVersionCode() }
    }

    fun currentVersionName(): String {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION") context.packageManager.getPackageInfo(context.packageName, 0)
        }
        return info.versionName ?: "未知"
    }

    fun currentVersionCode(): Int {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION") context.packageManager.getPackageInfo(context.packageName, 0)
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            info.versionCode
        }
    }

    suspend fun download(info: AppUpdateInfo, onProgress: (Long, Long) -> Unit): File = withContext(Dispatchers.IO) {
        require(info.apkPath.startsWith("/")) { "apkPath 必须以 / 开头" }
        require(!info.apkPath.contains("..")) { "apkPath 不允许包含 .." }
        val url = baseUrl + info.apkPath
        // Keep completed APKs in filesDir so leaving the settings page or process
        // recreation does not force the user to download the same version again.
        val directory = File(context.filesDir, "updates").apply { mkdirs() }
        val apk = File(directory, "xstar-${info.versionName}.apk")
        if (isValidApk(apk, info)) {
            onProgress(apk.length(), apk.length())
            return@withContext apk
        }
        if (apk.exists()) apk.delete()
        client.newCall(authorized(url).build()).execute().use { response ->
            check(response.isSuccessful) { "APK 下载失败：HTTP ${response.code}" }
            val body = response.body ?: error("APK 内容为空")
            val total = body.contentLength().takeIf { it > 0 } ?: info.apkSize
            body.byteStream().use { input ->
                apk.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var completed = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        completed += count
                        onProgress(completed, total)
                    }
                }
            }
        }
        check(isValidApk(apk, info)) { "APK 校验失败" }
        apk
    }

    private fun isValidApk(file: File, info: AppUpdateInfo): Boolean {
        if (!file.isFile || file.length() == 0L) return false
        if (info.apkSize > 0 && file.length() != info.apkSize) return false
        return info.sha256.isBlank() || sha256(file).equals(info.sha256, ignoreCase = true)
    }

    fun install(apk: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            val settings = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(settings)
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }

    private fun authorized(url: String): Request.Builder = Request.Builder()
        .url(url)
        .header("Authorization", Credentials.basic(credentials.username(), credentials.password()))

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
