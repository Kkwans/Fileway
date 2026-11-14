package io.github.kkwans.nasfilebrowser.ui.medialibrary

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayEmptyState
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewaySelectionRow

/** Modal owns its insets and focus domain. Selection changes the source only; the host decides
 * whether to resolve/play after acknowledging the selection. Transcoding is a separate action. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun PhoneMediaSourcePicker(state: PhoneMediaSourcesState,
    onAction: (PhoneMediaAction) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth().selectableGroup(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "title") {
                MediaHeading("片源与版本", actionLabel = "关闭", onAction = onDismiss)
                Text("选择有权限访问的片源开始播放。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            state.problem?.let { problem -> item(key = "problem") {
                MediaProblemCard(problem, { onAction(PhoneMediaAction.Retry("sources")) })
            } }
            if (state.loading) item(key = "loading") { MediaLoading("正在加载片源") }
            if (state.busy) item(key = "resolving") { MediaLoading("正在确认片源") }
            if (state.entries.isEmpty() && !state.loading && state.problem == null) item(key = "empty") {
                FilewayEmptyState(R.drawable.ic_video, "暂无可用片源", "片源可能尚未完成扫描，或你已失去访问权限。")
            }
            state.entries.groupBy { it.editionId }.forEach { (editionId, sources) ->
                item(key = "edition/$editionId") { MediaHeading(sources.first().edition) }
                items(sources, key = { "source/${it.id}" }) { source ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilewaySelectionRow(title = source.name,
                            subtitle = listOfNotNull(source.library, source.specifications, source.audio,
                                source.unavailableReason).filter { it.isNotBlank() }.joinToString("\n"),
                            selected = source.id == state.selectedId, enabled = source.available && !state.busy,
                            onClick = { onAction(PhoneMediaAction.SelectSource(source.id, state.revision)) })
                        if (!source.available && source.recoveryLabel != null) TextButton(
                            onClick = { onAction(PhoneMediaAction.RecoverSource(source.id)) }, enabled = !state.busy,
                            modifier = Modifier.heightIn(min = 48.dp)) { Text(source.recoveryLabel) }
                    }
                }
            }
            if (state.hasMore && !state.loading) item(key = "more") {
                TextButton(onClick = { onAction(PhoneMediaAction.LoadMore("sources")) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("加载更多") }
            }
        }
    }
}
