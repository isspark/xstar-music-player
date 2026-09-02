package life.xstar.music.player.data.audio

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri

object AudioAnalyzer {
    fun analyze(context: Context, uri: Uri, mimeType: String?): AudioInfo {
        val extractor = MediaExtractor()
        return runCatching {
            extractor.setDataSource(context, uri, null)
            val format = (0 until extractor.trackCount)
                .map { extractor.getTrackFormat(it) }
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: return@runCatching AudioInfo(codec = codecFrom(mimeType))
            val codec = codecFrom(format.getString(MediaFormat.KEY_MIME))
            val bitrate = format.longOrNull(MediaFormat.KEY_BIT_RATE)
            val sampleRate = format.intOrNull(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.intOrNull(MediaFormat.KEY_CHANNEL_COUNT)
            val bitDepth = format.intOrNull(MediaFormat.KEY_PCM_ENCODING)?.let(::pcmDepth)
            val base = AudioInfo(codec, bitrate, sampleRate, bitDepth, channels)
            base.copy(quality = AudioQualityClassifier.classify(base))
        }.getOrDefault(AudioInfo(codec = codecFrom(mimeType))).also { extractor.release() }
    }

    private fun codecFrom(mime: String?): String? = when (mime?.lowercase()) {
        "audio/mpeg" -> "MP3"
        "audio/mp4", "audio/aac" -> "AAC"
        "audio/flac" -> "FLAC"
        "audio/ogg" -> "OGG"
        "audio/opus" -> "OPUS"
        "audio/wav", "audio/x-wav" -> "WAV"
        "audio/alac" -> "ALAC"
        else -> mime?.substringAfter('/')?.uppercase()
    }

    private fun pcmDepth(encoding: Int): Int? = when (encoding) {
        android.media.AudioFormat.ENCODING_PCM_16BIT -> 16
        android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
        android.media.AudioFormat.ENCODING_PCM_32BIT -> 32
        else -> null
    }

    private fun MediaFormat.intOrNull(key: String) = if (containsKey(key)) getInteger(key) else null
    private fun MediaFormat.longOrNull(key: String) = if (containsKey(key)) getLong(key) else null
}
