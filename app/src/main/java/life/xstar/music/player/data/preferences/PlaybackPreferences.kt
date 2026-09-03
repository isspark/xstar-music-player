package life.xstar.music.player.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import life.xstar.music.player.player.EqualizerSettings
import life.xstar.music.player.player.KaraokeSettings

private val Context.playbackDataStore by preferencesDataStore("playback")

class PlaybackPreferences(private val context: Context) {
    private val queueKey = stringPreferencesKey("queue_ids")
    private val indexKey = longPreferencesKey("queue_index")
    private val positionKey = longPreferencesKey("position_ms")
    private val foldersKey = stringSetPreferencesKey("music_folders")
    private val themeKey = stringPreferencesKey("theme_accent")
    private val beatEffectKey = booleanPreferencesKey("beat_effect")
    private val onlineArtworkKey = booleanPreferencesKey("online_artwork_enabled")
    private val onlineLyricsKey = booleanPreferencesKey("online_lyrics_enabled")
    private val playbackModeKey = stringPreferencesKey("playback_mode")
    private val wasPlayingKey = booleanPreferencesKey("was_playing")
    private val fmModeKey = booleanPreferencesKey("fm_mode_enabled")
    private val fmQueueKey = stringPreferencesKey("fm_queue_ids")
    private val fmIndexKey = longPreferencesKey("fm_queue_index")
    private val fmPositionKey = longPreferencesKey("fm_position_ms")
    private val fmWasPlayingKey = booleanPreferencesKey("fm_was_playing")
    private val karaokeEnabledKey = booleanPreferencesKey("karaoke_enabled")
    private val karaokeVocalKey = stringPreferencesKey("karaoke_vocal")
    private val karaokePitchKey = longPreferencesKey("karaoke_pitch")
    private val onlineSearchEnabledKey = booleanPreferencesKey("online_search_enabled")
    private val onlinePlatformKey = stringPreferencesKey("online_platform")
    private val onlineApiBaseKey = stringPreferencesKey("online_api_base")
    private val downloadFolderKey = stringPreferencesKey("download_folder")
    private val equalizerEnabledKey = booleanPreferencesKey("equalizer_enabled")
    private val equalizerPresetKey = longPreferencesKey("equalizer_preset")
    private val equalizerLevelsKey = stringPreferencesKey("equalizer_levels")

    val folders: Flow<Set<String>> = context.playbackDataStore.data.map { it[foldersKey].orEmpty() }
    val theme: Flow<ThemeAccent> = context.playbackDataStore.data.map { values ->
        runCatching { ThemeAccent.valueOf(values[themeKey] ?: ThemeAccent.Blue.name) }.getOrDefault(ThemeAccent.Blue)
    }
    val beatEffect: Flow<Boolean> = context.playbackDataStore.data.map { it[beatEffectKey] ?: true }
    val onlineArtworkEnabled: Flow<Boolean> = context.playbackDataStore.data.map { it[onlineArtworkKey] ?: true }
    val onlineLyricsEnabled: Flow<Boolean> = context.playbackDataStore.data.map { it[onlineLyricsKey] ?: true }
    val fmModeEnabled: Flow<Boolean> = context.playbackDataStore.data.map { it[fmModeKey] ?: false }
    val karaokeModeEnabled: Flow<Boolean> = context.playbackDataStore.data.map { it[karaokeEnabledKey] ?: false }
    val onlineSearchEnabled: Flow<Boolean> = context.playbackDataStore.data.map { it[onlineSearchEnabledKey] ?: true }
    val onlinePlatform: Flow<String> = context.playbackDataStore.data.map { it[onlinePlatformKey] ?: "netease" }
    val onlineApiBase: Flow<String> = context.playbackDataStore.data.map { it[onlineApiBaseKey].orEmpty() }
    val downloadFolder: Flow<String> = context.playbackDataStore.data.map { it[downloadFolderKey].orEmpty() }

    suspend fun save(queue: List<Long>, index: Int, position: Long, mode: String, playing: Boolean) {
        context.playbackDataStore.edit { values ->
            values[queueKey] = queue.joinToString(",")
            values[indexKey] = index.toLong()
            values[positionKey] = position
            values[playbackModeKey] = mode
            values[wasPlayingKey] = playing
        }
    }

    suspend fun read(): SavedPlayback {
        val values = context.playbackDataStore.data.first()
        return SavedPlayback(
            queue = values[queueKey].toIds(),
            index = values[indexKey]?.toInt() ?: 0,
            position = values[positionKey] ?: 0,
            mode = values[playbackModeKey] ?: "Sequential",
            playing = values[wasPlayingKey] ?: false
        )
    }

    suspend fun saveFm(queue: List<Long>, index: Int, position: Long, playing: Boolean) = context.playbackDataStore.edit { values ->
        values[fmQueueKey] = queue.joinToString(",")
        values[fmIndexKey] = index.toLong()
        values[fmPositionKey] = position
        values[fmWasPlayingKey] = playing
    }

    suspend fun readFm(): SavedPlayback {
        val values = context.playbackDataStore.data.first()
        return SavedPlayback(
            queue = values[fmQueueKey].toIds(),
            index = values[fmIndexKey]?.toInt() ?: 0,
            position = values[fmPositionKey] ?: 0,
            playing = values[fmWasPlayingKey] ?: false
        )
    }

    suspend fun isFmModeEnabled(): Boolean = context.playbackDataStore.data.first()[fmModeKey] ?: false
    suspend fun setFmModeEnabled(enabled: Boolean) = context.playbackDataStore.edit { it[fmModeKey] = enabled }
    suspend fun isKaraokeModeEnabled(): Boolean = context.playbackDataStore.data.first()[karaokeEnabledKey] ?: false

    suspend fun readKaraoke(): KaraokeSettings {
        val values = context.playbackDataStore.data.first()
        return KaraokeSettings(
            enabled = values[karaokeEnabledKey] ?: false,
            vocal = values[karaokeVocalKey]?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0f,
            pitchSemi = (values[karaokePitchKey] ?: 0L).toInt().coerceIn(-7, 7)
        )
    }

    suspend fun saveKaraoke(settings: KaraokeSettings) = context.playbackDataStore.edit { values ->
        values[karaokeEnabledKey] = settings.enabled
        values[karaokeVocalKey] = settings.vocal.toString()
        values[karaokePitchKey] = settings.pitchSemi.toLong()
    }

    suspend fun addFolder(uri: String) = context.playbackDataStore.edit { values ->
        values[foldersKey] = values[foldersKey].orEmpty() + uri
    }

    suspend fun removeFolder(uri: String) = context.playbackDataStore.edit { values ->
        values[foldersKey] = values[foldersKey].orEmpty() - uri
    }

    suspend fun setTheme(theme: ThemeAccent) = context.playbackDataStore.edit { it[themeKey] = theme.name }
    suspend fun setBeatEffect(enabled: Boolean) = context.playbackDataStore.edit { it[beatEffectKey] = enabled }
    suspend fun setOnlineArtworkEnabled(enabled: Boolean) = context.playbackDataStore.edit { it[onlineArtworkKey] = enabled }
    suspend fun setOnlineLyricsEnabled(enabled: Boolean) = context.playbackDataStore.edit { it[onlineLyricsKey] = enabled }
    suspend fun setOnlineSearchEnabled(enabled: Boolean) = context.playbackDataStore.edit { it[onlineSearchEnabledKey] = enabled }
    suspend fun setOnlinePlatform(platform: String) = context.playbackDataStore.edit { it[onlinePlatformKey] = platform }
    suspend fun setOnlineApiBase(base: String) = context.playbackDataStore.edit { it[onlineApiBaseKey] = base.trim() }
    suspend fun setDownloadFolder(uri: String?) = context.playbackDataStore.edit { values ->
        if (uri.isNullOrBlank()) values.remove(downloadFolderKey) else values[downloadFolderKey] = uri
    }
    suspend fun currentOnlinePlatform(): String = context.playbackDataStore.data.first()[onlinePlatformKey] ?: "netease"
    suspend fun currentOnlineApiBase(): String = context.playbackDataStore.data.first()[onlineApiBaseKey].orEmpty()

    suspend fun readEqualizer(): EqualizerSettings {
        val values = context.playbackDataStore.data.first()
        return EqualizerSettings(
            enabled = values[equalizerEnabledKey] ?: false,
            preset = values[equalizerPresetKey]?.toInt()?.takeIf { it >= 0 },
            levelsByFrequency = values[equalizerLevelsKey].orEmpty().split(',').mapNotNull { entry ->
                val parts = entry.split(':')
                if (parts.size == 2) parts[0].toIntOrNull()?.let { frequency -> parts[1].toIntOrNull()?.let { frequency to it } } else null
            }.toMap()
        )
    }

    suspend fun saveEqualizer(settings: EqualizerSettings) = context.playbackDataStore.edit { values ->
        values[equalizerEnabledKey] = settings.enabled
        values[equalizerPresetKey] = (settings.preset ?: -1).toLong()
        values[equalizerLevelsKey] = settings.levelsByFrequency.entries.joinToString(",") { "${it.key}:${it.value}" }
    }
}

private fun String?.toIds() = orEmpty().split(',').mapNotNull { it.toLongOrNull() }

data class SavedPlayback(
    val queue: List<Long>,
    val index: Int,
    val position: Long,
    val mode: String = "Sequential",
    val playing: Boolean = false
)
enum class ThemeAccent { Blue, Violet, Rose, Emerald, Amber }
