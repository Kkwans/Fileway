package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.kkwans.nasfilebrowser.app.ResourceRef
import io.github.kkwans.nasfilebrowser.app.mediaKey
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayTheme

@Composable internal fun FileDetailsDialog(file: ResourceRef, showSize: Boolean = true, onOpen: (() -> Unit)? = null,
    openEnabled: Boolean = true, onLocation: (() -> Unit)? = null, actions: (@Composable () -> Unit)? = null,
    additionalActions: (@Composable () -> Unit)? = null, onDismiss: () -> Unit) {
    val foreground = remember(file.mediaKey) { FileOperationForeground() }
    // Dialog has its own composition. Moving movableContent between that owner
    // and the screen corrupts Compose's slot groups when opening child actions.
    DisposableEffect(foreground) { onDispose { foreground.close() } }
    val scroll = rememberScrollState()
    @Composable fun field(label: String, value: String) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = FilewayTheme.text.metadata, color = FilewayTheme.colors.onSurfaceVariant)
            SelectionContainer { Text(value, style = MaterialTheme.typography.bodyMedium) }
        }
    }
    CompositionLocalProvider(LocalFileOperationForeground provides foreground) {
        if (foreground.destination == FileOperationDestination.DETAILS) {
            FileOperationSheet("文件详情", onDismiss, footer = {
                if (onLocation != null) FilewayTextButton(onLocation, enabled = openEnabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("打开所在目录") }
                if (onOpen == null) FilewayTextButton(onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("关闭") }
                else {
                    FilewayTextButton(onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("关闭") }
                    FilewayButton(onOpen, enabled = openEnabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("打开") }
                }
            }) {
                Column(Modifier.weight(1f, fill = false).verticalScroll(scroll)
                    .semantics { contentDescription = "文件详情内容" }, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    field("名称", file.name)
                    FileOperationLocation("位置", file.path)
                    actions?.invoke()
                    additionalActions?.invoke()
                    HorizontalDivider(color = FilewayTheme.colors.outlineVariant)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Column(Modifier.weight(1f)) { field("类型", fileTypeLabel(file)) }
                        if (showSize && !file.directory) Column(Modifier.weight(1f)) { field("大小", readableSize(file.size)) }
                    }
                    field("修改时间", displayModified(file.modified) ?: "未提供")
                }
            }
        } else {
            // No old Modal window, touch target or accessibility tree remains behind the child.
            // Keep action-owned child dialogs composed, but never let their
            // underlying buttons paint outside this zero-sized host.
            Box(Modifier.size(0.dp).clipToBounds().clearAndSetSemantics {}) { actions?.invoke() }
        }
    }
}
