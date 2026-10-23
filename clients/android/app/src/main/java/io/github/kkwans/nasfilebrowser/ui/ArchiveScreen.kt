package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.app.ArchiveController
import io.github.kkwans.nasfilebrowser.data.*
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayTopAppBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ArchiveScreen(controller: ArchiveController, onBack: () -> Unit, onOpenTasks: () -> Unit,
    onOpenDirectory: (DirectoryCrumb) -> Unit, onOpenEntry: ((ArchiveEntry) -> Unit)? = null) {
    val state by controller.state.collectAsStateWithLifecycle()
    val listing = state.listing
    var blocked by remember(state.scope, state.file?.wirePath) { mutableStateOf(false) }
    var cancel by remember(state.scope, state.task?.id) { mutableStateOf(false) }
    var skipped by remember(state.scope, state.task?.id) { mutableStateOf(false) }
    val busy = state.loading || state.submitting
    val compactHeight = LocalConfiguration.current.screenHeightDp < 600
    val compactScroll = rememberScrollState()
    Scaffold(topBar = {
        FilewayTopAppBar("压缩包浏览", state.file?.name, R.drawable.ic_arrow_back, "返回文件", { if (!state.submitting) onBack() }, actions = {
            IconButton(controller::refresh, enabled = !busy && state.task?.active != true && !state.reportLoading) {
                Icon(painterResource(R.drawable.ic_refresh), "刷新压缩包")
            }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
            .then(if (compactHeight) Modifier.verticalScroll(compactScroll) else Modifier).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(state.file?.name.orEmpty(), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(state.file?.path.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { message ->
                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                if (listing == null && !busy) FilewayTextButton(controller::refresh) { Text("重试读取") }
            }
            if (state.unknownSubmission) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilewayTextButton(onOpenTasks) { Text("去任务中心核对") }
                FilewayTextButton(controller::acknowledgeSubmission) { Text("已核对未创建") }
            }
            if (listing != null) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${listing.format.uppercase()} · ${listing.entries.size} 项 · ${readableSize(listing.listedBytes)}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (listing.blockedCount > 0) FilewayTextButton({ blocked = true }) { Text("已阻止 ${listing.blockedCount} 项") }
                }
                if (listing.truncated) Text("${listing.limitReason}。完整范围无法确认，不会提交解压。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                OutlinedTextField(state.query, controller::search, Modifier.fillMaxWidth(), label = { Text("搜索包内路径") }, singleLine = true, enabled = !state.submitting,
                    leadingIcon = { Icon(painterResource(R.drawable.ic_search), null) })
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (state.prefix.isNotEmpty()) IconButton(controller::up, enabled = !state.submitting) { Icon(painterResource(R.drawable.ic_arrow_back), "返回压缩包上一级") }
                    Text(if (state.query.isNotBlank()) "全部搜索结果" else if (state.prefix.isEmpty()) "压缩包根目录" else state.rows.firstOrNull()?.path?.substringBeforeLast('/', state.prefix) ?: state.prefix,
                        Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    FilewayTextButton(controller::selectDirectory, enabled = !state.submitting) { Text(if (state.prefix.isEmpty()) "全选" else "选择此目录") }
                    FilewayTextButton(controller::clearSelection, enabled = !state.submitting && state.selected.isNotEmpty()) { Text("清空") }
                }
                LazyColumn(Modifier.then(if (compactHeight) Modifier.heightIn(max = 260.dp) else Modifier.weight(1f)).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (state.rows.isEmpty()) item { Text(if (state.query.isBlank()) "这个目录没有可浏览的条目" else "没有匹配的包内路径", Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(state.rows, key = { it.wirePath }) { entry ->
                        val chosen = archiveSelectionContains(state.selected, entry.wirePath)
                        val inherited = chosen && entry.wirePath !in state.selected
                        Surface(shape = RoundedCornerShape(12.dp), color = if (chosen) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .5f) else MaterialTheme.colorScheme.surfaceContainerLow) {
                            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(chosen, { controller.toggle(entry) }, enabled = !state.submitting && !inherited)
                                Row(Modifier.weight(1f).clickable(enabled = !state.submitting, role = Role.Button) {
                                    if (entry.isDir) controller.enter(entry) else controller.toggle(entry)
                                }.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(painterResource(fileArtworkIcon(entry.name, entry.isDir)), null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Text(entry.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        if (state.query.isNotBlank()) Text(entry.path.substringBeforeLast('/', "").ifEmpty { "压缩包根目录" },
                                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        if (inherited || !entry.isDir) Text(if (inherited) "已随上级目录选中" else readableSize(entry.size),
                                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                if (!entry.isDir && onOpenEntry != null) IconButton({ onOpenEntry(entry) }, enabled = !busy) { Icon(painterResource(R.drawable.ic_visibility), "打开包内文件：${entry.name}") }
                                else if (entry.isDir) IconButton({ controller.enter(entry) }, enabled = !state.submitting) { Icon(painterResource(R.drawable.ic_arrow_forward), "进入 ${entry.name}") }
                            }
                        }
                    }
                }
                state.task?.let { task ->
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("解压任务 · ${task.statusLabel}", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                FilewayTextButton(onOpenTasks) { Text("任务中心") }
                                if (task.active) FilewayTextButton({ cancel = true }, enabled = !state.canceling) { Text(if (state.canceling) "取消中…" else "取消解压") }
                            }
                            val progress = task.progress
                            if (task.active) { if (progress == null) LinearProgressIndicator(Modifier.fillMaxWidth()) else LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth()) }
                            if (task.totalItems > 0) Text("${task.processedItems} / ${task.totalItems} 项", style = MaterialTheme.typography.bodySmall)
                            if (task.error.isNotEmpty()) Text(task.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            state.taskError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error); FilewayTextButton(controller::retryTaskStatus) { Text("重试任务状态") } }
                            state.reportError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error); FilewayTextButton(controller::retryTaskStatus) { Text("重试结果") } }
                            if (state.reportLoading) Text("正在读取解压结果…", style = MaterialTheme.typography.bodySmall)
                            state.report?.let { report ->
                                Text("写入 ${report.extractedFiles} 个文件、${report.extractedDirs} 个目录 · ${readableSize(report.extractedBytes)}", style = MaterialTheme.typography.bodySmall)
                                if (report.skippedCount > 0) {
                                    Text("${report.skippedCount} 项已存在，已跳过且未覆盖", style = MaterialTheme.typography.bodySmall)
                                    if (report.skipped.isNotEmpty()) FilewayTextButton({ skipped = true }) { Text("查看跳过项目") }
                                }
                                FilewayTextButton({ onOpenDirectory(report.destination) }) { Text("打开解压目标目录") }
                            }
                            if (task.status == "canceled") Text("已成功写入的文件仍然保留。", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("解压到", style = MaterialTheme.typography.labelMedium)
                                Text(state.destination.path, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            FilewayTextButton(controller::pickDestination, enabled = !busy && state.task?.active != true && !state.reportLoading) { Text("选择目录") }
                        }
                        val entries = state.selectedEntries
                        Text("${entries.count { !it.isDir }} 个文件 · ${readableSize(entries.filterNot { it.isDir }.sumOf { it.size })} · 同名文件跳过", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (!state.canExtract) Text("当前账号可浏览，但没有创建文件权限。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FilewayButton(controller::submit, Modifier.fillMaxWidth(), enabled = state.canExtract && !busy && !listing.truncated && entries.isNotEmpty() && state.task?.active != true && !state.unknownSubmission && !state.reportLoading) {
                            Text(if (state.submitting) "正在提交解压…" else "解压所选项目")
                        }
                    }
                }
            } else Spacer(Modifier.weight(1f))
            Spacer(Modifier.height(8.dp))
        }
    }
    if (state.pickingDestination) ArchiveDestinationPicker(controller)
    if (skipped) state.report?.let { report -> FilewayAlertDialog(onDismissRequest = { skipped = false }, title = { Text("跳过 ${report.skippedCount} 个条目") }, text = {
        LazyColumn(Modifier.heightIn(max = 360.dp)) {
            if (report.skippedCount > report.skipped.size) item { Text("显示前 ${report.skipped.size} 个条目", style = MaterialTheme.typography.bodySmall) }
            items(report.skipped) { entry -> Column(Modifier.padding(vertical = 8.dp)) { Text(entry.path); Text(entry.reason, style = MaterialTheme.typography.bodySmall) } }
        }
    }, confirmButton = { FilewayTextButton({ skipped = false }) { Text("关闭") } }) }
    if (blocked && listing != null) FilewayAlertDialog(onDismissRequest = { blocked = false }, title = { Text("已阻止 ${listing.blockedCount} 个条目") }, text = {
        LazyColumn(Modifier.heightIn(max = 360.dp)) { items(listing.blocked) { entry -> Column(Modifier.padding(vertical = 8.dp)) { Text(entry.path); Text(entry.reason, style = MaterialTheme.typography.bodySmall) } } }
    }, confirmButton = { FilewayTextButton({ blocked = false }) { Text("知道了") } })
    if (cancel) FilewayAlertDialog(onDismissRequest = { cancel = false }, title = { Text("取消解压？") }, text = { Text("已完成的文件会保留，服务器会停止后续条目。最终状态可在任务中心核对。") },
        confirmButton = { FilewayTextButton({ cancel = false; controller.cancel() }) { Text("确认取消") } }, dismissButton = { FilewayTextButton({ cancel = false }) { Text("继续解压") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ArchiveDestinationPicker(controller: ArchiveController) {
    val state by controller.state.collectAsStateWithLifecycle()
    val directory = state.destinationDraft ?: return
    FileOperationSheet("选择解压目标目录", controller::closeDestination, footer = {
        FilewayTextButton(controller::closeDestination) { Text("取消") }
        FilewayButton(controller::confirmDestination, enabled = !state.directoryLoading && state.directoryError == null && directory.wirePath != null) { Text("使用此目录") }
    }) {
        Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DirectoryBreadcrumbs(directory.path, directory.wirePath.orEmpty(), state.directoryLoading, {
                directoryTrail(directory.path, directory.wirePath.orEmpty()).dropLast(1).lastOrNull()?.let(controller::browseDestination)
            }, controller::browseDestination)
            if (state.directoryLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.directoryError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error); FilewayTextButton({ controller.browseDestination(directory) }) { Text("重试目录") } }
            LazyColumn(Modifier.weight(1f, fill = false).heightIn(min = 72.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item { FileOperationLocation("当前解压目标", directory.path) }
                if (state.directories.isEmpty() && !state.directoryLoading && state.directoryError == null) item { Text("没有子目录，可使用当前目录", Modifier.padding(vertical = 20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(state.directories, key = { it.wirePath }) { folder ->
                    ListItem(headlineContent = { Text(folder.name, maxLines = 2, overflow = TextOverflow.Ellipsis) }, supportingContent = { Text(folder.path, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        leadingContent = { Icon(painterResource(R.drawable.ic_folder), null) }, modifier = Modifier.fillMaxWidth().clickable(enabled = !state.directoryLoading) {
                            controller.browseDestination(DirectoryCrumb(folder.name, folder.path, folder.wirePath))
                        })
                }
            }
        }
    }
}
