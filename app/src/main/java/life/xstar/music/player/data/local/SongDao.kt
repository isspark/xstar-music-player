package life.xstar.music.player.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SongDao {
    @Query("SELECT * FROM songs ORDER BY title COLLATE NOCASE")
    fun observeSongs(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE title LIKE '%' || :query || '%' OR artist LIKE '%' || :query || '%' OR album LIKE '%' || :query || '%' ORDER BY title COLLATE NOCASE")
    fun search(query: String): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE mediaStoreId IN (:ids)")
    suspend fun findByMediaIds(ids: List<Long>): List<SongEntity>

    @Query("SELECT * FROM songs WHERE isFavorite = 1 ORDER BY title COLLATE NOCASE")
    fun observeFavorites(): Flow<List<SongEntity>>

    @Query("UPDATE songs SET isFavorite = NOT isFavorite WHERE mediaStoreId = :id")
    suspend fun toggleFavorite(id: Long)

    @Upsert
    suspend fun upsertAll(songs: List<SongEntity>)

    @Query("DELETE FROM songs WHERE mediaStoreId NOT IN (:ids)")
    suspend fun deleteNotInMediaIds(ids: List<Long>)

    @Query("DELETE FROM songs")
    suspend fun deleteAll()
}
