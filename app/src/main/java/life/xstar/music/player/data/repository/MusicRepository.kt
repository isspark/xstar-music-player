package life.xstar.music.player.data.repository

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import life.xstar.music.player.data.local.SongDao
import life.xstar.music.player.data.local.SongEntity
import life.xstar.music.player.data.local.PlaylistDao
import life.xstar.music.player.data.local.PlaylistEntity
import life.xstar.music.player.data.local.PlaylistSongEntity
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

    suspend fun scan(): Int = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val songs = mutableListOf<SongEntity>()
        val ids = mutableListOf<Long>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.ALBUM_ID
        )
        resolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} > 0",
            null, "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
        )?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val title = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artist = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val album = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val duration = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val added = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val albumId = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            while (cursor.moveToNext()) {
                val mediaId = cursor.getLong(id)
                ids += mediaId
                songs += SongEntity(
                    mediaStoreId = mediaId,
                    title = cursor.getString(title).orDefault("未知歌曲"),
                    artist = cursor.getString(artist).orDefault("未知艺术家"),
                    album = cursor.getString(album).orDefault("未知专辑"),
                    durationMs = cursor.getLong(duration),
                    uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mediaId).toString(),
                    albumArtUri = ContentUris.withAppendedId(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, cursor.getLong(albumId)).toString(),
                    dateAdded = cursor.getLong(added)
                )
            }
        }
        val existing = if (ids.isEmpty()) emptyList() else dao.findByMediaIds(ids).associateBy { it.mediaStoreId }
        val merged = songs.map { scanned ->
            val old = existing[scanned.mediaStoreId]
            scanned.copy(isFavorite = old?.isFavorite ?: false, playCount = old?.playCount ?: 0, lastPlayedAt = old?.lastPlayedAt)
        }
        if (ids.isEmpty()) dao.deleteAll() else dao.deleteNotInMediaIds(ids)
        if (merged.isNotEmpty()) dao.upsertAll(merged)
        merged.size
    }

    suspend fun findByIds(ids: List<Long>): List<SongEntity> = dao.findByMediaIds(ids)
    private fun String?.orDefault(default: String) = if (isNullOrBlank() || this == "<unknown>") default else this
}
