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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import coil3.ImageLoader
import io.github.kkwans.fileway.tv.tvTextStyle
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
    var expanded by rememberSaveable(state.scopeKey, state.item.id) { mutableStateOf(false) }
    LazyColumn(state = scrollState, modifier = modifier.fillMaxSize().background(colors.canvas),
        contentPadding = PaddingValues(bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item(key = "hero:${state.item.id}") {
            Box(Modifier.fillMaxWidth()) {
                MediaBackdrop(state.item.backdrop, imageLoader, Modifier.matchParentSize())
                Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    MediaAction("影视", onBack, Modifier.padding(horizontal = 40.dp), icon = Art.drawable.fileway_ic_arrow_back)
                    Column(Modifier.fillMaxWidth(.76f).padding(start = 40.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(state.item.title, style = tvTextStyle(36, 46, FontWeight.Medium), color = colors.text)
                        state.item.originalTitle?.takeIf(String::isNotBlank)?.let {
                            Text(it, style = tvTextStyle(17, 25), color = colors.secondary)
                        }
                        if (state.item.metadataLabel.isNotBlank()) Text(state.item.metadataLabel,
                            style = tvTextStyle(18, 27), color = colors.text)
                    }
                    if (state.item.ratings.any { it.valueLabel.isNotBlank() }) LazyRow(
                        contentPadding = PaddingValues(horizontal = 40.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        items(state.item.ratings.filter { it.valueLabel.isNotBlank() }, key = { it.source.name }) { rating ->
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(rating.source.label, style = tvTextStyle(16, 23), color = colors.secondary)
                                Text(rating.valueLabel, style = tvTextStyle(26, 34, FontWeight.Medium), color = colors.score)
                                rating.countLabel?.takeIf(String::isNotBlank)?.let {
                                    Text(it, style = tvTextStyle(16, 23), color = colors.secondary)
                                }
                            }
                        }
                    }
                    state.item.overview?.takeIf(String::isNotBlank)?.let { overview ->
                        Column(Modifier.fillMaxWidth(.8f).padding(start = 40.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            var overflows by remember(overview) { mutableStateOf(false) }
                            Text(overview, style = tvTextStyle(18, 28), color = colors.text,
                                maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis,
                                onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow })
                            if (overflows || expanded) MediaAction(if (expanded) "收起简介" else "展开简介",
                                { expanded = !expanded }, icon = if (expanded) Art.drawable.fileway_ic_expand_less else Art.drawable.fileway_ic_expand_more)
                        }
                    }
                    LazyRow(contentPadding = PaddingValues(horizontal = 40.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.focusGroup()) {
                        if (onPlay != null && state.item.canPlay) item(key = "play") {
                            MediaAction(state.playLabel, onPlay, primary = true, icon = Art.drawable.fileway_ic_play_arrow)
                        }
                        if (onSources != null) item(key = "sources") {
                            MediaAction(if (state.sourceCount > 0) "片源 · ${state.sourceCount}" else "查看片源",
                                onSources, icon = Art.drawable.fileway_ic_expand_more)
                        }
                        if (onToggleFavorite != null) item(key = "favorite") {
                            MediaAction(if (state.favorite) "已收藏" else "收藏", onToggleFavorite,
                                selected = state.favorite, icon = Art.drawable.fileway_ic_bookmark)
                        }
                        if (onToggleWatched != null) item(key = "watched") {
                            MediaAction(if (state.watched) "已看" else "标记已看", onToggleWatched,
                                selected = state.watched, icon = Art.drawable.fileway_ic_visibility)
                        }
                        if (onPlayFromStart != null && state.item.canPlay) item(key = "restart") {
                            MediaAction("从头播放", onPlayFromStart, icon = Art.drawable.fileway_ic_restore)
                        }
                    }
                    listOfNotNull(state.item.availabilityLabel, state.sourceSummary).filter(String::isNotBlank).forEach { label ->
                        Text(label, Modifier.padding(horizontal = 40.dp), style = tvTextStyle(16, 24), color = colors.secondary)
                    }
                    if (state.error != null) MediaStatus(state.error, onRetry, compact = true)
                    if (state.loading) MediaStatus("正在更新影片资料…", compact = true)
                }
            }
        }
        if (state.seasons.isNotEmpty() || state.episodes.isNotEmpty() || state.episodesLoading || state.episodesError != null) item(key = "episodes") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MediaSectionTitle("选集", if (onMoreEpisodes != null) "更多剧集" else null, onMoreEpisodes)
                if (state.seasons.isNotEmpty()) LazyRow(Modifier.focusGroup(), contentPadding = PaddingValues(horizontal = 40.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(state.seasons, key = { it.id }) { season ->
                        if (onSeason != null) Column {
                            MediaAction(season.label, { onSeason(season.id) }, selected = state.selectedSeasonId == season.id)
                            Box(Modifier.align(Alignment.CenterHorizontally).width(28.dp).height(3.dp)
                                .background(if (state.selectedSeasonId == season.id) colors.accent else androidx.compose.ui.graphics.Color.Transparent))
                        } else Text(season.label, style = tvTextStyle(18, 26), color = colors.secondary)
                    }
                }
                if (state.episodesLoading) MediaStatus("正在加载剧集…", compact = true)
                if (state.episodesError != null) MediaStatus(state.episodesError, onRetryEpisodes, compact = true)
                if (!state.episodesLoading && state.episodesError == null && state.episodes.isEmpty()) MediaStatus("这一季尚未收录剧集。", compact = true)
                if (state.episodes.isNotEmpty()) LazyRow(Modifier.focusGroup(), contentPadding = PaddingValues(horizontal = 40.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    items(state.episodes, key = { it.id }) { episode ->
                        MediaEpisodeCard(episode, imageLoader, onEpisode?.let { { it(episode.id) } },
                            onEpisodeOptions?.let { { it(episode.id) } })
                    }
                }
            }
        }
        if (state.people.isNotEmpty()) item(key = "people") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MediaSectionTitle("演职人员", if (onAllPeople != null) "查看全部" else null, onAllPeople)
                LazyRow(Modifier.focusGroup(), contentPadding = PaddingValues(horizontal = 40.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(state.people, key = { it.id }) { person ->
                        MediaPersonCard(person, imageLoader, onPerson?.let { { it(person.id) } })
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
private fun MediaPersonCard(person: TvMediaPerson, imageLoader: ImageLoader, onClick: (() -> Unit)?) {
    val colors = LocalMediaColors.current
    Column(Modifier.width(136.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        val imageModifier = Modifier.fillMaxWidth().aspectRatio(1.16f)
        if (onClick != null) MediaSurface(onClick, imageModifier.semantics { contentDescription = person.name }) {
            MediaArtwork(person.portrait, imageLoader, Modifier.fillMaxSize(), Art.drawable.fileway_ic_person)
        } else MediaArtwork(person.portrait, imageLoader, imageModifier.clip(RoundedCornerShape(10.dp)), Art.drawable.fileway_ic_person)
        Text(person.name, style = tvTextStyle(18, 25, FontWeight.Medium), color = colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(person.roleLabel, style = tvTextStyle(16, 23), color = colors.secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun MediaEpisodeCard(episode: TvMediaEpisode, imageLoader: ImageLoader, onPlay: (() -> Unit)?, onOptions: (() -> Unit)?) {
    val colors = LocalMediaColors.current
    Column(Modifier.width(220.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        val imageModifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
        MediaSurface(onPlay ?: {}, imageModifier.semantics { contentDescription = "${episode.numberLabel} ${episode.title}" },
            enabled = onPlay != null && episode.canPlay) {
            MediaArtwork(episode.still, imageLoader, Modifier.fillMaxSize())
            MediaProgress(episode.progress, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(7.dp))
        }
        Text("${episode.numberLabel} · ${episode.title}", style = tvTextStyle(18, 25, FontWeight.Medium), color = colors.text,
            minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(listOfNotNull(episode.extentLabel, episode.availabilityLabel).filter(String::isNotBlank).joinToString(" · "),
            style = tvTextStyle(16, 23), color = colors.secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (onOptions != null) MediaAction("版本与资料", onOptions, icon = Art.drawable.fileway_ic_more_horiz)
    }
}
