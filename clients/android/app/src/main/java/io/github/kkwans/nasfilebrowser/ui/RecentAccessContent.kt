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
    FilewayRefresh(state.loading, { controller.refresh() }) {
Column(Modifier.fillMaxSize()) {
        if (!connected || state.scope.isEmpty()) {
            RecentAccessMessage("连接后查看最近访问", "成功进入目录或打开文件后，与同一账号的网页端同步。", "连接服务器", onConnect)
            return@Column
        }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        state.recordWarning?.let { message ->
            Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(12.dp)) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(painterResource(R.drawable.ic_info), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
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
                            enabled = entry.openable,
                            onOpen = { if (valid()) onOpen(entry) },
                            onLongClick = { menu = true },
                            trailing = {
                                Box {
                                    IconButton({ menu = true }, modifier = Modifier.size(48.dp)) {
                                        Icon(painterResource(R.drawable.ic_more_actions), "最近访问操作")
                                    }
                                    FilewayMenu(menu, { menu = false }, items = buildList {
                                        add(FilewayMenuEntry("open", "打开", R.drawable.ic_visibility, enabled = entry.openable))
                                        if (onLocation != null) add(FilewayMenuEntry("location", "打开所在目录", R.drawable.ic_folder, enabled = entry.openable))
                                        if (onDetails != null) add(FilewayMenuEntry("info", "文件信息", R.drawable.ic_info, enabled = entry.openable))
                                    }, onItemClick = { item ->
                                        menu = false
                                        if (valid()) when (item.key) {
                                            "open" -> onOpen(entry)
                                            "location" -> onLocation?.invoke(entry)
                                            "info" -> onDetails?.invoke(entry)
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

}

@Composable private fun ColumnScope.RecentAccessMessage(title: String, message: String, action: String, onAction: () -> Unit) {
    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(painterResource(R.drawable.ic_history), null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FilewayTextButton(onAction) { Text(action) }
        }
    }
}
