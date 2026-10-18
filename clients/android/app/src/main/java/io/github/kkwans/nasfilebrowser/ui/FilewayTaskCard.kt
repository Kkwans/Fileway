package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
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
    body: @Composable ColumnScope.() -> Unit = {},
) {
    val shape = RoundedCornerShape(12.dp)
    val titleModifier = titleAction?.let { Modifier.clickable(onClick = it) } ?: Modifier
    val content: @Composable () -> Unit = {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { icon() }
                Column(
                    Modifier.weight(1f).then(titleModifier),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    titleSupporting?.invoke()
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) { meta() }
                Box(
                    Modifier.widthIn(min = 48.dp),
                    contentAlignment = Alignment.CenterEnd,
                ) { actions() }
            }
            body()
        }
    }
    if (onClick == null) {
        Surface(modifier = modifier.fillMaxWidth(), shape = shape, color = MaterialTheme.colorScheme.surface) { content() }
    } else {
        Surface(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = shape, color = MaterialTheme.colorScheme.surface) { content() }
    }
}

@Composable
internal fun FilewayTaskActionStrip(
    actions: List<FilewayTaskAction>,
    onAction: (String) -> Unit,
    moreLabel: String = "更多任务操作",
) {
    if (actions.isEmpty()) return
    if (actions.size <= 2) {
        Row(horizontalArrangement = Arrangement.spacedBy(0.dp), verticalAlignment = Alignment.CenterVertically) {
            actions.forEach { action ->
                FilewayTaskIconButton(action.icon, action.label, action.enabled) { onAction(action.key) }
            }
        }
        return
    }
    var expanded by remember(actions.joinToString("|") { it.key }) { mutableStateOf(false) }
    Box {
        FilewayTaskIconButton(R.drawable.ic_more_horiz, moreLabel, actions.any { it.enabled }) { expanded = true }
        DropdownMenu(expanded, { expanded = false }) {
            actions.forEach { action ->
                DropdownMenuItem(
                    text = { Text(action.label) },
                    onClick = { expanded = false; onAction(action.key) },
                    enabled = action.enabled,
                )
            }
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
