package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayTheme

/** Stable presentation data for one menu row; business values stay with the caller. */
@Immutable
internal data class FilewayMenuEntry(
    val key: String,
    val label: String,
    val icon: Int? = null,
    val selected: Boolean = false,
    val enabled: Boolean = true,
    val destructive: Boolean = false,
)

internal enum class FilewayMenuAlignment { Start, Center }

internal enum class FilewayMenuSelection { None, Single, Multiple }

private val MenuHorizontalPadding = 16.dp
private val MenuIconSlot = 24.dp
private val MenuCheckSlot = 24.dp
private val MenuSlotGap = 8.dp
private val MenuWindowInset = 16.dp
private val MenuShape = RoundedCornerShape(12.dp)

/**
 * Shared popup shell. DropdownMenu owns popup placement, dismissal, Back and focus;
 * rows are rendered here so their width and minimum touch target remain explicit.
 */
@Composable
internal fun FilewayMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    items: List<FilewayMenuEntry>,
    selection: FilewayMenuSelection = FilewayMenuSelection.None,
    alignment: FilewayMenuAlignment = FilewayMenuAlignment.Start,
    extraContentWidth: Dp = 0.dp,
    itemExtras: (@Composable (FilewayMenuEntry) -> Unit)? = null,
    onItemClick: (FilewayMenuEntry) -> Unit,
) {
    val colors = FilewayTheme.colors
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val textMeasurer = rememberTextMeasurer()
    val normalStyle = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Normal)
    val selectedStyle = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
    val measuredLabelWidth = remember(items, normalStyle, selectedStyle, density) {
        val widthPx = items.maxOfOrNull { item ->
            maxOf(
                textMeasurer.measure(item.label, style = normalStyle).size.width,
                textMeasurer.measure(item.label, style = selectedStyle).size.width,
            )
        } ?: 0
        with(density) { widthPx.toDp() }
    }
    val hasIcons = items.any { it.icon != null }
    val hasChecks = alignment == FilewayMenuAlignment.Start && selection != FilewayMenuSelection.None
    val requiredWidth = MenuHorizontalPadding * 2f + measuredLabelWidth +
        (if (hasIcons) MenuIconSlot + MenuSlotGap else 0.dp) +
        (if (hasChecks) MenuSlotGap + MenuCheckSlot else 0.dp)
    val windowMaxWidth = (configuration.screenWidthDp - (MenuWindowInset.value * 2f).toInt())
        .coerceAtLeast(1).dp
    val menuWidth = maxOf(requiredWidth, extraContentWidth).coerceAtLeast(1.dp)
        .coerceAtMost(windowMaxWidth)

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = Modifier.width(menuWidth),
        shape = MenuShape,
        containerColor = colors.surface,
    ) {
        items.forEach { item ->
            FilewayMenuRow(
                item = item,
                selection = selection,
                alignment = alignment,
                hasIcons = hasIcons,
                hasChecks = hasChecks,
                normalStyle = normalStyle,
                selectedStyle = selectedStyle,
                onClick = { onItemClick(item) },
            )
            itemExtras?.invoke(item)
        }
    }
}

@Composable
private fun FilewayMenuRow(
    item: FilewayMenuEntry,
    selection: FilewayMenuSelection,
    alignment: FilewayMenuAlignment,
    hasIcons: Boolean,
    hasChecks: Boolean,
    normalStyle: TextStyle,
    selectedStyle: TextStyle,
    onClick: () -> Unit,
) {
    val colors = FilewayTheme.colors
    val enabledAlpha = if (item.enabled) 1f else .38f
    val selectedColor = if (item.destructive) colors.error else colors.primary
    val baseColor = if (item.destructive) colors.error else colors.onSurface
    val labelColor = (if (item.selected) selectedColor else baseColor).copy(alpha = enabledAlpha)
    val iconColor = (if (item.selected) selectedColor else colors.onSurfaceVariant).copy(alpha = enabledAlpha)
    val selectedSurface = alignment == FilewayMenuAlignment.Center && item.selected
    val role = when (selection) {
        FilewayMenuSelection.Single -> Role.RadioButton
        FilewayMenuSelection.Multiple -> Role.Checkbox
        FilewayMenuSelection.None -> null
    }
    val action = if (role == null) {
        Modifier.clickable(enabled = item.enabled, onClick = onClick)
    } else {
        Modifier.selectable(selected = item.selected, enabled = item.enabled, role = role, onClick = onClick)
    }
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(if (selectedSurface) Modifier.background(colors.primaryContainer.copy(alpha = enabledAlpha), MenuShape) else Modifier)
            .then(action)
            .semantics { this.selected = item.selected }
            .padding(horizontal = MenuHorizontalPadding, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (alignment == FilewayMenuAlignment.Center) Arrangement.Center else Arrangement.Start,
    ) {
        if (hasIcons && (alignment == FilewayMenuAlignment.Start || item.icon != null)) {
            Box(Modifier.size(MenuIconSlot), contentAlignment = Alignment.Center) {
                item.icon?.let { icon ->
                    Icon(painterResource(icon), null, Modifier.size(22.dp), tint = iconColor)
                }
            }
            Spacer(Modifier.width(MenuSlotGap))
        }
        Text(
            text = item.label,
            modifier = Modifier.weight(1f, fill = alignment == FilewayMenuAlignment.Start),
            style = if (item.selected) selectedStyle else normalStyle,
            color = labelColor,
            textAlign = if (alignment == FilewayMenuAlignment.Center) TextAlign.Center else TextAlign.Start,
            softWrap = true,
            overflow = TextOverflow.Clip,
        )
        if (hasChecks) {
            Spacer(Modifier.width(MenuSlotGap))
            Box(Modifier.size(MenuCheckSlot), contentAlignment = Alignment.Center) {
                if (item.selected) {
                    Icon(
                        painterResource(R.drawable.ic_check),
                        "当前选项",
                        Modifier.size(20.dp),
                        tint = selectedColor.copy(alpha = enabledAlpha),
                    )
                }
            }
        }
    }
}
