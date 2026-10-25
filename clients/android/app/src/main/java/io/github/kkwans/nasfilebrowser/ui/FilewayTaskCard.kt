package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.semantics.customActions
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
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
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
    onDoubleClick: (() -> Unit)? = null,
    selected: Boolean = false,
    body: @Composable ColumnScope.() -> Unit = {},
) {
    val shape = RoundedCornerShape(12.dp)
    val content: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { icon() }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                titleSupporting?.invoke()
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) { meta() }
                body()
            }
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { actions() }
        }
    }
    val click = onClick ?: titleAction
    Surface(modifier = modifier.fillMaxWidth().clip(shape).then(if (click != null || onLongClick != null)
        Modifier.combinedClickable(onClick = { click?.invoke() }, onLongClick = onLongClick, onDoubleClick = onDoubleClick,
            onLongClickLabel = if (onLongClick != null) "多选任务" else null) else Modifier).semantics { this.selected = selected
            if (onDoubleClick != null) customActions = listOf(androidx.compose.ui.semantics.CustomAccessibilityAction("打开") { onDoubleClick(); true })
        },
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
    FilewayIconButton(onClick, enabled = enabled, modifier = Modifier.size(48.dp)) {
        FilewayIcon(icon, label, Modifier.size(24.dp))
    }
}

internal fun transferRuntime(millis: Long, completeHistory: Boolean): String {
    val seconds = millis.coerceAtLeast(0) / 1000
    val value = when {
        seconds >= 3600 -> "${seconds / 3600}时${seconds % 3600 / 60}分"
        seconds >= 60 -> "${seconds / 60}分${seconds % 60}秒"
        else -> "$seconds 秒"
    }
    return (if (completeHistory) "用时 " else "升级后用时 ") + value
}

/** Progress stays in the information column; primary transport control owns the leading slot. */
@Composable internal fun TransferProgressRow(progress: String, fraction: Float, speed: String?, indeterminate: Boolean) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(listOfNotNull(progress, speed).joinToString(" · "), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (indeterminate) LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp))
        else LinearProgressIndicator(progress = { fraction }, Modifier.fillMaxWidth().height(3.dp), gapSize = 0.dp, drawStopIndicator = {})
    }
}

@Composable internal fun TransferPrimaryControl(running: Boolean, label: String, enabled: Boolean, onClick: () -> Unit) {
    FilewayTonalButton(onClick, Modifier.size(48.dp), enabled = enabled, contentPadding = PaddingValues(0.dp),
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = .09f),
            contentColor = MaterialTheme.colorScheme.primary)) {
        FilewayIcon(if (running) R.drawable.art_pause else R.drawable.art_play, label, Modifier.size(24.dp))
    }
}

/** One information axis for short record names, matching the library object rows. */
@Composable internal fun TaskRecordMeta(status: String, timestamp: Long, color: androidx.compose.ui.graphics.Color) {
    Text(buildAnnotatedString {
        withStyle(SpanStyle(color = color)) { append(status) }
        append(" · "); append(serverTime(timestamp))
    }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2, overflow = TextOverflow.Ellipsis)
}

internal fun transferSummary(bytes: Long, millis: Long, completeHistory: Boolean): String =
    if (!completeHistory && millis <= 0) "运行耗时未记录" else transferRuntime(millis, completeHistory) +
        (io.github.kkwans.nasfilebrowser.data.transferAverageBytesPerSecond(bytes, millis, completeHistory)
            ?.let { " · 平均 ${readableSize(it)}/s" } ?: "")

/** Creation time stays at end; active elapsed replaces completed-file size at start. */
@Composable internal fun RowScope.TransferListMeta(createdAt: Long, millis: Long,
    completeHistory: Boolean, showElapsed: Boolean, expectedSize: Long) {
    val elapsed = if (!showElapsed) null else when {
        millis <= 0 -> "用时 —"
        millis < 1000 -> if (completeHistory) "用时不足 1 秒" else "用时 —"
        completeHistory -> transferRuntime(millis, true)
        else -> transferRuntime(millis, true).replace("用时 ", "用时 ≥ ")
    }
    Text(elapsed ?: if (expectedSize >= 0) readableSize(expectedSize) else "大小待确认",
        Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1, overflow = TextOverflow.Ellipsis)
    Text(serverTime(createdAt), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
}
