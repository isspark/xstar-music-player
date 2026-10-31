package life.xstar.music.player.data.artwork

import java.net.HttpURLConnection
import java.net.URL

internal object ArtworkHttp {
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    private const val CONNECT_TIMEOUT = 6_000
    private const val READ_TIMEOUT = 8_000

    fun getText(address: String, headers: Map<String, String> = emptyMap()): String? = request(address, headers) { connection ->
        connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    fun getBytes(address: String, headers: Map<String, String> = emptyMap()): ByteArray? = request(address, headers) { connection ->
        connection.inputStream.use { it.readBytes() }
    }

    private fun <T> request(address: String, headers: Map<String, String>, read: (HttpURLConnection) -> T): T? = runCatching {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "*/*")
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }
        try {
            if (connection.responseCode !in 200..299) null else read(connection)
        } finally {
            connection.disconnect()
        }
    }.getOrNull()
}
