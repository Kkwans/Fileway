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
    var homeRefreshing by mutableStateOf(false); private set
    var supported by mutableStateOf(false); private set
    var home by mutableStateOf<MediaCatalogHome?>(null); private set
    var homeRows by mutableStateOf<List<TvMediaHomeRowState<MediaCatalogCard>>>(emptyList()); private set
    var homeError by mutableStateOf<String?>(null); private set
    var filter by mutableStateOf("all"); private set
    var catalog by mutableStateOf<List<MediaCatalogCard>>(emptyList()); private set
    var catalogLoading by mutableStateOf(false); private set
    var catalogError by mutableStateOf<String?>(null); private set
    var catalogCursor by mutableStateOf<MediaCatalogCursor?>(null); private set
    var searchQuery by mutableStateOf(""); private set
    var searchedQuery by mutableStateOf(""); private set
    var searchResults by mutableStateOf<List<MediaCatalogCard>>(emptyList()); private set
    var searchLoading by mutableStateOf(false); private set
    var searchError by mutableStateOf<String?>(null); private set
    var searchCursor by mutableStateOf<MediaCatalogCursor?>(null); private set
    var searchMode by mutableStateOf(TvMediaSearchMode.WORKS); private set
    var people by mutableStateOf<List<MediaCatalogPerson>>(emptyList()); private set
    var peopleLoading by mutableStateOf(false); private set
    var peopleError by mutableStateOf<String?>(null); private set
    var peopleCursor by mutableStateOf<MediaCatalogCursor?>(null); private set
    var files by mutableStateOf<List<MediaCatalogFileMatch>>(emptyList()); private set
    var filesLoading by mutableStateOf(false); private set
    var filesError by mutableStateOf<String?>(null); private set
    var filesCursor by mutableStateOf<MediaCatalogCursor?>(null); private set
    var collections by mutableStateOf<List<MediaCatalogCollection>>(emptyList()); private set
    var collectionsLoading by mutableStateOf(false); private set
    var collectionsError by mutableStateOf<String?>(null); private set
    var collectionsCursor by mutableStateOf<MediaCatalogCursor?>(null); private set
    var collectionRef by mutableStateOf<MediaCatalogCollectionRef?>(null); private set
    var collectionName by mutableStateOf(""); private set
    var collectionWorks by mutableStateOf<List<MediaCatalogCard>>(emptyList()); private set
    var collectionWorksLoading by mutableStateOf(false); private set
    var collectionWorksError by mutableStateOf<String?>(null); private set
    var collectionWorksCursor by mutableStateOf<MediaCatalogCursor?>(null); private set
    var personRef by mutableStateOf<MediaCatalogPersonRef?>(null); private set
    var personName by mutableStateOf(""); private set
    var personWorks by mutableStateOf<List<MediaCatalogCard>>(emptyList()); private set
    var personWorksLoading by mutableStateOf(false); private set
    var personWorksError by mutableStateOf<String?>(null); private set
    var personWorksCursor by mutableStateOf<MediaCatalogCursor?>(null); private set
    var detail by mutableStateOf<MediaCatalogDetail.Complete?>(null); private set
    var technical by mutableStateOf<MediaTechnicalObservation?>(null); private set
    var detailLoading by mutableStateOf(false); private set
    var detailError by mutableStateOf<String?>(null); private set
    var userStateBusy by mutableStateOf(false); private set
    var userStateError by mutableStateOf<String?>(null); private set
    val userStatePending: Boolean get() = pendingUserState != null
    var credits by mutableStateOf<List<MediaCredit>>(emptyList()); private set
    var creditsCursor by mutableStateOf<String?>(null); private set
    var creditsLoading by mutableStateOf(false); private set
    var creditsError by mutableStateOf<String?>(null); private set
    var seasons by mutableStateOf<List<MediaCatalogSeason>>(emptyList()); private set
    var seasonsLoading by mutableStateOf(false); private set
    var seasonsCursor by mutableStateOf<MediaCatalogCursor?>(null); private set
    var seasonsError by mutableStateOf<String?>(null); private set
    var selectedSeason by mutableStateOf<String?>(null); private set
    var episodes by mutableStateOf<List<MediaCatalogEpisode>>(emptyList()); private set
    var episodesLoading by mutableStateOf(false); private set
    var episodesCursor by mutableStateOf<MediaCatalogCursor?>(null); private set
    var episodesError by mutableStateOf<String?>(null); private set
    var jumpedEpisodeId by mutableStateOf<String?>(null); private set
    var sourceRef by mutableStateOf<MediaCatalogRef?>(null); private set
    var variants by mutableStateOf<List<MediaCatalogVariant>>(emptyList()); private set
    var sourcesLoading by mutableStateOf(false); private set
    var sourcesRecovering by mutableStateOf(false); private set
    var sourcesCursor by mutableStateOf<MediaCatalogCursor?>(null); private set
    var sourcesError by mutableStateOf<String?>(null); private set

    private var ref: MediaCatalogRef? = null
    private var detailCursor: MediaCatalogCursor? = null
    private var desiredSeason: String? = null
    private var homeJob: Job? = null
    private val homeRowJobs = mutableMapOf<MediaCatalogRowId, Job>()
    private var capabilityKnown = false
    private var retryCatalogPage = false
    private var catalogJob: Job? = null
    private var searchJob: Job? = null
    private var peopleJob: Job? = null
    private var filesJob: Job? = null
    private var collectionsJob: Job? = null
    private var collectionWorksJob: Job? = null
    private var personWorksJob: Job? = null
    private var detailJob: Job? = null
    private var technicalJob: Job? = null
    private var creditsJob: Job? = null
    private var seasonsJob: Job? = null
    private var episodesJob: Job? = null
    private var sourcesJob: Job? = null
    private var sourceRecoveryJob: Job? = null
    private var pendingSourceRecovery: MediaOperation? = null
    private var pendingUserState: MediaOperation? = null
    private var userStateJob: Job? = null
    private var variantsRef: MediaCatalogRef? = null

    fun close() { (listOf(homeJob, catalogJob, searchJob, peopleJob, filesJob, collectionsJob, collectionWorksJob, personWorksJob, detailJob, technicalJob, creditsJob, seasonsJob, episodesJob, sourcesJob, sourceRecoveryJob, userStateJob) + homeRowJobs.values).forEach { it?.cancel() } }

    fun start(savedFilter: String) {
        filter = savedFilter.takeIf { it in setOf("all", "movie", "series", "video") } ?: "all"
        refreshHome()
    }

    fun refreshHome() {
        homeJob?.cancel(); catalogJob?.cancel()
        homeRowJobs.values.forEach { it.cancel() }; homeRowJobs.clear()
        homeRows = homeRows.map { it.copy(loading = false) }
        checking = home == null; homeError = null; catalogLoading = false; homeRefreshing = true
        homeJob = scope.launch {
            try {
                if (!capabilityKnown && session.api.clientCapabilities().mediaLibraryVersion < 1) {
                    checking = false; homeError = "服务器尚未启用影视中心，或当前无法读取。"; return@launch
                }
                capabilityKnown = true; supported = true
                loadCatalog(true)
                when (val result = repository.home()) {
                    is MediaReadResult.Success -> if (result.scope == repository.scope) {
                        home = result.value
                        syncHomeRows(result.value.rows)
                        checking = false; supported = true
                    }
                    is MediaReadResult.Failure -> if (result.scope == repository.scope) {
                        if (result.error.invalidatesTvData()) {
                            catalogJob?.cancel(); catalogLoading = false
                            supported = false; closeDetail()
                            home = null; homeRows = emptyList(); catalog = emptyList(); catalogCursor = null
                            searchResults = emptyList(); searchCursor = null
                            people = emptyList(); peopleCursor = null; files = emptyList(); filesCursor = null
                            collections = emptyList(); collectionsCursor = null; collectionRef = null; collectionWorks = emptyList(); collectionWorksCursor = null
                            personRef = null; personWorks = emptyList(); personWorksCursor = null
                        }
                        checking = false; homeError = tvMediaFailure(result.error)
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { checking = false; homeError = "无法读取服务器能力，请检查连接后重试。" }
            finally { homeRefreshing = false }
        }
    }

    private fun syncHomeRows(rows: List<MediaCatalogHomeRow>) {
        val synced = syncTvMediaHomeRows(homeRows, rows.mapNotNull(::tvMediaHomeRowState))
        homeRowJobs.keys.retainAll(synced.map { it.rowId }.toSet())
        homeRows = synced
    }

    fun retryHomeRow(rowId: MediaCatalogRowId) {
        homeRows.firstOrNull { it.rowId == rowId }?.let { loadHomeRow(rowId, it.retryAppend) }
    }

    fun loadHomeRow(rowId: MediaCatalogRowId, append: Boolean) {
        if (!supported) return
        val current = homeRows.firstOrNull { it.rowId == rowId } ?: return
        if (current.loading || (append && current.cursor == null)) return
        homeRowJobs[rowId]?.cancel()
        val cursor = if (append) current.cursor else null
        updateHomeRow(rowId) { it.copy(loading = true, error = null, retryAppend = false) }
        homeRowJobs[rowId] = scope.launch {
            when (val result = repository.homeRow(rowId, cursor)) {
                is MediaReadResult.Success -> if (result.scope == repository.scope) {
                    val incoming = tvMediaHomeRowState(result.value) ?: return@launch
                    updateHomeRow(rowId) { latest ->
                        if (append && incoming.items.isEmpty() && incoming.error != null) {
                            latest.copy(loading = false, error = incoming.error, retryAppend = true)
                        } else if (!append && incoming.items.isEmpty() && incoming.error != null && latest.items.isNotEmpty()) {
                            latest.copy(cursor = incoming.cursor, hasMore = incoming.hasMore,
                                loading = false, error = incoming.error, retryAppend = false)
                        } else mergeTvMediaHomeRow(latest, incoming, append) { tvMediaKey(it.ref) }
                    }
                }
                is MediaReadResult.Failure -> if (result.scope == repository.scope) {
                    if (result.error.invalidatesTvData()) {
                        if (result.error.kind in setOf(MediaFailureKind.CURSOR_STALE, MediaFailureKind.CURSOR_EXPIRED, MediaFailureKind.CONFLICT)) {
                            updateHomeRow(rowId) { it.copy(cursor = null, hasMore = false, loading = false,
                                error = tvMediaFailure(result.error), retryAppend = false) }
                        } else homeRows = homeRows.filterNot { it.rowId == rowId }
                    } else updateHomeRow(rowId) { it.copy(loading = false,
                        error = tvMediaFailure(result.error), retryAppend = append) }
                }
            }
            coroutineContext.ensureActive()
            updateHomeRow(rowId) { it.copy(loading = false) }
        }
    }

    private fun updateHomeRow(rowId: MediaCatalogRowId, transform: (TvMediaHomeRowState<MediaCatalogCard>) -> TvMediaHomeRowState<MediaCatalogCard>) {
        homeRows = homeRows.map { if (it.rowId == rowId) transform(it) else it }
    }

    fun selectFilter(value: String) {
        if (value !in setOf("all", "movie", "series", "video") || value == filter) return
        filter = value; loadCatalog(true)
        if (searchedQuery.isNotBlank()) when (searchMode) {
            TvMediaSearchMode.WORKS -> loadSearch(true, discardCurrent = true)
            TvMediaSearchMode.PEOPLE -> loadPeople(true, discardCurrent = true)
            TvMediaSearchMode.FILES -> loadFiles(true, discardCurrent = true)
            TvMediaSearchMode.COLLECTIONS -> loadCollections(true, discardCurrent = true)
        }
    }

    fun chooseSearchMode(mode: TvMediaSearchMode) {
        if (searchMode == mode) return
        searchMode = mode
        if (searchedQuery.isNotBlank()) when (mode) {
            TvMediaSearchMode.WORKS -> loadSearch(true, discardCurrent = true)
            TvMediaSearchMode.PEOPLE -> loadPeople(true, discardCurrent = true)
            TvMediaSearchMode.FILES -> loadFiles(true, discardCurrent = true)
            TvMediaSearchMode.COLLECTIONS -> loadCollections(true, discardCurrent = true)
        }
    }

    fun editSearch(value: String) {
        if (value.codePointCount(0, value.length) > 200 || value.toByteArray(Charsets.UTF_8).size > 800 || value.any(Char::isISOControl)) {
            searchError = "请输入有效搜索内容，最多 200 个字符。"
            return
        }
        searchJob?.cancel(); peopleJob?.cancel(); filesJob?.cancel(); collectionsJob?.cancel()
        searchQuery = value; searchedQuery = ""; searchLoading = false; peopleLoading = false; filesLoading = false; collectionsLoading = false
        searchResults = emptyList(); searchCursor = null; searchError = null
        people = emptyList(); peopleCursor = null; peopleError = null
        files = emptyList(); filesCursor = null; filesError = null
        collections = emptyList(); collectionsCursor = null; collectionsError = null
    }

    fun submitSearch(value: String) {
        val query = value.trim()
        if (query.isEmpty()) {
            searchError = when (searchMode) {
                TvMediaSearchMode.PEOPLE -> "请输入演员、导演或编剧姓名。"
                TvMediaSearchMode.FILES -> "请输入媒体文件名。"
                TvMediaSearchMode.COLLECTIONS -> "请输入合集名称。"
                TvMediaSearchMode.WORKS -> "请输入作品标题或原名。"
            }
            return
        }
        editSearch(query); searchedQuery = query
        when (searchMode) {
            TvMediaSearchMode.WORKS -> loadSearch(true, discardCurrent = true)
            TvMediaSearchMode.PEOPLE -> loadPeople(true, discardCurrent = true)
            TvMediaSearchMode.FILES -> loadFiles(true, discardCurrent = true)
            TvMediaSearchMode.COLLECTIONS -> loadCollections(true, discardCurrent = true)
        }
    }

    fun retrySearch() {
        if (searchedQuery.isBlank()) submitSearch(searchQuery)
        else when (searchMode) {
            TvMediaSearchMode.WORKS -> loadSearch(reset = !searchLoading && searchCursor == null)
            TvMediaSearchMode.PEOPLE -> loadPeople(reset = !peopleLoading && peopleCursor == null)
            TvMediaSearchMode.FILES -> loadFiles(reset = !filesLoading && filesCursor == null)
            TvMediaSearchMode.COLLECTIONS -> loadCollections(reset = !collectionsLoading && collectionsCursor == null)
        }
    }

    fun loadPeople(reset: Boolean, discardCurrent: Boolean = false) {
        if (!supported || searchedQuery.isBlank() || (!reset && (peopleLoading || peopleCursor == null))) return
        peopleJob?.cancel()
        val cursor = if (reset) null else peopleCursor
        if (discardCurrent) { people = emptyList(); peopleCursor = null }
        peopleLoading = true; peopleError = null
        peopleJob = scope.launch {
            when (val result = repository.searchPeople(searchedQuery, cursor = cursor)) {
                is MediaReadResult.Success -> if (result.scope == repository.scope) {
                    val page = result.value
                    people = (if (reset) page.items else people + page.items).distinctBy { "${it.ref.libraryId}/${it.ref.personId}" }
                    peopleCursor = page.nextCursor; peopleError = tvPageError(page.state, page.nextCursor != null)
                }
                is MediaReadResult.Failure -> if (result.scope == repository.scope) {
                    if (result.error.invalidatesTvData()) { people = emptyList(); peopleCursor = null }
                    peopleError = tvMediaFailure(result.error)
                }
            }
            coroutineContext.ensureActive(); peopleLoading = false
        }
    }

    fun loadCollections(reset: Boolean, discardCurrent: Boolean = false) {
        if (!supported || searchedQuery.isBlank() || (!reset && (collectionsLoading || collectionsCursor == null))) return
        collectionsJob?.cancel()
        val cursor = if (reset) null else collectionsCursor
        if (discardCurrent) { collections = emptyList(); collectionsCursor = null }
        collectionsLoading = true; collectionsError = null
        collectionsJob = scope.launch {
            when (val result = repository.searchCollections(searchedQuery, cursor = cursor)) {
                is MediaReadResult.Success -> if (result.scope == repository.scope) {
                    val page = result.value
                    collections = (if (reset) page.items else collections + page.items).distinctBy { "${it.ref.libraryId}/${it.ref.collectionId}" }
                    collectionsCursor = page.nextCursor; collectionsError = tvPageError(page.state, page.nextCursor != null)
                }
                is MediaReadResult.Failure -> if (result.scope == repository.scope) {
                    if (result.error.invalidatesTvData()) { collections = emptyList(); collectionsCursor = null }
                    collectionsError = tvMediaFailure(result.error)
                }
            }
            coroutineContext.ensureActive(); collectionsLoading = false
        }
    }

    fun openCollection(ref: MediaCatalogCollectionRef, name: String) {
        collectionWorksJob?.cancel()
        collectionRef = ref; collectionName = name
        collectionWorks = emptyList(); collectionWorksCursor = null; collectionWorksError = null
        loadCollectionWorks(true)
    }

    fun closeCollection() {
        collectionWorksJob?.cancel()
        collectionRef = null; collectionName = ""; collectionWorks = emptyList(); collectionWorksCursor = null; collectionWorksError = null
    }

    fun loadCollectionWorks(reset: Boolean) {
        val item = collectionRef ?: return
        if (!reset && (collectionWorksLoading || collectionWorksCursor == null)) return
        collectionWorksJob?.cancel(); val cursor = if (reset) null else collectionWorksCursor
        if (reset) { collectionWorks = emptyList(); collectionWorksCursor = null }
        collectionWorksLoading = true; collectionWorksError = null
        collectionWorksJob = scope.launch {
            when (val result = repository.collectionWorks(item, cursor = cursor)) {
                is MediaReadResult.Success -> if (result.scope == repository.scope) {
                    val page = result.value
                    collectionWorks = (if (reset) page.items else collectionWorks + page.items).distinctBy { tvMediaKey(it.ref) }
                    collectionWorksCursor = page.nextCursor; collectionWorksError = tvPageError(page.state, page.nextCursor != null)
                }
                is MediaReadResult.Failure -> if (result.scope == repository.scope) {
                    if (result.error.invalidatesTvData()) { collectionWorks = emptyList(); collectionWorksCursor = null }
                    collectionWorksError = tvMediaFailure(result.error)
                }
            }
            coroutineContext.ensureActive(); collectionWorksLoading = false
        }
    }

    fun loadFiles(reset: Boolean, discardCurrent: Boolean = false) {
        if (!supported || searchedQuery.isBlank() || (!reset && (filesLoading || filesCursor == null))) return
        filesJob?.cancel()
        val cursor = if (reset) null else filesCursor
        if (discardCurrent) { files = emptyList(); filesCursor = null }
        filesLoading = true; filesError = null
        filesJob = scope.launch {
            when (val result = repository.searchFiles(searchedQuery, cursor = cursor)) {
                is MediaReadResult.Success -> if (result.scope == repository.scope) {
                    val page = result.value
                    files = (if (reset) page.items else files + page.items).distinctBy { it.fileId }
                    filesCursor = page.nextCursor; filesError = tvPageError(page.state, page.nextCursor != null)
                }
                is MediaReadResult.Failure -> if (result.scope == repository.scope) {
                    if (result.error.invalidatesTvData()) { files = emptyList(); filesCursor = null }
                    filesError = tvMediaFailure(result.error)
                }
            }
            coroutineContext.ensureActive(); filesLoading = false
        }
    }

    fun openPerson(ref: MediaCatalogPersonRef, name: String) {
        personWorksJob?.cancel()
        personRef = ref; personName = name
        personWorks = emptyList(); personWorksCursor = null; personWorksError = null
        loadPersonWorks(true)
    }

    fun closePerson() {
        personWorksJob?.cancel()
        personRef = null; personName = ""; personWorks = emptyList(); personWorksCursor = null; personWorksError = null
    }

    fun loadPersonWorks(reset: Boolean) {
        val item = personRef ?: return
        if (!reset && (personWorksLoading || personWorksCursor == null)) return
        personWorksJob?.cancel(); val cursor = if (reset) null else personWorksCursor
        if (reset) { personWorks = emptyList(); personWorksCursor = null }
        personWorksLoading = true; personWorksError = null
        personWorksJob = scope.launch {
            when (val result = repository.personWorks(item, cursor = cursor)) {
                is MediaReadResult.Success -> if (result.scope == repository.scope) {
                    val page = result.value
                    personWorks = (if (reset) page.items else personWorks + page.items).distinctBy { tvMediaKey(it.ref) }
                    personWorksCursor = page.nextCursor; personWorksError = tvPageError(page.state, page.nextCursor != null)
                }
                is MediaReadResult.Failure -> if (result.scope == repository.scope) {
                    if (result.error.invalidatesTvData()) { personWorks = emptyList(); personWorksCursor = null }
                    personWorksError = tvMediaFailure(result.error)
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
        searchLoading = true; searchError = null
        val query = MediaCatalogQuery(query = searchedQuery, sort = MediaCatalogSort.ID, limit = 24)
        searchJob = scope.launch {
            when (val result = repository.catalog(query, continuation)) {
                is MediaReadResult.Success -> if (result.scope == repository.scope) {
                    val page = result.value
                    searchResults = (if (reset) page.items else searchResults + page.items).distinctBy { tvMediaKey(it.ref) }
                    searchCursor = page.nextCursor; searchError = tvPageError(page.state, page.nextCursor != null)
                }
                is MediaReadResult.Failure -> if (result.scope == repository.scope) {
                    if (result.error.invalidatesTvData()) { searchResults = emptyList(); searchCursor = null }
                    searchError = tvMediaFailure(result.error)
                }
            }
            coroutineContext.ensureActive(); searchLoading = false
        }
    }

    fun retryCatalog() = loadCatalog(!retryCatalogPage)

    fun loadCatalog(reset: Boolean, discardCurrent: Boolean = false) {
        if (!supported || (!reset && (catalogLoading || catalogCursor == null))) return
        catalogJob?.cancel(); val cursor = if (reset) null else catalogCursor
        if (discardCurrent) { catalog = emptyList(); catalogCursor = null }
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
                    retryCatalogPage = false
                    if (page.items.isNotEmpty()) checking = false
                }
                is MediaReadResult.Failure -> if (result.scope == repository.scope) {
                    if (result.error.invalidatesTvData()) { catalog = emptyList(); catalogCursor = null }
                    retryCatalogPage = !reset && catalogCursor != null
                    catalogError = tvMediaFailure(result.error)
                }
            }
            coroutineContext.ensureActive(); catalogLoading = false
        }
    }

    fun openDetail(item: MediaCatalogRef, savedSeason: String?) {
        if (ref == item && (detail != null || detailLoading)) return
        detailJob?.cancel(); technicalJob?.cancel(); seasonsJob?.cancel(); episodesJob?.cancel(); closeSources(); closeCredits(); resetUserStateWrite()
        ref = item; desiredSeason = savedSeason; detail = null; technical = null; detailCursor = null; detailError = null
        variants = emptyList(); variantsRef = null; sourcesCursor = null
        seasons = emptyList(); seasonsCursor = null; selectedSeason = null; episodes = emptyList(); episodesCursor = null
        seasonsLoading = false; seasonsError = null; episodesLoading = false; episodesError = null; jumpedEpisodeId = null
        loadDetail()
    }

    fun closeDetail() {
        detailJob?.cancel(); technicalJob?.cancel(); seasonsJob?.cancel(); episodesJob?.cancel(); closeSources(); closeCredits(); resetUserStateWrite()
        ref = null; detail = null; technical = null; detailCursor = null; selectedSeason = null
        variants = emptyList(); variantsRef = null; sourcesCursor = null
        detailLoading = false; seasonsLoading = false; episodesLoading = false
    }

    fun openCredits() {
        creditsJob?.cancel()
        creditsLoading = false
        credits = detail?.detail?.credits?.items.orEmpty().distinctBy { it.personId }
        creditsCursor = detail?.detail?.credits?.nextCursor
        creditsError = null
        if (creditsCursor != null) loadCredits(false)
    }

    fun closeCredits() {
        creditsJob?.cancel()
        credits = emptyList(); creditsCursor = null; creditsError = null; creditsLoading = false
    }

    fun loadCredits(reset: Boolean) {
        val item = ref ?: return
        val revision = detail?.detail?.item?.revision ?: return
        if (!reset && (creditsLoading || creditsCursor == null)) return
        creditsJob?.cancel(); val cursor = if (reset) null else creditsCursor
        if (reset) { credits = emptyList(); creditsCursor = null }
        creditsLoading = true; creditsError = null
        creditsJob = scope.launch {
            when (val result = repository.listItemCredits(item.libraryId, item.itemId, revision, limit = 50, cursor = cursor)) {
                is MediaReadResult.Success -> if (result.scope == repository.scope) {
                    val page = result.value
                    credits = (if (reset) page.items else credits + page.items).distinctBy { it.personId }
                    creditsCursor = page.nextCursor
                }
                is MediaReadResult.Failure -> if (result.scope == repository.scope) {
                    if (result.error.invalidatesTvData()) { credits = emptyList(); creditsCursor = null }
                    creditsError = tvMediaFailure(result.error)
                }
            }
            coroutineContext.ensureActive(); creditsLoading = false
        }
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
                        loadTechnical(item)
                        if (value.card?.userState != null) {
                            pendingUserState = repository.pendingUserStateOperation(item)
                            if (pendingUserState != null) userStateError = "观看状态尚待确认，请确认原操作，避免重复提交。"
                        }
                        if (tvCanBrowseSources(value.detail.item.kind)) loadDetailSources(item)
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

    private fun resetUserStateWrite() {
        userStateJob?.cancel()
        userStateBusy = false
        pendingUserState = null
        userStateError = null
    }

    fun changeUserState(id: String, revision: Long?, favorite: Boolean? = null, watched: Boolean? = null) {
        val item = ref?.takeIf { tvMediaKey(it) == id } ?: return
        val card = detail?.card ?: return
        if (userStateBusy || pendingUserState != null || revision == null || card.userState?.revision != revision) return
        if (favorite != null && !card.actions.can(MediaCatalogAction.FAVORITE) ||
            watched != null && !card.actions.can(MediaCatalogAction.MARK_WATCHED)) return
        val operation = repository.newOperation()
        submitUserState(item, operation) { repository.updateUserState(operation, item, revision, favorite, watched) }
    }

    fun retryUserState() {
        val item = ref ?: return
        val pending = pendingUserState
        if (pending == null) {
            userStateError = null
            loadDetail()
        } else submitUserState(item, pending) { repository.retryOperation(pending) }
    }

    private fun submitUserState(item: MediaCatalogRef, operation: MediaOperation, submit: suspend () -> MediaWriteResult<Any>) {
        if (userStateBusy) return
        userStateBusy = true
        userStateError = null
        userStateJob = scope.launch {
            try {
                when (val result = submit()) {
                    is MediaWriteResult.Success -> {
                        pendingUserState = null
                        when (val fresh = repository.getUserState(item)) {
                            is MediaReadResult.Success -> detail?.let { loaded ->
                                detail = loaded.copy(card = loaded.card?.copy(userState = fresh.value))
                            }
                            is MediaReadResult.Failure -> {
                                if (fresh.error.invalidatesTvData()) {
                                    detail = null
                                    detailError = "影视详情已变化，请重新加载。"
                                }
                                userStateError = "操作已保存，暂时无法刷新观看状态。"
                            }
                        }
                        repository.forgetResolvedOperation(operation)
                    }
                    is MediaWriteResult.Failure -> {
                        val unknown = result.error.mutationMayHaveCommitted
                        pendingUserState = operation.takeIf { unknown }
                        userStateError = if (unknown) "结果尚待确认，请使用原操作确认，避免重复提交。"
                        else tvMediaFailure(result.error)
                        if (!unknown) {
                            repository.forgetResolvedOperation(operation)
                            if (result.error.invalidatesTvData()) loadDetail()
                        }
                    }
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                pendingUserState = repository.pendingUserStateOperation(item)
                userStateError = if (pendingUserState != null) "结果尚待确认，请使用原操作确认，避免重复提交。"
                else "未能更新观看状态，请刷新详情后重试。"
            } finally {
                if (ref == item) userStateBusy = false
            }
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
        desiredSeason = id; selectedSeason = id; episodesJob?.cancel(); episodes = emptyList(); episodesCursor = null; jumpedEpisodeId = null
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

    fun jumpToEpisode(number: Int) {
        val season = seasons.firstOrNull { it.ref.itemId == selectedSeason } ?: return
        val item = ref ?: return
        if (number !in 1..9999) { episodesError = "请输入 1–9999 的集数"; return }
        episodesJob?.cancel(); jumpedEpisodeId = null; episodesLoading = true; episodesError = null
        episodesJob = scope.launch {
            var cursor: MediaCatalogCursor? = null
            var found: String? = null
            for (pageIndex in 0 until 50) {
                when (val result = repository.episodes(item, season, limit = 100, cursor = cursor)) {
                    is MediaReadResult.Success -> {
                        if (result.scope != repository.scope) break
                        val page = result.value
                        val match = page.items.firstOrNull { it.episodeNumber.toInt() == number }
                        if (match != null) {
                            episodes = page.items.distinctBy { tvMediaKey(it.ref) }
                            episodesCursor = page.nextCursor
                            found = tvMediaKey(match.ref)
                            jumpedEpisodeId = found
                            break
                        }
                        val next = page.nextCursor
                        if (next == null) { episodesError = "这一季没有第 $number 集"; break }
                        cursor = next
                    }
                    is MediaReadResult.Failure -> {
                        if (result.scope == repository.scope) {
                            if (result.error.invalidatesTvData()) { episodes = emptyList(); episodesCursor = null }
                            episodesError = tvMediaFailure(result.error)
                        }
                        break
                    }
                }
            }
            if (found == null && episodesError == null) episodesError = "未找到该集，请缩小范围后重试"
            coroutineContext.ensureActive(); episodesLoading = false
        }
    }

    fun recoverSource(id: String) {
        if (sourcesRecovering) return
        pendingSourceRecovery?.let { operation ->
            submitSourceRecovery(operation)
            return
        }
        val variant = variants.firstOrNull { tvVariantKey(it) == id } ?: return
        when (variant.recoveryAction) {
            "retry" -> loadSources(true)
            "permission" -> sourcesError = "请联系媒体库所有者检查共享权限后重试。"
            "rescan" -> {
                val ref = sourceRef ?: return
                sourcesRecovering = true; sourcesError = null
                sourceRecoveryJob = scope.launch {
                    try {
                        val support = repository.scanSupport()
                        if (support !is MediaReadResult.Success || !support.value.available) {
                            sourcesError = "服务器未提供可用的本地扫描能力。"
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
                                    sourcesError = tvMediaFailure(result.error)
                                    return@launch
                                }
                            }
                        } while (cursor != null && binding == null)
                        val source = binding
                        if (source == null) {
                            sourcesError = "来源已变化，请关闭片源面板并刷新影视详情。"
                            return@launch
                        }
                        val operation = repository.newOperation()
                        when (val result = repository.startScan(operation, ref.libraryId, source.id, source.revision, support.value.localRulesRevision)) {
                            is MediaWriteResult.Success -> {
                                pendingSourceRecovery = null
                                repository.forgetResolvedOperation(operation)
                                sourcesError = "已开始重新扫描，完成后请重新打开片源面板。"
                                refreshHome()
                            }
                            is MediaWriteResult.Failure -> {
                                val unknown = result.error.mutationMayHaveCommitted
                                pendingSourceRecovery = operation.takeIf { unknown }
                                sourcesError = if (unknown) "扫描结果尚待确认，再次选择重新扫描以确认原操作。"
                                else {
                                    repository.forgetResolvedOperation(operation)
                                    tvMediaFailure(result.error)
                                }
                            }
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { sourcesError = "无法开始重新扫描，请检查连接后重试。" }
                    finally { sourcesRecovering = false }
                }
            }
        }
    }

    private fun submitSourceRecovery(operation: MediaOperation) {
        sourcesRecovering = true; sourcesError = null
        sourceRecoveryJob = scope.launch {
            try {
                when (val result = repository.retryOperation(operation)) {
                    is MediaWriteResult.Success -> {
                        pendingSourceRecovery = null
                        repository.forgetResolvedOperation(operation)
                        sourcesError = "已开始重新扫描，完成后请重新打开片源面板。"
                        refreshHome()
                    }
                    is MediaWriteResult.Failure -> {
                        val unknown = result.error.mutationMayHaveCommitted
                        pendingSourceRecovery = operation.takeIf { unknown }
                        sourcesError = if (unknown) "扫描结果尚待确认，再次选择重新扫描以确认原操作。"
                        else {
                            repository.forgetResolvedOperation(operation)
                            tvMediaFailure(result.error)
                        }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                pendingSourceRecovery = operation
                sourcesError = "扫描结果尚待确认，再次选择重新扫描以确认原操作。"
            } finally { sourcesRecovering = false }
        }
    }

    private fun loadTechnical(ref: MediaCatalogRef) {
        technicalJob?.cancel()
        technicalJob = scope.launch {
            when (val result = repository.identification(ref)) {
                is MediaReadResult.Success -> if (result.scope == repository.scope && this@TvMediaRouteState.ref == ref) {
                    technical = result.value.file?.technical
                }
                is MediaReadResult.Failure -> if (result.scope == repository.scope && this@TvMediaRouteState.ref == ref) {
                    technical = null
                }
            }
        }
    }

    private fun loadDetailSources(item: MediaCatalogRef) {
        if (variantsRef == item && !sourcesLoading) return
        loadSourcesFor(item, reset = true)
    }

    fun hasSourcesFor(item: MediaCatalogRef): Boolean = variantsRef == item

    fun openSources(item: MediaCatalogRef) {
        sourcesJob?.cancel()
        sourceRef = item
        if (variantsRef != item || sourcesLoading || sourcesError != null) loadSourcesFor(item, reset = true)
    }
    fun closeSources() {
        sourcesJob?.cancel(); sourceRef = null; sourcesError = null; sourcesLoading = false
    }
    fun loadSources(reset: Boolean) {
        val item = sourceRef ?: return
        loadSourcesFor(item, reset)
    }
    private fun loadSourcesFor(item: MediaCatalogRef, reset: Boolean) {
        if (!reset && (sourcesLoading || sourcesCursor == null)) return
        sourcesJob?.cancel(); val cursor = if (reset) null else sourcesCursor
        if (reset) { variants = emptyList(); sourcesCursor = null }
        variantsRef = item
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

internal fun tvMediaDetailFocusId(libraryId: String?, itemId: String?): String? =
    if (libraryId != null && itemId != null) "$libraryId/$itemId" else null

internal fun tvMediaDetailUserStateBusy(busy: Boolean, pending: Boolean): Boolean = busy || pending
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
