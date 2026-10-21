package io.github.kkwans.nasfilebrowser.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.app.DocumentPreviewController
import io.github.kkwans.nasfilebrowser.app.DocumentPreviewState
import io.github.kkwans.nasfilebrowser.app.ResourceRef
import io.github.kkwans.nasfilebrowser.app.FileCategory
import io.github.kkwans.nasfilebrowser.app.mediaKey
import io.github.kkwans.nasfilebrowser.data.*
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayTopAppBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable internal fun DocumentPreviewScreen(controller: DocumentPreviewController, onClose: () -> Unit,
    onDownload: (ResourceRef) -> Unit, canDownload: Boolean, onOpenExternal: ((ResourceRef) -> Unit)? = null,
    onEdit: ((ResourceRef, DocumentText) -> Unit)? = null, imageCache: CacheController? = null) {
    val state by controller.state.collectAsStateWithLifecycle()
    val file = state.file ?: return
    val view = LocalView.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    var menu by remember(state.scope, file.wirePath) { mutableStateOf(false) }
    var info by remember(state.scope, file.wirePath) { mutableStateOf(false) }
    var inputFocused by remember(state.scope, file.wirePath) { mutableStateOf(false) }
    fun leave() {
        if (inputFocused || ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true) {
            focus.clearFocus(); keyboard?.hide(); return
        }
        controller.close(); onClose()
    }
    BackHandler { leave() }
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets).imePadding()) {
            val summary = if (state.loading) "读取中" else if (state.kind == DocumentPreviewKind.TEXT) null else "${fileTypeLabel(file)} · 只读"
            FilewayTopAppBar(file.name, summary, R.drawable.ic_arrow_back, "关闭文档", ::leave, actions = {
                Box {
                    DocumentIcon(R.drawable.ic_more_vert, "文档操作") { focus.clearFocus(); keyboard?.hide(); menu = true }
                    DropdownMenu(menu, { menu = false }) {
                        if (onEdit != null && state.text != null) DocumentMenuAction(R.drawable.ic_edit, "编辑文本", enabled = !state.loading) {
                            menu = false; state.text?.let { onEdit(file, it) }
                        }
                        DocumentMenuAction(R.drawable.ic_refresh, "重新读取文档", enabled = !state.loading) { menu = false; controller.retry() }
                        DocumentMenuAction(R.drawable.ic_download, "下载文档", enabled = canDownload) { menu = false; onDownload(file) }
                        DocumentMenuAction(R.drawable.ic_info, "文档信息") { menu = false; info = true }
                        if (onOpenExternal != null) DocumentMenuAction(R.drawable.ic_arrow_forward, "使用系统应用打开已下载文件") { menu = false; onOpenExternal(file) }
                    }
                }
            })
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { message ->
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("无法打开文档", style = MaterialTheme.typography.titleMedium)
                    Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilewayTextButton(controller::retry, enabled = !state.loading) { Text("重试") }
                        FilewayTextButton({ onDownload(file) }, enabled = canDownload) { Text("下载后打开") }
                    }
                }
            }
            if (state.loading && state.text == null && state.pageCount == 0) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(if (state.kind == DocumentPreviewKind.PDF) "正在准备 PDF…" else "正在读取文档…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else when (state.kind) {
                DocumentPreviewKind.TEXT -> if (state.text != null) DocumentTextContent(controller, state, imageCache) { inputFocused = it }
                DocumentPreviewKind.PDF -> if (state.pageCount > 0) DocumentPdfContent(controller, state)
                DocumentPreviewKind.OTHER -> Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("文件详情", style = MaterialTheme.typography.titleMedium)
                    SelectionContainer { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(file.name, style = MaterialTheme.typography.bodyLarge)
                        Text(file.path, style = MaterialTheme.typography.bodyMedium)
                        Text("${fileTypeLabel(file)} · ${readableSize(file.size)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(displayModified(file.modified) ?: "未提供修改时间", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } }
                    Text("此类型暂不支持内置阅读，可下载后使用系统应用打开。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FilewayButton({ onDownload(file) }, enabled = canDownload) { Text("下载文件") }
                }
            }
        }
    }
    if (info) FileOperationSheet("文档信息", { info = false }, footer = { FilewayTextButton({ info = false }) { Text("关闭") } }) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(file.name, style = MaterialTheme.typography.titleMedium)
                    Text(file.path, style = MaterialTheme.typography.bodyMedium)
                    Text("${fileTypeLabel(file)} · ${readableSize(file.size)}", style = MaterialTheme.typography.bodySmall)
                    Text(displayModified(file.modified) ?: "未提供修改时间", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable private fun DocumentMenuAction(icon: Int, label: String, enabled: Boolean = true, click: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, onClick = click, enabled = enabled,
        leadingIcon = { Icon(painterResource(icon), null, Modifier.size(22.dp)) },
        modifier = Modifier.heightIn(min = 48.dp).clearAndSetSemantics {
            contentDescription = label
            role = Role.Button
            if (enabled) onClick { click(); true } else disabled()
        })
}

@Composable private fun DocumentIcon(icon: Int, label: String, enabled: Boolean = true, click: () -> Unit) {
    IconButton(onClick = click, enabled = enabled, modifier = Modifier.size(48.dp).clearAndSetSemantics {
        contentDescription = label
        role = Role.Button
        if (enabled) onClick { click(); true } else disabled()
    }) { Icon(painterResource(icon), null) }
}

@Composable private fun ColumnScope.DocumentTextContent(controller: DocumentPreviewController, state: DocumentPreviewState, imageCache: CacheController?, onInputFocus: (Boolean) -> Unit) {
    val document = state.text ?: return
    val file = state.file ?: return
    if (isCsvDocument(file)) {
        DocumentCsvContent(controller, state, onInputFocus)
        return
    }
    if (!isMarkdownDocument(file)) {
        DocumentSourceContent(controller, state, onInputFocus)
        return
    }
    // Re-key the entire reading tree so no old account/file result is displayed
    // while cancellation of an earlier parse is still propagating.
    key(state.scope, file.mediaKey, document) {
        var sourceMode by rememberSaveable { mutableStateOf(false) }
        var outlineOpen by rememberSaveable { mutableStateOf(false) }
        val modeState = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
        val preview by produceState<MarkdownPreview?>(null) {
            value = withContext(Dispatchers.Default) { markdownPreview(document) }
        }
        val fallback = preview as? MarkdownPreview.SourceOnly
        val showSource = sourceMode || fallback != null
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !showSource, onClick = { sourceMode = false }, enabled = fallback == null, label = { Text("阅读") })
            FilterChip(selected = showSource, onClick = { outlineOpen = false; sourceMode = true }, label = { Text("源码") })
            if (!showSource) Text("${document.encoding} · 只读", Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!showSource) FilewayTextButton(onClick = { outlineOpen = true },
                enabled = (preview as? MarkdownPreview.Reading)?.document?.navigation?.headings?.isNotEmpty() == true,
                modifier = Modifier.heightIn(min = 48.dp)) { Text("目录") }
        }
        fallback?.let { Text(it.reason, Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (showSource) modeState.SaveableStateProvider("markdown-source") { DocumentSourceContent(controller, state, onInputFocus) }
        else modeState.SaveableStateProvider("markdown-reading") {
            val reading = preview as? MarkdownPreview.Reading
            if (reading == null) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                val context = LocalContext.current
                var linkError by remember { mutableStateOf<String?>(null) }
                linkError?.let { Text(it, Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                val images = remember(controller, state.scope, file, document) { controller.imageBinding(file, document, state.scope) }
                MarkdownBody(reading.document, onOpenLink = { destination ->
                    val intent = markdownExternalIntent(destination)
                    if (intent == null) linkError = "此链接暂不支持打开。"
                    else try { context.startActivity(intent); linkError = null }
                    catch (_: Exception) { linkError = "没有可打开此链接的应用，可在源码中复制地址。" }
                }, modifier = Modifier.weight(1f).fillMaxWidth(), imageBinding = images, imageCache = imageCache, imageSource = file, outlineOpen = outlineOpen, onDismissOutline = { outlineOpen = false })
            }
        }
    }
}

@Composable private fun ColumnScope.DocumentCsvContent(controller: DocumentPreviewController, state: DocumentPreviewState, onInputFocus: (Boolean) -> Unit) {
    val document = state.text ?: return
    val file = state.file ?: return
    key(state.scope, file.mediaKey, document) {
        var sourceMode by rememberSaveable { mutableStateOf(false) }
        val tableState = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = !sourceMode, onClick = { sourceMode = false }, label = { Text("表格") }, modifier = Modifier.heightIn(min = 48.dp))
            FilterChip(selected = sourceMode, onClick = { sourceMode = true }, label = { Text("源码") }, modifier = Modifier.heightIn(min = 48.dp))
            if (!sourceMode) Text("${document.encoding} · 只读", Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (sourceMode) tableState.SaveableStateProvider("csv-source") { DocumentSourceContent(controller, state, onInputFocus) }
        else tableState.SaveableStateProvider("csv-table") {
            CsvReader(document, file.name, { sourceMode = true }, Modifier.weight(1f).fillMaxWidth())
        }
    }
}

@Composable private fun ColumnScope.DocumentSourceContent(controller: DocumentPreviewController, state: DocumentPreviewState, onInputFocus: (Boolean) -> Unit) {
    val document = state.text ?: return
    val clipboard = LocalClipboardManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val findFocus = remember { FocusRequester() }
    DisposableEffect(Unit) { onDispose { onInputFocus(false) } }
    var findOpen by rememberSaveable(state.scope, state.file?.wirePath) { mutableStateOf(false) }
    var copied by remember(document) { mutableStateOf(false) }
    var copyError by remember(document) { mutableStateOf(false) }
    val list = key(state.scope, state.file?.wirePath) { rememberLazyListState() }
    val active = state.search.matches.getOrNull(state.matchIndex)
    var matchOffset by remember(active, document) { mutableStateOf<Int?>(null) }
    LaunchedEffect(active, document, findOpen) {
        if (findOpen && active != null) {
            val index = document.blocks.indexOfLast { it.start <= active.start }
            if (index >= 0) list.scrollToItem(index)
        }
    }
    LaunchedEffect(active, matchOffset, document, findOpen) {
        if (findOpen && active != null && matchOffset != null) {
            val index = document.blocks.indexOfLast { it.start <= active.start }
            if (index >= 0) list.scrollToItem(index, matchOffset!!)
        }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("${document.encoding} · 只读", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FilewayTextButton({
            try { clipboard.setText(AnnotatedString(document.text)); copied = true; copyError = false }
            catch (_: Exception) { copied = false; copyError = true }
        }, enabled = document.text.length <= 131_072) { Text(if (copied) "已复制全文" else "复制全文") }
        DocumentIcon(if (findOpen) R.drawable.ic_close else R.drawable.ic_search,
            if (findOpen) "关闭文档查找" else "查找文档文字") {
            findOpen = !findOpen
            if (!findOpen) { focus.clearFocus(); keyboard?.hide() }
        }
    }
    if (copyError) Text("无法复制全文，请长按选择需要的文字。", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    if (document.text.length > 131_072) Text("全文较长，请长按选择需要的文字后复制。", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (findOpen) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        LaunchedEffect(Unit) { findFocus.requestFocus(); keyboard?.show() }
        OutlinedTextField(state.query, controller::search, Modifier.weight(1f).focusRequester(findFocus).onFocusChanged { onInputFocus(it.isFocused) }, label = { Text("查找文档文字") }, singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { controller.nextMatch(1) }))
        IconButton({ controller.nextMatch(-1) }, enabled = state.search.matches.isNotEmpty(), modifier = Modifier.size(48.dp)) { Icon(painterResource(R.drawable.ic_arrow_back), "上一个匹配") }
        IconButton({ controller.nextMatch(1) }, enabled = state.search.matches.isNotEmpty(), modifier = Modifier.size(48.dp)) { Icon(painterResource(R.drawable.ic_arrow_forward), "下一个匹配") }
    }
    if (findOpen && state.query.isNotEmpty()) Text(state.searchError ?: if (state.searching) "正在查找…" else if (state.search.matches.isEmpty()) "没有匹配文字"
        else "${state.matchIndex + 1} / ${state.search.matches.size}${if (state.search.truncated) "+" else ""} 处匹配",
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val highlight = MaterialTheme.colorScheme.primaryContainer
    val selected = MaterialTheme.colorScheme.tertiaryContainer
    val file = state.file
    val monospace = file != null && (FileCategory.CODE.matches(file.name, false) || FileCategory.CONFIG.matches(file.name, false) || isMarkdownDocument(file) || isCsvDocument(file))
    val sourceStyle = if (monospace) MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace) else MaterialTheme.typography.bodyLarge
    SelectionContainer(Modifier.weight(1f).fillMaxWidth()) {
        LazyColumn(Modifier.fillMaxSize().semantics { contentDescription = "文档文本内容" }, state = list,
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (document.text.isEmpty()) item { Text("文件内容为空", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            itemsIndexed(document.blocks, key = { _, block -> block.start }) { _, block ->
                val value = remember(block, state.search, active, highlight, selected, findOpen) {
                    AnnotatedString.Builder(block.text.removeSuffix("\n")).apply {
                        if (findOpen) state.search.matches.forEach { match ->
                            val from = (match.start - block.start).coerceIn(0, length)
                            val to = (match.end - block.start).coerceIn(0, length)
                            if (from < to) addStyle(SpanStyle(background = if (match == active) selected else highlight), from, to)
                        }
                    }.toAnnotatedString()
                }
                Text(value, Modifier.fillMaxWidth(), style = sourceStyle, onTextLayout = { layout ->
                    if (findOpen && active != null && active.start >= block.start && active.start < block.start + block.text.length && value.isNotEmpty())
                        matchOffset = layout.getBoundingBox((active.start - block.start).coerceAtMost(value.lastIndex)).top.toInt().coerceAtLeast(0)
                })
            }
        }
    }
}

@Composable private fun ColumnScope.DocumentPdfContent(controller: DocumentPreviewController, state: DocumentPreviewState) {
    var jumpOpen by remember(state.scope, state.file?.wirePath) { mutableStateOf(false) }
    var pageInput by remember(state.page, state.pageCount) { mutableStateOf((state.page + 1).toString()) }
    val keyboard = LocalSoftwareKeyboardController.current
    val validPage = pageInput.toIntOrNull()?.takeIf { it in 1..state.pageCount }
    fun jump() {
        validPage?.let { controller.selectPage(it - 1); keyboard?.hide(); jumpOpen = false }
    }
    val borrowed by produceState<DocumentBitmapLease?>(null, state.pageImage) {
        val lease = state.pageImage?.borrow()
        value = lease
        awaitDispose { lease?.close() }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        IconButton({ controller.selectPage(state.page - 1) }, enabled = state.page > 0, modifier = Modifier.size(48.dp)) { Icon(painterResource(R.drawable.ic_arrow_back), "上一页 PDF") }
        FilewayTextButton({ jumpOpen = true }, Modifier.weight(1f).heightIn(min = 48.dp).semantics { contentDescription = "跳转 PDF 页码" }) {
            Text("${state.page + 1} / ${state.pageCount}", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton({ controller.selectPage(state.page + 1) }, enabled = state.page + 1 < state.pageCount, modifier = Modifier.size(48.dp)) { Icon(painterResource(R.drawable.ic_arrow_forward), "下一页 PDF") }
        IconButton({ controller.zoom(state.zoom - 25) }, enabled = state.zoom > 50, modifier = Modifier.size(48.dp).semantics { contentDescription = "缩小 PDF" }) { Text("−", style = MaterialTheme.typography.titleLarge) }
        Text("${state.zoom}%", style = MaterialTheme.typography.bodySmall)
        IconButton({ controller.zoom(state.zoom + 25) }, enabled = state.zoom < 200, modifier = Modifier.size(48.dp).semantics { contentDescription = "放大 PDF" }) { Text("+", style = MaterialTheme.typography.titleLarge) }
    }
    if (jumpOpen) FileOperationDialog("跳转页码", { jumpOpen = false }, footer = {
        FilewayTextButton({ jumpOpen = false }) { Text("取消") }
        FilewayButton(::jump, enabled = validPage != null) { Text("转到") }
    }) {
        OutlinedTextField(pageInput, { pageInput = it }, Modifier.fillMaxWidth(), label = { Text("页码") }, singleLine = true,
            isError = pageInput.isNotBlank() && validPage == null,
            supportingText = { Text("有效范围：1–${state.pageCount}") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { jump() }))
    }
    if (state.rendering) LinearProgressIndicator(Modifier.fillMaxWidth())
    state.pageError?.let { message -> Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
        FilewayTextButton(controller::retryPage) { Text("重试此页") }
    } }
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
        val pixels = with(density) { maxWidth.roundToPx() }
        LaunchedEffect(pixels) { controller.viewport(pixels) }
        val page = borrowed?.bitmap?.takeUnless { it.isRecycled }
        if (page != null && state.pageImage != null) Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
            val dimensions = documentPdfDisplaySize(pixels, state.zoom, page.width, page.height)
            val width = with(density) { dimensions.width.toDp() }
            val height = with(density) { dimensions.height.toDp() }
            Image(page.asImageBitmap(), "PDF 第 ${state.page + 1} 页", modifier = Modifier.size(width, height))
        }
        else if (state.rendering) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
    }
}

/** Scroll children are unbounded, but a finite page still has to fit Compose's
 * packed pixel constraints. Derive the page from the real viewport and ratio,
 * then uniformly fit extreme pages; a large dp constant is not infinity. */
internal fun documentPdfDisplaySize(viewportWidthPixels: Int, zoomPercent: Int, pageWidth: Int, pageHeight: Int): IntSize {
    require(viewportWidthPixels >= 0 && zoomPercent > 0 && pageWidth > 0 && pageHeight > 0)
    val width = (viewportWidthPixels.toDouble() * zoomPercent / 100).coerceAtLeast(1.0)
    val height = (width * pageHeight / pageWidth).coerceAtLeast(1.0)
    val limits = Constraints.fitPrioritizingWidth(minWidth = 0, maxWidth = width.coerceAtMost((Int.MAX_VALUE - 1).toDouble()).roundToInt(),
        minHeight = 0, maxHeight = height.coerceAtMost((Int.MAX_VALUE - 1).toDouble()).roundToInt())
    val scale = minOf(1.0, limits.maxWidth / width, limits.maxHeight / height)
    return IntSize((width * scale).roundToInt().coerceIn(1, limits.maxWidth), (height * scale).roundToInt().coerceIn(1, limits.maxHeight))
}
