package io.github.kkwans.fileway.tv.medialibrary

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayMediaShape
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayMediaSpace
import io.github.kkwans.fileway.tv.TvRemoteDialogKeys
import io.github.kkwans.fileway.tv.LocalTvInteractive
import io.github.kkwans.fileway.design.R as Art

/** A separate Dialog window traps focus/input, including the existing phone remote gateway.
 * Selection is local until confirmation; no source switch or playback happens during focus movement. */
@Composable
fun TvMediaSourcePicker(
    state: TvMediaSourcePickerState,
    onDismiss: () -> Unit,
    onConfirm: (editionId: String, sourceId: String) -> Unit,
    onRecover: ((sourceId: String) -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    onMore: (() -> Unit)? = null,
) {
    val colors = LocalMediaColors.current
    val initialEdition = state.editions.firstOrNull { edition -> edition.sources.any { it.id == state.initialSourceId } }
        ?: state.editions.firstOrNull()
    var editionId by rememberSaveable(state.scopeKey, state.itemId) { mutableStateOf(initialEdition?.id) }
    // Current playback may change remotely while this panel is open. That updates
    // the Current badge, never the user's draft selection or the confirm target.
    var selectedSourceId by rememberSaveable(state.scopeKey, state.itemId) { mutableStateOf(state.initialSourceId) }
    // A removed edition is not interchangeable with the first remaining cut.
    val edition = if (editionId == null) initialEdition else state.editions.firstOrNull { it.id == editionId }
    val selectedSource = edition?.sources?.firstOrNull { it.id == selectedSourceId && it.available }
    val closeFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val currentOnMore by rememberUpdatedState(onMore)
    LaunchedEffect(listState, state.loading, state.applying, state.error, onMore != null) {
        if (onMore == null || state.loading || state.applying || state.error != null) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.any { it.key == "more" } }.collect { visible ->
            if (visible) currentOnMore?.invoke()
        }
    }
    val interactive = LocalTvInteractive.current
    Dialog(onDismissRequest = { if (interactive) onDismiss() }, properties = DialogProperties(
        usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        if (interactive) TvRemoteDialogKeys(onDismiss)
        LaunchedEffect(state.scopeKey, state.itemId, interactive) {
            if (!interactive) return@LaunchedEffect
            withFrameNanos { }
            closeFocus.requestFocus()
        }
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .5f))) {
            val endInset = if (maxWidth >= 720.dp) 48.dp else 16.dp
            val verticalInset = if (maxHeight >= 480.dp) 28.dp else 12.dp
            val panelWidth = (maxWidth * .48f).coerceIn(440.dp, 520.dp).coerceAtMost(maxWidth - endInset * 2)
            Column(Modifier.align(Alignment.CenterEnd).padding(end = endInset, top = verticalInset, bottom = verticalInset)
                .width(panelWidth).fillMaxHeight().clip(MediaLayout.shape).background(colors.canvas)
                .padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    MediaIconAction("返回", onDismiss, Art.drawable.fileway_ic_arrow_back, Modifier.focusRequester(closeFocus))
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("版本与片源", style = MediaType.pageTitle, color = colors.text,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("当前服务器中的可用片源", style = MediaType.secondary, color = colors.secondary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth().focusGroup(),
                    contentPadding = PaddingValues(4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item(key = "title") {
                        Text(state.title, style = MediaType.pageTitle, color = colors.text,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (state.editions.isNotEmpty()) item(key = "editions") {
                        LazyRow(Modifier.fillMaxWidth().focusGroup(),
                            horizontalArrangement = Arrangement.spacedBy(FilewayMediaSpace.Tv.content)) {
                            items(state.editions, key = { it.id }) { choice ->
                                Column {
                                    val active = edition?.id == choice.id
                                    MediaSurface({
                                        editionId = choice.id
                                        selectedSourceId = state.currentSourceId?.takeIf { current -> choice.sources.any { it.id == current && it.available } }
                                    }, Modifier.heightIn(min = 48.dp), selected = active, enabled = !state.applying,
                                        container = Color.Transparent, selectionContainer = false,
                                        contentColor = if (active) colors.accent else colors.secondary) {
                                        Text(choice.title, Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                                            style = MediaType.action, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    Box(Modifier.align(Alignment.CenterHorizontally).width(28.dp).height(2.dp)
                                        .background(if (active) colors.accent else Color.Transparent))
                                }
                            }
                        }
                    }
                    if (edition != null) item(key = "edition-summary") {
                        Text(listOfNotNull(edition.title, edition.runtimeLabel).joinToString(" · "),
                            style = MediaType.body, color = colors.secondary)
                    }
                    if (state.loading && edition?.sources.isNullOrEmpty()) item(key = "loading") {
                        Box(Modifier.fillMaxWidth().heightIn(min = 120.dp), contentAlignment = Alignment.Center) {
                            MediaLoadingStatus("正在读取片源…", compact = true)
                        }
                    }
                    if (state.error != null) item(key = "error") {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(state.error, style = MediaType.body, color = colors.secondary)
                            if (onRetry != null) MediaAction("重试", onRetry, icon = Art.drawable.fileway_ic_refresh)
                        }
                    }
                    items(edition?.sources.orEmpty(), key = { "source:${it.id}" }) { source ->
                        MediaSourceRow(source, current = source.id == state.currentSourceId,
                            selected = source.id == selectedSource?.id, applying = state.applying,
                            onSelect = { selectedSourceId = source.id },
                            onRecover = onRecover?.let { { it(source.id) } })
                    }
                    if (state.loading && !edition?.sources.isNullOrEmpty()) item(key = "loading-more") {
                        Box(Modifier.fillMaxWidth().heightIn(min = 120.dp), contentAlignment = Alignment.Center) {
                            MediaLoadingStatus("正在加载更多片源…", compact = true)
                        }
                    }
                    if (onMore != null && !state.loading && !state.applying) item(key = "more") {
                        MediaAction("加载更多片源", onMore)
                    }
                    if (!state.loading && state.error == null && edition?.sources.isNullOrEmpty() && onMore == null) item(key = "empty") {
                        Text("当前没有可访问的片源。", style = MediaType.body, color = colors.secondary)
                    }
                }
                Column(Modifier.align(Alignment.End), horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MediaAction(if (state.applying) "正在准备片源…" else state.confirmLabel, {
                        if (edition != null && selectedSource != null && !state.applying && !state.loading) onConfirm(edition.id, selectedSource.id)
                    }, Modifier.widthIn(min = 220.dp), primary = true,
                        enabled = edition != null && selectedSource != null && !state.applying && !state.loading)
                    Text(state.confirmHint, style = MediaType.secondary, color = colors.secondary)
                }
            }
        }
    }
}

@Composable
private fun SourceBadge(label: String) {
    val colors = LocalMediaColors.current
    Text(label, Modifier.background(colors.raised, RoundedCornerShape(FilewayMediaShape.badge))
        .padding(horizontal = FilewayMediaSpace.Tv.small, vertical = FilewayMediaSpace.Tv.tiny), style = MediaType.badge, color = colors.text)
}

@Composable
private fun MediaSourceRow(
    source: TvMediaSource, current: Boolean, selected: Boolean, applying: Boolean,
    onSelect: () -> Unit, onRecover: (() -> Unit)?,
) {
    val colors = LocalMediaColors.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        MediaSurface(onSelect, Modifier.fillMaxWidth().semantics {
            contentDescription = listOfNotNull(source.locationLabel, source.qualityLabel,
                source.unavailableReason, if (current) "当前片源" else null).joinToString("，")
        }, enabled = source.available && !applying, selected = selected,
            container = if (selected) colors.raised else colors.surface) {
            Row(Modifier.fillMaxWidth().heightIn(min = 104.dp).padding(14.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(46.dp).background(colors.raised, RoundedCornerShape(FilewayMediaShape.card)), contentAlignment = Alignment.Center) {
                    Icon(painterResource(when (source.kind) {
                        TvMediaSourceKind.SERVER -> Art.drawable.fileway_ic_server
                        TvMediaSourceKind.CLOUD -> Art.drawable.fileway_ic_storage
                        TvMediaSourceKind.NETWORK -> Art.drawable.fileway_ic_network
                    }), null, Modifier.size(27.dp), tint = colors.secondary)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(source.locationLabel, Modifier.weight(1f), style = MediaType.itemTitle,
                            color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (current) SourceBadge("当前")
                    }
                    source.qualityLabel?.takeIf(String::isNotBlank)?.let {
                        Text(it, style = MediaType.sectionTitle, color = colors.text)
                    }
                    source.detailLabel?.takeIf(String::isNotBlank)?.let {
                        Text(it, style = MediaType.secondary, color = colors.secondary)
                    }
                    source.fileName?.takeIf(String::isNotBlank)?.let {
                        Text(it, style = MediaType.secondary, color = colors.secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (!source.available) Text(source.unavailableReason?.takeIf(String::isNotBlank) ?: "此片源暂不可用",
                        style = MediaType.secondary, color = colors.secondary)
                }
                if (source.available) Box(Modifier.size(24.dp).border(1.5.dp, colors.secondary, CircleShape)
                    .background(if (selected) colors.text else Color.Transparent, CircleShape), contentAlignment = Alignment.Center) {
                    if (selected) Icon(painterResource(Art.drawable.fileway_ic_check), null, Modifier.size(18.dp), tint = colors.canvas)
                }
            }
        }
        if (!source.available && source.recoveryLabel != null && onRecover != null) MediaAction(source.recoveryLabel,
            onRecover, Modifier.align(Alignment.End), enabled = !applying, icon = Art.drawable.fileway_ic_refresh)
    }
}
