package life.xstar.music.player.data.lyrics

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import life.xstar.music.player.data.local.SongEntity
import life.xstar.music.player.data.repository.HttpArtworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

class LyricsRepository(private val context: Context) {
    suspend fun loadLocalOrEmbedded(song: SongEntity): LyricsDocument? = withContext(Dispatchers.IO) {
        val cached = File(context.filesDir, "lyrics/${song.mediaStoreId}.lrc")
        if (cached.isFile) LrcParser.parse(cached.readText()).takeIf { it.isNotEmpty() }?.let { return@withContext LyricsDocument(it, LyricsSource.Online) }
        val lrc = findSiblingLrc(song.uri)
        if (lrc != null) return@withContext LrcParser.parse(read(lrc.uri)).takeIf { it.isNotEmpty() }?.let { LyricsDocument(it, LyricsSource.LocalLrc) }
        null
    }

    suspend fun findOnline(song: SongEntity): List<LyricsCandidate> = withContext(Dispatchers.IO) {
        val params = "artist_name=${enc(song.artist)}&track_name=${enc(song.title)}&album_name=${enc(song.album)}&duration=${song.durationMs / 1000}"
        val exact = HttpArtworkClient.getText("https://lrclib.net/api/get?$params")
        val candidates = mutableListOf<LyricsCandidate>()
        exact?.let { json -> parseCandidate(JSONObject(json), "exact")?.let(candidates::add) }
        if (candidates.isEmpty()) {
            val search = HttpArtworkClient.getText("https://lrclib.net/api/search?q=${enc("${song.artist} ${song.title}")}")
            val array = search?.let { runCatching { JSONArray(it) }.getOrNull() }
            if (array != null) for (i in 0 until array.length()) parseCandidate(array.optJSONObject(i), i.toString())?.let(candidates::add)
        }
        candidates.distinctBy { it.id }.take(12)
    }

    suspend fun save(candidate: LyricsCandidate, song: SongEntity): LyricsDocument? = withContext(Dispatchers.IO) {
        val parsed = LrcParser.parse(candidate.content)
        val lines = if (parsed.isEmpty()) listOf(LyricLine(null, candidate.content.trim())) else parsed
        if (lines.isEmpty()) return@withContext null
        val dir = File(context.filesDir, "lyrics").apply { mkdirs() }
        File(dir, "${song.mediaStoreId}.lrc").writeText(candidate.content, Charsets.UTF_8)
        LyricsDocument(lines, LyricsSource.Online)
    }

    private fun parseCandidate(json: JSONObject?, fallbackId: String): LyricsCandidate? {
        if (json == null) return null
        val synced = json.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }
        val plain = json.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" }
        if (synced == null && plain == null) return null
        return LyricsCandidate(json.optString("id", fallbackId), json.optString("trackName"), json.optString("artistName"), json.optString("albumName"), synced, plain)
    }

    private fun findSiblingLrc(uri: String): DocumentFile? {
        val file = DocumentFile.fromSingleUri(context, Uri.parse(uri)) ?: return null
        val base = file.name?.substringBeforeLast('.') ?: return null
        return file.parentFile?.listFiles()?.firstOrNull { it.isFile && it.name?.substringBeforeLast('.')?.equals(base, true) == true && it.name?.endsWith(".lrc", true) == true }
    }

    private fun read(uri: Uri): String = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
}
