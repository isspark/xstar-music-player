package life.xstar.music.player.data.nas

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import life.xstar.music.player.data.audio.stableSongId
import life.xstar.music.player.data.local.SongEntity
import life.xstar.music.player.data.local.MusicDatabase
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class NasConfig(val id: String = UUID.randomUUID().toString(), val type: String = "WebDAV", val address: String = "", val username: String = "", val password: String = "", val enabled: Boolean = true, val syncedAt: Long = 0, val managed: Boolean = false)
data class NasStatus(val busy: Boolean = false, val message: String = "", val count: Int = 0, val resumable: Boolean = false)

/** Stable nas:// references never contain credentials or expiring Subsonic tokens. */
class NasRepository(private val context: Context, private val database: MusicDatabase) {
    private val prefs = context.getSharedPreferences("nas", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    private val _config = MutableStateFlow(readConfig())
    val config = _config.asStateFlow()
    private val _status = MutableStateFlow(NasStatus())
    val status = _status.asStateFlow()
    private val _initialSyncRequired = MutableStateFlow<Boolean?>(null)
    val initialSyncRequired = _initialSyncRequired.asStateFlow()
    suspend fun initializeDefault() = withContext(Dispatchers.IO) {
        _initialSyncRequired.value = !prefs.getBoolean("initial_sync_complete", false) && _config.value.syncedAt == 0L
        database.nasSyncDao().task(_config.value.id)?.let { task ->
            if (!_status.value.busy) _status.value = NasStatus(message = task.message, count = task.count, resumable = task.state != "complete")
            if (task.state == "running" || task.state == "waiting") NasSyncScheduler.schedule(context)
        }
    }
    suspend fun connectForSetup() = withContext(Dispatchers.IO) {
        val c = _config.value
        check(c.enabled) { "请先启用 NAS" }
        if (c.type == "Navidrome") api(c, "ping") else dav(c, "", collect = false)
        completeLocalSetup()
    }
    suspend fun useDeveloperDefault(passphrase: String) {
        require(passphrase.trim().equals("XSTAR13", ignoreCase = true)) { "口令不正确" }
        save(NasConfig(address = "https://dav.xstar-life.fnos.net:443", username = "xstar", password = "Xstar-13", managed = true))
    }
    suspend fun completeLocalSetup() = withContext(Dispatchers.IO) {
        check(prefs.edit().putBoolean("initial_sync_complete", true).commit()) { "保存初始化状态失败" }
        _initialSyncRequired.value = false
    }
    fun reportStatus(busy: Boolean, message: String) { _status.value = NasStatus(busy = busy, message = message) }
    private val mutex = kotlinx.coroutines.sync.Mutex()
    private class NasHttpException(val code: Int) : java.io.IOException("NAS HTTP $code，请检查账户和目录权限")

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey("xstar-nas", null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("xstar-nas", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun encrypt(text: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(text.toByteArray()), Base64.NO_WRAP)
    }
    private fun readConfig(): NasConfig = runCatching {
        val bytes = Base64.decode(prefs.getString("config", null) ?: return NasConfig(), Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12))) }
        val json = JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size))))
        NasConfig(json.getString("id"), json.getString("type"), json.getString("address"), json.getString("username"), json.getString("password"), json.optBoolean("enabled", true), json.optLong("syncedAt"), json.optBoolean("managed"))
    }.getOrElse { NasConfig() }

    suspend fun save(value: NasConfig) = withContext(Dispatchers.IO) {
        val address = value.address.trim().trimEnd('/')
        require(address.startsWith("http://") || address.startsWith("https://")) { "服务地址必须以 http:// 或 https:// 开头，并包含 WebDAV 音乐目录或 Navidrome 服务路径" }
        val url = address.toHttpUrl()
        require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) { "地址请勿包含账号、查询参数或片段" }
        require(value.type in listOf("WebDAV", "Navidrome"))
        val old = _config.value
        val changed = old.address != url.toString().trimEnd('/') || old.username != value.username || old.type != value.type
        val saved = value.copy(address = url.toString().trimEnd('/'), id = if (changed) UUID.randomUUID().toString() else old.id, syncedAt = if (changed) 0 else value.syncedAt)
        val json = JSONObject().apply {
            put("id", saved.id); put("type", saved.type); put("address", saved.address); put("username", saved.username)
            put("password", saved.password); put("enabled", saved.enabled); put("syncedAt", saved.syncedAt)
            put("managed", saved.managed)
        }
        check(prefs.edit().putString("config", encrypt(json.toString())).commit()) { "保存配置失败" }
        _config.value = saved
    }

    private fun reference(c: NasConfig, path: String) = Uri.Builder().scheme("nas").authority(c.id).appendPath(path).build().toString()
    fun resolve(value: String): Pair<String, Map<String, String>> {
        val uri = Uri.parse(value)
        if (uri.scheme != "nas") return value to emptyMap()
        val c = _config.value
        check(c.enabled && uri.authority == c.id) { "NAS 音乐源已停用或配置已更换" }
        val path = uri.pathSegments.single()
        return if (c.type == "Navidrome") apiUrl(c, "stream", mapOf("id" to path, "format" to "raw")) to emptyMap()
        else davUrl(c, path) to mapOf("Authorization" to Credentials.basic(c.username, c.password, Charsets.UTF_8))
    }
    private fun davUrl(c: NasConfig, path: String): String {
        return NasPaths.resolve(c.address, path)
    }
    fun open(value: String, range: String? = null): java.io.InputStream {
        val (url, headers) = resolve(value)
        val response = client.newCall(Request.Builder().url(url).apply { headers.forEach { (k,v) -> header(k,v) }; range?.let { header("Range", it) } }.build()).execute()
        if (!response.isSuccessful) { response.close(); error("NAS 请求失败，请检查连接和账户") }
        if (range != null && response.code != 206) { response.close(); error("NAS 不支持 Range 请求") }
        return response.body!!.byteStream()
    }
    private fun apiUrl(c: NasConfig, method: String, params: Map<String, String> = emptyMap()): String {
        val salt = UUID.randomUUID().toString().replace("-", "")
        val token = MessageDigest.getInstance("MD5").digest((c.password + salt).toByteArray()).joinToString("") { "%02x".format(it) }
        return (c.address + "/rest/$method.view").toHttpUrl().newBuilder().apply {
            mapOf("u" to c.username, "t" to token, "s" to salt, "v" to "1.16.1", "c" to "XSTAR", "f" to "json").plus(params).forEach { (k,v) -> addQueryParameter(k,v) }
        }.build().toString()
    }
    private fun api(c: NasConfig, method: String, params: Map<String, String> = emptyMap()): JSONObject {
        client.newCall(Request.Builder().url(apiUrl(c, method, params)).build()).execute().use { response ->
            if (!response.isSuccessful) throw NasHttpException(response.code)
            val json = JSONObject(response.body!!.string()).getJSONObject("subsonic-response")
            check(json.optString("status") == "ok") { "NAS 接口失败：${json.optJSONObject("error")?.optString("message") ?: "未知错误"}" }
            return json
        }
    }
    private suspend fun dav(c: NasConfig, path: String, collect: Boolean = true, onEntry: suspend (String, Boolean, Long?) -> Unit = { _, _, _ -> }) {
        val request = Request.Builder().url(davUrl(c, path)).header("Authorization", Credentials.basic(c.username, c.password, Charsets.UTF_8))
            .header("Depth", "1").method("PROPFIND", "".toRequestBody()).build()
        client.newCall(request).execute().use { response ->
            if (response.code != 207) throw NasHttpException(response.code)
            if (!collect) return
            // Android's DOM implementation does not support the Xerces-specific security
            // features. Use its native pull parser, without entity/DOCTYPE processing.
            val parser = android.util.Xml.newPullParser().apply {
                setFeature(org.xmlpull.v1.XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
                setInput(response.body!!.byteStream(), null)
            }
            val base = (c.address + "/").toHttpUrl()
            val current = davUrl(c, path).toHttpUrl().encodedPath.trimEnd('/')
            var href: String? = null
            var directory = false
            var size: Long? = null
            var closed = false
            var event = parser.eventType
            while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                currentCoroutineContext().ensureActive()
                check(event != org.xmlpull.v1.XmlPullParser.DOCDECL) { "不支持含 DOCTYPE 的 WebDAV 响应" }
                if (parser.namespace == "DAV:") {
                    if (event == org.xmlpull.v1.XmlPullParser.END_TAG && parser.name == "multistatus") closed = true
                    if (event == org.xmlpull.v1.XmlPullParser.START_TAG) when (parser.name) {
                        "response" -> { href = null; directory = false; size = null }
                        "href" -> href = parser.nextText()
                        "collection" -> directory = true
                        "getcontentlength" -> size = parser.nextText().trim().toLongOrNull()
                    }
                    if (event == org.xmlpull.v1.XmlPullParser.END_TAG && parser.name == "response") {
                        href?.let { value ->
                            val target = base.resolve(value) ?: error("WebDAV 返回无效路径")
                            val resolved = davUrl(c, target.toString()).toHttpUrl()
                            if (resolved.encodedPath.trimEnd('/') != current) {
                                val relative = resolved.encodedPath.removePrefix(base.encodedPath)
                                onEntry(relative, directory, size)
                            }
                        }
                    }
                }
                event = parser.nextToken()
            }
            check(closed) { "WebDAV 目录响应不完整，已保存批次，请继续同步" }
        }
    }
    suspend fun test() = operation {
        val c = _config.value
        check(c.enabled) { "请先启用 NAS" }
        if (c.type == "Navidrome") api(c, "ping") else dav(c, "", collect = false)
        _status.value = NasStatus(message = "连接成功")
    }
    private suspend fun operation(block: suspend () -> Unit) {
        if (!mutex.tryLock()) return
        try {
            _status.value = NasStatus(busy = true, message = "正在连接 NAS…")
            withContext(Dispatchers.IO) { block() }
        } catch (e: kotlinx.coroutines.CancellationException) { _status.value = NasStatus(message = "已取消"); throw e }
        catch (e: Exception) { _status.value = NasStatus(message = "操作失败：" + when (e) {
            is java.net.SocketTimeoutException, is java.io.InterruptedIOException -> "连接超时，请检查 NAS 地址、端口及手机是否能访问 NAS"
            is java.net.UnknownHostException -> "无法解析服务器地址，请检查域名或改用 NAS 局域网 IP"
            is java.net.ConnectException -> "无法连接服务器，请检查端口、服务是否开启及所在网络"
            is javax.net.ssl.SSLException -> "HTTPS 证书验证失败，请检查 NAS 证书与访问域名"
            else -> if (_config.value.managed) "NAS 同步失败，请检查网络后重试" else e.message?.takeUnless { it.contains("http") && it.contains("?") } ?: "NAS 连接失败，请检查网络和配置"
        }) }
        finally { mutex.unlock() }
    }
    private fun song(c: NasConfig, path: String, title: String, artist: String = "未知艺术家", album: String = "未知专辑", duration: Long = 0, size: Long? = null, mime: String? = null): SongEntity {
        val ref = reference(c, path)
        return SongEntity(stableSongId(ref), title, artist, album, duration, ref, mime, fileName = Uri.decode(path.substringAfterLast('/')), fileSize = size,
            albumArtUri = null, sourceFolderUri = "nas://${c.id}", dateAdded = System.currentTimeMillis())
    }
    private fun fromJson(c: NasConfig, json: JSONObject, fetchArtwork: Boolean = true): SongEntity {
        val indexed = song(c, json.getString("id"), json.optString("title"), json.optString("artist", "未知艺术家"), json.optString("album", "未知专辑"), json.optLong("duration") * 1000, json.optLong("size").takeIf { it > 0 }, json.optString("contentType").takeIf { it.isNotBlank() })
        val art = json.optString("coverArt").takeUnless { it.isBlank() || it == "null" }?.let { id ->
            val directory = java.io.File(context.cacheDir, "nas_art").apply { mkdirs() }
            val file = java.io.File(directory, "${stableSongId("${c.id}:$id")}.jpg")
            if (fetchArtwork && !file.isFile) runCatching {
                client.newCall(Request.Builder().url(apiUrl(c, "getCoverArt", mapOf("id" to id, "size" to "600"))).build()).execute().use { response ->
                    if (response.isSuccessful && response.header("Content-Type").orEmpty().startsWith("image/")) {
                        val bytes = response.body!!.byteStream().use { input ->
                            val output = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            var total = 0
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                total += read
                                check(total <= 10_000_000) { "封面过大" }
                                output.write(buffer, 0, read)
                            }
                            output.toByteArray()
                        }
                        if (bytes.size in 1..10_000_000) file.writeBytes(bytes)
                    }
                }
            }
            file.absolutePath.takeIf { file.isFile }
        }
        return indexed.copy(albumArtUri = art, fileName = json.optString("path").substringAfterLast('/').takeIf { it.isNotBlank() })
    }
    private suspend fun merge(songs: List<SongEntity>, preserveMetadata: Boolean = false) {
        val dao = database.songDao()
        songs.chunked(400).forEach { batch ->
            val old = dao.findByMediaIds(batch.map { it.mediaStoreId }).associateBy { it.mediaStoreId }
            dao.upsertAll(batch.map { s -> old[s.mediaStoreId]?.let {
                val metadata = if (preserveMetadata) s.copy(title = it.title, artist = it.artist, album = it.album,
                    durationMs = it.durationMs, mimeType = it.mimeType ?: s.mimeType, codec = it.codec,
                    bitrate = it.bitrate, sampleRate = it.sampleRate, bitDepth = it.bitDepth, channelCount = it.channelCount, audioQuality = it.audioQuality) else s
                metadata.copy(isFavorite = it.isFavorite, playCount = it.playCount, lastPlayedAt = it.lastPlayedAt, dateAdded = it.dateAdded, nasScanToken = s.nasScanToken ?: it.nasScanToken, albumArtUri = s.albumArtUri ?: it.albumArtUri)
            } ?: s })
        }
    }
    /** Every batch is durable. An interrupted directory is safely replayed using stable IDs. */
    suspend fun sync(detailed: Boolean = false, automatic: Boolean = false) = withContext(Dispatchers.IO) {
        if (!mutex.tryLock()) return@withContext
        val c = _config.value
        val dao = database.nasSyncDao()
        try {
            var task = dao.task(c.id)
            check(c.enabled && c.address.isNotBlank()) { "请先配置并启用 NAS" }
            if (automatic && (task == null || task.state !in listOf("running", "waiting"))) return@withContext
            if (task == null || task.state == "complete") {
                task = NasSyncTask(c.id, UUID.randomUUID().toString(), detailed)
                database.withTransaction {
                    dao.clear(c.id)
                    dao.save(task!!)
                    dao.enqueue(listOf(NasSyncItem(c.id, 0, if (c.type == "Navidrome") "0" else "")))
                }
            }
            var active = task!!.copy(state = "running")
            dao.save(active)
            NasSyncScheduler.schedule(context)
            _status.value = NasStatus(true, "正在恢复后台同步，已入库 ${active.count} 首", active.count, true)
            while (true) {
                currentCoroutineContext().ensureActive()
                if (dao.task(c.id)?.state == "paused") {
                    val paused = dao.task(c.id)!!
                    _status.value = NasStatus(message = paused.message, count = paused.count, resumable = true)
                    return@withContext
                }
                check(_config.value == c) { "配置已更改，请重新同步" }
                val item = dao.next(c.id, System.currentTimeMillis()) ?: break
                try {
                    if (item.kind == 1) {
                        val indexed = database.songDao().findById(stableSongId(reference(c, item.path)))
                        if (indexed != null) {
                            val enriched = if (c.type == "WebDAV") readDavTags(indexed) else {
                                val json = api(c, "getSong", mapOf("id" to item.path)).getJSONObject("song")
                                fromJson(c, json).copy(nasScanToken = indexed.nasScanToken)
                            }
                            database.withTransaction { merge(listOf(enriched)); dao.save(item.copy(done = true)) }
                        } else dao.save(item.copy(done = true))
                    } else if (c.type == "Navidrome") {
                        val offset = item.path.toInt()
                        val result = api(c, "search3", mapOf("query" to "", "songCount" to "300", "songOffset" to "$offset", "artistCount" to "0", "albumCount" to "0"))
                        val array = result.optJSONObject("searchResult3")?.optJSONArray("song")
                        val batch = if (array == null) emptyList() else (0 until array.length()).map { fromJson(c, array.getJSONObject(it), false).copy(nasScanToken = active.runId) }
                        database.withTransaction {
                            val enrich = if (active.detailed) enrichmentItems(c, batch) else emptyList()
                            merge(batch)
                            dao.enqueue(enrich)
                            if (batch.isNotEmpty()) dao.enqueue(listOf(NasSyncItem(c.id, 0, "${offset + batch.size}")))
                            dao.save(item.copy(done = true))
                        }
                    } else {
                        val batch = mutableListOf<SongEntity>()
                        val children = mutableListOf<NasSyncItem>()
                        suspend fun flush() {
                            currentCoroutineContext().ensureActive()
                            check(_config.value == c) { "配置已更改，请重新同步" }
                            database.withTransaction {
                                val enrich = if (active.detailed) enrichmentItems(c, batch) else emptyList()
                                merge(batch, preserveMetadata = true)
                                dao.enqueue(children)
                                dao.enqueue(enrich)
                            }
                            batch.clear(); children.clear()
                        }
                        dav(c, item.path) { file, directory, size ->
                            if (directory) children += NasSyncItem(c.id, 0, file)
                            else if (file.substringAfterLast('.').lowercase() in setOf("mp3", "m4a", "aac", "flac", "ogg", "opus", "wav", "wma")) {
                                val name = Uri.decode(file.substringAfterLast('/')).substringBeforeLast('.')
                                val parts = name.split(" - ", limit = 2)
                                batch += song(c, file, parts.last(), if (parts.size == 2) parts.first() else "未知艺术家",
                                    Uri.decode(file.substringBeforeLast('/', "未知专辑").substringAfterLast('/')), size = size,
                                    mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.substringAfterLast('.').lowercase())).copy(nasScanToken = active.runId)
                            }
                            if (batch.size + children.size >= 300) { flush(); publishProgress(c, active, dao) }
                        }
                        flush()
                        dao.save(item.copy(done = true))
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (error: Exception) {
                    val retryable = error is java.io.IOException && (error !is NasHttpException || error.code == 429 || error.code >= 500 || error.code == 408)
                    dao.save(item.copy(attempts = item.attempts + 1, retryAt = System.currentTimeMillis() + if (retryable) (30_000L * (1L shl item.attempts.coerceAtMost(7))).coerceAtMost(3_600_000L) else 86_400_000L))
                    if (!retryable) throw error
                }
                active = publishProgress(c, active, dao)
            }
            if (dao.task(c.id)?.state == "paused") return@withContext
            if (dao.pending(c.id) > 0) {
                val waiting = active.copy(state = "waiting", message = "等待网络重试 · 已入库 ${active.count} 首，可继续听歌")
                dao.save(waiting)
                _status.value = NasStatus(message = waiting.message, count = waiting.count, resumable = true)
                NasSyncScheduler.schedule(context)
            } else {
                val count = database.songDao().scannedCount("nas://${c.id}", active.runId)
                val missing = database.songDao().missingCount("nas://${c.id}", active.runId)
                val message = "同步完成 · $count 首" + if (missing > 0) "；$missing 首历史歌曲本轮未发现，已保留" else ""
                check(_config.value == c) { "配置已更改，请重新同步" }
                save(c.copy(syncedAt = System.currentTimeMillis()))
                dao.save(active.copy(state = "complete", message = message, count = count))
                _status.value = NasStatus(message = message, count = count)
                completeLocalSetup()
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                val latest = dao.task(c.id)
                if (latest != null && latest.state !in listOf("paused", "complete")) {
                    dao.save(latest.copy(state = "waiting", message = "同步中断，已保存进度，将自动继续"))
                    NasSyncScheduler.schedule(context)
                }
                _status.value = NasStatus(message = dao.task(c.id)?.message ?: "已保存同步进度", count = latest?.count ?: 0, resumable = true)
            }
            throw cancelled
        } catch (error: Exception) {
            val latest = dao.task(c.id)
            val message = "同步暂止，进度已保存；请检查网络、账户后继续"
            if (latest != null) dao.save(latest.copy(state = "paused", message = message))
            _status.value = NasStatus(message = message, count = latest?.count ?: 0, resumable = latest != null)
        } finally { mutex.unlock() }
    }

    private suspend fun publishProgress(c: NasConfig, task: NasSyncTask, dao: NasSyncDao): NasSyncTask {
        val count = database.songDao().scannedCount("nas://${c.id}", task.runId)
        val pending = dao.pending(c.id)
        val updated = task.copy(count = count, message = "后台同步 · 已入库 $count 首 · 待处理 $pending 项，可边听边同步")
        val published = database.withTransaction {
            if (dao.task(c.id)?.state == "paused") false else { dao.save(updated); true }
        }
        if (!published) return task
        _status.value = NasStatus(true, updated.message, count, true)
        return updated
    }

    private suspend fun enrichmentItems(c: NasConfig, batch: List<SongEntity>): List<NasSyncItem> {
        val previous = database.songDao().findByMediaIds(batch.map { it.mediaStoreId }).associateBy { it.mediaStoreId }
        return batch.filter { song ->
            val old = previous[song.mediaStoreId]
            old == null || old.durationMs == 0L || old.fileSize != song.fileSize || (c.type == "Navidrome" && old.albumArtUri == null)
        }.map { NasSyncItem(c.id, 1, Uri.parse(it.uri).pathSegments.single()) }
    }

    suspend fun pauseSync() = withContext(Dispatchers.IO) {
        val dao = database.nasSyncDao()
        dao.task(_config.value.id)?.let {
            dao.save(it.copy(state = "paused", message = "同步已暂停 · 已入库 ${it.count} 首，可随时继续"))
        }
        NasSyncScheduler.cancel(context)
    }

    suspend fun prepareResume() = withContext(Dispatchers.IO) {
        val dao = database.nasSyncDao()
        dao.task(_config.value.id)?.let { task ->
            if (task.state != "complete") {
                dao.save(task.copy(state = "running"))
                dao.resetRetries(task.sourceId)
            }
        }
        NasSyncScheduler.schedule(context)
    }
    suspend fun search(query: String): List<SongEntity> = withContext(Dispatchers.IO) {
        val c = _config.value
        check(c.enabled && c.address.isNotBlank()) { "请先在设置中配置并启用 NAS" }
        if (c.type == "WebDAV") {
            database.songDao().searchNas("nas://${c.id}", query)
        } else {
            val array = api(c, "search3", mapOf("query" to query, "songCount" to "200", "artistCount" to "0", "albumCount" to "0")).optJSONObject("searchResult3")?.optJSONArray("song")
            val songs = if (array == null) emptyList() else (0 until array.length()).map { fromJson(c, array.getJSONObject(it)) }
            database.withTransaction { merge(songs) }
            songs
        }
    }
    suspend fun siblingLyrics(song: SongEntity): String? = withContext(Dispatchers.IO) {
        if (_config.value.type != "WebDAV") return@withContext null
        val uri = Uri.parse(song.uri)
        if (uri.authority != _config.value.id || !_config.value.enabled) return@withContext null
        val path = uri.pathSegments.single().substringBeforeLast('.') + ".lrc"
        runCatching { open(reference(_config.value, path)).use {
            life.xstar.music.player.data.lyrics.LyricsTextDecoder.decode(it.readBytes()).take(1_000_000)
        } }.getOrNull()
    }
    private fun readDavTags(song: SongEntity): SongEntity {
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            val (url, headers) = resolve(song.uri)
            retriever.setDataSource(url, headers)
            fun tag(key: Int) = retriever.extractMetadata(key)?.takeUnless { it.isBlank() || it == "<unknown>" }
            val artwork = retriever.embeddedPicture?.takeIf { it.size <= 10_000_000 }?.let { bytes ->
                val dir = java.io.File(context.cacheDir, "nas_art").apply { mkdirs() }
                java.io.File(dir, "${song.mediaStoreId}.jpg").apply { writeBytes(bytes) }.absolutePath
            }
            song.copy(title = tag(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE) ?: song.title,
                artist = tag(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: song.artist,
                album = tag(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: song.album,
                durationMs = tag(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0,
                mimeType = tag(android.media.MediaMetadataRetriever.METADATA_KEY_MIMETYPE), albumArtUri = artwork)
        } catch (_: Exception) { song }
        finally { retriever.release() }
    }
}
