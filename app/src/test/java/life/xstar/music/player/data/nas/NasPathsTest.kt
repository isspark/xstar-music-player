package life.xstar.music.player.data.nas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NasPathsTest {
    @Test fun preservesEncodedNamesAndNestedDirectories() {
        assertEquals("https://nas.example/music/%E5%91%A8/01%20-%20Song.flac", NasPaths.resolve("https://nas.example/music", "%E5%91%A8/01%20-%20Song.flac"))
        assertEquals("https://nas.example/music/album/song.flac", NasPaths.resolve("https://nas.example/music/", "/music/album/song.flac"))
    }
    @Test fun rejectsCrossOriginAndDirectoryEscapes() {
        listOf("https://evil.example/music/a.mp3", "http://nas.example/music/a.mp3", "https://nas.example:99/music/a.mp3", "../secret.mp3", "/music-other/song.mp3", "%2e%2e/secret.mp3", "https://user:pass@nas.example/music/a.mp3", "song.mp3?token=secret").forEach { path ->
            assertThrows("Should reject $path", IllegalArgumentException::class.java) { NasPaths.resolve("https://nas.example/music", path) }
        }
    }
}
