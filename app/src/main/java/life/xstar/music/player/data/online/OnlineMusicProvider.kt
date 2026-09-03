package life.xstar.music.player.data.online

data class OnlinePlatform(val id: String, val label: String)

val supportedOnlinePlatforms = listOf(
    OnlinePlatform("netease", "网易云"),
    OnlinePlatform("tencent", "QQ音乐"),
    OnlinePlatform("kugou", "酷狗"),
    OnlinePlatform("kuwo", "酷我"),
    OnlinePlatform("migu", "咪咕")
)

fun platformLabel(id: String): String = supportedOnlinePlatforms.firstOrNull { it.id == id }?.label ?: id

data class OnlineTrack(
    val id: String,
    val platform: String,
    val title: String,
    val artist: String,
    val album: String,
    val picId: String? = null
) {
    val onlineId: String get() = "$platform:$id"
}

data class OnlineResolvedAudio(
    val url: String,
    val br: Int,
    val size: Long,
    val mimeType: String
)

interface OnlineMusicProvider {
    val platform: String
    suspend fun search(keyword: String, limit: Int): List<OnlineTrack>
    suspend fun resolveAudio(trackId: String): OnlineResolvedAudio?
    suspend fun albumPic(trackId: String): String?
}
