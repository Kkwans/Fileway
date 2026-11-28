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

internal data class TvMediaFileResult(val id: String, val fileName: String, val card: TvMediaCard)

@Composable
internal fun TvMediaFileShelf(files: List<TvMediaFileResult>, imageLoader: ImageLoader, onItem: (String) -> Unit,
    loading: Boolean, error: String?, hasMore: Boolean, onMore: (() -> Unit)?) {
    if (files.isEmpty() && !hasMore) return
    val listState = rememberLazyListState()
    TvMediaRowLoadMore(listState, hasMore, loading, error, onMore, empty = files.isEmpty())
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        MediaSectionTitle("文件")
        LazyRow(state = listState, modifier = Modifier.focusGroup(), contentPadding = MediaLayout.rowPadding,
            horizontalArrangement = Arrangement.spacedBy(MediaLayout.itemGap)) {
            items(files, key = { it.id }) { file ->
                val focusId = "file:${file.id}"
                val focusBinding = LocalTvMediaFocusBinding.current
                MediaSurface({
                    focusBinding?.onOpen?.invoke(focusId, "file-result")
                    onItem(file.card.id)
                }, Modifier.width(440.dp).heightIn(min = 132.dp).tvMediaReturnFocus(focusId, "file-result")) {
                    Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        MediaArtwork(file.card.backdrop, imageLoader, Modifier.width(120.dp).height(72.dp),
                            fallbackTitle = file.card.title)
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(file.fileName, style = MediaType.itemTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(file.card.title, style = MediaType.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}
