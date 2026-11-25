package io.github.kkwans.nasfilebrowser.ui.medialibrary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import io.github.kkwans.nasfilebrowser.data.MediaCatalogCollection
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayEmptyState
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewaySpace
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayTopAppBar
import io.github.kkwans.fileway.design.R as Art

@Composable
internal fun PhoneMediaCollectionResult(collection: MediaCatalogCollection, imageLoader: ImageLoader, onOpen: () -> Unit) {
    Card(onClick = onOpen, shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.fillMaxWidth().padding(FilewaySpace.content), horizontalArrangement = Arrangement.spacedBy(FilewaySpace.content),
            verticalAlignment = Alignment.CenterVertically) {
            collection.preview.first().backdrop?.let { artwork ->
                MediaImage(MediaArtwork(catalog = artwork), imageLoader, Modifier.width(96.dp).aspectRatio(16f / 9f),
                    fallbackTitle = collection.preview.first().title)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(collection.name, style = PhoneMediaType.itemTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("可见作品预览", style = PhoneMediaType.secondary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun PhoneMediaCollectionWorks(name: String, entries: List<MediaPoster>, loading: Boolean,
    problem: MediaProblem?, hasMore: Boolean, imageLoader: ImageLoader, onBack: () -> Unit,
    onAction: (PhoneMediaAction) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val grid = phoneMediaGridSpec(maxWidth)
        Column(Modifier.fillMaxSize()) {
            FilewayTopAppBar("合集作品", leadingIcon = Art.drawable.fileway_ic_arrow_back,
                leadingDescription = "返回合集搜索", onLeadingClick = onBack)
            Text(name, Modifier.fillMaxWidth().padding(horizontal = grid.pageInset), style = PhoneMediaType.pageTitle)
            LazyVerticalGrid(columns = GridCells.Fixed(grid.columns), modifier = Modifier.weight(1f).fillMaxSize(),
                contentPadding = PaddingValues(start = grid.pageInset, end = grid.pageInset, top = grid.pageInset, bottom = FilewaySpace.section),
                horizontalArrangement = Arrangement.spacedBy(FilewaySpace.content), verticalArrangement = Arrangement.spacedBy(FilewaySpace.page)) {
                if (loading) item(key = "loading", span = { GridItemSpan(maxLineSpan) }) { MediaLoading("正在加载合集作品") }
                problem?.let { issue -> item(key = "problem", span = { GridItemSpan(maxLineSpan) }) {
                    MediaProblemCard(issue, { onAction(PhoneMediaAction.Retry("collection-works")) })
                } }
                if (entries.isEmpty() && !loading && problem == null) item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                    FilewayEmptyState(Art.drawable.fileway_ic_library, "暂无可见作品", "该合集可能没有当前账号可访问的影视。")
                }
                items(entries, key = { "collection-works/${it.id}" }) { entry ->
                    MediaPosterCard(entry, imageLoader) { onAction(PhoneMediaAction.OpenItem(entry.id)) }
                }
                if (hasMore && !loading) item(key = "more", span = { GridItemSpan(maxLineSpan) }) {
                    MediaPaging("collection-works", false, true, onAction)
                }
            }
        }
    }
}
