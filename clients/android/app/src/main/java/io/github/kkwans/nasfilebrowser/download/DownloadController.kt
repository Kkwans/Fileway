package io.github.kkwans.nasfilebrowser.download

import android.content.Context
import androidx.room.withTransaction
import io.github.kkwans.nasfilebrowser.app.ResourceRef
import io.github.kkwans.nasfilebrowser.app.SessionContext
import io.github.kkwans.nasfilebrowser.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID

data class DownloadsState(val items: List<DownloadRecord> = emptyList(), val tree: String = "", val busy: Boolean = false, val error: String? = null, val notice: String? = null,
    val speeds: Map<String, Long?> = emptyMap(), val folderRequest: Boolean = false, val preparing: Boolean = false, val scannedFiles: Int = 0, val folderPlan: FolderDownloadPlan? = null,
    val zipPlan: ZipExportPlan? = null)

private data class PreparedFolderDownloads(val binding: SessionContext, val current: () -> Boolean,
    val created: (ResourceRef) -> Unit, val tree: String, var plan: FolderDownloadPlan)

class DownloadController(private val context: Context, private val scope: CoroutineScope) {
    private val database = ClientDatabase.get(context); private val dao = database.downloads()
    val target = DownloadTarget(context)
    private val mutable = MutableStateFlow(DownloadsState(tree = target.selectedTree()))
    val state = mutable.asStateFlow()
    private var sampling: Job? = null
    private var actionJob: Job? = null
    private var prepared: PreparedFolderDownloads? = null
    private var zipBinding: SessionContext? = null
    private var zipCurrent: (() -> Boolean)? = null
    fun enqueueZip(binding: SessionContext, files: List<ResourceRef>, current: () -> Boolean) = perform {
        check(prepared == null && mutable.value.zipPlan == null) { "请先完成或取消当前下载计划" }
        check(current()) { "ZIP下载来源已切换" }
        check(binding.api.permissions().download) { "当前账号没有下载权限" }
        val plan = withContext(Dispatchers.Default) { zipExportPlan(files.toList()) }
        check(current()) { "ZIP下载来源已切换" }
        val row = binding.api.request("GET", "/api/resources${plan.parentWire}?metadata=1")
        check(row.getBoolean("isDir") && resourceWireBytes(row.optString("wirePath")).contentEquals(resourceWireBytes(plan.parentWire))) { "ZIP父目录已变化，请重新选择项目" }
        check(current()) { "ZIP下载来源已切换" }
        zipBinding = binding; zipCurrent = current
        mutable.value = mutable.value.copy(zipPlan = plan)
        "请确认ZIP文件名和下载目录；暂停后会从零重新打包"
    }
    fun cancelZipExport() {
        if (mutable.value.busy) return
        zipBinding = null; zipCurrent = null
        mutable.value = mutable.value.copy(zipPlan = null)
    }
    fun confirmZipExport(name: String) = perform {
        val plan = mutable.value.zipPlan ?: error("ZIP下载计划已关闭")
        val binding = zipBinding ?: error("ZIP来源已关闭"); val current = zipCurrent ?: error("ZIP来源已关闭")
        require(zipExportNameError(name) == null) { zipExportNameError(name).orEmpty() }
        check(current()) { "ZIP下载来源已切换，请取消后重新选择" }
        check(binding.api.permissions().download) { "当前账号没有下载权限" }
        check(withContext(Dispatchers.IO) { target.hasAccess(target.selectedTree()) }) { "下载目录授权已失效，请重新选择目录" }
        val now = System.currentTimeMillis()
        val record = database.withTransaction {
            check(current()) { "ZIP下载来源已切换" }
            val next = dao.lastJobId() + 1; check(next in 7300001..7900000) { "下载任务编号已用完，请整理历史记录" }
            DownloadRecord(UUID.randomUUID().toString(), next, binding.account.key, binding.profile.id, binding.profile.sourceRevision,
                plan.parentPath, plan.parentWire, name, ZIP_EXPORT_TYPE, -1, "", plan.identity(), binding.profile.name + " · " + binding.account.username,
                target.selectedTree(), createdAt = now, updatedAt = now).also { dao.insert(it) }
        }
        zipBinding = null; zipCurrent = null; mutable.value = mutable.value.copy(zipPlan = null)
        try { DownloadScheduler.start(context, record) } catch (failure: Exception) { dao.command(record.id, "failed", now); throw failure }
        "ZIP打包下载已加入任务；恢复时从零重新打包"
    }
    fun refresh() { scope.launch { val rows = dao.observe().first(); mutable.value = mutable.value.copy(items = rows) } }
    fun visible(active: Boolean) {
        if (active == (sampling?.isActive == true)) return
        sampling?.cancel(); sampling = null
        if (!active) return
        sampling = scope.launch {
            val previous = mutableMapOf<String, DownloadTransfer>()
            while (isActive) {
                val samples = mutableMapOf<String, DownloadTransfer>()
                val rates = mutableMapOf<String, Long?>()
                for (item in mutable.value.items) {
                    val current = DownloadRuntime.get(context).transfers(item.id)
                    var rate = 0L; var known = current.isEmpty()
                    for (sample in current) {
                        samples[sample.key] = sample
                        val old = previous[sample.key]
                        if (old != null && sample.elapsedMillis > old.elapsedMillis) {
                            rate += ((sample.bytes - old.bytes).coerceAtLeast(0).toDouble() * 1000 / (sample.elapsedMillis - old.elapsedMillis)).toLong()
                            known = true
                        }
                    }
                    rates[item.id] = if (known) rate else null
                }
                previous.clear(); previous.putAll(samples)
                mutable.value = mutable.value.copy(speeds = rates)
                delay(1000)
            }
        }
    }
    init { scope.launch {
        // System Task Manager may kill without onStopJob. Reconcile durable
        // rows against our own live workers/jobs, leaving local files intact.
        val scheduler = context.getSystemService(android.app.job.JobScheduler::class.java)
        dao.observe().first().filter { it.active }.forEach { item ->
            if (!DownloadRuntime.get(context).isRunning(item.id) && scheduler.getPendingJob(item.jobId) == null)
                dao.command(item.id, "interrupted", System.currentTimeMillis())
        }
        dao.observe().collect { mutable.value = mutable.value.copy(items = it) }
    } }
    fun selectDirectory(uri: android.net.Uri?) = perform {
        check(prepared == null) { "请先完成或取消文件夹下载计划" }
        withContext(Dispatchers.IO) { target.selectTree(uri) }
        mutable.value = mutable.value.copy(tree = target.selectedTree())
        "下载目录已保存"
    }
    fun reauthorizeDirectory(id: String, uri: android.net.Uri) = perform {
        val record = dao.get(id) ?: error("下载记录不存在")
        check(record.treeUri.isNotEmpty()) { "默认下载目录无需重新授权" }
        withContext(Dispatchers.IO) { target.reauthorizeTree(record.treeUri, uri) }
        "原目录授权已恢复；文件和默认目录保持不变，可重新打开或继续下载"
    }
    fun enqueue(binding: SessionContext, file: ResourceRef, current: () -> Boolean) = enqueueAll(binding, listOf(file), current)
    fun enqueueAll(binding: SessionContext, files: List<ResourceRef>, current: () -> Boolean, onCreated: (ResourceRef) -> Unit = {}) = perform {
        check(prepared == null && mutable.value.zipPlan == null) { "请先完成或取消当前下载计划" }
        val snapshot = files.distinctBy { it.wirePath.ifEmpty { it.path } }
        require(snapshot.isNotEmpty() && snapshot.all { it.downloadId.isEmpty() }) { "请选择服务器上的文件或文件夹" }
        check(current()) { "下载来源已切换" }
        check(binding.api.permissions().download) { "当前账号没有下载权限" }
        if (snapshot.any { it.directory }) {
            mutable.value = mutable.value.copy(folderRequest = true, preparing = true, scannedFiles = 0)
            try {
                val plan = scanFolderDownloads(snapshot, current, { endpoint -> binding.api.request("GET", endpoint) }) { count ->
                    mutable.value = mutable.value.copy(scannedFiles = count)
                }
                check(current()) { "下载来源已切换" }
                check(plan.entries.isNotEmpty()) { "所选文件夹没有可下载的文件" }
                prepared = PreparedFolderDownloads(binding, current, onCreated, target.selectedTree(), plan)
                mutable.value = mutable.value.copy(folderPlan = plan)
            } finally { mutable.value = mutable.value.copy(preparing = false) }
            return@perform "已读取文件夹，请确认下载范围与保存位置"
        }
        var created = 0
        for (file in snapshot) {
            try {
                addDownload(binding, file, current) {
                    created++
                    if (current()) onCreated(file)
                    mutable.value = mutable.value.copy(notice = "已建立 $created / ${snapshot.size} 项下载任务")
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                throw IllegalStateException("已建立 $created / ${snapshot.size} 项任务；${file.name}：${failure.message ?: "添加失败"}。已有任务可在下载页查看或继续。", failure)
            }
        }
        "已添加 $created 项下载，可在本机下载中查看"
    }
    fun confirmFolderDownloads() {
        val pending = prepared ?: return
        perform {
            check(pending.current()) { "下载来源已切换，请重新选择" }
            check(pending.binding.api.permissions().download) { "当前账号没有下载权限" }
            check(withContext(Dispatchers.IO) { target.hasAccess(pending.tree) }) { "下载目录授权已失效，请取消后重新选择" }
            val roots = pending.plan.roots
            var created = 0
            val count = pending.plan.entries.size
            for (entry in pending.plan.entries.toList()) {
                try {
                    addDownload(pending.binding, entry.file, pending.current, entry.relativeDirectory, pending.tree, verifySnapshot = true) {
                        created++
                        pending.plan = pending.plan.copy(entries = pending.plan.entries.filterNot { it === entry })
                        if (prepared === pending && pending.current()) mutable.value = mutable.value.copy(folderPlan = pending.plan, notice = "已建立 $created / $count 项下载任务")
                        val root = roots.firstOrNull { file -> resourceWireBytes(file.wirePath.ifEmpty { SearchResult.encodePath(file.path) })
                            .let { java.util.Base64.getEncoder().encodeToString(it) } == entry.rootKey }
                        if (root != null && pending.plan.entries.none { it.rootKey == entry.rootKey } && prepared === pending && pending.current()) pending.created(root)
                    }
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    throw IllegalStateException("已建立 $created / $count 项任务；${entry.file.name}：${failure.message ?: "添加失败"}。重试只添加剩余项目，已有任务保留。", failure)
                }
            }
            currentCoroutineContext().ensureActive()
            check(prepared === pending && pending.current()) { "下载来源已切换，已建立的任务保留" }
            prepared = null
            mutable.value = mutable.value.copy(folderRequest = false, folderPlan = null)
            "已添加 $created 项下载，保留文件夹层级，可从本机下载查看"
        }
    }
    fun cancelFolderDownloads(quiet: Boolean = false) {
        if (!mutable.value.folderRequest) return
        actionJob?.cancel(); actionJob = null; prepared = null
        mutable.value = mutable.value.copy(busy = false, folderRequest = false, preparing = false, folderPlan = null, scannedFiles = 0, error = null,
            notice = if (quiet) null else "下载计划已取消，已经建立的任务保留")
    }
    private suspend fun addDownload(binding: SessionContext, file: ResourceRef, current: () -> Boolean,
        relativeDirectory: String = "", tree: String = target.selectedTree(), verifySnapshot: Boolean = false, created: () -> Unit) {
        check(current()) { "下载来源已切换" }
        val wire = file.wirePath.ifEmpty { SearchResult.encodePath(file.path) }
        val info = binding.api.request("GET", "/api/resources$wire?metadata=1")
        check(current() && !info.getBoolean("isDir")) { "下载来源已切换或文件已变化" }
        val size = info.getLong("size"); val modified = info.optString("modified")
        check(size >= 0) { "源文件长度无法确认" }
        if (verifySnapshot) check(size == file.size && modified == file.modified && resourceWireBytes(info.optString("wirePath").ifEmpty { SearchResult.encodePath(info.getString("path")) })
            .contentEquals(resourceWireBytes(wire))) { "源文件已变化，请重新读取下载范围" }
        val now = System.currentTimeMillis()
        val record = database.withTransaction {
            check(current()) { "下载来源已切换" }
            val next = dao.lastJobId() + 1
            check(next in 7300001..7900000) { "下载任务编号已用完，请整理历史记录" }
            DownloadRecord(UUID.randomUUID().toString(), next, binding.account.key, binding.profile.id, binding.profile.sourceRevision,
                file.path, wire, file.name, file.type, size, modified, "$size/$modified", binding.profile.name + " · " + binding.account.username,
                tree, createdAt = now, updatedAt = now, relativeDirectory = relativeDirectory).also { dao.insert(it) }
        }
        try { DownloadScheduler.start(context, record) }
        catch (failure: Exception) { dao.command(record.id, "failed", now); throw failure }
        finally { created() }
    }
    fun resume(record: DownloadRecord) = perform {
        val current = dao.get(record.id) ?: error("下载记录不存在")
        check(!current.complete && !current.active) { "下载已经开始或完成" }
        check(withContext(Dispatchers.IO) { target.hasAccess(current.treeUri) }) { "原下载目录授权已失效，请先在更多菜单中重新授权目录" }
        withTimeout(30_000) { DownloadRuntime.get(context).awaitStopped(record.id) }
        check(dao.command(record.id, "queued", System.currentTimeMillis()) == 1)
        try { DownloadScheduler.start(context, dao.get(record.id) ?: error("下载记录不存在")) }
        catch (failure: Exception) { dao.command(record.id, "failed", System.currentTimeMillis()); throw failure }
        if (current.zipExport) "ZIP正在从零重新打包；不完整的本机输出将被截断" else "正在继续下载"
    }
    fun pause(record: DownloadRecord) = perform { DownloadScheduler.pause(context, record.id); if (record.zipExport) "ZIP已暂停，恢复时会从零重新打包" else "下载已暂停，已保存的部分保留" }
    fun remove(record: DownloadRecord, file: Boolean) = perform {
        val current = dao.get(record.id) ?: error("下载记录不存在")
        check(!current.active) { "请先暂停下载" }
        check(file || current.complete) { "未完成文件请使用删除文件与记录，避免留下无法恢复的片段" }
        withTimeout(30_000) { DownloadRuntime.get(context).awaitStopped(record.id) }
        if (file && current.localUri.isNotEmpty()) check(target.delete(current)) { "无法删除本机文件，请检查目录授权" }
        check(dao.removeRecord(record.id) == 1)
        withContext(Dispatchers.IO) { DownloadIndex.get(context).remove(record) }
        if (file) "文件与记录已删除" else "记录已移除，本机文件保留"
    }
    private fun perform(action: suspend () -> String) {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true, error = null, notice = null)
        actionJob = scope.launch {
            try { val message = action(); mutable.value = mutable.value.copy(busy = false, notice = message) }
            catch (failure: Exception) { if (failure is CancellationException) throw failure; mutable.value = mutable.value.copy(busy = false, error = failure.message ?: "下载操作失败，请重试") }
        }
    }
    fun reportError(message: String) { mutable.value = mutable.value.copy(error = message, notice = null) }
    fun reportNotice(message: String) { mutable.value = mutable.value.copy(notice = message, error = null) }
}
