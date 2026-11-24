package io.github.kkwans.fileway.tv.medialibrary

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import coil3.ImageLoader
import coil3.fetch.Fetcher
import coil3.fetch.FetchResult
import coil3.fetch.ImageFetchResult
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.request.SuccessResult
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.kkwans.fileway.tv.LocalTvInteractive
import io.github.kkwans.fileway.tv.TvRemoteDialogKeys
import io.github.kkwans.fileway.tv.tvTextStyle
import io.github.kkwans.nasfilebrowser.app.SessionContext
import io.github.kkwans.nasfilebrowser.data.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.LinkedHashMap
import java.util.Locale

private val TvHomeFocusOrigins = setOf("poster", "hero", "hero-play", "continue")

@Composable internal fun TvMediaRoute(
    session: SessionContext,
    repository: MediaLibraryRepository,
    imageLoader: ImageLoader,
    onExit: () -> Unit,
    onPlayback: (MediaCatalogVariant, Boolean, MediaCatalogContinue?) -> Unit,
) {
    val interactive = LocalTvInteractive.current
    val health by session.api.sessionHealth.collectAsState()
    if (session.api.identity.id != repository.scope.account.userId ||
        health?.code == SessionHealthCode.ACCOUNT_CHANGED || health?.code == SessionHealthCode.EXPIRED) {
        TvMediaTheme {
            Column(Modifier.fillMaxSize()) {
                MediaStatus("登录状态已变化，请重新进入影视中心。")
                MediaAction("返回文件", onExit)
            }
        }
        BackHandler(enabled = interactive) { onExit() }
        return
    }
    key(session.owner, session.generation, session.api.identity.deviceSessionId) {
        val scope = rememberCoroutineScope()
        val route = remember(session, repository, scope) { TvMediaRouteState(session, repository, scope) }
        val watch = remember(repository, scope) { ContinueWatching(repository, scope) }
        val watching by watch.state.collectAsState()
        val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
        val context = LocalContext.current
        val art = remember(session, repository, context) { TvCatalogArtworkRequests(session, repository, context) }
        val holder = rememberSaveableStateHolder()
        val homeScroll = rememberLazyListState()
        val detailScroll = rememberLazyListState()
        var filter by rememberSaveable { mutableStateOf("all") }
        var library by rememberSaveable { mutableStateOf<String?>(null) }
        var item by rememberSaveable { mutableStateOf<String?>(null) }
        var season by rememberSaveable { mutableStateOf<String?>(null) }
        var homeAnchor by rememberSaveable { mutableStateOf<String?>(null) }
        var homeOrigin by rememberSaveable { mutableStateOf<String?>(null) }
        var homeAnchorIndex by remember { mutableIntStateOf(-1) }
        var lastAnchor by remember { mutableStateOf<String?>(null) }
        var lastOrigin by remember { mutableStateOf<String?>(null) }
        var restoreId by remember { mutableStateOf<String?>(null) }
        var restoreOrigin by remember { mutableStateOf<String?>(null) }
        var awaitingPlayer by remember { mutableStateOf(false) }
        var playerWasShown by remember { mutableStateOf(false) }
        var initialFocusRequested by remember { mutableStateOf(false) }
        var focusedDetail by remember { mutableStateOf<String?>(null) }
        var startFromBeginning by remember { mutableStateOf(false) }
        var watchMenu by remember { mutableStateOf<MediaCatalogContinue?>(null) }
        var searching by rememberSaveable { mutableStateOf(false) }
        var personPage by remember(route) { mutableStateOf<MediaCatalogPersonRef?>(null) }
        val libraryId = library
        val itemId = item
        val homeFocusIds = (route.home?.hero?.items.orEmpty().map { tvMediaKey(it.ref) } +
            watching.items.map { it.id } + route.catalog.map { tvMediaKey(it.ref) }).distinct()
        fun restore(id: String?, origin: String?) { restoreId = id; restoreOrigin = origin }
        fun restoreHome(id: String?, origin: String?) {
            val target = resolveTvMediaFocusTarget(id, origin, homeFocusIds, homeAnchorIndex)
            restore(target?.id, target?.origin)
        }
        DisposableEffect(route, watch) { onDispose { route.close(); watch.close() } }
        LaunchedEffect(route) { route.start(filter) }
        LaunchedEffect(route.home) { route.home?.let(watch::seed) ?: watch.clear() }
        LaunchedEffect(route, route.home?.state, interactive, lifecycle) {
            if (interactive && lifecycle.isAtLeast(Lifecycle.State.RESUMED) && route.home?.state == MediaCatalogHomeState.SCANNING) {
                while (isActive) {
                    delay(5_000)
                    if (!route.homeRefreshing) route.refreshHome()
                }
            }
        }
        LaunchedEffect(route, libraryId, itemId, route.supported) {
            if (route.supported && libraryId != null && itemId != null) {
                route.openDetail(repository.catalogRef(libraryId, itemId), season)
            } else route.closeDetail()
        }
        LaunchedEffect(route.selectedSeason) { route.selectedSeason?.let { season = it } }
        LaunchedEffect(interactive, itemId, libraryId, route.supported) {
            if (!interactive) return@LaunchedEffect
            if (itemId == null || !route.supported) {
                if (itemId == null) focusedDetail = null
                if (!initialFocusRequested) {
                    initialFocusRequested = true
                    if (searching && route.supported) { restoreId = "search-field"; restoreOrigin = "search" }
                    else { restoreId = "files"; restoreOrigin = "navigation" }
                }
            } else if (route.supported && libraryId != null) {
                val id = "$libraryId/$itemId"
                if (focusedDetail != id) {
                    initialFocusRequested = true; focusedDetail = id; restoreId = id; restoreOrigin = "detail-back"
                }
            }
        }
        LaunchedEffect(interactive, awaitingPlayer) {
            if (awaitingPlayer && !interactive) playerWasShown = true
            if (awaitingPlayer && interactive && playerWasShown) {
                watch.load()
                if (lastOrigin != null && lastOrigin in TvHomeFocusOrigins) restoreHome(lastAnchor, lastOrigin) else restore(lastAnchor, lastOrigin)
                awaitingPlayer = false; playerWasShown = false
            }
        }
        fun closeSources() { route.closeSources(); restore(lastAnchor, lastOrigin) }
        fun openSources(ref: MediaCatalogRef, fromStart: Boolean = false) {
            startFromBeginning = fromStart
            route.openSources(ref)
        }
        fun playContinue(entry: MediaCatalogContinue, fromStart: Boolean) {
            if (watching.items.none { it === entry } || watching.removing == entry.id) return
            watchMenu = null
            awaitingPlayer = true
            playerWasShown = false
            onPlayback(entry.variant, fromStart, entry)
        }
        fun closePersonPage() {
            val target = resolveTvPersonSearchFocusTarget(lastAnchor, lastOrigin,
                route.people.map { "person:${it.ref.libraryId}/${it.ref.personId}" })
            route.closePerson(); personPage = null
            restore(target.id, target.origin)
        }
        fun closeSearch() { closePersonPage(); searching = false; restoreHome(homeAnchor, homeOrigin) }
        fun back() {
            when {
                route.sourceRef != null -> closeSources()
                item != null -> {
                    item = null; library = null; season = null
                    if (personPage != null) restore("person-back", "search-person")
                    else if (searching) restore("search-field", "search") else restoreHome(homeAnchor, homeOrigin)
                }
                personPage != null -> closePersonPage()
                searching -> closeSearch()
                else -> onExit()
            }
        }
        BackHandler(enabled = interactive) { back() }
        val focus = TvMediaFocusBinding(restoreId, restoreOrigin, { id, origin ->
            lastAnchor = id; lastOrigin = origin
            if (origin in TvHomeFocusOrigins) {
                homeAnchor = id; homeOrigin = origin; homeAnchorIndex = homeFocusIds.indexOf(id)
            }
        }, { restoreId = null; restoreOrigin = null })

        TvMediaTheme {
            CompositionLocalProvider(LocalTvMediaFocusBinding provides focus) {
                if (itemId == null || !route.supported) {
                    if (searching && personPage != null && route.supported) {
                        val selected = personPage
                        val person = route.people.firstOrNull { it.ref == selected }
                        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            TvMediaPersonWorksHeader(route.personName.ifBlank { person?.name.orEmpty() }, person?.roles.orEmpty(), ::closePersonPage)
                            when {
                                route.personWorksLoading -> MediaLoadingStatus("正在加载人物作品")
                                route.personWorksError != null -> MediaStatus(route.personWorksError!!, { route.loadPersonWorks(true) })
                                route.personWorks.isEmpty() -> MediaStatus("暂无可见作品，该人物可能没有当前账号可访问的影视。")
                                else -> MediaShelf(TvMediaShelf("person-works", "作品", route.personWorks.map { tvCard(it, art) }), imageLoader,
                                    onItem = { id ->
                                        route.personWorks.firstOrNull { tvMediaKey(it.ref) == id }?.let {
                                            library = it.ref.libraryId; item = it.ref.itemId; season = null
                                        }
                                    })
                            }
                            if (route.personWorksCursor != null && !route.personWorksLoading) {
                                MediaAction("加载更多作品", { route.loadPersonWorks(false) }, modifier = Modifier.padding(horizontal = MediaLayout.pageInset))
                            }
                        }
                    } else if (searching && route.supported) {
                        TvMediaSearchScreen(route.searchQuery, route.searchedQuery.isNotBlank(), route.searchMode,
                            route.searchResults.map { tvCard(it, art) }, route.searchLoading, route.searchError, route.searchCursor != null,
                            route.people, route.peopleLoading, route.peopleError, route.peopleCursor != null, imageLoader,
                            onQuery = route::editSearch, onSubmit = { route.submitSearch(route.searchQuery) }, onMode = route::chooseSearchMode,
                            onBack = ::closeSearch, onItem = { id ->
                                (route.searchResults + route.personWorks + route.catalog + route.home?.hero?.items.orEmpty()).firstOrNull { tvMediaKey(it.ref) == id }?.let {
                                    library = it.ref.libraryId; item = it.ref.itemId; season = null
                                }
                            }, onPerson = { libraryId, personId ->
                                route.people.firstOrNull { it.ref.libraryId == libraryId && it.ref.personId == personId }?.let {
                                    personPage = it.ref; route.openPerson(it.ref, it.name); restore("person-back", "search-person")
                                }
                            }, onRetry = route::retrySearch, onMore = {
                                if (route.searchMode == TvMediaSearchMode.WORKS) route.loadSearch(false) else route.loadPeople(false)
                            })
                    } else {
                    val featured = route.home?.hero?.items.orEmpty()
                    val entries = route.catalog.map { tvCard(it, art) }
                    val title = when (route.filter) { "movie" -> "电影"; "series" -> "剧集"; "video" -> "视频"; else -> "全部影视" }
                    val homeError = route.homeError ?: when (route.home?.state) {
                        MediaCatalogHomeState.SOURCE_OFFLINE -> "来源暂时离线，仍可查看已收录的资料。"
                        MediaCatalogHomeState.RESTRICTED -> "当前账号没有可见的影视内容。"
                        MediaCatalogHomeState.SERVICE_UNAVAILABLE -> "影视目录暂不可用。"
                        MediaCatalogHomeState.PARTIAL -> "部分内容尚未读取完成。"
                        MediaCatalogHomeState.SCANNING -> "正在扫描媒体库，已发现的内容会逐步显示。"
                        else -> null
                    }
                    holder.SaveableStateProvider("tv-media-home") {
                        TvMediaHome(TvMediaHomeState(session.owner,
                            featured = featured.map { tvCard(it, art) },
                            continuing = watching.items.map {
                                TvMediaContinueItem(it.id, tvCard(it.card, art), remainingLabel = it.progressLabel(),
                                    progress = it.progress, removing = watching.removing == it.id)
                            },
                            continueLoading = watching.loading, continueError = watching.error, continueHasMore = watching.hasMore,
                            shelves = if (route.supported && route.home?.state != MediaCatalogHomeState.NO_LIBRARIES)
                                listOf(TvMediaShelf("catalog", title, entries, route.catalogLoading, route.catalogError)) else emptyList(),
                            loading = route.checking, error = homeError,
                            emptyTitle = if (route.home?.state == MediaCatalogHomeState.NO_LIBRARIES) "还没有媒体库" else "片库还没有内容",
                            emptyMessage = "请在手机端添加来源并完成扫描。"),
                            imageLoader, onItem = { id ->
                                (route.catalog + featured).firstOrNull { tvMediaKey(it.ref) == id }?.let {
                                    library = it.ref.libraryId; item = it.ref.itemId; season = null
                                }
                            }, onContinue = { id -> watching.items.firstOrNull { it.id == id }?.let { playContinue(it, false) } },
                            onContinueOptions = { id -> watchMenu = watching.items.firstOrNull { it.id == id } },
                            onRetryContinue = watch::retry, onMoreContinue = { watch.load(reset = false) }, onPlay = { id ->
                                (route.catalog + featured).firstOrNull { tvMediaKey(it.ref) == id }?.let { route.openSources(it.ref) }
                            }, onRetry = route::refreshHome, onRetryShelf = { route.retryCatalog() },
                            autoRotate = false, interactionPaused = !interactive, scrollState = homeScroll,
                            navigation = {
                                val navigation = listOf(TvMediaNavigationItem("files", "文件")) +
                                    if (route.supported && route.home?.state != MediaCatalogHomeState.NO_LIBRARIES)
                                        listOf(TvMediaNavigationItem("all", "全部"), TvMediaNavigationItem("movie", "电影"),
                                            TvMediaNavigationItem("series", "剧集"), TvMediaNavigationItem("video", "视频"))
                                    else emptyList()
                                TvMediaTopBar(navigation, route.filter, { id ->
                                    if (id == "files") onExit() else { filter = id; route.selectFilter(id) }
                                }, onSearch = if (route.home?.capabilities?.get("search") == true) ({
                                    searching = true; homeAnchor = "search"; homeOrigin = "navigation"
                                }) else null)
                                if (route.catalogCursor != null) Row(Modifier.fillMaxWidth().padding(horizontal = 40.dp)) {
                                    MediaAction(if (route.catalogLoading) "正在加载…" else "加载更多影视",
                                        { route.loadCatalog(false) }, enabled = !route.catalogLoading)
                                }
                            })
                    }
                    }
                } else {
                    val result = route.detail
                    val card = result?.card
                    val detail = result?.detail
                    val ratings = detail?.ratings?.items.orEmpty().mapNotNull { observation ->
                        val value = observation.rating
                        value.value?.let { score -> tvRating(value.provider ?: value.rawSource, value.metric, score, value.scale) }
                    }
                    val reference = if (libraryId != null) repository.catalogRef(libraryId, itemId) else null
                    val playable = card?.actions?.can(MediaCatalogAction.PLAY) == true
                    Column(Modifier.fillMaxSize()) {
                        if (route.seasonsError != null) MediaStatus(route.seasonsError!!, { route.loadSeasons(true) }, compact = true)
                        if (route.seasonsCursor != null) Row(Modifier.fillMaxWidth().padding(horizontal = 40.dp)) {
                            MediaAction(if (route.seasonsLoading) "正在加载季…" else "加载更多季",
                                { route.loadSeasons(false) }, enabled = !route.seasonsLoading)
                        }
                        TvMediaDetail(TvMediaDetailState(session.owner,
                            item = if (card != null) tvCard(card, art).copy(
                                overview = detail?.fields?.get("plot")?.values?.firstOrNull(),
                                ratings = ratings.ifEmpty { listOfNotNull(tvRating(card.primaryRating)) })
                            else TvMediaCard("$libraryId/$itemId", detail?.item?.title ?: "影视详情"),
                            people = detail?.credits?.items.orEmpty().map { TvMediaPerson(it.id, it.name, it.role.ifBlank { it.kind }) },
                            seasons = route.seasons.map { TvMediaSeason(it.ref.itemId, it.label) },
                            selectedSeasonId = route.selectedSeason,
                            episodes = route.episodes.map { episode -> TvMediaEpisode(
                                tvMediaKey(episode.ref), episode.card.title, "第 ${episode.episodeNumber} 集",
                                still = art.forArtwork(episode.card.artwork), extentLabel = episode.playback?.label,
                                availabilityLabel = tvAvailability(episode.card.availability),
                                canPlay = episode.card.actions.can(MediaCatalogAction.PLAY)) },
                            episodesLoading = route.episodesLoading, episodesError = route.episodesError,
                            loading = route.detailLoading, error = route.detailError), imageLoader, onBack = { back() },
                            modifier = Modifier.weight(1f),
                            onPlay = if (playable && reference != null) ({ openSources(reference) }) else null,
                            onPlayFromStart = if (playable && reference != null) ({ openSources(reference, fromStart = true) }) else null,
                            onSources = if (playable && reference != null) ({ openSources(reference) }) else null,
                            onSeason = { route.selectSeason(it) },
                            onEpisode = { id -> route.episodes.firstOrNull { tvMediaKey(it.ref) == id }?.let { openSources(it.ref) } },
                            onMoreEpisodes = if (route.episodesCursor != null && !route.episodesLoading) ({ route.loadEpisodes(false) }) else null,
                            onRetry = route::loadDetail, onRetryEpisodes = { route.loadEpisodes(true) }, scrollState = detailScroll)
                    }
                }
                watchMenu?.takeIf { entry -> interactive && watching.items.any { it === entry } }?.let { entry ->
                    fun dismiss() { watchMenu = null; restore(entry.id, "continue") }
                    TvContinueActions(entry, ::dismiss, onDetails = {
                        watchMenu = null
                        library = entry.card.ref.libraryId; item = entry.card.ref.itemId; season = null
                    }, onStartOver = { playContinue(entry, true) }, onRemove = {
                        dismiss()
                        watch.remove(entry)
                    })
                }
                val source = route.sourceRef
                if (source != null && interactive) {
                    val editions = route.variants.groupBy { it.editionId }.map { (id, values) ->
                        TvMediaEdition(id, values.first().editionName.ifBlank { "原始版本" }, sources = values.map { value ->
                            TvMediaSource(tvVariantKey(value), value.sourceLabel, value.resolution,
                                tvSize(value.size), value.displayName, available = true)
                        })
                    }
                    TvMediaSourcePicker(TvMediaSourcePickerState(session.owner, tvMediaKey(source), "选择片源", editions,
                        loading = route.sourcesLoading, error = route.sourcesError), onDismiss = ::closeSources,
                        onConfirm = { edition, sourceId ->
                            route.variants.firstOrNull { it.editionId == edition && tvVariantKey(it) == sourceId }?.let {
                                route.closeSources(); awaitingPlayer = true; playerWasShown = false; onPlayback(it, startFromBeginning, null)
                            }
                        }, onRetry = if (route.sourcesError != null) ({ route.loadSources(true) }) else null,
                        onMore = if (route.sourcesCursor != null) ({ route.loadSources(false) }) else null)
                }
            }
        }
    }
}

@Composable
private fun TvContinueActions(entry: MediaCatalogContinue, onDismiss: () -> Unit,
    onDetails: () -> Unit, onStartOver: () -> Unit, onRemove: () -> Unit) {
    val focus = remember(entry.id) { FocusRequester() }
    val colors = LocalMediaColors.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(dismissOnClickOutside = false)) {
        TvRemoteDialogKeys(onDismiss)
        LaunchedEffect(entry.id) { withFrameNanos { }; focus.requestFocus() }
        Column(Modifier.fillMaxWidth().background(colors.canvas, RoundedCornerShape(12.dp)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(entry.card.title, style = tvTextStyle(20, 28), color = colors.text, maxLines = 2)
            MediaAction("查看详情", onDetails, Modifier.fillMaxWidth().focusRequester(focus))
            if (entry.card.actions.can(MediaCatalogAction.START_OVER))
                MediaAction("从头播放", onStartOver, Modifier.fillMaxWidth())
            if (entry.card.actions.can(MediaCatalogAction.REMOVE_FROM_CONTINUE))
                MediaAction("移出继续观看", onRemove, Modifier.fillMaxWidth())
        }
    }
}

private fun tvCard(card: MediaCatalogCard, art: TvCatalogArtworkRequests): TvMediaCard = TvMediaCard(
    id = tvMediaKey(card.ref), title = card.title, poster = art.forArtwork(card.artwork),
    backdrop = art.forArtwork(card.backdrop), originalTitle = card.originalTitle, yearLabel = card.year?.toString(),
    extentLabel = card.episodes?.takeIf { it.state == MediaCatalogCountState.READY }?.collected?.let { "已收录 $it 集" }
        ?: card.duration?.seconds?.let { if (it < 60) "${it.toInt()} 秒" else "${(it / 60).toInt()} 分钟" },
    ratings = listOfNotNull(tvRating(card.primaryRating)), availabilityLabel = tvAvailability(card.availability),
    canPlay = card.actions.can(MediaCatalogAction.PLAY),
)

private fun tvAvailability(value: MediaCatalogAvailability): String? = when (value) {
    MediaCatalogAvailability.AVAILABLE -> null
    MediaCatalogAvailability.OFFLINE -> "来源离线"
    MediaCatalogAvailability.MISSING -> "文件缺失"
    MediaCatalogAvailability.CHECKING -> "正在确认来源"
    MediaCatalogAvailability.UNKNOWN -> "来源状态未知"
}

private fun tvRating(value: MediaCatalogRating?): TvMediaRating? {
    value ?: return null
    return tvRating(value.provider, value.metric, value.value, value.scale)
}

private fun tvRating(provider: String, metric: MediaRatingMetric, value: Double, scale: Double?): TvMediaRating? {
    val source = when (provider.lowercase(Locale.ROOT)) {
        "douban" -> TvMediaRatingSource.DOUBAN
        "imdb" -> TvMediaRatingSource.IMDB
        "tmdb" -> TvMediaRatingSource.TMDB
        "rottentomatoes", "rotten_tomatoes" -> when (metric) {
            MediaRatingMetric.CRITIC_APPROVAL, MediaRatingMetric.CRITIC_AVERAGE -> TvMediaRatingSource.ROTTEN_TOMATOES_CRITICS
            MediaRatingMetric.AUDIENCE_APPROVAL -> TvMediaRatingSource.ROTTEN_TOMATOES_AUDIENCE
            else -> return null
        }
        else -> return null
    }
    fun number(value: Double) = if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
    val label = if (metric in setOf(MediaRatingMetric.CRITIC_APPROVAL, MediaRatingMetric.AUDIENCE_APPROVAL) && scale != null && scale > 0)
        "${kotlin.math.round(value / scale * 100).toInt()}%"
    else listOfNotNull(number(value), scale?.let(::number)).joinToString(" / ")
    return TvMediaRating(source, label)
}

private fun tvSize(bytes: Long): String = if (bytes < 1024) "$bytes B" else if (bytes < 1024 * 1024)
    "${bytes / 1024} KiB" else "${bytes / (1024 * 1024)} MiB"

/** Request data carries no URL or credential; a lease exists only during the visible image load. */
private class TvCatalogArtworkData(val session: SessionContext, val repository: MediaLibraryRepository, val artwork: MediaCatalogArtwork) {
    override fun toString(): String = "TvCatalogArtworkData(redacted)"
}

private class TvCatalogArtworkRequests(
    private val session: SessionContext, private val repository: MediaLibraryRepository, private val context: android.content.Context,
) {
    private val requests = object : LinkedHashMap<String, TvMediaArtwork>(96, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TvMediaArtwork>?): Boolean = size > 96
    }
    fun forArtwork(artwork: MediaCatalogArtwork?): TvMediaArtwork? {
        artwork ?: return null
        val key = "${artwork.ref.libraryId}/${artwork.ref.itemId}/${artwork.assetId}/${artwork.revision}"
        return requests.getOrPut(key) {
            TvMediaArtwork(ImageRequest.Builder(context).data(TvCatalogArtworkData(session, repository, artwork))
                .fetcherFactory(TvCatalogArtworkFetcher).memoryCachePolicy(CachePolicy.DISABLED)
                .diskCachePolicy(CachePolicy.DISABLED).build())
        }
    }
}

private object TvCatalogArtworkFetcher : Fetcher.Factory<TvCatalogArtworkData> {
    override fun create(data: TvCatalogArtworkData, options: Options, imageLoader: ImageLoader): Fetcher = object : Fetcher {
        override suspend fun fetch(): FetchResult {
            var lease: PreviewLease? = null
            try {
                lease = data.session.api.assetLease(data.repository.artworkEndpoint(data.artwork))
                val request = ImageRequest.Builder(options.context).data(lease.url).size(options.size)
                    .memoryCachePolicy(CachePolicy.DISABLED).diskCachePolicy(CachePolicy.DISABLED).build()
                val result = imageLoader.execute(request)
                data.repository.artworkEndpoint(data.artwork)
                return if (result is SuccessResult) ImageFetchResult(result.image, result.isSampled, result.dataSource)
                else throw IllegalStateException("图片暂不可用")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { throw IllegalStateException("图片暂不可用") }
            finally { withContext(NonCancellable) { runCatching { lease?.release() } } }
        }
    }
}
