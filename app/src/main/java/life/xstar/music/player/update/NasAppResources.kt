package life.xstar.music.player.update

import android.content.Context
import okhttp3.Credentials
import okhttp3.Request

/** Shared endpoint and credentials for application updates and optional resources. */
internal class NasAppResources(context: Context) {
    private val credentials = UpdateCredentialStore(context)

    fun request(path: String): Request.Builder {
        require(path.startsWith("/App/") && !path.contains("..") && !path.contains('?') && !path.contains('#'))
        return Request.Builder().url(BASE_URL + path)
            .header("Authorization", Credentials.basic(credentials.username(), credentials.password()))
    }

    companion object {
        const val BASE_URL = "https://dav.xstar-life.fnos.net:443"
    }
}
