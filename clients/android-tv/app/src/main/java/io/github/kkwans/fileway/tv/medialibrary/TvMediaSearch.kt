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

internal enum class TvMediaSearchMode { WORKS, PEOPLE }

@Composable
internal fun TvMediaSearchScreen(query: String, searched: Boolean, mode: TvMediaSearchMode,
    entries: List<TvMediaCard>, loading: Boolean, error: String?, hasMore: Boolean,
    people: List<MediaCatalogPerson>, peopleLoading: Boolean, peopleError: String?, peopleHasMore: Boolean,
    imageLoader: ImageLoader, onQuery: (String) -> Unit, onSubmit: () -> Unit, onMode: (TvMediaSearchMode) -> Unit,
    onBack: () -> Unit, onItem: (String) -> Unit, onPerson: (String, String) -> Unit,
    onRetry: () -> Unit, onMore: () -> Unit) {
    val focus = remember { FocusRequester() }
    val interactive = LocalTvInteractive.current
    val peopleMode = mode == TvMediaSearchMode.PEOPLE
    val busy = if (peopleMode) peopleLoading else loading
    val issue = if (peopleMode) peopleError else error
    val more = if (peopleMode) peopleHasMore else hasMore
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
            TvField(if (peopleMode) "演员、导演或编剧姓名" else "作品标题或原名", query, onQuery,
                Modifier.weight(1f).focusGroup().focusRequester(focus).tvMediaReturnFocus("search-field", "search"), showLabel = false,
                options = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { onSubmit() }))
            MediaAction("搜索", onSubmit, enabled = query.isNotBlank() && !busy)
        }
        Row(Modifier.padding(horizontal = MediaLayout.pageInset), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            MediaQuietAction("作品", { onMode(TvMediaSearchMode.WORKS) }, selected = !peopleMode)
            MediaQuietAction("人物", { onMode(TvMediaSearchMode.PEOPLE) }, selected = peopleMode)
        }
        when {
            busy -> MediaLoadingStatus(if (peopleMode) "正在搜索人物" else "正在搜索影视")
            issue != null -> MediaStatus(issue, onRetry)
            searched && (if (peopleMode) people.isEmpty() else entries.isEmpty()) ->
                MediaStatus(if (peopleMode) "没有找到相关人物，试试使用姓名中的一部分。" else "没有找到相关影视，试试缩短片名或使用原名。")
            peopleMode -> TvMediaPeopleShelf(people, onPerson)
            else -> MediaShelf(TvMediaShelf("search-results", "搜索结果", entries), imageLoader, onItem)
        }
        if (more && !busy) MediaAction("加载更多", onMore, modifier = Modifier.padding(horizontal = MediaLayout.pageInset))
    }
}
