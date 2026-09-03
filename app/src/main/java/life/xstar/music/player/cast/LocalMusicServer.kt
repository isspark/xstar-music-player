package life.xstar.music.player.cast

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class LocalMusicServer(private val context: Context) {
    data class StreamSpec(
        val id: Long,
        val uri: String,
        val mime: String?,
        val fileSize: Long?
    )

    companion object {
        private const val TAG = "LocalMusicServer"
    }

    private val specs = ConcurrentHashMap<String, StreamSpec>()
    private var serverSocket: ServerSocket? = null
    private var acceptorThread: Thread? = null
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "xstar-http").apply { isDaemon = true }
    }
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)

    val port: Int get() = serverSocket?.localPort ?: 0

    fun start(): Boolean {
        if (running.get()) return true
        return try {
            serverSocket = ServerSocket(0).apply { reuseAddress = true }
            running.set(true)
            acceptorThread = Thread({ acceptLoop() }, "xstar-http-acceptor").apply {
                isDaemon = true
                start()
            }
            Log.i(TAG, "local music server started on port $port")
            true
        } catch (e: Exception) {
            Log.w(TAG, "failed to start http server: ${e.message}")
            false
        }
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        specs.clear()
    }

    fun register(spec: StreamSpec): String? {
        if (!running.get()) start()
        val token = UUID.randomUUID().toString().replace("-", "")
        specs[token] = spec
        val ip = localIpV4() ?: return null
        return "http://$ip:$port/stream?t=$token"
    }

    fun unregister(id: Long) {
        val token = specs.entries.firstOrNull { it.value.id == id }?.key ?: return
        specs.remove(token)
    }

    fun resolve(token: String): StreamSpec? = specs[token]

    fun localIpV4(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().flatMap { iface ->
            if (iface.isLoopback || !iface.isUp) emptyList() else iface.inetAddresses.toList()
        }.filterIsInstance<Inet4Address>().firstOrNull { !it.isLoopbackAddress }?.hostAddress
    }.getOrNull()

    private fun acceptLoop() {
        while (running.get()) {
            val socket = runCatching { serverSocket?.accept() }.getOrNull() ?: run {
                if (running.get()) Thread.sleep(20)
                continue
            }
            executor.execute { handleConnection(socket) }
        }
    }

    private fun handleConnection(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 60_000
            val input = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val output = client.getOutputStream()
            val requestLine = runCatching { input.readLine() }.getOrNull() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val pathAndQuery = parts[1]
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = runCatching { input.readLine() }.getOrNull() ?: break
                if (line.isNullOrEmpty()) break
                val index = line.indexOf(':')
                if (index > 0) headers[line.substring(0, index).trim().lowercase()] = line.substring(index + 1).trim()
            }
            val (path, query) = pathAndQuery.split("?").let { if (it.size == 1) it[0] to null else it[0] to it[1] }
            val token = query?.split("&")?.firstOrNull { it.startsWith("t=") }?.substring(2)
            val spec = token?.let { specs[it] }

            when {
                spec == null -> respond(output, "404 Not Found", null, byteArrayOf())
                method == "HEAD" -> respondHead(output, spec)
                method == "GET" -> respondStream(output, spec, headers["range"])
                else -> respond(output, "405 Method Not Allowed", null, byteArrayOf())
            }
        }
    }

    private fun respondHead(output: java.io.OutputStream, spec: StreamSpec) {
        val length = spec.fileSize ?: queryLength(spec.uri)
        val headers = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: ${contentType(spec)}\r\n")
            if (length != null) append("Content-Length: $length\r\n")
            append("Accept-Ranges: bytes\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(headers.toByteArray(Charsets.ISO_8859_1))
        output.flush()
    }

    private fun respondStream(output: java.io.OutputStream, spec: StreamSpec, rangeHeader: String?) {
        val total = spec.fileSize ?: queryLength(spec.uri)
        val range = total?.let { parseRange(rangeHeader, it) }
        val inputStream = runCatching {
            context.contentResolver.openInputStream(Uri.parse(spec.uri))
        }.getOrNull()
        if (inputStream == null) {
            respond(output, "404 Not Found", null, byteArrayOf())
            return
        }
        inputStream.use { input ->
            if (range != null && range.first <= range.second && range.first < (total ?: Long.MAX_VALUE)) {
                var skipped = 0L
                val skipTarget = range.first
                while (skipped < skipTarget) {
                    val skippedNow = input.skip(skipTarget - skipped)
                    if (skippedNow <= 0) break
                    skipped += skippedNow
                }
                val length = range.second - range.first + 1
                val header = buildString {
                    append("HTTP/1.1 206 Partial Content\r\n")
                    append("Content-Type: ${contentType(spec)}\r\n")
                    append("Content-Length: $length\r\n")
                    append("Content-Range: bytes ${range.first}-${range.second}/${total ?: "*"}\r\n")
                    append("Accept-Ranges: bytes\r\n")
                    append("Connection: close\r\n\r\n")
                }
                output.write(header.toByteArray(Charsets.ISO_8859_1))
                output.flush()
                input.copyTo(output)
            } else {
                val header = buildString {
                    append("HTTP/1.1 200 OK\r\n")
                    append("Content-Type: ${contentType(spec)}\r\n")
                    if (total != null) append("Content-Length: $total\r\n")
                    append("Accept-Ranges: bytes\r\n")
                    append("Connection: close\r\n\r\n")
                }
                output.write(header.toByteArray(Charsets.ISO_8859_1))
                output.flush()
                input.copyTo(output)
            }
            output.flush()
        }
    }

    private fun respond(output: java.io.OutputStream, status: String, contentType: String?, body: ByteArray) {
        val header = buildString {
            append("HTTP/1.1 $status\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(header.toByteArray(Charsets.ISO_8859_1))
        output.write(body)
        output.flush()
    }

    private fun parseRange(header: String?, total: Long): Pair<Long, Long>? {
        if (header == null) return null
        val match = Regex("bytes=(\\d*)-(\\d*)").find(header) ?: return null
        val startText = match.groupValues[1]
        val endText = match.groupValues[2]
        val start = startText.toLongOrNull() ?: return null
        val end = if (endText.isNotBlank()) endText.toLong().coerceAtMost(total - 1) else total - 1
        return start to end
    }

    private fun queryLength(uri: String): Long? = runCatching {
        context.contentResolver.query(Uri.parse(uri), arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }
    }.getOrNull()

    private fun contentType(spec: StreamSpec): String = when {
        !spec.mime.isNullOrBlank() -> spec.mime
        spec.uri.contains(".flac", true) -> "audio/flac"
        spec.uri.contains(".m4a", true) || spec.uri.contains(".aac", true) -> "audio/mp4"
        spec.uri.contains(".wav", true) -> "audio/wav"
        spec.uri.contains(".ogg", true) || spec.uri.contains(".opus", true) -> "audio/ogg"
        else -> "audio/mpeg"
    }
}
