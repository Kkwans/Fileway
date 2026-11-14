package io.github.kkwans.nasfilebrowser.ui.medialibrary

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.app.ResourceRef
import io.github.kkwans.nasfilebrowser.app.SessionContext
import io.github.kkwans.nasfilebrowser.data.*
import io.github.kkwans.nasfilebrowser.data.MediaSourceRoot as LibrarySourceRoot
import io.github.kkwans.nasfilebrowser.data.archiveAbsoluteWire
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
        var screen by rememberSaveable { mutableStateOf("libraries") }
        var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
        var name by rememberSaveable { mutableStateOf("") }
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
        var loading by remember { mutableStateOf(false) }
        var paging by remember { mutableStateOf(false) }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var notice by remember { mutableStateOf<String?>(null) }
        var syncWarning by remember { mutableStateOf<String?>(null) }
        var browseStack by remember { mutableStateOf(listOf(ResourceRef("/", "/", "根目录", true, "", 0))) }
        var browseEntries by remember { mutableStateOf<List<ResourceRef>>(emptyList()) }
        var browseLoading by remember { mutableStateOf(false) }
        var browseError by remember { mutableStateOf<String?>(null) }
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
                "directory" -> { screen = "library"; browseEntries = emptyList() }
                "create" -> screen = "libraries"
                "library" -> { screen = "libraries"; selectedId = null; selected = null }
                else -> onBack()
            }
        }
        BackHandler(enabled = screen != "libraries" || busy) { leave() }

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
        LaunchedEffect(repository, screen, currentDirectory.wirePath, browseRefresh) {
            if (screen != "directory") return@LaunchedEffect
            browseLoading = true; browseError = null; browseEntries = emptyList()
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
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { browseError = "无法读取此目录，可能已离线或没有访问权限。" }
            finally { browseLoading = false }
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

        fun accept(result: MediaWriteResult<*>) {
            when (result) {
                is MediaWriteResult.Success -> if (result.scope == repository.scope) {
                    pendingId = null; pendingKind = null
                    when (val value = result.value) {
                        is MediaLibrarySummary -> {
                            selected = value; selectedId = value.id; screen = "library"
                            notice = "媒体库已创建。接下来可以添加本地目录。"
                        }
                        is MediaLibrarySource -> {
                            selected = selected?.copy(revision = value.libraryRevision)
                            screen = "library"; notice = "来源已添加。请确认后启动扫描。"
                        }
                        is MediaScan -> {
                            scan = value; scanLibrary = value.libraryId; scanId = value.id
                            screen = "library"; notice = "扫描请求已接受。"
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

        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            FilewayTopAppBar(when (screen) {
                "create" -> "创建媒体库"; "library" -> "媒体库"; "directory" -> "选择服务器目录"; else -> "媒体库管理"
            }, leadingIcon = R.drawable.ic_arrow_back, leadingDescription = "返回", onLeadingClick = { leave() })
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
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
                        item(key = "intro") { Text("媒体库默认仅自己可见。", style = MaterialTheme.typography.bodyMedium) }
                        if (session.api.identity.deviceSessionId.isEmpty()) item(key = "create") {
                            Button(onClick = { screen = "create"; error = null; notice = null }, enabled = !busy && pendingId == null,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("创建媒体库") }
                        }
                        if (libraries.isEmpty() && !loading && error == null) item(key = "empty") {
                            FilewayEmptyState(R.drawable.ic_library, "还没有媒体库", "创建媒体库后再选择已有影视目录。")
                        }
                        items(libraries, key = { it.id }) { library ->
                            Card(onClick = { selectedId = library.id; selected = null; screen = "library"; notice = null },
                                enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp)) {
                                    Text(library.name, style = MaterialTheme.typography.titleMedium)
                                    Text(if (library.role == MediaLibraryRole.OWNER) "我的媒体库" else "共享媒体库 · 仅查看",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
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
                        val validName = name.isNotBlank() && name.trim().codePointCount(0, name.trim().length) <= 200 &&
                            name.none { it.isISOControl() }
                        item(key = "name") {
                            OutlinedTextField(name, { name = it }, label = { Text("媒体库名称") },
                                enabled = !busy && pendingId == null, singleLine = true, modifier = Modifier.fillMaxWidth(),
                                isError = name.isNotEmpty() && !validName,
                                supportingText = if (name.isNotEmpty() && !validName) ({ Text("名称须为 1–200 个字符且不含控制字符") }) else null)
                        }
                        item(key = "create-action") {
                            Button(onClick = { submit("create") { repository.createLibrary(it, name.trim()) } },
                                enabled = validName && !busy && pendingId == null,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("创建媒体库") }
                        }
                    }
                    "library" -> {
                        selected?.let { library ->
                            item(key = "library-name") {
                                Text(library.name, style = MaterialTheme.typography.headlineSmall)
                                Text(if (library.role == MediaLibraryRole.OWNER) "仅自己管理；来源文件不会被移动或改名。" else "你没有管理此媒体库来源的权限。",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (library.role == MediaLibraryRole.OWNER) {
                                item(key = "add-source") {
                                    Button(onClick = {
                                        browseStack = listOf(ResourceRef("/", "/", "根目录", true, "", 0))
                                        screen = "directory"; browseRefresh++; error = null
                                    }, enabled = !busy && pendingId == null, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                        Text("添加本地目录")
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
                                items(sources, key = { it.id }) { source ->
                                    Card(modifier = Modifier.fillMaxWidth()) {
                                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text(source.displayRoot ?: "服务器目录", style = MaterialTheme.typography.titleMedium)
                                            Text(sourceAccessLabel(source.access), style = MaterialTheme.typography.bodySmall)
                                            Button(onClick = {
                                                val rules = support?.localRulesRevision ?: return@Button
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
                            }
                        }
                    }
                    "directory" -> {
                        item(key = "directory-current") {
                            Text(currentDirectory.name, style = MaterialTheme.typography.titleLarge)
                            Text("只显示当前账号可访问的目录。", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (browseStack.size > 1) item(key = "up") {
                            TextButton(onClick = { browseStack = browseStack.dropLast(1) }, enabled = !browseLoading && !busy) { Text("上一级") }
                        }
                        if (browseLoading) item(key = "browse-loading") { MediaLoading("正在读取目录") }
                        browseError?.let { message -> item(key = "browse-error") {
                            MediaProblemCard(MediaProblem("无法读取目录", message, "重试"), { browseRefresh++ })
                        } }
                        if (browseEntries.isEmpty() && !browseLoading && browseError == null) item(key = "no-directories") {
                            FilewayEmptyState(R.drawable.ic_folder, "没有子目录", "可以选择当前目录。")
                        }
                        items(browseEntries, key = { it.wirePath }) { directory ->
                            Card(onClick = { browseStack = browseStack + directory }, enabled = !browseLoading && !busy,
                                modifier = Modifier.fillMaxWidth()) { Text(directory.name, Modifier.padding(16.dp)) }
                        }
                        item(key = "use-directory") {
                            Button(onClick = {
                                val library = selected ?: return@Button
                                val root = try { LibrarySourceRoot.fromDirectory(currentDirectory) } catch (_: Exception) {
                                    browseError = "目录原始路径无法确认，请返回后重新选择。"; return@Button
                                }
                                submit("source") { repository.createLocalSource(it, library.id, library.revision, root) }
                            }, enabled = !browseLoading && browseError == null && !busy && pendingId == null && selected != null,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("使用当前目录") }
                        }
                    }
                }
            }
        }
    }
}

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
