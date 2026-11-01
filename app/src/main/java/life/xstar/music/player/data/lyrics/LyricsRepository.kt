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
import kotlin.math.abs
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.CancellationException
import life.xstar.music.player.data.preferences.PlaybackPreferences
import life.xstar.music.player.data.online.GdstudioProvider

class LyricsRepository(private val context: Context) {
    suspend fun loadLocalOrEmbedded(song: SongEntity): LyricsDocument? = withContext(Dispatchers.IO) {
        val cached = File(context.filesDir, "lyrics/${song.mediaStoreId}.lrc")
        val metadata = File(context.filesDir, "lyrics/${song.mediaStoreId}.json")
        if (cached.isFile && cacheMatches(metadata, song)) {
            parse(cached.readText())?.let { return@withContext LyricsDocument(it, LyricsSource.Online) }
        }
        val lrc = findSiblingLrc(song.uri)
        if (lrc != null) parse(read(lrc.uri))?.let { return@withContext LyricsDocument(it, LyricsSource.LocalLrc) }
        readEmbedded(song)?.let { parse(it)?.let { lines -> return@withContext LyricsDocument(lines, LyricsSource.Embedded) } }
        null
    }

    suspend fun findOnline(song: SongEntity): List<LyricsCandidate> = withContext(Dispatchers.IO) {
        val base = PlaybackPreferences(context).currentOnlineApiBase().ifBlank { GdstudioProvider.DEFAULT_BASE_URL }
        supervisorScope {
            val lookups: List<suspend () -> List<LyricsCandidate>> = listOf(
                { findPublicLyrics(song) },
                { PlatformLyricsProvider(base, "netease").search(song) },
                { PlatformLyricsProvider(base, "tencent").search(song) },
                { PlatformLyricsProvider(base, "kugou").search(song) }
            )
            lookups.map { lookup -> async {
                try { lookup() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    android.util.Log.w("LyricsRepository", "歌词来源查询失败", failure)
                    emptyList()
                }
            } }.awaitAll().flatten().distinctBy { it.id }.onEach { it.score = score(song, it) }
                .sortedWith(compareByDescending<LyricsCandidate> { it.score }.thenByDescending { it.isSynced }).take(20)
        }
    }

    private suspend fun findPublicLyrics(song: SongEntity): List<LyricsCandidate> = withContext(Dispatchers.IO) {
        val candidates = mutableListOf<LyricsCandidate>()
        val exactParams = "artist_name=${enc(song.artist)}&track_name=${enc(clean(song.title))}&album_name=${enc(song.album)}&duration=${song.durationMs / 1000}"
        val exact = HttpArtworkClient.getTextRetry("https://lrclib.net/api/get?$exactParams")
        exact?.let { json -> parseCandidate(JSONObject(json), "exact")?.let(candidates::add) }
        val queries = listOf("${song.artist} ${clean(song.title)}", clean(song.title), song.title).distinct()
        for (query in queries) {
            val search = HttpArtworkClient.getTextRetry("https://lrclib.net/api/search?q=${enc(query)}")
            val array = search?.let { runCatching { JSONArray(it) }.getOrNull() }
            if (array != null) for (i in 0 until array.length()) parseCandidate(array.optJSONObject(i), i.toString())?.let(candidates::add)
        }
        if (candidates.none { it.isSynced }) {
            val fallback = HttpArtworkClient.getTextRetry(
                "https://api.lyrics.ovh/v1/${encPath(song.artist)}/${encPath(clean(song.title))}"
            )?.let { runCatching { JSONObject(it).optString("lyrics") }.getOrNull() }
            if (!fallback.isNullOrBlank()) {
                candidates += LyricsCandidate("lyrics-ovh", song.title, song.artist, song.album, null, fallback).apply { provider = "Lyrics.ovh" }
            }
        }
        candidates.distinctBy { it.id }.forEach { it.score = score(song, it) }
        candidates.sortedByDescending { it.score }.take(12)
    }

    suspend fun save(candidate: LyricsCandidate, song: SongEntity): LyricsDocument? = withContext(Dispatchers.IO) {
        val parsed = LrcParser.parse(candidate.content)
        val lines = if (parsed.isEmpty()) listOf(LyricLine(null, candidate.content.trim())) else parsed
        if (lines.isEmpty()) return@withContext null
        val dir = File(context.filesDir, "lyrics").apply { mkdirs() }
        File(dir, "${song.mediaStoreId}.lrc").writeText(candidate.content, Charsets.UTF_8)
        JSONObject().apply { put("title", song.title); put("artist", song.artist); put("durationMs", song.durationMs); put("provider", candidate.provider); put("candidateId", candidate.id) }
            .toString().also { File(dir, "${song.mediaStoreId}.json").writeText(it, Charsets.UTF_8) }
        LyricsDocument(lines, LyricsSource.Online)
    }

    suspend fun exportSibling(song: SongEntity, document: LyricsDocument): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val audio = DocumentFile.fromSingleUri(context, Uri.parse(song.uri)) ?: return@runCatching false
            val parent = audio.parentFile ?: return@runCatching false
            val name = (audio.name ?: song.title).substringBeforeLast('.') + ".lrc"
            val target = parent.findFile(name) ?: parent.createFile("text/plain", name) ?: return@runCatching false
            context.contentResolver.openOutputStream(target.uri, "wt")!!.bufferedWriter(Charsets.UTF_8).use { it.write(toLrc(document)) }
            true
        }.getOrDefault(false)
    }

    suspend fun embed(song: SongEntity, document: LyricsDocument): Result<Unit> {
        val content = toLrc(document)
        if (content.isBlank()) return Result.failure(IllegalArgumentException("当前没有可写入的歌词"))
        return life.xstar.music.player.data.audio.AudioMetadataWriter.edit(context, song,
            update = { it.setField(org.jaudiotagger.tag.FieldKey.LYRICS, content) },
            verify = { it.getFirst(org.jaudiotagger.tag.FieldKey.LYRICS) == content })
    }

    private fun parseCandidate(json: JSONObject?, fallbackId: String): LyricsCandidate? {
        if (json == null) return null
        val synced = json.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }
        val plain = json.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" }
        if (synced == null && plain == null) return null
        return LyricsCandidate("lrclib:${json.optString("id", fallbackId)}", json.optString("trackName"), json.optString("artistName"), json.optString("albumName"), synced, plain)
    }

    private fun findSiblingLrc(uri: String): DocumentFile? {
        val file = DocumentFile.fromSingleUri(context, Uri.parse(uri)) ?: return null
        val base = file.name?.substringBeforeLast('.') ?: return null
        return file.parentFile?.listFiles()?.firstOrNull { it.isFile && it.name?.substringBeforeLast('.')?.equals(base, true) == true && it.name?.endsWith(".lrc", true) == true }
    }

    private fun read(uri: Uri): String = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
    private fun encPath(value: String) = enc(value).replace("+", "%20")
    private fun parse(value: String): List<LyricLine>? = LrcParser.parse(value).takeIf { it.isNotEmpty() }
    private fun clean(value: String) = value.replace(Regex("\\s*[\\[(](official|audio|video|lyric video|remastered|高清|高音质)[\\])]", RegexOption.IGNORE_CASE), "").trim()
    private fun norm(value: String) = clean(value).lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")
    private fun similarity(a: String, b: String): Int = when {
        norm(a).isBlank() || norm(b).isBlank() -> 0
        norm(a) == norm(b) -> 100
        norm(a).contains(norm(b)) || norm(b).contains(norm(a)) -> 75
        else -> 0
    }
    private fun score(song: SongEntity, candidate: LyricsCandidate): Int {
        val title = similarity(song.title, candidate.trackName)
        val artist = similarity(song.artist, candidate.artistName)
        val duration = candidate.content.length.coerceAtLeast(1).let { if (candidate.isSynced) 20 else 8 }
        return (title * .4 + artist * .3 + duration).toInt().coerceIn(0, 100)
    }
    private fun cacheMatches(file: File, song: SongEntity): Boolean = runCatching {
        if (!file.isFile) return false
        val json = JSONObject(file.readText())
        json.optString("title") == song.title && json.optString("artist") == song.artist && abs(json.optLong("durationMs") - song.durationMs) < 10_000
    }.getOrDefault(false)
    private fun audioExtension(song: SongEntity): String = AudioTagFormat.extension(
        song.fileName ?: DocumentFile.fromSingleUri(context, Uri.parse(song.uri))?.name,
        song.mimeType
    )
    private fun readEmbedded(song: SongEntity): String? = runCatching {
        val temp = File.createTempFile("xstar-lyrics-", ".${audioExtension(song)}", context.cacheDir)
        try {
            requireNotNull(context.contentResolver.openInputStream(Uri.parse(song.uri))).use { input -> temp.outputStream().use { input.copyTo(it) } }
            org.jaudiotagger.audio.AudioFileIO.read(temp).tag?.getFirst(org.jaudiotagger.tag.FieldKey.LYRICS)?.takeIf { it.isNotBlank() }
        } finally {
            temp.delete()
        }
    }.getOrNull()
    private fun toLrc(document: LyricsDocument): String = document.lines.joinToString("\n") { line ->
        line.timeMs?.let { "[${it / 60000}:${((it % 60000) / 1000).toString().padStart(2, '0')}.${(it % 1000).toString().padStart(3, '0')}]${line.text}" } ?: line.text
    }
}
