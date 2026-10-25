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
    val selection = rememberFilewayTaskSelection(state.scope to state.filter, state.items.map { it.id }, complete = state.nextCursor.isEmpty())
    val chosen = state.items.filter { it.id in selection.ids }
    var deleteSelection by remember(state.scope, state.filter) { mutableStateOf(false) }
    TaskCenterHeaderActions {
        FilewaySelectionToggle(selection, "多选后台任务", enabled && state.items.isNotEmpty())
        val singleBulk = when (state.filter.view) {
            TaskView.ARCHIVED -> "batch-unarchive"
            TaskView.COMPLETED, TaskView.CANCELED -> "batch-archive"
            TaskView.ALL -> "archive-ended"
            TaskView.ATTENTION -> "batch-archive"
            else -> null
        }
        if (singleBulk != null) IconButton({ confirmAction(null, singleBulk) }, enabled = enabled && state.total > 0) {
            Icon(painterResource(if (singleBulk == "batch-unarchive") R.drawable.ic_restore else R.drawable.ic_trash),
                if (singleBulk == "batch-unarchive") "恢复全部记录" else "删除全部已结束任务记录", Modifier.size(24.dp),
                tint = if (singleBulk == "batch-unarchive") MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
        }
        if (state.filter.view == TaskView.ATTENTION && state.filter.type.isNotEmpty()) IconButton({ confirmAction(null, "batch-retry") }, enabled = enabled && state.total > 0) {
            Icon(painterResource(R.drawable.ic_refresh), "批量重试", Modifier.size(24.dp))
        }
    }
    LaunchedEffect(list, state.scope) { snapshotFlow { list.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? String }.toSet() }.collect(model.tasks::watch) }
    FilewayRefresh(state.loading, { model.tasks.refresh() }) {
Column(Modifier.fillMaxSize()) {
        if (selection.active) FilewayTaskSelectionBar(selection, enabled, {
            if (selection.ids.size == state.total) selection.select(emptyList()) else model.tasks.selectAll { selection.select(it) }
        }, onInvert = { val before = selection.ids; model.tasks.selectAll { selection.select(it - before) } }) {
            if (state.filter.view == TaskView.ARCHIVED) IconButton({ model.tasks.actionSelected(selection.ids, "unarchive") }, enabled = enabled && chosen.isNotEmpty() && chosen.all { model.tasks.canEdit(it) && it.archivedAt > 0 }) {
                Icon(painterResource(R.drawable.ic_restore), "恢复所选任务记录")
            } else IconButton({ if (chosen.size == state.total) deleteSelection = true else model.tasks.actionSelected(selection.ids, "archive") },
                enabled = enabled && chosen.isNotEmpty() && chosen.all { it.canArchive && model.tasks.canEdit(it) }) {
                Icon(painterResource(R.drawable.ic_trash), "删除所选已结束任务记录")
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilewaySearchField(search, { search = it }, "查找任务", Modifier.weight(1f), enabled) {
                model.tasks.filter(state.filter.copy(text = search.trim())); keyboard?.hide()
            }
            IconButton({ advanced = true }, enabled = enabled) { Icon(painterResource(R.drawable.ic_filter), "筛选任务") }

        }
        LibraryMessage(state.error, state.notice, state.loading || state.changing) { model.tasks.refresh() }
        LazyColumn(Modifier.weight(1f).semantics { contentDescription = "服务端任务列表" }, state = list, contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.items.isEmpty() && !state.loading && state.error == null) item {
            Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                Text("没有匹配的任务，可调整筛选条件", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
            val groups = listOf(true to "文件任务", false to "其他任务")
            for ((fileGroup, label) in groups) {
            val rows = state.items.filter { it.fileCategory == fileGroup }
            if (rows.isNotEmpty()) item(key = if (fileGroup) 1 else 2) {
                Text(label, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(rows, key = { it.id }) { task ->
                val cardActions = buildList {
                    if (task.active) add(FilewayTaskAction(if (task.pendingDeletion) "unarchive" else "cancel",
                        if (task.pendingDeletion) "撤销删除" else "停止任务", R.drawable.ic_close, enabled = enabled && model.tasks.canEdit(task), primary = true))
                    if (task.canRetry && !task.canArchive) add(FilewayTaskAction("retry", "重试", R.drawable.ic_refresh, enabled = enabled && model.tasks.canEdit(task), primary = true))
                    if (task.canArchive) add(FilewayTaskAction("archive", "删除记录", R.drawable.ic_trash, enabled = enabled && model.tasks.canEdit(task), primary = true))
                    if (task.archivedAt > 0) add(FilewayTaskAction("unarchive", "恢复记录", R.drawable.ic_restore, enabled = enabled && model.tasks.canEdit(task), primary = true))
                }
                FilewayTaskCard(
                    title = task.title.ifEmpty { task.typeLabel },
                    icon = { Icon(painterResource(serverTaskIcon(task)), null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary) },
                    meta = {
                        TaskRecordMeta(task.statusLabel, if (task.status == "completed") task.finishedAt else task.createdAt,
                            if (task.status in setOf("failed", "interrupted")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    actions = {
                        if (!selection.active) FilewayTaskActionStrip(cardActions, onAction = { command ->
                            when {
                                command == "detail" -> model.tasks.select(task.id)
                                command == "unarchive" && task.pendingDeletion -> model.tasks.action(task, "cancel")
                                command == "archive" || command == "unarchive" -> model.tasks.action(task, command)
                                else -> confirmAction(task, command)
                            }
                        }, moreLabel = "任务操作：${task.title.ifEmpty { task.typeLabel }}")
                    },
                    onDoubleClick = if (!selection.active && task.status == "completed" && task.outputPath.startsWith('/')) ({ model.openRemotePath(task.outputPath) }) else null,
                    onClick = { if (selection.active) selection.toggle(task.id) else model.tasks.select(task.id) },
                    onLongClick = { if (enabled) selection.start(task.id) }, selected = task.id in selection.ids,
                ) {
                    if (task.active || task.status != "completed" && (task.processedItems > 0 || task.processedBytes > 0 || task.processedSeconds > 0)) TaskProgress(task)
                    task.error.takeIf { it.isNotEmpty() && !(task.status == "canceled" && it in setOf("任务已取消", "task canceled", "context canceled")) }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            }
            if (state.nextCursor.isNotEmpty()) item { FilewayTextButton({ model.tasks.refresh(more = true) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(if (state.paging) "正在加载" else "加载更多任务") } }
        }
    }
}

    if (deleteSelection) FilewayAlertDialog(onDismissRequest = { deleteSelection = false }, title = { Text("删除当前列表的全部已结束记录？") },
        text = { Text("将删除 ${chosen.size} 条任务记录，文件和运行结果保留。记录可从“已删除”中恢复。") },
        confirmButton = { FilewayButton({ model.tasks.actionSelected(selection.ids, "archive"); deleteSelection = false }, enabled = enabled) { Text("删除记录") } },
        dismissButton = { FilewayOutlinedButton({ deleteSelection = false }) { Text("取消") } })
    if (advanced) TaskFilterSheet(state, client.accountName, { model.tasks.filter(it); search = it.text; advanced = false }) { advanced = false }
    if (state.focusId != null && LocalFilewayPageActive.current) {
        val task = state.selected
        val detailEnabled = enabled && !state.detailLoading && state.detailError == null
        val buttons = buildList {
            if (task != null) {
                if (task.type in setOf("analysis.storage", "analysis.duplicates")) add(FilewayMenuEntry("report", if (task.status == "completed") "分析报告" else "分析进度", R.drawable.ic_storage, enabled = !client.busy))
                if (task.status == "completed" && task.outputPath.startsWith('/')) add(FilewayMenuEntry("output", "打开输出", R.drawable.ic_visibility, enabled = !client.busy))
                if (task.active) add(FilewayMenuEntry("cancel", if (task.pendingDeletion) "撤销删除" else "停止任务", R.drawable.ic_close, enabled = detailEnabled && model.tasks.canEdit(task)))
                if (task.canRetry) add(FilewayMenuEntry("retry", "重试", R.drawable.ic_refresh, enabled = detailEnabled && model.tasks.canEdit(task)))
                if (task.canArchive) add(FilewayMenuEntry("archive", "删除记录", R.drawable.ic_trash, enabled = detailEnabled && model.tasks.canEdit(task), destructive = true))
                if (task.archivedAt > 0) add(FilewayMenuEntry("unarchive", "恢复记录", R.drawable.ic_restore, enabled = detailEnabled && model.tasks.canEdit(task)))
            }
        }
        FileOperationSheet("任务详情", model::closeTaskDetail, stableHeight = true, footer = if (buttons.isEmpty()) {
            if (state.detailLoading) ({ Box(Modifier.fillMaxWidth().height(64.dp), contentAlignment = Alignment.Center) { Text("正在读取可用操作", color = MaterialTheme.colorScheme.onSurfaceVariant) } }) else null
        } else {{
            FilewaySheetActions(buttons) { action -> if (task != null) when (action) {
                "report" -> { model.tasks.closeDetail(); model.showAnalysis(task.id, task.type) }
                "output" -> { model.tasks.closeDetail(); model.openRemotePath(task.outputPath) }
                "archive", "unarchive" -> { model.tasks.action(task, action); model.tasks.closeDetail() }
                "cancel" -> if (task.pendingDeletion) model.tasks.action(task, "cancel") else confirmAction(task, action)
                else -> confirmAction(task, action)
            } }
        }}) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                state.detailError?.let { Text(it, color = MaterialTheme.colorScheme.error); FilewayOutlinedButton({ state.focusId?.let(model.tasks::select) }) { Text("重新读取") } }
                if (task == null) {
                    val placeholder = if (state.detailLoading) "读取中…" else "未提供"
                    Text("任务信息", style = MaterialTheme.typography.titleMedium)
                    Text(if (state.detailLoading) "读取中…" else "暂时无法读取", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        DetailMetadata("创建时间", placeholder, Modifier.weight(1f))
                        DetailMetadata("结束时间", placeholder, Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        DetailMetadata("处理大小", placeholder, Modifier.weight(1f))
                        DetailMetadata("处理项目", placeholder, Modifier.weight(1f))
                    }
                }
                task?.let {
                    Text(task.title.ifEmpty { task.typeLabel }, style = MaterialTheme.typography.titleMedium)
                    Text("${task.typeLabel} · ${task.statusLabel}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (task.active) TaskProgress(task)
                    if (task.sourcePath.isNotEmpty()) FileOperationLocation("来源", task.sourcePath, showBasename = false)
                    if (task.outputPath.isNotEmpty()) FileOperationLocation("输出", task.outputPath, showBasename = false)
                    if (task.error.isNotEmpty()) Text(task.error, color = MaterialTheme.colorScheme.error)
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        DetailMetadata("创建时间", taskTimestamp(task.createdAt), Modifier.weight(1f))
                        DetailMetadata(if (task.finishedAt > 0) "结束时间" else "开始时间", taskTimestamp(if (task.finishedAt > 0) task.finishedAt else task.startedAt), Modifier.weight(1f))
                    }
                    if (task.totalBytes > 0 || task.totalItems > 0) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (task.totalBytes > 0) DetailMetadata("处理大小", "${readableSize(task.processedBytes)} / ${readableSize(task.totalBytes)}", Modifier.weight(1f))
                        if (task.totalItems > 0) DetailMetadata("处理项目", "${task.processedItems} / ${task.totalItems}", Modifier.weight(1f))
                    }
                    if (!task.active && task.duration > 0) DetailMetadata("处理时长", "${clock((task.processedSeconds * 1000).toLong())} / ${clock((task.duration * 1000).toLong())}")
                    FilewayTextButton({ technicalDetails = !technicalDetails }) {
                        Icon(painterResource(R.drawable.ic_info), null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text(if (technicalDetails) "收起技术信息" else "技术信息")
                    }
                    if (technicalDetails) SelectionContainer { Text("任务 ID：${task.id}\n创建：${taskTimestamp(task.createdAt)}\n开始：${taskTimestamp(task.startedAt)}\n结束：${taskTimestamp(task.finishedAt)}", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
    confirm?.let { choice ->
        val task = choice.task; val command = choice.command
        val batch = command.startsWith("batch-")
        val title = when (command) { "cancel" -> if (task?.pendingDeletion == true) "撤销删除任务？" else "停止这个任务？"; "retry", "batch-retry" -> "创建重试任务？"; "batch-unarchive", "unarchive" -> "恢复记录？"; else -> "删除全部已结束任务记录？" }
        FilewayAlertDialog(onDismissRequest = { confirm = null }, title = { Text(title) }, text = { Text(when {
            command == "archive-ended" -> "删除${if (choice.filter.category.isEmpty()) "全部分类" else "当前分类"}中我的所有已结束记录，包括筛选条件外的记录。文件保留，记录可从“已删除”恢复。"
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
    task.type == "file.copy" -> R.drawable.ic_copy
    task.type == "file.move" -> R.drawable.ic_move
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
                FilterChip(category.isEmpty(), { category = "" }, { Text("全部") }, modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp), colors = libraryChipColors())
                FilterChip(category == "file", { category = "file" }, { Text("文件任务", Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp), colors = libraryChipColors())
                FilterChip(category == "background", { category = "background" }, { Text("其他任务", Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp), colors = libraryChipColors())
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
