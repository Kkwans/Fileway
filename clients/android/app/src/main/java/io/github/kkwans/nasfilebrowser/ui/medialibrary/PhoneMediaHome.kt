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

/** Embedded tab: parent owns status/navigation-bar insets; do not apply them twice. */
@Composable internal fun PhoneMediaHome(state: PhoneMediaHomeState, imageLoader: ImageLoader,
    onAction: (PhoneMediaAction) -> Unit, modifier: Modifier = Modifier,
    gridState: LazyGridState = rememberLazyGridState(), onDrawer: (() -> Unit)? = null) {
    key(state.scopeKey) {
        var continueMenuOpen by remember { mutableStateOf(false) }
        BoxWithConstraints(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            val columns = when { maxWidth >= 840.dp -> 4; maxWidth >= 600.dp -> 3; else -> 2 }
            LazyVerticalGrid(columns = GridCells.Fixed(columns), state = gridState,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item(key = "heading", span = { GridItemSpan(maxLineSpan) }) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (onDrawer != null) MediaIconAction(Art.drawable.fileway_ic_menu, "打开导航菜单", onDrawer)
                        Text("影视", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.headlineMedium)
                        if (state.canManage) MediaIconAction(R.drawable.ic_settings, "管理媒体库",
                            { onAction(PhoneMediaAction.ManageLibraries) })
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
                if (state.featured.entries.isNotEmpty()) item(key = "featured", span = { GridItemSpan(maxLineSpan) }) {
                    FeaturedCarousel(state, imageLoader, gridState.isScrollInProgress || continueMenuOpen, onAction)
                }
                state.featured.problem?.let { problem -> item(key = "featured-error", span = { GridItemSpan(maxLineSpan) }) {
                    MediaProblemCard(problem, { onAction(PhoneMediaAction.Retry("featured")) })
                } }
                if (state.featured.loading && state.featured.entries.isEmpty()) item(key = "featured-loading", span = { GridItemSpan(maxLineSpan) }) {
                    MediaLoading("正在加载新入库影视")
                }
                if (state.continuing.entries.isNotEmpty() || state.continuing.loading || state.continuing.problem != null) {
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
                        }
                    }
                }
                if (state.categories.isNotEmpty()) item(key = "categories", span = { GridItemSpan(maxLineSpan) }) {
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
                            Text(state.catalogTitle, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                        }
                        state.filterSummary?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
                state.catalog.problem?.let { problem -> item(key = "catalog-error", span = { GridItemSpan(maxLineSpan) }) {
                    MediaProblemCard(problem, { onAction(PhoneMediaAction.Retry("catalog")) })
                } }
                if (state.libraryExists && state.catalog.entries.isEmpty() && !state.catalog.loading && state.catalog.problem == null && state.problem == null) {
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("新入库", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
            if (entries.size > 1) TextButton(onClick = {
                onAction(PhoneMediaAction.SetCarouselAutoAdvance(!state.carouselAutoAdvance))
            }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (state.carouselAutoAdvance) "暂停轮播" else "自动轮播") }
        }
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
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                    .clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainer)) {
                    if (entry.backdrop != null) {
                        MediaImage(entry.backdrop, loader, Modifier.fillMaxSize())
                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .85f)))))
                    } else if (entry.poster != null) {
                        MediaImage(entry.poster, loader, Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                            .aspectRatio(2f / 3f).padding(12.dp))
                        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(
                            listOf(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.colorScheme.surfaceContainer.copy(alpha = .85f), Color.Transparent))))
                    }
                    val titleColor = if (entry.backdrop != null) Color.White else MaterialTheme.colorScheme.onSurface
                    val subtitleColor = if (entry.backdrop != null) Color.White.copy(alpha = .85f) else MaterialTheme.colorScheme.onSurfaceVariant
                    Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                        Text(entry.title, color = titleColor, style = MaterialTheme.typography.headlineSmall,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(entry.subtitle, color = subtitleColor, style = MaterialTheme.typography.bodySmall,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        if (entries.size > 1) Text("${pager.currentPage + 1} / ${entries.size}",
            Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            shape = MaterialTheme.shapes.medium) {
            MediaImage(entry.item.backdrop, loader, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            MediaProgress(entry.progress)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(top = 8.dp)) {
                Text(entry.item.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(entry.progressLabel, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                entry.item.availability?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
            }
            Box {
                MediaIconAction(R.drawable.ic_more_vert, "${entry.item.title}的更多操作", { setMenu(true) })
                DropdownMenu(menu, { setMenu(false) }) {
                    FilewayMenuItem("查看详情", { setMenu(false); onAction(PhoneMediaAction.OpenItem(entry.item.id)) })
                    FilewayMenuItem("从头播放", { setMenu(false); onAction(PhoneMediaAction.Play(entry.id, true, MediaTargetKind.CONTINUE)) }, enabled = entry.playable)
                    FilewayMenuItem("移出继续观看", { setMenu(false); onAction(PhoneMediaAction.RemoveContinue(entry.id)) })
                }
            }
        }
    }
}
