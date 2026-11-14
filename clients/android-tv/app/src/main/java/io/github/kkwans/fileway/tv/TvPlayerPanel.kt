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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
        val action: () -> Unit, val fontFamily: FontFamily? = null, val accessibilityDetail: String? = null,
    ) : TvPlayerRow
}

internal class TvPlayerPanelFocus(initialKey: String?, val onFocused: (String) -> Unit,
    private val page: TvPlayerPage, private val depth: Int, private val windowFocused: State<Boolean>,
    val header: FocusRequester = FocusRequester(), startInBody: Boolean = true) {
    val body = FocusRequester()
    val section = FocusRequester()
    val appearanceScale = FocusRequester()
    var hasSection = false
    var hasHeader = true
    private val initial = FocusRequester()
    private var lastBodyKey = initialKey
    private var observedKey: String? = null
    var focusedBodyKey by mutableStateOf<String?>(null)
        private set
    var initialKey by mutableStateOf(initialKey)
        private set
    var pending by mutableStateOf(startInBody)
        private set

    fun retarget(key: String?) {
        initialKey = key
        pending = true
    }

    fun claimFocus() {
        pending = false
        focusedBodyKey = null
        observedKey = null
    }

    private fun recordFocus(key: String) {
        if (key.startsWith("header/") || key.startsWith("group/")) focusedBodyKey = null
        else {
            lastBodyKey = key
            focusedBodyKey = key
        }
        onFocused(key)
    }

    // A framework fallback is provisional until the requested target is placed.
    // Actual user input takes ownership so a later async result cannot steal focus.
    fun userInput() {
        if (pending) {
            pending = false
            observedKey?.let(::recordFocus)
            trace("page=$page depth=$depth source=user-input target=provisional")
        }
    }

    fun enterBody() {
        initialKey = lastBodyKey?.takeUnless { it.startsWith("header/") || it.startsWith("group/") }
        pending = true
    }

    @Composable fun target(key: String, preferred: Boolean, modifier: Modifier = Modifier): Modifier {
        var placed by remember { mutableStateOf(false) }
        LaunchedEffect(placed, preferred, pending, windowFocused.value) {
            if (placed && preferred && pending && windowFocused.value) {
                val requested = initial.requestFocus(FocusDirection.Enter)
                trace("page=$page depth=$depth source=request target=preferred result=$requested")
                if (requested) pending = false
            }
        }
        return modifier
            .then(if (preferred) Modifier.focusRequester(initial) else Modifier)
            .onFocusChanged {
                if (it.isFocused) {
                    observedKey = key
                    trace("page=$page depth=$depth source=body-focus preferred=$preferred pending=$pending")
                    if (!pending || preferred) {
                        pending = false
                        recordFocus(key)
                    }
                } else if (observedKey == key) {
                    observedKey = null
                }
            }
            .onGloballyPositioned { placed = it.isAttached }
    }
}

private fun TvPlayerPanelFocus.trace(message: String) {
    if (BuildConfig.DEBUG) android.util.Log.d("FilewayTvPanelFocus", message)
}

private fun Modifier.panelUserInput(focus: TvPlayerPanelFocus): Modifier =
    onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown) focus.userInput()
        false
    }.pointerInput(focus) {
        awaitPointerEventScope {
            while (true) {
                if (awaitPointerEvent(PointerEventPass.Initial).changes.any { it.pressed }) focus.userInput()
            }
        }
    }

private const val PanelBackKey = "header/back"
private const val PanelCloseKey = "header/close"
// Static neutral translucency: readable against bright footage without a live blur.
internal val PanelSurface = TvPlayerDesign.surface
internal val PanelFocus = TvPlayerDesign.raised
internal val PanelOutline = TvPlayerDesign.focus
private val PanelTabShape = TvShape

private val PanelTabs = listOf(
    TvPlayerPage.QUEUE to "队列",
    TvPlayerPage.SETTINGS to "设置",
    TvPlayerPage.SPEED to "倍速",
    TvPlayerPage.SUBTITLE to "字幕",
    TvPlayerPage.AUDIO to "音轨",
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
    page: TvPlayerPage, safePercent: Int, child: Boolean, depth: Int, audio: Boolean,
    quick: Boolean, hudHeight: androidx.compose.ui.unit.Dp, quickAnchor: Rect?, quickLabels: List<String>,
    parentPage: TvPlayerPage,
    initialKey: String?, stayOnTab: Boolean, onFocused: (String) -> Unit, onTab: (TvPlayerPage, Boolean) -> Unit,
    onSubtitleSection: (TvPlayerPage) -> Unit, onBack: (String) -> Unit, onClose: () -> Unit,
    feedback: @Composable BoxScope.() -> Unit,
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
            val windowFocused = remember(view) { mutableStateOf(view.hasWindowFocus()) }
            DisposableEffect(view) {
                (view.parent as? DialogWindowProvider)?.window?.let { window ->
                    window.setDimAmount(0f)
                    window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
                    WindowCompat.getInsetsController(window, view).hide(WindowInsetsCompat.Type.systemBars())
                }
                val observer = android.view.ViewTreeObserver.OnWindowFocusChangeListener { windowFocused.value = it }
                view.viewTreeObserver.addOnWindowFocusChangeListener(observer)
                windowFocused.value = view.hasWindowFocus()
                onDispose {
                    if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnWindowFocusChangeListener(observer)
                }
            }
            val saveable = rememberSaveableStateHolder()
            val tabFocus = remember { PanelTabs.associate { it.first to FocusRequester() } }
            val availableTabs = remember(audio) { PanelTabs.filterNot { audio && it.first == TvPlayerPage.SUBTITLE } }
            var requestedTab by remember { mutableStateOf<TvPlayerPage?>(null) }
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
                        val focus = remember(page) {
                            TvPlayerPanelFocus(initialKey, onFocused, page, depth, windowFocused).apply { hasHeader = false }
                        }
                        Column(
                            Modifier.align(Alignment.BottomStart).padding(start = left, bottom = popupBottom)
                                .width(width).clip(TvShape).background(PanelSurface, TvShape)
                                .border(1.dp, Color.White.copy(alpha = .09f), TvShape)
                                .panelUserInput(focus)
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
                    val tabPage = PanelTabs.firstOrNull { it.first == page }?.first
                    val category = if (subtitleSection) TvPlayerPage.SUBTITLE else tabPage ?: parentPage
                    val hasTabs = PanelTabs.any { it.first == category }
                    val panelWidth = (maxWidth - horizontal * 2).coerceAtLeast(0.dp)
                    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
                    // One group geometry for every tab and child. Only viewport/font changes resize it.
                    val panelHeight = minOf(272.dp * fontScale, (maxHeight - bottom * 2).coerceAtLeast(0.dp))
                    val dismiss by rememberUpdatedState(if (child) ({ onBack("dialog-outside") }) else onClose)
                    Box(Modifier.matchParentSize().pointerInput(Unit) { detectTapGestures { dismiss() } })
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(panelHeight + bottom + 80.dp)
                        .background(Brush.verticalGradient(0f to Color.Transparent, .35f to Color.Black.copy(alpha = .22f),
                            1f to Color.Black.copy(alpha = .62f))))
                    run {
                        val focus = remember(page, category, child) { TvPlayerPanelFocus(initialKey, onFocused,
                            page, depth, windowFocused,
                            if (child && !subtitleSection) FocusRequester() else tabFocus[category] ?: FocusRequester(),
                            startInBody = !stayOnTab)
                            .apply { hasSection = subtitleSection } }
                        Column(Modifier.align(Alignment.BottomStart).padding(start = horizontal, bottom = bottom)
                            .width(panelWidth).height(panelHeight).clip(TvShape).background(PanelSurface)
                            .border(1.dp, Color.White.copy(alpha = .08f), TvShape)
                            .panelUserInput(focus)
                            .pointerInput(Unit) { detectTapGestures { } }) {
                            Row(Modifier.fillMaxWidth().height(48.dp * fontScale).padding(horizontal = 24.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (child && !subtitleSection && !hasTabs) TvPlaybackAction(
                                    "返回上一页", Art.drawable.fileway_ic_arrow_back, { onBack("dialog-touch") },
                                    focus.target(PanelBackKey, focus.initialKey == PanelBackKey,
                                        (if (!hasTabs) Modifier.focusRequester(focus.header) else Modifier)
                                            .focusProperties { down = focus.body }))
                                if (hasTabs) {
                                    Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()).focusGroup(),
                                        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                        for ((index, item) in availableTabs.withIndex()) {
                                            val (tab, label) = item
                                            val current = category == tab
                                            TvPlayerPanelButton(label, current,
                                                onClick = {
                                                    if (page != tab || child) onTab(tab, false) else focus.enterBody()
                                                },
                                                description = tab.title.takeIf { tab == TvPlayerPage.QUEUE || tab == TvPlayerPage.SETTINGS },
                                                pill = true,
                                                modifier = Modifier.focusRequester(tabFocus.getValue(tab))
                                                    .onFocusChanged { state ->
                                                        if (state.isFocused) {
                                                            val movedByRemote = requestedTab == tab
                                                            requestedTab = null
                                                            if (BuildConfig.DEBUG) android.util.Log.d("FilewayTvPanelFocus",
                                                                "page=$page depth=$depth tab=$tab source=${if (movedByRemote) "remote-horizontal" else "focus-system"} pending=${focus.pending}")
                                                            if (movedByRemote) {
                                                                focus.claimFocus()
                                                                if (!current) onTab(tab, true)
                                                            } else if (current && !focus.pending) focus.claimFocus()
                                                        }
                                                    }
                                                    .tvRemoteKeys { event ->
                                                        when (event.nativeKeyEvent.keyCode) {
                                                            KeyEvent.KEYCODE_DPAD_DOWN -> {
                                                                if (event.nativeKeyEvent.repeatCount == 0) focus.enterBody()
                                                                true
                                                            }
                                                            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                                                val delta = if (event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
                                                                availableTabs.getOrNull(index + delta)?.first?.let { next ->
                                                                    if (BuildConfig.DEBUG) android.util.Log.d("FilewayTvPanelFocus",
                                                                        "page=$page depth=$depth source=remote-key key=${event.nativeKeyEvent.keyCode} from=$tab to=$next")
                                                                    requestedTab = next
                                                                    if (!tabFocus.getValue(next).requestFocus()) requestedTab = null
                                                                }
                                                                true
                                                            }
                                                            else -> false
                                                        }
                                                    }
                                                    .focusProperties { down = FocusRequester.Cancel })
                                        }
                                    }
                                } else Text(page.title, Modifier.weight(1f), style = tvTextStyle(24, 32, FontWeight.Medium),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                TvPlaybackAction("关闭面板", Art.drawable.fileway_ic_close, onClose,
                                    focus.target(PanelCloseKey, focus.initialKey == PanelCloseKey,
                                        (if (!hasTabs && !child) Modifier.focusRequester(focus.header) else Modifier)
                                            .focusProperties { down = focus.body }))
                            }
                            Box(Modifier.padding(horizontal = 24.dp).fillMaxWidth().height(1.dp).background(TvPlayerDesign.divider))
                            Row(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 24.dp, vertical = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Top) {
                                if (subtitleSection) Column(
                                    Modifier.width(minOf(168.dp * fontScale, panelWidth * .28f)).fillMaxHeight()
                                        .verticalScroll(rememberScrollState()).focusGroup(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    SubtitleSections.forEachIndexed { index, (section, label) ->
                                        val current = page == section
                                        val enter = { if (current) focus.enterBody() else onSubtitleSection(section) }
                                        val key = tvSubtitleGroupKey(section)
                                        TvPlayerPanelButton(label, current, { enter() },
                                            modifier = focus.target(key, focus.initialKey == key,
                                                (if (current) Modifier.focusRequester(focus.section) else Modifier).fillMaxWidth()
                                                    .focusProperties { if (index == 0) up = focus.header }
                                                    .tvRemoteKeys { event ->
                                                        if (event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                                                            enter(); true
                                                        } else false
                                                    }))
                                    }
                                }
                                Column(Modifier.weight(1f).fillMaxHeight().focusRequester(focus.body).focusGroup()) {
                                    if (child && hasTabs && !subtitleSection) Row(
                                        Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        TvPlaybackAction("返回上一页", Art.drawable.fileway_ic_arrow_back,
                                            { onBack("dialog-touch") }, focus.target(PanelBackKey,
                                                focus.initialKey == PanelBackKey, Modifier.focusRequester(focus.header)
                                                    .focusProperties {
                                                        up = tabFocus[category] ?: FocusRequester.Cancel
                                                        down = if (page == TvPlayerPage.APPEARANCE) focus.appearanceScale else focus.body
                                                    }))
                                        Text(page.title, style = tvTextStyle(20, 28, FontWeight.Medium))
                                    }
                                    saveable.SaveableStateProvider(page.name) { content(focus) }
                                }
                            }
                        }
                    }
                }
                feedback()
            }
        }
    }
}

private data class TvPanelTabMetrics(val width: Dp, val labelWidth: Dp)

@Composable private fun tvPanelTabMetrics(label: String): TvPanelTabMetrics {
    val density = LocalDensity.current
    val measure = rememberTextMeasurer()
    return remember(label, density, measure) {
        val width = with(density) { measure.measure(AnnotatedString(label), tvTextStyle(18, 24, FontWeight.Medium), maxLines = 1).size.width.toDp() }
        TvPanelTabMetrics(maxOf(64.dp, width + 24.dp), width)
    }
}

@Composable private fun TvPlayerTabVisual(
    label: String, selected: Boolean, focused: Boolean, hovered: Boolean, metrics: TvPanelTabMetrics,
) {
    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(PanelTabShape)
        .background(if (hovered && !focused) TvPlayerDesign.raised else Color.Transparent)
        .then(if (focused) Modifier.border(2.dp, playerFocusColor(focused), PanelTabShape) else Modifier),
        contentAlignment = Alignment.Center) {
        Text(label, style = tvTextStyle(18, 24, FontWeight.Medium),
            color = if (selected || focused) TvPlayerDesign.text else TvPlayerDesign.secondary, maxLines = 1)
        if (selected) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 1.dp)
            .width(metrics.labelWidth).height(2.dp).background(TvPlayerDesign.accent, PanelTabShape))
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
            .heightIn(min = if (pill) 48.dp else 56.dp)
            .then(if (!pill) Modifier.border(2.dp, playerFocusColor(focused), TvShape) else Modifier)
            .hoverable(interaction).tvTouch(onClick, interaction = interaction, requestFocusOnTouch = !pill).semantics {
            this.selected = selected
            if (description != null) contentDescription = description
        },
        contentPadding = if (pill) PaddingValues(0.dp) else PaddingValues(horizontal = 14.dp, vertical = 4.dp),
        shape = ButtonDefaults.shape(shape), scale = ButtonDefaults.scale(focusedScale = 1f),
        border = ButtonDefaults.border(focusedBorder = Border.None),
        colors = ButtonDefaults.colors(
            containerColor = if (selected && !pill) TvPlayerDesign.raised else if (!pill && hovered) TvPlayerDesign.raised else Color.Transparent,
            contentColor = if (selected) Color.White else Color.White.copy(alpha = .82f),
            focusedContainerColor = if (pill) Color.Transparent else PanelFocus, focusedContentColor = Color.White,
            pressedContainerColor = if (pill) Color.Transparent else PanelFocus,
        ),
    ) {
        if (metrics != null) TvPlayerTabVisual(label, selected, focused, hovered, metrics)
        else Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, Modifier.weight(1f), style = tvTextStyle(18, 28, FontWeight.Medium),
                maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Start)
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                if (selected) Icon(painterResource(Art.drawable.fileway_ic_check), null, Modifier.size(20.dp), tint = TvPlayerDesign.accent)
            }
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
            verticalArrangement = Arrangement.spacedBy(8.dp), flingBehavior = fling, content = listContent)
    }
}

// A fully visible row does not need to be repositioned by the TV focus pivot.
internal object FullRowVisibility : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = when {
        containerSize <= 0 -> 0f
        size >= containerSize || offset < 0 -> offset
        offset + size > containerSize -> offset + size - containerSize
        else -> 0f
    }
}

@Composable internal fun TvPlayerChoiceRow(
    row: TvPlayerRow.Choice, modifier: Modifier = Modifier, trackWidth: Dp? = null, settingsRow: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val titleStyle = tvTextStyle(18, 28, FontWeight.Medium)
    val detailStyle = tvTextStyle(16, 24).copy(fontFamily = row.fontFamily)
    val detailColor = TvPlayerDesign.secondary
    Button(onClick = row.action, enabled = row.enabled, interactionSource = interaction,
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp)
            .border(2.dp, playerFocusColor(focused), TvShape)
            .hoverable(interaction, enabled = row.enabled).tvTouch(row.action, row.enabled, interaction)
            .semantics {
                if (row.selectable) selected = row.selected
                if (row.accessibilityDetail != null) contentDescription = "${row.title}，${row.accessibilityDetail}"
            },
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = if (trackWidth != null) 4.dp else 12.dp),
        shape = ButtonDefaults.shape(TvShape), scale = ButtonDefaults.scale(focusedScale = 1f),
        border = ButtonDefaults.border(focusedBorder = Border.None),
        colors = ButtonDefaults.colors(
            containerColor = when { row.selected -> TvPlayerDesign.selected; hovered -> TvPlayerDesign.raised; else -> Color.Transparent },
            contentColor = TvPlayerDesign.text, focusedContentColor = TvPlayerDesign.text,
            focusedContainerColor = if (row.selected) TvPlayerDesign.selected else PanelFocus,
            pressedContainerColor = TvPlayerDesign.raised,
            disabledContainerColor = Color.Transparent, disabledContentColor = TvPlayerDesign.muted)) {
        Row(Modifier.fillMaxWidth().then(if (trackWidth != null) Modifier.heightIn(min = 48.dp) else Modifier),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (row.icon != null) Icon(painterResource(row.icon), null, Modifier.size(24.dp))
            if (trackWidth != null) {
                val measure = rememberTextMeasurer()
                val contentWidth = with(LocalDensity.current) { (trackWidth - 60.dp).toPx() }
                val detail = row.value
                val inline = row.supporting.isNullOrBlank() && detail != null &&
                    measure.measure(row.title, titleStyle, maxLines = 1).size.width +
                    measure.measure(detail, detailStyle, maxLines = 1).size.width +
                    with(LocalDensity.current) { 12.dp.toPx() } <= contentWidth
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (inline) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(row.title, Modifier.alignByBaseline(), style = titleStyle, maxLines = 1)
                            Text(detail, Modifier.alignByBaseline(), style = detailStyle, color = detailColor, maxLines = 1)
                        }
                    } else {
                        Text(row.title, style = titleStyle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        listOfNotNull(row.supporting, detail).takeIf { it.isNotEmpty() }?.let {
                            Text(it.joinToString(" · "), style = detailStyle, color = detailColor,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            } else if (settingsRow) {
                Text(row.title, Modifier.weight(1f).alignByBaseline(), style = titleStyle,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                row.value?.let {
                    Text(it, Modifier.widthIn(max = 132.dp).alignByBaseline(),
                        style = tvTextStyle(18, 28).copy(fontFeatureSettings = "tnum"),
                        textAlign = TextAlign.End, color = detailColor, maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                }
            } else {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(row.title, style = titleStyle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    row.supporting?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = detailStyle, color = detailColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                row.value?.takeIf { it.isNotBlank() }?.let {
                    Text(it, Modifier.widthIn(max = 132.dp), style = tvTextStyle(18, 24).copy(fontFeatureSettings = "tnum"),
                        textAlign = TextAlign.End, color = detailColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                when {
                    row.selectable && row.selected -> Icon(painterResource(Art.drawable.fileway_ic_check), null, Modifier.size(24.dp), tint = TvPlayerDesign.accent)
                    row.destination -> Icon(painterResource(Art.drawable.fileway_ic_expand_more), null, Modifier.size(24.dp).rotate(-90f), tint = TvPlayerDesign.secondary)
                }
            }
        }
    }
}

/** Pointer and D-pad share the live range; the last value always reaches the existing callback. */
@Composable internal fun TvPlayerRange(
    label: String, value: Float, range: ClosedFloatingPointRange<Float>, step: Float, format: (Float) -> String,
    modifier: Modifier = Modifier, expandTrack: Boolean = false, accelerate: Boolean = false,
    displayLabel: String = label, onCommit: (Float) -> Unit,
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
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { stopRepeat(); dragging = false; preview = null }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); repeatJob?.cancel() }
    }
    val span = range.endInclusive - range.start
    val trackInset = if (expandTrack) 12.dp else 10.dp
    val trackShape = TvShape
    @Composable fun Track(trackWidth: Modifier) {
        Canvas(
            modifier.then(trackWidth).height(48.dp)
                .onFocusChanged { focused = it.isFocused; if (!focused) stopRepeat() }
                .background(if (focused) Color.White.copy(alpha = .08f) else Color.Transparent, trackShape)
                .border(2.dp, playerFocusColor(focused), trackShape)
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
                            set(range.start + span * fraction)
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
    if (expandTrack) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(displayLabel, style = tvTextStyle(18, 28, FontWeight.Medium), color = TvPlayerDesign.text)
                Text(format(shown), style = tvTextStyle(18, 28, FontWeight.Medium).copy(fontFeatureSettings = "tnum"),
                    color = TvPlayerDesign.accent)
            }
            Track(Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth().padding(horizontal = trackInset), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(format(range.start), style = tvTextStyle(16, 24), color = TvPlayerDesign.secondary)
                Text(format(range.endInclusive), style = tvTextStyle(16, 24), color = TvPlayerDesign.secondary)
            }
        }
    } else {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = tvTextStyle(18, 26))
                Text(format(shown), style = tvTextStyle(16, 22), color = Color.White.copy(alpha = .82f))
            }
            Track(Modifier.weight(.65f, fill = false).width(176.dp))
        }
    }
}
