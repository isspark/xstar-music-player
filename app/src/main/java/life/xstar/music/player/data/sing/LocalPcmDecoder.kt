package life.xstar.music.player.data.sing

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class MonoPcm(val sampleRate: Int, val samples: ShortArray)

object LocalPcmDecoder {

    private const val TIMEOUT_US = 10_000L

    fun decodeMonoCenter(context: Context, uri: String): MonoPcm? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, Uri.parse(uri), null)
            val trackIndex = findAudioTrack(extractor) ?: return null
            val trackFormat = extractor.getTrackFormat(trackIndex)
            extractor.selectTrack(trackIndex)
            val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: return null
            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(prepareFormat(trackFormat), null, null, 0)
                codec.start()
                decodeLoop(extractor, codec)
            } finally {
                runCatching { codec.stop() }
                runCatching { codec.release() }
            }
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun findAudioTrack(extractor: MediaExtractor): Int? {
        var index = 0
        while (index < extractor.trackCount) {
            val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith("audio/") == true) return index
            index++
        }
        return null
    }

    private fun prepareFormat(format: MediaFormat): MediaFormat {
        val copy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaFormat(format)
        } else {
            MediaFormat().apply {
                format.getString(MediaFormat.KEY_MIME)?.let { setString(MediaFormat.KEY_MIME, it) }
                if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) setInteger(MediaFormat.KEY_SAMPLE_RATE, format.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) setInteger(MediaFormat.KEY_CHANNEL_COUNT, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                copyCsd(format, this)
            }
        }
        copyCsd(format, copy)
        return copy
    }

    private fun copyCsd(source: MediaFormat, target: MediaFormat) {
        for (key in listOf("csd-0", "csd-1", "csd-2")) {
            if (!source.containsKey(key)) continue
            val buffer = source.getByteBuffer(key) ?: continue
            val view = buffer.duplicate()
            if (view.remaining() == 0) continue
            val clone = ByteBuffer.allocateDirect(view.remaining()).order(ByteOrder.nativeOrder())
            clone.put(view)
            clone.rewind()
            target.setByteBuffer(key, clone)
        }
    }

    private fun decodeLoop(extractor: MediaExtractor, codec: MediaCodec): MonoPcm? {
        val info = MediaCodec.BufferInfo()
        var inputEnded = false
        var decoderEnded = false
        var sampleRate = 0
        var channelCount = 0
        var pcm16 = true
        val mono = GrowableShorts()
        var inputBuffer: ByteBuffer?
        var outputBuffer: ByteBuffer?
        var outputIndex: Int
        var idleRounds = 0

        while (!decoderEnded) {
            if (!inputEnded) {
                val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inIndex >= 0) {
                    inputBuffer = codec.getInputBuffer(inIndex)
                    if (inputBuffer == null) return null
                    val size = extractor.readSampleData(inputBuffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputEnded = true
                    } else {
                        codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            outputIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = codec.outputFormat
                    if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        pcm16 = format.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_16BIT
                    }
                }
                outputIndex >= 0 -> {
                    outputBuffer = codec.getOutputBuffer(outputIndex)
                    if (info.size > 0 && outputBuffer != null && pcm16) {
                        appendMono(outputBuffer, info.offset, info.size, channelCount, mono)
                    }
                    val flags = info.flags
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) decoderEnded = true
                    idleRounds = 0
                }
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    idleRounds++
                    if (idleRounds > 2_000) return null
                }
            }
        }
        if (sampleRate <= 0) return null
        if (mono.size == 0) return null
        return MonoPcm(sampleRate, mono.toArray())
    }

    private fun appendMono(buffer: ByteBuffer, offset: Int, size: Int, channelCount: Int, out: GrowableShorts) {
        buffer.position(offset)
        buffer.limit(offset + size)
        val shorts = buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val total = shorts.remaining()
        if (channelCount == 1) {
            var i = 0
            while (i < total) {
                out.add(shorts.get())
                i++
            }
        } else {
            var i = 0
            while (i + 1 < total) {
                val left = shorts.get().toInt()
                val right = shorts.get().toInt()
                out.add(((left + right) / 2).toShort())
                i += 2
            }
        }
    }
}
