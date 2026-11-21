package life.xstar.music.player.data.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.charset.Charset

class LyricsTextDecoderTest {
    private val lyrics = "[00:12.50]听见喜欢的声音"

    @Test fun readsUtf8() {
        assertEquals(lyrics, LyricsTextDecoder.decode(lyrics.toByteArray(Charsets.UTF_8)))
    }

    @Test fun readsLegacyChinese() {
        assertEquals(lyrics, LyricsTextDecoder.decode(lyrics.toByteArray(Charset.forName("GB18030"))))
    }

    @Test fun readsUtf16WithBom() {
        assertEquals(lyrics, LyricsTextDecoder.decode(lyrics.toByteArray(Charsets.UTF_16)))
    }

    @Test fun readsUtf16WithoutBom() {
        assertEquals(lyrics, LyricsTextDecoder.decode(lyrics.toByteArray(Charsets.UTF_16LE)))
    }

    @Test fun removesUtf8Bom() {
        assertEquals(lyrics, LyricsTextDecoder.decode("\uFEFF$lyrics".toByteArray(Charsets.UTF_8)))
    }
}
