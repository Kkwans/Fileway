package io.github.kkwans.nasfilebrowser.app

import io.github.kkwans.nasfilebrowser.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

data class Favorite(val id: String, val path: String, val name: String, val groupId: String, val order: Int,
    val wirePath: String = "", val openable: Boolean = false) {
    companion object { fun from(row: JSONObject): Favorite {
        val path = row.getString("path")
        val identity = favoritePathIdentity(path, if (row.has("wirePath")) row.get("wirePath") as? String ?: error("收藏原始路径格式无效") else null,
            if (row.has("pathVerified")) row.get("pathVerified") as? Boolean ?: error("收藏路径状态无效") else null)
        return Favorite(row.getString("id"), path, row.getString("name"), row.optString("groupId"), row.optInt("order"), identity.wirePath, identity.openable)
    } }
}
data class FavoriteGroup(val id: String, val name: String, val color: String, val order: Int)
data class FavoritesState(val scope: String = "", val items: List<Favorite> = emptyList(), val groups: List<FavoriteGroup> = emptyList(),
    val loading: Boolean = false, val changing: Boolean = false, val loaded: Boolean = false, val error: String? = null, val notice: String? = null)

/** The NAS remains the only authority. No local IDs or independent favorite database. */
class FavoritesController(private val scope: CoroutineScope, private val isCurrent: (SessionContext) -> Boolean) {
    private val mutable = MutableStateFlow(FavoritesState())
    val state = mutable.asStateFlow()
    private var bound: SessionContext? = null
    private var reads: Job? = null
    private var changes: Job? = null
    private var revision = 0L
    private val writes = Mutex()
    fun bind(context: SessionContext?) {
        reads?.cancel(); changes?.cancel(); revision++
        bound = context; mutable.value = FavoritesState(scope = context?.owner.orEmpty())
    }
    private suspend fun read(context: SessionContext): Pair<List<Favorite>, List<FavoriteGroup>> = coroutineScope {
        val items = async { context.api.array("/api/favorites") }
        val groups = async { context.api.array("/api/favorites/groups") }
        val rows = items.await(); val folders = groups.await()
        (0 until rows.length()).map { Favorite.from(rows.getJSONObject(it)) }.sortedWith(compareBy<Favorite> { it.order }.thenBy { it.id }) to
            (0 until folders.length()).map { folders.getJSONObject(it).let { row ->
                FavoriteGroup(row.getString("id"), row.getString("name"), row.optString("color"), row.optInt("order"))
            } }.sortedWith(compareBy<FavoriteGroup> { it.order }.thenBy { it.id })
    }
    fun refresh() = refresh(false)
    fun refresh(replaceRead: Boolean) {
        val context = bound ?: return
        if (mutable.value.loading && !replaceRead || mutable.value.changing || !isCurrent(context)) return
        if (replaceRead) reads?.cancel()
        val expected = ++revision
        mutable.value = mutable.value.copy(loading = true, error = null, notice = null)
        reads = scope.launch {
            try {
                val (items, groups) = read(context)
                if (bound === context && isCurrent(context) && revision == expected)
                    mutable.value = mutable.value.copy(items = items, groups = groups, loaded = true, loading = false)
            } catch (error: Exception) {
                if (error !is CancellationException && bound === context && isCurrent(context) && revision == expected)
                    mutable.value = mutable.value.copy(loading = false, error = error.message ?: "收藏读取失败，请重试")
            }
        }
    }
    fun favorite(file: ResourceRef): Favorite? {
        val wire = runCatching { favoriteWireIdentity(favoriteRecordTarget(file).wirePath) }.getOrNull() ?: return null
        return mutable.value.items.firstOrNull { it.openable && favoriteWireIdentity(it.wirePath) == wire }
    }
    fun favorite(path: String) = favorite(ResourceRef(path, "", "favorite", false, "", 0))
    private fun mutate(method: String, endpoint: String, body: JSONObject? = null, notice: String, onSaved: () -> Unit = {},
        write: (suspend (SessionContext) -> Unit)? = null) {
        val context = bound ?: return
        if (!isCurrent(context) || mutable.value.changing) return
        reads?.cancel(); revision++
        mutable.value = mutable.value.copy(changing = true, loading = false, error = null, notice = null)
        changes = scope.launch {
            writes.withLock {
                var applied = false
                try {
                    if (write == null) context.api.action(method, endpoint, body) else write(context)
                    applied = true
                    val (items, groups) = read(context)
                    if (bound === context && isCurrent(context)) mutable.value = mutable.value.copy(
                        items = items, groups = groups, loaded = true, changing = false, notice = notice)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    if (bound === context && isCurrent(context)) mutable.value = mutable.value.copy(
                        changing = false, error = if (applied) "操作已保存，列表刷新失败，请刷新后查看" else error.message ?: "收藏操作失败，请重试")
                }
                // An acknowledged write must not be submitted again just because
                // its follow-up read failed. The parent retains the refresh warning.
                if (applied && bound === context && isCurrent(context)) onSaved()
            }
        }
    }
    private fun id(value: String) = android.net.Uri.encode(value)
    fun add(file: ResourceRef, group: String = "", onSaved: () -> Unit = {}) = mutate("POST", "/api/favorites", notice = "已加入收藏", onSaved = onSaved,
        write = { context ->
            val target = favoriteRecordTarget(file)
            val body = JSONObject().put("wirePath", target.wirePath).put("name", file.name).put("groupId", group)
            // The legacy decoder ignores unknown fields. A known strict UTF-8
            // source can carry path in the first request, without write retries.
            if (target.legacyCompatible) body.put("path", target.path)
            val row = try { context.api.action("POST", "/api/favorites", body) }
            catch (error: ServiceException) {
                if (error.status == 400 && !target.legacyCompatible) throw IllegalStateException("服务器拒绝了原始路径收藏，请检查路径或升级服务器：${error.message}", error)
                throw error
            }
            val saved = Favorite.from(row)
            check(saved.openable && saved.id.isNotBlank() && favoriteWireIdentity(saved.wirePath) == favoriteWireIdentity(target.wirePath)) {
                "服务器未确认原始路径收藏结果，请刷新核对"
            }
        })
    fun remove(item: Favorite, onSaved: () -> Unit = {}) = mutate("DELETE", "/api/favorites/${id(item.id)}", notice = "已取消收藏", onSaved = onSaved)
    fun removeSelected(ids: Set<String>, sourceScope: String, onSaved: () -> Unit = {}) {
        if (sourceScope != mutable.value.scope || ids.isEmpty()) return
        val targets = mutable.value.items.filter { it.id in ids }
        mutate("DELETE", "/api/favorites", notice = "已取消所选收藏", onSaved = onSaved, write = { context ->
            for (item in targets) {
                currentCoroutineContext().ensureActive(); check(bound === context && isCurrent(context))
                context.api.action("DELETE", "/api/favorites/${id(item.id)}")
                if (bound === context) mutable.value = mutable.value.copy(items = mutable.value.items.filterNot { it.id == item.id })
            }
        })
    }
    fun update(item: Favorite, name: String, group: String, onSaved: () -> Unit = {}) = mutate("PUT", "/api/favorites/${id(item.id)}",
        JSONObject().put("name", name.trim()).put("groupId", group), "收藏已更新", onSaved)
    fun saveGroup(group: FavoriteGroup?, name: String, color: String, onSaved: () -> Unit = {}) = mutate(if (group == null) "POST" else "PUT",
        "/api/favorites/groups" + (group?.let { "/${id(it.id)}" } ?: ""), JSONObject().put("name", name.trim()).put("color", color), "分组已保存", onSaved)
    fun removeGroup(group: FavoriteGroup) = mutate("DELETE", "/api/favorites/groups/${id(group.id)}", notice = "分组已删除，收藏已移到未分组")
    fun move(item: Favorite, delta: Int) {
        val items = mutable.value.items.toMutableList(); val from = items.indexOfFirst { it.id == item.id }
        val to = from + delta
        if (from < 0 || to !in items.indices) return
        items.add(to, items.removeAt(from))
        mutate("PUT", "/api/favorites/reorder", JSONObject().put("ids", JSONArray(items.map { it.id })), "收藏顺序已保存")
    }
    fun moveGroup(group: FavoriteGroup, delta: Int) {
        val groups = mutable.value.groups.toMutableList(); val from = groups.indexOfFirst { it.id == group.id }; val to = from + delta
        if (from < 0 || to !in groups.indices) return
        groups.add(to, groups.removeAt(from))
        mutate("PUT", "/api/favorites/groups/reorder", JSONObject().put("ids", JSONArray(groups.map { it.id })), "分组顺序已保存")
    }
}
