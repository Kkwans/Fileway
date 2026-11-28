package io.github.kkwans.fileway.tv.medialibrary

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import coil3.ImageLoader
import io.github.kkwans.nasfilebrowser.data.MediaCatalogPerson
import io.github.kkwans.fileway.tv.LocalTvInteractive
import io.github.kkwans.fileway.tv.TvField
import io.github.kkwans.fileway.design.R as Art

internal enum class TvMediaSearchMode { WORKS, PEOPLE, FILES, COLLECTIONS }

@Composable
internal fun TvMediaSearchScreen(query: String, searched: Boolean, mode: TvMediaSearchMode,
    entries: List<TvMediaCard>, loading: Boolean, error: String?, hasMore: Boolean,
    people: List<MediaCatalogPerson>, peopleLoading: Boolean, peopleError: String?, peopleHasMore: Boolean,
    files: List<TvMediaFileResult>, filesLoading: Boolean, filesError: String?, filesHasMore: Boolean,
    collections: List<TvMediaCollectionResult>, collectionsLoading: Boolean, collectionsError: String?, collectionsHasMore: Boolean,
    canSearchFiles: Boolean, canSearchCollections: Boolean, imageLoader: ImageLoader,
    personPortrait: (MediaCatalogPerson) -> TvMediaArtwork?, onQuery: (String) -> Unit, onSubmit: () -> Unit,
    onMode: (TvMediaSearchMode) -> Unit, onBack: () -> Unit, onItem: (String) -> Unit,
    onPerson: (String, String) -> Unit, onCollection: (String, String) -> Unit, onRetry: () -> Unit, onMore: () -> Unit) {
    val focus = remember { FocusRequester() }
    val interactive = LocalTvInteractive.current
    val routeFocus = LocalTvMediaFocusBinding.current
    val entryTarget = remember {
        resolveTvSearchEntryFocusTarget(routeFocus?.let {
            it.targetId?.let { id -> it.targetOrigin?.let { origin -> TvMediaFocusTarget(id, origin) } }
        })
    }
    val busy = when (mode) {
        TvMediaSearchMode.WORKS -> loading
        TvMediaSearchMode.PEOPLE -> peopleLoading
        TvMediaSearchMode.FILES -> filesLoading
        TvMediaSearchMode.COLLECTIONS -> collectionsLoading
    }
    val issue = when (mode) {
        TvMediaSearchMode.WORKS -> error
        TvMediaSearchMode.PEOPLE -> peopleError
        TvMediaSearchMode.FILES -> filesError
        TvMediaSearchMode.COLLECTIONS -> collectionsError
    }
    val more = when (mode) {
        TvMediaSearchMode.WORKS -> hasMore
        TvMediaSearchMode.PEOPLE -> peopleHasMore
        TvMediaSearchMode.FILES -> filesHasMore
        TvMediaSearchMode.COLLECTIONS -> collectionsHasMore
    }
    val empty = when (mode) {
        TvMediaSearchMode.WORKS -> entries.isEmpty()
        TvMediaSearchMode.PEOPLE -> people.isEmpty()
        TvMediaSearchMode.FILES -> files.isEmpty()
        TvMediaSearchMode.COLLECTIONS -> collections.isEmpty()
    }
    LaunchedEffect(interactive, entryTarget) {
        if (interactive && entryTarget.id == "search-field" && entryTarget.origin == "search") {
            androidx.compose.runtime.withFrameNanos { }
            runCatching { focus.requestFocus() }
        }
    }
    Column(Modifier.fillMaxSize().background(LocalMediaColors.current.canvas), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = MediaLayout.pageInset, end = MediaLayout.pageInset, top = 28.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            MediaIconAction("返回", onBack, Art.drawable.fileway_ic_arrow_back)
            TvField(when (mode) {
                TvMediaSearchMode.WORKS -> "作品标题或原名"
                TvMediaSearchMode.PEOPLE -> "演员、导演或编剧姓名"
                TvMediaSearchMode.FILES -> "媒体文件名"
                TvMediaSearchMode.COLLECTIONS -> "合集名称"
            }, query, onQuery, Modifier.weight(1f).focusGroup().focusRequester(focus).tvMediaReturnFocus("search-field", "search"),
                showLabel = false, options = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }))
            MediaAction("搜索", onSubmit, enabled = query.isNotBlank() && !busy)
        }
        val colors = LocalMediaColors.current
        Row(Modifier.padding(horizontal = MediaLayout.pageInset), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val modes = buildList {
                add(TvMediaSearchMode.WORKS to "作品")
                add(TvMediaSearchMode.PEOPLE to "人物")
                if (canSearchFiles) add(TvMediaSearchMode.FILES to "文件")
                if (canSearchCollections) add(TvMediaSearchMode.COLLECTIONS to "合集")
            }
            modes.forEach { (value, label) -> SearchModeTab(label, mode == value, { onMode(value) }, colors.accent, colors.secondary) }
        }
        if (mode == TvMediaSearchMode.WORKS) {
            TvMediaWorksGrid(
                cards = entries,
                imageLoader = imageLoader,
                loading = busy,
                error = issue,
                emptyMessage = if (searched) "没有找到相关影视，试试缩短片名或使用原名。" else null,
                hasMore = more,
                onItem = onItem,
                onRetry = onRetry,
                onMore = onMore,
                modifier = Modifier.weight(1f).fillMaxWidth().testTag("media-search-results"),
            )
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("media-search-results"),
                contentPadding = PaddingValues(bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                when {
                    issue != null && empty -> item(key = "issue") { MediaStatus(issue, onRetry) }
                    tvMediaSearchShowsEmpty(searched, empty, busy, issue, more) -> item(key = "empty") {
                        MediaStatus(when (mode) {
                            TvMediaSearchMode.PEOPLE -> "没有找到相关人物，试试使用姓名中的一部分。"
                            TvMediaSearchMode.FILES -> "没有找到相关文件，试试使用文件名中的一部分。"
                            TvMediaSearchMode.COLLECTIONS -> "没有找到相关合集，试试使用合集名称中的一部分。"
                        })
                    }
                    mode == TvMediaSearchMode.PEOPLE -> item(key = "people") {
                        TvMediaPeopleShelf(people, imageLoader, personPortrait, onPerson, peopleLoading, peopleError, peopleHasMore, onMore)
                    }
                    mode == TvMediaSearchMode.FILES -> item(key = "files") {
                        TvMediaFileShelf(files, imageLoader, onItem, filesLoading, filesError, filesHasMore, onMore)
                    }
                    mode == TvMediaSearchMode.COLLECTIONS -> item(key = "collections") {
                        TvMediaCollectionShelf(collections, imageLoader, onCollection, collectionsLoading, collectionsError, collectionsHasMore, onMore)
                    }
                    else -> Unit
                }
                if (issue != null && !empty) item(key = "issue-more") { MediaStatus(issue, onRetry, compact = true) }
                if (busy && empty) item(key = "loading") {
                    MediaLoadingStatus(when (mode) {
                        TvMediaSearchMode.PEOPLE -> "正在搜索人物"
                        TvMediaSearchMode.FILES -> "正在搜索文件"
                        TvMediaSearchMode.COLLECTIONS -> "正在搜索合集"
                    })
                }
                if (busy && !empty) item(key = "loading-more") { MediaLoadingStatus("正在加载更多…", compact = true) }
                if (more && !busy) item(key = "more") {
                    MediaAction("加载更多", onMore, modifier = Modifier.padding(horizontal = MediaLayout.pageInset))
                }
            }
        }
    }
}

@Composable
internal fun TvMediaWorksPage(
    header: @Composable () -> Unit,
    cards: List<TvMediaCard>,
    imageLoader: ImageLoader,
    loading: Boolean,
    error: String?,
    emptyMessage: String,
    hasMore: Boolean,
    onItem: (String) -> Unit,
    onRetry: () -> Unit,
    onMore: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        header()
        TvMediaWorksGrid(
            cards = cards,
            imageLoader = imageLoader,
            loading = loading,
            error = error,
            emptyMessage = emptyMessage,
            hasMore = hasMore,
            onItem = onItem,
            onRetry = onRetry,
            onMore = onMore,
            moreLabel = "加载更多作品",
            modifier = Modifier.weight(1f).fillMaxWidth().testTag("media-works-results"),
        )
    }
}

@Composable
private fun TvMediaWorksGrid(
    cards: List<TvMediaCard>,
    imageLoader: ImageLoader,
    loading: Boolean,
    error: String?,
    emptyMessage: String?,
    hasMore: Boolean,
    onItem: (String) -> Unit,
    onRetry: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    moreLabel: String = "加载更多",
) {
    val gridState = rememberLazyGridState()
    val currentOnMore by rememberUpdatedState(onMore)
    LaunchedEffect(gridState, hasMore, loading, error, cards.isEmpty()) {
        if (!hasMore || loading || error != null) return@LaunchedEffect
        if (cards.isEmpty() && tvMediaShouldLoadEmptyPage(hasMore, loading, error)) {
            currentOnMore()
            return@LaunchedEffect
        }
        snapshotFlow {
            (gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) to gridState.layoutInfo.totalItemsCount
        }.collect { (lastVisible, itemCount) ->
            if (tvMediaShouldLoadMore(lastVisible, itemCount, hasMore, loading, error)) currentOnMore()
        }
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 160.dp),
        state = gridState,
        modifier = modifier,
        contentPadding = PaddingValues(start = MediaLayout.pageInset, end = MediaLayout.pageInset, bottom = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(MediaLayout.itemGap),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        if (loading) item(key = "loading", span = { GridItemSpan(maxLineSpan) }) { MediaLoadingStatus("正在加载…") }
        if (error != null) item(key = "error", span = { GridItemSpan(maxLineSpan) }) { MediaStatus(error, onRetry) }
        if (!loading && error == null && !hasMore && cards.isEmpty() && emptyMessage != null) {
            item(key = "empty", span = { GridItemSpan(maxLineSpan) }) { MediaStatus(emptyMessage) }
        }
        items(cards, key = { it.id }) { card ->
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                MediaPoster(card, imageLoader, { onItem(card.id) }, width = maxWidth)
            }
        }
        if (hasMore && !loading) item(key = "more", span = { GridItemSpan(maxLineSpan) }) {
            MediaAction(moreLabel, onMore, modifier = Modifier.padding(horizontal = MediaLayout.pageInset))
        }
    }
}

@Composable
private fun SearchModeTab(label: String, active: Boolean, onClick: () -> Unit, accent: Color, secondary: Color) {
    Column {
        MediaSurface(onClick, Modifier.heightIn(min = 48.dp), selected = active,
            container = Color.Transparent, selectionContainer = false,
            contentColor = if (active) accent else secondary) {
            Text(label, Modifier.padding(horizontal = 12.dp, vertical = 12.dp), style = MediaType.action)
        }
        Box(Modifier.align(Alignment.CenterHorizontally).width(if (active) 24.dp else 0.dp).height(2.dp)
            .background(if (active) accent else Color.Transparent))
    }
}
