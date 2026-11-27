package io.github.kkwans.nasfilebrowser.ui.medialibrary

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.kkwans.nasfilebrowser.app.SessionContext
import io.github.kkwans.nasfilebrowser.data.*
import io.github.kkwans.nasfilebrowser.data.MediaCredit as CatalogCredit
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
    var homeRefreshing by mutableStateOf(false); private set
    var supported by mutableStateOf(false); private set
    var home by mutableStateOf<MediaCatalogHome?>(null); private set
    var homeProblem by mutableStateOf<MediaProblem?>(null); private set
    var catalog by mutableStateOf<List<MediaCatalogCard>>(emptyList()); private set
    var catalogLoading by mutableStateOf(false); private set
    var catalogProblem by mutableStateOf<MediaProblem?>(null); private set
    var catalogCursor by mutableStateOf<MediaCatalogCursor?>(null); private set
    var catalogPageState by mutableStateOf<MediaCatalogPageState?>(null); private set
    var searchQuery by mutableStateOf(""); private set
    var searchedQuery by mutableStateOf(""); private set
    var searchResults by mutableStateOf<List<MediaCatalogCard>>(emptyList()); private set
    var searchLoading by mutableStateOf(false); private set
    var searchProblem by mutableStateOf<MediaProblem?>(null); private set
    var searchCursor by mutableStateOf<MediaCatalogCursor?>(null); private set
    var searchMode by mutableStateOf(MediaSearchMode.WORKS); private set
    var people by mutableStateOf<List<MediaCatalogPerson>>(emptyList()); private set
    var peopleLoading by mutableStateOf(false); private set
    var peopleProblem by mutableStateOf<MediaProblem?>(null); private set
    var peopleCursor by mutableStateOf<MediaCatalogCursor?>(null); private set
    var files by mutableStateOf<List<MediaCatalogFileMatch>>(emptyList()); private set
    var filesLoading by mutableStateOf(false); private set
    var filesProblem by mutableStateOf<MediaProblem?>(null); private set
    var filesCursor by mutableStateOf<MediaCatalogCursor?>(null); private set
    var collections by mutableStateOf<List<MediaCatalogCollection>>(emptyList()); private set
    var collectionsLoading by mutableStateOf(false); private set
    var collectionsProblem by mutableStateOf<MediaProblem?>(null); private set
    var collectionsCursor by mutableStateOf<MediaCatalogCursor?>(null); private set
    var collection by mutableStateOf<MediaCatalogCollectionRef?>(null); private set
    var collectionName by mutableStateOf(""); private set
    var collectionWorks by mutableStateOf<List<MediaCatalogCard>>(emptyList()); private set
    var collectionWorksLoading by mutableStateOf(false); private set
    var collectionWorksProblem by mutableStateOf<MediaProblem?>(null); private set
    var collectionWorksCursor by mutableStateOf<MediaCatalogCursor?>(null); private set
    var person by mutableStateOf<MediaCatalogPersonRef?>(null); private set
    var personName by mutableStateOf(""); private set
    var personWorks by mutableStateOf<List<MediaCatalogCard>>(emptyList()); private set
    var personWorksLoading by mutableStateOf(false); private set
    var personWorksProblem by mutableStateOf<MediaProblem?>(null); private set
    var personWorksCursor by mutableStateOf<MediaCatalogCursor?>(null); private set
    var credits by mutableStateOf<List<CatalogCredit>>(emptyList()); private set
    var creditsCursor by mutableStateOf<String?>(null); private set
    var creditsLoading by mutableStateOf(false); private set
    var creditsProblem by mutableStateOf<MediaProblem?>(null); private set
    var filter by mutableStateOf("all"); private set
    var libraryFilter by mutableStateOf<String?>(null); private set
    var catalogSort by mutableStateOf(MediaCatalogSort.NEWEST); private set
    var detail by mutableStateOf<MediaCatalogDetail.Complete?>(null); private set
    var technical by mutableStateOf<MediaTechnicalObservation?>(null); private set
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
    var sourcesRecovering by mutableStateOf(false); private set
    var sourcesProblem by mutableStateOf<MediaProblem?>(null); private set
    var sourcesCursor by mutableStateOf<MediaCatalogCursor?>(null); private set

    private var detailRef: MediaCatalogRef? = null
    private var detailCursor: MediaCatalogCursor? = null
    private var desiredSeason: String? = null
    private var homeJob: Job? = null
    private var capabilityKnown = false
    private var retryCatalogPage = false
    private var catalogJob: Job? = null
    private var searchJob: Job? = null
    private var peopleJob: Job? = null
    private var filesJob: Job? = null
    private var collectionsJob: Job? = null
    private var collectionWorksJob: Job? = null
    private var personWorksJob: Job? = null
    private var creditsJob: Job? = null
    private var detailJob: Job? = null
    private var technicalJob: Job? = null
    private var seasonsJob: Job? = null
    private var episodesJob: Job? = null
    private var sourcesJob: Job? = null
    private var sourceRecoveryJob: Job? = null
    private var pendingSourceRecovery: MediaOperation? = null
    private var userStateJob: Job? = null
    private var variantsRef: MediaCatalogRef? = null

    fun close() {
        listOf(homeJob, catalogJob, searchJob, peopleJob, filesJob, collectionsJob, collectionWorksJob, personWorksJob, creditsJob, detailJob, technicalJob, seasonsJob, episodesJob, sourcesJob, userStateJob).forEach { it?.cancel() }
    }

    fun start(initialFilter: String) {
        filter = initialFilter.takeIf { it in setOf("all", "movie", "series", "video") } ?: "all"
        refreshHome()
    }

    fun refreshHome() {
        homeJob?.cancel(); catalogJob?.cancel()
        checking = home == null; homeProblem = null; catalogLoading = false; homeRefreshing = true
        homeJob = scope.launch {
            try {
                if (!capabilityKnown && session.api.clientCapabilities().mediaLibraryVersion < 1) {
                    checking = false
                    homeProblem = MediaProblem("影视中心暂不可用", "当前服务器尚未启用或暂时无法读取影视中心。", "重试")
                    return@launch
                }
                capabilityKnown = true
                supported = true
                // The list does not depend on the home projection. Read both in parallel.
                loadCatalog(reset = true)
                when (val result = repository.home()) {
                    is MediaReadResult.Success -> if (result.scope == repository.scope) {
                        home = result.value; supported = true; checking = false
                    }
                    is MediaReadResult.Failure -> if (result.scope == repository.scope) {
                        if (result.error.invalidatesVisibleData()) {
                            catalogJob?.cancel(); catalogLoading = false
                            supported = false; closeDetail()
                            home = null; catalog = emptyList(); catalogCursor = null; catalogPageState = null
                            searchResults = emptyList(); searchCursor = null
                            people = emptyList(); peopleCursor = null; files = emptyList(); filesCursor = null
                            collections = emptyList(); collectionsCursor = null; collection = null; collectionWorks = emptyList(); collectionWorksCursor = null
                            person = null; personWorks = emptyList(); personWorksCursor = null
                        }
                        checking = false; homeProblem = problem(result.error, "无法加载影视中心")
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                checking = false
                homeProblem = MediaProblem("无法连接影视中心", "无法读取服务器能力，请检查连接或账号后重试。", "重试")
            } finally { homeRefreshing = false }
        }
    }

    fun chooseFilter(value: String) {
        val next = value.takeIf { it in setOf("all", "movie", "series", "video") } ?: return
        chooseQuery(libraryFilter, next)
    }

    fun chooseLibrary(id: String?) {
        chooseQuery(id, filter)
    }

    fun chooseSort(value: MediaCatalogSort) {
        if (value !in phoneMediaCatalogSortOptions(filter) || value == catalogSort) return
        catalogSort = value
        loadCatalog(reset = true, discardCurrent = true)
    }

    fun chooseQuery(id: String?, kind: String) {
        if (kind !in setOf("all", "movie", "series", "video")) return
        if (libraryFilter == id && filter == kind) return
        libraryFilter = id
        filter = kind
        if (catalogSort !in phoneMediaCatalogSortOptions(kind)) catalogSort = MediaCatalogSort.NEWEST
        loadCatalog(reset = true, discardCurrent = true)
        if (searchedQuery.isNotBlank()) when (searchMode) {
            MediaSearchMode.WORKS -> loadSearch(reset = true, discardCurrent = true)
            MediaSearchMode.PEOPLE -> loadPeople(reset = true, discardCurrent = true)
            MediaSearchMode.FILES -> loadFiles(reset = true, discardCurrent = true)
            MediaSearchMode.COLLECTIONS -> loadCollections(reset = true, discardCurrent = true)
        }
    }

    fun chooseSearchMode(mode: MediaSearchMode) {
        if (searchMode == mode) return
        searchMode = mode
        if (searchedQuery.isNotBlank()) when (mode) {
            MediaSearchMode.WORKS -> loadSearch(reset = true, discardCurrent = true)
            MediaSearchMode.PEOPLE -> loadPeople(reset = true, discardCurrent = true)
            MediaSearchMode.FILES -> loadFiles(reset = true, discardCurrent = true)
            MediaSearchMode.COLLECTIONS -> loadCollections(reset = true, discardCurrent = true)
        }
    }

    fun editSearch(value: String) {
        if (value.codePointCount(0, value.length) > 200 || value.toByteArray(Charsets.UTF_8).size > 800 || value.any(Char::isISOControl)) {
            searchProblem = MediaProblem("请输入有效片名", "最多 200 个字符，不要包含控制字符。")
            return
        }
        searchJob?.cancel(); peopleJob?.cancel(); filesJob?.cancel(); collectionsJob?.cancel()
        searchQuery = value; searchedQuery = ""; searchLoading = false; peopleLoading = false; filesLoading = false; collectionsLoading = false
        searchResults = emptyList(); searchCursor = null; searchProblem = null
        people = emptyList(); peopleCursor = null; peopleProblem = null
        files = emptyList(); filesCursor = null; filesProblem = null
        collections = emptyList(); collectionsCursor = null; collectionsProblem = null
    }

    fun submitSearch(value: String) {
        val query = value.trim()
        if (query.isEmpty()) {
            val issue = MediaProblem("请输入搜索内容", when (searchMode) {
                MediaSearchMode.PEOPLE -> "可搜索演员、导演或编剧姓名。"
                MediaSearchMode.FILES -> "可搜索媒体文件名。"
                MediaSearchMode.COLLECTIONS -> "可搜索合集名称。"
                MediaSearchMode.WORKS -> "可搜索作品标题或原名。"
            })
            when (searchMode) {
                MediaSearchMode.PEOPLE -> peopleProblem = issue
                MediaSearchMode.FILES -> filesProblem = issue
                MediaSearchMode.COLLECTIONS -> collectionsProblem = issue
                MediaSearchMode.WORKS -> searchProblem = issue
            }
            return
        }
        editSearch(query)
        searchedQuery = query
        when (searchMode) {
            MediaSearchMode.WORKS -> loadSearch(reset = true, discardCurrent = true)
            MediaSearchMode.PEOPLE -> loadPeople(reset = true, discardCurrent = true)
            MediaSearchMode.FILES -> loadFiles(reset = true, discardCurrent = true)
            MediaSearchMode.COLLECTIONS -> loadCollections(reset = true, discardCurrent = true)
        }
    }

    fun retrySearch() {
        if (searchedQuery.isBlank()) submitSearch(searchQuery)
        else when (searchMode) {
            MediaSearchMode.WORKS -> loadSearch(reset = !searchLoading && searchCursor == null)
            MediaSearchMode.PEOPLE -> loadPeople(reset = !peopleLoading && peopleCursor == null)
            MediaSearchMode.FILES -> loadFiles(reset = !filesLoading && filesCursor == null)
            MediaSearchMode.COLLECTIONS -> loadCollections(reset = !collectionsLoading && collectionsCursor == null)
        }
    }

    fun loadPeople(reset: Boolean, discardCurrent: Boolean = false) {
        if (!supported || searchedQuery.isBlank() || (!reset && (peopleLoading || peopleCursor == null))) return
        peopleJob?.cancel()
        val continuation = if (reset) null else peopleCursor
        if (discardCurrent) { people = emptyList(); peopleCursor = null }
        peopleLoading = true; peopleProblem = null
        peopleJob = scope.launch {
            when (val result = repository.searchPeople(searchedQuery, libraryFilter, cursor = continuation)) {
                is MediaReadResult.Success -> if (result.scope == repository.scope) {
                    val page = result.value
                    people = (if (reset) page.items else people + page.items).distinctBy { "${it.ref.libraryId}/${it.ref.personId}" }
                    peopleCursor = page.nextCursor
                    peopleProblem = pageProblem(page.state, page.nextCursor != null)
                }
                is MediaReadResult.Failure -> if (result.scope == repository.scope) {
                    if (result.error.invalidatesVisibleData()) { people = emptyList(); peopleCursor = null }
                    peopleProblem = problem(result.error, "无法搜索人物")
                }
            }
            coroutineContext.ensureActive(); peopleLoading = false
        }
    }

    fun loadCollections(reset: Boolean, discardCurrent: Boolean = false) {
        if (!supported || searchedQuery.isBlank() || (!reset && (collectionsLoading || collectionsCursor == null))) return
        collectionsJob?.cancel()
        val continuation = if (reset) null else collectionsCursor
        if (discardCurrent) { collections = emptyList(); collectionsCursor = null }
        collectionsLoading = true; collectionsProblem = null
        collectionsJob = scope.launch {
            when (val result = repository.searchCollections(searchedQuery, libraryFilter, cursor = continuation)) {
                is MediaReadResult.Success -> if (result.scope == repository.scope) {
                    val page = result.value
                    collections = (if (reset) page.items else collections + page.items).distinctBy { "${it.ref.libraryId}/${it.ref.collectionId}" }
                    collectionsCursor = page.nextCursor
                    collectionsProblem = pageProblem(page.state, page.nextCursor != null)
                }
                is MediaReadResult.Failure -> if (result.scope == repository.scope) {
                    if (result.error.invalidatesVisibleData()) { collections = emptyList(); collectionsCursor = null }
                    collectionsProblem = problem(result.error, "无法搜索合集")
                }
            }
            coroutineContext.ensureActive(); collectionsLoading = false
        }
    }

    fun openCollection(ref: MediaCatalogCollectionRef, name: String) {
        collectionWorksJob?.cancel()
        collection = ref; collectionName = name
        collectionWorks = emptyList(); collectionWorksCursor = null; collectionWorksProblem = null
        loadCollectionWorks(reset = true)
    }

    fun closeCollection() {
        collectionWorksJob?.cancel()
        collection = null; collectionName = ""; collectionWorks = emptyList(); collectionWorksCursor = null; collectionWorksProblem = null
    }

    fun loadCollectionWorks(reset: Boolean) {
        val ref = collection ?: return
        if (!reset && (collectionWorksLoading || collectionWorksCursor == null)) return
        collectionWorksJob?.cancel()
        val cursor = if (reset) null else collectionWorksCursor
        if (reset) { collectionWorks = emptyList(); collectionWorksCursor = null }
        collectionWorksLoading = true; collectionWorksProblem = null
        collectionWorksJob = scope.launch {
            when (val result = repository.collectionWorks(ref, cursor = cursor)) {
                is MediaReadResult.Success -> if (result.scope == repository.scope) {
                    val page = result.value
                    collectionWorks = (if (reset) page.items else collectionWorks + page.items).distinctBy { key(it.ref) }
                    collectionWorksCursor = page.nextCursor
                    collectionWorksProblem = pageProblem(page.state, page.nextCursor != null)
                }
                is MediaReadResult.Failure -> if (result.scope == repository.scope) {
                    if (result.error.invalidatesVisibleData()) { collectionWorks = emptyList(); collectionWorksCursor = null }
                    collectionWorksProblem = problem(result.error, "无法加载合集作品")
                }
            }
            coroutineContext.ensureActive(); collectionWorksLoading = false
        }
    }

    fun loadFiles(reset: Boolean, discardCurrent: Boolean = false) {
        if (!supported || searchedQuery.isBlank() || (!reset && (filesLoading || filesCursor == null))) return
        filesJob?.cancel()
        val continuation = if (reset) null else filesCursor
        if (discardCurrent) { files = emptyList(); filesCursor = null }
        filesLoading = true; filesProblem = null
        filesJob = scope.launch {
            when (val result = repository.searchFiles(searchedQuery, libraryFilter, cursor = continuation)) {
                is MediaReadResult.Success -> if (result.scope == repository.scope) {
                    val page = result.value
                    files = (if (reset) page.items else files + page.items).distinctBy { it.fileId }
                    filesCursor = page.nextCursor
                    filesProblem = pageProblem(page.state, page.nextCursor != null)
                }
                is MediaReadResult.Failure -> if (result.scope == repository.scope) {
                    if (result.error.invalidatesVisibleData()) { files = emptyList(); filesCursor = null }
                    filesProblem = problem(result.error, "无法搜索文件")
                }
            }
            coroutineContext.ensureActive(); filesLoading = false
        }
    }

    fun openPerson(ref: MediaCatalogPersonRef, name: String) {
        personWorksJob?.cancel()
        person = ref; personName = name
        personWorks = emptyList(); personWorksCursor = null; personWorksProblem = null
        loadPersonWorks(reset = true)
    }

    fun closePerson() {
        personWorksJob?.cancel()
        person = null; personName = ""; personWorks = emptyList(); personWorksCursor = null; personWorksProblem = null
    }

    fun openCredits() {
        creditsJob?.cancel()
        creditsLoading = false
        credits = detail?.detail?.credits?.items.orEmpty().distinctBy { it.personId }
        creditsCursor = detail?.detail?.credits?.nextCursor
        creditsProblem = null
        if (creditsCursor != null) loadCredits(reset = false)
    }

    fun closeCredits() {
        creditsJob?.cancel()
        credits = emptyList(); creditsCursor = null; creditsProblem = null; creditsLoading = false
    }

    fun loadCredits(reset: Boolean) {
        val ref = detailRef ?: return
        val revision = detail?.detail?.item?.revision ?: return
        if (!reset && (creditsLoading || creditsCursor == null)) return
        creditsJob?.cancel()
        val cursor = if (reset) null else creditsCursor
        if (reset) { credits = emptyList(); creditsCursor = null }
        creditsLoading = true; creditsProblem = null
        creditsJob = scope.launch {
            when (val result = repository.listItemCredits(ref.libraryId, ref.itemId, revision, limit = 50, cursor = cursor)) {
                is MediaReadResult.Success -> if (result.scope == repository.scope) {
                    val page = result.value
                    credits = (if (reset) page.items else credits + page.items).distinctBy { it.personId }
                    creditsCursor = page.nextCursor
                }
                is MediaReadResult.Failure -> if (result.scope == repository.scope) {
                    if (result.error.invalidatesVisibleData()) { credits = emptyList(); creditsCursor = null }
                    creditsProblem = problem(result.error, "无法加载演职人员")
                }
            }
            coroutineContext.ensureActive(); creditsLoading = false
        }
    }

    fun loadPersonWorks(reset: Boolean) {
        val ref = person ?: return
        if (!reset && (personWorksLoading || personWorksCursor == null)) return
        personWorksJob?.cancel()
        val cursor = if (reset) null else personWorksCursor
        if (reset) { personWorks = emptyList(); personWorksCursor = null }
        personWorksLoading = true; personWorksProblem = null
        personWorksJob = scope.launch {
            when (val result = repository.personWorks(ref, cursor = cursor)) {
                is MediaReadResult.Success -> if (result.scope == repository.scope) {
                    val page = result.value
                    personWorks = (if (reset) page.items else personWorks + page.items).distinctBy { key(it.ref) }
                    personWorksCursor = page.nextCursor
                    personWorksProblem = pageProblem(page.state, page.nextCursor != null)
                }
                is MediaReadResult.Failure -> if (result.scope == repository.scope) {
                    if (result.error.invalidatesVisibleData()) { personWorks = emptyList(); personWorksCursor = null }
                    personWorksProblem = problem(result.error, "无法加载人物作品")
                }
            }
            coroutineContext.ensureActive(); personWorksLoading = false
        }
    }

    fun loadSearch(reset: Boolean, discardCurrent: Boolean = false) {
        if (!supported || searchedQuery.isBlank() || (!reset && (searchLoading || searchCursor == null))) return
        searchJob?.cancel()
        val continuation = if (reset) null else searchCursor
        if (discardCurrent) { searchResults = emptyList(); searchCursor = null }
        searchLoading = true; searchProblem = null
        val query = MediaCatalogQuery(libraryId = libraryFilter, query = searchedQuery, sort = MediaCatalogSort.ID, limit = 24)
        searchJob = scope.launch {
            when (val result = repository.catalog(query, continuation)) {
                is MediaReadResult.Success -> if (result.scope == repository.scope) {
                    val page = result.value
                    searchResults = (if (reset) page.items else searchResults + page.items).distinctBy { key(it.ref) }
                    searchCursor = page.nextCursor
                    searchProblem = pageProblem(page.state, page.nextCursor != null)
                }
                is MediaReadResult.Failure -> if (result.scope == repository.scope) {
                    if (result.error.invalidatesVisibleData()) { searchResults = emptyList(); searchCursor = null }
                    searchProblem = problem(result.error, "无法搜索影视")
                }
            }
            coroutineContext.ensureActive(); searchLoading = false
        }
    }

    fun retryCatalog() = loadCatalog(reset = !retryCatalogPage)

    fun loadCatalog(reset: Boolean, discardCurrent: Boolean = false) {
        if (!supported || (!reset && (catalogLoading || catalogCursor == null))) return
        catalogJob?.cancel()
        val continuation = if (reset) null else catalogCursor
        if (discardCurrent) { catalog = emptyList(); catalogCursor = null; catalogPageState = null }
        catalogLoading = true; catalogProblem = null
        val query = MediaCatalogQuery(libraryId = libraryFilter, structureKind = when (filter) {
            "movie" -> MediaCatalogFilterKind.MOVIE; "series" -> MediaCatalogFilterKind.SERIES
            "video" -> MediaCatalogFilterKind.VIDEO; else -> null
        }, sort = catalogSort, limit = 24)
        catalogJob = scope.launch {
            when (val result = repository.catalog(query, continuation)) {
                is MediaReadResult.Success -> if (result.scope == repository.scope) {
                    val page = result.value
                    catalog = (if (reset) page.items else catalog + page.items).distinctBy { key(it.ref) }
                    catalogCursor = page.nextCursor
                    catalogPageState = page.state
                    retryCatalogPage = false
                    catalogProblem = pageProblem(page.state, page.nextCursor != null)
                    if (page.items.isNotEmpty()) checking = false
                }
                is MediaReadResult.Failure -> if (result.scope == repository.scope) {
                    if (result.error.invalidatesVisibleData()) { catalog = emptyList(); catalogCursor = null; catalogPageState = null }
                    retryCatalogPage = !reset && catalogCursor != null
                    catalogProblem = problem(result.error, "无法加载影视列表")
                }
            }
            coroutineContext.ensureActive()
            catalogLoading = false
        }
    }

    fun openDetail(ref: MediaCatalogRef, restoreSeason: String?) {
        if (detailRef == ref && (detail != null || detailLoading)) return
        detailJob?.cancel(); technicalJob?.cancel(); seasonsJob?.cancel(); episodesJob?.cancel(); closeSources(); closeCredits()
        resetUserStateWrite()
        detailRef = ref; desiredSeason = restoreSeason; detailCursor = null
        variants = emptyList(); variantsRef = null; sourcesCursor = null
        detail = null; technical = null; detailProblem = null; seasons = emptyList(); seasonsCursor = null
        selectedSeason = null; episodes = emptyList(); episodesCursor = null
        loadDetail()
    }

    fun closeDetail() {
        detailJob?.cancel(); technicalJob?.cancel(); seasonsJob?.cancel(); episodesJob?.cancel(); closeSources(); closeCredits()
        resetUserStateWrite()
        detailRef = null; detail = null; technical = null; detailCursor = null; selectedSeason = null
        variants = emptyList(); variantsRef = null; sourcesCursor = null
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
                        loadTechnical(ref)
                        if (value.card?.actions?.can(MediaCatalogAction.PLAY) == true) loadDetailSources(ref)
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

    private fun loadTechnical(ref: MediaCatalogRef) {
        technicalJob?.cancel()
        technicalJob = scope.launch {
            when (val result = repository.identification(ref)) {
                is MediaReadResult.Success -> if (result.scope == repository.scope && detailRef == ref) {
                    technical = result.value.file?.technical
                }
                is MediaReadResult.Failure -> if (result.scope == repository.scope && detailRef == ref) {
                    technical = null
                }
            }
        }
    }

    private fun loadDetailSources(ref: MediaCatalogRef) {
        if (variantsRef == ref && !sourcesLoading) return
        loadSourcesFor(ref, reset = true)
    }

    fun retryPlayback() {
        val ref = detailRef ?: return
        loadSourcesFor(ref, reset = true)
    }

    fun sourcesFor(id: String): List<MediaCatalogVariant> = variants.takeIf { variantsRef?.let(::key) == id }.orEmpty()
    fun sourceProblem(id: String): MediaProblem? = sourcesProblem.takeIf { variantsRef?.let(::key) == id }
    fun loadingSourcesFor(id: String): Boolean = sourcesLoading && variantsRef?.let(::key) == id
    fun hasMoreSourcesFor(id: String): Boolean = variantsRef?.let(::key) == id && sourcesCursor != null

    fun openSources(ref: MediaCatalogRef) {
        sourcesJob?.cancel()
        sourceRef = ref
        if (variantsRef != ref || sourcesLoading || sourcesProblem != null) loadSourcesFor(ref, reset = true)
    }

    fun closeSources() {
        sourcesJob?.cancel(); sourceRef = null; sourcesProblem = null; sourcesLoading = false
    }

    fun recoverSource(id: String) {
        if (sourcesRecovering) return
        pendingSourceRecovery?.let { operation ->
            submitSourceRecovery(operation)
            return
        }
        val variant = variant(id) ?: return
        when (variant.recoveryAction) {
            "retry" -> loadSources(reset = true)
            "permission" -> sourcesProblem = MediaProblem("需要来源权限", "请联系媒体库所有者检查共享权限后重试。")
            "rescan" -> {
                val ref = sourceRef ?: return
                sourcesRecovering = true; sourcesProblem = null
                sourceRecoveryJob = scope.launch {
                    try {
                        val support = repository.scanSupport()
                        if (support !is MediaReadResult.Success || !support.value.available) {
                            sourcesProblem = MediaProblem("暂时无法重新扫描", "服务器未提供可用的本地扫描能力。")
                            return@launch
                        }
                        var cursor: String? = null
                        var binding: MediaLibrarySource? = null
                        do {
                            when (val result = repository.listSources(ref.libraryId, cursor = cursor)) {
                                is MediaReadResult.Success -> {
                                    binding = result.value.items.firstOrNull { it.id == variant.bindingId } ?: binding
                                    cursor = result.value.nextCursor
                                }
                                is MediaReadResult.Failure -> {
                                    sourcesProblem = problem(result.error, "无法读取媒体库来源")
                                    return@launch
                                }
                            }
                        } while (cursor != null && binding == null)
                        val source = binding
                        if (source == null) {
                            sourcesProblem = MediaProblem("来源已变化", "请关闭片源面板并刷新影视详情。")
                            return@launch
                        }
                        val operation = repository.newOperation()
                        when (val result = repository.startScan(operation, ref.libraryId, source.id, source.revision, support.value.localRulesRevision)) {
                            is MediaWriteResult.Success -> {
                                pendingSourceRecovery = null
                                repository.forgetResolvedOperation(operation)
                                sourcesProblem = MediaProblem("已开始重新扫描", "扫描完成后请重新打开片源面板。")
                                refreshHome()
                            }
                            is MediaWriteResult.Failure -> {
                                val unknown = result.error.mutationMayHaveCommitted
                                pendingSourceRecovery = operation.takeIf { unknown }
                                if (unknown) sourcesProblem = MediaProblem("扫描结果尚待确认", "再次选择重新扫描以确认原操作。", "确认")
                                else {
                                    repository.forgetResolvedOperation(operation)
                                    sourcesProblem = problem(result.error, "无法开始重新扫描")
                                }
                            }
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) {
                        sourcesProblem = MediaProblem("无法开始重新扫描", "请检查连接后重试。")
                    } finally { sourcesRecovering = false }
                }
            }
        }
    }

    private fun submitSourceRecovery(operation: MediaOperation) {
        sourcesRecovering = true; sourcesProblem = null
        sourceRecoveryJob = scope.launch {
            try {
                when (val result = repository.retryOperation(operation)) {
                    is MediaWriteResult.Success -> {
                        pendingSourceRecovery = null
                        repository.forgetResolvedOperation(operation)
                        sourcesProblem = MediaProblem("已开始重新扫描", "扫描完成后请重新打开片源面板。")
                        refreshHome()
                    }
                    is MediaWriteResult.Failure -> {
                        val unknown = result.error.mutationMayHaveCommitted
                        pendingSourceRecovery = operation.takeIf { unknown }
                        sourcesProblem = if (unknown) MediaProblem("扫描结果尚待确认", "再次选择重新扫描以确认原操作。", "确认")
                        else {
                            repository.forgetResolvedOperation(operation)
                            problem(result.error, "无法开始重新扫描")
                        }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                pendingSourceRecovery = operation
                sourcesProblem = MediaProblem("扫描结果尚待确认", "再次选择重新扫描以确认原操作。", "确认")
            } finally { sourcesRecovering = false }
        }
    }

    fun loadSources(reset: Boolean) {
        val ref = sourceRef ?: return
        loadSourcesFor(ref, reset)
    }
    private fun loadSourcesFor(ref: MediaCatalogRef, reset: Boolean) {
        if (!reset && (sourcesLoading || sourcesCursor == null)) return
        sourcesJob?.cancel(); val cursor = if (reset) null else sourcesCursor
        if (reset) { variants = emptyList(); sourcesCursor = null }
        variantsRef = ref
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
