package io.github.kkwans.nasfilebrowser.upload

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import io.github.kkwans.nasfilebrowser.app.SessionContext
import io.github.kkwans.nasfilebrowser.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID

enum class UploadConflict(val label: String) { KEEP_BOTH("保留两份"), SKIP("跳过"), REPLACE("覆盖") }
data class UploadDraft(val source: LocalUploadSource, val targetPath: String, val targetWire: String,
    val existingIdentity: String? = null, val choice: UploadConflict = UploadConflict.KEEP_BOTH)
data class UploadRestartDraft(val record: UploadRecord, val source: LocalUploadSource)
data class UploadsState(val items: List<UploadRecord> = emptyList(), val busy: Boolean = false, val selecting: Boolean = false,
    val targetLabel: String = "", val scanned: Int = 0, val drafts: List<UploadDraft> = emptyList(),
    val folder: Boolean = false, val error: String? = null, val notice: String? = null, val canReplace: Boolean = false,
    val speeds: Map<String, Long?> = emptyMap(), val sent: Map<String, Long> = emptyMap(), val completedRevision: Long = 0,
    val restart: UploadRestartDraft? = null)
private data class UploadSelection(val binding: SessionContext, val directory: DirectoryCrumb)

internal suspend fun dispatchUploadRows(rows: List<UploadRecord>, scheduled: MutableSet<String>, current: () -> Boolean,
    start: (UploadRecord) -> Unit, failed: suspend (UploadRecord) -> Unit): Int {
    var failures = 0
    for (row in rows) {
        currentCoroutineContext().ensureActive()
        check(current()) { "上传目标已切换，已建立任务按原账号保留" }
        try { start(row) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failures++; failed(row) }
        scheduled.add(row.id)
    }
    return failures
}

class UploadController(private val context: Context, private val scope: CoroutineScope, private val isCurrent: (SessionContext) -> Boolean) {
    private val database = ClientDatabase.get(context); private val dao = database.uploads()
    private val mutable = MutableStateFlow(UploadsState()); val state = mutable.asStateFlow()
    private var selection: UploadSelection? = null
    private var action: Job? = null; private var sampling: Job? = null
    private fun current(selected: UploadSelection) = selection === selected && isCurrent(selected.binding)
    init { scope.launch {
        val scheduler = context.getSystemService(android.app.job.JobScheduler::class.java)
        dao.observe().first().filter { it.active }.forEach { row ->
            if (!UploadRuntime.get(context).isRunning(row.id) && scheduler.getPendingJob(row.jobId) == null) dao.command(row.id, "interrupted", System.currentTimeMillis())
        }
        dao.observe().first().filter { it.status == "canceling" && !UploadRuntime.get(context).isCanceling(it.id) }.forEach { row ->
            dao.canceled(row.id, row.generation, "cancel_failed", row.uploaded, "上次清理未得到确认，请重试核对原服务器", System.currentTimeMillis())
        }
        var completed = emptySet<String>()
        dao.observe().collect { rows ->
            val done = rows.filter { it.complete }.map { it.id }.toSet()
            val newlyCompleted = (done - completed).isNotEmpty()
            val before = mutable.value
            mutable.value = before.copy(items = rows, completedRevision = before.completedRevision + if (newlyCompleted) 1 else 0,
                notice = if (newlyCompleted && !before.busy && !before.selecting) "上传已完成，本机原文件保留" else before.notice)
            completed = done
        }
    } }
    fun refresh() { scope.launch { val rows = dao.observe().first(); mutable.value = mutable.value.copy(items = rows) } }
    fun visible(active: Boolean) {
        if (active == (sampling?.isActive == true)) return
        sampling?.cancel(); sampling = null
        if (!active) return
        sampling = scope.launch {
            val previous = mutableMapOf<String, UploadTransfer>()
            while (isActive) {
                val samples = mutableMapOf<String, UploadTransfer>(); val speeds = mutableMapOf<String, Long?>(); val sent = mutableMapOf<String, Long>()
                for (row in mutable.value.items.filter { it.active }) {
                    try {
                        val sample = UploadRuntime.get(context).transfer(row.id)
                        if (sample != null) {
                            samples[row.id] = sample; sent[row.id] = sample.progress
                            val old = previous[row.id]
                            speeds[row.id] = if (old != null && sample.elapsedMillis > old.elapsedMillis && sample.bytes >= old.bytes)
                                ((sample.bytes - old.bytes).toDouble() * 1000 / (sample.elapsedMillis - old.elapsedMillis)).toLong() else null
                        } else speeds[row.id] = 0
                    } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { speeds[row.id] = null }
                }
                previous.clear(); previous.putAll(samples)
                mutable.value = mutable.value.copy(speeds = speeds, sent = sent); delay(1000)
            }
        }
    }
    fun begin(binding: SessionContext, directory: DirectoryCrumb): Boolean {
        if (!isCurrent(binding) || mutable.value.busy || !binding.api.identity.permissions.create) return false
        cancelSelection(quiet = true)
        selection = UploadSelection(binding, directory)
        mutable.value = mutable.value.copy(selecting = true, targetLabel = binding.profile.name + " · " + directory.path,
            scanned = 0, drafts = emptyList(), error = null, notice = null, canReplace = binding.api.identity.permissions.modify)
        return true
    }
    fun cancelSelection(quiet: Boolean = false) {
        if (selection == null) return
        action?.cancel(); action = null; selection = null
        mutable.value = mutable.value.copy(busy = false, selecting = false, drafts = emptyList(), error = null, scanned = 0,
            notice = if (quiet) null else "上传准备已取消，已经建立的任务保留")
    }
    fun prepare(files: List<Uri> = emptyList(), tree: Uri? = null) {
        val selected = selection ?: return
        if (!current(selected) || mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true, folder = tree != null, error = null, scanned = 0)
        action = scope.launch {
            try {
                val permissions = selected.binding.api.permissions(); check(permissions.create) { "当前账号没有上传权限" }
                val wire = requireNotNull(selected.directory.wirePath)
                resourceWireBytes(wire)
                check(selected.binding.api.request("GET", "/api/resources$wire?metadata=1").getBoolean("isDir")) { "原上传目录已不可用" }
                val sources = withContext(Dispatchers.IO) {
                    val reader = UploadSources(context)
                    if (tree != null) reader.folder(tree, { current(selected) && isActive }) { count ->
                        if (current(selected)) mutable.value = mutable.value.copy(scanned = count)
                    } else {
                        require(files.isNotEmpty() && files.size <= 1000) { "请选择1到1000个本机文件" }
                        files.distinct().map { uri -> reader.retain(uri); reader.read(uri) }
                    }
                }
                currentCoroutineContext().ensureActive(); check(current(selected)) { "上传目标已切换" }
                check(sources.isNotEmpty()) { "所选文件夹没有可上传的文件" }
                val drafts = arrayListOf<UploadDraft>(); val seen = hashSetOf<String>()
                val parents = sources.map { it.relativeDirectory }.filter { it.isNotEmpty() }.flatMap { relative ->
                    val parts = relative.split('/'); (1..parts.size).map { parts.take(it).joinToString("/") }
                }.distinct()
                for (parent in parents) {
                    check(current(selected)) { "上传目标已切换" }
                    val parentWire = wire.trimEnd('/') + "/" + SearchResult.encodePath(parent)
                    val info = existing(selected.binding.api, parentWire)
                    check(info == null || info.getBoolean("isDir")) { "目标的 $parent 已有同名文件，请换一个上传目录" }
                }
                for (source in sources) {
                    check(current(selected)) { "上传目标已切换" }
                    val relative = listOf(source.relativeDirectory, source.name).filter { it.isNotEmpty() }.joinToString("/")
                    val targetPath = selected.directory.path.trimEnd('/') + "/" + relative
                    val targetWire = wire.trimEnd('/') + "/" + SearchResult.encodePath(relative)
                    check(seen.add(targetWire)) { "所选来源有重复目标名称，请分批选择" }
                    val info = existing(selected.binding.api, targetWire)
                    val identity = info?.let { if (it.getBoolean("isDir")) "directory" else "${it.getLong("size")}/${it.optString("modified")}" }
                    drafts.add(UploadDraft(source, targetPath, targetWire, identity))
                }
                sources.fold(0L) { total, item -> Math.addExact(total, item.size) }
                check(current(selected)) { "上传目标已切换" }
                mutable.value = mutable.value.copy(busy = false, scanned = sources.size, drafts = drafts, canReplace = permissions.modify)
            } catch (failure: Exception) {
                if (failure !is CancellationException && current(selected)) mutable.value = mutable.value.copy(busy = false, error = failure.message ?: "无法读取本机文件，请重新选择")
            }
        }
    }
    private suspend fun existing(api: NasSession, wire: String): org.json.JSONObject? = try { api.request("GET", "/api/resources$wire?metadata=1") }
        catch (failure: ServiceException) { if (failure.status == 404) null else throw failure }
    fun conflict(uri: String, choice: UploadConflict) {
        if (mutable.value.busy || choice == UploadConflict.REPLACE && !mutable.value.canReplace) return
        mutable.value = mutable.value.copy(drafts = mutable.value.drafts.map { if (it.source.uri == uri) it.copy(choice = choice) else it })
    }
    fun submit() {
        val selected = selection ?: return
        val before = mutable.value
        if (!current(selected) || before.busy || before.drafts.isEmpty()) return
        mutable.value = before.copy(busy = true, error = null, notice = null)
        action = scope.launch {
            var created = 0
            var added = emptyList<UploadRecord>()
            val scheduled = hashSetOf<String>()
            try {
                check(selected.binding.api.permissions().create) { "当前账号没有上传权限" }
                val reservations = dao.pendingTargets(selected.binding.account.key, selected.binding.profile.sourceRevision)
                val targets = resolveUploadTargets(before.drafts, reservations) { wire ->
                    check(current(selected)) { "上传目标已切换，未建立新任务" }
                    existing(selected.binding.api, wire) != null
                }
                check(current(selected)) { "上传目标已切换，未建立新任务" }
                val batch = UUID.randomUUID().toString(); val bytes = targets.fold(0L) { n, item -> Math.addExact(n, item.draft.source.size) }
                val permissions = selected.binding.api.permissions()
                check(permissions.create) { "当前账号没有上传权限" }
                targets.forEach { target ->
                    val draft = target.draft
                    check(draft.choice != UploadConflict.REPLACE || draft.existingIdentity == null ||
                        draft.existingIdentity != "directory" && permissions.modify) { "不能覆盖文件夹，或没有覆盖权限" }
                }
                val now = System.currentTimeMillis()
                added = database.withTransaction {
                    check(current(selected)) { "上传目标已切换，未建立新任务" }
                    val occupied = dao.pendingTargets(selected.binding.account.key, selected.binding.profile.sourceRevision)
                        .map { uploadTargetKey(it, foldCase = true) }.toMutableSet()
                    check(targets.all { occupied.add(uploadTargetKey(it.wire, foldCase = true)) }) {
                        "该目标已有其他上传任务，整批未建立，请核对原任务后重试"
                    }
                    val firstJob = dao.lastJobId() + 1
                    require(targets.isEmpty() || firstJob in 7900001..8500000 && firstJob.toLong() + targets.size - 1 <= 8500000) { "上传任务编号已用完" }
                    targets.mapIndexed { index, target ->
                        val draft = target.draft
                        val overwrite = draft.existingIdentity != null && draft.choice == UploadConflict.REPLACE
                        UploadRecord(UUID.randomUUID().toString(), firstJob + index, selected.binding.account.key, selected.binding.profile.id,
                            selected.binding.profile.sourceRevision, draft.source.uri, draft.source.name, draft.source.mime, draft.source.size,
                            draft.source.modified, target.path, target.wire, selected.directory.wirePath!!, selected.binding.profile.name + " · " + selected.binding.account.username,
                            overwrite, if (overwrite) draft.existingIdentity!! else "", if (draft.source.size == 0L) "resources" else "tus",
                            createdAt = now, updatedAt = now, batchId = batch, batchName = draft.source.relativeDirectory.substringBefore('/').ifEmpty { "本机文件" },
                            batchItems = targets.size, batchBytes = bytes, folderUpload = before.folder).also { dao.insert(it) }
                    }
                }
                created = added.size
                if (current(selected)) mutable.value = mutable.value.copy(drafts = emptyList(), notice = "已建立 $created 项上传任务")
                val failed = dispatchUploadRows(added, scheduled, { current(selected) }, { UploadScheduler.start(context, it) }) { row ->
                    withContext(NonCancellable) { dao.command(row.id, "failed", now) }
                }
                check(current(selected)) { "上传目标已切换，已建立任务保留" }
                selection = null; mutable.value = mutable.value.copy(busy = false, selecting = false, drafts = emptyList(),
                    notice = "已添加 $created 项上传任务，可从上传页查看", error = if (failed > 0) "$failed 项未能启动，可从上传页继续；任务记录已保留" else null)
            } catch (failure: Exception) {
                if (added.isNotEmpty()) withContext(NonCancellable) {
                    added.filterNot { it.id in scheduled }.forEach { dao.command(it.id, "interrupted", System.currentTimeMillis()) }
                }
                if (failure is CancellationException) throw failure
                if (current(selected)) mutable.value = mutable.value.copy(busy = false,
                    error = "已建立 $created 项任务；${failure.message ?: "上传准备失败"}。已有任务保留，可添加剩余项。")
            }
        }
    }
    fun pause(row: UploadRecord) = change { UploadScheduler.pause(context, row.id); "上传已暂停，本机原文件保留" }
    fun resume(row: UploadRecord) = change {
        val item = dao.get(row.id) ?: error("上传记录不存在")
        check(item.canResume && !item.active) { "上传正在进行、已经结束或正在清理" }
        withTimeout(30_000) { UploadRuntime.get(context).awaitStopped(item.id) }
        check(dao.command(item.id, "queued", System.currentTimeMillis()) == 1)
        try { UploadScheduler.start(context, dao.get(item.id)!!) }
        catch (failure: Exception) { dao.command(item.id, "failed", System.currentTimeMillis()); throw failure }
        "正在核对服务器进度并继续上传"
    }
    fun remove(row: UploadRecord) = change {
        val item = dao.get(row.id) ?: error("上传记录不存在")
        check(!item.active && (item.complete || item.status in setOf("canceled", "restarted"))) { "未完成清理请先保留记录并核对服务器片段" }
        check(dao.removeRecord(item.id) == 1)
        "上传记录已移除，本机原文件和服务器文件保留"
    }
    fun cancel(row: UploadRecord) = change { UploadRuntime.get(context).cancelTask(row.id) }
    fun reselectSource(id: String, generation: Long, uri: Uri) = change {
        val item = dao.get(id) ?: error("上传记录不存在")
        check(item.canReselectSource && item.generation == generation) { "上传任务状态已变化，请重新选择原文件" }
        withTimeout(30_000) { UploadRuntime.get(context).awaitStopped(item.id) }
        val source = withContext(Dispatchers.IO) { UploadSources(context).reauthorize(item, uri) }
        check(dao.reauthorize(item.id, generation, source.uri, System.currentTimeMillis()) == 1) { "上传任务已变化，原进度保留，请重试" }
        if (item.status == "expired") "原文件读取授权已恢复；服务器片段已过期，请重新开始上传"
        else "原文件读取授权已恢复，已有进度保留，可继续上传"
    }
    fun sourceSelectionCanceled() { mutable.value = mutable.value.copy(notice = "已取消重新选择，原任务和进度保留") }
    fun prepareRestart(id: String, generation: Long, uri: Uri) = change {
        val item = dao.get(id) ?: error("上传记录不存在")
        check(item.canRestart && item.generation == generation) { "原任务状态已变化，请重新选择" }
        val source = withContext(Dispatchers.IO) { UploadSources(context).restartSource(item, uri) }
        check(dao.get(id)?.let { it.canRestart && it.generation == generation } == true) { "原任务状态已变化，原进度保留" }
        mutable.value = mutable.value.copy(restart = UploadRestartDraft(item, source))
        "请确认是否放弃旧片段并重新开始"
    }
    fun dismissRestart() { if (!mutable.value.busy) mutable.value = mutable.value.copy(restart = null, notice = "原任务和已有进度保留") }
    fun restart() {
        val draft = mutable.value.restart ?: return
        change {
            try { restartTask(draft) }
            catch (failure: Exception) { mutable.value = mutable.value.copy(restart = null); throw failure }
        }
    }
    private suspend fun restartTask(draft: UploadRestartDraft): String {
        val item = dao.get(draft.record.id) ?: error("原上传记录不存在")
        check(item.canRestart && item.generation == draft.record.generation) { "原任务已变化，请重新选择原文件" }
        withTimeout(30_000) { UploadRuntime.get(context).awaitStopped(item.id) }
        val source = withContext(Dispatchers.IO) { UploadSources(context).restartSource(item, Uri.parse(draft.source.uri)) }
        check(source == draft.source) { "本机来源在确认期间已变化，请重新选择；旧片段保留" }
        if (item.status != "canceled") UploadRuntime.get(context).cancelTask(item.id, forRestart = true, expectedGeneration = item.generation)
        val previous = dao.get(item.id) ?: error("原上传记录已变化")
        if (previous.complete) {
            mutable.value = mutable.value.copy(restart = null)
            return "服务器已完成原任务，完整文件保留；如需更新文件，请从文件页重新上传"
        }
        check(previous.status == "canceled") { "旧片段清理未确认，未创建新任务" }
        val now = System.currentTimeMillis()
        val next = database.withTransaction {
            check(dao.restarted(previous.id, previous.generation, now) == 1) { "任务已被重新开始，未重复创建" }
            val job = dao.lastJobId() + 1; require(job in 7900001..8500000) { "上传任务编号已用完" }
            previous.copy(id = UUID.randomUUID().toString(), jobId = job, sourceUri = source.uri, name = source.name, mime = source.mime,
                expectedSize = source.size, sourceModified = source.modified, protocol = if (source.size == 0L) "resources" else "tus",
                remoteCreated = false, status = "queued", uploaded = 0, createdAt = now, updatedAt = now, generation = 0, error = "",
                batchId = UUID.randomUUID().toString(), batchName = "重新上传", batchItems = 1, batchBytes = source.size).also { dao.insert(it) }
        }
        mutable.value = mutable.value.copy(restart = null)
        try { UploadScheduler.start(context, next) }
        catch (failure: Exception) { dao.command(next.id, "failed", System.currentTimeMillis()); error("新任务已建立但未能启动，可从列表继续；原记录保留") }
        return "已从零建立新上传任务，原目标和账号保持不变"
    }
    private fun change(block: suspend () -> String) {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true, error = null, notice = null)
        scope.launch {
            try { val notice = block(); mutable.value = mutable.value.copy(busy = false, notice = notice) }
            catch (failure: Exception) { if (failure !is CancellationException) mutable.value = mutable.value.copy(busy = false, error = failure.message ?: "上传操作失败") }
        }
    }
    fun reportError(message: String) { mutable.value = mutable.value.copy(error = message) }
}
