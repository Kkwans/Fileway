package io.github.kkwans.nasfilebrowser.app

import io.github.kkwans.nasfilebrowser.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class StorageToolsState(val scope: String = "", val userId: Long = -1, val tool: String = "storage", val volumes: List<JSONObject> = emptyList(),
    val volumeError: String? = null, val recent: List<JSONObject> = emptyList(), val nextCursor: String = "", val loading: Boolean = false,
    val changing: Boolean = false, val error: String? = null, val notice: String? = null, val reportId: String? = null,
    val report: JSONObject? = null, val reportLoading: Boolean = false, val reportError: String? = null, val task: ServerTask? = null,
    val cleanup: ServerTask? = null, val cleanupResult: JSONObject? = null, val cleanupLoading: Boolean = false,
    val cleanupError: String? = null, val cleanupLookupKnown: Boolean = false, val permissions: ServerPermissions = ServerPermissions())

class StorageToolsController(private val scope: CoroutineScope, private val isCurrent: (SessionContext) -> Boolean) {
    private val mutable = MutableStateFlow(StorageToolsState())
    val state = mutable.asStateFlow()
    private var bound: SessionContext? = null
    private var reads: Job? = null; private var reports: Job? = null; private var writes: Job? = null; private var poll: Job? = null
    private var readEpoch = 0L; private var reportEpoch = 0L; private var visible = false
    private var fetchingReport: String? = null
    private val unconfirmedCleanupReports = mutableSetOf<String>()
    fun bind(context: SessionContext?) {
        reads?.cancel(); reports?.cancel(); writes?.cancel(); poll?.cancel(); readEpoch++; reportEpoch++
        bound = context; visible = false; fetchingReport = null; unconfirmedCleanupReports.clear()
        mutable.value = StorageToolsState(scope = context?.owner.orEmpty(), userId = context?.account?.userId ?: -1, permissions = context?.api?.identity?.permissions ?: ServerPermissions())
    }
    private fun current(context: SessionContext) = bound === context && isCurrent(context)
    private fun rows(value: JSONArray) = (0 until value.length()).map { value.getJSONObject(it) }
    fun tool(value: String) {
        require(value in setOf("storage", "duplicates"))
        if (value == mutable.value.tool || mutable.value.changing) return
        reads?.cancel(); reports?.cancel(); readEpoch++; reportEpoch++
        mutable.value = mutable.value.copy(tool = value, recent = emptyList(), nextCursor = "", loading = false, reportId = null,
            report = null, reportError = null, reportLoading = false, task = null, cleanup = null, cleanupResult = null, cleanupLoading = false, cleanupError = null, cleanupLookupKnown = false)
        refresh()
    }
    fun setVisible(value: Boolean) {
        if (visible == value) return
        visible = value; poll?.cancel()
        if (!value) return
        refresh()
        poll = scope.launch {
            while (isActive) {
                delay(2000)
                val input = mutable.value; val context = bound ?: continue
                if (input.task?.active == true && input.reportId != null) openReport(input.reportId, input.tool, silent = true)
                if (input.cleanup?.active == true || input.reportId in unconfirmedCleanupReports) refreshCleanup(context, input.reportId)
            }
        }
    }
    fun refresh(more: Boolean = false) {
        val context = bound ?: return
        val input = mutable.value
        if (!current(context) || reads?.isActive == true || input.changing || more && input.nextCursor.isEmpty()) return
        val epoch = ++readEpoch
        mutable.value = input.copy(loading = true, error = null, notice = null)
        reads = scope.launch {
            try {
                val permissions = context.api.permissions()
                var volumes = input.volumes; var volumeError: String? = null
                if (!more) {
                    if (permissions.admin) try { volumes = rows(context.api.array("/api/volumes")) }
                    catch (error: Exception) { if (error is CancellationException) throw error; volumeError = "磁盘概览暂时无法读取" }
                    else { volumes = emptyList(); volumeError = "磁盘概览需要管理员权限；可继续分析账号有权访问的路径" }
                }
                val query = "/api/analysis/recent?tool=${input.tool}&limit=6" + if (more) "&cursor=" + java.net.URLEncoder.encode(input.nextCursor, "UTF-8") else ""
                val history = context.api.request("GET", query)
                val received = rows(history.getJSONArray("items"))
                if (current(context) && readEpoch == epoch) mutable.value = mutable.value.copy(volumes = volumes, volumeError = volumeError,
                    recent = (if (more) input.recent else emptyList()).plus(received).distinctBy { it.getString("id") }, nextCursor = history.optString("nextCursor"),
                    permissions = permissions, loading = false)
            } catch (error: Exception) {
                if (error !is CancellationException && current(context) && readEpoch == epoch) mutable.value = mutable.value.copy(loading = false,
                    error = error.message ?: "分析记录读取失败，已保留内容，请重试")
            }
        }
    }
    fun start(paths: List<String>) {
        val context = bound ?: return
        val tool = mutable.value.tool
        if (!current(context) || mutable.value.changing) return
        require(paths.isNotEmpty() && paths.size <= 32)
        val targets = paths.map(::collectionPath).distinct()
        reads?.cancel(); reports?.cancel(); readEpoch++; reportEpoch++; fetchingReport = null
        mutable.value = mutable.value.copy(changing = true, loading = false, error = null, notice = null)
        writes = scope.launch {
            try {
                check(context.api.permissions().download) { "当前账号没有读取分析内容的权限" }
                val task = ServerTask.from(context.api.action("POST", "/api/analysis/$tool", JSONObject().put("paths", JSONArray(targets))))
                if (current(context)) {
                    mutable.value = mutable.value.copy(changing = false, task = task, reportId = task.id, report = null, reportError = null,
                        cleanup = null, cleanupResult = null, cleanupLoading = false, cleanupError = null, cleanupLookupKnown = false, notice = "扫描已提交，可在任务中心查看")
                    openReport(task.id, tool); refresh()
                }
            } catch (error: Exception) { if (error !is CancellationException && current(context)) mutable.value = mutable.value.copy(changing = false, error = error.message ?: "分析任务未能提交") }
        }
    }
    fun openReport(id: String, tool: String = mutable.value.tool, silent: Boolean = false) {
        val context = bound ?: return
        if (!current(context) || mutable.value.changing) return
        if (reports?.isActive == true && fetchingReport == id) return
        reports?.cancel(); fetchingReport = id
        require(tool in setOf("storage", "duplicates"))
        val epoch = ++reportEpoch
        val same = mutable.value.reportId == id && mutable.value.tool == tool
        mutable.value = mutable.value.copy(tool = tool, reportId = id, report = if (same) mutable.value.report else null,
            reportLoading = !silent, reportError = null, cleanup = if (same) mutable.value.cleanup else null, cleanupResult = if (same) mutable.value.cleanupResult else null,
            cleanupLoading = false, cleanupError = null, cleanupLookupKnown = same && mutable.value.cleanupLookupKnown)
        reports = scope.launch {
            try {
                val task = ServerTask.from(context.api.request("GET", "/api/tasks/${android.net.Uri.encode(id)}"))
                check(task.type == if (tool == "storage") "analysis.storage" else "analysis.duplicates") { "这个任务不是对应的分析报告" }
                val result = if (task.status == "completed") context.api.request("GET", if (tool == "storage") "/api/analysis/storage/${android.net.Uri.encode(id)}" else "/api/analysis/${android.net.Uri.encode(id)}") else null
                if (current(context) && reportEpoch == epoch) mutable.value = mutable.value.copy(task = task, report = result ?: mutable.value.report,
                    reportLoading = false, reportError = if (task.status in setOf("failed", "interrupted", "canceled")) task.error.ifEmpty { "分析没有完成，可在任务中心查看或重试" } else null)
                if (tool == "duplicates" && current(context) && reportEpoch == epoch) refreshCleanup(context, id)
            } catch (error: Exception) {
                if (error !is CancellationException && current(context) && reportEpoch == epoch) mutable.value = mutable.value.copy(reportLoading = false,
                    reportError = error.message ?: "报告暂时无法读取，请重试")
            }
        }
    }
    fun closeReport() { reports?.cancel(); reportEpoch++; mutable.value = mutable.value.copy(reportId = null, report = null, task = null, reportError = null, reportLoading = false, cleanup = null, cleanupResult = null, cleanupLoading = false, cleanupError = null, cleanupLookupKnown = false) }
    private suspend fun refreshCleanup(context: SessionContext, id: String?) {
        if (id == null) return
        val epoch = reportEpoch
        fun matches() = current(context) && mutable.value.reportId == id && mutable.value.tool == "duplicates" && reportEpoch == epoch
        if (!matches()) return
        mutable.value = mutable.value.copy(cleanupLoading = true, cleanupError = null)
        try {
            val task = ServerTask.from(context.api.request("GET", "/api/analysis/duplicates/${android.net.Uri.encode(id)}/cleanup"))
            if (!matches()) return
            unconfirmedCleanupReports.remove(id)
            // Keep the acknowledged task even when its result is still pending or fails to read.
            mutable.value = mutable.value.copy(cleanup = task, cleanupLookupKnown = true)
            val result = try { context.api.request("GET", "/api/analysis/duplicates/cleanup/${android.net.Uri.encode(task.id)}") }
            catch (error: ServiceException) { if (error.status == 409) null else throw error }
            if (result != null) check(DuplicateCleanupPresentation.from(result)?.reportId == id) { "清理结果结构或报告身份无法确认" }
            if (matches()) mutable.value = mutable.value.copy(cleanupResult = result ?: mutable.value.cleanupResult, cleanupLoading = false)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (matches()) {
                val absent = error is ServiceException && error.status == 404 && mutable.value.cleanup == null
                val unknown = id in unconfirmedCleanupReports
                mutable.value = mutable.value.copy(cleanupLoading = false, cleanupLookupKnown = !unknown && (absent || mutable.value.cleanup != null),
                    cleanupError = if (unknown) "清理提交结果仍待核对，请稍后刷新；不会重复提交"
                    else if (absent) null else error.message ?: "清理状态暂时无法读取，请刷新报告核对")
            }
        }
    }
    fun cleanup(keepers: Map<String, String>) {
        val context = bound ?: return
        val input = mutable.value; val id = input.reportId ?: return
        if (!current(context) || input.changing || input.cleanup != null || !input.cleanupLookupKnown || input.cleanupLoading || id in unconfirmedCleanupReports) return
        require(keepers.isNotEmpty())
        val report = input.report ?: return
        val reportGroups = report.optJSONArray("groups") ?: return
        val valid = (0 until reportGroups.length()).mapNotNull { reportGroups.optJSONObject(it) }.associateBy { it.optString("sha256") }
        if (input.task?.status != "completed" || keepers.any { (hash, path) ->
            val group = valid[hash]
            val files = group?.optJSONArray("files")
            val members = files?.let { rows -> (0 until rows.length()).mapNotNull { rows.optJSONObject(it) } }.orEmpty()
            group == null || !duplicateGroupCanBeCleaned(report.optInt("schemaVersion"), group.optInt("totalFiles"), group.optString("keepReason"), members.map {
                it.optJSONObject("identity")?.let { identity -> identity.optLong("links") to identity.optLong("mode").toInt() }
            }) || members.none { it.optString("path") == path }
        }) {
            mutable.value = input.copy(cleanupError = "报告或保留项已变化，请重新选择并确认")
            return
        }
        mutable.value = input.copy(changing = true, cleanupError = null)
        writes = scope.launch {
            var issued = false
            try {
                check(context.api.permissions().delete) { "当前账号没有清理重复文件的权限" }
                check(input.task?.userId == context.account.userId) { "只能清理当前账号自己的分析报告" }
                val groups = JSONArray(); keepers.forEach { (hash, path) -> groups.put(JSONObject().put("sha256", hash).put("keepPath", path)) }
                issued = true
                val task = ServerTask.from(context.api.action("POST", "/api/analysis/duplicates/${android.net.Uri.encode(id)}/cleanup", JSONObject().put("groups", groups)))
                if (current(context)) mutable.value = mutable.value.copy(changing = false,
                    cleanup = if (mutable.value.reportId == id) task else mutable.value.cleanup, cleanupLookupKnown = true, notice = "清理任务已提交，重复副本会移入回收站")
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (current(context)) {
                    val rejected = !issued || error is ServiceException && error.status in setOf(400, 401, 403, 404, 409)
                    if (!rejected) unconfirmedCleanupReports.add(id)
                    mutable.value = mutable.value.copy(changing = false, cleanupLookupKnown = false,
                        cleanupError = "清理提交未获确认，正在核对服务器状态；请勿重复提交")
                    refreshCleanup(context, id)
                    if (rejected && current(context) && mutable.value.reportId == id && mutable.value.cleanup == null)
                        mutable.value = mutable.value.copy(cleanupError = error.message ?: "清理未能提交，刷新报告后可重新确认")
                }
            }
        }
    }
}
