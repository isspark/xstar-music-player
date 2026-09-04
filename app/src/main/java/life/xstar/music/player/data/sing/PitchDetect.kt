package life.xstar.music.player.data.sing

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class PitchSample(val rms: Float, val frequency: Float?)

object PitchDetect {

    private const val MIN_FREQUENCY = 70f
    private const val MAX_FREQUENCY = 1300f

    fun detect(samples: ShortArray, offset: Int, count: Int, sampleRate: Int): PitchSample {
        val end = min(offset + count, samples.size)
        var energy = 0.0
        var index = offset
        while (index < end) {
            val v = samples[index].toDouble()
            energy += v * v
            index++
        }
        val rms = if (count > 0) (sqrt(energy / count) / 32768.0).toFloat() else 0f
        if (count < 32 || rms < 0.012f) return PitchSample(rms, null)
        val minLag = max(2f, sampleRate / MAX_FREQUENCY).toInt()
        val maxLag = (sampleRate.toFloat() / MIN_FREQUENCY).toInt()
        val bestLag = findBestLag(samples, offset, end, minLag, maxLag)
        if (bestLag <= 0) return PitchSample(rms, null)
        val refinedLag = refineLag(samples, offset, end, bestLag)
        return PitchSample(rms, sampleRate.toFloat() / refinedLag)
    }

    private fun findBestLag(samples: ShortArray, start: Int, end: Int, minLag: Int, maxLag: Int): Int {
        val limit = end - start
        var best = -1
        var bestScore = 0f
        var lag = minLag
        while (lag <= maxLag && lag < limit) {
            val score = correlation(samples, start, limit, lag)
            if (score > bestScore) {
                bestScore = score
                best = lag
            }
            lag++
        }
        return if (bestScore > 0.35f) best else -1
    }

    private fun correlation(samples: ShortArray, start: Int, limit: Int, lag: Int): Float {
        var sum0 = 0.0
        var sum1 = 0.0
        var i = 0
        while (i + lag < limit) {
            val a = samples[start + i].toDouble()
            val b = samples[start + i + lag].toDouble()
            sum0 += a * a
            sum1 += a * b
            i++
        }
        return if (sum0 > 0.0) (sum1 / sum0).toFloat() else 0f
    }

    private fun refineLag(samples: ShortArray, start: Int, end: Int, lag: Int): Float {
        val limit = end - start
        fun score(l: Int): Float = if (l in 1 until limit) correlation(samples, start, limit, l) else 0f
        val c0 = score(lag - 1)
        val c1 = score(lag)
        val c2 = score(lag + 1)
        val denominator = c0 - 2f * c1 + c2
        val offset = if (kotlin.math.abs(denominator) < 1e-6f) 0f else 0.5f * (c0 - c2) / denominator
        return lag + offset.coerceIn(-1f, 1f)
    }
}

class GrowableShorts {
    private var data = ShortArray(1 shl 12)
    var size: Int = 0
        private set

    fun add(value: Short) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = value
    }

    fun toArray(): ShortArray = data.copyOf(size)
}
