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
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewaySpace

internal data class PhoneMediaSourceSelection(val itemId: String, val selectedId: String? = null) {
    fun select(sourceId: String, available: Boolean): PhoneMediaSourceSelection =
        copy(selectedId = sourceId.takeIf { available })

    fun confirm(sourceId: String, expectedItemId: String, expectedRevision: String, revision: String): String? =
        selectedId?.takeIf { it == sourceId && itemId == expectedItemId && expectedRevision == revision }
}

/** Modal owns its insets and focus domain. Selection changes the source only; the host decides
 * whether to resolve/play after acknowledging the selection. Transcoding is a separate action. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun PhoneMediaSourcePicker(state: PhoneMediaSourcesState,
    onAction: (PhoneMediaAction) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        MediaHeading("片源与版本", modifier = Modifier.padding(horizontal = FilewaySpace.page),
            actionLabel = "关闭", onAction = onDismiss)
        Text("选择片源后不会自动播放", style = PhoneMediaType.secondary,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = FilewaySpace.page, vertical = FilewaySpace.small))
        if (state.title.isNotBlank()) Text(state.title, style = PhoneMediaType.workTitle,
            modifier = Modifier.padding(horizontal = FilewaySpace.page, vertical = FilewaySpace.small))
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).selectableGroup(),
            contentPadding = PaddingValues(start = FilewaySpace.page, top = FilewaySpace.small,
                end = FilewaySpace.page, bottom = FilewaySpace.section),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                        FilewaySelectionRow(title = source.specifications.ifBlank { source.library },
                            subtitle = listOfNotNull(source.library.takeIf { source.specifications.isNotBlank() && it.isNotBlank() },
                                source.name, source.audio, "当前播放".takeIf { source.current }, source.unavailableReason).filter { it.isNotBlank() }.joinToString("\n"),
                            selected = source.id == state.selectedId, enabled = source.available && !state.busy,
                            onClick = { onAction(PhoneMediaAction.SelectSource(source.id, state.revision)) },
                            minHeight = 88.dp, titleStyle = PhoneMediaType.itemTitle)
                        if (!source.available && source.recoveryLabel != null) TextButton(
                            onClick = { onAction(PhoneMediaAction.RecoverSource(source.id)) }, enabled = !state.busy,
                            modifier = Modifier.heightIn(min = 48.dp)) { Text(source.recoveryLabel, style = PhoneMediaType.action) }
                    }
                }
            }
            if (state.hasMore && !state.loading) item(key = "more") {
                TextButton(onClick = { onAction(PhoneMediaAction.LoadMore("sources")) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("加载更多", style = PhoneMediaType.action) }
            }
        }
        val selectedAvailable = state.entries.any { it.id == state.selectedId && it.available }
        Button(onClick = { state.selectedId?.let { onAction(PhoneMediaAction.ConfirmSource(it, state.revision)) } },
            enabled = selectedAvailable && !state.busy && !state.loading,
            modifier = Modifier.fillMaxWidth().padding(horizontal = FilewaySpace.page, vertical = FilewaySpace.small)
                .heightIn(min = 56.dp), shape = MaterialTheme.shapes.medium) {
            Text("使用所选片源", style = PhoneMediaType.action)
        }
        Text("不同剪辑分别保留续播位置", style = PhoneMediaType.secondary,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = FilewaySpace.page, vertical = FilewaySpace.small))
    }
}
