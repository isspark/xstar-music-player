package life.xstar.music.player.data.lyrics

internal object AudioTagFormat {
    fun extension(fileName: String?, mimeType: String?): String {
        val extension = fileName?.substringAfterLast('.', "")?.lowercase(java.util.Locale.ROOT)
        if (!extension.isNullOrBlank()) return extension
        return when (mimeType?.lowercase(java.util.Locale.ROOT)) {
            "audio/mpeg", "audio/mp3" -> "mp3"
            "audio/flac", "audio/x-flac" -> "flac"
            "audio/mp4", "audio/x-m4a", "audio/m4a" -> "m4a"
            "audio/ogg", "application/ogg" -> "ogg"
            "audio/opus" -> "opus"
            else -> "unknown"
        }
    }
}
