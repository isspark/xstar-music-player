package life.xstar.music.player.player

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Hann-windowed FFT mapped to logarithmic frequency bands, with a fixed dB scale. */
class SpectrumAnalyzer {
    private val real = DoubleArray(WINDOW_SIZE)
    private val imaginary = DoubleArray(WINDOW_SIZE)
    private val window = DoubleArray(WINDOW_SIZE) { 0.5 - 0.5 * cos(2 * PI * it / (WINDOW_SIZE - 1)) }

    fun analyze(samples: FloatArray, sampleRate: Int): FloatArray {
        require(samples.size == WINDOW_SIZE && sampleRate > 0)
        for (i in samples.indices) {
            real[i] = samples[i] * window[i]
            imaginary[i] = 0.0
        }
        var j = 0
        for (i in 1 until WINDOW_SIZE) {
            var bit = WINDOW_SIZE shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val value = real[i]; real[i] = real[j]; real[j] = value
            }
        }
        var length = 2
        while (length <= WINDOW_SIZE) {
            val angle = -2 * PI / length
            val stepReal = cos(angle)
            val stepImaginary = sin(angle)
            for (start in 0 until WINDOW_SIZE step length) {
                var wr = 1.0
                var wi = 0.0
                for (offset in 0 until length / 2) {
                    val a = start + offset
                    val b = a + length / 2
                    val tr = wr * real[b] - wi * imaginary[b]
                    val ti = wr * imaginary[b] + wi * real[b]
                    real[b] = real[a] - tr
                    imaginary[b] = imaginary[a] - ti
                    real[a] += tr
                    imaginary[a] += ti
                    val nextReal = wr * stepReal - wi * stepImaginary
                    wi = wr * stepImaginary + wi * stepReal
                    wr = nextReal
                }
            }
            length *= 2
        }
        val minimum = 40.0
        val maximum = minOf(16_000.0, sampleRate / 2.0)
        val result = FloatArray(SPECTRUM_BAND_COUNT)
        for (band in result.indices) {
            val low = minimum * (maximum / minimum).pow(band.toDouble() / result.size)
            val high = minimum * (maximum / minimum).pow((band + 1.0) / result.size)
            val first = max(1, (low * WINDOW_SIZE / sampleRate).toInt())
            val last = max(first, (high * WINDOW_SIZE / sampleRate).toInt()).coerceAtMost(WINDOW_SIZE / 2 - 1)
            var magnitude = 0.0
            for (bin in first..last) {
                magnitude = max(magnitude, sqrt(real[bin] * real[bin] + imaginary[bin] * imaginary[bin]) * 4 / WINDOW_SIZE)
            }
            val db = 20 * ln(max(magnitude, 1e-9)) / ln(10.0)
            result[band] = ((db + 65) / 65).coerceIn(0.0, 1.0).toFloat()
        }
        return result
    }

    companion object {
        const val WINDOW_SIZE = 2048
    }
}
