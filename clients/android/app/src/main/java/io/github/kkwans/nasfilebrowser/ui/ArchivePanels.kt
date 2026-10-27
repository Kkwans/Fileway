package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.app.ArchiveController
import io.github.kkwans.nasfilebrowser.app.ArchiveState
import io.github.kkwans.nasfilebrowser.data.*

/** Archive entries keep their relative opaque identity; they are never NAS ResourceRefs. */
@Composable internal fun ArchiveEntryDetails(entry: ArchiveEntry, state: ArchiveState, dismiss: () -> Unit,
    open: (() -> Unit)?, extract: () -> Unit) {
    FileOperationSheet("包内条目详情", dismiss, footer = {
        FilewaySheetActions(listOf(
            FilewayMenuEntry("open", if (entry.isDir) "进入文件夹" else "打开文件", if (entry.isDir) R.drawable.ic_folder else R.drawable.ic_visibility,
                enabled = open != null && !state.submitting && !state.loading),
            FilewayMenuEntry("extract", "解压", R.drawable.ic_archive, enabled = state.canExtract && !state.submitting && !state.loading &&
                state.listing?.truncated == false && !state.unknownSubmission && !state.reportLoading && state.task?.active != true),
        )) { if (it == "open") open?.invoke() else extract() }
    }) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SelectionContainer { Text(entry.name, style = MaterialTheme.typography.titleMedium) }
            FileOperationLocation("包内位置", entry.path.substringBeforeLast('/', "").ifEmpty { "压缩包根目录" }, showBasename = false)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                DetailMetadata("类型", if (entry.isDir) "文件夹" else entry.name.substringAfterLast('.', "文件").uppercase(), Modifier.weight(1f))
                val size = if (entry.isDir) state.listing?.entries.orEmpty().filter { !it.isDir && it.wirePath.startsWith(entry.wirePath + "/") }.sumOf { it.size } else entry.size
                DetailMetadata("解压大小", readableSize(size), Modifier.weight(1f))
            }
            if (entry.modified > 0) DetailMetadata("修改时间", serverTime(entry.modified))
        }
    }
}

@Composable internal fun ArchiveInformation(state: ArchiveState, dismiss: () -> Unit, openDirectory: (DirectoryCrumb) -> Unit) {
    val file = state.file ?: return
    FileOperationSheet("压缩包信息", dismiss, footer = {
        FilewaySheetActions(listOf(FilewayMenuEntry("directory", "所在目录", R.drawable.ic_folder, enabled = !state.submitting))) {
            dismiss(); openDirectory(DirectoryCrumb("所在目录", file.path.substringBeforeLast('/').ifEmpty { "/" }, file.wirePath.substringBeforeLast('/').ifEmpty { "/" }))
        }
    }) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SelectionContainer { Text(file.name, style = MaterialTheme.typography.titleMedium) }
            FileOperationLocation("位置", file.path.substringBeforeLast('/').ifEmpty { "/" }, showBasename = false)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                DetailMetadata("格式", state.listing?.format?.uppercase() ?: "—", Modifier.weight(1f))
                DetailMetadata("文件大小", readableSize(file.size), Modifier.weight(1f))
            }
            state.listing?.let { listing ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    DetailMetadata("条目", "${listing.entries.size} 项", Modifier.weight(1f))
                    DetailMetadata("解压大小", readableSize(listing.listedBytes), Modifier.weight(1f))
                }
            }
            if (!state.canExtract) Text("当前账号可浏览，但没有创建文件权限。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable internal fun ArchiveExtractSheet(controller: ArchiveController, dismiss: () -> Unit, openTasks: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    val entries = state.selectedEntries
    val ready = state.canExtract && !state.loading && !state.submitting && !state.reportLoading && !state.unknownSubmission &&
        state.listing?.truncated == false && entries.isNotEmpty() && state.task?.active != true
    FileOperationSheet("确认解压", dismiss, canDismiss = !state.submitting, footer = {
        FilewayOutlinedButton(dismiss, Modifier.weight(1f), enabled = !state.submitting) { Text("取消") }
        FilewayButton(controller::submit, Modifier.weight(1f), enabled = ready) {
            FilewayIcon(R.drawable.ic_archive, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text(if (state.submitting) "正在提交…" else "开始解压")
        }
    }) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                DetailMetadata("所选文件", "${entries.count { !it.isDir }} 项", Modifier.weight(1f))
                DetailMetadata("解压大小", readableSize(entries.filterNot { it.isDir }.sumOf { it.size }), Modifier.weight(1f))
            }
            FileOperationLocation("解压到", state.destination.path, showBasename = false)
            FilewayOutlinedButton(controller::pickDestination, Modifier.fillMaxWidth(), enabled = !state.submitting && !state.unknownSubmission) {
                FilewayIcon(R.drawable.ic_folder, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("更改目标目录")
            }
            Text("同名文件跳过，已有内容保留。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            if (state.unknownSubmission) FilewayOutlinedButton(openTasks, Modifier.fillMaxWidth()) { Text("核对任务中心") }
            if (state.submitting) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable internal fun ArchiveTaskSheet(controller: ArchiveController, dismiss: () -> Unit, openTasks: () -> Unit,
    openDirectory: (DirectoryCrumb) -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    val task = state.task ?: return
    var cancel by remember(task.id) { mutableStateOf(false) }
    var skipped by remember(task.id) { mutableStateOf(false) }
    FileOperationSheet("解压任务", dismiss, footer = {
        FilewaySheetActions(buildList {
            add(FilewayMenuEntry("tasks", "任务中心", R.drawable.ic_tasks))
            if (state.report != null) add(FilewayMenuEntry("directory", "所在目录", R.drawable.ic_folder))
            if (task.active) add(FilewayMenuEntry("cancel", "取消解压", R.drawable.ic_close, enabled = !state.canceling, destructive = true))
        }) { action -> when (action) {
            "tasks" -> { dismiss(); openTasks() }
            "directory" -> state.report?.let { dismiss(); openDirectory(it.destination) }
            "cancel" -> cancel = true
        } }
    }) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("解压任务 · ${task.statusLabel}", style = MaterialTheme.typography.titleMedium)
            if (task.active) {
                val progress = task.progress
                if (progress == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                else LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), gapSize = 0.dp, drawStopIndicator = {})
            }
            if (task.totalItems > 0) DetailMetadata("处理项目", "${task.processedItems} / ${task.totalItems}")
            if (task.error.isNotEmpty()) Text(task.error, color = MaterialTheme.colorScheme.error)
            (state.taskError ?: state.reportError)?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                FilewayOutlinedButton(controller::retryTaskStatus, Modifier.fillMaxWidth()) { Text("重试读取") }
            }
            if (state.reportLoading) Text("正在读取解压结果…", style = MaterialTheme.typography.bodyMedium)
            state.report?.let { report ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    DetailMetadata("已写入", "${report.extractedFiles} 个文件 · ${report.extractedDirs} 个目录", Modifier.weight(1f))
                    DetailMetadata("大小", readableSize(report.extractedBytes), Modifier.weight(1f))
                }
                FileOperationLocation("目标目录", report.destination.path, showBasename = false)
                if (report.skippedCount > 0) {
                    Text("${report.skippedCount} 项已存在，已跳过且未覆盖", style = MaterialTheme.typography.bodyMedium)
                    if (report.skipped.isNotEmpty()) FilewayOutlinedButton({ skipped = true }, Modifier.fillMaxWidth()) { Text("查看跳过项目") }
                }
            }
            if (task.status == "canceled") Text("已成功写入的文件仍然保留。", style = MaterialTheme.typography.bodyMedium)
        }
    }
    if (skipped) state.report?.let { ArchiveEntryReport("跳过 ${it.skippedCount} 项", it.skipped, { skipped = false }, it.skippedCount) }
    if (cancel) FilewayAlertDialog(onDismissRequest = { cancel = false }, title = { Text("取消解压？") },
        text = { Text("已完成的文件会保留，服务器会停止后续条目。最终状态可在任务中心核对。") },
        confirmButton = { FilewayTextButton({ cancel = false; controller.cancel() }) { Text("确认取消") } },
        dismissButton = { FilewayTextButton({ cancel = false }) { Text("继续解压") } })
}

@Composable internal fun ArchiveEntryReport(title: String, entries: List<ArchiveBlockedEntry>, dismiss: () -> Unit, total: Long = entries.size.toLong()) {
    FileOperationSheet(title, dismiss) {
        LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
            if (total > entries.size) item { Text("显示前 ${entries.size} 项", style = MaterialTheme.typography.bodySmall) }
            items(entries) { entry -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SelectionContainer { Text(entry.path, style = MaterialTheme.typography.bodyMedium) }
                Text(entry.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } }
        }
    }
}

@Composable internal fun ArchiveDestinationPicker(controller: ArchiveController) {
    val state by controller.state.collectAsStateWithLifecycle()
    val directory = state.destinationDraft ?: return
    FileOperationSheet("选择解压目标目录", controller::closeDestination, footer = {
        FilewayOutlinedButton(controller::closeDestination, Modifier.weight(1f)) { Text("取消") }
        FilewayButton(controller::confirmDestination, Modifier.weight(1f), enabled = !state.directoryLoading && state.directoryError == null && directory.wirePath != null) { Text("使用此目录") }
    }) {
        DirectoryBreadcrumbs(directory.path, directory.wirePath.orEmpty(), state.directoryLoading, {
            directoryTrail(directory.path, directory.wirePath.orEmpty()).dropLast(1).lastOrNull()?.let(controller::browseDestination)
        }, controller::browseDestination)
        if (state.directoryLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.directoryError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            FilewayOutlinedButton({ controller.browseDestination(directory) }) { Text("重试目录") }
        }
        LazyColumn(Modifier.weight(1f, fill = false).heightIn(min = 72.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
            item { FileOperationLocation("当前解压目标", directory.path, showBasename = false) }
            if (state.directories.isEmpty() && !state.directoryLoading && state.directoryError == null) item {
                Text("没有子目录，可使用当前目录", Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(state.directories, key = { it.wirePath }) { folder ->
                FilewayDirectoryRow(folder.name, enabled = !state.directoryLoading) {
                    controller.browseDestination(DirectoryCrumb(folder.name, folder.path, folder.wirePath))
                }
            }
        }
    }
}
