package life.xstar.music.player.data.lyrics

import kotlinx.coroutines.CancellationException
import life.xstar.music.player.data.local.SongEntity
import life.xstar.music.player.data.online.platformLabel
import life.xstar.music.player.data.repository.HttpArtworkClient
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** GD Studio-compatible third-party API, not an official platform SDK. */
internal class PlatformLyricsProvider(
    private val baseUrl: String,
    private val platform: String,
    private val request: suspend (String) -> String? = { HttpArtworkClient.getTextRetry(it, 2) }
) {
    suspend fun search(song: SongEntity): List<LyricsCandidate> {
        val directId = song.onlineId?.takeIf { it.substringBefore(':') == platform }?.substringAfter(':')
        if (!directId.isNullOrBlank()) {
            fetch(directId, song.title, song.artist, song.album)?.let { return listOf(it) }
        }
        val response = request(url("types" to "search", "source" to platform,
            "name" to "${song.artist} ${song.title}", "count" to "3", "pages" to "1")) ?: return emptyList()
        val tracks = JSONArray(response)
        return buildList {
            for (index in 0 until minOf(tracks.length(), 3)) {
                val track = tracks.optJSONObject(index) ?: continue
                val id = track.optString("lyric_id").valid() ?: track.optString("id").valid() ?: continue
                val title = track.optString("name").valid() ?: continue
                val artist = when (val value = track.opt("artist")) {
                    is JSONArray -> (0 until value.length()).mapNotNull {
                        when (val item = value.opt(it)) {
                            is String -> item.valid()
                            is JSONObject -> item.optString("name").valid()
                            else -> null
                        }
                    }.joinToString(" / ")
                    is String -> value
                    else -> ""
                }
                try {
                    fetch(id, title, artist, track.optString("album"))?.let(::add)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // An unavailable track must not discard other versions from this source.
                }
            }
        }
    }

    private suspend fun fetch(id: String, title: String, artist: String, album: String): LyricsCandidate? {
        val response = request(url("types" to "lyric", "source" to platform, "id" to id)) ?: return null
        return parse(response, platform, id, title, artist, album)
    }

    private fun url(vararg parameters: Pair<String, String>) = baseUrl +
        (if ('?' in baseUrl) "&" else "?") + parameters.joinToString("&") {
            "${it.first}=${URLEncoder.encode(it.second, "UTF-8")}"
        }

    companion object {
        private fun String.valid() = takeIf { it.isNotBlank() && it != "null" }

        internal fun parse(response: String, platform: String, id: String, title: String,
                           artist: String, album: String): LyricsCandidate? {
            val lyric = JSONObject(response).optString("lyric").valid() ?: return null
            val synced = LrcParser.parse(lyric).isNotEmpty()
            return LyricsCandidate("$platform:$id", title, artist, album,
                lyric.takeIf { synced }, lyric.takeUnless { synced }).apply {
                provider = "${platformLabel(platform)} · GD Studio"
            }
        }
    }
}
