package life.xstar.music.player.data.lyrics

import org.junit.Assert.*
import org.junit.Test

class PlatformLyricsProviderTest {
    @Test fun `platform candidate IDs do not collide and preserve LRC`() {
        for (platform in listOf("netease", "tencent", "kugou")) {
            val candidate = PlatformLyricsProvider.parse("""{"lyric":"[00:01.000]测试","tlyric":""}""",
                platform, "123", "测试", "歌手", "专辑")!!
            assertEquals("$platform:123", candidate.id)
            assertTrue(candidate.isSynced)
            assertEquals("[00:01.000]测试", candidate.content)
            assertTrue(candidate.provider.contains("GD Studio"))
        }
    }

    @Test fun `missing lyric is rejected and plain lyrics stay plain`() {
        assertNull(PlatformLyricsProvider.parse("""{"lyric":null}""", "netease", "1", "", "", ""))
        assertNull(PlatformLyricsProvider.parse("""{"lyric":""}""", "tencent", "1", "", "", ""))
        val candidate = PlatformLyricsProvider.parse("""{"lyric":"纯文本歌词"}""", "kugou", "1", "", "", "")!!
        assertFalse(candidate.isSynced)
        assertEquals("纯文本歌词", candidate.plainLyrics)
    }
}
