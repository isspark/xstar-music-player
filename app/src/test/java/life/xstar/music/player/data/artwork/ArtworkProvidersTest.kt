package life.xstar.music.player.data.artwork

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtworkProvidersTest {
    @Test fun `query skips unknown placeholders`() {
        assertEquals("周杰伦 叶惠美", buildArtworkQuery("周杰伦", "叶惠美"))
        assertEquals("叶惠美", buildArtworkQuery("未知艺术家", "叶惠美"))
        assertEquals("周杰伦", buildArtworkQuery("周杰伦", "未知专辑"))
        assertEquals("", buildArtworkQuery("未知艺术家", "未知专辑"))
    }

    @Test fun `netease cover is upgraded to https 600`() {
        assertEquals(
            "https://p1.music.126.net/abc.jpg?param=600y600",
            normalizeNeteaseCover("http://p1.music.126.net/abc.jpg?param=200y200")
        )
        assertNull(normalizeNeteaseCover(null))
        assertNull(normalizeNeteaseCover(""))
    }

    @Test fun `bing results are decoded and deduplicated`() {
        val html = """
            <a class="iusc" m="{&quot;murl&quot;:&quot;https://img.example.com/cover.jpg&quot;,&quot;turl&quot;:&quot;thumb&quot;}"></a>
            <a class="iusc" m="{&quot;murl&quot;:&quot;https://img.example.com/cover.jpg&quot;}" ></a>
            <a class="iusc" m="{&quot;murl&quot;:&quot;https://img.example.com/second.webp&quot;}" ></a>
            <a class="iusc" m="{&quot;murl&quot;:&quot;https://img.example.com/icon.svg&quot;}" ></a>
        """.trimIndent()
        assertEquals(
            listOf("https://img.example.com/cover.jpg", "https://img.example.com/second.webp"),
            parseBingImageUrls(html)
        )
    }
}
