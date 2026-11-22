package io.github.kkwans.nasfilebrowser.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

data class ContinueWatchingState(
    val items: List<MediaCatalogContinue> = emptyList(),
    val loading: Boolean = false,
    val hasMore: Boolean = false,
    val removing: String? = null,
    val error: String? = null,
)

/** Shared row state for one live catalog repository. */
class ContinueWatching(private val repository: MediaLibraryRepository, private val scope: CoroutineScope) {
    private val mutable = MutableStateFlow(ContinueWatchingState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var cursor: MediaCatalogCursor? = null
    private var supported = false
    private var retryReset = true

    fun seed(home: MediaCatalogHome) {
        if (mutable.value.removing != null) return
        job?.cancel()
        supported = home.capabilities["watchState"] == true
        val row = home.rows.firstOrNull { it.id == MediaCatalogRowId.CONTINUE }
        cursor = row?.nextCursor
        retryReset = true
        mutable.value = ContinueWatchingState(
            items = row?.continuing.orEmpty(),
            hasMore = cursor != null,
            error = row?.problem(),
        )
    }

    fun load(reset: Boolean = true) {
        if (!supported || mutable.value.removing != null || !reset && (mutable.value.loading || cursor == null)) return
        job?.cancel()
        job = scope.launch { read(reset) }
    }

    /** A failed page keeps its original continuation unless the server invalidated it. */
    fun retry() = load(reset = retryReset)

    private suspend fun read(reset: Boolean) {
        mutable.value = mutable.value.copy(loading = true, error = null)
        val result = repository.homeRow(MediaCatalogRowId.CONTINUE, if (reset) null else cursor)
        coroutineContext.ensureActive()
        when (result) {
            is MediaReadResult.Success -> {
                val row = result.value
                cursor = row.nextCursor
                retryReset = true
                val items = if (reset) row.continuing else (mutable.value.items + row.continuing).distinctBy { it.id }
                mutable.value = ContinueWatchingState(items, hasMore = cursor != null, error = row.problem())
            }
            is MediaReadResult.Failure -> {
                val inaccessible = result.error.kind in setOf(
                    MediaFailureKind.AUTHENTICATION, MediaFailureKind.FORBIDDEN, MediaFailureKind.NOT_FOUND,
                )
                val expired = result.error.kind in setOf(MediaFailureKind.CURSOR_STALE, MediaFailureKind.CURSOR_EXPIRED)
                if (inaccessible || expired) cursor = null
                retryReset = reset || inaccessible || expired
                val retained = if (inaccessible) emptyList() else mutable.value.items
                val message = if (expired) "观看列表已更新，请刷新后继续。" else "暂时无法读取继续观看，请重试。"
                mutable.value = ContinueWatchingState(retained, hasMore = cursor != null, error = message)
            }
        }
    }

    fun remove(entry: MediaCatalogContinue) {
        if (mutable.value.removing != null || mutable.value.items.none { it === entry }) return
        job?.cancel()
        retryReset = true
        mutable.value = mutable.value.copy(loading = false, removing = entry.id, error = null)
        job = scope.launch {
            val result = repository.removeContinue(entry)
            coroutineContext.ensureActive()
            val mayHaveChanged = result is MediaReadResult.Success ||
                result is MediaReadResult.Failure && result.error.kind in setOf(
                    MediaFailureKind.CONFLICT, MediaFailureKind.NOT_FOUND, MediaFailureKind.FORBIDDEN,
                )
            if (mayHaveChanged) {
                cursor = null
                mutable.value = ContinueWatchingState(mutable.value.items.filterNot { it.id == entry.id })
                read(reset = true)
            } else {
                mutable.value = mutable.value.copy(removing = null, error = "未能确认移出结果，可刷新列表或再次移出。")
            }
        }
    }

    fun close() { job?.cancel() }

    fun clear() {
        job?.cancel()
        cursor = null
        supported = false
        retryReset = true
        mutable.value = ContinueWatchingState()
    }

    private fun MediaCatalogHomeRow.problem(): String? = when (state) {
        MediaCatalogPageState.DEFERRED, MediaCatalogPageState.PREPARING, MediaCatalogPageState.ERROR -> "继续观看暂未读取完成。"
        else -> null
    }
}

fun MediaCatalogContinue.progressLabel(): String {
    val remaining = durationMs?.minus(positionMs)
    if (remaining != null && remaining < 60_000) return "剩余不到 1 分钟"
    val seconds = (remaining ?: positionMs) / 1_000
    val elapsed = when {
        seconds >= 3600 -> "${seconds / 3600} 小时 ${seconds / 60 % 60} 分钟"
        seconds >= 60 -> "${seconds / 60} 分钟"
        else -> "$seconds 秒"
    }
    return if (remaining == null) "已播放 $elapsed" else "剩余 $elapsed"
}
