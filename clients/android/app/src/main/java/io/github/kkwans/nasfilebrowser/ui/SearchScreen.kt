package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.app.FileLayout
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.app.ClientState
import io.github.kkwans.nasfilebrowser.app.FileCategory
import io.github.kkwans.nasfilebrowser.data.SearchEnding
import io.github.kkwans.nasfilebrowser.data.SearchResult
import io.github.kkwans.nasfilebrowser.data.SearchScope
import io.github.kkwans.nasfilebrowser.data.CompactGridSize
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayTheme

@Composable internal fun SearchScreen(model: ClientModel, client: ClientState) {
    val state by model.search.state.collectAsStateWithLifecycle()
    val displayed = remember(state.items, state.category, state.categories) { state.visibleItems }
    val colors = FilewayTheme.colors
    val focus = LocalFocusManager.current
    val onBack = LocalFilewayBack.current ?: { model.back(); Unit }
    var details by remember(state.resultBaseWirePath) { mutableStateOf<SearchResult?>(null) }
    fun submit() { if (!model.state.value.busy) { focus.clearFocus(); model.search.submit() } }
    MaterialTheme(colorScheme = colors) {
        Scaffold(containerColor = colors.background) { insets ->
            Box(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets), contentAlignment = Alignment.TopCenter) {
                FilewayRefresh(state.running, { if (!state.running && !client.busy && state.query.isNotBlank()) model.search.refresh() }) {
Column(Modifier.widthIn(max = 960.dp).fillMaxSize().imePadding()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                            Icon(painterResource(R.drawable.ic_arrow_back), "返回文件", Modifier.size(22.dp), tint = colors.onBackground)
                        }
                        FilewaySearchField(state.query, { if (!model.state.value.busy) model.search.query(it) },
                            "搜索文件名", Modifier.weight(1f), enabled = !client.busy, submit = ::submit)
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        FileCategoryMenu(state.categories.ifEmpty { if (state.category == FileCategory.ALL) emptySet() else setOf(state.category) }, !client.busy, model.search::categories)
                        Spacer(Modifier.weight(1f))
                        FileLayoutMenu(client.fileLayout, enabled = !client.busy, compactGridSize = client.compactGridSize,
                            onCompactGridSize = model::compactGridSize, select = model::fileLayout)
                    }
                    FileCollection(displayed, client.fileLayout, Modifier.weight(1f), "搜索结果",
                        resetKey = listOf(client.previewScope, state.resultBaseWirePath, state.query, state.scope, state.categories, state.category), keyOf = { it.relativePath }, compactGridSize = client.compactGridSize, header = {
                        Column {
                        Column {
                            Row(Modifier.fillMaxWidth().padding(top = 4.dp).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SearchScope.entries.forEach { value ->
                                    val selected = state.scope == value
                                    Column(Modifier.weight(1f).selectable(selected = selected, role = Role.Tab, enabled = !client.busy,
                                        onClick = { if (!model.state.value.busy) model.search.scope(value) }).heightIn(min = 48.dp), verticalArrangement = Arrangement.SpaceBetween,
                                        horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(value.label, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), textAlign = TextAlign.Center,
                                            color = if (selected) colors.primary else colors.onSurfaceVariant,
                                            style = MaterialTheme.typography.bodyLarge, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                                        Box(Modifier.fillMaxWidth().height(2.dp).background(if (selected) colors.primary else Color.Transparent))
                                    }
                                }
                            }
                            HorizontalDivider(color = colors.outlineVariant)
                        }
                        Column {
                            if (state.scope != SearchScope.GLOBAL) Text(state.resultBasePath,
                                Modifier.fillMaxWidth().padding(top = 8.dp), style = FilewayTheme.text.metadata,
                                color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val active = state.running || state.openingPath != null || client.busy
                            Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (active) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.primary)
                                val label = when {
                                    client.busy -> client.stage.ifBlank { "正在加载" }
                                    state.openingPath != null -> "正在确认文件"
                                    state.running -> if (state.items.isEmpty()) "正在搜索" else "已收到 ${state.items.size} 项，正在搜索"
                                    state.ending == SearchEnding.COMPLETED -> "找到 ${state.items.size} 项"
                                    state.items.isNotEmpty() -> "已保留 ${state.items.size} 项"
                                    else -> if (state.scope == SearchScope.GLOBAL) "搜索全部文件" else "搜索这个目录"
                                }
                                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = colors.onBackground)
                                if (active) FilewayTextButton(onClick = { model.search.cancel(); if (client.busy) model.cancel() }) { Text("取消") }
                            }
                            val message = client.error ?: state.message
                            if (message != null) Text(message, style = MaterialTheme.typography.bodyLarge,
                                color = if (state.ending == SearchEnding.FAILED || client.error != null) MaterialTheme.colorScheme.error else colors.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 8.dp))
                            if (!active && state.ending in setOf(SearchEnding.FAILED, SearchEnding.TIMEOUT, SearchEnding.LIMIT, SearchEnding.CANCELED)) {
                                FilewayTextButton(onClick = ::submit) { Text("重新搜索") }
                            }
                            if (!active && state.items.isEmpty() && state.message == null && client.error == null) {
                                Text(if (state.ending == SearchEnding.COMPLETED) "没有匹配的文件。试试更短的关键词或扩大范围。" else if (state.scope == SearchScope.GLOBAL) "输入文件名，查找账号可访问的全部文件。" else "输入文件名，查找这个目录中的文件。",
                                    style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant, modifier = Modifier.padding(bottom = 16.dp))
                            }
                            if (!active && state.items.isNotEmpty() && displayed.isEmpty()) {
                                Text("当前搜索结果中没有此类型的文件。", color = colors.onSurfaceVariant)
                                FilewayTextButton(onClick = { model.search.category(FileCategory.ALL) }) { Text("显示全部结果") }
                            }
                        }
                        }
                    }) { result ->
                        SearchResultRow(model, result, state.resultBasePath, state.resultBaseWirePath, client.fileLayout,
                            state.scope, state.openingPath == result.relativePath, enabled = !client.busy, compactGridSize = client.compactGridSize,
                            open = { if (!model.state.value.busy) { focus.clearFocus(); model.search.openResult(result) } }, details = { details = result })
                    }
                }
}

            }
        }
        details?.let { result ->
            val resource = result.resource(state.resultBasePath, state.resultBaseWirePath)
            if (resource != null) FileDetailsDialog(resource, openEnabled = !client.busy,
                actions = { FileActions(model, resource) { details = null } },
                onLocation = { details = null; model.openContainingDirectory(resource) },
                onOpen = { if (!model.state.value.busy) { details = null; model.search.openResult(result) } }, onDismiss = { details = null })
            else AlertDialog(onDismissRequest = { details = null }, shape = RoundedCornerShape(12.dp), containerColor = colors.background,
                title = { Text("文件详情", style = MaterialTheme.typography.titleLarge) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState()).semantics { contentDescription = "文件详情内容" }, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(result.name, style = MaterialTheme.typography.bodyLarge)
                        Text(state.resultBasePath.trimEnd('/') + "/" + result.relativePath, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                },
                confirmButton = { FilewayTextButton(onClick = { if (!model.state.value.busy) { details = null; model.search.openResult(result) } }, enabled = !client.busy) { Text("打开") } },
                dismissButton = { FilewayTextButton(onClick = { details = null }) { Text("关闭") } })
        }
    }
}

@Composable private fun SearchResultRow(model: ClientModel, result: SearchResult, basePath: String, baseWirePath: String,
    layout: FileLayout, scope: SearchScope, opening: Boolean, enabled: Boolean, compactGridSize: CompactGridSize, open: () -> Unit, details: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val resource = remember(result, basePath, baseWirePath) { result.resource(basePath, baseWirePath) }
    Column(Modifier.fillMaxWidth()) {
        if (resource != null) FileEntry(model, resource, layout,
            enabled, open, details, location = when (scope) {
                SearchScope.CURRENT -> null
                SearchScope.RECURSIVE -> result.relativePath.substringBeforeLast('/', "").ifBlank { null }
                SearchScope.GLOBAL -> resource.path.substringBeforeLast('/', "").ifEmpty { "/" }
            }, compactGridSize = compactGridSize)
        else Column(Modifier.fillMaxWidth().combinedClickable(onClick = open, onLongClick = details,
            onLongClickLabel = "查看完整名称和位置", role = Role.Button, enabled = enabled).padding(vertical = 12.dp)) {
            Text(result.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("文件名编码无法确认", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        if (opening) Row(Modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Text("正在确认文件", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
}
