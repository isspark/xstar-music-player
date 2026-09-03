package life.xstar.music.player.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

@OptIn(UnstableApi::class)
class VocalMixAudioProcessor : BaseAudioProcessor() {

    @Volatile
    var enabled: Boolean = false

    @Volatile
    var vocal: Float = 1f
        set(value) {
            field = value.coerceIn(0f, 1f)
        }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return if (inputAudioFormat.channelCount == 2) inputAudioFormat else AudioProcessor.AudioFormat.NOT_SET
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!enabled) {
            val bytes = inputBuffer.remaining()
            if (bytes == 0) return
            val output = replaceOutputBuffer(bytes)
            output.put(inputBuffer)
            output.limit(bytes)
            output.position(0)
            return
        }
        val frames = inputBuffer.remaining() / BYTES_PER_FRAME
        if (frames == 0) return
        val output = replaceOutputBuffer(frames * BYTES_PER_FRAME)
        output.order(ByteOrder.LITTLE_ENDIAN)
        val input = inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val target = output.asShortBuffer()
        val originalAmount = vocal
        val removedAmount = 1f - originalAmount
        var frame = 0
        while (frame < frames) {
            val left = input.get(frame * 2).toFloat()
            val right = input.get(frame * 2 + 1).toFloat()
            val sideLeft = (left - right) * 0.5f
            val sideRight = (right - left) * 0.5f
            target.put(frame * 2, toShortSample(originalAmount * left + removedAmount * sideLeft))
            target.put(frame * 2 + 1, toShortSample(originalAmount * right + removedAmount * sideRight))
            frame++
        }
        inputBuffer.position(inputBuffer.limit())
        output.limit(frames * BYTES_PER_FRAME)
    }

    private fun toShortSample(value: Float): Short {
        val rounded = value.roundToInt()
        return if (rounded > 32767) 32767.toShort()
        else if (rounded < -32768) (-32768).toShort()
        else rounded.toShort()
    }

    private companion object {
        const val BYTES_PER_FRAME = 4
    }
}
