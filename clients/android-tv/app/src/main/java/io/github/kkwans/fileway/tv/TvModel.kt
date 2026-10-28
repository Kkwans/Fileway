package io.github.kkwans.fileway.tv

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import coil3.ImageLoader
import coil3.memory.MemoryCache
import io.github.kkwans.nasfilebrowser.app.*
import io.github.kkwans.nasfilebrowser.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import io.github.kkwans.fileway.remote.*

enum class TvRoute(val title: String) { HOME("首页"), FILES("文件"), VIDEO("视频"), MUSIC("音乐"), IMAGES("图片"), FAVORITES("收藏"), RECENT("最近"), SETTINGS("设置") }
data class TvAnchor(val key: String = "", val index: Int = 0, val offset: Int = 0, val ascending: Boolean = true, val grid: Boolean = true)
data class TvState(
    val connected: Boolean = false, val restoring: Boolean = true, val busy: Boolean = false, val savedLogin: Boolean = false,
    val route: TvRoute = TvRoute.HOME, val path: String = "/", val wirePath: String = "/", val files: List<ResourceRef> = emptyList(),
    val error: String? = null, val notice: String? = null, val health: SessionHealth? = null,
    val dark: Boolean = true, val safePercent: Int = 5, val grid: Boolean = true, val gridColumns:Int=4, val clockAlways:Boolean=true, val speedAlways:Boolean=true, val sortAscending: Boolean = true,
    val search: Boolean = false, val searchQuery: String = "", val searchDone: Boolean = false, val searchScope: SearchScope = SearchScope.CURRENT, val searchBasePath: String = "/",
    val rootEntries:List<ResourceRef> = emptyList(),val recentPlayback: List<PlaybackSnapshot> = emptyList(), val profile: ServerProfile? = null,
)

class TvModel(application: Application) : AndroidViewModel(application) {
    private val database = TvDatabase.get(application)
    private val store = ProfileStore(database, CredentialVault(application))
    private val history = PlaybackHistory(database.playback())
    private val preferences = application.getSharedPreferences("tv-interface", 0)
    private val mutable = MutableStateFlow(TvState(dark = preferences.getBoolean("dark", true), safePercent = preferences.getInt("safe_percent", 5).coerceIn(0, 8), grid = preferences.getBoolean("grid", true),gridColumns=preferences.getInt("grid_columns",4).coerceIn(2,6),clockAlways=preferences.getBoolean("clock_always",true),speedAlways=preferences.getBoolean("speed_always",true)))
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
    private var authenticationJob: Job? = null
    private var sessionJob: Job? = null
    private var recentJob: Job? = null
    private var sessionRecoveryJob:Job?=null
    private var recoveryRevision = -1L
    private var inForeground=true
    private val stack = ArrayDeque<Pair<String, String>>()
    private var directoryOrigin:TvState?=null
    private val anchors = mutableMapOf<String, TvAnchor>()
    val favorites = FavoritesController(viewModelScope) { context === it }
    val recentAccess = RecentAccessController(viewModelScope) { context === it }
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
                    val device = DeviceAuthorization(getApplication()).refresh(profile,account.userId)
                    val restored = if(device==null) restoreSavedSession(store, profile, account) else {
                        val token=device.getString("token")
                        check(store.refreshToken(profile,account,token)){"设备登录来源已变化"}
                        val session=NasSession.restore(profile,token,account.userId)
                        try { SavedSession(session,session.request("GET","/api/resources/")) }
                        catch(error:Exception){withContext(NonCancellable){session.close()};throw error}
                    }
                    candidate=restored.api
                    if(generation!=expected)return@launch
                    bind(profile, account, restored.api,restored.verification);candidate=null
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if(generation==expected)mutable.update { it.copy(error = error.message?:"服务器暂时无法连接") }
            } finally {
                withContext(NonCancellable){candidate?.close()}
                if(generation==expected)mutable.update { it.copy(restoring = false) }
            }
        }
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
        imageLoader.memoryCache?.clear()
        media.closeAndSave()
        runCatching { old?.api?.close() }
        if(generation!=bound.generation){store.deactivate(account,bound.owner);throw CancellationException("登录来源已变化")}
        sessionJob?.cancel(); recentJob?.cancel(); navigationJob?.cancel();sessionRecoveryJob?.cancel();recoveryRevision=-1
        context = bound
        api.persistTokens { token -> check(store.refreshToken(profile, account, token)) { "登录来源已变化" } }
        favorites.bind(bound); recentAccess.bind(bound)
        stack.clear(); anchors.clear();directoryOrigin=null
        mutable.update { it.copy(connected = true, restoring = false, busy = false, profile = profile, route = TvRoute.HOME, path = "/", wirePath = "/", files = emptyList(), error = null, rootEntries=directoryFiles(root),recentPlayback = emptyList()) }
        sessionJob = viewModelScope.launch { api.observeSessionState().collect { health ->
            if(context===bound){mutable.update{it.copy(health=health)};if(health?.code==SessionHealthCode.EXPIRED)recoverSession(bound)}
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

    fun navigate(route: TvRoute) {
        openRequest++
        if (state.value.route == route && !state.value.search) return
        navigationJob?.cancel(); stack.clear();directoryOrigin=null
        mutable.update { it.copy(route = route, search = false, searchQuery = "", searchDone = false, error = null, busy = false) }
        when (route) {
            TvRoute.HOME, TvRoute.RECENT -> refreshRecent()
            TvRoute.FAVORITES -> favorites.refresh()
            TvRoute.FILES, TvRoute.VIDEO, TvRoute.MUSIC, TvRoute.IMAGES -> browse("/", "/", false)
            else -> Unit
        }
    }

    fun browse(path: String, wire: String, push: Boolean = true) {
        openRequest++
        val bound = context ?: return
        navigationJob?.cancel()
        val previous = state.value.path to state.value.wirePath
        mutable.update { it.copy(busy = true, error = null, search = false, searchQuery = "") }
        navigationJob = viewModelScope.launch {
            try {
                val response = bound.api.request("GET", "/api/resources${wire.ifEmpty { SearchResult.encodePath(path) }}")
                val files = directoryFiles(response)
                if (context !== bound) return@launch
                if (push && previous != (path to wire)) stack.addLast(previous)
                mutable.update { it.copy(path = response.getString("path"), wirePath = response.getString("wirePath"), files = files, busy = false) }
                store.saveDirectory(bound.account, path, wire)
                if (path != "/") recentAccess.record(ResourceRef(path, wire, response.getString("name").ifEmpty { "/" }, true, "", 0))
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (context === bound) mutable.update { it.copy(busy = false, error = error.message ?: "无法读取目录") }
            }
        }
    }

    fun backDirectory(): Boolean {
        if (state.value.search) { navigationJob?.cancel(); mutable.update { it.copy(search = false, busy = false) }; refresh(); return true }
        if (stack.isNotEmpty()) { val parent = stack.removeLast(); browse(parent.first, parent.second, false); return true }
        directoryOrigin?.let{origin->
            navigationJob?.cancel();directoryOrigin=null
            mutable.update{it.copy(route=origin.route,path=origin.path,wirePath=origin.wirePath,files=origin.files,search=origin.search,searchQuery=origin.searchQuery,error=null,busy=false)}
            return true
        }
        if (state.value.path != "/" && state.value.route in setOf(TvRoute.FILES, TvRoute.VIDEO, TvRoute.MUSIC, TvRoute.IMAGES)) { browse("/", "/", false); return true }
        return false
    }

    fun refresh() = when (state.value.route) {
        TvRoute.HOME, TvRoute.RECENT -> refreshRecent()
        TvRoute.FAVORITES -> favorites.refresh()
        TvRoute.SETTINGS -> Unit
        else -> browse(state.value.path, state.value.wirePath, false)
    }

    fun search(query: String, scope: SearchScope) {
        openRequest++
        val bound = context ?: return
        navigationJob?.cancel()
        val effectiveScope = if (state.value.route in setOf(TvRoute.HOME,TvRoute.FAVORITES,TvRoute.RECENT)) SearchScope.GLOBAL else scope
        val basePath = if (effectiveScope == SearchScope.GLOBAL) "/" else state.value.path
        val baseWire = if (effectiveScope == SearchScope.GLOBAL) "/" else state.value.wirePath
        mutable.update { it.copy(search = true, searchQuery = query, files = emptyList(), busy = true, searchDone = false, error = null, searchScope = effectiveScope, searchBasePath = basePath) }
        navigationJob = viewModelScope.launch {
            try {
                bound.api.search(basePath, baseWire, query, effectiveScope).collect { update ->
                    if (context === bound) mutable.update { old -> old.copy(files = (old.files + update.items.mapNotNull { it.resource(basePath, baseWire) }).distinctBy { it.mediaKey }, busy = !update.done, searchDone = update.done, notice = update.message) }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (context === bound) mutable.update { it.copy(busy = false, searchDone = true, error = error.message ?: "搜索失败，已保留收到的结果") }
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
            if(state.value.route in setOf(TvRoute.HOME,TvRoute.RECENT,TvRoute.FAVORITES)) {
                directoryOrigin=state.value;stack.clear()
                mutable.update{it.copy(route=TvRoute.FILES,files=emptyList(),search=false,searchQuery="")}
                browse(file.path,file.wirePath,false)
            } else browse(file.path,file.wirePath)
            return
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
                    else mutable.update { it.copy(notice = "TV 首版支持视频、音乐和图片；可在详情查看完整信息") }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    if (context === bound) mutable.update { it.copy(error = error.message ?: "无法确认文件信息") }
                }
            }
            return
        }
        if (file.mediaKind() == MediaKind.VIDEO) imageLoader.memoryCache?.clear()
        recentAccess.record(file)
        media.open(file, candidates)
    }
    fun openPlayback(row: PlaybackSnapshot) { val file = ResourceRef(row.path, row.wirePath, row.name, false, "", 0); open(file, listOf(file)) }
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
    fun toggleOrder() { mutable.update { it.copy(sortAscending = !it.sortAscending) } }
    fun clearNotice() { mutable.update { it.copy(notice = null, error = null) } }
    fun disconnect() {
        authenticationJob?.cancel(); navigationJob?.cancel(); sessionJob?.cancel(); recentJob?.cancel();sessionRecoveryJob?.cancel(); generation++
        val bound = context
        media.pause()
        context = null
        favorites.bind(null); recentAccess.bind(null)
        imageLoader.memoryCache?.clear()
        mutable.update { TvState(restoring = false, dark = it.dark, safePercent = it.safePercent, grid = it.grid,gridColumns=it.gridColumns,clockAlways=it.clockAlways,speedAlways=it.speedAlways) }
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
                    if(restored){mutable.update{it.copy(health=null)};if(media.state.value.file==null)refresh() else if(media.player.state.value.error!=null)media.retry()}
                    else mutable.update{it.copy(error="保存的登录已失效，请重新登录")}
                }
            } catch(error:Exception){if(error is CancellationException)throw error;if(context===bound)mutable.update{it.copy(error=error.message?:"自动登录暂不可用")}}
        }
    }
    fun reconnect(){context?.let{recoverSession(it,true)}?:restoreLogin()}
    fun pause() {inForeground=false;remote.stop(); media.pause();media.cache.background() }
    fun foreground() {inForeground=true;remote.start();media.cache.foreground();context?.takeIf{it.api.sessionHealth.value?.code==SessionHealthCode.EXPIRED}?.let{recoverSession(it)}}
    override fun onCleared() {remote.stop(); media.release(); imageLoader.shutdown(); val bound = context; CoroutineScope(Dispatchers.IO).launch { bound?.api?.close() } }

    companion object {
        fun directoryFiles(value: JSONObject): List<ResourceRef> {
            val items = value.getJSONArray("items")
            return (0 until items.length()).map { index -> val row = items.getJSONObject(index)
                ResourceRef(row.getString("path"), row.getString("wirePath"), row.getString("name"), row.getBoolean("isDir"), row.optString("type"), row.optLong("size"), row.optString("modified"), previewWidth = row.optJSONObject("resolution")?.optInt("width") ?: 0, previewHeight = row.optJSONObject("resolution")?.optInt("height") ?: 0)
            }
        }
    }
}
