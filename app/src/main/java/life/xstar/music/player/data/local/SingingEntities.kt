package life.xstar.music.player.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "singing_records")
data class SingingRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val songId: Long,
    val songTitle: String,
    val songArtist: String,
    val albumArtUri: String?,
    val filePath: String,
    val total: Int,
    val pitch: Int?,
    val activity: Int,
    val lightweight: Boolean,
    val durationMs: Long,
    val createdAt: Long
)
