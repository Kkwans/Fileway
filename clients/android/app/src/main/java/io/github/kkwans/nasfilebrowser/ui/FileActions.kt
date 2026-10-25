package io.github.kkwans.nasfilebrowser.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.app.*
import io.github.kkwans.nasfilebrowser.data.FileTransferAction
import io.github.kkwans.nasfilebrowser.data.renameNameError
import io.github.kkwans.nasfilebrowser.ui.designsystem.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

private val LocalFileActionWidth = staticCompositionLocalOf<Dp?> { null }

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun FileActionIcon(icon: Int, label: String, enabled: Boolean, active: Boolean = false,
    caption: String? = null, onClick: () -> Unit) {
    val width = LocalFileActionWidth.current
    val foreground = LocalFileOperationForeground.current
    val focus = remember(label) { FocusRequester() }
    val tooltip = rememberTooltipState()
    LaunchedEffect(foreground?.revision) {
        tooltip.dismiss()
        if (foreground?.destination == FileOperationDestination.DETAILS && foreground.returnAction == label && enabled)
            focus.requestFocus()
    }
    @Composable fun button() {
        if (caption == null) IconButton(onClick, enabled = enabled, modifier = Modifier.size(48.dp).focusRequester(focus)) {
            Icon(painterResource(icon), label, Modifier.size(24.dp), tint = if (!enabled) FilewayTheme.colors.onSurface.copy(alpha = .38f)
                else if (active) FilewayTheme.colors.primary else FilewayTheme.colors.onSurfaceVariant)
        } else FilewayTextButton(onClick, enabled = enabled, modifier = (if (width == null) Modifier.widthIn(min = 48.dp) else Modifier.width(width)).heightIn(min = 64.dp).focusRequester(focus)
            .background(filewayGlassLayerColor(), RoundedCornerShape(12.dp)).filewayGlassFrame()
            .semantics { contentDescription = label }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(painterResource(icon), null, Modifier.size(24.dp), tint = if (!enabled) FilewayTheme.colors.onSurface.copy(alpha = .38f)
                    else if (active) FilewayTheme.colors.primary else FilewayTheme.colors.onSurfaceVariant)
                Text(caption, style = MaterialTheme.typography.labelMedium,
                    color = if (enabled) FilewayTheme.colors.onSurfaceVariant else FilewayTheme.colors.onSurface.copy(alpha = .38f))
            }
        }
    }
    if (caption == null) TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } }, state = tooltip) { button() } else button()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun FileActions(model: ClientModel, file: ResourceRef, showFavorite: Boolean = true, onMoved: () -> Unit = {}) {
    if (model.isTemporaryContent(file)) {
        Text("包内文件 · 解压后可收藏、打标签或修改", style = FilewayTheme.text.metadata, color = FilewayTheme.colors.onSurfaceVariant)
        return
    }
    if (file.downloadId.isNotEmpty()) {
        Text("本机下载 · 收藏和标签在服务器原文件上管理", style = FilewayTheme.text.metadata, color = FilewayTheme.colors.onSurfaceVariant)
        return
    }
    val tags by model.tags.state.collectAsStateWithLifecycle()
    val trash by model.trash.state.collectAsStateWithLifecycle()
    val client by model.state.collectAsStateWithLifecycle()
    val downloads by model.downloads.state.collectAsStateWithLifecycle()
    val favorites by model.favorites.state.collectAsStateWithLifecycle()
    val operations by model.fileOperations.state.collectAsStateWithLifecycle()
    val checksum by model.fileChecksum.state.collectAsStateWithLifecycle()
    val parentForeground = LocalFileOperationForeground.current
    val foreground = parentForeground ?: remember(file.mediaKey, operations.scope) { FileOperationForeground() }
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    var more by remember(file.mediaKey, operations.scope) { mutableStateOf(false) }
    val initialScope = remember(file.mediaKey) { operations.scope }
    LaunchedEffect(operations.scope) { if (operations.scope != initialScope) { more = false; foreground.back(); onMoved() } }
    // Cleanup belongs to the stable details owner, not to a toolbar that is
    // removed when the operation opens in another Dialog composition.
    SideEffect {
        val scope = operations.scope
        foreground.onClosed = { if (model.fileChecksum.state.value.scope == scope && model.fileChecksum.state.value.file?.mediaKey == file.mediaKey) model.fileChecksum.close() }
    }
    if (parentForeground == null) DisposableEffect(foreground) { onDispose { foreground.close() } }
    fun switch(destination: FileOperationDestination, action: String) {
        more = false; focus.clearFocus(); keyboard?.hide(); foreground.open(destination, action)
    }
    fun back() { focus.clearFocus(); keyboard?.hide(); foreground.back() }
    fun leave() { more = false; focus.clearFocus(); keyboard?.hide(); onMoved() }
    val enabled = LocalFileDetailsActionsEnabled.current && !trash.changing && !client.busy && !operations.changing
    CompositionLocalProvider(LocalFileOperationForeground provides foreground) {
        if (foreground.destination == FileOperationDestination.DETAILS) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val hasMore = file.path != "/" && (client.permissions.rename || client.permissions.create || client.permissions.download)
                    val count = (if (showFavorite) 2 else 1) + (if (client.permissions.download) 1 else 0) +
                        (if (client.permissions.delete && file.path != "/") 1 else 0) + (if (hasMore) 1 else 0)
                    val width = maxOf(48.dp, (maxWidth - 8.dp * (count - 1)) / count)
                    CompositionLocalProvider(LocalFileActionWidth provides width) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (showFavorite) FavoriteFileAction(model, file, enabled)
                        FileActionIcon(R.drawable.ic_tag, "设置文件标签", enabled && !tags.changing, model.tags.assigned(file).isNotEmpty(), caption = "标签") {
                            switch(FileOperationDestination.TAGS, "设置文件标签")
                        }
                        if (client.permissions.download) FileActionIcon(R.drawable.ic_download, "下载到本机", enabled && !downloads.busy && downloads.folderPlan == null, caption = "下载") {
                            focus.clearFocus(); keyboard?.hide(); model.download(file)
                            if (file.directory) leave()
                            if (!file.directory && android.os.Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                                notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        }
                        if (client.permissions.delete && file.path != "/") FileActionIcon(R.drawable.ic_trash, "删除", enabled && !tags.changing, caption = "删除") {
                            switch(FileOperationDestination.TRASH, "删除")
                        }
                        if (file.path != "/" && (client.permissions.rename || client.permissions.create || client.permissions.download)) {
                            val extraActions = buildList {
                                if (client.permissions.download) add(FilewayMenuEntry("zip", "打包下载为 ZIP", R.drawable.ic_download))
                                if (client.permissions.download && !file.directory) add(FilewayMenuEntry("checksum", "校验文件", R.drawable.ic_check))
                                if (client.permissions.rename) add(FilewayMenuEntry("rename", "重命名", R.drawable.ic_edit, enabled = !tags.changing && !favorites.changing, accessibilityLabel = "重命名文件"))
                                if (client.permissions.create) add(FilewayMenuEntry("copy", "复制到…", R.drawable.ic_copy, accessibilityLabel = "复制文件"))
                                if (client.permissions.create && client.permissions.rename) add(FilewayMenuEntry("move", "移动到…", R.drawable.ic_move, accessibilityLabel = "移动文件"))
                            }
                            val performExtra: (FilewayMenuEntry) -> Unit = { item ->
                                more = false
                                when (item.key) {
                                    "zip" -> { model.startZipExport(listOf(file), client.previewScope); leave() }
                                    "checksum" -> {
                                        model.fileChecksum.open(file, client.previewScope)
                                        if (model.fileChecksum.state.value.file?.mediaKey == file.mediaKey) switch(FileOperationDestination.CHECKSUM, "更多文件操作")
                                    }
                                    "rename" -> switch(FileOperationDestination.RENAME, "更多文件操作")
                                    "copy" -> { model.startFileTransfer(listOf(file), FileTransferAction.COPY, client.previewScope); leave() }
                                    "move" -> { model.startFileTransfer(listOf(file), FileTransferAction.MOVE, client.previewScope); leave() }
                                }
                            }
                            Box {
                                FileActionIcon(R.drawable.ic_more_actions, "更多文件操作", enabled && operations.transfer == null, caption = "更多") { more = true }
                                FilewayMenu(more, { more = false }, items = extraActions, onItemClick = performExtra)
                            }
                        }
                    }
                }
                }
                favorites.error?.let { FilewayStatusBanner(it, FilewayStatusKind.ERROR, model.favorites::refresh, "重试") }
                FilewayToast(downloads.notice)
                downloads.error?.let { FilewayStatusBanner(it, FilewayStatusKind.ERROR) }
                tags.error?.let { FilewayStatusBanner(it, FilewayStatusKind.ERROR) }
                operations.error?.let { FilewayStatusBanner(it, FilewayStatusKind.ERROR) }
            }
        } else {
            Box(Modifier.size(0.dp)) { if (showFavorite) FavoriteFileAction(model, file, enabled) }
        }
        when (foreground.destination) {
            FileOperationDestination.TAGS -> FileTagPicker(model, file, ::back)
            FileOperationDestination.CHECKSUM -> if (checksum.file?.mediaKey == file.mediaKey) FileChecksumSheet(model.fileChecksum, {
                model.fileChecksum.close(); back()
            }, back = true) else LaunchedEffect(Unit) { back() }
            FileOperationDestination.RENAME -> FileRenameDialog(model, file, ::back) { back(); onMoved() }
            FileOperationDestination.TRASH -> FileOperationDialog("删除文件？", ::back, canDismiss = !trash.changing, footer = {
                FilewayTextButton({ model.trash.permanentlyDeleteResource(file) { back(); onMoved() } },
                    enabled = enabled && !trash.permanentResultUnknown, modifier = Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = FilewayTheme.colors.error)) { Text("永久删除") }
                FilewayButton({ model.trash.move(file) { back(); onMoved() } }, enabled = enabled && !trash.permanentResultUnknown, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (trash.changing) "正在移动" else "移入回收站")
                }
            }) {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SelectionContainer { Text(file.name, style = FilewayTheme.text.fileName) }
                    FileOperationLocation("原位置", file.path)
                    Text("移入回收站后可以恢复；永久删除不会进入回收站，执行后无法恢复。", style = MaterialTheme.typography.bodyMedium)
                    Text("永久删除提交为后台任务，开始前有短暂撤销窗口，可在任务中心查看。", style = FilewayTheme.text.metadata, color = FilewayTheme.colors.onSurfaceVariant)
                    trash.error?.let { FilewayStatusBanner(it, FilewayStatusKind.ERROR) }
                }
            }
            else -> Unit
        }
    }
}

@Composable private fun FileRenameDialog(model: ClientModel, file: ResourceRef, dismiss: () -> Unit, saved: () -> Unit) {
    val state by model.fileOperations.state.collectAsStateWithLifecycle()
    val stemEnd = if (file.directory) file.name.length else file.name.lastIndexOf('.').takeIf { it > 0 } ?: file.name.length
    var input by remember(file.mediaKey, state.scope) { mutableStateOf(TextFieldValue(file.name, TextRange(0, stemEnd))) }
    var attempted by remember(file.mediaKey, state.scope) { mutableStateOf(false) }
    val focus = remember(file.mediaKey, state.scope) { FocusRequester() }
    val error = renameNameError(input.text)
    val unchanged = input.text.trim() == file.name
    val canSubmit = !state.changing && error == null && !unchanged
    fun submit() { if (canSubmit) { attempted = true; model.fileOperations.rename(file, input.text, saved) } }
    LaunchedEffect(file.mediaKey, state.scope) { focus.requestFocus() }
    FileOperationDialog("重命名", dismiss, canDismiss = !state.changing, footer = {
        FilewayTextButton(dismiss, enabled = !state.changing, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") }
        FilewayButton(::submit, enabled = canSubmit, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (state.changing) "正在重命名" else "保存名称") }
    }) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SelectionContainer { Text(file.name, style = FilewayTheme.text.fileName) }
            OutlinedTextField(input, { input = it; attempted = false }, Modifier.fillMaxWidth().focusRequester(focus), label = { Text("新名称") },
                enabled = !state.changing, singleLine = true, isError = error != null,
                supportingText = { Text(error ?: if (unchanged) "请输入不同的名称" else if (file.directory) "同名文件夹存在时不会覆盖" else "保留扩展名可继续使用原来的打开方式") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { submit() }))
            if (attempted) state.error?.let { FilewayStatusBanner(it, FilewayStatusKind.ERROR) }
        }
    }
}

@Composable private fun FileTagPicker(model: ClientModel, file: ResourceRef, dismiss: () -> Unit) {
    val state by model.tags.state.collectAsStateWithLifecycle()
    val sourceScope = remember(file.mediaKey) { state.scope }
    var baseline by remember { mutableStateOf<Set<String>?>(null) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var attempt by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var submitted by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(file.mediaKey, state.scope, attempt) {
        loading = true; error = null
        try {
            check(state.scope == sourceScope) { "文件来源已切换，请重新选择文件" }
            model.tags.refresh()
            val current = withTimeout(10_000) { model.tags.state.first { !it.loading && !it.changing && (it.loaded || it.error != null) } }
            check(current.scope == sourceScope) { "文件来源已切换，请重新选择文件" }
            check(current.error == null) { current.error.orEmpty() }
            val assigned = model.tags.assigned(file)
            if (baseline == null) selected = assigned
            baseline = assigned
            submitted = false
        } catch (failure: Exception) { if (failure is kotlinx.coroutines.CancellationException) throw failure; error = failure.message ?: "标签读取失败，请重试" }
        finally { loading = false }
    }
    val writeError = state.error.takeIf { submitted && !state.changing }
    val editable = !loading && !state.changing && state.scope == sourceScope
    if (!creating) FileOperationSheet("文件标签", dismiss, canDismiss = !state.changing, back = true, footer = {
        FilewayTextButton(dismiss, enabled = !state.changing, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") }
        FilewayButton({ submitted = true; model.tags.assign(file, baseline.orEmpty(), selected, onSaved = dismiss, sourceScope = sourceScope) },
            enabled = baseline != null && editable && error == null && writeError == null, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(if (state.changing) "正在保存" else "保存标记")
        }
    }) {
        LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item { SelectionContainer { Text(file.name, style = FilewayTheme.text.fileName, modifier = Modifier.padding(bottom = 12.dp)) } }
            if (loading || state.changing) item { FilewayStatusBanner(if (loading) "正在读取标签" else "正在保存标记", FilewayStatusKind.LOADING) }
            (error ?: writeError)?.let { failure -> item { FilewayStatusBanner(failure, FilewayStatusKind.ERROR, { attempt++ }, "重新读取当前标记") } }
            if (state.items.isEmpty() && !loading) item { Text("还没有标签，可先新建一个。", Modifier.padding(vertical = 16.dp), color = FilewayTheme.colors.onSurfaceVariant) }
            items(state.items, key = { it.id }) { tag ->
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).toggleable(tag.id in selected, enabled = editable && writeError == null, role = Role.Checkbox) { checked ->
                    selected = if (checked) selected + tag.id else selected - tag.id
                }.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).background(metadataColor(tag.color, FilewayTheme.colors.primary), CircleShape))
                    Text(tag.name, Modifier.weight(1f).padding(horizontal = 12.dp), style = MaterialTheme.typography.bodyMedium)
                    Checkbox(tag.id in selected, null, enabled = editable && writeError == null)
                }
            }
            item { FilewayTextButton({ focus.clearFocus(); keyboard?.hide(); creating = true }, enabled = editable && writeError == null, modifier = Modifier.heightIn(min = 48.dp)) { Text("新建标签") } }
        }
    }
    if (creating) TagEditor(model, null, { creating = false })
}
