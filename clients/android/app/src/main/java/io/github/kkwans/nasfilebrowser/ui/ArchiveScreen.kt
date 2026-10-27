package io.github.kkwans.nasfilebrowser.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.app.ArchiveController
import io.github.kkwans.nasfilebrowser.data.*
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayTopAppBar

@Composable internal fun ArchiveScreen(controller: ArchiveController, onBack: () -> Unit, onOpenTasks: () -> Unit,
    onOpenDirectory: (DirectoryCrumb) -> Unit, onOpenEntry: ((ArchiveEntry) -> Unit)? = null) {
    val state by controller.state.collectAsStateWithLifecycle()
    val listing = state.listing
    val displayPrefix = remember(listing, state.prefix) {
        if (state.prefix.isEmpty()) "" else listing?.entries?.firstOrNull {
            it.wirePath == state.prefix || it.wirePath.startsWith(state.prefix + "/")
        }?.path?.split('/')?.take(state.prefix.split('/').size)?.joinToString("/").orEmpty()
    }
    var selecting by remember(state.scope, state.file?.wirePath) { mutableStateOf(false) }
    var details by remember(state.scope, state.file?.wirePath) { mutableStateOf<ArchiveEntry?>(null) }
    var panel by remember(state.scope, state.file?.wirePath) { mutableStateOf<String?>(null) }
    var review by remember(state.scope, state.file?.wirePath) { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    val busy = state.loading || state.submitting
    val list = rememberLazyListState()
    LaunchedEffect(state.prefix, state.query) { list.scrollToItem(0) }
    LaunchedEffect(state.task?.id) {
        if (state.task != null) { review = false; if (!selecting) controller.clearSelection() }
    }
    fun leaveSelection() { selecting = false; controller.clearSelection() }
    fun back() {
        when {
            state.submitting -> Unit
            selecting -> leaveSelection()
            state.query.isNotEmpty() -> controller.search("")
            state.prefix.isNotEmpty() -> controller.up()
            else -> onBack()
        }
    }
    BackHandler { back() }
    fun extract(entry: ArchiveEntry? = null) {
        controller.clearSelection()
        if (entry != null) controller.toggle(entry)
        else if (state.query.isNotBlank()) controller.selectRows() else controller.selectDirectory()
        if (controller.state.value.selected.isNotEmpty()) review = true
    }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
        Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
            FilewayTopAppBar(state.file?.name ?: "压缩包浏览", listing?.let { "${it.format.uppercase()} · ${it.entries.size} 项 · ${readableSize(it.sourceSize)}" },
                R.drawable.ic_arrow_back, "返回文件", ::back, actions = {
                FilewayIconButton(controller::refresh, enabled = !busy && state.task?.active != true && !state.reportLoading) {
                    FilewayIcon(R.drawable.ic_refresh, "刷新压缩包")
                }
                FilewayIconButton({ if (selecting) leaveSelection() else selecting = true }, enabled = !state.submitting && listing != null) {
                    FilewayIcon(if (selecting) R.drawable.ic_close else R.drawable.ic_select_multiple, if (selecting) "退出多选" else "多选包内文件")
                }
                Box {
                    FilewayIconButton({ more = true }) { FilewayIcon(R.drawable.ic_more_actions, "更多压缩包操作") }
                    FilewayMenu(more, { more = false }, items = buildList {
                        add(FilewayMenuEntry("info", "压缩包信息", R.drawable.ic_info))
                        if (listing != null && listing.blockedCount > 0) add(FilewayMenuEntry("blocked", "已阻止 ${listing.blockedCount} 项", R.drawable.ic_info))
                        if (state.task != null) add(FilewayMenuEntry("task", "解压任务", R.drawable.ic_tasks))
                    }) { item -> more = false; panel = item.key }
                }
            })
        }
    }, bottomBar = {
        if (listing != null) Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxWidth().imePadding().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))) {
                if (selecting) {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("已选 ${state.selectedEntries.size} 项", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        FilewayIconButton({ controller.selectRows() }, enabled = !busy) { FilewayIcon(R.drawable.ic_select_all, "全选当前结果") }
                        FilewayIconButton({ controller.selectRows(invert = true) }, enabled = !busy) { FilewayIcon(R.drawable.ic_invert_selection, "反选当前结果") }
                    }
                }
                FilewayButton({ if (selecting) review = true else extract() }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    enabled = state.canExtract && !busy && !listing.truncated && !state.unknownSubmission && !state.reportLoading && state.task?.active != true &&
                        (if (selecting) state.selectedEntries.isNotEmpty() else state.rows.isNotEmpty())) {
                    FilewayIcon(R.drawable.ic_archive, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                    Text(if (selecting) "解压所选项目" else if (state.query.isNotBlank()) "解压搜索结果" else if (state.prefix.isEmpty()) "解压全部" else "解压当前目录")
                }
            }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            if (listing != null) {
                FilewaySearchField(state.query, controller::search, "搜索包内路径", Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    enabled = !state.submitting, submit = {})
                DirectoryBreadcrumbs("/" + displayPrefix, "/" + state.prefix, state.submitting, controller::up) { crumb ->
                    val target = crumb.wirePath?.removePrefix("/") ?: return@DirectoryBreadcrumbs
                    controller.navigate(target)
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.error?.let { message -> item(key = "error") {
                    Text(message, Modifier.padding(horizontal = 4.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    if (!busy && !state.unknownSubmission) FilewayTextButton(controller::refresh) { Text("重试读取") }
                } }
                if (state.unknownSubmission) item(key = "unknown") {
                    Text("请先核对解压任务，避免重复提交。", style = MaterialTheme.typography.bodyMedium)
                    Row {
                        FilewayOutlinedButton(onOpenTasks) { Text("查看任务") }
                        FilewayTextButton(controller::acknowledgeSubmission) { Text("已核对未创建") }
                    }
                }
                if (listing?.truncated == true) item(key = "truncated") {
                    Text("${listing.limitReason}。完整范围无法确认，不会提交解压。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                if (state.task != null) item(key = "task") {
                    FilewayTaskCard("解压任务", { FilewayIcon(R.drawable.ic_archive, null, tint = MaterialTheme.colorScheme.primary) },
                        meta = { Text(state.task!!.statusLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        actions = { FilewayIcon(R.drawable.ic_arrow_forward, null) }, onClick = { panel = "task" })
                }
                if (listing != null && state.rows.isEmpty()) item(key = "empty") {
                    Text(if (state.query.isBlank()) "这个目录没有可浏览的条目" else "没有匹配的包内路径", Modifier.fillMaxWidth().padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(state.rows, key = { "entry/" + it.wirePath }) { entry ->
                    val chosen = archiveSelectionContains(state.selected, entry.wirePath)
                    FilewayTaskCard(entry.name, { FilewayIcon(fileArtworkIcon(entry.name, entry.isDir), null, tint = MaterialTheme.colorScheme.primary) },
                        meta = {
                            Text(if (entry.isDir) "文件夹" else readableSize(entry.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }, selected = chosen, longClickLabel = "多选包内文件",
                        titleSupporting = if (state.query.isNotBlank()) ({ Text(entry.path.substringBeforeLast('/', "").ifEmpty { "压缩包根目录" },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }) else null,
                        onClick = { if (!state.submitting) { if (selecting) controller.toggle(entry) else details = entry } },
                        onLongClick = { if (!state.submitting) { selecting = true; controller.toggle(entry) } },
                        onDoubleClick = if (selecting || state.submitting || !entry.isDir && onOpenEntry == null) null else ({ if (entry.isDir) controller.enter(entry) else onOpenEntry?.invoke(entry) }),
                        actions = { if (!selecting) FilewayIconButton({ details = entry }, enabled = !state.submitting) { FilewayIcon(R.drawable.ic_more_actions, "包内条目详情：${entry.name}") } })
                }
            }
        }
    }
    details?.let { entry -> ArchiveEntryDetails(entry, state, { details = null },
        open = if (entry.isDir) ({ details = null; controller.enter(entry) }) else onOpenEntry?.let { callback -> { details = null; callback(entry) } },
        extract = { details = null; extract(entry) }) }
    if (review && !state.pickingDestination) ArchiveExtractSheet(controller, {
        review = false
        if (!selecting) controller.clearSelection()
    }, onOpenTasks)
    if (state.pickingDestination) ArchiveDestinationPicker(controller)
    when (panel) {
        "info" -> ArchiveInformation(state, { panel = null }, onOpenDirectory)
        "blocked" -> listing?.let { ArchiveEntryReport("已阻止 ${it.blockedCount} 项", it.blocked, { panel = null }) }
        "task" -> ArchiveTaskSheet(controller, { panel = null }, onOpenTasks, onOpenDirectory)
    }
}
