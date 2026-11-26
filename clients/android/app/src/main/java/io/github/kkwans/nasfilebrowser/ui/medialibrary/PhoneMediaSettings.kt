package io.github.kkwans.nasfilebrowser.ui.medialibrary

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.data.*
import io.github.kkwans.nasfilebrowser.ui.FilewayIcon
import io.github.kkwans.nasfilebrowser.ui.FilewayIconButton
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewaySelectionRow
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayStatusBanner
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayStatusKind
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayTopAppBar
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable internal fun MediaSettingsGroup(title: String, subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, Modifier.padding(start = 4.dp).semantics { heading() }, style = PhoneMediaType.itemTitle,
            fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
        if (!subtitle.isNullOrBlank()) Text(subtitle, Modifier.padding(start = 4.dp),
            style = PhoneMediaType.secondary, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(Modifier.fillMaxWidth().semantics { contentDescription = "${title}设置分组" },
            shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(content = content)
        }
    }
}

@Composable internal fun MediaSettingsAction(title: String, subtitle: String, icon: Int,
    enabled: Boolean = true, navigation: Boolean = true, action: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val contentAlpha = if (enabled) 1f else .38f
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = action)
        .heightIn(min = 76.dp).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(42.dp).background(if (enabled) colors.primaryContainer else colors.surfaceContainerHighest,
            MaterialTheme.shapes.small), contentAlignment = Alignment.Center) {
            FilewayIcon(icon, null, Modifier.size(21.dp),
                tint = (if (enabled) colors.onPrimaryContainer else colors.onSurface).copy(alpha = contentAlpha))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = PhoneMediaType.body, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                color = colors.onSurface.copy(alpha = contentAlpha), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = PhoneMediaType.secondary, color = colors.onSurfaceVariant.copy(alpha = contentAlpha),
                maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        if (navigation) FilewayIcon(R.drawable.ic_arrow_forward, null, Modifier.size(20.dp),
            tint = colors.onSurfaceVariant.copy(alpha = contentAlpha))
    }
}

@Composable internal fun MediaSettingsFact(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, Modifier.weight(1f), style = PhoneMediaType.secondary,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = PhoneMediaType.action, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable internal fun MediaSettingsChip(label: String, selected: Boolean, enabled: Boolean = true,
    onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, enabled = enabled, label = { Text(label) },
        modifier = Modifier.heightIn(min = 44.dp), shape = MaterialTheme.shapes.medium)
}

@Composable
internal fun PhoneMediaSettings(onBack: () -> Unit, onLibraries: () -> Unit, onProvider: () -> Unit) {
    MediaSettingsPage("影视设置", onBack) {
        Text("管理媒体库与 TMDB 资料。扫描在服务器后台执行，源文件保持只读。",
            style = PhoneMediaType.secondary, color = MaterialTheme.colorScheme.onSurfaceVariant)
        MediaSettingsGroup("媒体库") {
            MediaSettingsAction("管理媒体库", "添加目录、扫描与查看识别结果",
                R.drawable.ic_library, action = onLibraries)
        }
        MediaSettingsGroup("资料源") {
            MediaSettingsAction("TMDB 数据源", "密钥、连接检测与默认中文资料",
                R.drawable.ic_settings, action = onProvider)
        }
        MediaSettingsGroup("识别规则") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("自动匹配", style = PhoneMediaType.body, fontWeight = FontWeight.SemiBold)
                Text("结合片名、别名、年份、真实片长、目录结构与唯一候选；只有证据冲突时才需要确认。",
                    style = PhoneMediaType.secondary, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
                MediaSettingsFact("电影最低时长", "60 分钟")
                MediaSettingsFact("电视剧单集最低时长", "10 分钟")
                Text("可在每个媒体库的“资料优先级”中调整。", style = PhoneMediaType.secondary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text("源视频、NFO 与图片不会被改名、移动或回写。",
            Modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
            style = PhoneMediaType.secondary, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MediaSettingsPage(title: String, onBack: () -> Unit, footer: @Composable () -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding()) {
        FilewayTopAppBar(title, leadingIcon = R.drawable.ic_arrow_back, leadingDescription = "返回", onLeadingClick = onBack)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            val pageInset = phoneMediaGridSpec(maxWidth).pageInset
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(pageInset),
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
    val checkConnection: () -> Unit = check@{
        if (!canCheck) return@check
        val target = selected
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

    BackHandler(enabled = saving) { }
    MediaSettingsPage("TMDB 数据源", { if (!saving) onBack() }, footer = {
        Button(onClick = { if (unknownSave) confirmUnknownSave = true else saveCredential() }, enabled = canSave,
            modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(16.dp).heightIn(min = 48.dp),
            shape = MaterialTheme.shapes.medium) { Text(if (saving) "正在保存" else if (unknownSave) "重新保存密钥" else "保存密钥", style = PhoneMediaType.action) }
    }) {
        MediaSettingsGroup("TMDB API 密钥") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("输入或粘贴 TMDB API 密钥或读取访问令牌。保存后密钥不会回显。", style = PhoneMediaType.secondary,
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
        MediaSettingsGroup("服务端状态") {
            when {
                statusLoading -> MediaLoading("正在读取服务端状态")
                statusError != null -> MediaProblemCard(MediaProblem("无法读取服务端状态", providerProblem(statusError!!),
                    if (!saving && !checking) "重新读取" else null), { statusRefresh++ }, Modifier.padding(16.dp))
                status?.credentialStoreAvailable == true -> Text("服务端已启用凭据存储。", Modifier.padding(16.dp), style = PhoneMediaType.body)
                status?.credentialStoreAvailable == false -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("服务端尚未启用 TMDB 凭据存储。可先输入密钥，启用后重新读取。", style = PhoneMediaType.body)
                    TextButton(onClick = { statusRefresh++ }, enabled = !saving && !checking, modifier = Modifier.heightIn(min = 48.dp)) { Text("重新读取", style = PhoneMediaType.action) }
                }
                else -> Text("服务端状态未知，请重新读取。", Modifier.padding(16.dp), style = PhoneMediaType.body)
            }
        }
        MediaSettingsGroup("个人连接") {
            when {
                connectionsLoading -> MediaLoading("正在读取完整连接列表")
                connectionsError != null -> MediaProblemCard(MediaProblem("无法读取完整连接列表", providerConnectionsProblem(connectionsError!!),
                    if (!saving && !checking) "重新读取" else null), { connectionsRefresh++ }, Modifier.padding(16.dp))
                ambiguous -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("存在无法识别的 TMDB 连接或连接状态，暂不能保存。请更新客户端或服务端后重新读取。", style = PhoneMediaType.body)
                    TextButton(onClick = { connectionsRefresh++ }, enabled = !saving && !checking, modifier = Modifier.heightIn(min = 48.dp)) { Text("重新读取", style = PhoneMediaType.action) }
                }
                connections != null && personal.isEmpty() -> Text("当前账号尚未保存个人连接。", Modifier.padding(16.dp), style = PhoneMediaType.body)
                personal.size == 1 -> Text("个人连接 · ${providerConnectionState(personal.single().state)}", Modifier.padding(16.dp), style = PhoneMediaType.body)
                personal.size > 1 -> {
                    Text("选择要更新或检测的个人连接", Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp), style = PhoneMediaType.body)
                    personal.forEachIndexed { index, item ->
                        FilewaySelectionRow("个人连接 ${index + 1}（${item.id.takeLast(6)}）", subtitle = providerConnectionState(item.state),
                            selected = selectedId == item.id, enabled = !saving && !checking, onClick = { selectedId = item.id })
                    }
                }
                else -> Text("连接状态未知，请重新读取。", Modifier.padding(16.dp), style = PhoneMediaType.body)
            }
        }
        MediaSettingsGroup("连接") {
            MediaSettingsAction("检测连接", if (checking) "正在检测" else "使用保存在当前服务器上的密钥", R.drawable.ic_settings,
                enabled = canCheck, navigation = false, action = checkConnection)
        }
        if (saving) MediaLoading("正在保存密钥")
        if (unknownSave) MediaProblemCard(MediaProblem("保存结果待确认",
            "服务器可能已保存密钥。刷新后的连接状态不能证明本次输入是否生效，请先检测连接；再次保存会发起新的操作。"), {})
        actionError?.let { MediaProblemCard(MediaProblem("无法完成操作", it), {}) }
        feedback?.let { FilewayStatusBanner(it, FilewayStatusKind.SUCCESS, onDismiss = { feedback = null }) }
        Text("如果服务器无法访问 TMDB，可在服务端配置 HOST 映射后重新检测。", style = PhoneMediaType.secondary, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (confirmUnknownSave) AlertDialog(
        onDismissRequest = { confirmUnknownSave = false },
        title = { Text("再次保存密钥？") },
        text = { Text("上次操作可能已经成功。" + if (selected == null)
            "继续将为当前账号新建个人连接。" else "继续将更新所选个人连接。") },
        confirmButton = { TextButton(onClick = { confirmUnknownSave = false; saveCredential() }, enabled = canSave) { Text("确认保存", style = PhoneMediaType.action) } },
        dismissButton = { TextButton(onClick = { confirmUnknownSave = false }) { Text("取消", style = PhoneMediaType.action) } },
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
    var movieMinutesText by remember(repository, libraryId) { mutableStateOf("60") }
    var seriesMinutesText by remember(repository, libraryId) { mutableStateOf("10") }
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
                    movieMinutesText = result.value.movieMinMinutes.toString()
                    seriesMinutesText = result.value.seriesMinMinutes.toString()
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
    val movieMinutes = movieMinutesText.toIntOrNull()
    val seriesMinutes = seriesMinutesText.toIntOrNull()
    val durationValid = movieMinutes != null && seriesMinutes != null && movieMinutes in 1..1440 && seriesMinutes in 1..1440
    val valid = durationValid && policy != null && mode != MediaProviderPolicyMode.UNKNOWN && (mode == MediaProviderPolicyMode.LOCAL_ONLY ||
        !connectionsLoading && connections?.any { it.id == chosen } == true)
    val currentPolicy = policy
    val dirty = currentPolicy != null && (mode != currentPolicy.mode || language != currentPolicy.language || region != currentPolicy.region ||
        movieMinutes != currentPolicy.movieMinMinutes || seriesMinutes != currentPolicy.seriesMinMinutes ||
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
            val movieMinimum = movieMinutes ?: return@Button
            val seriesMinimum = seriesMinutes ?: return@Button
            val existing = pending
            val operation = existing ?: repository.newOperation()
            busy = true; error = null
            scope.launch {
                try {
                    val result = if (existing != null) repository.retryOperation(existing) else repository.updateMetadataPolicy(
                        operation, libraryId, original.revision, mode, chosen.takeUnless { mode == MediaProviderPolicyMode.LOCAL_ONLY },
                        language, region, original.allowEditorJobs, movieMinimum, seriesMinimum)
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
            shape = MaterialTheme.shapes.medium) { Text(if (pending != null) "确认上次保存" else "保存设置", style = PhoneMediaType.action) }
    }) {
        MediaSettingsGroup("资料来源", "决定本地资料与 TMDB 的使用顺序") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(MediaProviderPolicyMode.LOCAL_ONLY to "仅本地资料",
                        MediaProviderPolicyMode.LOCAL_FIRST_ONLINE to "本地优先，TMDB 补缺",
                        MediaProviderPolicyMode.ONLINE_FIRST to "TMDB 优先").forEach { (value, label) ->
                        MediaSettingsChip(label, mode == value, !busy && pending == null) { mode = value }
                    }
                }
                Text(when (mode) {
                    MediaProviderPolicyMode.LOCAL_ONLY -> "只使用目录中已有资料，不发送 TMDB 匹配请求。"
                    MediaProviderPolicyMode.LOCAL_FIRST_ONLINE -> "优先保留本地资料，缺失字段由 TMDB 补全。"
                    MediaProviderPolicyMode.ONLINE_FIRST -> "优先采用 TMDB 资料，人工锁定与显式清空字段仍会保留。"
                    MediaProviderPolicyMode.UNKNOWN -> "当前资料策略未知，请重新读取后再选择。"
                }, style = PhoneMediaType.secondary, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        MediaSettingsGroup("语言与地区", "旧资料刷新也使用这里的目标语言") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("资料语言", style = PhoneMediaType.secondary, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("zh-CN" to "简体中文", "zh-TW" to "繁体中文", "en-US" to "English", "ja-JP" to "日本語")
                            .forEach { (value, label) ->
                                MediaSettingsChip(label, language == value, !busy && pending == null) { language = value }
                            }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("发行地区", style = PhoneMediaType.secondary, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("CN" to "中国大陆", "TW" to "台湾", "HK" to "香港", "US" to "美国", "JP" to "日本")
                            .forEach { (value, label) ->
                                MediaSettingsChip(label, region == value, !busy && pending == null) { region = value }
                            }
                    }
                }
            }
        }
        MediaSettingsGroup("刮削时长过滤") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("低于阈值的视频不会发送 TMDB 匹配请求；无法读取时长时进入待复核。", style = PhoneMediaType.secondary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(movieMinutesText, { value -> if (value.length <= 4 && value.all(Char::isDigit)) movieMinutesText = value },
                    Modifier.fillMaxWidth(), singleLine = true, label = { Text("电影最低时长（分钟）") },
                    isError = movieMinutesText.isEmpty() || (movieMinutes ?: 0) !in 1..1440,
                    supportingText = { Text(if (movieMinutesText.isEmpty() || (movieMinutes ?: 0) !in 1..1440) "请输入 1–1440 分钟" else "默认 60 分钟", style = PhoneMediaType.secondary) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = MaterialTheme.shapes.medium)
                OutlinedTextField(seriesMinutesText, { value -> if (value.length <= 4 && value.all(Char::isDigit)) seriesMinutesText = value },
                    Modifier.fillMaxWidth(), singleLine = true, label = { Text("电视剧单集最低时长（分钟）") },
                    isError = seriesMinutesText.isEmpty() || (seriesMinutes ?: 0) !in 1..1440,
                    supportingText = { Text(if (seriesMinutesText.isEmpty() || (seriesMinutes ?: 0) !in 1..1440) "请输入 1–1440 分钟" else "默认 10 分钟", style = PhoneMediaType.secondary) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = MaterialTheme.shapes.medium)
            }
        }
        if (mode != MediaProviderPolicyMode.LOCAL_ONLY && policy != null) MediaSettingsGroup("资料语言刷新") {
            Text("预览只统计影响，不会修改资料。", style = PhoneMediaType.secondary,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = ::previewLibraryRefresh, enabled = !busy && !libraryRefreshBusy && !dirty,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = MaterialTheme.shapes.medium) {
                Text(if (libraryRefreshBusy) "正在预览…" else "预览资料刷新影响", style = PhoneMediaType.action)
            }
            if (dirty) Text("请先保存当前语言、地区或连接设置，再预览影响。",
                style = PhoneMediaType.secondary, color = MaterialTheme.colorScheme.onSurfaceVariant)
            libraryRefreshError?.let { message -> MediaProblemCard(MediaProblem("无法预览资料刷新", message, "重试"), ::previewLibraryRefresh) }
            libraryRefreshPreview?.let { preview ->
                if (preview.policyRevision != currentPolicy?.revision) Text("资料设置已变化，请重新预览。",
                    style = PhoneMediaType.secondary, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            libraryRefreshPreview?.takeIf { !dirty && it.policyRevision == currentPolicy?.revision }?.let { preview ->
                Text("目标资料：${preview.targetLanguage} · ${preview.region}", style = PhoneMediaType.body)
                Text("已匹配 ${preview.matchedItems} 条；${preview.languageMismatchItems} 条需要刷新，${preview.alreadyCurrentItems} 条已是目标资料。",
                    style = PhoneMediaType.body)
                if (preview.episodeMetadataItems > 0) Text("其中 ${preview.episodeMetadataItems} 条剧集需要补全中文分集资料。",
                    style = PhoneMediaType.secondary, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (preview.lockedItems > 0 || preview.explicitClearItems > 0) {
                    Text("${preview.lockedItems} 条含人工锁定，${preview.explicitClearItems} 条含显式清空；这些字段会保留。",
                        style = PhoneMediaType.secondary, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("保留字段：${formatMetadataFieldCounts(preview.protectedFields)}",
                        style = PhoneMediaType.secondary, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (preview.nfoPreservedItems > 0) {
                    Text("${preview.nfoPreservedItems} 条含 NFO 优先字段；本地优先模式下会保留。",
                        style = PhoneMediaType.secondary, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("NFO 保留字段：${formatMetadataFieldCounts(preview.preservedFields)}",
                        style = PhoneMediaType.secondary, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (preview.fieldChangesRequireItemPreview) Text("具体变化字段请在条目详情中逐条预览。",
                    style = PhoneMediaType.secondary, color = MaterialTheme.colorScheme.onSurfaceVariant)
                when {
                    preview.languageMismatchItems == 0 -> Text("没有需要刷新的条目。", style = PhoneMediaType.body)
                    !preview.canRefresh -> Text("当前 TMDB 连接不可用，请先检查连接。", style = PhoneMediaType.body,
                        color = MaterialTheme.colorScheme.error)
                }
            }
            PhoneMediaRefreshJobSection(repository, libraryId, libraryRefreshPreview?.takeIf { !dirty && it.policyRevision == currentPolicy?.revision }, dirty,
                enabled = !busy && !libraryRefreshBusy && pending == null)
        }
        if (mode != MediaProviderPolicyMode.LOCAL_ONLY) MediaSettingsGroup("TMDB 连接") {
            when {
                connectionsLoading -> MediaLoading("正在读取完整连接列表")
                connectionsError != null -> MediaProblemCard(MediaProblem("无法读取完整连接列表", providerConnectionsProblem(connectionsError!!),
                    if (!busy) "重新读取" else null), { connectionsRefresh++ }, Modifier.padding(16.dp))
                connections != null -> {
                    connections.orEmpty().forEachIndexed { index, item ->
                        FilewaySelectionRow(if (item.owner == MediaProviderOwner.USER) "个人连接 ${index + 1}" else "服务器共享连接 ${index + 1}",
                            selected = chosen == item.id, enabled = !busy && pending == null, onClick = { chosen = item.id })
                    }
                    if (connections.orEmpty().isEmpty()) MediaSettingsAction("配置 TMDB", "保存个人密钥后选择连接", R.drawable.ic_settings, enabled = !busy, action = onProvider)
                    else MediaSettingsAction("管理密钥", "保存新密钥或检测连接", R.drawable.ic_settings, enabled = !busy, action = onProvider)
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
