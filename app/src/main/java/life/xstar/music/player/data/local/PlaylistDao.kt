package life.xstar.music.player.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY updatedAt DESC") fun observePlaylists(): Flow<List<PlaylistEntity>>
    @Query("SELECT * FROM playlist_songs WHERE playlistId = :playlistId ORDER BY position, addedAt, songId") fun observeSongs(playlistId: Long): Flow<List<PlaylistSongEntity>>
    @Insert suspend fun insert(playlist: PlaylistEntity): Long
    @Update suspend fun update(playlist: PlaylistEntity)
    @Delete suspend fun delete(playlist: PlaylistEntity)
    @Insert(onConflict = androidx.room.OnConflictStrategy.IGNORE) suspend fun addSong(item: PlaylistSongEntity)
    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_songs WHERE playlistId = :playlistId") suspend fun nextPosition(playlistId: Long): Int
    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId AND songId = :songId") suspend fun remove(playlistId: Long, songId: Long)
    @Query("UPDATE playlists SET name = :name, updatedAt = :updatedAt WHERE id = :id") suspend fun rename(id: Long, name: String, updatedAt: Long = System.currentTimeMillis())
    @Query("UPDATE playlists SET updatedAt = :time WHERE id = :id") suspend fun touch(id: Long, time: Long = System.currentTimeMillis())
    @Query("SELECT * FROM playlist_songs WHERE playlistId = :id ORDER BY position, addedAt, songId") suspend fun songsNow(id: Long): List<PlaylistSongEntity>
    @Query("UPDATE playlist_songs SET position = :position WHERE playlistId = :id AND songId = :songId") suspend fun position(id: Long, songId: Long, position: Int)

    @Transaction
    suspend fun append(id: Long, songIds: List<Long>) {
        var next = nextPosition(id)
        songIds.distinct().forEach { addSong(PlaylistSongEntity(id, it, next++)) }
        touch(id)
    }

    @Transaction
    suspend fun removeSong(id: Long, songId: Long) { remove(id, songId); touch(id) }

    @Transaction
    suspend fun move(id: Long, songId: Long, delta: Int) {
        val songs = songsNow(id).toMutableList()
        val from = songs.indexOfFirst { it.songId == songId }
        val to = from + delta
        if (from < 0 || to !in songs.indices) return
        songs.add(to, songs.removeAt(from))
        songs.forEachIndexed { index, song -> position(id, song.songId, index) }
        touch(id)
    }
}
