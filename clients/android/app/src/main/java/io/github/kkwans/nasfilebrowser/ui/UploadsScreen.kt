package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.layout.*
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
    FilewayRefresh(false, model.uploads::refresh) {
Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("全部", "未完成", "已完成").forEach { label -> FilterChip(filter == label, { filter = label }, { Text(label) }, colors = libraryChipColors()) }
        }
        state.error?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error) }
        FilewayToast(state.notice)
        LazyColumn(Modifier.weight(1f).semantics { contentDescription = "本机上传列表" }, contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("本机上传", style = MaterialTheme.typography.labelLarge)
                    Text("${items.size} 项", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (items.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(painterResource(R.drawable.ic_upload), null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(if (state.items.isEmpty()) "把本机文件带回文件库" else "这里还没有${filter}的上传", style = MaterialTheme.typography.titleMedium)
                    Text("在服务器文件页选择上传文件或文件夹，确认目标后开始。本机原文件不会被删除。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton({ model.tab("files") }) { Text("选择服务器目录") }
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
                val speed = if (item.active) state.speeds[item.id]?.let { readableSize(it) + "/s" } ?: "正在获取上传速度" else null
                val summary = if (item.complete) "$status · $progress" else "$status · $progress${speed?.let { " · $it" }.orEmpty()}"
                val cardActions = buildList {
                    if (item.canResume) add(FilewayTaskAction("toggle", if (item.active) "暂停上传" else "继续上传",
                        if (item.active) R.drawable.ic_close else R.drawable.ic_refresh, enabled = !state.busy))
                    if (item.status == "cancel_failed") add(FilewayTaskAction("cleanup", "重试清理上传", R.drawable.ic_refresh, enabled = !state.busy))
                    else if (!item.complete && item.status !in setOf("canceled", "canceling", "restarted")) add(FilewayTaskAction("cancel", "取消上传", R.drawable.ic_close, enabled = !state.busy))
                    if (item.canReselectSource) add(FilewayTaskAction("reselect", "重新选择上传原文件", R.drawable.ic_refresh, enabled = !state.busy))
                    if (item.canRestart) add(FilewayTaskAction("restart", "重新开始上传", R.drawable.ic_refresh, enabled = !state.busy))
                    if (item.complete) add(FilewayTaskAction("open", "定位服务器文件", R.drawable.ic_folder, enabled = !state.busy))
                    if (item.complete || item.status in setOf("canceled", "restarted")) add(FilewayTaskAction("remove", "移除记录", R.drawable.ic_trash, enabled = !state.busy))
                }
                FilewayTaskCard(
                    title = item.name,
                    icon = { Icon(painterResource(fileArtworkIcon(item.name, false)), null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary) },
                    meta = {
                        Text(summary, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                            color = if (item.status in setOf("failed", "expired")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    actions = {
                        FilewayTaskActionStrip(cardActions, onAction = { action ->
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
                    if (!item.complete) LinearProgressIndicator(progress = { fraction }, Modifier.fillMaxWidth().height(3.dp), gapSize = 0.dp, drawStopIndicator = {})
                    if (item.error.isNotEmpty()) Text(item.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

    state.restart?.let { draft -> AlertDialog(onDismissRequest = { model.uploads.dismissRestart() }, title = { Text("从零重新开始上传？") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("本机文件：${draft.source.name} · ${readableSize(draft.source.size)}")
            Text("目标：${draft.record.targetPath}")
            Text("确认后将清理本次未完成片段，放弃原上传进度，从零建立新任务。本机原文件和服务器已发布的完整文件保留。")
        } },
        confirmButton = { TextButton(model.uploads::restart, enabled = !state.busy) { Text(if (state.busy) "正在处理" else "确认重新开始") } },
        dismissButton = { TextButton(model.uploads::dismissRestart, enabled = !state.busy) { Text("保留原任务") } }) }
    remove?.let { item -> AlertDialog(onDismissRequest = { remove = null }, title = { Text("移除上传记录？") }, text = { Text("${item.name}\n\n本机原文件和服务器上的文件均保留。") },
        confirmButton = { TextButton({ model.uploads.remove(item); remove = null }) { Text("移除记录") } }, dismissButton = { TextButton({ remove = null }) { Text("取消") } }) }
    cancel?.let { item -> AlertDialog(onDismissRequest = { cancel = null }, title = { Text("取消这项上传？") },
        text = { Text("${item.name}\n\n将停止上传并清理原服务器上的本次未完成片段。本机原文件和服务器已经收到的完整文件保留。暂停后继续，请选择“暂停”。") },
        confirmButton = { TextButton({ cancel = null; model.uploads.cancel(item) }) { Text("取消上传") } },
        dismissButton = { TextButton({ cancel = null }) { Text("保留任务") } }) }
}
