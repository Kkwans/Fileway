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
import androidx.compose.ui.unit.dp
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.data.*
import io.github.kkwans.nasfilebrowser.ui.SettingsAction
import io.github.kkwans.nasfilebrowser.ui.SettingsGroup
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewaySelectionRow
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayTopAppBar
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
    var connection by remember(repository) { mutableStateOf<MediaProviderConnection?>(null) }
    // A credential draft never enters saved instance state or the durable write journal.
    var key by remember(repository) { mutableStateOf("") }
    var busy by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var retry by remember(repository) { mutableStateOf<MediaOperation?>(null) }
    LaunchedEffect(repository, refresh) {
        busy = true
        try {
            when (val result = repository.providerStatus()) {
                is MediaReadResult.Success -> status = result.value
                is MediaReadResult.Failure -> problem = providerProblem(result.error)
            }
            when (val result = repository.providerConnections()) {
                is MediaReadResult.Success -> connection = result.value.items.firstOrNull { it.owner == MediaProviderOwner.USER }
                is MediaReadResult.Failure -> problem = providerProblem(result.error)
            }
        } finally { busy = false }
    }
    val saved = connection?.state == MediaProviderConnectionState.CONFIGURED
    val valid = key.isNotBlank() && key.length <= 4096 && key.none { it.isWhitespace() || it.isISOControl() }
    MediaSettingsPage("TMDB 数据源", onBack, footer = {
        Button(onClick = {
            val operation = retry ?: repository.newOperation()
            val value = key
            val current = connection
            busy = true; problem = null; message = null
            scope.launch {
                try {
                    val result = if (current == null) repository.createPersonalProvider(operation, value)
                        else repository.rotatePersonalProvider(operation, current, value)
                    when (result) {
                        is MediaProviderCredentialResult.Success -> {
                            connection = result.connection; key = ""; retry = null; message = "密钥已保存，可检测连接。"
                        }
                        is MediaProviderCredentialResult.Failure -> {
                            retry = operation.takeIf { result.error.mutationMayHaveCommitted }
                            problem = if (retry != null) "保存结果待确认。可重试同一次保存，或刷新查看当前连接。" else providerProblem(result.error)
                        }
                    }
                } finally { busy = false }
            }
        }, enabled = valid && !busy && status?.credentialStoreAvailable == true,
            modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(16.dp).heightIn(min = 48.dp),
            shape = MaterialTheme.shapes.medium) { Text("保存密钥") }
    }) {
        SettingsGroup("TMDB API 密钥") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (saved) "当前账号已保存密钥" else "当前账号还没有密钥", style = MaterialTheme.typography.bodyLarge)
                OutlinedTextField(key, { key = it; retry = null }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(if (saved) "输入新密钥" else "输入密钥") }, enabled = !busy && status?.credentialStoreAvailable == true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), visualTransformation = PasswordVisualTransformation(),
                    shape = MaterialTheme.shapes.medium)
            }
        }
        SettingsGroup("连接") {
            SettingsAction("检测连接", message ?: "使用保存在当前服务器上的密钥", R.drawable.ic_settings, enabled = saved && !busy, navigation = false) {
                val savedConnection = connection ?: return@SettingsAction
                busy = true; problem = null; message = null
                scope.launch {
                    try {
                        when (val result = repository.checkProviderConnection(savedConnection.id)) {
                            is MediaReadResult.Success -> message = if (result.value.reachable) "连接正常" else "暂时无法连接"
                            is MediaReadResult.Failure -> problem = if (result.error.status == 422) "TMDB 拒绝了当前密钥，请更换后再检测。" else providerProblem(result.error)
                        }
                    } finally { busy = false }
                }
            }
        }
        if (busy) MediaLoading("正在读取或更新连接")
        if (status?.credentialStoreAvailable == false) Text("当前 Fileway 服务端尚未启用 TMDB 凭据存储。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        problem?.let { MediaProblemCard(MediaProblem("无法完成操作", it, "刷新"), { problem = null; key = ""; retry = null; refresh++ }) }
        Text("如果服务器无法访问 TMDB，可在服务端配置 HOST 映射后重新检测。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun PhoneMediaPolicySettings(repository: MediaLibraryRepository, libraryId: String, onBack: () -> Unit, onProvider: () -> Unit,
    onSaved: () -> Unit) {
    val scope = rememberCoroutineScope()
    var policy by remember(repository, libraryId) { mutableStateOf<MediaProviderPolicy?>(null) }
    var mode by remember { mutableStateOf(MediaProviderPolicyMode.LOCAL_ONLY) }
    var connections by remember { mutableStateOf<List<MediaProviderConnection>>(emptyList()) }
    var chosen by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<MediaOperation?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(repository, libraryId, refresh) {
        busy = true
        try {
            pending = repository.pendingMetadataPolicyOperation(libraryId)
            when (val result = repository.metadataPolicy(libraryId)) {
                is MediaReadResult.Success -> { policy = result.value; mode = result.value.mode; chosen = result.value.connectionId }
                is MediaReadResult.Failure -> error = providerProblem(result.error)
            }
            when (val result = repository.providerConnections()) {
                is MediaReadResult.Success -> connections = result.value.items.filter { it.state == MediaProviderConnectionState.CONFIGURED }
                is MediaReadResult.Failure -> error = providerProblem(result.error)
            }
        } finally { busy = false }
    }
    val valid = policy != null && mode != MediaProviderPolicyMode.UNKNOWN && (mode == MediaProviderPolicyMode.LOCAL_ONLY || chosen != null)
    BackHandler(enabled = busy && policy != null) { }
    MediaSettingsPage("资料优先级", { if (!busy) onBack() }, footer = {
        Button(onClick = {
            val original = policy ?: return@Button
            val existing = pending
            val operation = existing ?: repository.newOperation()
            busy = true; error = null
            scope.launch {
                try {
                    val result = if (existing != null) repository.retryOperation(existing) else repository.updateMetadataPolicy(
                        operation, libraryId, original.revision, mode, chosen.takeUnless { mode == MediaProviderPolicyMode.LOCAL_ONLY },
                        original.language, original.region, original.allowEditorJobs)
                    when (result) {
                        is MediaWriteResult.Success -> { repository.forgetResolvedOperation(operation); onSaved() }
                        is MediaWriteResult.Failure -> {
                            pending = operation.takeIf { result.error.mutationMayHaveCommitted }
                            error = providerProblem(result.error)
                        }
                    }
                } finally { busy = false }
            }
        }, enabled = !busy && (valid || pending != null), modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(16.dp).heightIn(min = 48.dp),
            shape = MaterialTheme.shapes.medium) { Text(if (pending != null) "确认上次保存" else "保存设置") }
    }) {
        SettingsGroup("资料来源") {
            listOf(MediaProviderPolicyMode.LOCAL_ONLY to "仅本地资料", MediaProviderPolicyMode.LOCAL_FIRST_ONLINE to "本地优先，TMDB 补缺",
                MediaProviderPolicyMode.ONLINE_FIRST to "TMDB 优先").forEach { (value, label) ->
                FilewaySelectionRow(label, selected = mode == value, enabled = !busy && pending == null, onClick = { mode = value })
            }
        }
        if (mode != MediaProviderPolicyMode.LOCAL_ONLY) SettingsGroup("TMDB 连接") {
            connections.forEachIndexed { index, item ->
                FilewaySelectionRow(if (item.owner == MediaProviderOwner.USER) "个人连接 ${index + 1}" else "服务器共享连接 ${index + 1}",
                    selected = chosen == item.id, enabled = !busy && pending == null, onClick = { chosen = item.id })
            }
            if (connections.isEmpty()) SettingsAction("配置 TMDB", "保存个人密钥后选择连接", R.drawable.ic_settings, enabled = !busy, action = onProvider)
            else SettingsAction("管理密钥", "保存新密钥或检测连接", R.drawable.ic_settings, enabled = !busy, action = onProvider)
        }
        if (busy) MediaLoading("正在读取或保存设置")
        error?.let { MediaProblemCard(MediaProblem("无法保存设置", it, "刷新"), { error = null; refresh++ }) }
    }
}

internal fun providerProblem(error: MediaFailure): String = when (error.kind) {
    MediaFailureKind.AUTHENTICATION -> "请重新登录当前服务器。"
    MediaFailureKind.NOT_FOUND, MediaFailureKind.FORBIDDEN -> "当前连接或内容不可访问，请检查账号和服务器版本。"
    MediaFailureKind.CONFLICT, MediaFailureKind.CURSOR_STALE -> "配置或条目已变化，请刷新后重试。"
    MediaFailureKind.NETWORK -> "无法连接服务器，请检查网络后重试。"
    MediaFailureKind.RATE_LIMITED -> "TMDB 请求较多，请稍后再试。"
    else -> if (error.status == 422) "请检查该库的 TMDB 设置与查询内容。" else "请确认服务器已启用 TMDB，再重试。"
}
