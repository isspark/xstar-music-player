package life.xstar.music.player.data.audio

import org.jaudiotagger.tag.images.AndroidArtwork

/** Image bounds are supplied by BitmapFactory, without desktop ImageIO/AWT dependencies. */
internal class AndroidTagArtwork(bytes: ByteArray, width: Int, height: Int, mimeType: String) : AndroidArtwork() {
    init {
        require(bytes.isNotEmpty() && width > 0 && height > 0) { "封面图片无效" }
        binaryData = bytes
        setWidth(width)
        setHeight(height)
        setMimeType(mimeType)
        pictureType = 3
    }

    // FLAC and Vorbis call this even when dimensions are already populated.
    // AndroidArtwork's default implementation throws UnsupportedOperationException.
    override fun setImageFromData(): Boolean = true
}
