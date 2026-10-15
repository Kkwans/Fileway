package io.github.kkwans.nasfilebrowser.ui

import android.app.DatePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.app.*
import io.github.kkwans.nasfilebrowser.data.*
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private data class TaskConfirmation(val task: ServerTask?, val command: String, val filter: TaskFilter, val count: Int)

internal fun serverTime(value: Long): String = if (value <= 0) "未提供" else runCatching {
    DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(value))
}.getOrDefault("未提供")

@Composable internal fun ServerTasksScreen(model: ClientModel, client: ClientState) {
    val state by model.tasks.state.collectAsStateWithLifecycle()
    var search by rememberSaveable(state.scope) { mutableStateOf(state.filter.text) }
    var advanced by remember(state.scope) { mutableStateOf(false) }
    var confirm by remember(state.scope) { mutableStateOf<TaskConfirmation?>(null) }
    fun confirmAction(task: ServerTask?, command: String) { confirm = TaskConfirmation(task, command, state.filter, state.total) }
    val list = rememberLazyListState()
    val enabled = !state.loading && !state.changing && !state.paging
    LaunchedEffect(list, state.scope) { snapshotFlow { list.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? String }.toSet() }.collect(model.tasks::watch) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("服务器任务", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton({ advanced = true }, enabled = enabled) { Text("筛选") }
            IconButton({ model.tasks.refresh() }, enabled = enabled) { Icon(painterResource(R.drawable.ic_refresh), "刷新任务") }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(state.filter.category == "file", { model.tasks.filter(state.filter.copy(category = "file")) }, { Text("文件任务") }, enabled = enabled, colors = libraryChipColors())
            FilterChip(state.filter.category == "background", { model.tasks.filter(state.filter.copy(category = "background")) }, { Text("其他后台任务") }, enabled = enabled, colors = libraryChipColors())
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = .10f), selectedLabelColor = MaterialTheme.colorScheme.primary)
            TaskView.entries.forEach { view ->
                val count = state.counts[when (view) { TaskView.ALL -> "all"; TaskView.ACTIVE -> "active"; TaskView.ATTENTION -> "attention"; TaskView.CANCELED -> "canceled"; TaskView.COMPLETED -> "completed"; TaskView.ARCHIVED -> "archived" }]
                FilterChip(state.filter.view == view, { model.tasks.filter(state.filter.copy(view = view)) }, { Text(view.label + (count?.let { " $it" } ?: "")) }, colors = colors, enabled = enabled)
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(search, { search = it }, label = { Text("查找任务") }, modifier = Modifier.weight(1f), singleLine = true)
            TextButton({ model.tasks.filter(state.filter.copy(text = search.trim())) }, enabled = enabled) { Text("查找") }
        }
        if (state.filter.type.isNotEmpty() || state.filter.owner.isNotEmpty() || state.filter.from > 0 || state.filter.to > 0) Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(listOfNotNull(state.filter.type.takeIf { it.isNotEmpty() }?.let { SERVER_TASK_TYPES[it] ?: it }, state.filter.owner.takeIf { it.isNotEmpty() },
                if (state.filter.from > 0 || state.filter.to > 0) "限定日期" else null).joinToString(" · "), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton({ model.tasks.filter(state.filter.copy(type = "", owner = "", from = 0, to = 0)) }, enabled = enabled) { Text("清除") }
        }
        LibraryMessage(state.error, state.notice, state.loading || state.changing) { model.tasks.refresh() }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${state.items.size} / ${state.total} 项", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when (state.filter.view) {
                TaskView.ARCHIVED -> TextButton({ confirmAction(null, "batch-unarchive") }, enabled = enabled && state.total > 0) { Text("全部移出归档") }
                TaskView.ATTENTION, TaskView.COMPLETED, TaskView.CANCELED -> TextButton({ confirmAction(null, "batch-archive") }, enabled = enabled && state.total > 0) { Text("批量归档") }
                TaskView.ALL -> TextButton({ confirmAction(null, "archive-ended") }, enabled = enabled) { Text("归档我的已结束记录") }
                else -> Unit
            }
            if (state.filter.view == TaskView.ATTENTION && state.filter.type.isNotEmpty()) TextButton({ confirmAction(null, "batch-retry") }, enabled = enabled && state.total > 0) { Text("批量重试") }
        }
        if (state.items.isEmpty() && !state.loading && state.error == null) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text("这里还没有匹配的服务端任务。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else LazyColumn(Modifier.weight(1f).semantics { contentDescription = "服务端任务列表" }, state = list, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.items, key = { it.id }) { task ->
                Surface(Modifier.fillMaxWidth(), shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(task.title.ifEmpty { task.typeLabel }, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            TextButton({ model.tasks.select(task.id) }) { Text("详情") }
                        }
                        Text("${task.typeLabel} · ${task.statusLabel}", style = MaterialTheme.typography.bodySmall,
                            color = if (task.status in setOf("failed", "interrupted")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        TaskProgress(task)
                        Text("${task.owner} · ${serverTime(task.createdAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        task.error.takeIf { it.isNotEmpty() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                        TaskActions(model, task, enabled && model.tasks.canEdit(task)) { command -> confirmAction(task, command) }
                    }
                }
            }
            if (state.nextCursor.isNotEmpty()) item { TextButton({ model.tasks.refresh(more = true) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(if (state.paging) "正在加载" else "加载更多任务") } }
        }
    }
    if (advanced) TaskFilterDialog(state, client.accountName, { model.tasks.filter(it); search = it.text; advanced = false }) { advanced = false }
    if (state.focusId != null) AlertDialog(onDismissRequest = model::closeTaskDetail, title = { Text("任务详情") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.detailLoading) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            state.detailError?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton({ state.focusId?.let(model.tasks::select) }) { Text("重试") } }
            state.selected?.let { task ->
                Text(task.title.ifEmpty { task.typeLabel }, style = MaterialTheme.typography.titleMedium)
                Text("${task.typeLabel} · ${task.statusLabel}"); TaskProgress(task)
                Text("创建：${serverTime(task.createdAt)}\n开始：${serverTime(task.startedAt)}\n结束：${serverTime(task.finishedAt)}")
                Text("账号：${task.owner}\n任务 ID：${task.id}", style = MaterialTheme.typography.bodySmall)
                if (task.sourcePath.isNotEmpty()) Text("来源：${task.sourcePath}")
                if (task.outputPath.isNotEmpty()) Text("输出：${task.outputPath}")
                if (task.error.isNotEmpty()) Text(task.error, color = MaterialTheme.colorScheme.error)
                if (task.type in setOf("analysis.storage", "analysis.duplicates")) TextButton({ model.tasks.closeDetail(); model.showAnalysis(task.id, task.type) }) { Text(if (task.status == "completed") "查看分析报告" else "查看分析进度") }
                if (task.status == "completed" && task.outputPath.startsWith('/')) TextButton({ model.tasks.closeDetail(); model.openRemotePath(task.outputPath) }, enabled = !client.busy) { Text("打开输出文件") }
                TaskActions(model, task, enabled && !state.detailLoading && state.detailError == null && model.tasks.canEdit(task)) { confirmAction(task, it) }
            }
        }
    }, confirmButton = { TextButton(model::closeTaskDetail) { Text("关闭") } })
    confirm?.let { choice ->
        val task = choice.task; val command = choice.command
        val batch = command.startsWith("batch-")
        val title = when (command) { "cancel" -> if (task?.pendingDeletion == true) "撤销删除任务？" else "停止这个任务？"; "retry", "batch-retry" -> "创建重试任务？"; "batch-unarchive", "unarchive" -> "移出归档？"; else -> "归档任务记录？" }
        AlertDialog(onDismissRequest = { confirm = null }, title = { Text(title) }, text = { Text(when {
            command == "archive-ended" -> "归档当前分类中我的所有已结束记录。文件不会被删除，之后可从“已归档”查看。"
            batch -> "将对当前筛选的 ${choice.count} 个服务端任务执行此操作。数量或状态变化时会停止并要求重新确认。"
            command == "cancel" -> "${task?.title}\n已经开始的任务可能已有部分结果；取消后可查看实际状态。"
            else -> task?.title.orEmpty()
        }) }, confirmButton = { TextButton({
            if (command == "archive-ended") model.tasks.archiveEnded()
            else if (batch) model.tasks.batch(command.removePrefix("batch-"), choice.filter, choice.count)
            else task?.let { model.tasks.action(it, command) }
            confirm = null
        }, enabled = enabled) { Text("确认") } }, dismissButton = { TextButton({ confirm = null }) { Text("取消") } })
    }
}

@Composable internal fun TaskProgress(task: ServerTask) {
    val value = task.progress
    if (task.active && value != null) LinearProgressIndicator(progress = { value }, modifier = Modifier.fillMaxWidth().height(4.dp), gapSize = 0.dp, drawStopIndicator = {})
    val counters = when {
        task.duration > 0 -> "${clock((task.processedSeconds * 1000).toLong())} / ${clock((task.duration * 1000).toLong())}"
        task.totalBytes > 0 -> "${readableSize(task.processedBytes)} / ${readableSize(task.totalBytes)}"
        task.totalItems > 0 -> "${task.processedItems} / ${task.totalItems} 项"
        task.processedBytes > 0 -> "已处理 ${readableSize(task.processedBytes)}"
        else -> if (task.active) "等待进度数据" else task.statusLabel
    }
    val summary = if (task.status == "completed") "已完成 · ${serverTime(task.finishedAt)}" else
        (value?.let { "${(it * 100).toInt()}% · " } ?: "") + counters +
            (if (task.speed.isFinite() && task.speed > 0) " · ${String.format(java.util.Locale.ROOT, "%.1f×", task.speed)}" else "")
    Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (task.pendingDeletion) Text("删除尚未开始，可撤销", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
}

@Composable private fun TaskActions(model: ClientModel, task: ServerTask, enabled: Boolean, confirm: (String) -> Unit) {
    Row(Modifier.fillMaxWidth()) {
        if (task.active) TextButton({ if (task.pendingDeletion) model.tasks.action(task, "cancel") else confirm("cancel") }, enabled = enabled) { Text(if (task.pendingDeletion) "撤销删除" else "停止任务") }
        if (task.canRetry) TextButton({ confirm("retry") }, enabled = enabled) { Text("重试") }
        if (task.canArchive) TextButton({ confirm("archive") }, enabled = enabled) { Text("归档") }
        if (task.archivedAt > 0) TextButton({ confirm("unarchive") }, enabled = enabled) { Text("移出归档") }
    }
}

@Composable private fun TaskFilterDialog(state: ServerTasksState, accountName: String, apply: (TaskFilter) -> Unit, dismiss: () -> Unit) {
    val context = LocalContext.current
    var type by remember { mutableStateOf(state.filter.type) }; var owner by remember { mutableStateOf(state.filter.owner) }
    var from by remember { mutableLongStateOf(state.filter.from) }; var to by remember { mutableLongStateOf(state.filter.to) }
    var types by remember { mutableStateOf(false) }; var owners by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf<DatePickerDialog?>(null) }
    DisposableEffect(Unit) { onDispose { picker?.dismiss() } }
    fun date(value: Long) = if (value <= 0) "不限" else Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault()).toLocalDate().toString()
    fun choose(start: Boolean) {
        val old = if (start) from else to
        val day = if (old > 0) Instant.ofEpochMilli(old).atZone(ZoneId.systemDefault()).toLocalDate() else LocalDate.now()
        picker = DatePickerDialog(context, { _, year, month, date ->
            val picked = LocalDate.of(year, month + 1, date)
            if (start) from = picked.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            else to = picked.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1
        }, day.year, day.monthValue - 1, day.dayOfMonth).also { it.show() }
    }
    AlertDialog(onDismissRequest = dismiss, title = { Text("任务筛选") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box {
                TextButton({ types = true }) { Text("任务类型：${SERVER_TASK_TYPES[type] ?: "全部"}") }
                DropdownMenu(types, { types = false }, modifier = Modifier.heightIn(max = 360.dp)) {
                    DropdownMenuItem({ Text("全部类型") }, { type = ""; types = false })
                    SERVER_TASK_TYPES.filterKeys { it !in setOf("file.delete.permanent", "trash.delete.permanent") }.forEach { (id, label) -> DropdownMenuItem({ Text(label) }, { type = id; types = false }) }
                }
            }
            Box {
                TextButton({ owners = true }) { Text("账号：${owner.ifEmpty { "全部可见账号" }}") }
                DropdownMenu(owners, { owners = false }) {
                    DropdownMenuItem({ Text("全部可见账号") }, { owner = ""; owners = false })
                    (listOf(accountName) + state.owners).distinct().filter { it.isNotEmpty() }.forEach { name -> DropdownMenuItem({ Text(name) }, { owner = name; owners = false }) }
                }
            }
            TextButton({ choose(true) }) { Text("开始日期：${date(from)}") }
            TextButton({ choose(false) }) { Text("结束日期：${date(to)}") }
            TextButton({ from = 0; to = 0 }) { Text("不限日期") }
            if (from > 0 && to > 0 && from > to) Text("结束日期不能早于开始日期", color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton({ apply(state.filter.copy(type = type, owner = owner, from = from, to = to)) }, enabled = from == 0L || to == 0L || from <= to) { Text("应用") } },
        dismissButton = { TextButton(dismiss) { Text("取消") } })
}
