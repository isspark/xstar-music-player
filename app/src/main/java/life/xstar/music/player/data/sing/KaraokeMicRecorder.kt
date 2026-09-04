package life.xstar.music.player.data.sing

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class MicLive(val level: Float = 0f, val frequency: Float? = null)

data class MicRecording(val samples: ShortArray, val notes: List<MicNote>, val sampleRate: Int)

@SuppressLint("MissingPermission")
class KaraokeMicRecorder {

    private val _live = MutableStateFlow(MicLive())
    val live: StateFlow<MicLive> = _live.asStateFlow()

    private val recordRates = intArrayOf(16_000, 22_050, 32_000, 44_100)
    private var record: AudioRecord? = null
    private var recordRate = 16_000
    private var samples = GrowableShorts()
    private var notes = mutableListOf<MicNote>()
    private var ring = ShortArray(0)
    private var ringHead = 0
    private var ringFilled = 0
    private var ringCapacity = 0
    private var totalSamples = 0
    @Volatile
    private var running = false
    private var thread: Thread? = null

    val isRunning: Boolean get() = running

    fun start(): Boolean {
        if (running) return true
        val created = createRecord() ?: return false
        record = created
        recordRate = created.sampleRate
        val window = recordRate / 40
        ring = ShortArray(window)
        ringHead = 0
        ringFilled = 0
        ringCapacity = window
        totalSamples = 0
        samples = GrowableShorts()
        notes = mutableListOf()
        _live.value = MicLive()
        running = true
        thread = Thread({ recordLoop(created) }, "karaoke-mic").apply { start() }
        return true
    }

    fun stop(): MicRecording? {
        if (!running && thread == null) return null
        running = false
        thread?.join(2_000)
        thread = null
        runCatching { record?.release() }
        record = null
        return if (samples.size > 0) MicRecording(samples.toArray(), notes.toList(), recordRate) else null
    }

    fun discard() {
        running = false
        thread?.join(2_000)
        thread = null
        runCatching { record?.release() }
        record = null
        samples = GrowableShorts()
        notes = mutableListOf()
        _live.value = MicLive()
    }

    private fun createRecord(): AudioRecord? {
        for (rate in recordRates) {
            val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuffer <= 0) continue
            val created = runCatching {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    rate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuffer.coerceAtLeast(rate / 25 * 2)
                )
            }.getOrNull() ?: continue
            if (created.state == AudioRecord.STATE_INITIALIZED) return created
            runCatching { created.release() }
        }
        return null
    }

    private fun recordLoop(audioRecord: AudioRecord) {
        audioRecord.startRecording()
        val chunkSize = (recordRate / 50).coerceAtLeast(160)
        val chunk = ShortArray(chunkSize)
        val windowCopy = ShortArray(ringCapacity)
        try {
            while (running) {
                val read = audioRecord.read(chunk, 0, chunkSize, AudioRecord.READ_BLOCKING)
                if (read <= 0) continue
                var index = 0
                while (index < read) {
                    val value = chunk[index]
                    samples.add(value)
                    ring[ringHead] = value
                    ringHead = (ringHead + 1) % ringCapacity
                    if (ringFilled < ringCapacity) ringFilled++
                    totalSamples++
                    index++
                }
                val timeMs = totalSamples * 1000 / recordRate
                val start = (ringHead - ringFilled + ringCapacity) % ringCapacity
                var copyIndex = 0
                while (copyIndex < ringFilled) {
                    windowCopy[copyIndex] = ring[(start + copyIndex) % ringCapacity]
                    copyIndex++
                }
                val detected = PitchDetect.detect(windowCopy, 0, ringFilled, recordRate)
                notes.add(MicNote(timeMs, detected.frequency))
                _live.value = MicLive(detected.rms, detected.frequency)
            }
        } finally {
            runCatching { audioRecord.stop() }
        }
    }
}

fun writeWav16Mono(file: File, samples: ShortArray, sampleRate: Int): Boolean = runCatching {
    val dataSize = samples.size * 2
    FileOutputStream(file).use { stream ->
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + dataSize)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1)
        header.putShort(1)
        header.putInt(sampleRate)
        header.putInt(sampleRate * 2)
        header.putShort(2)
        header.putShort(16)
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataSize)
        stream.write(header.array())
        val bytes = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { bytes.putShort(it) }
        stream.write(bytes.array())
    }
    true
}.getOrDefault(false)
