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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.app.OperationHistoryController
import io.github.kkwans.nasfilebrowser.data.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable internal fun OperationHistoryContent(controller: OperationHistoryController, connected: Boolean, onConnect: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    var search by remember(state.scope) { mutableStateOf(state.filter.text) }
    var filters by remember(state.scope) { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val enabled = connected && !state.loading && !state.paging && !state.clearing
    if (!connected) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(painterResource(R.drawable.ic_history), null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Text("连接后查看操作历史", style = MaterialTheme.typography.titleMedium)
            Text("与网页端共用当前账号的文件操作和任务记录。", Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onConnect) { Text("连接服务器") }
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(search, { search = it }, placeholder = { Text("查找操作、文件或详情") }, singleLine = true,
                enabled = !state.clearing, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (enabled) { controller.filter(state.filter.copy(text = search.trim())); keyboard?.hide() } }),
                trailingIcon = { IconButton({ controller.filter(state.filter.copy(text = search.trim())); keyboard?.hide() }, enabled = enabled) {
                    Icon(painterResource(R.drawable.ic_search), "查找操作历史")
                } })
            TextButton({ filters = true }, enabled = enabled) { Text("筛选") }
            IconButton({ controller.refresh() }, enabled = enabled) { Icon(painterResource(R.drawable.ic_refresh), "刷新操作历史") }
        }
        if (state.filter.active) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(listOfNotNull(state.filter.text.takeIf { it.isNotBlank() }?.let { "关键词：$it" },
                state.filter.action.takeIf { it.isNotEmpty() }?.let { OPERATION_HISTORY_ACTIONS[it] ?: it }, state.filter.status?.label,
                if (state.filter.from > 0 || state.filter.to > 0) "${historyDate(state.filter.from)} 至 ${historyDate(state.filter.to)}" else null).joinToString(" · "),
                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton({ search = ""; controller.filter(OperationHistoryFilter()) }, enabled = enabled) { Text("重置") }
        }
        if (state.loading || state.paging || state.clearing) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        state.error?.let { message ->
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                TextButton({ controller.retry() }, enabled = enabled) { Text(if (state.retryMore) "重试加载更多" else "重新读取") }
            }
        }
        if (state.showingPreviousFilter) Text("筛选结果尚未更新，以下保留上次读取的记录。", Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        state.notice?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().semantics { contentDescription = "操作历史列表" },
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (state.loaded) "当前账号 · ${state.items.size} / ${state.total} 条" else "当前账号的操作记录", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(controller::requestClear, enabled = enabled) { Text("清空记录", color = if (enabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f)) }
                }
            }
            if (state.items.isEmpty() && state.loaded && state.error == null && !state.loading) item {
                Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(painterResource(R.drawable.ic_history), null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (state.filter.active) "没有匹配的操作记录" else "还没有操作记录", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium)
                    Text(if (state.filter.active) "调整关键词、动作、状态或日期后再试。" else "重命名、移动、上传和任务操作会记录在这里。",
                        Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
            items(state.items, key = { it.id }) { entry ->
                Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(entry.actionLabel, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            Text(entry.statusLabel, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.labelMedium,
                                color = when (entry.status) { "failed" -> MaterialTheme.colorScheme.error; "submitted" -> MaterialTheme.colorScheme.primary; else -> MaterialTheme.colorScheme.onSurfaceVariant })
                        }
                        SelectionContainer {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(entry.target, style = MaterialTheme.typography.bodyMedium)
                                if (entry.detail.isNotEmpty() && entry.detail != entry.target) Text(entry.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Text(historyTime(entry.createdAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (state.nextCursor.isNotEmpty() && !state.showingPreviousFilter) item {
                TextButton({ controller.refresh(more = true) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.paging) "正在加载更多记录" else "加载更多记录")
                }
            }
        }
    }
    if (filters) OperationHistoryFilterDialog(state.filter, state.items.map { it.action }.distinct(), {
        controller.filter(it); search = it.text; filters = false
    }) { filters = false }
    if (state.clearConfirmation) AlertDialog(onDismissRequest = controller::cancelClear, title = { Text("清空当前账号的操作历史？") },
        text = { Text("将永久删除当前服务器上此账号的全部操作记录，包括筛选范围外的记录；网页端也会同步清空。文件和任务不会被删除。") },
        confirmButton = { TextButton(controller::confirmClear, enabled = enabled) { Text("确认清空", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(controller::cancelClear) { Text("取消") } })
}

private fun historyDate(value: Long): String = if (value <= 0) "不限" else Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault()).toLocalDate().toString()
private fun historyTime(value: Long): String = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(value))

@Composable private fun OperationHistoryFilterDialog(filter: OperationHistoryFilter, observedActions: List<String>,
    apply: (OperationHistoryFilter) -> Unit, dismiss: () -> Unit) {
    var action by remember { mutableStateOf(filter.action) }
    var status by remember { mutableStateOf(filter.status) }
    var from by remember { mutableLongStateOf(filter.from) }
    var to by remember { mutableLongStateOf(filter.to) }
    var actionsOpen by remember { mutableStateOf(false) }
    var statusesOpen by remember { mutableStateOf(false) }
    var dateTarget by remember { mutableStateOf<Boolean?>(null) }
    dateTarget?.let { start ->
        TaskCenterDatePicker(if (start) from else to, onDay = { picked ->
            if (start) from = picked.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            else to = picked.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1
        }, onDismiss = { dateTarget = null })
    }
    AlertDialog(onDismissRequest = dismiss, title = { Text("筛选操作历史") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box {
                TextButton({ actionsOpen = true }) { Text("动作：${if (action.isEmpty()) "全部" else OPERATION_HISTORY_ACTIONS[action] ?: action}") }
                DropdownMenu(actionsOpen, { actionsOpen = false }, modifier = Modifier.heightIn(max = 360.dp)) {
                    DropdownMenuItem({ Text("全部动作") }, { action = ""; actionsOpen = false })
                    (OPERATION_HISTORY_ACTIONS.keys + observedActions + listOf(action)).filter { it.isNotEmpty() }.distinct().forEach { id ->
                        DropdownMenuItem({ Text(OPERATION_HISTORY_ACTIONS[id] ?: id) }, { action = id; actionsOpen = false })
                    }
                }
            }
            Box {
                TextButton({ statusesOpen = true }) { Text("状态：${status?.label ?: "全部"}") }
                DropdownMenu(statusesOpen, { statusesOpen = false }) {
                    DropdownMenuItem({ Text("全部状态") }, { status = null; statusesOpen = false })
                    OperationHistoryStatus.entries.forEach { value -> DropdownMenuItem({ Text(value.label) }, { status = value; statusesOpen = false }) }
                }
            }
            TextButton({ dateTarget = true }) { Text("开始日期：${historyDate(from)}") }
            TextButton({ dateTarget = false }) { Text("结束日期：${historyDate(to)}") }
            TextButton({ from = 0; to = 0 }) { Text("不限日期") }
            if (from > 0 && to > 0 && from > to) Text("结束日期不能早于开始日期", color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton({ apply(filter.copy(action = action, status = status, from = from, to = to)) },
        enabled = from == 0L || to == 0L || from <= to) { Text("应用") } }, dismissButton = { TextButton(dismiss) { Text("取消") } })
}
