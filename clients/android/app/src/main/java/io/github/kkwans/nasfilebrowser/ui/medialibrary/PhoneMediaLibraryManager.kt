package io.github.kkwans.nasfilebrowser.ui.medialibrary

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.app.ResourceRef
import io.github.kkwans.nasfilebrowser.app.SessionContext
import io.github.kkwans.nasfilebrowser.data.*
import io.github.kkwans.nasfilebrowser.data.MediaSourceRoot as LibrarySourceRoot
import io.github.kkwans.nasfilebrowser.data.archiveAbsoluteWire
import io.github.kkwans.nasfilebrowser.ui.SettingsAction
import io.github.kkwans.nasfilebrowser.ui.SettingsGroup
import io.github.kkwans.nasfilebrowser.ui.DirectoryBreadcrumbItem
import io.github.kkwans.nasfilebrowser.ui.DirectoryBreadcrumbs
import io.github.kkwans.nasfilebrowser.ui.FilewayButton
import io.github.kkwans.nasfilebrowser.ui.FilewayDirectoryRow
import io.github.kkwans.nasfilebrowser.ui.FilewayIcon
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayEmptyState
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayTopAppBar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/** Creates a private library, then lets the owner explicitly bind and scan a local source. */
@Composable internal fun PhoneMediaLibraryManager(
    session: SessionContext,
    repository: MediaLibraryRepository,
    onBack: () -> Unit,
    onChanged: () -> Unit,
    onProvider: () -> Unit,
    onReviewItem: (MediaCatalogRef) -> Unit,
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
        val focusManager = LocalFocusManager.current
        var draftFolders by rememberSaveable(stateSaver = mediaFolderSaver) { mutableStateOf(emptyList<ResourceRef>()) }
        var draftLibraryId by rememberSaveable { mutableStateOf<String?>(null) }
        var draftBinding by rememberSaveable { mutableStateOf<String?>(null) }
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
        var scanLibrary by rememberSaveable { mutableStateOf<String?>(null) }
        var scanId by rememberSaveable { mutableStateOf<String?>(null) }
        var batchIds by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
        var batchScans by remember { mutableStateOf<List<MediaScan>>(emptyList()) }
        var loading by remember { mutableStateOf(false) }
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

        fun refreshPage() { refresh++; scanRefresh++ }
        LaunchedEffect(repository, busy, pendingId) {
            if (!busy && pendingId == null) pendingId = repository.pendingSetupOperation()?.id
        }
        LaunchedEffect(notice) { if (notice != null) { delay(5_000); notice = null } }
        fun leave() {
            if (busy) return
            when (screen) {
                "directory" -> if (browseStack.size > 1) browseStack = browseStack.dropLast(1)
                    else { screen = directoryReturn; browseEntries = emptyList() }
                "create" -> { name = ""; draftFolders = emptyList(); if (createInitially) onBack() else screen = "libraries" }
                "policy" -> screen = "library"
                "history" -> { historyScanId = null; screen = "library" }
                "library" -> { screen = "libraries"; selectedId = null; selected = null }
                else -> onBack()
            }
        }
        BackHandler(enabled = screen != "libraries" || busy) { leave() }
        if (screen == "policy" && selectedId != null) {
            PhoneMediaPolicySettings(repository, selectedId!!, { screen = "library" }, onProvider) {
                screen = "library"; notice = "资料优先级已保存。"; onChanged()
            }
        } else if (screen == "history" && selectedId != null) {
            PhoneMediaScanHistory(repository, selectedId!!, historyScanId, { historyScanId = it }, ::leave, onReviewItem, onChanged)
        } else {

        LaunchedEffect(repository, screen, selectedId, refresh) {
            if (screen != "libraries" && screen != "library") return@LaunchedEffect
            loading = true; error = null
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
                            selected = null; sources = emptyList(); error = managerFailure(result.error)
                        }
                    }
                    if (selected?.id == id && selected?.role == MediaLibraryRole.OWNER) {
                        when (val result = repository.listSources(id, limit = 30)) {
                            is MediaReadResult.Success -> if (result.scope == repository.scope) {
                                sources = result.value.items; sourceCursor = result.value.nextCursor
                            }
                            is MediaReadResult.Failure -> if (result.scope == repository.scope) {
                                sources = emptyList(); sourceCursor = null; error = managerFailure(result.error)
                            }
                        }
                        when (val result = repository.scanSupport()) {
                            is MediaReadResult.Success -> if (result.scope == repository.scope) support = result.value
                            is MediaReadResult.Failure -> if (result.scope == repository.scope) support = null
                        }
                    } else { sources = emptyList(); sourceCursor = null; support = null }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "暂时无法读取媒体库，请检查连接后重试。" }
            finally { loading = false }
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
                        scan = null; error = "无法刷新扫描状态，请稍后重试。"; return@LaunchedEffect
                    }
                }
                delay(2_000)
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
                if (current.size != batchIds.size) {
                    error = "有扫描任务暂时无法读取，可刷新后重试。"
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
                    pendingId = null; pendingKind = null
                    when (val value = result.value) {
                        is MediaLibrarySummary -> {
                            name = ""
                            selected = value; selectedId = value.id; screen = "library"
                            if (draftFolders.isNotEmpty()) { draftLibraryId = value.id; loading = true }
                            else notice = "媒体库已创建。"
                        }
                        is MediaLibrarySource -> {
                            selected = selected?.copy(revision = value.libraryRevision)
                            if (draftBinding != null) {
                                draftFolders = draftFolders.filterNot { it.wirePath == draftBinding }
                                draftBinding = null
                                if (draftFolders.isEmpty()) { draftLibraryId = null; notice = "媒体库和文件夹已保存，可以开始扫描。" }
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
                            screen = "library"; notice = "已提交 ${value.items.size} 个目录的扫描。"
                        }
                    }
                    if (result.journalStorageFailed || result.sessionSyncIncomplete) {
                        syncWarning = "操作已接受，但本机状态同步未完成，请刷新查看。"
                    }
                    error = null
                    refreshPage(); onChanged()
                }
                is MediaWriteResult.Failure -> if (result.scope == repository.scope) {
                    if (!result.error.mutationMayHaveCommitted) { pendingId = null; pendingKind = null }
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
                catch (_: Exception) { error = "操作结果尚未确认，请使用原操作记录确认。" }
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
                    accept(result)
                    if (result is MediaWriteResult.Success || result is MediaWriteResult.Failure && !result.error.mutationMayHaveCommitted) {
                        repository.forgetResolvedOperation(operation)
                    }
                }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { error = "无法确认上次操作，请检查连接后重试。" }
                finally { busy = false }
            }
        }

        LaunchedEffect(draftLibraryId, draftFolders, busy, pendingId, selected, screen, error, loading) {
            val library = selected?.takeIf { it.id == draftLibraryId } ?: return@LaunchedEffect
            val folder = draftFolders.firstOrNull() ?: return@LaunchedEffect
            if (screen == "library" && !busy && !loading && pendingId == null && error == null) {
                draftBinding = folder.wirePath
                submit("source") { repository.createLocalSource(it, library.id, library.revision, LibrarySourceRoot.fromDirectory(folder)) }
            }
        }
        val trimmedName = name.trim()
        val validName = trimmedName.isNotEmpty() && trimmedName.codePointCount(0, trimmedName.length) <= 200 &&
            name.none { it.isISOControl() }
        val canCreate = validName && !busy && pendingId == null
        fun createLibrary() {
            if (canCreate) {
                focusManager.clearFocus()
                submit("create") { repository.createLibrary(it, trimmedName) }
            }
        }

        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding(),
            horizontalAlignment = Alignment.CenterHorizontally) {
            FilewayTopAppBar(when (screen) {
                "create" -> "新建媒体库"; "library" -> selected?.name ?: "媒体库"; "directory" -> "选择媒体文件夹"; else -> "管理媒体库"
            }, leadingIcon = R.drawable.ic_arrow_back, leadingDescription = "返回", onLeadingClick = {
                if (screen == "directory" && !busy) screen = directoryReturn else leave()
            }, actions = {
                if (screen == "libraries" && session.api.identity.deviceSessionId.isEmpty()) {
                    TextButton(onClick = { screen = "create"; error = null; notice = null }, enabled = !busy && pendingId == null) { Text("创建") }
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
                          else if (screen == "directory") browseRefresh++ else refreshPage() })
                }
                if (pendingId != null && error == null && !busy) item(key = "pending") {
                    val label = when (pendingKind) { "create" -> "创建媒体库"; "source" -> "添加来源"; "scan" -> "启动扫描"; else -> "上次操作" }
                    MediaProblemCard(MediaProblem("${label}尚待确认", "请先确认结果，避免重复提交。", "确认上次操作"), ::confirmPending)
                }
                if (busy) item(key = "busy") { MediaLoading("正在处理，请稍候") }
                if (loading) item(key = "loading") { MediaLoading("正在读取媒体库") }
                when (screen) {
                    "libraries" -> {
                        if (libraries.isEmpty() && !loading && error == null) item(key = "empty") {
                            FilewayEmptyState(R.drawable.ic_library, "还没有媒体库", "创建媒体库后再选择已有影视目录。")
                        }
                        items(libraries, key = { it.id }) { library ->
                            Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
                                SettingsAction(library.name, if (library.role == MediaLibraryRole.OWNER) "管理目录与资料" else "共享媒体库",
                                    R.drawable.ic_library, enabled = !busy) { selectedId = library.id; selected = null; screen = "library"; notice = null }
                            }
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
                        item(key = "name") {
                            SettingsGroup("基本信息") {
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
                        item(key = "draft-folders") {
                            SettingsGroup("媒体文件夹") {
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
                                SettingsAction("添加文件夹", if (draftFolders.isEmpty()) "可选，也可以创建后再添加" else "${draftFolders.size} 个文件夹已选",
                                    R.drawable.ic_folder, enabled = !busy && pendingId == null) { openDirectory("create") }
                            }
                        }
                    }
                    "library" -> {
                        selected?.let { library ->
                            if (library.role == MediaLibraryRole.OWNER) {
                                item(key = "add-source") {
                                    SettingsGroup("媒体文件夹") {
                                        SettingsAction("添加文件夹", "可关联多个当前服务器目录", R.drawable.ic_folder,
                                            enabled = !busy && pendingId == null && draftLibraryId == null) { openDirectory("library") }
                                    }
                                }
                                if (draftLibraryId == library.id && draftFolders.isNotEmpty() && !busy && pendingId == null && error != null) {
                                    item(key = "retry-draft-folders") {
                                        SettingsGroup("待添加的文件夹") {
                                            SettingsAction("重试添加", "还有 ${draftFolders.size} 个文件夹待添加", R.drawable.ic_folder) {
                                                refreshPage()
                                            }
                                            TextButton(onClick = { draftLibraryId = null; draftBinding = null; draftFolders = emptyList(); error = null },
                                                modifier = Modifier.padding(horizontal = 12.dp)) { Text("稍后手动添加") }
                                        }
                                    }
                                }
                                item(key = "scan-history") {
                                    SettingsGroup("扫描与补全") {
                                        SettingsAction("扫描记录", "查看目录扫描、TMDB 补全和待确认影片", R.drawable.ic_search, enabled = !busy) {
                                            historyScanId = null; screen = "history"
                                        }
                                    }
                                }
                                item(key = "metadata-policy") {
                                    SettingsGroup("资料") {
                                        SettingsAction("资料优先级", "本地资料与 TMDB", R.drawable.ic_settings, enabled = !busy && pendingId == null) { screen = "policy" }
                                    }
                                }
                                if (support?.available != true) item(key = "scan-unavailable") {
                                    Column {
                                        Text("扫描服务暂不可用，添加来源后可稍后再试。", style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        TextButton(onClick = { refreshPage() }, enabled = !loading && !busy) { Text("重新检查扫描服务") }
                                    }
                                }
                                if (sources.isEmpty() && !loading && error == null) item(key = "no-source") {
                                    FilewayEmptyState(R.drawable.ic_folder, "还没有来源", "选择你有权访问的服务器目录。")
                                }
                                if (sources.size > 1 && sourceCursor == null && sources.size <= 60 && sources.all { it.access == MediaSourceAccess.AVAILABLE }) item(key = "scan-all-sources") {
                                    SettingsGroup("扫描") {
                                        SettingsAction("扫描全部目录", "${sources.size} 个目录 · 在服务器后台运行", R.drawable.ic_search,
                                            enabled = !busy && pendingId == null && support?.available == true && !loading, navigation = false) {
                                            val rules = support?.localRulesRevision ?: return@SettingsAction
                                            val activeSources = sources
                                            submit("scan") { repository.startScanBatch(it, library.id, activeSources, rules) }
                                        }
                                    }
                                }
                                items(sources, key = { it.id }) { source ->
                                    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
                                        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                            Column(Modifier.weight(1f)) {
                                            Text(source.displayRoot ?: "服务器目录", style = MaterialTheme.typography.titleMedium)
                                            Text(sourceAccessLabel(source.access), style = MaterialTheme.typography.bodySmall)
                                            }
                                            TextButton(onClick = {
                                                val rules = support?.localRulesRevision ?: return@TextButton
                                                submit("scan") { repository.startScan(it, library.id, source.id, source.revision, rules) }
                                            }, enabled = source.access == MediaSourceAccess.AVAILABLE && support?.available == true && !busy && pendingId == null,
                                                modifier = Modifier.heightIn(min = 48.dp)) { Text("开始扫描") }
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
                                scan?.takeIf { it.libraryId == library.id }?.let { value -> item(key = "scan") {
                                    Text("${scanStatusLabel(value.status)} · 已处理 ${value.processed} 项", style = MaterialTheme.typography.bodyMedium)
                                        TextButton(onClick = { historyScanId = value.id; screen = "history" }) { Text("查看进度") }
                                        when (value.status) {
                                            MediaScanStatus.QUEUED, MediaScanStatus.RUNNING ->
                                                TextButton(onClick = {
                                                    submit("cancel") { repository.cancelScan(it, library.id, value.id, value.generation) }
                                                }, enabled = !busy && pendingId == null) { Text("取消扫描") }
                                            MediaScanStatus.FAILED, MediaScanStatus.CANCELED, MediaScanStatus.INTERRUPTED ->
                                                TextButton(onClick = {
                                                    submit("retry") { repository.retryScan(it, library.id, value.id, value.generation) }
                                                }, enabled = !busy && pendingId == null) { Text("重试扫描") }
                                            else -> Unit
                                        }
                                } }
                                items(batchScans.filter { it.libraryId == library.id }, key = { "batch/${it.id}" }) { value ->
                                    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
                                        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Column(Modifier.weight(1f)) {
                                                Text(sources.firstOrNull { it.id == value.bindingId }?.displayRoot ?: "媒体文件夹", style = MaterialTheme.typography.titleSmall)
                                                Text("${scanStatusLabel(value.status)} · 已处理 ${value.processed} 项", style = MaterialTheme.typography.bodySmall)
                                            }
                                            TextButton(onClick = { historyScanId = value.id; screen = "history" }) { Text("进度") }
                                            when (value.status) {
                                                MediaScanStatus.QUEUED, MediaScanStatus.RUNNING -> TextButton(onClick = {
                                                    submit("cancel") { repository.cancelScan(it, library.id, value.id, value.generation) }
                                                }, enabled = !busy && pendingId == null) { Text("取消") }
                                                MediaScanStatus.FAILED, MediaScanStatus.CANCELED, MediaScanStatus.INTERRUPTED -> TextButton(onClick = {
                                                    submit("retry") { repository.retryScan(it, library.id, value.id, value.generation) }
                                                }, enabled = !busy && pendingId == null) { Text("重试") }
                                                else -> Unit
                                            }
                                        }
                                    }
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
                val alreadyAdded = if (directoryReturn == "create") draftFolders.any { it.wirePath == currentDirectory.wirePath }
                    else sources.any { it.wireRoot == LibrarySourceRoot.fromDirectory(currentDirectory) }
                val canSubmit = if (creating) canCreate else directoryVerified && !alreadyAdded &&
                    (directoryReturn == "create" || selected != null)
                Surface(color = MaterialTheme.colorScheme.background,
                    modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                    Column {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!creating) {
                                Text(if (alreadyAdded) "已添加 · ${currentDirectory.name}" else "当前文件夹 · ${currentDirectory.name}",
                                    style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            FilewayButton(onClick = {
                                if (creating) createLibrary()
                                else if (verifiedBrowse == (browseStack.last().wirePath to browseRefresh) && browseStack.last() == currentDirectory) {
                                    if (directoryReturn == "create") {
                                        draftFolders = draftFolders + currentDirectory
                                        screen = "create"
                                    } else {
                                        val library = selected ?: return@FilewayButton
                                        val root = LibrarySourceRoot.fromDirectory(currentDirectory)
                                        submit("source") { repository.createLocalSource(it, library.id, library.revision, root) }
                                    }
                                }
                            }, enabled = canSubmit && !busy && pendingId == null,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Text(when {
                                    busy -> "正在保存…"
                                    creating -> "创建媒体库"
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

private val mediaFolderSaver = listSaver<List<ResourceRef>, String>(
    save = { folders -> folders.flatMap { listOf(it.path, it.wirePath, it.name) } },
    restore = { saved -> saved.chunked(3).map { ResourceRef(it[0], it[1], it[2], true, "", 0) } },
)

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
