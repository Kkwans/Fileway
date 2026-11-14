package io.github.kkwans.fileway.tv.medialibrary

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import io.github.kkwans.fileway.tv.TvRemoteDialogKeys
import io.github.kkwans.fileway.tv.LocalTvInteractive
import io.github.kkwans.fileway.tv.tvTextStyle
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
    val initialEdition = state.editions.firstOrNull { edition -> edition.sources.any { it.id == state.currentSourceId } }
        ?: state.editions.firstOrNull()
    var editionId by rememberSaveable(state.scopeKey, state.itemId) { mutableStateOf(initialEdition?.id) }
    // Current playback may change remotely while this panel is open. That updates
    // the Current badge, never the user's draft selection or the confirm target.
    var selectedSourceId by rememberSaveable(state.scopeKey, state.itemId) { mutableStateOf(state.currentSourceId) }
    // A removed edition is not interchangeable with the first remaining cut.
    val edition = if (editionId == null) initialEdition else state.editions.firstOrNull { it.id == editionId }
    val selectedSource = edition?.sources?.firstOrNull { it.id == selectedSourceId && it.available }
    val closeFocus = remember { FocusRequester() }
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
            val panelWidth = (maxWidth * .54f).coerceAtLeast(440.dp).coerceAtMost(maxWidth)
            Column(Modifier.align(Alignment.CenterEnd).width(panelWidth).fillMaxHeight().background(colors.canvas)
                .padding(horizontal = 28.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    MediaAction("返回", onDismiss, Modifier.focusRequester(closeFocus), icon = Art.drawable.fileway_ic_arrow_back)
                    Text("版本与片源", style = tvTextStyle(26, 34, FontWeight.Medium), color = colors.text)
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(state.title, style = tvTextStyle(28, 36, FontWeight.Medium), color = colors.text,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("当前服务器中的可用片源", style = tvTextStyle(16, 24), color = colors.secondary)
                }
                if (state.editions.isNotEmpty()) LazyRow(Modifier.fillMaxWidth().focusGroup(),
                    contentPadding = PaddingValues(4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(state.editions, key = { it.id }) { choice ->
                        Column {
                            MediaAction(choice.title, {
                                editionId = choice.id
                                selectedSourceId = state.currentSourceId?.takeIf { current -> choice.sources.any { it.id == current && it.available } }
                            }, selected = edition?.id == choice.id, enabled = !state.applying)
                            Box(Modifier.align(Alignment.CenterHorizontally).width(32.dp).height(3.dp)
                                .background(if (edition?.id == choice.id) colors.accent else Color.Transparent))
                        }
                    }
                }
                if (edition != null) Text(listOfNotNull(edition.title, edition.runtimeLabel).joinToString(" · "),
                    style = tvTextStyle(18, 26), color = colors.text)
                LazyColumn(Modifier.weight(1f).fillMaxWidth().focusGroup(),
                    contentPadding = PaddingValues(4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (state.loading) item(key = "loading") {
                        Text("正在读取片源…", style = tvTextStyle(18, 27), color = colors.secondary)
                    }
                    if (state.error != null) item(key = "error") {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(state.error, style = tvTextStyle(18, 27), color = colors.secondary)
                            if (onRetry != null) MediaAction("重试", onRetry, icon = Art.drawable.fileway_ic_refresh)
                        }
                    }
                    items(edition?.sources.orEmpty(), key = { "source:${it.id}" }) { source ->
                        MediaSourceRow(source, current = source.id == state.currentSourceId,
                            selected = source.id == selectedSource?.id, applying = state.applying,
                            onSelect = { selectedSourceId = source.id },
                            onRecover = onRecover?.let { { it(source.id) } })
                    }
                    if (onMore != null) item(key = "more") {
                        MediaAction("加载更多片源", onMore, enabled = !state.loading && !state.applying)
                    }
                    if (!state.loading && state.error == null && edition?.sources.isNullOrEmpty()) item(key = "empty") {
                        Text("当前没有可访问的片源。", style = tvTextStyle(18, 27), color = colors.secondary)
                    }
                }
                Text("选择有权访问的片源", style = tvTextStyle(16, 23), color = colors.secondary)
                MediaAction(if (state.applying) "正在准备片源…" else "使用所选片源", {
                    if (edition != null && selectedSource != null && !state.applying && !state.loading) onConfirm(edition.id, selectedSource.id)
                }, Modifier.align(Alignment.End), primary = true,
                    enabled = edition != null && selectedSource != null && !state.applying && !state.loading)
            }
        }
    }
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
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(46.dp).background(colors.raised, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                    Icon(painterResource(when (source.kind) {
                        TvMediaSourceKind.SERVER -> Art.drawable.fileway_ic_server
                        TvMediaSourceKind.CLOUD -> Art.drawable.fileway_ic_storage
                        TvMediaSourceKind.NETWORK -> Art.drawable.fileway_ic_network
                    }), null, Modifier.size(27.dp), tint = colors.secondary)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(source.locationLabel + if (current) " · 当前" else "", style = tvTextStyle(17, 25, FontWeight.Medium),
                        color = colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    source.qualityLabel?.takeIf(String::isNotBlank)?.let {
                        Text(it, style = tvTextStyle(23, 31, FontWeight.Medium), color = colors.text)
                    }
                    source.detailLabel?.takeIf(String::isNotBlank)?.let {
                        Text(it, style = tvTextStyle(16, 24), color = colors.secondary)
                    }
                    source.fileName?.takeIf(String::isNotBlank)?.let {
                        Text(it, style = tvTextStyle(16, 24), color = colors.secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (!source.available) Text(source.unavailableReason?.takeIf(String::isNotBlank) ?: "此片源暂不可用",
                        style = tvTextStyle(16, 24), color = colors.secondary)
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
