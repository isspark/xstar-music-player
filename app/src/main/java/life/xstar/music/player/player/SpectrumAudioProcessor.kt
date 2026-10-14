package life.xstar.music.player.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Observes decoded PCM without changing the samples sent to the audio sink. */
@OptIn(UnstableApi::class)
class SpectrumAudioProcessor : BaseAudioProcessor() {
    private val analyzer = SpectrumAnalyzer()
    private val samples = FloatArray(SpectrumAnalyzer.WINDOW_SIZE)
    private var sampleCount = 0

    @Volatile
    var levels = FloatArray(SPECTRUM_BAND_COUNT)
        private set

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytes = inputBuffer.remaining()
        if (bytes == 0) return
        val input = inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val channels = inputAudioFormat.channelCount
        while (input.remaining() >= channels * 2) {
            // Use the strongest channel to avoid cancelling out-of-phase stereo.
            var sample = 0f
            repeat(channels) {
                val value = input.short / 32768f
                if (kotlin.math.abs(value) > kotlin.math.abs(sample)) sample = value
            }
            samples[sampleCount++] = sample
            if (sampleCount == samples.size) {
                levels = analyzer.analyze(samples, inputAudioFormat.sampleRate)
                sampleCount = 0
            }
        }
        val output = replaceOutputBuffer(bytes)
        output.put(inputBuffer)
        output.flip()
    }

    override fun onFlush() {
        sampleCount = 0
        levels = FloatArray(SPECTRUM_BAND_COUNT)
    }
}
