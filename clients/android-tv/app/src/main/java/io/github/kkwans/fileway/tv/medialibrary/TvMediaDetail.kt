package io.github.kkwans.fileway.tv.medialibrary

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import coil3.ImageLoader
import io.github.kkwans.fileway.design.R as Art

/** Callbacks express actual granted actions. A missing callback never produces an inert entry. */
@Composable
fun TvMediaDetail(
    state: TvMediaDetailState,
    imageLoader: ImageLoader,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onPlay: (() -> Unit)? = null,
    onPlayFromStart: (() -> Unit)? = null,
    onSources: (() -> Unit)? = null,
    onToggleFavorite: (() -> Unit)? = null,
    onToggleWatched: (() -> Unit)? = null,
    onPerson: ((String) -> Unit)? = null,
    onAllPeople: (() -> Unit)? = null,
    onSeason: ((String) -> Unit)? = null,
    onEpisode: ((String) -> Unit)? = null,
    onEpisodeOptions: ((String) -> Unit)? = null,
    onMoreEpisodes: (() -> Unit)? = null,
    onRelated: ((String) -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    onRetryEpisodes: (() -> Unit)? = null,
    scrollState: LazyListState = rememberLazyListState(),
) {
    val colors = LocalMediaColors.current
    val focus = LocalTvMediaFocusBinding.current
    var expanded by rememberSaveable(state.scopeKey, state.item.id) { mutableStateOf(false) }
    var fullTitle by rememberSaveable(state.scopeKey, state.item.id) { mutableStateOf(false) }
    var allRatings by rememberSaveable(state.scopeKey, state.item.id) { mutableStateOf(false) }
    LazyColumn(state = scrollState, modifier = modifier.fillMaxSize().background(colors.canvas),
        contentPadding = PaddingValues(bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(MediaLayout.sectionGap)) {
        item(key = "hero:${state.item.id}") {
            Box(Modifier.fillMaxWidth()) {
                MediaHeroCanvas(state.item, imageLoader, Modifier.matchParentSize())
                Column(Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    MediaQuietAction("影视", onBack, Modifier.padding(horizontal = MediaLayout.pageInset)
                        .tvMediaReturnFocus(state.item.id, "detail-back"), icon = Art.drawable.fileway_ic_arrow_back)
                    Column(Modifier.padding(horizontal = MediaLayout.pageInset).widthIn(max = 520.dp).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        var titleOverflows by remember(state.item.title) { mutableStateOf(false) }
                        Text(state.item.title, style = MediaType.workTitle, color = colors.text,
                            maxLines = if (fullTitle) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis,
                            onTextLayout = { if (!fullTitle) titleOverflows = it.hasVisualOverflow })
                        if (titleOverflows || fullTitle) MediaQuietAction(if (fullTitle) "收起片名" else "完整片名",
                            { fullTitle = !fullTitle }, icon = Art.drawable.fileway_ic_expand_more)
                        state.item.originalTitle?.takeIf(String::isNotBlank)?.let {
                            Text(it, style = MediaType.secondary, color = colors.secondary)
                        }
                        if (state.item.metadataLabel.isNotBlank()) Text(state.item.metadataLabel,
                            style = MediaType.body, color = colors.text)
                        if (state.collections.isNotEmpty()) {
                            Text("合集", style = MediaType.sectionTitle, color = colors.text)
                            state.collections.forEach { label ->
                                Text(label, style = MediaType.body, color = colors.secondary)
                            }
                        }
                    }
                    val ratings = state.item.ratings.filter { it.valueLabel.isNotBlank() }
                    if (ratings.isNotEmpty()) Column(Modifier.padding(horizontal = MediaLayout.pageInset)
                        .widthIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            (if (allRatings) ratings else ratings.take(3)).forEach { rating ->
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(rating.source.label, Modifier.alignByBaseline(), style = MediaType.secondary, color = colors.secondary)
                                        Text(rating.valueLabel, Modifier.alignByBaseline(), style = MediaType.sectionTitle, color = colors.score)
                                    }
                                    rating.countLabel?.takeIf(String::isNotBlank)?.let {
                                        Text(it, style = MediaType.secondary, color = colors.secondary)
                                    }
                                }
                            }
                        }
                        if (ratings.size > 3) MediaQuietAction(if (allRatings) "收起评分" else "全部评分",
                            { allRatings = !allRatings }, icon = if (allRatings) Art.drawable.fileway_ic_expand_less else Art.drawable.fileway_ic_expand_more)
                    }
                    state.item.overview?.takeIf(String::isNotBlank)?.let { overview ->
                        Column(Modifier.padding(horizontal = MediaLayout.pageInset).widthIn(max = 520.dp).fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            var overflows by remember(overview) { mutableStateOf(false) }
                            Text(overview, style = MediaType.body, color = colors.text,
                                maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis,
                                onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow })
                            if (overflows || expanded) MediaQuietAction(if (expanded) "收起简介" else "展开简介",
                                { expanded = !expanded }, icon = if (expanded) Art.drawable.fileway_ic_expand_less else Art.drawable.fileway_ic_expand_more)
                        }
                    }
                    FlowRow(Modifier.fillMaxWidth().padding(horizontal = MediaLayout.pageInset, vertical = MediaLayout.focusGutter).focusGroup(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (onPlay != null && state.item.canPlay) key("play") {
                            MediaAction(state.playLabel, { focus?.onOpen?.invoke(state.item.id, "detail-play"); onPlay() },
                                Modifier.tvMediaReturnFocus(state.item.id, "detail-play"), primary = true, icon = Art.drawable.fileway_ic_play_arrow)
                        }
                        if (onSources != null) key("sources") {
                            MediaAction(if (state.sourceCount > 0) "片源 · ${state.sourceCount}" else "查看片源",
                                { focus?.onOpen?.invoke(state.item.id, "detail-sources"); onSources() },
                                Modifier.tvMediaReturnFocus(state.item.id, "detail-sources"), icon = Art.drawable.fileway_ic_expand_more)
                        }
                        if (onToggleFavorite != null) key("favorite") {
                            MediaQuietAction(if (state.favorite) "已收藏" else "收藏", onToggleFavorite,
                                selected = state.favorite, icon = Art.drawable.fileway_ic_bookmark)
                        }
                        if (onToggleWatched != null) key("watched") {
                            MediaQuietAction(if (state.watched) "已看" else "标记已看", onToggleWatched,
                                selected = state.watched, icon = Art.drawable.fileway_ic_visibility)
                        }
                        if (onPlayFromStart != null && state.item.canPlay) key("restart") {
                            MediaQuietAction("从头播放", onPlayFromStart, icon = Art.drawable.fileway_ic_restore)
                        }
                    }
                    listOfNotNull(state.item.availabilityLabel, state.sourceSummary).filter(String::isNotBlank).forEach { label ->
                        Text(label, Modifier.padding(horizontal = MediaLayout.pageInset).widthIn(max = 520.dp),
                            style = MediaType.secondary, color = colors.secondary)
                    }
                    if (state.error != null) MediaStatus(state.error, onRetry, compact = true)
                    if (state.loading) MediaLoadingStatus("正在更新影片资料…", compact = true)
                }
            }
        }
        if (state.seasons.isNotEmpty() || state.episodes.isNotEmpty() || state.episodesLoading || state.episodesError != null) item(key = "episodes") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MediaSectionTitle("选集", if (onMoreEpisodes != null) "更多剧集" else null, onMoreEpisodes)
                if (state.seasons.isNotEmpty()) LazyRow(Modifier.focusGroup(), contentPadding = MediaLayout.rowPadding,
                    horizontalArrangement = Arrangement.spacedBy(MediaLayout.itemGap)) {
                    items(state.seasons, key = { it.id }) { season ->
                        if (onSeason != null) Column {
                            MediaQuietAction(season.label, { onSeason(season.id) }, selected = state.selectedSeasonId == season.id)
                            Box(Modifier.align(Alignment.CenterHorizontally).width(28.dp).height(2.dp)
                                .background(if (state.selectedSeasonId == season.id) colors.accent else androidx.compose.ui.graphics.Color.Transparent))
                        } else Text(season.label, style = MediaType.body, color = colors.secondary)
                    }
                }
                if (state.episodesLoading) MediaLoadingStatus("正在加载剧集…", compact = true)
                if (state.episodesError != null) MediaStatus(state.episodesError, onRetryEpisodes, compact = true)
                if (!state.episodesLoading && state.episodesError == null && state.episodes.isEmpty()) MediaStatus("这一季尚未收录剧集。", compact = true)
                if (state.episodes.isNotEmpty()) BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val width = mediaCardWidth(maxWidth, 272.dp, 3)
                    LazyRow(Modifier.focusGroup(), contentPadding = MediaLayout.rowPadding,
                        horizontalArrangement = Arrangement.spacedBy(MediaLayout.itemGap)) {
                        items(state.episodes, key = { it.id }) { episode ->
                            MediaEpisodeCard(episode, imageLoader, onEpisode?.let { { it(episode.id) } },
                                onEpisodeOptions?.let { { it(episode.id) } }, width)
                        }
                    }
                }
            }
        }
        if (state.people.isNotEmpty()) item(key = "people") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MediaSectionTitle("演职人员", if (onAllPeople != null) "查看全部" else null, onAllPeople)
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val width = mediaCardWidth(maxWidth, 128.dp, 6)
                    LazyRow(Modifier.focusGroup(), contentPadding = MediaLayout.rowPadding,
                        horizontalArrangement = Arrangement.spacedBy(MediaLayout.itemGap)) {
                        items(state.people, key = { it.id }) { person ->
                            MediaPersonCard(person, imageLoader, onPerson?.let { { it(person.id) } }, width)
                        }
                    }
                }
            }
        }
        if (state.related.isNotEmpty() && onRelated != null) item(key = "related") {
            MediaShelf(TvMediaShelf("related", "相关作品", state.related), imageLoader, onRelated)
        }
    }
}

@Composable
private fun MediaPersonCard(person: TvMediaPerson, imageLoader: ImageLoader, onClick: (() -> Unit)?, width: androidx.compose.ui.unit.Dp) {
    val colors = LocalMediaColors.current
    val focus = LocalTvMediaFocusBinding.current
    Column(Modifier.width(width), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val imageModifier = Modifier.fillMaxWidth().aspectRatio(1f)
        if (onClick != null) MediaSurface({ focus?.onOpen?.invoke(person.id, "person"); onClick() },
            imageModifier.tvMediaReturnFocus(person.id, "person").semantics { contentDescription = "${person.name} ${person.roleLabel}" }) {
            MediaArtwork(person.portrait, imageLoader, Modifier.fillMaxSize(), Art.drawable.fileway_ic_person)
        } else MediaArtwork(person.portrait, imageLoader, imageModifier.clip(MediaLayout.shape), Art.drawable.fileway_ic_person)
        Text(person.name, style = MediaType.itemTitle, color = colors.text, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(person.roleLabel, style = MediaType.secondary, color = colors.secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun MediaEpisodeCard(episode: TvMediaEpisode, imageLoader: ImageLoader, onPlay: (() -> Unit)?, onOptions: (() -> Unit)?,
    width: androidx.compose.ui.unit.Dp) {
    val colors = LocalMediaColors.current
    val focus = LocalTvMediaFocusBinding.current
    Column(Modifier.width(width), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val imageModifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
        MediaSurface({ focus?.onOpen?.invoke(episode.id, "episode"); onPlay?.invoke() },
            imageModifier.tvMediaReturnFocus(episode.id, "episode").semantics { contentDescription = "${episode.numberLabel} ${episode.title}" },
            enabled = onPlay != null && episode.canPlay) {
            MediaArtwork(episode.still, imageLoader, Modifier.fillMaxSize())
            MediaProgress(episode.progress, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(7.dp))
        }
        Text("${episode.numberLabel} · ${episode.title}", style = MediaType.itemTitle, color = colors.text,
            minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(listOfNotNull(episode.extentLabel, episode.availabilityLabel).filter(String::isNotBlank).joinToString(" · "),
            style = MediaType.secondary, color = colors.secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (onOptions != null) MediaQuietAction("版本与资料", onOptions, icon = Art.drawable.fileway_ic_more_horiz)
    }
}
