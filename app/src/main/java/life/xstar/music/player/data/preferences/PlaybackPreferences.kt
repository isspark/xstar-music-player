package life.xstar.music.player.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first

private val Context.playbackDataStore by preferencesDataStore("playback")

class PlaybackPreferences(private val context: Context) {
    private val queueKey = stringPreferencesKey("queue_ids")
    private val indexKey = longPreferencesKey("queue_index")
    private val positionKey = longPreferencesKey("position_ms")
    private val foldersKey = stringSetPreferencesKey("music_folders")
    private val themeKey = stringPreferencesKey("theme_accent")

    val folders: Flow<Set<String>> = context.playbackDataStore.data.map { it[foldersKey].orEmpty() }
    val theme: Flow<ThemeAccent> = context.playbackDataStore.data.map { values ->
        runCatching { ThemeAccent.valueOf(values[themeKey] ?: ThemeAccent.Blue.name) }.getOrDefault(ThemeAccent.Blue)
    }

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

    suspend fun addFolder(uri: String) = context.playbackDataStore.edit { values ->
        values[foldersKey] = values[foldersKey].orEmpty() + uri
    }

    suspend fun removeFolder(uri: String) = context.playbackDataStore.edit { values ->
        values[foldersKey] = values[foldersKey].orEmpty() - uri
    }

    suspend fun setTheme(theme: ThemeAccent) = context.playbackDataStore.edit { it[themeKey] = theme.name }
}

data class SavedPlayback(val queue: List<Long>, val index: Int, val position: Long)
enum class ThemeAccent { Blue, Violet, Rose, Emerald, Amber }
