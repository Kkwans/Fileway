package io.github.kkwans.nasfilebrowser.ui.medialibrary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import io.github.kkwans.nasfilebrowser.data.MediaCatalogCollection
import io.github.kkwans.nasfilebrowser.data.MediaCatalogFileMatch
import io.github.kkwans.nasfilebrowser.data.MediaCatalogPerson
import io.github.kkwans.nasfilebrowser.ui.FilewayIcon
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayEmptyState
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewaySpace
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayTopAppBar
import io.github.kkwans.fileway.design.R as Art

internal fun phoneMediaSearchShowsEmpty(
    searched: Boolean,
    empty: Boolean,
    busy: Boolean,
    hasProblem: Boolean,
    hasMore: Boolean,
): Boolean = searched && empty && !busy && !hasProblem && !hasMore

@Composable
internal fun PhoneMediaSearch(query: String, searched: Boolean, mode: MediaSearchMode,
    entries: List<MediaPoster>, loading: Boolean, problem: MediaProblem?, hasMore: Boolean,
    people: List<MediaCatalogPerson>, peopleLoading: Boolean, peopleProblem: MediaProblem?, peopleHasMore: Boolean,
    files: List<MediaCatalogFileMatch>, filesLoading: Boolean, filesProblem: MediaProblem?, filesHasMore: Boolean,
    collections: List<MediaCatalogCollection>, collectionsLoading: Boolean, collectionsProblem: MediaProblem?, collectionsHasMore: Boolean,
    canSearchFiles: Boolean, canSearchCollections: Boolean, imageLoader: ImageLoader, onAction: (PhoneMediaAction) -> Unit) {
    val focus = LocalFocusManager.current
    val busy = when (mode) {
        MediaSearchMode.WORKS -> loading
        MediaSearchMode.PEOPLE -> peopleLoading
        MediaSearchMode.FILES -> filesLoading
        MediaSearchMode.COLLECTIONS -> collectionsLoading
    }
    val issue = when (mode) {
        MediaSearchMode.WORKS -> problem
        MediaSearchMode.PEOPLE -> peopleProblem
        MediaSearchMode.FILES -> filesProblem
        MediaSearchMode.COLLECTIONS -> collectionsProblem
    }
    val more = when (mode) {
        MediaSearchMode.WORKS -> hasMore
        MediaSearchMode.PEOPLE -> peopleHasMore
        MediaSearchMode.FILES -> filesHasMore
        MediaSearchMode.COLLECTIONS -> collectionsHasMore
    }
    val empty = when (mode) {
        MediaSearchMode.WORKS -> entries.isEmpty()
        MediaSearchMode.PEOPLE -> people.isEmpty()
        MediaSearchMode.FILES -> files.isEmpty()
        MediaSearchMode.COLLECTIONS -> collections.isEmpty()
    }
    fun submit() {
        if (query.isNotBlank() && !busy) {
            focus.clearFocus(); onAction(PhoneMediaAction.SubmitSearch(query))
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val pageInset = phoneMediaGridSpec(maxWidth).pageInset
        Column(Modifier.fillMaxSize()) {
            FilewayTopAppBar("搜索影视", leadingIcon = Art.drawable.fileway_ic_arrow_back,
                leadingDescription = "返回", onLeadingClick = { onAction(PhoneMediaAction.CloseSearch) })
            OutlinedTextField(query, { onAction(PhoneMediaAction.UpdateSearchQuery(it)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = pageInset, vertical = 8.dp),
                label = { Text(when (mode) {
                    MediaSearchMode.WORKS -> "作品标题或原名"
                    MediaSearchMode.PEOPLE -> "演员、导演或编剧姓名"
                    MediaSearchMode.FILES -> "媒体文件名"
                    MediaSearchMode.COLLECTIONS -> "合集名称"
                }) }, singleLine = true, shape = MaterialTheme.shapes.medium,
                trailingIcon = if (query.isEmpty()) null else ({
                    IconButton(onClick = { onAction(PhoneMediaAction.UpdateSearchQuery("")) }) {
                        FilewayIcon(Art.drawable.fileway_ic_close, "清空搜索")
                    }
                }),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { submit() }))
            LazyRow(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = pageInset),
                horizontalArrangement = Arrangement.spacedBy(FilewaySpace.small)) {
                item {
                    FilterChip(mode == MediaSearchMode.WORKS, { onAction(PhoneMediaAction.SearchMode(MediaSearchMode.WORKS)) },
                        modifier = Modifier.heightIn(min = 48.dp), label = { Text("作品", style = PhoneMediaType.action) })
                }
                item {
                    FilterChip(mode == MediaSearchMode.PEOPLE, { onAction(PhoneMediaAction.SearchMode(MediaSearchMode.PEOPLE)) },
                        modifier = Modifier.heightIn(min = 48.dp), label = { Text("人物", style = PhoneMediaType.action) })
                }
                if (canSearchFiles) item {
                    FilterChip(mode == MediaSearchMode.FILES, { onAction(PhoneMediaAction.SearchMode(MediaSearchMode.FILES)) },
                        modifier = Modifier.heightIn(min = 48.dp), label = { Text("文件", style = PhoneMediaType.action) })
                }
                if (canSearchCollections) item {
                    FilterChip(mode == MediaSearchMode.COLLECTIONS, { onAction(PhoneMediaAction.SearchMode(MediaSearchMode.COLLECTIONS)) },
                        modifier = Modifier.heightIn(min = 48.dp), label = { Text("合集", style = PhoneMediaType.action) })
                }
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val columns = phoneMediaGridSpec(maxWidth).columns
                LazyVerticalGrid(columns = GridCells.Fixed(columns), modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = pageInset, end = pageInset, top = FilewaySpace.small, bottom = FilewaySpace.section),
                    horizontalArrangement = Arrangement.spacedBy(FilewaySpace.content), verticalArrangement = Arrangement.spacedBy(FilewaySpace.page)) {
                    if (busy) item(key = "loading", span = { GridItemSpan(maxLineSpan) }) {
                        MediaLoading(when (mode) {
                            MediaSearchMode.WORKS -> "正在搜索影视"
                            MediaSearchMode.PEOPLE -> "正在搜索人物"
                            MediaSearchMode.FILES -> "正在搜索文件"
                            MediaSearchMode.COLLECTIONS -> "正在搜索合集"
                        })
                    }
                    issue?.let { item(key = "problem", span = { GridItemSpan(maxLineSpan) }) {
                        MediaProblemCard(it, { onAction(PhoneMediaAction.Retry("search")) })
                    } }
                    if (phoneMediaSearchShowsEmpty(searched, empty, busy, issue != null, more)) item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                        FilewayEmptyState(Art.drawable.fileway_ic_search, when (mode) {
                            MediaSearchMode.WORKS -> "没有找到相关影视"
                            MediaSearchMode.PEOPLE -> "没有找到相关人物"
                            MediaSearchMode.FILES -> "没有找到相关文件"
                            MediaSearchMode.COLLECTIONS -> "没有找到相关合集"
                        }, when (mode) {
                            MediaSearchMode.WORKS -> "试试缩短片名或使用原名。"
                            MediaSearchMode.PEOPLE -> "试试使用姓名中的一部分。"
                            MediaSearchMode.FILES -> "试试使用文件名中的一部分。"
                            MediaSearchMode.COLLECTIONS -> "试试使用合集名称中的一部分。"
                        })
                    }
                    if (searched && empty && !busy && issue == null && more) item(key = "partial", span = { GridItemSpan(maxLineSpan) }) {
                        Text("仍在查找，可继续加载更多结果", style = PhoneMediaType.secondary,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(vertical = FilewaySpace.content))
                    }
                    when (mode) {
                        MediaSearchMode.WORKS -> items(entries, key = { "search/${it.id}" }) { entry ->
                            MediaPosterCard(entry, imageLoader) { onAction(PhoneMediaAction.OpenItem(entry.id)) }
                        }
                        MediaSearchMode.PEOPLE -> items(people, key = { "people/${it.ref.libraryId}/${it.ref.personId}" },
                            span = { GridItemSpan(maxLineSpan) }) { person ->
                            PhoneMediaPersonResult(person, imageLoader) {
                                onAction(PhoneMediaAction.OpenCatalogPerson(person.ref.libraryId, person.ref.personId))
                            }
                        }
                        MediaSearchMode.FILES -> items(files, key = { "files/${it.fileId}" },
                            span = { GridItemSpan(maxLineSpan) }) { file ->
                            PhoneMediaFileResult(file.fileName, poster(file.card), imageLoader) {
                                onAction(PhoneMediaAction.OpenItem(key(file.card.ref)))
                            }
                        }
                        MediaSearchMode.COLLECTIONS -> items(collections, key = { "collections/${it.ref.libraryId}/${it.ref.collectionId}" },
                            span = { GridItemSpan(maxLineSpan) }) { collection ->
                            PhoneMediaCollectionResult(collection, imageLoader) {
                                onAction(PhoneMediaAction.OpenCollection(collection.ref.libraryId, collection.ref.collectionId))
                            }
                        }
                    }
                    if (more && !busy) item(key = "more", span = { GridItemSpan(maxLineSpan) }) {
                        MediaPaging("search", false, true, onAction)
                    }
                }
            }
        }
    }
}
