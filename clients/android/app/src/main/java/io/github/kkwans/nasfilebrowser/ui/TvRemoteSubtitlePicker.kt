package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.kkwans.fileway.remote.RemoteSnapshot
import io.github.kkwans.nasfilebrowser.app.TvControlModel
import io.github.kkwans.nasfilebrowser.app.TvSubtitleSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@Composable internal fun TvRemoteSubtitlePicker(
    model: TvControlModel,
    snapshot: RemoteSnapshot,
    source: TvSubtitleSource,
    title: String,
    onReturn: () -> Unit,
) {
    val browser = remember(model, snapshot.connectionId, snapshot.sessionEpoch, snapshot.mediaEpoch, source) {
        TvSubtitleBrowser(source) { operation, payload -> model.subtitleBrowserCommand(snapshot, source, operation, payload) }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state by browser.state.collectAsState()
    val returnToTracks by rememberUpdatedState(onReturn)
    var actions by remember(browser) { mutableStateOf<TvSubtitlePickerActions?>(null) }
    LaunchedEffect(browser, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                coroutineScope {
                    val active = TvSubtitlePickerActions(this, browser)
                    actions = active
                    active.run { open() }
                    awaitCancellation()
                }
            } finally {
                actions = null
                withContext(NonCancellable) { withTimeoutOrNull(2_500L) { browser.close() } }
            }
        }
    }

    val parentId = state.trail.getOrNull(state.trail.lastIndex - 1)?.id
    val back: () -> Unit = {
        val active = actions
        if (parentId == null || active == null) returnToTracks()
        else active.run(replace = true) { navigate(parentId) }
    }
    FileOperationSheet(title, back, back = true, stableHeight = true,
        backDescription = if (parentId == null) "返回字幕轨道" else "上一级") {
        val enabled = actions != null && !state.reading && !state.preparing
        Box(Modifier.heightIn(min = 48.dp)) {
            DirectoryBreadcrumbs(state.trail.map { DirectoryBreadcrumbItem(it.id, it.name) },
                busy = !enabled, wrapCurrent = true) { id -> actions?.run { navigate(id) } }
        }
        if (state.preparing) SubtitlePickerNotice("正在电视上准备字幕", loading = true,
            actionLabel = "返回字幕轨道", action = returnToTracks)
        state.selectionError?.let { problem ->
            val canRefresh = problem.refreshDirectory || (source == TvSubtitleSource.LOCAL && problem == TvSubtitleProblem.ACCESS_DENIED)
            val refreshDirectory: () -> Unit = { actions?.run { refresh() } }
            SubtitlePickerNotice(problem.messageFor(source), error = true,
                actionLabel = when {
                    problem == TvSubtitleProblem.RESULT_UNKNOWN -> "查看 TV 当前轨道"
                    canRefresh -> "刷新目录"
                    else -> null
                },
                action = when {
                    problem == TvSubtitleProblem.RESULT_UNKNOWN -> returnToTracks
                    canRefresh -> refreshDirectory
                    else -> null
                })
        }
        // Reserve the list viewport from the first loading frame so the native
        // bottom-attached window stays in place as directory results arrive.
        SubtitlePickerList(Modifier.weight(1f, fill = false).height(288.dp)) {
            items(state.rows, key = { it.id }) { file ->
                SubtitlePickerRow(file.name, file.directory,
                    enabled && (file.directory || state.selectionError != TvSubtitleProblem.RESULT_UNKNOWN), {
                        actions?.run {
                            if (file.directory) navigate(file.id)
                            else if (select(file.id)) returnToTracks()
                        }
                    })
            }
            item {
                val problem = state.pageError
                when {
                    problem != null -> SubtitlePickerNotice(problem.messageFor(source), error = true,
                        actionLabel = when {
                            problem == TvSubtitleProblem.STORAGE_REMOVED -> "返回本机根目录"
                            problem == TvSubtitleProblem.LOCAL_SCAN_LIMIT -> null
                            problem.resetBrowser -> "重新打开"
                            else -> "重试"
                        },
                        action = { actions?.run { retry() } })
                    state.reading -> SubtitlePickerNotice(
                        if (state.rows.isEmpty()) "正在读取目录" else "正在读取更多字幕文件", loading = true)
                    state.nextOffset != null || state.nextPageToken != null -> FilewayTextButton(
                        onClick = { actions?.run { loadMore() } }, enabled = enabled, modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (state.rows.isEmpty()) "继续查找字幕" else "加载更多") }
                    actions != null && state.rows.isEmpty() && source == TvSubtitleSource.LOCAL && state.trail.size == 1 ->
                        SubtitlePickerNotice(TvSubtitleProblem.STORAGE_AUTH_REQUIRED.messageFor(source),
                            actionLabel = "重新打开", action = { actions?.run { retry() } })
                    actions != null && state.rows.isEmpty() -> SubtitlePickerNotice("这个目录没有支持的字幕文件")
                }
            }
        }
    }
}

/** A replacement back action waits for the old poll to be cancelled before sending a new request. */
private class TvSubtitlePickerActions(private val scope: CoroutineScope, private val browser: TvSubtitleBrowser) {
    private var task: Job? = null

    fun run(replace: Boolean = false, action: suspend TvSubtitleBrowser.() -> Unit) {
        val previous = task
        if (!replace && previous?.isActive == true) return
        if (replace) previous?.cancel()
        task = scope.launch {
            previous?.cancelAndJoin()
            browser.action()
        }
    }
}
