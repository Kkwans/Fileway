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
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.data.DirectorySize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import io.github.kkwans.nasfilebrowser.app.ResourceRef
import io.github.kkwans.nasfilebrowser.app.mediaKey
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayTheme
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayStatusBanner
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayStatusKind

@Composable internal fun FileDetailsDialog(file: ResourceRef, showSize: Boolean = true, onOpen: (() -> Unit)? = null,
    openEnabled: Boolean = true, onLocation: (() -> Unit)? = null, actions: (@Composable () -> Unit)? = null,
    additionalActions: (@Composable () -> Unit)? = null, model: ClientModel? = null,
    onUnassign: (() -> Unit)? = null, onDismiss: () -> Unit) {
    var metadata by remember(file.mediaKey) { mutableStateOf(file) }
    var metadataError by remember(file.mediaKey) { mutableStateOf<String?>(null) }
    var retry by remember(file.mediaKey) { mutableIntStateOf(0) }
    var size by remember(file.mediaKey) { mutableStateOf<DirectorySize?>(null) }
    var sizeError by remember(file.mediaKey) { mutableStateOf<String?>(null) }
    var folders by remember(file.mediaKey) { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    var sizing by remember(file.mediaKey) { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    DisposableEffect(file.mediaKey) { onDispose { sizing?.cancel() } }
    LaunchedEffect(file.mediaKey, model, retry) {
        if (model != null && file.downloadId.isEmpty() && !model.isTemporaryContent(file)) {
            try { metadata = model.resourceMetadata(file); metadataError = null }
            catch (failure: Exception) { if (failure is CancellationException) throw failure; metadataError = failure.message ?: "元数据暂时无法读取" }
        }
    }
    val foreground = remember(file.mediaKey) { FileOperationForeground() }
    // Dialog has its own composition. Moving movableContent between that owner
    // and the screen corrupts Compose's slot groups when opening child actions.
    DisposableEffect(foreground) { onDispose { foreground.close() } }
    val scroll = rememberScrollState()
    @Composable fun field(label: String, value: String) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = FilewayTheme.text.metadata, color = FilewayTheme.colors.onSurfaceVariant)
            SelectionContainer { Text(value, style = MaterialTheme.typography.bodyMedium) }
        }
    }
    CompositionLocalProvider(LocalFileOperationForeground provides foreground) {
        if (foreground.destination == FileOperationDestination.DETAILS) {
            FileOperationSheet("文件详情", onDismiss, footer = if (onLocation == null && onOpen == null && onUnassign == null) null else {{
                if (onUnassign != null) FilewayOutlinedButton(onUnassign, enabled = openEnabled, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("取消打标") }
                if (onLocation != null) FilewayOutlinedButton(onLocation, enabled = openEnabled, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("打开所在目录") }
                if (onOpen != null) {
                    FilewayButton(onOpen, enabled = openEnabled, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("打开") }
                }
            }}) {
                Column(Modifier.weight(1f, fill = false).verticalScroll(scroll)
                    .semantics { contentDescription = "文件详情内容" }, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    field("名称", metadata.name)
                    FileOperationLocation("位置", file.path, showBasename = false)
                    actions?.invoke()
                    additionalActions?.invoke()
                    HorizontalDivider(color = FilewayTheme.colors.outlineVariant)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Column(Modifier.weight(1f)) { field("类型", fileTypeLabel(file)) }
                        if (showSize && !file.directory) Column(Modifier.weight(1f)) { field("大小", readableSize(metadata.size)) }
                    }
                    if (showSize && file.directory && model != null) {
                        Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("文件夹大小", style = FilewayTheme.text.metadata, color = FilewayTheme.colors.onSurfaceVariant)
                            size?.let { result ->
                                Text("${readableSize(result.bytes)} · ${result.files} 个文件", style = MaterialTheme.typography.bodyMedium)
                                if (result.skippedLinks > 0) Text("不含 ${result.skippedLinks} 个符号链接", style = FilewayTheme.text.metadata)
                            }
                            if (sizing?.isActive == true) FilewayOutlinedButton({ sizing?.cancel() }) { Text("停止统计 · 已读取 $folders 个目录") }
                            else FilewayOutlinedButton({
                                sizing = scope.launch {
                                    folders = 0; sizeError = null; size = null
                                    try { size = model.directorySize(file) { folders = it } }
                                    catch (failure: Exception) { if (failure is CancellationException) throw failure; sizeError = failure.message ?: "统计失败，请重试" }
                                    finally { sizing = null }
                                }
                            }) { Text(if (size == null) "统计大小" else "重新统计") }
                            sizeError?.let { Text(it, color = FilewayTheme.colors.error, style = FilewayTheme.text.metadata) }
                        }
                    }
                    field("修改时间", displayModified(metadata.modified) ?: "未提供")
                    displayModified(metadata.created)?.let { field("创建时间", it) }
                    metadataError?.let { FilewayStatusBanner(it, FilewayStatusKind.ERROR, { retry++ }, "重新读取元数据") }
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
