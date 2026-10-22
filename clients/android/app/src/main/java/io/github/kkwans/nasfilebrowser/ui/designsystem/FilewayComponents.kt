package io.github.kkwans.nasfilebrowser.ui.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.kkwans.nasfilebrowser.R

/** Compact content bar. The screen's root scaffold owns all system insets. */
@Composable
fun FilewayTopAppBar(
    title: String,
    subtitle: String? = null,
    leadingIcon: Int,
    leadingDescription: String,
    onLeadingClick: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().height(FilewaySpace.appBar)
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = FilewaySpace.tiny),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onLeadingClick, modifier = Modifier.size(FilewaySpace.target)) {
            Icon(painterResource(leadingIcon), leadingDescription, Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onBackground)
        }
        Column(Modifier.weight(1f).padding(horizontal = FilewaySpace.tiny)) {
            Text(
                title,
                modifier = Modifier.semantics { heading() },
                style = FilewayTheme.text.appBar,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = FilewayTheme.text.metadata,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
            Row(verticalAlignment = Alignment.CenterVertically, content = actions)
        }
    }
}

/** Fixed leading and trailing slots keep short and long menu labels on one axis. */
@Composable
fun FilewayMenuItem(
    text: String,
    onClick: () -> Unit,
    icon: Int? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
) {
    DropdownMenuItem(
        text = { Text(text, style = MaterialTheme.typography.bodyMedium) },
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.heightIn(min = FilewaySpace.target).semantics { this.selected = selected },
        leadingIcon = {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                if (icon != null) Icon(painterResource(icon), null, Modifier.size(22.dp))
            }
        },
        trailingIcon = {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                if (selected) Icon(painterResource(R.drawable.ic_check), null, Modifier.size(20.dp))
            }
        },
    )
}

/** The whole row is the single radio target; its visual radio adds no second node. */
@Composable
fun FilewaySelectionRow(
    title: String,
    subtitle: String? = null,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val emphasis = if (enabled) 1f else .38f
    Row(
        Modifier.fillMaxWidth().heightIn(min = FilewaySpace.target)
            .clip(MaterialTheme.shapes.medium)
            .background(if (selected) colors.primaryContainer else colors.surface)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = FilewaySpace.page, vertical = FilewaySpace.small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FilewaySpace.content),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FilewaySpace.tiny)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface.copy(alpha = emphasis))
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = FilewayTheme.text.metadata, color = colors.onSurfaceVariant.copy(alpha = emphasis))
            }
        }
        RadioButton(
            selected = selected,
            onClick = null,
            enabled = enabled,
            modifier = Modifier.size(24.dp).clearAndSetSemantics {},
        )
    }
}

/** Object values remain separate; callers retain the original identity and wire path. */
@Composable
fun FilewayObjectSummary(name: String, location: String? = null, metadata: String? = null) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = FilewaySpace.page, vertical = FilewaySpace.content),
        verticalArrangement = Arrangement.spacedBy(FilewaySpace.tiny),
    ) {
        Text(name, style = FilewayTheme.text.fileName, color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (!location.isNullOrBlank()) {
            Text(location, style = FilewayTheme.text.metadata, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!metadata.isNullOrBlank()) {
            Text(metadata, style = FilewayTheme.text.metadata, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Content-sized empty state, suitable for an empty collection or a bounded panel. */
@Composable
fun FilewayEmptyState(
    icon: Int,
    title: String,
    description: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        Modifier.fillMaxWidth().padding(FilewaySpace.section),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(painterResource(icon), null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(FilewaySpace.content))
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center)
        Spacer(Modifier.height(FilewaySpace.tiny))
        Text(description, modifier = Modifier.widthIn(max = 360.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (onAction != null && !actionLabel.isNullOrBlank()) {
            Spacer(Modifier.height(FilewaySpace.content))
            Button(onClick = onAction, modifier = Modifier.heightIn(min = FilewaySpace.target).widthIn(min = FilewaySpace.target)) {
                Text(actionLabel, textAlign = TextAlign.Center)
            }
        }
    }
}

enum class FilewayStatusKind { NEUTRAL, LOADING, SUCCESS, WARNING, ERROR }

/** A lightweight result surface; LOADING is reserved for a real indeterminate operation. */
@Composable
fun FilewayStatusBanner(
    message: String,
    kind: FilewayStatusKind = FilewayStatusKind.NEUTRAL,
    onAction: (() -> Unit)? = null,
    actionLabel: String? = null,
    onDismiss: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val status = FilewayTheme.status
    val container = when (kind) {
        FilewayStatusKind.SUCCESS -> status.successContainer
        FilewayStatusKind.WARNING -> status.warningContainer
        FilewayStatusKind.ERROR -> colors.errorContainer
        else -> colors.surfaceContainer
    }
    val content = when (kind) {
        FilewayStatusKind.SUCCESS -> status.onSuccessContainer
        FilewayStatusKind.WARNING -> status.onWarningContainer
        FilewayStatusKind.ERROR -> colors.onErrorContainer
        else -> colors.onSurfaceVariant
    }
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.small) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = FilewaySpace.target)
                    .padding(start = FilewaySpace.page, end = if (onDismiss == null) FilewaySpace.page else 0.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (kind == FilewayStatusKind.LOADING) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp).clearAndSetSemantics {},
                        color = content,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(FilewaySpace.content))
                }
                Text(
                    message,
                    modifier = Modifier.weight(1f).padding(vertical = FilewaySpace.content)
                        .semantics {
                            liveRegion = LiveRegionMode.Polite
                            if (kind == FilewayStatusKind.LOADING) progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                        },
                    style = MaterialTheme.typography.bodySmall,
                )
                if (onDismiss != null) {
                    IconButton(onClick = onDismiss, modifier = Modifier.size(FilewaySpace.target)) {
                        Icon(painterResource(R.drawable.ic_close), "关闭提示", Modifier.size(20.dp), tint = content)
                    }
                }
            }
            // A separate action row keeps long messages and labels readable in narrow windows.
            if (onAction != null && !actionLabel.isNullOrBlank()) {
                TextButton(
                    onClick = onAction,
                    modifier = Modifier.align(Alignment.End).padding(horizontal = FilewaySpace.small)
                        .heightIn(min = FilewaySpace.target).widthIn(min = FilewaySpace.target),
                ) {
                    Text(actionLabel, color = content, textAlign = TextAlign.Center)
                }
            }
        }
    }
}
