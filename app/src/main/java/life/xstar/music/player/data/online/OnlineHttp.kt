package life.xstar.music.player.data.online

import java.net.HttpURLConnection
import java.net.URL

internal object OnlineHttp {
    private const val CONNECT_TIMEOUT = 10_000
    private const val READ_TIMEOUT = 12_000

    fun getText(address: String): String? = runCatching {
        val connection = open(address) ?: return@runCatching null
        try {
            if (connection.responseCode !in 200..299) null
            else connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    fun getBytes(address: String): ByteArray? = runCatching {
        val connection = open(address) ?: return@runCatching null
        try {
            if (connection.responseCode !in 200..299) null
            else connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    private fun open(address: String): HttpURLConnection? = runCatching {
        (URL(address).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            setRequestProperty("User-Agent", "XSTAR/0.1.0 (online-download)")
            setRequestProperty("Accept", "*/*")
        }
    }.getOrNull()
}
