package io.github.kkwans.nasfilebrowser.ui.medialibrary

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.ui.FilewayIcon
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayEmptyState
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayMenuItem
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayTopAppBar
import io.github.kkwans.fileway.design.R as Art

/** Parent supplies safe insets and an account-scoped state. A revoked item must be removed
 * from the state, not retained under an error banner. listState may be held by the route. */
@Composable internal fun PhoneMediaDetail(state: PhoneMediaDetailState, imageLoader: ImageLoader,
    onAction: (PhoneMediaAction) -> Unit, modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState()) {
    key(state.scopeKey, state.id) {
        var expanded by rememberSaveable { mutableStateOf(false) }
        var compact by rememberSaveable { mutableStateOf(false) }
        var menu by remember { mutableStateOf(false) }
        var jump by remember { mutableStateOf(false) }
        val background = MaterialTheme.colorScheme.background
        Column(modifier.fillMaxSize().background(background)) {
            FilewayTopAppBar("影视详情", leadingIcon = R.drawable.ic_arrow_back, leadingDescription = "返回",
                onLeadingClick = { onAction(PhoneMediaAction.Back) }, actions = {
                    if (state.canReviewMatch) Box {
                        MediaIconAction(R.drawable.ic_more_vert, "更多操作", { menu = true })
                        DropdownMenu(menu, { menu = false }) {
                            if (state.canReviewMatch) FilewayMenuItem("纠正识别", {
                                menu = false; onAction(PhoneMediaAction.ReviewMatch(state.id))
                            }, icon = R.drawable.ic_edit, enabled = !state.busy)
                        }
                    }
                })
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                LazyColumn(Modifier.widthIn(max = 840.dp).fillMaxSize(), state = listState,
                    contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (state.loading) item(key = "loading") { MediaLoading("正在加载影视详情") }
                    state.problem?.let { problem -> item(key = "problem") {
                        MediaProblemCard(problem, { onAction(PhoneMediaAction.Retry("detail")) }, Modifier.padding(horizontal = 16.dp))
                    } }
                    item(key = "hero") {
                        Column {
                            if (state.backdrop != null) Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                                MediaImage(state.backdrop, imageLoader, Modifier.fillMaxSize())
                                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                                    0f to Color.Transparent, .55f to Color.Transparent, 1f to background)))
                            }
                            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(state.title, style = MaterialTheme.typography.headlineLarge,
                                    modifier = Modifier.semantics { heading() })
                                state.originalTitle?.takeIf { it.isNotBlank() && it != state.title }?.let {
                                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (state.metadata.isNotBlank()) Text(state.metadata, style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    item(key = "ratings") {
                        if (state.ratings.isEmpty()) Text("暂无评分", Modifier.padding(horizontal = 16.dp),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(state.ratings, key = { it.id }) { rating -> RatingCard(rating) }
                        }
                    }
                    if (state.canSelectSources) item(key = "play") {
                        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { onAction(PhoneMediaAction.Play(state.id)) }, enabled = state.canPlay && !state.busy,
                                    modifier = Modifier.weight(1f).heightIn(min = 56.dp), shape = MaterialTheme.shapes.medium,
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)) {
                                    FilewayIcon(R.drawable.art_play, null, Modifier.size(22.dp)); Spacer(Modifier.width(8.dp))
                                    Text(state.playLabel)
                                }
                                if (state.canHandoff) FilledTonalButton(onClick = { onAction(PhoneMediaAction.Handoff(state.id)) },
                                    enabled = state.canHandoff && !state.busy, modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                                    shape = MaterialTheme.shapes.medium, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)) {
                                    FilewayIcon(Art.drawable.fileway_ic_cast, null, Modifier.size(22.dp)); Spacer(Modifier.width(8.dp))
                                    Text("在 TV 播放")
                                }
                            }
                            if (state.busy) MediaLoading("正在处理")
                            state.playProblem?.let { MediaProblemCard(it, { onAction(PhoneMediaAction.Retry("playback")) }) }
                            if (!state.canPlay && state.playProblem == null && !state.loading) Text("暂无可播放片源",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (state.canSelectSources) item(key = "sources") {
                        Surface(onClick = { onAction(PhoneMediaAction.Sources(state.id)) }, enabled = !state.busy,
                            modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(), shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceContainerLow) {
                            Row(Modifier.heightIn(min = 72.dp).padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(state.sourceCount?.let { "片源与版本 · $it" } ?: "片源与版本", style = MaterialTheme.typography.titleMedium)
                                    Text(state.sourceSummary ?: "查看可用片源", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                FilewayIcon(R.drawable.ic_arrow_forward, "选择片源", Modifier.size(20.dp))
                            }
                        }
                    }
                    if (state.canChangeUserState) item(key = "user-state") {
                        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { onAction(PhoneMediaAction.Favorite(state.id, !state.favorite, state.userStateRevision)) },
                                enabled = !state.userStateBusy, modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                                    .semantics { selected = state.favorite }, shape = MaterialTheme.shapes.medium) {
                                FilewayIcon(R.drawable.ic_bookmark, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                                Text(if (state.favorite) "已收藏" else "收藏")
                            }
                            OutlinedButton(onClick = { onAction(PhoneMediaAction.Watched(state.id, !state.watched, state.userStateRevision)) },
                                enabled = !state.userStateBusy, modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                                    .semantics { selected = state.watched }, shape = MaterialTheme.shapes.medium) {
                                FilewayIcon(R.drawable.ic_check, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                                Text(if (state.watched) "已看过" else "标记已看")
                            }
                        }
                    }
                    state.userStateProblem?.let { issue -> item(key = "user-state-recovery") {
                        MediaProblemCard(issue, { onAction(PhoneMediaAction.Retry("user-state")) }, Modifier.padding(horizontal = 16.dp))
                    } }
                    item(key = "overview") {
                        Column(Modifier.padding(horizontal = 16.dp)) {
                            MediaHeading("剧情简介")
                            Text(state.overview?.takeIf { it.isNotBlank() } ?: "暂无简介", style = MaterialTheme.typography.bodyLarge,
                                maxLines = if (expanded) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis)
                            if (!state.overview.isNullOrBlank()) TextButton(onClick = { expanded = !expanded },
                                modifier = Modifier.heightIn(min = 48.dp)) { Text(if (expanded) "收起简介" else "展开简介") }
                        }
                    }
                    if (state.seasons.isNotEmpty() || state.seasonsLoading || state.seasonsProblem != null) {
                        item(key = "seasons") {
                            Column(Modifier.padding(horizontal = 16.dp)) {
                                MediaHeading("剧集", actionLabel = if (compact) "卡片视图" else "序号视图", onAction = { compact = !compact })
                                state.episodeSummary?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(state.seasons, key = { it.id }) { season ->
                                        FilterChip(state.selectedSeason == season.id,
                                            { onAction(PhoneMediaAction.SelectSeason(season.id)) }, label = { Text(season.label) },
                                            modifier = Modifier.heightIn(min = 48.dp))
                                    }
                                }
                                state.seasonsProblem?.let { MediaProblemCard(it, { onAction(PhoneMediaAction.Retry("seasons")) }) }
                                MediaPaging("seasons", state.seasonsLoading, state.seasonsHasMore, onAction)
                            }
                        }
                        state.episodes.problem?.let { problem -> item(key = "episodes-error") {
                            MediaProblemCard(problem, { onAction(PhoneMediaAction.Retry("episodes")) }, Modifier.padding(horizontal = 16.dp))
                        } }
                        if (compact) {
                            items(state.episodes.entries.chunked(4), key = { "episode-row/${it.first().id}" }) { row ->
                                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    row.forEach { episode ->
                                        OutlinedButton(onClick = { onAction(PhoneMediaAction.Play(episode.id, kind = MediaTargetKind.EPISODE)) }, enabled = episode.playable,
                                            modifier = Modifier.weight(1f).heightIn(min = 56.dp).semantics {
                                                contentDescription = listOfNotNull(episode.numberLabel, episode.title, episode.unavailableReason).joinToString("，")
                                            }, shape = MaterialTheme.shapes.medium,
                                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)) { Text(episode.numberLabel) }
                                    }
                                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        } else items(state.episodes.entries, key = { "episode/${it.id}" }) { episode ->
                            EpisodeCard(episode, imageLoader, onAction)
                        }
                        if (state.episodes.entries.isEmpty() && !state.episodes.loading && state.episodes.problem == null) item(key = "episodes-empty") {
                            FilewayEmptyState(R.drawable.ic_video, "本季尚未收录单集", "切换其他季，或在媒体库中查看扫描进度。")
                        }
                        item(key = "episodes-paging") { MediaPaging("episodes", state.episodes.loading, state.episodes.hasMore, onAction) }
                    }
                    item(key = "credits") {
                        Column {
                            MediaHeading("演职人员", Modifier.padding(horizontal = 16.dp),
                                if (state.credits.hasMore) "全部" else null,
                                if (state.credits.hasMore) ({ onAction(PhoneMediaAction.AllCredits(state.id)) }) else null)
                            state.credits.problem?.let { MediaProblemCard(it, { onAction(PhoneMediaAction.Retry("credits")) }, Modifier.padding(horizontal = 16.dp)) }
                            if (state.credits.loading && state.credits.entries.isEmpty()) MediaLoading("正在加载演职人员")
                            if (state.credits.entries.isEmpty() && !state.credits.loading && state.credits.problem == null) Text("暂无演职员资料",
                                Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(state.credits.entries, key = { it.id }) { credit ->
                                    Card(modifier = Modifier.width(104.dp),
                                        colors = CardDefaults.cardColors(containerColor = background)) {
                                        MediaImage(credit.portrait, imageLoader, Modifier.fillMaxWidth().aspectRatio(1f), person = true)
                                        Text(credit.name, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall,
                                            minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text(credit.role, style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (jump) EpisodeJumpDialog(state.selectedSeason, { jump = false }, onAction)
    }
}

@Composable private fun RatingCard(rating: MediaRating) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.widthIn(min = 108.dp, max = 220.dp).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(listOfNotNull(rating.source, rating.metric).joinToString(" · "), style = MaterialTheme.typography.labelLarge)
            Text(rating.value, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.headlineSmall)
            rating.count?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            rating.scope?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable private fun EpisodeCard(episode: MediaEpisode, loader: ImageLoader, onAction: (PhoneMediaAction) -> Unit) {
    Card(onClick = { onAction(if (episode.playable) PhoneMediaAction.Play(episode.id, kind = MediaTargetKind.EPISODE)
        else PhoneMediaAction.Sources(episode.id, MediaTargetKind.EPISODE)) },
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(), shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.width(112.dp)) {
                MediaImage(episode.image, loader, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                MediaProgress(episode.progress)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${episode.numberLabel} · ${episode.title}", style = MaterialTheme.typography.titleSmall,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(episode.unavailableReason ?: episode.subtitle, style = MaterialTheme.typography.bodySmall,
                    color = if (episode.unavailableReason != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            MediaIconAction(R.drawable.ic_more_vert, "${episode.numberLabel}片源与版本", {
                onAction(PhoneMediaAction.Sources(episode.id, MediaTargetKind.EPISODE)) })
        }
    }
}

@Composable private fun EpisodeJumpDialog(seasonId: String?, dismiss: () -> Unit, onAction: (PhoneMediaAction) -> Unit) {
    var number by rememberSaveable { mutableStateOf("") }
    val parsed = number.toIntOrNull()
    AlertDialog(onDismissRequest = dismiss, title = { Text("跳转到指定集") }, text = {
        OutlinedTextField(number, { number = it.filter(Char::isDigit).take(6) }, label = { Text("集数") },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            supportingText = { Text("特别篇请先切换到对应季。") }, modifier = Modifier.fillMaxWidth())
    }, confirmButton = {
        TextButton(onClick = { if (parsed != null && seasonId != null) {
            onAction(PhoneMediaAction.JumpToEpisode(seasonId, parsed)); dismiss()
        } }, enabled = parsed != null && parsed > 0 && seasonId != null, modifier = Modifier.heightIn(min = 48.dp)) { Text("跳转") }
    }, dismissButton = { TextButton(onClick = dismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") } })
}
