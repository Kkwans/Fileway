package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.app.*
import io.github.kkwans.nasfilebrowser.data.*
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayMenuItem
import java.time.Instant
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
    val keyboard = LocalSoftwareKeyboardController.current
    var confirm by remember(state.scope) { mutableStateOf<TaskConfirmation?>(null) }
    var technicalDetails by remember(state.scope, state.focusId) { mutableStateOf(false) }
    fun confirmAction(task: ServerTask?, command: String) { confirm = TaskConfirmation(task, command, state.filter, state.total) }
    val list = rememberLazyListState()
    val enabled = !state.loading && !state.changing && !state.paging
    LaunchedEffect(list, state.scope) { snapshotFlow { list.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? String }.toSet() }.collect(model.tasks::watch) }
    FilewayRefresh(state.loading, { model.tasks.refresh() }) {
Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilewaySearchField(search, { search = it }, "查找任务", Modifier.weight(1f), enabled) {
                model.tasks.filter(state.filter.copy(text = search.trim())); keyboard?.hide()
            }
            IconButton({ advanced = true }, enabled = enabled) { Icon(painterResource(R.drawable.ic_filter), "筛选任务") }

        }
        LibraryMessage(state.error, state.notice, state.loading || state.changing) { model.tasks.refresh() }
        LazyColumn(Modifier.weight(1f).semantics { contentDescription = "服务端任务列表" }, state = list, contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Chrome uses a non-string key so visible-task polling never treats it as a task ID.
        item(key = 0) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text("${if (state.filter.category == "file") "文件任务" else "其他后台任务"} · ${state.filter.view.label} · ${state.items.size}/${state.total}", style = MaterialTheme.typography.labelLarge)
                val conditions = listOfNotNull(state.filter.type.takeIf { it.isNotEmpty() }?.let { SERVER_TASK_TYPES[it] ?: it },
                    if (state.filter.from > 0 || state.filter.to > 0) "限定日期" else null)
                if (conditions.isNotEmpty()) Text(conditions.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val singleBulk = when (state.filter.view) {
                TaskView.ARCHIVED -> "batch-unarchive"
                TaskView.COMPLETED, TaskView.CANCELED -> "batch-archive"
                TaskView.ALL -> "archive-ended"
                TaskView.ATTENTION -> if (state.filter.type.isEmpty()) "batch-archive" else null
                else -> null
            }
            if (singleBulk != null) IconButton({ confirmAction(null, singleBulk) }, enabled = enabled && (state.total > 0 || singleBulk == "archive-ended")) {
                Icon(painterResource(if (singleBulk == "batch-unarchive") R.drawable.ic_restore else R.drawable.ic_archive),
                    if (singleBulk == "batch-unarchive") "全部移出归档" else "归档已结束任务", Modifier.size(22.dp))
            } else if (state.filter.view == TaskView.ATTENTION && state.filter.type.isNotEmpty()) {
                IconButton({ confirmAction(null, "batch-archive") }, enabled = enabled && state.total > 0) {
                    Icon(painterResource(R.drawable.ic_archive), "批量归档", Modifier.size(22.dp))
                }
                IconButton({ confirmAction(null, "batch-retry") }, enabled = enabled && state.total > 0) {
                    Icon(painterResource(R.drawable.ic_refresh), "批量重试", Modifier.size(22.dp))
                }
            }
        }
        }
        if (state.items.isEmpty() && !state.loading && state.error == null) item {
            Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                Text("没有匹配的任务，可调整筛选条件", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
            items(state.items, key = { it.id }) { task ->
                val cardActions = buildList {
                    add(FilewayTaskAction("detail", "查看详情", R.drawable.ic_info))
                    if (task.active) add(FilewayTaskAction(if (task.pendingDeletion) "unarchive" else "cancel",
                        if (task.pendingDeletion) "撤销删除" else "停止任务", R.drawable.ic_close, enabled = enabled && model.tasks.canEdit(task), primary = true))
                    if (task.canRetry) add(FilewayTaskAction("retry", "重试", R.drawable.ic_refresh, enabled = enabled && model.tasks.canEdit(task), primary = true))
                    if (task.canArchive) add(FilewayTaskAction("archive", "归档", R.drawable.ic_archive, enabled = enabled && model.tasks.canEdit(task), primary = true))
                    if (task.archivedAt > 0) add(FilewayTaskAction("unarchive", "移出归档", R.drawable.ic_restore, enabled = enabled && model.tasks.canEdit(task), primary = true))
                }
                FilewayTaskCard(
                    title = task.title.ifEmpty { task.typeLabel },
                    icon = { Icon(painterResource(serverTaskIcon(task)), null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary) },
                    meta = {
                        Text(task.statusLabel, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                            color = if (task.status in setOf("failed", "interrupted")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(serverTime(if (task.status == "completed") task.finishedAt else task.createdAt), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    },
                    actions = {
                        FilewayTaskActionStrip(cardActions, onAction = { command ->
                            when {
                                command == "detail" -> model.tasks.select(task.id)
                                command == "unarchive" && task.pendingDeletion -> model.tasks.action(task, "cancel")
                                else -> confirmAction(task, command)
                            }
                        }, moreLabel = "任务操作：${task.title.ifEmpty { task.typeLabel }}")
                    },
                    onClick = { model.tasks.select(task.id) },
                ) {
                    if (task.active || task.status != "completed" && (task.processedItems > 0 || task.processedBytes > 0 || task.processedSeconds > 0)) TaskProgress(task)
                    task.error.takeIf { it.isNotEmpty() && !(task.status == "canceled" && it in setOf("任务已取消", "task canceled", "context canceled")) }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (state.nextCursor.isNotEmpty()) item { FilewayTextButton({ model.tasks.refresh(more = true) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(if (state.paging) "正在加载" else "加载更多任务") } }
        }
    }
}

    if (advanced) TaskFilterSheet(state, client.accountName, { model.tasks.filter(it); search = it.text; advanced = false }) { advanced = false }
    if (state.focusId != null) FileOperationSheet(title = "任务详情", onDismiss = model::closeTaskDetail,
        footer = { FilewayTextButton(model::closeTaskDetail) { Text("关闭") } }) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.detailLoading) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            state.detailError?.let { Text(it, color = MaterialTheme.colorScheme.error); FilewayTextButton({ state.focusId?.let(model.tasks::select) }) { Text("重试") } }
            state.selected?.let { task ->
                Text(task.title.ifEmpty { task.typeLabel }, style = MaterialTheme.typography.titleMedium)
                Text("${task.typeLabel} · ${task.statusLabel}"); TaskProgress(task)
                Text("创建于 ${serverTime(task.createdAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (task.sourcePath.isNotEmpty()) FileOperationLocation("来源", task.sourcePath)
                        if (task.outputPath.isNotEmpty()) FileOperationLocation("输出", task.outputPath)
                    }
                }
                if (task.error.isNotEmpty()) Text(task.error, color = MaterialTheme.colorScheme.error)
                if (task.type in setOf("analysis.storage", "analysis.duplicates")) FilewayTextButton({ model.tasks.closeDetail(); model.showAnalysis(task.id, task.type) }) { Text(if (task.status == "completed") "查看分析报告" else "查看分析进度") }
                if (task.status == "completed" && task.outputPath.startsWith('/')) FilewayTextButton({ model.tasks.closeDetail(); model.openRemotePath(task.outputPath) }, enabled = !client.busy) { Text("打开输出文件") }
                TaskActions(model, task, enabled && !state.detailLoading && state.detailError == null && model.tasks.canEdit(task)) { confirmAction(task, it) }
                FilewayTextButton({ technicalDetails = !technicalDetails }) { Text(if (technicalDetails) "收起技术信息" else "任务 ID 与完整时间") }
                if (technicalDetails) SelectionContainer {
                    Text("任务 ID：${task.id}\n创建：${taskTimestamp(task.createdAt)}\n开始：${taskTimestamp(task.startedAt)}\n结束：${taskTimestamp(task.finishedAt)}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    confirm?.let { choice ->
        val task = choice.task; val command = choice.command
        val batch = command.startsWith("batch-")
        val title = when (command) { "cancel" -> if (task?.pendingDeletion == true) "撤销删除任务？" else "停止这个任务？"; "retry", "batch-retry" -> "创建重试任务？"; "batch-unarchive", "unarchive" -> "移出归档？"; else -> "归档任务记录？" }
        FilewayAlertDialog(onDismissRequest = { confirm = null }, title = { Text(title) }, text = { Text(when {
            command == "archive-ended" -> "归档当前分类中我的所有已结束记录。文件不会被删除，之后可从“已归档”查看。"
            batch -> "将对当前筛选的 ${choice.count} 个服务端任务执行此操作。数量或状态变化时会停止并要求重新确认。"
            command == "cancel" -> "${task?.title}\n已经开始的任务可能已有部分结果；取消后可查看实际状态。"
            else -> task?.title.orEmpty()
        }) }, confirmButton = { FilewayTextButton({
            if (command == "archive-ended") model.tasks.archiveEnded()
            else if (batch) model.tasks.batch(command.removePrefix("batch-"), choice.filter, choice.count)
            else task?.let { model.tasks.action(it, command) }
            confirm = null
        }, enabled = enabled) { Text("确认") } }, dismissButton = { FilewayTextButton({ confirm = null }) { Text("取消") } })
    }
}

private fun taskTimestamp(value: Long): String = if (value <= 0) "未提供" else runCatching {
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(value))
}.getOrDefault("未提供")

private fun serverTaskIcon(task: ServerTask): Int = when {
    task.fileCategory -> R.drawable.ic_file
    task.type.startsWith("archive") -> R.drawable.ic_archive
    task.type.startsWith("analysis") -> R.drawable.ic_storage
    task.type.startsWith("media") -> R.drawable.ic_video
    else -> R.drawable.ic_tasks
}

@Composable internal fun TaskProgress(task: ServerTask) {
    val value = task.progress
    if (task.active) {
        if (value != null) LinearProgressIndicator(progress = { value }, modifier = Modifier.fillMaxWidth().height(3.dp), gapSize = 0.dp, drawStopIndicator = {})
        else LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp))
    }
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
    if (!task.active && !task.canRetry && !task.canArchive && task.archivedAt <= 0) return
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (task.active) FilewayTextButton({ if (task.pendingDeletion) model.tasks.action(task, "cancel") else confirm("cancel") }, enabled = enabled) { Text(if (task.pendingDeletion) "撤销删除" else "停止任务") }
        if (task.canRetry) FilewayTextButton({ confirm("retry") }, enabled = enabled) { Text("重试") }
        if (task.canArchive) FilewayTextButton({ confirm("archive") }, enabled = enabled) { Text("归档") }
        if (task.archivedAt > 0) FilewayTextButton({ confirm("unarchive") }, enabled = enabled) { Text("移出归档") }
    }
}

@Composable private fun TaskFilterSheet(state: ServerTasksState, accountName: String, apply: (TaskFilter) -> Unit, dismiss: () -> Unit) {
    var category by remember(state.filter) { mutableStateOf(state.filter.category) }
    var view by remember(state.filter) { mutableStateOf(state.filter.view) }
    var type by remember(state.filter) { mutableStateOf(state.filter.type) }; var owner by remember(state.filter) { mutableStateOf(state.filter.owner) }
    var from by remember(state.filter) { mutableLongStateOf(state.filter.from) }; var to by remember(state.filter) { mutableLongStateOf(state.filter.to) }
    var types by remember { mutableStateOf(false) }; var owners by remember { mutableStateOf(false) }
    var dateTarget by remember { mutableStateOf<Boolean?>(null) }
    fun date(value: Long) = if (value <= 0) "不限" else Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault()).toLocalDate().toString()
    dateTarget?.let { start ->
        TaskCenterDatePicker(if (start) from else to, onDay = { picked ->
            if (start) from = picked.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            else to = picked.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1
        }, onDismiss = { dateTarget = null })
    }
    FilewayFilterSheet(
        title = "任务筛选",
        onDismiss = dismiss,
        onReset = { category = TaskFilter().category; view = TaskView.ALL; type = ""; owner = ""; from = 0; to = 0 },
        onApply = { apply(state.filter.copy(category = category, view = view, type = type, owner = owner, from = from, to = to)) },
        applyEnabled = from == 0L || to == 0L || from <= to,
    ) {
            Text("分类", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(category == "file", { category = "file" }, { Text("文件任务", Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp), colors = libraryChipColors())
                FilterChip(category == "background", { category = "background" }, { Text("其他后台任务", Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp), colors = libraryChipColors())
            }
            Text("状态", style = MaterialTheme.typography.labelLarge)
            TaskView.entries.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { option ->
                        val count = if (category == state.filter.category) state.counts[option.name.lowercase(java.util.Locale.ROOT)] else null
                        FilterChip(view == option, { view = option }, { Text(option.label + (count?.let { " $it" } ?: ""), Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp), colors = libraryChipColors())
                    }
                }
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) {
                FilewayFilterField("任务类型", SERVER_TASK_TYPES[type] ?: "全部", onClick = { types = true; owners = false })
                FilewayMenu(types, { types = false }, items = listOf(FilewayMenuEntry("", "全部类型", selected = type.isEmpty())) +
                    SERVER_TASK_TYPES.filterKeys { it !in setOf("file.delete.permanent", "trash.delete.permanent") }.map { (id, label) ->
                        FilewayMenuEntry(id, label, selected = type == id)
                    }, selection = FilewayMenuSelection.Single) { item -> type = item.key; types = false }
            }
            Box(Modifier.weight(1f)) {
                FilewayFilterField("账号", owner.ifEmpty { "全部可见账号" }, onClick = { owners = true; types = false })
                FilewayMenu(owners, { owners = false }, items = listOf(FilewayMenuEntry("", "全部可见账号", selected = owner.isEmpty())) +
                    (listOf(accountName) + state.owners).distinct().filter { it.isNotEmpty() }.map { name ->
                        FilewayMenuEntry(name, name, selected = owner == name)
                    }, selection = FilewayMenuSelection.Single) { item -> owner = item.key; owners = false }
            }
            }
            FilewayDateRangeFields(date(from), date(to), from > 0 || to > 0,
                { dateTarget = true }, { dateTarget = false }, { from = 0; to = 0 },
                invalidRange = from > 0 && to > 0 && from > to)
    }
}
