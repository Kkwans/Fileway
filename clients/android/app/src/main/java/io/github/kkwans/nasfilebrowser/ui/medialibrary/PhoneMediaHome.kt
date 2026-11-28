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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
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
import io.github.kkwans.nasfilebrowser.data.MediaCatalogSort
import io.github.kkwans.nasfilebrowser.data.MediaLibrarySummary
import io.github.kkwans.nasfilebrowser.ui.FilewayIcon
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayEmptyState
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayMenuItem
import io.github.kkwans.fileway.design.R as Art

internal enum class PhoneMediaPage { HOME, LIBRARIES, CATEGORIES }

private const val PHONE_HOME_PREVIEW_ROWS = 3

internal fun phoneMediaShowsBack(page: PhoneMediaPage): Boolean = page != PhoneMediaPage.HOME

internal fun phoneMediaHomeUsesCompactHeader(page: PhoneMediaPage): Boolean = page == PhoneMediaPage.HOME

internal fun phoneMediaPageForTab(tab: String): PhoneMediaPage = when (tab) {
    "libraries" -> PhoneMediaPage.LIBRARIES
    "categories" -> PhoneMediaPage.CATEGORIES
    else -> PhoneMediaPage.HOME
}

internal fun phoneMediaLibraryCatalogTitle(
    page: PhoneMediaPage,
    selectedLibraryId: String?,
    libraries: List<MediaLibrarySummary>,
    fallback: String,
): String = if (page == PhoneMediaPage.LIBRARIES) {
    libraries.firstOrNull { it.id == selectedLibraryId }?.name ?: "全部媒体库"
} else fallback

internal fun phoneMediaCatalogForPage(entries: List<MediaPoster>, page: PhoneMediaPage, columns: Int = 2): List<MediaPoster> =
    if (page == PhoneMediaPage.HOME) entries.take(columns * PHONE_HOME_PREVIEW_ROWS) else entries

internal fun phoneMediaCatalogColumns(base: Int, density: PhoneMediaDensity): Int =
    if (density == PhoneMediaDensity.COMPACT) (base + 1).coerceAtMost(6) else base

internal fun phoneMediaHomeShowsFeatured(showFeatured: Boolean, hasEntries: Boolean): Boolean = showFeatured && hasEntries

internal fun phoneMediaHomeSectionOrder(): List<String> = listOf("continue", "catalog", "updated", "featured")

internal fun phoneMediaCatalogSortOptions(filter: String): List<MediaCatalogSort> =
    if (filter == "series") listOf(MediaCatalogSort.NEWEST, MediaCatalogSort.ID, MediaCatalogSort.UPDATED)
    else listOf(MediaCatalogSort.NEWEST, MediaCatalogSort.ID)

internal fun phoneMediaCatalogSortLabel(sort: MediaCatalogSort): String = when (sort) {
    MediaCatalogSort.NEWEST -> "最新加入"
    MediaCatalogSort.ID -> "目录顺序"
    MediaCatalogSort.UPDATED -> "最近更新"
}

internal fun phoneMediaCatalogGridKey(page: String, libraryId: String?, category: String, sort: MediaCatalogSort): String =
    listOf(page, libraryId.orEmpty(), category, sort.name).joinToString("\u0000")

internal fun phoneMediaCatalogHeading(sort: MediaCatalogSort): String = when (sort) {
    MediaCatalogSort.NEWEST -> "最近加入"
    MediaCatalogSort.ID -> "全部影视"
    MediaCatalogSort.UPDATED -> "最近更新"
}

/** The media workspace owns its system-bar insets and navigation. */
@Composable internal fun PhoneMediaHome(state: PhoneMediaHomeState, imageLoader: ImageLoader,
    onAction: (PhoneMediaAction) -> Unit, modifier: Modifier = Modifier,
    gridState: LazyGridState = rememberLazyGridState(), page: PhoneMediaPage = PhoneMediaPage.HOME,
    libraries: List<io.github.kkwans.nasfilebrowser.data.MediaLibrarySummary> = emptyList(),
    selectedLibrary: String? = null, onLibrary: (String?) -> Unit = {}, onBack: () -> Unit = {}) {
    key(state.scopeKey) {
        if (page != PhoneMediaPage.HOME) PhoneMediaGridLoadMore(gridState, state.catalog.hasMore,
            state.catalog.loading, state.catalog.problem != null) { onAction(PhoneMediaAction.LoadMore("catalog")) }
        var continueMenuOpen by remember { mutableStateOf(false) }
        BoxWithConstraints(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            val grid = phoneMediaGridSpec(maxWidth)
            val columns = phoneMediaCatalogColumns(grid.columns, state.density)
            val previewLimit = columns * PHONE_HOME_PREVIEW_ROWS
            val catalog = phoneMediaCatalogForPage(state.catalog.entries, page, columns)
            LazyVerticalGrid(columns = GridCells.Fixed(columns), state = gridState,
                contentPadding = PaddingValues(start = grid.pageInset, end = grid.pageInset, top = 8.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                item(key = "heading", span = { GridItemSpan(maxLineSpan) }) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (phoneMediaShowsBack(page)) MediaIconAction(Art.drawable.fileway_ic_arrow_back, "返回", onBack)
                        Text(when (page) {
                            PhoneMediaPage.HOME -> "影视中心"; PhoneMediaPage.LIBRARIES -> "媒体库"; PhoneMediaPage.CATEGORIES -> "分类"
                        }, Modifier.weight(1f).semantics { heading() },
                            style = if (page == PhoneMediaPage.HOME) PhoneMediaType.workTitle else PhoneMediaType.pageTitle,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (phoneMediaHomeUsesCompactHeader(page) && state.canSearch) {
                            MediaSearchPill({ onAction(PhoneMediaAction.Search) })
                        }
                        if (page == PhoneMediaPage.HOME && (state.featured.entries.isNotEmpty() || state.featured.loading || state.featured.problem != null)) {
                            MediaIconAction(
                                if (state.showFeatured) Art.drawable.fileway_ic_visibility else Art.drawable.fileway_ic_visibility_off,
                                if (state.showFeatured) "隐藏精选推荐" else "显示精选推荐",
                                { onAction(PhoneMediaAction.SetShowFeatured(!state.showFeatured)) })
                        }
                        if (page == PhoneMediaPage.LIBRARIES && state.canManage) {
                            MediaIconAction(R.drawable.ic_settings, "管理媒体库", { onAction(PhoneMediaAction.ManageLibraries) })
                        }
                        if (state.canSearchCollections) {
                            MediaIconAction(Art.drawable.fileway_ic_library, "合集", { onAction(PhoneMediaAction.Collections) })
                        }
                    }
                }
                if (state.canSearch && !phoneMediaHomeUsesCompactHeader(page)) item(key = "search-entry", span = { GridItemSpan(maxLineSpan) }) {
                    MediaSearchEntry(onClick = { onAction(PhoneMediaAction.Search) })
                }
                state.problem?.let { problem -> item(key = "problem", span = { GridItemSpan(maxLineSpan) }) {
                    MediaProblemCard(problem, { onAction(PhoneMediaAction.Retry("home")) })
                } }
                if (!state.libraryExists && state.problem == null) item(key = "first-library", span = { GridItemSpan(maxLineSpan) }) {
                    FilewayEmptyState(R.drawable.ic_library, "还没有媒体库", "媒体库创建后，影视会显示在这里。",
                        if (state.canManage) "创建媒体库" else null,
                        if (state.canManage) ({ onAction(PhoneMediaAction.CreateLibrary) }) else null)
                }
                if (page == PhoneMediaPage.HOME && (state.continuing.entries.isNotEmpty() || state.continuing.loading || state.continuing.problem != null || state.continuing.hasMore)) {
                    item(key = "continue", span = { GridItemSpan(maxLineSpan) }) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            MediaHeading("继续观看")
                            state.continuing.problem?.let { MediaProblemCard(it, { onAction(PhoneMediaAction.Retry("continue")) }) }
                            if (state.continuing.loading && state.continuing.entries.isEmpty()) MediaLoading("正在加载观看进度")
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(end = 8.dp)) {
                                items(state.continuing.entries, key = { it.id }) { entry ->
                                    ContinueCard(entry, imageLoader, onAction) { continueMenuOpen = it }
                                }
                            }
                            MediaPaging("continue", state.continuing.loading, state.continuing.hasMore, onAction)
                        }
                    }
                }
                if (state.libraryExists && (page == PhoneMediaPage.LIBRARIES || state.categories.isNotEmpty())) {
                    item(key = "browse-toolbar", span = { GridItemSpan(maxLineSpan) }) {
                        MediaBrowseToolbar(state, page, libraries, selectedLibrary, onLibrary, onAction)
                    }
                }
                if (state.libraryExists) item(key = "catalog-heading", span = { GridItemSpan(maxLineSpan) }) {
                    val viewAll = page == PhoneMediaPage.HOME &&
                        (state.catalog.entries.size > previewLimit || state.catalog.hasMore)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        MediaHeading(if (page == PhoneMediaPage.HOME) phoneMediaCatalogHeading(state.catalogSort) else
                            phoneMediaLibraryCatalogTitle(page, selectedLibrary, libraries, state.catalogTitle),
                            actionLabel = if (viewAll) "查看全部" else null,
                            onAction = if (viewAll) ({ onAction(PhoneMediaAction.ViewAllCatalog) }) else null)
                        state.filterSummary?.let { Text(it, style = PhoneMediaType.secondary,
                            color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
                state.catalog.problem?.let { problem -> item(key = "catalog-error", span = { GridItemSpan(maxLineSpan) }) {
                    MediaProblemCard(problem, { onAction(PhoneMediaAction.Retry("catalog")) })
                } }
                if (page == PhoneMediaPage.HOME && state.catalog.loading && state.catalog.entries.isEmpty()) {
                    item(key = "catalog-loading", span = { GridItemSpan(maxLineSpan) }) { MediaLoading("正在加载影视") }
                }
                if (state.libraryExists && state.catalog.entries.isEmpty() && !state.catalog.loading && !state.catalog.hasMore && state.catalog.problem == null && state.problem == null) {
                    item(key = "catalog-empty", span = { GridItemSpan(maxLineSpan) }) {
                        FilewayEmptyState(R.drawable.ic_video, "这里还没有影视", if (state.filterSummary != null)
                            "试试调整筛选条件。" else "扫描完成后，已收录的作品会出现在这里。",
                            if (state.canManage) "查看媒体库" else null,
                            if (state.canManage) ({ onAction(PhoneMediaAction.ManageLibraries) }) else null)
                    }
                }
                gridItems(catalog, key = { "poster/${it.id}" }) { item ->
                    MediaPosterCard(item, imageLoader) { onAction(PhoneMediaAction.OpenItem(item.id)) }
                }
                if (page != PhoneMediaPage.HOME) item(key = "catalog-paging", span = { GridItemSpan(maxLineSpan) }) {
                    MediaPaging("catalog", state.catalog.loading, state.catalog.hasMore, onAction)
                }
                if (page == PhoneMediaPage.HOME && state.updatedSeries.entries.isNotEmpty()) {
                    item(key = "updated-series", span = { GridItemSpan(maxLineSpan) }) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            MediaHeading("最近更新剧集", actionLabel = "全部剧集") { onAction(PhoneMediaAction.ViewAllSeries) }
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(end = 8.dp)) {
                                items(state.updatedSeries.entries, key = { it.id }) { entry ->
                                    Box(Modifier.width(264.dp)) {
                                        Card(onClick = { onAction(PhoneMediaAction.OpenItem(entry.id)) },
                                            shape = MaterialTheme.shapes.medium) {
                                            MediaBackdrop(entry, imageLoader, Modifier.fillMaxWidth())
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (page == PhoneMediaPage.HOME && phoneMediaHomeShowsFeatured(state.showFeatured, state.featured.entries.isNotEmpty())) {
                    item(key = "featured", span = { GridItemSpan(maxLineSpan) }) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            MediaHeading("精选推荐")
                            FeaturedCarousel(state, imageLoader, gridState.isScrollInProgress || continueMenuOpen, onAction)
                        }
                    }
                }
                if (page == PhoneMediaPage.HOME && state.showFeatured) state.featured.problem?.let { problem ->
                    item(key = "featured-error", span = { GridItemSpan(maxLineSpan) }) {
                        MediaProblemCard(problem, { onAction(PhoneMediaAction.Retry("featured")) })
                    }
                }
                if (page == PhoneMediaPage.HOME && state.showFeatured && state.featured.loading && state.featured.entries.isEmpty()) {
                    item(key = "featured-loading", span = { GridItemSpan(maxLineSpan) }) { MediaLoading("正在加载精选推荐") }
                }
            }
        }
    }
}

@Composable private fun MediaBrowseToolbar(
    state: PhoneMediaHomeState,
    page: PhoneMediaPage,
    libraries: List<io.github.kkwans.nasfilebrowser.data.MediaLibrarySummary>,
    selectedLibrary: String?,
    onLibrary: (String?) -> Unit,
    onAction: (PhoneMediaAction) -> Unit,
) {
    var sortMenu by remember { mutableStateOf(false) }
    val sortOptions = phoneMediaCatalogSortOptions(state.selectedCategory ?: "all")
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (page == PhoneMediaPage.LIBRARIES) {
            item {
                FilterChip(selected = selectedLibrary == null, onClick = { onLibrary(null) },
                    label = { Text("全部媒体库", maxLines = 1) }, modifier = Modifier.heightIn(min = 48.dp))
            }
            items(libraries, key = { "library/${it.id}" }) { library ->
                FilterChip(selected = selectedLibrary == library.id, onClick = { onLibrary(library.id) },
                    label = {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(library.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(mediaLibraryKindLabel(library.contentKind), style = PhoneMediaType.badge)
                        }
                    }, modifier = Modifier.heightIn(min = 48.dp))
            }
        } else {
            items(state.categories, key = { "category/${it.id}" }) { category ->
                FilterChip(selected = state.selectedCategory == category.id,
                    onClick = { onAction(PhoneMediaAction.Category(category.id)) },
                    label = { Text(category.label) }, modifier = Modifier.heightIn(min = 48.dp))
            }
        }
        item {
            Box {
                FilterChip(selected = false, onClick = { sortMenu = true },
                    label = { Text(phoneMediaCatalogSortLabel(state.catalogSort), maxLines = 1) },
                    modifier = Modifier.heightIn(min = 48.dp))
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    sortOptions.forEach { sort ->
                        DropdownMenuItem(text = { Text(phoneMediaCatalogSortLabel(sort)) },
                            onClick = { sortMenu = false; onAction(PhoneMediaAction.Sort(sort)) })
                    }
                }
            }
        }
        item {
            MediaIconAction(
                if (state.density == PhoneMediaDensity.COMPACT) Art.drawable.fileway_ic_layout_cover else Art.drawable.fileway_ic_layout_compact,
                if (state.density == PhoneMediaDensity.COMPACT) "切换为舒适海报" else "切换为紧凑海报",
                { onAction(PhoneMediaAction.SetDensity(if (state.density == PhoneMediaDensity.COMPACT)
                    PhoneMediaDensity.COMFORTABLE else PhoneMediaDensity.COMPACT)) })
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
    Box(Modifier.fillMaxWidth()) {
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
                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .88f)))))
                    } else if (entry.poster != null) {
                        MediaImage(entry.poster, loader, Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                            .aspectRatio(2f / 3f).padding(12.dp))
                        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(
                            MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.colorScheme.surfaceContainer.copy(alpha = .9f), Color.Transparent))))
                    } else {
                        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(
                            MaterialTheme.colorScheme.surfaceContainerHighest,
                            MaterialTheme.colorScheme.surfaceContainer,
                            MaterialTheme.colorScheme.surfaceContainerLow))))
                        FilewayIcon(R.drawable.ic_video, null, Modifier.align(Alignment.CenterEnd)
                            .padding(24.dp).size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .32f))
                    }
                    val darkArtwork = entry.backdrop != null || entry.poster != null
                    val titleColor = if (darkArtwork) Color.White else MaterialTheme.colorScheme.onSurface
                    val subtitleColor = if (darkArtwork) Color.White.copy(alpha = .78f) else MaterialTheme.colorScheme.onSurfaceVariant
                    Surface(Modifier.align(Alignment.TopStart).padding(12.dp),
                        color = if (darkArtwork) Color.Black.copy(alpha = .48f) else MaterialTheme.colorScheme.surface.copy(alpha = .72f),
                        shape = MaterialTheme.shapes.small) {
                        Text("最近入库", Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = PhoneMediaType.badge, color = titleColor)
                    }
                    entry.primaryRating?.let { rating ->
                        Surface(Modifier.align(Alignment.TopEnd).padding(12.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = .94f), shape = MaterialTheme.shapes.small) {
                            Text(rating.value, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = PhoneMediaType.badge)
                        }
                    }
                    Column(Modifier.align(Alignment.BottomStart).padding(start = 16.dp, end = if (entries.size > 1) 132.dp else 16.dp, bottom = 16.dp)) {
                        Text(entry.title, color = titleColor, style = PhoneMediaType.workTitle,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (entry.subtitle.isNotBlank() && entry.subtitle != entry.title) {
                            Text(entry.subtitle, color = subtitleColor, style = PhoneMediaType.secondary,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        if (entries.size > 1) Surface(Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 12.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = .9f), shape = MaterialTheme.shapes.small) {
            Row(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                repeat(entries.size) { index ->
                    Box(Modifier.size(if (index == pager.currentPage) 16.dp else 6.dp).clip(CircleShape).background(
                        if (index == pager.currentPage) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant))
                }
                MediaIconAction(if (state.carouselAutoAdvance) Art.drawable.fileway_ic_pause else Art.drawable.fileway_ic_play_arrow,
                    if (state.carouselAutoAdvance) "暂停轮播" else "自动轮播",
                    { onAction(PhoneMediaAction.SetCarouselAutoAdvance(!state.carouselAutoAdvance)) })
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
    Column(Modifier.width(272.dp)) {
        Card(onClick = { onAction(if (entry.playable) PhoneMediaAction.Play(entry.id, kind = MediaTargetKind.CONTINUE) else PhoneMediaAction.OpenItem(entry.item.id)) },
            enabled = !entry.removing,
            modifier = Modifier.semantics { contentDescription = "继续观看 ${entry.item.title}，${entry.progressLabel}" },
            shape = MaterialTheme.shapes.medium) {
            MediaBackdrop(entry.item, loader, Modifier.fillMaxWidth(), entry.progress)
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (entry.removing) "正在移出…" else entry.progressLabel, style = PhoneMediaType.secondary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                entry.item.availability?.let { Text(it, style = PhoneMediaType.badge,
                    color = MaterialTheme.colorScheme.error, maxLines = 1, overflow = TextOverflow.Ellipsis) }
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
