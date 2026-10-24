package io.github.kkwans.nasfilebrowser.app

import io.github.kkwans.nasfilebrowser.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject

data class ServerTasksState(val scope: String = "", val filter: TaskFilter = TaskFilter(), val items: List<ServerTask> = emptyList(),
    val loaded: Boolean = false, val loading: Boolean = false, val paging: Boolean = false, val changing: Boolean = false,
    val nextCursor: String = "", val total: Int = 0, val counts: Map<String, Int> = emptyMap(), val owners: List<String> = emptyList(),
    val error: String? = null, val notice: String? = null, val selected: ServerTask? = null, val detailLoading: Boolean = false,
    val detailError: String? = null, val focusId: String? = null, val permissions: ServerPermissions = ServerPermissions())

class ServerTasksController(private val scope: CoroutineScope, private val isCurrent: (SessionContext) -> Boolean) {
    private val mutable = MutableStateFlow(ServerTasksState())
    val state = mutable.asStateFlow()
    private var bound: SessionContext? = null
    private var reads: Job? = null
    private var changes: Job? = null
    private var detail: Job? = null
    private var poll: Job? = null
    private var visible = false
    private var revision = 0L
    private var detailEpoch = 0L
    private var watched = emptySet<String>()
    fun bind(context: SessionContext?) {
        reads?.cancel(); changes?.cancel(); detail?.cancel(); poll?.cancel(); revision++; detailEpoch++
        bound = context; visible = false; watched = emptySet()
        mutable.value = ServerTasksState(scope = context?.owner.orEmpty(), filter = TaskFilter(owner = context?.account?.username.orEmpty()), permissions = context?.api?.identity?.permissions ?: ServerPermissions())
    }
    private fun current(context: SessionContext) = bound === context && isCurrent(context)
    fun setVisible(value: Boolean) {
        if (visible == value) return
        visible = value; poll?.cancel()
        if (!value) return
        refresh(silent = mutable.value.loaded)
        poll = scope.launch {
            while (isActive) {
                delay(if (mutable.value.items.any { it.active } || mutable.value.selected?.active == true) 2000 else 10_000)
                refresh(silent = true)
            }
        }
    }
    fun filter(value: TaskFilter) {
        if (value == mutable.value.filter || mutable.value.changing) return
        reads?.cancel(); revision++
        mutable.value = mutable.value.copy(filter = value, items = emptyList(), nextCursor = "", loaded = false, loading = false, paging = false, error = null, notice = null)
        refresh()
    }
    fun watch(ids: Set<String>) { watched = ids }
    fun selectAll(onSelected: (Set<String>) -> Unit) {
        val context = bound ?: return
        val before = mutable.value
        if (!current(context) || before.changing || before.loading || before.paging) return
        reads?.cancel(); val epoch = ++revision
        mutable.value = before.copy(loading = true, error = null)
        reads = scope.launch {
            try {
                val rows = linkedMapOf<String, ServerTask>()
                val cursors = mutableSetOf<String>()
                var cursor = ""
                do {
                    check(current(context) && revision == epoch) { "任务来源已切换" }
                    val page = context.api.request("GET", before.filter.query(cursor))
                    val entries = page.getJSONArray("items")
                    for (i in 0 until entries.length()) ServerTask.from(entries.getJSONObject(i)).let { rows[it.id] = it }
                    cursor = page.optString("nextCursor")
                    check(cursor.isEmpty() || cursors.add(cursor)) { "服务器返回重复分页，未执行全选" }
                } while (cursor.isNotEmpty())
                if (current(context) && revision == epoch) {
                    mutable.value = mutable.value.copy(items = rows.values.toList(), total = rows.size, loaded = true, loading = false, nextCursor = "")
                    onSelected(rows.keys.toSet())
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (current(context) && revision == epoch) mutable.value = mutable.value.copy(loading = false, error = error.message ?: "全选读取失败")
            }
        }
    }
    fun refresh(silent: Boolean = false, more: Boolean = false) {
        val context = bound ?: return
        val before = mutable.value
        if (!current(context) || reads?.isActive == true || before.changing || more && before.nextCursor.isEmpty()) return
        val epoch = ++revision; val selectionEpoch = detailEpoch; val selectedId = before.focusId
        mutable.value = before.copy(loading = !silent && !more, paging = more, error = if (silent) before.error else null, notice = if (silent) before.notice else null)
        reads = scope.launch {
            try {
                val response = context.api.request("GET", before.filter.query(if (more) before.nextCursor else ""))
                val permissions = context.api.permissions()
                val items = linkedMapOf<String, ServerTask>()
                if (more || silent) before.items.filter { permissions.admin || it.userId == context.account.userId }.forEach { items[it.id] = it }
                val rows = response.getJSONArray("items"); val returned = mutableSetOf<String>()
                for (i in 0 until rows.length()) ServerTask.from(rows.getJSONObject(i)).let { items[it.id] = it; returned.add(it.id) }
                var selected = mutable.value.selected
                var detailMessage: String? = null
                val gate = Semaphore(4)
                val updates = coroutineScope {
                    (if (silent) watched else emptySet()).plus(listOfNotNull(selectedId)).minus(returned).map { id -> async {
                        gate.withPermit {
                            try { Triple(id, ServerTask.from(context.api.request("GET", "/api/tasks/${android.net.Uri.encode(id)}")), null as String?) }
                            catch (error: Exception) { if (error is CancellationException) throw error; Triple(id, null, error.message ?: "任务更新失败") }
                        }
                    } }.awaitAll()
                }
                updates.forEach { (id, task, message) ->
                    if (task != null && matches(task, before.filter)) items[id] = task else if (task != null) items.remove(id)
                    if (id == selectedId) { selected = task ?: selected; detailMessage = message }
                }
                if (selectedId != null && selectedId in returned) selected = items[selectedId]
                val count = response.optJSONObject("categoryCounts")?.optJSONObject(before.filter.category) ?: response.optJSONObject("counts")
                val owners = response.optJSONArray("owners")
                if (current(context) && revision == epoch) {
                    mutable.value = mutable.value.copy(items = items.values.sortedWith(compareByDescending<ServerTask> { it.createdAt }.thenByDescending { it.id }),
                        nextCursor = if (silent && before.items.size > 30) before.nextCursor.ifEmpty {
                            if (response.optInt("total") > items.size) response.optString("nextCursor") else ""
                        } else response.optString("nextCursor"), total = response.optInt("total"), loaded = true,
                        loading = false, paging = false, permissions = permissions,
                        selected = if (detailEpoch == selectionEpoch && mutable.value.focusId == selectedId) selected else mutable.value.selected,
                        detailError = if (detailEpoch == selectionEpoch && mutable.value.focusId == selectedId) detailMessage else mutable.value.detailError,
                        counts = if (count == null) emptyMap() else count.keys().asSequence().associateWith { count.optInt(it) },
                        owners = (if (permissions.admin) before.owners else emptyList()).plus(if (owners == null) emptyList() else (0 until owners.length()).map { owners.getString(it) }).distinct().sorted(), error = null)
                }
            } catch (error: Exception) {
                if (error !is CancellationException && current(context) && revision == epoch)
                    mutable.value = mutable.value.copy(loading = false, paging = false, error = error.message ?: "任务读取失败，已保留列表，请重试")
            }
        }
    }
    private fun matches(task: ServerTask, filter: TaskFilter): Boolean =
        task.fileCategory == (filter.category == "file") && (task.archivedAt > 0) == (filter.view == TaskView.ARCHIVED) &&
            (filter.view.statuses.isEmpty() || task.status in filter.view.statuses) && (filter.type.isEmpty() || task.type == filter.type) &&
            (filter.owner.isEmpty() || task.owner.equals(filter.owner.trim(), true) || task.userId.toString() == filter.owner.trim()) &&
            (filter.from <= 0 || task.createdAt >= filter.from) && (filter.to <= 0 || task.createdAt <= filter.to) &&
            (filter.text.isBlank() || listOf(task.id, task.title, task.type, task.error, task.owner).joinToString("\n").contains(filter.text.trim(), true))
    fun canEdit(task: ServerTask) = bound?.let { mutable.value.permissions.admin || task.userId == it.account.userId } == true
    fun select(id: String) {
        val context = bound ?: return
        detail?.cancel(); val epoch = ++detailEpoch
        mutable.value = mutable.value.copy(selected = mutable.value.items.firstOrNull { it.id == id }, detailLoading = true, detailError = null, focusId = id)
        detail = scope.launch {
            try {
                val task = ServerTask.from(context.api.request("GET", "/api/tasks/${android.net.Uri.encode(id)}"))
                if (current(context) && detailEpoch == epoch) mutable.value = mutable.value.copy(selected = task, detailLoading = false)
            } catch (error: Exception) {
                if (error !is CancellationException && current(context) && detailEpoch == epoch)
                    mutable.value = mutable.value.copy(detailLoading = false, detailError = error.message ?: "任务详情读取失败")
            }
        }
    }
    fun closeDetail() { detail?.cancel(); detailEpoch++; mutable.value = mutable.value.copy(selected = null, focusId = null, detailLoading = false, detailError = null) }
    private fun change(action: suspend (SessionContext) -> String) {
        val context = bound ?: return
        if (!current(context) || mutable.value.changing) return
        reads?.cancel(); revision++
        mutable.value = mutable.value.copy(changing = true, loading = false, paging = false, error = null, notice = null)
        changes = scope.launch {
            try {
                val message = action(context)
                if (current(context)) { mutable.value = mutable.value.copy(changing = false, notice = message); refresh(silent = true) }
            } catch (error: Exception) {
                if (error !is CancellationException && current(context)) mutable.value = mutable.value.copy(changing = false,
                    error = if (error is ServiceException && error.status == 409) "任务数量或状态已变化，请刷新后重新确认" else error.message ?: "任务操作失败，请重试")
            }
        }
    }
    fun action(task: ServerTask, value: String) = change { context ->
        check(canEdit(task)) { "当前账号无法操作这个任务" }
        check(when (value) { "cancel" -> task.active; "retry" -> task.canRetry; "archive" -> task.canArchive; "unarchive" -> task.archivedAt > 0; else -> false }) { "这个任务当前不支持所选操作" }
        val updated = ServerTask.from(context.api.action("POST", "/api/tasks/${android.net.Uri.encode(task.id)}/$value"))
        if (current(context) && mutable.value.focusId == task.id) { detailEpoch++; mutable.value = mutable.value.copy(selected = updated, focusId = updated.id) }
        when (value) { "cancel" -> "已请求取消任务"; "retry" -> "已创建重试任务"; "archive" -> "任务记录已删除，可从已删除中恢复"; else -> "任务记录已恢复" }
    }
    fun batch(value: String, filter: TaskFilter, expectedCount: Int) = change { context ->
        val response = context.api.action("POST", "/api/tasks/batch", JSONObject().put("action", value).put("filters", filter.json()).put("expectedCount", expectedCount))
        val failed = response.optJSONArray("failures")?.length() ?: 0
        "已处理 ${response.optInt("succeeded")} / ${response.optInt("matched")} 项" + if (failed > 0) "，$failed 项失败，请刷新后查看" else ""
    }
    fun actionSelected(ids: Set<String>, value: String) = change { context ->
        val selected = mutable.value.items.filter { it.id in ids }
        require(ids.isNotEmpty() && selected.size == ids.size && value in setOf("archive", "unarchive"))
        check(selected.all { canEdit(it) && if (value == "archive") it.canArchive else it.archivedAt > 0 }) { "所选任务状态或权限已变化，请刷新核对" }
        var completed = 0
        for (task in selected) {
            check(current(context)) { "任务来源已切换" }
            try {
                ServerTask.from(context.api.action("POST", "/api/tasks/${android.net.Uri.encode(task.id)}/$value"))
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                throw IllegalStateException("已处理 $completed / ${selected.size} 项；后续结果未确认，请刷新核对：${error.message.orEmpty()}", error)
            }
            if (!current(context)) return@change "任务来源已切换"
            completed++
            mutable.value = mutable.value.copy(items = mutable.value.items.filterNot { it.id == task.id }, total = (mutable.value.total - 1).coerceAtLeast(0))
        }
        "已${if (value == "archive") "删除" else "恢复"} $completed 条任务记录，文件保留"
    }
    fun archiveEnded() = change { context ->
        val count = context.api.action("DELETE", "/api/tasks?category=${mutable.value.filter.category}").optInt("deleted")
        "已删除我的 $count 条已结束任务记录，文件保留"
    }
}
