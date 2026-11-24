package io.github.kkwans.nasfilebrowser.ui.medialibrary

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.data.*
import io.github.kkwans.nasfilebrowser.ui.FilewayIcon
import io.github.kkwans.nasfilebrowser.ui.FilewayIconButton
import io.github.kkwans.nasfilebrowser.ui.SettingsAction
import io.github.kkwans.nasfilebrowser.ui.SettingsGroup
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewaySelectionRow
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayStatusBanner
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayStatusKind
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayTopAppBar
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun PhoneMediaSettings(onBack: () -> Unit, onLibraries: () -> Unit, onProvider: () -> Unit) {
    MediaSettingsPage("影视设置", onBack) {
        SettingsGroup("媒体库") {
            SettingsAction("管理媒体库", "添加目录、设置资料优先级、查看扫描进度", R.drawable.ic_library, action = onLibraries)
        }
        SettingsGroup("数据源") {
            SettingsAction("TMDB", "配置密钥并检测与服务器的连接", R.drawable.ic_settings, action = onProvider)
        }
    }
}

@Composable
private fun MediaSettingsPage(title: String, onBack: () -> Unit, footer: @Composable () -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding()) {
        FilewayTopAppBar(title, leadingIcon = R.drawable.ic_arrow_back, leadingDescription = "返回", onLeadingClick = onBack)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
        }
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxWidth()) {
            Box(contentAlignment = Alignment.Center) { footer() }
        }
    }
}

@Composable
internal fun PhoneMediaProviderSettings(repository: MediaLibraryRepository, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var status by remember(repository) { mutableStateOf<MediaProviderStatus?>(null) }
    var statusLoading by remember(repository) { mutableStateOf(true) }
    var statusError by remember(repository) { mutableStateOf<MediaFailure?>(null) }
    var statusRefresh by remember(repository) { mutableIntStateOf(0) }
    var connections by remember(repository) { mutableStateOf<List<MediaProviderConnection>?>(null) }
    var connectionsLoading by remember(repository) { mutableStateOf(true) }
    var connectionsError by remember(repository) { mutableStateOf<MediaFailure?>(null) }
    var connectionsRefresh by remember(repository) { mutableIntStateOf(0) }
    var selectedId by remember(repository) { mutableStateOf<String?>(null) }
    // Never save a credential draft in instance state or the durable write journal.
    var key by remember(repository) { mutableStateOf("") }
    var keyRevision by remember(repository) { mutableLongStateOf(0L) }
    var keyVisible by remember(repository) { mutableStateOf(false) }
    var saving by remember(repository) { mutableStateOf(false) }
    var checking by remember(repository) { mutableStateOf(false) }
    var unknownSave by remember(repository) { mutableStateOf(false) }
    var confirmUnknownSave by remember(repository) { mutableStateOf(false) }
    var actionError by remember(repository) { mutableStateOf<String?>(null) }
    var feedback by remember(repository) { mutableStateOf<String?>(null) }
    var feedbackRevision by remember(repository) { mutableIntStateOf(0) }
    var actionJob by remember(repository) { mutableStateOf<Job?>(null) }

    DisposableEffect(repository) { onDispose { actionJob?.cancel() } }
    LaunchedEffect(repository, feedbackRevision) {
        if (feedback != null) { delay(4_000); feedback = null }
    }
    LaunchedEffect(repository, statusRefresh) {
        statusLoading = true
        status = null
        statusError = null
        try {
            when (val result = repository.providerStatus()) {
                is MediaReadResult.Success -> if (result.scope == repository.scope) status = result.value
                is MediaReadResult.Failure -> if (result.scope == repository.scope) statusError = result.error
            }
        } finally { statusLoading = false }
    }
    LaunchedEffect(repository, connectionsRefresh) {
        connectionsLoading = true
        connections = null
        connectionsError = null
        try {
            when (val result = readAllProviderConnections(repository)) {
                is MediaReadResult.Success -> if (result.scope == repository.scope) {
                    connections = result.value
                    val own = result.value.filter {
                        it.provider == "tmdb" && it.owner == MediaProviderOwner.USER && it.ownerUserId == repository.scope.account.userId
                    }
                    selectedId = selectedId?.takeIf { id -> own.any { it.id == id } } ?: own.singleOrNull()?.id
                }
                is MediaReadResult.Failure -> if (result.scope == repository.scope) connectionsError = result.error
            }
        } finally { connectionsLoading = false }
    }
    val personal = connections.orEmpty().filter {
        it.provider == "tmdb" && it.owner == MediaProviderOwner.USER && it.ownerUserId == repository.scope.account.userId
    }
    val ambiguous = connections.orEmpty().any {
        it.provider == "tmdb" && (it.owner == MediaProviderOwner.UNKNOWN ||
            it.owner == MediaProviderOwner.USER && (it.ownerUserId != repository.scope.account.userId || it.state == MediaProviderConnectionState.UNKNOWN))
    }
    val selected = personal.firstOrNull { it.id == selectedId }
    val valid = remember(key) { validProviderDraft(key) }
    val canSave = valid && !saving && !checking && !statusLoading && status?.credentialStoreAvailable == true &&
        !connectionsLoading && connections != null && !ambiguous && (personal.isEmpty() || selected != null)
    val canCheck = !saving && !checking && !connectionsLoading && connections != null && !ambiguous &&
        status?.credentialStoreAvailable == true && selected?.state == MediaProviderConnectionState.CONFIGURED

    val saveCredential: () -> Unit = {
        if (canSave) {
            val operation = repository.newOperation()
            val submitted = key
            val submittedRevision = keyRevision
            val target = selected
            saving = true
            actionError = null
            feedback = null
            actionJob = scope.launch {
                try {
                    val result = if (target == null) repository.createPersonalProvider(operation, submitted)
                        else repository.rotatePersonalProvider(operation, target, submitted)
                    when (result) {
                        is MediaProviderCredentialResult.Success -> if (result.scope == repository.scope) {
                            val unchanged = keyRevision == submittedRevision
                            if (unchanged) { key = ""; keyRevision++; keyVisible = false }
                            selectedId = result.connection.id
                            unknownSave = false
                            if (result.sessionSyncIncomplete) {
                                actionError = "密钥已保存，但会话同步未完成，请重新登录后检测连接。"
                            } else {
                                feedback = if (!unchanged) "密钥已保存，输入框中的新内容尚未保存。" else "密钥已保存，可以检测连接。"
                                feedbackRevision++
                            }
                            connections = null
                            connectionsLoading = true
                            connectionsRefresh++
                        }
                        is MediaProviderCredentialResult.Failure -> if (result.scope == repository.scope) {
                            if (result.error.mutationMayHaveCommitted) {
                                unknownSave = true
                                actionError = null
                                connections = null
                                connectionsLoading = true
                                connectionsRefresh++
                            } else {
                                actionError = providerProblem(result.error)
                                if (result.error.kind == MediaFailureKind.CONFLICT) {
                                    connections = null
                                    connectionsLoading = true
                                    connectionsRefresh++
                                }
                            }
                        }
                    }
                } finally { saving = false }
            }
        }
    }
    val checkConnection: () -> Unit = {
        val target = selected
        if (canCheck && target != null) {
            checking = true
            actionError = null
            feedback = null
            actionJob = scope.launch {
                try {
                    when (val result = repository.checkProviderConnection(target.id)) {
                        is MediaReadResult.Success -> if (result.scope == repository.scope) {
                            feedback = if (result.value.revision != target.revision) {
                                connections = null
                                connectionsLoading = true
                                connectionsRefresh++
                                "TMDB 连接正常，连接配置已变化，正在刷新。"
                            } else "TMDB 连接正常。"
                            feedbackRevision++
                        }
                        is MediaReadResult.Failure -> if (result.scope == repository.scope) {
                            actionError = if (result.error.status == 422) "TMDB 拒绝了已保存的密钥，请检查或更换后再检测。"
                                else providerProblem(result.error)
                        }
                    }
                } finally { checking = false }
            }
        }
    }

    BackHandler(enabled = saving) { }
    MediaSettingsPage("TMDB 数据源", { if (!saving) onBack() }, footer = {
        Button(onClick = { if (unknownSave) confirmUnknownSave = true else saveCredential() }, enabled = canSave,
            modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(16.dp).heightIn(min = 48.dp),
            shape = MaterialTheme.shapes.medium) { Text(if (saving) "正在保存" else if (unknownSave) "重新保存密钥" else "保存密钥") }
    }) {
        SettingsGroup("TMDB API 密钥") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("输入或粘贴 TMDB API 密钥或读取访问令牌。保存后密钥不会回显。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(key, { key = it; keyRevision++ }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("输入密钥") },
                    isError = key.isNotEmpty() && !valid,
                    supportingText = if (key.isNotEmpty() && !valid)
                        ({ Text("密钥不能包含空白或控制字符，且不能超过 4096 字节。") }) else null,
                    trailingIcon = {
                        FilewayIconButton(onClick = { keyVisible = !keyVisible }) {
                            FilewayIcon(if (keyVisible) R.drawable.ic_visibility_off else R.drawable.ic_visibility,
                                if (keyVisible) "隐藏密钥" else "显示密钥")
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    shape = MaterialTheme.shapes.medium)
            }
        }
        SettingsGroup("服务端状态") {
            when {
                statusLoading -> MediaLoading("正在读取服务端状态")
                statusError != null -> MediaProblemCard(MediaProblem("无法读取服务端状态", providerProblem(statusError!!),
                    if (!saving && !checking) "重新读取" else null), { statusRefresh++ }, Modifier.padding(16.dp))
                status?.credentialStoreAvailable == true -> Text("服务端已启用凭据存储。", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                status?.credentialStoreAvailable == false -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("服务端尚未启用 TMDB 凭据存储。可先输入密钥，启用后重新读取。", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { statusRefresh++ }, enabled = !saving && !checking, modifier = Modifier.heightIn(min = 48.dp)) { Text("重新读取") }
                }
                else -> Text("服务端状态未知，请重新读取。", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
        SettingsGroup("个人连接") {
            when {
                connectionsLoading -> MediaLoading("正在读取完整连接列表")
                connectionsError != null -> MediaProblemCard(MediaProblem("无法读取完整连接列表", providerConnectionsProblem(connectionsError!!),
                    if (!saving && !checking) "重新读取" else null), { connectionsRefresh++ }, Modifier.padding(16.dp))
                ambiguous -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("存在无法识别的 TMDB 连接或连接状态，暂不能保存。请更新客户端或服务端后重新读取。", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { connectionsRefresh++ }, enabled = !saving && !checking, modifier = Modifier.heightIn(min = 48.dp)) { Text("重新读取") }
                }
                connections != null && personal.isEmpty() -> Text("当前账号尚未保存个人连接。", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                personal.size == 1 -> Text("个人连接 · ${providerConnectionState(personal.single().state)}", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                personal.size > 1 -> {
                    Text("选择要更新或检测的个人连接", Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp), style = MaterialTheme.typography.bodyMedium)
                    personal.forEachIndexed { index, item ->
                        FilewaySelectionRow("个人连接 ${index + 1}（${item.id.takeLast(6)}）", subtitle = providerConnectionState(item.state),
                            selected = selectedId == item.id, enabled = !saving && !checking, onClick = { selectedId = item.id })
                    }
                }
                else -> Text("连接状态未知，请重新读取。", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
        SettingsGroup("连接") {
            SettingsAction("检测连接", if (checking) "正在检测" else "使用保存在当前服务器上的密钥", R.drawable.ic_settings,
                enabled = canCheck, navigation = false, action = checkConnection)
        }
        if (saving) MediaLoading("正在保存密钥")
        if (unknownSave) MediaProblemCard(MediaProblem("保存结果待确认",
            "服务器可能已保存密钥。刷新后的连接状态不能证明本次输入是否生效，请先检测连接；再次保存会发起新的操作。"), {})
        actionError?.let { MediaProblemCard(MediaProblem("无法完成操作", it), {}) }
        feedback?.let { FilewayStatusBanner(it, FilewayStatusKind.SUCCESS, onDismiss = { feedback = null }) }
        Text("如果服务器无法访问 TMDB，可在服务端配置 HOST 映射后重新检测。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (confirmUnknownSave) AlertDialog(
        onDismissRequest = { confirmUnknownSave = false },
        title = { Text("再次保存密钥？") },
        text = { Text("上次操作可能已经成功。" + if (selected == null)
            "继续将为当前账号新建个人连接。" else "继续将更新所选个人连接。") },
        confirmButton = { TextButton(onClick = { confirmUnknownSave = false; saveCredential() }, enabled = canSave) { Text("确认保存") } },
        dismissButton = { TextButton(onClick = { confirmUnknownSave = false }) { Text("取消") } },
        shape = MaterialTheme.shapes.medium,
    )
}

private fun providerConnectionState(state: MediaProviderConnectionState): String = when (state) {
    MediaProviderConnectionState.CONFIGURED -> "已保存，尚需检测"
    MediaProviderConnectionState.MISSING -> "尚未保存密钥"
    MediaProviderConnectionState.UNKNOWN -> "状态未知"
}

private fun providerConnectionsProblem(error: MediaFailure): String = if (error.kind == MediaFailureKind.INVALID_RESPONSE) {
    "连接列表未能完整读取或响应无法识别，请检查客户端和服务端版本后重试。"
} else providerProblem(error)

private fun validProviderDraft(value: String): Boolean = value.isNotEmpty() &&
    value.none { it.isWhitespace() || Character.isSpaceChar(it) || it.isISOControl() } &&
    value.toByteArray(Charsets.UTF_8).size <= 4096 && Charsets.UTF_8.newEncoder().canEncode(value)

/** Never infer that a personal connection is absent from a partial page. */
private suspend fun readAllProviderConnections(repository: MediaLibraryRepository): MediaReadResult<List<MediaProviderConnection>> {
    val items = mutableListOf<MediaProviderConnection>()
    val ids = mutableSetOf<String>()
    var cursor: MediaProviderConnectionCursor? = null
    repeat(100) {
        when (val result = repository.providerConnections(limit = 100, cursor = cursor)) {
            is MediaReadResult.Failure -> return result
            is MediaReadResult.Success -> {
                if (result.scope != repository.scope || result.value.items.any { !ids.add(it.id) } ||
                    result.value.nextCursor != null && result.value.items.isEmpty()) {
                    return MediaReadResult.Failure(repository.scope, MediaFailure(MediaFailureKind.INVALID_RESPONSE))
                }
                items.addAll(result.value.items)
                cursor = result.value.nextCursor
                if (cursor == null) return MediaReadResult.Success(repository.scope, items, result.sessionHealth)
            }
        }
    }
    return MediaReadResult.Failure(repository.scope, MediaFailure(MediaFailureKind.INVALID_RESPONSE))
}

@Composable
internal fun PhoneMediaPolicySettings(repository: MediaLibraryRepository, libraryId: String, onBack: () -> Unit, onProvider: () -> Unit,
    onSaved: () -> Unit) {
    val scope = rememberCoroutineScope()
    var policy by remember(repository, libraryId) { mutableStateOf<MediaProviderPolicy?>(null) }
    var mode by remember(repository, libraryId) { mutableStateOf(MediaProviderPolicyMode.LOCAL_ONLY) }
    var language by remember(repository, libraryId) { mutableStateOf("zh-CN") }
    var region by remember(repository, libraryId) { mutableStateOf("CN") }
    var connections by remember(repository, libraryId) { mutableStateOf<List<MediaProviderConnection>?>(null) }
    var connectionsLoading by remember(repository, libraryId) { mutableStateOf(true) }
    var connectionsError by remember(repository, libraryId) { mutableStateOf<MediaFailure?>(null) }
    var connectionsRefresh by remember(repository, libraryId) { mutableIntStateOf(0) }
    var chosen by remember(repository, libraryId) { mutableStateOf<String?>(null) }
    var busy by remember(repository, libraryId) { mutableStateOf(true) }
    var error by remember(repository, libraryId) { mutableStateOf<String?>(null) }
    var pending by remember(repository, libraryId) { mutableStateOf<MediaOperation?>(null) }
    var refresh by remember(repository, libraryId) { mutableIntStateOf(0) }
    var libraryRefreshPreview by remember(repository, libraryId) { mutableStateOf<MediaMetadataRefreshLibraryPreview?>(null) }
    var libraryRefreshBusy by remember(repository, libraryId) { mutableStateOf(false) }
    var libraryRefreshError by remember(repository, libraryId) { mutableStateOf<String?>(null) }
    LaunchedEffect(repository, libraryId, refresh) {
        busy = true
        try {
            pending = repository.pendingMetadataPolicyOperation(libraryId)
            when (val result = repository.metadataPolicy(libraryId)) {
                is MediaReadResult.Success -> if (result.scope == repository.scope) {
                    policy = result.value; mode = result.value.mode; chosen = result.value.connectionId
                    language = result.value.language; region = result.value.region
                    libraryRefreshPreview = null; libraryRefreshError = null
                }
                is MediaReadResult.Failure -> if (result.scope == repository.scope) error = providerProblem(result.error)
            }
        } finally { busy = false }
    }
    LaunchedEffect(repository, libraryId, connectionsRefresh) {
        connectionsLoading = true
        connections = null
        connectionsError = null
        try {
            when (val result = readAllProviderConnections(repository)) {
                is MediaReadResult.Success -> if (result.scope == repository.scope) connections = result.value.filter {
                    it.provider == "tmdb" && it.state == MediaProviderConnectionState.CONFIGURED
                }
                is MediaReadResult.Failure -> if (result.scope == repository.scope) connectionsError = result.error
            }
        } finally { connectionsLoading = false }
    }
    val valid = policy != null && mode != MediaProviderPolicyMode.UNKNOWN && (mode == MediaProviderPolicyMode.LOCAL_ONLY ||
        !connectionsLoading && connections?.any { it.id == chosen } == true)
    val currentPolicy = policy
    val dirty = currentPolicy != null && (mode != currentPolicy.mode || language != currentPolicy.language || region != currentPolicy.region ||
        chosen.takeUnless { mode == MediaProviderPolicyMode.LOCAL_ONLY } != currentPolicy.connectionId)
    fun previewLibraryRefresh() {
        if (busy || libraryRefreshBusy || dirty || currentPolicy == null || mode == MediaProviderPolicyMode.LOCAL_ONLY) return
        libraryRefreshBusy = true; libraryRefreshError = null
        scope.launch {
            try {
                when (val result = repository.previewLibraryMetadataRefresh(libraryId)) {
                    is MediaReadResult.Success -> if (result.scope == repository.scope) libraryRefreshPreview = result.value
                    is MediaReadResult.Failure -> if (result.scope == repository.scope) libraryRefreshError = providerProblem(result.error)
                }
            } finally { libraryRefreshBusy = false }
        }
    }
    BackHandler(enabled = (busy || libraryRefreshBusy) && policy != null) { }
    MediaSettingsPage("资料优先级", { if (!busy && !libraryRefreshBusy) onBack() }, footer = {
        Button(onClick = {
            val original = policy ?: return@Button
            val existing = pending
            val operation = existing ?: repository.newOperation()
            busy = true; error = null
            scope.launch {
                try {
                    val result = if (existing != null) repository.retryOperation(existing) else repository.updateMetadataPolicy(
                        operation, libraryId, original.revision, mode, chosen.takeUnless { mode == MediaProviderPolicyMode.LOCAL_ONLY },
                        language, region, original.allowEditorJobs)
                    when (result) {
                        is MediaWriteResult.Success -> { repository.forgetResolvedOperation(operation); onSaved() }
                        is MediaWriteResult.Failure -> {
                            pending = operation.takeIf { result.error.mutationMayHaveCommitted }
                            error = providerProblem(result.error)
                        }
                    }
                } finally { busy = false }
            }
        }, enabled = !busy && !libraryRefreshBusy && (valid || pending != null), modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(16.dp).heightIn(min = 48.dp),
            shape = MaterialTheme.shapes.medium) { Text(if (pending != null) "确认上次保存" else "保存设置") }
    }) {
        SettingsGroup("资料来源") {
            listOf(MediaProviderPolicyMode.LOCAL_ONLY to "仅本地资料", MediaProviderPolicyMode.LOCAL_FIRST_ONLINE to "本地优先，TMDB 补缺",
                MediaProviderPolicyMode.ONLINE_FIRST to "TMDB 优先").forEach { (value, label) ->
                FilewaySelectionRow(label, selected = mode == value, enabled = !busy && pending == null, onClick = { mode = value })
            }
        }
        SettingsGroup("语言与地区") {
            listOf("zh-CN" to "简体中文", "zh-TW" to "繁体中文", "en-US" to "English", "ja-JP" to "日本語").forEach { (value, label) ->
                FilewaySelectionRow(label, selected = language == value, enabled = !busy && pending == null, onClick = { language = value })
            }
            listOf("CN" to "中国大陆", "TW" to "台湾", "HK" to "香港", "US" to "美国", "JP" to "日本").forEach { (value, label) ->
                FilewaySelectionRow(label, subtitle = "发行地区", selected = region == value, enabled = !busy && pending == null, onClick = { region = value })
            }
        }
        if (mode != MediaProviderPolicyMode.LOCAL_ONLY && policy != null) SettingsGroup("资料语言刷新") {
            Text("预览只统计影响，不会修改资料。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = ::previewLibraryRefresh, enabled = !busy && !libraryRefreshBusy && !dirty,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = MaterialTheme.shapes.medium) {
                Text(if (libraryRefreshBusy) "正在预览…" else "预览资料刷新影响")
            }
            if (dirty) Text("请先保存当前语言、地区或连接设置，再预览影响。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            libraryRefreshError?.let { message -> MediaProblemCard(MediaProblem("无法预览资料刷新", message, "重试"), ::previewLibraryRefresh) }
            libraryRefreshPreview?.let { preview ->
                if (preview.policyRevision != currentPolicy?.revision) Text("资料设置已变化，请重新预览。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            libraryRefreshPreview?.takeIf { !dirty && it.policyRevision == currentPolicy?.revision }?.let { preview ->
                Text("目标资料：${preview.targetLanguage} · ${preview.region}", style = MaterialTheme.typography.bodyMedium)
                Text("已匹配 ${preview.matchedItems} 条；${preview.languageMismatchItems} 条语言不同，${preview.alreadyCurrentItems} 条已是目标语言。",
                    style = MaterialTheme.typography.bodyMedium)
                if (preview.lockedItems > 0 || preview.explicitClearItems > 0) {
                    Text("${preview.lockedItems} 条含人工锁定，${preview.explicitClearItems} 条含显式清空；这些字段会保留。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("保留字段：${formatMetadataFieldCounts(preview.protectedFields)}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (preview.nfoPreservedItems > 0) {
                    Text("${preview.nfoPreservedItems} 条含 NFO 优先字段；本地优先模式下会保留。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("NFO 保留字段：${formatMetadataFieldCounts(preview.preservedFields)}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (preview.fieldChangesRequireItemPreview) Text("具体变化字段请在条目详情中逐条预览。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                when {
                    preview.languageMismatchItems == 0 -> Text("没有需要刷新语言的条目。", style = MaterialTheme.typography.bodyMedium)
                    !preview.canRefresh -> Text("当前 TMDB 连接不可用，请先检查连接。", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error)
                }
            }
        }
        if (mode != MediaProviderPolicyMode.LOCAL_ONLY) SettingsGroup("TMDB 连接") {
            when {
                connectionsLoading -> MediaLoading("正在读取完整连接列表")
                connectionsError != null -> MediaProblemCard(MediaProblem("无法读取完整连接列表", providerConnectionsProblem(connectionsError!!),
                    if (!busy) "重新读取" else null), { connectionsRefresh++ }, Modifier.padding(16.dp))
                connections != null -> {
                    connections.orEmpty().forEachIndexed { index, item ->
                        FilewaySelectionRow(if (item.owner == MediaProviderOwner.USER) "个人连接 ${index + 1}" else "服务器共享连接 ${index + 1}",
                            selected = chosen == item.id, enabled = !busy && pending == null, onClick = { chosen = item.id })
                    }
                    if (connections.orEmpty().isEmpty()) SettingsAction("配置 TMDB", "保存个人密钥后选择连接", R.drawable.ic_settings, enabled = !busy, action = onProvider)
                    else SettingsAction("管理密钥", "保存新密钥或检测连接", R.drawable.ic_settings, enabled = !busy, action = onProvider)
                }
            }
        }
        if (busy) MediaLoading("正在读取或保存设置")
        error?.let { MediaProblemCard(MediaProblem("无法保存设置", it, "刷新"), { error = null; refresh++ }) }
    }
}

internal fun formatMetadataFieldCounts(counts: Map<String, Int>): String =
    counts.entries.sortedBy { it.key }.joinToString("、") { "${it.key} ×${it.value}" }.ifBlank { "无" }

internal fun providerProblem(error: MediaFailure): String = when (error.kind) {
    MediaFailureKind.AUTHENTICATION -> "请重新登录当前服务器。"
    MediaFailureKind.NOT_FOUND, MediaFailureKind.FORBIDDEN -> "当前连接或内容不可访问，请检查账号和服务器版本。"
    MediaFailureKind.CONFLICT, MediaFailureKind.CURSOR_STALE -> "配置或条目已变化，请刷新后重试。"
    MediaFailureKind.NETWORK -> "无法连接服务器，请检查网络后重试。"
    MediaFailureKind.RATE_LIMITED -> "TMDB 请求较多，请稍后再试。"
    else -> if (error.status == 422) "请检查该库的 TMDB 设置与查询内容。" else "请确认服务器已启用 TMDB，再重试。"
}
