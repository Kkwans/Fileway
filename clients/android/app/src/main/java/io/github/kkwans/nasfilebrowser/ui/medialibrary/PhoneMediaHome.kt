package io.github.kkwans.nasfilebrowser.ui.medialibrary

import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.ImageLoader
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayEmptyState
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayMenuItem
import io.github.kkwans.fileway.design.R as Art

internal enum class PhoneMediaPage { HOME, LIBRARIES, CATEGORIES }

/** The media workspace owns its system-bar insets and navigation. */
@Composable internal fun PhoneMediaHome(state: PhoneMediaHomeState, imageLoader: ImageLoader,
    onAction: (PhoneMediaAction) -> Unit, modifier: Modifier = Modifier,
    gridState: LazyGridState = rememberLazyGridState(), page: PhoneMediaPage = PhoneMediaPage.HOME,
    libraries: List<io.github.kkwans.nasfilebrowser.data.MediaLibrarySummary> = emptyList(),
    selectedLibrary: String? = null, onLibrary: (String?) -> Unit = {}, onBack: () -> Unit = {}) {
    key(state.scopeKey) {
        var continueMenuOpen by remember { mutableStateOf(false) }
        BoxWithConstraints(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            val grid = phoneMediaGridSpec(maxWidth)
            LazyVerticalGrid(columns = GridCells.Fixed(grid.columns), state = gridState,
                contentPadding = PaddingValues(start = grid.pageInset, end = grid.pageInset, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item(key = "heading", span = { GridItemSpan(maxLineSpan) }) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                        MediaIconAction(Art.drawable.fileway_ic_arrow_back, "返回", onBack)
                        Text(when (page) { PhoneMediaPage.HOME -> "影视中心"; PhoneMediaPage.LIBRARIES -> "媒体库"; PhoneMediaPage.CATEGORIES -> "分类" },
                            Modifier.weight(1f).semantics { heading() }, style = PhoneMediaType.pageTitle)
                        if (page == PhoneMediaPage.LIBRARIES && state.canManage)
                            MediaIconAction(R.drawable.ic_settings, "管理媒体库", { onAction(PhoneMediaAction.ManageLibraries) })
                        if (state.canSearchCollections) MediaIconAction(Art.drawable.fileway_ic_library, "合集", { onAction(PhoneMediaAction.Collections) })
                        if (state.canSearch) MediaIconAction(R.drawable.ic_search, "搜索影视", { onAction(PhoneMediaAction.Search) })
                    }
                }
                state.problem?.let { problem -> item(key = "problem", span = { GridItemSpan(maxLineSpan) }) {
                    MediaProblemCard(problem, { onAction(PhoneMediaAction.Retry("home")) })
                } }
                if (!state.libraryExists && state.problem == null) item(key = "first-library", span = { GridItemSpan(maxLineSpan) }) {
                    FilewayEmptyState(R.drawable.ic_library, "还没有媒体库", "媒体库创建后，影视会显示在这里。",
                        if (state.canManage) "创建媒体库" else null,
                        if (state.canManage) ({ onAction(PhoneMediaAction.CreateLibrary) }) else null)
                }
                if (page == PhoneMediaPage.HOME && state.featured.entries.isNotEmpty()) item(key = "featured", span = { GridItemSpan(maxLineSpan) }) {
                    FeaturedCarousel(state, imageLoader, gridState.isScrollInProgress || continueMenuOpen, onAction)
                }
                if (page == PhoneMediaPage.HOME) state.featured.problem?.let { problem -> item(key = "featured-error", span = { GridItemSpan(maxLineSpan) }) {
                    MediaProblemCard(problem, { onAction(PhoneMediaAction.Retry("featured")) })
                } }
                if (page == PhoneMediaPage.HOME && state.featured.loading && state.featured.entries.isEmpty()) item(key = "featured-loading", span = { GridItemSpan(maxLineSpan) }) {
                    MediaLoading("正在加载新入库影视")
                }
                if (page == PhoneMediaPage.HOME && (state.continuing.entries.isNotEmpty() || state.continuing.loading || state.continuing.problem != null || state.continuing.hasMore)) {
                    item(key = "continue", span = { GridItemSpan(maxLineSpan) }) {
                        Column {
                            MediaHeading("继续观看")
                            state.continuing.problem?.let { MediaProblemCard(it, { onAction(PhoneMediaAction.Retry("continue")) }) }
                            if (state.continuing.loading && state.continuing.entries.isEmpty()) MediaLoading("正在加载观看进度")
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(state.continuing.entries, key = { it.id }) { entry ->
                                    ContinueCard(entry, imageLoader, onAction) { continueMenuOpen = it }
                                }
                            }
                            MediaPaging("continue", state.continuing.loading, state.continuing.hasMore, onAction)
                        }
                    }
                }
                if (page == PhoneMediaPage.HOME && state.updatedSeries.entries.isNotEmpty()) item(key = "updated-series", span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        MediaHeading("最近更新剧集")
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(state.updatedSeries.entries, key = { it.id }) { entry ->
                                Box(Modifier.width(140.dp)) {
                                    MediaPosterCard(entry, imageLoader) { onAction(PhoneMediaAction.OpenItem(entry.id)) }
                                }
                            }
                        }
                    }
                }
                if (page == PhoneMediaPage.LIBRARIES && libraries.isNotEmpty()) item(key = "libraries", span = { GridItemSpan(maxLineSpan) }) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { FilterChip(selected = selectedLibrary == null, onClick = { onLibrary(null) },
                            label = { Text("全部媒体库") }, modifier = Modifier.heightIn(min = 48.dp)) }
                        items(libraries, key = { it.id }) { library ->
                            FilterChip(selected = selectedLibrary == library.id, onClick = { onLibrary(library.id) },
                                label = { Text(library.name) }, modifier = Modifier.heightIn(min = 48.dp))
                        }
                    }
                }
                if (page == PhoneMediaPage.CATEGORIES && state.categories.isNotEmpty()) item(key = "categories", span = { GridItemSpan(maxLineSpan) }) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(state.categories, key = { it.id }) { category ->
                            FilterChip(selected = state.selectedCategory == category.id,
                                onClick = { onAction(PhoneMediaAction.Category(category.id)) },
                                label = { Text(category.label) }, modifier = Modifier.heightIn(min = 48.dp))
                        }
                    }
                }
                if (state.libraryExists) item(key = "catalog-heading", span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (page == PhoneMediaPage.HOME) "最近加入" else state.catalogTitle,
                                Modifier.weight(1f).semantics { heading() }, style = PhoneMediaType.sectionTitle)
                        }
                        state.filterSummary?.let { Text(it, style = PhoneMediaType.secondary,
                            color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
                state.catalog.problem?.let { problem -> item(key = "catalog-error", span = { GridItemSpan(maxLineSpan) }) {
                    MediaProblemCard(problem, { onAction(PhoneMediaAction.Retry("catalog")) })
                } }
                if (state.libraryExists && state.catalog.entries.isEmpty() && !state.catalog.loading && !state.catalog.hasMore && state.catalog.problem == null && state.problem == null) {
                    item(key = "catalog-empty", span = { GridItemSpan(maxLineSpan) }) {
                        FilewayEmptyState(R.drawable.ic_video, "这里还没有影视", if (state.filterSummary != null)
                            "试试调整筛选条件。" else "扫描完成后，已收录的作品会出现在这里。",
                            if (state.canManage) "查看媒体库" else null,
                            if (state.canManage) ({ onAction(PhoneMediaAction.ManageLibraries) }) else null)
                    }
                }
                gridItems(state.catalog.entries, key = { "poster/${it.id}" }) { item ->
                    MediaPosterCard(item, imageLoader) { onAction(PhoneMediaAction.OpenItem(item.id)) }
                }
                item(key = "catalog-paging", span = { GridItemSpan(maxLineSpan) }) {
                    MediaPaging("catalog", state.catalog.loading, state.catalog.hasMore, onAction)
                }
            }
        }
    }
}

@Composable private fun FeaturedCarousel(state: PhoneMediaHomeState, loader: ImageLoader,
    parentScrolling: Boolean, onAction: (PhoneMediaAction) -> Unit) {
    val entries = state.featured.entries.take(5)
    val pager = rememberPagerState { entries.size }
    val fallbackHeight = 132.dp * LocalDensity.current.fontScale.coerceIn(1f, 2f)
    var focused by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    var hovered by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    val accessibility = LocalContext.current.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    var accessible by remember(accessibility) { mutableStateOf(accessibility.isEnabled) }
    DisposableEffect(accessibility) {
        val listener = AccessibilityManager.AccessibilityStateChangeListener { accessible = it }
        accessibility.addAccessibilityStateChangeListener(listener)
        onDispose { accessibility.removeAccessibilityStateChangeListener(listener) }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val canAdvance = state.carouselAutoAdvance && resumed && !accessible && !focused && !pressed && !hovered &&
        !parentScrolling && !state.interactionBlocked && !pager.isScrollInProgress && entries.size > 1
    LaunchedEffect(canAdvance, pager.currentPage, entries.map { it.id }) {
        if (canAdvance) { kotlinx.coroutines.delay(8_000); pager.animateScrollToPage((pager.currentPage + 1) % entries.size) }
    }
    Column {
        HorizontalPager(pager, key = { entries[it].id }, modifier = Modifier.fillMaxWidth()
            .onFocusChanged { focused = it.hasFocus }.focusGroup().pointerInput(Unit) {
                try { awaitPointerEventScope { while (true) {
                    val event = awaitPointerEvent()
                    pressed = event.changes.any { it.pressed }
                    if (event.type == PointerEventType.Enter) hovered = true
                    if (event.type == PointerEventType.Exit) hovered = false
                } } } finally { pressed = false; hovered = false }
            }) { index ->
            val entry = entries[index]
            Card(onClick = { onAction(PhoneMediaAction.OpenItem(entry.id)) }, shape = MaterialTheme.shapes.medium) {
                Box(Modifier.fillMaxWidth().then(if (entry.backdrop != null || entry.poster != null)
                    Modifier.aspectRatio(16f / 9f) else Modifier.height(fallbackHeight))
                    .clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainer)) {
                    if (entry.backdrop != null) {
                        MediaImage(entry.backdrop, loader, Modifier.fillMaxSize())
                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .85f)))))
                    } else if (entry.poster != null) {
                        MediaImage(entry.poster, loader, Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                            .aspectRatio(2f / 3f).padding(12.dp))
                        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(
                            listOf(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.colorScheme.surfaceContainer.copy(alpha = .85f), Color.Transparent))))
                    } else {
                        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(
                            MaterialTheme.colorScheme.surfaceContainerHighest,
                            MaterialTheme.colorScheme.surfaceContainer,
                            MaterialTheme.colorScheme.surfaceContainerLow))))
                    }
                    val titleColor = if (entry.backdrop != null) Color.White else MaterialTheme.colorScheme.onSurface
                    val subtitleColor = if (entry.backdrop != null) Color.White.copy(alpha = .85f) else MaterialTheme.colorScheme.onSurfaceVariant
                    Surface(Modifier.align(Alignment.TopStart).padding(12.dp),
                        color = if (entry.backdrop != null) Color.Black.copy(alpha = .48f) else MaterialTheme.colorScheme.surface.copy(alpha = .72f),
                        shape = MaterialTheme.shapes.small) {
                        Text("最近入库", Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = PhoneMediaType.badge, color = titleColor)
                    }
                    Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                        Text(entry.title, color = titleColor, style = PhoneMediaType.workTitle,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(entry.subtitle, color = subtitleColor, style = PhoneMediaType.secondary,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        if (entries.size > 1) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${pager.currentPage + 1} / ${entries.size}", Modifier.weight(1f), style = PhoneMediaType.secondary,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { onAction(PhoneMediaAction.SetCarouselAutoAdvance(!state.carouselAutoAdvance)) }) {
                Text(if (state.carouselAutoAdvance) "暂停轮播" else "自动轮播", style = PhoneMediaType.action)
            }
        }
    }
}

@Composable private fun ContinueCard(entry: MediaContinue, loader: ImageLoader, onAction: (PhoneMediaAction) -> Unit,
    onMenuChanged: (Boolean) -> Unit) {
    var menu by remember(entry.id) { mutableStateOf(false) }
    val updateMenu by rememberUpdatedState(onMenuChanged)
    DisposableEffect(entry.id) { onDispose { if (menu) updateMenu(false) } }
    val setMenu: (Boolean) -> Unit = { menu = it; updateMenu(it) }
    Column(Modifier.width(240.dp)) {
        Card(onClick = { onAction(if (entry.playable) PhoneMediaAction.Play(entry.id, kind = MediaTargetKind.CONTINUE) else PhoneMediaAction.OpenItem(entry.item.id)) },
            enabled = !entry.removing,
            modifier = Modifier.semantics { contentDescription = "继续观看 ${entry.item.title}，${entry.progressLabel}" },
            shape = MaterialTheme.shapes.medium) {
            if (entry.item.backdrop != null) {
                MediaImage(entry.item.backdrop, loader, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            } else {
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(MaterialTheme.colorScheme.surfaceContainer)) {
                    entry.item.poster?.let { MediaImage(it, loader,
                        Modifier.align(Alignment.CenterEnd).fillMaxHeight().aspectRatio(2f / 3f)) }
                    Text(entry.item.title, Modifier.align(Alignment.BottomStart).fillMaxWidth(.65f).padding(12.dp),
                        style = PhoneMediaType.itemTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            MediaProgress(entry.progress)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(top = 8.dp)) {
                Text(entry.item.title, style = PhoneMediaType.itemTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (entry.removing) "正在移出…" else entry.progressLabel, style = PhoneMediaType.secondary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                entry.item.availability?.let { Text(it, style = PhoneMediaType.badge, color = MaterialTheme.colorScheme.error) }
            }
            Box {
                MediaIconAction(R.drawable.ic_more_vert, "${entry.item.title}的更多操作", { setMenu(true) })
                DropdownMenu(menu, { setMenu(false) }) {
                    FilewayMenuItem("查看详情", { setMenu(false); onAction(PhoneMediaAction.OpenItem(entry.item.id)) })
                    if (entry.canStartOver) FilewayMenuItem("从头播放", {
                        setMenu(false); onAction(PhoneMediaAction.Play(entry.id, true, MediaTargetKind.CONTINUE))
                    }, enabled = entry.playable && !entry.removing)
                    if (entry.canRemove) FilewayMenuItem("移出继续观看", {
                        setMenu(false); onAction(PhoneMediaAction.RemoveContinue(entry.id))
                    }, enabled = !entry.removing)
                }
            }
        }
    }
}
