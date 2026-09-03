package life.xstar.music.player.data.online

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import life.xstar.music.player.data.audio.AudioAnalyzer
import life.xstar.music.player.data.audio.cacheDownloadedArtwork
import life.xstar.music.player.data.audio.stableSongId
import life.xstar.music.player.data.local.SongDao
import life.xstar.music.player.data.local.SongEntity
import life.xstar.music.player.data.preferences.PlaybackPreferences
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

sealed class OnlineDownloadState {
    data class DownloadingPercent(val percent: Int?) : OnlineDownloadState()
    data object Done : OnlineDownloadState()
    data class Failed(val message: String) : OnlineDownloadState()
}

class OnlineMusicRepository(
    private val context: Context,
    private val dao: SongDao,
    private val preferences: PlaybackPreferences
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val connections = ConcurrentHashMap<String, HttpURLConnection>()
    private val _downloads = MutableStateFlow<Map<String, OnlineDownloadState>>(emptyMap())
    val downloads: StateFlow<Map<String, OnlineDownloadState>> = _downloads.asStateFlow()

    suspend fun search(keyword: String): List<OnlineTrack> = provider().search(keyword, 30)

    suspend fun findByOnlineId(onlineId: String): SongEntity? = dao.findByOnlineId(onlineId)

    suspend fun markDownloadedIfAny(tracks: List<OnlineTrack>) {
        if (tracks.isEmpty()) return
        val present = dao.findOnlineIds(tracks.map { it.onlineId })
        if (present.isNotEmpty()) _downloads.update { it + present.associateWith { OnlineDownloadState.Done } }
    }

    fun startDownload(track: OnlineTrack) {
        val key = track.onlineId
        if (jobs.containsKey(key)) return
        val job = scope.launch {
            setState(key, OnlineDownloadState.DownloadingPercent(null))
            try {
                downloadNow(track)
                setState(key, OnlineDownloadState.Done)
            } catch (e: CancellationException) {
                _downloads.update { it - key }
                throw e
            } catch (e: Exception) {
                setState(key, OnlineDownloadState.Failed(e.message ?: "下载失败"))
            } finally {
                jobs.remove(key)
            }
        }
        jobs[key] = job
    }

    fun cancel(onlineId: String) {
        jobs[onlineId]?.cancel()
        connections[onlineId]?.disconnect()
    }

    private suspend fun downloadNow(track: OnlineTrack) {
        val key = track.onlineId
        if (dao.findByOnlineId(key) != null) return
        val provider = provider()
        val resolved = provider.resolveAudio(track.id)
            ?: throw IllegalStateException("暂无可用的下载链接，该歌曲可能需要会员或受版权保护")
        val downloadFolderUri = preferences.downloadFolder.first()
            .ifBlank { throw IllegalStateException("请先在设置中选择下载目录") }
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(downloadFolderUri))
            ?: throw IllegalStateException("下载目录不可用，请重新选择")
        val name = buildString {
            append(sanitizeFileName("${track.artist} - ${track.title}").take(80))
            append("_${track.platform}_${track.id}.")
            append(extensionOf(resolved.url))
        }
        val document = runCatching { tree.createFile(resolved.mimeType, name) }.getOrNull()
            ?: throw IllegalStateException("无法在下载目录创建文件，请确认该目录已授权写入权限")
        val uri = document.uri
        try {
            val bytes = streamAudioToFile(uri, resolved, key)
            if (bytes <= 0L) throw IllegalStateException("下载内容为空")
            val audio = AudioAnalyzer.analyze(context, uri, resolved.mimeType)
            val durationMs = readDuration(uri)
            val artworkPath = runCatching { provider.albumPic(track.id) }
                .getOrNull()
                ?.let { OnlineHttp.getBytes(it) }
                ?.takeIf { it.isNotEmpty() }
                ?.let { cacheDownloadedArtwork(context, stableSongId("art_$key"), it) }
            dao.upsertAll(
                listOf(
                    SongEntity(
                        mediaStoreId = stableSongId(uri.toString()),
                        title = track.title,
                        artist = track.artist,
                        album = track.album,
                        durationMs = durationMs,
                        uri = uri.toString(),
                        mimeType = resolved.mimeType,
                        codec = audio.codec,
                        bitrate = audio.bitrate,
                        sampleRate = audio.sampleRate,
                        bitDepth = audio.bitDepth,
                        channelCount = audio.channelCount,
                        audioQuality = audio.quality.name,
                        fileName = document.name,
                        fileSize = bytes,
                        albumArtUri = artworkPath,
                        sourceFolderUri = downloadFolderUri,
                        dateAdded = System.currentTimeMillis(),
                        onlineId = key
                    )
                )
            )
        } catch (e: Exception) {
            runCatching { document.delete() }
            throw e
        }
    }

    private suspend fun streamAudioToFile(uri: Uri, resolved: OnlineResolvedAudio, key: String): Long =
        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(resolved.url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15_000
                    readTimeout = 20_000
                    setRequestProperty("User-Agent", "XSTAR/0.1.0 (online-download)")
                    setRequestProperty("Accept", "*/*")
                }
                connections[key] = connection
                if (connection.responseCode !in 200..299) throw IllegalStateException("下载地址失效，请重试")
                val expected = (connection.contentLengthLong.takeIf { it > 0 } ?: resolved.size.takeIf { it > 0 }) ?: -1L
                val output = runCatching { context.contentResolver.openOutputStream(uri, "w") }.getOrNull()
                    ?: throw IllegalStateException("无法写入下载目录，请确认该目录已授权写入权限")
                var total = 0L
                var lastPercent = -1
                val buffer = ByteArray(64 * 1024)
                output.use { out ->
                    connection.inputStream.use { input ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            total += read
                            val percent = if (expected > 0) ((total * 100) / expected).toInt().coerceIn(0, 100) else null
                            if (percent != null && percent - lastPercent >= 2) {
                                lastPercent = percent
                                setState(key, OnlineDownloadState.DownloadingPercent(percent))
                            }
                        }
                    }
                }
                if (expected > 0 && total < expected) throw IllegalStateException("下载中断，请重试")
                total
            } finally {
                connections.remove(key)
                runCatching { connection?.disconnect() }
            }
        }

    private fun readDuration(uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return runCatching {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        }.getOrDefault(0L).also { retriever.release() }
    }

    private suspend fun provider(): GdstudioProvider = GdstudioProvider(
        baseUrl = preferences.currentOnlineApiBase().ifBlank { GdstudioProvider.DEFAULT_BASE_URL },
        platform = preferences.currentOnlinePlatform()
    )

    private fun setState(key: String, state: OnlineDownloadState) {
        _downloads.update { it + (key to state) }
    }

    private fun sanitizeFileName(value: String): String =
        value.replace(Regex("[\\\\/:*?\"<>|\n\r]"), "_").trim(' ', '.')

    private fun extensionOf(url: String): String {
        val ext = url.substringBefore('?').substringAfterLast('.', "").lowercase()
        return if (ext in setOf("mp3", "m4a", "aac", "flac", "ogg", "opus", "wav", "ape", "wma")) ext else "mp3"
    }
}
