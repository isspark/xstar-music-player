package life.xstar.music.player.data.font

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

data class DownloadableFont(
    val id: String,
    val name: String,
    val description: String,
    val urls: List<String>,
    val fileName: String
)

data class FontDownloadProgress(val id: String? = null, val downloaded: Long = 0, val total: Long = -1, val error: String? = null) {
    val fraction: Float get() = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else 0f
}

val downloadableFonts = listOf(
    DownloadableFont("wenkai", "霞鹜文楷", "圆润自然的中文楷体", listOf(
        "https://cdn.jsdelivr.net/gh/lxgw/LxgWWenKai@main/fonts/TTF/LXGWWenKai-Regular.ttf",
        "https://fastly.jsdelivr.net/gh/lxgw/LxgWWenKai@main/fonts/TTF/LXGWWenKai-Regular.ttf",
        "https://raw.githubusercontent.com/lxgw/LxgWWenKai/main/fonts/TTF/LXGWWenKai-Regular.ttf"
    ), "wenkai.ttf"),
    DownloadableFont("mashanzheng", "马善政毛笔", "自由舒展的毛笔书法", listOf(
        "https://cdn.jsdelivr.net/gh/google/fonts@main/ofl/mashanzheng/MaShanZheng-Regular.ttf",
        "https://fastly.jsdelivr.net/gh/google/fonts@main/ofl/mashanzheng/MaShanZheng-Regular.ttf",
        "https://raw.githubusercontent.com/google/fonts/main/ofl/mashanzheng/MaShanZheng-Regular.ttf"
    ), "mashanzheng.ttf"),
    DownloadableFont("zcoolkuaile", "站酷快乐体", "活泼醒目的标题字体", listOf(
        "https://cdn.jsdelivr.net/gh/google/fonts@main/ofl/zcoolkuaile/ZCOOLKuaiLe-Regular.ttf",
        "https://fastly.jsdelivr.net/gh/google/fonts@main/ofl/zcoolkuaile/ZCOOLKuaiLe-Regular.ttf",
        "https://raw.githubusercontent.com/google/fonts/main/ofl/zcoolkuaile/ZCOOLKuaiLe-Regular.ttf"
    ), "zcoolkuaile.ttf"),
    DownloadableFont("zcoolxiaowei", "站酷小薇体", "清新可爱的中文标题字体", listOf(
        "https://cdn.jsdelivr.net/gh/google/fonts@main/ofl/zcoolxiaowei/ZCOOLXiaoWei-Regular.ttf",
        "https://fastly.jsdelivr.net/gh/google/fonts@main/ofl/zcoolxiaowei/ZCOOLXiaoWei-Regular.ttf",
        "https://raw.githubusercontent.com/google/fonts/main/ofl/zcoolxiaowei/ZCOOLXiaoWei-Regular.ttf"
    ), "zcoolxiaowei.ttf"),
    DownloadableFont("longcang", "龙藏体", "潇洒流畅的行书风格", listOf(
        "https://cdn.jsdelivr.net/gh/google/fonts@main/ofl/longcang/LongCang-Regular.ttf",
        "https://fastly.jsdelivr.net/gh/google/fonts@main/ofl/longcang/LongCang-Regular.ttf",
        "https://raw.githubusercontent.com/google/fonts/main/ofl/longcang/LongCang-Regular.ttf"
    ), "longcang.ttf"),
    DownloadableFont("liujianmaocao", "刘建毛草", "奔放自然的草书风格", listOf(
        "https://cdn.jsdelivr.net/gh/google/fonts@main/ofl/liujianmaocao/LiuJianMaoCao-Regular.ttf",
        "https://fastly.jsdelivr.net/gh/google/fonts@main/ofl/liujianmaocao/LiuJianMaoCao-Regular.ttf",
        "https://raw.githubusercontent.com/google/fonts/main/ofl/liujianmaocao/LiuJianMaoCao-Regular.ttf"
    ), "liujianmaocao.ttf"),
    DownloadableFont("zhimangxing", "知茉行书", "轻盈灵动的手写行书", listOf(
        "https://cdn.jsdelivr.net/gh/google/fonts@main/ofl/zhimangxing/ZhiMangXing-Regular.ttf",
        "https://fastly.jsdelivr.net/gh/google/fonts@main/ofl/zhimangxing/ZhiMangXing-Regular.ttf",
        "https://raw.githubusercontent.com/google/fonts/main/ofl/zhimangxing/ZhiMangXing-Regular.ttf"
    ), "zhimangxing.ttf"),
    DownloadableFont("noto-serif-sc", "思源宋体", "端庄清晰的中文衬线字体", listOf(
        "https://cdn.jsdelivr.net/gh/notofonts/noto-cjk@main/Serif/OTF/SimplifiedChinese/NotoSerifCJKsc-Regular.otf",
        "https://fastly.jsdelivr.net/gh/notofonts/noto-cjk@main/Serif/OTF/SimplifiedChinese/NotoSerifCJKsc-Regular.otf",
        "https://raw.githubusercontent.com/notofonts/noto-cjk/main/Serif/OTF/SimplifiedChinese/NotoSerifCJKsc-Regular.otf"
    ), "noto-serif-sc.otf")
)

class FontCatalog(private val context: Context) {
    private val directory = File(context.filesDir, "fonts").apply { mkdirs() }
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version
    private val _progress = MutableStateFlow(FontDownloadProgress())
    val progress: StateFlow<FontDownloadProgress> = _progress

    fun file(font: DownloadableFont): File = File(directory, font.fileName)
    fun isDownloaded(font: DownloadableFont): Boolean = file(font).let { it.isFile && it.length() > 1024 }

    suspend fun download(font: DownloadableFont) = withContext(Dispatchers.IO) {
        val target = file(font)
        val temp = File(directory, "${font.fileName}.part")
        var lastFailure: Throwable? = null
        for (address in font.urls) {
            temp.delete()
            val connection = runCatching { (URL(address).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 60_000
                requestMethod = "GET"
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "XSTAR-Music-Player/1.0")
            } }.getOrElse { lastFailure = it; continue }
            try {
                if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
                val total = connection.contentLengthLong
                var downloaded = 0L
                _progress.value = FontDownloadProgress(font.id, 0, total)
                connection.inputStream.use { input -> temp.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        _progress.value = FontDownloadProgress(font.id, downloaded, total)
                    }
                } }
                if (temp.length() <= 1024) error("字体文件无效")
                if (!temp.renameTo(target)) error("无法保存字体文件")
                lastFailure = null
                break
            } catch (failure: Throwable) {
                lastFailure = failure
            } finally {
                connection.disconnect()
            }
        }
        temp.delete()
        lastFailure?.let { error("所有下载地址均失败：${it.message ?: it.javaClass.simpleName}") }
        _version.value++
        _progress.value = FontDownloadProgress()
    }

    fun reportError(id: String, message: String) {
        _progress.value = FontDownloadProgress(id = id, error = message)
    }

    fun clearProgress() {
        _progress.value = FontDownloadProgress()
    }

    fun delete(font: DownloadableFont) {
        file(font).delete()
        _version.value++
    }
}
