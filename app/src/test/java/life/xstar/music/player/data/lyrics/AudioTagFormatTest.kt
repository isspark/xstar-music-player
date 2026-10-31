package life.xstar.music.player.data.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioTagFormatTest {
    @Test fun `preserves real extension even with generic MIME`() {
        assertEquals("flac", AudioTagFormat.extension("歌曲.FLAC", "application/octet-stream"))
        assertEquals("mp3", AudioTagFormat.extension("song.mp3", "audio/mpeg"))
    }

    @Test fun `uses MIME when filename has no extension`() {
        assertEquals("m4a", AudioTagFormat.extension(null, "audio/mp4"))
        assertEquals("opus", AudioTagFormat.extension("song", "audio/opus"))
        assertEquals("unknown", AudioTagFormat.extension(null, null))
    }
}
