package io.github.kkwans.nasfilebrowser.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.github.kkwans.nasfilebrowser.R

@Stable internal class FilewayTaskSelection {
    var active by mutableStateOf(false)
        private set
    var ids by mutableStateOf(emptySet<String>())
        private set
    fun start(id: String? = null) { active = true; if (id != null) ids = ids + id }
    fun toggle(id: String) { ids = if (id in ids) ids - id else ids + id }
    fun select(values: Collection<String>) { active = true; ids = values.toSet() }
    fun retain(values: Set<String>) { ids = ids.intersect(values) }
    fun close() { active = false; ids = emptySet() }
}

@Composable internal fun rememberFilewayTaskSelection(key: Any?, ids: List<String>, pageActive: Boolean = LocalFilewayPageActive.current): FilewayTaskSelection {
    val selection = remember(key) { FilewayTaskSelection() }
    LaunchedEffect(ids) { selection.retain(ids.toSet()) }
    BackHandler(selection.active && pageActive) { selection.close() }
    return selection
}

@Composable internal fun FilewayTaskSelectionBar(selection: FilewayTaskSelection, enabled: Boolean,
    onSelectAll: () -> Unit, actions: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("已选 ${selection.ids.size} 项", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        IconButton(onSelectAll, enabled = enabled) { Icon(painterResource(R.drawable.ic_select_multiple), "全选或取消全选", Modifier.size(22.dp)) }
        actions()
        IconButton(selection::close, enabled = enabled) { Icon(painterResource(R.drawable.ic_close), "退出多选", Modifier.size(22.dp)) }
    }
}
