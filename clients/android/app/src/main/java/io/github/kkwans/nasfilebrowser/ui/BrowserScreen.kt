package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.app.FileLayout
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.app.ClientState
import io.github.kkwans.nasfilebrowser.app.ResourceRef
import io.github.kkwans.nasfilebrowser.app.FileCategory
import io.github.kkwans.nasfilebrowser.app.mediaKind
import io.github.kkwans.nasfilebrowser.data.collectionPath
import io.github.kkwans.nasfilebrowser.data.FileTransferAction
import io.github.kkwans.nasfilebrowser.data.DirectoryCrumb
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayTheme

/** Official reference: restrained chrome, cover-led content and compact directory entries. */
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun BrowserScreen(model: ClientModel, state: ClientState) {
    val colors = FilewayTheme.colors
    val layout = state.fileLayout
    val tags by model.tags.state.collectAsStateWithLifecycle()
    LaunchedEffect(tags.scope) { if (tags.scope.isNotEmpty()) model.tags.refresh() }
    val displayed = remember(state.files, state.fileCategory, state.fileCategories, state.fileOrder, tags.items, tags.filterId, tags.globalFilter) { model.directoryItems() }
    var details by remember(state.previewScope, state.wirePath) { mutableStateOf<ResourceRef?>(null) }
    var createMenu by remember(state.previewScope, state.wirePath) { mutableStateOf(false) }
    var selecting by remember(state.previewScope, state.wirePath) { mutableStateOf(false) }
    var selected by remember(state.previewScope, state.wirePath) { mutableStateOf(emptySet<String>()) }
    fun key(file: ResourceRef) = file.wirePath.ifEmpty { file.path }
    val shownKeys = displayed.map(::key).toSet()
    LaunchedEffect(shownKeys) { selected = selected.intersect(shownKeys) }
    val chosen = displayed.filter { key(it) in selected }
    val downloads by model.downloads.state.collectAsStateWithLifecycle()
    val trash by model.trash.state.collectAsStateWithLifecycle()
    val operations by model.fileOperations.state.collectAsStateWithLifecycle()
    val uploads by model.uploads.state.collectAsStateWithLifecycle()
    var browserMenu by remember(state.previewScope, state.wirePath) { mutableStateOf(false) }
    val uploadFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) model.uploads.prepare(files = uris) else model.uploads.cancelSelection()
    }
    val uploadFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) model.uploads.prepare(tree = uri) else model.uploads.cancelSelection()
    }
    LaunchedEffect(operations.lastTask?.id) {
        if (operations.lastTask != null) {
            selected = selected - operations.lastSources.map(::key).toSet()
            if (selected.isEmpty()) selecting = false
        }
    }
    LaunchedEffect(operations.scope, operations.batchCompletion) {
        selected = selected - operations.lastBatchSources.map(::key).toSet()
        if (operations.lastBatchSources.isNotEmpty() && selected.isEmpty()) selecting = false
    }
    var batchKind by remember(state.previewScope, state.wirePath) { mutableStateOf("") }
    var pendingTrash by remember(state.previewScope, state.wirePath) { mutableStateOf<List<ResourceRef>?>(null) }
    var trashAttempted by remember(state.previewScope, state.wirePath) { mutableStateOf(false) }
    val selectionBusy = downloads.busy || trash.changing || operations.changing
    val context = LocalContext.current
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    BackHandler(selecting) { selecting = false; selected = emptySet() }
    MaterialTheme(colorScheme = colors) {
        Scaffold(containerColor = colors.background, bottomBar = { ClientNavigation(model, "files") }) { insets ->
            FilewayRefresh(state.busy, model::retry, Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets)) {
Column(Modifier.fillMaxSize()) {
                FilewayPageHeader("文件") {
                    if (state.permissions.create) Box {
                        IconButton({ createMenu = true }, enabled = !state.busy && !selectionBusy && operations.transfer == null && operations.creation == null && operations.batchRename == null) {
                            Icon(painterResource(R.drawable.ic_add), "新建文件或文件夹", Modifier.size(22.dp), tint = colors.onSurfaceVariant)
                        }
                        FilewayMenu(createMenu, { createMenu = false }, items = listOf(
                            FilewayMenuEntry("file", "新建文件", R.drawable.ic_file),
                            FilewayMenuEntry("directory", "新建文件夹", R.drawable.ic_folder),
                        ), onItemClick = { item ->
                            createMenu = false
                            if (item.key == "file") model.startFileCreation(state.previewScope)
                            else model.startDirectoryCreation(state.previewScope)
                        })
                    }
                    Box {
                        IconButton({ browserMenu = true }, enabled = !state.busy) {
                            Icon(painterResource(R.drawable.ic_more_vert), "更多文件操作", Modifier.size(22.dp), tint = colors.onSurfaceVariant)
                        }
                        FilewayMenu(browserMenu, { browserMenu = false }, items = buildList {
                            if (state.permissions.create) {
                                val canUpload = !uploads.busy && !uploads.selecting && !selectionBusy
                                add(FilewayMenuEntry("upload", "上传文件", R.drawable.ic_upload, enabled = canUpload))
                                add(FilewayMenuEntry("uploadFolder", "上传文件夹", R.drawable.ic_folder, enabled = canUpload))
                            }
                            add(FilewayMenuEntry("refresh", "刷新", R.drawable.ic_refresh))
                            add(FilewayMenuEntry("select", "多选", R.drawable.ic_check, enabled = displayed.isNotEmpty() && !selectionBusy))
                        }, onItemClick = { item ->
                            browserMenu = false
                            when (item.key) {
                                "upload" -> if (model.beginUploadSelection()) try { uploadFiles.launch(arrayOf("*/*")) }
                                    catch (_: Exception) { model.uploads.cancelSelection(); model.uploads.reportError("无法打开系统文件选择器") }
                                "uploadFolder" -> if (model.beginUploadSelection()) try { uploadFolder.launch(null) }
                                    catch (_: Exception) { model.uploads.cancelSelection(); model.uploads.reportError("无法打开系统文件夹选择器") }
                                "refresh" -> { model.retry(); model.tags.refresh() }
                                "select" -> { selecting = true; selected = emptySet(); batchKind = "" }
                            }
                        })
                    }
                    IconButton(onClick = model::openSearch, enabled = !state.busy) {
                        Icon(painterResource(R.drawable.ic_search), "搜索文件", Modifier.size(22.dp), tint = colors.onBackground)
                    }
                }
                if (state.path != "/") DirectoryBreadcrumbs(state.path, state.wirePath, state.busy, { model.back() }, model::jumpDirectory)
                if (!selecting) Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FileCategoryMenu(state.fileCategories.ifEmpty { if (state.fileCategory == FileCategory.ALL) emptySet() else setOf(state.fileCategory) }, !state.busy, model::fileCategories)
                    Spacer(Modifier.weight(1f))
                    FileOrderMenu(state.fileOrder, !state.busy, model::fileOrder)
                    FileLayoutMenu(layout, !state.busy, select = model::fileLayout,
                        compactGridSize = state.compactGridSize, onCompactGridSize = model::compactGridSize)
                }
                if (selecting) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("已选 ${chosen.size} 项", Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
                        FilewayTextButton({ selected = if (selected == shownKeys) emptySet() else shownKeys }, enabled = !selectionBusy && !state.busy) { Text(if (selected == shownKeys) "全不选" else "全选") }
                        FilewayTextButton({ selected = shownKeys - selected }, enabled = !selectionBusy && !state.busy) { Text("反选") }
                        FileActionIcon(R.drawable.ic_arrow_back, "退出多选", true) { selecting = false; selected = emptySet() }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (state.permissions.create) FileActionIcon(R.drawable.ic_copy, "批量复制", !state.busy && !selectionBusy && operations.transfer == null && operations.batchRename == null && chosen.isNotEmpty()) {
                            model.startFileTransfer(chosen, FileTransferAction.COPY, state.previewScope)
                        }
                        if (state.permissions.create && state.permissions.rename) FileActionIcon(R.drawable.ic_move, "批量移动", !state.busy && !selectionBusy && operations.transfer == null && operations.batchRename == null && chosen.isNotEmpty()) {
                            model.startFileTransfer(chosen, FileTransferAction.MOVE, state.previewScope)
                        }
                        if (state.permissions.rename) FileActionIcon(R.drawable.ic_edit, "批量重命名", !state.busy && !selectionBusy && operations.transfer == null && operations.creation == null && operations.batchRename == null && chosen.size in 1..500) {
                            batchKind = "rename"
                            model.fileOperations.startBatchRename(chosen, DirectoryCrumb("原目录", state.path, state.wirePath), state.previewScope)
                        }
                        if (state.permissions.download) FileActionIcon(R.drawable.ic_folder, "打包下载为 ZIP", !state.busy && !selectionBusy && chosen.isNotEmpty()) { model.startZipExport(chosen, state.previewScope) }
                        FileActionIcon(R.drawable.ic_download, "批量下载", !state.busy && !selectionBusy && downloads.folderPlan == null && chosen.isNotEmpty() && state.permissions.download) {
                            batchKind = "download"
                            model.downloadFiles(chosen) { file ->
                                selected = selected - key(file)
                                if (selected.isEmpty()) selecting = false
                            }
                            if (chosen.none { it.directory } && android.os.Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                                notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        }
                        if (state.permissions.delete) FileActionIcon(R.drawable.ic_trash, "批量移入回收站", !state.busy && !selectionBusy && chosen.isNotEmpty()) {
                            pendingTrash = chosen.toList(); trashAttempted = false
                        }
                    }
                    if (state.permissions.rename && chosen.size > 500) Text("批量重命名最多 500 项，请减少选择。", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    if (selectionBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    val error = if (batchKind == "trash") trash.error else if (batchKind == "download") downloads.error else if (batchKind == "rename") operations.error else null
                    val notice = if (batchKind == "trash") trash.notice else if (batchKind == "download") downloads.notice else if (batchKind == "rename") operations.notice else null
                    FilewayToast(notice)
                    error?.let { Text(it, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = colors.error) }
                }
                if (!selecting) TransferTaskBanner(model)
                state.error?.let { message ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(message, modifier = Modifier.weight(1f), color = colors.error, style = MaterialTheme.typography.bodyMedium)
                        FilewayTextButton(onClick = model::retry) { Text("重试") }
                    }
                }
                tags.items.firstOrNull { it.id == tags.filterId }?.let { tag -> Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("标签：${tag.name} · ${if (tags.globalFilter) "含目录关联" else "直接标记"}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    FilewayTextButton({ model.tags.filter(null) }) { Text("清除筛选") }
                } }
                if (state.busy && state.files.isEmpty()) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = colors.primary, strokeWidth = 2.dp)
                    Text(state.stage, Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium)
                    FilewayTextButton(onClick = model::cancel) { Text("取消") }
                }
                if (!state.busy && displayed.isEmpty() && state.error == null) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (state.files.isEmpty()) "这个目录还没有文件。" else "当前目录没有此类型的文件。", color = colors.onSurfaceVariant)
                        if (state.fileCategory != FileCategory.ALL || state.fileCategories.isNotEmpty()) FilewayTextButton(onClick = { model.fileCategory(FileCategory.ALL) }) { Text("显示全部文件") }
                    }
                } else FileCollection(displayed, layout, Modifier.weight(1f), layout.collectionDescription(),
                    resetKey = listOf(state.previewScope, state.wirePath, state.fileCategory, state.fileCategories, state.fileOrder,
                        tags.filterId, tags.globalFilter), keyOf = { it.wirePath.ifEmpty { it.path } }, compactGridSize = state.compactGridSize) { file ->
                    FileEntry(model, file, layout, !state.busy && (!selecting || !selectionBusy), {
                        if (selecting) selected = if (key(file) in selected) selected - key(file) else selected + key(file)
                        else model.open(file)
                    }, { details = file }, selected = if (selecting) key(file) in selected else null, compactGridSize = state.compactGridSize)
                }
            }
}

        }
        details?.let { file -> FileDetailsDialog(file, actions = { FileActions(model, file) { details = null } }, onDismiss = { details = null }) }
        operations.batchRename?.let { rename -> BatchRenameSheet(model.fileOperations) {
            selected = emptySet(); selecting = false
            model.openContainingDirectory(rename.files.first())
        } }
        pendingTrash?.let { pending -> FilewayAlertDialog(onDismissRequest = { if (!trash.changing) pendingTrash = null },
            title = { Text("将 ${pending.size} 项移入回收站？") }, text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        pending.forEach { Text(it.name, style = MaterialTheme.typography.bodyMedium) }
                    }
                    Text("可从回收站恢复，收藏和标签会随服务端操作同步。", style = MaterialTheme.typography.bodySmall)
                    if (trashAttempted) trash.error?.let { Text(it, color = colors.error, style = MaterialTheme.typography.bodySmall) }
                }
            }, confirmButton = { FilewayTextButton({
                batchKind = "trash"; trashAttempted = true
                model.trash.moveAll(pending, { file ->
                    selected = selected - key(file)
                    if (selected.isEmpty()) selecting = false
                    pendingTrash = pendingTrash?.filterNot { key(it) == key(file) }
                }, { pendingTrash = null })
            }, enabled = !selectionBusy && pending.isNotEmpty()) { Text(if (trash.changing) "正在移动" else "移入回收站") } },
            dismissButton = { FilewayTextButton({ pendingTrash = null }, enabled = !trash.changing) { Text("取消") } }) }
    }
}
