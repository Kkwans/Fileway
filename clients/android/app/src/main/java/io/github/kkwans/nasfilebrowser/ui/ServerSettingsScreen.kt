package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.app.ServerSettingsController
import io.github.kkwans.nasfilebrowser.app.serverSettingNumberValid
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayTopAppBar
import io.github.kkwans.nasfilebrowser.data.*

private enum class GlobalSettingsGroup(val label: String) {
    ACCOUNTS("注册与账户"), DEFAULTS("默认用户"), BRANDING("品牌与外观"), UPLOADS("上传"), COMMANDS("命令与事件"), RULES("全局路径规则")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ServerSettingsScreen(controller: ServerSettingsController, onBack: () -> Unit, onReconnect: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    val draft = state.draft
    var group by remember(state.scope) { mutableStateOf<GlobalSettingsGroup?>(null) }
    val numbers = state.numberDrafts
    val fullPage = group in setOf(GlobalSettingsGroup.DEFAULTS, GlobalSettingsGroup.COMMANDS, GlobalSettingsGroup.RULES)
    val enabled = state.authorized && !state.loading && !state.saving && !state.unknown
    fun validNumber(key: String, value: String) = serverSettingNumberValid(key, value)
    val numbersValid = numbers.all { (key, value) -> validNumber(key, value) }
    if (!fullPage) Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
        Box(Modifier.statusBarsPadding()) {
        FilewayTopAppBar("全局设置", null, R.drawable.ic_arrow_back, "返回设置", { if (!state.saving) onBack() },
            actions = { IconButton(controller::refresh, enabled = !state.loading && !state.saving) { Icon(painterResource(R.drawable.ic_refresh), "刷新全局设置") } })
        }
    }, bottomBar = {
        if (draft != null) Surface {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
                if (!numbersValid) Text("请完成有误的数值输入", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                FilewayButton(controller::requestSave, enabled = enabled && state.dirty && numbersValid,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (state.saving) "正在保存" else "保存全局设置") }
            }
        }
    }) { insets ->
        LazyColumn(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets).semantics { contentDescription = "全局设置内容" },
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Text("设置影响当前服务器。默认用户项仅用于之后创建的账号。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
            if (state.loading || state.saving) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            state.notice?.let { item { Text(it, color = MaterialTheme.colorScheme.primary) } }
            if (state.unknown) item { Column {
                FilewayOutlinedButton(controller::refresh, enabled = !state.loading && !state.saving, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("读取服务器核对") }
                FilewayTextButton(controller::resetToServer, enabled = !state.loading && !state.saving, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("采用服务器设置并重新编辑") }
            } }
            if (!state.authorized && state.error != null) item { FilewayOutlinedButton(onReconnect, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("重新连接") } }
            if (draft != null) {
                item { SettingsGroup("服务器能力") { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("认证方式：" + when (state.settings?.authMethod) { "json" -> "账号密码"; "proxy" -> "代理认证"; "hook" -> "外部认证"; "noauth" -> "无密码认证"; else -> "尚未确认" })
                    Text("认证方式由服务器配置管理。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("命令执行：" + when (state.capabilities?.enableExec) { true -> "已启用"; false -> "未启用"; null -> "尚未确认" })
                    Text("由服务器启动配置控制，此处仅管理 Shell 参数与事件命令。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } } }
                GlobalSettingsGroup.entries.forEach { section -> item {
                    SettingsGroup(section.label) { SettingsAction("编辑${section.label}", when (section) {
                        GlobalSettingsGroup.ACCOUNTS -> "注册、用户目录、密码要求与会话期限"
                        GlobalSettingsGroup.DEFAULTS -> "新账号的目录、权限、列表和交互偏好"
                        GlobalSettingsGroup.BRANDING -> "实例名称、主题、颜色与品牌资源"
                        GlobalSettingsGroup.UPLOADS -> "TUS 分块大小与重试次数"
                        GlobalSettingsGroup.COMMANDS -> "Shell 参数与 ${draft.hooks.size} 类事件命令"
                        GlobalSettingsGroup.RULES -> "${draft.rules.size} 条允许或禁止规则"
                    }, R.drawable.ic_storage, enabled) { group = section } }
                } }
                if (state.dirty && !state.unknown) item { FilewayTextButton(controller::resetToServer, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("放弃设置草稿") } }
            }
        }
    }
    group?.let { selected -> if (draft != null) GlobalSettingsEditor(selected.label, fullPage, !state.saving, { group = null }) {
            item { Text("更改保留在草稿中；返回后点击“保存全局设置”才会提交。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            when (selected) {
                GlobalSettingsGroup.ACCOUNTS -> {
                    item { AdminToggle("允许注册", "开放服务器注册入口", draft.signup, enabled) { value -> controller.edit { it.copy(signup = value) } } }
                    item { AdminToggle("创建用户目录", "为新用户生成独立目录", draft.createUserDir, enabled) { value -> controller.edit { it.copy(createUserDir = value) } } }
                    item { AdminToggle("隐藏登录按钮", "控制网页端登录按钮显示", draft.hideLoginButton, enabled) { value -> controller.edit { it.copy(hideLoginButton = value) } } }
                    item { GlobalText("用户主目录路径", draft.userHomeBasePath, enabled) { value -> controller.edit { it.copy(userHomeBasePath = value) } } }
                    item { GlobalNumber("最小密码长度（字节）", numbers["minimum"] ?: draft.minimumPasswordLength.toString(), enabled, validNumber("minimum", numbers["minimum"] ?: draft.minimumPasswordLength.toString())) { text ->
                        controller.editNumber("minimum", text)
                    } }
                    item { FilewaySessionDurationField(state.scope, numbers["session"] ?: draft.sessionMinutes.toString(), enabled, validNumber("session", numbers["session"] ?: draft.sessionMinutes.toString())) { text ->
                        controller.editNumber("session", text)
                    } }
                }
                GlobalSettingsGroup.DEFAULTS -> {
                    item { Text("只影响新账号的初始值。访问规则请在“全局路径规则”中配置。", style = MaterialTheme.typography.bodySmall) }
                    item { GlobalText("默认用户目录", draft.defaults.scope, enabled) { value -> controller.edit { it.copy(defaults = it.defaults.copy(scope = value)) } } }
                    item { GlobalText("默认界面语言", draft.defaults.locale, enabled, "例如 zh-cn、en") { value -> controller.edit { it.copy(defaults = it.defaults.copy(locale = value)) } } }
                    item { GlobalChoice("默认列表样式", draft.defaults.viewMode, listOf("mosaic" to "网格", "compact-grid" to "紧凑网格", "windows-icons" to "图标", "details" to "详情", "compact-list" to "紧凑列表", "list" to "列表"), enabled) { value -> controller.edit { it.copy(defaults = it.defaults.copy(viewMode = value)) } } }
                    item { GlobalChoice("默认排序", draft.defaults.sortingBy, listOf("" to "服务器默认", "name" to "名称", "size" to "大小", "modified" to "修改时间"), enabled) { value -> controller.edit { it.copy(defaults = it.defaults.copy(sortingBy = value)) } } }
                    item { AdminToggle("升序排列", "关闭时使用降序", draft.defaults.sortingAscending, enabled) { value -> controller.edit { it.copy(defaults = it.defaults.copy(sortingAscending = value)) } } }
                    item { AdminToggle("桌面端单击打开", "网页端桌面列表的默认打开方式", draft.defaults.singleClick, enabled) { value -> controller.edit { it.copy(defaults = it.defaults.copy(singleClick = value)) } } }
                    item { AdminToggle("复制或移动后跳转", "默认进入目标目录", draft.defaults.redirectAfterCopyMove, enabled) { value -> controller.edit { it.copy(defaults = it.defaults.copy(redirectAfterCopyMove = value)) } } }
                    item { AdminToggle("使用绝对日期", "关闭时使用相对时间", draft.defaults.dateFormat, enabled) { value -> controller.edit { it.copy(defaults = it.defaults.copy(dateFormat = value)) } } }
                    item { AdminToggle("隐藏点号文件", "兼容现有隐藏文件默认设置", draft.defaults.hideDotfiles, enabled) { value -> controller.edit { it.copy(defaults = it.defaults.copy(hideDotfiles = value)) } } }
                    item { GlobalText("默认网页编辑器主题", draft.defaults.aceEditorTheme, enabled, "留空使用默认主题") { value -> controller.edit { it.copy(defaults = it.defaults.copy(aceEditorTheme = value)) } } }
                    item { Text("默认权限", style = MaterialTheme.typography.titleMedium)
                        GlobalPermissions(draft.defaults.permissions, enabled) { value -> controller.edit { it.copy(defaults = it.defaults.copy(permissions = value)) } } }
                    item { GlobalText("默认命令白名单", draft.defaults.commands.joinToString(" "), enabled, "命令名以空格分隔") { value -> controller.edit {
                        it.copy(defaults = it.defaults.copy(commands = value.trim().split(Regex("\\s+")).filter(String::isNotEmpty)))
                    } } }
                }
                GlobalSettingsGroup.BRANDING -> {
                    item { GlobalText("实例名称", draft.branding.name, enabled) { value -> controller.edit { it.copy(branding = it.branding.copy(name = value)) } } }
                    item { GlobalText("品牌资源目录", draft.branding.files, enabled) { value -> controller.edit { it.copy(branding = it.branding.copy(files = value)) } } }
                    item { GlobalText("品牌颜色", draft.branding.color, enabled, "例如 #2979ff") { value -> controller.edit { it.copy(branding = it.branding.copy(color = value)) } } }
                    item { GlobalChoice("网页主题", draft.branding.theme, listOf("" to "默认", "light" to "明色", "dark" to "暗色"), enabled) { value -> controller.edit { it.copy(branding = it.branding.copy(theme = value)) } } }
                    item { AdminToggle("禁用外部链接", "控制网页端外部链接", draft.branding.disableExternal, enabled) { value -> controller.edit { it.copy(branding = it.branding.copy(disableExternal = value)) } } }
                    item { AdminToggle("禁用磁盘用量百分比", "隐藏网页端用量百分比", draft.branding.disableUsedPercentage, enabled) { value -> controller.edit { it.copy(branding = it.branding.copy(disableUsedPercentage = value)) } } }
                }
                GlobalSettingsGroup.UPLOADS -> {
                    item { GlobalNumber("TUS 分块大小（字节）", numbers["chunk"] ?: draft.chunkBytes.toString(), enabled, validNumber("chunk", numbers["chunk"] ?: draft.chunkBytes.toString())) { text ->
                        controller.editNumber("chunk", text)
                    } }
                    item { GlobalNumber("重试次数", numbers["retry"] ?: draft.retryCount.toString(), enabled, validNumber("retry", numbers["retry"] ?: draft.retryCount.toString()), "0–65535") { text ->
                        controller.editNumber("retry", text)
                    } }
                }
                GlobalSettingsGroup.COMMANDS -> {
                    item { Text(if (state.capabilities?.enableExec == true) "服务器已启用执行。事件命令会在对应文件操作前后运行。" else "服务器尚未启用执行。这里保存的命令只在启动配置允许时运行。", style = MaterialTheme.typography.bodySmall) }
                    item { GlobalText("Shell 执行参数", draft.shell.joinToString("\n"), enabled, "每行一个参数，第一行是执行程序", false) { value -> controller.edit { it.copy(shell = if (value.isEmpty()) emptyList() else value.split('\n')) } } }
                    draft.hooks.forEach { (key, commands) -> item(key = "hook-$key") {
                        val event = when (key.substringAfter('_')) { "copy" -> "复制"; "delete" -> "删除"; "rename" -> "重命名"; "save" -> "保存"; "upload" -> "上传"; else -> key }
                        val label = if (key.startsWith("before_")) "$event 前" else if (key.startsWith("after_")) "$event 后" else key
                        GlobalText(label, commands.joinToString("\n"), enabled, "每行一条事件命令", false) { value -> controller.edit { it.copy(hooks = it.hooks + (key to if (value.isEmpty()) emptyList() else value.split('\n'))) } }
                    } }
                }
                GlobalSettingsGroup.RULES -> item { GlobalRules(draft.rules, enabled) { value -> controller.edit { it.copy(rules = value) } } }
            }
    } }
    if (state.confirmation) AlertDialog(onDismissRequest = controller::cancelConfirmation, title = { Text("保存全局设置？") },
        text = { Text("将保存当前服务器的配置。默认用户项仅用于新账号；路径规则和事件命令可能影响所有用户。") },
        confirmButton = { FilewayTextButton(controller::confirmSave, enabled = !state.saving, modifier = Modifier.heightIn(min = 48.dp)) { Text("确认保存") } },
        dismissButton = { FilewayTextButton(controller::cancelConfirmation, enabled = !state.saving, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") } })
}

@Composable private fun GlobalSettingsEditor(title: String, fullPage: Boolean, canDismiss: Boolean,
    onBack: () -> Unit, content: LazyListScope.() -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val ime = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    fun back() { if (ime) { keyboard?.hide(); focus.clearFocus() } else if (canDismiss) onBack() }
    if (fullPage) {
        BackHandler { back() }
        Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
            FilewayTopAppBar(title, "全局设置草稿", R.drawable.ic_arrow_back, "保留草稿并返回", ::back)
        }, bottomBar = {
            Surface { FilewayTextButton(::back, enabled = canDismiss, modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding().heightIn(min = 48.dp)) { Text("保留草稿并返回") } }
        }) { insets ->
            LazyColumn(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets), contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }
    } else FileOperationSheet(title, onBack, canDismiss = canDismiss, footer = {
        FilewayTextButton(onBack, enabled = canDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("保留草稿并返回") }
    }) {
        LazyColumn(Modifier.weight(1f, fill = false), contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable private fun GlobalText(label: String, value: String, enabled: Boolean, hint: String = "", singleLine: Boolean = true, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, label = { Text(label) }, enabled = enabled, singleLine = singleLine,
        supportingText = if (hint.isEmpty()) null else ({ Text(hint) }), minLines = if (singleLine) 1 else 3, modifier = Modifier.fillMaxWidth())
}
@Composable private fun GlobalNumber(label: String, value: String, enabled: Boolean, valid: Boolean, hint: String = "", decimal: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(value, { if (it.length <= 24) onChange(it) }, label = { Text(label) }, enabled = enabled, singleLine = true, isError = !valid,
        supportingText = if (hint.isEmpty()) null else ({ Text(hint) }), keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number), modifier = Modifier.fillMaxWidth())
}
@Composable private fun GlobalChoice(label: String, value: String, choices: List<Pair<String, String>>, enabled: Boolean, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val options = if (choices.any { it.first == value }) choices else choices + (value to value)
    Box { FilewayOutlinedButton({ open = true }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(label, Modifier.weight(1f)); Text(options.first { it.first == value }.second) }
        FilewayMenu(open, { open = false }, items = options.map { (key, text) ->
            FilewayMenuEntry(key, text, selected = key == value)
        }, selection = FilewayMenuSelection.Single) { item -> open = false; onChange(item.key) }
    }
}
@Composable private fun GlobalPermissions(value: ManagedPermissions, enabled: Boolean, onChange: (ManagedPermissions) -> Unit) {
    AdminToggle("管理员", "新账号默认拥有管理能力", value.admin, enabled) { onChange(value.administrator(it)) }
    AdminToggle("创建文件和文件夹", "默认允许创建和上传", value.create, enabled && !value.admin) { onChange(value.copy(create = it)) }
    AdminToggle("重命名或移动", "默认允许修改位置与名称", value.rename, enabled && !value.admin) { onChange(value.copy(rename = it)) }
    AdminToggle("编辑文件", "默认允许修改文件内容", value.modify, enabled && !value.admin) { onChange(value.copy(modify = it)) }
    AdminToggle("删除文件和文件夹", "默认允许删除资源", value.delete, enabled && !value.admin) { onChange(value.copy(delete = it)) }
    AdminToggle("下载", if (value.share) "已有分享授权依赖下载，此处保留原授权" else "默认允许获取文件内容", value.download, enabled && !value.admin && !value.share) { onChange(value.copy(download = it)) }
    AdminToggle("执行命令", "还需启动配置和命令白名单允许", value.execute, enabled && !value.admin) { onChange(value.copy(execute = it)) }
}
@Composable private fun GlobalRules(values: List<ManagedRule>, enabled: Boolean, onChange: (List<ManagedRule>) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("按顺序应用于所有用户。正则语法由服务器验证。", style = MaterialTheme.typography.bodySmall)
        values.forEachIndexed { index, rule -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("规则 ${index + 1}", style = MaterialTheme.typography.titleSmall)
            AdminToggle("允许访问", "关闭时禁止匹配路径", rule.allow, enabled) { value -> onChange(values.mapIndexed { i, old -> if (i == index) old.copy(allow = value) else old }) }
            AdminToggle("使用正则", "关闭时匹配路径及其子目录", rule.regex, enabled) { value -> onChange(values.mapIndexed { i, old -> if (i == index) old.copy(regex = value) else old }) }
            GlobalText(if (rule.regex) "Go 正则表达式" else "匹配路径", if (rule.regex) rule.expression else rule.path, enabled) { value -> onChange(values.mapIndexed { i, old ->
                if (i != index) old else if (old.regex) old.copy(expression = value) else old.copy(path = value)
            }) }
            Row {
                FilewayTextButton({ onChange(values.toMutableList().apply { add(index - 1, removeAt(index)) }) }, enabled = enabled && index > 0, modifier = Modifier.heightIn(min = 48.dp)) { Text("上移") }
                FilewayTextButton({ onChange(values.toMutableList().apply { add(index + 1, removeAt(index)) }) }, enabled = enabled && index < values.lastIndex, modifier = Modifier.heightIn(min = 48.dp)) { Text("下移") }
                FilewayTextButton({ onChange(values.filterIndexed { i, _ -> i != index }) }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("移除规则") }
            }
            HorizontalDivider()
        } }
        FilewayOutlinedButton({ onChange(values + ManagedRule(path = "/")) }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("添加规则") }
    }
}
