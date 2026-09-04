package life.xstar.music.player.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SingingDao {
    @Query("SELECT * FROM singing_records ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<SingingRecordEntity>>

    @Insert
    suspend fun insert(record: SingingRecordEntity): Long

    @Query("UPDATE singing_records SET total = :total, pitch = :pitch, activity = :activity, lightweight = :lightweight WHERE id = :id")
    suspend fun updateScore(id: Long, total: Int, pitch: Int?, activity: Int, lightweight: Boolean)

    @Delete
    suspend fun delete(record: SingingRecordEntity)
}
