package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.app.AccountSettingsController

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun AccountSettingsScreen(controller: AccountSettingsController, onBack: () -> Unit, onReconnect: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    var passwordOpen by remember(state.scope) { mutableStateOf(false) }
    DisposableEffect(controller, state.scope) {
        onDispose { if (!controller.state.value.passwordUnknown && !controller.state.value.saving) controller.clearPasswordDraft() }
    }
    Scaffold(containerColor = MaterialTheme.colorScheme.surfaceContainer, topBar = {
        TopAppBar(title = { Text("账户设置") }, navigationIcon = {
            IconButton(onBack) { Icon(painterResource(R.drawable.ic_arrow_back), "返回设置") }
        }, actions = { IconButton(controller::refresh, enabled = !state.loading && !state.saving) {
            Icon(painterResource(R.drawable.ic_refresh), "刷新账户资料")
        } })
    }) { insets ->
        LazyColumn(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets).semantics { contentDescription = "账户设置内容" },
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(state.profile?.username ?: "当前账号", style = MaterialTheme.typography.titleLarge)
                Text("管理当前服务器账号的登录安全。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } }
            if (state.loading || state.saving) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            state.error?.let { message -> item { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(message, color = MaterialTheme.colorScheme.error)
                if (state.passwordUnknown) OutlinedButton(onReconnect, Modifier.heightIn(min = 48.dp)) { Text("重新连接核对") }
                else TextButton(controller::refresh, enabled = !state.loading && !state.saving, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("重新读取资料")
                }
            } } }
            state.notice?.let { item { Text(it, color = MaterialTheme.colorScheme.primary) } }
            if (state.profile != null) {
                item { SettingsGroup("账户安全") {
                    if (state.canChangePassword) SettingsAction("修改密码", "单独确认，不会更改其他账户设置", R.drawable.ic_lock,
                        !state.saving && !state.loading && !state.passwordUnknown) { passwordOpen = true }
                    else Text(when {
                        state.capabilities == null -> "账户认证能力尚未确认"
                        state.capabilities?.passwordChangesAvailable == false -> "服务器使用无密码认证"
                        else -> "密码已由管理员锁定"
                    }, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } }
            }
        }
    }
    if (passwordOpen) ModalBottomSheet(onDismissRequest = {
        if (!state.saving) { passwordOpen = false; if (!state.passwordUnknown) controller.clearPasswordDraft() }
    }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth().imePadding().semantics { contentDescription = "密码修改内容" }, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("修改密码", style = MaterialTheme.typography.titleLarge) }
            item { Text("至少 ${state.capabilities?.minimumPasswordLength ?: "—"} 字节；密码只用于本次提交。", style = MaterialTheme.typography.bodySmall) }
            if (state.capabilities?.currentPasswordRequired == true) item {
                AccountPasswordField("当前密码", state.password.current, !state.saving && !state.passwordUnknown) { value -> controller.editPassword { it.copy(current = value) } }
            }
            item { AccountPasswordField("新密码", state.password.replacement, !state.saving && !state.passwordUnknown) { value -> controller.editPassword { it.copy(replacement = value) } } }
            item { AccountPasswordField("确认新密码", state.password.confirmation, !state.saving && !state.passwordUnknown) { value -> controller.editPassword { it.copy(confirmation = value) } } }
            state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            state.notice?.let { item { Text(it, color = MaterialTheme.colorScheme.primary) } }
            item { Button(controller::savePassword, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = state.canChangePassword && !state.loading && !state.saving && !state.passwordUnknown) { Text(if (state.saving) "正在修改" else "确认修改密码") } }
            if (state.passwordUnknown) item {
                OutlinedButton(onReconnect, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("重新连接核对") }
                TextButton(controller::clearPasswordDraft, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("清除密码草稿") }
            }
        }
    }
}

@Composable private fun AccountPasswordField(label: String, value: String, enabled: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true, enabled = enabled,
        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = label })
}
