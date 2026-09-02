package life.xstar.music.player.data.audio

enum class AudioQuality {
    Standard,
    High,
    Lossless,
    HiRes
}

data class AudioInfo(
    val codec: String? = null,
    val bitrate: Long? = null,
    val sampleRate: Int? = null,
    val bitDepth: Int? = null,
    val channelCount: Int? = null,
    val quality: AudioQuality = AudioQuality.Standard
)

object AudioQualityClassifier {
    fun classify(info: AudioInfo): AudioQuality {
        if ((info.bitDepth ?: 0) >= 24 || (info.sampleRate ?: 0) > 48_000) return AudioQuality.HiRes
        if (info.codec?.lowercase() in setOf("flac", "alac", "ape", "wav", "pcm") || info.bitDepth != null && info.bitrate == null) return AudioQuality.Lossless
        if ((info.bitrate ?: 0) >= 256_000) return AudioQuality.High
        return AudioQuality.Standard
    }
}
