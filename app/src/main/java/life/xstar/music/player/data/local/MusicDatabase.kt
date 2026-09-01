package life.xstar.music.player.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [SongEntity::class, PlaylistEntity::class, PlaylistSongEntity::class], version = 3, exportSchema = false)
abstract class MusicDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao
    abstract fun playlistDao(): PlaylistDao

    companion object {
        fun create(context: Context) = Room.databaseBuilder(
            context, MusicDatabase::class.java, "xstar.db"
        ).addMigrations(object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE songs ADD COLUMN mimeType TEXT")
            }
        }, object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE songs ADD COLUMN sourceFolderUri TEXT NOT NULL DEFAULT ''")
            }
        }).build()
    }
}
