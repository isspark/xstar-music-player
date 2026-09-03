package life.xstar.music.player.data.online

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

class GdstudioProvider(
    private val baseUrl: String,
    override val platform: String
) : OnlineMusicProvider {

    override suspend fun search(keyword: String, limit: Int): List<OnlineTrack> = withContext(Dispatchers.IO) {
        val params = listOf("types=search", "source=$platform", "name=${encode(keyword)}", "count=$limit", "pages=1")
        val text = OnlineHttp.getText("$baseUrl$separator${params.joinToString("&")}") ?: throw IllegalStateException("无法连接在线音乐服务，请检查网络或稍后重试")
        val array = runCatching { JSONArray(text) }.getOrNull()
            ?: throw IllegalStateException("在线服务返回异常，请稍后重试")
        buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
                val name = item.optString("name").ifBlank { continue }
                add(
                    OnlineTrack(
                        id = id,
                        platform = platform,
                        title = name,
                        artist = parseArtist(item.opt("artist")),
                        album = item.optString("album").ifBlank { "未知专辑" },
                        picId = item.optString("pic_id").takeIf { it.isNotBlank() }
                    )
                )
            }
        }
    }

    override suspend fun resolveAudio(trackId: String): OnlineResolvedAudio? = withContext(Dispatchers.IO) {
        val params = listOf("types=url", "source=$platform", "id=$trackId", "br=999")
        val text = OnlineHttp.getText("$baseUrl$separator${params.joinToString("&")}") ?: return@withContext null
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return@withContext null
        val url = json.optString("url").takeIf { it.isNotBlank() && it != "null" } ?: return@withContext null
        val br = json.optInt("br", 0).coerceAtLeast(0)
        val size = json.optLong("size", 0L).coerceAtLeast(0L)
        val mime = mimeFromUrl(url)
        OnlineResolvedAudio(url, br, size, mime)
    }

    override suspend fun albumPic(trackId: String): String? = withContext(Dispatchers.IO) {
        val params = listOf("types=pic", "source=$platform", "id=$trackId", "size=300")
        val text = OnlineHttp.getText("$baseUrl$separator${params.joinToString("&")}") ?: return@withContext null
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return@withContext null
        json.optString("url").takeIf { it.isNotBlank() && it != "null" }
    }

    private val separator: String get() = if (baseUrl.contains('?')) "&" else "?"

    private fun parseArtist(value: Any?): String {
        val names = when (value) {
            is JSONArray -> buildList {
                for (i in 0 until value.length()) {
                    when (val item = value.opt(i)) {
                        is String -> if (item.isNotBlank()) add(item)
                        is JSONObject -> item.optString("name").takeIf { it.isNotBlank() }?.let(::add)
                    }
                }
            }
            is JSONObject -> listOfNotNull(value.optString("name").takeIf { it.isNotBlank() })
            is String -> if (value.isBlank()) emptyList() else listOf(value)
            else -> emptyList()
        }
        return names.distinct().joinToString(" / ").ifBlank { "未知艺术家" }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://music-api.gdstudio.xyz/api.php"

        private val mimeByExtension = mapOf(
            "flac" to "audio/flac",
            "mp3" to "audio/mpeg",
            "m4a" to "audio/mp4",
            "aac" to "audio/aac",
            "ogg" to "audio/ogg",
            "opus" to "audio/opus",
            "wav" to "audio/wav",
            "ape" to "audio/ape",
            "wma" to "audio/x-ms-wma"
        )

        private fun mimeFromUrl(url: String): String {
            val path = url.substringBefore('?')
            val extension = path.substringAfterLast('.', "").lowercase()
            return mimeByExtension[extension] ?: "audio/mpeg"
        }
    }
}
