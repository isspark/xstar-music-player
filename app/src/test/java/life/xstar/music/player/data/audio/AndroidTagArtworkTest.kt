package life.xstar.music.player.data.audio

import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.flac.FlacTag
import org.jaudiotagger.tag.id3.ID3v24Tag
import org.jaudiotagger.tag.mp4.Mp4Tag
import org.jaudiotagger.tag.vorbiscomment.VorbisCommentTag
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidTagArtworkTest {
    @Test
    fun embedsArtworkWithoutInvokingAnImageDecoder() {
        // Deliberately not a decodable image: tag construction must use supplied bounds.
        val bytes = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte(),
            0, 16, 0x4a, 0x46, 0x49, 0x46, 0)
        val tags: List<Tag> = listOf(FlacTag(), VorbisCommentTag(), ID3v24Tag(), Mp4Tag())
        tags.forEach { tag ->
            tag.setField(AndroidTagArtwork(bytes, 640, 480, "image/jpeg"))
            val artwork = tag.firstArtwork
            assertArrayEquals(bytes, artwork.binaryData)
            assertEquals("image/jpeg", artwork.mimeType)
            if (tag !is Mp4Tag) assertEquals(3, artwork.pictureType)
            if (tag is FlacTag || tag is VorbisCommentTag) {
                assertEquals(640, artwork.width)
                assertEquals(480, artwork.height)
            }
        }
    }
}
