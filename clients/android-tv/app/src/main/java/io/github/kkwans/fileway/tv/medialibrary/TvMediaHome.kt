package io.github.kkwans.fileway.tv.medialibrary

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.Text
import coil3.ImageLoader
import io.github.kkwans.fileway.tv.tvTextStyle
import kotlinx.coroutines.delay
import io.github.kkwans.fileway.design.R as Art

/** Stateless navigation/playback boundary. Keep scrollState in the owning route for return restoration. */
@Composable
fun TvMediaHome(
    state: TvMediaHomeState,
    imageLoader: ImageLoader,
    onItem: (String) -> Unit,
    onContinue: (String) -> Unit,
    modifier: Modifier = Modifier,
    onPlay: ((String) -> Unit)? = null,
    onContinueOptions: ((String) -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    onRetryShelf: ((String) -> Unit)? = null,
    onManageLibrary: (() -> Unit)? = null,
    autoRotate: Boolean = true,
    interactionPaused: Boolean = false,
    scrollState: LazyListState = rememberLazyListState(),
    navigation: @Composable () -> Unit = {},
) {
    val colors = LocalMediaColors.current
    val shelves = state.shelves.filter { it.items.isNotEmpty() || it.loading || it.error != null }
    val hasContent = state.featured.isNotEmpty() || state.continuing.isNotEmpty() || shelves.isNotEmpty()
    Column(modifier.fillMaxSize().background(colors.canvas)) {
        navigation()
        LazyColumn(state = scrollState, modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            if (state.error != null) item(key = "status-error") { MediaStatus(state.error, onRetry, compact = hasContent) }
            if (state.featured.isNotEmpty()) item(key = "featured") {
                MediaFeaturedCarousel(state.scopeKey, state.featured.take(5), imageLoader, onItem, onPlay,
                    autoRotate, interactionPaused)
            }
            if (state.continuing.isNotEmpty()) item(key = "continue") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    MediaSectionTitle("继续观看")
                    LazyRow(Modifier.focusGroup(), contentPadding = PaddingValues(horizontal = 40.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        items(state.continuing, key = { it.id }) { entry ->
                            MediaContinueCard(entry, imageLoader, { onContinue(entry.id) },
                                onContinueOptions?.let { { it(entry.id) } })
                        }
                    }
                }
            }
            items(shelves, key = { "shelf:${it.id}" }) { shelf -> MediaShelf(shelf, imageLoader, onItem, onRetryShelf) }
            if (state.loading && !hasContent) item(key = "loading") { MediaStatus("正在加载片库…") }
            if (!hasContent && !state.loading && state.error == null) item(key = "empty") {
                Column(Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 64.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(state.emptyTitle, style = tvTextStyle(28, 36, FontWeight.Medium), color = colors.text)
                    Text(state.emptyMessage, style = tvTextStyle(18, 28), color = colors.secondary)
                    if (onManageLibrary != null) MediaAction("管理片库", onManageLibrary,
                        primary = true, icon = Art.drawable.fileway_ic_library)
                }
            }
        }
    }
}

@Composable
private fun MediaFeaturedCarousel(
    scopeKey: String, entries: List<TvMediaCard>, imageLoader: ImageLoader,
    onItem: (String) -> Unit, onPlay: ((String) -> Unit)?, autoRotate: Boolean, interactionPaused: Boolean,
) {
    val colors = LocalMediaColors.current
    var activeId by rememberSaveable(scopeKey) { mutableStateOf(entries.first().id) }
    // A reordered list must not retarget the focused action. Disappearance is treated
    // as invalidation, including both a top-five eviction and permission revocation:
    // never retain an old artwork/payload or silently fall back to another work.
    val index = entries.indexOfFirst { it.id == activeId }
    val current = entries.getOrNull(index)
    var containsFocus by remember(scopeKey) { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val clearInvalidatedFocus = current == null && containsFocus
    LaunchedEffect(scopeKey, activeId, current == null) {
        if (clearInvalidatedFocus) focusManager.clearFocus(force = true)
    }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val ids = entries.map { it.id }
    val mayRotate = autoRotate && !interactionPaused && !containsFocus && windowFocused &&
        lifecycle.isAtLeast(Lifecycle.State.RESUMED) && entries.size > 1 && current != null
    LaunchedEffect(scopeKey, ids, activeId, mayRotate) {
        if (mayRotate) {
            delay(8_000)
            activeId = entries[(index + 1) % entries.size].id
        }
    }
    if (current == null) {
        // This is a different action node from the former Details/Play buttons. A
        // pending press cannot become an action on the replacement work. Recovery
        // requires an explicit new selection; auto-rotation stays paused meanwhile.
        key(scopeKey, "invalidated-feature") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("最近入库内容已更新", style = tvTextStyle(24, 32, FontWeight.Medium), color = colors.text)
                Text("当前影片已不在此列表中，请重新选择。", style = tvTextStyle(18, 27), color = colors.secondary)
                MediaAction("重新浏览", { activeId = entries.first().id }, icon = Art.drawable.fileway_ic_refresh)
            }
        }
        return
    }
    Box(Modifier.fillMaxWidth().onFocusChanged { containsFocus = it.hasFocus }.focusGroup()) {
        MediaBackdrop(current.backdrop, imageLoader, Modifier.matchParentSize())
        Column(Modifier.fillMaxWidth().padding(start = 40.dp, end = 40.dp, top = 16.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.width(3.dp).height(17.dp).background(colors.accent, RoundedCornerShape(2.dp)))
                Text("最近入库", style = tvTextStyle(16, 22), color = colors.secondary)
            }
            Column(Modifier.fillMaxWidth(.72f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(current.title, style = tvTextStyle(36, 46, FontWeight.Medium), color = colors.text,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                current.originalTitle?.takeIf(String::isNotBlank)?.let {
                    Text(it, style = tvTextStyle(16, 23), color = colors.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (current.metadataLabel.isNotBlank()) Text(current.metadataLabel, style = tvTextStyle(18, 26), color = colors.text)
                current.primaryRating?.let {
                    Text("${it.source.label} ${it.valueLabel}", style = tvTextStyle(17, 24, FontWeight.Medium), color = colors.score)
                }
                current.overview?.takeIf(String::isNotBlank)?.let {
                    Text(it, style = tvTextStyle(18, 27), color = colors.secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            key(scopeKey, current.id) {
                val focus = LocalTvMediaFocusBinding.current
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    MediaAction("查看详情", { focus?.onOpen?.invoke(current.id, "hero"); onItem(current.id) },
                        Modifier.tvMediaReturnFocus(current.id, "hero"), primary = true)
                    if (onPlay != null && current.canPlay) MediaAction("播放", {
                        focus?.onOpen?.invoke(current.id, "hero-play"); onPlay(current.id)
                    }, Modifier.tvMediaReturnFocus(current.id, "hero-play"), icon = Art.drawable.fileway_ic_play_arrow)
                }
            }
            if (entries.size > 1) Row(Modifier.align(Alignment.End),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                MediaAction("上一部", { activeId = entries[(index - 1 + entries.size) % entries.size].id },
                    icon = Art.drawable.fileway_ic_arrow_back)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    repeat(entries.size) { dot ->
                        Box(Modifier.width(if (dot == index) 18.dp else 10.dp).height(3.dp)
                            .background(if (dot == index) colors.accent else colors.secondary.copy(alpha = .35f), RoundedCornerShape(2.dp)))
                    }
                }
                Text("${index + 1} / ${entries.size}", style = tvTextStyle(16, 22), color = colors.secondary)
                MediaAction("下一部", { activeId = entries[(index + 1) % entries.size].id }, icon = Art.drawable.fileway_ic_arrow_forward)
            }
        }
    }
}

@Composable
private fun MediaContinueCard(
    entry: TvMediaContinueItem, imageLoader: ImageLoader, onClick: () -> Unit, onOptions: (() -> Unit)?,
) {
    val colors = LocalMediaColors.current
    Row(Modifier.width(356.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
        MediaSurface(onClick, Modifier.width(184.dp).aspectRatio(16f / 9f)
            .semantics { contentDescription = "继续观看 ${entry.item.title} ${entry.episodeLabel.orEmpty()}" },
            enabled = entry.item.canPlay) {
            MediaArtwork(entry.item.backdrop, imageLoader, Modifier.fillMaxSize())
            MediaProgress(entry.progress, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(7.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(entry.item.title, style = tvTextStyle(18, 25, FontWeight.Medium), color = colors.text,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            listOfNotNull(entry.episodeLabel, entry.remainingLabel, entry.item.availabilityLabel)
                .filter(String::isNotBlank).forEach { label ->
                    Text(label, style = tvTextStyle(16, 23), color = colors.secondary,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            if (onOptions != null) MediaAction("更多", onOptions, icon = Art.drawable.fileway_ic_more_horiz)
        }
    }
}
