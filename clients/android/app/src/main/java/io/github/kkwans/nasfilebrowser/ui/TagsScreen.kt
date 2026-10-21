package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.app.*
import io.github.kkwans.nasfilebrowser.app.FileLayout
import io.github.kkwans.nasfilebrowser.data.*

internal fun metadataColor(value: String, fallback: Color): Color = try { Color(android.graphics.Color.parseColor(value)) } catch (_: Exception) { fallback }

@Composable internal fun TagsScreen(model: ClientModel, client: ClientState) {
    val state by model.tags.state.collectAsStateWithLifecycle()
    var chosen by rememberSaveable(state.scope) { mutableStateOf<String?>(null) }
    var currentOnly by rememberSaveable(state.scope) { mutableStateOf(false) }
    var creating by remember(state.scope) { mutableStateOf(false) }
    var editing by remember(state.scope) { mutableStateOf<ServerTag?>(null) }
    var removing by remember(state.scope) { mutableStateOf<ServerTag?>(null) }
    var scopeDetails by remember(state.scope) { mutableStateOf(false) }
    var pathDetails by remember(state.scope) { mutableStateOf<String?>(null) }
    var details by remember(state.scope) { mutableStateOf<ResourceRef?>(null) }
    val tag = state.items.firstOrNull { it.id == chosen }
    val enabled = !state.loading && !state.changing && !client.busy
    LaunchedEffect(state.scope) { model.tags.refresh() }
    LaunchedEffect(state.items) { if (state.items.none { it.id == chosen }) chosen = state.items.firstOrNull()?.id }
    LaunchedEffect(tag, currentOnly, client.path, client.wirePath) { tag?.let { model.tags.loadPaths(it.id, if (currentOnly) client.path else null, parentWire = if (currentOnly) client.wirePath else null) } }
    LibraryScaffold(model, LibrarySection.TAGS, actions = {
        if (currentOnly) IconButton({ scopeDetails = true }) { Icon(painterResource(R.drawable.ic_info), "查看当前目录范围") }
        IconButton({ creating = true }, enabled = enabled && state.loaded) { Icon(painterResource(R.drawable.ic_add), "新建标签") }
    }) {
        LibraryMessage(state.error ?: client.error, state.notice, state.loading || state.changing, model.tags::refresh)
        if (state.loaded && state.items.isEmpty() && !state.loading && state.error == null) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("给文件加上自己的分类", style = MaterialTheme.typography.titleMedium)
                Text("创建标签后，在文件详情中多选标记。颜色和关联会与网页端同步。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                FilewayTextButton({ creating = true }) { Text("新建第一个标签") }
            }
        } else {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = .10f), selectedLabelColor = MaterialTheme.colorScheme.primary)
                state.items.forEach { value -> FilterChip(chosen == value.id, { chosen = value.id }, { Text(value.name) }, colors = colors,
                    leadingIcon = { Box(Modifier.size(8.dp).background(metadataColor(value.color, MaterialTheme.colorScheme.primary), CircleShape)) }) }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                var scopeMenu by remember { mutableStateOf(false) }
                Box {
                    FilewayTextButton({ scopeMenu = true }, enabled = enabled, shape = RoundedCornerShape(12.dp)) {
                        Text(if (currentOnly) "当前目录" else "全部关联")
                        Icon(painterResource(R.drawable.ic_expand_more), null, Modifier.size(18.dp))
                    }
                    FilewayMenu(scopeMenu, { scopeMenu = false }, items = buildList {
                        add(FilewayMenuEntry("all", "全部关联", selected = !currentOnly))
                        add(FilewayMenuEntry("current", "当前目录", selected = currentOnly))
                    }, selection = FilewayMenuSelection.Single, onItemClick = {
                        scopeMenu = false
                        when (it.key) { "all" -> currentOnly = false; "current" -> currentOnly = true }
                    })
                }
                Text("${state.paths.size} / ${state.pathTotal}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                tag?.let { selected ->
                    IconButton({ model.tags.filter(selected.id, !currentOnly); model.tab("files") }, enabled = enabled) {
                        Icon(painterResource(R.drawable.ic_filter), "在文件页筛选此标签")
                    }
                    IconButton({ editing = selected }, enabled = enabled) { Icon(painterResource(R.drawable.ic_edit), "编辑标签") }
                    IconButton({ removing = selected }, enabled = enabled) { Icon(painterResource(R.drawable.ic_trash), "删除标签", tint = MaterialTheme.colorScheme.error) }
                }
            }
            LibraryMessage(state.pathsError, null, state.pathsLoading) { tag?.let { model.tags.loadPaths(it.id, if (currentOnly) client.path else null, parentWire = if (currentOnly) client.wirePath else null) } }
            val candidates = state.paths.mapNotNull { it.file }
            val sourceScope = state.scope
            LazyColumn(Modifier.weight(1f).semantics { contentDescription = "标签关联路径" }, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.paths.isEmpty() && !state.pathsLoading && state.pathsError == null) item { Text(if (currentOnly) "这个目录还没有直接标记的文件。" else "还没有关联路径。从文件详情中添加标记。", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(state.paths, key = { it.key }) { row ->
                    val file = row.file
                    if (file != null) LibraryObjectCard(file.name, libraryLocation(file.path), fileArtworkIcon(file.name, file.directory),
                        enabled = enabled, summary = if (file.directory) null else readableSize(file.size), artwork = if (file.mediaKind() in setOf(MediaKind.IMAGE, MediaKind.VIDEO)) { {
                            MediaThumbnail(model, file, Modifier.size(36.dp).clip(MaterialTheme.shapes.small), showStatusText = false)
                        } } else null, onOpen = {
                            if (file.directory) model.tags.filter(tag?.id, true)
                            model.openTagged(file, candidates, sourceScope)
                        }, trailing = { IconButton({ details = file }, enabled = enabled) { Icon(painterResource(R.drawable.ic_info), "标签文件操作：${file.name}") } })
                    else Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(libraryBasename(row.path), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("位置 · ${libraryParent(row.path)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(row.error.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            Row {
                                FilewayTextButton({ pathDetails = row.path }) { Text("完整路径") }
                                tag?.let { value -> FilewayTextButton({ model.tags.removePath(value, row.ref, sourceScope) }, enabled = enabled && row.ref.identity != null) { Text("取消此关联") } }
                            }
                        }
                    }
                }
                if (state.paths.size < state.pathTotal) item { FilewayTextButton({ tag?.let { model.tags.loadPaths(it.id, if (currentOnly) client.path else null, true, if (currentOnly) client.wirePath else null) } }, enabled = !state.pathsLoading && enabled,
                    modifier = Modifier.fillMaxWidth()) { Text("加载更多关联") } }
            }
        }
    }
    if (scopeDetails) AlertDialog(onDismissRequest = { scopeDetails = false }, title = { Text("当前目录范围") },
        text = { SelectionContainer { Text(client.path) } }, confirmButton = { FilewayTextButton({ scopeDetails = false }) { Text("关闭") } })
    pathDetails?.let { path -> AlertDialog(onDismissRequest = { pathDetails = null }, title = { Text("关联路径") },
        text = { SelectionContainer { Text(path) } }, confirmButton = { FilewayTextButton({ pathDetails = null }) { Text("关闭") } }) }
    if (creating || editing != null) TagEditor(model, editing, dismiss = { creating = false; editing = null }, remove = { removing = editing; editing = null })
    removing?.let { value -> AlertDialog(onDismissRequest = { removing = null }, title = { Text("删除标签“${value.name}”？") },
        text = { Text("这个标签的全部关联将被取消，文件保持原位。网页端也会同步此变更。") },
        confirmButton = { FilewayTextButton({ model.tags.remove(value); removing = null }, enabled = enabled) { Text("删除标签") } },
        dismissButton = { FilewayTextButton({ removing = null }) { Text("取消") } }) }
    details?.let { file -> FileDetailsDialog(file, actions = { FileActions(model, file) { details = null } }, onLocation = { details = null; model.openContainingDirectory(file) }, onDismiss = { details = null }) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun TagEditor(model: ClientModel, tag: ServerTag?, dismiss: () -> Unit, remove: (() -> Unit)? = null) {
    val state by model.tags.state.collectAsStateWithLifecycle()
    var name by remember(tag?.id) { mutableStateOf(tag?.name.orEmpty()) }
    var color by remember(tag?.id) { mutableStateOf(tag?.color ?: TAG_COLORS.firstOrNull { model.tags.colorAvailable(it) } ?: "") }
    val colorValid = color.equals(tag?.color, true) || model.tags.colorAvailable(color, tag?.id)
    AlertDialog(onDismissRequest = { if (!state.changing) dismiss() }, title = { Text(if (tag == null) "新建标签" else "编辑标签") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.changing) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedTextField(name, { name = it }, label = { Text("标签名称") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !state.changing)
            Text("选择标记颜色", style = MaterialTheme.typography.titleSmall)
            if (!colorValid) Text(if (color.isEmpty()) "预设颜色已全部占用，请先调整已有标签的颜色。" else "这个颜色已有标签使用，请选择可用颜色。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            FlowRow {
                TAG_COLORS.forEachIndexed { index, value ->
                    val available = model.tags.colorAvailable(value, tag?.id) || value.equals(tag?.color, true)
                    Box(Modifier.size(48.dp).selectable(value.equals(color, true), enabled = available && !state.changing, role = Role.RadioButton) { color = value }
                        .semantics { contentDescription = "标签颜色 ${index + 1}${if (!available) "，已被使用" else ""}" }, contentAlignment = Alignment.Center) {
                        Box(Modifier.size(28.dp).border(if (value.equals(color, true)) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape).padding(3.dp)
                            .background(metadataColor(value, MaterialTheme.colorScheme.primary).copy(alpha = if (available) 1f else .25f), CircleShape))
                    }
                }
            }
            remove?.let { FilewayTextButton(it, enabled = !state.changing) { Text("删除标签") } }
        }
    }, confirmButton = { FilewayTextButton({ model.tags.save(tag, name, color, onSaved = dismiss) }, enabled = name.isNotBlank() && colorValid && !state.changing) { Text(if (state.changing) "正在保存" else "保存") } },
        dismissButton = { FilewayTextButton(dismiss, enabled = !state.changing) { Text("取消") } })
}
