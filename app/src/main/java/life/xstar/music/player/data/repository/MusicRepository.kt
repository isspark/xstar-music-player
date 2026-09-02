package life.xstar.music.player.data.repository

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import org.json.JSONObject
import java.security.MessageDigest
import life.xstar.music.player.data.local.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class MusicRepository(private val context: Context, private val dao: SongDao, private val playlistDao: PlaylistDao) {
    fun songs(): Flow<List<SongEntity>> = dao.observeSongs()
    fun search(query: String): Flow<List<SongEntity>> = dao.search(query)
    fun favorites(): Flow<List<SongEntity>> = dao.observeFavorites()
    fun playlists(): Flow<List<PlaylistEntity>> = playlistDao.observePlaylists()
    fun playlistSongs(id: Long): Flow<List<PlaylistSongEntity>> = playlistDao.observeSongs(id)
    suspend fun toggleFavorite(id: Long) = dao.toggleFavorite(id)
    suspend fun createPlaylist(name: String) = playlistDao.insert(PlaylistEntity(name = name))
    suspend fun deletePlaylist(playlist: PlaylistEntity) = playlistDao.delete(playlist)
    suspend fun addToPlaylist(playlistId: Long, songId: Long) = playlistDao.addSong(PlaylistSongEntity(playlistId, songId, playlistDao.nextPosition(playlistId)))
    suspend fun removeFromPlaylist(playlistId: Long, songId: Long) = playlistDao.remove(playlistId, songId)
    suspend fun renamePlaylist(id: Long, name: String) = playlistDao.rename(id, name.trim())
    suspend fun enrichMissingArtwork() = withContext(Dispatchers.IO) {
        dao.findSongsWithoutArtwork().groupBy { it.artist to it.album }.forEach { (key, songs) ->
            val artwork = findOnlineArtwork(key.first, key.second) ?: return@forEach
            songs.forEach { dao.updateArtwork(it.mediaStoreId, artwork) }
        }
    }

    suspend fun getSong(id: Long): SongEntity? = dao.findById(id)

    suspend fun scan(folderUris: Set<String>): Int = withContext(Dispatchers.IO) {
        if (folderUris.isEmpty()) {
            dao.deleteAll()
            return@withContext 0
        }
        val scanned = mutableListOf<SongEntity>()
        folderUris.forEach { folderUri ->
            DocumentFile.fromTreeUri(context, Uri.parse(folderUri))?.let { scanDirectory(it, folderUri, scanned) }
        }
        val ids = scanned.map { it.mediaStoreId }
        val existing = if (ids.isEmpty()) emptyMap() else dao.findByMediaIds(ids).associateBy { it.mediaStoreId }
        val merged = scanned.map { song -> existing[song.mediaStoreId]?.let { old ->
            song.copy(
                albumArtUri = song.albumArtUri ?: old.albumArtUri?.takeUnless { it.endsWith(".img") },
                isFavorite = old.isFavorite,
                playCount = old.playCount,
                lastPlayedAt = old.lastPlayedAt
            )
        } ?: song }
        if (ids.isEmpty()) dao.deleteAll() else dao.deleteNotInMediaIds(ids)
        if (merged.isNotEmpty()) dao.upsertAll(merged)
        merged.size
    }

    private fun scanDirectory(directory: DocumentFile, folderUri: String, output: MutableList<SongEntity>) {
        directory.listFiles().forEach { file ->
            when {
                file.isDirectory -> scanDirectory(file, folderUri, output)
                file.isFile && isAudio(file) -> readSong(file, folderUri)?.let(output::add)
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
            val embeddedArtwork = retriever.embeddedPicture ?: readEmbeddedFromCopy(file.uri)
            SongEntity(
                mediaStoreId = id,
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE).orDefault(fileName.substringBeforeLast('.')),
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orDefault("未知艺术家"),
                album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).orDefault("未知专辑"),
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0,
                uri = file.uri.toString(),
                mimeType = file.type ?: context.contentResolver.getType(file.uri),
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

    private fun findOnlineArtwork(artist: String, album: String): String? {
        if (artist == "未知艺术家" || album == "未知专辑") return null
        val query = "artist:\"${artist.trim()}\" AND release:\"${album.trim()}\""
        val searchUrl = "https://musicbrainz.org/ws/2/release/?query=${URLEncoder.encode(query, "UTF-8")}&fmt=json&limit=5"
        val releases = getText(searchUrl)?.let { JSONObject(it).optJSONArray("releases") }
        if (releases != null) for (index in 0 until releases.length()) {
            val releaseId = releases.optJSONObject(index)?.optString("id").orEmpty()
            if (releaseId.isNotBlank()) {
                getBytes("https://coverartarchive.org/release/$releaseId/front-500")?.let { bytes ->
                    cacheDownloadedArtwork(artist, album, bytes)?.let { return it }
                }
            }
        }
        val groupsUrl = "https://musicbrainz.org/ws/2/release-group/?query=${URLEncoder.encode(query, "UTF-8")}&fmt=json&limit=5"
        val groups = getText(groupsUrl)?.let { JSONObject(it).optJSONArray("release-groups") }
        if (groups != null) for (index in 0 until groups.length()) {
            val groupId = groups.optJSONObject(index)?.optString("id").orEmpty()
            if (groupId.isBlank()) continue
            getBytes("https://coverartarchive.org/release-group/$groupId/front-500")?.let { bytes ->
                cacheDownloadedArtwork(artist, album, bytes)?.let { return it }
            }
        }
        return null
    }

    private fun cacheDownloadedArtwork(artist: String, album: String, bytes: ByteArray): String? {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val directory = File(context.cacheDir, "album_art").apply { mkdirs() }
        val file = File(directory, "online_${stableId("$artist\u0000$album")}.jpg")
        if (!file.exists()) file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        bitmap.recycle()
        return file.absolutePath
    }

    private fun getText(address: String): String? = getConnection(address)?.let { connection ->
        try { connection.inputStream.bufferedReader().use { it.readText() } } finally { connection.disconnect() }
    }

    private fun getBytes(address: String): ByteArray? = getConnection(address)?.let { connection ->
        try { connection.inputStream.use { it.readBytes() } } finally { connection.disconnect() }
    }
    private fun getConnection(address: String): HttpURLConnection? = runCatching {
        (URL(address).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 12_000
            setRequestProperty("User-Agent", "XSTAR/0.1.0 (local-player)")
            setRequestProperty("Accept", "application/json, image/*")
            if (responseCode !in 200..299) { disconnect(); return null }
        }
    }.getOrNull()

    private fun stableId(value: String): Long = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).take(8).fold(0L) { result, byte -> (result shl 8) or (byte.toLong() and 0xff) }
    suspend fun findByIds(ids: List<Long>): List<SongEntity> { val songs = dao.findByMediaIds(ids).associateBy { it.mediaStoreId }; return ids.mapNotNull(songs::get) }
    private fun String?.orDefault(default: String) = if (isNullOrBlank() || this == "<unknown>") default else this
}
