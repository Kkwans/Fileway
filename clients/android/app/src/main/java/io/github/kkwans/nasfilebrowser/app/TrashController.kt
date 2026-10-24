package io.github.kkwans.nasfilebrowser.app

import io.github.kkwans.nasfilebrowser.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class TrashItem(val id: String, val userId: Long, val owner: String, val path: String, val name: String, val directory: Boolean,
    val size: Long, val sizeState: String, val sizeTaskId: String, val deletedAt: Long, val status: String, val error: String) {
    companion object { fun from(row: JSONObject) = TrashItem(row.getString("id"), row.getLong("userId"), row.optString("ownerName"),
        row.getString("originalPath"), row.getString("name"), row.getBoolean("isDir"), row.optLong("size"), row.optString("sizeState").ifEmpty { if (row.optBoolean("isDir")) "unknown" else "accurate" },
        row.optString("sizeTaskId"), row.optLong("deletedAt"), row.getString("status"), row.optString("error")) }
}
data class TrashState(val scope: String = "", val items: List<TrashItem> = emptyList(), val loaded: Boolean = false,
    val loading: Boolean = false, val changing: Boolean = false, val error: String? = null, val notice: String? = null,
    val permissions: ServerPermissions = ServerPermissions(), val conflict: TrashItem? = null, val lastTask: ServerTask? = null,
    val permanentResultUnknown: Boolean = false)
private data class TrashResult(val notice: String, val task: ServerTask? = null, val moved: ResourceRef? = null, val restored: String? = null)

class TrashController(private val scope: CoroutineScope, private val isCurrent: (SessionContext) -> Boolean,
    private val onMoved: (SessionContext, ResourceRef) -> Unit, private val onRestored: (SessionContext, String) -> Unit) {
    private val mutable = MutableStateFlow(TrashState())
    val state = mutable.asStateFlow()
    private var bound: SessionContext? = null
    private var reads: Job? = null
    private var writes: Job? = null
    private var poll: Job? = null
    private var visible = false
    private var revision = 0L
    fun bind(context: SessionContext?) {
        reads?.cancel(); writes?.cancel(); poll?.cancel(); revision++; visible = false; bound = context
        mutable.value = TrashState(scope = context?.owner.orEmpty(), permissions = context?.api?.identity?.permissions ?: ServerPermissions())
    }
    private fun current(context: SessionContext) = bound === context && isCurrent(context)
    private suspend fun list(context: SessionContext): List<TrashItem> {
        val rows = context.api.array("/api/trash")
        return (0 until rows.length()).map { TrashItem.from(rows.getJSONObject(it)) }
    }
    fun setVisible(value: Boolean) {
        if (visible == value) return
        visible = value; poll?.cancel()
        if (!value) return
        refresh(silent = mutable.value.loaded)
        poll = scope.launch {
            while (isActive) {
                val active = mutable.value.items.any { it.status in setOf("pending", "restoring") || it.sizeState == "calculating" } || mutable.value.lastTask?.active == true
                delay(if (active) 1000 else 10_000)
                refresh(silent = true)
            }
        }
    }
    fun refresh(silent: Boolean = false) {
        val context = bound ?: return
        if (!current(context) || reads?.isActive == true || mutable.value.changing) return
        val epoch = ++revision
        mutable.value = mutable.value.copy(loading = !silent, error = if (silent) mutable.value.error else null, notice = if (silent) mutable.value.notice else null)
        reads = scope.launch {
            try {
                val rows = list(context); val permissions = context.api.permissions()
                val previous = mutable.value.lastTask
                val task = previous?.takeIf { it.active }?.let { ServerTask.from(context.api.request("GET", "/api/tasks/${android.net.Uri.encode(it.id)}")) } ?: previous
                if (current(context) && revision == epoch) mutable.value = mutable.value.copy(items = rows, permissions = permissions, lastTask = task, loaded = true, loading = false, error = null)
            } catch (error: Exception) {
                if (error !is CancellationException && current(context) && revision == epoch)
                    mutable.value = mutable.value.copy(loading = false, error = error.message ?: "回收站读取失败，已保留列表，请重试")
            }
        }
    }
    private fun change(done: (() -> Unit)? = null, uncertainOnFailure: () -> Boolean = { false }, action: suspend (SessionContext) -> TrashResult) {
        val context = bound ?: return
        if (!current(context) || mutable.value.changing) return
        reads?.cancel(); revision++
        mutable.value = mutable.value.copy(changing = true, loading = false, error = null, notice = null)
        writes = scope.launch {
            var applied: TrashResult? = null
            try {
                val result = action(context); applied = result
                if (!current(context)) return@launch
                result.moved?.let { onMoved(context, it) }; result.restored?.let { onRestored(context, it) }; done?.invoke()
                mutable.value = mutable.value.copy(notice = result.notice, lastTask = result.task ?: mutable.value.lastTask)
                val rows = list(context); val permissions = context.api.permissions()
                if (current(context)) mutable.value = mutable.value.copy(items = rows, loaded = true, permissions = permissions, changing = false)
            } catch (error: Exception) {
                if (error !is CancellationException && current(context)) {
                    val unknown = applied == null && uncertainOnFailure() && (error !is ServiceException || error.status >= 500)
                    mutable.value = mutable.value.copy(changing = false,
                        permanentResultUnknown = mutable.value.permanentResultUnknown || unknown,
                        error = if (unknown) "删除提交结果未确认，请先在任务中心核对，勿重复提交"
                            else if (applied != null) "操作已保存，列表刷新失败，请刷新查看实际状态" else error.message ?: "回收站操作失败，请重试")
                }
            }
        }
    }
    fun move(file: ResourceRef, done: () -> Unit) = change(done) { context ->
        require(file.path != "/" && file.path.startsWith('/')) { "不能移动文件系统根目录" }
        check(context.api.permissions().delete) { "当前账号没有删除权限" }
        val wire = file.wirePath.ifEmpty { SearchResult.encodePath(file.path) }
        context.api.action("DELETE", "/api/resources$wire?mode=trash")
        TrashResult("已移入回收站", moved = file)
    }
    fun moveAll(files: List<ResourceRef>, moved: (ResourceRef) -> Unit, done: () -> Unit) = change(done) { context ->
        val snapshot = files.distinctBy { it.wirePath.ifEmpty { it.path } }
        require(snapshot.isNotEmpty() && snapshot.all { it.path != "/" && it.path.startsWith('/') && it.downloadId.isEmpty() }) { "请选择服务器上的文件或文件夹，不能移动根目录" }
        check(context.api.permissions().delete) { "当前账号没有删除权限" }
        var completed = 0
        for (file in snapshot) {
            currentCoroutineContext().ensureActive()
            check(current(context)) { "连接已切换，已停止剩余操作" }
            try {
                val wire = file.wirePath.ifEmpty { SearchResult.encodePath(file.path) }
                context.api.action("DELETE", "/api/resources$wire?mode=trash")
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                throw IllegalStateException("已移入 $completed / ${snapshot.size} 项；${file.name}：${failure.message ?: "结果未确认"}。请刷新核对未完成项后再处理。", failure)
            }
            if (!current(context)) return@change TrashResult("连接已切换")
            completed++
            onMoved(context, file); moved(file)
            if (!current(context)) return@change TrashResult("连接已切换")
            mutable.value = mutable.value.copy(notice = "已移入 $completed / ${snapshot.size} 项")
        }
        TrashResult("已将 $completed 项移入回收站")
    }
    fun restore(item: TrashItem, conflict: String = "fail") = change { context ->
        require(conflict in setOf("fail", "keep-both", "replace", "skip"))
        val perm = context.api.permissions()
        check(perm.admin || perm.create) { "当前账号没有恢复文件的权限" }
        check(conflict != "replace" || perm.admin || perm.delete) { "替换现有文件需要删除权限" }
        val result = try { context.api.action("POST", "/api/trash/${android.net.Uri.encode(item.id)}/restore", JSONObject().put("conflict", conflict)) }
        catch (error: ServiceException) {
            if (error.status == 409 && conflict == "fail" && current(context)) {
                mutable.value = mutable.value.copy(conflict = item)
                return@change TrashResult("恢复未完成，请选择同名文件处理方式或刷新状态")
            }
            throw error
        }
        val skipped = result.optBoolean("skipped"); val path = result.optString("path", item.path)
        if (current(context)) mutable.value = mutable.value.copy(conflict = null)
        TrashResult(if (skipped) "已跳过，项目仍在回收站" else "已恢复到 $path", restored = path.takeUnless { skipped })
    }
    fun closeConflict() { mutable.value = mutable.value.copy(conflict = null) }
    fun restoreAll(items: List<TrashItem>) = change { context ->
        val snapshot = items.distinctBy { it.id }
        require(snapshot.isNotEmpty() && snapshot.all { it.status == "available" }) { "所选项目包含暂不可还原的内容" }
        val perm = context.api.permissions()
        check(perm.admin || perm.create) { "当前账号没有恢复文件的权限" }
        var completed = 0
        for (item in snapshot) {
            if (!current(context)) return@change TrashResult("连接已切换")
            val result = try { context.api.action("POST", "/api/trash/${android.net.Uri.encode(item.id)}/restore", JSONObject().put("conflict", "fail")) }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                if (error is ServiceException && error.status == 409 && current(context)) {
                    mutable.value = mutable.value.copy(conflict = item)
                    return@change TrashResult("已还原 $completed 项；${item.name} 存在同名目标，请处理冲突后继续")
                }
                throw IllegalStateException("已还原 $completed / ${snapshot.size} 项；后续结果未确认，请刷新核对：${error.message.orEmpty()}", error)
            }
            if (!current(context)) return@change TrashResult("连接已切换")
            if (!result.optBoolean("skipped")) { completed++; onRestored(context, result.optString("path", item.path)) }
        }
        TrashResult("已还原 $completed 项")
    }
    fun permanentlyDeleteResource(file: ResourceRef, done: () -> Unit) {
        if (mutable.value.permanentResultUnknown) return
        var submitted = false
        change(done, uncertainOnFailure = { submitted }) { context ->
            require(file.path != "/" && file.path.startsWith('/') && file.downloadId.isEmpty()) { "请选择服务器文件，不能永久删除根目录" }
            check(context.api.permissions().delete) { "当前账号没有永久删除权限" }
            check(current(context)) { "文件来源已切换，请重新选择" }
            val target = recentAccessRecordTarget(file)
            val body = JSONObject().put("kind", "resources").put("wirePaths", JSONArray().put(target.wirePath))
            if (target.legacyCompatible) body.put("paths", JSONArray().put(target.path))
            submitted = true
            val task = ServerTask.from(context.api.action("POST", "/api/deletions/pending", body))
            TrashResult("永久删除任务已提交，开始前可以在任务中心撤销", task = task)
        }
    }
    fun permanentlyDelete(ids: List<String>, all: Boolean = false) = change { context ->
        check(context.api.permissions().delete) { "当前账号没有永久删除权限" }
        require(all || ids.isNotEmpty())
        val body = JSONObject().put("kind", if (all) "trash-all" else "trash-items")
        if (!all) body.put("ids", JSONArray(ids))
        val task = ServerTask.from(context.api.action("POST", "/api/deletions/pending", body))
        TrashResult("删除任务已提交，开始前可以撤销", task = task)
    }
    fun measure(item: TrashItem) = change { context ->
        val perm = context.api.permissions()
        check(perm.admin || perm.delete) { "当前账号没有统计此项目的权限" }
        val task = ServerTask.from(context.api.action("POST", "/api/trash/${android.net.Uri.encode(item.id)}/size"))
        TrashResult("大小统计任务已提交", task = task)
    }
    fun undo() = change { context ->
        val task = requireNotNull(mutable.value.lastTask)
        check(task.active)
        val result = ServerTask.from(context.api.action("POST", "/api/tasks/${android.net.Uri.encode(task.id)}/cancel"))
        TrashResult("已请求取消删除任务，请查看实际结果", task = result)
    }
}
