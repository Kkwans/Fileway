package io.github.kkwans.fileway.tv

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import coil3.ImageLoader
import coil3.memory.MemoryCache
import io.github.kkwans.nasfilebrowser.app.*
import io.github.kkwans.nasfilebrowser.core.TransportException
import io.github.kkwans.nasfilebrowser.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import io.github.kkwans.fileway.remote.*

enum class TvRoute(val title: String) { HOME("首页"), FILES("文件"), VIDEO("视频"), MUSIC("音乐"), IMAGES("图片"), FAVORITES("收藏"), RECENT("最近播放"), RECENT_ACCESS("最近访问"), SETTINGS("设置") }

internal fun tvRecentAccessResources(entries: List<RecentAccessEntry>): List<ResourceRef> = entries.asSequence()
    .filter { it.openable && (it.isDir || mediaKind(it.name, false, "") != MediaKind.VIDEO) }
    .map { it.resource() }
    .distinctBy { it.mediaKey }
    .toList()
data class TvAnchor(val key: String = "", val index: Int = 0, val offset: Int = 0, val ascending: Boolean = true, val grid: Boolean = true)
data class TvState(
    val connected: Boolean = false, val restoring: Boolean = true, val busy: Boolean = false, val savedLogin: Boolean = false,
    val route: TvRoute = TvRoute.HOME, val path: String = "/", val wirePath: String = "/", val files: List<ResourceRef> = emptyList(),
    val error: String? = null, val notice: String? = null, val health: SessionHealth? = null,
    val dark: Boolean = true, val safePercent: Int = 5, val grid: Boolean = true, val gridColumns:Int=4, val clockAlways:Boolean=true, val speedAlways:Boolean=true, val sortAscending: Boolean = true,
    val search: Boolean = false, val searchQuery: String = "", val searchDone: Boolean = false, val searchScope: SearchScope = SearchScope.CURRENT, val searchBasePath: String = "/",
    val rootEntries:List<ResourceRef> = emptyList(),val recentPlayback: List<PlaybackSnapshot> = emptyList(), val profile: ServerProfile? = null,
    val seekStepSeconds:Int=10, val controlsTimeoutSeconds:Int=5, val detailsHoldMillis:Int=1000,
)

class TvModel(application: Application) : AndroidViewModel(application) {
    private val database = TvDatabase.get(application)
    private val store = ProfileStore(database, CredentialVault(application))
    private val history = PlaybackHistory(database.playback())
    private val preferences = application.getSharedPreferences("tv-interface", 0)
    private val mutable = MutableStateFlow(TvState(
        dark=preferences.getBoolean("dark",true),safePercent=preferences.getInt("safe_percent",5).coerceIn(0,8),
        grid=preferences.getBoolean("grid",true),gridColumns=preferences.getInt("grid_columns",4).coerceIn(2,6),
        clockAlways=preferences.getBoolean("clock_always",true),speedAlways=preferences.getBoolean("speed_always",true),
        seekStepSeconds=preferences.getInt("seek_step_seconds",10).coerceIn(3,30),
        controlsTimeoutSeconds=preferences.getInt("controls_timeout_seconds",5).coerceIn(2,30),
        detailsHoldMillis=preferences.getInt("details_hold_millis",1000).coerceIn(500,2000)))
    val state = mutable.asStateFlow()
    val profiles = store.profiles.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    var context: SessionContext? = null
        private set
    private var generation = 0
    private var openRequest = 0L
    private var visibleSource: List<ResourceRef>? = null
    private var visibleRoute: TvRoute? = null
    private var visibleAscending = true
    private var visibleCache = emptyList<ResourceRef>()
    private var navigationJob: Job? = null
    private var navigationRevision = 0L
    private enum class DirectoryTransition { ENTER, BACK, SWITCH_ROUTE, FROM_SOURCE, REFRESH }
    private data class DirectoryOrigin(
        val owner: SessionContext,
        val page: TvState,
        val stack: List<Pair<String, String>>,
        val previous: DirectoryOrigin?,
        val anchorKey: String? = null,
        val anchor: TvAnchor? = null,
        val reloadSearch: Boolean = false,
        val failedSearch: SearchRestore? = null,
    )
    private data class SearchRestore(val owner: SessionContext, val key: String, val anchor: TvAnchor,
        val query: String, val scope: SearchScope)
    private data class SettingsOrigin(
        val owner: SessionContext,
        val page: TvState,
        val stack: List<Pair<String, String>>,
        val directoryOrigin: DirectoryOrigin?,
        val anchorKey: String,
        val anchor: TvAnchor,
        val reloadSearch: Boolean,
        val failedDirectory: DirectoryRequest?,
        val failedSearch: SearchRestore?,
        val activeSearch: SearchRestore?,
        val previous: SettingsOrigin?,
    )
    private data class DirectoryRequest(
        val path: String, val wire: String, val route: TvRoute, val transition: DirectoryTransition,
        val previous: Pair<String, String>? = null, val origin: DirectoryOrigin? = null,
    )
    private var loadingDirectory: DirectoryRequest? = null
    private var failedDirectory: DirectoryRequest? = null
    private var failedSearchRestore: SearchRestore? = null
    private var activeSearchRestore: SearchRestore? = null
    private var authenticationJob: Job? = null
    private var sessionJob: Job? = null
    private var recentJob: Job? = null
    private var sessionRecoveryJob:Job?=null
    private var recoveryRevision = -1L
    private var inForeground=true
    private val stack = ArrayDeque<Pair<String, String>>()
    private var directoryOrigin:DirectoryOrigin?=null
    private var settingsOrigin: SettingsOrigin? = null
    private val anchors = mutableMapOf<String, TvAnchor>()
    val favorites = FavoritesController(viewModelScope) { context === it }
    val recentAccess = RecentAccessController(viewModelScope) { context === it }
    internal val updates = TvUpdateRuntime.get(application)
    val media = TvMediaController(application, viewModelScope, history) { context }
    val imageLoader = ImageLoader.Builder(application).memoryCache { MemoryCache.Builder().maxSizeBytes(16L * 1024 * 1024).build() }.build()
    val remoteGateway = TvRemoteGateway(this, application)
    val remote = TvRemoteHost(application, viewModelScope, remoteGateway::execute, remoteGateway::snapshot)
    val sessionEpoch:Long get()=(context?.generation ?: generation).toLong()

    init { restoreLogin() }

    fun restoreLogin() {
        authenticationJob?.cancel()
        val expected=++generation
        mutable.update{it.copy(restoring=true,error=null)}
        authenticationJob = viewModelScope.launch {
            var candidate:NasSession?=null
            try {
                store.active()?.let { (profile, account) ->
                    if(generation!=expected)return@launch
                    mutable.update{it.copy(savedLogin=true,profile=profile)}
                    require(profile.network == ConnectionMode.DIRECT) { "TV 只支持直接连接" }
                    val authorizer = DeviceAuthorization(getApplication())
                    // Refresh persists its request nonce, so a retry can recover an interrupted response.
                    val device = retrySavedLogin { authorizer.refresh(profile, account.userId) }
                    val restored = if(device==null) retrySavedLogin { restoreSavedSession(store, profile, account) } else {
                        val token=device.getString("token")
                        check(store.refreshToken(profile,account,token)){"设备登录来源已变化"}
                        val session=NasSession.restore(profile,token,account.userId)
                        try { SavedSession(session, retrySavedLogin { session.request("GET", "/api/resources/") }) }
                        catch(error:Exception){withContext(NonCancellable){session.close()};throw error}
                    }
                    candidate=restored.api
                    if(generation!=expected)return@launch
                    bind(profile, account, restored.api,restored.verification);candidate=null
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (generation == expected) mutable.update {
                    it.copy(error = if (isTemporaryRestoreFailure(error)) "暂时无法连接已保存的服务器，请重试"
                        else error.message ?: "服务器暂时无法连接")
                }
            } finally {
                withContext(NonCancellable){candidate?.close()}
                if(generation==expected)mutable.update { it.copy(restoring = false) }
            }
        }
    }

    private suspend fun <T> retrySavedLogin(operation: suspend () -> T): T {
        var attempts = 0
        while (true) {
            try {
                return operation()
            } catch (failure: Exception) {
                if (!isTemporaryRestoreFailure(failure) || attempts == 2 || !inForeground) throw failure
                delay(if (attempts++ == 0) 750L else 2_000L)
                if (!inForeground) throw failure
            }
        }
    }

    private fun isTemporaryRestoreFailure(failure: Exception): Boolean = when (failure) {
        is ServiceException -> failure.status in 502..504
        is TransportException -> failure.message == "cannot connect to service"
        else -> false
    }

    fun login(address: String, username: String, password: String, profile: ServerProfile? = null) {
        authenticationJob?.cancel()
        val expected = ++generation
        mutable.update { it.copy(busy = true, error = null) }
        authenticationJob = viewModelScope.launch {
            var api: NasSession? = null
            try {
                val saved = store.save((profile ?: ServerProfile(name = "Fileway", address = address)).copy(address = address, network = ConnectionMode.DIRECT))
                api = NasSession.login(saved, username, password)
                val root=api.request("GET", "/api/resources/")
                if (generation != expected) return@launch
                val account = store.saveLogin(saved, api.identity.id, api.identity.username, api.token())
                if (password.isNotEmpty()) store.rememberPassword(saved, account, password)
                bind(saved, account, api,root)
                api = null
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (generation == expected) mutable.update { it.copy(error = error.message ?: "连接失败，请重试") }
            } finally {
                withContext(NonCancellable) { api?.close() }
                if (generation == expected) mutable.update { it.copy(busy = false, restoring = false) }
            }
        }
    }

    private suspend fun bind(profile: ServerProfile, account: AccountRecord, api: NasSession,root:JSONObject) {
        val old = context
        val bound = SessionContext(profile, account, api, generation)
        // Persist the new owner before releasing the old source. Validation or
        // storage failure before this point leaves the old player/session usable.
        store.activate(profile, account, bound.owner)
        if(generation!=bound.generation){store.deactivate(account,bound.owner);throw CancellationException("登录来源已变化")}
        mutable.update { it.copy(connected = false, restoring = true, files = emptyList(), recentPlayback = emptyList(), health = null) }
        context = null
        updates.unbind(erase = true)
        imageLoader.memoryCache?.clear()
        media.closeAndSave()
        runCatching { old?.api?.close() }
        if(generation!=bound.generation){store.deactivate(account,bound.owner);throw CancellationException("登录来源已变化")}
        sessionJob?.cancel(); recentJob?.cancel(); cancelNavigation();sessionRecoveryJob?.cancel();recoveryRevision=-1
        stack.clear(); anchors.clear();directoryOrigin=null;settingsOrigin=null;failedDirectory=null
        context = bound
        updates.bind(bound)
        api.persistTokens { token -> check(store.refreshToken(profile, account, token)) { "登录来源已变化" } }
        favorites.bind(bound); recentAccess.bind(bound)
        mutable.update { it.copy(connected = true, restoring = false, busy = false, profile = profile, route = TvRoute.HOME, path = "/", wirePath = "/", files = emptyList(), search = false, searchQuery = "", searchDone = false, error = null, rootEntries=directoryFiles(root),recentPlayback = emptyList()) }
        sessionJob = viewModelScope.launch { api.observeSessionState().collect { health ->
            if(context===bound){
                updates.bind(bound)
                mutable.update{it.copy(health=health)}
                if(health?.code==SessionHealthCode.EXPIRED)recoverSession(bound)
            }
        } }
        recentJob = viewModelScope.launch { history.recent(account).collect { local ->
            if (context === bound) mutable.update { it.copy(recentPlayback = (local + it.recentPlayback).distinctBy { row -> row.resourceKey }.sortedByDescending { row -> row.updatedAt }.take(100)) }
        } }
        favorites.refresh(); refreshRecent()
    }

    suspend fun adoptDeviceCredential(credential:JSONObject,authorized:suspend ()->Boolean) {
        val principal=NasSession.parseIdentity(credential.getString("token"))
        check(principal.id==credential.getLong("userId")){"TV授权账号不一致"}
        val origin=credential.getString("origin")
        val originId=java.security.MessageDigest.getInstance("SHA-256").digest(origin.toByteArray(Charsets.UTF_8)).take(8).joinToString(""){"%02x".format(it)}
        val profile=ServerProfile(id="tv-device-${credential.getString("serverId")}-${principal.id}-$originId",name="Fileway",address=origin,network=ConnectionMode.DIRECT)
        var candidate:NasSession?=NasSession.restore(profile,credential.getString("token"),principal.id)
        try {
            val root=candidate!!.request("GET","/api/resources/")
            check(authorized()){"TV登录或控制授权已变化"}
            val saved=store.save(profile)
            val account=store.saveLogin(saved,principal.id,principal.username,credential.getString("token"))
            val verified=candidate!!
            candidate=NasSession.restore(saved,credential.getString("token"),principal.id)
            withContext(NonCancellable){verified.close()}
            val authorizer=DeviceAuthorization(getApplication(),remote.store)
            val grant=authorizer.save(saved,credential)
            withContext(NonCancellable) {
                check(authorized()){"TV登录或控制授权已变化"}
                authenticationJob?.cancel();generation++
                bind(saved,account,candidate!!,root);candidate=null
                try {remote.store.dao.saveGrant(grant.copy(phase="ACTIVE",updatedAt=System.currentTimeMillis()))}
                catch(_:Exception){mutable.update{it.copy(notice="TV已独立登录，授权记录状态稍后恢复")}}
            }
        } finally {withContext(NonCancellable){candidate?.close()}}
    }

    fun refreshRecent() {
        val bound = context ?: return
        recentAccess.setVisible(true); recentAccess.refresh()
        viewModelScope.launch {
            try {
                val remote = history.recentRemote(bound.api, bound.account)
                if (context === bound) mutable.update { it.copy(recentPlayback = (it.recentPlayback.filter { row -> row.sync == ProgressSync.PENDING } + remote).distinctBy { row -> row.resourceKey }.sortedByDescending { row -> row.updatedAt }.take(100)) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (context === bound) mutable.update { it.copy(notice = "最近播放暂未同步，已保留本机记录") }
            }
        }
    }

    private fun cancelNavigation() {
        navigationRevision++
        navigationJob?.cancel()
        navigationJob = null
        loadingDirectory = null
        failedSearchRestore = null
        activeSearchRestore = null
    }

    private fun mustReloadSearch(page: TvState): Boolean {
        var pages = 1
        var items = page.files.size.toLong()
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<DirectoryOrigin, Boolean>())
        fun countDirectories(first: DirectoryOrigin?) {
            var origin = first
            while (origin != null && seen.add(origin)) {
                if (origin.page.search && !origin.reloadSearch) {
                    pages++
                    items += origin.page.files.size
                }
                origin = origin.previous
            }
        }
        countDirectories(directoryOrigin)
        var settings = settingsOrigin
        while (settings != null) {
            if (settings.page.search && !settings.reloadSearch) {
                pages++
                items += settings.page.files.size
            }
            countDirectories(settings.directoryOrigin)
            settings = settings.previous
        }
        return pages > MAX_SAVED_SEARCH_PAGES || items > MAX_SAVED_SEARCH_ITEMS
    }

    fun navigate(route: TvRoute) {
        openRequest++
        if (state.value.route == route && !state.value.search) {
            if (loadingDirectory != null || failedDirectory != null) {
                cancelNavigation(); failedDirectory = null
                mutable.update { it.copy(busy = false, error = null) }
            }
            return
        }
        if (route in browseRoutes) {
            loadDirectory(DirectoryRequest("/", "/", route, DirectoryTransition.SWITCH_ROUTE))
            return
        }
        if (route == TvRoute.SETTINGS) {
            val owner = context
            settingsOrigin = owner?.let {
                val page = state.value
                val discardSearchFiles = page.search && mustReloadSearch(page)
                // Preserve a failed search for an explicit retry. Returning from
                // settings must not silently restart it or hide its error.
                val reloadSearch = discardSearchFiles && page.error == null
                SettingsOrigin(it, page.copy(files = if (discardSearchFiles) emptyList() else page.files,
                    error = if (reloadSearch) null else page.error), stack.toList(), directoryOrigin, anchorKey(), anchor(),
                    reloadSearch, failedDirectory, failedSearchRestore, activeSearchRestore,
                    settingsOrigin?.takeIf { previous -> previous.owner === it })
            }
        } else settingsOrigin = null
        cancelNavigation(); failedDirectory = null; stack.clear(); directoryOrigin = null
        mutable.update { it.copy(route = route, path = "/", wirePath = "/", files = emptyList(), search = false, searchQuery = "", searchDone = false, error = null, busy = false) }
        when (route) {
            TvRoute.HOME, TvRoute.RECENT, TvRoute.RECENT_ACCESS -> refreshRecent()
            TvRoute.FAVORITES -> favorites.refresh()
            else -> Unit
        }
    }

    fun browse(path: String, wire: String, push: Boolean = true) {
        openRequest++
        val current = state.value
        val pendingRoute = loadingDirectory?.takeIf { it.transition == DirectoryTransition.SWITCH_ROUTE }?.route
        loadDirectory(DirectoryRequest(path, wire, pendingRoute ?: current.route,
            if (pendingRoute != null) DirectoryTransition.SWITCH_ROUTE else if (push) DirectoryTransition.ENTER else DirectoryTransition.REFRESH,
            previous = current.path to current.wirePath))
    }

    private fun loadDirectory(request: DirectoryRequest) {
        val bound = context ?: return
        if (request.origin != null && request.origin.owner !== bound) return
        cancelNavigation(); failedDirectory = null
        loadingDirectory = request
        val expected = navigationRevision
        mutable.update { it.copy(busy = true, error = null,
            notice = if (it.search && it.busy && !it.searchDone) "搜索已停止，已保留收到的结果；可刷新重新搜索" else it.notice) }
        navigationJob = viewModelScope.launch {
            val response: JSONObject
            val files: List<ResourceRef>
            val path: String
            val wire: String
            val name: String
            try {
                response = bound.api.request("GET", "/api/resources${request.wire.ifEmpty { SearchResult.encodePath(request.path) }}")
                files = directoryFiles(response)
                path = response.getString("path")
                wire = response.getString("wirePath")
                name = if (request.path != "/") response.getString("name").ifEmpty { "/" } else "/"
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (context === bound && navigationRevision == expected) {
                    loadingDirectory = null; failedDirectory = request
                    val target = if (request.transition == DirectoryTransition.SWITCH_ROUTE) "“${request.route.title}”" else "目录“${request.path}”"
                    mutable.update { it.copy(busy = false, error = "无法打开$target：${error.message ?: "请重试"}") }
                }
                return@launch
            }
            if (context !== bound || navigationRevision != expected) return@launch
            when (request.transition) {
                DirectoryTransition.ENTER -> request.previous?.takeIf { it != (request.path to request.wire) }?.let(stack::addLast)
                DirectoryTransition.BACK -> if (stack.lastOrNull() == request.previous) stack.removeLast()
                DirectoryTransition.SWITCH_ROUTE -> { stack.clear(); directoryOrigin = null; settingsOrigin = null }
                DirectoryTransition.FROM_SOURCE -> { stack.clear(); directoryOrigin = request.origin }
                DirectoryTransition.REFRESH -> Unit
            }
            loadingDirectory = null; failedDirectory = null
            mutable.update { it.copy(route = request.route, path = path, wirePath = wire, files = files,
                search = false, searchQuery = "", searchDone = false, busy = false, error = null,
                notice = if (it.search) null else it.notice) }

            // The directory is already visible. Ancillary history failures cannot
            // turn that successful read into a failed navigation or a retry.
            if (request.path != "/") recentAccess.record(ResourceRef(request.path, request.wire, name, true, "", 0))
            try { store.saveDirectory(bound.account, request.path, request.wire) }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                if (context === bound && navigationRevision == expected)
                    mutable.update { it.copy(notice = "目录已打开，但浏览位置暂未保存") }
            }
        }
    }

    fun backDirectory(): Boolean {
        if (loadingDirectory != null || failedDirectory != null) {
            openRequest++; cancelNavigation(); failedDirectory = null
            mutable.update { it.copy(busy = false, error = null) }
            return true
        }
        val current = state.value
        if (current.search) {
            if (current.route in browseRoutes) browse(current.path, current.wirePath, false)
            else {
                openRequest++; cancelNavigation()
                mutable.update { it.copy(search = false, searchQuery = "", searchDone = false, files = emptyList(), busy = false, error = null) }
                refresh()
            }
            return true
        }
        if (current.route == TvRoute.SETTINGS) {
            val origin = settingsOrigin
            if (origin == null || origin.owner !== context) {
                settingsOrigin = null
                return false
            }
            openRequest++; cancelNavigation()
            settingsOrigin = origin.previous?.takeIf { it.owner === context }
            stack.clear(); stack.addAll(origin.stack)
            directoryOrigin = origin.directoryOrigin
            failedDirectory = origin.failedDirectory
            failedSearchRestore = origin.failedSearch?.takeIf { it.owner === context }
            anchors[origin.anchorKey] = origin.anchor
            val page = origin.page
            val interruptedSearch = page.search && page.busy && !page.searchDone
            val activeSearch = origin.activeSearch?.takeIf { it.owner === context }
                ?: if (origin.reloadSearch) SearchRestore(origin.owner, origin.anchorKey, origin.anchor, page.searchQuery, page.searchScope) else null
            mutable.update { it.copy(route = page.route, path = page.path, wirePath = page.wirePath, files = page.files,
                search = page.search, searchQuery = page.searchQuery, searchDone = page.searchDone,
                searchScope = page.searchScope, searchBasePath = page.searchBasePath,
                error = page.error, busy = false,
                notice = if (interruptedSearch) "搜索已停止，已保留收到的结果；可刷新重新搜索" else page.notice) }
            if (activeSearch != null && (interruptedSearch || origin.reloadSearch)) {
                startSearch(activeSearch.query, activeSearch.scope, activeSearch)
            }
            return true
        }
        directoryOrigin?.takeIf { it.owner !== context }?.let { directoryOrigin = null; stack.clear() }
        if (stack.isNotEmpty()) {
            openRequest++
            val parent = stack.last()
            loadDirectory(DirectoryRequest(parent.first, parent.second, current.route, DirectoryTransition.BACK, previous = parent))
            return true
        }
        directoryOrigin?.let { origin ->
            openRequest++; cancelNavigation(); directoryOrigin = origin.previous
            stack.clear(); stack.addAll(origin.stack)
            failedSearchRestore = origin.failedSearch?.takeIf { it.owner === context }
            if (origin.anchorKey != null && origin.anchor != null) anchors[origin.anchorKey] = origin.anchor
            val page = origin.page
            mutable.update { it.copy(route = page.route, path = page.path, wirePath = page.wirePath, files = page.files,
                search = page.search, searchQuery = page.searchQuery, searchDone = page.searchDone,
                searchScope = page.searchScope, searchBasePath = page.searchBasePath,
                error = page.error.takeIf { page.search && !origin.reloadSearch }, busy = origin.reloadSearch,
                notice = when {
                    origin.reloadSearch -> "正在重新搜索以恢复之前的结果"
                    page.search && page.busy && !page.searchDone -> "搜索已停止，已保留收到的结果；可刷新重新搜索"
                    else -> page.notice
                }) }
            if (origin.reloadSearch && origin.anchorKey != null && origin.anchor != null) {
                startSearch(page.searchQuery, page.searchScope,
                    SearchRestore(origin.owner, origin.anchorKey, origin.anchor, page.searchQuery, page.searchScope))
            }
            return true
        }
        if (current.path != "/" && current.route in browseRoutes) { browse("/", "/", false); return true }
        if (current.route == TvRoute.RECENT || current.route == TvRoute.RECENT_ACCESS) { navigate(TvRoute.HOME); return true }
        return false
    }

    fun refresh() {
        val current = state.value
        if (current.search) { search(current.searchQuery, current.searchScope); return }
        if (current.route in browseRoutes) {
            openRequest++
            loadDirectory(DirectoryRequest(current.path, current.wirePath, current.route, DirectoryTransition.REFRESH))
            return
        }
        openRequest++; cancelNavigation(); failedDirectory = null
        mutable.update { it.copy(busy = false, error = null) }
        when (current.route) {
            TvRoute.HOME, TvRoute.RECENT, TvRoute.RECENT_ACCESS -> refreshRecent()
            TvRoute.FAVORITES -> favorites.refresh()
            else -> Unit
        }
    }

    fun retry() {
        val request = failedDirectory
        val search = failedSearchRestore
        if (request != null) { openRequest++; loadDirectory(request) }
        else if (search != null && context === search.owner) startSearch(search.query, search.scope, search)
        else refresh()
    }

    fun search(query: String, scope: SearchScope) = startSearch(query, scope, null)

    private fun startSearch(query: String, scope: SearchScope, restore: SearchRestore?) {
        openRequest++
        val bound = context ?: return
        if (restore != null && restore.owner !== bound) return
        cancelNavigation(); failedDirectory = null
        activeSearchRestore = restore
        val expected = navigationRevision
        val effectiveScope = if (state.value.route in setOf(TvRoute.HOME,TvRoute.FAVORITES,TvRoute.RECENT,TvRoute.RECENT_ACCESS)) SearchScope.GLOBAL else scope
        val basePath = if (effectiveScope == SearchScope.GLOBAL) "/" else state.value.path
        val baseWire = if (effectiveScope == SearchScope.GLOBAL) "/" else state.value.wirePath
        mutable.update { it.copy(search = true, searchQuery = query, files = emptyList(), busy = true, searchDone = false, error = null,
            searchScope = effectiveScope, searchBasePath = basePath, notice = if (restore != null) "正在重新搜索以恢复之前的结果" else null) }
        navigationJob = viewModelScope.launch {
            var restoredFiles = emptyList<ResourceRef>()
            try {
                bound.api.search(basePath, baseWire, query, effectiveScope).collect { update ->
                    if (context === bound && navigationRevision == expected) {
                        if (update.done) activeSearchRestore = null
                        val items = update.items.mapNotNull { it.resource(basePath, baseWire) }
                        if (restore == null) {
                            mutable.update { old -> old.copy(files = (old.files + items).distinctBy { it.mediaKey }, busy = !update.done, searchDone = update.done, notice = update.message) }
                        } else {
                            // Keep the original semantic focus until the restored
                            // search finishes; early batches may not contain it.
                            restoredFiles = (restoredFiles + items).distinctBy { it.mediaKey }
                            if (update.done) {
                                anchors[restore.key] = restore.anchor
                                val failed = update.ending == SearchEnding.FAILED || update.ending == SearchEnding.CANCELED
                                if (failed) failedSearchRestore = restore
                                mutable.update { it.copy(files = restoredFiles, busy = false, searchDone = true,
                                    error = update.message.takeIf { failed }, notice = update.message.takeUnless { failed }) }
                            }
                        }
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (context === bound && navigationRevision == expected) {
                    activeSearchRestore = null
                    if (restore != null) { anchors[restore.key] = restore.anchor; failedSearchRestore = restore }
                    mutable.update { it.copy(files = if (restore != null) restoredFiles else it.files,
                        busy = false, searchDone = true, notice = null, error = error.message ?: "搜索失败，已保留收到的结果") }
                }
            }
        }
    }

    fun visibleFiles(): List<ResourceRef> {
        val value = state.value
        if (visibleSource === value.files && visibleRoute == value.route && visibleAscending == value.sortAscending) return visibleCache
        val kind = when (value.route) { TvRoute.VIDEO -> MediaKind.VIDEO; TvRoute.MUSIC -> MediaKind.AUDIO; TvRoute.IMAGES -> MediaKind.IMAGE; else -> null }
        val filtered = value.files.filter { it.directory || kind == null || it.mediaKind() == kind }
        visibleCache = filtered.sortedWith(if (value.sortAscending) compareBy<ResourceRef> { !it.directory }.thenBy { it.name.lowercase(java.util.Locale.ROOT) }
            else compareBy<ResourceRef> { !it.directory }.thenByDescending { it.name.lowercase(java.util.Locale.ROOT) })
        visibleSource = value.files; visibleRoute = value.route; visibleAscending = value.sortAscending
        return visibleCache
    }

    fun open(file: ResourceRef, candidates: List<ResourceRef> = visibleFiles()) {
        val requested = ++openRequest
        if(file.directory) {
            val current = state.value
            if(current.search || current.route in setOf(TvRoute.HOME,TvRoute.RECENT,TvRoute.RECENT_ACCESS,TvRoute.FAVORITES)) {
                val owner = context ?: return
                val reloadSearch = current.search && mustReloadSearch(current)
                val key = if (current.search) anchorKey() else null
                val selectedAnchor = if (current.search) {
                    val saved = anchor()
                    val index = candidates.indexOfFirst { it.mediaKey == file.mediaKey }
                    saved.copy(key = file.mediaKey, index = if (saved.key.isEmpty() && index >= 0) index else saved.index)
                } else null
                val originPage = current.copy(files = if (reloadSearch) emptyList() else current.files,
                    error = if (failedDirectory != null) null else current.error)
                val origin = DirectoryOrigin(owner, originPage,
                    stack.toList(), directoryOrigin, key, selectedAnchor, reloadSearch, failedSearchRestore)
                val route = if (current.route in browseRoutes) current.route else TvRoute.FILES
                loadDirectory(DirectoryRequest(file.path, file.wirePath, route, DirectoryTransition.FROM_SOURCE, origin = origin))
            } else {
                loadDirectory(DirectoryRequest(file.path, file.wirePath, current.route, DirectoryTransition.ENTER,
                    previous = current.path to current.wirePath))
            }
            return
        }
        if (loadingDirectory != null || failedDirectory != null) {
            cancelNavigation(); failedDirectory = null
            mutable.update { it.copy(busy = false, error = null) }
        }
        if (file.mediaKind() == null) {
            val bound = context ?: return
            viewModelScope.launch {
                try {
                    val value = bound.api.request("GET", "/api/resources${file.wirePath}")
                    if (context !== bound || openRequest != requested) return@launch
                    val resolved = file.copy(directory = value.getBoolean("isDir"), type = value.optString("type"), size = value.optLong("size"), modified = value.optString("modified"))
                    if (resolved.directory) open(resolved,candidates)
                    else if (resolved.mediaKind() != null) open(resolved, candidates)
                    else mutable.update { it.copy(notice = "TV 无法打开此文件。可在详情查看名称和路径。") }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    if (context === bound && openRequest == requested) mutable.update { it.copy(error = error.message ?: "无法确认文件信息") }
                }
            }
            return
        }
        if (file.mediaKind() == MediaKind.VIDEO) imageLoader.memoryCache?.clear()
        recentAccess.record(file)
        media.open(file, candidates)
    }
    fun openPlayback(row: PlaybackSnapshot) { val file = ResourceRef(row.path, row.wirePath, row.name, false, "", 0); open(file, listOf(file)) }
    internal fun openHandoff(file:ResourceRef,start:TvMediaStart) {
        ++openRequest
        if(file.mediaKind()==MediaKind.VIDEO)imageLoader.memoryCache?.clear()
        recentAccess.record(file)
        media.openHandoff(file,start)
    }
    fun toggleFavorite(file: ResourceRef) { favorites.favorite(file)?.let { favorites.remove(it) } ?: favorites.add(file) }
    fun anchorKey() = "${context?.owner}/${state.value.route}/${state.value.wirePath}/${state.value.searchQuery}"
    fun anchor() = anchors[anchorKey()] ?: TvAnchor()
    fun rememberAnchor(key: String, index: Int, offset: Int) { anchors[anchorKey()] = TvAnchor(key, index, offset, state.value.sortAscending, state.value.grid) }
    fun setDark(value: Boolean) { preferences.edit().putBoolean("dark", value).apply(); mutable.update { it.copy(dark = value) } }
    fun setSafePercent(value: Int) { val valid = value.coerceIn(0,8); preferences.edit().putInt("safe_percent", valid).apply(); mutable.update { it.copy(safePercent = valid) } }
    fun toggleLayout() { val grid = !state.value.grid; preferences.edit().putBoolean("grid", grid).apply(); mutable.update { it.copy(grid = grid) } }
    fun setColumns(value:Int) {val columns=value.coerceIn(2,6);preferences.edit().putInt("grid_columns",columns).apply();mutable.update{it.copy(gridColumns=columns)} }
    fun setClockAlways(value:Boolean) {preferences.edit().putBoolean("clock_always",value).apply();mutable.update{it.copy(clockAlways=value)} }
    fun setSpeedAlways(value:Boolean) {preferences.edit().putBoolean("speed_always",value).apply();mutable.update{it.copy(speedAlways=value)} }
    fun setSeekStep(value:Int) {val valid=value.coerceIn(3,30);preferences.edit().putInt("seek_step_seconds",valid).apply();mutable.update{it.copy(seekStepSeconds=valid)} }
    fun setControlsTimeout(value:Int) {val valid=value.coerceIn(2,30);preferences.edit().putInt("controls_timeout_seconds",valid).apply();mutable.update{it.copy(controlsTimeoutSeconds=valid)} }
    fun setDetailsHold(value:Int) {val valid=value.coerceIn(500,2000);preferences.edit().putInt("details_hold_millis",valid).apply();mutable.update{it.copy(detailsHoldMillis=valid)} }
    fun toggleOrder() { mutable.update { it.copy(sortAscending = !it.sortAscending) } }
    fun dismissNotice(expected: String) {
        mutable.update { if (it.notice == expected) it.copy(notice = null) else it }
    }
    fun disconnect() {
        authenticationJob?.cancel(); cancelNavigation(); failedDirectory = null; sessionJob?.cancel(); recentJob?.cancel();sessionRecoveryJob?.cancel(); generation++
        stack.clear(); directoryOrigin = null; settingsOrigin = null; anchors.clear()
        val bound = context
        media.pause()
        context = null
        updates.unbind(erase = true)
        favorites.bind(null); recentAccess.bind(null)
        imageLoader.memoryCache?.clear()
        mutable.update { TvState(restoring = false, dark = it.dark, safePercent = it.safePercent, grid = it.grid,gridColumns=it.gridColumns,clockAlways=it.clockAlways,speedAlways=it.speedAlways,seekStepSeconds=it.seekStepSeconds,controlsTimeoutSeconds=it.controlsTimeoutSeconds,detailsHoldMillis=it.detailsHoldMillis) }
        viewModelScope.launch {
            media.closeAndSave()
            if (bound != null) {
                val deviceSession=bound.api.identity.deviceSessionId
                store.signOut(bound.account, bound.owner)
                DeviceAuthorization(getApplication(),remote.store).forget(bound.profile,bound.account.userId,deviceSession)
                bound.api.close()
            }
        }
    }

    private fun recoverSession(bound:SessionContext,manual:Boolean=false) {
        if(context!==bound || !inForeground || sessionRecoveryJob?.isActive==true)return
        if(!manual && recoveryRevision==bound.api.businessRevision)return
        recoveryRevision=bound.api.businessRevision
        sessionRecoveryJob=viewModelScope.launch {
            try {
                val device=DeviceAuthorization(getApplication(),remote.store).refresh(bound.profile,bound.account.userId)
                val restored=if(device!=null){
                    if(context!==bound)return@launch
                    bound.api.recoverDeviceAuthentication(device.getString("token"));true
                }else recoverBoundSession(store,bound.profile,bound.account,bound.api)
                if(context===bound) {
                    if(restored){updates.bind(bound);mutable.update{it.copy(health=null)};if(media.state.value.file==null)refresh() else if(media.player.state.value.error!=null)media.retry()}
                    else mutable.update{it.copy(error="保存的登录已失效，请重新登录")}
                }
            } catch(error:Exception){if(error is CancellationException)throw error;if(context===bound)mutable.update{it.copy(error=error.message?:"自动登录暂不可用")}}
        }
    }
    fun reconnect(){context?.let{recoverSession(it,true)}?:restoreLogin()}
    fun pause() {inForeground=false;remote.stop(); media.pause();media.cache.background() }
    fun foreground() {inForeground=true;remote.start();media.cache.foreground();context?.takeIf{it.api.sessionHealth.value?.code==SessionHealthCode.EXPIRED}?.let{recoverSession(it)}}
    override fun onCleared() {
        remote.stop(); media.release(); imageLoader.shutdown()
        val bound = context
        if (bound != null) updates.unbind(erase = false, session = bound)
        CoroutineScope(Dispatchers.IO).launch { bound?.api?.close() }
    }

    companion object {
        private const val MAX_SAVED_SEARCH_PAGES = 8
        private const val MAX_SAVED_SEARCH_ITEMS = 4000L
        private val browseRoutes = setOf(TvRoute.FILES, TvRoute.VIDEO, TvRoute.MUSIC, TvRoute.IMAGES)
        fun directoryFiles(value: JSONObject): List<ResourceRef> {
            val items = value.getJSONArray("items")
            return (0 until items.length()).map { index -> val row = items.getJSONObject(index)
                ResourceRef(row.getString("path"), row.getString("wirePath"), row.getString("name"), row.getBoolean("isDir"), row.optString("type"), row.optLong("size"), row.optString("modified"), previewWidth = row.optJSONObject("resolution")?.optInt("width") ?: 0, previewHeight = row.optJSONObject("resolution")?.optInt("height") ?: 0)
            }
        }
    }
}
