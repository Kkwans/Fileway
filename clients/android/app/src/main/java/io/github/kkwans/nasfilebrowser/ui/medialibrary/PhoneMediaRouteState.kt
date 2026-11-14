package io.github.kkwans.nasfilebrowser.ui.medialibrary

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

/** Only live, session-bound values and opaque cursors are kept here. */
internal class PhoneMediaRouteState(
    private val session: SessionContext,
    private val repository: MediaLibraryRepository,
    private val scope: CoroutineScope,
) {
    var checking by mutableStateOf(true); private set
    var supported by mutableStateOf(false); private set
    var home by mutableStateOf<MediaCatalogHome?>(null); private set
    var homeProblem by mutableStateOf<MediaProblem?>(null); private set
    var catalog by mutableStateOf<List<MediaCatalogCard>>(emptyList()); private set
    var catalogLoading by mutableStateOf(false); private set
    var catalogProblem by mutableStateOf<MediaProblem?>(null); private set
    var catalogCursor by mutableStateOf<MediaCatalogCursor?>(null); private set
    var filter by mutableStateOf("all"); private set
    var detail by mutableStateOf<MediaCatalogDetail.Complete?>(null); private set
    var detailLoading by mutableStateOf(false); private set
    var detailProblem by mutableStateOf<MediaProblem?>(null); private set
    var userStateBusy by mutableStateOf(false); private set
    var pendingUserState by mutableStateOf<MediaOperation?>(null); private set
    var userStateProblem by mutableStateOf<MediaProblem?>(null); private set
    var seasons by mutableStateOf<List<MediaCatalogSeason>>(emptyList()); private set
    var seasonsLoading by mutableStateOf(false); private set
    var seasonsProblem by mutableStateOf<MediaProblem?>(null); private set
    var seasonsCursor by mutableStateOf<MediaCatalogCursor?>(null); private set
    var selectedSeason by mutableStateOf<String?>(null); private set
    var episodes by mutableStateOf<List<MediaCatalogEpisode>>(emptyList()); private set
    var episodesLoading by mutableStateOf(false); private set
    var episodesProblem by mutableStateOf<MediaProblem?>(null); private set
    var episodesCursor by mutableStateOf<MediaCatalogCursor?>(null); private set
    var sourceRef by mutableStateOf<MediaCatalogRef?>(null); private set
    var variants by mutableStateOf<List<MediaCatalogVariant>>(emptyList()); private set
    var sourcesLoading by mutableStateOf(false); private set
    var sourcesProblem by mutableStateOf<MediaProblem?>(null); private set
    var sourcesCursor by mutableStateOf<MediaCatalogCursor?>(null); private set

    private var detailRef: MediaCatalogRef? = null
    private var detailCursor: MediaCatalogCursor? = null
    private var desiredSeason: String? = null
    private var homeJob: Job? = null
    private var catalogJob: Job? = null
    private var detailJob: Job? = null
    private var seasonsJob: Job? = null
    private var episodesJob: Job? = null
    private var sourcesJob: Job? = null
    private var userStateJob: Job? = null

    fun close() {
        listOf(homeJob, catalogJob, detailJob, seasonsJob, episodesJob, sourcesJob, userStateJob).forEach { it?.cancel() }
    }

    fun start(initialFilter: String) {
        filter = initialFilter.takeIf { it in setOf("all", "movie", "series", "video") } ?: "all"
        refreshHome()
    }

    fun refreshHome() {
        homeJob?.cancel(); catalogJob?.cancel()
        checking = true; supported = false; home = null; homeProblem = null
        catalog = emptyList(); catalogCursor = null; catalogProblem = null; catalogLoading = false
        homeJob = scope.launch {
            try {
                if (session.api.clientCapabilities().mediaLibraryVersion < 1) {
                    checking = false
                    homeProblem = MediaProblem("影视中心暂不可用", "当前服务器尚未启用或暂时无法读取影视中心。", "重试")
                    return@launch
                }
                when (val result = repository.home()) {
                    is MediaReadResult.Success -> if (result.scope == repository.scope) {
                        home = result.value; supported = true; checking = false
                        if (result.value.state != MediaCatalogHomeState.NO_LIBRARIES) loadCatalog(reset = true)
                    }
                    is MediaReadResult.Failure -> if (result.scope == repository.scope) {
                        checking = false; homeProblem = problem(result.error, "无法加载影视中心")
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                checking = false
                homeProblem = MediaProblem("无法连接影视中心", "无法读取服务器能力，请检查连接或账号后重试。", "重试")
            }
        }
    }

    fun chooseFilter(value: String) {
        val next = value.takeIf { it in setOf("all", "movie", "series", "video") } ?: return
        if (filter == next) return
        filter = next
        loadCatalog(reset = true)
    }

    fun loadCatalog(reset: Boolean) {
        if (!supported || (!reset && (catalogLoading || catalogCursor == null))) return
        catalogJob?.cancel()
        val continuation = if (reset) null else catalogCursor
        if (reset) { catalog = emptyList(); catalogCursor = null }
        catalogLoading = true; catalogProblem = null
        val query = MediaCatalogQuery(structureKind = when (filter) {
            "movie" -> MediaCatalogFilterKind.MOVIE; "series" -> MediaCatalogFilterKind.SERIES
            "video" -> MediaCatalogFilterKind.VIDEO; else -> null
        }, sort = MediaCatalogSort.NEWEST, limit = 24)
        catalogJob = scope.launch {
            when (val result = repository.catalog(query, continuation)) {
                is MediaReadResult.Success -> if (result.scope == repository.scope) {
                    val page = result.value
                    catalog = (if (reset) page.items else catalog + page.items).distinctBy { key(it.ref) }
                    catalogCursor = page.nextCursor
                    catalogProblem = pageProblem(page.state, page.nextCursor != null)
                }
                is MediaReadResult.Failure -> if (result.scope == repository.scope) {
                    if (result.error.invalidatesVisibleData()) { catalog = emptyList(); catalogCursor = null }
                    catalogProblem = problem(result.error, "无法加载影视列表")
                }
            }
            coroutineContext.ensureActive()
            catalogLoading = false
        }
    }

    fun openDetail(ref: MediaCatalogRef, restoreSeason: String?) {
        if (detailRef == ref && (detail != null || detailLoading)) return
        detailJob?.cancel(); seasonsJob?.cancel(); episodesJob?.cancel(); closeSources()
        resetUserStateWrite()
        detailRef = ref; desiredSeason = restoreSeason; detailCursor = null
        detail = null; detailProblem = null; seasons = emptyList(); seasonsCursor = null
        selectedSeason = null; episodes = emptyList(); episodesCursor = null
        loadDetail()
    }

    fun closeDetail() {
        detailJob?.cancel(); seasonsJob?.cancel(); episodesJob?.cancel(); closeSources()
        resetUserStateWrite()
        detailRef = null; detail = null; detailCursor = null; selectedSeason = null
    }

    fun loadDetail() {
        val ref = detailRef ?: return
        detailJob?.cancel(); detailLoading = true; detailProblem = null
        val cursor = detailCursor
        detailJob = scope.launch {
            when (val result = repository.catalogDetail(ref, cursor)) {
                is MediaReadResult.Success -> if (result.scope == repository.scope) when (val value = result.value) {
                    is MediaCatalogDetail.Complete -> {
                        detail = value; detailCursor = null
                        if (value.card?.userState != null) pendingUserState = repository.pendingUserStateOperation(ref)
                        if (value.detail.item.kind == MediaItemKind.SERIES) loadSeasons(reset = true)
                    }
                    is MediaCatalogDetail.Partial -> {
                        detailCursor = value.nextCursor
                        detailProblem = MediaProblem("详情尚未加载完成", "继续读取当前条目的可见资料。", "继续加载")
                    }
                }
                is MediaReadResult.Failure -> if (result.scope == repository.scope) {
                    if (result.error.invalidatesVisibleData()) { detail = null; detailCursor = null }
                    detailProblem = problem(result.error, "无法加载影视详情")
                }
            }
            coroutineContext.ensureActive(); detailLoading = false
        }
    }

    private fun resetUserStateWrite() {
        userStateJob?.cancel()
        userStateBusy = false
        pendingUserState = null
        userStateProblem = null
    }

    fun changeUserState(id: String, revision: Long?, favorite: Boolean? = null, watched: Boolean? = null) {
        val ref = detailRef?.takeIf { key(it) == id } ?: return
        val card = detail?.card ?: return
        if (userStateBusy || pendingUserState != null || revision == null || card.userState?.revision != revision) return
        if (favorite != null && !card.actions.can(MediaCatalogAction.FAVORITE) ||
            watched != null && !card.actions.can(MediaCatalogAction.MARK_WATCHED)) return
        val operation = repository.newOperation()
        submitUserState(ref, operation) { repository.updateUserState(operation, ref, revision, favorite, watched) }
    }

    fun retryUserState() {
        val ref = detailRef ?: return
        val pending = pendingUserState
        if (pending == null) {
            userStateProblem = null
            loadDetail()
        } else submitUserState(ref, pending) { repository.retryOperation(pending) }
    }

    private fun submitUserState(ref: MediaCatalogRef, operation: MediaOperation, submit: suspend () -> MediaWriteResult<Any>) {
        if (userStateBusy) return
        userStateBusy = true
        userStateProblem = null
        userStateJob = scope.launch {
            try {
                when (val result = submit()) {
                    is MediaWriteResult.Success -> {
                        pendingUserState = null
                        when (val fresh = repository.getUserState(ref)) {
                            is MediaReadResult.Success -> detail?.let { loaded ->
                                detail = loaded.copy(card = loaded.card?.copy(userState = fresh.value))
                            }
                            is MediaReadResult.Failure -> {
                                if (fresh.error.invalidatesVisibleData()) {
                                    detail = null
                                    detailProblem = problem(fresh.error, "影视详情已变化")
                                }
                                userStateProblem = MediaProblem("操作已保存", "暂时无法刷新观看状态。", "刷新")
                            }
                        }
                        repository.forgetResolvedOperation(operation)
                    }
                    is MediaWriteResult.Failure -> {
                        val unknown = result.error.mutationMayHaveCommitted
                        pendingUserState = operation.takeIf { unknown }
                        userStateProblem = if (unknown) MediaProblem("结果尚待确认", "使用原操作确认，避免重复提交。", "确认")
                        else problem(result.error, "未能更新观看状态")
                        if (!unknown) {
                            repository.forgetResolvedOperation(operation)
                            if (result.error.invalidatesVisibleData()) loadDetail()
                        }
                    }
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                pendingUserState = repository.pendingUserStateOperation(ref)
                userStateProblem = if (pendingUserState != null) MediaProblem("结果尚待确认", "使用原操作确认，避免重复提交。", "确认")
                else MediaProblem("未能更新观看状态", "请刷新详情后重试。", "刷新")
            } finally {
                if (detailRef == ref) userStateBusy = false
            }
        }
    }

    fun loadSeasons(reset: Boolean) {
        val ref = detailRef ?: return
        if (!reset && (seasonsLoading || seasonsCursor == null)) return
        seasonsJob?.cancel(); val cursor = if (reset) null else seasonsCursor
        if (reset) { seasons = emptyList(); seasonsCursor = null }
        seasonsLoading = true; seasonsProblem = null
        seasonsJob = scope.launch {
            when (val result = repository.seasons(ref, limit = 20, cursor = cursor)) {
                is MediaReadResult.Success -> if (result.scope == repository.scope) {
                    val page = result.value
                    seasons = (if (reset) page.items else seasons + page.items).distinctBy { it.ref.itemId }
                    seasonsCursor = page.nextCursor; seasonsProblem = pageProblem(page.state, page.nextCursor != null)
                    val target = desiredSeason?.let { id -> seasons.firstOrNull { it.ref.itemId == id } }
                        ?: if (desiredSeason == null) seasons.firstOrNull() else null
                    if (target != null && selectedSeason != target.ref.itemId) selectSeason(target.ref.itemId)
                }
                is MediaReadResult.Failure -> if (result.scope == repository.scope) {
                    if (result.error.invalidatesVisibleData()) { seasons = emptyList(); seasonsCursor = null; episodes = emptyList() }
                    seasonsProblem = problem(result.error, "无法加载季列表")
                }
            }
            coroutineContext.ensureActive(); seasonsLoading = false
        }
    }

    fun selectSeason(id: String) {
        val season = seasons.firstOrNull { it.ref.itemId == id } ?: return
        desiredSeason = id; selectedSeason = id
        episodesJob?.cancel(); episodes = emptyList(); episodesCursor = null
        loadEpisodes(season, reset = true)
    }

    fun loadEpisodes(reset: Boolean) {
        val season = seasons.firstOrNull { it.ref.itemId == selectedSeason } ?: return
        loadEpisodes(season, reset)
    }

    private fun loadEpisodes(season: MediaCatalogSeason, reset: Boolean) {
        val ref = detailRef ?: return
        if (!reset && (episodesLoading || episodesCursor == null)) return
        episodesJob?.cancel(); val cursor = if (reset) null else episodesCursor
        if (reset) { episodes = emptyList(); episodesCursor = null }
        episodesLoading = true; episodesProblem = null
        episodesJob = scope.launch {
            when (val result = repository.episodes(ref, season, limit = 20, cursor = cursor)) {
                is MediaReadResult.Success -> if (result.scope == repository.scope) {
                    val page = result.value
                    episodes = (if (reset) page.items else episodes + page.items).distinctBy { key(it.ref) }
                    episodesCursor = page.nextCursor; episodesProblem = pageProblem(page.state, page.nextCursor != null)
                }
                is MediaReadResult.Failure -> if (result.scope == repository.scope) {
                    if (result.error.invalidatesVisibleData()) { episodes = emptyList(); episodesCursor = null }
                    episodesProblem = problem(result.error, "无法加载剧集")
                }
            }
            coroutineContext.ensureActive(); episodesLoading = false
        }
    }

    fun openSources(ref: MediaCatalogRef) {
        sourcesJob?.cancel(); sourceRef = ref; variants = emptyList(); sourcesCursor = null
        loadSources(reset = true)
    }

    fun closeSources() {
        sourcesJob?.cancel(); sourceRef = null; variants = emptyList(); sourcesCursor = null; sourcesProblem = null
    }

    fun loadSources(reset: Boolean) {
        val ref = sourceRef ?: return
        if (!reset && (sourcesLoading || sourcesCursor == null)) return
        sourcesJob?.cancel(); val cursor = if (reset) null else sourcesCursor
        if (reset) { variants = emptyList(); sourcesCursor = null }
        sourcesLoading = true; sourcesProblem = null
        sourcesJob = scope.launch {
            when (val result = repository.playbackVariants(ref, limit = 20, cursor = cursor)) {
                is MediaReadResult.Success -> if (result.scope == repository.scope) {
                    variants = (if (reset) result.value.items else variants + result.value.items).distinctBy(::variantKey)
                    sourcesCursor = result.value.nextCursor
                }
                is MediaReadResult.Failure -> if (result.scope == repository.scope) {
                    if (result.error.invalidatesVisibleData()) { variants = emptyList(); sourcesCursor = null }
                    sourcesProblem = problem(result.error, "无法加载片源")
                }
            }
            coroutineContext.ensureActive(); sourcesLoading = false
        }
    }

    fun variant(id: String): MediaCatalogVariant? = variants.firstOrNull { variantKey(it) == id }
}

internal fun key(ref: MediaCatalogRef): String = "${ref.libraryId}/${ref.itemId}"
internal fun variantKey(value: MediaCatalogVariant): String = "${value.editionId}/${value.variantId}/${value.bindingId}"

internal fun MediaFailure.invalidatesVisibleData(): Boolean = kind in setOf(
    MediaFailureKind.AUTHENTICATION, MediaFailureKind.FORBIDDEN, MediaFailureKind.NOT_FOUND,
    MediaFailureKind.CURSOR_STALE, MediaFailureKind.CURSOR_EXPIRED, MediaFailureKind.CONFLICT,
)

internal fun problem(error: MediaFailure, title: String): MediaProblem = MediaProblem(title, when (error.kind) {
    MediaFailureKind.AUTHENTICATION -> "登录状态已失效，请重新登录。"
    MediaFailureKind.FORBIDDEN, MediaFailureKind.NOT_FOUND -> "内容不存在或当前账号无权访问。"
    MediaFailureKind.CURSOR_STALE, MediaFailureKind.CURSOR_EXPIRED, MediaFailureKind.CONFLICT -> "列表已变化，请重新加载。"
    MediaFailureKind.NETWORK -> "连接失败，请检查网络后重试。"
    MediaFailureKind.RATE_LIMITED -> "请求过于频繁，请稍后重试。"
    else -> "服务器暂时无法提供这些内容，请稍后重试。"
}, "重试")

private fun pageProblem(state: MediaCatalogPageState, canContinue: Boolean): MediaProblem? = when (state) {
    MediaCatalogPageState.PARTIAL -> if (canContinue) null else MediaProblem("列表尚未完成", "请稍后刷新。", "重试")
    MediaCatalogPageState.PREPARING, MediaCatalogPageState.DEFERRED -> MediaProblem("列表尚在准备", "请稍后刷新。", "重试")
    MediaCatalogPageState.UNSUPPORTED, MediaCatalogPageState.ERROR -> MediaProblem("列表暂不可用", "请稍后重试。", "重试")
    MediaCatalogPageState.READY -> null
}
