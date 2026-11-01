package life.xstar.music.player.data.audio

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import life.xstar.music.player.data.local.SongEntity
import life.xstar.music.player.data.lyrics.AudioTagFormat
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.Tag
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Serialize tag edits so artwork and lyrics cannot overwrite each other's changes. */
internal object AudioMetadataWriter {
    private val lock = Mutex()

    suspend fun edit(context: Context, song: SongEntity, update: (Tag) -> Unit, verify: (Tag) -> Boolean): Result<Unit> =
        withContext(Dispatchers.IO) {
            lock.withLock {
                var backup: File? = null
                var edited: File? = null
                var retainBackup = false
                try {
                    runCatching {
                        val originalUri = Uri.parse(song.uri)
                        // Keep the granted tree context when a document was stored as a single URI.
                        val uri = if (originalUri.scheme == "content" && song.sourceFolderUri.startsWith("content://")) {
                            runCatching {
                                val tree = Uri.parse(song.sourceFolderUri)
                                require(tree.authority == originalUri.authority)
                                android.provider.DocumentsContract.buildDocumentUriUsingTree(tree,
                                    android.provider.DocumentsContract.getDocumentId(originalUri))
                            }.getOrDefault(originalUri)
                        } else originalUri
                        require(uri.scheme in setOf("content", "file")) { "在线播放或共享曲目不能写入本机文件" }
                        val file = if (uri.scheme == "content") DocumentFile.fromSingleUri(context, uri) else null
                        val extension = AudioTagFormat.extension(song.fileName ?: file?.name ?: uri.lastPathSegment, song.mimeType)
                        require(extension in setOf("mp3", "flac", "m4a", "mp4", "ogg", "opus")) {
                            "暂不支持向 .$extension 文件写入元数据"
                        }
                        if (file?.canWrite() == false) throw SecurityException("音乐目录只有读取权限，请重新选择目录并授权读写")
                        val original = File.createTempFile("metadata-original-", ".$extension", context.filesDir).also { backup = it }
                        requireNotNull(context.contentResolver.openInputStream(uri)) { "无法读取原音频文件" }.use { input ->
                            original.outputStream().use { input.copyTo(it) }
                        }
                        val target = File.createTempFile("metadata-edit-", ".$extension", context.cacheDir).also { edited = it }
                        original.copyTo(target, overwrite = true)
                        val audio = AudioFileIO.read(target)
                        update(audio.tagOrCreateAndSetDefault)
                        audio.commit()
                        check(AudioFileIO.read(target).tag?.let(verify) == true) { "元数据校验失败，原文件未修改" }
                        val output = requireNotNull(context.contentResolver.openOutputStream(uri, "rwt")) { "无法打开音频文件进行写入" }
                        try {
                            output.use { stream -> target.inputStream().use { it.copyTo(stream) } }
                            check(hash(target.inputStream()).contentEquals(hash(requireNotNull(context.contentResolver.openInputStream(uri))))) {
                                "音频写回校验失败"
                            }
                        } catch (failure: Exception) {
                            try {
                                requireNotNull(context.contentResolver.openOutputStream(uri, "rwt")).use { stream ->
                                    original.inputStream().use { it.copyTo(stream) }
                                }
                                check(hash(original.inputStream()).contentEquals(hash(requireNotNull(context.contentResolver.openInputStream(uri))))) {
                                    "恢复校验失败"
                                }
                            } catch (restore: Exception) {
                                retainBackup = true
                                throw IOException("写回及恢复失败，原音频备份保存在 ${original.absolutePath}", restore)
                            }
                            throw IOException("写回失败，已恢复原音频：${failure.message}", failure)
                        }
                    }
                } finally {
                    edited?.delete()
                    if (!retainBackup) backup?.delete()
                }
            }
        }

    private fun hash(input: java.io.InputStream): ByteArray = input.use {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var count = it.read(buffer)
        while (count >= 0) {
            if (count > 0) digest.update(buffer, 0, count)
            count = it.read(buffer)
        }
        digest.digest()
    }
}
