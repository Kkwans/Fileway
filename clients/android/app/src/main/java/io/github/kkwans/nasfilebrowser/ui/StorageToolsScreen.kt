package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.app.*
import io.github.kkwans.nasfilebrowser.data.*
import org.json.JSONArray
import org.json.JSONObject

private fun jsonRows(value: JSONObject, key: String): List<JSONObject> = value.optJSONArray(key)?.let { rows -> (0 until rows.length()).map { rows.getJSONObject(it) } }.orEmpty()
private fun cleanable(report: JSONObject, group: JSONObject): Boolean {
    val files = jsonRows(group, "files")
    return files.all { favoritePathIdentity(it.optString("path"), null, null).openable } &&
        duplicateGroupCanBeCleaned(report.optInt("schemaVersion"), group.optInt("totalFiles"), group.optString("keepReason"), files.map {
            val identity = it.optJSONObject("identity")
            identity?.let { value -> value.optLong("links") to value.optLong("mode").toInt() }
        })
}

@Composable internal fun StorageToolsScreen(model: ClientModel, client: ClientState) {
    val state by model.storageTools.state.collectAsStateWithLifecycle()
    var scan by remember(state.scope) { mutableStateOf(false) }
    var keepers by remember(state.scope, state.reportId) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var selecting by remember(state.scope, state.reportId) { mutableStateOf<JSONObject?>(null) }
    var confirmCleanup by remember(state.scope, state.reportId) { mutableStateOf(false) }
    var visibleGroups by remember(state.reportId) { mutableIntStateOf(20) }
    val enabled = !state.loading && !state.changing && !client.busy
    LaunchedEffect(state.scope) { model.storageTools.refresh() }
    LibraryScaffold(model, LibrarySection.TOOLS, actions = {
        TextButton({ scan = true }, enabled = enabled && state.permissions.download) { Text("新建扫描") }
        IconButton({ model.storageTools.refresh() }, enabled = enabled) { Icon(painterResource(R.drawable.ic_refresh), "刷新存储工具") }
    }) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(state.tool == "storage", { model.storageTools.tool("storage") }, { Text("存储占用") }, enabled = enabled, colors = libraryChipColors())
            FilterChip(state.tool == "duplicates", { model.storageTools.tool("duplicates") }, { Text("重复文件") }, enabled = enabled, colors = libraryChipColors())
        }
        LibraryMessage(state.error, state.notice, state.loading || state.changing) { model.storageTools.refresh() }
        LazyColumn(Modifier.weight(1f).semantics { contentDescription = "存储工具内容" }, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.reportId == null) {
                item { Text("选择扫描范围，查看空间占用或内容相同的文件。扫描由当前服务器执行。", Modifier.padding(4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (state.volumes.isNotEmpty()) item { Text("磁盘概览", style = MaterialTheme.typography.titleMedium) }
                items(state.volumes, key = { "volume/" + it.getString("path") }) { volume ->
                    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(volume.optString("name"), style = MaterialTheme.typography.titleSmall)
                            val total = volume.optLong("totalSpace"); val used = volume.optLong("usedSpace")
                            if (total > 0) LinearProgressIndicator(progress = { (used.toDouble() / total).coerceIn(0.0, 1.0).toFloat() }, modifier = Modifier.fillMaxWidth().height(4.dp), gapSize = 0.dp, drawStopIndicator = {})
                            Text("已用 ${readableSize(used)}" + if (total > 0) " / ${readableSize(total)} · 可用 ${readableSize(volume.optLong("freeSpace", (total - used).coerceAtLeast(0)))}" else " · 总容量未知", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row {
                                TextButton({ model.openRemotePath(volume.getString("path")) }, enabled = !client.busy) { Text("打开存储卷") }
                                TextButton({ scan = true }, enabled = enabled && state.permissions.download) { Text("扫描范围") }
                            }
                        }
                    }
                }
                state.volumeError?.let { item { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                item { Text("最近分析", style = MaterialTheme.typography.titleMedium) }
                if (state.recent.isEmpty() && !state.loading) item { Text("还没有分析记录。从当前目录开始一次扫描。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(state.recent, key = { "scan/" + it.getString("id") }) { row ->
                    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.background) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            val scopes = row.optJSONArray("scopes")
                            Text(scopes?.let { (0 until it.length()).joinToString(" · ") { i -> it.getString(i) } }.orEmpty(), maxLines = 3, overflow = TextOverflow.Ellipsis)
                            Text("${serverTime(row.optLong("createdAt"))} · " + when (row.optString("status")) { "completed" -> "已完成"; "failed" -> "失败"; "canceled" -> "已取消"; "interrupted" -> "已中断"; "running" -> "进行中"; else -> "排队中" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row {
                                TextButton({ model.storageTools.openReport(row.getString("id"), row.getString("tool")) }) { Text(if (row.optBoolean("resultReady")) "查看报告" else "查看进度") }
                                TextButton({ model.showServerTask(row.getString("id")) }) { Text("任务详情") }
                            }
                        }
                    }
                }
                if (state.nextCursor.isNotEmpty()) item { TextButton({ model.storageTools.refresh(more = true) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("加载更多分析记录") } }
            } else {
                item { Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(model.storageTools::closeReport, enabled = !state.changing) { Text("返回分析记录") }
                    Spacer(Modifier.weight(1f))
                    TextButton({ state.reportId?.let { model.storageTools.openReport(it) } }, enabled = !state.reportLoading && !state.changing) { Text("刷新报告") }
                } }
                if (state.reportLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                state.reportError?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
                state.task?.let { task -> item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${task.title} · ${task.statusLabel}", style = MaterialTheme.typography.titleMedium)
                        if (task.active) TaskProgress(task)
                        else Text("完成时间 · ${serverTime(task.finishedAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton({ model.showServerTask(task.id) }) { Text("查看分析任务") }
                    }
                } }
                state.report?.let { report ->
                    item {
                        val scopes = report.optJSONArray("scopes")
                        if (scopes != null) SelectionContainer { Text((0 until scopes.length()).mapNotNull {
                            when (val value = scopes.opt(it)) { is String -> value; is JSONObject -> value.optString("path"); else -> null }
                        }.joinToString("\n"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Text("扫描 ${report.optLong("scannedFiles")} 个文件 · ${readableSize(report.optLong("scannedBytes"))}", style = MaterialTheme.typography.bodyMedium)
                        if (state.cleanup != null) Text("分析报告为清理前的历史快照，清理结果在下方单独列出。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (report.optBoolean("truncated")) Text("结果数量达到服务端上限，报告未包含所有条目。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        if (report.optInt("skippedCount") > 0) Text("${report.optInt("skippedCount")} 项跳过或无法读取。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                    if (state.tool == "storage") {
                        item { Text("最大的文件夹", style = MaterialTheme.typography.titleMedium) }
                        items(jsonRows(report, "largestDirectories"), key = { "dir/" + it.getString("path") }) { value -> AnalysisPathRow(value.getString("path"), value.optLong("bytes"), "${value.optLong("files")} 个文件", model, directory = true, enabled = !client.busy) }
                        item { Text("最大的文件", style = MaterialTheme.typography.titleMedium) }
                        items(jsonRows(report, "largestFiles"), key = { "file/" + it.getString("path") }) { value -> AnalysisPathRow(value.getString("path"), value.optLong("size"), serverTime(value.optLong("modified")), model, enabled = !client.busy) }
                        if (jsonRows(report, "largestFiles").isEmpty() && jsonRows(report, "largestDirectories").isEmpty()) item { Text("这个范围没有可列出的文件。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    } else {
                        val groups = jsonRows(report, "groups")
                        item { Text("${report.optInt("duplicateGroups")} 个重复组 · 最多可回收 ${readableSize(report.optLong("reclaimableBytes"))}", style = MaterialTheme.typography.titleMedium) }
                        items(groups.take(visibleGroups), key = { it.getString("sha256") }) { group ->
                            val hash = group.getString("sha256"); val keep = keepers[hash]
                            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    val members = jsonRows(group, "files")
                                    Text(members.firstOrNull()?.optString("path")?.let(::libraryBasename) ?: "重复文件组", style = MaterialTheme.typography.titleSmall)
                                    Text("${group.optInt("totalFiles")} 个相同文件 · 每份 ${readableSize(group.optLong("size"))}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    members.take(3).forEach { file -> Text(libraryParent(file.getString("path")), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                    if (members.size > 3) Text("另 ${members.size - 3} 项 · 点下方查看全部", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (keep != null) Text("保留：${libraryBasename(keep)}\n${libraryParent(keep)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                    if (!cleanable(report, group)) Text("这组缺少完整、安全的文件身份信息，可查看但不能清理。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    TextButton({ selecting = group }, enabled = !state.changing) { Text(if (keep == null) "查看并选择保留文件" else "修改保留文件") }
                                }
                            }
                        }
                        if (visibleGroups < groups.size) item { TextButton({ visibleGroups += 20 }, Modifier.fillMaxWidth()) { Text("显示更多重复组") } }
                        if (keepers.isNotEmpty() && state.cleanup == null && state.permissions.delete && state.task?.userId == state.userId) item { Button({ confirmCleanup = true }, Modifier.fillMaxWidth(), enabled = enabled && state.cleanupLookupKnown && !state.cleanupLoading) { Text("清理已选择的 ${keepers.size} 组") } }
                    }
                }
                state.cleanup?.let { task -> item {
                    Text("清理任务 · ${task.statusLabel}", style = MaterialTheme.typography.titleMedium)
                    if (task.active) TaskProgress(task)
                    TextButton({ model.showServerTask(task.id) }) { Text("查看清理任务") }
                } }
                state.cleanupError?.let { item { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) } }
                state.cleanupResult?.let { raw ->
                    val result = DuplicateCleanupPresentation.from(raw)
                    if (result == null) item { Text("清理结果无法确认，请刷新报告核对。", color = MaterialTheme.colorScheme.error) }
                    else {
                        item { val count = result.counts; Text("已移入回收站 ${count.success} · 跳过 ${count.skipped} · 失败 ${count.failed} · 待确认 ${count.unknown}", style = MaterialTheme.typography.bodyMedium) }
                        items(result.groups.sortedBy { if (it.counts.failed > 0) 0 else if (it.counts.unknown > 0) 1 else 2 }, key = { "cleanup/" + it.sha256 }) { group ->
                            Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium) {
                                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    SelectionContainer { Text("保留：${group.keepPath}", style = MaterialTheme.typography.bodyMedium) }
                                    group.filesForDisplay.forEach { file ->
                                        Text(libraryBasename(file.path) + " · " + when (file.outcome) {
                                            DuplicateCleanupOutcome.SUCCESS -> "已移入回收站"; DuplicateCleanupOutcome.SKIPPED -> "已跳过"
                                            DuplicateCleanupOutcome.FAILED -> "失败"; DuplicateCleanupOutcome.UNKNOWN -> "结果待确认"
                                        }, style = MaterialTheme.typography.bodySmall, color = if (file.outcome == DuplicateCleanupOutcome.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                                        SelectionContainer { Text(file.path + file.reason?.let { "\n$it" }.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (scan) {
        var input by remember { mutableStateOf(client.path) }
        val targets = input.lines().filter { it.isNotBlank() }.distinct()
        AlertDialog(onDismissRequest = { scan = false }, title = { Text(if (state.tool == "storage") "分析存储占用" else "查找重复文件") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("选择有权访问的服务器路径。扫描只在你提交后开始。", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(input, { input = it }, label = { Text("扫描路径，每行一个") }, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 5)
                TextButton({ input = client.path }) { Text("使用当前目录") }
                if (targets.any { !it.startsWith('/') }) Text("请输入以 / 开始的服务器路径", color = MaterialTheme.colorScheme.error)
                if (targets.size > 32) Text("一次最多选择32个范围", color = MaterialTheme.colorScheme.error)
                if ("/" in targets) Text("根目录可能包含大量文件，扫描会占用服务器资源。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = { TextButton({ model.storageTools.start(targets); scan = false }, enabled = targets.isNotEmpty() && targets.size <= 32 && targets.all { it.startsWith('/') } && enabled) { Text("提交扫描") } },
            dismissButton = { TextButton({ scan = false }) { Text("取消") } })
    }
    selecting?.let { group ->
        val report = state.report ?: return@let
        var keep by remember(group) { mutableStateOf(keepers[group.getString("sha256")].orEmpty()) }
        val safe = cleanable(report, group)
        AlertDialog(onDismissRequest = { selecting = null }, title = { Text("选择要保留的文件") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                group.optString("suggestedKeepPath").takeIf { it.isNotEmpty() }?.let { Text("服务端建议：$it", style = MaterialTheme.typography.bodySmall) }
                jsonRows(group, "files").forEach { file ->
                    val path = file.getString("path")
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(path == keep, enabled = safe && state.cleanup == null, role = Role.RadioButton) { keep = path }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(path == keep, null, enabled = safe && state.cleanup == null)
                        Column(Modifier.weight(1f).padding(start = 8.dp)) { Text(libraryBasename(path)); Text(path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Text("${readableSize(file.optLong("size"))} · ${serverTime(file.optLong("modified"))}", style = MaterialTheme.typography.bodySmall) }
                    }
                    TextButton({ model.openRemotePath(path) }, enabled = !client.busy && favoritePathIdentity(path, null, null).openable) { Text("打开文件") }
                }
                if (!safe) Text("报告被截断，或文件是链接/身份信息缺失。请重新扫描或手动查看文件。", color = MaterialTheme.colorScheme.error)
                if (keepers.containsKey(group.getString("sha256"))) TextButton({ keepers = keepers - group.getString("sha256"); selecting = null }) { Text("取消此组清理") }
            }
        }, confirmButton = { TextButton({ keepers = keepers + (group.getString("sha256") to keep); selecting = null }, enabled = safe && keep.isNotEmpty() && state.cleanup == null) { Text("保留此文件") } },
            dismissButton = { TextButton({ selecting = null }) { Text("取消") } })
    }
    if (confirmCleanup) AlertDialog(onDismissRequest = { confirmCleanup = false }, title = { Text("将重复副本移入回收站？") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val groups = state.report?.let { jsonRows(it, "groups") }.orEmpty()
            val copies = groups.filter { it.optString("sha256") in keepers }.sumOf { (it.optInt("totalFiles") - 1).coerceAtLeast(0) }
            Text("${keepers.size} 个组 · $copies 份副本将移入回收站")
            keepers.values.forEach { path -> FileOperationLocation("保留文件", path) }
            Text("服务端会重新检查文件身份，逐项结果可在任务中心查看。", style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { Button({ model.storageTools.cleanup(keepers.toMap()); confirmCleanup = false }, enabled = enabled && state.cleanupLookupKnown && !state.cleanupLoading) { Text("提交清理") } },
        dismissButton = { TextButton({ confirmCleanup = false }) { Text("取消") } })
}

@Composable private fun AnalysisPathRow(path: String, size: Long, info: String, model: ClientModel, directory: Boolean = false, enabled: Boolean = true) {
    val safe = favoritePathIdentity(path, null, null).openable && enabled
    var inspecting by remember(path) { mutableStateOf(false) }
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(painterResource(fileArtworkIcon(libraryBasename(path), directory)), null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
                Text(libraryBasename(path), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            SelectionContainer { Text(libraryParent(path), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text("${readableSize(size)} · $info", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row {
                TextButton({ model.openRemotePath(path) }, enabled = safe) { Text("打开") }
                TextButton({ model.openRemotePath(libraryParent(path)) }, enabled = safe) { Text("所在目录") }
                TextButton({ inspecting = true }) { Text("详情") }
            }
        }
    }
    if (inspecting) FileOperationDialog("对象详情", { inspecting = false }, footer = {
        TextButton({ inspecting = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("关闭") }
    }) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FileOperationLocation("完整路径", path)
            SelectionContainer { Text("${readableSize(size)} · $info", style = MaterialTheme.typography.bodySmall) }
        }
    }
}
