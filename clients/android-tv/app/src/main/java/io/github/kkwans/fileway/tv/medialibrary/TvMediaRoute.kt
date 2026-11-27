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
    currentSourceId: () -> String?,
    onSettings: (() -> Unit)? = null,
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
        val sourceJourney = remember(route) { TvMediaSourceJourney() }
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
        var personReturnItem by remember(route) { mutableStateOf<String?>(null) }
        var personReturnLibrary by remember(route) { mutableStateOf<String?>(null) }
        var personReturnSeason by remember(route) { mutableStateOf<String?>(null) }
        var creditsPage by remember(route) { mutableStateOf(false) }
        var collectionPage by remember(route) { mutableStateOf<MediaCatalogCollectionRef?>(null) }
        val libraryId = library
        val itemId = item
        val homeFocusIds = (route.home?.hero?.items.orEmpty().map { tvMediaKey(it.ref) } +
            watching.items.map { it.id } + route.homeRows.flatMap { it.items }.map { tvMediaKey(it.ref) } +
            route.catalog.map { tvMediaKey(it.ref) }).distinct()
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
    LaunchedEffect(route.jumpedEpisodeId) { route.jumpedEpisodeId?.let { restore(it, "episode") } }
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
        fun openSources(ref: MediaCatalogRef, intent: TvMediaSourceIntent, fromStart: Boolean = false) {
            startFromBeginning = fromStart
            sourceJourney.open(tvMediaKey(ref), intent)
            route.openSources(ref)
        }
        fun play(ref: MediaCatalogRef, fromStart: Boolean = false) {
            val selectedId = sourceJourney.pendingSourceId(tvMediaKey(ref))
            val selected = if (route.hasSourcesFor(ref)) route.variants.firstOrNull {
                it.ref == ref && tvVariantKey(it) == selectedId && it.available
            } else null
            if (selected != null) {
                awaitingPlayer = true
                playerWasShown = false
                onPlayback(selected, fromStart, null)
            } else openSources(ref, TvMediaSourceIntent.PLAY, fromStart)
        }
        fun openDetailPerson(personId: String, name: String) {
            libraryId?.let { targetLibrary ->
                personReturnItem = itemId; personReturnLibrary = targetLibrary; personReturnSeason = season
                val ref = MediaCatalogPersonRef(targetLibrary, personId)
                personPage = ref; route.openPerson(ref, name); searching = true; creditsPage = false
                item = null; library = null; season = null
            }
        }
        fun playContinue(entry: MediaCatalogContinue, fromStart: Boolean) {
            if (watching.items.none { it === entry } || watching.removing == entry.id) return
            watchMenu = null
            awaitingPlayer = true
            playerWasShown = false
            onPlayback(entry.variant, fromStart, entry)
        }
        fun closePersonPage() {
            val target = resolveTvCatalogSearchFocusTarget(lastAnchor, lastOrigin,
                route.people.map { "person:${it.ref.libraryId}/${it.ref.personId}" })
            route.closePerson(); personPage = null
            if (personReturnItem != null && personReturnLibrary != null) {
                val returnItem = personReturnItem
                searching = false
                item = personReturnItem; library = personReturnLibrary; season = personReturnSeason
                personReturnItem = null; personReturnLibrary = null; personReturnSeason = null
                restore(returnItem.orEmpty(), "detail-back")
            } else {
                searching = true
                restore(target.id, target.origin)
            }
        }
        fun closeCollectionPage() {
            val target = resolveTvCatalogSearchFocusTarget(lastAnchor, lastOrigin,
                route.collections.map { "collection:${it.ref.collectionId}" })
            route.closeCollection(); collectionPage = null
            restore(target.id, target.origin)
        }
        fun closeSearch() {
            if (personPage != null) closePersonPage()
            if (collectionPage != null) closeCollectionPage()
            searching = false; restoreHome(homeAnchor, homeOrigin)
        }
        fun back() {
            when {
                creditsPage -> { creditsPage = false; route.closeCredits(); restore(itemId, "detail-back") }
                route.sourceRef != null -> closeSources()
                item != null -> {
                    item = null; library = null; season = null
                    if (collectionPage != null) restore("collection-back", "search-collection")
                    else if (personPage != null) restore("person-back", "search-person")
                    else if (searching) {
                        val target = resolveTvCatalogSearchFocusTarget(lastAnchor, lastOrigin,
                            route.files.map { "file:${it.fileId}" } + route.searchResults.map { tvMediaKey(it.ref) } +
                                route.collectionWorks.map { tvMediaKey(it.ref) } + route.personWorks.map { tvMediaKey(it.ref) })
                        restore(target.id, target.origin)
                    } else restoreHome(homeAnchor, homeOrigin)
                }
                collectionPage != null -> closeCollectionPage()
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
                    if (searching && collectionPage != null && route.supported) {
                        TvMediaWorksPage(
                            header = { TvMediaCollectionWorksHeader(route.collectionName, ::closeCollectionPage) },
                            cards = route.collectionWorks.map { tvCard(it, art) },
                            imageLoader = imageLoader,
                            loading = route.collectionWorksLoading,
                            error = route.collectionWorksError,
                            emptyMessage = "暂无可见作品，该合集可能没有当前账号可访问的影视。",
                            hasMore = route.collectionWorksCursor != null,
                            onItem = { id ->
                                route.collectionWorks.firstOrNull { tvMediaKey(it.ref) == id }?.let {
                                    library = it.ref.libraryId; item = it.ref.itemId; season = null
                                }
                            },
                            onRetry = { route.loadCollectionWorks(true) },
                            onMore = { route.loadCollectionWorks(false) },
                        )
                    } else if (searching && personPage != null && route.supported) {
                        val selected = personPage
                        val person = route.people.firstOrNull { it.ref == selected }
                        TvMediaWorksPage(
                            header = { TvMediaPersonWorksHeader(route.personName.ifBlank { person?.name.orEmpty() }, person?.roles.orEmpty(), ::closePersonPage) },
                            cards = route.personWorks.map { tvCard(it, art) },
                            imageLoader = imageLoader,
                            loading = route.personWorksLoading,
                            error = route.personWorksError,
                            emptyMessage = "暂无可见作品，该人物可能没有当前账号可访问的影视。",
                            hasMore = route.personWorksCursor != null,
                            onItem = { id ->
                                route.personWorks.firstOrNull { tvMediaKey(it.ref) == id }?.let {
                                    library = it.ref.libraryId; item = it.ref.itemId; season = null
                                }
                            },
                            onRetry = { route.loadPersonWorks(true) },
                            onMore = { route.loadPersonWorks(false) },
                        )
                    } else if (searching && route.supported) {
                        TvMediaSearchScreen(route.searchQuery, route.searchedQuery.isNotBlank(), route.searchMode,
                            route.searchResults.map { tvCard(it, art) }, route.searchLoading, route.searchError, route.searchCursor != null,
                            route.people, route.peopleLoading, route.peopleError, route.peopleCursor != null,
                            route.files.map { TvMediaFileResult(it.fileId, it.fileName, tvCard(it.card, art)) },
                            route.filesLoading, route.filesError, route.filesCursor != null,
                            route.collections.map { TvMediaCollectionResult(it.ref.libraryId, it.ref.collectionId, it.name, tvCard(it.preview.first(), art)) },
                            route.collectionsLoading, route.collectionsError, route.collectionsCursor != null,
                            route.home?.capabilities?.get("fileSearch") == true,
                            route.home?.capabilities?.get("collections") == true, imageLoader,
                            onQuery = route::editSearch, onSubmit = { route.submitSearch(route.searchQuery) }, onMode = route::chooseSearchMode,
                            onBack = ::closeSearch, onItem = { id ->
                                (route.searchResults + route.files.map { it.card } + route.collectionWorks + route.personWorks + route.catalog + route.home?.hero?.items.orEmpty()).firstOrNull { tvMediaKey(it.ref) == id }?.let {
                                    library = it.ref.libraryId; item = it.ref.itemId; season = null
                                }
                            }, onPerson = { libraryId, personId ->
                                route.people.firstOrNull { it.ref.libraryId == libraryId && it.ref.personId == personId }?.let {
                                    personPage = it.ref; route.openPerson(it.ref, it.name); restore("person-back", "search-person")
                                }
                            }, onCollection = { libraryId, collectionId ->
                                route.collections.firstOrNull { it.ref.libraryId == libraryId && it.ref.collectionId == collectionId }?.let {
                                    collectionPage = it.ref; route.openCollection(it.ref, it.name); restore("collection-back", "search-collection")
                                }
                            }, onRetry = route::retrySearch, onMore = {
                                when (route.searchMode) {
                                    TvMediaSearchMode.WORKS -> route.loadSearch(false)
                                    TvMediaSearchMode.PEOPLE -> route.loadPeople(false)
                                    TvMediaSearchMode.FILES -> route.loadFiles(false)
                                    TvMediaSearchMode.COLLECTIONS -> route.loadCollections(false)
                                }
                            })
                    } else {
                    val featured = route.home?.hero?.items.orEmpty()
                    val rowCards = route.homeRows.flatMap { it.items }
                    val entries = route.catalog.map { tvCard(it, art) }
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
                            rows = route.homeRows.map { row -> TvMediaHomeRowState(row.rowId,
                                items = row.items.map { tvCard(it, art) }, cursor = row.cursor, hasMore = row.hasMore,
                                loading = row.loading, error = row.error, retryAppend = row.retryAppend) },
                            shelves = if (route.supported && route.home?.state != MediaCatalogHomeState.NO_LIBRARIES)
                                listOf(TvMediaShelf("catalog", "我的片库", entries, route.catalogLoading, route.catalogError,
                                    hasMore = route.catalogCursor != null)) else emptyList(),
                            categories = if (route.supported && route.home?.state != MediaCatalogHomeState.NO_LIBRARIES)
                                listOf(TvMediaCategory("all", "全部"), TvMediaCategory("movie", "电影"),
                                    TvMediaCategory("series", "剧集"), TvMediaCategory("video", "视频")) else emptyList(),
                            selectedCategory = route.filter,
                            loading = route.checking, error = homeError,
                            emptyTitle = if (route.home?.state == MediaCatalogHomeState.NO_LIBRARIES) "还没有媒体库" else "片库还没有内容",
                            emptyMessage = "请在手机端添加来源并完成扫描。"),
                            imageLoader, onItem = { id ->
                                (rowCards + route.catalog + featured).firstOrNull { tvMediaKey(it.ref) == id }?.let {
                                    library = it.ref.libraryId; item = it.ref.itemId; season = null
                                }
                            }, onContinue = { id -> watching.items.firstOrNull { it.id == id }?.let { playContinue(it, false) } },
                            onContinueOptions = { id -> watchMenu = watching.items.firstOrNull { it.id == id } },
                            onRetryContinue = watch::retry, onMoreContinue = { watch.load(reset = false) }, onPlay = { id ->
                                (route.catalog + featured).firstOrNull { tvMediaKey(it.ref) == id }?.let { play(it.ref) }
                            }, onRetry = route::refreshHome, onRetryShelf = { route.retryCatalog() },
                            onMoreShelf = { route.loadCatalog(false) },
                            onRetryRow = route::retryHomeRow, onMoreRow = { route.loadHomeRow(it, false) },
                            onCategory = { value ->
                                val target = resolveTvCatalogCategoryFocusTarget(value,
                                    listOf("all", "movie", "series", "video"))
                                restore(target.id, target.origin)
                                filter = value; route.selectFilter(value)
                            },
                            autoRotate = false, interactionPaused = !interactive, scrollState = homeScroll,
                            navigation = {
                                val navigation = listOf(TvMediaNavigationItem("media", "影视"), TvMediaNavigationItem("files", "文件")) +
                                    if (route.home?.capabilities?.get("collections") == true)
                                        listOf(TvMediaNavigationItem("collections", "合集")) else emptyList()
                                TvMediaTopBar(navigation, "media", { id ->
                                    when (id) {
                                        "files" -> onExit()
                                        "collections" -> {
                                            route.editSearch(""); route.chooseSearchMode(TvMediaSearchMode.COLLECTIONS)
                                            searching = true; homeAnchor = "search"; homeOrigin = "navigation"
                                        }
                                    }
                                }, onSearch = if (route.home?.capabilities?.get("search") == true) ({
                                    route.chooseSearchMode(TvMediaSearchMode.WORKS)
                                    searching = true; homeAnchor = "search"; homeOrigin = "navigation"
                                }) else null, onSettings = onSettings)
                            })
                    }
                    }
                } else {
                    val result = route.detail
                    val card = result?.card
                    val detail = result?.detail
                    val ratings = detail?.ratings?.items.orEmpty().mapNotNull { observation ->
                        val value = observation.rating
                        value.value?.let { score -> tvRating(value.provider ?: value.rawSource, value.metric, score, value.scale,
                            value.count, value.countPrecision) }
                    }
                    val reference = if (libraryId != null) repository.catalogRef(libraryId, itemId) else null
                    val playable = card?.actions?.can(MediaCatalogAction.PLAY) == true
                    val canBrowseSources = reference != null && card != null && tvCanBrowseSources(card.structureKind)
                    val userState = card?.userState
                    val detailSources = if (reference != null && route.hasSourcesFor(reference)) route.variants else emptyList()
                    val pendingSource = reference?.let { value ->
                        sourceJourney.pendingSourceId(tvMediaKey(value))?.let { id ->
                            detailSources.firstOrNull { tvVariantKey(it) == id && it.available }
                        }
                    }
                    Column(Modifier.fillMaxSize()) {
                        if (creditsPage) {
                            TvMediaCredits(card?.title ?: detail?.item?.title.orEmpty(), route.credits.map { credit ->
                                TvMediaPerson(credit.personId, credit.name, credit.role.ifBlank { credit.roles.joinToString(" · ") })
                            }, route.creditsLoading, route.creditsError, route.creditsCursor != null, imageLoader,
                                onBack = ::back, onPerson = { personId ->
                                    val name = route.credits.firstOrNull { it.personId == personId }?.name.orEmpty()
                                    openDetailPerson(personId, name)
                                }, onRetry = { route.loadCredits(true) }, onMore = { route.loadCredits(false) },
                                modifier = Modifier.weight(1f))
                        } else {
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
                            people = detail?.credits?.items.orEmpty().map { TvMediaPerson(it.personId, it.name, it.role.ifBlank { it.kind }) },
                            collections = detail?.collections?.items.orEmpty().map { collection ->
                                listOfNotNull(collection.name, collection.number?.let { "编号 $it" }).joinToString(" · ")
                            },
                            seasons = route.seasons.map { TvMediaSeason(it.ref.itemId, it.label) },
                            selectedSeasonId = route.selectedSeason,
                            episodes = route.episodes.map { episode -> TvMediaEpisode(
                                tvMediaKey(episode.ref), episode.card.title, "第 ${episode.episodeNumber} 集",
                                still = art.forArtwork(episode.card.artwork), extentLabel = episode.playback?.label,
                                availabilityLabel = tvAvailability(episode.card.availability),
                                canPlay = episode.card.actions.can(MediaCatalogAction.PLAY)) },
                            episodesLoading = route.episodesLoading, episodesError = route.episodesError,
                            favorite = userState?.favorite == true, watched = userState?.manualWatched == true,
                            userStateBusy = route.userStateBusy, userStateError = route.userStateError,
                            sourceCount = detailSources.size,
                            sourceSummary = listOfNotNull(
                                tvSourceSummary(detailSources),
                                pendingSource?.let { "待播放片源：${it.sourceLabel}" },
                                if (route.sourcesLoading && reference != null && route.hasSourcesFor(reference)) "正在读取片源…" else null,
                            ).joinToString(" · ").takeIf(String::isNotBlank),
                            technical = route.technical,
                            loading = route.detailLoading, error = route.detailError), imageLoader, onBack = { back() },
                            modifier = Modifier.weight(1f),
                            onPlay = if (playable && reference != null) ({ play(reference) }) else null,
                            onPlayFromStart = if (playable && reference != null) ({ play(reference, fromStart = true) }) else null,
                            onSources = if (reference != null && canBrowseSources) ({ openSources(reference, TvMediaSourceIntent.BROWSE) }) else null,
                            onToggleFavorite = if (reference != null && !route.userStatePending && card?.actions?.can(MediaCatalogAction.FAVORITE) == true) ({
                                route.changeUserState(tvMediaKey(reference), userState?.revision, favorite = userState?.favorite != true)
                            }) else null,
                            onToggleWatched = if (reference != null && !route.userStatePending && card?.actions?.can(MediaCatalogAction.MARK_WATCHED) == true) ({
                                route.changeUserState(tvMediaKey(reference), userState?.revision, watched = userState?.manualWatched != true)
                            }) else null,
                            onPerson = { personId ->
                                val name = detail?.credits?.items?.firstOrNull { it.personId == personId }?.name.orEmpty()
                                openDetailPerson(personId, name)
                            },
                            onAllPeople = { creditsPage = true; route.openCredits() },
                            onSeason = { route.selectSeason(it) },
                            onEpisode = { id -> route.episodes.firstOrNull { tvMediaKey(it.ref) == id }?.let { openSources(it.ref, TvMediaSourceIntent.PLAY) } },
                            onEpisodeOptions = { id -> route.episodes.firstOrNull { tvMediaKey(it.ref) == id }?.let { openSources(it.ref, TvMediaSourceIntent.BROWSE) } },
                            onMoreEpisodes = if (route.episodesCursor != null && !route.episodesLoading) ({ route.loadEpisodes(false) }) else null,
                            onJumpEpisode = { route.jumpToEpisode(it) },
                            onRetry = route::loadDetail, onRetryUserState = route::retryUserState,
                            onRetryEpisodes = { route.loadEpisodes(true) }, scrollState = detailScroll)
                        }
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
                            TvMediaSource(id = tvVariantKey(value), locationLabel = value.sourceLabel,
                                qualityLabel = listOfNotNull(value.resolution, value.size.takeIf { it > 0 }?.let(::tvSize)).joinToString(" · "),
                                detailLabel = null, fileName = value.displayName, kind = tvSourceKind(value.sourceKind), available = value.available,
                                unavailableReason = tvSourceState(value.availability),
                                recoveryLabel = tvSourceRecovery(value.recoveryAction))
                        })
                    }
                    val sourceTitle = route.episodes.firstOrNull { it.ref == source }?.card?.title
                        ?: route.detail?.card?.title ?: route.detail?.detail?.item?.title ?: "版本与片源"
                    TvMediaSourcePicker(TvMediaSourcePickerState(session.owner, tvMediaKey(source),
                        sourceTitle, editions,
                        currentSourceId = currentSourceId(),
                        preferredSourceId = sourceJourney.pendingSourceId(tvMediaKey(source)),
                        loading = route.sourcesLoading,
                        error = route.sourcesError, applying = route.sourcesRecovering,
                        intent = sourceJourney.currentIntent), onDismiss = ::closeSources,
                        onConfirm = { edition, sourceId ->
                            route.variants.firstOrNull { it.editionId == edition && tvVariantKey(it) == sourceId }
                                ?.takeIf { it.available }?.let {
                                    if (sourceJourney.confirm(sourceId)) {
                                        route.closeSources(); awaitingPlayer = true; playerWasShown = false; onPlayback(it, startFromBeginning, null)
                                    } else closeSources()
                                }
                        }, onRecover = route::recoverSource,
                        onRetry = if (route.sourcesError != null) ({ route.loadSources(true) }) else null,
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

private fun tvSourceSummary(variants: List<MediaCatalogVariant>): String? {
    if (variants.isEmpty()) return null
    val available = variants.count { it.available }
    val editions = variants.map { it.editionId }.distinct().size
    val qualities = variants.mapNotNull { it.resolution?.takeIf(String::isNotBlank) }.distinct()
    return buildList {
        add("$available 个可用片源")
        if (editions > 1) add("$editions 个版本")
        if (qualities.isNotEmpty()) add(qualities.joinToString(" · "))
    }.joinToString(" · ")
}

private fun tvCard(card: MediaCatalogCard, art: TvCatalogArtworkRequests): TvMediaCard = TvMediaCard(
    id = tvMediaKey(card.ref), title = card.title, poster = art.forArtwork(card.artwork),
    backdrop = art.forArtwork(card.backdrop), originalTitle = card.originalTitle, yearLabel = card.year?.toString(),
    extentLabel = card.episodes?.takeIf { it.state == MediaCatalogCountState.READY }?.collected?.let { "已收录 $it 集" }
        ?: card.duration?.seconds?.let { if (it < 60) "${it.toInt()} 秒" else "${(it / 60).toInt()} 分钟" },
    ratings = listOfNotNull(tvRating(card.primaryRating)), availabilityLabel = tvAvailability(card.availability),
    canPlay = card.actions.can(MediaCatalogAction.PLAY),
)

private fun tvSourceKind(value: MediaCatalogSourceKind): TvMediaSourceKind = when (value) {
    MediaCatalogSourceKind.CLOUD -> TvMediaSourceKind.CLOUD
    MediaCatalogSourceKind.NETWORK -> TvMediaSourceKind.NETWORK
    else -> TvMediaSourceKind.SERVER
}

private fun tvSourceState(value: MediaCatalogSourceAvailability): String? = when (value) {
    MediaCatalogSourceAvailability.AVAILABLE -> null
    MediaCatalogSourceAvailability.MISSING -> "文件缺失"
    MediaCatalogSourceAvailability.OFFLINE -> "来源离线"
    MediaCatalogSourceAvailability.CHANGED -> "文件已变化，需要重新核对"
    MediaCatalogSourceAvailability.UNVERIFIED -> "文件身份尚未验证"
    MediaCatalogSourceAvailability.DENIED -> "当前账号无权访问此片源"
    MediaCatalogSourceAvailability.UNAVAILABLE -> "片源暂时不可用"
    MediaCatalogSourceAvailability.UNKNOWN -> "片源状态未知"
}

private fun tvSourceRecovery(value: String?): String? = when (value) {
    "retry" -> "重试"
    "rescan" -> "重新扫描"
    "permission" -> "检查权限"
    else -> null
}

private fun tvAvailability(value: MediaCatalogAvailability): String? = when (value) {
    MediaCatalogAvailability.AVAILABLE -> null
    MediaCatalogAvailability.OFFLINE -> "来源离线"
    MediaCatalogAvailability.MISSING -> "文件缺失"
    MediaCatalogAvailability.CHECKING -> "正在确认来源"
    MediaCatalogAvailability.UNKNOWN -> "来源状态未知"
}

private fun tvRating(value: MediaCatalogRating?): TvMediaRating? {
    value ?: return null
    return tvRating(value.provider, value.metric, value.value, value.scale, value.count, value.countPrecision)
}

private fun tvRating(provider: String, metric: MediaRatingMetric, value: Double, scale: Double?,
    count: Long? = null, countPrecision: String? = null): TvMediaRating? {
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
    return TvMediaRating(source, label, tvMediaRatingCountLabel(source, count, countPrecision))
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
