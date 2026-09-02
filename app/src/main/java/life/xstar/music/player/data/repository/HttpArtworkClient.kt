package life.xstar.music.player.data.repository

import java.net.HttpURLConnection
import java.net.URL

object HttpArtworkClient {
    fun getText(address: String): String? = runCatching {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 12_000
            setRequestProperty("User-Agent", "XSTAR/0.1.0 (local-player)")
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (connection.responseCode !in 200..299) null
            else connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()
}
