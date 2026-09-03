package life.xstar.music.player.cast

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

class SsdpDiscovery(private val context: Context) {
    companion object {
        private const val TAG = "SsdpDiscovery"
        private const val SSDP_HOST = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private const val RENDERER_TYPE = "urn:schemas-upnp-org:device:MediaRenderer:1"
        private const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
        private const val SOAP_TIMEOUT = 3_000
    }

    suspend fun discover(): List<CastDevice> = withContext(Dispatchers.IO) {
        val applicationContext = context.applicationContext
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val multicastLock = wifiManager.createMulticastLock("xstar_ssdp_discovery")
        multicastLock.setReferenceCounted(false)
        val found = ConcurrentHashMap<String, CastDevice>()
        var socket: java.net.MulticastSocket? = null
        try {
            multicastLock.acquire()
            socket = java.net.MulticastSocket().apply {
                reuseAddress = true
                soTimeout = 300
                joinGroup(InetAddress.getByName(SSDP_HOST))
            }
            val search = buildSearchMessage()
            val group = InetAddress.getByName(SSDP_HOST)
            val searchPacket = java.net.DatagramPacket(search, search.size, group, SSDP_PORT)

            var deadline = System.currentTimeMillis() + 4_500
            var probe = 0
            val buffer = ByteArray(32 * 1024)
            while (System.currentTimeMillis() < deadline) {
                if (probe < 3) {
                    try { socket?.send(searchPacket) } catch (ignore: Exception) { }
                    probe++
                }
                try {
                    val packet = java.net.DatagramPacket(buffer, buffer.size)
                    socket?.receive(packet)
                    val message = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    parseSsdpMessage(message, packet.address.hostAddress)?.let { location ->
                        if (found.none { it.value.location == location }) {
                            val device = fetchDeviceDescription(location)
                            if (device != null && device.supportsAvTransport && found.none { it.value.udn == device.udn }) {
                                found[device.udn] = device
                            }
                        }
                    }
                } catch (timeout: java.net.SocketTimeoutException) {
                    // expected, poll again
                } catch (end: java.net.SocketException) {
                    if (!currentCoroutineContext().isActive) break
                } catch (e: Exception) {
                    Log.w(TAG, "receive loop error: ${e.message}")
                }
            }
            found.values.toList()
        } catch (e: Exception) {
            Log.w(TAG, "discovery failed: ${e.message}")
            emptyList()
        } finally {
            runCatching { socket?.leaveGroup(InetAddress.getByName(SSDP_HOST)) }
            runCatching { socket?.close() }
            if (multicastLock.isHeld) multicastLock.release()
        }
    }

    private fun buildSearchMessage(): ByteArray {
        val message = buildString {
            append("M-SEARCH * HTTP/1.1\r\n")
            append("HOST: $SSDP_HOST:$SSDP_PORT\r\n")
            append("MAN: \"ssdp:discover\"\r\n")
            append("MX: 3\r\n")
            append("ST: $RENDERER_TYPE\r\n")
            append("\r\n")
        }
        return message.toByteArray(Charsets.UTF_8)
    }

    private fun parseSsdpMessage(message: String, sourceAddress: String?): String? {
        val headers = parseHeaders(message)
        val location = headers["location"] ?: return null
        val st = headers["st"] ?: headers["nt"] ?: return null
        val isNotifyAlive = headers["nt"] != null && headers["nts"] != null && !headers["nts"]!!.contains("byebye", true)
        val isSearchResponse = headers["st"] != null
        if (!isNotifyAlive && !isSearchResponse) return null
        if (!st.contains("MediaRenderer", true) && !st.contains("AVTransport", true)) return null
        if (!location.startsWith("http://") && !location.startsWith("https://")) return null
        return runCatching { URL(location) }.getOrNull()?.toString() ?: location
    }

    private fun parseHeaders(message: String): Map<String, String> {
        val lines = message.split("\r\n")
        if (lines.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, String>()
        lines.drop(1).forEach { line ->
            val index = line.indexOf(':')
            if (index > 0) {
                val key = line.substring(0, index).trim().lowercase()
                val value = line.substring(index + 1).trim()
                result[key] = value
            }
        }
        return result
    }

    private fun fetchDeviceDescription(location: String): CastDevice? = runCatching {
        val connection = URL(location).openConnection() as HttpURLConnection
        connection.connectTimeout = SOAP_TIMEOUT
        connection.readTimeout = SOAP_TIMEOUT
        connection.setRequestProperty("User-Agent", "XSTAR/0.1.0 (upnp)")
        connection.setRequestProperty("Accept", "text/xml")
        val xml = try {
            if (connection.responseCode !in 200..299) return@runCatching null
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
        val doc = parseDeviceXml(xml) ?: return@runCatching null
        val url = URL(location)
        val baseUrl = "${url.protocol}://${url.host}:${if (url.port > 0) url.port else url.defaultPort}"
        val av = doc.services.firstOrNull { it.serviceType.contains("AVTransport", true) }
        CastDevice(
            udn = doc.udn.ifBlank { url.toString() },
            friendlyName = doc.friendlyName.ifBlank { url.host ?: "未知设备" },
            deviceType = doc.deviceType,
            modelName = doc.modelName,
            location = location,
            baseUrl = baseUrl,
            controlUrl = av?.controlUrl?.let { resolveUrl(baseUrl, it) },
            eventSubUrl = av?.eventSubUrl?.let { resolveUrl(baseUrl, it) }
        )
    }.getOrNull()

    private fun resolveUrl(baseUrl: String, path: String): String? {
        if (path.isBlank()) return null
        return if (path.startsWith("http://") || path.startsWith("https://")) path
        else runCatching { URL(URL(baseUrl), path).toString() }.getOrNull()
    }

    private data class DeviceXml(
        val udn: String,
        val friendlyName: String,
        val deviceType: String,
        val modelName: String?,
        val services: List<ServiceXml>
    )
    private data class ServiceXml(val serviceType: String, val controlUrl: String?, val eventSubUrl: String?)

    private fun parseDeviceXml(xml: String): DeviceXml? = runCatching {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xml))
        var udn = ""
        var friendlyName = ""
        var deviceType = ""
        var modelName: String? = null
        var rootDeviceSeen = false
        var insideRootDevice = false
        val services = mutableListOf<ServiceXml>()
        var currentTag: String? = null
        var inService = false
        var serviceType = ""
        var controlUrl: String? = null
        var eventSubUrl: String? = null
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    val tag = parser.name
                    if (tag == "device") {
                        if (!rootDeviceSeen) {
                            rootDeviceSeen = true
                            insideRootDevice = true
                        } else if (insideRootDevice) {
                            return@runCatching null
                        }
                    }
                    if (insideRootDevice) {
                        when (tag) {
                            "UDN" -> currentTag = "udn"
                            "friendlyName" -> currentTag = "friendlyName"
                            "deviceType" -> currentTag = "deviceType"
                            "modelName" -> currentTag = "modelName"
                            "service" -> { inService = true; serviceType = ""; controlUrl = null; eventSubUrl = null }
                            "serviceType" -> if (inService) currentTag = "serviceType"
                            "controlURL" -> if (inService) currentTag = "controlURL"
                            "eventSubURL" -> if (inService) currentTag = "eventSubURL"
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    val text = parser.text?.trim().orEmpty()
                    when (currentTag) {
                        "udn" -> udn = text
                        "friendlyName" -> friendlyName = text
                        "deviceType" -> deviceType = text
                        "modelName" -> modelName = text
                        "serviceType" -> serviceType = text
                        "controlURL" -> controlUrl = text
                        "eventSubURL" -> eventSubUrl = text
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "service" -> if (inService) {
                            inService = false
                            if (serviceType.contains("AVTransport", true)) services += ServiceXml(serviceType, controlUrl, eventSubUrl)
                        }
                        "device" -> if (insideRootDevice && rootDeviceSeen) { insideRootDevice = false }
                    }
                    currentTag = null
                }
            }
            parser.next()
        }
        DeviceXml(udn, friendlyName, deviceType, modelName, services)
    }.getOrNull()
}
