package life.xstar.music.player.data.nas

import androidx.room.*

@Entity(tableName = "nas_sync_tasks")
data class NasSyncTask(
    @PrimaryKey val sourceId: String,
    val runId: String,
    val detailed: Boolean,
    val state: String = "running",
    val message: String = "正在建立曲库索引",
    val count: Int = 0
)

@Entity(tableName = "nas_sync_items", primaryKeys = ["sourceId", "kind", "path"],
    indices = [Index(value = ["sourceId", "done", "kind", "retryAt"])])
data class NasSyncItem(
    val sourceId: String,
    val kind: Int, // 0: listing/page, 1: optional metadata enrichment
    val path: String,
    val done: Boolean = false,
    val attempts: Int = 0,
    val retryAt: Long = 0
)

@Dao
interface NasSyncDao {
    @Query("SELECT * FROM nas_sync_tasks WHERE sourceId = :source LIMIT 1")
    suspend fun task(source: String): NasSyncTask?
    @Upsert suspend fun save(task: NasSyncTask)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun enqueue(items: List<NasSyncItem>)
    @Upsert suspend fun save(item: NasSyncItem)
    @Query("SELECT * FROM nas_sync_items WHERE sourceId = :source AND done = 0 AND retryAt <= :now ORDER BY kind, retryAt, path LIMIT 1")
    suspend fun next(source: String, now: Long): NasSyncItem?
    @Query("SELECT COUNT(*) FROM nas_sync_items WHERE sourceId = :source AND done = 0")
    suspend fun pending(source: String): Int
    @Query("DELETE FROM nas_sync_items WHERE sourceId = :source")
    suspend fun clear(source: String)
    @Query("UPDATE nas_sync_items SET retryAt = 0 WHERE sourceId = :source AND done = 0")
    suspend fun resetRetries(source: String)
}
