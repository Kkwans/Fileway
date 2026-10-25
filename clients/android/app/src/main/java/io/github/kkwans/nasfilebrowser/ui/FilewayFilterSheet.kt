package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.kkwans.nasfilebrowser.R

private val filewayFilterShape = RoundedCornerShape(12.dp)

/** The callers retain calendar conversion and draft ownership. */
@Composable
internal fun FilewayDateRangeFields(
    start: String, end: String, canClear: Boolean,
    onStart: () -> Unit, onEnd: () -> Unit, onClear: () -> Unit,
    invalidRange: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            FilewayFilterField("开始日期", start, onStart, Modifier.weight(1f))
            FilewayFilterField("结束日期", end, onEnd, Modifier.weight(1f))
            if (canClear) FilewayIconButton(onClear, modifier = Modifier.size(48.dp)) {
                FilewayIcon(R.drawable.ic_close, "清除日期", Modifier.size(22.dp))
            }
        }
        if (invalidRange) Text("结束日期不能早于开始日期", color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun FilewayFilterSheet(
    title: String,
    onDismiss: () -> Unit,
    onReset: () -> Unit,
    onApply: () -> Unit,
    applyEnabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    FileOperationSheet(title = title, onDismiss = onDismiss, footer = {
        FilewayTextButton(onClick = onReset, shape = filewayFilterShape) { Text("重置条件") }
        Spacer(Modifier.weight(1f))
        FilewayButton(onClick = onApply, enabled = applyEnabled, shape = filewayFilterShape) { Text("应用") }
    }) {
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
internal fun FilewayFilterField(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    FilewayOutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
        shape = filewayFilterShape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                value,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        FilewayIcon(
            R.drawable.ic_expand_more,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
