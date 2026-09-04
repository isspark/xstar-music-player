package life.xstar.music.player.data.sing

import android.content.Context
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.min
import kotlin.math.roundToInt

data class VoiceFrame(val timeMs: Int, val frequency: Float)

data class MicNote(val timeMs: Int, val frequency: Float?)

data class SingingScore(
    val total: Int,
    val pitch: Int?,
    val activity: Int,
    val lightweight: Boolean
)

object VocalPitchAnalyzer {

    private const val CACHE_VERSION = 1

    fun cacheFile(context: Context, songId: Long): File {
        val dir = File(context.cacheDir, "vocal_pitch")
        dir.mkdirs()
        return File(dir, "$songId.bin")
    }

    fun load(context: Context, songId: Long): List<VoiceFrame>? {
        val file = cacheFile(context, songId)
        if (!file.exists()) return null
        return runCatching {
            DataInputStream(file.inputStream().buffered()).use { input ->
                if (input.readInt() != CACHE_VERSION) return null
                val count = input.readInt()
                if (count !in 0..200_000) return null
                val result = ArrayList<VoiceFrame>(count)
                repeat(count) {
                    result.add(VoiceFrame(input.readInt(), input.readFloat()))
                }
                result
            }
        }.getOrNull()
    }

    fun save(context: Context, songId: Long, frames: List<VoiceFrame>) {
        runCatching {
            DataOutputStream(cacheFile(context, songId).outputStream().buffered()).use { output ->
                output.writeInt(CACHE_VERSION)
                output.writeInt(frames.size)
                frames.forEach { output.writeInt(it.timeMs); output.writeFloat(it.frequency) }
            }
        }
    }

    fun analyze(context: Context, uri: String, isCancelled: () -> Boolean = { false }): List<VoiceFrame>? {
        val mono = LocalPcmDecoder.decodeMonoCenter(context, uri) ?: return null
        return extractFrames(mono, isCancelled)
    }

    fun extractFrames(mono: MonoPcm, isCancelled: () -> Boolean = { false }): List<VoiceFrame>? {
        val originalRate = mono.sampleRate
        val step = (originalRate / 16_000).coerceAtLeast(1)
        val rate = originalRate / step
        val source = mono.samples
        val sourceCount = source.size
        val decimatedSize = sourceCount / step
        val decimated = ShortArray(decimatedSize)
        var outIndex = 0
        var i = 0
        while (i + step <= sourceCount) {
            var sum = 0
            var j = 0
            while (j < step) {
                sum += source[i + j].toInt()
                j++
            }
            decimated[outIndex++] = (sum / step).toShort()
            i += step
        }
        if (isCancelled()) return null
        val hop = rate / 50
        val window = rate / 40
        val frames = ArrayList<VoiceFrame>(decimatedSize / hop + 1)
        var lastFrequency = -1f
        var start = 0
        while (start < decimatedSize) {
            if (isCancelled()) return null
            val length = min(window, decimatedSize - start)
            val sample = PitchDetect.detect(decimated, start, length, rate)
            val frequency = sample.frequency
            if (frequency != null && frequency >= 80f && frequency <= 1300f) {
                val smoothed = lastFrequency < 0f || centsBetween(frequency, lastFrequency) <= 400f
                if (smoothed) {
                    frames.add(VoiceFrame(start * 1000 / rate, frequency))
                    lastFrequency = frequency
                }
            } else {
                if (sample.rms < 0.012f) lastFrequency = -1f
            }
            start += hop
        }
        return frames
    }

    private fun centsBetween(a: Float, b: Float): Float = abs(1200f * log2(a / b))

    fun computeScore(reference: List<VoiceFrame>?, notes: List<MicNote>, songStartMs: Long): SingingScore {
        if (notes.isEmpty()) return SingingScore(0, null, 0, lightweight = true)
        if (reference.isNullOrEmpty()) {
            val activity = trimSilenceActivity(notes)
            return SingingScore(activity, null, activity, lightweight = true)
        }
        var eligible = 0
        var voiced = 0
        val scored = mutableListOf<Boolean>()
        for (note in notes) {
            val songTimeMs = songStartMs + note.timeMs
            val nearest = nearestReference(reference, songTimeMs) ?: continue
            eligible++
            val frequency = note.frequency
            if (frequency != null) {
                voiced++
                val semitones = 12f * log2(frequency / nearest.frequency)
                scored.add(abs(semitones) <= 1.0f)
            }
        }
        val activity = if (eligible == 0) 0 else (voiced * 100f / eligible).roundToInt().coerceIn(0, 100)
        if (scored.isEmpty()) {
            val fallback = trimSilenceActivity(notes)
            return SingingScore(fallback, null, fallback, lightweight = true)
        }
        val pitch = (scored.count { it } * 100f / scored.size).roundToInt().coerceIn(0, 100)
        val total = (0.85f * pitch + 0.15f * activity).roundToInt().coerceIn(0, 100)
        return SingingScore(total, pitch, activity, lightweight = false)
    }

    private fun trimSilenceActivity(notes: List<MicNote>): Int {
        var first = -1
        var last = -1
        for (index in notes.indices) {
            if (notes[index].frequency != null) {
                if (first < 0) first = index
                last = index
            }
        }
        if (first < 0 || last < first) return 0
        var voiced = 0
        for (index in first..last) if (notes[index].frequency != null) voiced++
        return (voiced * 100f / (last - first + 1)).roundToInt().coerceIn(0, 100)
    }

    private fun nearestReference(reference: List<VoiceFrame>, timeMs: Long): VoiceFrame? {
        var low = 0
        var high = reference.size - 1
        var best: VoiceFrame? = null
        var bestDistance = 300
        while (low <= high) {
            val mid = (low + high) ushr 1
            val frame = reference[mid]
            val distance = abs(frame.timeMs - timeMs).toInt()
            if (distance < bestDistance) {
                bestDistance = distance
                best = frame
            }
            if (frame.timeMs < timeMs) low = mid + 1 else high = mid - 1
        }
        return best
    }
}

fun gradeLabel(score: Int): String = when {
    score >= 90 -> "SSS"
    score >= 80 -> "S"
    score >= 70 -> "A"
    score >= 60 -> "B"
    else -> "C"
}

sealed interface VocalReferenceState {
    data object Idle : VocalReferenceState
    data object Computing : VocalReferenceState
    data class Ready(val frames: List<VoiceFrame>) : VocalReferenceState
    data class Unavailable(val message: String) : VocalReferenceState
}
