package life.xstar.music.player.data.audio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

fun cacheDownloadedArtwork(context: Context, keyValue: Long, bytes: ByteArray): String? {
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
    val directory = File(context.cacheDir, "album_art").apply { mkdirs() }
    val file = File(directory, "online_${keyValue}.jpg")
    if (!file.exists() || file.length() == 0L) {
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
    }
    bitmap.recycle()
    return file.absolutePath
}
