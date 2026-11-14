package io.github.kkwans.fileway.tv.medialibrary

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.kkwans.nasfilebrowser.app.SessionContext
import io.github.kkwans.nasfilebrowser.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/** In-memory results and cursors belong only to this session and query. */
internal class TvMediaRouteState(
    private val session: SessionContext,
    private val repository: MediaLibraryRepository,
    private val scope: CoroutineScope,
) {
    var checking by mutableStateOf(true); private set
    var supported by mutableStateOf(false); private set
    var home by mutableStateOf<MediaCatalogHome?>(null); private set
    var homeError by mutableStateOf<String?>(null); private set
    var filter by mutableStateOf("all"); private set
    var catalog by mutableStateOf<List<MediaCatalogCard>>(emptyList()); private set
    var catalogLoading by mutableStateOf(false); private set
    var catalogError by mutableStateOf<String?>(null); private set
    var catalogCursor by mutableStateOf<MediaCatalogCursor?>(null); private set
    var detail by mutableStateOf<MediaCatalogDetail.Complete?>(null); private set
    var detailLoading by mutableStateOf(false); private set
    var detailError by mutableStateOf<String?>(null); private set
    var seasons by mutableStateOf<List<MediaCatalogSeason>>(emptyList()); private set
    var seasonsLoading by mutableStateOf(false); private set
    var seasonsCursor by mutableStateOf<MediaCatalogCursor?>(null); private set
    var seasonsError by mutableStateOf<String?>(null); private set
    var selectedSeason by mutableStateOf<String?>(null); private set
    var episodes by mutableStateOf<List<MediaCatalogEpisode>>(emptyList()); private set
    var episodesLoading by mutableStateOf(false); private set
    var episodesCursor by mutableStateOf<MediaCatalogCursor?>(null); private set
    var episodesError by mutableStateOf<String?>(null); private set
    var sourceRef by mutableStateOf<MediaCatalogRef?>(null); private set
    var variants by mutableStateOf<List<MediaCatalogVariant>>(emptyList()); private set
    var sourcesLoading by mutableStateOf(false); private set
    var sourcesCursor by mutableStateOf<MediaCatalogCursor?>(null); private set
    var sourcesError by mutableStateOf<String?>(null); private set

    private var ref: MediaCatalogRef? = null
    private var detailCursor: MediaCatalogCursor? = null
    private var desiredSeason: String? = null
    private var homeJob: Job? = null
    private var catalogJob: Job? = null
    private var detailJob: Job? = null
    private var seasonsJob: Job? = null
    private var episodesJob: Job? = null
    private var sourcesJob: Job? = null

    fun close() { listOf(homeJob, catalogJob, detailJob, seasonsJob, episodesJob, sourcesJob).forEach { it?.cancel() } }

    fun start(savedFilter: String) {
        filter = savedFilter.takeIf { it in setOf("all", "movie", "series", "video") } ?: "all"
        refreshHome()
    }

    fun refreshHome() {
        homeJob?.cancel(); catalogJob?.cancel()
        checking = true; supported = false; home = null; homeError = null
        catalog = emptyList(); catalogCursor = null; catalogError = null; catalogLoading = false
        homeJob = scope.launch {
            try {
                if (session.api.clientCapabilities().mediaLibraryVersion < 1) {
                    checking = false; homeError = "服务器尚未启用影视中心，或当前无法读取。"; return@launch
                }
                when (val result = repository.home()) {
                    is MediaReadResult.Success -> if (result.scope == repository.scope) {
                        home = result.value; checking = false; supported = true
                        if (result.value.state != MediaCatalogHomeState.NO_LIBRARIES) loadCatalog(true)
                    }
                    is MediaReadResult.Failure -> if (result.scope == repository.scope) {
                        checking = false; homeError = tvMediaFailure(result.error)
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { checking = false; homeError = "无法读取服务器能力，请检查连接后重试。" }
        }
    }

    fun selectFilter(value: String) {
        if (value !in setOf("all", "movie", "series", "video") || value == filter) return
        filter = value; loadCatalog(true)
    }

    fun loadCatalog(reset: Boolean) {
        if (!supported || (!reset && (catalogLoading || catalogCursor == null))) return
        catalogJob?.cancel(); val cursor = if (reset) null else catalogCursor
        if (reset) { catalog = emptyList(); catalogCursor = null }
        val query = MediaCatalogQuery(structureKind = when (filter) {
            "movie" -> MediaCatalogFilterKind.MOVIE; "series" -> MediaCatalogFilterKind.SERIES
            "video" -> MediaCatalogFilterKind.VIDEO; else -> null
        }, sort = MediaCatalogSort.NEWEST, limit = 24)
        catalogLoading = true; catalogError = null
        catalogJob = scope.launch {
            when (val result = repository.catalog(query, cursor)) {
                is MediaReadResult.Success -> if (result.scope == repository.scope) {
                    val page = result.value
                    catalog = (if (reset) page.items else catalog + page.items).distinctBy { tvMediaKey(it.ref) }
                    catalogCursor = page.nextCursor; catalogError = tvPageError(page.state, page.nextCursor != null)
                }
                is MediaReadResult.Failure -> if (result.scope == repository.scope) {
                    if (result.error.invalidatesTvData()) { catalog = emptyList(); catalogCursor = null }
                    catalogError = tvMediaFailure(result.error)
                }
            }
            coroutineContext.ensureActive(); catalogLoading = false
        }
    }

    fun openDetail(item: MediaCatalogRef, savedSeason: String?) {
        if (ref == item && (detail != null || detailLoading)) return
        detailJob?.cancel(); seasonsJob?.cancel(); episodesJob?.cancel(); closeSources()
        ref = item; desiredSeason = savedSeason; detail = null; detailCursor = null; detailError = null
        seasons = emptyList(); seasonsCursor = null; selectedSeason = null; episodes = emptyList(); episodesCursor = null
        seasonsLoading = false; seasonsError = null; episodesLoading = false; episodesError = null
        loadDetail()
    }

    fun closeDetail() {
        detailJob?.cancel(); seasonsJob?.cancel(); episodesJob?.cancel(); closeSources()
        ref = null; detail = null; detailCursor = null; selectedSeason = null
        detailLoading = false; seasonsLoading = false; episodesLoading = false
    }

    fun loadDetail() {
        val item = ref ?: return
        detailJob?.cancel(); val cursor = detailCursor
        detailLoading = true; detailError = null
        detailJob = scope.launch {
            when (val result = repository.catalogDetail(item, cursor)) {
                is MediaReadResult.Success -> if (result.scope == repository.scope) when (val value = result.value) {
                    is MediaCatalogDetail.Complete -> {
                        detail = value; detailCursor = null
                        if (value.detail.item.kind == MediaItemKind.SERIES) loadSeasons(true)
                    }
                    is MediaCatalogDetail.Partial -> { detailCursor = value.nextCursor; detailError = "详情尚未加载完成，请继续读取。" }
                }
                is MediaReadResult.Failure -> if (result.scope == repository.scope) {
                    if (result.error.invalidatesTvData()) {
                        detail = null; detailCursor = null
                        if (result.error.kind in setOf(MediaFailureKind.AUTHENTICATION, MediaFailureKind.FORBIDDEN, MediaFailureKind.NOT_FOUND)) {
                            catalog = catalog.filterNot { it.ref == item }
                            home = home?.let { value -> value.copy(
                                hero = value.hero?.let { row -> row.copy(items = row.items.filterNot { it.ref == item }) },
                                rows = value.rows.map { row -> row.copy(items = row.items.filterNot { it.ref == item }) },
                            ) }
                        }
                    }
                    detailError = tvMediaFailure(result.error)
                }
            }
            coroutineContext.ensureActive(); detailLoading = false
        }
    }

    fun loadSeasons(reset: Boolean) {
        val item = ref ?: return
        if (!reset && (seasonsLoading || seasonsCursor == null)) return
        seasonsJob?.cancel(); val cursor = if (reset) null else seasonsCursor
        if (reset) { seasons = emptyList(); seasonsCursor = null }
        seasonsLoading = true; seasonsError = null
        seasonsJob = scope.launch {
            when (val result = repository.seasons(item, limit = 20, cursor = cursor)) {
                is MediaReadResult.Success -> if (result.scope == repository.scope) {
                    val page = result.value
                    seasons = (if (reset) page.items else seasons + page.items).distinctBy { it.ref.itemId }
                    seasonsCursor = page.nextCursor; seasonsError = tvPageError(page.state, page.nextCursor != null)
                    val selected = desiredSeason?.let { wanted -> seasons.firstOrNull { it.ref.itemId == wanted } }
                        ?: if (desiredSeason == null) seasons.firstOrNull() else null
                    if (selected != null && selectedSeason != selected.ref.itemId) selectSeason(selected.ref.itemId)
                }
                is MediaReadResult.Failure -> if (result.scope == repository.scope) {
                    if (result.error.invalidatesTvData()) { seasons = emptyList(); seasonsCursor = null; episodes = emptyList() }
                    seasonsError = tvMediaFailure(result.error)
                }
            }
            coroutineContext.ensureActive(); seasonsLoading = false
        }
    }

    fun selectSeason(id: String) {
        val season = seasons.firstOrNull { it.ref.itemId == id } ?: return
        desiredSeason = id; selectedSeason = id; episodesJob?.cancel(); episodes = emptyList(); episodesCursor = null
        loadEpisodes(season, true)
    }

    fun loadEpisodes(reset: Boolean) {
        val season = seasons.firstOrNull { it.ref.itemId == selectedSeason } ?: return
        loadEpisodes(season, reset)
    }

    private fun loadEpisodes(season: MediaCatalogSeason, reset: Boolean) {
        val item = ref ?: return
        if (!reset && (episodesLoading || episodesCursor == null)) return
        episodesJob?.cancel(); val cursor = if (reset) null else episodesCursor
        if (reset) { episodes = emptyList(); episodesCursor = null }
        episodesLoading = true; episodesError = null
        episodesJob = scope.launch {
            when (val result = repository.episodes(item, season, limit = 20, cursor = cursor)) {
                is MediaReadResult.Success -> if (result.scope == repository.scope) {
                    val page = result.value
                    episodes = (if (reset) page.items else episodes + page.items).distinctBy { tvMediaKey(it.ref) }
                    episodesCursor = page.nextCursor; episodesError = tvPageError(page.state, page.nextCursor != null)
                }
                is MediaReadResult.Failure -> if (result.scope == repository.scope) {
                    if (result.error.invalidatesTvData()) { episodes = emptyList(); episodesCursor = null }
                    episodesError = tvMediaFailure(result.error)
                }
            }
            coroutineContext.ensureActive(); episodesLoading = false
        }
    }

    fun openSources(item: MediaCatalogRef) {
        sourcesJob?.cancel(); sourceRef = item; variants = emptyList(); sourcesCursor = null; loadSources(true)
    }
    fun closeSources() { sourcesJob?.cancel(); sourceRef = null; variants = emptyList(); sourcesCursor = null; sourcesError = null }
    fun loadSources(reset: Boolean) {
        val item = sourceRef ?: return
        if (!reset && (sourcesLoading || sourcesCursor == null)) return
        sourcesJob?.cancel(); val cursor = if (reset) null else sourcesCursor
        if (reset) { variants = emptyList(); sourcesCursor = null }
        sourcesLoading = true; sourcesError = null
        sourcesJob = scope.launch {
            when (val result = repository.playbackVariants(item, limit = 20, cursor = cursor)) {
                is MediaReadResult.Success -> if (result.scope == repository.scope) {
                    variants = (if (reset) result.value.items else variants + result.value.items).distinctBy(::tvVariantKey)
                    sourcesCursor = result.value.nextCursor
                }
                is MediaReadResult.Failure -> if (result.scope == repository.scope) {
                    if (result.error.invalidatesTvData()) { variants = emptyList(); sourcesCursor = null }
                    sourcesError = tvMediaFailure(result.error)
                }
            }
            coroutineContext.ensureActive(); sourcesLoading = false
        }
    }
}

internal fun tvMediaKey(value: MediaCatalogRef): String = "${value.libraryId}/${value.itemId}"
internal fun tvVariantKey(value: MediaCatalogVariant): String = "${value.editionId}/${value.variantId}/${value.bindingId}"

private fun MediaFailure.invalidatesTvData(): Boolean = kind in setOf(
    MediaFailureKind.AUTHENTICATION, MediaFailureKind.FORBIDDEN, MediaFailureKind.NOT_FOUND,
    MediaFailureKind.CURSOR_STALE, MediaFailureKind.CURSOR_EXPIRED, MediaFailureKind.CONFLICT,
)

private fun tvMediaFailure(error: MediaFailure): String = when (error.kind) {
    MediaFailureKind.AUTHENTICATION -> "登录状态已失效，请重新登录。"
    MediaFailureKind.FORBIDDEN, MediaFailureKind.NOT_FOUND -> "内容不存在或当前账号无权访问。"
    MediaFailureKind.CURSOR_STALE, MediaFailureKind.CURSOR_EXPIRED, MediaFailureKind.CONFLICT -> "目录已变化，请重新加载。"
    MediaFailureKind.NETWORK -> "连接失败，请检查网络后重试。"
    MediaFailureKind.RATE_LIMITED -> "请求过于频繁，请稍后重试。"
    else -> "服务器暂时无法提供影视内容，请稍后重试。"
}

private fun tvPageError(state: MediaCatalogPageState, canContinue: Boolean): String? = when (state) {
    MediaCatalogPageState.READY -> null
    MediaCatalogPageState.PARTIAL -> if (canContinue) "列表尚未读取完成，请加载更多。" else "列表尚未完成，请稍后重试。"
    MediaCatalogPageState.PREPARING, MediaCatalogPageState.DEFERRED -> "列表尚在准备，请稍后重试。"
    MediaCatalogPageState.UNSUPPORTED, MediaCatalogPageState.ERROR -> "列表暂不可用，请稍后重试。"
}
