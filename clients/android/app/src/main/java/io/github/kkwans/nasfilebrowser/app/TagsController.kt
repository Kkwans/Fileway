package io.github.kkwans.nasfilebrowser.app

import io.github.kkwans.nasfilebrowser.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

data class ServerTag(val id: String, val name: String, val color: String, val paths: List<String>, val pathRefs: List<TagPathRef> = paths.map { tagPathRef(it) }) {
    companion object { internal fun from(row: JSONObject): ServerTag { val refs = tagReferences(row); return ServerTag(row.getString("id"), row.getString("name"), row.getString("color"), refs.map { it.path }, refs) } }
}
data class TaggedResource(val path: String, val file: ResourceRef? = null, val error: String? = null, val ref: TagPathRef = tagPathRef(path), val key: String = ref.identity ?: "unknown/$path")
data class TagsState(val scope: String = "", val items: List<ServerTag> = emptyList(), val loaded: Boolean = false,
    val loading: Boolean = false, val changing: Boolean = false, val error: String? = null, val notice: String? = null,
    val filterId: String? = null, val globalFilter: Boolean = false,
    val pathTag: String? = null, val pathParent: String? = null, val pathParentWire: String? = null, val paths: List<TaggedResource> = emptyList(), val pathTotal: Int = 0,
    val pathsLoading: Boolean = false, val pathsError: String? = null)

class TagsController(private val scope: CoroutineScope, private val isCurrent: (SessionContext) -> Boolean) {
    private val mutable = MutableStateFlow(TagsState())
    val state = mutable.asStateFlow()
    private var bound: SessionContext? = null
    private var readJob: Job? = null
    private var writeJob: Job? = null
    private var pathJob: Job? = null
    private var pathEpoch = 0L
    private var readEpoch = 0L
    fun bind(context: SessionContext?) {
        readJob?.cancel(); writeJob?.cancel(); pathJob?.cancel(); pathEpoch++; readEpoch++
        bound = context; mutable.value = TagsState(scope = context?.owner.orEmpty())
    }
    private fun current(context: SessionContext) = bound === context && isCurrent(context)
    private suspend fun read(context: SessionContext): List<ServerTag> {
        val rows = context.api.array("/api/tags")
        return (0 until rows.length()).map { ServerTag.from(rows.getJSONObject(it)) }
    }
    fun refresh() = refresh(false)
    fun refresh(replaceRead: Boolean) {
        val context = bound ?: return
        if (!current(context) || mutable.value.loading && !replaceRead || mutable.value.changing) return
        if (replaceRead) readJob?.cancel()
        val epoch = ++readEpoch
        mutable.value = mutable.value.copy(loading = true, error = null, notice = null)
        readJob = scope.launch {
            try { val rows = read(context); if (current(context) && readEpoch == epoch) apply(rows) }
            catch (error: Exception) { if (error !is CancellationException && current(context) && readEpoch == epoch) mutable.value = mutable.value.copy(loading = false, error = error.message ?: "标签读取失败，请重试") }
        }
    }
    private fun apply(items: List<ServerTag>) {
        val before = mutable.value
        mutable.value = mutable.value.copy(items = items, loaded = true, loading = false, changing = false,
            filterId = mutable.value.filterId?.takeIf { id -> items.any { it.id == id } })
        before.pathTag?.let { selected ->
            if (items.any { it.id == selected }) { pathJob?.cancel(); pathEpoch++; loadPaths(selected, before.pathParent, parentWire = before.pathParentWire) }
            else { pathJob?.cancel(); pathEpoch++; mutable.value = mutable.value.copy(pathTag = null, paths = emptyList(), pathTotal = 0, pathsLoading = false) }
        }
    }
    fun filter(id: String?, global: Boolean = mutable.value.globalFilter) { mutable.value = mutable.value.copy(filterId = id, globalFilter = global) }
    fun matches(file: ResourceRef): Boolean = mutable.value.let { value ->
        val tag = value.items.firstOrNull { it.id == value.filterId } ?: return true
        return taggedResourceMatches(file, tag.pathRefs, value.globalFilter)
    }
    fun assigned(file: ResourceRef): Set<String> = mutable.value.items.filter { taggedResourceMatches(file, it.pathRefs) }.map { it.id }.toSet()
    fun colorAvailable(color: String, except: String? = null) = mutable.value.items.none { it.id != except && it.color.trim().equals(color.trim(), true) }
    private fun change(notice: String, onSaved: () -> Unit = {}, action: suspend (SessionContext) -> Unit) {
        val context = bound ?: return
        if (!current(context) || mutable.value.changing) return
        readJob?.cancel(); pathJob?.cancel(); pathEpoch++; readEpoch++
        mutable.value = mutable.value.copy(changing = true, loading = false, pathsLoading = false, error = null, notice = null)
        writeJob = scope.launch {
            var applied = false
            try {
                action(context); applied = true
                val rows = read(context)
                if (current(context)) { apply(rows); mutable.value = mutable.value.copy(notice = notice) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                // A multi-label save can partly succeed. Re-read the authority
                // before exposing another edit, rather than restoring a local fiction.
                val refreshed = try { read(context) } catch (e: Exception) { if (e is CancellationException) throw e; null }
                if (current(context)) {
                    if (refreshed != null) apply(refreshed)
                    mutable.value = mutable.value.copy(changing = false,
                        notice = notice.takeIf { applied },
                        error = if (applied) {
                            if (refreshed == null) "操作已保存，列表刷新失败，请刷新后查看" else null
                        } else error.message ?: "标签操作未完成，请确认当前标记后重试")
                }
            }
            if (applied && current(context)) onSaved()
        }
    }
    fun save(tag: ServerTag?, name: String, color: String, onSaved: () -> Unit = {}) {
        require(name.isNotBlank())
        if ((tag == null || !color.equals(tag.color, true)) && !colorAvailable(color, tag?.id)) {
            mutable.value = mutable.value.copy(error = "这个颜色已有标签使用，请选择其他颜色"); return
        }
        change("标签已保存", onSaved) { context ->
            val body = JSONObject().put("name", name.trim())
            if (tag == null || !color.equals(tag.color, true)) body.put("color", color.uppercase(java.util.Locale.ROOT))
            context.api.action(if (tag == null) "POST" else "PUT", "/api/tags" + (tag?.let { "/${android.net.Uri.encode(it.id)}" } ?: ""), body)
        }
    }
    fun remove(tag: ServerTag) = change("标签已删除，文件保持原位") { it.api.action("DELETE", "/api/tags/${android.net.Uri.encode(tag.id)}") }
    fun removePath(tag: ServerTag, ref: TagPathRef, sourceScope: String = mutable.value.scope) {
        val context = bound ?: return
        if (context.owner != sourceScope || !current(context) || mutable.value.changing) return
        if (!ref.openable) {
            // Identity is already known to be unavailable. Keep active reads
            // intact and report locally, without fetching the entire tag list.
            mutable.value = mutable.value.copy(error = "原始路径无法确认，不能按显示名称取消关联", notice = null)
            return
        }
        change("已取消此路径的标签") {
            check(ref.openable) { "原始路径无法确认，不能按显示名称取消关联" }
            writeAssociation(it, tag.id, ResourceRef(ref.path, ref.wirePath, "tag", false, "", 0), false)
        }
    }
    fun removePaths(tag: ServerTag, refs: List<TagPathRef>, sourceScope: String, onSaved: () -> Unit = {}) {
        if (bound?.owner != sourceScope || refs.isEmpty() || refs.any { !it.openable }) return
        change("已取消所选路径的标签", onSaved) { context ->
            for (ref in refs) {
                currentCoroutineContext().ensureActive(); check(current(context))
                writeAssociation(context, tag.id, ResourceRef(ref.path, ref.wirePath, "tag", false, "", 0), false)
            }
        }
    }
    private suspend fun writeAssociation(context: SessionContext, id: String, file: ResourceRef, adding: Boolean) {
        val body = tagAssociationBody(file)
        val response = context.api.action(if (adding) "POST" else "DELETE", "/api/tags/${android.net.Uri.encode(id)}/paths", body)
        val saved = ServerTag.from(response)
        check(saved.id == id && taggedResourceMatches(file, saved.pathRefs) == adding) { "服务器未确认原始路径标签结果，请刷新核对" }
    }
    fun assign(file: ResourceRef, baseline: Set<String>, desired: Set<String>, onSaved: () -> Unit = {}, sourceScope: String = mutable.value.scope) {
        if (bound?.owner != sourceScope) return
        change("文件标签已更新", onSaved) { context ->
            check(sourceScope == context.owner) { "文件来源已切换，请重新选择" }
            val additions = desired - baseline; val removals = baseline - desired
            val fresh = read(context).map { it.id }.toSet()
            check(additions.all { it in fresh }) { "所选标签已被删除，请刷新后重新选择" }
            for (id in additions + removals) {
                currentCoroutineContext().ensureActive(); check(current(context))
                if (id !in fresh) continue
                writeAssociation(context, id, file, id in additions)
            }
        }
    }
    fun selectionPaths(): List<TagPathRef> {
        val state = mutable.value
        val tag = state.items.firstOrNull { it.id == state.pathTag } ?: return emptyList()
        val parent = state.pathParent?.let { tagPathRef(it, state.pathParentWire).identity }
        return tag.pathRefs.filter { it.openable && (state.pathParent == null || parent != null && it.identity?.substringBeforeLast('/')?.ifEmpty { "/" } == parent) }
    }
    fun loadPaths(id: String, parent: String?, more: Boolean = false, parentWire: String? = null) {
        val context = bound ?: return
        val tag = mutable.value.items.firstOrNull { it.id == id } ?: return
        val parentIdentity = parent?.let { tagPathRef(it, parentWire).identity }
        if (parent != null && parentIdentity == null) {
            pathJob?.cancel(); pathEpoch++
            mutable.value = mutable.value.copy(pathTag = id, pathParent = parent, pathParentWire = parentWire, paths = emptyList(), pathTotal = 0, pathsLoading = false, pathsError = "当前目录原始路径无法确认，请重新选择目录")
            return
        }
        val candidates = tag.pathRefs.filter { parent == null || parentIdentity != null && it.identity?.substringBeforeLast('/')?.ifEmpty { "/" } == parentIdentity }
        val same = mutable.value.pathTag == id && mutable.value.pathParent == parent && mutable.value.pathParentWire == parentWire
        if (same && pathJob?.isActive == true) return
        val count = if (more && same) mutable.value.paths.size + 40 else if (same) maxOf(40, mutable.value.paths.size) else 40
        pathJob?.cancel(); val epoch = ++pathEpoch
        mutable.value = mutable.value.copy(pathTag = id, pathParent = parent, pathParentWire = parentWire, paths = if (same) mutable.value.paths else emptyList(),
            pathTotal = candidates.size, pathsLoading = true, pathsError = null)
        pathJob = scope.launch {
            try {
                val result = mutableListOf<TaggedResource>()
                val selected = candidates.take(count)
                val resolved = linkedMapOf<String, TaggedResource>()
                for (chunk in selected.filter { it.openable }.chunked(100)) {
                    val rows = context.api.resourceBatch(chunk.map { it.path }, chunk.map { it.wirePath })
                    check(rows.length() == chunk.size)
                    for (index in chunk.indices) {
                        val row = rows.getJSONObject(index); val ref = chunk[index]; val item = row.optJSONObject("item")
                        val acknowledged = favoritePathIdentity(row.getString("path"), row.optString("wirePath").takeIf { it.isNotEmpty() }, null)
                        check(acknowledged.openable && favoriteWireIdentity(acknowledged.wirePath) == ref.identity) { "批量资源返回的原始路径顺序不匹配，请刷新重试" }
                        val file = item?.takeIf { row.optInt("status") == 200 }?.let {
                            val actual = favoritePathIdentity(it.getString("path"), it.optString("wirePath").takeIf { it.isNotEmpty() }, null)
                            check(actual.openable && favoriteWireIdentity(actual.wirePath) == ref.identity) { "标签资源的原始路径不匹配" }
                            ResourceRef(it.getString("path"), actual.wirePath, it.getString("name"),
                                it.getBoolean("isDir"), it.optString("type"), it.optLong("size"), it.optString("modified"))
                        }
                        resolved[ref.identity!!] = TaggedResource(ref.path, file, if (file == null) when (row.optInt("status")) {
                            403 -> "当前账号无权访问"; 404 -> "文件已移走或删除"; else -> "路径暂时无法读取"
                        } else null, ref, ref.identity!!)
                    }
                }
                selected.forEachIndexed { index, ref -> result.add(ref.identity?.let { resolved[it] } ?: TaggedResource(ref.path, error = "原始路径无法确认，请刷新或升级服务器", ref = ref, key = "$id/unknown/$index")) }
                if (current(context) && pathEpoch == epoch) mutable.value = mutable.value.copy(paths = result, pathsLoading = false)
            } catch (error: Exception) {
                if (error !is CancellationException && current(context) && pathEpoch == epoch)
                    mutable.value = mutable.value.copy(pathsLoading = false, pathsError = error.message ?: "标记路径读取失败，请重试")
            }
        }
    }
}
