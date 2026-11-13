package io.github.kkwans.fileway.tv

// Selection-list focus structure adapted from BV, MIT, Copyright (c) 2022 aaa1115910.
// Source: aaa1115910/bv@0e454c06, player/tv/.../component/RadioMenuList.kt.
// Fileway supplies its own row layout, identity keys, touch input and media callbacks.

import android.graphics.drawable.ColorDrawable
import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.tv.material3.*
import io.github.kkwans.fileway.design.R as Art
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayPalette
import io.github.kkwans.nasfilebrowser.ui.designsystem.drawMediaProgressTrack
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal enum class TvPlayerPage(val title: String) {
    SETTINGS("播放设置"), AUDIO("音轨"), SUBTITLE("字幕"), SUBTITLE_ADJUST("字幕调整"),
    APPEARANCE("字幕外观"), SYNC("字幕同步"), FONT("字幕字体"), SUBTITLE_SOURCE("添加字幕"),
    EXTERNAL("服务器字幕"), LOCAL_SUBTITLE("本机字幕"), SPEED("播放速度"), HDR("动态范围"), RATIO("画面比例"),
    VOLUME("应用音量"), QUEUE("播放列表"), INFO("播放信息"),
}

internal sealed interface TvPlayerRow {
    val key: String

    data class Heading(override val key: String, val title: String) : TvPlayerRow
    data class Choice(
        override val key: String, val title: String, val selected: Boolean = false,
        val enabled: Boolean = true, val supporting: String? = null, val value: String? = null,
        val icon: Int? = null, val destination: Boolean = false, val selectable: Boolean = true,
        val action: () -> Unit, val fontFamily: FontFamily? = null,
    ) : TvPlayerRow
}

internal class TvPlayerPanelFocus(initialKey: String?, val onFocused: (String) -> Unit) {
    val header = FocusRequester()
    val body = FocusRequester()
    val section = FocusRequester()
    val appearanceScale = FocusRequester()
    var hasSection = false
    var hasHeader = true
    private val initial = FocusRequester()
    var initialKey by mutableStateOf(initialKey)
        private set
    var pending by mutableStateOf(true)
        private set

    fun retarget(key: String?) {
        initialKey = key
        pending = true
    }

    @Composable fun target(key: String, preferred: Boolean, modifier: Modifier = Modifier): Modifier {
        var placed by remember { mutableStateOf(false) }
        LaunchedEffect(placed, preferred, pending) {
            if (placed && preferred && pending && initial.requestFocus(FocusDirection.Enter)) pending = false
        }
        return modifier
            .then(if (preferred) Modifier.focusRequester(initial) else Modifier)
            .onFocusChanged { if (it.isFocused) onFocused(key) }
            .onGloballyPositioned { placed = it.isAttached }
    }
}

private const val PanelBackKey = "header/back"
private const val PanelCloseKey = "header/close"
// Static neutral translucency: readable against bright footage without a live blur.
internal val PanelSurface = Color(0xD61A1B1C)
internal val PanelFocus = Color.White.copy(alpha = .12f)
internal val PanelOutline = Color(0xF2F2F5F8)
private val PanelTabShape = RoundedCornerShape(50)

private fun tvPlayerPanelVerticalInset(height: androidx.compose.ui.unit.Dp): androidx.compose.ui.unit.Dp =
    if (height >= 160.dp) ((height - 144.dp) / 2).coerceIn(8.dp, 16.dp) else 7.dp

private val PanelTabs = listOf(
    TvPlayerPage.QUEUE to "列表",
    TvPlayerPage.SETTINGS to "设置",
    TvPlayerPage.SPEED to "倍速",
    TvPlayerPage.SUBTITLE to "字幕",
    TvPlayerPage.AUDIO to "音频",
    TvPlayerPage.INFO to "信息",
)
private val SubtitleSections = listOf(
    TvPlayerPage.SUBTITLE to "选择字幕",
    TvPlayerPage.SUBTITLE_SOURCE to "添加字幕",
    TvPlayerPage.SUBTITLE_ADJUST to "字幕调整",
)
internal fun tvSubtitleGroupKey(page: TvPlayerPage) = "group/${page.name}"

/** The dialog keeps the same native focus window while its bottom panel changes pages. */
@Composable internal fun TvPlayerPanel(
    page: TvPlayerPage, safePercent: Int, child: Boolean, audio: Boolean,
    quick: Boolean, hudHeight: androidx.compose.ui.unit.Dp, quickAnchor: Rect?, quickLabels: List<String>,
    parentPage: TvPlayerPage,
    initialKey: String?, onFocused: (String) -> Unit, onTab: (TvPlayerPage) -> Unit,
    onSubtitleSection: (TvPlayerPage) -> Unit, onBack: (String) -> Unit, onClose: () -> Unit,
    content: @Composable ColumnScope.(TvPlayerPanelFocus) -> Unit,
) {
    Dialog(
        onDismissRequest = { onBack("dialog-window") },
        properties = DialogProperties(
            usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnClickOutside = false,
        ),
    ) {
        TvTheme(dark = true, media = true) {
            TvRemoteDialogKeys { onBack("dialog-phone") }
            val view = LocalView.current
            DisposableEffect(view) {
                (view.parent as? DialogWindowProvider)?.window?.let { window ->
                    window.setDimAmount(0f)
                    window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
                    WindowCompat.getInsetsController(window, view).hide(WindowInsetsCompat.Type.systemBars())
                }
                onDispose { }
            }
            val saveable = rememberSaveableStateHolder()
            BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
                val horizontal = tvPlayerSafeInset(maxWidth, safePercent, 16.dp)
                val bottom = tvPlayerSafeInset(maxHeight, safePercent, 12.dp)
                if (quick) {
                    val availableWidth = (maxWidth - horizontal * 2).coerceAtLeast(0.dp)
                    val density = LocalDensity.current
                    val measure = rememberTextMeasurer()
                    val minWidth = minOf(availableWidth, 208.dp)
                    val maxPopupWidth = minOf(availableWidth, (availableWidth * .40f).coerceAtLeast(minWidth))
                    val width = remember(quickLabels, page, minWidth, maxPopupWidth, density, measure) {
                        val style = tvTextStyle(17, 23, FontWeight.Medium)
                        val maxTextPx = with(density) { (maxPopupWidth - 72.dp).coerceAtLeast(0.dp).toPx() }
                        var longest = 0f
                        for (label in quickLabels.asSequence() + sequenceOf(page.title)) {
                            longest = maxOf(longest, measure.measure(AnnotatedString(label.take(256)), style, maxLines = 1).size.width.toFloat())
                            if (longest >= maxTextPx) break
                        }
                        (with(density) { longest.toDp() } + 72.dp).coerceIn(minWidth, maxPopupWidth)
                    }
                    val anchorX = quickAnchor?.let { with(density) { it.center.x.toDp() } }
                    val left = (anchorX?.minus(width / 2) ?: if (page == TvPlayerPage.QUEUE) horizontal else maxWidth - horizontal - width)
                        .coerceIn(horizontal, (maxWidth - horizontal - width).coerceAtLeast(horizontal))
                    val anchorTop = quickAnchor?.let { with(density) { it.top.toDp() } }
                    val popupBottom = (anchorTop?.let { maxHeight - it + 8.dp } ?: maxOf(bottom + 84.dp, hudHeight - 48.dp))
                        .coerceIn(bottom, (maxHeight - 144.dp).coerceAtLeast(bottom))
                    val bodyHeight = (maxHeight * .40f).coerceIn(96.dp, 250.dp)
                        .coerceAtMost((maxHeight - popupBottom - bottom - 44.dp).coerceAtLeast(0.dp))
                    val dismiss by rememberUpdatedState { onBack("quick-outside") }
                    Box(Modifier.matchParentSize().pointerInput(Unit) { detectTapGestures { dismiss() } })
                    saveable.SaveableStateProvider("quick/${page.name}") {
                        val focus = remember(page) { TvPlayerPanelFocus(initialKey, onFocused).apply { hasHeader = false } }
                        Column(
                            Modifier.align(Alignment.BottomStart).padding(start = left, bottom = popupBottom)
                                .width(width).clip(TvShape).background(PanelSurface, TvShape)
                                .border(1.dp, Color.White.copy(alpha = .09f), TvShape)
                                .semantics { contentDescription = "${page.title}快捷菜单" }
                                .pointerInput(Unit) { detectTapGestures { } },
                        ) {
                            Box(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 20.dp), contentAlignment = Alignment.CenterStart) {
                                Text(page.title, style = tvTextStyle(17, 23, FontWeight.Medium), color = Color.White)
                            }
                            Column(Modifier.fillMaxWidth().heightIn(max = bodyHeight).padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
                                .focusRequester(focus.body).focusGroup()) {
                                content(focus)
                            }
                        }
                    }
                } else {
                val subtitleSection = SubtitleSections.any { it.first == page }
                val category = if (subtitleSection) TvPlayerPage.SUBTITLE else parentPage
                val hasTabs = PanelTabs.any { it.first == category }
                val browser = page == TvPlayerPage.EXTERNAL || page == TvPlayerPage.LOCAL_SUBTITLE
                val panelWidth = (maxWidth - horizontal * 2).coerceAtLeast(0.dp)
                val keyboardEditor = page == TvPlayerPage.SPEED && WindowInsets.ime.getBottom(LocalDensity.current) > 0
                val panelBottom = if (keyboardEditor) 4.dp else if (page == TvPlayerPage.QUEUE) bottom + 16.dp else bottom
                val heightFraction = if (browser) .43f else if (page == TvPlayerPage.QUEUE) .28f else .39f
                val panelHeight = (if (keyboardEditor) 92.dp else (maxHeight * heightFraction).coerceIn(144.dp, 300.dp))
                    .coerceAtMost((maxHeight - panelBottom - 56.dp).coerceAtLeast(0.dp))
                val dismiss by rememberUpdatedState(if (child) ({ onBack("dialog-outside") }) else onClose)
                Box(Modifier.matchParentSize().pointerInput(Unit) { detectTapGestures { dismiss() } })
                if (page == TvPlayerPage.QUEUE) {
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(panelHeight + panelBottom + 96.dp)
                        .background(Brush.verticalGradient(0f to Color.Transparent,
                            .22f to Color.Black.copy(alpha = .42f), .65f to Color.Black.copy(alpha = .66f),
                            1f to Color.Black.copy(alpha = .80f))))
                } else if (hasTabs) {
                    Box(Modifier.align(Alignment.BottomCenter)
                        .padding(bottom = (panelHeight + panelBottom - 8.dp).coerceAtLeast(0.dp))
                        .fillMaxWidth().height(80.dp)
                        .background(Brush.verticalGradient(0f to Color.Transparent,
                            .40f to Color.Black.copy(alpha = .30f), .65f to Color.Black.copy(alpha = .40f),
                            1f to Color.Transparent)))
                }
                saveable.SaveableStateProvider(page.name) {
                    val focus = remember(page) { TvPlayerPanelFocus(initialKey, onFocused).apply { hasSection = subtitleSection } }
                    focus.hasHeader = !keyboardEditor
                    Column(
                        Modifier.align(Alignment.BottomStart).padding(start = horizontal, bottom = panelBottom)
                            .width(panelWidth),
                        verticalArrangement = Arrangement.spacedBy(if (page == TvPlayerPage.QUEUE) 0.dp else 6.dp),
                    ) {
                        if (!keyboardEditor) Row(
                            Modifier.width(panelWidth).heightIn(min = 48.dp)
                                .pointerInput(Unit) { detectTapGestures { } },
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (hasTabs && child && !subtitleSection) TvPlaybackAction(
                                "返回上一页", Art.drawable.fileway_ic_arrow_back, { onBack("dialog-touch") },
                                focus.target(PanelBackKey, focus.initialKey == PanelBackKey), showLabel = false,
                            )
                            if (hasTabs) {
                                Row(
                                    Modifier.weight(1f).padding(start = 4.dp).horizontalScroll(rememberScrollState()).focusGroup(),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    for ((tab, label) in PanelTabs) {
                                        if (audio && tab == TvPlayerPage.SUBTITLE) continue
                                        val key = "header/tab/${tab.name}"
                                        val current = category == tab
                                        TvPlayerPanelButton(
                                            label, selected = current, description = tab.title.takeIf { tab == TvPlayerPage.QUEUE || tab == TvPlayerPage.SETTINGS }, pill = true,
                                            onClick = { if (page == tab) focus.body.requestFocus() else onTab(tab) },
                                            modifier = focus.target(key, focus.initialKey == key,
                                                (if (current) Modifier.focusRequester(focus.header) else Modifier)
                                                    .focusProperties { down = focus.body }),
                                        )
                                    }
                                }
                            } else {
                                if (child) TvPlaybackAction(
                                    "返回上一页", Art.drawable.fileway_ic_arrow_back, { onBack("dialog-touch") },
                                    focus.target(PanelBackKey, focus.initialKey == PanelBackKey,
                                        Modifier.focusRequester(focus.header).focusProperties {
                                            down = if (page == TvPlayerPage.APPEARANCE) focus.appearanceScale else focus.body
                                        }),
                                    showLabel = false,
                                )
                                Text(
                                    page.title, Modifier.weight(1f).padding(start = if (child) 0.dp else 10.dp),
                                    style = tvTextStyle(18, 24, FontWeight.Medium), maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (!hasTabs) TvPlaybackAction(
                                "关闭面板", Art.drawable.fileway_ic_close, onClose,
                                focus.target(PanelCloseKey, focus.initialKey == PanelCloseKey,
                                    (if (!child) Modifier.focusRequester(focus.header) else Modifier)
                                        .focusProperties { down = focus.body }),
                                showLabel = false,
                            )
                        }
                        Box(Modifier.fillMaxWidth()) {
                            // Match the reference's inset group column and long option rows,
                            // but derive them from the available width for calibrated displays.
                            val sideWidth = (panelWidth * .13f).coerceIn(104.dp, 112.dp)
                                .coerceAtMost(panelWidth * .30f)
                            val structured = subtitleSection || page == TvPlayerPage.AUDIO || page == TvPlayerPage.SETTINGS
                            // Tabs, queue artwork and the first panel column share a visual left edge.
                            val leftInset = 12.dp
                            val rightInset = 12.dp
                            val columnGap = 12.dp
                            val verticalInset = when {
                                page == TvPlayerPage.QUEUE -> 0.dp
                                keyboardEditor -> 4.dp
                                structured -> tvPlayerPanelVerticalInset(panelHeight)
                                else -> 7.dp
                            }
                            Row(
                                Modifier.width(panelWidth).height(panelHeight)
                                    .then(if (page == TvPlayerPage.QUEUE) Modifier else Modifier.background(PanelSurface, TvShape)
                                        .border(1.dp, Color.White.copy(alpha = .09f), TvShape))
                                    .pointerInput(Unit) { detectTapGestures { } }
                                    .padding(start = leftInset, end = rightInset, top = verticalInset, bottom = verticalInset),
                                horizontalArrangement = Arrangement.spacedBy(columnGap),
                                verticalAlignment = Alignment.Top,
                            ) {
                                if (subtitleSection) Column(
                                    Modifier.width(sideWidth).fillMaxHeight().verticalScroll(rememberScrollState()).focusGroup(),
                                ) {
                                    SubtitleSections.forEachIndexed { index, (section, label) ->
                                        val current = page == section
                                        val enter = { if (current) focus.body.requestFocus() else onSubtitleSection(section) }
                                        val key = tvSubtitleGroupKey(section)
                                        TvPlayerPanelButton(
                                            label, selected = current, onClick = { enter() },
                                            modifier = focus.target(key, focus.initialKey == key,
                                                (if (current) Modifier.focusRequester(focus.section) else Modifier)
                                                    .fillMaxWidth()
                                                    .focusProperties { if (index == 0) up = focus.header }
                                                    .tvRemoteKeys { event ->
                                                        if (event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                                                            enter(); true
                                                        } else false
                                                    }),
                                        )
                                    }
                                }
                                if (page == TvPlayerPage.AUDIO) Box(
                                    Modifier.width(sideWidth).height(48.dp).padding(horizontal = 14.dp),
                                    contentAlignment = Alignment.CenterStart,
                                ) {
                                    Text("音轨", style = tvTextStyle(16, 22), color = Color.White.copy(alpha = .82f))
                                }
                                Column(Modifier.weight(1f).fillMaxHeight().focusRequester(focus.body).focusGroup()) {
                                    if (child && hasTabs && !subtitleSection) Text(page.title,
                                        Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = tvTextStyle(16, 22))
                                    content(focus)
                                }
                            }
                        }
                    }
                }
                }
            }
        }
    }
}

private data class TvPanelTabMetrics(val width: Dp, val labelWidth: Dp)

@Composable private fun tvPanelTabMetrics(label: String): TvPanelTabMetrics {
    val density = LocalDensity.current
    val measure = rememberTextMeasurer()
    return remember(label, density, measure) {
        val width = with(density) { measure.measure(AnnotatedString(label), tvTextStyle(16, 22, FontWeight.SemiBold), maxLines = 1).size.width.toDp() }
        TvPanelTabMetrics(maxOf(48.dp, width + 16.dp), width)
    }
}

@Composable private fun TvPlayerTabVisual(
    label: String, selected: Boolean, focused: Boolean, hovered: Boolean, metrics: TvPanelTabMetrics,
) {
    Box(Modifier.fillMaxWidth().height(34.dp).clip(PanelTabShape)
        .background(if (focused || hovered) Color.White.copy(alpha = .12f) else Color.Transparent)
        .then(if (focused) Modifier.border(1.dp, Color.White.copy(alpha = .88f), PanelTabShape) else Modifier),
        contentAlignment = Alignment.Center) {
        Text(label, style = tvTextStyle(16, 22, if (selected) FontWeight.SemiBold else FontWeight.Normal)
            .copy(shadow = Shadow(Color.Black.copy(alpha = .9f), Offset(0f, 1f), 3f)),
            color = Color.White.copy(alpha = if (selected || focused) 1f else .82f), maxLines = 1)
        if (selected) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 1.dp)
            .width(metrics.labelWidth).height(2.dp).background(Color.White.copy(alpha = .90f), PanelTabShape))
    }
}

@Composable internal fun TvPlayerPanelButton(
    label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier,
    description: String? = null, pill: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val shape = if (pill) PanelTabShape else TvShape
    val metrics = if (pill) tvPanelTabMetrics(label) else null
    Button(
        onClick = onClick, interactionSource = interaction,
        modifier = modifier.then(if (metrics != null) Modifier.width(metrics.width) else Modifier)
            .heightIn(min = 48.dp)
            .then(if (!pill) Modifier.border(1.25.dp, if (focused) PanelOutline else Color.Transparent, TvShape) else Modifier)
            .hoverable(interaction).tvTouch(onClick, interaction = interaction).semantics {
            this.selected = selected
            if (description != null) contentDescription = description
        },
        contentPadding = if (pill) PaddingValues(0.dp) else PaddingValues(horizontal = 14.dp, vertical = 4.dp),
        shape = ButtonDefaults.shape(shape), scale = ButtonDefaults.scale(focusedScale = 1f),
        border = ButtonDefaults.border(focusedBorder = Border.None),
        colors = ButtonDefaults.colors(
            containerColor = if (selected && !pill) Color.White.copy(alpha = .10f) else if (!pill && hovered) Color.White.copy(alpha = .07f) else Color.Transparent,
            contentColor = if (selected) Color.White else Color.White.copy(alpha = .82f),
            focusedContainerColor = if (pill) Color.Transparent else PanelFocus, focusedContentColor = Color.White,
            pressedContainerColor = if (pill) Color.Transparent else PanelFocus,
        ),
    ) {
        if (metrics != null) TvPlayerTabVisual(label, selected, focused, hovered, metrics)
        else Box(Modifier.fillMaxWidth().heightIn(min = 40.dp), contentAlignment = Alignment.Center) {
            Text(label, Modifier.fillMaxWidth(), style = tvTextStyle(16, 22, if (selected) FontWeight.Medium else FontWeight.Normal),
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable internal fun TvPlayerChoiceList(
    rows: List<TvPlayerRow>, focus: TvPlayerPanelFocus, modifier: Modifier = Modifier,
    afterLast: FocusRequester? = null,
    alignSelectedRow: Boolean = false,
) {
    val choices = remember(rows) { rows.filterIsInstance<TvPlayerRow.Choice>() }
    val first = choices.firstOrNull { it.enabled }?.key
    val last = choices.lastOrNull { it.enabled }?.key
    val preferred = choices.firstOrNull { it.enabled && it.key == focus.initialKey }?.key
        ?: choices.firstOrNull { it.enabled && it.selected }?.key ?: first
    val index = rows.indexOfFirst { it.key == preferred }
    val scroll = rememberLazyListState(initialFirstVisibleItemIndex = if (alignSelectedRow) index.coerceAtLeast(0) else 0)
    val prefersChrome = focus.initialKey?.let { it.startsWith("header/") || it.startsWith("group/") } == true
    LaunchedEffect(index, focus.pending, prefersChrome) {
        if (index >= 0 && focus.pending && !prefersChrome) {
            snapshotFlow { scroll.layoutInfo.viewportSize.height }.first { it > 0 }
            if (scroll.layoutInfo.visibleItemsInfo.none { it.index == index }) scroll.scrollToItem(index)
        }
    }
    val listContent: LazyListScope.() -> Unit = {
        items(rows, key = { it.key }) { row ->
            when (row) {
                is TvPlayerRow.Heading -> Text(
                    row.title, Modifier.padding(start = 12.dp, top = 8.dp, bottom = 6.dp),
                    style = tvTextStyle(17, 23, FontWeight.Medium), color = Color.White.copy(alpha = .86f),
                )
                is TvPlayerRow.Choice -> TvPlayerChoiceRow(
                    row,
                    focus.target(row.key, row.key == preferred && !prefersChrome,
                        Modifier.focusProperties {
                            if (row.key == first && focus.hasHeader) up = focus.header
                            if (row.key == last && afterLast != null) down = afterLast
                            left = if (focus.hasSection) focus.section else FocusRequester.Cancel
                            right = FocusRequester.Cancel
                        }),
                )
            }
        }
    }
    val visibility = if (alignSelectedRow) FullRowVisibility else LocalBringIntoViewSpec.current
    val fling = if (alignSelectedRow) rememberSnapFlingBehavior(scroll, SnapPosition.Start) else ScrollableDefaults.flingBehavior()
    CompositionLocalProvider(LocalBringIntoViewSpec provides visibility) {
        LazyColumn(modifier.fillMaxWidth(), state = scroll, contentPadding = PaddingValues(0.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp), flingBehavior = fling, content = listContent)
    }
}

// A fully visible row does not need to be repositioned by the TV focus pivot.
private object FullRowVisibility : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = when {
        containerSize <= 0 -> 0f
        size >= containerSize || offset < 0 -> offset
        offset + size > containerSize -> offset + size - containerSize
        else -> 0f
    }
}

@Composable internal fun TvPlayerChoiceRow(row: TvPlayerRow.Choice, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var focused by remember { mutableStateOf(false) }
    Button(
        onClick = row.action, enabled = row.enabled, interactionSource = interaction,
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp)
            // TV Material's surface border is centred on the bounds and can be
            // cut by a LazyColumn viewport; draw the whole outline inside instead.
            .border(1.25.dp, if (focused) PanelOutline else Color.Transparent, TvShape)
            .onFocusChanged { focused = it.isFocused }
            .hoverable(interaction, enabled = row.enabled).tvTouch(row.action, row.enabled, interaction).semantics { if (row.selectable) selected = row.selected },
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        shape = ButtonDefaults.shape(TvShape), scale = ButtonDefaults.scale(focusedScale = 1f),
        border = ButtonDefaults.border(focusedBorder = Border.None),
        colors = ButtonDefaults.colors(
            containerColor = if (row.selected) Color.White.copy(alpha = .055f) else if (hovered) Color.White.copy(alpha = .07f) else Color.Transparent,
            contentColor = Color.White, focusedContentColor = Color.White,
            focusedContainerColor = PanelFocus,
            disabledContainerColor = Color.Transparent, disabledContentColor = Color.White.copy(alpha = .4f),
        ),
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (row.icon != null) Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                Icon(painterResource(row.icon), null, Modifier.size(18.dp))
            }
            Column(Modifier.weight(1f)) {
                val overflow = if (focused) Modifier.basicMarquee() else Modifier
                Text(
                    row.title, overflow, style = tvTextStyle(17, 23, if (row.selected) FontWeight.Medium else FontWeight.Normal),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                row.supporting?.takeIf { it.isNotBlank() }?.let {
                    Text(it, overflow, style = tvTextStyle(15, 20).copy(fontFamily = row.fontFamily),
                        color = LocalContentColor.current.copy(alpha = .78f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            row.value?.let {
                Text(it, Modifier.widthIn(max = 132.dp).then(if (focused) Modifier.basicMarquee() else Modifier), style = tvTextStyle(15, 21), textAlign = TextAlign.End,
                    color = LocalContentColor.current.copy(alpha = .84f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (row.selectable) Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                if (row.selected) Icon(painterResource(Art.drawable.fileway_ic_check), null, Modifier.size(18.dp), tint = FilewayPalette.darkAccent)
            }
            if (row.destination) Icon(painterResource(Art.drawable.fileway_ic_expand_more), null, Modifier.size(18.dp).rotate(-90f))
        }
    }
}

/** Same real range for pointer and D-pad. A drag commits only on release. */
@Composable internal fun TvPlayerRange(
    label: String, value: Float, range: ClosedFloatingPointRange<Float>, step: Float, format: (Float) -> String,
    modifier: Modifier = Modifier, expandTrack: Boolean = false, accelerate: Boolean = false, onCommit: (Float) -> Unit,
) {
    var preview by remember { mutableStateOf<Float?>(null) }
    var focused by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    val commit by rememberUpdatedState(onCommit)
    val currentValue by rememberUpdatedState(value)
    val scope = rememberCoroutineScope()
    var heldKey by remember { mutableIntStateOf(KeyEvent.KEYCODE_UNKNOWN) }
    var repeatJob by remember { mutableStateOf<Job?>(null) }
    val shown = preview ?: value
    LaunchedEffect(value) {
        if (!dragging && preview?.let { kotlin.math.abs(it - value) < step / 2 } == true) preview = null
    }
    LaunchedEffect(preview) {
        if (preview != null) {
            delay(5000)
            if (!dragging) preview = null
        }
    }
    fun snap(next: Float): Float {
        val bounded = next.coerceIn(range)
        return if (accelerate) (range.start + ((bounded - range.start) / step).roundToInt() * step).coerceIn(range)
        else bounded
    }
    fun set(next: Float) {
        val bounded = snap(next)
        if (bounded == (preview ?: currentValue)) return
        preview = bounded
        commit(bounded)
    }
    fun stopRepeat() {
        repeatJob?.cancel()
        repeatJob = null
        heldKey = KeyEvent.KEYCODE_UNKNOWN
    }
    val span = range.endInclusive - range.start
    val trackInset = 10.dp
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = tvTextStyle(18, 26))
            Text(format(shown), style = tvTextStyle(16, 22), color = Color.White.copy(alpha = .82f))
        }
        val trackWidth = if (expandTrack) Modifier.weight(2.6f) else Modifier.weight(.65f, fill = false).width(176.dp)
        Canvas(
            modifier.then(trackWidth).height(48.dp)
                .onFocusChanged { focused = it.isFocused; if (!focused) stopRepeat() }
                .background(if (focused) Color.White.copy(alpha = .10f) else Color.Transparent, TvShape)
                .border(2.dp, if (focused) PanelOutline else Color.Transparent, TvShape)
                .pointerInput(range) {
                    detectTapGestures { point ->
                        val inset = trackInset.toPx()
                        val fraction = ((point.x - inset) / (size.width - 2 * inset).coerceAtLeast(1f)).coerceIn(0f, 1f)
                        set(range.start + span * fraction)
                    }
                }
                .pointerInput(range) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragging = true },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            val inset = trackInset.toPx()
                            val fraction = ((change.position.x - inset) / (size.width - 2 * inset).coerceAtLeast(1f)).coerceIn(0f, 1f)
                            preview = snap(range.start + span * fraction)
                        },
                        onDragEnd = { dragging = false; preview?.let(commit) },
                        onDragCancel = { dragging = false; preview = null },
                    )
                }
                .then(if (accelerate) Modifier.onPreviewKeyEvent { event ->
                    val key = event.nativeKeyEvent
                    val direction = when (key.keyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> -1
                        KeyEvent.KEYCODE_DPAD_RIGHT -> 1
                        else -> return@onPreviewKeyEvent false
                    }
                    if (key.action == KeyEvent.ACTION_UP) {
                        if (heldKey == key.keyCode) stopRepeat()
                    } else if (key.action == KeyEvent.ACTION_DOWN && key.repeatCount == 0) {
                        stopRepeat()
                        heldKey = key.keyCode
                        set((preview ?: currentValue) + direction * step)
                        val started = SystemClock.uptimeMillis()
                        repeatJob = scope.launch {
                            delay(380)
                            while (true) {
                                val elapsed = SystemClock.uptimeMillis() - started
                                val multiplier = when {
                                    elapsed >= 1_800 -> 10
                                    elapsed >= 700 -> 5
                                    else -> 1
                                }
                                set((preview ?: currentValue) + direction * step * multiplier)
                                delay(85)
                            }
                        }
                    }
                    true
                } else Modifier.tvRemoteKeys { event ->
                    val key = event.nativeKeyEvent
                    val direction = when (key.keyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> -1
                        KeyEvent.KEYCODE_DPAD_RIGHT -> 1
                        else -> 0
                    }
                    if (direction != 0) set((preview ?: currentValue) + direction * step)
                    direction != 0
                })
                .semantics {
                    contentDescription = label
                    stateDescription = format(shown)
                    progressBarRangeInfo = ProgressBarRangeInfo(shown, range,
                        steps = if (accelerate) ((span / step).roundToInt() - 1).coerceAtLeast(0) else 0)
                    setProgress { target -> if (target.isFinite()) { set(target); true } else false }
                }
                .focusable().padding(horizontal = trackInset),
        ) {
            drawMediaProgressTrack((shown - range.start) / span, Color.White.copy(alpha = .22f), FilewayPalette.darkAccent,
                thickness = if (focused) 4.dp else 3.dp, thumbRadius = if (focused || dragging) 6.dp else 4.dp)
        }
    }
}
