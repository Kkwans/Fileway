package io.github.kkwans.nasfilebrowser.ui.medialibrary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.data.MediaCatalogPerson
import io.github.kkwans.nasfilebrowser.ui.FilewayIcon
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayEmptyState
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewaySpace
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayTopAppBar
import io.github.kkwans.fileway.design.R as Art

@Composable
internal fun PhoneMediaPersonResult(person: MediaCatalogPerson, onOpen: () -> Unit) {
    Card(onClick = onOpen, shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.fillMaxWidth().padding(FilewaySpace.content), horizontalArrangement = Arrangement.spacedBy(FilewaySpace.content),
            verticalAlignment = Alignment.CenterVertically) {
            Surface(Modifier.size(56.dp), shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                Box(contentAlignment = Alignment.Center) {
                    FilewayIcon(R.drawable.ic_person, null, Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(person.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(person.roles.joinToString(" · ").ifBlank { "演职人员" }, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                person.preview.firstOrNull()?.let { preview ->
                    Text("作品：${preview.title.ifBlank { "未命名影视" }}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
internal fun PhoneMediaPersonWorks(name: String, roles: List<String>, entries: List<MediaPoster>,
    loading: Boolean, problem: MediaProblem?, hasMore: Boolean, imageLoader: ImageLoader,
    onBack: () -> Unit, onAction: (PhoneMediaAction) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        FilewayTopAppBar("人物作品", leadingIcon = Art.drawable.fileway_ic_arrow_back,
            leadingDescription = "返回人物搜索", onLeadingClick = onBack)
        Column(Modifier.fillMaxWidth().padding(horizontal = FilewaySpace.page), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(name, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(roles.joinToString(" · ").ifBlank { "演职人员" }, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val columns = when { maxWidth >= 840.dp -> 4; maxWidth >= 600.dp -> 3; else -> 2 }
            LazyVerticalGrid(columns = GridCells.Fixed(columns), modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = FilewaySpace.page, end = FilewaySpace.page, top = FilewaySpace.page, bottom = FilewaySpace.section),
                horizontalArrangement = Arrangement.spacedBy(FilewaySpace.content), verticalArrangement = Arrangement.spacedBy(FilewaySpace.page)) {
                if (loading) item(key = "loading", span = { GridItemSpan(maxLineSpan) }) { MediaLoading("正在加载人物作品") }
                problem?.let { issue -> item(key = "problem", span = { GridItemSpan(maxLineSpan) }) {
                    MediaProblemCard(issue, { onAction(PhoneMediaAction.Retry("person-works")) })
                } }
                if (entries.isEmpty() && !loading && problem == null) item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                    FilewayEmptyState(R.drawable.ic_person, "暂无可见作品", "该人物可能没有当前账号可访问的影视。")
                }
                items(entries, key = { "person-works/${it.id}" }) { entry ->
                    MediaPosterCard(entry, imageLoader) { onAction(PhoneMediaAction.OpenItem(entry.id)) }
                }
                if (hasMore && !loading) item(key = "more", span = { GridItemSpan(maxLineSpan) }) {
                    MediaPaging("person-works", false, true, onAction)
                }
            }
        }
    }
}
