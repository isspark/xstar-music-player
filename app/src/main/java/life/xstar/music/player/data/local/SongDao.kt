package life.xstar.music.player.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import androidx.room.SkipQueryVerification
import kotlinx.coroutines.flow.Flow

@Dao
interface SongDao {
    @SkipQueryVerification
    @Query("SELECT * FROM songs WHERE mediaStoreId IN (SELECT mediaStoreId FROM songs_fts WHERE title LIKE '%' || :query || '%' OR artist LIKE '%' || :query || '%' OR album LIKE '%' || :query || '%' OR fileName LIKE '%' || :query || '%') ORDER BY title COLLATE NOCASE, mediaStoreId")
    suspend fun searchFts(query: String): List<SongEntity>
    @SkipQueryVerification
    @Query("DELETE FROM songs_fts")
    suspend fun clearSearchIndex()
    @SkipQueryVerification
    @Query("INSERT INTO songs_fts(mediaStoreId, title, artist, album, fileName) VALUES (:id, :title, :artist, :album, :fileName)")
    suspend fun indexSong(id: Long, title: String, artist: String, album: String, fileName: String?)
    @SkipQueryVerification
    @Query("DELETE FROM songs_fts WHERE mediaStoreId = :id")
    suspend fun removeFromSearchIndex(id: Long)
    @SkipQueryVerification
    @Query("SELECT indexedCount FROM search_index_state WHERE id = 1")
    suspend fun searchIndexCount(): Int?
    @SkipQueryVerification
    @Query("UPDATE search_index_state SET indexedCount = :count, complete = :complete WHERE id = 1")
    suspend fun updateSearchIndexState(count: Int, complete: Boolean)
    @SkipQueryVerification
    @Query("SELECT indexedCount = (SELECT COUNT(*) FROM songs) AND complete = 1 FROM search_index_state WHERE id = 1")
    suspend fun isSearchIndexReady(): Boolean?
    @Query("SELECT * FROM songs WHERE (:artist IS NULL OR artist = :artist) AND (:album IS NULL OR album = :album) AND (:query = '' OR title LIKE '%' || :query || '%' OR artist LIKE '%' || :query || '%' OR album LIKE '%' || :query || '%' OR fileName LIKE '%' || :query || '%') ORDER BY title COLLATE NOCASE, mediaStoreId")
    suspend fun libraryQueue(query: String, artist: String?, album: String?): List<SongEntity>
    @Query("SELECT COUNT(*) FROM songs")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM songs WHERE (:artist IS NULL OR artist = :artist) AND (:album IS NULL OR album = :album) AND (:query = '' OR title LIKE '%' || :query || '%' OR artist LIKE '%' || :query || '%' OR album LIKE '%' || :query || '%' OR fileName LIKE '%' || :query || '%') ORDER BY title COLLATE NOCASE, mediaStoreId")
    fun pagedSongs(query: String, artist: String?, album: String?): androidx.paging.PagingSource<Int, SongEntity>

    @Query("SELECT artist AS name, COUNT(*) AS count, MIN(albumArtUri) AS artwork FROM songs WHERE :query = '' OR artist LIKE '%' || :query || '%' OR title LIKE '%' || :query || '%' OR album LIKE '%' || :query || '%' GROUP BY artist ORDER BY artist COLLATE NOCASE")
    fun pagedArtists(query: String): androidx.paging.PagingSource<Int, LibraryGroup>

    @Query("SELECT album AS name, COUNT(*) AS count, MIN(albumArtUri) AS artwork FROM songs WHERE :query = '' OR artist LIKE '%' || :query || '%' OR title LIKE '%' || :query || '%' OR album LIKE '%' || :query || '%' GROUP BY album ORDER BY album COLLATE NOCASE")
    fun pagedAlbums(query: String): androidx.paging.PagingSource<Int, LibraryGroup>
    @Query("SELECT * FROM songs WHERE sourceFolderUri = :source AND (title LIKE '%' || :query || '%' OR artist LIKE '%' || :query || '%' OR album LIKE '%' || :query || '%' OR fileName LIKE '%' || :query || '%') ORDER BY title COLLATE NOCASE LIMIT 200")
    suspend fun searchNas(source: String, query: String): List<SongEntity>

    @Query("SELECT COUNT(*) FROM songs WHERE sourceFolderUri = :source AND nasScanToken = :token")
    suspend fun scannedCount(source: String, token: String): Int

    @Query("SELECT COUNT(*) FROM songs WHERE sourceFolderUri = :source AND (nasScanToken IS NULL OR nasScanToken != :token)")
    suspend fun missingCount(source: String, token: String): Int
    @Query("SELECT * FROM songs WHERE title LIKE '%' || :query || '%' OR artist LIKE '%' || :query || '%' OR album LIKE '%' || :query || '%' OR fileName LIKE '%' || :query || '%' ORDER BY title COLLATE NOCASE")
    suspend fun searchOnce(query: String): List<SongEntity>
    @Query("SELECT * FROM songs WHERE sourceFolderUri NOT LIKE 'nas://%' OR mediaStoreId IN (SELECT mediaStoreId FROM songs WHERE sourceFolderUri LIKE 'nas://%' ORDER BY title COLLATE NOCASE LIMIT 1500) ORDER BY title COLLATE NOCASE")
    fun observeSongs(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs")
    suspend fun allSongs(): List<SongEntity>

    @Query("SELECT * FROM songs WHERE title LIKE '%' || :query || '%' OR artist LIKE '%' || :query || '%' OR album LIKE '%' || :query || '%' ORDER BY title COLLATE NOCASE")
    fun search(query: String): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE mediaStoreId IN (:ids)")
    suspend fun findByMediaIdsBatch(ids: List<Long>): List<SongEntity>

    suspend fun findByMediaIds(ids: List<Long>): List<SongEntity> =
        ids.distinct().chunked(900).flatMap { findByMediaIdsBatch(it) }

    @Query("SELECT * FROM songs WHERE isFavorite = 1 ORDER BY title COLLATE NOCASE")
    fun observeFavorites(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE albumArtUri IS NULL OR albumArtUri = '' OR albumArtUri LIKE '%.img'")
    suspend fun findSongsWithoutArtwork(): List<SongEntity>

    @Query("SELECT * FROM songs WHERE mediaStoreId = :id LIMIT 1")
    suspend fun findById(id: Long): SongEntity?

    @Query("SELECT * FROM songs WHERE onlineId = :onlineId LIMIT 1")
    suspend fun findByOnlineId(onlineId: String): SongEntity?

    @Query("SELECT onlineId FROM songs WHERE onlineId IN (:ids)")
    suspend fun findOnlineIds(ids: List<String>): List<String>

    @Query("UPDATE songs SET albumArtUri = :artworkUri WHERE mediaStoreId = :songId")
    suspend fun updateArtwork(songId: Long, artworkUri: String)

    @Query("UPDATE songs SET albumArtUri = :artworkUri WHERE artist = :artist AND album = :album")
    suspend fun updateArtworkForAlbum(artist: String, album: String, artworkUri: String)

    @Query("SELECT * FROM songs WHERE artist = :artist AND album = :album")
    suspend fun findByAlbum(artist: String, album: String): List<SongEntity>

    @Query("UPDATE songs SET isFavorite = NOT isFavorite WHERE mediaStoreId = :id")
    suspend fun toggleFavorite(id: Long)
    @Query("UPDATE songs SET isFavorite = :value WHERE mediaStoreId IN (:ids)")
    suspend fun setFavorite(ids: List<Long>, value: Boolean)

    @Upsert
    suspend fun upsertAll(songs: List<SongEntity>)

    @Query("DELETE FROM songs WHERE mediaStoreId NOT IN (:ids) AND sourceFolderUri NOT LIKE 'nas://%'")
    suspend fun deleteNotInMediaIds(ids: List<Long>)

    @Query("DELETE FROM songs WHERE sourceFolderUri NOT LIKE 'nas://%'")
    suspend fun deleteAll()
}

data class LibraryGroup(val name: String, val count: Int, val artwork: String?)
