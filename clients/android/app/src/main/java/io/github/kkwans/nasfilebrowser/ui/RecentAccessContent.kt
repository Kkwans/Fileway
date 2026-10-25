package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import io.github.kkwans.nasfilebrowser.app.RecentAccessController
import io.github.kkwans.nasfilebrowser.app.MediaKind
import io.github.kkwans.nasfilebrowser.app.mediaKind
import io.github.kkwans.nasfilebrowser.data.RecentAccessEntry

@Composable internal fun RecentAccessContent(
    controller: RecentAccessController, connected: Boolean, onConnect: () -> Unit,
    onLocation: ((RecentAccessEntry) -> Unit)? = null,
    onDetails: ((RecentAccessEntry) -> Unit)? = null,
    onOpen: (RecentAccessEntry) -> Unit,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val entries = remember(state.items) { state.items.filter { it.isDir || mediaKind(it.name, false, "") != MediaKind.VIDEO } }
    val selection = rememberFilewayTaskSelection(state.scope, entries.map { it.id })
    var deletion by remember(state.scope) { mutableStateOf<Set<String>?>(null) }
    var unverified by remember(state.scope) { mutableStateOf<RecentAccessEntry?>(null) }
    val enabled = connected && !state.changing
    FilewayPageHeaderActions {
        if (connected) {
            FilewaySelectionToggle(selection, "多选访问记录", enabled && entries.isNotEmpty())
            FilewayIconButton({ deletion = emptySet() }, enabled = enabled && entries.isNotEmpty()) { FilewayIcon(R.drawable.ic_trash, "清空最近访问", Modifier.size(24.dp), tint = MaterialTheme.colorScheme.error) }
        }
    }
    FilewayRefresh(state.loading, { controller.refresh() }) {
Column(Modifier.fillMaxSize()) {
        if (!connected || state.scope.isEmpty()) {
            RecentAccessMessage("连接后查看最近访问", "成功进入目录或打开文件后，与同一账号的网页端同步。", "连接服务器", onConnect)
            return@Column
        }
        if (selection.active) FilewayTaskSelectionBar(selection, enabled, {
            selection.select(if (selection.ids.size == entries.size) emptyList() else entries.map { it.id })
        }) {
            FilewayIconButton({ if (selection.ids.size == entries.size) deletion = selection.ids.toSet() else controller.deleteSelected(selection.ids, onSaved = selection::close) }, enabled = enabled && selection.ids.isNotEmpty()) {
                FilewayIcon(R.drawable.ic_trash, "删除所选访问记录", tint = MaterialTheme.colorScheme.error)
            }
        }
        if (state.loading || state.changing) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        state.recordWarning?.let { message ->
            Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(12.dp)) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilewayIcon(R.drawable.ic_info, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
        }
        if (state.error != null && entries.isEmpty()) {
            RecentAccessMessage("无法读取最近访问", state.error!!, "重试", { controller.refresh() })
        } else if (state.loading && !state.loaded && entries.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("正在读取最近访问…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (entries.isEmpty()) {
            RecentAccessMessage("还没有最近访问", "成功进入目录或打开文件后，会按访问时间出现在这里。", "刷新", { controller.refresh() })
        } else {
            state.error?.let { message ->
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("刷新失败，当前显示上次读取的记录。$message", Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    FilewayTextButton(onClick = { controller.refresh() }, enabled = !state.loading) { Text("重试") }
                }
            }
            key(state.scope) {
                LazyColumn(Modifier.weight(1f).fillMaxWidth().semantics { contentDescription = "服务端最近访问列表" },
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(entries, key = { it.id }) { entry ->
                        var menu by remember(entry.id) { mutableStateOf(false) }
                        fun valid() = controller.state.value.scope == state.scope && controller.state.value.items.any { it == entry }
                        LibraryObjectCard(
                            name = entry.name,
                            location = libraryLocation(entry.path),
                            icon = fileArtworkIcon(entry.name, entry.isDir),
                            summary = serverTime(entry.accessedAt),
                            modifier = Modifier.secondaryClick { if (entry.openable) menu = true },
                            enabled = enabled,
                            selected = entry.id in selection.ids, selectionMode = selection.active,
                            onOpen = { if (valid()) { if (selection.active) selection.toggle(entry.id) else if (entry.openable) onDetails?.invoke(entry) else unverified = entry } },
                            onDoubleClick = if (entry.openable) ({ if (valid()) onOpen(entry) }) else null,
                            onLongClick = { selection.start(entry.id) },
                            trailing = {
                                if (!selection.active) Box {
                                    FilewayIconButton({ menu = true }, modifier = Modifier.size(48.dp)) {
                                        FilewayIcon(R.drawable.ic_more_actions, "最近访问操作")
                                    }
                                    FilewayMenu(menu, { menu = false }, items = buildList {
                                        add(FilewayMenuEntry("open", "打开", R.drawable.ic_visibility, enabled = entry.openable))
                                        if (onLocation != null) add(FilewayMenuEntry("location", "打开所在目录", R.drawable.ic_folder, enabled = entry.openable))
                                        if (onDetails != null) add(FilewayMenuEntry("info", "文件详情", R.drawable.ic_info))
                                        add(FilewayMenuEntry("delete", "删除记录", R.drawable.ic_trash, enabled = enabled, destructive = true))
                                    }, onItemClick = { item ->
                                        menu = false
                                        if (valid()) when (item.key) {
                                            "open" -> onOpen(entry)
                                            "location" -> onLocation?.invoke(entry)
                                            "info" -> if (entry.openable) onDetails?.invoke(entry) else unverified = entry
                                            "delete" -> controller.deleteSelected(setOf(entry.id))
                                        }
                                    })
                                }
                            },
                            context = {
                                if (!entry.openable) Text("原始路径无法确认，请从文件列表重新访问此项", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error)
                            },
                        )
                    }
                }
            }
        }
    }
}
    deletion?.let { ids -> FilewayAlertDialog(onDismissRequest = { deletion = null }, title = { Text(if (ids.isEmpty()) "清空最近访问？" else "删除所选访问记录？") },
        text = { Text("仅删除" + (if (ids.isEmpty()) "当前账号的全部访问记录" else "所选 ${ids.size} 条访问记录") + "，服务器文件和播放记录保留。") },
        confirmButton = { FilewayButton({ controller.deleteSelected(ids, clear = ids.isEmpty(), onSaved = selection::close); deletion = null }, enabled = enabled) { Text("删除记录") } },
        dismissButton = { FilewayOutlinedButton({ deletion = null }) { Text("取消") } }) }
    unverified?.let { entry -> FileOperationSheet("访问记录详情", { unverified = null }, footer = {
        FilewaySheetActions(listOf(FilewayMenuEntry("delete", "删除记录", R.drawable.ic_trash, enabled = enabled, destructive = true))) { controller.deleteSelected(setOf(entry.id), onSaved = { unverified = null }) }
    }) {
        Text(entry.name, style = MaterialTheme.typography.titleMedium)
        FileOperationLocation("位置", entry.path, showBasename = false)
        Text("原始路径无法确认，暂不可打开文件；可以删除此条访问记录。", color = MaterialTheme.colorScheme.error)
    } }
}

@Composable private fun ColumnScope.RecentAccessMessage(title: String, message: String, action: String, onAction: () -> Unit) {
    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FilewayIcon(R.drawable.ic_history, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FilewayTextButton(onAction) { Text(action) }
        }
    }
}
