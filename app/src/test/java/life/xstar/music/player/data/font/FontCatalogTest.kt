package life.xstar.music.player.data.font

import life.xstar.music.player.data.preferences.AppFont
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FontCatalogTest {
    @Test fun allFontsUseNasFontDirectory() {
        assertEquals(knownFonts.size, knownFonts.map { it.id }.distinct().size)
        knownFonts.forEach {
            assertEquals(listOf("https://dav.xstar-life.fnos.net:443/App/Font/${it.fileName}"), it.urls)
            assertTrue(!it.fileName.contains('/') && !it.fileName.contains(".."))
        }
    }

    @Test fun previousFontSelectionsMapToRemoteFiles() {
        assertEquals("xstar_sans_sc.ttf", AppFont.Default.nasFont().fileName)
        assertEquals("xstar_serif_sc.ttf", AppFont.Serif.nasFont().fileName)
        assertEquals(AppFont.Cursive.nasFont(), AppFont.Rounded.nasFont())
        assertEquals("xstar_calligraphy.ttf", AppFont.Lishu.nasFont().fileName)
    }
}
