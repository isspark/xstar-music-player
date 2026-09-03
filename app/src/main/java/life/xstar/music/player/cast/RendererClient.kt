package life.xstar.music.player.cast

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL

class RendererClient(private val device: CastDevice) {
    companion object {
        private const val TAG = "RendererClient"
        private const val AV_NAMESPACE = "urn:schemas-upnp-org:service:AVTransport:1"
        private const val SOAP_NS = "http://schemas.xmlsoap.org/soap/envelope/"
        private const val TIMEOUT = 8_000

        enum class TransportState { Stopped, Playing, Paused, Transitioning, NoMediaPresent, Unknown }
    }

    private val controlUrl: String
        get() = device.controlUrl ?: throw IllegalStateException("device has no AVTransport control URL")

    suspend fun setTransportUri(uri: String, metadata: String = ""): Boolean = invokeAction("SetAVTransportURI", """
        <InstanceID>0</InstanceID>
        <CurrentURI>${xmlEscape(uri)}</CurrentURI>
        <CurrentURIMetaData>${xmlEscape(metadata)}</CurrentURIMetaData>
    """.trimIndent())

    suspend fun play(): Boolean = invokeAction("Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")
    suspend fun pause(): Boolean = invokeAction("Pause", "<InstanceID>0</InstanceID>")
    suspend fun stop(): Boolean = invokeAction("Stop", "<InstanceID>0</InstanceID>")

    suspend fun seek(seconds: Long): Boolean {
        val relTime = formatTime(seconds)
        return invokeAction("Seek", "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>$relTime</Target>")
    }

    suspend fun transportState(): Companion.TransportState = withContext(Dispatchers.IO) {
        val xml = invoke("GetTransportInfo", "<InstanceID>0</InstanceID>") ?: return@withContext Companion.TransportState.Unknown
        when (extractText(xml, "CurrentTransportState")) {
            "STOPPED" -> Companion.TransportState.Stopped
            "PLAYING" -> Companion.TransportState.Playing
            "PAUSED_PLAYBACK" -> Companion.TransportState.Paused
            "TRANSITIONING" -> Companion.TransportState.Transitioning
            "NO_MEDIA_PRESENT" -> Companion.TransportState.NoMediaPresent
            else -> Companion.TransportState.Unknown
        }
    }

    suspend fun positionInfo(): PositionInfo = withContext(Dispatchers.IO) {
        val xml = invoke("GetPositionInfo", "<InstanceID>0</InstanceID>") ?: return@withContext PositionInfo(0, 0, null)
        val trackDuration = extractText(xml, "TrackDuration")?.toDurationMs() ?: 0
        val relTime = extractText(xml, "RelTime")?.toDurationMs() ?: 0
        val trackUri = extractText(xml, "TrackURI")?.takeIf { it.isNotBlank() }
        PositionInfo(relTime, trackDuration, trackUri)
    }

    data class PositionInfo(val positionMs: Long, val durationMs: Long, val trackUri: String?)

    private suspend fun invokeAction(action: String, arguments: String): Boolean = withContext(Dispatchers.IO) {
        val xml = invoke(action, arguments)
        if (xml == null) {
            Log.w(TAG, "soap $action failed on ${device.friendlyName}")
            false
        } else {
            extractFault(xml) == null
        }
    }

    private suspend fun invoke(action: String, arguments: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val body = """
                <?xml version="1.0" encoding="utf-8"?>
                <s:Envelope xmlns:s="$SOAP_NS" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                  <s:Body>
                    <u:$action xmlns:u="$AV_NAMESPACE">$arguments</u:$action>
                  </s:Body>
                </s:Envelope>
            """.trimIndent()
            val connection = URL(controlUrl).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = TIMEOUT
            connection.readTimeout = TIMEOUT
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            connection.setRequestProperty("SOAPACTION", "\"$AV_NAMESPACE#$action\"")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val result = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            connection.disconnect()
            if (code in 200..299) result else null
        }.getOrNull()
    }

    private fun extractFault(xml: String): String? = runCatching {
        val parser = newParser(xml)
        var faultText: String? = null
        var current: String? = null
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> if (parser.name == "faultstring" || parser.name == "errorDescription") current = parser.name
                XmlPullParser.TEXT -> if (current != null) faultText = parser.text?.trim().orEmpty().ifBlank { faultText }
                XmlPullParser.END_TAG -> current = null
            }
            parser.next()
        }
        faultText
    }.getOrNull()

    private fun extractText(xml: String, tag: String): String? = runCatching {
        val parser = newParser(xml)
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

    private fun newParser(xml: String) = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }
        .newPullParser().apply { setInput(StringReader(xml)) }

    private fun xmlEscape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun String.toDurationMs(): Long? {
        val cleaned = trim()
        val match = Regex("^(\\d{1,2}):(\\d{1,2}):(\\d{1,2})(?:[.,](\\d{1,3}))?$").find(cleaned)
            ?: Regex("^(\\d{1,2}):(\\d{1,2})(?:[.,](\\d{1,3}))?$").find(cleaned)
            ?: return null
        val groups = match.groupValues
        val hours = if (groups.size > 3) groups[1].toLongOrNull() ?: 0 else 0
        val minutesIndex = if (groups.size > 3) 2 else 1
        val secondsIndex = if (groups.size > 3) 3 else 2
        val fractionIndex = if (groups.size > 3) 4 else 3
        val minutes = groups[minutesIndex].toLongOrNull() ?: 0
        val seconds = groups[secondsIndex].toLongOrNull() ?: 0
        val fractionText = if (fractionIndex < groups.size) groups[fractionIndex] else ""
        val millis = when (fractionText.length) {
            1 -> fractionText.toLongOrNull()?.times(100) ?: 0
            2 -> fractionText.toLongOrNull()?.times(10) ?: 0
            else -> fractionText.toLongOrNull() ?: 0
        }
        return ((hours * 3600 + minutes * 60 + seconds) * 1000 + millis)
    }

    private fun formatTime(seconds: Long): String {
        val safe = seconds.coerceAtLeast(0)
        val h = safe / 3600
        val m = safe % 3600 / 60
        val s = safe % 60
        return "%02d:%02d:%02d".format(h, m, s)
    }
}
