package io.github.kkwans.nasfilebrowser.download

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.room.withTransaction
import io.github.kkwans.nasfilebrowser.core.EmbeddedNetwork
import io.github.kkwans.nasfilebrowser.core.NativeTransport
import io.github.kkwans.nasfilebrowser.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal class DownloadSource(val api: NasSession, val url: String, private val released: () -> Unit, private val asset: PreviewLease? = null) {
    suspend fun close() { try { if (asset != null) asset.release() else NativeTransport.call(JSONObject().put("op", "revoke").put("url", url)) } finally { try { runCatching { api.token() }; api.close() } finally { released() } } }
}
data class DownloadTransfer(val key: String, val bytes: Long, val elapsedMillis: Long)

/** One process-wide writer per task; Room generation fences callbacks after pause/retry. */
@androidx.annotation.OptIn(UnstableApi::class)
class DownloadRuntime private constructor(private val context: Context) {
    private val database = ClientDatabase.get(context)
    private val dao = database.downloads()
    private val store = ProfileStore(database, CredentialVault(context))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = ConcurrentHashMap<String, Job>()
    private val prefixes = ConcurrentHashMap<String, AtomicLong>()
    private val inputs = ConcurrentHashMap<String, androidx.media3.datasource.HttpDataSource>()
    private val sources = ConcurrentHashMap<String, Pair<String, DownloadSource>>()
    private val streams = Semaphore(2)
    private val network = EmbeddedNetwork(context)
    internal suspend fun source(record: DownloadRecord): DownloadSource {
        val profile = store.profile(record.profileId) ?: error("原服务器档案已移除，已下载文件仍保留")
        check(profile.sourceRevision == record.sourceRevision) { "下载来源已修改，不能追加旧文件，请创建新的下载" }
        val account = store.account(record.accountKey) ?: error("原账号已移除，已下载文件仍保留")
        check(store.token(profile, account) != null) { "原账号登录已失效，请重新登录后恢复下载" }
        if (profile.network == ConnectionMode.TAILNET) {
            val state = network.start()
            check(state.connected) { "内嵌网络尚未连接，请先完成登录或批准后恢复下载" }
        }
        NativeTransport.call(cacheCommand(context, readCacheSettings(context)))
        val wire = record.wirePath.ifEmpty { SearchResult.encodePath(record.path) }
        val saved = restoreSavedSession(store, profile, account, "/api/resources$wire?metadata=1")
        val api = saved.api
        try {
            api.persistTokens { renewed -> store.refreshToken(profile, account, renewed); Unit }
            val meta = saved.verification
            if (record.zipExport) {
                check(meta.getBoolean("isDir") && resourceWireBytes(meta.optString("wirePath")).contentEquals(resourceWireBytes(wire))) { "ZIP原父目录已变化，请重新创建打包任务" }
                check(api.permissions().download) { "原账号没有下载权限" }
                val lease = api.assetLease(zipExportEndpoint(record))
                val key = java.util.UUID.randomUUID().toString()
                return DownloadSource(api, lease.url, { sources.remove(key) }, lease).also { sources[key] = record.id to it }
            }
            check(!meta.getBoolean("isDir") && meta.getLong("size") == record.expectedSize && meta.optString("modified") == record.modified) {
                "源文件已变化，已下载部分保留，请新建下载"
            }
            check(api.permissions().download) { "原账号没有下载权限" }
            if (record.downloaded > 0) check(record.modified.isNotEmpty()) { "源服务未提供修改标识，无法安全追加，请新建下载" }
            val url = api.lease(record.path, record.wirePath, record.accountKey + "/download/" + record.identity)
            val key = java.util.UUID.randomUUID().toString()
            return DownloadSource(api, url, { sources.remove(key) }).also { sources[key] = record.id to it }
        } catch (failure: Throwable) { withContext(NonCancellable) { api.close() }; throw failure }
    }
    fun launch(id: String, finished: () -> Unit): Boolean = synchronized(running) {
        if (running[id]?.isCompleted == false) return@synchronized false
        val work = scope.launch(start = CoroutineStart.LAZY) {
            try { streams.withPermit { write(id) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                // A storage/claim failure must not crash the application process.
                runCatching { dao.command(id, "failed", System.currentTimeMillis()) }
            }
            finally {
                val own = currentCoroutineContext()[Job]
                synchronized(running) { if (running[id] === own) { prefixes.remove(id); running.remove(id) } }
                runCatching(finished)
            }
        }
        running[id] = work; work.start(); true
    }
    fun cancel(id: String) {
        val input = inputs[id]
        running[id]?.cancel()
        if (input != null) scope.launch { runCatching { input.close() }; inputs.remove(id, input) }
    }
    suspend fun awaitStopped(id: String) { running[id]?.join() }
    fun isRunning(id: String): Boolean = running[id]?.isCompleted == false
    fun prefix(record: DownloadRecord): Long = prefixes[record.id]?.get() ?: record.downloaded
    suspend fun transfers(id: String): List<DownloadTransfer> = sources.entries.filter { it.value.first == id }.mapNotNull { (key, entry) ->
        try {
            val stats = NativeTransport.call(JSONObject().put("op", "lease_stats").put("session", entry.second.api.id).put("url", entry.second.url)) as JSONObject
            entry.second.api.token()
            DownloadTransfer(key, stats.getLong("upstreamBytes"), stats.getLong("elapsedMillis"))
        } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
    }
    private suspend fun synchronizeHeaders(api: NasSession) {
        try { api.synchronizeSession() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Preserve a valid media response; session health is separate. */ }
    }
    private suspend fun write(id: String) {
        val record = database.withTransaction {
            if (dao.claim(id, System.currentTimeMillis()) != 1) return@withTransaction null
            dao.get(id)
        } ?: return
        io.github.kkwans.nasfilebrowser.data.withTransferTiming({ dao.addRuntime(id, it) }) { writeClaimed(record) }
    }
    private suspend fun writeClaimed(record: DownloadRecord) {
        val id = record.id
        if (record.zipExport) { writeZip(record); return }
        var input: androidx.media3.datasource.HttpDataSource? = null
        var access: DownloadSource? = null
        var target = record
        try {
            val destinations = DownloadTarget(context)
            if (record.localUri.isNotEmpty() && record.downloaded == record.expectedSize) {
                val bytes = context.contentResolver.openFileDescriptor(Uri.parse(record.localUri), "r")?.use { it.statSize }
                if (bytes == record.expectedSize) {
                    destinations.complete(record)
                    dao.finish(id, record.generation, "completed", "", System.currentTimeMillis())
                    runCatching { DownloadIndex.get(context).remove(record) }
                    return
                }
            }
            access = source(record)
            if (target.localUri.isEmpty()) {
                val uri = destinations.allocate(target)
                if (dao.allocated(id, record.generation, uri.toString(), System.currentTimeMillis()) != 1) {
                    destinations.delete(target.copy(localUri = uri.toString())); throw CancellationException()
                }
                target = target.copy(localUri = uri.toString())
            }
            if (record.name.substringAfterLast('.').lowercase() in setOf("mkv", "mp4", "m4v", "mov", "webm")) {
                try { DownloadIndex.get(context).prepare(target) }
                catch (failure: Exception) { if (failure is CancellationException) throw failure /* Full download remains available if metadata is unsupported. */ }
            }
            val descriptor = context.contentResolver.openFileDescriptor(Uri.parse(target.localUri), "rw") ?: throw IOException("无法写入下载文件，请检查目录授权")
            android.os.ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
                    // A crash may leave bytes after the last committed checkpoint.
                    // Keep only a verified contiguous prefix before continuing.
                    val available = output.channel.size()
                    val start = minOf(record.downloaded, available)
                    output.channel.truncate(start); output.channel.position(start)
                    val prefix = AtomicLong(start); prefixes[id] = prefix
                    check(dao.progress(id, record.generation, start, System.currentTimeMillis()) == 1)
                    if (start < record.expectedSize) {
                        currentCoroutineContext().ensureActive()
                        input = DefaultHttpDataSource.Factory().setConnectTimeoutMs(15_000).setReadTimeoutMs(30_000).createDataSource()
                        inputs[id] = input!!
                        val length = input!!.open(DataSpec.Builder().setUri(access!!.url).setPosition(start).build())
                        synchronizeHeaders(access!!.api)
                        if (length != C.LENGTH_UNSET.toLong()) check(start + length == record.expectedSize) { "文件长度已变化，不能追加" }
                    }
                    val buffer = ByteArray(128 * 1024); var bytes = start; var lastCheckpoint = 0L
                    while (bytes < record.expectedSize) {
                        currentCoroutineContext().ensureActive()
                        val count = input!!.read(buffer, 0, buffer.size)
                        if (count == C.RESULT_END_OF_INPUT) break
                        check(bytes + count <= record.expectedSize) { "接收的数据超过源文件长度" }
                        output.write(buffer, 0, count); bytes += count; prefix.set(bytes)
                        val now = System.currentTimeMillis()
                        if (now - lastCheckpoint >= 1000) {
                            output.fd.sync()
                            check(dao.progress(id, record.generation, bytes, now) == 1) { "下载操作已被替换" }
                            access!!.api.token()
                            lastCheckpoint = now
                        }
                    }
                    check(bytes == record.expectedSize) { "文件未完整接收，已保存的部分可以继续下载" }
                    output.fd.sync()
                    check(dao.progress(id, record.generation, bytes, System.currentTimeMillis()) == 1)
            }
            currentCoroutineContext().ensureActive()
            access?.api?.token()
            destinations.complete(target)
            dao.finish(id, record.generation, "completed", "", System.currentTimeMillis())
            runCatching { DownloadIndex.get(context).remove(record) }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { dao.finish(id, record.generation, "interrupted", "任务被暂停或系统停止，已保存的部分保留", System.currentTimeMillis()) }
            throw cancelled
        } catch (failure: Exception) {
            val cancelled = !currentCoroutineContext().isActive
            withContext(NonCancellable) { dao.finish(id, record.generation,
                if (cancelled) "interrupted" else "failed", failure.message ?: "下载失败，已保存的部分保留", System.currentTimeMillis()) }
        } finally {
            inputs.remove(id)
            try { runCatching { input?.close() } } finally { withContext(NonCancellable) { runCatching { access?.close() } } }
        }
    }
    private suspend fun writeZip(record: DownloadRecord) {
        var input: androidx.media3.datasource.HttpDataSource? = null
        var access: DownloadSource? = null
        var target = record
        try {
            val destinations = DownloadTarget(context)
            access = source(record)
            if (target.localUri.isEmpty()) {
                val uri = destinations.allocate(target)
                if (dao.allocated(record.id, record.generation, uri.toString(), System.currentTimeMillis()) != 1) {
                    destinations.delete(target.copy(localUri = uri.toString())); throw CancellationException()
                }
                target = target.copy(localUri = uri.toString())
            }
            val descriptor = context.contentResolver.openFileDescriptor(Uri.parse(target.localUri), "rw") ?: throw IOException("无法写入ZIP，请检查下载目录授权")
            var total = 0L
            android.os.ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
                // ZIP is generated anew each time. Never append another archive
                // onto the partial bytes left by pause/process interruption.
                output.channel.truncate(0); output.channel.position(0)
                check(dao.progress(record.id, record.generation, 0, System.currentTimeMillis()) == 1) { "ZIP下载已被替换" }
                val prefix = AtomicLong(0); prefixes[record.id] = prefix
                input = DefaultHttpDataSource.Factory().setConnectTimeoutMs(15_000).setReadTimeoutMs(30_000).createDataSource()
                inputs[record.id] = input!!
                input!!.open(DataSpec.Builder().setUri(access!!.url).build())
                synchronizeHeaders(access!!.api)
                val contentType = input!!.responseHeaders.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value?.joinToString(";").orEmpty()
                check(input!!.responseCode == 200 && contentType.contains("application/zip", true) && contentType.contains("fileway-selection=wire-v1", true)) {
                    "服务器未确认所选项目的ZIP导出协议，请更新服务器后重试"
                }
                val buffer = ByteArray(128 * 1024); var checkpoint = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input!!.read(buffer, 0, buffer.size)
                    if (count == C.RESULT_END_OF_INPUT) break
                    check(count >= 0 && total <= Long.MAX_VALUE - count) { "ZIP接收长度无效" }
                    output.write(buffer, 0, count); total += count; prefix.set(total)
                    val now = System.currentTimeMillis()
                    if (now - checkpoint >= 1000) {
                        output.fd.sync(); check(dao.progress(record.id, record.generation, total, now) == 1) { "ZIP下载已被替换" }
                        access!!.api.token(); checkpoint = now
                    }
                }
                output.fd.sync()
                check(dao.progress(record.id, record.generation, total, System.currentTimeMillis()) == 1) { "ZIP下载已被替换" }
            }
            currentCoroutineContext().ensureActive()
            val verified = context.contentResolver.openFileDescriptor(Uri.parse(target.localUri), "r") ?: error("ZIP无法重新读取验证，请检查下载目录")
            val verificationContext = currentCoroutineContext()
            android.os.ParcelFileDescriptor.AutoCloseInputStream(verified).use { stream ->
                val channel = stream.channel
                check(channel.size() == total) { "ZIP本机文件长度不一致" }
                verifyZipExport(total) { offset, count ->
                    verificationContext.ensureActive()
                    val bytes = ByteArray(count); val buffer = java.nio.ByteBuffer.wrap(bytes)
                    channel.position(offset)
                    while (buffer.hasRemaining()) check(channel.read(buffer) > 0) { "ZIP结构读取不完整" }
                    bytes
                }
            }
            access!!.api.token(); currentCoroutineContext().ensureActive()
            check(dao.finalExport(record.id, record.generation, total, System.currentTimeMillis()) == 1) { "ZIP下载已被替换" }
            target = target.copy(expectedSize = total, downloaded = total)
            destinations.complete(target)
            check(dao.finish(record.id, record.generation, "completed", "", System.currentTimeMillis()) == 1) { "ZIP下载已被替换" }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { dao.finish(record.id, record.generation, "interrupted", "ZIP已中断，恢复时会从零重新打包", System.currentTimeMillis()) }
            throw cancelled
        } catch (failure: Exception) {
            withContext(NonCancellable) { dao.finish(record.id, record.generation, "failed", failure.message ?: "ZIP下载失败，请从零重新打包", System.currentTimeMillis()) }
        } finally {
            inputs.remove(record.id)
            try { runCatching { input?.close() } } finally { withContext(NonCancellable) { runCatching { access?.close() } } }
        }
    }
    companion object {
        @Volatile private var instance: DownloadRuntime? = null
        fun get(context: Context): DownloadRuntime = instance ?: synchronized(this) { instance ?: DownloadRuntime(context.applicationContext).also { instance = it } }
    }
}
