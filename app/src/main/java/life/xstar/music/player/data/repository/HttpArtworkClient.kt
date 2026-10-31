package life.xstar.music.player.data.repository

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.delay

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

    suspend fun getTextRetry(address: String, attempts: Int = 3): String? {
        var result: String? = null
        repeat(attempts.coerceAtLeast(1)) { attempt ->
            result = getText(address)
            if (result != null) return result
            if (attempt + 1 < attempts) delay(300L shl attempt)
        }
        return result
    }
}
