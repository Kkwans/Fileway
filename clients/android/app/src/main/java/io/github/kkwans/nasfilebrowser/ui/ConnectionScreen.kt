package io.github.kkwans.nasfilebrowser.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayStatusBanner
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayStatusKind
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.app.ClientState
import io.github.kkwans.nasfilebrowser.data.*
import io.github.kkwans.nasfilebrowser.ui.adaptive.LocalFilewayWindowLayout

@Composable internal fun ConnectionScreen(model: ClientModel, state: ClientState) {
    val network by model.networkState.collectAsStateWithLifecycle()
    LibraryTheme { ConnectionForm(model, state, network) }
}

@Composable internal fun ConnectionForm(model: ClientModel, state: ClientState, network: io.github.kkwans.nasfilebrowser.core.NetworkState) {
    val profiles by model.profiles.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    fun hasLocalAccess() = Build.VERSION.SDK_INT < 37 || context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED
    var localAccess by remember { mutableStateOf(hasLocalAccess()) }
    var accessDenied by rememberSaveable { mutableStateOf(false) }
    var accessMessage by remember { mutableStateOf<String?>(null) }
    var afterPermission by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        localAccess = granted
        accessDenied = !granted
        val next = afterPermission; afterPermission = null
        // Permission denial must not block a public service that needs no LAN
        // access. Local connections will report their real network error.
        next?.invoke()
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) localAccess = hasLocalAccess() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    var name by rememberSaveable(state.editorVersion) { mutableStateOf(state.profile?.name.orEmpty()) }
    var backend by rememberSaveable(state.editorVersion) { mutableStateOf(state.profile?.backend ?: BackendKind.NAS) }
    var url by rememberSaveable(state.editorVersion) { mutableStateOf(state.profile?.address.orEmpty()) }
    var username by rememberSaveable(state.editorVersion) { mutableStateOf(state.accountName) }
    // Passwords never enter saved instance state or acceptance artifacts.
    var password by remember(state.editorVersion) { mutableStateOf("") }
    var visiblePassword by remember { mutableStateOf(false) }
    val loginPreferences = remember(context) { context.getSharedPreferences("login-preferences", android.content.Context.MODE_PRIVATE) }
    var keepLogin by rememberSaveable { mutableStateOf(loginPreferences.getBoolean("keep-login", true)) }
    var mode by rememberSaveable(state.editorVersion) { mutableStateOf(if (state.profile?.network == ConnectionMode.TAILNET) "tailnet" else "direct") }
    var remove by remember { mutableStateOf<ServerProfile?>(null) }
    var profilesOpen by remember { mutableStateOf(false) }
    var optionsOpen by rememberSaveable(state.editorVersion) { mutableStateOf(state.profile?.backend == BackendKind.WINDOWS) }
    val profileName = name.trim().ifBlank { Uri.parse(url).host.orEmpty() }
    val canSave = !state.busy && url.isNotBlank() && profileName.isNotBlank()
    val canConnect = canSave && username.isNotBlank() && password.isNotEmpty() && (mode == "direct" || network.connected)
    val missing = when {
        url.isBlank() -> "填写服务器地址后继续"
        username.isBlank() -> "填写服务器账号后继续"
        password.isEmpty() -> "填写密码，或使用已保存的账号"
        mode == "tailnet" && !network.connected -> "先连接 Tailscale，再登录服务器"
        else -> "使用服务器账号登录；保存档案不会登录"
    }
    // A login may publish its saved account before the first directory is ready.
    // Freeze that optional section for the busy episode so fields never jump.
    val savedAccounts = remember(state.editorVersion, state.busy) { state.accounts }
    val unchanged = state.profile?.let { it.address == url.trim() && it.backend == backend && (it.network == ConnectionMode.TAILNET) == (mode == "tailnet") } == true
    val connect = {
        if (canConnect) {
            focus.clearFocus()
            val server = url.trim(); val user = username; val secret = password; val networkMode = mode; val serverType = backend
            val rememberLogin = keepLogin
            val action = { model.connectDraft(profileName, server, serverType, user, secret, networkMode, rememberPassword = rememberLogin) }
            if (Build.VERSION.SDK_INT >= 37 && networkMode == "direct" && !hasLocalAccess() && !accessDenied) {
                afterPermission = action
                permission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
            } else action()
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { insets ->
        Box(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets), contentAlignment = Alignment.TopCenter) {
            val page = Modifier.widthIn(max = 560.dp).fillMaxWidth()
                .imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp)
            val windowLayout = LocalFilewayWindowLayout.current
            val form: @Composable (Boolean) -> Unit = { showProfilePicker ->
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (showProfilePicker) Box(Modifier.fillMaxWidth()) {
                        FilewayOutlinedButton(onClick = { focus.clearFocus(); profilesOpen = true }, enabled = !state.busy, shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics { contentDescription = "选择服务器档案" }) {
                            FilewayIcon(R.drawable.ic_storage, null, Modifier.size(22.dp))
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp), horizontalAlignment = Alignment.Start) {
                                Text(state.profile?.name ?: "新建服务器", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(state.profile?.let { "${if (it.backend == BackendKind.WINDOWS) "Windows" else "NAS"} · ${serverAddressSummary(it.address)}" }
                                    ?: if (profiles.isEmpty()) "填写下方地址与账号" else "选择已保存的服务器档案", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            FilewayIcon(R.drawable.ic_arrow_forward, null, Modifier.size(18.dp))
                        }
                        FilewayMenu(profilesOpen, { profilesOpen = false }, items = buildList {
                            add(FilewayMenuEntry("new", "新建服务器", R.drawable.ic_add, selected = state.profile == null))
                            profiles.forEach { profile ->
                                add(FilewayMenuEntry("profile/${profile.id}", profile.name, R.drawable.ic_storage,
                                    selected = profile.id == state.profile?.id,
                                    description = "${if (profile.backend == BackendKind.WINDOWS) "Windows" else "NAS"} · ${serverAddressSummary(profile.address)} · ${if (profile.network == ConnectionMode.TAILNET) "Tailscale" else "直连"}"))
                            }
                            if (state.profile != null) add(FilewayMenuEntry("remove", "移除当前档案", R.drawable.ic_trash, destructive = true, selectable = false))
                        }, selection = FilewayMenuSelection.Single) { item ->
                            profilesOpen = false
                            when (item.key) {
                                "new" -> model.selectProfile(null)
                                "remove" -> remove = state.profile
                                else -> profiles.firstOrNull { "profile/${it.id}" == item.key }?.let(model::selectProfile)
                            }
                        }
                    }
                    ConnectionSection("服务器", actions = {
                        FilewayTextButton(onClick = { focus.clearFocus(); optionsOpen = !optionsOpen }, enabled = !state.busy, shape = MaterialTheme.shapes.medium) {
                            Text(if (optionsOpen) "收起连接选项" else "更多连接选项")
                        }
                    }) {
                        ConnectionField(url, { url = it }, "服务器地址", R.drawable.ic_link, enabled = !state.busy,
                            placeholder = "https://nas.example.com:8080", keyboardType = KeyboardType.Uri)
                        if (optionsOpen) {
                            ConnectionField(name, { name = it }, "档案名称（选填）", R.drawable.ic_storage, enabled = !state.busy, placeholder = "留空时使用 IP 或域名")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("服务类型", style = MaterialTheme.typography.bodySmall)
                                FilterChip(selected = backend == BackendKind.NAS, onClick = { backend = BackendKind.NAS }, enabled = !state.busy, label = { Text("NAS") })
                                FilterChip(selected = backend == BackendKind.WINDOWS, onClick = { backend = BackendKind.WINDOWS }, enabled = !state.busy, label = { Text("Windows") })
                            }
                            FilewayOutlinedButton(onClick = { focus.clearFocus(); model.saveDraft(profileName, url.trim(), backend, mode) },
                                enabled = canSave, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("仅保存服务器档案") }
                            Text("档案只保存地址与连接方式，账号在登录后保存。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ConnectionMode("直接连接", R.drawable.ic_storage, mode == "direct", Modifier.weight(1f), !state.busy) { focus.clearFocus(); mode = "direct" }
                            ConnectionMode("Tailscale", R.drawable.ic_network, mode == "tailnet", Modifier.weight(1f), !state.busy) { focus.clearFocus(); mode = "tailnet" }
                        }
                    }
                    if (mode == "tailnet") NetworkSection(model, network)
                    if (Build.VERSION.SDK_INT >= 37 && !localAccess) {
                        Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium) {
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("本地网络访问", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                    FilewayTextButton(onClick = { afterPermission = null; permission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK) }) { Text("允许访问") }
                                    if (accessDenied) FilewayTextButton(onClick = {
                                        try { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }
                                        catch (_: Exception) { accessMessage = "无法打开设置，请在系统应用设置中允许本地网络访问。" }
                                    }) { Text("打开设置") }
                                }
                                if (accessDenied) Text("连接本地服务器需要允许访问局域网。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                accessMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                    ConnectionSection("服务账号") {
                        if (savedAccounts.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            savedAccounts.forEach { account -> key(account.key) {
                                val canRestore = !state.busy && unchanged && (mode == "direct" || network.connected)
                                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
                                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                                        .clickable(enabled = canRestore, role = Role.Button) {
                                            focus.clearFocus()
                                            val action = { model.restore(account) }
                                            if (Build.VERSION.SDK_INT >= 37 && mode == "direct" && !hasLocalAccess() && !accessDenied) {
                                                afterPermission = action; permission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
                                            } else action()
                                        }.semantics { contentDescription = "使用已保存账号${account.username}连接" }
                                        .alpha(if (canRestore) 1f else .38f).padding(end = 12.dp, top = 4.dp, bottom = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                                            FilewayIcon(R.drawable.ic_person, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Text(account.username, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text("已保存", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        FilewayIcon(R.drawable.ic_arrow_forward, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            } }
                        }
                        ConnectionField(username, { username = it }, "账号", R.drawable.ic_person, enabled = !state.busy)
                        ConnectionField(password, { password = it }, "密码", R.drawable.ic_lock, enabled = !state.busy,
                            keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, onDone = connect,
                            visualTransformation = if (visiblePassword) VisualTransformation.None else PasswordVisualTransformation(),
                            trailing = {
                                FilewayIconButton(onClick = { visiblePassword = !visiblePassword }) {
                                    FilewayIcon(if (visiblePassword) R.drawable.ic_visibility_off else R.drawable.ic_visibility,
                                        if (visiblePassword) "隐藏密码" else "显示密码")
                                }
                            })
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(keepLogin, enabled = !state.busy, role = Role.Checkbox) {
                            keepLogin = it; loginPreferences.edit().putBoolean("keep-login", it).apply()
                        }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(keepLogin, onCheckedChange = null, enabled = !state.busy)
                            Column(Modifier.padding(start = 8.dp)) {
                                Text("保持登录", style = MaterialTheme.typography.bodyMedium)
                                Text("在本机加密保存密码，过期后自动恢复", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    state.error?.let { message ->
                        FilewayStatusBanner(message, FilewayStatusKind.ERROR)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (state.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text(if (state.busy) state.stage.ifBlank { "正在连接" } else missing, Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (state.busy) FilewayTextButton(onClick = model::cancel, shape = MaterialTheme.shapes.medium) { Text("取消") }
                        }
                        FilewayButton(onClick = connect, enabled = canConnect, shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(if (state.busy) "正在连接服务器" else "连接服务器")
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
            if (windowLayout.useDualPane) {
                Row(
                    Modifier
                        .widthIn(max = 1040.dp)
                        .fillMaxSize()
                        .imePadding()
                        .padding(horizontal = 32.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(32.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Left brand & saved server profiles column
                    Column(
                        Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Surface(
                                modifier = Modifier.size(48.dp),
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    FilewayIcon(R.drawable.ic_server, null, Modifier.size(26.dp), tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                            Column {
                                Text("栖卷 · Fileway", style = MaterialTheme.typography.headlineSmall)
                                Text("本地优先的 NAS 与桌面文件工作台", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Text("已保存服务器档案", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (profiles.isEmpty()) {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surface,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    "还没有已保存的服务器档案。在右侧填写服务器地址与账号即可连接并自动保存。",
                                    modifier = Modifier.padding(16.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                profiles.forEach { profile ->
                                    val selected = profile.id == state.profile?.id
                                    Surface(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable(enabled = !state.busy, role = Role.Button) {
                                                focus.clearFocus()
                                                model.selectProfile(profile)
                                            },
                                        shape = RoundedCornerShape(14.dp),
                                        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.11f) else MaterialTheme.colorScheme.surface,
                                        border = BorderStroke(
                                            if (selected) 1.5.dp else 1.dp,
                                            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                                        ),
                                    ) {
                                        Row(
                                            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        ) {
                                            FilewayIcon(
                                                R.drawable.ic_storage,
                                                null,
                                                Modifier.size(22.dp),
                                                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                Text(profile.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                Text(
                                                    "${if (profile.backend == BackendKind.WINDOWS) "Windows" else "NAS"} · ${serverAddressSummary(profile.address)} · ${if (profile.network == ConnectionMode.TAILNET) "Tailscale" else "直连"}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                            if (selected) {
                                                FilewayIconButton(onClick = { remove = profile }, enabled = !state.busy) {
                                                    FilewayIcon(R.drawable.ic_trash, "移除档案", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        FilewayOutlinedButton(
                            onClick = { focus.clearFocus(); model.selectProfile(null) },
                            enabled = !state.busy && state.profile != null,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                        ) {
                            FilewayIcon(R.drawable.ic_add, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("新建服务器档案")
                        }
                    }
                    // Right bounded 440dp form card
                    Surface(
                        modifier = Modifier.width(440.dp),
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)),
                    ) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(20.dp),
                        ) {
                            form(false)
                        }
                    }
                }
            } else Column(page) { form(true) }
        }
    }
    remove?.let { profile -> FileOperationSheet("移除 ${profile.name}？", { remove = null }, footer = {
        FilewayTextButton({ remove = null }) { Text("取消") }
        FilewayTextButton({ remove = null; model.removeProfile(profile) }) { Text("移除", color = MaterialTheme.colorScheme.error) }
    }) { Text("将移除本机档案和保存的登录信息。服务器文件不会被删除。", style = MaterialTheme.typography.bodyMedium) } }
}

@Composable private fun ConnectionSection(title: String, actions: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            actions()
        }
        content()
    }
}

@Composable private fun ConnectionMode(title: String, icon: Int, selected: Boolean, modifier: Modifier, enabled: Boolean, choose: () -> Unit) {
    Surface(modifier.selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = choose),
        shape = MaterialTheme.shapes.medium, color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f) else MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilewayIcon(icon, null, Modifier.size(18.dp), tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable private fun ConnectionField(
    value: String, change: (String) -> Unit, label: String, icon: Int, enabled: Boolean,
    placeholder: String = "", supporting: String? = null, keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next, onDone: () -> Unit = {}, visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(value, change, label = { Text(label, fontSize = 13.sp) }, enabled = enabled, singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium,
        leadingIcon = { FilewayIcon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        trailingIcon = trailing, placeholder = if (placeholder.isEmpty()) null else ({ Text(placeholder) }),
        supportingText = if (supporting == null) null else ({ Text(supporting) }),
        visualTransformation = visualTransformation, keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { onDone() }), shape = MaterialTheme.shapes.medium,
        colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = MaterialTheme.colorScheme.surface, focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth())
}

/** Keep port and base path: identical hosts can represent different servers. */
internal fun serverAddressSummary(address: String): String {
    val uri = Uri.parse(address)
    val host = uri.host ?: return address
    val port = if (uri.port >= 0) ":${uri.port}" else ""
    val path = uri.path.orEmpty().takeUnless { it == "/" }.orEmpty()
    return "$host$port$path"
}
