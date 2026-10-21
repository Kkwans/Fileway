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
            val form: @Composable () -> Unit = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.fillMaxWidth()) {
                        FilewayOutlinedButton(onClick = { focus.clearFocus(); profilesOpen = true }, enabled = !state.busy, shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics { contentDescription = "选择服务器档案" }) {
                            Icon(painterResource(R.drawable.ic_storage), null, Modifier.size(22.dp))
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp), horizontalAlignment = Alignment.Start) {
                                Text(state.profile?.name ?: "新建服务器", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(state.profile?.let { "${if (it.backend == BackendKind.WINDOWS) "Windows" else "NAS"} · ${serverAddressSummary(it.address)}" }
                                    ?: if (profiles.isEmpty()) "填写下方地址与账号" else "选择已保存的服务器档案", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Icon(painterResource(R.drawable.ic_arrow_forward), null, Modifier.size(18.dp))
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
                        if (savedAccounts.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("已保存的账号", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                savedAccounts.forEach { account -> FilewayTextButton(onClick = {
                                    focus.clearFocus()
                                    val action = { model.restore(account) }
                                    if (Build.VERSION.SDK_INT >= 37 && mode == "direct" && !hasLocalAccess() && !accessDenied) { afterPermission = action; permission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK) } else action()
                                }, enabled = !state.busy && unchanged && (mode == "direct" || network.connected)) { Text(account.username) } }
                            }
                        }
                        ConnectionField(username, { username = it }, "账号", R.drawable.ic_person, enabled = !state.busy)
                        ConnectionField(password, { password = it }, "密码", R.drawable.ic_lock, enabled = !state.busy,
                            keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, onDone = connect,
                            visualTransformation = if (visiblePassword) VisualTransformation.None else PasswordVisualTransformation(),
                            trailing = {
                                IconButton(onClick = { visiblePassword = !visiblePassword }) {
                                    Icon(painterResource(if (visiblePassword) R.drawable.ic_visibility_off else R.drawable.ic_visibility),
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
            Column(page) { form() }
        }
    }
    remove?.let { profile -> AlertDialog(onDismissRequest = { remove = null }, title = { Text("移除 ${profile.name}？") }, text = { Text("将移除本机档案和保存的登录信息。服务器文件不会被删除。") }, confirmButton = { FilewayTextButton(onClick = { remove = null; model.removeProfile(profile) }) { Text("移除") } }, dismissButton = { FilewayTextButton(onClick = { remove = null }) { Text("取消") } }) }
}

@Composable private fun ConnectionSection(title: String, actions: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
            Icon(painterResource(icon), null, Modifier.size(18.dp), tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
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
        leadingIcon = { Icon(painterResource(icon), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
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
