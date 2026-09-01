package life.xstar.music.player.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [SongEntity::class, PlaylistEntity::class, PlaylistSongEntity::class], version = 1, exportSchema = false)
abstract class MusicDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao
    abstract fun playlistDao(): PlaylistDao

    companion object {
        fun create(context: Context) = Room.databaseBuilder(
            context, MusicDatabase::class.java, "xstar.db"
        ).build()
    }
}
