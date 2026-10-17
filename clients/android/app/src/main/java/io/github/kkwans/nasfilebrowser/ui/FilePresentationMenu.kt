package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.app.FileCategory
import io.github.kkwans.nasfilebrowser.app.FileLayout
import io.github.kkwans.nasfilebrowser.app.FileOrder
import io.github.kkwans.nasfilebrowser.data.CompactGridSize
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayTheme

@Composable internal fun FileLayoutMenu(layout: FileLayout, enabled: Boolean = true,
    compactGridSize: CompactGridSize = CompactGridSize.MEDIUM,
    onCompactGridSize: (CompactGridSize) -> Unit = {}, select: (FileLayout) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val colors = FilewayTheme.colors
    Box {
        TextButton(onClick = { expanded = true }, enabled = enabled, shape = RoundedCornerShape(8.dp),
            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "切换文件布局，当前${layout.label}" }) {
            Text(if (layout == FileLayout.COMPACT) "紧凑 · ${compactGridSize.label}" else layout.label,
                style = MaterialTheme.typography.labelLarge, color = colors.primary)
            Spacer(Modifier.width(4.dp))
            Icon(painterResource(R.drawable.ic_expand_more), null, Modifier.size(18.dp), tint = colors.primary)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, containerColor = colors.surfaceContainerHigh,
            modifier = Modifier.width(248.dp)) {
            FileLayout.entries.forEach { option ->
                DropdownMenuItem(text = {
                    Text(option.label, Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                        color = if (layout == option) colors.primary else colors.onSurface,
                        style = MaterialTheme.typography.labelLarge)
                }, leadingIcon = {
                    Icon(painterResource(layoutIcon(option)), null, Modifier.size(22.dp),
                        tint = if (layout == option) colors.primary else colors.onSurfaceVariant)
                }, trailingIcon = { MenuCheck(layout == option) }, enabled = enabled,
                    onClick = { select(option); expanded = false })
                if (option == FileLayout.COMPACT) {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp).selectableGroup(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CompactGridSize.entries.forEach { size ->
                            val active = layout == FileLayout.COMPACT && compactGridSize == size
                            Column(Modifier.weight(1f).background(if (active) colors.primaryContainer else colors.surface,
                                RoundedCornerShape(8.dp)).selectable(selected = active, enabled = enabled, role = Role.RadioButton,
                                onClick = { onCompactGridSize(size); select(FileLayout.COMPACT); expanded = false })
                                .heightIn(min = 48.dp).padding(vertical = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                Text(size.label, style = MaterialTheme.typography.labelLarge,
                                    color = if (active) colors.primary else colors.onSurface)
                                Text("${size.columns} 列", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun layoutIcon(layout: FileLayout) = when (layout) {
    FileLayout.COVER -> R.drawable.ic_layout_cover
    FileLayout.DETAIL -> R.drawable.ic_layout_large
    FileLayout.LIST -> R.drawable.ic_layout_list
    FileLayout.COMPACT -> R.drawable.ic_layout_compact
    FileLayout.UNBOUNDED -> R.drawable.ic_layout_unbounded
}

@Composable private fun MenuCheck(selected: Boolean) {
    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        if (selected) Icon(painterResource(R.drawable.ic_check), "当前选项", Modifier.size(20.dp), tint = FilewayTheme.colors.primary)
    }
}

@Composable internal fun FileCategoryMenu(value: FileCategory, enabled: Boolean = true, select: (FileCategory) -> Unit) =
    FileCategoryMenu(if (value == FileCategory.ALL) emptySet() else setOf(value), enabled) { select((it - value).firstOrNull() ?: it.firstOrNull() ?: FileCategory.ALL) }

@Composable internal fun FileCategoryMenu(values: Set<FileCategory>, enabled: Boolean = true, select: (Set<FileCategory>) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = if (values.isEmpty()) "全部" else if (values.size == 1) values.first().label else "${values.size} 类"
    Box {
        TextButton({ expanded = true }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("类型 · $label", style = MaterialTheme.typography.labelLarge, color = FilewayTheme.colors.primary)
        }
        DropdownMenu(expanded, { expanded = false }, containerColor = FilewayTheme.colors.surfaceContainerHigh,
            modifier = Modifier.widthIn(min = 184.dp)) {
            FileCategory.entries.forEach { item ->
                val checked = if (item == FileCategory.ALL) values.isEmpty() else item in values
                DropdownMenuItem(text = { Text(item.label) }, trailingIcon = { MenuCheck(checked) }, enabled = enabled,
                    onClick = { select(if (item == FileCategory.ALL) emptySet() else if (checked) values - item else values + item) })
            }
        }
    }
}

@Composable internal fun FileOrderMenu(value: FileOrder, enabled: Boolean = true, select: (FileOrder) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }, enabled = enabled,
            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "文件排序，当前${value.label}" }) {
            Text(value.label, style = MaterialTheme.typography.labelLarge, color = FilewayTheme.colors.primary)
        }
        DropdownMenu(expanded, { expanded = false }, containerColor = FilewayTheme.colors.surfaceContainerHigh,
            modifier = Modifier.widthIn(min = 200.dp)) {
            FileOrder.entries.forEach { item ->
                DropdownMenuItem(text = { Text(item.label, style = MaterialTheme.typography.labelLarge) },
                    trailingIcon = { MenuCheck(item == value) }, enabled = enabled,
                    onClick = { expanded = false; select(item) })
            }
        }
    }
}
