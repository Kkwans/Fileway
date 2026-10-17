package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

private fun favoriteColor(value: String, fallback: Color): Color = try { Color(android.graphics.Color.parseColor(value)) } catch (_: Exception) { fallback }

@Composable internal fun FavoritesScreen(model: ClientModel, client: ClientState) {
    val state by model.favorites.state.collectAsStateWithLifecycle()
    var filter by rememberSaveable(state.scope) { mutableStateOf<String?>(null) }
    var editing by remember(state.scope) { mutableStateOf<Favorite?>(null) }
    var managing by rememberSaveable(state.scope) { mutableStateOf(false) }
    val enabled = !state.loading && !state.changing && !client.busy
    LaunchedEffect(state.scope) { model.favorites.refresh() }
    LaunchedEffect(state.groups) { if (!filter.isNullOrEmpty() && state.groups.none { it.id == filter }) filter = null }
    val items = state.items.filter { filter == null || it.groupId == filter }
    LibraryScaffold(model, LibrarySection.FAVORITES, actions = {
    }) {
            LibrarySectionLabel("收藏分组", "按服务器顺序排列") {
                TextButton(onClick = { managing = true }, enabled = enabled && state.loaded) { Text("管理分组") }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val chipColors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = .10f),
                    selectedLabelColor = MaterialTheme.colorScheme.primary, selectedLeadingIconColor = MaterialTheme.colorScheme.primary)
                FilterChip(filter == null, { filter = null }, { Text("全部 ${state.items.size}") }, colors = chipColors)
                FilterChip(filter == "", { filter = "" }, { Text("未分组") }, colors = chipColors)
                state.groups.forEach { group -> FilterChip(filter == group.id, { filter = group.id }, { Text(group.name) },
                    colors = chipColors,
                    leadingIcon = { Box(Modifier.size(8.dp).background(favoriteColor(group.color, MaterialTheme.colorScheme.primary), CircleShape)) }) }
            }
            if (state.loading || state.changing || client.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            (state.error ?: client.error)?.let { message -> Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = model.favorites::refresh, enabled = enabled) { Text("刷新") }
            } }
            FilewayToast(state.notice)
            if (state.loaded && items.isEmpty() && !state.loading && state.error == null) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (state.items.isEmpty()) io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayStatusIcon(
                        io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayStatusAnimation.EMPTY_FAVORITES, state.scope, Modifier.size(40.dp), play = LocalFilewayPageActive.current)
                    else Icon(painterResource(R.drawable.ic_bookmark), null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(if (state.items.isEmpty()) "常用文件，随手收藏" else if (filter == "") "还没有未分组的收藏" else "这个分组还没有收藏", style = MaterialTheme.typography.titleMedium)
                    Text(if (state.items.isEmpty()) "长按文件或文件夹，在详情中加入收藏。这里与网页端同步。" else "试试其他分组，或在收藏的更多菜单中调整分组。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { model.tab("files") }) { Text("浏览文件") }
                }
            } else LazyColumn(Modifier.weight(1f).semantics { contentDescription = "服务端收藏列表" }, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items, key = { it.id }) { favorite ->
                    var menu by remember(favorite.id) { mutableStateOf(false) }
                    val basename = libraryBasename(favorite.path)
                    LibraryObjectCard(favorite.name.ifEmpty { basename }, "位置 · ${libraryParent(favorite.path)}", R.drawable.ic_bookmark,
                        onOpen = { model.openFavorite(favorite, state.scope) }, enabled = enabled,
                        trailing = {
                            Box {
                                IconButton({ menu = true }, enabled = enabled) { Icon(painterResource(R.drawable.ic_more_vert), "收藏更多：${favorite.name.ifEmpty { basename }}") }
                                DropdownMenu(menu, { menu = false }) {
                                    DropdownMenuItem({ Text("编辑收藏") }, { menu = false; editing = favorite })
                                    DropdownMenuItem({ Text("上移") }, { menu = false; model.favorites.move(favorite, -1) }, enabled = state.items.firstOrNull()?.id != favorite.id)
                                    DropdownMenuItem({ Text("下移") }, { menu = false; model.favorites.move(favorite, 1) }, enabled = state.items.lastOrNull()?.id != favorite.id)
                                    DropdownMenuItem({ Text("取消收藏") }, { menu = false; model.favorites.remove(favorite) {} })
                                }
                            }
                        }) {
                        if (favorite.name.isNotEmpty() && favorite.name != basename) Text("原名 · $basename", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        state.groups.firstOrNull { it.id == favorite.groupId }?.let { group ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Box(Modifier.size(6.dp).background(favoriteColor(group.color, MaterialTheme.colorScheme.primary), CircleShape))
                                Text(group.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (!favorite.openable) Text("原始路径待确认，打开前请刷新核对", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
    }
    editing?.let { item ->
        var name by remember(item.id) { mutableStateOf(item.name) }
        var group by remember(item.id) { mutableStateOf(item.groupId) }
        AlertDialog(onDismissRequest = { if (!state.changing) editing = null }, title = { Text("编辑收藏") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.changing) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                OutlinedTextField(name, { name = it }, label = { Text("显示名称") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = enabled)
                Text(item.path, style = MaterialTheme.typography.bodySmall)
                Text("收藏分组", style = MaterialTheme.typography.titleSmall)
                FavoriteGroupChoice("", "未分组", group, enabled) { group = "" }
                state.groups.forEach { folder -> FavoriteGroupChoice(folder.id, folder.name, group, enabled) { group = folder.id } }
                Row {
                    TextButton(onClick = { model.favorites.move(item, -1) }, enabled = enabled && state.items.firstOrNull()?.id != item.id) { Text("上移") }
                    TextButton(onClick = { model.favorites.move(item, 1) }, enabled = enabled && state.items.lastOrNull()?.id != item.id) { Text("下移") }
                    TextButton(onClick = { model.favorites.remove(item) { editing = null } }, enabled = enabled) { Text("取消收藏") }
                }
            }
        }, confirmButton = { TextButton(onClick = { model.favorites.update(item, name, group) { editing = null } }, enabled = enabled && name.isNotBlank()) { Text(if (state.changing) "正在保存" else "保存") } },
            dismissButton = { TextButton(onClick = { editing = null }, enabled = !state.changing) { Text("取消") } })
    }
    if (managing) FavoriteGroupsDialog(model) { managing = false }
}

@Composable private fun FavoriteGroupChoice(id: String, name: String, selected: String, enabled: Boolean = true, choose: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = id == selected, enabled = enabled, role = Role.RadioButton, onClick = choose), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(id == selected, onClick = null, enabled = enabled); Text(name, Modifier.padding(start = 8.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun FavoriteGroupsDialog(model: ClientModel, dismiss: () -> Unit) {
    val state by model.favorites.state.collectAsStateWithLifecycle()
    var create by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<FavoriteGroup?>(null) }
    var deleting by remember { mutableStateOf<FavoriteGroup?>(null) }
    val enabled = !state.changing && !state.loading
    AlertDialog(onDismissRequest = { if (!state.changing) dismiss() }, title = { Text("收藏分组") }, text = {
        Column {
            if (state.changing) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (state.groups.isEmpty()) Text("用分组整理电影、照片和常用文件夹。", style = MaterialTheme.typography.bodyMedium)
            LazyColumn(Modifier.heightIn(max = 400.dp)) {
                items(state.groups, key = { it.id }) { group ->
                    var menu by remember(group.id) { mutableStateOf(false) }
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(12.dp).background(favoriteColor(group.color, MaterialTheme.colorScheme.primary), CircleShape))
                        Text(group.name, Modifier.weight(1f).padding(horizontal = 12.dp), style = MaterialTheme.typography.titleSmall)
                        Box {
                            IconButton({ menu = true }, enabled = enabled) { Icon(painterResource(R.drawable.ic_more_vert), "分组更多：${group.name}") }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem({ Text("编辑分组") }, { menu = false; editing = group })
                                DropdownMenuItem({ Text("上移") }, { menu = false; model.favorites.moveGroup(group, -1) }, enabled = state.groups.firstOrNull()?.id != group.id)
                                DropdownMenuItem({ Text("下移") }, { menu = false; model.favorites.moveGroup(group, 1) }, enabled = state.groups.lastOrNull()?.id != group.id)
                                DropdownMenuItem({ Text("删除分组") }, { menu = false; deleting = group })
                            }
                        }
                    }
                }
            }
        }
    }, confirmButton = { TextButton({ create = true }, enabled = enabled) { Text("新建分组") } }, dismissButton = { TextButton(dismiss, enabled = !state.changing) { Text("完成") } })
    if (create || editing != null) {
        val group = editing
        var name by remember(group) { mutableStateOf(group?.name.orEmpty()) }
        var color by remember(group) { mutableStateOf(group?.color ?: "#3F72D8") }
        val palette = listOf("#E5484D", "#D95876", "#F06A5B", "#F28C28", "#DDAA1D", "#D6BE21", "#86B83E", "#35A867", "#2AA889", "#28AFC0", "#3A9BD9", "#3F72D8", "#5B62D9", "#7656C9", "#9B4DB5", "#C34F90", "#758195")
        AlertDialog(onDismissRequest = { if (!state.changing) { create = false; editing = null } }, title = { Text(if (group == null) "新建分组" else "编辑分组") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.changing) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                OutlinedTextField(name, { name = it }, modifier = Modifier.fillMaxWidth(), label = { Text("分组名称") }, singleLine = true, enabled = enabled)
                Text("标记颜色", style = MaterialTheme.typography.titleSmall)
                FlowRow {
                    palette.forEachIndexed { index, value -> Box(Modifier.size(48.dp).selectable(selected = value == color, enabled = enabled, role = Role.RadioButton) { color = value }
                        .semantics { contentDescription = "分组颜色 ${index + 1}" }, contentAlignment = Alignment.Center) {
                        Box(Modifier.size(28.dp).border(if (value == color) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                            .padding(3.dp).background(favoriteColor(value, MaterialTheme.colorScheme.primary), CircleShape))
                    } }
                }
            }
        }, confirmButton = { TextButton({ model.favorites.saveGroup(group, name, color) { create = false; editing = null } }, enabled = enabled && name.isNotBlank()) { Text(if (state.changing) "正在保存" else "保存") } },
            dismissButton = { TextButton({ create = false; editing = null }, enabled = !state.changing) { Text("取消") } })
    }
    deleting?.let { group -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除分组“${group.name}”？") },
        text = { Text("组内收藏将移到“未分组”，文件保持原位。网页端也会同步此变更。") },
        confirmButton = { TextButton({ model.favorites.removeGroup(group); deleting = null }, enabled = enabled) { Text("删除分组") } },
        dismissButton = { TextButton({ deleting = null }) { Text("取消") } }) }
}
