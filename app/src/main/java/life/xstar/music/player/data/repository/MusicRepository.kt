package life.xstar.music.player.data.repository

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.net.URLEncoder
import org.json.JSONObject
import life.xstar.music.player.data.local.*
import life.xstar.music.player.data.audio.AudioAnalyzer
import life.xstar.music.player.data.audio.stableSongId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class MusicRepository(private val context: Context, private val dao: SongDao, private val playlistDao: PlaylistDao, private val singingDao: SingingDao) {
    fun songs(): Flow<List<SongEntity>> = dao.observeSongs()
    fun search(query: String): Flow<List<SongEntity>> = dao.search(query)
    fun favorites(): Flow<List<SongEntity>> = dao.observeFavorites()
    fun playlists(): Flow<List<PlaylistEntity>> = playlistDao.observePlaylists()
    fun playlistSongs(id: Long): Flow<List<PlaylistSongEntity>> = playlistDao.observeSongs(id)
    fun singingRecords(): Flow<List<SingingRecordEntity>> = singingDao.observeAll()
    suspend fun toggleFavorite(id: Long) = dao.toggleFavorite(id)
    suspend fun createPlaylist(name: String) = playlistDao.insert(PlaylistEntity(name = name))
    suspend fun deletePlaylist(playlist: PlaylistEntity) = playlistDao.delete(playlist)
    suspend fun addToPlaylist(playlistId: Long, songId: Long) = playlistDao.addSong(PlaylistSongEntity(playlistId, songId, playlistDao.nextPosition(playlistId)))
    suspend fun removeFromPlaylist(playlistId: Long, songId: Long) = playlistDao.remove(playlistId, songId)
    suspend fun renamePlaylist(id: Long, name: String) = playlistDao.rename(id, name.trim())
    suspend fun saveSingingRecord(record: SingingRecordEntity): Long = singingDao.insert(record)
    suspend fun updateSingingRecordScore(id: Long, total: Int, pitch: Int?, activity: Int, lightweight: Boolean) = singingDao.updateScore(id, total, pitch, activity, lightweight)
    suspend fun deleteSingingRecord(record: SingingRecordEntity) = singingDao.delete(record)
    suspend fun enrichMissingArtwork() = Unit

    suspend fun getSong(id: Long): SongEntity? = dao.findById(id)

    suspend fun findArtworkCandidates(artist: String, album: String): List<ArtworkCandidate> = withContext(Dispatchers.IO) {
        if (artist == "未知艺术家" || album == "未知专辑") return@withContext emptyList()
        val term = URLEncoder.encode("${artist.trim()} ${album.trim()}", "UTF-8")
        val json = HttpArtworkClient.getText("https://itunes.apple.com/search?term=$term&entity=album&limit=10&country=cn")
            ?: HttpArtworkClient.getText("https://itunes.apple.com/search?term=$term&entity=album&limit=10&country=us")
            ?: return@withContext emptyList()
        val results = JSONObject(json).optJSONArray("results") ?: return@withContext emptyList()
        val candidates = mutableListOf<ArtworkCandidate>()
        for (i in 0 until results.length()) {
            val result = results.optJSONObject(i) ?: continue
            val image = result.optString("artworkUrl100").replace("100x100", "600x600")
            if (image.isNotBlank() && candidates.none { it.url == image }) {
                val title = result.optString("collectionName", album)
                val resultArtist = result.optString("artistName", artist)
                val label = "$resultArtist · $title"
                candidates += ArtworkCandidate(image, label)
            }
        }
        candidates
    }

    suspend fun saveArtworkForAlbum(artist: String, album: String, candidate: ArtworkCandidate) = withContext(Dispatchers.IO) {
        val bytes = getBytes(candidate.url) ?: return@withContext false
        val artwork = cacheDownloadedArtwork(artist, album, bytes) ?: return@withContext false
        dao.updateArtworkForAlbum(artist, album, artwork)
        true
    }

    suspend fun scan(folderUris: Set<String>, onProgress: (ScanProgress) -> Unit = {}): Int = withContext(Dispatchers.IO) {
        if (folderUris.isEmpty()) {
            dao.deleteAll()
            return@withContext 0
        }
        val scanned = mutableListOf<SongEntity>()
        folderUris.forEach { folderUri ->
            DocumentFile.fromTreeUri(context, Uri.parse(folderUri))?.let { scanDirectory(it, folderUri, scanned, onProgress) }
        }
        val ids = scanned.map { it.mediaStoreId }
        val existing = if (ids.isEmpty()) emptyMap() else dao.findByMediaIds(ids).associateBy { it.mediaStoreId }
        val merged = scanned.map { song -> existing[song.mediaStoreId]?.let { old ->
            song.copy(
                albumArtUri = song.albumArtUri ?: old.albumArtUri?.takeUnless { it.endsWith(".img") },
                isFavorite = old.isFavorite,
                playCount = old.playCount,
                lastPlayedAt = old.lastPlayedAt,
                onlineId = old.onlineId
            )
        } ?: song }
        if (ids.isEmpty()) dao.deleteAll() else dao.deleteNotInMediaIds(ids)
        if (merged.isNotEmpty()) dao.upsertAll(merged)
        merged.size
    }

    private suspend fun scanDirectory(directory: DocumentFile, folderUri: String, output: MutableList<SongEntity>, onProgress: (ScanProgress) -> Unit) {
        directory.listFiles().forEach { file ->
            currentCoroutineContext().ensureActive()
            when {
                file.isDirectory -> scanDirectory(file, folderUri, output, onProgress)
                file.isFile && isAudio(file) -> {
                    onProgress(ScanProgress(file.name.orEmpty(), output.size))
                    readSong(file, folderUri)?.let(output::add)
                    onProgress(ScanProgress(file.name.orEmpty(), output.size))
                }
            }
        }
    }

    private fun isAudio(file: DocumentFile): Boolean {
        if (file.type?.startsWith("audio/") == true) return true
        return file.name?.substringAfterLast('.', "")?.lowercase() in setOf("mp3", "m4a", "aac", "flac", "ogg", "opus", "wav", "wma")
    }

    private fun readSong(file: DocumentFile, folderUri: String): SongEntity? {
        val retriever = MediaMetadataRetriever()
        return runCatching {
            retriever.setDataSource(context, file.uri)
            val fileName = file.name.orEmpty()
            val id = stableId(file.uri.toString())
            val mimeType = file.type ?: context.contentResolver.getType(file.uri)
            val audioInfo = AudioAnalyzer.analyze(context, file.uri, mimeType)
            val embeddedArtwork = retriever.embeddedPicture ?: readEmbeddedFromCopy(file.uri)
            SongEntity(
                mediaStoreId = id,
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE).orDefault(fileName.substringBeforeLast('.')),
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orDefault("未知艺术家"),
                album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).orDefault("未知专辑"),
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0,
                uri = file.uri.toString(),
                mimeType = mimeType,
                codec = audioInfo.codec,
                bitrate = audioInfo.bitrate,
                sampleRate = audioInfo.sampleRate,
                bitDepth = audioInfo.bitDepth,
                channelCount = audioInfo.channelCount,
                audioQuality = audioInfo.quality.name,
                fileName = fileName,
                fileSize = file.length(),
                albumArtUri = cacheEmbeddedArtwork(id, embeddedArtwork),
                sourceFolderUri = folderUri,
                dateAdded = file.lastModified()
            )
        }.getOrNull().also { retriever.release() }
    }

    private fun readEmbeddedFromCopy(uri: Uri): ByteArray? = runCatching {
        val temporary = File.createTempFile("xstar_audio_", ".media", context.cacheDir)
        context.contentResolver.openInputStream(uri)?.use { input -> temporary.outputStream().use { output -> input.copyTo(output) } }
        val retriever = MediaMetadataRetriever()
        val artwork = runCatching { retriever.setDataSource(temporary.absolutePath); retriever.embeddedPicture }.getOrNull()
        retriever.release()
        temporary.delete()
        artwork
    }.getOrNull()

    private fun cacheEmbeddedArtwork(id: Long, bytes: ByteArray?): String? {
        if (bytes == null || bytes.isEmpty()) return null
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val directory = File(context.cacheDir, "album_art").apply { mkdirs() }
        val artwork = File(directory, "${id.toULong()}.jpg")
        if (!artwork.exists() || artwork.length() == 0L) artwork.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        bitmap.recycle()
        return artwork.absolutePath
    }

    private fun cacheDownloadedArtwork(artist: String, album: String, bytes: ByteArray): String? {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val directory = File(context.cacheDir, "album_art").apply { mkdirs() }
        val file = File(directory, "online_${stableId("$artist\u0000$album")}.jpg")
        if (!file.exists()) file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        bitmap.recycle()
        return file.absolutePath
    }

    private fun getText(address: String): String? = HttpArtworkClient.getText(address)

    private fun getBytes(address: String): ByteArray? = runCatching {
        val connection = (java.net.URL(address).openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 12_000
            setRequestProperty("User-Agent", "XSTAR/0.1.0 (local-player)")
        }
        try { if (connection.responseCode !in 200..299) null else connection.inputStream.use { it.readBytes() } }
        finally { connection.disconnect() }
    }.getOrNull()

    private fun stableId(value: String): Long = stableSongId(value)
    suspend fun findByIds(ids: List<Long>): List<SongEntity> { val songs = dao.findByMediaIds(ids).associateBy { it.mediaStoreId }; return ids.mapNotNull(songs::get) }
    private fun String?.orDefault(default: String) = if (isNullOrBlank() || this == "<unknown>") default else this
}

data class ArtworkCandidate(val url: String, val label: String)
data class ScanProgress(val fileName: String, val discovered: Int)
