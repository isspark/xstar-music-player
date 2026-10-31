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

    suspend fun embed(song: SongEntity, document: LyricsDocument): Result<Unit> = withContext(Dispatchers.IO) {
        var edited: File? = null
        var backup: File? = null
        var retainBackup = false
        try {
            runCatching {
                val extension = audioExtension(song)
                require(extension in setOf("mp3", "flac", "m4a", "mp4", "ogg", "opus")) {
                    "暂不支持向 .$extension 文件写入歌词，请导出 LRC"
                }
                val content = toLrc(document)
                require(content.isNotBlank()) { "当前没有可写入的歌词" }
                val uri = Uri.parse(song.uri)
                if (uri.scheme == "content" && DocumentFile.fromSingleUri(context, uri)?.canWrite() == false) {
                    throw SecurityException("音乐目录只有读取权限，请在设置中重新选择目录并授权读写")
                }
                val original = File.createTempFile("lyrics-original-", ".$extension", context.filesDir).also { backup = it }
                requireNotNull(context.contentResolver.openInputStream(uri)) { "无法读取原音频文件" }.use { input ->
                    original.outputStream().use { input.copyTo(it) }
                }
                val source = File.createTempFile("lyrics-edit-", ".$extension", context.cacheDir).also { edited = it }
                original.copyTo(source, overwrite = true)
                val audio = org.jaudiotagger.audio.AudioFileIO.read(source)
                audio.tagOrCreateAndSetDefault.setField(org.jaudiotagger.tag.FieldKey.LYRICS, content)
                audio.commit()
                check(org.jaudiotagger.audio.AudioFileIO.read(source).tag?.getFirst(org.jaudiotagger.tag.FieldKey.LYRICS) == content) {
                    "音频标签校验失败，原文件未修改"
                }
                // Open before entering recovery: permission failures must not trigger another write.
                val output = requireNotNull(context.contentResolver.openOutputStream(uri, "rwt")) { "无法打开音频文件进行写入" }
                try {
                    output.use { stream -> source.inputStream().use { it.copyTo(stream) } }
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                    fun hash(input: java.io.InputStream): ByteArray = input.use {
                        digest.reset()
                        val buffer = ByteArray(64 * 1024)
                        var count = it.read(buffer)
                        while (count >= 0) {
                            if (count > 0) digest.update(buffer, 0, count)
                            count = it.read(buffer)
                        }
                        digest.digest()
                    }
                    check(hash(source.inputStream()).contentEquals(hash(requireNotNull(context.contentResolver.openInputStream(uri))))) {
                        "音频写回校验失败"
                    }
                } catch (failure: Exception) {
                    try {
                        requireNotNull(context.contentResolver.openOutputStream(uri, "rwt")).use { stream ->
                            original.inputStream().use { it.copyTo(stream) }
                        }
                    } catch (restore: Exception) {
                        retainBackup = true
                        throw java.io.IOException("写回及恢复失败，原音频备份保存在 ${original.absolutePath}", restore)
                    }
                    throw java.io.IOException("写回失败，已恢复原音频：${failure.message}", failure)
                }
            }.onFailure { android.util.Log.e("LyricsRepository", "嵌入歌词失败", it) }
        } finally {
            edited?.delete()
            if (!retainBackup) backup?.delete()
        }
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
    private fun encPath(value: String) = enc(value).replace("+", "%20")
    private fun parse(value: String): List<LyricLine>? = LrcParser.parse(value).takeIf { it.isNotEmpty() }
    private fun clean(value: String) = value.replace(Regex("\\s*[\\[(](official|audio|video|lyric video|remastered|高清|高音质)[\\])]", RegexOption.IGNORE_CASE), "").trim()
    private fun norm(value: String) = clean(value).lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")
    private fun similarity(a: String, b: String): Int = when {
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
