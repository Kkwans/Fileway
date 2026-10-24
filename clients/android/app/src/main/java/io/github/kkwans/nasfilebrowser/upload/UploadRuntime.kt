package io.github.kkwans.nasfilebrowser.upload

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import io.github.kkwans.nasfilebrowser.core.EmbeddedNetwork
import io.github.kkwans.nasfilebrowser.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONObject
import java.io.InputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class UploadTransfer(val id: String, val bytes: Long, val accepted: Long, val progress: Long, val elapsedMillis: Long)
internal data class UploadCancellation(val completed: Boolean, val notice: String)
private data class UploadReply(val status: Int, val headers: Headers)

/** Stream through the original Go network/account. The existing OkHttp stack
 * supplies body/cancellation; only this NAS's TUS control flow is orchestrated. */
class UploadRuntime private constructor(private val context: Context) {
    private val database = ClientDatabase.get(context)
    private val dao = database.uploads()
    private val store = ProfileStore(database, CredentialVault(context))
    private val network = EmbeddedNetwork(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val streams = Semaphore(2)
    private val running = ConcurrentHashMap<String, Job>()
    private val cleaning = ConcurrentHashMap<String, Job>()
    private val calls = ConcurrentHashMap<String, Call>()
    private val inputs = ConcurrentHashMap<String, InputStream>()
    private val active = ConcurrentHashMap<String, Pair<NasSession, PreviewLease>>()
    private val baselines = ConcurrentHashMap<String, Long>()
    private val client = OkHttpClient.Builder().cache(null).followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS).build()

    fun launch(id: String, finished: () -> Unit): Boolean = synchronized(running) {
        if (running[id]?.isCompleted == false) return@synchronized false
        val work = scope.launch(start = CoroutineStart.LAZY) {
            try { streams.withPermit { write(id) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { runCatching { dao.command(id, "failed", System.currentTimeMillis()) } }
            finally {
                val own = currentCoroutineContext()[Job]
                synchronized(running) { if (running[id] === own) running.remove(id) }
                runCatching(finished)
            }
        }
        running[id] = work; work.start(); true
    }
    fun cancel(id: String) {
        running[id]?.cancel(); calls[id]?.cancel()
        inputs[id]?.let { source -> scope.launch { runCatching { source.close() }; inputs.remove(id, source) } }
    }
    fun isRunning(id: String) = running[id]?.isCompleted == false
    suspend fun awaitStopped(id: String) { running[id]?.join() }
    internal fun isCanceling(id: String) = cleaning[id]?.isActive == true
    internal suspend fun cancelTask(id: String, forRestart: Boolean = false, expectedGeneration: Long? = null): String {
        val owner = currentCoroutineContext()[Job] ?: error("取消请求不可用")
        check(cleaning.putIfAbsent(id, owner) == null) { "此上传正在清理，请稍候" }
        try {
            val now = System.currentTimeMillis()
            val claimed = if (expectedGeneration == null) dao.beginCancel(id, now) else dao.beginCancelAtGeneration(id, expectedGeneration, now)
            check(claimed == 1) { "上传已完成、状态已变化或正在清理，请刷新记录" }
            val item = dao.get(id) ?: error("上传记录不存在")
            UploadScheduler.stop(context, item)
            try {
                withTimeout(30_000) { awaitStopped(item.id) }
                val result = cancelRemote(item, forRestart)
                check(dao.canceled(item.id, item.generation, if (result.completed) "completed" else "canceled",
                    if (result.completed) item.expectedSize else item.uploaded, "", System.currentTimeMillis()) == 1) { "上传记录已变化，请重新核对" }
                return result.notice
            } catch (failure: Exception) {
                withContext(NonCancellable) { dao.canceled(item.id, item.generation, "cancel_failed", item.uploaded,
                    "本机上传已停止；${failure.message ?: "服务器清理未确认"}。可重试清理，原文件保留。", System.currentTimeMillis()) }
                throw failure
            }
        } finally { cleaning.remove(id, owner) }
    }
    /** Cleanup uses only the durable original destination, never a local source
     * URI or the server currently selected in the browser. */
    internal suspend fun cancelRemote(record: UploadRecord, forRestart: Boolean = false): UploadCancellation {
        if (record.protocol != "tus") return UploadCancellation(false, "本机上传已取消，服务器若已收到完整文件会保留")
        val profile = store.profile(record.profileId) ?: error("原服务器档案已移除，未删除服务器文件")
        check(profile.sourceRevision == record.sourceRevision) { "原服务器来源已变化，未向其他服务器发送清理请求" }
        val account = store.account(record.accountKey) ?: error("原账号已移除，未删除服务器文件")
        check(account.profileId == profile.id && account.sourceRevision == record.sourceRevision) { "原账号与服务器来源不匹配" }
        if (profile.network == ConnectionMode.TAILNET) check(network.start().connected) { "请先连接原内嵌网络，再重试清理" }
        val api = restoreSavedSession(store, profile, account, "/api/resources/").api
        var lease: PreviewLease? = null
        val operation = "cleanup-${record.id}-${java.util.UUID.randomUUID()}"
        try {
            api.persistTokens { token -> store.refreshToken(profile, account, token); Unit }
            val permissions = api.permissions()
            check(permissions.delete) { "原账号没有清理权限；本机上传已停止，可在权限恢复后重试" }
            if (forRestart) check(permissions.create && (!record.overwrite || permissions.modify)) { "原账号没有重新上传或覆盖权限，旧片段保留" }
            val capability = api.upload(record.targetWire, record.expectedSize, "fileway-" + record.id, "tus", record.overwrite, true)
            lease = capability
            val headers = mapOf("Tus-Resumable" to "1.0.0")
            suspend fun inspect(): UploadReply {
                val reply = send(operation, api, capability, "HEAD", headers = headers)
                // Legacy Fileway tracked only paths and could delete a later
                // replacement. Never issue DELETE without its modern protocol
                // acknowledgement and the original transfer-id capability.
                check(reply.headers["Tus-Resumable"] == "1.0.0") { "服务器不提供可验证的会话清理；已停止上传，服务器文件保留" }
                if (reply.status in setOf(404, 410)) return reply
                checkReply(reply, setOf(200))
                val offset = reply.headers["Upload-Offset"]?.toLongOrNull()
                check(reply.headers["Upload-Length"]?.toLongOrNull() == record.expectedSize &&
                    offset != null && offset in 0..record.expectedSize) { "原上传身份或长度已变化，未删除服务器文件" }
                return reply
            }
            suspend fun completed(reply: UploadReply): Boolean {
                if (reply.status != 200 || reply.headers["Upload-Offset"]?.toLongOrNull() != record.expectedSize) return false
                verifyCompleted(api, record)
                return true
            }
            val before = inspect()
            if (before.status in setOf(404, 410)) return UploadCancellation(false, "上传已取消，原未完成片段已不存在")
            if (completed(before)) return UploadCancellation(true, "服务器已收到完整文件，已确认完成并保留")
            val result = send(operation, api, capability, "DELETE", headers = headers)
            check(result.headers["Tus-Resumable"] == "1.0.0") { "服务器未确认会话清理，原文件保留，请重试核对" }
            if (result.status in setOf(204, 404, 410)) return UploadCancellation(false, "上传已取消，仅清理本次未完成片段，本机原文件保留")
            if (result.status == 409 && completed(inspect())) return UploadCancellation(true, "服务器刚完成上传，完整文件已保留")
            checkReply(result, setOf(204))
            error("服务器未确认取消，请重试核对")
        } finally { withContext(NonCancellable) {
            calls.remove(operation)?.cancel()
            runCatching { api.token() }; runCatching { lease?.release() }; runCatching { api.close() }
        } }
    }
    suspend fun transfer(id: String): UploadTransfer? {
        val (api, lease) = active[id] ?: return null
        val stats = api.uploadStatistics(lease)
        val bytes = stats.getLong("sentBytes"); val accepted = stats.getLong("acceptedOffset")
        return UploadTransfer(id, bytes, accepted, maxOf(accepted, (baselines[id] ?: 0) + bytes), stats.getLong("elapsedMillis"))
    }
    private suspend fun send(id: String, api: NasSession, lease: PreviewLease, method: String, body: RequestBody? = null,
        headers: Map<String, String> = emptyMap()): UploadReply {
        val reply = suspendCancellableCoroutine<UploadReply> { continuation ->
            val uri = Uri.parse(lease.url)
            require(uri.scheme == "http" && uri.host == "127.0.0.1") { "上传来源已失效" }
            val request = Request.Builder().url(lease.url).method(method, body).apply { headers.forEach { (key, value) -> header(key, value) } }.build()
            val call = client.newCall(request); calls[id] = call
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    calls.remove(id, call)
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use { value ->
                        calls.remove(id, call)
                        if (continuation.isActive) continuation.resume(UploadReply(value.code, value.headers))
                    }
                }
            })
        }
        // Headers have arrived and core may already have renewed the token.
        // A local persistence error must not erase a POST/PATCH acknowledgement.
        try { api.synchronizeSession() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* The bound session exposes its own health warning. */ }
        return reply
    }
    private fun checkReply(reply: UploadReply, statuses: Set<Int>) {
        if (reply.status in statuses) return
        throw ServiceException(reply.status, when (reply.status) {
            401 -> "原账号登录已失效，请重新登录后继续"
            403 -> "原账号没有上传或覆盖权限"
            404, 410 -> "服务器上的未完成上传已过期或不存在，请核对后重新上传"
            409 -> "目标或上传进度已变化，请核对服务器文件；不会自动覆盖"
            else -> "上传未得到有效确认，已确认的进度保留，请重试或核对服务器文件"
        })
    }
    private fun body(input: InputStream, length: Long, mime: String, owner: Job) = object : RequestBody() {
        override fun contentType() = mime.toMediaType()
        override fun contentLength() = length
        override fun isOneShot() = true
        override fun writeTo(sink: BufferedSink) {
            var remaining = length; val buffer = ByteArray(64 * 1024)
            while (remaining > 0) {
                owner.ensureActive()
                val count = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                check(count > 0) { "本机源文件读取不完整，请重新选择" }
                sink.write(buffer, 0, count); remaining -= count
            }
        }
    }
    private suspend fun metadata(api: NasSession, wire: String): JSONObject? = try { api.request("GET", "/api/resources$wire?metadata=1") }
        catch (failure: ServiceException) { if (failure.status == 404) null else throw failure }
    private suspend fun verifyCompleted(api: NasSession, record: UploadRecord) {
        val info = metadata(api, record.targetWire) ?: error("上传响应已结束，但目标文件暂时无法读取，请核对")
        check(!info.getBoolean("isDir") && info.getLong("size") == record.expectedSize &&
            resourceWireBytes(info.optString("wirePath").ifEmpty { SearchResult.encodePath(info.getString("path")) }).contentEquals(resourceWireBytes(record.targetWire))) {
            "服务器文件身份或长度不匹配，未将上传标记为完成"
        }
    }
    private suspend fun write(id: String) {
        val record = database.withTransaction {
            if (dao.claim(id, System.currentTimeMillis()) != 1) return@withTransaction null
            dao.get(id)
        } ?: return
        io.github.kkwans.nasfilebrowser.data.withTransferTiming({ dao.addRuntime(id, it) }) { writeClaimed(record) }
    }
    private suspend fun writeClaimed(record: UploadRecord) {
        val id = record.id
        var api: NasSession? = null; var lease: PreviewLease? = null
        try {
            val local = UploadSources(context).read(Uri.parse(record.sourceUri))
            check(local.name == record.name && local.size == record.expectedSize && local.modified == record.sourceModified) { "本机源文件已变化，请创建新的上传" }
            if (record.remoteCreated && record.sourceModified == 0L) error("此来源没有可靠修改标识，不能安全追加；请核对后重新选择本机文件")
            val profile = store.profile(record.profileId) ?: error("原服务器档案已移除，本机原文件仍保留")
            check(profile.sourceRevision == record.sourceRevision) { "原服务器地址或模式已修改，不能续传到其他来源" }
            val account = store.account(record.accountKey) ?: error("原账号已移除，本机原文件仍保留")
            if (profile.network == ConnectionMode.TAILNET) check(network.start().connected) { "请先完成原内嵌网络登录后继续" }
            api = restoreSavedSession(store, profile, account, "/api/resources${record.parentWire}?metadata=1").api
            api.persistTokens { token -> store.refreshToken(profile, account, token); Unit }
            check(api.permissions().create && (!record.overwrite || api.permissions().modify)) { "原账号没有上传或覆盖权限" }
            if (!record.remoteCreated) {
                val existing = metadata(api, record.targetWire)
                check(existing == null || record.overwrite && !existing.getBoolean("isDir") &&
                    record.replacedIdentity == "${existing.getLong("size")}/${existing.optString("modified")}") { "目标已存在或已变化，请核对；不会自动覆盖" }
            }
            val batch = JSONObject()
            if (record.batchId.isNotEmpty()) {
                batch.put("X-Upload-Batch-ID", record.batchId).put("X-Upload-Batch-Name", Uri.encode(record.batchName))
                    .put("X-Upload-Batch-Items", record.batchItems.toString()).put("X-Upload-Batch-Bytes", record.batchBytes.toString())
                    .put("X-Upload-Folder", record.folderUpload.toString())
            }
            lease = api.upload(record.targetWire, record.expectedSize, "fileway-" + record.id, record.protocol, record.overwrite, record.remoteCreated, batch)
            active[id] = api to lease
            val input = context.contentResolver.openInputStream(Uri.parse(record.sourceUri)) ?: error("无法读取本机原文件，请检查授权")
            inputs[id] = input
            input.use { source ->
                val owner = currentCoroutineContext()[Job]!!
                if (record.protocol == "resources") {
                    baselines[id] = 0
                    val reply = send(id, api, lease, "POST", body(source, record.expectedSize, record.mime, owner))
                    checkReply(reply, setOf(200))
                    check(dao.progress(id, record.generation, record.expectedSize, System.currentTimeMillis()) == 1)
                } else {
                    var offset = 0L
                    if (record.remoteCreated) {
                        val reply = send(id, api, lease, "HEAD", headers = mapOf("Tus-Resumable" to "1.0.0"))
                        checkReply(reply, setOf(200, 204))
                        offset = reply.headers["Upload-Offset"]?.toLongOrNull() ?: error("服务器未返回可靠上传位置")
                        check(reply.headers["Upload-Length"]?.toLongOrNull() == record.expectedSize && offset in 0..record.expectedSize) { "原上传的文件长度或位置已变化" }
                    } else {
                        val reply = send(id, api, lease, "POST", ByteArray(0).toRequestBody(), mapOf("Upload-Length" to record.expectedSize.toString(), "Tus-Resumable" to "1.0.0"))
                        checkReply(reply, setOf(201)); check(reply.headers["Location"] == lease.url) { "上传位置与原来源不一致，请核对" }
                        check(dao.created(id, record.generation, System.currentTimeMillis()) == 1)
                        // A duplicate creation acknowledgement can belong to
                        // an already started session; never assume offset zero.
                        val head = send(id, api, lease, "HEAD", headers = mapOf("Tus-Resumable" to "1.0.0"))
                        checkReply(head, setOf(200, 204))
                        offset = head.headers["Upload-Offset"]?.toLongOrNull() ?: error("服务器未确认上传初始位置")
                        check(head.headers["Upload-Length"]?.toLongOrNull() == record.expectedSize && offset in 0..record.expectedSize) { "原上传的文件长度或位置已变化" }
                    }
                    check(dao.progress(id, record.generation, offset, System.currentTimeMillis()) == 1)
                    baselines[id] = offset
                    var skipped = 0L; val discard = ByteArray(64 * 1024)
                    while (skipped < offset) {
                        currentCoroutineContext().ensureActive()
                        val fast = try { source.skip(offset - skipped) } catch (_: IOException) { 0 }
                        if (fast > 0) { skipped += fast; continue }
                        val count = source.read(discard, 0, minOf(discard.size.toLong(), offset - skipped).toInt())
                        check(count > 0) { "本机原文件不足，未继续追加" }; skipped += count
                    }
                    while (offset < record.expectedSize) {
                        currentCoroutineContext().ensureActive(); api.token()
                        val length = minOf(2L * 1024 * 1024, record.expectedSize - offset)
                        val reply = send(id, api, lease, "PATCH", body(source, length, "application/offset+octet-stream", owner),
                            mapOf("Upload-Offset" to offset.toString(), "Tus-Resumable" to "1.0.0"))
                        checkReply(reply, setOf(204))
                        val acknowledged = reply.headers["Upload-Offset"]?.toLongOrNull()
                        check(acknowledged == offset + length) { "服务器未确认完整片段，请核对续传位置" }
                        offset += length
                        check(dao.progress(id, record.generation, offset, System.currentTimeMillis()) == 1)
                    }
                }
            }
            currentCoroutineContext().ensureActive(); api.token(); verifyCompleted(api, record)
            val after = UploadSources(context).read(Uri.parse(record.sourceUri))
            check(after.size == record.expectedSize && after.modified == record.sourceModified) { "本机来源在上传中发生变化，请核对服务器文件；未标记完成" }
            dao.finish(id, record.generation, "completed", "", System.currentTimeMillis())
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { dao.finish(id, record.generation, "interrupted", "上传已停止，本机原文件保留；可从服务器已确认位置继续", System.currentTimeMillis()) }
            throw cancelled
        } catch (failure: Exception) {
            withContext(NonCancellable) { dao.finish(id, record.generation, if (failure is ServiceException && failure.status in setOf(404, 410)) "expired" else "failed",
                failure.message ?: "上传失败，本机原文件保留", System.currentTimeMillis()) }
        } finally {
            inputs.remove(id)?.let { runCatching { it.close() } }; calls.remove(id)?.cancel(); active.remove(id); baselines.remove(id)
            withContext(NonCancellable) { runCatching { api?.token() }; runCatching { lease?.release() }; runCatching { api?.close() } }
        }
    }
    companion object {
        @Volatile private var instance: UploadRuntime? = null
        fun get(context: Context): UploadRuntime = instance ?: synchronized(this) {
            instance ?: UploadRuntime(context.applicationContext).also { instance = it }
        }
    }
}
