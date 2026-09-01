package life.xstar.music.player.data.repository

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import java.io.File
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
        val merged = scanned.map { song -> existing[song.mediaStoreId]?.let { old -> song.copy(isFavorite = old.isFavorite, playCount = old.playCount, lastPlayedAt = old.lastPlayedAt) } ?: song }
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
            SongEntity(
                mediaStoreId = id,
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE).orDefault(fileName.substringBeforeLast('.')),
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orDefault("未知艺术家"),
                album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).orDefault("未知专辑"),
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0,
                uri = file.uri.toString(),
                mimeType = file.type ?: context.contentResolver.getType(file.uri),
                albumArtUri = cacheEmbeddedArtwork(id, retriever.embeddedPicture),
                sourceFolderUri = folderUri,
                dateAdded = file.lastModified()
            )
        }.getOrNull().also { retriever.release() }
    }

    private fun cacheEmbeddedArtwork(id: Long, bytes: ByteArray?): String? {
        if (bytes == null || bytes.isEmpty()) return null
        val directory = File(context.cacheDir, "album_art").apply { mkdirs() }
        val artwork = File(directory, "${id.toULong()}.img")
        if (!artwork.exists() || artwork.length() != bytes.size.toLong()) artwork.writeBytes(bytes)
        return FileProvider.getUriForFile(context, "${context.packageName}.files", artwork).toString()
    }

    private fun stableId(value: String): Long = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).take(8).fold(0L) { result, byte -> (result shl 8) or (byte.toLong() and 0xff) }
    suspend fun findByIds(ids: List<Long>): List<SongEntity> { val songs = dao.findByMediaIds(ids).associateBy { it.mediaStoreId }; return ids.mapNotNull(songs::get) }
    private fun String?.orDefault(default: String) = if (isNullOrBlank() || this == "<unknown>") default else this
}
