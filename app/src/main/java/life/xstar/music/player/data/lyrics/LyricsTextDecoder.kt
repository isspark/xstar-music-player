package life.xstar.music.player.data.lyrics

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** LRC files commonly use UTF-8, UTF-16 or legacy Chinese encodings. */
object LyricsTextDecoder {
    fun decode(bytes: ByteArray): String {
        fun starts(vararg prefix: Int) = bytes.size >= prefix.size && prefix.indices.all { (bytes[it].toInt() and 255) == prefix[it] }
        val text = when {
            starts(0xEF, 0xBB, 0xBF) -> String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            starts(0xFF, 0xFE) -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            starts(0xFE, 0xFF) -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            bytes.size >= 4 && bytes.indices.count { it % 2 == 1 && bytes[it] == 0.toByte() } > bytes.size / 5 -> String(bytes, Charsets.UTF_16LE)
            bytes.size >= 4 && bytes.indices.count { it % 2 == 0 && bytes[it] == 0.toByte() } > bytes.size / 5 -> String(bytes, Charsets.UTF_16BE)
            else -> try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            } catch (_: CharacterCodingException) {
                String(bytes, Charset.forName("GB18030"))
            }
        }
        return text.removePrefix("\uFEFF").replace("\r\n", "\n")
    }
}
