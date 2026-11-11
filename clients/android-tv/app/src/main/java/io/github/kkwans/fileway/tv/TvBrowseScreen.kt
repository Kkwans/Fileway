package io.github.kkwans.fileway.tv

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.*
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import io.github.kkwans.fileway.design.R as Art
import io.github.kkwans.nasfilebrowser.app.*
import io.github.kkwans.nasfilebrowser.data.*
import kotlinx.coroutines.*

private val browseRoutes = listOf(TvRoute.FILES, TvRoute.VIDEO, TvRoute.MUSIC, TvRoute.IMAGES)
private val destinations = listOf(TvRoute.HOME) + browseRoutes + TvRoute.FAVORITES
private val tileGap = 12.dp
private val tileFocusInset = 5.dp

@Composable
fun TvBrowseScreen(model: TvModel, onExit: () -> Unit, active: Boolean = true) {
    val state by model.state.collectAsStateWithLifecycle()
    val favorites by model.favorites.state.collectAsStateWithLifecycle()
    val recent by model.recentAccess.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val bodyFocus = remember { FocusRequester() }
    val destinationFocus = remember { FocusRequester() }
    val homeEntryFocus = remember { FocusRequester() }
    val searchFocus = remember { FocusRequester() }
    val locationFocus = remember { FocusRequester() }
    val optionsFocus = remember { FocusRequester() }
    var searchOpen by remember { mutableStateOf(false) }
    var details by remember(model.anchorKey(), state.search) { mutableStateOf<ResourceRef?>(null) }
    var detailCandidates by remember(model.anchorKey(), state.search) { mutableStateOf<List<ResourceRef>>(emptyList()) }
    var locationOpen by remember { mutableStateOf(false) }
    var optionsOpen by remember { mutableStateOf(false) }
    var restoreRequest by remember { mutableIntStateOf(0) }
    val atHome = !state.search && state.route == TvRoute.HOME
    var homeEngaged by remember(model.anchorKey(), state.search) { mutableStateOf(false) }
    val canBrowse = state.route in browseRoutes
    val globalSearch = !canBrowse
    val panelOpen = searchOpen || locationOpen || optionsOpen || details != null
    val canRestore by rememberUpdatedState(!panelOpen)
    val hasParent = !state.search && canBrowse && state.path != "/"
    val ready = !state.busy || state.search
    val files = when {
        state.search || canBrowse -> model.visibleFiles()
        state.route == TvRoute.FAVORITES -> remember(favorites.items) {
            favorites.items.filter { it.openable }.map { ResourceRef(it.path, it.wirePath, it.name, false, "", 0) }
        }
        state.route == TvRoute.RECENT -> remember(state.recentPlayback) {
            state.recentPlayback.distinctBy { it.resourceKey }.map { it.resource() }
        }
        state.route == TvRoute.RECENT_ACCESS -> remember(recent.items) { tvRecentAccessResources(recent.items) }
        else -> emptyList()
    }
    val loading = state.busy || (!state.search && state.route == TvRoute.FAVORITES && favorites.loading) ||
        (!state.search && state.route == TvRoute.RECENT_ACCESS && recent.loading)

    fun returnFocus(target: FocusRequester = bodyFocus) {
        scope.launch {
            withFrameNanos {}
            if (!target.requestFocus() && target != searchFocus) searchFocus.requestFocus()
        }
    }

    fun refresh() {
        if (state.search) model.search(state.searchQuery, state.searchScope) else model.refresh()
    }

    fun retry() = model.retry()

    fun backFromPage(): Boolean {
        val returned = model.backDirectory()
        if (returned) restoreRequest++
        return returned
    }

    val selectRoute: (TvRoute) -> Unit = { route ->
        val alreadyThere = route == state.route && !state.search
        model.navigate(route)
        if (alreadyThere) returnFocus()
    }
    val closeSearch: () -> Unit = { searchOpen = false; returnFocus(searchFocus) }
    val closeLocation: () -> Unit = { locationOpen = false; returnFocus(locationFocus) }
    val closeDetails: () -> Unit = {
        details = null
        restoreRequest++
    }
    val closeOptions: () -> Unit = { optionsOpen = false; returnFocus(optionsFocus) }

    LaunchedEffect(active, state.route, state.search) {
        if (!active) {
            searchOpen = false
            details = null
            locationOpen = false
            optionsOpen = false
        } else if (!state.search && state.route == TvRoute.SETTINGS) {
            withFrameNanos {}
            if (canRestore && !bodyFocus.requestFocus()) destinationFocus.requestFocus()
        }
    }

    BackHandler(enabled = active && !panelOpen) {
        if (!backFromPage()) onExit()
    }

    CompositionLocalProvider(LocalTvInteractive provides active) {
        BoxWithConstraints(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                .then(if (atHome) Modifier.onPreviewKeyEvent {
                    if (it.type == KeyEventType.KeyDown) homeEngaged = true
                    false
                }.pointerInput(model.anchorKey()) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.any { it.pressed && !it.previousPressed }) homeEngaged = true
                        }
                    }
                } else Modifier)
                .then(if (active) Modifier else Modifier.clearAndSetSemantics {})
        ) {
            val horizontal = maxWidth * (state.safePercent / 100f)
            val vertical = maxHeight * (state.safePercent / 100f)
            val compact = maxHeight - vertical * 2 < 430.dp
            val colors = MaterialTheme.colorScheme
            CompositionLocalProvider(LocalTvCompact provides compact) {
                Column(Modifier.fillMaxSize().padding(horizontal = horizontal, vertical = vertical)) {
                    TvBrowseTopBar(
                        state, bodyFocus, destinationFocus, searchFocus, selectRoute, onSearch = { searchOpen = true },
                    )
                    Spacer(Modifier.height(if (compact) 8.dp else 14.dp))
                    val pageTools: @Composable RowScope.() -> Unit = {
                        TvToolbarAction("浏览选项", Art.drawable.fileway_ic_more_actions, { optionsOpen = true },
                            Modifier.focusRequester(optionsFocus))
                    }
                    if (!atHome) {
                        TvBrowseHeader(
                            state, files.size, hasParent, locationFocus, destinationFocus,
                            onBack = { backFromPage() },
                            onLocation = { locationOpen = true },
                            pageTools = pageTools,
                        )
                    }
                    val health = state.health
                    if (health != null) {
                        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(health.message, Modifier.weight(1f), color = colors.error,
                                style = MaterialTheme.typography.bodyMedium)
                            TvButton("重新连接", model::reconnect)
                            if (health.code == SessionHealthCode.EXPIRED || health.code == SessionHealthCode.ACCOUNT_CHANGED) {
                                TvButton("重新登录", model::disconnect)
                            }
                        }
                    }
                    val failure = state.error ?: favorites.error.takeIf { !state.search && state.route == TvRoute.FAVORITES }
                        ?: recent.error.takeIf { !state.search && state.route in setOf(TvRoute.HOME, TvRoute.RECENT_ACCESS) }
                    if (failure != null && (files.isNotEmpty() || state.route == TvRoute.HOME || state.route == TvRoute.SETTINGS)) {
                        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(failure, Modifier.weight(1f), color = colors.error,
                                style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            TvButton("重试", ::retry)
                        }
                    }
                    Box(
                        Modifier.weight(1f).fillMaxWidth().focusRequester(bodyFocus)
                            .focusRestorer(if (atHome) homeEntryFocus else FocusRequester.Default).focusGroup()
                    ) {
                        when {
                            atHome -> TvHome(
                                model, state, recent.items, homeEntryFocus, restoreRequest,
                                onDetails = { file, candidates -> details = file; detailCandidates = candidates },
                                selectRoute = selectRoute, userEngaged = homeEngaged,
                            )
                            !state.search && state.route == TvRoute.SETTINGS -> TvSettingsScreen(model)
                            else -> key(model.anchorKey(), state.search, state.grid, state.gridColumns, state.sortAscending) {
                                TvFiles(model, state, files,
                                    busy = loading,
                                    enabled = ready,
                                    failure = failure,
                                    canRestore = { canRestore },
                                    restoreRequest = restoreRequest,
                                    onRetry = { if (failure != null) retry() else refresh() },
                                    onSearch = { searchOpen = true },
                                    onBack = { backFromPage() },
                                    onFiles = { selectRoute(TvRoute.FILES) },
                                    onDetails = { file, candidates -> details = file; detailCandidates = candidates })
                            }
                        }
                    }
                }
                if (active && !panelOpen) state.notice?.let { notice ->
                    TvBrowseNotice(notice, Modifier.align(Alignment.BottomEnd)
                        .padding(end = horizontal + 8.dp, start = horizontal + 8.dp, bottom = vertical + 8.dp)) { focused ->
                        model.dismissNotice(notice)
                        if (focused) {
                            if (state.route == TvRoute.SETTINGS && !state.search) returnFocus()
                            else restoreRequest++
                        }
                    }
                }
            }
        }

        if (active && searchOpen) {
            TvSearchPanel(
                if (state.search) state.searchQuery else "",
                if (globalSearch) SearchScope.GLOBAL else state.searchScope,
                globalSearch,
                closeSearch,
            ) { query, searchScope ->
                searchOpen = false
                model.search(query, searchScope)
            }
        }
        if (active && locationOpen) TvLocationPanel(
            if (state.search) state.searchBasePath else state.path,
            closeLocation,
        )
        if (active && optionsOpen) TvPanel("浏览选项", closeOptions) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (canBrowse || state.search) {
                    item {
                        TvOptionRow(
                            if (state.grid) "切换列表" else "切换网格",
                            { optionsOpen = false; model.toggleLayout() },
                            icon = if (state.grid) Art.drawable.fileway_ic_layout_list else Art.drawable.fileway_ic_layout_cover,
                            value = if (state.grid) "${state.gridColumns} 列网格" else "列表",
                            showSelection = false,
                        )
                    }
                    item {
                        TvOptionRow(
                            if (state.sortAscending) "名称正序" else "名称倒序",
                            { optionsOpen = false; model.toggleOrder() },
                            icon = Art.drawable.fileway_ic_sort,
                            showSelection = false,
                        )
                    }
                }
                item {
                    TvOptionRow("刷新", { optionsOpen = false; refresh(); returnFocus() },
                        icon = Art.drawable.fileway_ic_refresh, showSelection = false, enabled = !state.busy)
                }
            }
        }
        if (active) details?.let { file ->
            TvPanel("详情", closeDetails) {
                val favorite = model.favorites.favorite(file) != null
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val infoHeight = maxHeight * .52f
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            TvReadableInfo(Modifier.heightIn(max = infoHeight)) {
                                Text(file.name, style = MaterialTheme.typography.titleMedium)
                                Text(file.path, style = MaterialTheme.typography.bodyMedium, color = LocalTvSurfaces.current.secondaryText)
                                val metadata = listOfNotNull(
                                    if (file.directory) "文件夹" else file.size.takeIf { it > 0 }?.let(::tvSize),
                                    file.modified.takeIf { it.isNotBlank() }?.let(::tvDate),
                                ).joinToString(" · ")
                                if (metadata.isNotEmpty()) Text(metadata, style = MaterialTheme.typography.bodySmall,
                                    color = LocalTvSurfaces.current.secondaryText)
                            }
                        }
                        item {
                            TvOptionRow("打开", { details = null; model.open(file, detailCandidates.ifEmpty { listOf(file) }) },
                                icon = if (file.directory) Art.drawable.fileway_ic_folder else Art.drawable.fileway_ic_play_arrow,
                                destination = true)
                        }
                        favorites.error?.let { failure ->
                            item { Text(failure, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                        }
                        item {
                            TvOptionRow(if (favorite) "取消收藏" else "加入收藏", { model.toggleFavorite(file) },
                                icon = Art.drawable.fileway_ic_bookmark, selected = favorite, enabled = !favorites.changing)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TvBrowseNotice(message: String, modifier: Modifier, onDismiss: (Boolean) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val dismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(message) {
        delay(5_000)
        dismiss(focused)
    }
    val surfaces = LocalTvSurfaces.current
    Row(
        modifier.widthIn(max = 480.dp).background(surfaces.raised, TvShape)
            .border(1.dp, surfaces.outline, TvShape).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(message, Modifier.weight(1f, fill = false).padding(vertical = 10.dp),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        TvToolbarAction("关闭提示", Art.drawable.fileway_ic_close, { dismiss(focused) },
            Modifier.onFocusChanged { focused = it.isFocused })
    }
}

@Composable
private fun TvBrowseTopBar(
    state: TvState,
    bodyFocus: FocusRequester,
    destinationFocus: FocusRequester,
    searchFocus: FocusRequester,
    selectRoute: (TvRoute) -> Unit,
    onSearch: () -> Unit,
) {
    val current = if (state.search) null else if (state.route in setOf(TvRoute.RECENT, TvRoute.RECENT_ACCESS)) TvRoute.HOME else state.route
    val down = if (state.busy && !state.search) FocusRequester.Default else bodyFocus
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val split = maxWidth < 320.dp
        val showBrandName = maxWidth >= 600.dp
        if (split) {
            Column {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TvBrowseBrand(showName = true)
                    Spacer(Modifier.weight(1f))
                    TvBrowseUtilities(state, down, destinationFocus, searchFocus, onSearch) { selectRoute(TvRoute.SETTINGS) }
                }
                TvDestinationTabs(current, down, destinationFocus, selectRoute, Modifier.fillMaxWidth())
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TvBrowseBrand(showName = showBrandName)
                TvDestinationTabs(current, down, destinationFocus, selectRoute, Modifier.weight(1f))
                TvBrowseUtilities(state, down, destinationFocus, searchFocus, onSearch) { selectRoute(TvRoute.SETTINGS) }
            }
        }
    }
}

@Composable
private fun TvBrowseBrand(showName: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Image(painterResource(Art.drawable.fileway_ic_launcher_foreground), null, Modifier.size(32.dp))
        if (showName) Text("栖卷", style = MaterialTheme.typography.titleMedium, maxLines = 1)
    }
}

@Composable
private fun TvDestinationTabs(
    current: TvRoute?,
    bodyFocus: FocusRequester,
    destinationFocus: FocusRequester,
    selectRoute: (TvRoute) -> Unit,
    modifier: Modifier = Modifier,
) {
    val style = MaterialTheme.typography.labelLarge
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val tabWidth = remember(style, density, measurer) {
        val width = destinations.maxOf {
            measurer.measure(AnnotatedString(it.title), style.copy(fontWeight = FontWeight.SemiBold)).size.width
        }
        with(density) { width.toDp() + 24.dp }.coerceAtLeast(60.dp)
    }
    val gap = 4.dp
    val selectedIndex = current?.let(destinations::indexOf) ?: -1
    val scroll = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex.coerceAtLeast(0))
    BoxWithConstraints(modifier) {
        val slots = ((maxWidth + gap) / (tabWidth + gap)).toInt().coerceIn(1, destinations.size)
        val rowWidth = tabWidth * slots + gap * (slots - 1)
        LaunchedEffect(selectedIndex, slots) {
            if (slots == destinations.size) scroll.scrollToItem(0)
            else if (selectedIndex >= 0 && selectedIndex !in scroll.firstVisibleItemIndex until scroll.firstVisibleItemIndex + slots) {
                scroll.scrollToItem(selectedIndex)
            }
        }
        LazyRow(
            modifier = Modifier.align(Alignment.Center).width(rowWidth).focusGroup(),
            state = scroll,
            flingBehavior = rememberSnapFlingBehavior(scroll, SnapPosition.Start),
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            items(destinations, key = { it.name }) { route ->
                val selected = route == current
                TvTopDestination(
                    route.title, selected, { selectRoute(route) }, bodyFocus,
                    modifier = if (selected) Modifier.width(tabWidth).focusRequester(destinationFocus) else Modifier.width(tabWidth),
                )
            }
        }
    }
}

@Composable
private fun TvBrowseUtilities(
    state: TvState,
    bodyFocus: FocusRequester,
    destinationFocus: FocusRequester,
    searchFocus: FocusRequester,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        val settings = state.route == TvRoute.SETTINGS && !state.search
        val searchModifier = Modifier.focusRequester(searchFocus)
        TvTopDestination("搜索", state.search, onSearch, bodyFocus,
            modifier = if (state.search) searchModifier.focusRequester(destinationFocus) else searchModifier,
            icon = Art.drawable.fileway_ic_search, showLabel = false)
        TvTopDestination("设置", settings, onSettings, bodyFocus,
            modifier = if (settings) Modifier.focusRequester(destinationFocus) else Modifier,
            icon = Art.drawable.fileway_ic_settings, showLabel = false)
    }
}

@Composable
private fun TvTopDestination(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    bodyFocus: FocusRequester,
    modifier: Modifier = Modifier,
    icon: Int? = null,
    showLabel: Boolean = true,
) {
    val interactive = LocalTvInteractive.current
    val colors = MaterialTheme.colorScheme
    val surfaces = LocalTvSurfaces.current
    val interaction = remember { MutableInteractionSource() }
    var focused by remember { mutableStateOf(false) }
    Surface(
        selected = selected,
        onClick = onClick,
        enabled = interactive,
        interactionSource = interaction,
        modifier = modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .tvTouch(onClick, interactive, interaction)
            .focusProperties { canFocus = interactive; down = bodyFocus }
            .onFocusChanged { focused = it.isFocused }
            .border(2.dp, if (focused) colors.primary else Color.Transparent, TvShape)
            .then(if (showLabel) Modifier else Modifier.semantics { contentDescription = label }),
        shape = SelectableSurfaceDefaults.shape(TvShape),
        scale = SelectableSurfaceDefaults.scale(focusedScale = 1f),
        colors = SelectableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = surfaces.secondaryText,
            selectedContainerColor = colors.onSurface.copy(alpha = .11f),
            selectedContentColor = colors.onSurface,
            focusedContainerColor = surfaces.raised,
            focusedContentColor = colors.onSurface,
            focusedSelectedContainerColor = surfaces.raised,
            focusedSelectedContentColor = colors.onSurface,
            pressedContainerColor = surfaces.raised,
            pressedContentColor = colors.onSurface,
            pressedSelectedContainerColor = surfaces.raised,
            pressedSelectedContentColor = colors.onSurface,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = surfaces.secondaryText,
        ),
    ) {
        Row(
            Modifier.align(Alignment.Center).padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) Icon(painterResource(icon), null, Modifier.size(24.dp))
            if (showLabel) Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun TvBrowseHeader(
    state: TvState,
    count: Int,
    hasParent: Boolean,
    locationFocus: FocusRequester,
    destinationFocus: FocusRequester,
    onBack: () -> Unit,
    onLocation: () -> Unit,
    pageTools: @Composable RowScope.() -> Unit,
) {
    val directory = state.route in browseRoutes
    val surfaces = LocalTvSurfaces.current
    val title = when {
        state.search -> "搜索结果"
        hasParent -> state.path.substringAfterLast('/')
        state.route == TvRoute.FILES -> "根目录"
        state.route == TvRoute.VIDEO -> "视频文件"
        state.route == TvRoute.MUSIC -> "音乐文件"
        state.route == TvRoute.IMAGES -> "图片文件"
        state.route == TvRoute.FAVORITES -> "已收藏的文件"
        state.route == TvRoute.RECENT -> "最近播放记录"
        state.route == TvRoute.RECENT_ACCESS -> "最近访问记录"
        else -> state.route.title
    }
    val hasLocation = hasParent || state.search && state.searchBasePath != "/"
    val parent = if (state.search) state.searchBasePath else state.path.substringBeforeLast('/').ifBlank { "/" }
    val records = state.route == TvRoute.RECENT || state.route == TvRoute.RECENT_ACCESS
    val hasCount = state.search || directory || state.route == TvRoute.FAVORITES || records
    @Composable fun location(modifier: Modifier = Modifier) {
        TvBrowseAction(
            onLocation, modifier.focusRequester(locationFocus).semantics { contentDescription = "当前位置" },
            contentColor = surfaces.secondaryText,
        ) {
            Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(painterResource(Art.drawable.fileway_ic_folder), null, Modifier.size(20.dp))
                Text(parent, style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.StartEllipsis)
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(start = tileFocusInset, end = tileFocusInset, bottom = 6.dp).tvRemoteKeys {
        it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_UP && destinationFocus.requestFocus()
    }) {
        val wide = maxWidth >= 620.dp
        Column {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (wide && hasLocation) location(Modifier.widthIn(max = 180.dp))
                if (wide && state.search) Text("${state.searchQuery} · ${state.searchScope.label}",
                    Modifier.widthIn(max = 150.dp), style = MaterialTheme.typography.bodySmall,
                    color = surfaces.secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (wide && (state.search || hasParent || records)) {
                    TvToolbarAction(if (state.search) "返回目录" else if (records) "返回首页" else "上一级", Art.drawable.fileway_ic_arrow_back, onBack)
                }
                if (wide && hasCount && (!state.busy || state.search)) {
                    Text("$count 项", Modifier.padding(horizontal = 6.dp), style = MaterialTheme.typography.bodySmall,
                        color = surfaces.secondaryText)
                }
                if (state.route == TvRoute.SETTINGS && !state.search) {
                    TvToolbarAction("返回浏览", Art.drawable.fileway_ic_arrow_back, onBack)
                } else pageTools()
            }
            if (!wide && hasCount) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (hasLocation) location(Modifier.weight(1f))
                else if (!state.search) Spacer(Modifier.weight(1f))
                if (state.search) Text("${state.searchQuery} · ${state.searchScope.label}", Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall, color = surfaces.secondaryText,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (state.search || hasParent || records) {
                    TvToolbarAction(if (state.search) "返回目录" else if (records) "返回首页" else "上一级", Art.drawable.fileway_ic_arrow_back, onBack)
                }
                if (!state.busy || state.search) Text("$count 项", style = MaterialTheme.typography.bodySmall,
                    color = surfaces.secondaryText)
            }
        }
    }
}

@Composable
fun TvSectionTitle(title: String) {
    Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
}

private data class TvHomeTile(val file: ResourceRef, val playback: PlaybackSnapshot? = null)

private enum class TvHomeShelf(val title: String, val action: String? = null, val route: TvRoute? = null) {
    PLAYBACK("继续观看", "查看全部", TvRoute.RECENT),
    ACCESS("最近访问", "查看全部", TvRoute.RECENT_ACCESS),
    ROOTS("根目录", "查看全部", TvRoute.FILES),
}

@Composable
private fun TvHome(
    model: TvModel,
    state: TvState,
    recent: List<RecentAccessEntry>,
    entryFocus: FocusRequester,
    restoreRequest: Int,
    onDetails: (ResourceRef, List<ResourceRef>) -> Unit,
    selectRoute: (TvRoute) -> Unit,
    userEngaged: Boolean,
) {
    val playback = remember(state.recentPlayback) {
        state.recentPlayback.distinctBy { it.resourceKey }.take(12).map { TvHomeTile(it.resource(), it) }
    }
    val access = remember(recent) {
        tvRecentAccessResources(recent).take(12).map { TvHomeTile(it) }
    }
    val roots = remember(state.rootEntries) { state.rootEntries.take(12).map { TvHomeTile(it) } }
    val shelves = remember(playback, access, roots) {
        listOf(TvHomeShelf.PLAYBACK to playback, TvHomeShelf.ACCESS to access, TvHomeShelf.ROOTS to roots)
            .filter { it.second.isNotEmpty() }
    }
    val interactive = LocalTvInteractive.current
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val pageKey = model.anchorKey()
    val resourceKey = remember(pageKey, interactive, restoreRequest) { model.anchor().key }
    val shelfIndex = shelves.indexOfFirst { (_, tiles) -> tiles.any { it.file.mediaKey == resourceKey } }.coerceAtLeast(0)
    val rowIndex = shelves.getOrNull(shelfIndex)?.second?.indexOfFirst { it.file.mediaKey == resourceKey }
        ?.coerceAtLeast(0) ?: 0
    val list = rememberLazyListState(initialFirstVisibleItemIndex = shelfIndex)
    var restorePending by remember(pageKey, interactive, restoreRequest) { mutableStateOf(interactive) }
    var entryFocused by remember(shelfIndex, rowIndex) { mutableStateOf(false) }
    fun canUsePage() = interactive && model.media.state.value.file == null && model.anchorKey() == pageKey
    val compact = LocalTvCompact.current
    val latestEngagement by rememberUpdatedState(userEngaged)
    val firstShelf = shelves.firstOrNull()?.first
    var observedFirst by remember(pageKey) { mutableStateOf<TvHomeShelf?>(null) }
    LaunchedEffect(firstShelf, windowFocused, interactive, userEngaged) {
        if (!windowFocused || !interactive) return@LaunchedEffect
        val changed = firstShelf != observedFirst
        observedFirst = firstShelf
        if (changed && firstShelf != null && !userEngaged && resourceKey.isEmpty() && canUsePage()) {
            // A first batch of local/remote history can insert a shelf above
            // roots. Move the untouched initial viewport, never a user's place.
            list.scrollToItem(0)
            repeat(3) {
                withFrameNanos { }
                if (latestEngagement || !canUsePage()) return@LaunchedEffect
                val requested = try { entryFocus.requestFocus() } catch (_: IllegalStateException) { false }
                if (requested) return@LaunchedEffect
            }
        }
    }
    LaunchedEffect(windowFocused, restorePending, shelfIndex) {
        if (windowFocused && restorePending && canUsePage() && list.layoutInfo.visibleItemsInfo.none { it.index == shelfIndex }) {
            list.scrollToItem(shelfIndex)
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val tileWidth = ((maxWidth - tileFocusInset * 2 - tileGap * 3) / 4).coerceAtLeast(112.dp)
        val artworkHeight = tileHeight(tileWidth, compact)
        LazyColumn(
            Modifier.fillMaxSize(),
            state = list,
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(bottom = 8.dp),
        ) {
            itemsIndexed(shelves, key = { _, (shelf, _) -> shelf.name }) { index, (shelf, tiles) ->
                val isRestoreShelf = index == shelfIndex
                val row = rememberLazyListState(initialFirstVisibleItemIndex = if (isRestoreShelf) rowIndex else 0)
                LaunchedEffect(windowFocused, restorePending, isRestoreShelf, rowIndex) {
                    if (windowFocused && restorePending && isRestoreShelf) {
                        val result = restoreBrowseFocus(entryFocus, { entryFocused }, ::canUsePage) { row.scrollToItem(rowIndex) }
                        restorePending = result == BrowseFocusRestore.DEFERRED
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TvShelfHeader(
                        shelf.title, shelf.action, shelf.route?.let { route -> { selectRoute(route) } },
                    )
                    LazyRow(
                        state = row,
                        horizontalArrangement = Arrangement.spacedBy(tileGap),
                        contentPadding = PaddingValues(horizontal = tileFocusInset, vertical = tileFocusInset),
                        modifier = Modifier.focusRestorer().focusGroup(),
                    ) {
                        itemsIndexed(tiles, key = { _, tile -> tile.file.mediaKey }) { tileIndex, tile ->
                            val file = tile.file
                            val isEntry = isRestoreShelf && tileIndex == rowIndex
                            val entry = if (isEntry) Modifier.focusRequester(entryFocus) else Modifier
                            fun recordAnchor() {
                                model.rememberAnchor(file.mediaKey, row.firstVisibleItemIndex, row.firstVisibleItemScrollOffset)
                            }
                            TvFileCard(
                                model, file, {
                                    if (canUsePage()) {
                                        recordAnchor()
                                        when (shelf) {
                                            TvHomeShelf.PLAYBACK -> model.openPlayback(requireNotNull(tile.playback))
                                            TvHomeShelf.ROOTS -> model.open(file, state.rootEntries)
                                            TvHomeShelf.ACCESS -> model.open(file)
                                        }
                                    }
                                },
                                entry.width(tileWidth).onFocusChanged {
                                    if (isEntry) entryFocused = it.isFocused
                                    if (it.isFocused && canUsePage() && (!restorePending || isEntry)) recordAnchor()
                                },
                                artworkHeight = artworkHeight,
                                sourceIdentity = tile.playback?.identity ?: file.modified,
                                playback = tile.playback,
                                onDetails = { if (canUsePage()) {
                                    recordAnchor()
                                    onDetails(file, if (shelf == TvHomeShelf.PLAYBACK) listOf(file) else tiles.map { it.file })
                                } },
                                detailHoldMillis = state.detailsHoldMillis,
                                focusedScale = minOf(1.035f, 1f + (tileFocusInset * 2 / tileWidth)),
                            )
                        }
                    }
                }
            }
            if (shelves.isEmpty()) item(key = "empty") {
                LaunchedEffect(windowFocused, restorePending) {
                    if (windowFocused && restorePending) {
                        withFrameNanos {}
                        if (canUsePage() && entryFocus.requestFocus()) restorePending = false
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TvShelfHeader("文件库")
                    TvButton("浏览文件", { selectRoute(TvRoute.FILES) }, Modifier.focusRequester(entryFocus),
                        icon = Art.drawable.fileway_ic_folder)
                }
            }
        }
    }
}

@Composable
private fun TvShelfHeader(
    title: String,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = tileFocusInset).heightIn(min = 42.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
        if (action != null && onAction != null) {
            TvBrowseAction(onAction, Modifier.semantics { contentDescription = "$title$action" },
                contentColor = LocalTvSurfaces.current.secondaryText) {
                Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(action, style = MaterialTheme.typography.bodySmall)
                    Icon(painterResource(Art.drawable.fileway_ic_expand_more), null, Modifier.size(20.dp).rotate(-90f))
                }
            }
        }
    }
}

private fun PlaybackSnapshot.resource() = ResourceRef(path, wirePath, name, false, "", 0)

@Composable
private fun BoxScope.TvPlaybackIndicator(item: PlaybackSnapshot) {
    Text(
        if (item.durationMs > 0) "${tvTime(item.positionMs)} / ${tvTime(item.durationMs)}" else tvTime(item.positionMs),
        Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 10.dp)
            .clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = .65f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        style = tvTextStyle(14, 20, FontWeight.Medium).copy(fontFeatureSettings = "tnum"),
        color = Color.White, maxLines = 1,
    )
    if (item.durationMs > 0) {
        val fraction = (item.positionMs.toDouble() / item.durationMs).toFloat().coerceIn(0f, 1f)
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(fraction).height(3.dp)
            .background(MaterialTheme.colorScheme.primary))
    }
}

private enum class BrowseFocusRestore { DEFERRED, FOCUSED, EXHAUSTED }

private suspend fun restoreBrowseFocus(
    requester: FocusRequester,
    isTargetFocused: () -> Boolean,
    canRestore: () -> Boolean,
    scrollToTarget: suspend () -> Unit,
): BrowseFocusRestore {
    withFrameNanos {}
    if (!canRestore()) return BrowseFocusRestore.DEFERRED
    if (isTargetFocused()) return BrowseFocusRestore.FOCUSED
    if (!requester.requestFocus()) scrollToTarget()
    // Entering a group can redirect a successful request to its saved child.
    // The home shelf can also redirect at its nested row.
    repeat(2) {
        if (isTargetFocused()) return BrowseFocusRestore.FOCUSED
        withFrameNanos {}
        if (!canRestore()) return BrowseFocusRestore.DEFERRED
        requester.requestFocus()
    }
    if (isTargetFocused()) return BrowseFocusRestore.FOCUSED
    if (BuildConfig.DEBUG) {
        android.util.Log.d("FilewayTvFocus", "browser focus restore exhausted; retaining the saved anchor")
    }
    // The callers suppressed other cards' focus callbacks throughout the attempt.
    // Ending the attempt lets subsequent navigation update the saved anchor normally.
    return BrowseFocusRestore.EXHAUSTED
}

@Composable
private fun TvFiles(
    model: TvModel,
    state: TvState,
    files: List<ResourceRef>,
    busy: Boolean,
    enabled: Boolean,
    failure: String?,
    canRestore: () -> Boolean,
    restoreRequest: Int,
    onRetry: () -> Unit,
    onSearch: () -> Unit,
    onBack: () -> Unit,
    onFiles: () -> Unit,
    onDetails: (ResourceRef, List<ResourceRef>) -> Unit,
) {
    val interactive = LocalTvInteractive.current
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val pageKey = model.anchorKey()
    val requester = remember { FocusRequester() }
    val showBusy = delayedBusy(busy)
    val canFocusContent = interactive && enabled && (files.isNotEmpty() || !busy)
    var restorePending by remember(canFocusContent, restoreRequest, files.isEmpty()) { mutableStateOf(canFocusContent) }
    if (files.isEmpty()) {
        LaunchedEffect(windowFocused, restorePending) {
            if (windowFocused && restorePending) {
                withFrameNanos {}
                if (canRestore() && requester.requestFocus()) restorePending = false
            }
        }
        if (showBusy) TvBrowseLoading(state)
        if (!busy) TvBrowsePlaceholder(state, failure, requester, onRetry, onSearch, onBack, onFiles)
        return
    }

    val anchor = remember(interactive, enabled, restoreRequest) { model.anchor() }
    val restoredIndex = anchor.index.coerceIn(0, files.lastIndex)
    val target = remember(files, anchor) { files.indexOfFirst { it.mediaKey == anchor.key } }
        .takeIf { it >= 0 } ?: restoredIndex
    val targetKey = files[target].mediaKey
    var targetFocused by remember(targetKey) { mutableStateOf(false) }
    val initialIndex = if (anchor.ascending != state.sortAscending || anchor.grid != state.grid) target else restoredIndex
    fun canUsePage() = canFocusContent && model.media.state.value.file == null && model.anchorKey() == pageKey
    fun recordAnchor(file: ResourceRef, index: Int, offset: Int) {
        model.rememberAnchor(file.mediaKey, index, offset)
    }
    fun focusFile(file: ResourceRef, focused: Boolean, index: Int, offset: Int) {
        val isTarget = file.mediaKey == targetKey
        if (isTarget) targetFocused = focused
        if (focused && canUsePage() && (!restorePending || isTarget)) recordAnchor(file, index, offset)
    }
    fun openFile(file: ResourceRef, index: Int, offset: Int) {
        if (canUsePage()) {
            recordAnchor(file, index, offset)
            model.open(file, files)
        }
    }
    fun showDetails(file: ResourceRef, index: Int, offset: Int) {
        if (canUsePage()) {
            recordAnchor(file, index, offset)
            onDetails(file, files)
        }
    }
    val playbackByKey = remember(state.route, state.search, state.recentPlayback) {
        if (state.route == TvRoute.RECENT && !state.search)
            state.recentPlayback.associateBy { it.resource().mediaKey }
        else emptyMap()
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (state.grid) {
            val grid = rememberLazyGridState(initialIndex, anchor.offset)
            val columns = gridColumnsAtWidth(maxWidth, state.gridColumns)
            val tileWidth = (maxWidth - tileFocusInset * 2 - tileGap * (columns - 1)) / columns
            val artworkHeight = tileHeight(tileWidth, LocalTvCompact.current)
            LaunchedEffect(windowFocused, restorePending, targetKey) {
                if (windowFocused && restorePending) {
                    val result = restoreBrowseFocus(requester, { targetFocused }, { canUsePage() && canRestore() }) {
                        grid.scrollToItem(target)
                    }
                    restorePending = result == BrowseFocusRestore.DEFERRED
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns), state = grid,
                horizontalArrangement = Arrangement.spacedBy(tileGap), verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(horizontal = tileFocusInset, vertical = tileFocusInset),
                modifier = Modifier.fillMaxSize().focusGroup(),
            ) {
                gridItems(files, key = { it.mediaKey }) { file ->
                    val focus = if (file.mediaKey == targetKey) Modifier.focusRequester(requester) else Modifier
                    val playback = playbackByKey[file.mediaKey]
                    TvFileCard(model, file, { openFile(file, grid.firstVisibleItemIndex, grid.firstVisibleItemScrollOffset) }, focus.fillMaxWidth().onFocusChanged {
                        focusFile(file, it.isFocused, grid.firstVisibleItemIndex, grid.firstVisibleItemScrollOffset)
                    }, artworkHeight, enabled,
                        sourceIdentity = playback?.identity ?: file.modified, playback = playback,
                        onDetails = { showDetails(file, grid.firstVisibleItemIndex, grid.firstVisibleItemScrollOffset) },
                        detailHoldMillis = state.detailsHoldMillis,
                        focusedScale = minOf(1.035f, 1f + (tileFocusInset * 2 / tileWidth)))
                }
            }
        } else {
            val list = rememberLazyListState(initialIndex, anchor.offset)
            LaunchedEffect(windowFocused, restorePending, targetKey) {
                if (windowFocused && restorePending) {
                    val result = restoreBrowseFocus(requester, { targetFocused }, { canUsePage() && canRestore() }) {
                        list.scrollToItem(target)
                    }
                    restorePending = result == BrowseFocusRestore.DEFERRED
                }
            }
            LazyColumn(
                state = list, verticalArrangement = Arrangement.spacedBy(2.dp),
                contentPadding = PaddingValues(horizontal = tileFocusInset, vertical = 4.dp), modifier = Modifier.fillMaxSize().focusGroup(),
            ) {
                items(files, key = { it.mediaKey }) { file ->
                    val focus = if (file.mediaKey == targetKey) Modifier.focusRequester(requester) else Modifier
                    TvBrowseAction(
                        { openFile(file, list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset) },
                        focus.fillMaxWidth().onFocusChanged {
                            focusFile(file, it.isFocused, list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset)
                        }, enabled, onHold = { showDetails(file, list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset) },
                        longPressMillis = state.detailsHoldMillis,
                    ) {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 58.dp).padding(horizontal = 8.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            TvThumbnail(model, file, Modifier.size(76.dp, 44.dp))
                            Column(Modifier.weight(1f)) {
                                Text(file.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (state.search) {
                                    Text(file.path.substringBeforeLast('/').ifBlank { "/" }, style = MaterialTheme.typography.bodySmall,
                                        color = LocalTvSurfaces.current.secondaryText, maxLines = 1, overflow = TextOverflow.StartEllipsis)
                                } else playbackByKey[file.mediaKey]?.let { progress ->
                                    Text(if (progress.durationMs > 0) "${tvTime(progress.positionMs)} / ${tvTime(progress.durationMs)}" else tvTime(progress.positionMs),
                                        style = MaterialTheme.typography.bodySmall, color = LocalTvSurfaces.current.secondaryText,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            Text(if (file.directory) "文件夹" else tvSize(file.size),
                                style = MaterialTheme.typography.bodySmall, color = LocalTvSurfaces.current.secondaryText)
                            if (file.directory) Icon(painterResource(Art.drawable.fileway_ic_expand_more), null, Modifier.size(20.dp).rotate(-90f))
                            else Spacer(Modifier.width(20.dp))
                        }
                    }
                }
            }
        }
        if (showBusy) {
            Text(if (state.search) "正在搜索…" else "正在刷新…",
                Modifier.align(Alignment.BottomEnd).clip(TvShape).background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodySmall, color = LocalTvSurfaces.current.secondaryText)
        }
    }
}

@Composable
private fun gridColumnsAtWidth(width: Dp, preferred: Int): Int {
    val fontSize = MaterialTheme.typography.bodyMedium.fontSize
    val minimum = with(LocalDensity.current) { fontSize.toDp() * 4 + 16.dp }.coerceAtLeast(84.dp)
    return ((width + tileGap) / (minimum + tileGap)).toInt().coerceIn(1, preferred)
}

@Composable
private fun TvBrowseLoading(state: TvState) {
    val label = if (state.search) "正在搜索…" else "正在读取内容…"
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = LocalTvSurfaces.current.secondaryText)
    }
}

@Composable
private fun TvBrowsePlaceholder(
    state: TvState,
    failure: String?,
    requester: FocusRequester,
    onRetry: () -> Unit,
    onSearch: () -> Unit,
    onBack: () -> Unit,
    onFiles: () -> Unit,
) {
    val search = state.search
    val title = when {
        failure != null -> if (search) "搜索未完成" else "无法读取内容"
        search -> "没有匹配的文件"
        state.route == TvRoute.FAVORITES -> "暂无收藏"
        state.route == TvRoute.RECENT -> "暂无播放记录"
        state.route == TvRoute.RECENT_ACCESS -> "暂无最近访问"
        state.route == TvRoute.VIDEO -> "没有视频"
        state.route == TvRoute.MUSIC -> "没有音乐"
        state.route == TvRoute.IMAGES -> "没有图片"
        else -> "文件夹为空"
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!search && failure == null && state.route in browseRoutes) {
            Icon(painterResource(Art.drawable.fileway_ic_folder), null, Modifier.size(30.dp),
                tint = LocalTvSurfaces.current.secondaryText)
        }
        TvSectionTitle(title)
        if (failure != null) Text(failure, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val canBack = search || state.path != "/" && state.route in browseRoutes
            when {
                failure != null -> TvButton("重试", onRetry, Modifier.focusRequester(requester), icon = Art.drawable.fileway_ic_refresh)
                search -> TvButton("修改搜索", onSearch, Modifier.focusRequester(requester), icon = Art.drawable.fileway_ic_search)
                canBack -> TvButton("上一级", onBack, Modifier.focusRequester(requester), icon = Art.drawable.fileway_ic_arrow_back)
                state.route == TvRoute.FAVORITES || state.route == TvRoute.RECENT || state.route == TvRoute.RECENT_ACCESS ->
                    TvButton("浏览文件", onFiles, Modifier.focusRequester(requester), icon = Art.drawable.fileway_ic_folder)
                else -> TvButton("刷新", onRetry, Modifier.focusRequester(requester), icon = Art.drawable.fileway_ic_refresh)
            }
            if (canBack && (failure != null || search)) {
                TvButton(if (search) "返回目录" else "上一级", onBack, icon = Art.drawable.fileway_ic_arrow_back)
            }
        }
    }
}

private fun tileHeight(width: Dp, compact: Boolean) =
    (width * .5625f).coerceIn(48.dp, if (compact) 124.dp else 148.dp)

@Composable
private fun TvFileCard(
    model: TvModel,
    file: ResourceRef,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    artworkHeight: Dp,
    enabled: Boolean = true,
    sourceIdentity: String = file.modified,
    playback: PlaybackSnapshot? = null,
    onDetails: (() -> Unit)? = null,
    detailHoldMillis: Int = 1000,
    focusedScale: Float = 1.035f,
) {
    var focused by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    TvBrowseAction(
        onClick, modifier.onFocusChanged { focused = it.isFocused }, enabled,
        showFocusFill = false, focusedScale = focusedScale, onHold = onDetails, longPressMillis = detailHoldMillis,
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.fillMaxWidth().height(artworkHeight).clip(TvShape)) {
                TvThumbnail(model, file, Modifier.fillMaxSize(), sourceIdentity)
                if (playback != null) TvPlaybackIndicator(playback)
                if (focused) {
                    Box(Modifier.matchParentSize().border(3.dp, Color.Black.copy(alpha = .55f), TvShape))
                    Box(Modifier.matchParentSize().border(1.5.dp, Color.White.copy(alpha = .96f), TvShape))
                }
            }
            Text(file.name, Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                color = if (focused) colors.onSurface else LocalTvSurfaces.current.secondaryText)
        }
    }
}

@Composable
private fun TvBrowseAction(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color = Color.Transparent,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    showFocusFill: Boolean = true,
    focusedScale: Float = 1f,
    onHold: (() -> Unit)? = null,
    longPressMillis: Int = 1000,
    content: @Composable BoxScope.() -> Unit,
) {
    val interactive = LocalTvInteractive.current && enabled
    val colors = MaterialTheme.colorScheme
    val surfaces = LocalTvSurfaces.current
    val interaction = remember { MutableInteractionSource() }
    val focusBorder = if (showFocusFill) Border(BorderStroke(2.dp, colors.primary), shape = TvShape) else Border.None
    val scope = rememberCoroutineScope()
    val confirmGuard = LocalTvConfirmKeyGuard.current
    val view = LocalView.current
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val click by rememberUpdatedState(onClick)
    val hold by rememberUpdatedState(onHold)
    var heldKey by remember { mutableIntStateOf(-1) }
    var heldDownTime by remember { mutableLongStateOf(0L) }
    var heldDevice by remember { mutableIntStateOf(0) }
    var holdJob by remember { mutableStateOf<Job?>(null) }
    var heldPress by remember { mutableStateOf<PressInteraction.Press?>(null) }
    fun cancelHold(released: Boolean = false, retainUntilUp: Boolean = false) {
        if (heldKey >= 0) {
            val press = TvConfirmPress(heldKey, heldDownTime, heldDevice)
            if (retainUntilUp) confirmGuard?.suppress(press) else confirmGuard?.finish(press)
        }
        holdJob?.cancel(); holdJob = null; heldKey = -1
        heldPress?.let { press ->
            scope.launch { interaction.emit(if (released) PressInteraction.Release(press) else PressInteraction.Cancel(press)) }
        }
        heldPress = null
    }
    DisposableEffect(confirmGuard) { onDispose { cancelHold(retainUntilUp = true) } }
    LaunchedEffect(interactive, windowFocused) {
        if (!interactive || !windowFocused) cancelHold(retainUntilUp = true)
    }
    val detailKeys = if (onHold == null) Modifier else Modifier
        .onFocusChanged { if (!it.hasFocus) cancelHold(retainUntilUp = true) }
        .onPreviewKeyEvent { event ->
            val native = event.nativeKeyEvent
            val key = native.keyCode
            if (!interactive) false
            else if (key == KeyEvent.KEYCODE_INFO) {
                if (event.type == KeyEventType.KeyDown && native.repeatCount == 0) {
                    cancelHold(retainUntilUp = true)
                    hold?.invoke()
                }
                event.type == KeyEventType.KeyDown || event.type == KeyEventType.KeyUp
            } else if (key in setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                when (event.type) {
                    KeyEventType.KeyDown -> {
                        if (native.repeatCount == 0 &&
                            (heldKey != key || heldDownTime != native.downTime || heldDevice != native.deviceId)) {
                            cancelHold(retainUntilUp = true)
                            heldKey = key; heldDownTime = native.downTime; heldDevice = native.deviceId
                            confirmGuard?.begin(TvConfirmPress(key, native.downTime, native.deviceId))
                            val press = PressInteraction.Press(Offset.Zero)
                            heldPress = press
                            scope.launch { interaction.emit(press) }
                            val downTime = native.downTime
                            val device = native.deviceId
                            holdJob = scope.launch {
                                delay(longPressMillis.toLong())
                                if (heldKey == key && heldDownTime == downTime && heldDevice == device) {
                                    val action = hold.takeIf { interactive && view.hasWindowFocus() }
                                    holdJob = null
                                    cancelHold(retainUntilUp = true)
                                    action?.invoke()
                                }
                            }
                        }
                        true
                    }
                    KeyEventType.KeyUp -> {
                        if (heldKey != key || heldDownTime != native.downTime || heldDevice != native.deviceId) false else {
                            val activate = !native.isCanceled
                            cancelHold(released = !native.isCanceled)
                            if (activate) click()
                            true
                        }
                    }
                    else -> false
                }
            } else false
        }
    Surface(
        onClick = onClick, enabled = interactive, interactionSource = interaction,
        modifier = modifier.then(detailKeys).tvTouch(onClick, interactive, interaction, onHold)
            .focusProperties { canFocus = interactive }
            .then(if (onHold != null) Modifier.semantics { onLongClick(label = "查看详情") {
                if (!interactive) false else { hold?.invoke(); true }
            } } else Modifier),
        shape = ClickableSurfaceDefaults.shape(TvShape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = focusedScale),
        border = ClickableSurfaceDefaults.border(focusedBorder = focusBorder),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = containerColor, contentColor = contentColor,
            focusedContainerColor = if (showFocusFill) surfaces.raised else containerColor,
            focusedContentColor = colors.onSurface,
            pressedContainerColor = colors.primary.copy(alpha = .18f),
            pressedContentColor = colors.onSurface,
            disabledContainerColor = containerColor,
            disabledContentColor = surfaces.secondaryText,
        ),
        content = content,
    )
}

@Composable fun TvThumbnail(model:TvModel,file:ResourceRef,modifier:Modifier=Modifier,sourceIdentity:String=file.modified,
    shape: Shape = TvShape) {
    val owner=model.context
    var lease by remember(owner?.owner,file.mediaKey){mutableStateOf<PreviewLease?>(null)}
    var thumbnailFailed by remember(owner?.owner,file.mediaKey,sourceIdentity){mutableStateOf(false)}
    var thumbnailReady by remember(owner?.owner,file.mediaKey,sourceIdentity){mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    LaunchedEffect(owner?.owner,file.mediaKey) {
        if(owner!=null&&!file.directory&&file.mediaKind()!=null)try{lease=owner.api.preview(file.path,file.wirePath,true)}catch(error:Exception){if(error is CancellationException)throw error}
    }
    DisposableEffect(owner?.owner,file.mediaKey){onDispose{val resource=lease;scope.launch(NonCancellable){resource?.release()}}}
    Box(modifier.clip(shape).background(MaterialTheme.colorScheme.surface),contentAlignment=Alignment.Center) {
        val resource=lease
        if(!thumbnailReady) Icon(painterResource(if(file.directory)Art.drawable.fileway_ic_folder else when(file.mediaKind()){MediaKind.VIDEO->Art.drawable.fileway_ic_video;MediaKind.AUDIO->Art.drawable.fileway_ic_audio;MediaKind.IMAGE->Art.drawable.fileway_ic_image;else->Art.drawable.fileway_ic_file}),null,Modifier.size(40.dp),tint=LocalTvSurfaces.current.secondaryText)
        if(resource!=null&&!thumbnailFailed)AsyncImage(model=ImageRequest.Builder(LocalContext.current).data(resource.url).memoryCacheKey("${owner?.owner}/${file.mediaKey}/$sourceIdentity").size(512,if(file.mediaKind()==MediaKind.AUDIO)512 else 288).build(),imageLoader=model.imageLoader,onSuccess={thumbnailReady=true},onError={thumbnailFailed=true;thumbnailReady=false},contentDescription=null,contentScale=ContentScale.Fit,modifier=Modifier.fillMaxSize())
    }
}

@Composable
private fun TvLocationPanel(path: String, onClose: () -> Unit) {
    TvPanel("当前位置", onClose) {
        TvReadableInfo {
            Text(path.substringAfterLast('/').ifBlank { "根目录" }, style = MaterialTheme.typography.titleMedium)
            Text(path, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun TvReadableInfo(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val step = with(LocalDensity.current) { 96.dp.roundToPx() }
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier.fillMaxWidth().tvRemoteKeys { event ->
            val delta = when (event.nativeKeyEvent.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> -step
                KeyEvent.KEYCODE_DPAD_DOWN -> step
                else -> 0
            }
            val next = (scroll.value + delta).coerceIn(0, scroll.maxValue)
            if (next == scroll.value) false else {
                scope.launch { scroll.scrollTo(next) }
                true
            }
        }.onFocusChanged { focused = it.isFocused }
            .border(2.dp, if (focused) MaterialTheme.colorScheme.primary else Color.Transparent, TvShape)
            .focusable().verticalScroll(scroll).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
private fun TvSearchPanel(
    initialQuery: String,
    initialScope: SearchScope,
    globalOnly: Boolean,
    onClose: () -> Unit,
    onSearch: (String, SearchScope) -> Unit,
) {
    var query by remember { mutableStateOf(initialQuery) }
    var scope by remember { mutableStateOf(initialScope) }
    fun submit() {
        val keyword = query.trim()
        if (keyword.isNotEmpty()) onSearch(keyword, scope)
    }

    TvPanel("搜索", onClose) {
        val focusManager = LocalFocusManager.current
        val view = LocalView.current
        val submitFocus = remember { FocusRequester() }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Bottom) {
                    val field = Modifier.weight(1f).tvRemoteKeys { event ->
                        val direction = when (event.nativeKeyEvent.keyCode) {
                            KeyEvent.KEYCODE_DPAD_DOWN -> FocusDirection.Down
                            KeyEvent.KEYCODE_DPAD_RIGHT -> FocusDirection.Right
                            else -> return@tvRemoteKeys false
                        }
                        val editing = ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true
                        !editing && focusManager.moveFocus(direction)
                    }
                    TvField("文件名或关键词", query, { query = it }, field,
                        options = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { submit() }))
                    TvButton("开始搜索", ::submit, Modifier.focusRequester(submitFocus).heightIn(min = 50.dp),
                        enabled = query.isNotBlank())
                }
            }
            if (!globalOnly) {
                item { Text("搜索范围", style = MaterialTheme.typography.bodySmall, color = LocalTvSurfaces.current.secondaryText) }
                itemsIndexed(SearchScope.entries, key = { _, value -> value.name }) { index, value ->
                    val modifier = if (index == 0 && query.isNotBlank()) Modifier.focusProperties { up = submitFocus } else Modifier
                    TvOptionRow(value.label, { scope = value }, modifier, selected = scope == value)
                }
            }
        }
    }
}
