package io.github.kkwans.nasfilebrowser.ui.medialibrary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import io.github.kkwans.nasfilebrowser.data.MediaCatalogFileMatch
import io.github.kkwans.nasfilebrowser.data.MediaCatalogPerson
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayEmptyState
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewaySpace
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayTopAppBar
import io.github.kkwans.fileway.design.R as Art

@Composable
internal fun PhoneMediaSearch(query: String, searched: Boolean, mode: MediaSearchMode,
    entries: List<MediaPoster>, loading: Boolean, problem: MediaProblem?, hasMore: Boolean,
    people: List<MediaCatalogPerson>, peopleLoading: Boolean, peopleProblem: MediaProblem?, peopleHasMore: Boolean,
    files: List<MediaCatalogFileMatch>, filesLoading: Boolean, filesProblem: MediaProblem?, filesHasMore: Boolean,
    canSearchFiles: Boolean, imageLoader: ImageLoader, onAction: (PhoneMediaAction) -> Unit) {
    val focus = LocalFocusManager.current
    val busy = when (mode) {
        MediaSearchMode.WORKS -> loading
        MediaSearchMode.PEOPLE -> peopleLoading
        MediaSearchMode.FILES -> filesLoading
    }
    val issue = when (mode) {
        MediaSearchMode.WORKS -> problem
        MediaSearchMode.PEOPLE -> peopleProblem
        MediaSearchMode.FILES -> filesProblem
    }
    val more = when (mode) {
        MediaSearchMode.WORKS -> hasMore
        MediaSearchMode.PEOPLE -> peopleHasMore
        MediaSearchMode.FILES -> filesHasMore
    }
    val empty = when (mode) {
        MediaSearchMode.WORKS -> entries.isEmpty()
        MediaSearchMode.PEOPLE -> people.isEmpty()
        MediaSearchMode.FILES -> files.isEmpty()
    }
    fun submit() {
        if (query.isNotBlank() && !busy) {
            focus.clearFocus(); onAction(PhoneMediaAction.SubmitSearch(query))
        }
    }
    Column(Modifier.fillMaxSize()) {
        FilewayTopAppBar("搜索影视", leadingIcon = Art.drawable.fileway_ic_arrow_back,
            leadingDescription = "返回", onLeadingClick = { onAction(PhoneMediaAction.CloseSearch) })
        OutlinedTextField(query, { onAction(PhoneMediaAction.UpdateSearchQuery(it)) }, modifier = Modifier.fillMaxWidth().padding(horizontal = FilewaySpace.page),
            label = { Text(when (mode) {
                MediaSearchMode.WORKS -> "作品标题或原名"
                MediaSearchMode.PEOPLE -> "演员、导演或编剧姓名"
                MediaSearchMode.FILES -> "媒体文件名"
            }) }, singleLine = true, shape = MaterialTheme.shapes.medium,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { submit() }))
        Row(Modifier.fillMaxWidth().padding(horizontal = FilewaySpace.page), horizontalArrangement = Arrangement.spacedBy(FilewaySpace.content)) {
            FilterChip(mode == MediaSearchMode.WORKS, { onAction(PhoneMediaAction.SearchMode(MediaSearchMode.WORKS)) },
                modifier = Modifier.heightIn(min = 48.dp), label = { Text("作品") })
            FilterChip(mode == MediaSearchMode.PEOPLE, { onAction(PhoneMediaAction.SearchMode(MediaSearchMode.PEOPLE)) },
                modifier = Modifier.heightIn(min = 48.dp), label = { Text("人物") })
            if (canSearchFiles) FilterChip(mode == MediaSearchMode.FILES, { onAction(PhoneMediaAction.SearchMode(MediaSearchMode.FILES)) },
                modifier = Modifier.heightIn(min = 48.dp), label = { Text("文件") })
        }
        Button(onClick = ::submit, enabled = query.isNotBlank() && !busy,
            modifier = Modifier.fillMaxWidth().padding(horizontal = FilewaySpace.page).heightIn(min = 48.dp),
            shape = MaterialTheme.shapes.medium) { Text("搜索") }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val columns = when { maxWidth >= 840.dp -> 4; maxWidth >= 600.dp -> 3; else -> 2 }
            LazyVerticalGrid(columns = GridCells.Fixed(columns), modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = FilewaySpace.page, end = FilewaySpace.page, top = FilewaySpace.small, bottom = FilewaySpace.section),
                horizontalArrangement = Arrangement.spacedBy(FilewaySpace.content), verticalArrangement = Arrangement.spacedBy(FilewaySpace.page)) {
                if (busy) item(key = "loading", span = { GridItemSpan(maxLineSpan) }) {
                    MediaLoading(when (mode) {
                        MediaSearchMode.WORKS -> "正在搜索影视"
                        MediaSearchMode.PEOPLE -> "正在搜索人物"
                        MediaSearchMode.FILES -> "正在搜索文件"
                    })
                }
                issue?.let { item(key = "problem", span = { GridItemSpan(maxLineSpan) }) {
                    MediaProblemCard(it, { onAction(PhoneMediaAction.Retry("search")) })
                } }
                if (searched && empty && !busy && issue == null) item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                    FilewayEmptyState(Art.drawable.fileway_ic_search, when (mode) {
                        MediaSearchMode.WORKS -> "没有找到相关影视"
                        MediaSearchMode.PEOPLE -> "没有找到相关人物"
                        MediaSearchMode.FILES -> "没有找到相关文件"
                    }, when (mode) {
                        MediaSearchMode.WORKS -> "试试缩短片名或使用原名。"
                        MediaSearchMode.PEOPLE -> "试试使用姓名中的一部分。"
                        MediaSearchMode.FILES -> "试试使用文件名中的一部分。"
                    })
                }
                when (mode) {
                    MediaSearchMode.WORKS -> items(entries, key = { "search/${it.id}" }) { entry ->
                        MediaPosterCard(entry, imageLoader) { onAction(PhoneMediaAction.OpenItem(entry.id)) }
                    }
                    MediaSearchMode.PEOPLE -> items(people, key = { "people/${it.ref.libraryId}/${it.ref.personId}" },
                        span = { GridItemSpan(maxLineSpan) }) { person ->
                        PhoneMediaPersonResult(person) {
                            onAction(PhoneMediaAction.OpenCatalogPerson(person.ref.libraryId, person.ref.personId))
                        }
                    }
                    MediaSearchMode.FILES -> items(files, key = { "files/${it.fileId}" },
                        span = { GridItemSpan(maxLineSpan) }) { file ->
                        PhoneMediaFileResult(file.fileName, poster(file.card), imageLoader) {
                            onAction(PhoneMediaAction.OpenItem(key(file.card.ref)))
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
