package life.xstar.music.player.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY updatedAt DESC") fun observePlaylists(): Flow<List<PlaylistEntity>>
    @Query("SELECT * FROM playlist_songs WHERE playlistId = :playlistId ORDER BY position") fun observeSongs(playlistId: Long): Flow<List<PlaylistSongEntity>>
    @Insert suspend fun insert(playlist: PlaylistEntity): Long
    @Update suspend fun update(playlist: PlaylistEntity)
    @Delete suspend fun delete(playlist: PlaylistEntity)
    @Insert(onConflict = androidx.room.OnConflictStrategy.IGNORE) suspend fun addSong(item: PlaylistSongEntity)
    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_songs WHERE playlistId = :playlistId") suspend fun nextPosition(playlistId: Long): Int
    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId AND songId = :songId") suspend fun remove(playlistId: Long, songId: Long)
    @Query("UPDATE playlists SET name = :name, updatedAt = :updatedAt WHERE id = :id") suspend fun rename(id: Long, name: String, updatedAt: Long = System.currentTimeMillis())
}
