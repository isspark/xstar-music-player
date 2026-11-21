package life.xstar.music.player.data.nas

import okhttp3.HttpUrl.Companion.toHttpUrl

internal object NasPaths {
    fun resolve(address: String, path: String): String {
        val base = (address.trimEnd('/') + "/").toHttpUrl()
        val target = base.resolve(path) ?: error("无效 NAS 路径")
        require(target.scheme == base.scheme && target.host == base.host && target.port == base.port &&
            target.username.isEmpty() && target.password.isEmpty() && target.query == null && target.fragment == null &&
            target.encodedPath.startsWith(base.encodedPath)) { "NAS 路径超出音乐目录" }
        return target.toString()
    }
}
