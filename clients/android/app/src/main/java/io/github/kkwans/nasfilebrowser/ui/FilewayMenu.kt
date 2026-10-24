package io.github.kkwans.nasfilebrowser.ui

import android.app.Activity
import android.content.ContextWrapper
import android.os.Build
import android.view.Gravity
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.roundToIntRect
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
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
    val description: String? = null,
    val selectable: Boolean = true,
    val accessibilityLabel: String? = null,
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
 * Content-sized native window: public background blur also works over video.
 * Keep Material's anchor capture and placement candidates, row sizing and focus.
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
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val host = LocalView.current
    val direction = LocalLayoutDirection.current
    val activity = remember(host) {
        var context = host.context
        while (context is ContextWrapper && context !is Activity) {
            val base = context.baseContext
            if (base === context) break
            context = base
        }
        context as? Activity
    }
    val root = activity?.window?.decorView ?: host.rootView
    var anchor by remember(expanded) { mutableStateOf(IntRect.Zero) }
    // Same zero-sized anchor layout used by Compose Popup. Account for menus
    // opened inside another dialog by converting to the activity's coordinates.
    if (expanded) Layout(content = {}, modifier = Modifier.onGloballyPositioned { coordinates ->
        val parent = coordinates.parentLayoutCoordinates ?: return@onGloballyPositioned
        val screen = IntArray(2); val inWindow = IntArray(2); val origin = IntArray(2)
        host.getLocationOnScreen(screen); host.getLocationInWindow(inWindow); root.getLocationOnScreen(origin)
        val offset = androidx.compose.ui.geometry.Offset(
            (screen[0] - inWindow[0] - origin[0]).toFloat(),
            (screen[1] - inWindow[1] - origin[1]).toFloat())
        anchor = parent.boundsInWindow().translate(offset).roundToIntRect()
    }) { _, _ -> layout(0, 0) {} }
    val scroll = rememberScrollState()
    val textMeasurer = rememberTextMeasurer()
    val normalStyle = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Normal)
    val selectedStyle = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
    val descriptionStyle = MaterialTheme.typography.bodySmall
    val measuredLabelWidth = remember(items, normalStyle, selectedStyle, descriptionStyle, density) {
        val widthPx = items.maxOfOrNull { item ->
            maxOf(
                textMeasurer.measure(item.label, style = normalStyle).size.width,
                textMeasurer.measure(item.label, style = selectedStyle).size.width,
                item.description?.let { textMeasurer.measure(it, style = descriptionStyle).size.width } ?: 0,
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

    if (!expanded || anchor == IntRect.Zero) return
    val margin = with(density) { MenuWindowInset.roundToPx() }
    fun availableBounds(): IntRect {
        val insets = ViewCompat.getRootWindowInsets(root)
        val safe = insets?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val ime = insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
        val w = root.width.takeIf { it > 0 } ?: with(density) { configuration.screenWidthDp.dp.roundToPx() }
        val h = root.height.takeIf { it > 0 } ?: with(density) { configuration.screenHeightDp.dp.roundToPx() }
        val left = maxOf(margin, safe?.left ?: 0)
        val top = maxOf(margin, safe?.top ?: 0)
        return IntRect(left, top, (w - maxOf(margin, safe?.right ?: 0)).coerceAtLeast(left + 1),
            (h - maxOf(margin, safe?.bottom ?: 0, ime)).coerceAtLeast(top + 1))
    }
    val bounds = availableBounds()
    val widthPx = with(density) { menuWidth.roundToPx() }.coerceAtMost(bounds.width)
    Dialog(onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = true)) {
        val view = LocalView.current
        val window = (view.parent as? DialogWindowProvider)?.window
        var blurred by remember { mutableStateOf(false) }
        FilewayDialogGlass { blurred = it }
        val latestAnchor by rememberUpdatedState(anchor)
        val latestDirection by rememberUpdatedState(direction)
        SideEffect {
            window?.let {
                WindowCompat.setDecorFitsSystemWindows(it, false)
                val attributes = it.attributes
                attributes.gravity = Gravity.TOP or Gravity.LEFT
                attributes.width = widthPx
                attributes.height = WindowManager.LayoutParams.WRAP_CONTENT
                if (Build.VERSION.SDK_INT >= 30) attributes.setFitInsetsTypes(0)
                it.attributes = attributes
            }
        }
        DisposableEffect(window, root) {
            val decor = window?.decorView
            val observer = decor?.viewTreeObserver
            val decorOrigin = IntArray(2)
            val activityOrigin = IntArray(2)
            val listener = ViewTreeObserver.OnPreDrawListener {
                if (window == null || decor == null || decor.height <= 0) true else {
                    val position = filewayMenuPosition(latestAnchor, availableBounds(),
                        IntSize(decor.width, decor.height), latestDirection)
                    val attributes = window.attributes
                    decor.getLocationOnScreen(decorOrigin)
                    root.getLocationOnScreen(activityOrigin)
                    // WindowManager offsets a floating window from its parent
                    // frame, which may already exclude status bars or cutouts.
                    // The anchor is activity-local: translate using the actual
                    // native origin instead of adding the safe inset twice.
                    val x = position.x + activityOrigin[0] - (decorOrigin[0] - attributes.x)
                    val y = position.y + activityOrigin[1] - (decorOrigin[1] - attributes.y)
                    if (attributes.x == x && attributes.y == y) true else {
                        attributes.x = x; attributes.y = y
                        window.attributes = attributes
                        decor.requestLayout()
                        false // Position before the first drawn frame, without a visible jump.
                    }
                }
            }
            observer?.addOnPreDrawListener(listener)
            onDispose { decor?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnPreDrawListener(listener) }
        }
        Surface(Modifier.width(with(density) { widthPx.toDp() }).heightIn(max = with(density) { bounds.height.toDp() }).filewayGlassFrame(MenuShape),
            color = filewayGlassColor(blurred), shape = MenuShape, tonalElevation = 0.dp) {
        Column(Modifier.verticalScroll(scroll).padding(vertical = 8.dp)) {
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
    val role = if (!item.selectable) null else when (selection) {
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
            .semantics {
                this.selected = item.selected
                item.accessibilityLabel?.let { contentDescription = it }
            }
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
        Column(Modifier.weight(1f, fill = alignment == FilewayMenuAlignment.Start),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            horizontalAlignment = if (alignment == FilewayMenuAlignment.Center) Alignment.CenterHorizontally else Alignment.Start) {
            val textAlign = if (alignment == FilewayMenuAlignment.Center) TextAlign.Center else TextAlign.Start
            Text(item.label, style = if (item.selected) selectedStyle else normalStyle,
                color = labelColor, textAlign = textAlign, softWrap = true, overflow = TextOverflow.Clip)
            item.description?.takeIf { it.isNotBlank() }?.let { description ->
                Text(description, style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant.copy(alpha = enabledAlpha), textAlign = textAlign,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
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
