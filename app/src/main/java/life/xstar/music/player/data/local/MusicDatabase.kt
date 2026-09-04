package life.xstar.music.player.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [SongEntity::class, PlaylistEntity::class, PlaylistSongEntity::class, SingingRecordEntity::class], version = 6, exportSchema = false)
abstract class MusicDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun singingDao(): SingingDao

    companion object {
        fun create(context: Context) = Room.databaseBuilder(
            context, MusicDatabase::class.java, "xstar.db"
        ).addMigrations(object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE songs ADD COLUMN mimeType TEXT")
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS playlists (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                """.trimIndent())
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS playlist_songs (
                        playlistId INTEGER NOT NULL,
                        songId INTEGER NOT NULL,
                        position INTEGER NOT NULL,
                        addedAt INTEGER NOT NULL,
                        PRIMARY KEY(playlistId, songId),
                        FOREIGN KEY(playlistId) REFERENCES playlists(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(songId) REFERENCES songs(mediaStoreId) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS index_playlist_songs_songId ON playlist_songs(songId)")
            }
        }, object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE songs ADD COLUMN sourceFolderUri TEXT NOT NULL DEFAULT ''")
            }
        }, object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE songs ADD COLUMN codec TEXT")
                database.execSQL("ALTER TABLE songs ADD COLUMN bitrate INTEGER")
                database.execSQL("ALTER TABLE songs ADD COLUMN sampleRate INTEGER")
                database.execSQL("ALTER TABLE songs ADD COLUMN bitDepth INTEGER")
                database.execSQL("ALTER TABLE songs ADD COLUMN channelCount INTEGER")
                database.execSQL("ALTER TABLE songs ADD COLUMN audioQuality TEXT NOT NULL DEFAULT 'Standard'")
                database.execSQL("ALTER TABLE songs ADD COLUMN fileName TEXT")
                database.execSQL("ALTER TABLE songs ADD COLUMN fileSize INTEGER")
            }
        }, object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE songs ADD COLUMN onlineId TEXT")
            }
        }, object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS singing_records (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        songId INTEGER NOT NULL,
                        songTitle TEXT NOT NULL,
                        songArtist TEXT NOT NULL,
                        albumArtUri TEXT,
                        filePath TEXT NOT NULL,
                        total INTEGER NOT NULL,
                        pitch INTEGER,
                        activity INTEGER NOT NULL,
                        lightweight INTEGER NOT NULL,
                        durationMs INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS index_singing_records_createdAt ON singing_records(createdAt)")
            }
        }).build()
    }
}
