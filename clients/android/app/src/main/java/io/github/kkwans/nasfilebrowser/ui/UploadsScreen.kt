package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.upload.UploadRecord

@Composable internal fun UploadsScreen(model: ClientModel, reselectSource: (UploadRecord, Boolean) -> Unit) {
    val state by model.uploads.state.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf("全部") }
    var remove by remember { mutableStateOf<UploadRecord?>(null) }
    var cancel by remember { mutableStateOf<UploadRecord?>(null) }
    val client by model.state.collectAsStateWithLifecycle()
    val account = model.cacheAccount()
    val items = state.items.filter { it.accountKey == account && client.connected }.filter { when (filter) { "已完成" -> it.complete; "未完成" -> !it.complete; else -> true } }
    val selection = rememberFilewayTaskSelection(account to filter, items.map { it.id })
    val chosen = items.filter { it.id in selection.ids }
    var clearSelected by remember(account, filter) { mutableStateOf(false) }
    var infoId by remember(account) { mutableStateOf<String?>(null) }
    TaskCenterHeaderActions {
        if (!selection.active) IconButton({ selection.start() }, enabled = !state.busy && items.isNotEmpty()) {
            Icon(painterResource(R.drawable.ic_select_multiple), "多选上传")
        }
    }
    FilewayRefresh(false, model.uploads::refresh) {
Column(Modifier.fillMaxSize()) {
        if (selection.active) FilewayTaskSelectionBar(selection, !state.busy, {
            selection.select(if (selection.ids.size == items.size) emptyList() else items.map { it.id })
        }) {
            IconButton({ model.uploads.batch(chosen.filter { it.active }, "pause") }, enabled = !state.busy && chosen.any { it.active }) { Icon(painterResource(R.drawable.art_pause), "暂停所选运行中上传") }
            IconButton({ model.uploads.batch(chosen.filter { it.canResume && !it.active }, "resume") }, enabled = !state.busy && chosen.any { it.canResume && !it.active }) { Icon(painterResource(R.drawable.art_play), "继续所选可恢复上传") }
            IconButton({ if (chosen.size == items.size) clearSelected = true else model.uploads.batch(chosen, "remove") },
                enabled = !state.busy && chosen.isNotEmpty() && chosen.all { it.complete || it.status in setOf("canceled", "restarted") }) { Icon(painterResource(R.drawable.ic_trash), "移除所选已结束记录") }
        } else Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("全部", "未完成", "已完成").forEach { label -> FilterChip(filter == label, { filter = label }, { Text(label) }, colors = libraryChipColors()) }
        }
        state.error?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error) }
        FilewayToast(state.notice)
        LazyColumn(Modifier.weight(1f).semantics { contentDescription = "本机上传列表" }, contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (items.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(painterResource(R.drawable.ic_upload), null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(if (state.items.isEmpty()) "把本机文件带回文件库" else "这里还没有${filter}的上传", style = MaterialTheme.typography.titleMedium)
                    Text("在服务器文件页选择上传文件或文件夹，确认目标后开始。本机原文件不会被删除。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FilewayTextButton({ model.tab("files") }) { Text("选择服务器目录") }
                }
            }
            items(items, key = { it.id }) { item ->
                val transferred = maxOf(item.uploaded, state.sent[item.id] ?: 0).coerceAtMost(item.expectedSize)
                val fraction = if (item.expectedSize > 0) (transferred.toDouble() / item.expectedSize).toFloat().coerceIn(0f, 1f) else if (item.complete) 1f else 0f
                val status = when (item.status) {
                    "completed" -> "上传完成"
                    "queued" -> "等待上传"
                    "running" -> "正在上传"
                    "paused" -> "已暂停"
                    "interrupted" -> "上传中断"
                    "expired" -> "服务器片段已过期"
                    "canceling" -> "正在清理未完成片段"
                    "cancel_failed" -> "已停止，清理待重试"
                    "canceled" -> "已取消上传"
                    "restarted" -> "已重新开始，原任务已结束"
                    else -> "上传失败"
                }
                val progress = if (item.complete) readableSize(item.expectedSize) else "${(fraction * 100).toInt().coerceAtMost(99)}% · ${readableSize(transferred)} / ${readableSize(item.expectedSize)}"
                val speed = if (item.active) state.speeds[item.id]?.let { readableSize(it) + "/s" } else null

                val cardActions = buildList {
                    if (item.status == "cancel_failed") add(FilewayTaskAction("cleanup", "重试清理上传", R.drawable.ic_refresh, enabled = !state.busy, primary = true))
                    else if (!item.complete && item.status !in setOf("canceled", "canceling", "restarted")) add(FilewayTaskAction("cancel", "取消上传", R.drawable.ic_close, enabled = !state.busy))
                    if (item.canReselectSource) add(FilewayTaskAction("reselect", "重新选择上传原文件", R.drawable.ic_refresh, enabled = !state.busy))
                    if (item.canRestart) add(FilewayTaskAction("restart", "重新开始上传", R.drawable.ic_refresh, enabled = !state.busy))
                    if (item.complete) add(FilewayTaskAction("open", "定位服务器文件", R.drawable.ic_folder, enabled = !state.busy, primary = true))
                    if (item.complete || item.status in setOf("canceled", "restarted")) add(FilewayTaskAction("remove", "移除记录", R.drawable.ic_trash, enabled = !state.busy))
                }
                FilewayTaskCard(
                    title = item.name,
                    onClick = { if (selection.active) selection.toggle(item.id) else infoId = item.id },
                    onLongClick = { if (!state.busy) selection.start(item.id) }, selected = item.id in selection.ids,
                    icon = {
                        if (!item.complete && item.canResume && !selection.active) TransferPrimaryControl(item.active,
                            if (item.active) "暂停上传" else "继续上传", !state.busy) {
                            if (item.active) model.uploads.pause(item) else model.uploads.resume(item)
                        } else Icon(painterResource(fileArtworkIcon(item.name, false)), null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                    },
                    meta = {
                        TransferListMeta(item.createdAt, item.activeMillis, item.timingComplete,
                            item.status in setOf("running", "paused"), item.expectedSize)
                    },
                    actions = {
                        if (!selection.active) FilewayTaskActionStrip(cardActions, alwaysMenu = true, onAction = { action ->
                            when (action) {
                                "toggle" -> if (item.active) model.uploads.pause(item) else model.uploads.resume(item)
                                "cleanup" -> model.uploads.cancel(item)
                                "cancel" -> cancel = item
                                "reselect" -> reselectSource(item, false)
                                "restart" -> reselectSource(item, true)
                                "open" -> model.openUploadedFile(item)
                                "remove" -> remove = item
                            }
                        }, moreLabel = "上传操作：${item.name}")
                    },
                ) {
                    if (!item.complete && item.canResume) TransferProgressRow(progress, fraction, speed, false)
                    if (item.status !in setOf("running", "paused", "completed")) Text(status, style = MaterialTheme.typography.bodySmall,
                        color = if (item.status in setOf("failed", "expired")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    if (item.error.isNotEmpty()) Text(item.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

    state.items.firstOrNull { it.id == infoId }?.let { row -> FileOperationSheet("上传详情", { infoId = null }) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(row.name, style = MaterialTheme.typography.titleMedium)
            Text("创建：${serverTime(row.createdAt)}", style = MaterialTheme.typography.bodySmall)
            Text("更新：${serverTime(row.updatedAt)}", style = MaterialTheme.typography.bodySmall)
            Text(transferSummary(row.uploaded, row.activeMillis, row.timingComplete), style = MaterialTheme.typography.bodySmall)
            if (!row.timingComplete) Text("旧任务仅累计升级后的运行时间，暂停与离线时间不计入。", style = MaterialTheme.typography.bodySmall)
            FileOperationLocation("目标位置", row.targetPath, showBasename = false)
            Text("${readableSize(row.uploaded)} / ${readableSize(row.expectedSize)}", style = MaterialTheme.typography.bodyMedium)
            if (row.error.isNotEmpty()) Text(row.error, color = MaterialTheme.colorScheme.error)
        }
    } }
    if (clearSelected) FilewayAlertDialog(onDismissRequest = { clearSelected = false }, title = { Text("移除当前列表的全部已结束记录？") },
        text = { Text("所选 ${chosen.size} 条上传记录将移除，本机原文件和服务器文件保留。") },
        confirmButton = { FilewayButton({ model.uploads.batch(chosen, "remove"); clearSelected = false }, enabled = !state.busy) { Text("移除记录") } },
        dismissButton = { FilewayOutlinedButton({ clearSelected = false }) { Text("取消") } })
    state.restart?.let { draft -> FilewayAlertDialog(onDismissRequest = { model.uploads.dismissRestart() }, title = { Text("从零重新开始上传？") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("本机文件：${draft.source.name} · ${readableSize(draft.source.size)}")
            Text("目标：${draft.record.targetPath}")
            Text("确认后将清理本次未完成片段，放弃原上传进度，从零建立新任务。本机原文件和服务器已发布的完整文件保留。")
        } },
        confirmButton = { FilewayTextButton(model.uploads::restart, enabled = !state.busy) { Text(if (state.busy) "正在处理" else "确认重新开始") } },
        dismissButton = { FilewayTextButton(model.uploads::dismissRestart, enabled = !state.busy) { Text("保留原任务") } }) }
    remove?.let { item -> FilewayAlertDialog(onDismissRequest = { remove = null }, title = { Text("移除上传记录？") }, text = { Text("${item.name}\n\n本机原文件和服务器上的文件均保留。") },
        confirmButton = { FilewayTextButton({ model.uploads.remove(item); remove = null }) { Text("移除记录") } }, dismissButton = { FilewayTextButton({ remove = null }) { Text("取消") } }) }
    cancel?.let { item -> FilewayAlertDialog(onDismissRequest = { cancel = null }, title = { Text("取消这项上传？") },
        text = { Text("${item.name}\n\n将停止上传并清理原服务器上的本次未完成片段。本机原文件和服务器已经收到的完整文件保留。暂停后继续，请选择“暂停”。") },
        confirmButton = { FilewayTextButton({ cancel = null; model.uploads.cancel(item) }) { Text("取消上传") } },
        dismissButton = { FilewayTextButton({ cancel = null }) { Text("保留任务") } }) }
}
