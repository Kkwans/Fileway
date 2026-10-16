package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.staggeredgrid.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.kkwans.nasfilebrowser.app.FileLayout
import io.github.kkwans.nasfilebrowser.data.CompactGridSize
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayTheme

internal fun FileLayout.collectionDescription() = when (this) {
    FileLayout.COVER -> "文件网格"
    FileLayout.COMPACT -> "紧凑文件网格"
    FileLayout.UNBOUNDED -> "无界文件网格"
    FileLayout.LIST -> "文件列表"
    FileLayout.DETAIL -> "大图文件列表"
}

private val anchorSaver = listSaver<CollectionAnchor, Any>(
    save = { listOf(it.key, it.offset) + it.neighbours },
    restore = { CollectionAnchor(it[0] as String, it[1] as Int, it.drop(2).map { value -> value as String }) },
)

/** Each scope/layout/tier retains an object anchor, including when its composition is removed. */
@Composable internal fun <T> FileCollection(entries: List<T>, layout: FileLayout, modifier: Modifier,
    description: String, resetKey: Any?, keyOf: (T) -> String,
    header: @Composable (() -> Unit)? = null, compactGridSize: CompactGridSize = CompactGridSize.MEDIUM,
    entry: @Composable (T) -> Unit) {
    val holder = rememberSaveableStateHolder()
    // Caller supplies account scope, wire directory/query and filter; length framing prevents collisions.
    val scope = resetKey.toString()
    val tier = if (layout == FileLayout.COMPACT) compactGridSize.name else ""
    val stateKey = "${scope.length}:$scope:${layout.name}:$tier"
    val previewAspects = remember(stateKey) { mutableMapOf<String, Float>() }
    CompositionLocalProvider(LocalNaturalPreviewAspects provides previewAspects) {
    holder.SaveableStateProvider(stateKey) {
        val keys = (if (header != null) listOf("collection-header") else emptyList()) + entries.map { "entry:${keyOf(it)}" }
        var anchor by rememberSaveable(stateSaver = anchorSaver) {
            mutableStateOf(CollectionAnchor(keys.firstOrNull().orEmpty(), 0, emptyList()))
        }
        val initial = remember { anchor.resolve(keys) }
        val list = rememberLazyListState(initial.first, initial.second)
        val grid = rememberLazyGridState(initial.first, initial.second)
        val waterfall = rememberLazyStaggeredGridState(initial.first, initial.second)
        var restoring by remember { mutableStateOf(true) }
        var initialized by remember { mutableStateOf(false) }
        // Reordering is keyed by the lazy container. Removal resolves to a neighbouring object.
        LaunchedEffect(keys) {
            restoring = true
            if (keys.isNotEmpty() && (!initialized || anchor.key !in keys)) {
                val target = anchor.resolve(keys)
                when (layout) {
                    FileLayout.LIST, FileLayout.DETAIL -> list.scrollToItem(target.first, target.second)
                    FileLayout.UNBOUNDED -> waterfall.scrollToItem(target.first, target.second)
                    else -> grid.scrollToItem(target.first, target.second)
                }
            }
            initialized = true
            restoring = false
        }
        LaunchedEffect(layout, keys) {
            snapshotFlow {
                if (restoring) null else when (layout) {
                    FileLayout.LIST, FileLayout.DETAIL -> list.layoutInfo.visibleItemsInfo.firstOrNull()?.let {
                        it.key.toString() to list.firstVisibleItemScrollOffset
                    }
                    FileLayout.UNBOUNDED -> waterfall.layoutInfo.visibleItemsInfo.minByOrNull { it.index }?.let {
                        it.key.toString() to waterfall.firstVisibleItemScrollOffset
                    }
                    else -> grid.layoutInfo.visibleItemsInfo.firstOrNull()?.let {
                        it.key.toString() to grid.firstVisibleItemScrollOffset
                    }
                }
            }.collect { visible ->
                if (visible != null && visible.first in keys) anchor = CollectionAnchor.capture(keys, visible.first, visible.second)
            }
        }
        val area = modifier.background(FilewayTheme.colors.background).semantics { contentDescription = description }
        val padding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)
        when (layout) {
            FileLayout.UNBOUNDED -> BoxWithConstraints(area) {
                LazyVerticalStaggeredGrid(if (maxWidth < 600.dp) StaggeredGridCells.Fixed(2) else StaggeredGridCells.Adaptive(180.dp),
                    Modifier.fillMaxSize(), state = waterfall, contentPadding = padding,
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalItemSpacing = 12.dp) {
                    if (header != null) item(key = "collection-header", span = StaggeredGridItemSpan.FullLine) { header() }
                    items(entries, key = { "entry:${keyOf(it)}" }) { entry(it) }
                }
            }
            FileLayout.COVER, FileLayout.COMPACT -> BoxWithConstraints(area) {
                val columns = if (maxWidth < 600.dp) GridCells.Fixed(
                    if (layout == FileLayout.COVER) 2 else compactGridSize.columns
                ) else GridCells.Adaptive(if (layout == FileLayout.COVER) 180.dp else when (compactGridSize) {
                    CompactGridSize.SMALL -> 88.dp; CompactGridSize.MEDIUM -> 112.dp; CompactGridSize.LARGE -> 164.dp
                })
                LazyVerticalGrid(columns, Modifier.fillMaxSize(), state = grid, contentPadding = padding,
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (header != null) item(key = "collection-header", span = { GridItemSpan(maxLineSpan) }) { header() }
                    items(entries, key = { "entry:${keyOf(it)}" }) { entry(it) }
                }
            }
            FileLayout.LIST, FileLayout.DETAIL -> LazyColumn(area, state = list, contentPadding = padding,
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (header != null) item(key = "collection-header") { header() }
                items(entries, key = { "entry:${keyOf(it)}" }) { entry(it) }
            }
        }
    }
}
}
