package io.github.kkwans.fileway.tv.medialibrary

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import io.github.kkwans.nasfilebrowser.data.MediaCatalogPerson
import io.github.kkwans.fileway.tv.LocalTvInteractive
import io.github.kkwans.fileway.tv.TvField
import io.github.kkwans.fileway.design.R as Art

internal enum class TvMediaSearchMode { WORKS, PEOPLE, FILES }

@Composable
internal fun TvMediaSearchScreen(query: String, searched: Boolean, mode: TvMediaSearchMode,
    entries: List<TvMediaCard>, loading: Boolean, error: String?, hasMore: Boolean,
    people: List<MediaCatalogPerson>, peopleLoading: Boolean, peopleError: String?, peopleHasMore: Boolean,
    files: List<TvMediaFileResult>, filesLoading: Boolean, filesError: String?, filesHasMore: Boolean,
    canSearchFiles: Boolean, imageLoader: ImageLoader, onQuery: (String) -> Unit, onSubmit: () -> Unit,
    onMode: (TvMediaSearchMode) -> Unit, onBack: () -> Unit, onItem: (String) -> Unit,
    onPerson: (String, String) -> Unit, onRetry: () -> Unit, onMore: () -> Unit) {
    val focus = remember { FocusRequester() }
    val interactive = LocalTvInteractive.current
    val busy = when (mode) {
        TvMediaSearchMode.WORKS -> loading
        TvMediaSearchMode.PEOPLE -> peopleLoading
        TvMediaSearchMode.FILES -> filesLoading
    }
    val issue = when (mode) {
        TvMediaSearchMode.WORKS -> error
        TvMediaSearchMode.PEOPLE -> peopleError
        TvMediaSearchMode.FILES -> filesError
    }
    val more = when (mode) {
        TvMediaSearchMode.WORKS -> hasMore
        TvMediaSearchMode.PEOPLE -> peopleHasMore
        TvMediaSearchMode.FILES -> filesHasMore
    }
    val empty = when (mode) {
        TvMediaSearchMode.WORKS -> entries.isEmpty()
        TvMediaSearchMode.PEOPLE -> people.isEmpty()
        TvMediaSearchMode.FILES -> files.isEmpty()
    }
    LaunchedEffect(interactive) {
        if (interactive) {
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
            }, query, onQuery, Modifier.weight(1f).focusGroup().focusRequester(focus).tvMediaReturnFocus("search-field", "search"),
                showLabel = false, options = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }))
            MediaAction("搜索", onSubmit, enabled = query.isNotBlank() && !busy)
        }
        Row(Modifier.padding(horizontal = MediaLayout.pageInset), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            MediaQuietAction("作品", { onMode(TvMediaSearchMode.WORKS) }, selected = mode == TvMediaSearchMode.WORKS)
            MediaQuietAction("人物", { onMode(TvMediaSearchMode.PEOPLE) }, selected = mode == TvMediaSearchMode.PEOPLE)
            if (canSearchFiles) MediaQuietAction("文件", { onMode(TvMediaSearchMode.FILES) }, selected = mode == TvMediaSearchMode.FILES)
        }
        when {
            busy -> MediaLoadingStatus(when (mode) {
                TvMediaSearchMode.WORKS -> "正在搜索影视"
                TvMediaSearchMode.PEOPLE -> "正在搜索人物"
                TvMediaSearchMode.FILES -> "正在搜索文件"
            })
            issue != null -> MediaStatus(issue, onRetry)
            searched && empty -> MediaStatus(when (mode) {
                TvMediaSearchMode.WORKS -> "没有找到相关影视，试试缩短片名或使用原名。"
                TvMediaSearchMode.PEOPLE -> "没有找到相关人物，试试使用姓名中的一部分。"
                TvMediaSearchMode.FILES -> "没有找到相关文件，试试使用文件名中的一部分。"
            })
            mode == TvMediaSearchMode.PEOPLE -> TvMediaPeopleShelf(people, onPerson)
            mode == TvMediaSearchMode.FILES -> TvMediaFileShelf(files, imageLoader, onItem)
            else -> MediaShelf(TvMediaShelf("search-results", "搜索结果", entries), imageLoader, onItem)
        }
        if (more && !busy) MediaAction("加载更多", onMore, modifier = Modifier.padding(horizontal = MediaLayout.pageInset))
    }
}
