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
    var universe by mutableStateOf(emptySet<String>())
    fun invert() { select(universe - ids) }
    fun toggleMode() { if (active) close() else start() }
    fun clear() { ids = emptySet() }
    fun start(id: String? = null) { active = true; if (id != null) ids = ids + id }
    fun toggle(id: String) { ids = if (id in ids) ids - id else ids + id }
    fun select(values: Collection<String>) { active = true; ids = values.toSet() }
    fun retain(values: Set<String>) { ids = ids.intersect(values) }
    fun close() { active = false; ids = emptySet() }
}

@Composable internal fun rememberFilewayTaskSelection(key: Any?, ids: List<String>, pageActive: Boolean = LocalFilewayPageActive.current, complete: Boolean = true): FilewayTaskSelection {
    val scope = when (key) { is Pair<*, *> -> key.first; is List<*> -> key.firstOrNull(); else -> key }
    val selection = remember(scope) { FilewayTaskSelection() }
    LaunchedEffect(key) { selection.clear() }
    LaunchedEffect(ids, complete) { selection.universe = ids.toSet(); if (complete) selection.retain(ids.toSet()) }
    BackHandler(selection.active && pageActive) { selection.close() }
    return selection
}

@Composable internal fun FilewayTaskSelectionBar(selection: FilewayTaskSelection, enabled: Boolean,
    onSelectAll: () -> Unit, onInvert: () -> Unit = selection::invert, actions: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("已选 ${selection.ids.size} 项", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        FilewayIconButton(onSelectAll, enabled = enabled) { FilewayIcon(R.drawable.ic_select_all, "全选或取消全选", Modifier.size(22.dp)) }
        actions()
        FilewayIconButton(onInvert, enabled = enabled) { FilewayIcon(R.drawable.ic_invert_selection, "反选", Modifier.size(24.dp)) }
    }
}

@Composable internal fun FilewaySelectionToggle(selection: FilewayTaskSelection, label: String, enabled: Boolean) {
    FilewayIconButton(selection::toggleMode, enabled = enabled || selection.active) {
        FilewayIcon(if (selection.active) R.drawable.ic_close else R.drawable.ic_select_multiple,
            if (selection.active) "退出多选" else label, Modifier.size(24.dp))
    }
}
