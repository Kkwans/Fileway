package io.github.kkwans.nasfilebrowser.app

import io.github.kkwans.nasfilebrowser.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.net.URLEncoder
import java.time.OffsetDateTime

data class ArchiveState(val scope: String = "", val file: ResourceRef? = null, val listing: ArchiveListing? = null,
    val canExtract: Boolean = false,
    val loading: Boolean = false, val error: String? = null, val prefix: String = "", val query: String = "",
    val selected: Set<String> = emptySet(), val destination: DirectoryCrumb = DirectoryCrumb("根目录", "/", "/"),
    val pickingDestination: Boolean = false, val directories: List<ResourceRef> = emptyList(), val directoryLoading: Boolean = false,
    val destinationDraft: DirectoryCrumb? = null,
    val directoryError: String? = null, val submitting: Boolean = false, val unknownSubmission: Boolean = false,
    val task: ServerTask? = null, val canceling: Boolean = false, val taskError: String? = null, val report: ArchiveReport? = null,
    val reportLoading: Boolean = false, val reportError: String? = null) {
    val rows get() = listing?.let { archiveChildren(it, prefix, query) }.orEmpty()
    val selectedEntries get() = listing?.let { archiveSelectedEntries(it, selected) }.orEmpty()
}

class ArchiveController(private val scope: CoroutineScope, private val isCurrent: (SessionContext) -> Boolean,
    private val onOpened: (SessionContext, ResourceRef) -> Unit = { _, _ -> },
    private val onTaskAccepted: (SessionContext, ServerTask) -> Unit = { _, _ -> }) {
    private val mutable = MutableStateFlow(ArchiveState())
    val state = mutable.asStateFlow()
    private var bound: SessionContext? = null
    private var read: Job? = null
    private var directories: Job? = null
    private var write: Job? = null
    private var poll: Job? = null
    private var epoch = 0L
    private var directoryEpoch = 0L
    private var visible = false
    private var submittedDestination: DirectoryCrumb? = null
    private var submittedSelection: Set<String> = emptySet()
    private fun current(owner: SessionContext, revision: Long = epoch) = bound === owner && isCurrent(owner) && revision == epoch
    private fun reset() { read?.cancel(); directories?.cancel(); write?.cancel(); poll?.cancel(); epoch++; directoryEpoch++; submittedDestination = null; submittedSelection = emptySet() }

    fun bind(owner: SessionContext?) {
        if (bound === owner) return
        reset(); bound = owner
        mutable.value = ArchiveState(scope = owner?.owner.orEmpty())
    }
    fun open(file: ResourceRef, sourceScope: String) {
        val owner = bound ?: return
        if (!isCurrent(owner) || sourceScope != owner.api.id || file.directory || file.downloadId.isNotEmpty() || mutable.value.submitting) return
        reset()
        val wire = try { archiveAbsoluteWire(file.path, file.wirePath) } catch (error: Exception) {
            mutable.value = ArchiveState(scope = owner.owner, file = file, error = error.message ?: "压缩包原始路径无法确认"); return
        }
        val parent = DirectoryCrumb("原目录", file.path.substringBeforeLast('/').ifEmpty { "/" }, wire.substringBeforeLast('/').ifEmpty { "/" })
        mutable.value = ArchiveState(scope = owner.owner, file = file.copy(wirePath = wire), destination = parent)
        refresh()
    }
    fun close() {
        if (mutable.value.submitting) return
        reset(); mutable.value = ArchiveState(scope = bound?.owner.orEmpty())
    }
    fun setVisible(value: Boolean) {
        if (visible == value) return
        visible = value; poll?.cancel()
        if (value && mutable.value.task != null) startPoll()
    }
    fun refresh() {
        val owner = bound ?: return
        val file = mutable.value.file ?: return
        if (!isCurrent(owner) || mutable.value.submitting || mutable.value.task?.active == true || mutable.value.reportLoading) return
        read?.cancel(); val revision = ++epoch; poll?.cancel()
        mutable.value = mutable.value.copy(loading = true, error = null)
        read = scope.launch {
            try {
                val permissions = owner.api.permissions()
                check(permissions.download) { "当前账号没有读取压缩包的权限" }
                val wire = archiveAbsoluteWire(file.path, file.wirePath)
                val listing = parseArchiveListing(owner.api.request("GET", "/api/archives/entries?wirePath=" + URLEncoder.encode(wire, "UTF-8")))
                check(resourceWireBytes(listing.archiveWirePath).contentEquals(resourceWireBytes(wire))) { "服务器返回了不同压缩包，请从文件列表重新选择" }
                if (current(owner, revision)) {
                    val actual = file.copy(path = listing.archivePath, wirePath = listing.archiveWirePath, size = listing.sourceSize)
                    mutable.value = mutable.value.copy(file = actual, listing = listing, loading = false, canExtract = permissions.create, prefix = "", query = "", selected = emptySet())
                    try { onOpened(owner, actual) } catch (error: Exception) { if (error is CancellationException) throw error }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (current(owner, revision)) mutable.value = mutable.value.copy(loading = false, error = failure(error, "压缩包读取失败，请重试"))
            }
        }
    }
    fun search(value: String) { if (!mutable.value.submitting) mutable.value = mutable.value.copy(query = value) }
    fun enter(entry: ArchiveEntry) {
        val before = mutable.value
        if (!entry.isDir || before.submitting || before.rows.none { it.wirePath == entry.wirePath }) return
        mutable.value = before.copy(prefix = entry.wirePath, query = "")
    }
    fun navigate(prefix: String) {
        val before = mutable.value
        if (before.submitting) return
        if (prefix.isNotEmpty() && before.listing?.entries?.none { it.wirePath == prefix && it.isDir || it.wirePath.startsWith(prefix + "/") } != false) return
        mutable.value = before.copy(prefix = prefix, query = "")
    }
    fun up() {
        val before = mutable.value
        if (!before.submitting) mutable.value = before.copy(prefix = before.prefix.substringBeforeLast('/', ""), query = "")
    }
    fun root() { if (!mutable.value.submitting) mutable.value = mutable.value.copy(prefix = "", query = "") }
    fun toggle(entry: ArchiveEntry) {
        val before = mutable.value
        if (before.submitting || before.rows.none { it.wirePath == entry.wirePath }) return
        updateSelection(before, listOf(entry), invert = true)
    }
    fun selectRows(invert: Boolean = false) {
        val before = mutable.value
        if (before.submitting) return
        if (!invert && before.query.isBlank()) {
            // A complete directory is one wire selection, even when it contains
            // more than500 direct children. Keep choices outside that directory.
            if (before.prefix.isEmpty()) { selectDirectory(); return }
            val entry = before.listing?.let { archiveChildren(it, before.prefix.substringBeforeLast('/', ""), "") }
                ?.firstOrNull { it.wirePath == before.prefix }
            if (entry != null) { updateSelection(before, listOf(entry), invert = false); return }
        }
        updateSelection(before, before.rows, invert)
    }
    private fun updateSelection(before: ArchiveState, rows: List<ArchiveEntry>, invert: Boolean) {
        val listing = before.listing ?: return
        try {
            mutable.value = before.copy(selected = archiveSelectRows(listing, before.selected, rows, invert), error = null)
        } catch (error: IllegalArgumentException) {
            mutable.value = before.copy(error = error.message)
        }
    }
    fun selectDirectory() {
        if (!mutable.value.submitting) mutable.value = mutable.value.copy(selected = setOf(mutable.value.prefix.ifEmpty { "." }), error = null)
    }
    fun clearSelection() { if (!mutable.value.submitting) mutable.value = mutable.value.copy(selected = emptySet()) }
    fun pickDestination() {
        if (mutable.value.submitting || mutable.value.task?.active == true || mutable.value.reportLoading) return
        mutable.value = mutable.value.copy(pickingDestination = true, destinationDraft = mutable.value.destination)
        browseDestination(mutable.value.destination)
    }
    fun closeDestination() { directories?.cancel(); directoryEpoch++; mutable.value = mutable.value.copy(pickingDestination = false, destinationDraft = null, directoryLoading = false) }
    fun confirmDestination() {
        val before = mutable.value; val directory = before.destinationDraft ?: return
        if (before.directoryLoading || before.directoryError != null || directory.wirePath == null) return
        directories?.cancel(); directoryEpoch++
        mutable.value = before.copy(destination = directory, destinationDraft = null, pickingDestination = false, directoryLoading = false)
    }
    fun browseDestination(directory: DirectoryCrumb) {
        val owner = bound ?: return
        if (!isCurrent(owner) || !mutable.value.pickingDestination) return
        directories?.cancel(); val revision = ++directoryEpoch
        mutable.value = mutable.value.copy(destinationDraft = directory, directoryLoading = true, directoryError = null, directories = emptyList())
        directories = scope.launch {
            try {
                val wire = archiveAbsoluteWire(directory.path, requireNotNull(directory.wirePath) { "目录原始路径无法确认" })
                val row = owner.api.request("GET", "/api/resources$wire")
                check(row.getBoolean("isDir")) { "目标不是目录" }
                val actualWire = archiveAbsoluteWire(row.getString("path"), row.optString("wirePath"))
                check(resourceWireBytes(actualWire).contentEquals(resourceWireBytes(wire))) { "目标目录已变化，请重新选择" }
                val entries = row.getJSONArray("items")
                val folders = (0 until entries.length()).map { entries.getJSONObject(it) }.filter { it.getBoolean("isDir") }.map {
                    ResourceRef(it.getString("path"), archiveAbsoluteWire(it.getString("path"), it.optString("wirePath")), it.getString("name"), true, "", 0)
                }
                if (current(owner) && revision == directoryEpoch) mutable.value = mutable.value.copy(destinationDraft = directory.copy(path = row.getString("path"), wirePath = actualWire),
                    directories = folders.sortedBy { it.name.lowercase() }, directoryLoading = false)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (current(owner) && revision == directoryEpoch) mutable.value = mutable.value.copy(directoryLoading = false, directoryError = failure(error, "目录读取失败，请重试"))
            }
        }
    }
    fun submit() {
        val owner = bound ?: return
        val before = mutable.value; val listing = before.listing ?: return
        if (!isCurrent(owner) || before.loading || before.submitting || before.pickingDestination || before.reportLoading || before.task?.active == true || before.unknownSubmission || listing.truncated) return
        val body = try { archiveExtractionBody(listing, before.destination, before.selected) } catch (error: Exception) {
            mutable.value = before.copy(error = error.message); return
        }
        val revision = epoch
        mutable.value = before.copy(submitting = true, error = null, report = null, reportError = null)
        write = scope.launch {
            var sending = false; var accepted = false
            try {
                val permissions = owner.api.permissions()
                check(permissions.download && permissions.create) { "解压需要读取文件和创建文件的权限" }
                val meta = owner.api.request("GET", "/api/resources${listing.archiveWirePath}?metadata=1")
                check(meta.getLong("size") == listing.sourceSize && OffsetDateTime.parse(meta.getString("modified")).toInstant().toEpochMilli() == listing.sourceModified &&
                    resourceWireBytes(meta.optString("wirePath")).contentEquals(resourceWireBytes(listing.archiveWirePath))) { "压缩包已变化，请刷新后重新选择" }
                val destination = owner.api.request("GET", "/api/resources${before.destination.wirePath}?metadata=1")
                check(destination.getBoolean("isDir") && resourceWireBytes(destination.optString("wirePath")).contentEquals(resourceWireBytes(before.destination.wirePath!!))) { "目标目录已变化，请重新选择" }
                check(current(owner, revision)) { "连接已切换" }
                sending = true
                val task = ServerTask.from(owner.api.action("POST", "/api/archives/extractions", body))
                checkTask(owner, task); accepted = true
                if (current(owner, revision)) {
                    mutable.value = mutable.value.copy(submitting = false, task = task, taskError = null)
                    submittedDestination = before.destination; submittedSelection = before.selected.toSet()
                    try { onTaskAccepted(owner, task) } catch (error: Exception) { if (error is CancellationException) throw error }
                    if (visible) startPoll()
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (current(owner, revision)) mutable.value = mutable.value.copy(submitting = false,
                    unknownSubmission = sending && !accepted && (error !is ServiceException || error.status >= 500),
                    error = if (accepted) "任务已提交，请到任务中心查看" else if (sending && (error !is ServiceException || error.status >= 500))
                        "无法确认解压任务是否已创建，请先到任务中心核对，避免重复提交" else failure(error, "解压提交失败，请重试"))
            }
        }
    }
    fun acknowledgeSubmission() { if (!mutable.value.submitting) mutable.value = mutable.value.copy(unknownSubmission = false, error = null) }
    fun cancel() {
        val owner = bound ?: return; val task = mutable.value.task ?: return
        if (!isCurrent(owner) || !task.active || mutable.value.canceling) return
        mutable.value = mutable.value.copy(canceling = true, taskError = null)
        write = scope.launch {
            try {
                val result = ServerTask.from(owner.api.action("POST", "/api/tasks/${android.net.Uri.encode(task.id)}/cancel"))
                checkTask(owner, result); check(result.id == task.id)
                if (current(owner)) { mutable.value = mutable.value.copy(task = result, canceling = false); if (visible) startPoll() }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (current(owner)) mutable.value = mutable.value.copy(canceling = false, taskError = failure(error, "取消结果尚未确认，请到任务中心核对"))
            }
        }
    }
    fun retryTaskStatus() { if (mutable.value.task != null) startPoll() }
    private fun startPoll() {
        if (!visible) return
        val owner = bound ?: return; val known = mutable.value.task ?: return
        val revision = epoch; poll?.cancel()
        poll = scope.launch {
            while (isActive && current(owner, revision) && mutable.value.task?.id == known.id) {
                try {
                    val task = ServerTask.from(owner.api.request("GET", "/api/tasks/${android.net.Uri.encode(known.id)}"))
                    checkTask(owner, task); check(task.id == known.id)
                    if (!current(owner, revision)) return@launch
                    mutable.value = mutable.value.copy(task = task, taskError = null)
                    if (task.status == "completed") { loadReport(owner, task, revision); return@launch }
                    if (!task.active) return@launch
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    if (current(owner, revision)) mutable.value = mutable.value.copy(taskError = failure(error, "任务状态暂时不可用，请重试或到任务中心查看"))
                    return@launch
                }
                delay(2000)
            }
        }
    }
    private suspend fun loadReport(owner: SessionContext, task: ServerTask, revision: Long) {
        mutable.value = mutable.value.copy(reportLoading = true, reportError = null)
        try {
            val report = parseArchiveReport(owner.api.request("GET", "/api/archives/extractions/${android.net.Uri.encode(task.id)}"))
            val listing = mutable.value.listing ?: error("压缩包来源已关闭")
            check(resourceWireBytes(report.archiveWirePath).contentEquals(resourceWireBytes(listing.archiveWirePath))) { "解压结果的压缩包来源不匹配" }
            check(resourceWireBytes(report.destination.wirePath!!).contentEquals(resourceWireBytes(requireNotNull(submittedDestination?.wirePath)))) { "解压结果目标目录不匹配" }
            check(report.selectedWires.map { archiveWireKey(it) }.toSet() == submittedSelection.map { archiveWireKey(it) }.toSet()) { "解压结果选择范围不匹配" }
            if (current(owner, revision)) mutable.value = mutable.value.copy(reportLoading = false, report = report)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (current(owner, revision)) mutable.value = mutable.value.copy(reportLoading = false, reportError = failure(error, "任务已完成，结果读取失败，请重试"))
        }
    }
    private fun checkTask(owner: SessionContext, task: ServerTask) {
        check(task.id.isNotEmpty() && task.userId == owner.account.userId && task.type == "archive.extract" && task.status in setOf("queued", "running", "completed", "failed", "canceled", "interrupted")) { "解压任务不属于当前来源和账号或状态无法确认" }
    }
    private fun failure(error: Exception, fallback: String): String = when ((error as? ServiceException)?.status) {
        401 -> "登录已过期，请重新连接"
        403 -> "当前账号没有访问此压缩包或目标目录的权限"
        400 -> "压缩包参数或原始路径协议不受支持，请刷新或更新服务器"
        404 -> "压缩包、目录或服务端压缩包功能不存在，请刷新并检查服务器版本"
        415 -> "当前只支持 ZIP、TAR、tar.gz、tar.bz2、tar.xz、tar.zst"
        422 -> "压缩包损坏或超过安全限制，请检查文件"
        else -> error.message ?: fallback
    }
}
