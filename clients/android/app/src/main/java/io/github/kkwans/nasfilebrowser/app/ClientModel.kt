package io.github.kkwans.nasfilebrowser.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import coil3.ImageLoader
import coil3.memory.MemoryCache
import coil3.request.allowHardware
import io.github.kkwans.nasfilebrowser.core.NativeTransport
import io.github.kkwans.nasfilebrowser.core.EmbeddedNetwork
import io.github.kkwans.nasfilebrowser.core.NetworkState
import io.github.kkwans.nasfilebrowser.player.NativePlayer
import io.github.kkwans.nasfilebrowser.player.DecoderRestore
import io.github.kkwans.nasfilebrowser.data.*
import io.github.kkwans.nasfilebrowser.download.DownloadController
import io.github.kkwans.nasfilebrowser.download.DownloadRecord
import io.github.kkwans.nasfilebrowser.download.DownloadDataSource
import io.github.kkwans.nasfilebrowser.upload.UploadController
import io.github.kkwans.nasfilebrowser.upload.UploadRecord
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.net.URLEncoder

typealias FileLayout = io.github.kkwans.nasfilebrowser.data.FileLayout
enum class RecentSection(val label: String) { PLAYBACK("最近播放"), ACCESS("最近访问") }
enum class TaskCenterSection(val route: String, val label: String) {
    DOWNLOADS("downloads", "下载"), UPLOADS("uploads", "上传"), BACKGROUND("tasks", "后台任务"), HISTORY("history", "操作历史");
    companion object { fun forRoute(route: String): TaskCenterSection? = entries.firstOrNull { it.route == route } }
}
enum class LibrarySection(val label: String, val title: String) { FAVORITES("收藏", "收藏夹"), TAGS("标签", "标签"), TRASH("回收站", "回收站"), TASKS("任务", "任务中心"), TOOLS("工具", "存储工具") }

data class ResourceRef(val path: String, val wirePath: String, val name: String, val directory: Boolean, val type: String, val size: Long, val modified: String = "", val downloadId: String = "", val previewWidth: Int = 0, val previewHeight: Int = 0)
data class ClientState(
    val startupPending: Boolean = false,
    val connected: Boolean = false, val busy: Boolean = false, val stage: String = "",
    val serverLabel: String = "", val accountName: String = "", val path: String = "/", val wirePath: String = "/", val files: List<ResourceRef> = emptyList(),
    val error: String? = null, val selected: ResourceRef? = null, val image: ResourceRef? = null,
    val mediaQueue: MediaQueue? = null,
    val fileCategory: FileCategory = FileCategory.ALL, val fileCategories: Set<FileCategory> = emptySet(), val fileOrder: FileOrder = FileOrder.NAME,
    val downloadBytesPerSecond: Long? = null,
    val profile: ServerProfile? = null, val accounts: List<AccountRecord> = emptyList(), val editorVersion: Int = 0,
    val librarySection: LibrarySection = LibrarySection.FAVORITES,
    val recentSection: RecentSection = RecentSection.PLAYBACK,
    val recentPlaybackLoading: Boolean = false, val recentPlaybackError: String? = null,
    val taskCenterSection: TaskCenterSection = TaskCenterSection.DOWNLOADS,
    val permissions: ServerPermissions = ServerPermissions(),
    val notice: String? = null,
    val health: SessionHealth? = null, val recoveryInProgress: Boolean = false, val recoveryNeedsPassword: Boolean = false, val recoveryError: String? = null,
    val progressStatus: String? = null, val tab: String = "files", val previewScope: String = "", val fileLayout: FileLayout = FileLayout.COVER,
    val searchDisplayed: Boolean = false, val compactGridSize: CompactGridSize = CompactGridSize.MEDIUM,
)
data class SessionContext(val profile: ServerProfile, val account: AccountRecord, val api: NasSession, val generation: Int, val owner: String = java.util.UUID.randomUUID().toString())
private data class PlaybackBinding(val context: SessionContext, val file: ResourceRef, val identity: String, val writer: PlaybackWriter)
private data class TemporaryMediaBinding(val context: SessionContext, val file: ResourceRef, val asset: PreviewLease)
private sealed interface DecoderSubtitleSource {
    val key: String
    val name: String
    data class Server(override val key: String, override val name: String, val bound: SessionContext,
        val file: ResourceRef, val identity: String) : DecoderSubtitleSource
    data class Local(override val key: String, override val name: String, val uri: String) : DecoderSubtitleSource
}
private data class DecoderReopen(val player: DecoderRestore, val remoteIdentity: String?,
    val local: DownloadRecord?, val sources: List<DecoderSubtitleSource>)

class ClientModel(application: Application) : AndroidViewModel(application) {
    private val presentationPreferences = FilePresentationPreferences(application)
    private val mutable = MutableStateFlow(ClientState(startupPending = true,
        compactGridSize = presentationPreferences.compactGridSize()))
    val state = mutable.asStateFlow()
    val player = NativePlayer(application)
    val playbackPreferences = PlaybackPreferences(application)
    val cache = CacheController(application, viewModelScope)
    val downloads = DownloadController(application, viewModelScope)
    internal val updates = io.github.kkwans.nasfilebrowser.update.AppUpdates(application, viewModelScope)
    val uploads = UploadController(application, viewModelScope) { context === it && generation == it.generation }
    val previewImageLoader get() = cache.thumbnailLoader.value
    fun cacheAccount(): String = context?.account?.key.orEmpty()
    fun thumbnailKey(file: ResourceRef): String = cache.key(if (file.downloadId.isEmpty()) cacheAccount() else "local-downloads", file.mediaKey, "${file.size}/${file.modified}")
    private val store = ProfileStore(ClientDatabase.get(application), CredentialVault(application))
    private val appearanceStore = AppearanceStore(ClientDatabase.get(application))
    val appearance = AppearanceController(viewModelScope, { appearanceStore.theme.first() }, appearanceStore::save)
    private val history = PlaybackHistory(ClientDatabase.get(application))
    private val recentMutable = MutableStateFlow<List<PlaybackSnapshot>>(emptyList())
    val recent = recentMutable.asStateFlow()
    private var recentLocalEntries = emptyList<PlaybackSnapshot>()
    private var recentCloudEntries: List<PlaybackSnapshot>? = null
    private var recentCloudJob: Job? = null
    private var recentCloudOwner = ""
    private var recentCloudUnavailable = false
    val profiles = store.profiles.catch { mutable.value = mutable.value.copy(error = "无法读取服务器档案，请重试") }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val embeddedNetwork = EmbeddedNetwork(application)
    private val networkMutable = MutableStateFlow(NetworkState())
    val networkState = networkMutable.asStateFlow()
    private var networkJob: Job? = null
    private var networkPollJob: Job? = null
    private var networkActivated = false
    private var transferRefresh: Job? = null
    private var transferRefreshPending = false
    private var foreground = false
    private var context: SessionContext? = null
    private var sessionSyncJob: Job? = null
    private var sessionSyncBound: SessionContext? = null
    private var sessionFlushJob: Job? = null
    private var sessionRecoveryJob: Job? = null
    private var sessionRecoveryBound: SessionContext? = null
    private var automaticRecoveryOwner = ""
    private var automaticRecoveryCode: SessionHealthCode? = null
    private var automaticRecoveryRevision = Long.MIN_VALUE
    private val feedbackMutable = MutableSharedFlow<UiFeedbackEvent>(extraBufferCapacity = 32)
    private var feedbackSequence = 0L
    internal val feedbackEvents = feedbackMutable.asSharedFlow()
    private var lease = ""
    private val subtitleLeases = mutableListOf<PreviewLease>()
    private val subtitleSources = mutableMapOf<String, DecoderSubtitleSource>()
    private var generation = 0
    private var mediaRequest = 0L
    private var queueSequence = 0L
    private var pendingMediaOpen: Long? = null
    private var pendingOpenFromPlayer = false
    private var operation: Job? = null
    private var startupJob: Job? = null
    private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val navigation = ArrayDeque<Pair<String, String>>()
    private val returnNavigation = ClientNavigationHistory()
    private var compactSizeRequest = 0L
    private var pendingDirectory: Pair<String, String>? = null
    private var playback: PlaybackBinding? = null
    private var localPlayback: DownloadRecord? = null
    private var temporaryMedia: TemporaryMediaBinding? = null
    private var localProgressPending: Job? = null
    private var saveTimer: Job? = null
    private var transferTimer: Job? = null
    private var resumeOperation: Job? = null
    private var recentJob: Job? = null
    private var closing: Job? = null
    private var mediaClosing: Job? = null
    private var lastSaved: Pair<Long, Long>? = null
    private val layoutWrites = Mutex()
    private var layoutRequest = 0L
    val search: SearchController = SearchController(viewModelScope, { context == it && generation == it.generation }, ::openSearchResult)
    val favorites = FavoritesController(viewModelScope) { context === it && generation == it.generation }
    val tags = TagsController(viewModelScope) { context === it && generation == it.generation }
    val tasks = ServerTasksController(viewModelScope) { context === it && generation == it.generation }
    val operationHistory = OperationHistoryController(viewModelScope) { context === it && generation == it.generation }
    val recentAccess = RecentAccessController(viewModelScope) { context === it && generation == it.generation }
    val fileChecksum = FileChecksumController(viewModelScope) { context === it && generation == it.generation }
    val documents = DocumentPreviewController(application, viewModelScope,
        { context === it && generation == it.generation }, { bound, file -> if (context === bound) recentAccess.record(file) })
    val archives = ArchiveController(viewModelScope, isCurrent = { context === it && generation == it.generation },
        onOpened = { bound, file -> if (context === bound) recentAccess.record(file) },
        onTaskAccepted = { bound, _ -> if (context === bound) tasks.refresh() })
    val archiveEntry = ArchiveEntryController(viewModelScope, { context === it && generation == it.generation }, ::openTemporaryContent)
    val serverSettings = ServerSettingsController(viewModelScope) { context === it && generation == it.generation }
    val shell = ShellController(viewModelScope) { context === it && generation == it.generation }
    val documentEdits = DocumentEditController(application, viewModelScope, { context === it && generation == it.generation },
        onSaved = { bound, _ -> if (context === bound) { documents.retry(); transferRefreshPending = true; refreshTransferDirectoryIfVisible() } },
        onCreated = { bound, _ -> if (context === bound) { transferRefreshPending = true; refreshTransferDirectoryIfVisible() } })
    val accountSettings = AccountSettingsController(viewModelScope, { context === it && generation == it.generation },
        onPasswordChanged = { bound, password ->
            if (context === bound && generation == bound.generation && store.password(bound.profile, bound.account) != null && context === bound)
                store.rememberPassword(bound.profile, bound.account, password)
        }, onProfileChanged = { bound, profile ->
            if (context === bound && generation == bound.generation) mutable.value = mutable.value.copy(accountName = profile.username)
        })
    val adminUsers = AdminUsersController(viewModelScope, { context === it && generation == it.generation },
        onOwnAccountChanged = { bound, _, password ->
            if (context === bound && password != null && store.password(bound.profile, bound.account) != null && context === bound)
                store.rememberPassword(bound.profile, bound.account, password)
            if (context === bound) disconnect()
        }, onOwnAccountDeleted = { bound -> if (context === bound) disconnect() })
    val trash = TrashController(viewModelScope, { context === it && generation == it.generation }, ::resourceTrashed, ::resourceRestored)
    val fileOperations = FileOperationsController(viewModelScope, { context === it && generation == it.generation }, ::resourceRenamed, ::resourceTransferFinished,
        { bound, _ -> if (context === bound && generation == bound.generation) { transferRefreshPending = true; refreshTransferDirectoryIfVisible() } }, ::resourcesBatchRenamed)
    val storageTools = StorageToolsController(viewModelScope) { context === it && generation == it.generation }
    init {
        player.externalSubtitleReady = { key, url, name ->
            // Server sources are registered before import. The existing system picker
            // calls NativePlayer directly, so register its accepted URI here.
            if ((playback != null || localPlayback != null) && key !in subtitleSources && android.net.Uri.parse(url).scheme == "content")
                subtitleSources[key] = DecoderSubtitleSource.Local(key, name, url)
        }
        viewModelScope.launch { state.collect { syncLibraryObservers() } }
        viewModelScope.launch { search.state.collect { syncLibraryObservers() } }
        viewModelScope.launch { documents.state.collect { syncLibraryObservers() } }
        viewModelScope.launch { documentEdits.state.collect { syncLibraryObservers() } }
        viewModelScope.launch { archives.state.collect { syncLibraryObservers() } }
        viewModelScope.launch { shell.state.collect { syncLibraryObservers() } }
        viewModelScope.launch {
            var watchedScope = ""
            val terminal = linkedSetOf<String>()
            tasks.state.collect { value ->
                if (value.scope != watchedScope) { watchedScope = value.scope; terminal.clear() }
                val finished = (value.items + listOfNotNull(value.selected)).filter { it.fileCategory && !it.active && it.id !in terminal }
                terminal.addAll(finished.map { it.id }); while (terminal.size > 1024) terminal.remove(terminal.first())
                val bound = context
                if (bound != null && value.scope == bound.owner) finished.lastOrNull()?.let { resourceTransferFinished(bound, it) }
            }
        }
        viewModelScope.launch { downloads.state.collect { value ->
            localPlayback?.let { item -> mutable.value = mutable.value.copy(downloadBytesPerSecond = value.speeds[item.id]) }
        } }
        viewModelScope.launch {
            var revision = 0L
            uploads.state.collect { value ->
                if (value.completedRevision != revision) {
                    revision = value.completedRevision; transferRefreshPending = true; refreshTransferDirectoryIfVisible()
                }
            }
        }
        // A request/lease is not a successful visit. Record video only after its
        // first decoded frame, once for each actual player generation.
        viewModelScope.launch {
            var recordedGeneration = -1L
            player.state.collect { value ->
                val binding = playback
                val opened = value.firstFrameRendered || binding?.file?.mediaKind() == MediaKind.AUDIO && value.playing && value.positionMs > 0
                if (opened && value.error == null && binding != null && value.mediaGeneration != recordedGeneration &&
                    context === binding.context && generation == binding.context.generation) {
                    recordedGeneration = value.mediaGeneration
                    recentAccess.record(binding.file)
                }
            }
        }
        player.checkpoint = { saveProgress() }
        val expected = generation
        startupJob = viewModelScope.launch {
            try {
                val active = store.active()
                if (generation != expected) return@launch
                if (active == null) { mutable.value = mutable.value.copy(startupPending = false); return@launch }
                val (profile, account) = active
                val accounts = store.accounts(profile)
                if (generation != expected) return@launch
                mutable.value = mutable.value.copy(profile = profile, accounts = accounts, accountName = account.username,
                    editorVersion = mutable.value.editorVersion + 1)
                connectTo(profile, account.username, "", account, automatic = true)
            } catch (error: Exception) {
                if (error !is CancellationException && generation == expected) {
                    val feedback = loginFailureFeedback(error, LoginPhase.LOCAL_RECORDS, restoring = true)
                    android.util.Log.w("LoginRecovery", feedback.diagnostic)
                    mutable.value = mutable.value.copy(startupPending = false, error = feedback.message)
                }
            }
        }
    }

    fun connect(url: String, username: String, password: String, network: String = "direct") {
        val profile = mutable.value.profile
        connectDraft(profile?.name ?: android.net.Uri.parse(url).host.orEmpty(), url, profile?.backend ?: BackendKind.NAS, username, password, network)
    }
    fun connectDraft(name: String, url: String, backend: BackendKind, username: String, password: String, network: String, rememberPassword: Boolean = false) {
        val draft = (mutable.value.profile ?: ServerProfile(name = name, address = url)).copy(name = name, address = url, backend = backend,
            network = if (network == "tailnet") ConnectionMode.TAILNET else ConnectionMode.DIRECT)
        connectTo(draft, username, password, null, rememberPassword = rememberPassword)
    }
    fun restore(account: AccountRecord) { mutable.value.profile?.let { connectTo(it, account.username, "", account) } }
    private fun connectTo(draft: ServerProfile, username: String, password: String, restored: AccountRecord?, automatic: Boolean = false, rememberPassword: Boolean = false) {
        if (!automatic) startupJob?.cancel()
        operation?.cancel(); generation++
        closeSession(); navigation.clear()
        val expected = generation
        mutable.value = mutable.value.copy(startupPending = automatic, busy = true, stage = if (restored == null) "正在登录服务器" else "正在恢复登录", error = null, selected = null, image = null, mediaQueue = null, previewScope = "", fileLayout = FileLayout.COVER, searchDisplayed = false)
        operation = viewModelScope.launch {
            var opened: NasSession? = null
            var phase = LoginPhase.LOCAL_RECORDS
            try {
                closing?.join()
                val profile = store.save(draft)
                if (profile.network == ConnectionMode.TAILNET) {
                    phase = LoginPhase.EMBEDDED_NETWORK
                    var status = if (restored == null) embeddedNetwork.status() else embeddedNetwork.start()
                    if (restored != null) {
                        networkActivated = true
                        try {
                            kotlinx.coroutines.withTimeout(20_000) {
                                while (!status.connected && status.state !in setOf("NeedsLogin", "NeedsMachineAuth", "Error")) {
                                    delay(250); status = embeddedNetwork.status()
                                }
                            }
                        } catch (_: kotlinx.coroutines.TimeoutCancellationException) { throw IllegalStateException("内嵌节点尚未连接，请检查网络并重试。") }
                        observeNetwork()
                    }
                    networkMutable.value = status
                    if (!status.connected) throw IllegalStateException("请先连接并登录 Tailscale")
                }
                phase = if (restored != null) LoginPhase.RESTORE else LoginPhase.SIGN_IN
                val saved = restored?.let { restoreSavedSession(store, profile, it) }
                opened = saved?.api ?: NasSession.login(profile, username, password)
                if (generation != expected) return@launch
                // A restored token is accepted only after an authenticated read.
                phase = LoginPhase.VERIFY
                val root = saved?.verification ?: opened.request("GET", "/api/resources/")
                if (generation != expected) return@launch
                phase = LoginPhase.SAVE_LOGIN
                val account = store.saveLogin(profile, opened.identity.id, opened.identity.username, opened.token())
                if (restored == null) store.rememberPassword(profile, account, password.takeIf { rememberPassword })
                opened.persistTokens { token -> store.refreshToken(profile, account, token); Unit }
                val bound = SessionContext(profile, account, opened, expected)
                val accounts = store.accounts(profile)
                val directory = store.directory(account)
                if (generation != expected) return@launch
                phase = LoginPhase.ACTIVATE
                store.activate(profile, account, bound.owner)
                if (generation != expected) return@launch
                context = bound
                phase = LoginPhase.DIRECTORY
                favorites.bind(bound)
                tags.bind(bound)
                tasks.bind(bound)
                operationHistory.bind(bound)
                recentAccess.bind(bound)
                fileChecksum.bind(bound)
                documents.bind(bound)
                archives.bind(bound)
                archiveEntry.bind(bound)
                serverSettings.bind(bound)
                shell.bind(bound)
                documentEdits.bind(bound)
                accountSettings.bind(bound)
                adminUsers.bind(bound)
                trash.bind(bound)
                fileOperations.bind(bound)
                storageTools.bind(bound)
                recentJob?.cancel()
                recentLocalEntries = emptyList(); recentCloudEntries = null
                recentJob = viewModelScope.launch { history.recent(account).collect { entries ->
                    if (context === bound) { recentLocalEntries = entries.distinctBy { it.resourceKey }; publishRecentPlayback() }
                } }
                mutable.value = mutable.value.copy(connected = true, profile = profile, accounts = accounts, serverLabel = "${profile.name} · ${account.username}", busy = true, stage = "正在读取目录", notice = null,
                    health = bound.api.sessionHealth.value, recoveryInProgress = false, recoveryNeedsPassword = false, recoveryError = null,
                    previewScope = opened.id, accountName = account.username, fileLayout = directory?.fileLayout ?: FileLayout.COVER, permissions = opened.identity.permissions)
                if (foreground) startSessionSync(bound)
                if (directory == null || directory.path == "/") applyDirectory(root, "/", "/", bound)
                else try { loadDirectory(directory.path, directory.wirePath, bound) } catch (error: Exception) {
                    if (error !is ServiceException || error.status !in setOf(403, 404)) throw error
                    applyDirectory(root, "/", "/", bound)
                    if (generation == expected) mutable.value = mutable.value.copy(notice = "上次的目录已不可用，已打开根目录")
                }
                if (generation == expected && context == bound) {
                    mutable.value = mutable.value.copy(startupPending = false)
                }
            } catch (error: Exception) {
                if (error !is CancellationException && generation == expected) {
                    val feedback = loginFailureFeedback(error, phase, restoring = restored != null)
                    android.util.Log.w("LoginRecovery", feedback.diagnostic)
                    val message = if (feedback.reason == "EMBEDDED_NETWORK") networkError(error) else feedback.message
                    closeSession()
                    mutable.value = mutable.value.copy(startupPending = false, connected = false, busy = false, stage = "", error = message)
                }
            } finally {
                val release = opened
                if (release != null && release != context?.api) cleanup.launch { release.close() }
            }
        }
    }
    fun selectProfile(profile: ServerProfile?) {
        disconnect()
        val expected = generation
        mutable.value = mutable.value.copy(profile = profile, editorVersion = mutable.value.editorVersion + 1, busy = profile != null, stage = if (profile != null) "正在读取档案" else "")
        if (profile != null) operation = viewModelScope.launch {
            try {
                val accounts = store.accounts(profile)
                val recent = accounts.firstOrNull { store.token(profile, it) != null }
                if (generation == expected) {
                    mutable.value = mutable.value.copy(accounts = accounts, busy = false, stage = "")
                    if (recent != null) restore(recent)
                }
            }
            catch (error: Exception) { if (error !is CancellationException && generation == expected) mutable.value = mutable.value.copy(busy = false, error = "无法读取档案账号，请重试") }
        }
    }
    fun saveDraft(name: String, address: String, backend: BackendKind, network: String) {
        operation?.cancel(); val expected = ++generation
        val draft = (mutable.value.profile ?: ServerProfile(name = name, address = address)).copy(name = name, address = address, backend = backend,
            network = if (network == "tailnet") ConnectionMode.TAILNET else ConnectionMode.DIRECT)
        mutable.value = mutable.value.copy(busy = true, stage = "正在保存档案", error = null)
        operation = viewModelScope.launch {
            try { val profile = store.save(draft); val accounts = store.accounts(profile); if (expected == generation) mutable.value = mutable.value.copy(profile = profile, accounts = accounts, busy = false, stage = "") }
            catch (error: Exception) { if (error !is CancellationException && expected == generation) mutable.value = mutable.value.copy(busy = false, stage = "", error = error.message ?: "无法保存档案") }
        }
    }
    fun removeProfile(profile: ServerProfile) {
        selectProfile(null); val expected = generation
        operation = viewModelScope.launch { try { store.remove(profile) } catch (error: Exception) { if (error !is CancellationException && generation == expected) mutable.value = mutable.value.copy(error = "无法移除档案，请重试") } }
    }
    private suspend fun loadDirectory(path: String, wire: String, bound: SessionContext) {
        val encoded = wire.ifEmpty { path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") } }
        applyDirectory(bound.api.request("GET", "/api/resources$encoded?dimensions=1"), path, wire, bound)
    }
    private suspend fun applyDirectory(data: JSONObject, path: String, wire: String, bound: SessionContext) {
        val files = directoryFiles(data)
        if (generation == bound.generation && context == bound) {
            store.saveDirectory(bound.account, path, wire)
            if (generation == bound.generation && context == bound) {
                if (pendingDirectory == (path to wire)) pendingDirectory = null
                mutable.value = mutable.value.copy(busy = false, stage = "", path = path, wirePath = wire, files = files, error = null)
                recentAccess.record(ResourceRef(path, wire, data.optString("name").ifEmpty { path.substringAfterLast('/').ifEmpty { "/" } }, true, "", 0))
            }
        }
    }
    private fun directoryFiles(data: JSONObject): List<ResourceRef> {
        val items = data.optJSONArray("items") ?: error("服务器返回了不支持的目录格式")
        return (0 until items.length()).map { index ->
            val item = items.getJSONObject(index)
            ResourceRef(item.optString("path"), item.optString("wirePath"), item.optString("name"), item.optBoolean("isDir"), item.optString("type"), item.optLong("size"), item.optString("modified"), previewWidth = item.optJSONObject("resolution")?.optInt("width") ?: 0, previewHeight = item.optJSONObject("resolution")?.optInt("height") ?: 0)
        }.sortedWith(compareByDescending<ResourceRef> { it.directory }.thenBy { it.name.lowercase() })
    }

    fun directoryItems(): List<ResourceRef> = mutable.value.let { presentFiles(it.files, it.selectedFileCategories, it.fileOrder).filter { file -> tags.matches(file) } }
    fun fileCategory(value: FileCategory) = fileCategories(if (value == FileCategory.ALL) emptySet() else setOf(value))
    fun fileCategories(values: Set<FileCategory>) {
        val selected = values - FileCategory.ALL
        mutable.value = mutable.value.copy(fileCategory = selected.singleOrNull() ?: FileCategory.ALL, fileCategories = selected)
    }
    fun fileOrder(value: FileOrder) { mutable.value = mutable.value.copy(fileOrder = value) }
    fun open(file: ResourceRef) = openFrom(file, directoryItems(), MediaQueueSource.DIRECTORY)
    fun startFileCreation(sourceScope: String) {
        val bound = context ?: return
        if (sourceScope != bound.api.id || mutable.value.busy || !mutable.value.permissions.create) return
        val operations = fileOperations.state.value
        if (operations.changing || operations.creation != null || operations.transfer != null || operations.batchRename != null) return
        documentEdits.startCreate(DirectoryCrumb("当前目录", mutable.value.path, mutable.value.wirePath), sourceScope)
    }
    fun startZipExport(files: List<ResourceRef>, sourceScope: String) {
        val bound = context ?: return
        if (sourceScope != bound.api.id) return
        downloads.enqueueZip(bound, files) { context === bound && generation == bound.generation }
        tab("downloads")
    }
    fun openShell() {
        val bound = context ?: return
        if (!mutable.value.permissions.execute || shell.state.value.open) return
        rememberNavigationSource(ClientReturnKind.AUXILIARY)
        mutable.value = mutable.value.copy(tab = "shell", searchDisplayed = false)
        shell.open(DirectoryCrumb("当前目录", mutable.value.path, mutable.value.wirePath), bound.api.id)
    }
    fun finishShellNavigation() { if (mutable.value.tab == "shell") restoreNavigationSource(ClientReturnKind.AUXILIARY) }
    fun openArchiveEntry(entry: ArchiveEntry, sourceScope: String) {
        val bound = context ?: return
        val state = archives.state.value
        if (sourceScope != bound.api.id || state.scope != bound.owner) return
        archiveEntry.open(state.file ?: return, state.listing ?: return, entry, sourceScope)
    }
    fun isTemporaryContent(file: ResourceRef): Boolean = temporaryMedia?.let { it.file.mediaKey == file.mediaKey } == true
    fun closeTemporaryContent() {
        val previous = temporaryMedia ?: return
        temporaryMedia = null
        cleanup.launch { previous.asset.release() }
    }
    private fun openTemporaryContent(bound: SessionContext, original: ResourceRef, asset: PreviewLease, kind: ArchiveEntryKind) {
        if (context !== bound || generation != bound.generation || archiveEntry.state.value.archive?.mediaKey != archives.state.value.file?.mediaKey) {
            cleanup.launch { asset.release() }; return
        }
        operation?.cancel(); val request = ++mediaRequest; pendingMediaOpen = null; pendingOpenFromPlayer = false
        documents.close(); endPlayback()
        val file = original.copy(wirePath = "/@archive-preview/${java.util.UUID.randomUUID()}/${original.wirePath}")
        mutable.value = mutable.value.copy(selected = null, image = null, mediaQueue = null, busy = true, stage = "正在打开包内文件", error = null)
        operation = viewModelScope.launch {
            var transferred = false
            try {
                mediaClosing?.join(); currentCoroutineContext().ensureActive()
                check(context === bound && generation == bound.generation && mediaRequest == request)
                temporaryMedia = TemporaryMediaBinding(bound, file, asset); transferred = true
                mutable.value = mutable.value.copy(busy = false, stage = "", progressStatus = "包内临时预览")
                when (kind) {
                    ArchiveEntryKind.IMAGE -> mutable.value = mutable.value.copy(image = file)
                    ArchiveEntryKind.VIDEO, ArchiveEntryKind.AUDIO -> {
                        mutable.value = mutable.value.copy(selected = file)
                        player.open(asset.url, 0, autoplay = foreground, videoDecodePolicy = playbackPreferences.videoDecodePolicy.value)
                        observeTransfer(bound, asset.url)
                    }
                    else -> documents.openAsset(file, asset, bound.api.id)
                }
            } catch (error: Exception) {
                if (error !is CancellationException && context === bound && mediaRequest == request)
                    mutable.value = mutable.value.copy(busy = false, stage = "", error = "包内文件打开失败，请返回压缩包重试")
                if (transferred) closeTemporaryContent()
            } finally { if (!transferred) asset.release() }
        }
    }
    fun verifyDocumentDirectory(parent: DirectoryCrumb) {
        navigateToDirectory(parent.path, parent.wirePath ?: SearchResult.encodePath(parent.path))
    }
    fun openRemotePath(path: String) = openRemotePathWithWire(path, "")
    fun openFavorite(item: Favorite, sourceScope: String) {
        val bound = context ?: return
        val collection = favorites.state.value
        if (sourceScope != bound.owner || collection.scope != sourceScope) return
        val current = collection.items.firstOrNull { it.id == item.id && it.wirePath == item.wirePath && it.path == item.path } ?: return
        if (!current.openable) {
            mutable.value = mutable.value.copy(error = "收藏原始路径无法确认，请刷新收藏或升级服务器")
            return
        }
        openRemotePathWithWire(current.path, current.wirePath)
    }
    private fun openRemotePathWithWire(path: String, requestedWire: String) {
        val bound = context ?: return
        if (mutable.value.busy) return
        operation?.cancel()
        mutable.value = mutable.value.copy(busy = true, stage = "正在确认文件来源", error = null)
        operation = viewModelScope.launch {
            try {
                val wire = requestedWire.ifEmpty { SearchResult.encodePath(path) }
                val data = bound.api.request("GET", "/api/resources$wire?metadata=1")
                currentCoroutineContext().ensureActive()
                check(context === bound && generation == bound.generation)
                val actualPath = data.optString("path", path)
                check(actualPath == path) { "文件路径已变化，请重新选择" }
                if (requestedWire.isNotEmpty()) {
                    val confirmed = favoritePathIdentity(actualPath, data.optString("wirePath").takeIf { it.isNotEmpty() }, if (data.has("wirePath")) true else null)
                    check(confirmed.openable && favoriteWireIdentity(confirmed.wirePath) == favoriteWireIdentity(requestedWire)) { "服务器返回的原始路径与所选资源不一致，请刷新核对" }
                }
                val file = ResourceRef(path, data.optString("wirePath").ifEmpty { wire }, data.optString("name").ifEmpty { path.substringAfterLast('/') },
                    data.getBoolean("isDir"), data.optString("type"), data.optLong("size"), data.optString("modified"))
                mutable.value = mutable.value.copy(busy = false, stage = "")
                if (file.directory) openQueued(file, null)
                else openQueued(file, MediaQueue.snapshot(++queueSequence, bound.account.key, file, listOf(file), MediaQueueSource.SINGLE))
            } catch (error: Exception) {
                if (error !is CancellationException && context === bound) mutable.value = mutable.value.copy(
                    busy = false, stage = "", error = error.message ?: "文件无法打开，请重试")
            }
        }
    }
    private fun openSearchResult(file: ResourceRef) = openFrom(file, search.mediaSnapshot(), MediaQueueSource.SEARCH)
    fun openTagged(file: ResourceRef, candidates: List<ResourceRef>, sourceScope: String) {
        val bound = context ?: return
        if (sourceScope != bound.owner || tags.state.value.scope != sourceScope || tags.state.value.paths.none { it.file?.mediaKey == file.mediaKey }) return
        openFrom(file, candidates, MediaQueueSource.TAGGED)
    }
    fun download(file: ResourceRef) {
        val bound = context ?: return
        downloads.enqueue(bound, file) { context === bound && generation == bound.generation }
    }
    fun beginUploadSelection(): Boolean {
        val bound = context ?: return false
        val current = mutable.value
        if (current.busy || fileOperations.state.value.changing || trash.state.value.changing) return false
        return uploads.begin(bound, DirectoryCrumb("当前目录", current.path, current.wirePath))
    }
    fun openUploadedFile(item: UploadRecord) {
        val bound = context
        if (bound == null || bound.profile.id != item.profileId || bound.profile.sourceRevision != item.sourceRevision || bound.account.key != item.accountKey) {
            uploads.reportError("请先连接这项上传原来的服务器和账号，再查看服务器文件")
            return
        }
        openContainingDirectory(ResourceRef(item.targetPath, item.targetWire, item.targetPath.substringAfterLast('/'), false, "", item.expectedSize))
    }
    fun downloadFiles(files: List<ResourceRef>, onCreated: (ResourceRef) -> Unit = {}) {
        val bound = context ?: return
        downloads.enqueueAll(bound, files.toList(), { context === bound && generation == bound.generation }, onCreated)
    }
    fun startFileTransfer(files: List<ResourceRef>, action: FileTransferAction, sourceScope: String) {
        val current = mutable.value
        if (!current.connected || current.busy || trash.state.value.changing || current.previewScope != sourceScope) return
        fileOperations.startTransfer(files, action, DirectoryCrumb("当前目录", current.path, current.wirePath))
    }
    fun showFileTask(id: String? = null) {
        if (mutable.value.tab != "tasks") rememberNavigationSource(if (id == null) ClientReturnKind.AUXILIARY else ClientReturnKind.TASK_DETAIL)
        if (mutable.value.image != null) closeImage()
        if (mutable.value.selected != null) leavePlayer()
        taskCenterSection(TaskCenterSection.BACKGROUND)
        tasks.filter(TaskFilter(category = "file"))
        id?.let(tasks::select)
    }
    fun startDirectoryCreation(sourceScope: String) {
        val current = mutable.value
        if (!current.connected || current.busy || trash.state.value.changing || current.previewScope != sourceScope) return
        fileOperations.startDirectoryCreation(DirectoryCrumb("当前目录", current.path, current.wirePath))
    }
    fun openExistingCreationDirectory() {
        val draft = fileOperations.state.value.creation ?: return
        val directory = draft.existing ?: return
        fileOperations.closeDirectoryCreation()
        navigation.addLast(mutable.value.path to mutable.value.wirePath)
        browse(directory.path, directory.wirePath!!)
    }
    fun openTransferDestination() {
        val target = fileOperations.state.value.lastDestination ?: return
        if (target.path == mutable.value.path) retry() else openRemotePath(target.path)
    }
    private fun downloadRef(item: DownloadRecord) = ResourceRef(item.path, item.wirePath, item.name, false, item.type, item.expectedSize, item.modified, item.id)
    fun openDownload(item: DownloadRecord) {
        val file = downloadRef(item)
        val candidates = downloads.state.value.items.filter { it.localUri.isNotEmpty() && (it.complete || downloadRef(it).mediaKind() == MediaKind.VIDEO) }.map(::downloadRef)
        openQueued(file, MediaQueue.snapshot(++queueSequence, "local-downloads", file, candidates, MediaQueueSource.DOWNLOADED))
    }
    private fun openFrom(file: ResourceRef, candidates: List<ResourceRef>, source: MediaQueueSource) {
        val bound = context ?: return
        openQueued(file, MediaQueue.snapshot(++queueSequence, bound.account.key, file, candidates, source))
    }
    fun navigateMedia(index: Int) {
        val current = mutable.value.mediaQueue ?: return
        if (current.owner != "local-downloads" && current.owner != context?.account?.key) return
        val queue = current.select(index) ?: return
        if (queue.index == mutable.value.mediaQueue?.index) return
        if (queue.kind == MediaKind.IMAGE) mutable.value = mutable.value.copy(image = queue.current, mediaQueue = queue, error = null)
        else openQueued(queue.current, queue)
    }
    fun previousMedia() { mutable.value.mediaQueue?.let { navigateMedia(it.index - 1) } }
    fun nextMedia() { mutable.value.mediaQueue?.let { navigateMedia(it.index + 1) } }
    fun retryPlayback() {
        val file = mutable.value.selected ?: return
        openQueued(file, mutable.value.mediaQueue)
    }
    suspend fun changeVideoDecoder(value: VideoDecodePolicy) {
        val local = localPlayback
        val binding = playback
        if (local == null && binding == null) return
        val request = mediaRequest
        val mediaGeneration = player.state.value.mediaGeneration
        val queue = mutable.value.mediaQueue
        playbackPreferences.saveVideoDecodePolicy(value)
        if (request != mediaRequest || mediaGeneration != player.state.value.mediaGeneration ||
            localPlayback !== local || playback !== binding || binding != null && context !== binding.context) return
        // Capture after the acknowledged preference write. Never use persisted history
        // or an unconfirmed seek request as this intentional reopen's checkpoint.
        val snapshot = player.decoderRestoreSnapshot() ?: return
        val restore = DecoderReopen(snapshot, binding?.identity, local,
            snapshot.externalSources.keys.mapNotNull(subtitleSources::get))
        openQueued(local?.let(::downloadRef) ?: binding!!.file, queue, snapshot.wantsPlay, restore)
    }
    private fun openQueued(file: ResourceRef, queue: MediaQueue?, autoplay: Boolean = true, decoderRestore: DecoderReopen? = null) {
        if (file.downloadId.isNotEmpty()) { openLocal(file, queue, autoplay, decoderRestore); return }
        if (isBrowsableArchive(file)) {
            val bound = context ?: return
            search.cancel(); operation?.cancel(); mediaRequest++; pendingMediaOpen = null; pendingOpenFromPlayer = false; endPlayback(); documents.close()
            mutable.value = mutable.value.copy(selected = null, image = null, mediaQueue = null, busy = false, stage = "", error = null)
            archives.open(file, bound.api.id)
            archives.setVisible(foreground)
            return
        }
        if (file.directory) {
            navigateToDirectory(file.path, file.wirePath)
        } else if (file.mediaKind() in setOf(MediaKind.VIDEO, MediaKind.AUDIO)) {
            val bound = context ?: return
            search.cancel()
            operation?.cancel(); val expected = generation; val request = ++mediaRequest
            // Preserve the entry context when a queued selection supersedes an
            // initial open that has not completed yet.
            pendingOpenFromPlayer = if (pendingMediaOpen != null) pendingOpenFromPlayer else mutable.value.selected != null
            pendingMediaOpen = request
            documents.close()
            mutable.value = mutable.value.copy(selected = file, image = null, mediaQueue = queue, busy = true, stage = if (file.mediaKind() == MediaKind.AUDIO) "正在打开音频" else "正在打开视频", error = null)
            operation = viewModelScope.launch {
                try {
                    endPlayback()
                    mediaClosing?.join()
                    currentCoroutineContext().ensureActive()
                    if (generation != expected || mediaRequest != request || context != bound) return@launch
                    val remote = history.remote(bound.api, file.path, file.wirePath)
                    val key = file.wirePath.ifEmpty { file.path }
                    val local = history.local(bound.account, key, remote.identity)
                    val resume = PlaybackHistory.resume(local, remote)
                    cache.awaitReady()
                    val url = bound.api.lease(file.path, file.wirePath, bound.account.key + "/" + remote.identity)
                    if (generation != expected || mediaRequest != request || context != bound) { cleanup.launch { NativeTransport.call(JSONObject().put("op", "revoke").put("url", url)) }; return@launch }
                    revokeLease(); lease = url
                    val writer = PlaybackWriter(history, bound.api) { snapshot, failure -> viewModelScope.launch {
                        val current = playback
                        if (mediaRequest == request && current?.context == bound && current.file.wirePath.ifEmpty { current.file.path } == key && current.identity == remote.identity &&
                            (snapshot == null || lastSaved == (snapshot.positionMs to snapshot.durationMs))) {
                            val message = when (snapshot?.sync) {
                                ProgressSync.SYNCED -> "续播已同步"
                                ProgressSync.IDENTITY_CHANGED -> "文件已变化，旧进度仅保留本机"
                                ProgressSync.UNSUPPORTED -> "续播已保存本机，此路径无法远端同步"
                                ProgressSync.PENDING -> "续播已保存本机，待同步"
                                null -> if (failure != null) "续播保存失败，请重试" else null
                            }
                            if (snapshot == null && failure != null) lastSaved = null
                            mutable.value = mutable.value.copy(progressStatus = message)
                        }
                    } }
                    playback = PlaybackBinding(bound, file, remote.identity, writer)
                    pendingMediaOpen = null
                    pendingOpenFromPlayer = false
                    lastSaved = null
                    mutable.value = mutable.value.copy(selected = file, busy = false, stage = "", progressStatus = null)
                    val restore = decoderRestore?.takeIf { it.remoteIdentity == remote.identity }
                    if (restore != null) player.openRestoring(url, restore.player, foreground && autoplay, playbackPreferences.videoDecodePolicy.value)
                    else {
                        player.open(url, resume, autoplay = foreground && autoplay, videoDecodePolicy = playbackPreferences.videoDecodePolicy.value)
                        if (decoderRestore != null) player.reportDecoderRestoreError(player.state.value.mediaGeneration,
                            "媒体版本已变化，未应用旧的解码切换恢复状态")
                    }
                    observeTransfer(bound, url)
                    saveTimer?.cancel()
                    saveTimer = viewModelScope.launch { while (true) { delay(10_000); if (player.state.value.playing) saveProgress() } }
                    if (restore != null) restoreDecoderSubtitles(restore, request, player.state.value.mediaGeneration)
                } catch (error: Exception) {
                    if (error !is CancellationException && generation == expected && mediaRequest == request) {
                        pendingMediaOpen = null
                        pendingOpenFromPlayer = false
                        mutable.value = mutable.value.copy(busy = false, stage = "", error = "无法打开视频，请重试或选择其他视频")
                    }
                }
            }
        } else if (file.mediaKind() == MediaKind.IMAGE) {
            search.cancel(); operation?.cancel(); mediaRequest++; pendingMediaOpen = null; pendingOpenFromPlayer = false; endPlayback()
            mutable.value = mutable.value.copy(selected = null, image = file, mediaQueue = queue, busy = false, stage = "", error = null)
        } else {
            val bound = context ?: return
            search.cancel(); operation?.cancel(); mediaRequest++; pendingMediaOpen = null; pendingOpenFromPlayer = false; endPlayback()
            mutable.value = mutable.value.copy(selected = null, image = null, mediaQueue = null, busy = false, stage = "", error = null)
            documents.open(file, bound.api.id)
        }
    }
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun openLocal(file: ResourceRef, queue: MediaQueue?, autoplay: Boolean, decoderRestore: DecoderReopen? = null) {
        search.close(); operation?.cancel(); documents.close()
        val request = ++mediaRequest
        pendingOpenFromPlayer = mutable.value.selected != null
        pendingMediaOpen = request
        mutable.value = mutable.value.copy(selected = file.takeIf { it.mediaKind() in setOf(MediaKind.VIDEO, MediaKind.AUDIO) }, image = null, mediaQueue = queue,
            busy = true, stage = "正在打开本机文件", error = null)
        operation = viewModelScope.launch {
            try {
                endPlayback(); mediaClosing?.join()
                val item = ClientDatabase.get(getApplication()).downloads().get(file.downloadId) ?: error("下载记录已移除")
                currentCoroutineContext().ensureActive()
                if (mediaRequest != request) return@launch
                check(item.localUri.isNotEmpty()) { "文件仍在准备，请稍后打开" }
                when (file.mediaKind()) {
                    MediaKind.IMAGE -> {
                        check(item.complete) { "图片下载完成后即可查看" }
                        mutable.value = mutable.value.copy(image = file, selected = null, busy = false, stage = "", progressStatus = null)
                    }
                    MediaKind.VIDEO, MediaKind.AUDIO -> {
                        if (!item.complete) {
                            mutable.value = mutable.value.copy(stage = "正在准备播放索引")
                            try { withContext(Dispatchers.IO) { io.github.kkwans.nasfilebrowser.download.DownloadIndex.get(getApplication()).prepare(item) } }
                            catch (failure: Exception) { if (failure is CancellationException) throw failure }
                            currentCoroutineContext().ensureActive()
                            if (mediaRequest != request) return@launch
                        }
                        localPlayback = item; lastSaved = null
                        mutable.value = mutable.value.copy(selected = file, busy = false, stage = "", progressStatus = "续播仅保存本机", downloadBytesPerSecond = 0)
                        val uri = android.net.Uri.Builder().scheme("fileway-download").authority(item.id).appendPath(item.name).build().toString()
                        val original = decoderRestore?.local
                        val restore = decoderRestore?.takeIf { original != null && original.id == item.id &&
                            original.accountKey == item.accountKey && original.sourceRevision == item.sourceRevision &&
                            original.identity == item.identity && original.localUri == item.localUri &&
                            original.expectedSize == item.expectedSize && original.modified == item.modified }
                        if (restore != null) player.openRestoring(uri, restore.player, foreground && autoplay,
                            playbackPreferences.videoDecodePolicy.value, DownloadDataSource.Factory(getApplication()))
                        else {
                            player.open(uri, item.positionMs, foreground && autoplay, playbackPreferences.videoDecodePolicy.value, DownloadDataSource.Factory(getApplication()))
                            if (decoderRestore != null) player.reportDecoderRestoreError(player.state.value.mediaGeneration,
                                "本机媒体来源或版本已变化，未应用旧的解码切换恢复状态")
                        }
                        saveTimer = viewModelScope.launch { while (true) { delay(10_000); if (player.state.value.playing) saveProgress() } }
                        // The reopen is now established; subtitle restoration must not
                        // keep the initial-open navigation flags alive.
                        pendingMediaOpen = null; pendingOpenFromPlayer = false
                        if (restore != null) restoreDecoderSubtitles(restore, request, player.state.value.mediaGeneration)
                    }
                    null -> {
                        check(item.complete) { "文档下载完成后即可内置查看" }
                        check(documentPreviewKind(downloadRef(item)) != DocumentPreviewKind.OTHER) { "此文件请从下载页面使用系统应用打开" }
                        mutable.value = mutable.value.copy(selected = null, image = null, mediaQueue = null, busy = false, stage = "", progressStatus = null)
                        documents.openLocal(item)
                    }
                }
                pendingMediaOpen = null; pendingOpenFromPlayer = false
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                if (mediaRequest == request) {
                    pendingMediaOpen = null; pendingOpenFromPlayer = false
                    mutable.value = mutable.value.copy(busy = false, stage = "", error = failure.message ?: "无法打开本机文件，请检查下载目录")
                    if (file.mediaKind() !in setOf(MediaKind.VIDEO, MediaKind.AUDIO)) downloads.reportError(failure.message ?: "本机文件无法打开")
                }
            }
        }
    }
    private fun browse(path: String, wire: String, preserveSearch: Boolean = returnNavigation.keepsSearch(navigationScope())) {
        val bound = context ?: return
        pendingDirectory = path to wire
        if (preserveSearch) search.cancel() else {
            search.close()
            mutable.value = mutable.value.copy(searchDisplayed = false)
        }
        operation?.cancel(); val expected = generation
        mutable.value = mutable.value.copy(busy = true, stage = "正在读取目录", error = null)
        operation = viewModelScope.launch {
            try { loadDirectory(path, wire, bound) } catch (error: Exception) {
                if (error !is CancellationException && generation == expected) mutable.value = mutable.value.copy(busy = false, error = error.message ?: "目录读取失败")
            }
        }
    }
    fun retry() {
        if (mutable.value.busy) return
        val target = pendingDirectory ?: (mutable.value.path to mutable.value.wirePath)
        browse(target.first, target.second)
    }
    fun openContainingDirectory(file: ResourceRef) {
        val current = mutable.value
        if (!current.connected || current.busy) return
        val parent = file.path.substringBeforeLast('/').ifEmpty { "/" }
        val wire = file.wirePath.ifEmpty { SearchResult.encodePath(file.path) }
            .substringBeforeLast('/').ifEmpty { "/" }
        navigateToDirectory(parent, wire, resetCategory = true)
    }
    fun jumpDirectory(crumb: DirectoryCrumb) {
        if (mutable.value.busy || crumb.path == mutable.value.path || crumb.wirePath == null) return
        if (directoryTrail(mutable.value.path, mutable.value.wirePath).none { it == crumb }) return
        navigation.clear()
        browse(crumb.path, crumb.wirePath)
    }
    suspend fun thumbnailPreview(file: ResourceRef): PreviewLease {
        cache.awaitReady()
        if (isTemporaryContent(file)) return preview(file, contain = true)
        val scope = state.value.previewScope
        var cached: coil3.disk.DiskCache.Snapshot? = null
        try {
            withContext(NonCancellable) { cached = cache.openThumbnail(thumbnailKey(file) + "/contain") }
            currentCoroutineContext().ensureActive()
            check(state.value.previewScope == scope) { "预览来源已变化" }
            val snapshot = cached
            if (snapshot != null) return PreviewLease(android.net.Uri.fromFile(snapshot.data.toFile()).toString(), scope) { snapshot.close() }
        } catch (failure: Exception) { cached?.close(); throw failure }
        return preview(file, contain = true)
    }
    suspend fun preview(file: ResourceRef, contain: Boolean = false): PreviewLease {
        temporaryMedia?.takeIf { it.file.mediaKey == file.mediaKey && context === it.context }?.let { return PreviewLease(it.asset.url, it.asset.scope) {} }
        if (file.downloadId.isNotEmpty()) return image(file, ImageQuality.ORIGINAL).first
        val bound = context ?: error("服务器尚未连接")
        val asset = bound.api.preview(file.path, file.wirePath, contain)
        try {
            currentCoroutineContext().ensureActive()
            check(context == bound && generation == bound.generation) { "服务器来源已切换" }
            return asset
        } catch (error: Throwable) {
            runCatching { asset.release() }.onFailure { error.addSuppressed(it) }
            throw error
        }
    }
    suspend fun videoSprite(file: ResourceRef): Pair<VideoSprite, android.graphics.Bitmap>? {
        val binding = playback ?: return null
        if (binding.file.mediaKey != file.mediaKey) return null
        fun current() = playback === binding && context == binding.context
        val metadata = kotlinx.coroutines.withTimeoutOrNull(180_000) {
            var ready: VideoSprite? = null
            while (ready == null) {
                currentCoroutineContext().ensureActive()
                check(current()) { "播放来源已切换" }
                val result = binding.context.api.spriteMetadata(file.path)
                check(current()) { "播放来源已切换" }
                when (result.optString("state")) {
                    "ready" -> ready = VideoSprite.from(result)
                    "preparing" -> delay(2000)
                    else -> return@withTimeoutOrNull null
                }
            }
            ready
        }
        val sprite = metadata ?: return null
        val lease = binding.context.api.spriteImage(file.path)
        try {
            check(current()) { "播放来源已切换" }
            val key = thumbnailKey(file) + "/sprite"
            val result = cache.thumbnailLoader.value.execute(coil3.request.ImageRequest.Builder(getApplication<Application>())
                .data(lease.url).size(sprite.sheetWidth, sprite.sheetHeight).allowHardware(false)
                .memoryCacheKey(key).diskCacheKey(key)
                .diskCachePolicy(if (cache.state.value.settings.thumbnailMB == 0L) coil3.request.CachePolicy.DISABLED else coil3.request.CachePolicy.ENABLED)
                .build())
            currentCoroutineContext().ensureActive()
            check(current()) { "播放来源已切换" }
            val image = (result as? coil3.request.SuccessResult)?.image as? coil3.BitmapImage ?: return null
            if (image.bitmap.width != sprite.sheetWidth || image.bitmap.height != sprite.sheetHeight) return null
            return sprite to image.bitmap
        } finally { withContext(kotlinx.coroutines.NonCancellable) { lease.release() } }
    }
    suspend fun subtitleFiles(path: String, wirePath: String): List<ResourceRef> {
        val binding = playback ?: error("视频尚未打开")
        val wire = wirePath.ifEmpty { SearchResult.encodePath(path) }
        val data = binding.context.api.request("GET", "/api/resources$wire")
        check(playback === binding && context == binding.context) { "播放来源已切换" }
        val items = data.optJSONArray("items") ?: error("不是可读取的目录")
        return (0 until items.length()).map { i -> items.getJSONObject(i).let {
            ResourceRef(it.optString("path"), it.optString("wirePath"), it.optString("name"), it.optBoolean("isDir"), it.optString("type"), it.optLong("size"), it.optString("modified"))
        } }.filter { it.directory || isExternalSubtitle(it.name) }
            .sortedWith(compareByDescending<ResourceRef> { it.directory }.thenBy { it.name.lowercase() })
    }
    suspend fun addExternalSubtitle(file: ResourceRef) {
        require(!file.directory && isExternalSubtitle(file.name)) { "请选择支持的字幕文件" }
        val binding = playback ?: error("视频尚未打开")
        check(player.canAddExternalSubtitle) { "视频仍在加载，请稍后重试添加字幕" }
        val request = mediaRequest
        val epoch = player.state.value.mediaGeneration
        fun current() = mediaRequest == request && player.state.value.mediaGeneration == epoch &&
            playback === binding && context === binding.context
        val verified = subtitleVersion(binding.context, file)
        check(current()) { "播放来源已切换" }
        val source = DecoderSubtitleSource.Server(java.util.UUID.randomUUID().toString(), file.name,
            binding.context, verified, "${verified.size}/${verified.modified}")
        check(importServerSubtitle(source, selectOnReady = true, ::current)) { "播放器未能加载字幕，请确认文件格式后重试" }
    }
    private suspend fun subtitleVersion(bound: SessionContext, file: ResourceRef): ResourceRef {
        check(sessionCurrent(bound)) { "字幕来源账号已切换" }
        val wire = file.wirePath.ifEmpty { SearchResult.encodePath(file.path) }
        val metadata = bound.api.request("GET", "/api/resources$wire")
        check(sessionCurrent(bound)) { "字幕来源账号已切换" }
        require(!metadata.optBoolean("isDir") && metadata.has("size") && metadata.has("modified")) { "无法确认字幕文件版本" }
        return file.copy(size = metadata.getLong("size"), modified = metadata.getString("modified"))
    }
    private suspend fun importServerSubtitle(source: DecoderSubtitleSource.Server, selectOnReady: Boolean,
        current: () -> Boolean, expectedVersion: String? = null): Boolean {
        check(current() && sessionCurrent(source.bound)) { "字幕来源已切换" }
        val version = subtitleVersion(source.bound, source.file)
        check(current() && "${version.size}/${version.modified}" == source.identity) { "字幕文件版本已变化" }
        val url = source.bound.api.lease(source.file.path, source.file.wirePath)
        val asset = PreviewLease(url, source.bound.api.id) { NativeTransport.call(JSONObject().put("op", "revoke").put("url", url)); Unit }
        var retained = false
        try {
            currentCoroutineContext().ensureActive()
            check(current() && sessionCurrent(source.bound)) { "字幕来源已切换" }
            subtitleSources[source.key] = source
            val imported = withTimeoutOrNull(60_000) {
                player.importSubtitle(url, source.name, source.key, selectOnReady, expectedVersion)
            } == true
            check(current()) { "播放来源已切换" }
            if (imported) { subtitleLeases.add(asset); retained = true }
            return imported
        } finally {
            if (!retained) {
                if (subtitleSources[source.key] === source) subtitleSources.remove(source.key)
                withContext(NonCancellable) { asset.release() }
            }
        }
    }
    private suspend fun restoreDecoderSubtitles(restore: DecoderReopen, request: Long, epoch: Long) {
        try { importDecoderSubtitles(restore, request, epoch) }
        catch (cancelled: CancellationException) {
            player.cancelDecoderRestore(epoch)
            throw cancelled
        }
    }
    private suspend fun importDecoderSubtitles(restore: DecoderReopen, request: Long, epoch: Long) {
        fun current() = mediaRequest == request && player.state.value.mediaGeneration == epoch
        val failures = mutableListOf<String>()
        if (restore.sources.size != restore.player.externalSources.size)
            failures.add("部分外挂字幕来源已失效，请重新添加")
        if (restore.sources.isNotEmpty()) {
            val ready = withTimeoutOrNull(30_000) {
                player.state.first { !current() || !player.wantsDecoderSubtitleRestore(epoch) || player.canAddExternalSubtitle || it.error != null }
                current() && player.canAddExternalSubtitle
            } == true
            if (!current()) return
            if (!player.wantsDecoderSubtitleRestore(epoch)) { player.finishDecoderRestore(epoch, emptyList()); return }
            if (!ready) failures.add("播放器尚未就绪，未能恢复外挂字幕，请重新添加")
            else for (source in restore.sources.sortedBy {
                if (it.key == restore.player.externalSubtitle?.sourceKey) 0 else 1
            }) {
                if (!current()) return
                if (!player.wantsDecoderSubtitleRestore(epoch)) { player.finishDecoderRestore(epoch, emptyList()); return }
                try {
                    val imported = when (source) {
                        is DecoderSubtitleSource.Server -> importServerSubtitle(source, false, ::current, restore.player.externalSources[source.key])
                        is DecoderSubtitleSource.Local -> {
                            // Re-read only the exact URI originally granted by the system picker.
                            // The data source checks the still-live URI permission; no new grant.
                            val result = withTimeoutOrNull(60_000) {
                                player.importSubtitle(source.uri, source.name, source.key, false, restore.player.externalSources[source.key])
                            } == true
                            if (current() && result) subtitleSources[source.key] = source
                            result
                        }
                    }
                    if (!imported && player.wantsDecoderSubtitleRestore(epoch)) failures.add("部分外挂字幕读取或导入失败，请重新添加")
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    if (current()) failures.add("部分外挂字幕已变化、失去读取权限或导入失败，请重新添加")
                }
            }
        }
        if (current()) player.finishDecoderRestore(epoch, failures.distinct())
    }
    fun closeImage() { if (mutable.value.image?.let(::isTemporaryContent) == true) closeTemporaryContent(); mutable.value = mutable.value.copy(image = null, mediaQueue = null) }
    suspend fun hasCachedOriginal(file: ResourceRef): Boolean {
        cache.awaitReady()
        val account = if (file.downloadId.isNotEmpty()) "local-downloads" else cacheAccount()
        return cache.hasCachedImage(cache.key(account, file.mediaKey, "${file.size}/${file.modified}/${ImageQuality.ORIGINAL.name}"), account)
    }
    suspend fun saveImageToAlbum(file: ResourceRef) {
        val account = if (file.downloadId.isNotEmpty()) "local-downloads" else cacheAccount()
        val key = cache.key(account, file.mediaKey, "${file.size}/${file.modified}/${ImageQuality.ORIGINAL.name}")
        val image = cache.cachedImages(account).firstOrNull { it.key == key } ?: error("原图尚未缓存，请等待原图加载后保存")
        cache.saveCachedImageToAlbum(image)
    }
    suspend fun image(file: ResourceRef, quality: ImageQuality): Pair<PreviewLease, ResourceRef> {
        temporaryMedia?.takeIf { it.file.mediaKey == file.mediaKey && context === it.context }?.let { return PreviewLease(it.asset.url, it.asset.scope) {} to file }
        if (file.downloadId.isNotEmpty()) {
            val item = ClientDatabase.get(getApplication()).downloads().get(file.downloadId) ?: error("下载记录已移除")
            check(item.complete && item.localUri.isNotEmpty()) { "图片下载完成后即可查看" }
            return PreviewLease(item.localUri, "local-downloads") { } to file
        }
        val bound = context ?: error("服务器尚未连接")
        cache.awaitReady()
        val cachedKey = cache.key(cacheAccount(), file.mediaKey, "${file.size}/${file.modified}/${quality.name}")
        if (cache.hasCachedImage(cachedKey, cacheAccount())) {
            val cached = cache.openCachedImage(cachedKey, cacheAccount())
            if (context !== bound || generation != bound.generation) { cached.close(); error("服务器来源已切换") }
            return PreviewLease(android.net.Uri.fromFile(cached.file).toString(), bound.api.id) { cached.close() } to file
        }
        val wire = file.wirePath.ifEmpty { file.path.split('/').joinToString("/") { android.net.Uri.encode(it) } }
        val data = bound.api.request("GET", "/api/resources$wire")
        require(!data.optBoolean("isDir")) { "该文件已变化" }
        val current = file.copy(size = data.optLong("size", file.size), modified = data.optString("modified", file.modified))
        val asset = bound.api.image(current.path, current.wirePath, quality)
        try { currentCoroutineContext().ensureActive(); check(context == bound && generation == bound.generation) { "服务器来源已切换" }; return asset to current }
        catch (error: Throwable) { asset.release(); throw error }
    }
    fun openSearch() {
        val bound = context ?: return
        if (!mutable.value.busy && mutable.value.selected == null) {
            search.open(bound, mutable.value.path, mutable.value.wirePath)
            mutable.value = mutable.value.copy(searchDisplayed = true)
        }
    }
    fun cancel() {
        pendingDirectory = null
        startupJob?.cancel()
        operation?.cancel()
        resumeOperation?.cancel()
        val opening = pendingMediaOpen != null
        val returnToSource = opening && !pendingOpenFromPlayer
        if (opening) { mediaRequest++; pendingMediaOpen = null; pendingOpenFromPlayer = false }
        if (returnToSource) endPlayback()
        val startup = mutable.value.startupPending
        if (startup) { generation++; closeSession() }
        mutable.value = mutable.value.copy(startupPending = false, busy = false, stage = "",
            connected = if (startup) false else mutable.value.connected,
            previewScope = if (startup) "" else mutable.value.previewScope,
            selected = if (returnToSource) null else mutable.value.selected,
            mediaQueue = if (returnToSource) null else mutable.value.mediaQueue,
            error = if (returnToSource) null else if (opening) "已取消打开，可重试或选择其他视频" else mutable.value.error)
    }
    fun back(): Boolean {
        // The editor's own BackHandler owns dirty/unknown-write confirmation.
        if (documentEdits.state.value.file != null) return true
        if (documents.state.value.file != null) { documents.close(); closeTemporaryContent(); return true }
        if (shell.state.value.open) { shell.close(); finishShellNavigation(); return true }
        if (mutable.value.image != null) { closeImage(); return true }
        if (mutable.value.selected != null) { leavePlayer(); return true }
        if (archives.state.value.file != null) { closeArchive(); return true }
        if (mutable.value.searchDisplayed && search.state.value.open) {
            cancel(); search.close(); mutable.value = mutable.value.copy(searchDisplayed = false); return true
        }
        if (tasks.state.value.focusId != null && mutable.value.tab == "tasks") { closeTaskDetail(); return true }
        if (mutable.value.tab == "admin-users") {
            val users = adminUsers.state.value
            if (users.saving) return true
            if (users.draft != null && !users.unknown) { adminUsers.closeEditor(); return true }
        }
        if (mutable.value.tab == "server-settings" && serverSettings.state.value.saving) return true
        val source = returnNavigation.peek(navigationScope())
        if (source != null && source.kind != ClientReturnKind.DIRECTORY) return restoreNavigationSource()
        if (mutable.value.tab != "files") { navigateMain(ClientMainDestination.FILES); return true }
        if (navigation.isNotEmpty()) { val previous = navigation.removeLast(); browse(previous.first, previous.second); return true }
        if (source?.kind == ClientReturnKind.DIRECTORY) return restoreNavigationSource(ClientReturnKind.DIRECTORY)
        if (mutable.value.path != "/") {
            val trail = directoryTrail(mutable.value.path, mutable.value.wirePath)
            val parent = trail.dropLast(1).lastOrNull { it.wirePath != null } ?: trail.first()
            browse(parent.path, parent.wirePath ?: "/"); return true
        }
        return false
    }
    fun clearPreviewCache() { previewImageLoader.memoryCache?.clear() }
    fun fileLayout(value: FileLayout) {
        val bound = context ?: return
        val request = ++layoutRequest
        viewModelScope.launch {
            try {
                // Preserve write order for rapid selections. Each write keeps
                // its original account; a late result never changes a new session.
                layoutWrites.withLock { store.saveFileLayout(bound.account, value) }
                if (context == bound && generation == bound.generation && layoutRequest == request)
                    mutable.value = mutable.value.copy(fileLayout = value,
                        notice = mutable.value.notice.takeUnless { it == "布局未能保存，请重新选择并重试" })
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (context == bound && generation == bound.generation && layoutRequest == request)
                    mutable.value = mutable.value.copy(notice = "布局未能保存，请重新选择并重试")
            }
        }
    }
    fun tab(value: String) {
        val destination = ClientDestination.forRoute(value, mutable.value.taskCenterSection) ?: return
        if (destination == ClientDestination.SEARCH) { openSearch(); return }
        if (destination == ClientDestination.SHELL) { openShell(); return }
        val current = mutable.value
        if (!destination.allowed(current.connected, current.permissions.admin, current.permissions.execute)) return
        val target = destination.route
        if (destination.main == null && target != current.tab) rememberNavigationSource(ClientReturnKind.AUXILIARY)
        if ((TaskCenterSection.forRoute(target) != null || target == "updates") && mutable.value.startupPending) cancel()
        if (returnNavigation.keepsSearch(navigationScope())) search.cancel() else search.close()
        mutable.value = mutable.value.copy(tab = target, searchDisplayed = false,
            taskCenterSection = TaskCenterSection.forRoute(target) ?: mutable.value.taskCenterSection)
    }
    fun navigateMain(destination: ClientMainDestination) {
        returnNavigation.clear()
        search.close()
        if (destination != ClientMainDestination.TASKS) tasks.closeDetail()
        if (destination == ClientMainDestination.LIBRARY && !mutable.value.connected) { tab("files"); return }
        tab(destination.route)
    }
    fun recentSection(value: RecentSection) { search.close(); mutable.value = mutable.value.copy(tab = "recent", recentSection = value, searchDisplayed = false) }
    fun taskCenterSection(value: TaskCenterSection) { tab(value.route) }
    fun consumeNotice(message: String, sourceScope: String) {
        val current = mutable.value
        if (current.previewScope == sourceScope && current.notice == message) mutable.value = current.copy(notice = null)
    }
    fun showConnection() { tab("files") }
    fun openUpdates() { tab("updates") }
    fun openDownloads() {
        documents.close(); closeTemporaryContent()
        if (mutable.value.selected != null || pendingMediaOpen != null) leavePlayer()
        if (mutable.value.image != null) closeImage()
        tab("downloads")
    }
    fun openUploads() {
        if (mutable.value.selected != null || pendingMediaOpen != null) leavePlayer()
        if (mutable.value.image != null) closeImage()
        tab("uploads")
    }
    fun librarySection(value: LibrarySection) {
        if (value == LibrarySection.TASKS) { taskCenterSection(TaskCenterSection.BACKGROUND); return }
        if (returnNavigation.keepsSearch(navigationScope())) search.cancel() else search.close()
        mutable.value = mutable.value.copy(tab = "library", librarySection = value, searchDisplayed = false)
    }
    fun showServerTask(id: String) {
        if (mutable.value.tab != "tasks") rememberNavigationSource(ClientReturnKind.TASK_DETAIL)
        taskCenterSection(TaskCenterSection.BACKGROUND)
        tasks.filter(tasks.state.value.filter.copy(category = "background")); tasks.select(id)
    }
    fun closeTaskDetail() {
        tasks.closeDetail()
        restoreNavigationSource(ClientReturnKind.TASK_DETAIL)
    }
    fun showAnalysis(id: String, type: String) {
        val source = returnNavigation.peek(navigationScope())
        if (source?.kind == ClientReturnKind.TASK_DETAIL && source.point.route == "library" && source.point.library == LibrarySection.TOOLS) {
            restoreNavigationSource(ClientReturnKind.TASK_DETAIL)
        }
        if (mutable.value.tab != "library" || mutable.value.librarySection != LibrarySection.TOOLS)
            rememberNavigationSource(ClientReturnKind.AUXILIARY)
        librarySection(LibrarySection.TOOLS); storageTools.openReport(id, if (type == "analysis.storage") "storage" else "duplicates")
    }
    fun closeArchive() { if (!archives.state.value.submitting) archives.close() }

    private fun navigationScope(): String = context?.owner ?: "local"
    private fun rememberNavigationSource(kind: ClientReturnKind) {
        returnNavigation.push(navigationScope(), ClientNavigationReturn(kind, ClientNavigationPoint.from(mutable.value), navigation.toList()))
    }
    private fun restoreNavigationSource(kind: ClientReturnKind? = null): Boolean {
        val source = returnNavigation.pop(navigationScope(), kind) ?: return false
        val point = source.point
        navigation.clear(); navigation.addAll(source.directoryHistory)
        val old = mutable.value
        mutable.value = old.copy(tab = point.route, librarySection = point.library, recentSection = point.recent,
            taskCenterSection = point.task, fileCategory = point.category, fileCategories = point.categories, fileOrder = point.order,
            searchDisplayed = point.searchDisplayed && search.state.value.open)
        if (old.connected && (old.path != point.path || old.wirePath != point.wirePath))
            browse(point.path, point.wirePath, preserveSearch = point.searchDisplayed || returnNavigation.keepsSearch(navigationScope()))
        return true
    }
    private fun navigateToDirectory(path: String, wire: String, resetCategory: Boolean = false) {
        val current = mutable.value
        if (current.tab != "files" || current.searchDisplayed) {
            rememberNavigationSource(ClientReturnKind.DIRECTORY)
            navigation.clear()
        } else if (path != current.path || wire != current.wirePath) navigation.addLast(current.path to current.wirePath)
        mutable.value = current.copy(tab = "files", searchDisplayed = false,
            fileCategory = if (resetCategory) FileCategory.ALL else current.fileCategory, fileCategories = if (resetCategory) emptySet() else current.selectedFileCategories)
        browse(path, wire)
    }
    fun compactGridSize(value: CompactGridSize) {
        val request = ++compactSizeRequest
        viewModelScope.launch {
            try {
                presentationPreferences.saveCompactGridSize(value)
                if (compactSizeRequest == request) mutable.value = mutable.value.copy(compactGridSize = value,
                    notice = mutable.value.notice.takeUnless { it == "图标大小未能保存，请重试" })
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (compactSizeRequest == request) mutable.value = mutable.value.copy(notice = "图标大小未能保存，请重试")
            }
        }
    }
    private fun syncLibraryObservers() {
        val contentCovered = mutable.value.selected != null || mutable.value.image != null || documents.state.value.file != null ||
            documentEdits.state.value.file != null || archives.state.value.file != null || shell.state.value.open
        val visible = foreground && mutable.value.connected && !contentCovered && !mutable.value.searchDisplayed
        tasks.setVisible(visible && mutable.value.tab == TaskCenterSection.BACKGROUND.route)
        operationHistory.setVisible(visible && mutable.value.tab == TaskCenterSection.HISTORY.route)
        recentAccess.setVisible(visible && mutable.value.tab == "recent" && mutable.value.recentSection == RecentSection.ACCESS)
        trash.setVisible(visible && mutable.value.tab == "library" && mutable.value.librarySection == LibrarySection.TRASH)
        storageTools.setVisible(visible && mutable.value.tab == "library" && mutable.value.librarySection == LibrarySection.TOOLS)
        accountSettings.setVisible(visible && mutable.value.tab == "account")
        serverSettings.setVisible(visible && mutable.value.tab == "server-settings")
        archives.setVisible(foreground && archives.state.value.file != null && mutable.value.connected && mutable.value.selected == null &&
            mutable.value.image == null && documents.state.value.file == null && documentEdits.state.value.file == null)
        adminUsers.setVisible(visible && mutable.value.tab == "admin-users")
        downloads.visible(foreground && (!contentCovered && mutable.value.tab == "downloads" || localPlayback != null))
        uploads.visible(foreground && !contentCovered && mutable.value.tab == "uploads")
        fileOperations.setVisible(foreground && mutable.value.connected)
        refreshTransferDirectoryIfVisible()
    }
    private fun resourceTransferFinished(bound: SessionContext, task: ServerTask, sources: List<ResourceRef> = emptyList()) {
        if (context !== bound || generation != bound.generation || !task.fileCategory) return
        favorites.refresh(replaceRead = true); tags.refresh(replaceRead = true)
        if (task.type == "file.move" && task.status == "completed") mutable.value.image?.let { image ->
            val imageWire = image.wirePath.ifEmpty { SearchResult.encodePath(image.path) }
            if (sources.any { val wire = it.wirePath.ifEmpty { SearchResult.encodePath(it.path) }; wire == imageWire || it.directory && imageWire.startsWith(wire.trimEnd('/') + "/") }) closeImage()
        }
        transferRefreshPending = true
        refreshTransferDirectoryIfVisible()
    }
    private fun refreshTransferDirectoryIfVisible() {
        val bound = context ?: return
        val current = mutable.value
        if (!transferRefreshPending || transferRefresh?.isActive == true || !foreground || !current.connected || current.busy ||
            current.selected != null || current.image != null || current.tab != "files" || current.searchDisplayed ||
            documents.state.value.file != null || documentEdits.state.value.file != null || archives.state.value.file != null || shell.state.value.open) return
        transferRefreshPending = false
        transferRefresh = viewModelScope.launch {
            try {
                val files = directoryFiles(bound.api.request("GET", "/api/resources${current.wirePath.ifEmpty { SearchResult.encodePath(current.path) }}"))
                val after = mutable.value
                if (context === bound && generation == bound.generation && after.path == current.path && after.wirePath == current.wirePath &&
                    !after.busy && after.selected == null && after.image == null) mutable.value = after.copy(files = files)
                else if (context === bound) transferRefreshPending = true
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (context === bound) mutable.value = mutable.value.copy(notice = "文件任务状态已更新，目录刷新失败，请刷新查看实际结果")
            } finally {
                if (context === bound) { transferRefresh = null; if (transferRefreshPending) refreshTransferDirectoryIfVisible() }
            }
        }
    }
    private fun resourceTrashed(bound: SessionContext, file: ResourceRef) {
        if (context !== bound) return
        val wire = file.wirePath.ifEmpty { SearchResult.encodePath(file.path) }
        fun removed(path: String) = path == wire || file.directory && path.startsWith(wire.trimEnd('/') + "/")
        mutable.value.selected?.takeIf { removed(it.wirePath.ifEmpty { SearchResult.encodePath(it.path) }) }?.let { leavePlayer() }
        mutable.value.image?.takeIf { removed(it.wirePath.ifEmpty { SearchResult.encodePath(it.path) }) }?.let { closeImage() }
        mutable.value = mutable.value.copy(files = mutable.value.files.filterNot { removed(it.wirePath.ifEmpty { SearchResult.encodePath(it.path) }) }, notice = "已移入回收站")
        favorites.refresh(); tags.refresh()
        if (removed(mutable.value.wirePath)) {
            navigation.clear()
            mutable.value = mutable.value.copy(tab = "files", fileCategory = FileCategory.ALL, fileCategories = emptySet(), searchDisplayed = false)
            browse(file.path.substringBeforeLast('/').ifEmpty { "/" }, wire.substringBeforeLast('/').ifEmpty { "/" })
        }
    }
    private fun resourceRenamed(bound: SessionContext, file: ResourceRef, target: RenameTarget) {
        if (context !== bound) return
        val source = file.wirePath.ifEmpty { SearchResult.encodePath(file.path) }
        fun matches(wire: String) = wire == source || file.directory && wire.startsWith(source.trimEnd('/') + "/")
        mutable.value = mutable.value.copy(files = mutable.value.files.map { item ->
            val wire = item.wirePath.ifEmpty { SearchResult.encodePath(item.path) }
            if (!matches(wire)) item else item.copy(path = target.path + item.path.removePrefix(file.path),
                wirePath = target.wirePath + wire.removePrefix(source), name = if (wire == source) target.name else item.name)
        }, notice = "已重命名为 ${target.name}")
        favorites.refresh(replaceRead = true); tags.refresh(replaceRead = true)
        if (matches(mutable.value.wirePath)) {
            val current = mutable.value
            navigation.clear()
            browse(target.path + current.path.removePrefix(file.path), target.wirePath + current.wirePath.removePrefix(source))
        }
    }
    private fun resourcesBatchRenamed(bound: SessionContext, changes: List<BatchRenameChange>) {
        if (context !== bound || generation != bound.generation) return
        // Apply the entire original snapshot once: a->b, b->a must never rewrite
        // the result of the first rename as though it were the second source.
        mutable.value = mutable.value.copy(files = applyBatchResourceRenames(mutable.value.files, changes),
            notice = "已重命名 ${changes.size} 项")
        favorites.refresh(replaceRead = true); tags.refresh(replaceRead = true)
        if (recentAccess.state.value.loaded) recentAccess.refresh()
        transferRefreshPending = true
        refreshTransferDirectoryIfVisible()
    }
    private fun resourceRestored(bound: SessionContext, path: String) {
        if (context !== bound) return
        favorites.refresh(); tags.refresh()
        // Re-read the directory before returning from the recycle bin; a
        // restored file's favorites/tags are supplied by the server transaction.
        if (mutable.value.path == path.substringBeforeLast('/').ifEmpty { "/" }) retry()
    }
    /** Called only after the active remote image has actually decoded. */
    fun recordRecentAccess(file: ResourceRef, sourceScope: String = mutable.value.previewScope) {
        if (isTemporaryContent(file)) return
        val bound = context ?: return
        val active = mutable.value.image ?: return
        if (sourceScope == mutable.value.previewScope && file.downloadId.isEmpty() && active.mediaKey == file.mediaKey && generation == bound.generation) recentAccess.record(file)
    }
    fun openRecentAccess(entry: RecentAccessEntry, sourceScope: String) {
        val bound = context ?: return
        val access = recentAccess.state.value
        if (sourceScope != bound.owner || access.scope != sourceScope || mutable.value.busy || !entry.openable ||
            access.items.none { it.id == entry.id && it.path == entry.path && it.wirePath == entry.wirePath }) return
        val file = entry.resource()
        // Revalidate the authoritative resource metadata. The history DTO is
        // not a playback-progress record and never becomes a directory queue.
        openRemotePathWithWire(file.path, file.wirePath)
    }
    fun openRecentAccessLocation(entry: RecentAccessEntry, sourceScope: String) {
        val bound = context ?: return
        val access = recentAccess.state.value
        if (sourceScope != bound.owner || access.scope != sourceScope || mutable.value.busy || !entry.openable ||
            access.items.none { it.id == entry.id && it.path == entry.path && it.wirePath == entry.wirePath }) return
        openContainingDirectory(entry.resource())
    }
    private fun publishRecentPlayback() {
        val cloud = recentCloudEntries
        val local = recentLocalEntries.associateBy { it.resourceKey }
        val merged = if (cloud == null) recentLocalEntries else {
            val serverKeys = cloud.map { it.resourceKey }.toSet()
            cloud.map { remote -> local[remote.resourceKey]?.takeIf {
                it.identity == remote.identity && it.sync == ProgressSync.PENDING && it.updatedAt > remote.updatedAt
            } ?: remote } + recentLocalEntries.filter { it.resourceKey !in serverKeys && it.sync in setOf(ProgressSync.PENDING, ProgressSync.UNSUPPORTED) }
        }
        recentMutable.value = merged.distinctBy { it.resourceKey }.sortedByDescending { it.updatedAt }.take(100)
    }
    fun refreshRecentPlayback(force: Boolean = false) {
        if (recentCloudUnavailable && !force) return
        val bound = context ?: return
        if (recentCloudJob?.isActive == true && recentCloudOwner == bound.api.id) return
        recentCloudJob?.cancel(); recentCloudOwner = bound.api.id
        mutable.value = mutable.value.copy(recentPlaybackLoading = recentCloudEntries == null)
        recentCloudJob = viewModelScope.launch {
            try {
                val rows = history.recentRemote(bound.api, bound.account)
                if (context !== bound || generation != bound.generation) return@launch
                recentCloudUnavailable = false
                recentCloudEntries = rows; publishRecentPlayback()
                mutable.value = mutable.value.copy(recentPlaybackLoading = false, recentPlaybackError = null)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (context === bound && generation == bound.generation) recentCloudUnavailable = error is ServiceException && error.status in setOf(404, 501)
                if (context === bound && generation == bound.generation) mutable.value = mutable.value.copy(
                    recentPlaybackLoading = false, recentPlaybackError = if (error is ServiceException && error.status in setOf(404, 501))
                        "服务器尚不支持云端播放列表，需升级服务；当前显示本机记录"
                    else when {
                        error is ServiceException && error.status == 401 -> "登录已过期，请重新连接；已保留本机记录"
                        error is ServiceException && error.status == 403 -> "当前账号无权读取播放记录"
                        error is org.json.JSONException -> "服务器未返回有效播放列表，请升级配套服务；已保留本机记录"
                        error is ServiceException -> "播放记录暂不可用（HTTP ${error.status}），已保留现有记录"
                        else -> "无法连接播放记录服务，已保留现有记录"
                    })
            }
        }
    }
    fun stopRecentPlaybackRefresh(sourceScope: String) {
        if (recentCloudOwner == sourceScope) { recentCloudJob?.cancel(); mutable.value = mutable.value.copy(recentPlaybackLoading = false) }
    }
    fun openRecent(snapshot: PlaybackSnapshot) {
        val bound = context ?: return
        if (snapshot.accountKey != bound.account.key) return
        val file = ResourceRef(snapshot.path, snapshot.wirePath, snapshot.name, false, if (mediaKind(snapshot.name) == MediaKind.AUDIO) "audio" else "video", 0)
        openFrom(file, listOf(file), MediaQueueSource.SINGLE)
    }
    fun togglePlayback() {
        if (player.state.value.playing) { pausePlayback(); return }
        if (localPlayback != null) { if (foreground) player.toggle(); return }
        val binding = playback ?: return
        if (!foreground) return
        resumeOperation?.cancel()
        mutable.value = mutable.value.copy(busy = true, stage = "正在确认播放来源", error = null)
        resumeOperation = viewModelScope.launch {
            try {
                val remote = history.remote(binding.context.api, binding.file.path, binding.file.wirePath)
                if (playback != binding || context != binding.context) return@launch
                if (remote.identity != binding.identity) {
                    endPlayback()
                    mutable.value = mutable.value.copy(busy = false, stage = "", selected = null, error = "文件已变化，请重新打开；旧进度不会用于新文件。")
                } else {
                    mutable.value = mutable.value.copy(busy = false, stage = "")
                    player.toggle()
                }
            } catch (error: Exception) {
                if (error !is CancellationException && playback == binding) mutable.value = mutable.value.copy(busy = false, stage = "", error = "无法确认网络和文件，请重试播放。")
            }
        }
    }
    fun pausePlayback() {
        if (resumeOperation?.isActive == true) {
            resumeOperation?.cancel()
            mutable.value = mutable.value.copy(busy = false, stage = "")
        }
        player.pause(); saveProgress()
    }
    fun retryProgress() { lastSaved = null; saveProgress() }
    private fun snapshot(binding: PlaybackBinding): PlaybackSnapshot? {
        val value = player.state.value
        if (!value.canSavePosition) return null
        if (value.durationMs <= 0 && value.positionMs <= 0) return null
        return PlaybackSnapshot(binding.context.account.key, binding.file.wirePath.ifEmpty { binding.file.path }, binding.identity,
            binding.file.path, binding.file.wirePath, binding.file.name, value.positionMs, value.durationMs, System.currentTimeMillis(), ProgressSync.PENDING)
    }
    private fun saveProgress() {
        if (!player.state.value.canSavePosition) return
        localPlayback?.let { item ->
            val value = player.state.value
            val fingerprint = value.positionMs to value.durationMs
            if (fingerprint != lastSaved && (value.durationMs > 0 || value.positionMs > 0)) {
                lastSaved = fingerprint
                val previous = localProgressPending
                localProgressPending = cleanup.launch { previous?.join(); ClientDatabase.get(getApplication()).downloads().playback(item.id, fingerprint.first, fingerprint.second) }
            }
            return
        }
        val binding = playback ?: return
        val value = snapshot(binding) ?: return
        val fingerprint = value.positionMs to value.durationMs
        if (lastSaved == fingerprint) return
        lastSaved = fingerprint
        mutable.value = mutable.value.copy(progressStatus = "正在保存续播")
        binding.writer.submit(value)
    }
    private fun endPlayback() {
        closeTemporaryContent()
        saveProgress()
        val oldLocal = localPlayback; localPlayback = null
        val localStored = localProgressPending
        transferTimer?.cancel(); transferTimer = null
        mutable.value = mutable.value.copy(downloadBytesPerSecond = null)
        resumeOperation?.cancel()
        saveTimer?.cancel()
        val old = playback; val value = old?.let(::snapshot)
        playback = null; lastSaved = null
        player.pause()
        val stored = if (old != null && value != null) old.writer.submit(value) else null
        val subtitles = subtitleLeases.toList(); subtitleLeases.clear(); subtitleSources.clear()
        val oldLease = lease; lease = ""
        val previous = mediaClosing
        if (old != null || oldLocal != null || oldLease.isNotEmpty()) mediaClosing = cleanup.launch {
            previous?.join()
            try { stored?.await(); if (oldLocal != null) localStored?.join() }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                withContext(Dispatchers.Main) { mutable.value = mutable.value.copy(notice = "上次的续播未能保存，请检查本机存储。") }
            } finally {
                withContext(Dispatchers.Main) { player.stop() }
                try { if (oldLease.isNotEmpty()) NativeTransport.call(JSONObject().put("op", "revoke").put("url", oldLease)) }
                finally { subtitles.forEach { runCatching { it.release() } }; old?.writer?.close() }
            }
        } else player.stop()
    }
    fun leavePlayer() {
        val returnToFiles = pendingMediaOpen != null && !pendingOpenFromPlayer && mutable.value.mediaQueue?.source == MediaQueueSource.SEARCH
        operation?.cancel(); mediaRequest++; pendingMediaOpen = null; pendingOpenFromPlayer = false
        endPlayback()
        if (returnToFiles) search.close()
        mutable.value = mutable.value.copy(selected = null, mediaQueue = null, busy = false, stage = "", error = null)
    }
    private fun revokeLease() { val old = lease; lease = ""; if (old.isNotEmpty()) cleanup.launch { NativeTransport.call(JSONObject().put("op", "revoke").put("url", old)) } }
    private fun observeTransfer(bound: SessionContext, url: String) {
        transferTimer?.cancel()
        mutable.value = mutable.value.copy(downloadBytesPerSecond = null)
        transferTimer = viewModelScope.launch {
            var previousBytes = 0L
            var previousMillis = -1L
            val samples = ArrayDeque<Long>()
            while (context == bound && (lease == url || temporaryMedia?.asset?.url == url)) {
                try {
                    val result = NativeTransport.call(JSONObject().put("op", "lease_stats").put("session", bound.api.id).put("url", url)) as JSONObject
                    bound.api.token()
                    if (context != bound || lease != url && temporaryMedia?.asset?.url != url) break
                    val bytes = result.getLong("upstreamBytes")
                    val millis = result.getLong("elapsedMillis")
                    if (previousMillis >= 0 && millis > previousMillis) {
                        samples.addLast(((bytes - previousBytes).coerceAtLeast(0) * 1000 / (millis - previousMillis)))
                        while (samples.size > 3) samples.removeFirst()
                        mutable.value = mutable.value.copy(downloadBytesPerSecond = samples.average().toLong())
                    }
                    previousBytes = bytes; previousMillis = millis
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    if (context == bound && lease == url) mutable.value = mutable.value.copy(downloadBytesPerSecond = null)
                    previousMillis = -1; samples.clear()
                }
                delay(500)
            }
        }
    }
    private fun closeSession() {
        sessionSyncJob?.cancel(); sessionSyncJob = null; sessionSyncBound = null
        sessionRecoveryJob?.cancel(); sessionRecoveryJob = null; sessionRecoveryBound = null
        sessionFlushJob?.cancel(); sessionFlushJob = null
        automaticRecoveryOwner = ""; automaticRecoveryCode = null; automaticRecoveryRevision = Long.MIN_VALUE
        returnNavigation.clear()
        transferRefresh?.cancel(); transferRefresh = null; transferRefreshPending = false
        pendingDirectory = null
        mediaRequest++; pendingMediaOpen = null; pendingOpenFromPlayer = false
        previewImageLoader.memoryCache?.clear()
        search.close()
        favorites.bind(null)
        tags.bind(null)
        tasks.bind(null)
        operationHistory.bind(null)
        recentAccess.bind(null)
        fileChecksum.bind(null)
        documents.close(); documents.bind(null)
        archives.bind(null)
        archiveEntry.bind(null)
        serverSettings.bind(null)
        shell.bind(null)
        documentEdits.bind(null)
        accountSettings.bind(null)
        adminUsers.bind(null)
        trash.bind(null)
        fileOperations.bind(null)
        downloads.cancelFolderDownloads(quiet = true)
        uploads.cancelSelection(quiet = true)
        storageTools.bind(null)
        endPlayback(); val old = context; context = null
        recentJob?.cancel(); recentCloudJob?.cancel(); recentCloudOwner = ""; recentCloudUnavailable = false
        recentLocalEntries = emptyList(); recentCloudEntries = null; recentMutable.value = emptyList()
        mutable.value = mutable.value.copy(recentPlaybackError = null, recentPlaybackLoading = false, health = null, recoveryInProgress = false, recoveryNeedsPassword = false, recoveryError = null)
        val previous = closing; val media = mediaClosing
        if (old != null) closing = cleanup.launch { previous?.join(); media?.join(); old.api.close() }
    }
    fun disconnect() { startupJob?.cancel(); operation?.cancel(); generation++; closeSession(); navigation.clear();
        mutable.value = ClientState(editorVersion = mutable.value.editorVersion, compactGridSize = mutable.value.compactGridSize) }
    fun signOut() {
        val bound = context ?: return
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true)
        viewModelScope.launch {
            try {
                store.signOut(bound.account, bound.owner)
                if (context === bound) disconnect()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (context === bound) mutable.value = mutable.value.copy(busy = false, error = "退出登录未完成，请重试")
            }
        }
    }
    fun connectNetwork() {
        networkActivated = true
        networkJob?.cancel()
        networkPollJob?.cancel()
        networkJob = viewModelScope.launch {
            try {
                networkMutable.value = networkMutable.value.copy(state = "Starting", authUrl = "", error = null)
                networkMutable.value = embeddedNetwork.start()
                observeNetwork()
            } catch (error: Exception) {
                if (error !is CancellationException) networkMutable.value = networkMutable.value.copy(state = "Error", authUrl = "", error = networkError(error))
            }
        }
    }
    private fun networkError(error: Exception): String = when (error.message) {
        "android network snapshot unavailable", "invalid android network snapshot", "invalid android network interface", "invalid android interface address" -> "无法读取系统网络信息，请重试。"
        "cannot start embedded network" -> "内嵌节点启动失败，请重试（TS_START）。"
        "cannot control embedded network" -> "无法控制内嵌节点，请重试（TS_CONTROL）。"
        "cannot enable approved subnet routes" -> "无法设置子网路由，请重试（TS_ROUTES）。"
        "cannot open embedded login flow" -> "无法发起官方登录，请重试（TS_LOGIN）。"
        "cannot prepare embedded log storage", "cannot configure embedded log storage" -> "无法准备内嵌节点存储，请检查可用空间后重试（TS_STORAGE）。"
        "cannot unlock node state; original identity preserved" -> "无法解锁保存的节点，原有身份已保留。"
        else -> "内嵌网络暂不可用，请检查网络后重试（TS_NETWORK）。"
    }
    private fun observeNetwork() {
        networkPollJob?.cancel()
        if (!foreground) return
        networkPollJob = viewModelScope.launch {
            try {
                networkMutable.value = embeddedNetwork.status()
                while (foreground) { delay(if (networkMutable.value.connected) 5000 else 1500); networkMutable.value = embeddedNetwork.status() }
            }
            catch (error: Exception) { if (error !is CancellationException) networkMutable.value = networkMutable.value.copy(state = "Error", error = "无法确认内嵌网络状态，请重试") }
        }
    }

    private fun sessionCurrent(bound: SessionContext): Boolean = context === bound && generation == bound.generation

    private fun publishSessionHealth(bound: SessionContext) {
        if (!sessionCurrent(bound)) return
        val health = bound.api.sessionHealth.value
        val revision = bound.api.businessRevision
        val revisionAdvanced = automaticRecoveryOwner == bound.owner && automaticRecoveryCode == SessionHealthCode.EXPIRED &&
            revision > automaticRecoveryRevision
        val needsPassword = when {
            health?.code == SessionHealthCode.ACCOUNT_CHANGED -> true
            health?.code != SessionHealthCode.EXPIRED -> false
            revisionAdvanced -> false
            else -> mutable.value.recoveryNeedsPassword
        }
        val current = mutable.value
        if (current.health != health || current.recoveryNeedsPassword != needsPassword)
            mutable.value = current.copy(health = health, recoveryNeedsPassword = needsPassword)
        if (foreground && health?.code == SessionHealthCode.EXPIRED) maybeRecoverExpired(bound)
    }

    private fun startSessionSync(bound: SessionContext) {
        if (!foreground || !sessionCurrent(bound)) return
        if (sessionSyncJob?.isActive == true && sessionSyncBound === bound) return
        sessionSyncJob?.cancel()
        val flush = sessionFlushJob
        sessionSyncBound = bound
        sessionSyncJob = viewModelScope.launch {
            flush?.join()
            if (!foreground || !sessionCurrent(bound)) return@launch
            while (isActive && foreground && sessionCurrent(bound)) {
                try {
                    bound.api.synchronizeSession()
                    if (!sessionCurrent(bound)) return@launch
                    publishSessionHealth(bound)
                } catch (error: CancellationException) { throw error }
                catch (_: Exception) {
                    if (sessionCurrent(bound)) publishSessionHealth(bound)
                }
                if (!isActive || !foreground || !sessionCurrent(bound)) break
                delay(1000)
            }
        }
    }

    private fun maybeRecoverExpired(bound: SessionContext) {
        if (!foreground || !sessionCurrent(bound) || mutable.value.recoveryNeedsPassword) return
        if (sessionRecoveryJob?.isActive == true && sessionRecoveryBound === bound) return
        val revision = bound.api.businessRevision
        if (automaticRecoveryOwner == bound.owner && automaticRecoveryCode == SessionHealthCode.EXPIRED && automaticRecoveryRevision == revision) return
        automaticRecoveryOwner = bound.owner
        automaticRecoveryCode = SessionHealthCode.EXPIRED
        automaticRecoveryRevision = revision
        startSessionRecovery(bound, automatic = true)
    }

    private fun startSessionRecovery(bound: SessionContext, automatic: Boolean, manualPassword: String? = null) {
        if (!sessionCurrent(bound)) return
        if (sessionRecoveryJob?.isActive == true && sessionRecoveryBound === bound) return
        val code = bound.api.sessionHealth.value?.code ?: mutable.value.health?.code ?: return
        sessionRecoveryBound = bound
        val recovery = viewModelScope.launch {
            var recovered = false
            var needsPassword = false
            try {
                if (!sessionCurrent(bound)) return@launch
                mutable.value = mutable.value.copy(recoveryInProgress = true, recoveryError = null)
                when (code) {
                    SessionHealthCode.TOKEN_STORAGE_FAILED -> bound.api.synchronizeSession()
                    SessionHealthCode.RENEWAL_FAILED -> bound.api.refreshAuthentication()
                    SessionHealthCode.EXPIRED -> {
                        if (manualPassword == null) {
                            val restored = recoverBoundSession(store, bound.profile, bound.account, bound.api)
                            needsPassword = !restored
                            recovered = restored
                        } else {
                            bound.api.recoverAuthentication(bound.account.username, manualPassword)
                            recovered = true
                        }
                    }
                    SessionHealthCode.ACCOUNT_CHANGED -> {
                        if (manualPassword == null) needsPassword = true
                        else {
                            bound.api.recoverAuthentication(bound.account.username, manualPassword)
                            recovered = true
                        }
                    }
                }
                if (!sessionCurrent(bound)) return@launch
                if (code !in setOf(SessionHealthCode.ACCOUNT_CHANGED, SessionHealthCode.TOKEN_STORAGE_FAILED)) bound.api.synchronizeSession()
                val health = bound.api.sessionHealth.value
                recovered = recovered || code != SessionHealthCode.EXPIRED && health == null
                needsPassword = needsPassword || health?.code == SessionHealthCode.ACCOUNT_CHANGED
                mutable.value = mutable.value.copy(health = health, recoveryInProgress = false, recoveryNeedsPassword = needsPassword, recoveryError = null)
                if (automatic && recovered && health == null) emitFeedback("登录已恢复，请重试刚才的操作")
            } catch (error: CancellationException) { throw error }
            catch (failure: Exception) {
                if (sessionCurrent(bound)) {
                    val health = bound.api.sessionHealth.value
                    val rejectedPassword = code in setOf(SessionHealthCode.EXPIRED, SessionHealthCode.ACCOUNT_CHANGED) &&
                        failure is ServiceException && failure.status in setOf(401, 403)
                    val recoveryError = when {
                        rejectedPassword -> "密码不正确或账号身份已变化，请重新输入"
                        manualPassword != null -> "登录恢复失败，请检查密码后重试"
                        else -> null
                    }
                    mutable.value = mutable.value.copy(health = health ?: mutable.value.health, recoveryInProgress = false,
                        recoveryNeedsPassword = needsPassword || manualPassword != null || health?.code == SessionHealthCode.ACCOUNT_CHANGED || rejectedPassword,
                        recoveryError = recoveryError)
                }
            } finally {
                if (sessionRecoveryBound === bound) {
                    sessionRecoveryBound = null
                    sessionRecoveryJob = null
                }
                if (sessionCurrent(bound)) mutable.value = mutable.value.copy(recoveryInProgress = false)
            }
        }
        sessionRecoveryJob = recovery
    }

    private fun emitFeedback(message: String) {
        feedbackMutable.tryEmit(UiFeedbackEvent(++feedbackSequence, message))
    }

    fun retrySessionRecovery() {
        val bound = context ?: return
        if (!sessionCurrent(bound) || mutable.value.recoveryNeedsPassword) return
        startSessionRecovery(bound, automatic = false)
    }

    fun recoverSessionWithPassword(password: String) {
        val bound = context ?: return
        if (!sessionCurrent(bound) || password.isEmpty() || mutable.value.recoveryInProgress) return
        val code = bound.api.sessionHealth.value?.code ?: mutable.value.health?.code
        if (code !in setOf(SessionHealthCode.EXPIRED, SessionHealthCode.ACCOUNT_CHANGED)) return
        startSessionRecovery(bound, automatic = false, manualPassword = password)
    }

    fun foreground(active: Boolean) {
        val wasForeground = foreground
        foreground = active
        syncLibraryObservers()
        if (!active) {
            sessionSyncJob?.cancel(); sessionSyncJob = null; sessionSyncBound = null
            if (!wasForeground) { networkPollJob?.cancel(); search.cancel(); return }
            context?.let { bound ->
                if (sessionFlushJob?.isActive != true) sessionFlushJob = cleanup.launch {
                    try {
                        bound.api.synchronizeSession()
                        withContext(Dispatchers.Main.immediate) { if (sessionCurrent(bound)) publishSessionHealth(bound) }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* The next foreground sync will retry local state. */ }
                }
            }
            networkPollJob?.cancel(); search.cancel(); return
        }
        context?.let(::startSessionSync)
        if (networkActivated && networkJob?.isActive != true) observeNetwork()
    }
    fun stopNetwork(logout: Boolean = false) {
        networkJob?.cancel(); networkPollJob?.cancel()
        val old = context?.takeIf { it.profile.network == ConnectionMode.TAILNET }
        if (old != null) disconnect()
        networkJob = viewModelScope.launch { try { closing?.join(); mediaClosing?.join(); old?.let { store.deactivate(it.account, it.owner) }; if (logout) embeddedNetwork.logout() else embeddedNetwork.stop(); networkMutable.value = embeddedNetwork.status(); observeNetwork() } catch (error: Exception) { if (error !is CancellationException) networkMutable.value = networkMutable.value.copy(state = "Error", error = "无法断开内嵌网络，请重试") } }
    }
    override fun onCleared() { operation?.cancel(); networkJob?.cancel(); networkPollJob?.cancel(); sessionSyncJob?.cancel(); sessionRecoveryJob?.cancel(); sessionFlushJob?.cancel(); closeSession(); cache.close(); player.checkpoint = null; player.release(); super.onCleared() }
}
