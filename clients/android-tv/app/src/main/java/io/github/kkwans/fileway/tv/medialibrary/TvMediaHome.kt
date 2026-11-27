package io.github.kkwans.fileway.tv.medialibrary

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.Text
import coil3.ImageLoader
import io.github.kkwans.nasfilebrowser.data.MediaCatalogRowId
import kotlinx.coroutines.delay
import io.github.kkwans.fileway.design.R as Art

/** Stateless navigation/playback boundary. Keep scrollState in the owning route for return restoration. */
@Composable
fun TvMediaHome(
    state: TvMediaHomeState,
    imageLoader: ImageLoader,
    onItem: (String) -> Unit,
    onContinue: (String) -> Unit,
    modifier: Modifier = Modifier,
    onPlay: ((String) -> Unit)? = null,
    onContinueOptions: ((String) -> Unit)? = null,
    onRetryContinue: (() -> Unit)? = null,
    onMoreContinue: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    onRetryShelf: ((String) -> Unit)? = null,
    onMoreShelf: ((String) -> Unit)? = null,
    onRetryRow: ((MediaCatalogRowId) -> Unit)? = null,
    onMoreRow: ((MediaCatalogRowId) -> Unit)? = null,
    onCategory: ((String) -> Unit)? = null,
    onManageLibrary: (() -> Unit)? = null,
    autoRotate: Boolean = true,
    interactionPaused: Boolean = false,
    scrollState: LazyListState = rememberLazyListState(),
    navigation: @Composable () -> Unit = {},
) {
    val colors = LocalMediaColors.current
    val catalog = state.shelves.firstOrNull { it.id == "catalog" }
    val showCatalog = catalog != null && tvMediaCatalogShelfVisible(catalog, state.categories)
    val shelves = state.shelves.mapNotNull { shelf ->
        if (shelf.id == "catalog") shelf.takeIf { showCatalog } else shelf.takeIf(::tvMediaShelfVisible)
    }
    val rows = state.rows.mapNotNull { row -> tvMediaHomeRowShelf(row)?.let { row.rowId to it } }
        .filter { (_, shelf) -> tvMediaShelfVisible(shelf) }
    val hasContent = state.featured.isNotEmpty() || state.continuing.isNotEmpty() || rows.isNotEmpty() || shelves.isNotEmpty()
    Column(modifier.fillMaxSize().background(colors.canvas)) {
        LazyColumn(state = scrollState, modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(MediaLayout.sectionGap)) {
            if (state.featured.isEmpty()) item(key = "navigation") { navigation() }
            if (state.featured.isNotEmpty()) item(key = "featured") {
                MediaFeaturedCarousel(state.scopeKey, state.featured.take(5), imageLoader, onItem, onPlay,
                    autoRotate, interactionPaused, navigation)
            }
            if (state.error != null) item(key = "status-error") { MediaStatus(state.error, onRetry, compact = hasContent) }
            if (state.continuing.isNotEmpty() || state.continueLoading || state.continueError != null || state.continueHasMore) item(key = "continue") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    MediaSectionTitle("继续观看")
                    state.continueError?.let { MediaStatus(it, onRetryContinue, compact = true) }
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val cardWidth = mediaCardWidth(maxWidth, 200.dp, 4)
                        LazyRow(Modifier.focusGroup(), contentPadding = MediaLayout.rowPadding,
                            horizontalArrangement = Arrangement.spacedBy(MediaLayout.itemGap)) {
                            items(state.continuing, key = { it.id }) { entry ->
                                MediaContinueCard(entry, imageLoader, { onContinue(entry.id) },
                                    onContinueOptions?.let { { it(entry.id) } }, cardWidth)
                            }
                            if (state.continueHasMore && onMoreContinue != null) item(key = "more") {
                                MediaAction(if (state.continueLoading) "正在加载…" else "查看更多",
                                    onMoreContinue, enabled = !state.continueLoading)
                            }
                        }
                    }
                    if (state.continueLoading && state.continuing.isEmpty()) MediaLoadingStatus("正在读取观看进度…", compact = true)
                }
            }
            items(rows, key = { "row:${it.first}" }) { (rowId, shelf) ->
                MediaShelf(shelf, imageLoader, onItem,
                    onRetry = onRetryRow?.let { { it(rowId) } },
                    onMore = onMoreRow?.let { { it(rowId) } })
            }
            items(shelves, key = { "shelf:${it.id}" }) { shelf ->
                if (shelf.id == "catalog") MediaCatalogShelf(shelf, state.categories, state.selectedCategory,
                    imageLoader, onItem, onRetryShelf?.let { { it(shelf.id) } }, onMoreShelf?.let { { it(shelf.id) } }, onCategory)
                else MediaShelf(shelf, imageLoader, onItem, onRetryShelf)
            }
            if (state.loading && !hasContent) item(key = "loading") { MediaLoadingStatus("正在加载片库…") }
            if (!hasContent && !state.loading && state.error == null) item(key = "empty") {
                Column(Modifier.fillMaxWidth().padding(horizontal = MediaLayout.pageInset, vertical = 64.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(state.emptyTitle, style = MediaType.pageTitle, color = colors.text)
                    Text(state.emptyMessage, Modifier.widthIn(max = 520.dp), style = MediaType.body, color = colors.secondary)
                    if (onManageLibrary != null) MediaAction("管理片库", onManageLibrary,
                        primary = true, icon = Art.drawable.fileway_ic_library)
                }
            }
        }
    }
}

@Composable
private fun MediaCatalogShelf(
    shelf: TvMediaShelf,
    categories: List<TvMediaCategory>,
    selectedCategory: String?,
    imageLoader: ImageLoader,
    onItem: (String) -> Unit,
    onRetry: (() -> Unit)?,
    onMore: (() -> Unit)?,
    onCategory: ((String) -> Unit)?,
) {
    val colors = LocalMediaColors.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        MediaSectionTitle("我的片库")
        if (categories.isNotEmpty() && onCategory != null) LazyRow(Modifier.fillMaxWidth().focusGroup(),
            contentPadding = MediaLayout.rowPadding, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(categories, key = { it.id }) { category ->
                val active = category.id == selectedCategory
                val focus = LocalTvMediaFocusBinding.current
                Column {
                    MediaSurface({
                        focus?.onOpen?.invoke(category.id, "category")
                        onCategory(category.id)
                    }, Modifier.heightIn(min = 48.dp).tvMediaReturnFocus(category.id, "category"), selected = active,
                        container = Color.Transparent, selectionContainer = false,
                        contentColor = if (active) colors.accent else colors.secondary) {
                        Text(category.label, Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                            style = MediaType.action, maxLines = 1)
                    }
                    Box(Modifier.align(Alignment.CenterHorizontally).width(if (active) 24.dp else 0.dp).height(2.dp)
                        .background(if (active) colors.accent else Color.Transparent))
                }
            }
        }
        shelf.error?.let { MediaStatus(it, onRetry, compact = true) }
        if (shelf.items.isEmpty() && shelf.loading) MediaLoadingStatus("正在加载片库…", compact = true)
        if (shelf.items.isEmpty() && !shelf.loading && shelf.error == null) {
            Text("这个分类还没有可见内容。", Modifier.padding(horizontal = MediaLayout.pageInset),
                style = MediaType.body, color = colors.secondary)
        }
        if (shelf.items.isNotEmpty()) BoxWithConstraints(Modifier.fillMaxWidth()) {
            val width = mediaCardWidth(maxWidth, 128.dp, 6)
            val columns = ((maxWidth - MediaLayout.pageInset * 2 + MediaLayout.itemGap) / (width + MediaLayout.itemGap))
                .toInt().coerceAtLeast(1)
            shelf.items.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(horizontal = MediaLayout.pageInset).focusGroup(),
                    horizontalArrangement = Arrangement.spacedBy(MediaLayout.itemGap)) {
                    row.forEach { item -> MediaPoster(item, imageLoader, { onItem(item.id) }, width = width) }
                }
            }
        }
        if (shelf.hasMore && onMore != null) MediaAction(if (shelf.loading) "正在加载…" else "加载更多影视",
            onMore, Modifier.padding(horizontal = MediaLayout.pageInset), enabled = !shelf.loading)
    }
}

@Composable
private fun MediaFeaturedCarousel(
    scopeKey: String, entries: List<TvMediaCard>, imageLoader: ImageLoader,
    onItem: (String) -> Unit, onPlay: ((String) -> Unit)?, autoRotate: Boolean, interactionPaused: Boolean,
    navigation: @Composable () -> Unit,
) {
    val colors = LocalMediaColors.current
    var activeId by rememberSaveable(scopeKey) { mutableStateOf(entries.first().id) }
    // A reordered list must not retarget the focused action. Disappearance is treated
    // as invalidation, including both a top-five eviction and permission revocation:
    // never retain an old artwork/payload or silently fall back to another work.
    val index = entries.indexOfFirst { it.id == activeId }
    val current = entries.getOrNull(index)
    var containsFocus by remember(scopeKey) { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val clearInvalidatedFocus = current == null && containsFocus
    LaunchedEffect(scopeKey, activeId, current == null) {
        if (clearInvalidatedFocus) focusManager.clearFocus(force = true)
    }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val ids = entries.map { it.id }
    val mayRotate = autoRotate && !interactionPaused && !containsFocus && windowFocused &&
        lifecycle.isAtLeast(Lifecycle.State.RESUMED) && entries.size > 1 && current != null
    LaunchedEffect(scopeKey, ids, activeId, mayRotate) {
        if (mayRotate) {
            delay(8_000)
            activeId = entries[(index + 1) % entries.size].id
        }
    }
    if (current == null) {
        // This is a different action node from the former Details/Play buttons. A
        // pending press cannot become an action on the replacement work. Recovery
        // requires an explicit new selection; auto-rotation stays paused meanwhile.
        key(scopeKey, "invalidated-feature") {
            Column(Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                navigation()
                Column(Modifier.padding(horizontal = MediaLayout.pageInset, vertical = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("最近入库内容已更新", style = MediaType.sectionTitle, color = colors.text)
                    Text("当前影片已不在此列表中，请重新选择。", style = MediaType.body, color = colors.secondary)
                    MediaAction("重新浏览", { activeId = entries.first().id }, icon = Art.drawable.fileway_ic_refresh)
                }
            }
        }
        return
    }
    Box(Modifier.fillMaxWidth().onFocusChanged { containsFocus = it.hasFocus }.focusGroup()) {
        MediaHeroCanvas(current, imageLoader, Modifier.matchParentSize())
        Column(Modifier.fillMaxWidth()) {
            navigation()
        Column(Modifier.fillMaxWidth().heightIn(min = if (current.backdrop != null || current.poster != null) 260.dp else 180.dp)
            .padding(start = MediaLayout.pageInset, end = MediaLayout.pageInset, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.width(3.dp).height(17.dp).background(colors.accent, RoundedCornerShape(2.dp)))
                Text("最近入库", style = MediaType.secondary, color = colors.secondary)
            }
            Column(Modifier.widthIn(max = 480.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(current.title, style = MediaType.workTitle, color = colors.text,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                current.originalTitle?.takeIf(String::isNotBlank)?.let {
                    Text(it, style = MediaType.secondary, color = colors.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (current.metadataLabel.isNotBlank()) Text(current.metadataLabel, Modifier.alignByBaseline(),
                        style = MediaType.body, color = colors.text)
                    current.primaryRating?.let {
                        Text("${it.source.label} ${it.valueLabel}", Modifier.alignByBaseline(), style = MediaType.badge, color = colors.score)
                    }
                }
                current.overview?.takeIf(String::isNotBlank)?.let {
                    Text(it, style = MediaType.body, color = colors.secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
            key(scopeKey, current.id) {
                val focus = LocalTvMediaFocusBinding.current
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    MediaAction("查看影片", { focus?.onOpen?.invoke(current.id, "hero"); onItem(current.id) },
                        Modifier.tvMediaReturnFocus(current.id, "hero"), primary = true)
                    if (onPlay != null && current.canPlay) MediaQuietAction("播放", {
                        focus?.onOpen?.invoke(current.id, "hero-play"); onPlay(current.id)
                    }, Modifier.tvMediaReturnFocus(current.id, "hero-play"), icon = Art.drawable.fileway_ic_play_arrow)
                }
            }
            if (entries.size > 1) Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                MediaIconAction("上一部", { activeId = entries[(index - 1 + entries.size) % entries.size].id },
                    Art.drawable.fileway_ic_arrow_back)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    repeat(entries.size) { dot ->
                        Box(Modifier.width(if (dot == index) 18.dp else 10.dp).height(3.dp)
                            .background(if (dot == index) colors.accent else colors.secondary.copy(alpha = .35f), RoundedCornerShape(2.dp)))
                    }
                }
                Text("${index + 1} / ${entries.size}", style = MediaType.secondary, color = colors.secondary)
                MediaIconAction("下一部", { activeId = entries[(index + 1) % entries.size].id }, Art.drawable.fileway_ic_arrow_forward)
            }
            }
        }
        }
    }
}

@Composable
private fun MediaContinueCard(
    entry: TvMediaContinueItem, imageLoader: ImageLoader, onClick: () -> Unit, onOptions: (() -> Unit)?,
    width: androidx.compose.ui.unit.Dp,
) {
    val colors = LocalMediaColors.current
    val focus = LocalTvMediaFocusBinding.current
    Column(Modifier.width(width), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MediaSurface({ focus?.onOpen?.invoke(entry.id, "continue"); onClick() },
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).tvMediaReturnFocus(entry.id, "continue")
            .semantics { contentDescription = "继续观看 ${entry.item.title} ${entry.episodeLabel.orEmpty()}" },
            enabled = entry.item.canPlay && !entry.removing) {
            MediaArtwork(entry.item.backdrop ?: entry.item.poster, imageLoader, Modifier.fillMaxSize())
            MediaProgress(entry.progress, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(7.dp))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(entry.item.title, style = MediaType.itemTitle, color = colors.text,
                    minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                listOfNotNull(entry.episodeLabel, if (entry.removing) "正在移出…" else entry.remainingLabel, entry.item.availabilityLabel)
                    .filter(String::isNotBlank).forEach { label ->
                        Text(label, style = MediaType.secondary, color = colors.secondary,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
            }
            if (onOptions != null) MediaIconAction("${entry.item.title} · 更多", {
                focus?.onOpen?.invoke(entry.id, "continue"); onOptions()
            }, Art.drawable.fileway_ic_more_horiz, enabled = !entry.removing)
        }
    }
}
