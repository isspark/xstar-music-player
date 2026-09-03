package life.xstar.music.player.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "songs")
data class SongEntity(
    @PrimaryKey val mediaStoreId: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val uri: String,
    val mimeType: String?,
    val codec: String? = null,
    val bitrate: Long? = null,
    val sampleRate: Int? = null,
    val bitDepth: Int? = null,
    val channelCount: Int? = null,
    val audioQuality: String = "Standard",
    val fileName: String? = null,
    val fileSize: Long? = null,
    val albumArtUri: String?,
    val sourceFolderUri: String,
    val dateAdded: Long,
    val isFavorite: Boolean = false,
    val playCount: Int = 0,
    val lastPlayedAt: Long? = null,
    val onlineId: String? = null
)
