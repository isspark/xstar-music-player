package life.xstar.music.player.together

import android.content.Context
import android.net.wifi.WifiManager
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext

internal class TogetherLink internal constructor(private val socket: Socket) {
    private val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
    private val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
    private val writeLock = Any()

    @Volatile
    var lastActivity: Long = System.currentTimeMillis()
        private set

    @Volatile
    var closed: Boolean = false
        private set

    val remote: String get() = runCatching { socket.inetAddress?.hostAddress ?: "" }.getOrDefault("")

    fun read(): TogetherMessage? {
        val msg = runCatching {
            val length = input.readInt()
            if (length <= 0 || length > MAX_FRAME) return null
            val bytes = ByteArray(length)
            input.readFully(bytes)
            TogetherProtocol.decodeMessage(bytes)
        }.onFailure {
            android.util.Log.w("TogetherLink", "read failed: ${it.javaClass.simpleName}: ${it.message}")
        }.getOrNull()
        if (msg != null) lastActivity = System.currentTimeMillis()
        return msg
    }

    suspend fun write(message: TogetherMessage): Boolean = withContext(Dispatchers.IO) {
        synchronized(writeLock) {
            val ok = runCatching {
                val bytes = TogetherProtocol.encodeMessage(message)
                output.writeInt(bytes.size)
                output.write(bytes)
                output.flush()
                true
            }.onFailure {
                android.util.Log.w("TogetherLink", "write ${message::class.simpleName} failed: ${it.javaClass.simpleName}: ${it.message}")
            }.getOrDefault(false)
            if (ok) lastActivity = System.currentTimeMillis()
            ok
        }
    }

    fun close() {
        closed = true
        runCatching { socket.close() }
    }

    companion object {
        private const val MAX_FRAME = 4 * 1024 * 1024

        fun acceptOnce(server: ServerSocket): TogetherLink? = runCatching {
            TogetherLink(server.accept().apply { tcpNoDelay = true })
        }.getOrNull()

        fun connect(host: String, port: Int, timeoutMs: Int = 4_000): TogetherLink? = runCatching {
            val socket = Socket()
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            socket.tcpNoDelay = true
            TogetherLink(socket)
        }.onFailure {
            android.util.Log.i("TogetherLink", "connect $host:$port failed: ${it.javaClass.simpleName}: ${it.message}")
        }.getOrNull()
    }
}

internal class TogetherListener {
    val server: ServerSocket = ServerSocket(0).apply { reuseAddress = true }
    val port: Int get() = server.localPort

    fun acceptBlocking(): TogetherLink? = TogetherLink.acceptOnce(server)

    fun close() {
        runCatching { server.close() }
    }
}

object TogetherDiscovery {
    private const val TAG = "TogetherDiscovery"
    private const val GROUP = "239.255.255.250"
    private const val PORT = 51999
    private const val PROTO = "XSTAR-TOGETHER-V1"
    private const val LOCATE = "XSTAR-TOGETHER-LOCATE"

    private class MulticastHandle(val socket: MulticastSocket, val lock: WifiManager.MulticastLock) {
        fun release() {
            runCatching { socket.leaveGroup(InetAddress.getByName(GROUP)) }
            runCatching { socket.close() }
            runCatching { if (lock.isHeld) lock.release() }
        }
    }

    private fun buildMulticast(context: Context): MulticastHandle? = runCatching {
        val manager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = manager.createMulticastLock("xstar_together_mcast")
        lock.setReferenceCounted(false)
        lock.acquire()
        val socket = MulticastSocket(PORT)
        socket.reuseAddress = true
        socket.joinGroup(InetAddress.getByName(GROUP))
        socket.timeToLive = 2
        MulticastHandle(socket, lock)
    }.getOrNull()

    fun advertiseAsync(context: Context, invite: TogetherInvite, stop: () -> Boolean): Thread =
        Thread({
            val payload = TogetherProtocol.base64(TogetherProtocol.encodeInvite(invite))
            val line = "$PROTO code=${invite.code} payload=$payload\r\n"
            val handle = buildMulticast(context)
            if (handle == null) return@Thread
            val group = runCatching { InetAddress.getByName(GROUP) }.getOrNull()
            if (group == null) {
                handle.release()
                return@Thread
            }
            try {
                val bytes = line.toByteArray(Charsets.UTF_8)
                while (!stop()) {
                    runCatching {
                        handle.socket.send(DatagramPacket(bytes, bytes.size, group, PORT))
                    }
                    try {
                        Thread.sleep(1_500)
                    } catch (e: InterruptedException) {
                        break
                    }
                }
            } finally {
                handle.release()
            }
        }, "xstar-together-advertise").apply { isDaemon = true; start() }

    suspend fun locateInvites(context: Context, code: String, timeoutMs: Long = 3_500): List<TogetherInvite> =
        withContext(Dispatchers.IO) {
            val result = mutableListOf<TogetherInvite>()
            val seen = mutableSetOf<String>()
            val handle = buildMulticast(context) ?: return@withContext emptyList()
            val group = runCatching { InetAddress.getByName(GROUP) }.getOrNull()
            if (group == null) {
                handle.release()
                return@withContext emptyList()
            }
            try {
                val request = "$LOCATE code=$code\r\n".toByteArray(Charsets.UTF_8)
                var probe = 0
                val deadline = System.currentTimeMillis() + timeoutMs
                val buffer = ByteArray(16 * 1024)
                while (System.currentTimeMillis() < deadline) {
                    if (probe < 4) {
                        runCatching { handle.socket.send(DatagramPacket(request, request.size, group, PORT)) }
                        probe++
                    }
                    runCatching { handle.socket.soTimeout = 300 }
                    runCatching {
                        val packet = DatagramPacket(buffer, buffer.size)
                        handle.socket.receive(packet)
                        val text = String(packet.data, 0, packet.length, Charsets.UTF_8).trim()
                        val parsed = parseAnnounce(text)
                        if (parsed != null && parsed.code == code && seen.add(parsed.code + parsed.controlHost)) {
                            result += parsed
                        }
                    }
                }
            } finally {
                handle.release()
            }
            android.util.Log.i("TogetherDiscovery", "locateInvites code=$code found=${result.size}")
            result
        }

    private fun parseAnnounce(line: String): TogetherInvite? {
        if (!line.startsWith(PROTO)) return null
        val payload = line.substringAfter("payload=").trim().removeSuffix("\r").removeSuffix("\n")
        if (payload.isBlank()) return null
        return TogetherProtocol.decodeInvite(runCatching { TogetherProtocol.fromBase64(payload) }.getOrNull() ?: return null)
    }
}

fun localIpV4Fallback(): String? = runCatching {
    java.net.NetworkInterface.getNetworkInterfaces().toList().flatMap { iface ->
        if (iface.isLoopback || !iface.isUp) emptyList() else iface.inetAddresses.toList()
    }.filterIsInstance<java.net.Inet4Address>().firstOrNull { !it.isLoopbackAddress }?.hostAddress
}.getOrNull()

private fun isPrivateLanIp(ip: String): Boolean =
    ip.startsWith("192.168.") || ip.startsWith("10.") || (ip.startsWith("172.") && runCatching {
        val second = ip.substringAfter('.').substringBefore('.').toInt()
        second in 16..31
    }.getOrDefault(false))

private val cellularInterfaceNames = listOf("rmnet", "pdp", "ccmni", "wwan", "cell", "ppp", "clat", "tun", "vpn")

/** 优先返回 Wi-Fi 局域网 IPv4 地址，避免取到蜂窝/VPN 网卡的 IP。 */
fun preferredLanIpV4(): String? = runCatching {
    val candidates = java.net.NetworkInterface.getNetworkInterfaces().toList()
        .filter { !it.isLoopback && it.isUp }
        .flatMap { iface -> iface.inetAddresses.toList().map { iface.name.lowercase() to it } }
        .filter { (_, addr) -> addr is java.net.Inet4Address && !addr.isLoopbackAddress }
        .map { (name, addr) -> name to addr.hostAddress.orEmpty() }
    candidates.firstOrNull { (name, ip) -> name.contains("wlan") && isPrivateLanIp(ip) }?.second
        ?: candidates.firstOrNull { (name, ip) -> isPrivateLanIp(ip) && cellularInterfaceNames.none { name.contains(it) } }?.second
        ?: candidates.firstOrNull { (name, _) -> name.contains("wlan") }?.second
        ?: candidates.firstOrNull()?.second
}.getOrNull()

internal const val TUNNEL_MAX_REQUEST = 64 * 1024

/** Opens a control connection to the relay server after writing the handshake line. */
internal fun connectServerControl(host: String, port: Int, line: String, timeoutMs: Int = 5_000): TogetherLink? = runCatching {
    val socket = Socket()
    socket.connect(InetSocketAddress(host, port), timeoutMs)
    socket.tcpNoDelay = true
    val output = BufferedOutputStream(socket.getOutputStream())
    output.write((line + "\r\n").toByteArray(Charsets.UTF_8))
    output.flush()
    TogetherLink(socket)
}.getOrNull()

/** Reads bytes from [input] up to and including the first "\r\n\r\n". Returns null on EOF/overflow. */
private fun readUntilDoubleCrlf(input: InputStream): ByteArray? {
    val out = ByteArrayOutputStream()
    val tail = IntArray(4) { -1 }
    while (out.size() <= TUNNEL_MAX_REQUEST) {
        val b = runCatching { input.read() }.getOrNull() ?: return if (out.size() == 0) null else null
        if (b < 0) return null
        out.write(b)
        tail[0] = tail[1]; tail[1] = tail[2]; tail[2] = tail[3]; tail[3] = b
        if (tail[0] == '\r'.code && tail[1] == '\n'.code && tail[2] == '\r'.code && tail[3] == '\n'.code) {
            return out.toByteArray()
        }
    }
    return null
}

private fun writeTunnelFrame(output: java.io.OutputStream, chunk: ByteArray, length: Int) {
    synchronized(output) {
        val header = ByteArray(4)
        header[0] = (length ushr 24).toByte()
        header[1] = (length ushr 16).toByte()
        header[2] = (length ushr 8).toByte()
        header[3] = length.toByte()
        output.write(header)
        output.write(chunk, 0, length)
        output.flush()
    }
}

private fun writeTunnelEnd(output: java.io.OutputStream) {
    synchronized(output) {
        output.write(byteArrayOf(0, 0, 0, 0))
        output.flush()
    }
}

private fun tunnelError(status: String): ByteArray =
    ("HTTP/1.1 $status\r\nContent-Type: text/plain\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").toByteArray(Charsets.UTF_8)

/** Rewrites "/room/<code>/stream..." into "/stream..." so the origin server sees plain paths. */
private fun stripRoomPrefix(request: ByteArray, code: String): ByteArray {
    val prefix = "/room/$code"
    var index = -1
    for (i in request.indices) {
        if (request[i] == '\n'.code.toByte() && i + 1 < request.size && request[i + 1] == '\r'.code.toByte()) break
        if (request[i] == '\r'.code.toByte()) { index = i; break }
    }
    if (index <= 0) return request
    val headerText = String(request, 0, index, Charsets.UTF_8)
    if (!headerText.contains(prefix)) return request
    val rewritten = headerText.replace(prefix, "")
    val out = ByteArray(rewritten.length + (request.size - index))
    val head = rewritten.toByteArray(Charsets.UTF_8)
    head.copyInto(out, 0, 0, head.size)
    request.copyInto(out, head.size, index, request.size)
    return out
}

/**
 * Host-side relay tunnel pump. Keeps an outbound connection to the relay server
 * and, for every forwarded member HTTP request, dials the local [originPort]
 * LocalMusicServer on loopback and streams the full response back as a sequence
 * of [int32 len][chunk] frames terminated by a zero-length frame.
 */
fun startServerTunnelPump(
    host: String,
    port: Int,
    code: String,
    originPort: Int,
    stop: () -> Boolean
): Thread = Thread({
    val socket = runCatching {
        val s = Socket()
        s.connect(InetSocketAddress(host, port), 5_000)
        s.tcpNoDelay = true
        s
    }.getOrNull() ?: return@Thread
    try {
        val output = BufferedOutputStream(socket.getOutputStream())
        output.write(("TUNNEL $code\r\n").toByteArray(Charsets.UTF_8))
        output.flush()
        val input = socket.getInputStream()
        while (!stop()) {
            val request = readUntilDoubleCrlf(input) ?: break
            val ok = runCatching {
                val local = Socket("127.0.0.1", originPort)
                local.use { client ->
                    val originRequest = stripRoomPrefix(request, code)
                    client.getOutputStream().write(originRequest)
                    client.getOutputStream().flush()
                    val body = client.getInputStream()
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val n = body.read(buffer)
                        if (n < 0) break
                        if (!runCatching { writeTunnelFrame(output, buffer, n); true }.getOrDefault(false)) {
                            return@use false
                        }
                    }
                    true
                }
            }.getOrDefault(false)
            if (!ok) {
                runCatching { writeTunnelFrame(output, tunnelError("502 Bad Gateway"), tunnelError("502 Bad Gateway").size) }
            }
            runCatching { writeTunnelEnd(output) }
        }
    } finally {
        runCatching { socket.close() }
    }
}, "xstar-together-tunnel").apply { isDaemon = true; start() }
