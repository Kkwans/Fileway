package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
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
    onUnassign: (() -> Unit)? = null, readMetadata: Boolean = true,
    footerActions: (@Composable RowScope.() -> Unit)? = null, onDismiss: () -> Unit) {
    var metadata by remember(file.mediaKey) { mutableStateOf(file) }
    var metadataError by remember(file.mediaKey) { mutableStateOf<String?>(null) }
    var retry by remember(file.mediaKey) { mutableIntStateOf(0) }
    var size by remember(file.mediaKey) { mutableStateOf<DirectorySize?>(null) }
    var sizeError by remember(file.mediaKey) { mutableStateOf<String?>(null) }
    var folders by remember(file.mediaKey) { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    var sizing by remember(file.mediaKey) { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    DisposableEffect(file.mediaKey) { onDispose { sizing?.cancel() } }
    LaunchedEffect(file.mediaKey, model, readMetadata, retry) {
        if (readMetadata && model != null && file.downloadId.isEmpty() && !model.isTemporaryContent(file)) {
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
            val buttons = buildList {
                if (onUnassign != null) add(FilewayMenuEntry("unassign", "取消打标", io.github.kkwans.nasfilebrowser.R.drawable.ic_tag, enabled = openEnabled))
                if (onLocation != null) add(FilewayMenuEntry("location", "所在目录", io.github.kkwans.nasfilebrowser.R.drawable.ic_folder, enabled = openEnabled, accessibilityLabel = "打开所在目录"))
                if (onOpen != null) add(FilewayMenuEntry("open", "打开", io.github.kkwans.nasfilebrowser.R.drawable.ic_visibility, enabled = openEnabled))
            }
            FileOperationSheet("文件详情", onDismiss, footer = footerActions ?: if (buttons.isEmpty()) null else {{
                FilewaySheetActions(buttons) { action -> when (action) {
                    "unassign" -> onUnassign?.invoke(); "location" -> onLocation?.invoke(); "open" -> onOpen?.invoke()
                } }
            }}) {
                Column(Modifier.weight(1f, fill = false).verticalScroll(scroll)
                    .semantics { contentDescription = "文件详情内容" }, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    field("名称", metadata.name)
                    FileOperationLocation("位置", file.path, showBasename = false)
                    actions?.invoke()
                    additionalActions?.invoke()
                    HorizontalDivider(color = FilewayTheme.colors.outlineVariant)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Column(Modifier.weight(1f)) { field("类型", fileTypeLabel(metadata)) }
                        if (showSize) Column(Modifier.weight(1f)) {
                            if (!file.directory) field("大小", readableSize(metadata.size))
                            else Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                                    .clickable(enabled = model != null, role = Role.Button) {
                                        if (sizing?.isActive == true) sizing?.cancel()
                                        else if (model != null) sizing = scope.launch {
                                            folders = 0; sizeError = null; size = null
                                            try { size = model.directorySize(file) { folders = it } }
                                            catch (failure: Exception) { if (failure is CancellationException) throw failure; sizeError = failure.message ?: "统计失败，请重试" }
                                            finally { sizing = null }
                                        }
                                    }.semantics { contentDescription = if (sizing?.isActive == true) "停止统计文件夹大小" else "统计或重新统计文件夹大小" },
                                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("大小", style = FilewayTheme.text.metadata, color = FilewayTheme.colors.onSurfaceVariant)
                                    Text(if (model == null) "未提供" else if (sizing?.isActive == true) "停止统计 · $folders 目录"
                                        else size?.let { "${readableSize(it.bytes)} · ${it.files} 个文件" } ?: "统计大小",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (model == null) FilewayTheme.colors.onSurface else FilewayTheme.colors.primary)
                                }
                                size?.takeIf { it.skippedLinks > 0 }?.let { Text("不含 ${it.skippedLinks} 个符号链接", style = FilewayTheme.text.metadata) }
                                sizeError?.let { Text(it, color = FilewayTheme.colors.error, style = FilewayTheme.text.metadata) }
                            }
                        }
                    }
                    val created = displayModified(metadata.created)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Column(Modifier.weight(1f)) { field("修改时间", displayModified(metadata.modified) ?: "未提供") }
                        if (created != null) Column(Modifier.weight(1f)) { field("创建时间", created) }
                    }
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
