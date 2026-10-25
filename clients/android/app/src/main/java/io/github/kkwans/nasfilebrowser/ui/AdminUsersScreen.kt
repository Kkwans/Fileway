package io.github.kkwans.nasfilebrowser.ui

import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayTopAppBar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.app.AdminUsersController
import io.github.kkwans.nasfilebrowser.data.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun AdminUsersScreen(controller: AdminUsersController, onBack: () -> Unit, onReconnect: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    val draft = state.draft
    val enabled = state.authorized && !state.loading && !state.saving && !state.unknown
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val ruleKeys = remember(state.scope, draft?.id, draft?.creating) { mutableStateListOf<String>() }
    LaunchedEffect(draft?.rules?.size) {
        while (ruleKeys.size < (draft?.rules?.size ?: 0)) ruleKeys.add(java.util.UUID.randomUUID().toString())
        while (ruleKeys.size > (draft?.rules?.size ?: 0)) ruleKeys.removeAt(ruleKeys.lastIndex)
    }
    fun editorBack() {
        if (imeVisible) { keyboard?.hide(); focus.clearFocus() }
        else if (draft != null && !state.unknown) controller.closeEditor() else onBack()
    }
    BackHandler(draft != null && !state.saving && !state.unknown && state.confirmation == null) { editorBack() }
    DisposableEffect(controller, state.scope) { onDispose { if (!controller.state.value.unknown) controller.clearSecrets() } }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
        Column(Modifier.statusBarsPadding()) {
            FilewayTopAppBar(if (draft == null) "用户管理" else if (draft.creating) "新增用户" else "编辑用户",
                leadingIcon = R.drawable.ic_arrow_back, leadingDescription = if (draft == null) "返回来源页面" else "返回用户列表",
                onLeadingClick = { if (!state.saving) editorBack() }, actions = {
                    if (draft == null) FilewayTextButton(controller::create, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("新增") }
                    FilewayIconButton(controller::refresh, enabled = !state.loading && !state.saving) { FilewayIcon(R.drawable.ic_refresh, "刷新用户列表") }
                })
        }
    }, bottomBar = {
        if (draft != null) Surface {
            Row(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!draft.creating) FilewayOutlinedButton(controller::requestDelete, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("删除用户", color = MaterialTheme.colorScheme.error) }
                FilewayButton(controller::requestSave, enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(if (state.saving) "正在提交" else "保存用户") }
            }
        }
    }) { insets ->
        LazyColumn(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets).semantics { contentDescription = "用户管理内容" },
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (state.loading || state.saving) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            state.notice?.let { item { Text(it, color = MaterialTheme.colorScheme.primary) } }
            if (state.unknown || !state.authorized && state.draft == null && state.notice != null) item {
                FilewayOutlinedButton(onReconnect, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("重新连接核对") }
            }
            if (draft == null) {
                item { FilewaySearchField(state.query, controller::query, "搜索用户名或用户目录", Modifier.fillMaxWidth(), enabled = !state.loading && !state.saving) { focus.clearFocus(); keyboard?.hide() } }
                val rows = state.users.filter { state.query.isBlank() || it.username.contains(state.query, true) || it.scope.contains(state.query, true) }
                if (rows.isEmpty() && !state.loading) item { Text(if (state.query.isBlank()) "暂无可显示的用户" else "没有匹配的用户", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(rows, key = { it.id }) { user ->
                    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
                        Column(Modifier.fillMaxWidth().clickable(enabled = enabled) { controller.select(user.id) }.heightIn(min = 72.dp).padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(user.username, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                Text(if (user.permissions.admin) "管理员" else "普通用户", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            }
                            Text("用户目录：${user.scope.ifEmpty { "/" }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } else {
                item { SettingsGroup("账号") {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(draft.username, { value -> controller.edit { it.copy(username = value) } }, label = { Text("用户名") }, enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(draft.password, { value -> controller.edit { it.copy(password = value) } }, label = { Text(if (draft.creating) "初始密码" else "新密码（留空保持原密码）") },
                            enabled = enabled, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                        if (draft.homeCreationAvailable && draft.creating) AdminToggle("自动创建用户目录", "根据服务器设置生成目录", draft.autoHome, enabled) { value -> controller.edit { it.copy(autoHome = value) } }
                        OutlinedTextField(draft.scope, { value -> controller.edit { it.copy(scope = value) } }, label = { Text("用户目录") },
                            enabled = enabled && !draft.autoHome, singleLine = true, supportingText = { Text(if (draft.autoHome) "创建时由服务器生成用户目录" else "相对于服务器根目录") }, modifier = Modifier.fillMaxWidth())
                        AdminToggle("锁定密码", "普通用户无法自行修改密码", draft.lockPassword, enabled && !draft.permissions.admin) { value -> controller.edit { it.copy(lockPassword = value) } }
                    }
                } }
                item { SettingsGroup("权限") {
                    AdminToggle("管理员", "可管理用户和服务器设置", draft.permissions.admin, enabled) { value -> controller.edit { it.copy(permissions = it.permissions.administrator(value), lockPassword = if (value) false else it.lockPassword) } }
                    AdminToggle("创建文件和文件夹", "允许上传和创建目录", draft.permissions.create, enabled && !draft.permissions.admin) { value -> controller.edit { it.copy(permissions = it.permissions.copy(create = value)) } }
                    AdminToggle("重命名或移动", "允许修改文件位置与名称", draft.permissions.rename, enabled && !draft.permissions.admin) { value -> controller.edit { it.copy(permissions = it.permissions.copy(rename = value)) } }
                    AdminToggle("编辑文件", "允许修改现有文件内容", draft.permissions.modify, enabled && !draft.permissions.admin) { value -> controller.edit { it.copy(permissions = it.permissions.copy(modify = value)) } }
                    AdminToggle("删除文件和文件夹", "允许删除服务器资源", draft.permissions.delete, enabled && !draft.permissions.admin) { value -> controller.edit { it.copy(permissions = it.permissions.copy(delete = value)) } }
                    AdminToggle("下载", if (draft.permissions.share) "已有分享授权依赖下载，此处保留原授权" else "允许获取文件内容", draft.permissions.download,
                        enabled && !draft.permissions.admin && !draft.permissions.share) { value -> controller.edit { it.copy(permissions = it.permissions.copy(download = value)) } }
                    AdminToggle("执行命令", if (state.capabilities?.enableExec == true) "还须符合下方命令白名单" else "服务器尚未启用命令执行", draft.permissions.execute,
                        enabled && !draft.permissions.admin) { value -> controller.edit { it.copy(permissions = it.permissions.copy(execute = value)) } }
                } }
                item { SettingsGroup("命令白名单") {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("填写允许执行的命令名，以空格分隔。服务器未启用执行时不会执行命令。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        var commands by remember(state.scope, draft.id, draft.creating) { mutableStateOf(draft.commands.joinToString(" ")) }
                        OutlinedTextField(commands, { value -> commands = value; controller.edit { it.copy(commands = value.trim().split(Regex("\\s+")).filter(String::isNotEmpty)) } },
                            label = { Text("允许的命令") }, enabled = enabled, modifier = Modifier.fillMaxWidth())
                    }
                } }
                item { SettingsGroup("路径规则") {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("按顺序应用允许或禁止规则。正则语法由服务器验证。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        draft.rules.forEachIndexed { index, rule ->
                            key(ruleKeys.getOrNull(index) ?: "rule-$index") {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("规则 ${index + 1}", style = MaterialTheme.typography.titleSmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(rule.allow, { controller.edit { it.copy(rules = it.rules.mapIndexed { i, old -> if (i == index) old.copy(allow = true) else old }) } }, { Text("允许") }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp))
                                    FilterChip(!rule.allow, { controller.edit { it.copy(rules = it.rules.mapIndexed { i, old -> if (i == index) old.copy(allow = false) else old }) } }, { Text("禁止") }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp))
                                }
                                AdminToggle("使用正则", "关闭时匹配路径及其子目录", rule.regex, enabled) { value -> controller.edit { it.copy(rules = it.rules.mapIndexed { i, old -> if (i == index) old.copy(regex = value) else old }) } }
                                OutlinedTextField(if (rule.regex) rule.expression else rule.path, { value -> controller.edit { it.copy(rules = it.rules.mapIndexed { i, old ->
                                    if (i != index) old else if (old.regex) old.copy(expression = value) else old.copy(path = value)
                                }) } }, label = { Text(if (rule.regex) "Go 正则表达式" else "匹配路径") }, enabled = enabled, modifier = Modifier.fillMaxWidth())
                                Row {
                                    FilewayTextButton({ ruleKeys.add(index - 1, ruleKeys.removeAt(index)); controller.edit { it.copy(rules = it.rules.toMutableList().apply { add(index - 1, removeAt(index)) }) } }, enabled = enabled && index > 0, modifier = Modifier.heightIn(min = 48.dp)) { Text("上移") }
                                    FilewayTextButton({ ruleKeys.add(index + 1, ruleKeys.removeAt(index)); controller.edit { it.copy(rules = it.rules.toMutableList().apply { add(index + 1, removeAt(index)) }) } }, enabled = enabled && index < draft.rules.lastIndex, modifier = Modifier.heightIn(min = 48.dp)) { Text("下移") }
                                    FilewayTextButton({ ruleKeys.removeAt(index); controller.edit { it.copy(rules = it.rules.filterIndexed { i, _ -> i != index }) } }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("移除规则") }
                                }
                                HorizontalDivider()
                            }
                            }
                        }
                        FilewayOutlinedButton({ ruleKeys.add(java.util.UUID.randomUUID().toString()); controller.edit { it.copy(rules = it.rules + ManagedRule(path = "/")) } }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("添加规则") }
                    }
                } }
            }
        }
    }
    state.confirmation?.let { action -> FileOperationSheet(
        if (action == "delete") "删除这个用户？" else if (draft?.creating == true) "创建用户？" else "保存用户更改？",
        onDismiss = controller::cancelConfirmation, canDismiss = !state.saving && !state.unknown,
        footer = {
            FilewayTextButton(controller::cancelConfirmation, enabled = !state.saving) { Text(if (state.unknown) "保留草稿" else "取消") }
            FilewayButton(controller::confirm, enabled = !state.saving && !state.unknown,
                colors = if (action == "delete") ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError) else ButtonDefaults.buttonColors()) {
                Text(if (state.saving) "正在提交" else if (action == "delete") "删除用户" else if (draft?.creating == true) "创建用户" else "保存更改")
            }
        }) {
        Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val originalName = state.users.firstOrNull { it.id == draft?.id }?.username ?: draft?.username.orEmpty()
            Text(if (action == "delete") "将删除账号 $originalName。用户文件不会因此自动删除。" else "请确认用户目录、权限和命令白名单。修改当前账号后需要重新连接。", style = MaterialTheme.typography.bodyMedium)
            if (state.capabilities?.currentPasswordRequired == true) OutlinedTextField(state.currentPassword, controller::currentPassword,
                label = { Text("当前管理员密码") }, singleLine = true, enabled = !state.saving && !state.unknown,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (state.unknown) FilewayTextButton(onReconnect, Modifier.heightIn(min = 48.dp)) { Text("重新连接核对") }
        }
    } }

}

@Composable internal fun AdminToggle(title: String, subtitle: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange).heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.bodyMedium); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}
