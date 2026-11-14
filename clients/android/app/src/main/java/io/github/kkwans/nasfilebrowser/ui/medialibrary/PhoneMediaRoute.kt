package io.github.kkwans.nasfilebrowser.ui.medialibrary

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import io.github.kkwans.nasfilebrowser.app.SessionContext
import io.github.kkwans.nasfilebrowser.data.*
import io.github.kkwans.nasfilebrowser.ui.FilewayPageHeader
import io.github.kkwans.nasfilebrowser.ui.readableSize
import kotlin.math.roundToInt

@Composable
internal fun PhoneMediaRoute(
    session: SessionContext,
    repository: MediaLibraryRepository,
    imageLoader: ImageLoader,
    onDrawer: () -> Unit,
    onPlayback: (MediaCatalogVariant) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val route = remember(session, repository, scope) { PhoneMediaRouteState(session, repository, scope) }
    val binding = remember(session, repository) { MediaArtworkBinding(session, repository) }
    var filter by rememberSaveable(session.owner) { mutableStateOf("all") }
    var item by rememberSaveable(session.owner) { mutableStateOf<String?>(null) }
    var library by rememberSaveable(session.owner) { mutableStateOf<String?>(null) }
    var season by rememberSaveable(session.owner) { mutableStateOf<String?>(null) }
    var managing by rememberSaveable(session.owner) { mutableStateOf(false) }
    var createLibrary by rememberSaveable(session.owner) { mutableStateOf(false) }
    var settings by rememberSaveable(session.owner) { mutableStateOf<String?>(null) }
    var resumePolicy by rememberSaveable(session.owner) { mutableStateOf(false) }
    var reviewing by rememberSaveable(session.owner) { mutableStateOf(false) }
    var reviewLibraryId by rememberSaveable(session.owner) { mutableStateOf<String?>(null) }
    var reviewItemId by rememberSaveable(session.owner) { mutableStateOf<String?>(null) }
    var autoAdvance by rememberSaveable(session.owner) { mutableStateOf(true) }
    val grid = rememberLazyGridState()
    val pages = rememberSaveableStateHolder()
    val detailScroll = androidx.compose.runtime.key(item) { rememberLazyListState() }
    val detailId = item
    val libraryId = library
    DisposableEffect(route) { onDispose(route::close) }
    LaunchedEffect(route) { route.start(filter) }
    LaunchedEffect(route, libraryId, detailId) {
        if (libraryId != null && detailId != null) route.openDetail(repository.catalogRef(libraryId, detailId), season)
        else route.closeDetail()
    }
    LaunchedEffect(route.selectedSeason) { route.selectedSeason?.let { season = it } }

    fun back() {
        if (reviewItemId != null) { reviewItemId = null; reviewLibraryId = null }
        else if (reviewing) reviewing = false
        else if (route.sourceRef != null) route.closeSources()
        else { item = null; library = null; season = null }
    }

    fun closeSettings() {
        if (settings == "provider") {
            settings = if (resumePolicy) null else "home"
            resumePolicy = false
        } else settings = null
    }

    BackHandler(detailId != null || managing || settings != null || reviewItemId != null) {
        if (settings != null) closeSettings()
        else if (reviewItemId != null) back()
        else if (managing) managing = false else back()
    }
    val action: (PhoneMediaAction) -> Unit = { intent ->
        val detailRef = if (libraryId != null && detailId != null) repository.catalogRef(libraryId, detailId) else null
        fun source(id: String) {
            val ref = if (detailRef != null && key(detailRef) == id) detailRef
            else route.episodes.firstOrNull { key(it.ref) == id }?.ref
            ref?.let(route::openSources)
        }
        when (intent) {
            PhoneMediaAction.Back -> back()
            PhoneMediaAction.Settings -> settings = "home"
            PhoneMediaAction.CreateLibrary -> { createLibrary = true; managing = true }
            PhoneMediaAction.ManageLibraries -> { createLibrary = false; managing = true }
            is PhoneMediaAction.ReviewMatch -> reviewing = detailRef != null
            is PhoneMediaAction.OpenItem -> {
                val first = route.home?.hero?.items.orEmpty()
                (route.catalog + first).firstOrNull { key(it.ref) == intent.id }?.let {
                    library = it.ref.libraryId
                    item = it.ref.itemId
                    season = null
                }
            }
            is PhoneMediaAction.Category -> { filter = intent.id; route.chooseFilter(intent.id) }
            is PhoneMediaAction.SetCarouselAutoAdvance -> autoAdvance = intent.enabled
            is PhoneMediaAction.SelectSeason -> route.selectSeason(intent.id)
            is PhoneMediaAction.Play -> source(intent.id)
            is PhoneMediaAction.Favorite -> route.changeUserState(intent.id, intent.expectedRevision, favorite = intent.value)
            is PhoneMediaAction.Watched -> route.changeUserState(intent.id, intent.expectedRevision, watched = intent.value)
            is PhoneMediaAction.Sources -> source(intent.id)
            is PhoneMediaAction.SelectSource -> route.variant(intent.id)?.let { route.closeSources(); onPlayback(it) }
            is PhoneMediaAction.Retry -> when (intent.section) {
                "home", "featured" -> route.refreshHome()
                "catalog" -> route.loadCatalog(reset = true)
                "detail" -> route.loadDetail()
                "seasons" -> route.loadSeasons(reset = true)
                "episodes" -> route.loadEpisodes(reset = true)
                "sources" -> route.loadSources(reset = true)
                "user-state" -> route.retryUserState()
                else -> Unit
            }
            is PhoneMediaAction.LoadMore -> when (intent.section) {
                "catalog" -> route.loadCatalog(reset = false)
                "seasons" -> route.loadSeasons(reset = false)
                "episodes" -> route.loadEpisodes(reset = false)
                "sources" -> route.loadSources(reset = false)
                else -> Unit
            }
            else -> Unit
        }
    }
    CompositionLocalProvider(LocalMediaArtworkBinding provides binding) {
        val safe = Modifier.fillMaxSize().statusBarsPadding().consumeWindowInsets(WindowInsets.statusBars)
        if (settings != null) {
            Column(safe) {
                if (settings == "provider") PhoneMediaProviderSettings(repository, ::closeSettings)
                else PhoneMediaSettings(::closeSettings, { settings = null; createLibrary = false; managing = true }, { settings = "provider" })
            }
        } else if (reviewLibraryId != null && reviewItemId != null) {
            val ref = repository.catalogRef(requireNotNull(reviewLibraryId), requireNotNull(reviewItemId))
            Column(safe) {
                PhoneMediaMatchRoute(repository, ref, imageLoader, onBack = ::back, onMatched = { back(); route.refreshHome() })
            }
        } else if (managing) {
            Column(safe) {
                pages.SaveableStateProvider("media-manager/${session.owner}") {
                    PhoneMediaLibraryManager(session, repository, { managing = false; settings = "home" }, route::refreshHome,
                        onProvider = { resumePolicy = true; settings = "provider" },
                        onReviewItem = { ref -> reviewLibraryId = ref.libraryId; reviewItemId = ref.itemId },
                        createInitially = createLibrary)
                }
            }
        } else if (reviewing && detailId != null && libraryId != null) {
            Column(safe) {
                PhoneMediaMatchRoute(repository, repository.catalogRef(libraryId, detailId), imageLoader,
                    onBack = { reviewing = false }, onMatched = { reviewing = false; route.loadDetail(); route.refreshHome() })
            }
        } else if (detailId != null) {
            PhoneMediaDetail(route.detailState(session.owner, "$libraryId/$detailId"), imageLoader, action, safe, detailScroll)
        } else if (route.checking) {
            Column(safe) { FilewayPageHeader("影视"); MediaLoading("正在读取影视目录") }
        } else {
            val home = route.home
            val hasLibrary = home?.state != MediaCatalogHomeState.NO_LIBRARIES
            PhoneMediaHome(PhoneMediaHomeState(
                scopeKey = session.owner,
                libraryExists = hasLibrary,
                featured = MediaSection(home?.hero?.items.orEmpty().map(::poster)),
                catalog = MediaSection(route.catalog.map(::poster), route.catalogLoading, route.catalogProblem, route.catalogCursor != null),
                categories = if (hasLibrary && route.supported) listOf(
                    MediaCategory("all", "全部"), MediaCategory("movie", "电影"),
                    MediaCategory("series", "剧集"), MediaCategory("video", "未分类"),
                ) else emptyList(),
                selectedCategory = route.filter,
                canManage = home?.actions?.can(MediaCatalogAction.CREATE_LIBRARY) == true,
                carouselAutoAdvance = autoAdvance,
                filterSummary = if (route.filter != "all") "已筛选" else null,
                problem = route.homeProblem ?: when (home?.state) {
                    MediaCatalogHomeState.SOURCE_OFFLINE -> MediaProblem("来源暂时离线", "仍可浏览已收录的资料。", "刷新")
                    MediaCatalogHomeState.RESTRICTED -> MediaProblem("当前看不到媒体内容", "查看媒体库共享权限后重试。", "刷新")
                    MediaCatalogHomeState.SERVICE_UNAVAILABLE -> MediaProblem("影视目录暂不可用", "服务器暂时无法提供目录。", "重试")
                    else -> null
                },
            ), imageLoader, action, safe, grid, onDrawer)
        }
        route.sourceRef?.let { ref ->
            PhoneMediaSourcePicker(PhoneMediaSourcesState(
                itemId = key(ref), revision = "",
                entries = route.variants.map { variant ->
                    MediaSourceOption(variantKey(variant), variant.editionId, variant.editionName.ifBlank { "原始版本" },
                        variant.displayName, variant.sourceLabel, listOfNotNull(variant.resolution, readableSize(variant.size)).joinToString(" · "),
                        available = true)
                },
                loading = route.sourcesLoading, problem = route.sourcesProblem, hasMore = route.sourcesCursor != null,
            ), action, route::closeSources)
        }
    }
}

private fun PhoneMediaRouteState.detailState(scope: String, id: String): PhoneMediaDetailState {
    val data = detail
    val card = data?.card
    val item = data?.detail
    val playable = card?.actions?.can(MediaCatalogAction.PLAY) == true
    val state = card?.userState
    return PhoneMediaDetailState(scope, id, item?.item?.revision?.toString().orEmpty(),
        title = card?.title ?: item?.item?.title.orEmpty(),
        originalTitle = card?.originalTitle, metadata = card?.let(::summary).orEmpty(),
        backdrop = card?.backdrop?.let { MediaArtwork(catalog = it) },
        overview = item?.fields?.get("plot")?.values?.firstOrNull(), canPlay = playable, canSelectSources = playable,
        canReviewMatch = card?.actions?.can(MediaCatalogAction.EDIT_METADATA) == true && card.structureKind in setOf(
            MediaItemKind.VIDEO, MediaItemKind.MOVIE, MediaItemKind.SERIES),
        canChangeUserState = state != null && card.actions.can(MediaCatalogAction.FAVORITE) && card.actions.can(MediaCatalogAction.MARK_WATCHED),
        userStateRevision = state?.revision, favorite = state?.favorite == true, watched = state?.manualWatched == true,
        userStateBusy = userStateBusy || pendingUserState != null,
        userStateProblem = userStateProblem ?: pendingUserState?.let { MediaProblem("结果尚待确认", "使用原操作确认，避免重复提交。", "确认") },
        ratings = item?.ratings?.items.orEmpty().mapNotNull { observation ->
            val value = observation.rating
            val score = value.value ?: return@mapNotNull null
            rating(observation.id, value.provider ?: value.rawSource, value.metric, score, value.scale, value.count)
        },
        credits = MediaSection(item?.credits?.items.orEmpty().map { credit ->
            MediaCredit(credit.id, credit.name, credit.role.ifBlank { credit.roles.joinToString(" · ") })
        }),
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

private fun poster(card: MediaCatalogCard): MediaPoster = MediaPoster(
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
    val percent = metric == MediaRatingMetric.CRITIC_APPROVAL || metric == MediaRatingMetric.AUDIENCE_APPROVAL
    val score = if (percent && scale != null && scale > 0) "${(value / scale * 100).roundToInt()}%"
    else listOfNotNull(value.toString(), scale?.toString()).joinToString("/")
    return MediaRating(id, name, score, when (metric) {
        MediaRatingMetric.CRITIC_APPROVAL, MediaRatingMetric.CRITIC_AVERAGE -> "影评人"
        MediaRatingMetric.AUDIENCE_APPROVAL -> "观众"
        else -> null
    }, count?.let { when (source.lowercase()) {
        "douban" -> "$it 人评价"
        "imdb" -> "$it 票"
        else -> null
    } })
}
