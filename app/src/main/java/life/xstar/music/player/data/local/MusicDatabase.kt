package life.xstar.music.player.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [SongEntity::class, PlaylistEntity::class, PlaylistSongEntity::class, SingingRecordEntity::class, life.xstar.music.player.data.nas.NasSyncTask::class, life.xstar.music.player.data.nas.NasSyncItem::class], version = 8, exportSchema = false)
abstract class MusicDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun singingDao(): SingingDao
    abstract fun nasSyncDao(): life.xstar.music.player.data.nas.NasSyncDao

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
        }, object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS nas_sync_tasks (sourceId TEXT NOT NULL PRIMARY KEY, runId TEXT NOT NULL, detailed INTEGER NOT NULL, state TEXT NOT NULL, message TEXT NOT NULL, count INTEGER NOT NULL)")
                database.execSQL("CREATE TABLE IF NOT EXISTS nas_sync_items (sourceId TEXT NOT NULL, kind INTEGER NOT NULL, path TEXT NOT NULL, done INTEGER NOT NULL, attempts INTEGER NOT NULL, retryAt INTEGER NOT NULL, PRIMARY KEY(sourceId, kind, path))")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_nas_sync_items_sourceId_done_kind_retryAt ON nas_sync_items(sourceId, done, kind, retryAt)")
                database.execSQL("ALTER TABLE songs ADD COLUMN nasScanToken TEXT")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_songs_sourceFolderUri ON songs(sourceFolderUri)")
            }
        }, object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Independent and rebuildable: an interrupted index build cannot damage songs.
                database.execSQL("CREATE TABLE IF NOT EXISTS songs_fts (mediaStoreId INTEGER PRIMARY KEY NOT NULL, title TEXT NOT NULL, artist TEXT NOT NULL, album TEXT NOT NULL, fileName TEXT)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_songs_fts_title ON songs_fts(title)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_songs_fts_artist ON songs_fts(artist)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_songs_fts_album ON songs_fts(album)")
                database.execSQL("CREATE TABLE IF NOT EXISTS search_index_state (id INTEGER PRIMARY KEY NOT NULL, indexedCount INTEGER NOT NULL DEFAULT 0, complete INTEGER NOT NULL DEFAULT 0)")
                database.execSQL("INSERT OR IGNORE INTO search_index_state(id, indexedCount, complete) VALUES(1, 0, 0)")
            }
        }).build()
    }
}
