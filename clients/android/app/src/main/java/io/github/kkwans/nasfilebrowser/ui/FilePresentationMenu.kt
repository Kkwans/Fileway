package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.app.FileCategory
import io.github.kkwans.nasfilebrowser.app.FileLayout
import io.github.kkwans.nasfilebrowser.app.FileOrder
import io.github.kkwans.nasfilebrowser.data.CompactGridSize
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayTheme

@Composable
internal fun FileLayoutMenu(
    layout: FileLayout,
    enabled: Boolean = true,
    compactGridSize: CompactGridSize = CompactGridSize.MEDIUM,
    onCompactGridSize: (CompactGridSize) -> Unit = {},
    select: (FileLayout) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val colors = FilewayTheme.colors
    val compactMetrics = rememberCompactGridMenuMetrics()
    val entries = FileLayout.entries.map { option ->
        FilewayMenuEntry(
            key = option.name,
            label = option.label,
            icon = layoutIcon(option),
            selected = layout == option,
            enabled = enabled,
        )
    }
    Box {
        TextButton(
            onClick = { expanded = true },
            enabled = enabled,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.heightIn(min = 48.dp).semantics {
                contentDescription = "切换文件布局，当前${layout.label}"
            },
        ) {
            Text(
                if (layout == FileLayout.COMPACT) "紧凑 · ${compactGridSize.label}" else layout.label,
                style = MaterialTheme.typography.labelLarge,
                color = colors.primary,
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                painterResource(R.drawable.ic_expand_more),
                null,
                Modifier.size(18.dp),
                tint = colors.primary,
            )
        }
        FilewayMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            items = entries,
            selection = FilewayMenuSelection.Single,
            alignment = FilewayMenuAlignment.Start,
            extraContentWidth = compactMetrics.width,
            itemExtras = { item ->
                if (item.key == FileLayout.COMPACT.name) {
                    CompactGridSizeMenu(
                        layout = layout,
                        compactGridSize = compactGridSize,
                        enabled = enabled,
                        metrics = compactMetrics,
                        onCompactGridSize = onCompactGridSize,
                        select = select,
                        close = { expanded = false },
                    )
                }
            },
            onItemClick = { item ->
                FileLayout.entries.firstOrNull { it.name == item.key }?.let { option ->
                    select(option)
                    expanded = false
                }
            },
        )
    }
}

private fun layoutIcon(layout: FileLayout) = when (layout) {
    FileLayout.COVER -> R.drawable.ic_layout_cover
    FileLayout.DETAIL -> R.drawable.ic_layout_large
    FileLayout.LIST -> R.drawable.ic_layout_list
    FileLayout.COMPACT -> R.drawable.ic_layout_compact
    FileLayout.UNBOUNDED -> R.drawable.ic_layout_unbounded
}

private data class CompactGridMenuMetrics(val width: Dp, val cellWidths: List<Dp>)

@Composable
private fun rememberCompactGridMenuMetrics(): CompactGridMenuMetrics {
    val textMeasurer = rememberTextMeasurer()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val normalStyle = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Normal)
    val selectedStyle = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
    val secondaryStyle = MaterialTheme.typography.labelSmall
    val cellWidths = CompactGridSize.entries.map { size ->
        val labelWidth = maxOf(
            textMeasurer.measure(size.label, style = normalStyle).size.width,
            textMeasurer.measure(size.label, style = selectedStyle).size.width,
            textMeasurer.measure("${size.columns} 列", style = secondaryStyle).size.width,
        )
        (with(density) { labelWidth.toDp() } + 16.dp).coerceAtLeast(48.dp)
    }
    val width = cellWidths.fold(0.dp) { total, cellWidth -> total + cellWidth } +
        8.dp * (cellWidths.size - 1).toFloat() + 32.dp
    return CompactGridMenuMetrics(width = width, cellWidths = cellWidths)
}

@Composable
private fun CompactGridSizeMenu(
    layout: FileLayout,
    compactGridSize: CompactGridSize,
    enabled: Boolean,
    metrics: CompactGridMenuMetrics,
    onCompactGridSize: (CompactGridSize) -> Unit,
    select: (FileLayout) -> Unit,
    close: () -> Unit,
) {
    val colors = FilewayTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CompactGridSize.entries.forEachIndexed { index, size ->
            val active = layout == FileLayout.COMPACT && compactGridSize == size
            val alpha = if (enabled) 1f else .38f
            Column(
                Modifier.width(metrics.cellWidths[index])
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (active) colors.primaryContainer.copy(alpha = alpha) else colors.surface,
                    )
                    .selectable(
                        selected = active,
                        enabled = enabled,
                        role = Role.RadioButton,
                        onClick = {
                            onCompactGridSize(size)
                            select(FileLayout.COMPACT)
                            close()
                        },
                    )
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    size.label,
                    style = if (active) MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                    else MaterialTheme.typography.labelLarge,
                    color = (if (active) colors.primary else colors.onSurface).copy(alpha = alpha),
                    textAlign = TextAlign.Center,
                )
                Text(
                    "${size.columns} 列",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant.copy(alpha = alpha),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
internal fun FileCategoryMenu(
    value: FileCategory,
    enabled: Boolean = true,
    select: (FileCategory) -> Unit,
) = FileCategoryMenu(
    if (value == FileCategory.ALL) emptySet() else setOf(value),
    enabled,
) { selected ->
    select((selected - value).firstOrNull() ?: selected.firstOrNull() ?: FileCategory.ALL)
}

@Composable
internal fun FileCategoryMenu(
    values: Set<FileCategory>,
    enabled: Boolean = true,
    select: (Set<FileCategory>) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = if (values.isEmpty()) "全部" else if (values.size == 1) values.first().label else "${values.size} 类"
    val entries = FileCategory.entries.map { item ->
        FilewayMenuEntry(
            key = item.name,
            label = item.label,
            selected = if (item == FileCategory.ALL) values.isEmpty() else item in values,
            enabled = enabled,
        )
    }
    Box {
        TextButton(
            onClick = { expanded = true },
            enabled = enabled,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text("类型 · $label", style = MaterialTheme.typography.labelLarge, color = FilewayTheme.colors.primary)
        }
        FilewayMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            items = entries,
            selection = FilewayMenuSelection.Multiple,
            alignment = FilewayMenuAlignment.Start,
            onItemClick = { item ->
                FileCategory.entries.firstOrNull { it.name == item.key }?.let { option ->
                    val checked = if (option == FileCategory.ALL) values.isEmpty() else option in values
                    select(
                        when {
                            option == FileCategory.ALL -> emptySet()
                            checked -> values - option
                            else -> values + option
                        },
                    )
                }
            },
        )
    }
}

@Composable
internal fun FileOrderMenu(
    value: FileOrder,
    enabled: Boolean = true,
    select: (FileOrder) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val entries = FileOrder.entries.map { item ->
        FilewayMenuEntry(key = item.name, label = item.label, selected = item == value, enabled = enabled)
    }
    Box {
        TextButton(
            onClick = { expanded = true },
            enabled = enabled,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.heightIn(min = 48.dp).semantics {
                contentDescription = "文件排序，当前${value.label}"
            },
        ) {
            Text(value.label, style = MaterialTheme.typography.labelLarge, color = FilewayTheme.colors.primary)
        }
        FilewayMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            items = entries,
            selection = FilewayMenuSelection.Single,
            alignment = FilewayMenuAlignment.Start,
            onItemClick = { item ->
                FileOrder.entries.firstOrNull { it.name == item.key }?.let { option ->
                    expanded = false
                    select(option)
                }
            },
        )
    }
}
