package life.xstar.music.player.data.font

import org.junit.Assert.*
import org.junit.Test

class FontDirectoryTest {
    private fun entry(href: String, status: Int = 200, collection: Boolean = false) = """
        <d:response><d:href>$href</d:href><d:propstat><d:prop><d:resourcetype>${if (collection) "<d:collection/>" else ""}</d:resourcetype></d:prop><d:status>HTTP/1.1 $status OK</d:status></d:propstat></d:response>
    """.trimIndent()
    private fun directory(vararg entries: String) = "<d:multistatus xmlns:d=\"DAV:\">${entries.joinToString("")}</d:multistatus>"

    @Test fun listsOnlySuccessfulDirectFontFiles() {
        val fonts = parseFontDirectory(directory(
            entry("/App/Font/", collection = true), entry("/App/Font/xstar_round.ttf"),
            entry("/App/Font/%E6%B5%8B%E8%AF%95%20Font.OTF"), entry("/App/Font/readme.txt"),
            entry("/App/Font/missing.ttf", 404), entry("/App/Font/sub/test.ttf"),
            entry("https://evil.example/App/Font/test.ttf"), entry("/App/Other/test.ttf"),
            entry("/App/Font/xstar_round.ttf"), entry("/App/Font/%2Fbad.ttf")
        ))
        assertEquals(setOf("xstar_round.ttf", "测试 Font.OTF"), fonts.map { it.fileName }.toSet())
        assertEquals("xstar-round", fonts.first { it.fileName == "xstar_round.ttf" }.id)
        assertEquals("nas:测试 Font.OTF", fonts.first { it.fileName.endsWith("OTF") }.id)
    }

    @Test fun handlesEmptyDirectory() { assertTrue(parseFontDirectory(directory()).isEmpty()) }

    @Test fun discoversSubfoldersAndFontsWithDuplicateBasenames() {
        val root = parseFontListing(directory(entry("/App/Font/family/", collection = true)))
        assertEquals(listOf("family"), root.directories)
        val child = parseFontListing(directory(entry("/App/Font/family/font.TTF"), entry("/App/Font/family/", collection = true)), "family")
        assertEquals("family/font.TTF", child.fonts.single().fileName)
        assertNotEquals(fontFromFileName("font.TTF").id, child.fonts.single().id)
        assertTrue(child.fonts.single().urls.single().endsWith("/family/font.TTF"))
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsEntities() {
        parseFontDirectory("<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///test'>]>" + directory())
    }

    @Test fun idsStayStable() {
        assertEquals(fontFromFileName("new.ttf"), fontFromFileName("new.ttf"))
        assertFalse(isFontFileName("../bad.ttf"))
        assertFalse(isFontFileName("bad\\font.ttf"))
    }
}
