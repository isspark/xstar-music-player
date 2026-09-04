package life.xstar.music.player.together

import android.content.Context
import android.net.wifi.WifiManager
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.DatagramPacket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

class NatProbe(private val context: Context) {
    private data class IgdService(val controlUrl: String, val serviceType: String)
    data class PublicEndpoint(val externalIp: String, val controlUrl: String, val serviceType: String)

    suspend fun discoverEndpoint(timeoutMs: Int = 3_500): PublicEndpoint? {
        val service = findIgd(timeoutMs) ?: return null
        val ip = externalIp(service.controlUrl, service.serviceType)
        if (ip.isNullOrBlank()) return null
        return PublicEndpoint(ip, service.controlUrl, service.serviceType)
    }

    suspend fun addMapping(endpoint: PublicEndpoint, protocol: String, externalPort: Int, internalIp: String, internalPort: Int): Boolean {
        val arguments = buildString {
            append("<NewRemoteHost></NewRemoteHost>")
            append("<NewExternalPort>$externalPort</NewExternalPort>")
            append("<NewProtocol>$protocol</NewProtocol>")
            append("<NewInternalPort>$internalPort</NewInternalPort>")
            append("<NewInternalClient>$internalIp</NewInternalClient>")
            append("<NewEnabled>1</NewEnabled>")
            append("<NewPortMappingDescription>xstar-together</NewPortMappingDescription>")
            append("<NewLeaseDuration>0</NewLeaseDuration>")
        }
        val body = soap(endpoint.controlUrl, endpoint.serviceType, "AddPortMapping", arguments)
        return body != null && !body.contains("errorCode")
    }

    private suspend fun externalIp(controlUrl: String, serviceType: String): String? {
        val body = soap(controlUrl, serviceType, "GetExternalIPAddress", "") ?: return null
        return extractText(body, "NewExternalIPAddress")
    }

    private suspend fun soap(controlUrl: String, serviceType: String, action: String, arguments: String): String? = withContextIO {
        val namespace = "urn:schemas-upnp-org:service:$serviceType#"
        val body = buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
            append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">")
            append("<s:Body><u:$action xmlns:u=\"$namespace\">")
            if (arguments.isNotBlank()) append(arguments)
            append("</u:$action></s:Body></s:Envelope>")
        }
        runCatching {
            val connection = URL(controlUrl).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 3_000
            connection.readTimeout = 3_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            connection.setRequestProperty("SOAPACTION", "\"$namespace$action\"")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val result = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            connection.disconnect()
            result
        }.getOrNull()
    }

    private suspend fun findIgd(timeoutMs: Int): IgdService? = withContextIO {
        val manager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = manager.createMulticastLock("xstar_upnp_igd")
        lock.setReferenceCounted(false)
        var socket: java.net.MulticastSocket? = null
        try {
            lock.acquire()
            socket = java.net.MulticastSocket().apply {
                reuseAddress = true
                soTimeout = 250
                joinGroup(InetAddress.getByName("239.255.255.250"))
            }
            val search = buildString {
                append("M-SEARCH * HTTP/1.1\r\n")
                append("HOST: 239.255.255.250:1900\r\n")
                append("MAN: \"ssdp:discover\"\r\n")
                append("MX: 2\r\n")
                append("ST: urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\n\r\n")
            }.toByteArray(Charsets.UTF_8)
            val group = InetAddress.getByName("239.255.255.250")
            val buffer = ByteArray(32 * 1024)
            val deadline = System.currentTimeMillis() + timeoutMs
            var probe = 0
            var found: IgdService? = null
            while (System.currentTimeMillis() < deadline && found == null) {
                if (probe < 3) {
                    runCatching { socket?.send(DatagramPacket(search, search.size, group, 1900)) }
                    probe++
                }
                runCatching {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket?.receive(packet)
                    val message = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    val location = parseLocation(message)
                    if (location != null) found = fetchIgd(location)
                }
            }
            found
        } finally {
            runCatching { socket?.leaveGroup(InetAddress.getByName("239.255.255.250")) }
            runCatching { socket?.close() }
            if (lock.isHeld) lock.release()
        }
    }

    private fun fetchIgd(location: String): IgdService? = runCatching {
        val connection = URL(location).openConnection() as HttpURLConnection
        connection.connectTimeout = 3_000
        connection.readTimeout = 3_000
        connection.setRequestProperty("User-Agent", "XSTAR/0.1.0 (upnp-igd)")
        val xml = try {
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
        val doc = parseDeviceXml(xml) ?: return null
        val url = URL(location)
        val base = "${url.protocol}://${url.host}:${if (url.port > 0) url.port else url.defaultPort}"
        val wan = doc.services.firstOrNull {
            it.serviceType.contains("WANIPConnection", true) || it.serviceType.contains("WANPPPConnection", true)
        } ?: return null
        val control = wan.controlUrl?.let { resolveUrl(base, it) } ?: return null
        IgdService(control, wan.serviceType)
    }.getOrNull()

    private fun parseLocation(message: String): String? {
        if (!message.contains("InternetGatewayDevice", true)) return null
        val headers = parseHeaders(message)
        val location = headers["location"] ?: return null
        return if (location.startsWith("http")) location else null
    }

    private fun parseHeaders(message: String): Map<String, String> {
        val lines = message.split("\r\n")
        if (lines.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, String>()
        lines.drop(1).forEach { line ->
            val index = line.indexOf(':')
            if (index > 0) result[line.substring(0, index).trim().lowercase()] = line.substring(index + 1).trim()
        }
        return result
    }

    private fun resolveUrl(base: String, path: String): String? {
        if (path.isBlank()) return null
        return if (path.startsWith("http://") || path.startsWith("https://")) path
        else runCatching { URL(URL(base), path).toString() }.getOrNull()
    }

    private fun parseDeviceXml(xml: String): DeviceXml? = runCatching {
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }.newPullParser()
        parser.setInput(StringReader(xml))
        val services = mutableListOf<ServiceXml>()
        var currentTag: String? = null
        var serviceType = ""
        var controlUrl: String? = null
        var inService = false
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "service" -> { inService = true; serviceType = ""; controlUrl = null }
                    "serviceType" -> if (inService) currentTag = "st"
                    "controlURL" -> if (inService) currentTag = "cu"
                }
                XmlPullParser.TEXT -> when (currentTag) {
                    "st" -> serviceType = parser.text?.trim().orEmpty()
                    "cu" -> controlUrl = parser.text?.trim()
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "service" && inService) {
                        inService = false
                        if (serviceType.isNotBlank()) services += ServiceXml(serviceType, controlUrl)
                    }
                    currentTag = null
                }
            }
            parser.next()
        }
        DeviceXml(services)
    }.getOrNull()

    private data class DeviceXml(val services: List<ServiceXml>)
    private data class ServiceXml(val serviceType: String, val controlUrl: String?)

    private fun extractText(xml: String, tag: String): String? = runCatching {
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }.newPullParser()
        parser.setInput(StringReader(xml))
        var inTarget = false
        var value: String? = null
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> if (parser.name == tag) inTarget = true
                XmlPullParser.TEXT -> if (inTarget) value = parser.text?.trim()
                XmlPullParser.END_TAG -> if (parser.name == tag) inTarget = false
            }
            parser.next()
        }
        value
    }.getOrNull()

    private suspend fun <T> withContextIO(block: () -> T): T =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { block() }
}
