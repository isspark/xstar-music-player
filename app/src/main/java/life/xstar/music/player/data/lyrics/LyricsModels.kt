package life.xstar.music.player.data.lyrics

import life.xstar.music.player.data.local.SongEntity

enum class LyricsSource { LocalLrc, Embedded, Online }

data class LyricLine(val timeMs: Long?, val text: String)

data class LyricsDocument(val lines: List<LyricLine>, val source: LyricsSource)

sealed interface LyricsState {
    data object Idle : LyricsState
    data object Loading : LyricsState
    data object NotFound : LyricsState
    data class Loaded(val document: LyricsDocument) : LyricsState
    data class Candidates(val song: SongEntity, val items: List<LyricsCandidate>) : LyricsState
    data class Error(val message: String) : LyricsState
}

data class LyricsCandidate(
    val id: String,
    val trackName: String,
    val artistName: String,
    val albumName: String,
    val syncedLyrics: String?,
    val plainLyrics: String?
) {
    val isSynced: Boolean get() = !syncedLyrics.isNullOrBlank()
    val content: String get() = syncedLyrics ?: plainLyrics.orEmpty()
}
