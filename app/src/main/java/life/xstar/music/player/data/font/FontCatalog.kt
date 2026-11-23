package life.xstar.music.player.data.font

import android.content.Context
import java.io.File
import android.graphics.Typeface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import life.xstar.music.player.data.preferences.AppFont
import life.xstar.music.player.update.NasAppResources
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import java.util.concurrent.TimeUnit
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

private val legacyFonts = listOf(
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

internal val knownFonts = listOf(
    DownloadableFont("xstar-sans", "经典黑体", "原内置中文黑体", emptyList(), "xstar_sans_sc.ttf"),
    DownloadableFont("xstar-serif", "经典宋体", "原内置中文宋体", emptyList(), "xstar_serif_sc.ttf"),
    DownloadableFont("xstar-round", "经典圆体", "原内置圆润字体", emptyList(), "xstar_round.ttf"),
    DownloadableFont("xstar-calligraphy", "经典书法", "原内置书法字体", emptyList(), "xstar_calligraphy.ttf")
).plus(legacyFonts).map { it.copy(urls = listOf("${NasAppResources.BASE_URL}/App/Font/${it.fileName}")) }

fun AppFont.nasFont(): DownloadableFont = knownFonts.first { it.id == when (this) {
    AppFont.Default, AppFont.Monospace -> "xstar-sans"
    AppFont.Serif -> "xstar-serif"
    AppFont.Cursive, AppFont.Rounded -> "xstar-round"
    AppFont.Lishu -> "xstar-calligraphy"
} }

class FontCatalog(private val context: Context) {
    private val resources by lazy { NasAppResources(context) }
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS).callTimeout(5, TimeUnit.MINUTES)
        .followRedirects(false).followSslRedirects(false).build()
    private val downloadMutex = Mutex()
    private val directory = File(context.filesDir, "fonts").apply { mkdirs() }
    private val catalogFile = File(directory, "catalog.json")
    private val _fonts = MutableStateFlow(readCachedFonts())
    val fonts: StateFlow<List<DownloadableFont>> = _fonts
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing
    private val _catalogError = MutableStateFlow<String?>(null)
    val catalogError: StateFlow<String?> = _catalogError
    private val refreshMutex = Mutex()
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version
    private val _progress = MutableStateFlow(FontDownloadProgress())
    val progress: StateFlow<FontDownloadProgress> = _progress

    private fun readCachedFonts(): List<DownloadableFont> = runCatching {
        val cached = if (catalogFile.isFile) JSONArray(catalogFile.readText()).let { array ->
            (0 until array.length()).map { fontFromFileName(array.getString(it)) }
        } else emptyList()
        val installed = directory.listFiles().orEmpty().filter { it.name.endsWith(".font-info") }.mapNotNull {
            runCatching { fontFromFileName(it.readText()) }.getOrNull()?.takeIf(::isDownloaded)
        }
        (cached + installed + directory.listFiles().orEmpty().filter { it.isFile && isFontFileName(it.name) }
            .filter { it.name.startsWith("nas_").not() }.map { fontFromFileName(it.name) }).distinctBy { it.id }
    }.getOrDefault(emptyList())

    suspend fun refresh() = withContext(Dispatchers.IO) {
        if (!refreshMutex.tryLock()) return@withContext
        _refreshing.value = true
        _catalogError.value = null
        try {
            val remote = mutableListOf<DownloadableFont>()
            val pending = java.util.ArrayDeque<String>().apply { add("") }
            val visited = mutableSetOf<String>()
            while (pending.isNotEmpty()) {
                currentCoroutineContext().ensureActive()
                val path = pending.removeFirst()
                if (!visited.add(path)) continue
                check(visited.size <= 500) { "字体子目录过多" }
                val encoded = path.split('/').filter { it.isNotEmpty() }.joinToString("/") { android.net.Uri.encode(it) }
                val request = resources.request("/App/Font/" + if (encoded.isEmpty()) "" else "$encoded/").header("Depth", "1")
                    .method("PROPFIND", "".toRequestBody()).build()
                val listing = client.newCall(request).execute().use { response ->
                    check(response.code == 207) { "字体目录请求失败" }
                    val body = response.body ?: error("字体目录为空")
                    val bytes = body.byteStream().use { it.readBytesLimited(2 * 1024 * 1024) }
                    parseFontListing(bytes.toString(Charsets.UTF_8), path)
                }
                remote += listing.fonts
                pending.addAll(listing.directories)
            }
            remote.sortBy { it.name.lowercase() }
            val cached = JSONArray(remote.map { it.fileName }).toString()
            val temp = File(directory, "catalog.json.part")
            temp.writeText(cached)
            check(temp.renameTo(catalogFile)) { "无法保存字体目录" }
            // Removed NAS files are no longer offered for download; keep installed fonts offline.
            _fonts.value = (remote + _fonts.value.filter(::isDownloaded)).distinctBy { it.id }
            _version.value++
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { _catalogError.value = "无法同步 NAS 字体目录，暂时显示缓存列表，请重试" }
        finally { _refreshing.value = false; refreshMutex.unlock() }
    }

    fun file(font: DownloadableFont): File = File(directory, if ('/' !in font.fileName) font.fileName else
        "nas_" + java.security.MessageDigest.getInstance("SHA-256").digest(font.fileName.toByteArray()).joinToString("") { "%02x".format(it) } + "." + font.fileName.substringAfterLast('.'))
    fun isDownloaded(font: DownloadableFont): Boolean = file(font).let { it.isFile && it.length() > 1024 }

    suspend fun download(font: DownloadableFont) = withContext(Dispatchers.IO) {
        if (!downloadMutex.tryLock()) return@withContext
        val target = file(font)
        val temp = File(directory, "${target.name}.part")
        try {
            _progress.value = FontDownloadProgress(font.id)
            require(isFontPath(font.fileName))
            val path = "/App/Font/" + font.fileName.split('/').joinToString("/") { android.net.Uri.encode(it) }
            client.newCall(resources.request(path).build()).execute().use { response ->
                check(response.isSuccessful) { "字体下载失败：HTTP ${response.code}" }
                val body = response.body ?: error("字体内容为空")
                val total = body.contentLength()
                check(total <= MAX_FONT_SIZE) { "字体文件过大" }
                var downloaded = 0L
                _progress.value = FontDownloadProgress(font.id, 0, total)
                body.byteStream().use { input -> temp.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        check(downloaded + count <= MAX_FONT_SIZE) { "字体文件过大" }
                        output.write(buffer, 0, count)
                        downloaded += count
                        _progress.value = FontDownloadProgress(font.id, downloaded, total)
                    }
                } }
                check(total < 0 || downloaded == total) { "字体下载不完整，请重试" }
                if (temp.length() <= 1024) error("字体文件无效")
                Typeface.createFromFile(temp) // Reject HTML/error pages before installing.
                if (!temp.renameTo(target)) error("无法保存字体文件")
                File(directory, "${target.name}.font-info").writeText(font.fileName)
            }
            _version.value++
            _progress.value = FontDownloadProgress()
        } catch (cancelled: CancellationException) {
            _progress.value = FontDownloadProgress()
            throw cancelled
        } finally {
            temp.delete()
            downloadMutex.unlock()
        }
    }

    fun reportError(id: String, message: String) {
        _progress.value = FontDownloadProgress(id = id, error = message)
    }

    fun clearProgress() {
        _progress.value = FontDownloadProgress()
    }

    fun delete(font: DownloadableFont) {
        file(font).delete()
        _fonts.value = readCachedFonts()
        _version.value++
    }

    companion object {
        private const val MAX_FONT_SIZE = 64L * 1024 * 1024
    }
}
