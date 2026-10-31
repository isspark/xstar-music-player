package life.xstar.music.player.data.artwork

import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject

interface ArtworkProvider {
    val id: String
    val displayName: String
    suspend fun search(artist: String, album: String): List<ArtworkCandidate>
}

class ArtworkProviderChain(private val providers: List<ArtworkProvider> = defaultArtworkProviders()) {
    suspend fun search(artist: String, album: String, limit: Int = 18): List<ArtworkCandidate> = withContext(Dispatchers.IO) {
        if (buildArtworkQuery(artist, album).isBlank()) return@withContext emptyList()
        val batches = coroutineScope {
            providers.map { provider ->
                async { runCatching { provider.search(artist.trim(), album.trim()) }.getOrDefault(emptyList()) }
            }.awaitAll()
        }
        val unique = LinkedHashMap<String, ArtworkCandidate>()
        for (batch in batches) {
            for (candidate in batch) {
                if (candidate.url.isBlank() || unique.containsKey(candidate.url)) continue
                unique[candidate.url] = candidate
                if (unique.size >= limit) break
            }
            if (unique.size >= limit) break
        }
        unique.values.toList()
    }

    suspend fun first(artist: String, album: String): ArtworkCandidate? = withContext(Dispatchers.IO) {
        if (buildArtworkQuery(artist, album).isBlank()) return@withContext null
        for (provider in providers) {
            val candidate = runCatching { provider.search(artist.trim(), album.trim()) }
                .getOrDefault(emptyList())
                .firstOrNull { it.url.isNotBlank() }
            if (candidate != null) return@withContext candidate
        }
        null
    }
}

fun defaultArtworkProviders(): List<ArtworkProvider> = listOf(
    ItunesArtworkProvider(),
    NeteaseArtworkProvider(),
    QqMusicArtworkProvider(),
    BingArtworkProvider()
)

class ItunesArtworkProvider : ArtworkProvider {
    override val id = "itunes"
    override val displayName = "iTunes"

    override suspend fun search(artist: String, album: String): List<ArtworkCandidate> {
        val term = encodeQuery(buildArtworkQuery(artist, album))
        val json = ArtworkHttp.getText("https://itunes.apple.com/search?term=$term&entity=album&limit=10&country=cn")
            ?: ArtworkHttp.getText("https://itunes.apple.com/search?term=$term&entity=album&limit=10&country=us")
            ?: return emptyList()
        val results = runCatching { JSONObject(json).optJSONArray("results") }.getOrNull() ?: return emptyList()
        val candidates = mutableListOf<ArtworkCandidate>()
        for (i in 0 until results.length()) {
            val result = results.optJSONObject(i) ?: continue
            val image = result.optString("artworkUrl100").replace("100x100", "600x600")
            if (!image.startsWith("http")) continue
            val title = result.optString("collectionName").ifBlank { album }
            val name = result.optString("artistName").ifBlank { artist }
            candidates += ArtworkCandidate(image, "$name · $title", displayName)
        }
        return candidates
    }
}

class NeteaseArtworkProvider : ArtworkProvider {
    override val id = "netease"
    override val displayName = "网易云音乐"

    private val headers = mapOf(
        "Referer" to "https://music.163.com/",
        "Cookie" to "os=android; appver=2.0.2; deviceId=xstar",
        "Accept" to "application/json, text/plain, */*"
    )

    override suspend fun search(artist: String, album: String): List<ArtworkCandidate> {
        val term = encodeQuery(buildArtworkQuery(artist, album))
        searchAlbums(term, artist, album).takeIf { it.isNotEmpty() }?.let { return it }
        return searchSongs(term, artist, album)
    }

    private fun searchAlbums(term: String, artist: String, album: String): List<ArtworkCandidate> {
        val text = ArtworkHttp.getText("https://music.163.com/api/search/get/web?csrf_token=&s=$term&type=10&offset=0&total=true&limit=10", headers)
            ?: return emptyList()
        val albums = runCatching { JSONObject(text).optJSONObject("result")?.optJSONArray("albums") }.getOrNull() ?: return emptyList()
        val candidates = mutableListOf<ArtworkCandidate>()
        for (i in 0 until albums.length()) {
            val item = albums.optJSONObject(i) ?: continue
            val url = normalizeNeteaseCover(item.optString("picUrl")) ?: continue
            val title = item.optString("name").ifBlank { album }
            val name = artistNames(item, artist)
            candidates += ArtworkCandidate(url, "$name · $title", displayName)
        }
        return candidates
    }

    private fun searchSongs(term: String, artist: String, album: String): List<ArtworkCandidate> {
        val text = ArtworkHttp.getText("https://music.163.com/api/search/get/web?csrf_token=&s=$term&type=1&offset=0&total=true&limit=10", headers)
            ?: return emptyList()
        val songs = runCatching { JSONObject(text).optJSONObject("result")?.optJSONArray("songs") }.getOrNull() ?: return emptyList()
        val candidates = mutableListOf<ArtworkCandidate>()
        for (i in 0 until songs.length()) {
            val item = songs.optJSONObject(i) ?: continue
            val info = item.optJSONObject("album") ?: continue
            val url = normalizeNeteaseCover(info.optString("picUrl")) ?: continue
            val title = info.optString("name").ifBlank { album }
            val name = artistNames(item, artist)
            candidates += ArtworkCandidate(url, "$name · $title", displayName)
        }
        return candidates
    }

    private fun artistNames(item: JSONObject, fallback: String): String {
        val artists = item.optJSONArray("artists") ?: return fallback
        val names = (0 until artists.length()).mapNotNull { artists.optJSONObject(it)?.optString("name") }.filter { it.isNotBlank() }
        return names.joinToString("、").ifBlank { fallback }
    }
}

class QqMusicArtworkProvider : ArtworkProvider {
    override val id = "qq"
    override val displayName = "QQ音乐"

    private val headers = mapOf(
        "Referer" to "https://y.qq.com/",
        "Accept" to "application/json, text/plain, */*"
    )

    override suspend fun search(artist: String, album: String): List<ArtworkCandidate> {
        val term = encodeQuery(buildArtworkQuery(artist, album))
        searchAlbums(term, artist, album).takeIf { it.isNotEmpty() }?.let { return it }
        return searchSongs(term, artist, album)
    }

    private fun searchAlbums(term: String, artist: String, album: String): List<ArtworkCandidate> {
        val text = ArtworkHttp.getText(
            "https://c.y.qq.com/splcloud/fcgi-bin/smartbox_new.fcg?key=$term&format=json&g_tk=5381&inCharset=utf-8&outCharset=utf-8",
            headers
        ) ?: return emptyList()
        val items = runCatching { JSONObject(text).optJSONObject("data")?.optJSONObject("album")?.optJSONArray("itemlist") }.getOrNull()
            ?: return emptyList()
        val candidates = mutableListOf<ArtworkCandidate>()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val mid = item.optString("mid")
            if (mid.isBlank() || mid == "0") continue
            val title = item.optString("name").ifBlank { album }
            val name = item.optString("singer").ifBlank { artist }
            candidates += ArtworkCandidate(qqCover(mid), "$name · $title", displayName)
        }
        return candidates
    }

    private fun searchSongs(term: String, artist: String, album: String): List<ArtworkCandidate> {
        val text = ArtworkHttp.getText(
            "https://c.y.qq.com/soso/fcgi-bin/search_for_qq_cp?w=$term&format=json&p=1&n=10&new_json=1",
            headers
        ) ?: return emptyList()
        val songs = runCatching { JSONObject(text).optJSONObject("data")?.optJSONObject("song")?.optJSONArray("list") }.getOrNull()
            ?: return emptyList()
        val candidates = mutableListOf<ArtworkCandidate>()
        for (i in 0 until songs.length()) {
            val item = songs.optJSONObject(i) ?: continue
            val mid = item.optString("albummid")
            if (mid.isBlank() || mid == "0") continue
            val title = item.optString("albumname").ifBlank { album }
            val names = item.optJSONArray("singer")?.let { singers ->
                (0 until singers.length()).mapNotNull { singers.optJSONObject(it)?.optString("name") }.filter { it.isNotBlank() }
            }.orEmpty()
            val name = names.joinToString("、").ifBlank { artist }
            candidates += ArtworkCandidate(qqCover(mid), "$name · $title", displayName)
        }
        return candidates
    }

    private fun qqCover(mid: String) = "https://y.gtimg.cn/music/photo_new/T002R500x500M000$mid.jpg"
}

class BingArtworkProvider : ArtworkProvider {
    override val id = "bing"
    override val displayName = "Bing 图片"

    override suspend fun search(artist: String, album: String): List<ArtworkCandidate> {
        val term = encodeQuery("${buildArtworkQuery(artist, album)} 专辑封面")
        val html = ArtworkHttp.getText(
            "https://cn.bing.com/images/search?q=$term&form=HDRSC2&first=1",
            mapOf("Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8")
        ) ?: return emptyList()
        return parseBingImageUrls(html).map { ArtworkCandidate(it, "$artist · $album", displayName) }
    }
}

internal fun buildArtworkQuery(artist: String, album: String): String {
    val placeholders = setOf("未知艺术家", "未知专辑", "<unknown>", "unknown")
    return listOf(artist.trim(), album.trim())
        .filter { it.isNotBlank() && it.lowercase() !in placeholders }
        .distinct()
        .joinToString(" ")
}

internal fun normalizeNeteaseCover(url: String?): String? {
    val raw = url?.trim().orEmpty()
    if (!raw.startsWith("http")) return null
    val base = raw.substringBefore('?').replaceFirst("http://", "https://")
    return "$base?param=600y600"
}

internal fun parseBingImageUrls(html: String): List<String> {
    val regex = Regex("""\sm="([^"]+)"""")
    val urls = LinkedHashSet<String>()
    for (match in regex.findAll(html)) {
        val raw = unescapeHtml(match.groupValues[1])
        val url = runCatching { JSONObject(raw).optString("murl") }.getOrNull().orEmpty()
        if (!url.startsWith("http") || url.endsWith(".svg")) continue
        urls += url
        if (urls.size >= 8) break
    }
    return urls.toList()
}

private fun unescapeHtml(value: String): String = value
    .replace("&quot;", "\"")
    .replace("&#34;", "\"")
    .replace("&#39;", "'")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&amp;", "&")

private fun encodeQuery(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
