package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.app.OperationHistoryController
import io.github.kkwans.nasfilebrowser.data.*
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayMenuItem
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable internal fun OperationHistoryContent(controller: OperationHistoryController, connected: Boolean, onConnect: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    var search by remember(state.scope) { mutableStateOf(state.filter.text) }
    var filters by remember(state.scope) { mutableStateOf(false) }
    var management by remember(state.scope) { mutableStateOf(false) }
    var detail by remember(state.scope) { mutableStateOf<OperationHistoryEntry?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current
    val enabled = connected && !state.loading && !state.paging && !state.clearing
    val selection = rememberFilewayTaskSelection(state.scope to state.filter, state.items.map { it.id })
    var deleteSelection by remember(state.scope, state.filter) { mutableStateOf(false) }
    TaskCenterHeaderActions {
        if (!selection.active && connected) {
            IconButton({ selection.start() }, enabled = enabled && state.items.isNotEmpty()) { Icon(painterResource(R.drawable.ic_select_multiple), "多选操作记录") }
            IconButton({ controller.requestClear() }, enabled = enabled && state.items.isNotEmpty()) { Icon(painterResource(R.drawable.ic_trash), "清空操作历史", tint = MaterialTheme.colorScheme.error) }
        }
    }
    if (!connected) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(painterResource(R.drawable.ic_history), null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Text("连接后查看操作历史", style = MaterialTheme.typography.titleMedium)
            Text("与网页端共用当前账号的文件操作和任务记录。", Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            FilewayButton(onConnect) { Text("连接服务器") }
        }
        return
    }
    FilewayRefresh(state.loading, { controller.refresh() }) {
Column(Modifier.fillMaxSize()) {
        if (selection.active) FilewayTaskSelectionBar(selection, enabled, {
            if (selection.ids.size == state.total) selection.select(emptyList()) else controller.selectAll { selection.select(it) }
        }) {
            IconButton({ if (selection.ids.size == state.total) deleteSelection = true else controller.deleteSelected(selection.ids) },
                enabled = enabled && !state.showingPreviousFilter && selection.ids.isNotEmpty()) { Icon(painterResource(R.drawable.ic_trash), "删除所选操作记录") }
        } else Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilewaySearchField(search, { search = it }, "查找操作、文件或详情", Modifier.weight(1f), enabled) {
                controller.filter(state.filter.copy(text = search.trim())); keyboard?.hide()
            }
            IconButton({ filters = true }, enabled = enabled) { Icon(painterResource(R.drawable.ic_filter), "筛选操作历史") }

        }
        if (state.filter.active) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(listOfNotNull(state.filter.text.takeIf { it.isNotBlank() }?.let { "关键词：$it" },
                state.filter.action.takeIf { it.isNotEmpty() }?.let { OPERATION_HISTORY_ACTIONS[it] ?: it }, state.filter.status?.label,
                if (state.filter.from > 0 || state.filter.to > 0) "${historyDate(state.filter.from)} 至 ${historyDate(state.filter.to)}" else null).joinToString(" · "),
                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FilewayTextButton({ search = ""; controller.filter(OperationHistoryFilter()) }, enabled = enabled) { Text("重置") }
        }
        if (state.loading || state.paging || state.clearing) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        state.error?.let { message ->
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                FilewayTextButton({ controller.retry() }, enabled = enabled) { Text(if (state.retryMore) "重试加载更多" else "重新读取") }
            }
        }
        if (state.showingPreviousFilter) Text("筛选结果尚未更新，以下保留上次读取的记录。", Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FilewayToast(state.notice)
        LazyColumn(Modifier.weight(1f).fillMaxWidth().semantics { contentDescription = "操作历史列表" },
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.items.isEmpty() && state.loaded && state.error == null && !state.loading) item {
                Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(painterResource(R.drawable.ic_history), null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (state.filter.active) "没有匹配的操作记录" else "还没有操作记录", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium)
                    Text(if (state.filter.active) "调整关键词、动作、状态或日期后再试。" else "重命名、移动、上传和任务操作会记录在这里。",
                        Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
            items(state.items, key = { it.id }) { entry ->
                val target = operationHistoryTarget(entry.target)
                FilewayTaskCard(title = target.name.ifEmpty { entry.actionLabel },
                    icon = { Icon(painterResource(R.drawable.ic_history), null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary) },
                    meta = {
                        TaskRecordMeta(if (target.name.isEmpty()) entry.statusLabel else "${entry.actionLabel} · ${entry.statusLabel}", entry.createdAt,
                            when (entry.status) { "failed" -> MaterialTheme.colorScheme.error; "submitted" -> MaterialTheme.colorScheme.primary; else -> MaterialTheme.colorScheme.onSurfaceVariant })
                    }, onClick = { if (selection.active) selection.toggle(entry.id) else detail = entry },
                    onLongClick = { if (enabled) selection.start(entry.id) }, selected = entry.id in selection.ids,
                    actions = { if (!selection.active) IconButton({ controller.deleteSelected(setOf(entry.id)) }, enabled = enabled && !state.showingPreviousFilter) {
                        Icon(painterResource(R.drawable.ic_trash), "删除记录：${target.name.ifEmpty { entry.actionLabel }}")
                    } }) {
                    target.parent?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    if (entry.action != "trash.move" && entry.detail.isNotEmpty() && entry.detail != entry.target) Text(entry.detail, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (state.nextCursor.isNotEmpty() && !state.showingPreviousFilter) item {
                FilewayTextButton({ controller.refresh(more = true) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.paging) "正在加载更多记录" else "加载更多记录")
                }
            }
        }
    }
}

    if (deleteSelection) FilewayAlertDialog(onDismissRequest = { deleteSelection = false }, title = { Text("删除当前筛选的全部记录？") },
        text = { Text("将删除所选 ${selection.ids.size} 条操作记录。文件和任务保留。") },
        confirmButton = { FilewayButton({ controller.deleteSelected(selection.ids); deleteSelection = false }, enabled = enabled) { Text("删除记录") } },
        dismissButton = { FilewayOutlinedButton({ deleteSelection = false }) { Text("取消") } })
    detail?.let { entry -> FileOperationSheet("操作记录", { detail = null }, footer = {
        FilewaySheetActions(listOf(FilewayMenuEntry("delete", "删除记录", R.drawable.ic_trash, enabled = enabled, destructive = true))) { controller.deleteSelected(setOf(entry.id)); detail = null }
    }) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${entry.actionLabel} · ${entry.statusLabel}", style = MaterialTheme.typography.titleMedium)
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (entry.target.isNotBlank()) FileOperationLocation("位置", entry.target, showBasename = false)
                    if (entry.detail.isNotEmpty() && entry.detail != entry.target) Text(entry.detail, style = MaterialTheme.typography.bodySmall)
                    DetailMetadata("操作时间", historyTime(entry.createdAt))
                }
            }
        }
    } }
    if (filters) OperationHistoryFilterSheet(state.filter, state.items.map { it.action }.distinct(), {
        controller.filter(it); search = it.text; filters = false
    }) { filters = false }
    if (state.clearConfirmation) FilewayAlertDialog(onDismissRequest = controller::cancelClear, title = { Text("清空当前账号的操作历史？") },
        text = { Text("将永久删除当前服务器上此账号的全部操作记录，包括筛选范围外的记录；网页端也会同步清空。文件和任务不会被删除。") },
        confirmButton = { FilewayTextButton(controller::confirmClear, enabled = enabled) { Text("确认清空", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { FilewayTextButton(controller::cancelClear) { Text("取消") } })
}

private fun historyDate(value: Long): String = if (value <= 0) "不限" else Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault()).toLocalDate().toString()
private fun historyTime(value: Long): String = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(value))

@Composable private fun OperationHistoryFilterSheet(filter: OperationHistoryFilter, observedActions: List<String>,
    apply: (OperationHistoryFilter) -> Unit, dismiss: () -> Unit) {
    var action by remember(filter) { mutableStateOf(filter.action) }
    var status by remember(filter) { mutableStateOf(filter.status) }
    var from by remember(filter) { mutableLongStateOf(filter.from) }
    var to by remember(filter) { mutableLongStateOf(filter.to) }
    var actionsOpen by remember { mutableStateOf(false) }
    var statusesOpen by remember { mutableStateOf(false) }
    var dateTarget by remember { mutableStateOf<Boolean?>(null) }
    dateTarget?.let { start ->
        TaskCenterDatePicker(if (start) from else to, onDay = { picked ->
            if (start) from = picked.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            else to = picked.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1
        }, onDismiss = { dateTarget = null })
    }
    FilewayFilterSheet(
        title = "筛选操作历史",
        onDismiss = dismiss,
        onReset = { action = ""; status = null; from = 0; to = 0 },
        onApply = { apply(filter.copy(action = action, status = status, from = from, to = to)) },
        applyEnabled = from == 0L || to == 0L || from <= to,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) {
                FilewayFilterField("动作", if (action.isEmpty()) "全部" else OPERATION_HISTORY_ACTIONS[action] ?: action,
                    { actionsOpen = true; statusesOpen = false })
                val actionIds = (OPERATION_HISTORY_ACTIONS.keys + observedActions + listOf(action))
                    .filter { it.isNotEmpty() }.distinct()
                FilewayMenu(actionsOpen, { actionsOpen = false },
                    items = listOf(FilewayMenuEntry("", "全部动作", selected = action.isEmpty())) +
                        actionIds.map { FilewayMenuEntry(it, OPERATION_HISTORY_ACTIONS[it] ?: it, selected = action == it) },
                    selection = FilewayMenuSelection.Single,
                    onItemClick = { action = it.key; actionsOpen = false })
            }
            Box(Modifier.weight(1f)) {
                FilewayFilterField("状态", status?.label ?: "全部", { statusesOpen = true; actionsOpen = false })
                FilewayMenu(statusesOpen, { statusesOpen = false },
                    items = listOf(FilewayMenuEntry("", "全部状态", selected = status == null)) +
                        OperationHistoryStatus.entries.map { FilewayMenuEntry(it.name, it.label, selected = status == it) },
                    selection = FilewayMenuSelection.Single,
                    onItemClick = { item -> status = OperationHistoryStatus.entries.firstOrNull { it.name == item.key }; statusesOpen = false })
            }
        }
        FilewayDateRangeFields(historyDate(from), historyDate(to), from > 0 || to > 0,
            { dateTarget = true }, { dateTarget = false }, { from = 0; to = 0 },
            invalidRange = from > 0 && to > 0 && from > to)
    }
}
