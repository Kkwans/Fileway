package io.github.kkwans.nasfilebrowser.app

import io.github.kkwans.nasfilebrowser.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

data class RecentAccessState(
    val scope: String = "", val items: List<RecentAccessEntry> = emptyList(),
    val loading: Boolean = false, val changing: Boolean = false, val loaded: Boolean = false,
    val error: String? = null, val recordWarning: String? = null,
)

/** The authenticated server list is authoritative; no local history or synthetic timestamps. */
class RecentAccessController(private val scope: CoroutineScope, private val isCurrent: (SessionContext) -> Boolean) {
    private val mutable = MutableStateFlow(RecentAccessState())
    val state = mutable.asStateFlow()
    private var bound: SessionContext? = null
    private var sessionScope: CoroutineScope? = null
    private var reads: Job? = null
    private var revision = 0L
    private var visible = false
    private var writes = Mutex()

    fun bind(context: SessionContext?) {
        if (bound === context) return
        sessionScope?.cancel(); revision++
        bound = context
        writes = Mutex()
        sessionScope = context?.let { CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job])) }
        mutable.value = RecentAccessState(scope = context?.owner.orEmpty())
        if (visible) refresh()
    }

    fun setVisible(value: Boolean) {
        if (visible == value) return
        visible = value
        if (value) refresh()
        else {
            reads?.cancel(); revision++
            mutable.value = mutable.value.copy(loading = false)
        }
    }

    fun refresh(more: Boolean = false) {
        // The existing API has a limit, with no cursor/page contract.
        if (more) return
        val context = bound ?: return
        val ownerScope = sessionScope ?: return
        if (!current(context) || mutable.value.changing) return
        reads?.cancel()
        val expected = ++revision
        mutable.value = mutable.value.copy(loading = true, error = null)
        reads = ownerScope.launch {
            try {
                val items = parseRecentAccess(context.api.array("/api/recent?limit=$RECENT_ACCESS_LIMIT"))
                if (current(context) && expected == revision)
                    mutable.value = mutable.value.copy(items = items, loaded = true, loading = false)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (current(context) && expected == revision)
                    mutable.value = mutable.value.copy(loading = false, error = recentAccessFailure(error, false))
            }
        }
    }

    fun deleteSelected(ids: Set<String>, clear: Boolean = false, onSaved: () -> Unit = {}) {
        val context = bound ?: return
        val ownerScope = sessionScope ?: return
        if (!current(context) || mutable.value.changing || !clear && ids.isEmpty()) return
        reads?.cancel(); revision++
        mutable.value = mutable.value.copy(changing = true, loading = false, error = null)
        ownerScope.launch {
            writes.withLock {
                var applied = false
                try {
                    check(current(context))
                    if (clear) context.api.action("DELETE", "/api/recent")
                    else context.api.action("POST", "/api/recent/delete", JSONObject().put("ids", org.json.JSONArray(ids.toList())))
                    applied = true
                    if (current(context)) {
                        mutable.value = mutable.value.copy(items = if (clear) emptyList() else mutable.value.items.filterNot { it.id in ids })
                        onSaved()
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    if (current(context)) mutable.value = mutable.value.copy(error = error.message ?: "最近访问删除失败，请重试")
                } finally {
                    if (current(context)) { mutable.value = mutable.value.copy(changing = false); if (applied) refresh() }
                }
            }
        }
    }

    /** Called only after directory/media success. Failure never propagates to browsing/playback. */
    fun record(file: ResourceRef) {
        if (file.downloadId.isNotEmpty()) return
        val context = bound ?: return
        val ownerScope = sessionScope ?: return
        if (!current(context)) return
        val ownerWrites = writes
        ownerScope.launch {
            ownerWrites.withLock {
                if (!current(context)) return@withLock
                try {
                    val target = recentAccessRecordTarget(file)
                    val response = try {
                        // Old servers reject a missing path before writing anything, rather than
                        // ignoring wirePath and accidentally recording the display-name sibling.
                        context.api.action("POST", "/api/recent", JSONObject().put("wirePath", target.wirePath))
                    } catch (error: ServiceException) {
                        if (error.status != 400) throw error
                        check(target.legacyCompatible) { "服务器最近访问接口不支持原始路径，请升级服务器；该次访问未同步" }
                        context.api.action("POST", "/api/recent", JSONObject().put("path", target.path))
                    }
                    val entry = parseRecentAccessEntry(response)
                    check(recentAccessWireIdentity(entry.wirePath) == recentAccessWireIdentity(target.wirePath)) {
                        "服务器返回的最近访问原始路径与本次访问不一致"
                    }
                    if (current(context)) {
                        // Invalidate pre-write list reads so a late snapshot cannot erase this acknowledged access.
                        reads?.cancel(); revision++
                        val items = (mutable.value.items.filterNot { it.id == entry.id ||
                            it.openable && recentAccessWireIdentity(it.wirePath) == recentAccessWireIdentity(entry.wirePath) } + entry)
                            .sortedWith(compareByDescending<RecentAccessEntry> { it.accessedAt }.thenByDescending { it.id })
                            .take(RECENT_ACCESS_LIMIT)
                        mutable.value = mutable.value.copy(items = items, loading = false)
                        // If the first full read was canceled, finish it while the content is visible.
                        if (visible && !mutable.value.loaded) refresh()
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    if (current(context)) mutable.value = mutable.value.copy(recordWarning = "“${file.name}”：${recentAccessFailure(error, true)}")
                }
            }
        }
    }

    private fun current(context: SessionContext) = bound === context && mutable.value.scope == context.owner && isCurrent(context)
}
