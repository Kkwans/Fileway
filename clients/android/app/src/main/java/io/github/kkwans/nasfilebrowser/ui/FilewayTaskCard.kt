package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.kkwans.nasfilebrowser.R

internal data class FilewayTaskAction(
    val key: String,
    val label: String,
    val icon: Int,
    val enabled: Boolean = true,
    val primary: Boolean = false,
)

/** Compact task layout shared by server, download, and upload task lists. */
@Composable
internal fun FilewayTaskCard(
    title: String,
    icon: @Composable () -> Unit,
    meta: @Composable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
    titleSupporting: (@Composable () -> Unit)? = null,
    titleAction: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    selected: Boolean = false,
    body: @Composable ColumnScope.() -> Unit = {},
) {
    val shape = RoundedCornerShape(12.dp)
    val content: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { icon() }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        titleSupporting?.invoke()
                    }
                    actions()
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) { meta() }
                body()
            }
        }
    }
    val click = onClick ?: titleAction
    Surface(modifier = modifier.fillMaxWidth().clip(shape).then(if (click != null || onLongClick != null)
        Modifier.combinedClickable(onClick = { click?.invoke() }, onLongClick = onLongClick,
            onLongClickLabel = if (onLongClick != null) "多选任务" else null) else Modifier).semantics { this.selected = selected },
        shape = shape, color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .09f) else MaterialTheme.colorScheme.surface,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null) { content() }
}

@Composable
internal fun FilewayTaskActionStrip(
    actions: List<FilewayTaskAction>,
    onAction: (String) -> Unit,
    moreLabel: String = "更多任务操作",
    alwaysMenu: Boolean = false,
) {
    if (actions.isEmpty()) return
    val primary = actions.firstOrNull { it.primary }
    if (!alwaysMenu && actions.size <= 2) {
        Row(horizontalArrangement = Arrangement.spacedBy(0.dp), verticalAlignment = Alignment.CenterVertically) {
            (actions.filterNot { it === primary } + listOfNotNull(primary)).forEach { action ->
                FilewayTaskIconButton(action.icon, action.label, action.enabled) { onAction(action.key) }
            }
        }
        return
    }
    val overflow = actions
    var expanded by remember(actions.joinToString("|") { it.key }) { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box {
            FilewayTaskIconButton(R.drawable.ic_more_actions, moreLabel, overflow.any { it.enabled }) { expanded = true }
            FilewayMenu(expanded, { expanded = false }, items = overflow.map {
                FilewayMenuEntry(it.key, it.label, it.icon, enabled = it.enabled)
            }, onItemClick = { item -> expanded = false; onAction(item.key) })
        }
    }
}

@Composable
internal fun FilewayTaskIconButton(
    icon: Int,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    IconButton(onClick, enabled = enabled, modifier = Modifier.size(48.dp)) {
        Icon(painterResource(icon), label, Modifier.size(22.dp))
    }
}

internal fun transferRuntime(millis: Long, completeHistory: Boolean): String {
    val seconds = millis.coerceAtLeast(0) / 1000
    val value = when {
        seconds >= 3600 -> "${seconds / 3600}时${seconds % 3600 / 60}分"
        seconds >= 60 -> "${seconds / 60}分${seconds % 60}秒"
        else -> "$seconds 秒"
    }
    return (if (completeHistory) "运行 " else "已记录 ") + value
}

@Composable internal fun TransferProgressRow(progress: String, fraction: Float, speed: String?, indeterminate: Boolean,
    toggleEnabled: Boolean, running: Boolean, toggleLabel: String, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(listOfNotNull(progress, speed).joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (indeterminate) LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp))
            else LinearProgressIndicator(progress = { fraction }, Modifier.fillMaxWidth().height(3.dp), gapSize = 0.dp, drawStopIndicator = {})
        }
        FilewayTaskIconButton(if (running) R.drawable.art_pause else R.drawable.art_play, toggleLabel, toggleEnabled, onToggle)
    }
}

internal fun transferSummary(bytes: Long, millis: Long, completeHistory: Boolean): String =
    transferRuntime(millis, completeHistory) +
        (io.github.kkwans.nasfilebrowser.data.transferAverageBytesPerSecond(bytes, millis, completeHistory)
            ?.let { " · 平均 ${readableSize(it)}/s" } ?: "")
