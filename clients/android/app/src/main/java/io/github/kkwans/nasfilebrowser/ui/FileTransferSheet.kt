package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.clickable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.app.mediaKey
import io.github.kkwans.nasfilebrowser.data.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable internal fun FileTransferSheet(model: ClientModel) {
    val state by model.fileOperations.state.collectAsStateWithLifecycle()
    val client by model.state.collectAsStateWithLifecycle()
    val draft = state.transfer?.takeIf { it.visible } ?: return
    val directoryScroll = rememberLazyListState()
    val busy = state.changing || draft.loading
    val planned = remember(draft.files, draft.directory, draft.action) {
        runCatching { fileTransferEntries(draft.files, draft.directory, draft.action, allowOpaque = true) }
    }
    val entryError = planned.exceptionOrNull()?.message
    var sources by remember(state.scope, draft.files) { mutableStateOf(false) }
    var replaceConfirmed by remember(draft.directory, draft.choices) { mutableStateOf(false) }
    val selected = (planned.getOrNull()?.map { it.file } ?: draft.files).count { draft.choices[it.mediaKey] != FileConflictChoice.SKIP }
    val replaces = draft.choices.values.any { it == FileConflictChoice.REPLACE }
    if (!sources) FileOperationSheet("${draft.action.label}到服务器目录", model.fileOperations::closeTransfer,
        canDismiss = !state.changing, footer = {
            TextButton(model.fileOperations::closeTransfer, enabled = !state.changing, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (draft.unknownSubmission) "稍后核对" else "取消") }
            key(draft.reviewed, state.changing) { Button({ model.fileOperations.submitTransfer(replaceConfirmed) }, enabled = !busy && !draft.unknownSubmission && entryError == null &&
                selected > 0 && (!replaces || replaceConfirmed), modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (state.changing) "正在处理" else if (draft.reviewed) "${draft.action.label} $selected 项" else "检查此目录")
            } }
        }) {
            Text(client.serverLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${draft.files.size} 项 · ${if (draft.reviewed) "${draft.conflicts.size} 项同名" else "选择目标目录"}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                TextButton({ sources = true }) { Text("查看项目") }
            }
            DirectoryBreadcrumbs(draft.directory.path, draft.directory.wirePath.orEmpty(), busy || draft.unknownSubmission, {
                if (draft.directory.path != "/") directoryTrail(draft.directory.path, draft.directory.wirePath.orEmpty()).dropLast(1).lastOrNull()?.let(model.fileOperations::readTransferDirectory)
            }, model.fileOperations::readTransferDirectory)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            (draft.error ?: entryError)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            if (draft.error != null && !draft.reviewed && !draft.unknownSubmission && !busy) TextButton({ model.fileOperations.readTransferDirectory(draft.directory) }) { Text("重新读取目录") }
            LazyColumn(Modifier.weight(1f, fill = false).heightIn(min = 72.dp).semantics { contentDescription = "复制移动目标目录" }, state = directoryScroll, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FileOperationLocation("目标目录", draft.directory.path) }
                if (draft.unknownSubmission) item {
                    Text("核对文件任务后，可返回这里重新检查。关闭此面板不会取消服务器可能已经创建的任务。", style = MaterialTheme.typography.bodyMedium)
                    TextButton({ model.fileOperations.showTransfer(false); model.showFileTask() }) { Text("查看文件任务") }
                    TextButton(model.fileOperations::acknowledgeUnknownSubmission) { Text("已核对未创建，重新检查") }
                }
                else if (draft.reviewed) {
                    if (draft.conflicts.isEmpty()) item { Text("目标名称没有冲突，可以确认提交。", Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodyMedium) }
                    items(draft.files.filter { it.mediaKey in draft.conflicts }, key = { it.mediaKey }) { file ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(file.name, style = MaterialTheme.typography.bodyMedium)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FileConflictChoice.entries.forEach { choice ->
                                    val target = planned.getOrNull()?.firstOrNull { it.file.mediaKey == file.mediaKey }?.targetWire
                                    val protected = target != null && draft.files.any { source ->
                                        runCatching { resourceWireContains(target, taskResourceTarget(source.path, source.wirePath, allowOpaque = true).wirePath) }.getOrDefault(true)
                                    }
                                    val enabled = !busy && (choice != FileConflictChoice.REPLACE || client.permissions.modify && !protected)
                                    FilterChip(draft.choices[file.mediaKey] == choice, { model.fileOperations.chooseConflict(file.mediaKey, choice) },
                                        label = { Text(choice.label) }, enabled = enabled,
                                        modifier = Modifier.semantics { contentDescription = "${file.name}：${choice.label}" })
                                }
                            }
                        }
                    }
                } else {
                    if (draft.directories.isEmpty() && !draft.loading && draft.error == null) item { Text("没有子文件夹，可选择当前目录。", Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(draft.directories, key = { it.mediaKey }) { folder ->
                        val excluded = draft.files.any { source -> source.directory && runCatching {
                            resourceWireContains(taskResourceTarget(source.path, source.wirePath, allowOpaque = true).wirePath,
                                taskResourceTarget(folder.path, folder.wirePath, allowOpaque = true).wirePath)
                        }.getOrDefault(true) }
                        ListItem(headlineContent = { Text(folder.name) }, leadingContent = { Icon(painterResource(R.drawable.ic_folder), null) },
                            supportingContent = if (excluded) ({ Text("源目录内，不能作为目标") }) else null,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = !busy && !excluded) {
                                model.fileOperations.readTransferDirectory(DirectoryCrumb(folder.name, folder.path, folder.wirePath))
                            }.semantics { contentDescription = "目标目录：${folder.name}" }, colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface))
                    }
                }
            }
            if (replaces && draft.reviewed) {
                Text("将替换同名目标的已有内容，不会合并文件夹。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(replaceConfirmed, enabled = !busy, role = Role.Checkbox) { replaceConfirmed = it }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(replaceConfirmed, null); Text("确认替换同名目标内容", style = MaterialTheme.typography.bodyMedium)
                }
            }
    }
    if (sources) FileOperationSheet("${draft.action.label} ${draft.files.size} 项", { sources = false }, footer = {
        TextButton({ sources = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("返回目标选择") }
    }) {
        LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(draft.files, key = { it.mediaKey }) { file -> Column {
                Text(file.name, style = MaterialTheme.typography.bodyMedium)
                Text(file.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } }
        }
    }
}

@Composable internal fun TransferTaskBanner(model: ClientModel) {
    val state by model.fileOperations.state.collectAsStateWithLifecycle()
    val draft = state.transfer
    if (draft != null && !draft.visible) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("文件操作等待核对", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        TextButton({ model.fileOperations.showTransfer(true) }) { Text("继续确认") }
        TextButton(model.fileOperations::closeTransfer) { Text("关闭确认") }
    }
    val task = state.lastTask ?: return
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${task.title} · ${task.statusLabel}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                color = if (task.status in setOf("failed", "interrupted")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton({ model.showFileTask(task.id) }) { Text("查看任务") }
            TextButton(model.fileOperations::dismissTaskNotice) { Text("收起提示") }
        }
        if (task.active) TaskProgress(task)
        (state.taskError ?: task.error.takeIf { it.isNotEmpty() })?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        if (!task.active) TextButton(model::openTransferDestination) { Text("打开目标目录") }
    }
}
