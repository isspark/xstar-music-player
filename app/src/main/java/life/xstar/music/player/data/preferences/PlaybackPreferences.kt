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
import life.xstar.music.player.together.TogetherConnectMode

private val Context.playbackDataStore by preferencesDataStore("playback")

class PlaybackPreferences(private val context: Context) {
    private val queueKey = stringPreferencesKey("queue_ids")
    private val indexKey = longPreferencesKey("queue_index")
    private val positionKey = longPreferencesKey("position_ms")
    private val foldersKey = stringSetPreferencesKey("music_folders")
    private val themeKey = stringPreferencesKey("theme_accent")
    private val customThemeColorKey = longPreferencesKey("custom_theme_color")
    private val playerThemeKey = stringPreferencesKey("player_scene_theme")
    private val fontKey = stringPreferencesKey("app_font")
    private val downloadedFontKey = stringPreferencesKey("downloaded_font")
    private val lyricsModeKey = stringPreferencesKey("lyrics_display_mode")
    private val floatingButtonStyleKey = stringPreferencesKey("floating_button_style")
    private val playerBackgroundKey = stringPreferencesKey("player_background_uri")
    private val albumArtworkBackgroundKey = booleanPreferencesKey("album_artwork_background")
    private val playerDiscOpacityKey = stringPreferencesKey("player_disc_opacity")
    private val playerDiscVisibleKey = booleanPreferencesKey("player_disc_visible")
    private val beatEffectKey = booleanPreferencesKey("beat_effect")
    private val bottomEffectKey = booleanPreferencesKey("bottom_effect")
    private val lockScreenPlayerKey = booleanPreferencesKey("lock_screen_player")
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
    private val togetherConnectModeKey = stringPreferencesKey("together_connect_mode")
    private val togetherServerEnabledKey = booleanPreferencesKey("together_server_enabled")
    private val togetherServerAddressKey = stringPreferencesKey("together_server_address")

    val folders: Flow<Set<String>> = context.playbackDataStore.data.map { it[foldersKey].orEmpty() }
    val theme: Flow<ThemeAccent> = context.playbackDataStore.data.map { values ->
        if (values[themeKey] == "Custom") ThemeAccent.custom(values[customThemeColorKey] ?: ThemeAccent.Blue.argb)
        else runCatching { ThemeAccent.valueOf(values[themeKey] ?: ThemeAccent.Blue.name) }.getOrDefault(ThemeAccent.Blue)
    }
    val playerBackground: Flow<String> = context.playbackDataStore.data.map { it[playerBackgroundKey].orEmpty() }
    val albumArtworkBackground: Flow<Boolean> = context.playbackDataStore.data.map { it[albumArtworkBackgroundKey] ?: false }
    val playerTheme: Flow<PlayerTheme> = context.playbackDataStore.data.map { values ->
        PlayerTheme.fromStored(values[playerThemeKey])
    }
    val font: Flow<AppFont> = context.playbackDataStore.data.map { values -> AppFont.fromStored(values[fontKey]) }
    val downloadedFont: Flow<String> = context.playbackDataStore.data.map { it[downloadedFontKey].orEmpty() }
    val lyricsMode: Flow<LyricsMode> = context.playbackDataStore.data.map { LyricsMode.fromStored(it[lyricsModeKey]) }
    val floatingButtonStyle: Flow<FloatingButtonStyle> = context.playbackDataStore.data.map { FloatingButtonStyle.fromStored(it[floatingButtonStyleKey]) }
    val playerDiscOpacity: Flow<Float> = context.playbackDataStore.data.map { it[playerDiscOpacityKey]?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.42f }
    val playerDiscVisible: Flow<Boolean> = context.playbackDataStore.data.map { it[playerDiscVisibleKey] ?: true }
    val beatEffect: Flow<Boolean> = context.playbackDataStore.data.map { it[beatEffectKey] ?: true }
    val bottomEffect: Flow<Boolean> = context.playbackDataStore.data.map { it[bottomEffectKey] ?: true }
    val lockScreenPlayer: Flow<Boolean> = context.playbackDataStore.data.map { it[lockScreenPlayerKey] ?: false }
    val onlineArtworkEnabled: Flow<Boolean> = context.playbackDataStore.data.map { it[onlineArtworkKey] ?: true }
    val onlineLyricsEnabled: Flow<Boolean> = context.playbackDataStore.data.map { it[onlineLyricsKey] ?: true }
    val fmModeEnabled: Flow<Boolean> = context.playbackDataStore.data.map { it[fmModeKey] ?: false }
    val karaokeModeEnabled: Flow<Boolean> = context.playbackDataStore.data.map { it[karaokeEnabledKey] ?: false }
    val onlineSearchEnabled: Flow<Boolean> = context.playbackDataStore.data.map { it[onlineSearchEnabledKey] ?: true }
    val onlinePlatform: Flow<String> = context.playbackDataStore.data.map { it[onlinePlatformKey] ?: "netease" }
    val onlineApiBase: Flow<String> = context.playbackDataStore.data.map { it[onlineApiBaseKey].orEmpty() }
    val downloadFolder: Flow<String> = context.playbackDataStore.data.map { it[downloadFolderKey].orEmpty() }
    val togetherConnectMode: Flow<TogetherConnectMode> = context.playbackDataStore.data.map { values ->
        runCatching { TogetherConnectMode.valueOf(values[togetherConnectModeKey] ?: TogetherConnectMode.MultiPath.name) }
            .getOrDefault(TogetherConnectMode.MultiPath)
    }
    val togetherServerEnabled: Flow<Boolean> = context.playbackDataStore.data.map { it[togetherServerEnabledKey] ?: false }
    val togetherServerAddress: Flow<String> = context.playbackDataStore.data.map { it[togetherServerAddressKey].orEmpty().trim() }

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
    suspend fun setCustomThemeColor(argb: Long) = context.playbackDataStore.edit {
        it[customThemeColorKey] = argb or 0xFF000000L
        it[themeKey] = "Custom"
    }
    suspend fun setPlayerTheme(theme: PlayerTheme) = context.playbackDataStore.edit { it[playerThemeKey] = theme.name }
    suspend fun setFont(font: AppFont) = context.playbackDataStore.edit { it[fontKey] = font.name }
    suspend fun setDownloadedFont(id: String?) = context.playbackDataStore.edit { values ->
        if (id.isNullOrBlank()) values.remove(downloadedFontKey) else values[downloadedFontKey] = id
    }
    suspend fun setLyricsMode(mode: LyricsMode) = context.playbackDataStore.edit { it[lyricsModeKey] = mode.name }
    suspend fun setFloatingButtonStyle(style: FloatingButtonStyle) = context.playbackDataStore.edit { it[floatingButtonStyleKey] = style.name }
    suspend fun setPlayerBackground(uri: String?) = context.playbackDataStore.edit { values ->
        if (uri.isNullOrBlank()) values.remove(playerBackgroundKey) else values[playerBackgroundKey] = uri
    }
    suspend fun setAlbumArtworkBackground(enabled: Boolean) = context.playbackDataStore.edit { it[albumArtworkBackgroundKey] = enabled }
    suspend fun setAppearanceBackground(albumArtwork: Boolean, image: String?) = context.playbackDataStore.edit { values ->
        values[albumArtworkBackgroundKey] = albumArtwork
        if (image.isNullOrBlank()) values.remove(playerBackgroundKey) else values[playerBackgroundKey] = image
    }
    suspend fun setPlayerDiscOpacity(opacity: Float) = context.playbackDataStore.edit { values ->
        values[playerDiscOpacityKey] = opacity.coerceIn(0f, 1f).toString()
    }
    suspend fun setPlayerDiscVisible(visible: Boolean) = context.playbackDataStore.edit { values -> values[playerDiscVisibleKey] = visible }
    suspend fun setBeatEffect(enabled: Boolean) = context.playbackDataStore.edit { it[beatEffectKey] = enabled }
    suspend fun setBottomEffect(enabled: Boolean) = context.playbackDataStore.edit { it[bottomEffectKey] = enabled }
    suspend fun setLockScreenPlayer(enabled: Boolean) = context.playbackDataStore.edit { it[lockScreenPlayerKey] = enabled }
    suspend fun setOnlineArtworkEnabled(enabled: Boolean) = context.playbackDataStore.edit { it[onlineArtworkKey] = enabled }
    suspend fun setOnlineLyricsEnabled(enabled: Boolean) = context.playbackDataStore.edit { it[onlineLyricsKey] = enabled }
    suspend fun setOnlineSearchEnabled(enabled: Boolean) = context.playbackDataStore.edit { it[onlineSearchEnabledKey] = enabled }
    suspend fun setOnlinePlatform(platform: String) = context.playbackDataStore.edit { it[onlinePlatformKey] = platform }
    suspend fun setOnlineApiBase(base: String) = context.playbackDataStore.edit { it[onlineApiBaseKey] = base.trim() }
    suspend fun setDownloadFolder(uri: String?) = context.playbackDataStore.edit { values ->
        if (uri.isNullOrBlank()) values.remove(downloadFolderKey) else values[downloadFolderKey] = uri
    }
    suspend fun setTogetherConnectMode(mode: TogetherConnectMode) = context.playbackDataStore.edit { it[togetherConnectModeKey] = mode.name }
    suspend fun currentTogetherConnectMode(): TogetherConnectMode {
        val value = context.playbackDataStore.data.first()[togetherConnectModeKey]
        return runCatching { TogetherConnectMode.valueOf(value ?: TogetherConnectMode.MultiPath.name) }.getOrDefault(TogetherConnectMode.MultiPath)
    }
    suspend fun setTogetherServerEnabled(enabled: Boolean) = context.playbackDataStore.edit { it[togetherServerEnabledKey] = enabled }
    suspend fun setTogetherServerAddress(address: String) = context.playbackDataStore.edit { it[togetherServerAddressKey] = address.trim() }
    suspend fun isTogetherServerEnabled(): Boolean = context.playbackDataStore.data.first()[togetherServerEnabledKey] ?: false
    suspend fun currentTogetherServerAddress(): String = context.playbackDataStore.data.first()[togetherServerAddressKey].orEmpty().trim()
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
@ConsistentCopyVisibility
data class ThemeAccent private constructor(val name: String, val argb: Long) {
    companion object {
        val Blue = ThemeAccent("Blue", 0xFF78A9FF)
        val Violet = ThemeAccent("Violet", 0xFFAE8CFF)
        val Rose = ThemeAccent("Rose", 0xFFFF7998)
        val Emerald = ThemeAccent("Emerald", 0xFF55D6A9)
        val Amber = ThemeAccent("Amber", 0xFFFFBE5C)
        val entries = listOf(Blue, Violet, Rose, Emerald, Amber)
        fun valueOf(name: String): ThemeAccent = entries.first { it.name == name }
        fun custom(argb: Long) = ThemeAccent("Custom", argb or 0xFF000000L)
    }
}

enum class LyricsMode(val title: String, val subtitle: String) {
    Random("随机", "每次切换歌曲随机应用一种歌词模式，连续两首不重复"),
    Normal("普通", "保留各播放外观原有的歌词效果"),
    Terminal("终端心跳", "主题色余辉 · 歌词逐字输入 · 光标跟随 · 历史日志向上提交"),
    NeonStorm("霓虹磁暴", "字符律动 · 游走电弧 · 拖动牵引，跟随主题配色"),
    DensityMap("密度地图", "整首歌词构图 · 连续轨道 · 当前句聚焦"),
    LyricRain("文字瀑布", "错落竖排字流 · 多句缓缓下落 · 中央逐字点亮"),
    BambooScroll("千字竹简", "从右向左竖排 · 竹简横向展开 · 主题色逐字染墨"),
    PopBounce("逐字弹跳", "当前歌词逐字弹入 · 弹出时高亮 · 完成后恢复"),
    LightTrail("流光逐字", "舒展弧线书写 · 正向短句轻弹 · 双句交替柔和淡出"),
    Sakura("落花", "枫叶缓缓飘落 · 四方汇聚成微倾歌词 · 保留三句向下滑动"),
    RiftFall("裂隙坠落", "歌词向右穿过中央裂缝 · 逐字失重旋转坠落"),
    Firework("烟花", "底部升空 · 字符绽放成句 · 唱过的歌词下移"),
    TypeMachine("活字机关", "文字逐字翻转 · 末字主题色 · 下一句镜面倒影"),
    DeepSea("深海气泡", "单字圆泡漂浮 · 原地破碎展开 · 唱完淡出归泡"),
    Stardust("星尘铸字", "空间粒子升空 · 汇聚成句 · 星尘下沉"),
    ForestLight("林间漏光", "透明扫光 · 散字慢聚 · 直线行列 · 主题色逐字点亮 · 双句浮动");

    companion object {
        fun fromStored(value: String?): LyricsMode = if (value == "Snake") Sakura else entries.firstOrNull { it.name == value } ?: Normal
    }
}

val LyricsMode.isSpatial: Boolean get() = this in setOf(LyricsMode.TypeMachine,
    LyricsMode.DeepSea, LyricsMode.Stardust, LyricsMode.ForestLight)

enum class PlayerTheme(val title: String, val subtitle: String) {
    Classic("经典播放器", "专辑光碟 · 原有界面"),
    Orbital("星际漫游", "星球轨道 · 长按召唤流星"),
    Theatre("歌词剧场", "逐句舞台 · 滑动探索歌词"),
    Liquid("液态光碟", "流彩 CD · 长按晕开色彩"),
    Tape("口袋磁带", "复古随身听 · 长按翻面");

    companion object {
        fun fromStored(value: String?): PlayerTheme = entries.firstOrNull { it.name == value } ?: Classic
    }
}

enum class FloatingButtonStyle(val title: String, val subtitle: String) {
    Classic("经典按钮", "简洁音乐符号，轻量省电"),
    Breathing("呼吸光晕", "播放时柔和呼吸，暂停后静止"),
    Spectrum("频谱环", "外圈随节拍律动，强化音乐感"),
    Liquid("液态气泡", "渐变形变与水波扩散效果"),
    Magnetic("磁吸胶囊", "靠近边缘时压缩并贴合屏幕"),
    Playback("播放状态", "用播放、暂停和加载状态表达当前状态"),
    IceSnowflake("冰晶雪花", "六向冰晶缓缓旋转 · 点击晶枝舒展"),
    StardustWreath("星尘花环", "彩色星尘错速环绕 · 点击散成星雨"),
    MiniWormhole("微型虫洞", "紫青涡环交错吞吐 · 点击核心收缩"),
    VinylKnob("黑胶旋钮", "细密黑胶纹理 · 桃色唱片标 · 播放时旋转");

    companion object {
        fun fromStored(value: String?): FloatingButtonStyle = entries.firstOrNull { it.name == value } ?: Breathing
    }
}

enum class AppFont(val title: String, val subtitle: String) {
    Default("系统无衬线", "清晰耐看，适合长时间听歌"),
    Serif("优雅衬线", "更有杂志感的音乐文字"),
    Monospace("复古等宽", "节拍感与终端风格"),
    Cursive("轻盈手写", "柔和、随性的氛围字体"),
    Rounded("幼圆圆体", "内置圆体字形，圆润亲和"),
    Lishu("隶书书法", "内置书法字形，古朴舒展");

    companion object {
        fun fromStored(value: String?): AppFont = entries.firstOrNull { it.name == value } ?: Default
    }
}
