package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.LocalDensity
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayTheme
import androidx.compose.ui.unit.dp
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.data.DirectoryCrumb
import io.github.kkwans.nasfilebrowser.data.directoryTrail

@Composable internal fun DirectoryBreadcrumbs(path: String, wirePath: String, busy: Boolean, back: () -> Unit, jump: (DirectoryCrumb) -> Unit) {
    val trail = remember(path, wirePath) { directoryTrail(path, wirePath) }
    var expanded by remember(path, wirePath) { mutableStateOf(false) }
    val colors = FilewayTheme.colors
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val style = FilewayTheme.text.fileName
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = back, enabled = !busy, modifier = Modifier.size(48.dp)) {
            Icon(painterResource(R.drawable.ic_arrow_back), "上一级", Modifier.size(22.dp), tint = colors.onSurfaceVariant)
        }
        BoxWithConstraints(Modifier.weight(1f)) {
            val available = with(density) { maxWidth.roundToPx() }
            val padding = with(density) { 16.dp.roundToPx() }
            val target = with(density) { 48.dp.roundToPx() }
            val separator = measurer.measure("/", style = MaterialTheme.typography.bodySmall).size.width +
                with(density) { 8.dp.roundToPx() }
            val widths = trail.map { measurer.measure(it.label, style = style, softWrap = false).size.width + padding }
            val plan = planBreadcrumbWidths(widths, available, separator, target, target)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                @Composable fun separator() {
                    Box(Modifier.width(with(density) { separator.toDp() }), contentAlignment = Alignment.Center) {
                        Text("/", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                }
                if (plan.firstVisible > 0) {
                    FilewayTextButton(onClick = { expanded = true }, enabled = !busy, modifier = Modifier.size(48.dp)
                        .semantics { contentDescription = "展开完整路径" }, contentPadding = PaddingValues(0.dp)) {
                        Text("…", color = colors.primary)
                    }
                    separator()
                }
                trail.drop(plan.firstVisible).forEachIndexed { index, crumb ->
                    if (index > 0) separator()
                    val current = crumb.path == path
                    FilewayTextButton(onClick = { if (current) expanded = true else jump(crumb) },
                        enabled = !busy && (current || crumb.wirePath != null),
                        modifier = Modifier.width(with(density) { plan.widths[index].toDp() }).heightIn(min = 48.dp)
                            .semantics { contentDescription = if (current) "当前目录：${crumb.label}，展开完整路径" else "进入目录：${crumb.label}" },
                        contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text(crumb.label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = style,
                            color = if (current) colors.onSurface else colors.primary)
                    }
                }
            }
        }
    }
    if (expanded) FilewayAlertDialog(onDismissRequest = { expanded = false }, shape = RoundedCornerShape(12.dp),
        containerColor = colors.surface,
        title = { Text("目录路径", style = MaterialTheme.typography.titleLarge) },
        text = {
            LazyColumn(Modifier.heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item(key = "full-path") { SelectionContainer { Text(path, style = MaterialTheme.typography.bodySmall) } }
                items(trail, key = { it.path }) { crumb ->
                    FilewayTextButton(onClick = { expanded = false; jump(crumb) }, enabled = !busy && crumb.wirePath != null && crumb.path != path,
                        modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(crumb.label, style = MaterialTheme.typography.bodyMedium)
                            if (crumb.path != "/") Text(crumb.path, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (crumb.wirePath == null) Text("此路径无法安全跳转", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }, confirmButton = { FilewayTextButton(onClick = { expanded = false }) { Text("关闭") } })
}
