package io.github.kkwans.fileway.tv.medialibrary

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import coil3.ImageLoader
import io.github.kkwans.nasfilebrowser.data.MediaCatalogPerson
import io.github.kkwans.fileway.design.R as Art

@Composable
internal fun TvMediaPeopleShelf(people: List<MediaCatalogPerson>, imageLoader: ImageLoader,
    portrait: (MediaCatalogPerson) -> TvMediaArtwork?, onPerson: (String, String) -> Unit,
    loading: Boolean, error: String?, hasMore: Boolean, onMore: (() -> Unit)?) {
    if (people.isEmpty() && !hasMore) return
    val listState = rememberLazyListState()
    TvMediaRowLoadMore(listState, hasMore, loading, error, onMore, empty = people.isEmpty())
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        MediaSectionTitle("人物")
        LazyRow(state = listState, modifier = Modifier.focusGroup(), contentPadding = MediaLayout.rowPadding,
            horizontalArrangement = Arrangement.spacedBy(MediaLayout.itemGap)) {
            items(people, key = { "${it.ref.libraryId}/${it.ref.personId}" }) { person ->
                val id = "person:${person.ref.libraryId}/${person.ref.personId}"
                val focusBinding = LocalTvMediaFocusBinding.current
                MediaSurface({
                    focusBinding?.onOpen?.invoke(id, "person-result")
                    onPerson(person.ref.libraryId, person.ref.personId)
                },
                    Modifier.width(320.dp).heightIn(min = 112.dp).tvMediaReturnFocus(id, "person-result")) {
                    Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        MediaArtwork(portrait(person), imageLoader, Modifier.size(56.dp).clip(CircleShape), Art.drawable.fileway_ic_person)
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(person.name, style = MediaType.itemTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(person.roles.joinToString(" · ").ifBlank { "演职人员" }, style = MediaType.secondary,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            tvMediaPersonPreviewTitle(person.preview)?.let { title ->
                                Text("作品：$title", style = MediaType.secondary,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun TvMediaPersonWorksHeader(name: String, roles: List<String>, portrait: TvMediaArtwork?, imageLoader: ImageLoader, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = MediaLayout.pageInset, end = MediaLayout.pageInset, top = 28.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        MediaIconAction("返回人物搜索", onBack, Art.drawable.fileway_ic_arrow_back,
            modifier = Modifier.tvMediaReturnFocus("person-back", "search-person"))
        MediaArtwork(portrait, imageLoader, Modifier.size(72.dp).clip(CircleShape), Art.drawable.fileway_ic_person)
        Column {
            Text(name, style = MediaType.pageTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(roles.joinToString(" · ").ifBlank { "演职人员" }, style = MediaType.secondary)
        }
    }
}
