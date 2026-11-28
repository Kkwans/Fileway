package io.github.kkwans.nasfilebrowser.ui.medialibrary

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.data.MediaTechnicalFormatter
import io.github.kkwans.nasfilebrowser.ui.FilewayIcon
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayMenuItem
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayMediaTechnicalPanel
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayMediaTechnicalPanelStyle
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayTheme
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayTopAppBar
import io.github.kkwans.fileway.design.R as Art
import kotlinx.coroutines.launch

/** Insets and account-scoped data belong to the route; only local presentation lives here. */
internal fun phoneMediaDetailShowsPlaybackBar(canSelectSources: Boolean, canHandoff: Boolean): Boolean =
    canSelectSources || canHandoff

@Composable
internal fun PhoneMediaDetail(state: PhoneMediaDetailState, imageLoader: ImageLoader,
    onAction: (PhoneMediaAction) -> Unit, modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState()) {
    key(state.scopeKey, state.id) {
        var expanded by rememberSaveable { mutableStateOf(false) }
        var fullTitle by rememberSaveable { mutableStateOf(false) }
        var compact by rememberSaveable { mutableStateOf(false) }
        var menu by remember { mutableStateOf(false) }
        val creditsRowState = rememberLazyListState()
        val seasonRowState = rememberLazyListState()
        val scope = rememberCoroutineScope()
        val hasEpisodeSection = state.seasons.isNotEmpty() || state.seasonsHasMore || state.seasonsLoading || state.seasonsProblem != null ||
            state.episodes.entries.isNotEmpty() || state.episodes.hasMore || state.episodes.loading || state.episodes.problem != null
        val currentOnAction by rememberUpdatedState(onAction)
        LaunchedEffect(creditsRowState, state.credits.hasMore, state.credits.loading, state.credits.problem) {
            if (!state.credits.hasMore || state.credits.loading || state.credits.problem != null) return@LaunchedEffect
            if (state.credits.entries.isEmpty()) {
                currentOnAction(PhoneMediaAction.LoadMore("credits"))
                return@LaunchedEffect
            }
            snapshotFlow {
                (creditsRowState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) to creditsRowState.layoutInfo.totalItemsCount
            }.collect { (lastVisible, itemCount) ->
                if (phoneMediaShouldLoadMore(lastVisible, itemCount, hasMore = true, loading = false, problem = false)) {
                    currentOnAction(PhoneMediaAction.LoadMore("credits"))
                }
            }
        }
        LaunchedEffect(seasonRowState, state.seasonsHasMore, state.seasonsLoading, state.seasonsProblem) {
            if (!state.seasonsHasMore || state.seasonsLoading || state.seasonsProblem != null) return@LaunchedEffect
            if (state.seasons.isEmpty()) {
                currentOnAction(PhoneMediaAction.LoadMore("seasons"))
                return@LaunchedEffect
            }
            snapshotFlow {
                (seasonRowState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) to seasonRowState.layoutInfo.totalItemsCount
            }.collect { (lastVisible, itemCount) ->
                if (phoneMediaShouldLoadMore(lastVisible, itemCount, hasMore = true, loading = false, problem = false)) {
                    currentOnAction(PhoneMediaAction.LoadMore("seasons"))
                }
            }
        }
        LaunchedEffect(listState, state.episodes.hasMore, state.episodes.loading, state.episodes.problem) {
            if (!state.episodes.hasMore || state.episodes.loading || state.episodes.problem != null) return@LaunchedEffect
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.any { it.key == "episodes-paging" } }.collect { visible ->
                if (visible) currentOnAction(PhoneMediaAction.LoadMore("episodes"))
            }
        }
        val background = MaterialTheme.colorScheme.background
        BoxWithConstraints(modifier.fillMaxSize().background(background), contentAlignment = Alignment.TopCenter) {
            val contentWidth = maxWidth.coerceAtMost(640.dp)
            val inset = if (maxWidth < 600.dp) 16.dp else 24.dp
            val density = LocalDensity.current
            val episodeColumns = (contentWidth / (72.dp * density.fontScale.coerceAtLeast(1f))).toInt().coerceIn(1, 6)
            val heroHeight = if (state.backdrop != null) contentWidth * 9f / 16f
                else if (state.poster != null) 240.dp else 104.dp
            val collapseOffset = with(density) { (heroHeight - 64.dp).roundToPx() }
            val collapsed by remember(listState, collapseOffset) { derivedStateOf {
                listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > collapseOffset
            } }
            val showPlaybackBar = phoneMediaDetailShowsPlaybackBar(state.canSelectSources, state.canHandoff)
            Column(Modifier.widthIn(max = 640.dp).fillMaxSize()) {
            LazyColumn(Modifier.weight(1f), state = listState,
                contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                item(key = "hero") {
                    DetailHero(state, imageLoader, heroHeight, inset, fullTitle, { fullTitle = !fullTitle })
                }
                if (state.loading) item(key = "loading") {
                    PhoneDetailLoading("正在加载影视详情", Modifier.padding(horizontal = inset))
                }
                state.problem?.let { problem -> item(key = "problem") {
                    MediaProblemCard(problem, { onAction(PhoneMediaAction.Retry("detail")) }, Modifier.padding(horizontal = inset))
                } }
                val ratings = phoneMediaDetailRatings(state.ratings)
                if (ratings.isNotEmpty()) item(key = "ratings") {
                    Column(Modifier.padding(horizontal = inset), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        PhoneDetailHeading("评分")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            ratings.forEach { rating -> RatingCard(rating, Modifier.widthIn(min = 140.dp, max = 220.dp)) }
                        }
                    }
                }
                state.playProblem?.let { problem -> item(key = "play-problem") {
                    MediaProblemCard(problem, { onAction(PhoneMediaAction.Retry("playback")) }, Modifier.padding(horizontal = inset))
                } }
                if (state.canSelectSources) item(key = "sources") {
                    DetailSourceRow(state, onAction, Modifier.padding(horizontal = inset))
                }
                if (hasEpisodeSection) item(key = "episode-shortcut") {
                    PhoneDetailAction(
                        state.episodeSummary?.takeIf(String::isNotBlank)?.let { "选集 · $it" } ?: "选集",
                        Art.drawable.fileway_ic_playlist_play,
                        { scope.launch {
                            repeat(12) {
                                if (listState.layoutInfo.visibleItemsInfo.any { item -> item.key == "seasons" }) return@launch
                                listState.animateScrollBy(with(density) { 600.dp.toPx() })
                            }
                        } },
                        Modifier.padding(horizontal = inset),
                    )
                }
                if (state.canChangeUserState) item(key = "user-state") {
                    Column(Modifier.padding(horizontal = inset), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            PhoneDetailAction(if (state.favorite) "已收藏" else "收藏", Art.drawable.fileway_ic_bookmark,
                                { onAction(PhoneMediaAction.Favorite(state.id, !state.favorite, state.userStateRevision)) },
                                Modifier.semantics { selected = state.favorite }, enabled = !state.userStateBusy)
                            PhoneDetailAction(if (state.watched) "已看过" else "标记已看", Art.drawable.fileway_ic_check,
                                { onAction(PhoneMediaAction.Watched(state.id, !state.watched, state.userStateRevision)) },
                                Modifier.semantics { selected = state.watched }, enabled = !state.userStateBusy)
                        }
                        if (state.userStateBusy) PhoneDetailLoading("正在更新观看状态")
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
                state.userStateProblem?.let { issue -> item(key = "user-state-recovery") {
                    MediaProblemCard(issue, { onAction(PhoneMediaAction.Retry("user-state")) }, Modifier.padding(horizontal = inset))
                } }
                if (state.collections.isNotEmpty()) item(key = "collections") {
                    Column(Modifier.padding(horizontal = inset), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PhoneDetailHeading("合集")
                        state.collections.forEach { label ->
                            Text(label, style = PhoneDetailType.body, color = MaterialTheme.colorScheme.onBackground)
                        }
                    }
                }
                item(key = "overview") {
                    Column(Modifier.padding(horizontal = inset), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        PhoneDetailHeading("剧情简介")
                        val overview = state.overview?.takeIf(String::isNotBlank)
                        var overflows by remember(overview) { mutableStateOf(false) }
                        Text(overview ?: "暂无简介", style = PhoneDetailType.body,
                            color = if (overview == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onBackground,
                            maxLines = if (expanded) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis,
                            onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow })
                        if (overflows || expanded) PhoneDetailAction(if (expanded) "收起简介" else "展开简介",
                            if (expanded) Art.drawable.fileway_ic_expand_less else Art.drawable.fileway_ic_expand_more,
                            { expanded = !expanded })
                    }
                }
                item(key = "credits") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        PhoneDetailHeading("演职人员", Modifier.padding(horizontal = inset)) {
                            if (state.credits.entries.isNotEmpty()) PhoneDetailAction("全部", Art.drawable.fileway_ic_arrow_forward,
                                { onAction(PhoneMediaAction.AllCredits(state.id)) })
                        }
                        state.credits.problem?.let {
                            MediaProblemCard(it, { onAction(PhoneMediaAction.Retry("credits")) }, Modifier.padding(horizontal = inset))
                        }
                        if (state.credits.loading && state.credits.entries.isEmpty()) {
                            PhoneDetailLoading("正在加载演职人员", Modifier.padding(horizontal = inset))
                        }
                        if (state.credits.entries.isEmpty() && !state.credits.loading && state.credits.problem == null && !state.credits.hasMore) {
                            Text("暂无演职员资料", Modifier.padding(horizontal = inset), style = PhoneDetailType.metadata,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        LazyRow(state = creditsRowState, contentPadding = PaddingValues(horizontal = inset), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(state.credits.entries, key = { it.id }) { credit -> CreditColumn(credit, imageLoader) {
                                onAction(PhoneMediaAction.OpenPerson(credit.personId))
                            } }
                        }
                    }
                }
                if (hasEpisodeSection) {
                    item(key = "seasons") {
                        Column(Modifier.padding(horizontal = inset), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            PhoneDetailHeading("剧集") {
                                PhoneDetailAction(if (compact) "卡片视图" else "序号视图", Art.drawable.fileway_ic_more_horiz,
                                    { compact = !compact })
                            }
                            state.episodeSummary?.takeIf(String::isNotBlank)?.let {
                                Text(it, style = PhoneDetailType.metadata, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            LazyRow(state = seasonRowState, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(state.seasons, key = { it.id }) { season ->
                                    FilterChip(state.selectedSeason == season.id,
                                        { onAction(PhoneMediaAction.SelectSeason(season.id)) },
                                        label = { Text(season.label, style = PhoneDetailType.action) },
                                        shape = MaterialTheme.shapes.medium, modifier = Modifier.heightIn(min = 48.dp))
                                }
                            }
                            state.seasonsProblem?.let { MediaProblemCard(it, { onAction(PhoneMediaAction.Retry("seasons")) }) }
                            DetailPaging("seasons", state.seasonsLoading, state.seasonsHasMore, onAction)
                        }
                    }
                    state.episodes.problem?.let { problem -> item(key = "episodes-error") {
                        MediaProblemCard(problem, { onAction(PhoneMediaAction.Retry("episodes")) }, Modifier.padding(horizontal = inset))
                    } }
                    if (compact) {
                        items(state.episodes.entries.chunked(episodeColumns), key = { "episode-row/${it.first().id}" }) { row ->
                            Row(Modifier.padding(horizontal = inset), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { episode ->
                                    OutlinedButton(onClick = { onAction(PhoneMediaAction.Play(episode.id, kind = MediaTargetKind.EPISODE)) },
                                        enabled = episode.playable && !state.busy,
                                        modifier = Modifier.weight(1f).heightIn(min = 56.dp).semantics {
                                            contentDescription = listOfNotNull(episode.numberLabel, episode.title, episode.unavailableReason).joinToString("，")
                                        }, shape = MaterialTheme.shapes.medium,
                                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 12.dp)) {
                                        Text(episode.numberLabel, style = PhoneDetailType.action)
                                    }
                                }
                                repeat(episodeColumns - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                        // Disabled numbers retain an adjacent readable reason and source-details action.
                        state.episodes.entries.filter { !it.playable }.forEach { episode ->
                            item(key = "episode-unavailable/${episode.id}") {
                                EpisodeRow(episode, imageLoader, onAction, state.busy, inset)
                            }
                        }
                    } else items(state.episodes.entries, key = { "episode/${it.id}" }) { episode ->
                        EpisodeRow(episode, imageLoader, onAction, state.busy, inset)
                    }
                    if (state.episodes.entries.isEmpty() && !state.episodes.loading && state.episodes.problem == null && !state.episodes.hasMore) item(key = "episodes-empty") {
                        Text("本季尚未收录单集", Modifier.padding(horizontal = inset),
                            style = PhoneDetailType.body, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    item(key = "episodes-paging") {
                        DetailPaging("episodes", state.episodes.loading, state.episodes.hasMore, onAction, Modifier.padding(horizontal = inset))
                    }
                }
                state.technical?.let { technical ->
                    val badges = MediaTechnicalFormatter.badges(technical)
                    val lines = MediaTechnicalFormatter.lines(technical)
                    if (badges.isNotEmpty() || lines.isNotEmpty()) item(key = "technical") {
                        FilewayMediaTechnicalPanel(
                            title = "技术信息", badges = badges, lines = lines,
                            modifier = Modifier.padding(horizontal = inset),
                            style = FilewayMediaTechnicalPanelStyle(
                                titleStyle = PhoneDetailType.section,
                                badgeStyle = PhoneDetailType.metadata,
                                lineStyle = PhoneDetailType.body,
                                titleColor = MaterialTheme.colorScheme.onBackground,
                                badgeColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                badgeBackground = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .38f),
                                lineColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
            }
            if (showPlaybackBar) Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.background,
                tonalElevation = 3.dp,
            ) {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    DetailPlayback(state, onAction, Modifier.padding(horizontal = inset, vertical = 8.dp), showProblem = false)
                }
            }
            }
            DetailAppBar(state, collapsed, menu, { menu = it }, onAction,
                Modifier.widthIn(max = 640.dp).fillMaxWidth(), overArtwork = state.backdrop != null && !collapsed)
        }
    }
}

@Composable
private fun DetailHero(state: PhoneMediaDetailState, imageLoader: ImageLoader, heroHeight: Dp, inset: Dp,
    fullTitle: Boolean, onFullTitle: () -> Unit) {
    val canvas = MaterialTheme.colorScheme.background
    Box(Modifier.fillMaxWidth()) {
        if (state.backdrop != null) Box(Modifier.fillMaxWidth().height(heroHeight)) {
            MediaImage(state.backdrop, imageLoader, Modifier.fillMaxSize())
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(
                0f to Color.Black.copy(alpha = .38f), .28f to Color.Transparent,
                .55f to canvas.copy(alpha = .12f), .8f to canvas.copy(alpha = .92f), 1f to canvas)))
        } else if (state.poster != null) Box(Modifier.fillMaxWidth().height(heroHeight)) {
            MediaImage(state.poster, imageLoader, Modifier.align(Alignment.Center).padding(top = 64.dp, bottom = 32.dp)
                .width(96.dp).aspectRatio(2f / 3f))
        }
        Column(Modifier.fillMaxWidth().padding(top = heroHeight - 32.dp).padding(horizontal = inset),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            var overflows by remember(state.title) { mutableStateOf(false) }
            Text(state.title, style = PhoneDetailType.title, color = MaterialTheme.colorScheme.onBackground,
                maxLines = if (fullTitle) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() }, onTextLayout = { if (!fullTitle) overflows = it.hasVisualOverflow })
            if (overflows || fullTitle) PhoneDetailAction(if (fullTitle) "收起片名" else "完整片名",
                if (fullTitle) Art.drawable.fileway_ic_expand_less else Art.drawable.fileway_ic_expand_more, onFullTitle)
            state.originalTitle?.takeIf { it.isNotBlank() && it != state.title }?.let {
                Text(it, style = PhoneDetailType.metadata, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.metadata.isNotBlank()) Text(state.metadata, style = PhoneDetailType.metadata,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DetailAppBar(state: PhoneMediaDetailState, collapsed: Boolean, menu: Boolean, onMenu: (Boolean) -> Unit,
    onAction: (PhoneMediaAction) -> Unit, modifier: Modifier, overArtwork: Boolean) {
    Box(modifier) {
        if (collapsed) FilewayTopAppBar(state.title, leadingIcon = Art.drawable.fileway_ic_arrow_back, leadingDescription = "返回",
            onLeadingClick = { onAction(PhoneMediaAction.Back) }, actions = { DetailMoreMenu(state, menu, onMenu, onAction) })
        else Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            val ink = if (overArtwork) Color.White else MaterialTheme.colorScheme.onBackground
            CompositionLocalProvider(LocalContentColor provides ink) {
                IconButton({ onAction(PhoneMediaAction.Back) }, Modifier.size(48.dp)) {
                    FilewayIcon(Art.drawable.fileway_ic_arrow_back, "返回", Modifier.size(24.dp), tint = ink)
                }
                Spacer(Modifier.weight(1f))
                DetailMoreMenu(state, menu, onMenu, onAction)
            }
        }
    }
}

@Composable
private fun DetailMoreMenu(state: PhoneMediaDetailState, menu: Boolean, onMenu: (Boolean) -> Unit,
    onAction: (PhoneMediaAction) -> Unit) {
    if (state.canReviewMatch) Box {
        IconButton({ onMenu(true) }, Modifier.size(48.dp)) {
            FilewayIcon(Art.drawable.fileway_ic_more_vert, "更多操作", Modifier.size(24.dp), tint = LocalContentColor.current)
        }
        DropdownMenu(menu, { onMenu(false) }) {
            FilewayMenuItem("纠正识别", { onMenu(false); onAction(PhoneMediaAction.ReviewMatch(state.id)) },
                icon = R.drawable.ic_edit, enabled = !state.busy)
        }
    }
}

@Composable
private fun DetailPlayback(state: PhoneMediaDetailState, onAction: (PhoneMediaAction) -> Unit, modifier: Modifier,
    showProblem: Boolean = true) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val stack = maxWidth < 340.dp || LocalDensity.current.fontScale > 1.3f
            val play: @Composable (Modifier) -> Unit = { actionModifier ->
                Button({ onAction(PhoneMediaAction.Play(state.id)) }, actionModifier.heightIn(min = 52.dp),
                    enabled = state.canPlay && !state.busy, shape = MaterialTheme.shapes.medium,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
                    FilewayIcon(Art.drawable.fileway_ic_play_arrow, null, Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp)); Text(if (state.busy) "正在准备…" else state.playLabel, style = PhoneDetailType.item)
                }
            }
            val handoff: @Composable (Modifier) -> Unit = { actionModifier ->
                FilledTonalButton({ onAction(PhoneMediaAction.Handoff(state.id)) }, actionModifier.heightIn(min = 52.dp),
                    enabled = !state.busy, shape = MaterialTheme.shapes.medium,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
                    FilewayIcon(Art.drawable.fileway_ic_cast, null, Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp)); Text("在 TV 播放", style = PhoneDetailType.action)
                }
            }
            if (stack) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.canSelectSources) play(Modifier.fillMaxWidth())
                if (state.canHandoff) handoff(Modifier.fillMaxWidth())
            } else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.canSelectSources) play(Modifier.weight(1f))
                if (state.canHandoff) handoff(Modifier.weight(1f))
            }
        }
        if (state.canSelectSources && state.playLabel != "播放") PhoneDetailAction("从头播放", Art.drawable.fileway_ic_restore,
            { onAction(PhoneMediaAction.Play(state.id, fromStart = true)) }, enabled = state.canPlay && !state.busy)
        if (showProblem) state.playProblem?.let { MediaProblemCard(it, { onAction(PhoneMediaAction.Retry("playback")) }) }
        if (!state.canPlay && state.playProblem == null && !state.loading) Text("暂无可播放片源",
            style = PhoneDetailType.metadata, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DetailSourceRow(state: PhoneMediaDetailState, onAction: (PhoneMediaAction) -> Unit, modifier: Modifier) {
    Column(modifier) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Surface(onClick = { onAction(PhoneMediaAction.Sources(state.id)) }, enabled = !state.busy,
            shape = MaterialTheme.shapes.medium, color = Color.Transparent) {
            Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(state.sourceCount?.let { "片源与版本 · $it" } ?: "片源与版本", style = PhoneDetailType.item)
                    Text(state.sourceSummary?.takeIf(String::isNotBlank) ?: "查看可用片源与版本",
                        style = PhoneDetailType.metadata, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FilewayIcon(Art.drawable.fileway_ic_arrow_forward, null, Modifier.size(24.dp))
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun RatingCard(rating: MediaRating, modifier: Modifier = Modifier) {
    Surface(modifier, shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(rating.source, style = PhoneDetailType.action, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    rating.metric?.takeIf(String::isNotBlank)?.let {
                        Text(it, style = PhoneDetailType.metadata, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Text(rating.detailValue, style = PhoneMediaType.workTitle,
                    color = FilewayTheme.status.warning, maxLines = 1)
            }
            listOfNotNull(rating.count, rating.scope).filter(String::isNotBlank).forEach {
                Text(it, style = PhoneDetailType.metadata, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun CreditColumn(credit: MediaCredit, loader: ImageLoader, onClick: () -> Unit) {
    val width = 96.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
    CreditCard(credit, loader, onClick, Modifier.width(width))
}

@Composable
private fun CreditCard(credit: MediaCredit, loader: ImageLoader, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(onClick = onClick, modifier = modifier, shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .38f)) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MediaImage(credit.portrait, loader, Modifier.fillMaxWidth().aspectRatio(1f).clip(MaterialTheme.shapes.small), person = true)
            Text(credit.name, style = PhoneDetailType.item, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(credit.role, style = PhoneDetailType.metadata, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun PhoneMediaCredits(title: String, credits: List<MediaCredit>, loading: Boolean, problem: MediaProblem?,
    hasMore: Boolean, imageLoader: ImageLoader, onBack: () -> Unit, onAction: (PhoneMediaAction) -> Unit, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val pageInset = phoneMediaGridSpec(maxWidth).pageInset
        val gridState = rememberLazyGridState()
        PhoneMediaGridLoadMore(gridState, hasMore, loading, problem != null) {
            onAction(PhoneMediaAction.LoadMore("credits"))
        }
        Column(Modifier.fillMaxSize()) {
            FilewayTopAppBar("演职人员", leadingIcon = Art.drawable.fileway_ic_arrow_back, leadingDescription = "返回", onLeadingClick = onBack)
            LazyVerticalGrid(columns = GridCells.Adaptive(minSize = 136.dp), state = gridState, modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = pageInset, end = pageInset, bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item(key = "title", span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(title, style = PhoneMediaType.pageTitle)
                        Text("已加载 ${credits.size} 位演职人员", style = PhoneMediaType.secondary, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(credits, key = { "credit/${it.personId}/${it.role}" }) { credit ->
                    CreditCard(credit, imageLoader, { onAction(PhoneMediaAction.OpenPerson(credit.personId)) }, Modifier.fillMaxWidth())
                }
                if (loading) item(key = "loading", span = { GridItemSpan(maxLineSpan) }) {
                    PhoneDetailLoading("正在加载演职人员")
                }
                if (problem != null) item(key = "problem", span = { GridItemSpan(maxLineSpan) }) {
                    MediaProblemCard(problem, { onAction(PhoneMediaAction.Retry("credits")) })
                }
                if (hasMore && !loading) item(key = "more", span = { GridItemSpan(maxLineSpan) }) {
                    TextButton({ onAction(PhoneMediaAction.LoadMore("credits")) }, Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        shape = MaterialTheme.shapes.medium) { Text("加载更多", style = PhoneDetailType.action) }
                }
                if (credits.isEmpty() && !loading && problem == null && !hasMore) item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                    Text("暂无演职员资料", style = PhoneDetailType.metadata, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

internal fun phoneMediaEpisodeImage(artwork: MediaArtwork?, backdrop: MediaArtwork?): MediaArtwork? = artwork ?: backdrop

private val mediaEpisodeFilename = Regex("""(?i)(?:^|[._\s-])(?:S\d{1,2})?EP?(\d{1,3})(?=$|[._\s-])""")

internal fun phoneMediaEpisodeTitle(numberLabel: String, title: String): String =
    if (title.isBlank() || mediaEpisodeFilename.containsMatchIn(title)) numberLabel else "$numberLabel · $title"

@Composable
private fun EpisodeRow(episode: MediaEpisode, loader: ImageLoader, onAction: (PhoneMediaAction) -> Unit, busy: Boolean, inset: Dp) {
    var menu by remember(episode.id) { mutableStateOf(false) }
    Surface(onClick = { onAction(if (episode.playable) PhoneMediaAction.Play(episode.id, kind = MediaTargetKind.EPISODE)
        else PhoneMediaAction.Sources(episode.id, MediaTargetKind.EPISODE)) }, enabled = !busy,
        modifier = Modifier.padding(horizontal = inset).fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = Color.Transparent) {
        if (episode.image == null) {
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(48.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.medium),
                        contentAlignment = Alignment.Center) {
                        FilewayIcon(Art.drawable.fileway_ic_play_arrow, null, Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(phoneMediaEpisodeTitle(episode.numberLabel, episode.title), style = PhoneDetailType.item,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (episode.unavailableReason != null || episode.subtitle.isNotBlank()) {
                            Text(episode.unavailableReason ?: episode.subtitle, style = PhoneDetailType.metadata,
                                color = if (episode.unavailableReason != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Box {
                        MediaIconAction(Art.drawable.fileway_ic_more_vert, "${episode.numberLabel}的更多操作", { menu = true }, enabled = !busy)
                        DropdownMenu(menu, { menu = false }) {
                            FilewayMenuItem("查看剧集详情", { menu = false; onAction(PhoneMediaAction.OpenItem(episode.id)) })
                            FilewayMenuItem("片源与版本", { menu = false; onAction(PhoneMediaAction.Sources(episode.id, MediaTargetKind.EPISODE)) })
                        }
                    }
                }
                MediaProgress(episode.progress, Modifier.fillMaxWidth())
            }
            return@Surface
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val stack = maxWidth < 300.dp || LocalDensity.current.fontScale > 1.3f
            val artwork: @Composable (Modifier) -> Unit = { imageModifier ->
                Box(imageModifier.clip(MaterialTheme.shapes.medium)) {
                    MediaImage(episode.image, loader, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                    MediaProgress(episode.progress, Modifier.align(Alignment.BottomCenter))
                }
            }
            val information: @Composable (Modifier) -> Unit = { infoModifier ->
                Row(infoModifier, verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(phoneMediaEpisodeTitle(episode.numberLabel, episode.title), style = PhoneDetailType.item,
                            maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Text(episode.unavailableReason ?: episode.subtitle, style = PhoneDetailType.metadata,
                            color = if (episode.unavailableReason != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Box {
                        MediaIconAction(Art.drawable.fileway_ic_more_vert, "${episode.numberLabel}的更多操作", { menu = true }, enabled = !busy)
                        DropdownMenu(menu, { menu = false }) {
                            FilewayMenuItem("查看剧集详情", { menu = false; onAction(PhoneMediaAction.OpenItem(episode.id)) })
                            FilewayMenuItem("片源与版本", { menu = false; onAction(PhoneMediaAction.Sources(episode.id, MediaTargetKind.EPISODE)) })
                        }
                    }
                }
            }
            if (stack) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                artwork(Modifier.fillMaxWidth()); information(Modifier.fillMaxWidth())
            } else Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                artwork(Modifier.width(120.dp)); information(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun DetailPaging(section: String, loading: Boolean, hasMore: Boolean,
    onAction: (PhoneMediaAction) -> Unit, modifier: Modifier = Modifier) {
    if (loading) PhoneDetailLoading("正在加载", modifier)
    else if (hasMore) TextButton({ onAction(PhoneMediaAction.LoadMore(section)) }, modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text("加载更多", style = PhoneDetailType.action)
    }
}
