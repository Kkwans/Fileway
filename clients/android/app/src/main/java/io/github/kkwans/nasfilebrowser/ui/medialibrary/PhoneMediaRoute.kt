package io.github.kkwans.nasfilebrowser.ui.medialibrary

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.kkwans.nasfilebrowser.app.SessionContext
import io.github.kkwans.nasfilebrowser.data.*
import io.github.kkwans.nasfilebrowser.ui.FilewayIcon
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayTopAppBar
import io.github.kkwans.nasfilebrowser.ui.readableSize
import io.github.kkwans.fileway.design.R as Art
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
internal fun PhoneMediaRoute(
    session: SessionContext,
    repository: MediaLibraryRepository,
    imageLoader: ImageLoader,
    onExit: () -> Unit,
    currentSourceId: () -> String?,
    onHandoff: ((MediaCatalogRef, String) -> Unit)?,
    onPlayback: (MediaCatalogVariant, Boolean, MediaCatalogContinue?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val route = remember(session, repository, scope) { PhoneMediaRouteState(session, repository, scope) }
    val watch = remember(repository, scope) { ContinueWatching(repository, scope) }
    val watching by watch.state.collectAsState()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val binding = remember(session, repository) { MediaArtworkBinding(session, repository) }
    var filter by rememberSaveable(session.owner) { mutableStateOf("all") }
    var selectedLibrary by rememberSaveable(session.owner) { mutableStateOf<String?>(null) }
    var item by rememberSaveable(session.owner) { mutableStateOf<String?>(null) }
    var library by rememberSaveable(session.owner) { mutableStateOf<String?>(null) }
    var season by rememberSaveable(session.owner) { mutableStateOf<String?>(null) }
    var parentItem by rememberSaveable(session.owner) { mutableStateOf<String?>(null) }
    var parentLibrary by rememberSaveable(session.owner) { mutableStateOf<String?>(null) }
    var parentSeason by rememberSaveable(session.owner) { mutableStateOf<String?>(null) }
    var managing by rememberSaveable(session.owner) { mutableStateOf(false) }
    var createLibrary by rememberSaveable(session.owner) { mutableStateOf(false) }
    var settings by rememberSaveable(session.owner) { mutableStateOf<String?>(null) }
    var reviewing by rememberSaveable(session.owner) { mutableStateOf(false) }
    var reviewLibraryId by rememberSaveable(session.owner) { mutableStateOf<String?>(null) }
    var reviewItemId by rememberSaveable(session.owner) { mutableStateOf<String?>(null) }
    var autoAdvance by rememberSaveable(session.owner) { mutableStateOf(true) }
    var mediaTab by rememberSaveable(session.owner) { mutableStateOf("home") }
    var searching by rememberSaveable(session.owner) { mutableStateOf(false) }
    var personPage by remember(route) { mutableStateOf<MediaCatalogPersonRef?>(null) }
    var personReturnItem by remember(route) { mutableStateOf<String?>(null) }
    var personReturnLibrary by remember(route) { mutableStateOf<String?>(null) }
    var personReturnSeason by remember(route) { mutableStateOf<String?>(null) }
    var creditsPage by remember(route) { mutableStateOf(false) }
    var collectionPage by remember(route) { mutableStateOf<MediaCatalogCollectionRef?>(null) }
    var startFromBeginning by remember(route) { mutableStateOf(false) }
    var sourceSelection by remember(route) { mutableStateOf<PhoneMediaSourceSelection?>(null) }
    val homeGrid = rememberLazyGridState()
    val libraryGrid = rememberLazyGridState()
    val categoryGrid = rememberLazyGridState()
    val pages = rememberSaveableStateHolder()
    val detailId = item
    val libraryId = library
    DisposableEffect(route, watch) { onDispose { route.close(); watch.close() } }
    LaunchedEffect(route) {
        route.start(if (mediaTab == "categories") filter else "all")
        if (mediaTab == "libraries") route.chooseLibrary(selectedLibrary)
    }
    LaunchedEffect(route.home) { route.home?.let(watch::seed) ?: watch.clear() }
    LaunchedEffect(route, route.home?.state, lifecycle) {
        if (lifecycle.isAtLeast(Lifecycle.State.RESUMED) && route.home?.state == MediaCatalogHomeState.SCANNING) {
            while (isActive) {
                delay(5_000)
                if (!route.homeRefreshing) route.refreshHome()
            }
        }
    }
    LaunchedEffect(route, libraryId, detailId) {
        if (libraryId != null && detailId != null) route.openDetail(repository.catalogRef(libraryId, detailId), season)
        else route.closeDetail()
    }
    LaunchedEffect(route.selectedSeason) { route.selectedSeason?.let { season = it } }
    LaunchedEffect(route.supported, route.checking) {
        if (!route.supported && !route.checking && route.homeProblem != null) {
            item = null; library = null; season = null; parentItem = null; parentLibrary = null; parentSeason = null; reviewing = false
            reviewItemId = null; reviewLibraryId = null; personPage = null; route.closePerson()
            collectionPage = null; route.closeCollection()
        }
    }

    fun back() {
        if (creditsPage) { creditsPage = false; route.closeCredits() }
        else if (collectionPage != null && detailId == null) { collectionPage = null; route.closeCollection() }
        else if (personPage != null && detailId == null) {
            personPage = null; route.closePerson()
            if (personReturnItem != null && personReturnLibrary != null) {
                searching = false
                item = personReturnItem; library = personReturnLibrary; season = personReturnSeason
                personReturnItem = null; personReturnLibrary = null; personReturnSeason = null
            } else searching = true
        }
        else if (searching && detailId == null) searching = false
        else if (reviewItemId != null) { reviewItemId = null; reviewLibraryId = null }
        else if (reviewing) reviewing = false
        else if (route.sourceRef != null) route.closeSources()
        else if (parentItem != null && parentLibrary != null) {
            item = parentItem; library = parentLibrary; season = parentSeason
            parentItem = null; parentLibrary = null; parentSeason = null
        } else if (mediaTab !in setOf("home", "settings")) {
            mediaTab = "home"
            route.chooseQuery(null, "all")
        } else { item = null; library = null; season = null }
    }

    fun closeSettings() {
        settings = null
    }

    BackHandler {
        if (creditsPage || collectionPage != null && detailId == null || personPage != null && detailId == null || searching && detailId == null) back()
        else if (settings != null) closeSettings()
        else if (reviewItemId != null) back()
        else if (managing) managing = false
        else if (detailId != null || reviewing || route.sourceRef != null) back()
        else if (phoneMediaShowsBack(phoneMediaPageForTab(mediaTab))) back()
        else onExit()
    }
    val action: (PhoneMediaAction) -> Unit = { intent ->
        val detailRef = if (libraryId != null && detailId != null) repository.catalogRef(libraryId, detailId) else null
        fun source(id: String, fromStart: Boolean = false, playDirect: Boolean = false) {
            val ref = if (detailRef != null && key(detailRef) == id) detailRef
            else route.episodes.firstOrNull { key(it.ref) == id }?.ref
            if (ref != null) {
                if (playDirect) {
                    val sources = route.sourcesFor(id).map { PhoneMediaPlaybackSource(variantKey(it), it.available, it.isDefault) }
                    val directId = phoneMediaDirectPlaySource(sources, currentSourceId(), route.hasMoreSourcesFor(id))
                    directId?.let(route::variant)?.let {
                        onPlayback(it, fromStart, null)
                        return
                    }
                }
                startFromBeginning = fromStart
                sourceSelection = PhoneMediaSourceSelection(key(ref))
                route.openSources(ref)
            }
        }
        when (intent) {
            PhoneMediaAction.Back -> back()
            PhoneMediaAction.Search -> searching = true
            PhoneMediaAction.Collections -> { route.editSearch(""); route.chooseSearchMode(MediaSearchMode.COLLECTIONS); searching = true }
            PhoneMediaAction.CloseSearch -> {
                searching = false; personPage = null; route.closePerson(); collectionPage = null; route.closeCollection()
            }
            is PhoneMediaAction.UpdateSearchQuery -> route.editSearch(intent.query)
            is PhoneMediaAction.SearchMode -> route.chooseSearchMode(intent.mode)
            is PhoneMediaAction.SubmitSearch -> route.submitSearch(intent.query)
            PhoneMediaAction.LoadMoreSearch -> when (route.searchMode) {
                MediaSearchMode.WORKS -> route.loadSearch(reset = false)
                MediaSearchMode.PEOPLE -> route.loadPeople(reset = false)
                MediaSearchMode.FILES -> route.loadFiles(reset = false)
                MediaSearchMode.COLLECTIONS -> route.loadCollections(reset = false)
            }
            is PhoneMediaAction.OpenCatalogPerson -> {
                val person = route.people.firstOrNull { it.ref.libraryId == intent.libraryId && it.ref.personId == intent.personId }
                if (person != null) {
                    personPage = person.ref
                    route.openPerson(person.ref, person.name)
                }
            }
            is PhoneMediaAction.OpenPerson -> libraryId?.let { targetLibrary ->
                val name = route.credits.firstOrNull { it.personId == intent.id }?.name
                    ?: route.detail?.detail?.credits?.items?.firstOrNull { it.personId == intent.id }?.name.orEmpty()
                personReturnItem = detailId; personReturnLibrary = targetLibrary; personReturnSeason = season
                val ref = MediaCatalogPersonRef(targetLibrary, intent.id)
                personPage = ref; route.openPerson(ref, name); searching = true; creditsPage = false
                item = null; library = null; season = null; parentItem = null; parentLibrary = null; parentSeason = null
            }
            is PhoneMediaAction.AllCredits -> { creditsPage = true; route.openCredits() }
            PhoneMediaAction.LoadMorePersonWorks -> route.loadPersonWorks(reset = false)
            is PhoneMediaAction.OpenCollection -> {
                val collection = route.collections.firstOrNull { it.ref.libraryId == intent.libraryId && it.ref.collectionId == intent.collectionId }
                if (collection != null) {
                    collectionPage = collection.ref
                    route.openCollection(collection.ref, collection.name)
                }
            }
            PhoneMediaAction.LoadMoreCollectionWorks -> route.loadCollectionWorks(reset = false)
            is PhoneMediaAction.Handoff -> {
                val ref = detailRef
                if (ref != null && onHandoff != null) {
                    onHandoff(ref, route.detail?.card?.title ?: route.detail?.detail?.item?.title ?: "影视")
                }
            }
            PhoneMediaAction.Settings -> mediaTab = "settings"
            PhoneMediaAction.CreateLibrary -> { createLibrary = true; managing = true }
            PhoneMediaAction.ManageLibraries -> { createLibrary = false; managing = true }
            is PhoneMediaAction.ReviewMatch -> reviewing = detailRef != null
            is PhoneMediaAction.OpenItem -> {
                val first = route.home?.hero?.items.orEmpty()
                val homeItems = route.home?.rows.orEmpty().flatMap { it.items }
                val episode = route.episodes.firstOrNull { key(it.ref) == intent.id }
                (route.catalog + route.searchResults + route.files.map { it.card } + route.collectionWorks + route.personWorks + first + homeItems + watching.items.map { it.card } + listOfNotNull(episode?.card)).firstOrNull { key(it.ref) == intent.id }?.let {
                    if (episode != null && item != null && library != null) {
                        parentItem = item; parentLibrary = library; parentSeason = season
                    } else { parentItem = null; parentLibrary = null; parentSeason = null }
                    library = it.ref.libraryId
                    item = it.ref.itemId
                    season = null
                }
            }
            is PhoneMediaAction.Category -> {
                filter = intent.id; selectedLibrary = null; mediaTab = "categories"
                route.chooseQuery(null, intent.id)
            }
            PhoneMediaAction.ViewAllCatalog -> {
                filter = "all"; selectedLibrary = null; mediaTab = "libraries"
                route.chooseQuery(null, "all")
            }
            PhoneMediaAction.ViewAllSeries -> {
                filter = "series"; selectedLibrary = null; mediaTab = "categories"
                route.chooseQuery(null, "series")
            }
            is PhoneMediaAction.SetCarouselAutoAdvance -> autoAdvance = intent.enabled
            is PhoneMediaAction.SelectSeason -> route.selectSeason(intent.id)
            is PhoneMediaAction.Play -> if (intent.kind == MediaTargetKind.CONTINUE) {
                watching.items.firstOrNull { it.id == intent.id && watching.removing != it.id }?.let {
                    onPlayback(it.variant, intent.fromStart, it)
                }
            } else source(intent.id, intent.fromStart, playDirect = true)
            is PhoneMediaAction.RemoveContinue -> watching.items.firstOrNull { it.id == intent.id }?.let(watch::remove)
            is PhoneMediaAction.Favorite -> route.changeUserState(intent.id, intent.expectedRevision, favorite = intent.value)
            is PhoneMediaAction.Watched -> route.changeUserState(intent.id, intent.expectedRevision, watched = intent.value)
            is PhoneMediaAction.Sources -> source(intent.id)
            is PhoneMediaAction.SelectSource -> {
                val ref = route.sourceRef
                if (ref != null && route.variant(intent.id)?.available == true) {
                    sourceSelection = (sourceSelection ?: PhoneMediaSourceSelection(key(ref)))
                        .select(intent.id, available = true)
                }
            }
            is PhoneMediaAction.RecoverSource -> route.recoverSource(intent.id)
            is PhoneMediaAction.ConfirmSource -> {
                val ref = route.sourceRef
                val confirmed = sourceSelection?.confirm(intent.id, ref?.let(::key).orEmpty(), intent.expectedRevision, "")
                if (confirmed != null) route.variant(confirmed)?.takeIf { it.available }?.let {
                    sourceSelection = null
                    route.closeSources()
                    onPlayback(it, startFromBeginning, null)
                }
            }
            is PhoneMediaAction.Retry -> when (intent.section) {
                "home", "featured" -> route.refreshHome()
                "continue" -> watch.retry()
                "catalog" -> route.retryCatalog()
                "detail" -> route.loadDetail()
                "seasons" -> route.loadSeasons(reset = true)
                "episodes" -> route.loadEpisodes(reset = true)
                "sources" -> route.loadSources(reset = true)
                "search" -> route.retrySearch()
                "person-works" -> route.loadPersonWorks(reset = true)
                "collection-works" -> route.loadCollectionWorks(reset = true)
                "user-state" -> route.retryUserState()
                "credits" -> route.loadCredits(reset = true)
                else -> Unit
            }
            is PhoneMediaAction.LoadMore -> when (intent.section) {
                "catalog" -> route.loadCatalog(reset = false)
                "continue" -> watch.load(reset = false)
                "seasons" -> route.loadSeasons(reset = false)
                "episodes" -> route.loadEpisodes(reset = false)
                "sources" -> route.loadSources(reset = false)
                "search" -> when (route.searchMode) {
                    MediaSearchMode.WORKS -> route.loadSearch(reset = false)
                    MediaSearchMode.PEOPLE -> route.loadPeople(reset = false)
                    MediaSearchMode.FILES -> route.loadFiles(reset = false)
                    MediaSearchMode.COLLECTIONS -> route.loadCollections(reset = false)
                }
                "person-works" -> route.loadPersonWorks(reset = false)
                "credits" -> route.loadCredits(reset = false)
                "collection-works" -> route.loadCollectionWorks(reset = false)
                else -> Unit
            }
            else -> Unit
        }
    }
    CompositionLocalProvider(LocalMediaArtworkBinding provides binding) {
        val safe = Modifier.fillMaxSize().statusBarsPadding().consumeWindowInsets(WindowInsets.statusBars)
        val subpage = safe.navigationBarsPadding().consumeWindowInsets(WindowInsets.navigationBars)
        if (settings != null) {
            Column(subpage) {
                if (settings == "provider") PhoneMediaProviderSettings(repository, ::closeSettings)
                else PhoneMediaSettings(::closeSettings, { settings = null; createLibrary = false; managing = true }, { settings = "provider" },
                    route.home?.libraries?.size ?: 0, route.home?.state)
            }
        } else if (reviewLibraryId != null && reviewItemId != null) {
            val ref = repository.catalogRef(requireNotNull(reviewLibraryId), requireNotNull(reviewItemId))
            Column(subpage) {
                PhoneMediaMatchRoute(repository, ref, imageLoader, onBack = ::back, onMatched = { back(); route.refreshHome() })
            }
        } else if (managing) {
            Column(subpage) {
                pages.SaveableStateProvider("media-manager/${session.owner}") {
                    PhoneMediaLibraryManager(session, repository, { managing = false; settings = null }, route::refreshHome,
                        onProvider = { settings = "provider" },
                        onReviewItem = { ref -> reviewLibraryId = ref.libraryId; reviewItemId = ref.itemId },
                        onBrowse = { id ->
                            managing = false; settings = null; selectedLibrary = id; mediaTab = "libraries"
                            route.chooseLibrary(id); route.refreshHome()
                        },
                        createInitially = createLibrary)
                }
            }
        } else if (reviewing && detailId != null && libraryId != null) {
            Column(subpage) {
                PhoneMediaMatchRoute(repository, repository.catalogRef(libraryId, detailId), imageLoader,
                    onBack = { reviewing = false }, onMatched = { reviewing = false; route.loadDetail(); route.refreshHome() })
            }
        } else if (creditsPage && detailId != null && libraryId != null) {
            PhoneMediaCredits(route.detail?.card?.title.orEmpty(), route.credits.map { credit ->
                MediaCredit(credit.id, credit.name, credit.role.ifBlank { credit.roles.joinToString(" · ") },
                    portrait = credit.portraitEndpoint?.let { MediaArtwork(endpoint = it) }, personId = credit.personId)
            }, route.creditsLoading, route.creditsProblem, route.creditsCursor != null, imageLoader, ::back, action, subpage)
        } else if (detailId != null) {
            pages.SaveableStateProvider("media-detail/$libraryId/$detailId") {
                val ref = repository.catalogRef(requireNotNull(libraryId), requireNotNull(detailId))
                PhoneMediaDetail(route.detailState(session.owner, "$libraryId/$detailId")
                    .copy(canHandoff = onHandoff != null && route.detail?.card?.actions?.can(MediaCatalogAction.PLAY) == true),
                    imageLoader, action, subpage, rememberLazyListState())
            }
        } else if (searching && collectionPage != null) {
            PhoneMediaCollectionWorks(route.collectionName, route.collectionWorks.map(::poster), route.collectionWorksLoading,
                route.collectionWorksProblem, route.collectionWorksCursor != null, imageLoader, onBack = ::back, onAction = action)
        } else if (searching && personPage != null) {
            val person = requireNotNull(personPage)
            val result = route.people.firstOrNull { it.ref == person }
            val portraitEndpoint = result?.portraitEndpoint ?: (route.credits.firstOrNull { it.personId == person.personId }
                ?: route.detail?.detail?.credits?.items?.firstOrNull { it.personId == person.personId })?.portraitEndpoint
            PhoneMediaPersonWorks(route.personName.ifBlank { result?.name.orEmpty() }, result?.roles.orEmpty(),
                portraitEndpoint?.let { MediaArtwork(endpoint = it) },
                route.personWorks.map(::poster), route.personWorksLoading, route.personWorksProblem,
                route.personWorksCursor != null, imageLoader, onBack = ::back, onAction = action)
        } else if (searching) {
            pages.SaveableStateProvider("media-search/${session.owner}") {
                PhoneMediaSearch(route.searchQuery, route.searchedQuery.isNotBlank(), route.searchMode,
                    route.searchResults.map(::poster), route.searchLoading, route.searchProblem, route.searchCursor != null,
                    route.people, route.peopleLoading, route.peopleProblem, route.peopleCursor != null,
                    route.files, route.filesLoading, route.filesProblem, route.filesCursor != null,
                    route.collections, route.collectionsLoading, route.collectionsProblem, route.collectionsCursor != null,
                    route.home?.capabilities?.get("fileSearch") == true, route.home?.capabilities?.get("collections") == true, imageLoader, action)
            }
        } else {
            val home = route.home
            val hasLibrary = route.catalog.isNotEmpty() || home != null && home.state != MediaCatalogHomeState.NO_LIBRARIES
            val page = phoneMediaPageForTab(mediaTab)
            val grid = when (page) { PhoneMediaPage.HOME -> homeGrid; PhoneMediaPage.LIBRARIES -> libraryGrid; PhoneMediaPage.CATEGORIES -> categoryGrid }
            Column(Modifier.fillMaxSize()) {
              Box(Modifier.weight(1f)) {
               if (mediaTab == "settings") {
                Column(safe) {
                    PhoneMediaSettings(onExit, { createLibrary = false; managing = true }, { settings = "provider" },
                        home?.libraries?.size ?: 0, home?.state)
                }
               } else if (route.checking) {
                Column(safe) {
                    FilewayTopAppBar("影视中心", leadingIcon = Art.drawable.fileway_ic_arrow_back, leadingDescription = "返回", onLeadingClick = onExit)
                    MediaLoading("正在读取影视目录", Modifier.weight(1f))
                }
               } else PhoneMediaHome(PhoneMediaHomeState(
                scopeKey = session.owner,
                libraryExists = hasLibrary,
                featured = MediaSection(home?.hero?.items.orEmpty().map(::poster)),
                updatedSeries = MediaSection(home?.rows?.firstOrNull { it.id == MediaCatalogRowId.RECENTLY_UPDATED_SERIES }
                    ?.items.orEmpty().map(::poster)),
                continuing = MediaSection(watching.items.map { entry ->
                    MediaContinue(entry.id, poster(entry.card), entry.progressLabel(), entry.progress,
                        removing = watching.removing == entry.id,
                        canStartOver = entry.card.actions.can(MediaCatalogAction.START_OVER),
                        canRemove = entry.card.actions.can(MediaCatalogAction.REMOVE_FROM_CONTINUE))
                }, watching.loading, watching.error?.let { MediaProblem("继续观看", it, "重试") }, watching.hasMore),
                catalog = MediaSection(route.catalog.map(::poster), route.catalogLoading, route.catalogProblem, route.catalogCursor != null),
                categories = if (hasLibrary && route.supported) listOf(
                    MediaCategory("all", "全部"), MediaCategory("movie", "电影"),
                    MediaCategory("series", "剧集"), MediaCategory("video", "未分类"),
                ) else emptyList(),
                selectedCategory = route.filter,
                catalogTitle = when (route.filter) {
                    "movie" -> "电影"; "series" -> "剧集"; "video" -> "未分类视频"; else -> "全部影视"
                },
                canManage = home?.actions?.can(MediaCatalogAction.CREATE_LIBRARY) == true,
                canSearch = home?.capabilities?.get("search") == true,
                canSearchCollections = home?.capabilities?.get("collections") == true,
                carouselAutoAdvance = autoAdvance,
                filterSummary = if (route.filter != "all") "已筛选" else null,
                problem = route.homeProblem ?: when (home?.state) {
                    MediaCatalogHomeState.SCANNING -> MediaProblem("正在扫描媒体库", "已发现的内容会逐步显示。")
                    MediaCatalogHomeState.PARTIAL -> MediaProblem("部分内容仍在准备", "可以先浏览已加载的影视。")
                    MediaCatalogHomeState.SOURCE_OFFLINE -> MediaProblem("来源暂时离线", "仍可浏览已收录的资料。", "刷新")
                    MediaCatalogHomeState.RESTRICTED -> MediaProblem("当前看不到媒体内容", "查看媒体库共享权限后重试。", "刷新")
                    MediaCatalogHomeState.SERVICE_UNAVAILABLE -> MediaProblem("影视目录暂不可用", "服务器暂时无法提供目录。", "重试")
                    else -> null
                },
            ), imageLoader, action, safe, grid, page, home?.libraries.orEmpty(), route.libraryFilter,
                onLibrary = { selectedLibrary = it; route.chooseLibrary(it) }, onBack = ::back)
              }
              PhoneMediaNavigation(mediaTab) { target ->
                  mediaTab = target
                  route.chooseQuery(if (target == "libraries") selectedLibrary else null,
                      if (target == "categories") filter else "all")
              }
            }
        }
        route.sourceRef?.let { ref ->
            PhoneMediaSourcePicker(PhoneMediaSourcesState(
                itemId = key(ref), title = route.detail?.card?.title ?: route.detail?.detail?.item?.title ?: "版本与片源",
                revision = "", selectedId = sourceSelection?.takeIf { it.itemId == key(ref) }?.selectedId
                    ?: route.variants.firstOrNull { variantKey(it) == currentSourceId() && it.available }?.let(::variantKey),
                entries = route.variants.map { variant ->
                    MediaSourceOption(variantKey(variant), variant.editionId, variant.editionName.ifBlank { "原始版本" },
                        variant.displayName, variant.sourceLabel,
                        listOfNotNull(variant.resolution, variant.size.takeIf { it > 0 }?.let(::readableSize)).joinToString(" · "),
                        available = variant.available,
                        unavailableReason = sourceStateLabel(variant.availability),
                        recoveryLabel = sourceRecoveryLabel(variant.recoveryAction),
                        current = variantKey(variant) == currentSourceId())
                },
                loading = route.sourcesLoading, busy = route.sourcesRecovering,
                problem = route.sourcesProblem, hasMore = route.sourcesCursor != null,
            ), action, route::closeSources)
        }
    }
}

@Composable
private fun PhoneMediaNavigation(selected: String, onSelect: (String) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
        listOf(Triple("home", "首页", Art.drawable.fileway_ic_home),
            Triple("libraries", "媒体库", Art.drawable.fileway_ic_library),
            Triple("categories", "分类", Art.drawable.fileway_ic_layout_cover),
            Triple("settings", "设置", Art.drawable.fileway_ic_settings)).forEach { (id, label, icon) ->
            NavigationBarItem(selected = selected == id, onClick = { onSelect(id) },
                icon = { FilewayIcon(icon, null, Modifier.size(24.dp)) }, label = { Text(label) },
                colors = NavigationBarItemDefaults.colors(indicatorColor = Color.Transparent,
                    selectedIconColor = MaterialTheme.colorScheme.primary, selectedTextColor = MaterialTheme.colorScheme.primary,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant))
        }
    }
}

private fun PhoneMediaRouteState.detailState(scope: String, id: String): PhoneMediaDetailState {
    val data = detail
    val card = data?.card
    val item = data?.detail
    val playable = card?.actions?.can(MediaCatalogAction.PLAY) == true
    val state = card?.userState
    val detailSources = sourcesFor(id)
    return PhoneMediaDetailState(scope, id, item?.item?.revision?.toString().orEmpty(),
        title = card?.title ?: item?.item?.title.orEmpty(),
        originalTitle = card?.originalTitle, metadata = card?.let(::summary).orEmpty(),
        backdrop = card?.backdrop?.let { MediaArtwork(catalog = it) },
        poster = card?.artwork?.takeIf { it.role == "poster" }?.let { MediaArtwork(catalog = it) },
        overview = item?.fields?.get("plot")?.values?.firstOrNull(), canPlay = playable, canSelectSources = playable,
        sourceCount = if (playable) detailSources.size else null,
        sourceSummary = phoneSourceSummary(detailSources) ?: if (loadingSourcesFor(id)) "正在读取片源…" else null,
        technical = technical,
        canReviewMatch = card?.actions?.can(MediaCatalogAction.EDIT_METADATA) == true && card.structureKind in setOf(
            MediaItemKind.VIDEO, MediaItemKind.MOVIE, MediaItemKind.SERIES, MediaItemKind.EPISODE),
        canChangeUserState = state != null && card.actions.can(MediaCatalogAction.FAVORITE) && card.actions.can(MediaCatalogAction.MARK_WATCHED),
        userStateRevision = state?.revision, favorite = state?.favorite == true, watched = state?.manualWatched == true,
        userStateBusy = userStateBusy || pendingUserState != null,
        userStateProblem = userStateProblem ?: pendingUserState?.let { MediaProblem("结果尚待确认", "使用原操作确认，避免重复提交。", "确认") },
        ratings = item?.ratings?.items.orEmpty().mapNotNull { observation ->
            val value = observation.rating
            val score = value.value ?: return@mapNotNull null
            rating(observation.id, value.provider ?: value.rawSource, value.metric, score, value.scale, value.count)
        },
        collections = item?.collections?.items.orEmpty().map { collection ->
            listOfNotNull(collection.name, collection.number?.let { "编号 $it" }).joinToString(" · ")
        },
        credits = MediaSection(item?.credits?.items.orEmpty().map { credit ->
            MediaCredit(credit.id, credit.name, credit.role.ifBlank { credit.roles.joinToString(" · ") },
                portrait = credit.portraitEndpoint?.let { MediaArtwork(endpoint = it) }, personId = credit.personId)
        }, hasMore = item?.credits?.nextCursor != null),
        seasons = seasons.map { MediaSeason(it.ref.itemId, it.label) },
        seasonsLoading = seasonsLoading, seasonsHasMore = seasonsCursor != null, seasonsProblem = seasonsProblem,
        selectedSeason = selectedSeason,
        episodes = MediaSection(episodes.map { episode ->
            MediaEpisode(key(episode.ref), "第 ${episode.episodeNumber} 集", episode.card.title,
                episode.playback?.label.orEmpty(), playable = episode.card.actions.can(MediaCatalogAction.PLAY),
                unavailableReason = availability(episode.card))
        }, episodesLoading, episodesProblem, episodesCursor != null),
        episodeSummary = card?.episodes?.collected?.let { "已收录 $it 集" },
        problem = detailProblem, loading = detailLoading || data == null && detailProblem == null,
    )
}

private fun phoneSourceSummary(variants: List<MediaCatalogVariant>): String? {
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

private fun sourceStateLabel(state: MediaCatalogSourceAvailability): String? = when (state) {
    MediaCatalogSourceAvailability.AVAILABLE -> null
    MediaCatalogSourceAvailability.MISSING -> "文件缺失"
    MediaCatalogSourceAvailability.OFFLINE -> "来源离线"
    MediaCatalogSourceAvailability.CHANGED -> "文件已变化，需要重新核对"
    MediaCatalogSourceAvailability.UNVERIFIED -> "文件身份尚未验证"
    MediaCatalogSourceAvailability.DENIED -> "当前账号无权访问此片源"
    MediaCatalogSourceAvailability.UNAVAILABLE -> "片源暂时不可用"
    MediaCatalogSourceAvailability.UNKNOWN -> "片源状态未知"
}

private fun sourceRecoveryLabel(action: String?): String? = when (action) {
    "retry" -> "重试"
    "rescan" -> "重新扫描"
    "permission" -> "检查权限"
    else -> null
}

internal fun poster(card: MediaCatalogCard): MediaPoster = MediaPoster(
    id = key(card.ref), title = card.title.ifBlank { "未命名影视" }, subtitle = summary(card),
    poster = card.artwork?.takeIf { it.role == "poster" }?.let { MediaArtwork(catalog = it) },
    backdrop = card.backdrop?.let { MediaArtwork(catalog = it) },
    primaryRating = card.primaryRating?.let { rating(it.provider, it.provider, it.metric, it.value, it.scale, null) },
    availability = availability(card),
)

private fun summary(card: MediaCatalogCard) = listOfNotNull(
    card.year?.toString(),
    card.episodes?.collected?.let { "已收录 $it 集" },
    card.duration?.seconds?.takeIf { it >= 60 }?.let { "${(it / 60).toInt()} 分钟" },
    "待确认".takeIf { card.matchState == MediaCatalogMatchState.NEEDS_REVIEW },
    "未匹配".takeIf { card.matchState == MediaCatalogMatchState.UNMATCHED },
).joinToString(" · ")

private fun availability(card: MediaCatalogCard) = when (card.availability) {
    MediaCatalogAvailability.OFFLINE -> "来源离线"
    MediaCatalogAvailability.MISSING -> "片源暂不可用"
    MediaCatalogAvailability.CHECKING -> "正在检查片源"
    else -> null
}

private fun rating(id: String, source: String, metric: MediaRatingMetric, value: Double, scale: Double?, count: Long?): MediaRating {
    val name = when (source.lowercase()) {
        "douban" -> "豆瓣"
        "imdb" -> "IMDb"
        "rottentomatoes", "rotten_tomatoes" -> "烂番茄"
        "tmdb" -> "TMDB"
        else -> source
    }
    val score = formatMediaRating(value, scale, metric)
    val detailScore = formatMediaRating(value, scale, metric, detailed = true)
    return MediaRating(id, name, score, when (metric) {
        MediaRatingMetric.CRITIC_APPROVAL, MediaRatingMetric.CRITIC_AVERAGE -> "影评人"
        MediaRatingMetric.AUDIENCE_APPROVAL -> "观众"
        else -> null
    }, count?.let { when (source.lowercase()) {
        "douban" -> "$it 人评价"
        "imdb" -> "$it 票"
        else -> null
    } }, detailValue = detailScore)
}
