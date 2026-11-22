package life.xstar.music.player.update

import kotlinx.serialization.Serializable

@Serializable
data class AppUpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val forceUpdate: Boolean = false,
    val title: String = "发现新版本",
    val changelog: List<String> = emptyList(),
    val apkPath: String,
    val apkSize: Long = 0,
    val sha256: String = "",
    val publishedAt: String? = null
)
