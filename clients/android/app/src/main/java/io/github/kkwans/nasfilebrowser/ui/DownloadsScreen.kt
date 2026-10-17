package io.github.kkwans.nasfilebrowser.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import io.github.kkwans.nasfilebrowser.download.*
import io.github.kkwans.nasfilebrowser.data.DocumentPreviewKind
import io.github.kkwans.nasfilebrowser.data.documentPreviewKind
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun DownloadsScreen(model: ClientModel) {
    val state by model.downloads.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var filter by rememberSaveable { mutableStateOf("全部") }
    var settings by rememberSaveable { mutableStateOf(false) }
    var remove by remember { mutableStateOf<DownloadRecord?>(null) }
    var detailsId by remember { mutableStateOf<String?>(null) }
    var deleteFile by remember { mutableStateOf(false) }
    var reauthorizing by rememberSaveable { mutableStateOf<String?>(null) }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) model.downloads.selectDirectory(uri) }
    val recoverFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val id = reauthorizing; reauthorizing = null
        if (id != null && uri != null) model.downloads.reauthorizeDirectory(id, uri)
    }
    val folderFallback = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        model.downloads.reportNotice("目录浏览已结束，下载位置未更改")
    }
    fun openFolder(tree: String, relativeDirectory: String = "") {
        fun browseFolder() = scope.launch {
            try {
                model.downloads.reportNotice("使用系统目录浏览器查看文件，不会更改下载位置")
                val intent = withContext(Dispatchers.IO) { model.downloads.target.directoryPicker(tree, relativeDirectory) }
                folderFallback.launch(intent)
            } catch (_: Exception) { model.downloads.reportError("此设备没有可用的目录浏览器，请检查系统文件管理器") }
        }
        scope.launch {
            try {
                val intent = withContext(Dispatchers.IO) { model.downloads.target.directoryIntent(tree, relativeDirectory) }
                context.startActivity(intent)
            } catch (_: android.content.ActivityNotFoundException) { browseFolder() }
            catch (_: SecurityException) { browseFolder() }
            catch (_: Exception) { model.downloads.reportError("无法打开目录，请检查文件管理器及目录授权") }
        }
    }
    val records = state.items.filter { when (filter) { "已完成" -> it.complete; "未完成" -> !it.complete; else -> true } }
    FilewayRefresh(false, model.downloads::refresh) {
Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("全部", "未完成", "已完成").forEach { label -> FilterChip(filter == label, { filter = label }, { Text(label) }, colors = libraryChipColors()) }
            Spacer(Modifier.weight(1f))
            TextButton({ settings = true }) { Text("目录") }
        }
        state.error?.let { message -> Text(message, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.error) }
        FilewayToast(state.notice)
        LazyColumn(Modifier.weight(1f).semantics { contentDescription = "本机下载列表" }, contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("本机下载", style = MaterialTheme.typography.labelLarge)
                    Text("${records.size} 项", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (records.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(painterResource(R.drawable.ic_download), null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(if (state.items.isEmpty()) "把文件带在身边" else "这里还没有${filter}的文件", style = MaterialTheme.typography.titleMedium)
                    Text("从文件详情中下载。下载中的视频可边下边播，完成的图片和视频可离线打开。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton({ model.tab("files") }) { Text("浏览服务器文件") }
                }
            }
            items(records, key = { it.id }) { item ->
                var completionObserved by remember(item.id) { mutableStateOf(item.active) }
                LaunchedEffect(item.active) { if (item.active) completionObserved = true }
                val file = ResourceRef(item.path, item.wirePath, item.name, false, item.type, item.expectedSize, item.modified, item.id)
                val kind = file.mediaKind()
                val canOpen = item.localUri.isNotEmpty() && (item.complete || kind == MediaKind.VIDEO)
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                            if (item.complete && kind == MediaKind.IMAGE) MediaThumbnail(model, file, Modifier.fillMaxSize(), showStatusText = false)
                            else Icon(painterResource(fileArtworkIcon(item.name, false, item.type)), null,
                                Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                        Column(Modifier.weight(1f).clickable(enabled = canOpen) { openDownloaded(context, model, item, kind) }.padding(start = 12.dp)) {
                            Text(item.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(item.sourceLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (item.relativeDirectory.isNotEmpty()) Text(item.relativeDirectory, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    val fraction = if (item.expectedSize > 0) (item.downloaded.toDouble() / item.expectedSize).toFloat().coerceIn(0f, 1f) else 0f
                    val unknownZip = item.zipExport && !item.complete
                    val status = when (item.status) { "completed" -> "已完成"; "queued" -> if (unknownZip) "等待打包" else "等待下载"; "running" -> if (unknownZip) "正在打包下载" else "正在下载"; "paused" -> "已暂停"; "interrupted" -> "下载中断"; else -> "下载失败" }
                    if (!item.complete) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(status, style = MaterialTheme.typography.labelMedium, color = if (item.status == "failed") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(if (item.complete) readableSize(item.expectedSize) else if (unknownZip) "已接收 ${readableSize(item.downloaded)}" else "${(fraction * 100).toInt().coerceAtMost(99)}% · ${readableSize(item.downloaded)} / ${readableSize(item.expectedSize)}", style = MaterialTheme.typography.labelMedium)
                    }
                    if (!item.complete && !unknownZip) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().height(3.dp), gapSize = 0.dp, drawStopIndicator = {})
                    if (unknownZip && item.active) LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp))
                    if (unknownZip) Text("ZIP总大小生成中；恢复时从零重新打包，旧的未完成输出会被截断。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (item.active) Text(state.speeds[item.id]?.let { readableSize(it) + "/s" } ?: "正在获取下载速度", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (item.error.isNotEmpty()) Text(item.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                        if (item.complete) io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayStatusIcon(
                            io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayStatusAnimation.COMPLETE, item.id, Modifier.size(22.dp), play = completionObserved && LocalFilewayPageActive.current)
                        if (item.complete) Column(Modifier.weight(1f)) {
                            Text("已完成", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(readableSize(item.expectedSize), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (canOpen) TextButton({ openDownloaded(context, model, item, kind) }, Modifier.semantics { contentDescription = "打开下载：${item.name}" }, enabled = !state.busy) { Text(if (item.complete) "打开" else "边下边播") }
                        if (!item.complete) TextButton({ if (item.active) model.downloads.pause(item) else model.downloads.resume(item) },
                            Modifier.semantics { contentDescription = "${if (item.active) "暂停下载" else if (item.zipExport) "重新打包" else "继续下载"}：${item.name}" }, enabled = !state.busy) { Text(if (item.active) "暂停" else if (item.zipExport) "重新打包" else "继续下载") }
                        var more by remember(item.id) { mutableStateOf(false) }
                        Box {
                            IconButton({ more = true }, enabled = !state.busy) { Icon(painterResource(R.drawable.ic_more_horiz), "下载更多操作", Modifier.size(22.dp)) }
                            DropdownMenu(more, { more = false }) {
                                DropdownMenuItem({ Text("任务信息") }, { more = false; detailsId = item.id })
                                DropdownMenuItem({ Text("打开所在目录") }, { more = false; openFolder(item.treeUri, item.relativeDirectory) })
                                if (item.treeUri.isNotEmpty()) DropdownMenuItem({ Text("重新授权目录") }, {
                                    more = false; reauthorizing = item.id
                                    try { recoverFolder.launch(model.downloads.target.directoryUri(item.treeUri)) }
                                    catch (_: Exception) { reauthorizing = null; model.downloads.reportError("无法打开目录选择器，请检查系统文件管理器") }
                                }, enabled = !item.active)
                                DropdownMenuItem({ Text("移除记录") }, { more = false; deleteFile = false; remove = item }, enabled = item.complete)
                                DropdownMenuItem({ Text("删除文件与记录") }, { more = false; deleteFile = true; remove = item }, enabled = !item.active)
                            }
                        }
                    }
                }
                }
            }
        }
    }
}

    state.items.firstOrNull { it.id == detailsId }?.let { item ->
        FileOperationSheet("下载任务信息", { detailsId = null }, footer = { TextButton({ detailsId = null }) { Text("关闭") } }) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(item.name, style = MaterialTheme.typography.titleMedium)
                        Text(item.sourceLabel, style = MaterialTheme.typography.bodyMedium)
                        Text("服务器原位置：${item.path}", style = MaterialTheme.typography.bodySmall)
                        if (item.relativeDirectory.isNotEmpty()) Text("本机子目录：${item.relativeDirectory}", style = MaterialTheme.typography.bodySmall)
                        Text(if (item.zipExport && !item.complete) "已接收 ${readableSize(item.downloaded)} · ZIP 总量待生成" else "${readableSize(item.downloaded)} / ${readableSize(item.expectedSize)}", style = MaterialTheme.typography.bodySmall)
                        if (item.error.isNotEmpty()) Text(item.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
    state.zipPlan?.let { plan ->
        var name by remember(plan) { mutableStateOf(plan.suggestedName) }
        val error = zipExportNameError(name)
        AlertDialog(onDismissRequest = model.downloads::cancelZipExport, title = { Text("ZIP打包下载") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${plan.files.size} 个所选项目 · ${plan.parentPath}", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("ZIP文件名") }, singleLine = true,
                    enabled = !state.busy, isError = error != null, supportingText = { Text(error ?: "保留 .zip 扩展名") })
                Text("保存到：${downloadDirectoryLabel(state.tree)}", style = MaterialTheme.typography.bodySmall)
                TextButton({ try { folder.launch(model.downloads.target.directoryUri(state.tree)) } catch (_: Exception) { model.downloads.reportError("无法打开下载目录选择器") } }, enabled = !state.busy) { Text("更改下载目录") }
                Text("即时生成ZIP；暂停、断网或进程恢复后会从零重新打包，不支持断点追加。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { TextButton({ model.downloads.confirmZipExport(name) }, enabled = !state.busy && error == null) { Text("开始打包下载") } },
            dismissButton = { TextButton(model.downloads::cancelZipExport, enabled = !state.busy) { Text("取消") } })
    }
    if (settings) ModalBottomSheet(onDismissRequest = { settings = false }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("下载目录", style = MaterialTheme.typography.titleLarge)
            Text(downloadDirectoryLabel(state.tree), style = MaterialTheme.typography.bodyLarge)
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            FilewayToast(state.notice)
            Text("更改目录只影响新下载。现有文件留在原目录，不会自动移动或删除。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button({ try { folder.launch(model.downloads.target.directoryUri(state.tree)) } catch (_: Exception) { model.downloads.reportError("无法打开目录选择器") } }, Modifier.fillMaxWidth(), enabled = !state.busy) { Text("选择下载目录") }
            OutlinedButton({ openFolder(state.tree) }, Modifier.fillMaxWidth()) { Text("打开下载目录") }
            if (state.tree.isNotEmpty()) TextButton({ model.downloads.selectDirectory(null) }, Modifier.fillMaxWidth(), enabled = !state.busy) { Text("恢复默认 · Download/fileway") }
        }
    }
    remove?.let { item -> AlertDialog(onDismissRequest = { remove = null }, title = { Text(if (deleteFile) "删除本机文件？" else "移除下载记录？") },
        text = { Text(item.name + "\n\n" + if (deleteFile) "本机文件与下载记录将删除。服务器上的原文件保持不变。" else if (item.complete) "仅移除记录，本机文件仍保留，可通过系统文件管理器查看。" else "仅移除记录会留下未完成文件，且无法继续下载。建议选择删除文件与记录。") },
        confirmButton = { TextButton({ model.downloads.remove(item, deleteFile); remove = null }) { Text(if (deleteFile) "删除" else "移除记录") } },
        dismissButton = { TextButton({ remove = null }) { Text("取消") } }) }
}

internal fun downloadDirectoryLabel(tree: String): String = if (tree.isEmpty()) "Download/fileway" else runCatching {
    DocumentsContract.getTreeDocumentId(Uri.parse(tree)).replace("primary:", "本机存储 / ")
}.getOrDefault("已授权的自定义目录")

private fun openDownloaded(context: Context, model: ClientModel, item: DownloadRecord, kind: MediaKind?) {
    val file = ResourceRef(item.path, item.wirePath, item.name, false, item.type, item.expectedSize, item.modified, item.id)
    if (kind != null || documentPreviewKind(file) != DocumentPreviewKind.OTHER) { model.openDownload(item); return }
    try {
        val uri = Uri.parse(item.localUri)
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(item.name.substringAfterLast('.', "").lowercase(Locale.ROOT)) ?: "application/octet-stream"
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri(item.name, uri) })
    } catch (_: Exception) { model.downloads.reportError("没有可打开此文件的应用，请通过文件管理器查看") }
}
