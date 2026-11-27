package io.github.kkwans.nasfilebrowser.ui.medialibrary

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.app.ResourceRef
import io.github.kkwans.nasfilebrowser.app.SessionContext
import io.github.kkwans.nasfilebrowser.data.*
import io.github.kkwans.nasfilebrowser.data.MediaSourceRoot as LibrarySourceRoot
import io.github.kkwans.nasfilebrowser.data.archiveAbsoluteWire
import io.github.kkwans.nasfilebrowser.ui.DirectoryBreadcrumbItem
import io.github.kkwans.nasfilebrowser.ui.DirectoryBreadcrumbs
import io.github.kkwans.nasfilebrowser.ui.FilewayButton
import io.github.kkwans.nasfilebrowser.ui.FilewayDirectoryRow
import io.github.kkwans.nasfilebrowser.ui.FilewayIcon
import io.github.kkwans.nasfilebrowser.ui.FilewayOutlinedButton
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayEmptyState
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayTopAppBar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/** Creates a private library and binds its selected local sources one at a time. */
@Composable internal fun PhoneMediaLibraryManager(
    session: SessionContext,
    repository: MediaLibraryRepository,
    onBack: () -> Unit,
    onChanged: () -> Unit,
    onProvider: () -> Unit,
    onReviewItem: (MediaCatalogRef) -> Unit,
    onBrowse: (String) -> Unit,
    createInitially: Boolean = false,
) {
    val health by session.api.sessionHealth.collectAsState()
    if (session.api.identity.id != repository.scope.account.userId || health?.code == SessionHealthCode.ACCOUNT_CHANGED) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            FilewayTopAppBar("媒体库管理", leadingIcon = R.drawable.ic_arrow_back, leadingDescription = "返回", onLeadingClick = onBack)
            Text("账号已变化，请重新进入媒体库。", Modifier.padding(16.dp))
        }
        return
    }
    key(session.owner, session.generation, session.api.identity.deviceSessionId) {
        val scope = rememberCoroutineScope()
        var screen by rememberSaveable { mutableStateOf(if (createInitially) "create" else "libraries") }
        var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
        var historyScanId by rememberSaveable { mutableStateOf<String?>(null) }
        var name by rememberSaveable { mutableStateOf("") }
        var contentKind by rememberSaveable { mutableStateOf(MediaLibraryKind.MIXED) }
        var kindDraft by rememberSaveable { mutableStateOf(MediaLibraryKind.MIXED) }
        val focusManager = LocalFocusManager.current
        var draftFolders by rememberSaveable(stateSaver = mediaFolderSaver) { mutableStateOf(emptyList<ResourceRef>()) }
        var draftLibraryId by rememberSaveable { mutableStateOf<String?>(null) }
        var draftBinding by rememberSaveable { mutableStateOf<String?>(null) }
        var setupLibraryName by rememberSaveable { mutableStateOf("") }
        var setupTotal by rememberSaveable { mutableIntStateOf(0) }
        var setupCompleted by rememberSaveable(stateSaver = mediaFolderSaver) { mutableStateOf(emptyList<ResourceRef>()) }
        var setupBindingIds by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
        var setupScanRequested by rememberSaveable { mutableStateOf(false) }
        var setupPaused by rememberSaveable { mutableStateOf(false) }
        var directoryReturn by rememberSaveable { mutableStateOf("library") }
        // The journal owns the original request; only its opaque operation ID is saved here.
        var pendingId by rememberSaveable { mutableStateOf<String?>(null) }
        var pendingKind by rememberSaveable { mutableStateOf<String?>(null) }
        var libraries by remember { mutableStateOf<List<MediaLibrarySummary>>(emptyList()) }
        var libraryCursor by remember { mutableStateOf<String?>(null) }
        var selected by remember { mutableStateOf<MediaLibrarySummary?>(null) }
        var sources by remember { mutableStateOf<List<MediaLibrarySource>>(emptyList()) }
        var sourceCursor by remember { mutableStateOf<String?>(null) }
        var support by remember { mutableStateOf<MediaScanSupport?>(null) }
        var scan by remember { mutableStateOf<MediaScan?>(null) }
        var scanMode by rememberSaveable { mutableStateOf(MediaScanMode.INCREMENTAL) }
        var scanLibrary by rememberSaveable { mutableStateOf<String?>(null) }
        var scanId by rememberSaveable { mutableStateOf<String?>(null) }
        var batchIds by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
        var batchScans by remember { mutableStateOf<List<MediaScan>>(emptyList()) }
        var scanMetadata by remember { mutableStateOf<Map<String, MediaMetadataJob>>(emptyMap()) }
        var policy by remember { mutableStateOf<MediaProviderPolicy?>(null) }
        var historyReviewOpen by rememberSaveable { mutableStateOf(false) }
        var attestingSourceId by rememberSaveable { mutableStateOf<String?>(null) }
        var loading by remember { mutableStateOf(false) }
        var auxiliaryLoading by remember { mutableStateOf(false) }
        var paging by remember { mutableStateOf(false) }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var notice by remember { mutableStateOf<String?>(null) }
        var syncWarning by remember { mutableStateOf<String?>(null) }
        var browseStack by rememberSaveable(stateSaver = mediaFolderSaver) {
            mutableStateOf(listOf(ResourceRef("/", "/", "服务器根目录", true, "", 0)))
        }
        var browseEntries by remember { mutableStateOf<List<ResourceRef>>(emptyList()) }
        var browseError by remember { mutableStateOf<String?>(null) }
        var verifiedBrowse by remember { mutableStateOf<Pair<String, Int>?>(null) }
        var refresh by remember { mutableIntStateOf(0) }
        var scanRefresh by remember { mutableIntStateOf(0) }
        var browseRefresh by remember { mutableIntStateOf(0) }

        fun refreshPage() {
            if (screen == "libraries" || screen == "library") loading = true
            refresh++; scanRefresh++
        }
        LaunchedEffect(repository, busy, pendingId) {
            if (!busy && pendingId == null) pendingId = repository.pendingSetupOperation()?.id
        }
        LaunchedEffect(notice) { if (notice != null) { delay(5_000); notice = null } }
        fun leave() {
            if (busy) return
            when (screen) {
                "directory" -> if (browseStack.size > 1) browseStack = browseStack.dropLast(1)
                    else { screen = directoryReturn; browseEntries = emptyList(); if (directoryReturn == "attest") attestingSourceId = null }
                "create" -> {
                    if (pendingId != null) {
                        error = "创建结果尚待确认。请先确认上次操作，已选文件夹会保留。"
                        return
                    }
                    name = ""; contentKind = MediaLibraryKind.MIXED; draftFolders = emptyList(); if (createInitially) onBack() else screen = "libraries"
                }
                "policy" -> screen = "library"
                "history" -> { historyScanId = null; historyReviewOpen = false; screen = "library" }
                "library" -> {
                    if (selectedId == draftLibraryId && draftLibraryId != null) setupPaused = true
                    screen = "libraries"; selectedId = null; selected = null
                }
                else -> onBack()
            }
        }
        BackHandler(enabled = screen != "libraries" || busy) { leave() }
        if (screen == "policy" && selectedId != null) {
            PhoneMediaPolicySettings(repository, selectedId!!, { screen = "library" }, onProvider) {
                screen = "library"; notice = "资料优先级已保存。"; onChanged()
            }
        } else if (screen == "history" && selectedId != null) {
            PhoneMediaScanHistory(repository, selectedId!!, historyScanId, { historyScanId = it }, ::leave, onReviewItem, onChanged,
                reviewInitiallyOpen = historyReviewOpen, onBrowse = { onBrowse(selectedId!!) })
        } else {

        LaunchedEffect(selected?.id, selected?.contentKind) {
            selected?.let { kindDraft = it.contentKind }
        }
        LaunchedEffect(repository, screen, selectedId, refresh) {
            if (!mediaLibraryShowsCoreLoading(screen)) return@LaunchedEffect
            loading = true; error = null
            if (screen == "library") {
                auxiliaryLoading = false
                policy = null; support = null; scan = null; scanId = null; scanLibrary = null
                batchIds = arrayListOf(); batchScans = emptyList(); scanMetadata = emptyMap()
            }
            try {
                if (screen == "libraries") {
                    when (val result = repository.listLibraries(limit = 30)) {
                        is MediaReadResult.Success -> if (result.scope == repository.scope) {
                            libraries = result.value.items; libraryCursor = result.value.nextCursor
                        }
                        is MediaReadResult.Failure -> if (result.scope == repository.scope) {
                            libraries = emptyList(); libraryCursor = null; error = managerFailure(result.error)
                        }
                    }
                } else {
                    val id = selectedId ?: return@LaunchedEffect
                    when (val result = repository.getLibrary(id)) {
                        is MediaReadResult.Success -> if (result.scope == repository.scope) selected = result.value
                        is MediaReadResult.Failure -> if (result.scope == repository.scope) {
                            selected = null; sources = emptyList(); sourceCursor = null; error = managerFailure(result.error)
                        }
                    }
                    if (selected?.id == id && selected?.role == MediaLibraryRole.OWNER) {
                        val preparingScan = draftLibraryId == id && setupScanRequested && draftFolders.isEmpty()
                        if (preparingScan) {
                            when (val result = readAllSetupSources(repository, id)) {
                                is MediaReadResult.Success -> if (result.scope == repository.scope) {
                                    sources = result.value; sourceCursor = null
                                }
                                is MediaReadResult.Failure -> if (result.scope == repository.scope) {
                                    sources = emptyList(); sourceCursor = null; setupPaused = true
                                    error = "媒体库已创建，但无法完整读取已保存的文件夹：${managerFailure(result.error)}"
                                }
                            }
                        } else when (val result = repository.listSources(id, limit = 30)) {
                            is MediaReadResult.Success -> if (result.scope == repository.scope) {
                                sources = result.value.items; sourceCursor = result.value.nextCursor
                            }
                            is MediaReadResult.Failure -> if (result.scope == repository.scope) {
                                sources = emptyList(); sourceCursor = null; error = managerFailure(result.error)
                                if (draftLibraryId == id) setupPaused = true
                            }
                        }
                    } else { sources = emptyList(); sourceCursor = null; support = null; policy = null }
                }
                if (screen == "libraries") {
                    scan = null; scanId = null; scanLibrary = null; batchIds = arrayListOf(); batchScans = emptyList(); scanMetadata = emptyMap()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "暂时无法读取媒体库，请检查连接后重试。" }
            finally { loading = false }
        }

        LaunchedEffect(repository, screen, selectedId, selected?.id, selected?.role, draftLibraryId, setupScanRequested, refresh) {
            val library = selected ?: return@LaunchedEffect
            if (library.id != selectedId || !mediaLibraryNeedsAuxiliaryLoad(screen, library.role)) return@LaunchedEffect
            val id = library.id
            auxiliaryLoading = true
            try {
                coroutineScope {
                    val policyResult = async { repository.metadataPolicy(id) }
                    val supportResult = async { repository.scanSupport() }
                    val scansResult = async { repository.listScans(id, limit = 10) }
                    launch {
                        val result = policyResult.await()
                        if (!mediaLibraryAuxiliaryResultIsCurrent(selectedId, selected?.id, screen, id)) return@launch
                        when (result) {
                            is MediaReadResult.Success -> if (result.scope == repository.scope) policy = result.value
                            is MediaReadResult.Failure -> if (result.scope == repository.scope) policy = null
                        }
                    }
                    launch {
                        val result = supportResult.await()
                        if (!mediaLibraryAuxiliaryResultIsCurrent(selectedId, selected?.id, screen, id)) return@launch
                        when (result) {
                            is MediaReadResult.Success -> if (result.scope == repository.scope) support = result.value
                            is MediaReadResult.Failure -> if (result.scope == repository.scope) {
                                support = null
                                val preparingScan = draftLibraryId == id && setupScanRequested && draftFolders.isEmpty()
                                if (preparingScan && error == null) {
                                    setupPaused = true
                                    error = "媒体库和文件夹已保存，但无法读取扫描服务状态：${managerFailure(result.error)}"
                                }
                            }
                        }
                    }
                    launch {
                        val result = scansResult.await()
                        if (!mediaLibraryAuxiliaryResultIsCurrent(selectedId, selected?.id, screen, id)) return@launch
                        when (result) {
                            is MediaReadResult.Success -> if (result.scope == repository.scope) {
                                val recent = result.value.items.sortedByDescending { it.updatedAt }
                                val latest = recent.firstOrNull()
                                if (latest != null && (scan?.libraryId != id || scan?.status !in setOf(MediaScanStatus.QUEUED, MediaScanStatus.RUNNING))) {
                                    scan = latest; scanLibrary = id; scanId = latest.id
                                }
                                batchScans = recent.filter { it.bindingId != null && it.id != scan?.id }.take(3)
                                batchIds = ArrayList(batchScans.map { it.id })
                            }
                            is MediaReadResult.Failure -> Unit
                        }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (selectedId == id && error == null) error = "暂时无法读取扫描服务与记录，请稍后重试。" }
            finally { if (selectedId == id) auxiliaryLoading = false }
        }

        val currentDirectory = browseStack.last()
        val browseRequest = currentDirectory.wirePath to browseRefresh
        val directoryVerified = verifiedBrowse == browseRequest
        fun openDirectory(returnTo: String) {
            focusManager.clearFocus()
            directoryReturn = returnTo
            browseStack = listOf(ResourceRef("/", "/", "服务器根目录", true, "", 0))
            browseRefresh++
            screen = "directory"
            error = null
        }
        LaunchedEffect(repository, screen, currentDirectory.wirePath, browseRefresh) {
            if (screen != "directory") return@LaunchedEffect
            browseError = null; browseEntries = emptyList(); verifiedBrowse = null
            try {
                val requested = archiveAbsoluteWire(currentDirectory.path, currentDirectory.wirePath)
                val row = session.api.request("GET", "/api/resources$requested")
                check(row.getBoolean("isDir"))
                val actual = archiveAbsoluteWire(row.getString("path"), row.optString("wirePath"))
                check(resourceWireBytes(actual).contentEquals(resourceWireBytes(requested)))
                val rows = row.getJSONArray("items")
                val folders = (0 until rows.length()).map { rows.getJSONObject(it) }.filter { it.getBoolean("isDir") }.map {
                    val path = it.getString("path")
                    ResourceRef(path, archiveAbsoluteWire(path, it.optString("wirePath")), it.getString("name"), true, "", 0)
                        .also { directory -> LibrarySourceRoot.fromDirectory(directory) }
                }
                coroutineContext.ensureActive()
                check(session.api.identity.id == repository.scope.account.userId &&
                    session.api.sessionHealth.value?.code != SessionHealthCode.ACCOUNT_CHANGED)
                browseEntries = folders.distinctBy { recentAccessWireIdentity(it.wirePath) }.sortedBy { it.name.lowercase() }
                verifiedBrowse = browseRequest
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { browseError = "无法读取此目录，可能已离线或没有访问权限。" }
        }

        LaunchedEffect(repository, scanLibrary, scanId, scanRefresh) {
            val libraryId = scanLibrary ?: return@LaunchedEffect
            val id = scanId ?: return@LaunchedEffect
            while (true) {
                when (val result = repository.getScan(libraryId, id)) {
                    is MediaReadResult.Success -> if (result.scope == repository.scope) {
                        scan = result.value
                        if (result.value.status !in setOf(MediaScanStatus.QUEUED, MediaScanStatus.RUNNING)) {
                            if (result.value.status == MediaScanStatus.COMPLETED) onChanged()
                            return@LaunchedEffect
                        }
                    }
                    is MediaReadResult.Failure -> if (result.scope == repository.scope) {
                        scan = null
                        if (screen == "library" && selectedId == libraryId) error = "无法刷新扫描状态，请稍后重试。"
                        return@LaunchedEffect
                    }
                }
                delay(2_000)
            }
        }

        LaunchedEffect(repository, scanLibrary, scanId, scan?.status, scanRefresh) {
            val libraryId = scanLibrary ?: return@LaunchedEffect
            val id = scanId ?: return@LaunchedEffect
            while (true) {
                when (val result = repository.metadataJob(libraryId, id)) {
                    is MediaReadResult.Success -> if (result.scope == repository.scope) {
                        scanMetadata = scanMetadata + (id to result.value)
                        if (result.value.status !in setOf(MediaScanStatus.QUEUED, MediaScanStatus.RUNNING)) return@LaunchedEffect
                    }
                    is MediaReadResult.Failure -> if (result.scope == repository.scope) {
                        if (result.error.kind != MediaFailureKind.NOT_FOUND ||
                            scan?.status !in setOf(MediaScanStatus.QUEUED, MediaScanStatus.RUNNING)) return@LaunchedEffect
                    }
                }
                delay(2_500)
            }
        }

        LaunchedEffect(repository, scanLibrary, batchIds, scanRefresh) {
            val id = scanLibrary ?: return@LaunchedEffect
            if (batchIds.isEmpty()) return@LaunchedEffect
            while (true) {
                val current = batchIds.mapNotNull { job ->
                    (repository.getScan(id, job) as? MediaReadResult.Success)?.value
                }
                batchScans = current
                current.filter { it.status == MediaScanStatus.COMPLETED }.forEach { value ->
                    when (val result = repository.metadataJob(id, value.id)) {
                        is MediaReadResult.Success -> if (result.scope == repository.scope) {
                            scanMetadata = scanMetadata + (value.id to result.value)
                        }
                        is MediaReadResult.Failure -> Unit
                    }
                }
                if (current.size != batchIds.size) {
                    if (screen == "library" && selectedId == id) error = "有扫描任务暂时无法读取，可刷新后重试。"
                    return@LaunchedEffect
                }
                if (current.none { it.status in setOf(MediaScanStatus.QUEUED, MediaScanStatus.RUNNING) }) {
                    if (current.any { it.status == MediaScanStatus.COMPLETED }) onChanged()
                    return@LaunchedEffect
                }
                delay(2_000)
            }
        }

        fun accept(result: MediaWriteResult<*>) {
            when (result) {
                is MediaWriteResult.Success -> if (result.scope == repository.scope) {
                    val submittedKind = pendingKind
                    pendingId = null; pendingKind = null
                    when (val value = result.value) {
                        is MediaLibrarySummary -> {
                            selected = value; selectedId = value.id; screen = "library"
                            if (submittedKind == "kind") {
                                notice = "媒体库类型已更新。"
                            } else {
                                name = ""; contentKind = MediaLibraryKind.MIXED
                                setupLibraryName = value.name
                                if (draftFolders.isNotEmpty()) { draftLibraryId = value.id; loading = true }
                                else { setupScanRequested = false; notice = "媒体库已创建，可继续设置资料优先级。" }
                            }
                        }
                        is MediaLibrarySource -> {
                            selected = selected?.copy(revision = value.libraryRevision)
                            if (attestingSourceId != null) {
                                attestingSourceId = null
                                notice = "来源已重新确认，可以开始扫描。"
                            } else if (draftBinding != null) {
                                draftFolders.firstOrNull { it.wirePath == draftBinding }?.let { folder ->
                                    setupCompleted = setupCompleted + folder
                                    if (value.id !in setupBindingIds) setupBindingIds = ArrayList(setupBindingIds + value.id)
                                }
                                draftFolders = draftFolders.filterNot { it.wirePath == draftBinding }
                                draftBinding = null
                                loading = true
                                if (draftFolders.isEmpty() && !setupScanRequested) {
                                    draftLibraryId = null
                                    notice = "媒体库和 ${setupCompleted.size} 个文件夹已保存，可继续设置资料优先级或开始扫描。"
                                }
                            } else notice = "文件夹已添加，可以开始扫描。"
                            screen = "library"
                        }
                        is MediaScan -> {
                            val old = batchScans.firstOrNull { it.bindingId != null && it.bindingId == value.bindingId }
                            if (old != null && old.libraryId == value.libraryId) {
                                batchIds = ArrayList(batchIds.map { if (it == old.id) value.id else it })
                                batchScans = batchScans.map { if (it.id == old.id) value else it }
                            } else {
                                scan = value; scanLibrary = value.libraryId; scanId = value.id
                            }
                            screen = "library"; notice = "扫描请求已接受。"
                        }
                        is MediaScanBatch -> {
                            scan = null; scanId = null; scanLibrary = value.libraryId
                            batchScans = value.items; batchIds = ArrayList(value.items.map { it.id })
                            if (draftLibraryId == value.libraryId && setupScanRequested) {
                                draftLibraryId = null; setupScanRequested = false; setupPaused = false
                            }
                            screen = "library"; notice = "已提交 ${value.items.size} 个目录的扫描。"
                        }
                    }
                    if (result.journalStorageFailed || result.sessionSyncIncomplete) {
                        syncWarning = "操作已接受，但本机状态同步未完成，请刷新查看。"
                        if (draftLibraryId != null) setupPaused = true
                    }
                    error = null
                    refreshPage(); onChanged()
                }
                is MediaWriteResult.Failure -> if (result.scope == repository.scope) {
                    if (!result.error.mutationMayHaveCommitted) { pendingId = null; pendingKind = null }
                    if (draftLibraryId != null) setupPaused = true
                    error = if (result.error.mutationMayHaveCommitted)
                        "操作结果尚未确认。可使用原操作记录确认，避免重复创建。"
                    else managerFailure(result.error)
                }
            }
        }

        fun submit(kind: String, action: suspend (MediaOperation) -> MediaWriteResult<*>) {
            if (busy || pendingId != null) return
            val operation = repository.newOperation()
            pendingId = operation.id; pendingKind = kind; busy = true; error = null
            scope.launch {
                try {
                    val result = action(operation)
                    accept(result)
                    if (result is MediaWriteResult.Success || result is MediaWriteResult.Failure && !result.error.mutationMayHaveCommitted) {
                        repository.forgetResolvedOperation(operation)
                    }
                }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    if (draftLibraryId != null) setupPaused = true
                    error = "操作结果尚未确认，请使用原操作记录确认。"
                }
                finally { busy = false }
            }
        }

        fun confirmPending() {
            val id = pendingId ?: return
            if (busy) return
            busy = true; error = null
            scope.launch {
                try {
                    val operation = MediaOperation(repository.scope.account, id)
                    val result = repository.retryOperation(operation)
                    if (result is MediaWriteResult.Success && draftLibraryId != null) setupPaused = false
                    accept(result)
                    if (result is MediaWriteResult.Success || result is MediaWriteResult.Failure && !result.error.mutationMayHaveCommitted) {
                        repository.forgetResolvedOperation(operation)
                    }
                }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    if (draftLibraryId != null) setupPaused = true
                    error = "无法确认上次操作，请检查连接后重试。"
                }
                finally { busy = false }
            }
        }

        LaunchedEffect(draftLibraryId, draftFolders, busy, pendingId, selected, screen, error, loading, setupPaused) {
            val library = selected?.takeIf { it.id == draftLibraryId } ?: return@LaunchedEffect
            val folder = draftFolders.firstOrNull() ?: return@LaunchedEffect
            if (screen == "library" && !busy && !loading && pendingId == null && error == null && !setupPaused) {
                draftBinding = folder.wirePath
                submit("source") { repository.createLocalSource(it, library.id, library.revision, LibrarySourceRoot.fromDirectory(folder)) }
            }
        }
        LaunchedEffect(draftLibraryId, draftFolders, busy, pendingId, selected, screen, error, loading, setupPaused,
            setupScanRequested, setupBindingIds, sources, sourceCursor, support) {
            val library = selected?.takeIf { it.id == draftLibraryId } ?: return@LaunchedEffect
            if (!setupScanRequested || draftFolders.isNotEmpty() || screen != "library" || busy || loading ||
                pendingId != null || error != null || setupPaused) return@LaunchedEffect
            val ids = setupBindingIds.toSet()
            val activeSources = sources.filter { it.id in ids }
            val problem = when {
                setupTotal !in 1..60 || ids.size != setupTotal || activeSources.size != setupTotal || sourceCursor != null ->
                    "媒体库已创建，但未能确认全部已选文件夹，请重新读取后再扫描。"
                activeSources.any { it.access != MediaSourceAccess.AVAILABLE } ->
                    "媒体库和文件夹已保存，但部分文件夹当前不可用；恢复访问后可重新尝试扫描。"
                support == null -> "媒体库和文件夹已保存，但无法确认扫描服务状态，请重新读取后再试。"
                support?.available != true -> "媒体库和文件夹已保存，但扫描服务尚不可用；启用后可重新尝试扫描。"
                else -> null
            }
            if (problem != null) { setupPaused = true; error = problem }
            else {
                val rules = support?.localRulesRevision ?: return@LaunchedEffect
                submit("setup-scan") { repository.startScanBatch(it, library.id, activeSources, rules, scanMode) }
            }
        }
        val trimmedName = name.trim()
        val validName = trimmedName.isNotEmpty() && trimmedName.codePointCount(0, trimmedName.length) <= 200 &&
            name.none { it.isISOControl() }
        val canCreate = validName && !busy && pendingId == null && draftLibraryId == null
        fun createLibrary(andScan: Boolean) {
            if (canCreate && (!andScan || draftFolders.size in 1..60)) {
                focusManager.clearFocus()
                setupTotal = draftFolders.size
                setupCompleted = emptyList()
                setupBindingIds = arrayListOf()
                setupScanRequested = andScan
                setupPaused = false
                submit("create") { repository.createLibrary(it, trimmedName, contentKind) }
            }
        }

        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding(),
            horizontalAlignment = Alignment.CenterHorizontally) {
            FilewayTopAppBar(when (screen) {
                "create" -> "新建媒体库"; "library" -> selected?.name ?: "媒体库"; "directory" -> "选择媒体文件夹"; else -> "管理媒体库"
            }, leadingIcon = R.drawable.ic_arrow_back, leadingDescription = "返回", onLeadingClick = {
                if (screen == "directory" && !busy) {
                    screen = directoryReturn
                    if (directoryReturn == "attest") attestingSourceId = null
                } else leave()
            }, actions = {
                if (screen == "libraries" && session.api.identity.deviceSessionId.isEmpty()) {
                    TextButton(onClick = { screen = "create"; error = null; notice = null }, enabled = !busy && pendingId == null && draftLibraryId == null) { Text("创建") }
                }
            })
            if (screen == "directory") {
                Surface(Modifier.widthIn(max = 640.dp).fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
                    DirectoryBreadcrumbs(browseStack.map { DirectoryBreadcrumbItem(it.wirePath, it.name) },
                        busy = busy, fullPath = currentDirectory.path, wrapCurrent = true) { wire ->
                        val index = browseStack.indexOfFirst { it.wirePath == wire }
                        if (index >= 0) browseStack = browseStack.take(index + 1)
                    }
                }
            }
            key(screen, if (screen == "directory") currentDirectory.wirePath else null) {
            LazyColumn(Modifier.weight(1f).widthIn(max = 640.dp).fillMaxWidth(), contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (notice != null) item(key = "notice") {
                    Row(Modifier.fillMaxWidth()) {
                        Text(notice!!, Modifier.weight(1f), color = MaterialTheme.colorScheme.primary)
                        TextButton(onClick = { notice = null }) { Text("关闭") }
                    }
                }
                if (syncWarning != null) item(key = "sync-warning") {
                    Row(Modifier.fillMaxWidth()) {
                        Text(syncWarning!!, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { syncWarning = null }) { Text("关闭") }
                    }
                }
                if (error != null) item(key = "error") {
                    MediaProblemCard(MediaProblem("暂时无法完成", error!!,
                        if (pendingId != null) "确认上次操作" else if (screen == "create") "关闭" else "刷新"),
                        { if (pendingId != null) confirmPending() else if (screen == "create") error = null
                          else if (screen == "directory") browseRefresh++ else { setupPaused = false; refreshPage() } })
                }
                if (pendingId != null && error == null && !busy) item(key = "pending") {
                    val label = when (pendingKind) { "create" -> "创建媒体库"; "source" -> "添加来源"; "attest" -> "确认来源"; "scan", "setup-scan" -> "启动扫描"; else -> "上次操作" }
                    MediaProblemCard(MediaProblem("${label}尚待确认", "请先确认结果，避免重复提交。", "确认上次操作"), ::confirmPending)
                }
                if (busy) item(key = "busy") { MediaLoading("正在处理，请稍候") }
                if (loading) item(key = "loading") { MediaLoading("正在读取媒体库") }
                if (auxiliaryLoading) item(key = "auxiliary-loading") { PhoneDetailLoading("正在读取扫描服务与记录") }
                when (screen) {
                    "libraries" -> {
                        if (draftLibraryId != null) item(key = "setup-pending") {
                            MediaSettingsGroup("未完成的媒体库") {
                                MediaSettingsAction("继续设置 ${setupLibraryName.ifBlank { "媒体库" }}",
                                    "已添加 ${setupCompleted.size}/${setupTotal} 个文件夹", R.drawable.ic_library, enabled = !busy) {
                                    selectedId = draftLibraryId; selected = null; screen = "library"
                                }
                            }
                        }
                        if (libraries.isEmpty() && !loading && error == null) item(key = "empty") {
                            FilewayEmptyState(R.drawable.ic_library, "还没有媒体库", "创建媒体库后再选择已有影视目录。")
                        }
                        items(libraries, key = { it.id }) { library ->
                            MediaLibraryRow(library, enabled = !busy,
                                onOpen = {
                                    when (mediaLibraryOpenDestination(library.role)) {
                                        MediaLibraryOpenDestination.MANAGE -> {
                                            selectedId = library.id; selected = null; screen = "library"; notice = null
                                        }
                                        MediaLibraryOpenDestination.BROWSE -> onBrowse(library.id)
                                    }
                                },
                                onHistory = {
                                    selectedId = library.id; selected = null; historyScanId = null
                                    historyReviewOpen = false; screen = "history"; notice = null
                                })
                        }
                        if (libraryCursor != null && !loading && !paging) item(key = "more-libraries") {
                            TextButton(onClick = {
                                val cursor = libraryCursor ?: return@TextButton
                                scope.launch {
                                    paging = true
                                    try { when (val result = repository.listLibraries(limit = 30, cursor = cursor)) {
                                        is MediaReadResult.Success -> if (result.scope == repository.scope && screen == "libraries" && libraryCursor == cursor) {
                                            libraries = (libraries + result.value.items).distinctBy { it.id }; libraryCursor = result.value.nextCursor
                                        }
                                        is MediaReadResult.Failure -> if (result.scope == repository.scope && screen == "libraries" && libraryCursor == cursor) error = managerFailure(result.error)
                                    } } catch (cancelled: CancellationException) { throw cancelled }
                                    catch (_: Exception) { if (screen == "libraries" && libraryCursor == cursor) error = "无法加载更多媒体库，请重试。" }
                                    finally { paging = false }
                                }
                            }, modifier = Modifier.fillMaxWidth()) { Text("加载更多") }
                        }
                    }
                    "create" -> {
                        item(key = "intro") {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("从已有目录开始", style = PhoneMediaType.pageTitle)
                                Text("新媒体库默认仅自己可见。先填写名称并选择文件夹，再创建或直接扫描。",
                                    style = PhoneMediaType.body, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        item(key = "name") {
                            MediaSettingsGroup("基本信息") {
                                OutlinedTextField(name, { name = it }, label = { Text("媒体库名称") },
                                    placeholder = { Text("例如：电影、电视剧") },
                                    enabled = !busy && pendingId == null, singleLine = true,
                                    modifier = Modifier.fillMaxWidth().padding(16.dp), shape = MaterialTheme.shapes.medium,
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                                    isError = name.isNotBlank() && !validName,
                                    supportingText = if (name.isNotBlank() && !validName)
                                        ({ Text("名称须为 1–200 个字符且不含控制字符") }) else null)
                            }
                        }
                        item(key = "content-kind") {
                            MediaSettingsGroup("媒体库类型") {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("只展示所选类型，混入的电影或剧集不会进入待确认。",
                                        style = PhoneMediaType.secondary, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        listOf(MediaLibraryKind.MOVIE to "电影", MediaLibraryKind.SERIES to "电视剧",
                                            MediaLibraryKind.MIXED to "混合").forEach { (value, label) ->
                                            MediaSettingsChip(label, contentKind == value, !busy && pendingId == null) { contentKind = value }
                                        }
                                    }
                                }
                            }
                        }
                        item(key = "draft-folders") {
                            MediaSettingsGroup("媒体文件夹") {
                                draftFolders.forEach { folder ->
                                    ListItem(headlineContent = { Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        supportingContent = { Text(folder.path, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                                        leadingContent = { FilewayIcon(R.drawable.ic_folder, null, Modifier.size(24.dp)) },
                                        trailingContent = {
                                            TextButton(onClick = { draftFolders = draftFolders.filterNot { it.wirePath == folder.wirePath } },
                                                enabled = !busy && pendingId == null) { Text("移除") }
                                        })
                                }
                                MediaSettingsAction("添加文件夹", if (draftFolders.isEmpty()) "可选，也可以创建后再添加" else "${draftFolders.size} 个文件夹已选",
                                    R.drawable.ic_folder, enabled = !busy && pendingId == null) { openDirectory("create") }
                            }
                        }
                        item(key = "default-policy") {
                            MediaSettingsGroup("信息获取") {
                                MediaSettingsAction("TMDB 数据源", "先保存并检测密钥，创建后可选择连接、语言与地区",
                                    R.drawable.ic_settings, enabled = !busy && pendingId == null, action = onProvider)
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("默认仅本地资料", style = PhoneMediaType.itemTitle)
                                    Text("创建后可在媒体库详情直接设置 TMDB 优先、本地优先或仅本地资料。默认语言为简体中文，地区为中国大陆。",
                                        style = PhoneMediaType.body, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    "library" -> {
                        selected?.let { library ->
                            if (library.role == MediaLibraryRole.OWNER) {
                                if (draftLibraryId == library.id) item(key = "setup-progress") {
                                    MediaSettingsGroup("创建进度") {
                                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text("已添加 ${setupCompleted.size}/${setupTotal} 个文件夹", style = PhoneMediaType.itemTitle)
                                            setupCompleted.forEach { Text("已添加 · ${it.path}", style = PhoneMediaType.secondary) }
                                            draftFolders.forEach { Text("待添加 · ${it.path}", style = PhoneMediaType.secondary,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                            if (setupScanRequested) Text("全部添加后将按${mediaScanModeLabel(scanMode)}开始扫描。", style = PhoneMediaType.secondary)
                                            if (setupPaused && pendingId == null) TextButton(onClick = {
                                                setupPaused = false; error = null; refreshPage()
                                            }, enabled = !busy && !loading, modifier = Modifier.heightIn(min = 48.dp)) {
                                                Text(if (draftFolders.isEmpty() && setupScanRequested) "重新尝试扫描" else "继续添加文件夹")
                                            }
                                        }
                                    }
                                }
                                scan?.takeIf { it.libraryId == library.id }?.let { value -> item(key = "scan-summary") {
                                    MediaScanSummaryCard(value, scanMetadata[value.id],
                                        onOpen = { historyScanId = value.id; historyReviewOpen = false; screen = "history" },
                                        onReview = { historyScanId = value.id; historyReviewOpen = true; screen = "history" },
                                        onCancel = { submit("cancel") { repository.cancelScan(it, library.id, value.id, value.generation) } },
                                        onRetry = { submit("retry") { repository.retryScan(it, library.id, value.id, value.generation) } },
                                        enabled = !busy && pendingId == null)
                                } }
                                item(key = "library-kind") {
                                    MediaSettingsGroup("媒体库类型") {
                                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                            Text("类型决定首页与搜索展示哪些作品，不移动或删除源文件。",
                                                style = PhoneMediaType.secondary, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                listOf(MediaLibraryKind.MOVIE to "电影", MediaLibraryKind.SERIES to "电视剧",
                                                    MediaLibraryKind.MIXED to "混合").forEach { (value, label) ->
                                                    MediaSettingsChip(label, kindDraft == value, !busy && pendingId == null) { kindDraft = value }
                                                }
                                            }
                                            if (kindDraft != MediaLibraryKind.UNKNOWN && kindDraft != library.contentKind) {
                                                FilewayButton(onClick = {
                                                    submit("kind") { repository.updateLibrary(it, library.id, library.revision, library.name, kindDraft) }
                                                }, enabled = !busy && pendingId == null, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                                    Text("保存类型", style = PhoneMediaType.action)
                                                }
                                            }
                                        }
                                    }
                                }
                                item(key = "add-source") {
                                    MediaSettingsGroup("媒体文件夹") {
                                        MediaSettingsAction("添加文件夹", "可关联多个当前服务器目录", R.drawable.ic_folder,
                                            enabled = !busy && pendingId == null && draftLibraryId == null) { openDirectory("library") }
                                    }
                                }
                                item(key = "scan-history") {
                                    MediaSettingsGroup("扫描与补全") {
                                        MediaSettingsAction("扫描记录", if (scan != null) "直接查看本次扫描与补全结果" else "查看目录扫描、TMDB 补全和待确认影片",
                                            R.drawable.ic_search, enabled = !busy) {
                                            historyScanId = scan?.id; historyReviewOpen = false; screen = "history"
                                        }
                                    }
                                }
                                item(key = "metadata-policy") {
                                    MediaSettingsGroup("资料") {
                                        MediaSettingsAction("资料优先级", metadataPolicySummary(policy), R.drawable.ic_settings,
                                            enabled = !busy && pendingId == null && (draftLibraryId == null || setupPaused)) { screen = "policy" }
                                    }
                                }
                                if (!loading && !auxiliaryLoading && support?.available != true) item(key = "scan-unavailable") {
                                    Column {
                                        Text(if (support == null) "无法确认扫描服务状态，请重新检查。" else "扫描服务暂不可用，添加来源后可稍后再试。", style = PhoneMediaType.secondary,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        TextButton(onClick = { refreshPage() }, enabled = !loading && !busy) { Text("重新检查扫描服务") }
                                    }
                                }
                                if (sources.isEmpty() && !loading && error == null) item(key = "no-source") {
                                    FilewayEmptyState(R.drawable.ic_folder, "还没有来源", "选择你有权访问的服务器目录。")
                                }
                                if (sources.isNotEmpty()) item(key = "scan-controls") {
                                    MediaSettingsGroup("扫描方式") {
                                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                MediaScanMode.entries.forEach { mode ->
                                                    MediaSettingsChip(mediaScanModeLabel(mode), scanMode == mode,
                                                        !busy && pendingId == null && draftLibraryId == null) { scanMode = mode }
                                                }
                                            }
                                            Text(mediaScanModeDescription(scanMode), style = PhoneMediaType.secondary,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            if (sources.all { it.access == MediaSourceAccess.AVAILABLE }) {
                                                FilewayButton(onClick = {
                                                    val rules = support?.localRulesRevision ?: return@FilewayButton
                                                    submit("scan") { repository.startScanBatch(it, library.id, sources, rules, scanMode) }
                                                }, enabled = !busy && pendingId == null && draftLibraryId == null && support?.available == true && !loading,
                                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                                    Text("开始${mediaScanModeLabel(scanMode)}", style = PhoneMediaType.action)
                                                }
                                            }
                                        }
                                    }
                                }
                                items(sources, key = { it.id }) { source ->
                                    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
                                        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                            Column(Modifier.weight(1f)) {
                                            Text(source.displayRoot ?: "服务器目录", style = PhoneMediaType.itemTitle)
                                            Text(sourceAccessLabel(source.access), style = PhoneMediaType.secondary)
                                            }
                                            TextButton(onClick = {
                                                if (source.access == MediaSourceAccess.REBIND_REQUIRED) {
                                                    attestingSourceId = source.id
                                                    openDirectory("attest")
                                                } else {
                                                    val rules = support?.localRulesRevision ?: return@TextButton
                                                    submit("scan") { repository.startScan(it, library.id, source.id, source.revision, rules, scanMode) }
                                                }
                                            }, enabled = !busy && !loading && pendingId == null && draftLibraryId == null &&
                                                (source.access == MediaSourceAccess.REBIND_REQUIRED || source.access == MediaSourceAccess.AVAILABLE && support?.available == true),
                                                modifier = Modifier.heightIn(min = 48.dp)) {
                                                Text(if (source.access == MediaSourceAccess.REBIND_REQUIRED) "重新确认" else "开始扫描")
                                            }
                                        }
                                    }
                                }
                                if (sourceCursor != null && !loading && !paging) item(key = "more-sources") {
                                    TextButton(onClick = {
                                        val cursor = sourceCursor ?: return@TextButton
                                        scope.launch {
                                            paging = true
                                            try { when (val result = repository.listSources(library.id, limit = 30, cursor = cursor)) {
                                                is MediaReadResult.Success -> if (result.scope == repository.scope && screen == "library" && selectedId == library.id && sourceCursor == cursor) {
                                                    sources = (sources + result.value.items).distinctBy { it.id }; sourceCursor = result.value.nextCursor
                                                }
                                                is MediaReadResult.Failure -> if (result.scope == repository.scope && screen == "library" && selectedId == library.id && sourceCursor == cursor) error = managerFailure(result.error)
                                            } } catch (cancelled: CancellationException) { throw cancelled }
                                            catch (_: Exception) { if (screen == "library" && selectedId == library.id && sourceCursor == cursor) error = "无法加载更多来源，请重试。" }
                                            finally { paging = false }
                                        }
                                    }, modifier = Modifier.fillMaxWidth()) { Text("加载更多来源") }
                                }
                                items(batchScans.filter { it.libraryId == library.id }, key = { "batch/${it.id}" }) { value ->
                                    MediaScanSummaryCard(value, scanMetadata[value.id],
                                        onOpen = { historyScanId = value.id; historyReviewOpen = false; screen = "history" },
                                        onReview = { historyScanId = value.id; historyReviewOpen = true; screen = "history" },
                                        onCancel = { submit("cancel") { repository.cancelScan(it, library.id, value.id, value.generation) } },
                                        onRetry = { submit("retry") { repository.retryScan(it, library.id, value.id, value.generation) } },
                                        enabled = !busy && pendingId == null)
                                }
                            }
                        }
                    }
                    "directory" -> {
                        if (!directoryVerified && browseError == null) item(key = "browse-loading") { MediaLoading("正在读取文件夹") }
                        browseError?.let { message -> item(key = "browse-error") {
                            MediaProblemCard(MediaProblem("无法读取文件夹", message, "重试"), { browseError = null; browseRefresh++ })
                        } }
                        if (browseEntries.isEmpty() && directoryVerified) item(key = "no-directories") {
                            FilewayEmptyState(R.drawable.ic_folder, "没有下级文件夹", "可以添加当前文件夹。")
                        }
                        if (directoryVerified) items(browseEntries, key = { it.wirePath }) { directory ->
                            FilewayDirectoryRow(directory.name, enabled = !busy) {
                                browseError = null; browseStack = browseStack + directory
                            }
                        }
                    }
                }
            }
            }
            if (screen == "create" || screen == "directory") {
                val creating = screen == "create"
                val attesting = directoryReturn == "attest"
                val alreadyAdded = if (creating || attesting) false else if (directoryReturn == "create") draftFolders.any { it.wirePath == currentDirectory.wirePath }
                    else sources.any { it.wireRoot == LibrarySourceRoot.fromDirectory(currentDirectory) }
                val canSubmit = if (creating) canCreate else if (attesting) directoryVerified && selected != null && attestingSourceId != null
                    else directoryVerified && !alreadyAdded && (directoryReturn == "create" || selected != null)
                Surface(color = MaterialTheme.colorScheme.background,
                    modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                    Column {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!creating) {
                                Text(when {
                                    attesting -> "请选择与原来源相同的文件夹"
                                    alreadyAdded -> "已添加 · ${currentDirectory.name}"
                                    else -> "当前文件夹 · ${currentDirectory.name}"
                                }, style = PhoneMediaType.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            if (creating) {
                                Text(if (draftFolders.isEmpty()) "可以先创建媒体库，之后再添加文件夹。"
                                    else "将创建媒体库并添加 ${draftFolders.size} 个文件夹；创建后可立即设置资料策略。",
                                    style = PhoneMediaType.secondary, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (draftFolders.size > 60) Text("一次最多扫描 60 个文件夹，可以先创建媒体库。",
                                    style = PhoneMediaType.secondary, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (draftFolders.isEmpty()) {
                                    FilewayButton(onClick = { createLibrary(andScan = false) }, enabled = canCreate,
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("创建媒体库") }
                                } else {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        FilewayOutlinedButton(onClick = { createLibrary(andScan = false) }, enabled = canCreate,
                                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("仅创建") }
                                        FilewayButton(onClick = { createLibrary(andScan = true) },
                                            enabled = canCreate && draftFolders.size in 1..60,
                                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                                            Text(if (busy) "正在保存…" else "创建并扫描")
                                        }
                                    }
                                }
                            } else FilewayButton(onClick = {
                                if (verifiedBrowse == (browseStack.last().wirePath to browseRefresh) && browseStack.last() == currentDirectory) {
                                    if (directoryReturn == "create") {
                                        draftFolders = draftFolders + currentDirectory
                                        screen = "create"
                                    } else {
                                        val library = selected ?: return@FilewayButton
                                        val root = LibrarySourceRoot.fromDirectory(currentDirectory)
                                        if (attesting) {
                                            val source = sources.firstOrNull { it.id == attestingSourceId } ?: return@FilewayButton
                                            submit("attest") { repository.attestSource(it, library.id, source.id, source.revision, root) }
                                        } else submit("source") { repository.createLocalSource(it, library.id, library.revision, root) }
                                    }
                                }
                            }, enabled = canSubmit && !busy && pendingId == null,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Text(when {
                                    busy -> "正在保存…"
                                    attesting -> "确认当前文件夹"
                                    alreadyAdded -> "已添加此文件夹"
                                    else -> "添加当前文件夹"
                                })
                            }
                        }
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun MediaLibraryRow(library: MediaLibrarySummary, enabled: Boolean, onOpen: () -> Unit, onHistory: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = colors.surface) {
        Row(Modifier.fillMaxWidth().heightIn(min = 88.dp).padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(42.dp).background(colors.primaryContainer, MaterialTheme.shapes.small),
                contentAlignment = Alignment.Center) {
                FilewayIcon(R.drawable.ic_library, null, Modifier.size(21.dp), tint = colors.onPrimaryContainer)
            }
            Column(Modifier.weight(1f).clickable(enabled = enabled, role = Role.Button, onClick = onOpen).padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(library.name, style = PhoneMediaType.body, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(mediaLibraryRowSubtitle(library), style = PhoneMediaType.secondary,
                    color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = onHistory, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("扫描记录", style = PhoneMediaType.action)
            }
        }
    }
}

@Composable
private fun MediaScanSummaryCard(scan: MediaScan, metadata: MediaMetadataJob?, onOpen: () -> Unit, onReview: () -> Unit,
    onCancel: () -> Unit, onRetry: () -> Unit, enabled: Boolean) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilewayIcon(R.drawable.ic_search, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(scan.sourceLabel ?: "最近扫描", style = PhoneMediaType.itemTitle)
                    Text("${scanStatusLabel(scan.status)} · 已处理 ${scan.processed} 项", style = PhoneMediaType.secondary,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (scan.status == MediaScanStatus.RUNNING || scan.status == MediaScanStatus.QUEUED) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            metadata?.let { job ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MediaScanStat("已匹配", job.matched, Modifier.weight(1f))
                    MediaScanStat("待确认", job.review, Modifier.weight(1f))
                    MediaScanStat("待处理", job.pending, Modifier.weight(1f))
                }
                val secondary = buildList {
                    if (job.skipped > 0) add("已跳过 ${job.skipped}")
                    if (job.failed > 0) add("失败 ${job.failed}")
                }.joinToString(" · ")
                if (secondary.isNotEmpty()) Text(secondary, style = PhoneMediaType.secondary, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FilewayButton(onClick = onOpen, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("查看扫描结果", style = PhoneMediaType.action)
            }
            if ((metadata?.review ?: 0) > 0) {
                FilewayOutlinedButton(onClick = onReview, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("处理待确认 · ${metadata?.review ?: 0}", style = PhoneMediaType.action)
                }
            }
            if (scan.status in setOf(MediaScanStatus.QUEUED, MediaScanStatus.RUNNING, MediaScanStatus.FAILED,
                    MediaScanStatus.CANCELED, MediaScanStatus.INTERRUPTED)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = if (scan.status == MediaScanStatus.QUEUED || scan.status == MediaScanStatus.RUNNING) onCancel else onRetry,
                        enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(if (scan.status == MediaScanStatus.QUEUED || scan.status == MediaScanStatus.RUNNING) "取消扫描" else "重试扫描",
                            style = PhoneMediaType.action)
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaScanStat(label: String, value: Long, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value.toString(), style = PhoneMediaType.sectionTitle)
        Text(label, style = PhoneMediaType.badge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

internal fun mediaLibraryKindLabel(kind: MediaLibraryKind): String = when (kind) {
    MediaLibraryKind.MOVIE -> "电影"
    MediaLibraryKind.SERIES -> "电视剧"
    MediaLibraryKind.MIXED -> "混合"
    MediaLibraryKind.UNKNOWN -> "类型未知"
}

internal enum class MediaLibraryOpenDestination { MANAGE, BROWSE }

internal fun mediaLibraryOpenDestination(role: MediaLibraryRole): MediaLibraryOpenDestination =
    if (role == MediaLibraryRole.OWNER) MediaLibraryOpenDestination.MANAGE else MediaLibraryOpenDestination.BROWSE

internal fun mediaLibraryRowSubtitle(library: MediaLibrarySummary): String =
    "${mediaLibraryKindLabel(library.contentKind)} · ${if (library.role == MediaLibraryRole.OWNER) "管理目录与资料" else "共享媒体库"}"

private fun metadataPolicySummary(policy: MediaProviderPolicy?): String {
    if (policy == null) return "读取后可配置语言、地区与时长过滤"
    val mode = when (policy.mode) {
        MediaProviderPolicyMode.LOCAL_ONLY -> "仅本地资料"
        MediaProviderPolicyMode.LOCAL_FIRST_ONLINE -> "本地优先，TMDB 补缺"
        MediaProviderPolicyMode.ONLINE_FIRST -> "TMDB 优先"
        MediaProviderPolicyMode.UNKNOWN -> "资料策略未知"
    }
    val language = when (policy.language) {
        "zh-CN" -> "简体中文"
        "zh-TW" -> "繁体中文"
        "en-US" -> "English"
        "ja-JP" -> "日本語"
        else -> policy.language.ifBlank { "语言未设置" }
    }
    return "$mode · $language · ${policy.region.ifBlank { "地区未设置" }}"
}

/** Scan setup only after every source page has been read and no continuation remains. */
private suspend fun readAllSetupSources(repository: MediaLibraryRepository, libraryId: String): MediaReadResult<List<MediaLibrarySource>> {
    val sources = mutableListOf<MediaLibrarySource>()
    val ids = mutableSetOf<String>()
    val cursors = mutableSetOf<String>()
    var cursor: String? = null
    repeat(100) {
        when (val result = repository.listSources(libraryId, limit = 100, cursor = cursor)) {
            is MediaReadResult.Failure -> return result
            is MediaReadResult.Success -> {
                if (result.scope != repository.scope || result.value.items.any { it.libraryId != libraryId || !ids.add(it.id) }) {
                    return MediaReadResult.Failure(repository.scope, MediaFailure(MediaFailureKind.INVALID_RESPONSE))
                }
                sources.addAll(result.value.items)
                val next = result.value.nextCursor
                if (next == null) return MediaReadResult.Success(repository.scope, sources, result.sessionHealth)
                if (result.value.items.isEmpty() || !cursors.add(next)) {
                    return MediaReadResult.Failure(repository.scope, MediaFailure(MediaFailureKind.INVALID_RESPONSE))
                }
                cursor = next
            }
        }
    }
    return MediaReadResult.Failure(repository.scope, MediaFailure(MediaFailureKind.INVALID_RESPONSE))
}

private val mediaFolderSaver = listSaver<List<ResourceRef>, String>(
    save = { folders -> folders.flatMap { listOf(it.path, it.wirePath, it.name) } },
    restore = { saved -> saved.chunked(3).map { ResourceRef(it[0], it[1], it[2], true, "", 0) } },
)

internal fun mediaLibraryShowsCoreLoading(screen: String): Boolean = screen == "libraries" || screen == "library"

internal fun mediaLibraryNeedsAuxiliaryLoad(screen: String, role: MediaLibraryRole?): Boolean =
    screen == "library" && role == MediaLibraryRole.OWNER

internal fun mediaLibraryAuxiliaryResultIsCurrent(selectedId: String?, selectedLibraryId: String?, screen: String, loadId: String): Boolean =
    selectedId == loadId && selectedLibraryId == loadId && screen == "library"

private fun managerFailure(error: MediaFailure): String = when (error.kind) {
    MediaFailureKind.AUTHENTICATION -> "登录状态已失效，请重新登录。"
    MediaFailureKind.FORBIDDEN, MediaFailureKind.NOT_FOUND -> "内容不存在或当前账号没有操作权限。"
    MediaFailureKind.CONFLICT, MediaFailureKind.CURSOR_STALE, MediaFailureKind.CURSOR_EXPIRED -> "媒体库已变化，请刷新后重试。"
    MediaFailureKind.NETWORK -> "连接失败，请检查网络后重试。"
    MediaFailureKind.STORAGE -> "本机无法保存操作记录，未发送请求。"
    else -> "服务器暂时无法完成操作，请稍后重试。"
}

private fun sourceAccessLabel(access: MediaSourceAccess): String = when (access) {
    MediaSourceAccess.AVAILABLE -> "来源可用"
    MediaSourceAccess.OFFLINE -> "来源离线"
    MediaSourceAccess.UNVERIFIED -> "来源尚未确认"
    MediaSourceAccess.REBIND_REQUIRED -> "来源已变化，需要重新确认"
    MediaSourceAccess.UNAVAILABLE, MediaSourceAccess.UNKNOWN -> "来源暂不可用"
}

private fun scanStatusLabel(status: MediaScanStatus): String = when (status) {
    MediaScanStatus.QUEUED -> "等待扫描"
    MediaScanStatus.RUNNING -> "正在扫描"
    MediaScanStatus.COMPLETED -> "扫描完成"
    MediaScanStatus.FAILED -> "扫描失败"
    MediaScanStatus.CANCELED -> "扫描已取消"
    MediaScanStatus.INTERRUPTED -> "扫描已中断"
    MediaScanStatus.UNKNOWN -> "扫描状态未知"
}
