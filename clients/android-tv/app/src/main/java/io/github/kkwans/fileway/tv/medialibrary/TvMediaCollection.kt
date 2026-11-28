package io.github.kkwans.fileway.tv.medialibrary

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import coil3.ImageLoader
import io.github.kkwans.fileway.design.R as Art

internal data class TvMediaCollectionResult(val libraryId: String, val collectionId: String, val name: String, val preview: TvMediaCard?) {
    val id: String get() = collectionId
}

@Composable
internal fun TvMediaCollectionShelf(collections: List<TvMediaCollectionResult>, imageLoader: ImageLoader,
    onCollection: (String, String) -> Unit, loading: Boolean, error: String?, hasMore: Boolean, onMore: (() -> Unit)?) {
    if (collections.isEmpty() && !hasMore) return
    val listState = rememberLazyListState()
    TvMediaRowLoadMore(listState, hasMore, loading, error, onMore, empty = collections.isEmpty())
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        MediaSectionTitle("合集")
        LazyRow(state = listState, modifier = Modifier.focusGroup(), contentPadding = MediaLayout.rowPadding,
            horizontalArrangement = Arrangement.spacedBy(MediaLayout.itemGap)) {
            items(collections, key = { it.id }) { collection ->
                val focusId = "collection:${collection.id}"
                val focusBinding = LocalTvMediaFocusBinding.current
                MediaSurface({
                    focusBinding?.onOpen?.invoke(focusId, "collection-result")
                    onCollection(collection.libraryId, collection.collectionId)
                }, Modifier.width(360.dp).heightIn(min = 128.dp).tvMediaReturnFocus(focusId, "collection-result")) {
                    Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        MediaArtwork(collection.preview?.backdrop, imageLoader, Modifier.width(128.dp).height(72.dp),
                            fallbackTitle = collection.name)
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(collection.name, style = MediaType.itemTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(if (collection.preview != null) "可见作品预览" else "暂无可见作品预览",
                                style = MediaType.secondary, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun TvMediaCollectionWorksHeader(name: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = MediaLayout.pageInset, end = MediaLayout.pageInset, top = 28.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        MediaIconAction("返回合集搜索", onBack, Art.drawable.fileway_ic_arrow_back,
            modifier = Modifier.tvMediaReturnFocus("collection-back", "search-collection"))
        Text(name, style = MediaType.pageTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
