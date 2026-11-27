package io.github.kkwans.fileway.tv.medialibrary

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import coil3.ImageLoader
import io.github.kkwans.nasfilebrowser.data.MediaCatalogPerson
import io.github.kkwans.fileway.tv.LocalTvInteractive
import io.github.kkwans.fileway.tv.TvField
import io.github.kkwans.fileway.design.R as Art

internal enum class TvMediaSearchMode { WORKS, PEOPLE, FILES, COLLECTIONS }

@Composable
internal fun TvMediaSearchScreen(query: String, searched: Boolean, mode: TvMediaSearchMode,
    entries: List<TvMediaCard>, loading: Boolean, error: String?, hasMore: Boolean,
    people: List<MediaCatalogPerson>, peopleLoading: Boolean, peopleError: String?, peopleHasMore: Boolean,
    files: List<TvMediaFileResult>, filesLoading: Boolean, filesError: String?, filesHasMore: Boolean,
    collections: List<TvMediaCollectionResult>, collectionsLoading: Boolean, collectionsError: String?, collectionsHasMore: Boolean,
    canSearchFiles: Boolean, canSearchCollections: Boolean, imageLoader: ImageLoader, onQuery: (String) -> Unit, onSubmit: () -> Unit,
    onMode: (TvMediaSearchMode) -> Unit, onBack: () -> Unit, onItem: (String) -> Unit,
    onPerson: (String, String) -> Unit, onCollection: (String, String) -> Unit, onRetry: () -> Unit, onMore: () -> Unit) {
    val focus = remember { FocusRequester() }
    val interactive = LocalTvInteractive.current
    val routeFocus = LocalTvMediaFocusBinding.current
    val entryTarget = remember {
        resolveTvSearchEntryFocusTarget(routeFocus?.let {
            it.targetId?.let { id -> it.targetOrigin?.let { origin -> TvMediaFocusTarget(id, origin) } }
        })
    }
    val busy = when (mode) {
        TvMediaSearchMode.WORKS -> loading
        TvMediaSearchMode.PEOPLE -> peopleLoading
        TvMediaSearchMode.FILES -> filesLoading
        TvMediaSearchMode.COLLECTIONS -> collectionsLoading
    }
    val issue = when (mode) {
        TvMediaSearchMode.WORKS -> error
        TvMediaSearchMode.PEOPLE -> peopleError
        TvMediaSearchMode.FILES -> filesError
        TvMediaSearchMode.COLLECTIONS -> collectionsError
    }
    val more = when (mode) {
        TvMediaSearchMode.WORKS -> hasMore
        TvMediaSearchMode.PEOPLE -> peopleHasMore
        TvMediaSearchMode.FILES -> filesHasMore
        TvMediaSearchMode.COLLECTIONS -> collectionsHasMore
    }
    val empty = when (mode) {
        TvMediaSearchMode.WORKS -> entries.isEmpty()
        TvMediaSearchMode.PEOPLE -> people.isEmpty()
        TvMediaSearchMode.FILES -> files.isEmpty()
        TvMediaSearchMode.COLLECTIONS -> collections.isEmpty()
    }
    LaunchedEffect(interactive, entryTarget) {
        if (interactive && entryTarget.id == "search-field" && entryTarget.origin == "search") {
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
                TvMediaSearchMode.COLLECTIONS -> "合集名称"
            }, query, onQuery, Modifier.weight(1f).focusGroup().focusRequester(focus).tvMediaReturnFocus("search-field", "search"),
                showLabel = false, options = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }))
            MediaAction("搜索", onSubmit, enabled = query.isNotBlank() && !busy)
        }
        val colors = LocalMediaColors.current
        Row(Modifier.padding(horizontal = MediaLayout.pageInset), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val modes = buildList {
                add(TvMediaSearchMode.WORKS to "作品")
                add(TvMediaSearchMode.PEOPLE to "人物")
                if (canSearchFiles) add(TvMediaSearchMode.FILES to "文件")
                if (canSearchCollections) add(TvMediaSearchMode.COLLECTIONS to "合集")
            }
            modes.forEach { (value, label) -> SearchModeTab(label, mode == value, { onMode(value) }, colors.accent, colors.secondary) }
        }
        when {
            issue != null && empty -> MediaStatus(issue, onRetry)
            searched && empty && !busy -> MediaStatus(when (mode) {
                TvMediaSearchMode.WORKS -> "没有找到相关影视，试试缩短片名或使用原名。"
                TvMediaSearchMode.PEOPLE -> "没有找到相关人物，试试使用姓名中的一部分。"
                TvMediaSearchMode.FILES -> "没有找到相关文件，试试使用文件名中的一部分。"
                TvMediaSearchMode.COLLECTIONS -> "没有找到相关合集，试试使用合集名称中的一部分。"
            })
            mode == TvMediaSearchMode.PEOPLE -> TvMediaPeopleShelf(people, onPerson)
            mode == TvMediaSearchMode.FILES -> TvMediaFileShelf(files, imageLoader, onItem)
            mode == TvMediaSearchMode.COLLECTIONS -> TvMediaCollectionShelf(collections, imageLoader, onCollection)
            else -> MediaShelf(TvMediaShelf("search-results", "搜索结果", entries), imageLoader, onItem)
        }
        if (issue != null && !empty) MediaStatus(issue, onRetry, compact = true)
        if (busy && empty) MediaLoadingStatus(when (mode) {
            TvMediaSearchMode.WORKS -> "正在搜索影视"
            TvMediaSearchMode.PEOPLE -> "正在搜索人物"
            TvMediaSearchMode.FILES -> "正在搜索文件"
            TvMediaSearchMode.COLLECTIONS -> "正在搜索合集"
        })
        if (busy && !empty) MediaLoadingStatus("正在加载更多…", compact = true)
        if (more && !busy) MediaAction("加载更多", onMore, modifier = Modifier.padding(horizontal = MediaLayout.pageInset))
    }
}

@Composable
private fun SearchModeTab(label: String, active: Boolean, onClick: () -> Unit, accent: Color, secondary: Color) {
    Column {
        MediaSurface(onClick, Modifier.heightIn(min = 48.dp), selected = active,
            container = Color.Transparent, selectionContainer = false,
            contentColor = if (active) accent else secondary) {
            Text(label, Modifier.padding(horizontal = 12.dp, vertical = 12.dp), style = MediaType.action)
        }
        Box(Modifier.align(Alignment.CenterHorizontally).width(if (active) 24.dp else 0.dp).height(2.dp)
            .background(if (active) accent else Color.Transparent))
    }
}
