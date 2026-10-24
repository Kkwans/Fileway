package io.github.kkwans.nasfilebrowser.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.app.*

@Composable internal fun TrashScreen(model: ClientModel, client: ClientState) {
    val state by model.trash.state.collectAsStateWithLifecycle()
    var selecting by remember(state.scope) { mutableStateOf(false) }
    var selected by remember(state.scope) { mutableStateOf<Set<String>>(emptySet()) }
    BackHandler(selecting && LocalFilewayPageActive.current) { selecting = false; selected = emptySet() }
    var status by rememberSaveable(state.scope) { mutableStateOf("all") }
    var order by rememberSaveable(state.scope) { mutableStateOf("deleted") }
    var clearing by remember(state.scope) { mutableStateOf(false) }
    var deleting by remember(state.scope) { mutableStateOf<List<String>?>(null) }
    var details by remember(state.scope) { mutableStateOf<TrashItem?>(null) }
    val enabled = !state.loading && !state.changing && !client.busy
    val canDelete = state.permissions.delete
    val canRestore = state.permissions.admin || state.permissions.create
    LaunchedEffect(state.scope) { model.trash.refresh() }
    LaunchedEffect(state.items) { selected = selected.intersect(state.items.map { it.id }.toSet()) }
    val filtered = state.items.filter { when (status) { "available" -> it.status == "available"; "working" -> it.status in setOf("pending", "restoring") || it.sizeState == "calculating"; "failed" -> it.status == "failed" || it.sizeState == "failed"; else -> true } }
    val displayed = when (order) { "name" -> filtered.sortedBy { it.name.lowercase(java.util.Locale.ROOT) }; "size" -> filtered.sortedByDescending { it.size }; else -> filtered.sortedByDescending { it.deletedAt } }
    LibraryScaffold(model, LibrarySection.TRASH, actions = {
        if (canDelete || canRestore) IconButton({ selecting = !selecting; selected = emptySet() }, enabled = enabled) {
            Icon(painterResource(if (selecting) R.drawable.ic_close else R.drawable.ic_select_multiple), if (selecting) "退出回收站多选" else "多选回收项目")
        }
        if (!selecting) IconButton({ clearing = true }, enabled = enabled && canDelete && state.items.isNotEmpty()) {
            Icon(painterResource(R.drawable.ic_trash), "清空回收站", tint = MaterialTheme.colorScheme.error)
        }
    }) {
        LibraryMessage(state.error, state.notice, state.loading || state.changing) { model.trash.refresh() }
        state.lastTask?.let { task ->
            Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = .08f)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${task.title} · ${task.statusLabel}", style = MaterialTheme.typography.bodyMedium)
                    Text(when {
                        task.pendingDeletion -> "服务端撤销窗口内可取消删除；任务开始后内容将无法恢复。"
                        task.active -> "正在永久删除。停止请求可能无法阻止已经处理的项目。"
                        task.status == "canceled" -> "任务已取消，请刷新核对仍在回收站中的项目。"
                        task.status in setOf("failed", "interrupted") -> "删除未完整结束，请打开任务核对已处理项目。"
                        else -> "请在任务详情核对逐项结果。"
                    }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row {
                        if (task.active && task.type in setOf("trash.clear", "trash.delete.permanent")) FilewayTextButton({ model.trash.undo() }, enabled = enabled) { Text(if (task.pendingDeletion) "撤销删除" else "停止删除任务") }
                        FilewayTextButton({ model.showServerTask(task.id) }) { Text("查看任务") }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = .10f), selectedLabelColor = MaterialTheme.colorScheme.primary)
            listOf("all" to "全部", "available" to "可恢复", "working" to "处理中", "failed" to "异常").forEach { (id, label) -> FilterChip(status == id, { status = id }, { Text(label) }, colors = colors) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${displayed.size} / ${state.items.size} 项", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            var orders by remember { mutableStateOf(false) }
            Box {
                FilewayTextButton({ orders = true }) { Text(when (order) { "name" -> "按名称"; "size" -> "按大小"; else -> "最新删除" }) }
                FilewayMenu(orders, { orders = false }, items = listOf("deleted" to "最新删除", "name" to "按名称", "size" to "按大小").map { (id, label) ->
                    FilewayMenuEntry(id, label, selected = order == id)
                }, selection = FilewayMenuSelection.Single) { item -> order = item.key; orders = false }
            }
            if (selecting) {
                val eligible = displayed.filter { it.status != "restoring" }.map { it.id }.toSet()
                IconButton({ selected = if (selected.containsAll(eligible)) selected - eligible else selected + eligible }, enabled = enabled) {
                    Icon(painterResource(R.drawable.ic_select_multiple), "全选或取消全选")
                }
                if (canRestore) IconButton({ model.trash.restoreAll(state.items.filter { it.id in selected }) },
                    enabled = enabled && selected.isNotEmpty() && state.items.filter { it.id in selected }.all { it.status == "available" }) {
                    Icon(painterResource(R.drawable.ic_restore), "还原所选项目")
                }
                if (canDelete) IconButton({ deleting = selected.toList() }, enabled = enabled && selected.isNotEmpty()) {
                    Icon(painterResource(R.drawable.ic_trash), "永久删除所选项目", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
        if (state.loaded && displayed.isEmpty() && !state.loading && state.error == null) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (state.items.isEmpty()) "回收站是空的" else "没有匹配的项目", style = MaterialTheme.typography.titleMedium)
                Text(if (state.items.isEmpty()) "从文件详情移入的内容会出现在这里，可恢复到原位置。" else "试试切换状态筛选，查看其他回收记录。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else LazyColumn(Modifier.weight(1f).semantics { contentDescription = "服务端回收站列表" }, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(displayed, key = { it.id }) { item ->
                LibraryObjectCard(item.name, libraryLocation(item.path), fileArtworkIcon(item.name, item.directory),
                    summary = "${trashSize(item)} · ${serverTime(item.deletedAt)}",
                    onOpen = { if (selecting) { if (enabled && item.status != "restoring") selected = if (item.id in selected) selected - item.id else selected + item.id } else details = item },
                    enabled = enabled && (!selecting || item.status != "restoring"),
                    selected = item.id in selected, selectionMode = selecting,
                    onLongClick = if (canDelete || canRestore) ({ if (enabled && item.status != "restoring") { selecting = true; selected = selected + item.id } }) else null,
                    trailing = {
                        if (!selecting) {
                            if (canRestore) IconButton({ model.trash.restore(item) }, enabled = enabled && item.status == "available") {
                                Icon(painterResource(R.drawable.ic_restore), "还原：${item.name}", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }) {
                    if (item.status != "available") Text(when (item.status) { "pending" -> "等待完成移入"; "restoring" -> "正在恢复"; "failed" -> "处理失败"; else -> "状态暂不可用" },
                        color = if (item.status == "failed") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    item.error.takeIf { it.isNotEmpty() }?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
    if (clearing || deleting != null) FilewayAlertDialog(onDismissRequest = { clearing = false; deleting = null }, title = { Text(if (clearing) "清空回收站？" else "永久删除所选 ${deleting!!.size} 项？") },
        text = { Text(if (clearing) "将删除当前账号可见回收站的所有项目。任务开始后无法恢复；提交后会先提供服务端撤销窗口。" else "所选内容将无法恢复。提交后会先提供服务端撤销窗口，可在任务开始前取消。") },
        confirmButton = { FilewayTextButton({ model.trash.permanentlyDelete(deleting.orEmpty(), clearing); clearing = false; deleting = null; selected = emptySet() }, enabled = enabled && canDelete) { Text("提交删除") } },
        dismissButton = { FilewayTextButton({ clearing = false; deleting = null }) { Text("取消") } })
    state.conflict?.let { item -> FilewayAlertDialog(onDismissRequest = model.trash::closeConflict, title = { Text("恢复遇到冲突") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(item.path)
            Text("原位置已有同名目标。保留两者会由服务器另起名称；替换将覆盖原位置现有目标；跳过会保留此回收记录。", style = MaterialTheme.typography.bodySmall)
            FilewayTextButton({ model.trash.restore(item, "keep-both") }, enabled = enabled && canRestore) { Text("保留两者") }
            if (state.permissions.admin || canDelete) FilewayTextButton({ model.trash.restore(item, "replace") }, enabled = enabled && canRestore) { Text("替换原位置文件") }
            FilewayTextButton({ model.trash.restore(item, "skip") }, enabled = enabled && canRestore) { Text("跳过恢复") }
        }
    }, confirmButton = { FilewayTextButton(model.trash::closeConflict) { Text("取消") } }) }
    details?.let { item -> FileOperationSheet("回收站详情", { details = null }, footer = if (!canDelete && !canRestore) null else {{
        if (canDelete) FilewayOutlinedButton({ deleting = listOf(item.id); details = null }, enabled = enabled && item.status != "restoring", modifier = Modifier.weight(1f)) {
            Icon(painterResource(R.drawable.ic_trash), null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("永久删除")
        }
        if (canRestore) FilewayButton({ model.trash.restore(item); details = null }, enabled = enabled && item.status == "available", modifier = Modifier.weight(1f)) {
            Icon(painterResource(R.drawable.ic_restore), null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("还原")
        }
    }}) {
        SelectionContainer(Modifier.weight(1f, fill = false)) {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(item.name, style = MaterialTheme.typography.titleMedium)
                FileOperationLocation("原位置", item.path, showBasename = false)
                Text("${if (item.directory) "文件夹" else "文件"} · ${trashSize(item)}", style = MaterialTheme.typography.bodyMedium)
                Text("删除时间：${serverTime(item.deletedAt)}", style = MaterialTheme.typography.bodyMedium)
                if (item.directory && item.status == "available" && item.sizeState !in setOf("accurate", "calculating") && (canDelete || state.permissions.admin))
                    FilewayOutlinedButton({ model.trash.measure(item) }, enabled = enabled) { Text("统计大小") }
                if (item.sizeTaskId.isNotEmpty() && item.sizeState in setOf("calculating", "failed", "incomplete"))
                    FilewayOutlinedButton({ model.showServerTask(item.sizeTaskId) }) { Text("查看大小统计任务") }
                if (item.error.isNotEmpty()) Text(item.error, color = MaterialTheme.colorScheme.error)
            }
        }
    } }
}

private fun trashSize(item: TrashItem): String = when (item.sizeState) {
    "accurate" -> readableSize(item.size)
    "calculating" -> "正在统计大小"
    "incomplete" -> "至少 ${readableSize(item.size)}，统计未完整"
    "failed" -> "大小统计失败"
    else -> "大小待统计"
}
