package life.xstar.music.player.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.playbackDataStore by preferencesDataStore("playback")

class PlaybackPreferences(private val context: Context) {
    private val queueKey = stringPreferencesKey("queue_ids")
    private val indexKey = longPreferencesKey("queue_index")
    private val positionKey = longPreferencesKey("position_ms")

    suspend fun save(queue: List<Long>, index: Int, position: Long) {
        context.playbackDataStore.edit { values ->
            values[queueKey] = queue.joinToString(",")
            values[indexKey] = index.toLong()
            values[positionKey] = position
        }
    }

    suspend fun read(): SavedPlayback {
        val values = context.playbackDataStore.data.first()
        val queue = values[queueKey].orEmpty().split(',').mapNotNull { it.toLongOrNull() }
        return SavedPlayback(queue, values[indexKey]?.toInt() ?: 0, values[positionKey] ?: 0)
    }
}

data class SavedPlayback(val queue: List<Long>, val index: Int, val position: Long)
