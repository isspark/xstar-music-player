package life.xstar.music.player.data.font

import java.io.InputStream
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.xml.sax.InputSource
import org.xml.sax.SAXException

internal fun isFontFileName(name: String): Boolean = name.isNotBlank() &&
    name.none { it == '/' || it == '\\' || it.isISOControl() } && name != "." && name != ".." &&
    name.substringAfterLast('.').lowercase() in setOf("ttf", "otf")

internal fun fontFromFileName(name: String): DownloadableFont {
    require(isFontPath(name))
    return knownFonts.firstOrNull { it.fileName == name } ?: DownloadableFont(
        id = "nas:$name", name = name.substringAfterLast('/').substringBeforeLast('.'), description = "NAS 字体 · $name",
        urls = listOf("https://dav.xstar-life.fnos.net:443/App/Font/" +
            name.split('/').joinToString("/") { segment ->
                "https://dav.xstar-life.fnos.net/".toHttpUrl().newBuilder().addPathSegment(segment).build().encodedPath.removePrefix("/")
            }),
        fileName = name
    )
}

/** Only accept successful, direct font children of the configured directory. */
internal fun isFontPath(path: String): Boolean = path.split('/').all { segment ->
    segment.isNotBlank() && segment != "." && segment != ".." && segment.none { it == '\\' || it.isISOControl() }
} && isFontFileName(path.substringAfterLast('/'))

internal data class FontDirectoryListing(val fonts: List<DownloadableFont>, val directories: List<String>)

internal fun parseFontDirectory(xml: String): List<DownloadableFont> = parseFontListing(xml).fonts

internal fun parseFontListing(xml: String, directory: String = ""): FontDirectoryListing {
    require(!xml.contains("<!DOCTYPE", ignoreCase = true) && !xml.contains("<!ENTITY", ignoreCase = true))
    val builder = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
    builder.setEntityResolver { _, _ -> throw SAXException("External entities are not allowed") }
    val doc = builder.parse(InputSource(StringReader(xml)))
    require(doc.documentElement.localName == "multistatus" && doc.documentElement.namespaceURI == "DAV:")
    val base = "https://dav.xstar-life.fnos.net:443/App/Font/".toHttpUrl()
    val entries = doc.getElementsByTagNameNS("DAV:", "response")
    val directories = mutableListOf<String>()
    val parent = listOf("App", "Font") + directory.split('/').filter { it.isNotEmpty() }
    val fonts = (0 until entries.length).mapNotNull { index ->
        val element = entries.item(index) as org.w3c.dom.Element
        val collection = element.getElementsByTagNameNS("DAV:", "collection").length > 0
        val statuses = element.getElementsByTagNameNS("DAV:", "status")
        if ((0 until statuses.length).none { statuses.item(it).textContent.trim().split(' ').getOrNull(1) == "200" }) return@mapNotNull null
        val href = element.getElementsByTagNameNS("DAV:", "href").item(0)?.textContent ?: return@mapNotNull null
        val current = base.newBuilder().apply { directory.split('/').filter { it.isNotEmpty() }.forEach { addPathSegment(it) } }.addPathSegment("").build()
        val url = current.resolve(href) ?: return@mapNotNull null
        if (url.scheme != base.scheme || url.host != base.host || url.port != base.port || url.query != null || url.fragment != null) return@mapNotNull null
        val segments = if (collection) url.pathSegments.dropLastWhile { it.isEmpty() } else url.pathSegments
        if (segments.size != parent.size + 1 || segments.take(parent.size) != parent) return@mapNotNull null
        if (segments.any { it.isBlank() || it == "." || it == ".." || it.any { ch -> ch == '/' || ch == '\\' || ch.isISOControl() } }) return@mapNotNull null
        val relative = segments.drop(2).joinToString("/")
        if (collection) { directories += relative; null }
        else relative.takeIf(::isFontPath)?.let(::fontFromFileName)
    }.distinctBy { it.id }.sortedBy { it.name.lowercase() }
    return FontDirectoryListing(fonts, directories.distinct())
}

internal fun InputStream.readBytesLimited(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        check(output.size() + count <= limit) { "字体目录过大" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
