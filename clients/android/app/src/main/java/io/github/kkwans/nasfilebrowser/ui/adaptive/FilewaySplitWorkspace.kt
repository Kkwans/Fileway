package io.github.kkwans.nasfilebrowser.ui.adaptive

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.ui.FilewayIcon
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pure geometry contract for Fileway's resizable Master-Detail workspace.
 * Kept Android-free so unit tests verify every clamp, snap, and collapse boundary directly.
 */
@Immutable
data class SplitPaneSpec(
    val defaultRatio: Float = 0.38f,
    val minLeftDp: Float = 300f,
    val minRightDp: Float = 360f,
    val collapseLeftDp: Float = 160f,
    val dividerDp: Float = 12f,
)

@Immutable
data class SplitPaneLayoutResult(
    val canSplit: Boolean,
    val leftWidthDp: Float,
    val dividerWidthDp: Float,
    val rightWidthDp: Float,
    val effectiveRatio: Float,
)

@Immutable
data class SplitDragStep(
    val ratio: Float,
    val collapsed: Boolean,
    val snappedAnchor: Float?,
)

object SplitPaneMath {
    val SnapAnchors = floatArrayOf(0.35f, 0.50f, 0.65f)
    const val SnapThreshold = 0.012f

    fun minimumSplitWidthDp(spec: SplitPaneSpec): Float =
        spec.minLeftDp + spec.minRightDp + spec.dividerDp

    fun clampRatio(
        desiredRatio: Float,
        totalWidthDp: Float,
        spec: SplitPaneSpec,
    ): Float {
        val usable = (totalWidthDp - spec.dividerDp).coerceAtLeast(1f)
        val minRatio = (spec.minLeftDp / usable).coerceIn(0.15f, 0.85f)
        val maxRatio = (1f - spec.minRightDp / usable).coerceIn(minRatio, 0.85f)
        return desiredRatio.coerceIn(minRatio, maxRatio)
    }

    fun computeLayout(
        totalWidthDp: Float,
        ratio: Float,
        leftCollapsed: Boolean,
        detailFullscreen: Boolean,
        spec: SplitPaneSpec,
        hingeCenterFraction: Float? = null,
    ): SplitPaneLayoutResult {
        val safeTotal = totalWidthDp.coerceAtLeast(0f)
        val canSplit = safeTotal >= minimumSplitWidthDp(spec)
        if (!canSplit) {
            return SplitPaneLayoutResult(
                canSplit = false,
                leftWidthDp = safeTotal,
                dividerWidthDp = 0f,
                rightWidthDp = 0f,
                effectiveRatio = 1f,
            )
        }
        if (detailFullscreen || leftCollapsed) {
            return SplitPaneLayoutResult(
                canSplit = true,
                leftWidthDp = 0f,
                dividerWidthDp = 0f,
                rightWidthDp = safeTotal,
                effectiveRatio = 0f,
            )
        }
        val targetRatio = hingeCenterFraction?.takeIf { abs(it - ratio) <= 0.025f } ?: ratio
        val effective = clampRatio(targetRatio, safeTotal, spec)
        val usable = (safeTotal - spec.dividerDp).coerceAtLeast(0f)
        val left = (usable * effective).coerceIn(spec.minLeftDp, usable - spec.minRightDp)
        val right = (usable - left).coerceAtLeast(spec.minRightDp)
        return SplitPaneLayoutResult(
            canSplit = true,
            leftWidthDp = left,
            dividerWidthDp = spec.dividerDp,
            rightWidthDp = right,
            effectiveRatio = effective,
        )
    }

    fun applyDragDelta(
        currentRatio: Float,
        rawLeftWidthDp: Float,
        deltaDp: Float,
        totalWidthDp: Float,
        spec: SplitPaneSpec,
        allowCollapse: Boolean = true,
        magneticSnap: Boolean = true,
    ): SplitDragStep {
        val usable = (totalWidthDp - spec.dividerDp).coerceAtLeast(1f)
        val candidateLeft = rawLeftWidthDp + deltaDp
        if (allowCollapse && candidateLeft <= spec.collapseLeftDp) {
            return SplitDragStep(
                ratio = clampRatio(currentRatio, totalWidthDp, spec),
                collapsed = true,
                snappedAnchor = null,
            )
        }
        val unclampedRatio = candidateLeft / usable
        val clamped = clampRatio(unclampedRatio, totalWidthDp, spec)
        val snapped = if (magneticSnap) SnapAnchors.firstOrNull { abs(it - clamped) <= SnapThreshold } else null
        return SplitDragStep(
            ratio = snapped ?: clamped,
            collapsed = false,
            snappedAnchor = snapped,
        )
    }
}

/** Persistent store for tablet split ratios and rail collapse preference. */
object TabletLayoutStore {
    private const val PREFS_NAME = "fileway-tablet-layout"
    private const val KEY_RAIL_EXPANDED = "large_rail_expanded"
    private const val PREFIX_RATIO = "split_ratio_"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun readRatio(context: Context, sceneKey: String, defaultRatio: Float): Float {
        val value = prefs(context).getFloat(PREFIX_RATIO + sceneKey, -1f)
        return if (value in 0.15f..0.85f) value else defaultRatio
    }

    fun saveRatio(context: Context, sceneKey: String, ratio: Float) {
        if (ratio in 0.15f..0.85f) {
            prefs(context).edit().putFloat(PREFIX_RATIO + sceneKey, ratio).apply()
        }
    }

    fun clearSavedRatios(context: Context) {
        val shared = prefs(context)
        val editor = shared.edit()
        shared.all.keys.filter { it.startsWith(PREFIX_RATIO) }.forEach { editor.remove(it) }
        editor.apply()
    }

    fun readLargeRailExpanded(context: Context, defaultExpanded: Boolean = true): Boolean =
        prefs(context).getBoolean(KEY_RAIL_EXPANDED, defaultExpanded)

    fun saveLargeRailExpanded(context: Context, expanded: Boolean) {
        prefs(context).edit().putBoolean(KEY_RAIL_EXPANDED, expanded).apply()
    }

    fun resetAll(context: Context) {
        prefs(context).edit().clear().apply()
        TabletLayoutPreferences.resetDefaults(context)
    }
}

/** Exposes split workspace controls to embedded right-pane headers (e.g. expand/collapse left list or fullscreen). */
@Stable
interface SplitWorkspaceController {
    val leftCollapsed: Boolean
    val detailFullscreen: Boolean
    val canToggleFullscreen: Boolean
    fun expandLeftPane()
    fun collapseLeftPane()
    fun resetRatio()
    fun setDetailFullscreen(fullscreen: Boolean)
}

val LocalSplitWorkspaceController = staticCompositionLocalOf<SplitWorkspaceController?> { null }

@Stable
class FilewaySplitWorkspaceState(
    val sceneKey: String,
    val spec: SplitPaneSpec,
    initialRatio: Float,
    private val onPersistRatio: (Float) -> Unit,
) : SplitWorkspaceController {
    var ratio by mutableFloatStateOf(initialRatio)
        internal set
    override var leftCollapsed by mutableStateOf(false)
        internal set
    override var detailFullscreen by mutableStateOf(false)
        internal set
    override val canToggleFullscreen: Boolean get() = true

    internal var lastSnappedAnchor by mutableStateOf<Float?>(null)
    internal var rawDragLeftDp by mutableFloatStateOf(0f)

    fun updateRatio(newRatio: Float, persist: Boolean = false) {
        ratio = newRatio
        if (persist) onPersistRatio(newRatio)
    }

    fun persistCurrentRatio() {
        onPersistRatio(ratio)
    }

    override fun expandLeftPane() {
        leftCollapsed = false
        detailFullscreen = false
    }

    override fun collapseLeftPane() {
        leftCollapsed = true
    }

    override fun resetRatio() {
        leftCollapsed = false
        ratio = spec.defaultRatio
        onPersistRatio(spec.defaultRatio)
    }

    override fun setDetailFullscreen(fullscreen: Boolean) {
        detailFullscreen = fullscreen
    }
}

@Composable
fun rememberFilewaySplitWorkspaceState(
    sceneKey: String,
    spec: SplitPaneSpec = SplitPaneSpec(),
): FilewaySplitWorkspaceState {
    val context = LocalContext.current
    val tabletConfig = LocalTabletLayoutConfig.current
    val initial = remember(sceneKey, spec.defaultRatio) {
        TabletLayoutStore.readRatio(context, sceneKey, spec.defaultRatio)
    }
    val state = remember(sceneKey, spec) {
        FilewaySplitWorkspaceState(
            sceneKey = sceneKey,
            spec = spec,
            initialRatio = initial,
            onPersistRatio = { TabletLayoutStore.saveRatio(context, sceneKey, it) },
        )
    }
    var lastObservedReset by remember(state) { mutableStateOf(tabletConfig.resetRevision) }
    LaunchedEffect(tabletConfig.resetRevision, spec.defaultRatio) {
        if (tabletConfig.resetRevision != lastObservedReset) {
            lastObservedReset = tabletConfig.resetRevision
            val target = TabletLayoutStore.readRatio(context, sceneKey, spec.defaultRatio)
            val start = state.ratio
            state.leftCollapsed = false
            val anim = Animatable(start)
            anim.animateTo(
                targetValue = target,
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
            ) {
                state.updateRatio(value, persist = false)
            }
            state.updateRatio(target, persist = false)
        }
    }
    return state
}

/**
 * Resizable Master-Detail container for Fileway tablet & foldable screens.
 *
 * Key guarantees:
 * 1. Keeps [detailPane] in a stable slot even when [state.detailFullscreen] toggles so video
 *    surfaces (Media3) and zoomable images never recreate or flash.
 * 2. Provides a 12dp visual divider with a 28dp touch hit zone, hover resize cursor, haptic snap
 *    feedback, double-tap reset, and extreme-left collapse.
 * 3. Automatically degrades to single-pane [listPane] whenever available width drops below
 *    `minLeft + minRight + divider`.
 */
@Composable
fun FilewaySplitWorkspace(
    state: FilewaySplitWorkspaceState,
    modifier: Modifier = Modifier,
    showDetailInSinglePane: Boolean = false,
    onFullscreenChanged: ((Boolean) -> Unit)? = null,
    listPane: @Composable () -> Unit,
    detailPane: @Composable () -> Unit,
) {
    val windowLayout = LocalFilewayWindowLayout.current
    val tabletConfig = LocalTabletLayoutConfig.current
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var lastTotalWidthDp by remember { mutableFloatStateOf(0f) }
    var draggingDivider by remember { mutableStateOf(false) }
    var dragPendingCollapse by remember { mutableStateOf(false) }
    var lastDividerCenterX by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(state.detailFullscreen) {
        onFullscreenChanged?.invoke(state.detailFullscreen)
    }
    DisposableEffect(Unit) {
        onDispose { onFullscreenChanged?.invoke(false) }
    }

    CompositionLocalProvider(LocalSplitWorkspaceController provides state) {
        Box(modifier.fillMaxSize()) {
            Layout(
                modifier = Modifier.fillMaxSize(),
                content = {
                    Box(Modifier.fillMaxSize()) { listPane() }
                    SplitDividerHandle(
                        onDragStart = {
                            draggingDivider = true
                            dragPendingCollapse = false
                            val layout = SplitPaneMath.computeLayout(
                                totalWidthDp = lastTotalWidthDp,
                                ratio = state.ratio,
                                leftCollapsed = state.leftCollapsed,
                                detailFullscreen = state.detailFullscreen,
                                spec = state.spec,
                                hingeCenterFraction = windowLayout.hingeCenterFraction,
                            )
                            state.rawDragLeftDp = layout.leftWidthDp
                            state.lastSnappedAnchor = null
                        },
                        onDragDeltaPx = { deltaPx ->
                            if (lastTotalWidthDp <= 0f) return@SplitDividerHandle
                            val deltaDp = with(density) { deltaPx.toDp().value }
                            val step = SplitPaneMath.applyDragDelta(
                                currentRatio = state.ratio,
                                rawLeftWidthDp = state.rawDragLeftDp,
                                deltaDp = deltaDp,
                                totalWidthDp = lastTotalWidthDp,
                                spec = state.spec,
                                allowCollapse = tabletConfig.allowEdgeCollapse,
                                magneticSnap = tabletConfig.magneticSnapEnabled,
                            )
                            state.rawDragLeftDp += deltaDp
                            if (step.collapsed) {
                                dragPendingCollapse = true
                                if (!state.leftCollapsed) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    state.collapseLeftPane()
                                }
                            } else {
                                dragPendingCollapse = false
                                state.updateRatio(step.ratio, persist = false)
                                if (step.snappedAnchor != null && step.snappedAnchor != state.lastSnappedAnchor) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                                state.lastSnappedAnchor = step.snappedAnchor
                            }
                        },
                        onDragEnd = {
                            draggingDivider = false
                            dragPendingCollapse = false
                            state.persistCurrentRatio()
                            state.lastSnappedAnchor = null
                        },
                        onDoubleTap = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            val start = state.ratio
                            val target = state.spec.defaultRatio
                            scope.launch {
                                val anim = Animatable(start)
                                anim.animateTo(
                                    targetValue = target,
                                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                                ) {
                                    state.updateRatio(value, persist = false)
                                }
                                state.resetRatio()
                            }
                        },
                    )
                    Box(Modifier.fillMaxSize()) { detailPane() }
                },
            ) { measurables, constraints ->
                val totalWidthPx = constraints.maxWidth
                val totalHeightPx = constraints.maxHeight
                val totalWidthDp = with(density) { totalWidthPx.toDp().value }
                lastTotalWidthDp = totalWidthDp

                val result = SplitPaneMath.computeLayout(
                    totalWidthDp = totalWidthDp,
                    ratio = state.ratio,
                    leftCollapsed = state.leftCollapsed,
                    detailFullscreen = state.detailFullscreen,
                    spec = state.spec,
                    hingeCenterFraction = windowLayout.hingeCenterFraction,
                )

                val listMeasurable = measurables[0]
                val dividerMeasurable = measurables[1]
                val detailMeasurable = measurables[2]

                if (!result.canSplit) {
                    val activeMeasurable = if (showDetailInSinglePane) detailMeasurable else listMeasurable
                    val inactiveMeasurable = if (showDetailInSinglePane) listMeasurable else detailMeasurable
                    val activePlaceable = activeMeasurable.measure(Constraints.fixed(totalWidthPx, totalHeightPx))
                    val zero = Constraints.fixed(0, 0)
                    inactiveMeasurable.measure(zero)
                    dividerMeasurable.measure(zero)
                    return@Layout layout(totalWidthPx, totalHeightPx) {
                        activePlaceable.placeRelative(0, 0)
                    }
                }

                if (state.detailFullscreen || state.leftCollapsed) {
                    val zero = Constraints.fixed(0, 0)
                    listMeasurable.measure(zero)
                    dividerMeasurable.measure(zero)
                    val detailPlaceable = detailMeasurable.measure(Constraints.fixed(totalWidthPx, totalHeightPx))
                    return@Layout layout(totalWidthPx, totalHeightPx) {
                        detailPlaceable.placeRelative(0, 0)
                    }
                }

                val dividerPx = with(density) { result.dividerWidthDp.dp.roundToPx() }
                val hitDividerPx = with(density) { 28.dp.roundToPx() }.coerceAtLeast(dividerPx)
                val leftPx = with(density) { result.leftWidthDp.dp.roundToPx() }
                    .coerceIn(0, (totalWidthPx - dividerPx).coerceAtLeast(0))
                val rightPx = (totalWidthPx - leftPx - dividerPx).coerceAtLeast(0)
                lastDividerCenterX = leftPx + dividerPx / 2f

                val listPlaceable = listMeasurable.measure(Constraints.fixed(leftPx, totalHeightPx))
                val detailPlaceable = detailMeasurable.measure(Constraints.fixed(rightPx, totalHeightPx))
                val dividerPlaceable = dividerMeasurable.measure(Constraints.fixed(hitDividerPx, totalHeightPx))

                layout(totalWidthPx, totalHeightPx) {
                    listPlaceable.placeRelative(0, 0)
                    detailPlaceable.placeRelative(leftPx + dividerPx, 0)
                    // Place the 28dp hit-target divider centered over the 12dp visual gap on top z-index
                    val hitOffsetX = leftPx + (dividerPx - hitDividerPx) / 2
                    dividerPlaceable.placeRelative(hitOffsetX, 0, zIndex = 2f)
                }
            }

            // Live split-ratio HUD pill shown only while actively dragging the divider.
            val showHud = tabletConfig.showDragRatioHud && draggingDivider && !state.detailFullscreen && !state.leftCollapsed
            AnimatedVisibility(
                visible = showHud,
                enter = fadeIn() + scaleIn(initialScale = 0.92f),
                exit = fadeOut() + scaleOut(targetScale = 0.92f),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset {
                        val badgeHalfPx = with(density) { 54.dp.roundToPx() }
                        val maxX = with(density) { (lastTotalWidthDp.dp.roundToPx() - badgeHalfPx * 2).coerceAtLeast(0) }
                        IntOffset(
                            x = (lastDividerCenterX.roundToInt() - badgeHalfPx).coerceIn(12, maxX),
                            y = with(density) { 52.dp.roundToPx() },
                        )
                    },
            ) {
                val leftPct = (state.ratio * 100f).roundToInt().coerceIn(15, 85)
                val rightPct = 100 - leftPct
                val snapped = state.lastSnappedAnchor != null
                val colors = MaterialTheme.colorScheme
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = when {
                        dragPendingCollapse -> colors.errorContainer
                        snapped -> colors.primaryContainer
                        else -> colors.surfaceContainerHigh
                    },
                    border = BorderStroke(
                        1.dp,
                        when {
                            dragPendingCollapse -> colors.error.copy(alpha = 0.45f)
                            snapped -> colors.primary.copy(alpha = 0.5f)
                            else -> colors.outlineVariant.copy(alpha = 0.7f)
                        },
                    ),
                    shadowElevation = 4.dp,
                ) {
                    Text(
                        text = if (dragPendingCollapse) "已折叠左栏" else "$leftPct% · $rightPct%",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = when {
                            dragPendingCollapse -> colors.onErrorContainer
                            snapped -> colors.onPrimaryContainer
                            else -> colors.onSurface
                        },
                    )
                }
            }

            // Edge restore pill guarantees any split screen (including Settings/Library/Tasks)
            // can always restore a collapsed left pane via tap or rightward swipe.
            AnimatedVisibility(
                visible = state.leftCollapsed && !state.detailFullscreen,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 6.dp),
            ) {
                var swipeAccum by remember { mutableFloatStateOf(0f) }
                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .clickable(role = Role.Button, onClickLabel = "展开左侧列表") {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            state.expandLeftPane()
                        }
                        .pointerInput(Unit) {
                            detectHorizontalDragGestures(
                                onDragStart = { swipeAccum = 0f },
                                onHorizontalDrag = { _, dragAmount ->
                                    swipeAccum += dragAmount
                                    if (swipeAccum > 28f) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        state.expandLeftPane()
                                    }
                                },
                            )
                        }
                        .semantics { contentDescription = "展开左侧列表" },
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.75f)),
                    shadowElevation = 3.dp,
                ) {
                    Row(
                        Modifier.padding(horizontal = 6.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        FilewayIcon(
                            R.drawable.ic_arrow_forward,
                            null,
                            Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SplitDividerHandle(
    onDragStart: () -> Unit,
    onDragDeltaPx: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onDoubleTap: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val dragged by interactionSource.collectIsDraggedAsState()
    val hovered by interactionSource.collectIsHoveredAsState()
    val active = dragged || hovered
    val colors = MaterialTheme.colorScheme

    val pillWidth by animateDpAsState(if (active) 6.dp else 4.dp, label = "splitPillWidth")
    val pillHeight by animateDpAsState(if (active) 52.dp else 36.dp, label = "splitPillHeight")
    val pillColor by animateColorAsState(
        targetValue = if (active) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.32f),
        label = "splitPillColor",
    )
    val lineColor by animateColorAsState(
        targetValue = if (active) colors.primary.copy(alpha = 0.35f) else colors.outlineVariant.copy(alpha = 0.7f),
        label = "splitLineColor",
    )

    val draggableState = rememberDraggableState { delta -> onDragDeltaPx(delta) }
    val context = LocalContext.current
    val resizeIcon = remember(context) {
        PointerIcon(
            android.view.PointerIcon.getSystemIcon(
                context,
                android.view.PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW,
            ),
        )
    }

    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(28.dp)
            .pointerHoverIcon(resizeIcon)
            .hoverable(interactionSource)
            .draggable(
                state = draggableState,
                orientation = Orientation.Horizontal,
                interactionSource = interactionSource,
                onDragStarted = { onDragStart() },
                onDragStopped = { onDragEnd() },
            )
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { onDoubleTap() })
            }
            .semantics { contentDescription = "拖动调整左右分栏宽度，双击恢复默认比例" },
        contentAlignment = Alignment.Center,
    ) {
        // Subtle 1dp vertical hairline
        Box(
            Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(lineColor),
        )
        // Tactile pill handle
        Box(
            Modifier
                .width(pillWidth)
                .height(pillHeight)
                .clip(RoundedCornerShape(999.dp))
                .background(pillColor),
        )
    }
}
