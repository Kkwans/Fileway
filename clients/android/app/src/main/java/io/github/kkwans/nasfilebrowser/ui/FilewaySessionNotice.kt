package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.data.SessionHealthCode

@Composable
internal fun FilewaySessionNotice(model: ClientModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val health = state.health ?: return
    val needsInput = state.recoveryNeedsPassword || health.code == SessionHealthCode.ACCOUNT_CHANGED
    var recoveryDialog by remember(state.previewScope) { mutableStateOf(false) }
    var password by remember(state.previewScope) { mutableStateOf("") }
    var inputError by remember(state.previewScope) { mutableStateOf<String?>(null) }
    var submitted by remember(state.previewScope) { mutableStateOf(false) }
    val message = when (health.code) {
        SessionHealthCode.TOKEN_STORAGE_FAILED -> "操作结果已保留，登录状态暂未保存"
        SessionHealthCode.RENEWAL_FAILED -> "登录续期暂不可用，当前结果已保留"
        SessionHealthCode.EXPIRED -> if (needsInput) "登录已过期，请输入密码恢复" else "登录已过期，可恢复后重试刚才的操作"
        SessionHealthCode.ACCOUNT_CHANGED -> "登录账号发生变化，请输入密码确认"
    }
    val action: () -> Unit = if (needsInput) {
        { recoveryDialog = true; inputError = null; submitted = false }
    } else model::retrySessionRecovery
    val actionLabel = when {
        state.recoveryInProgress -> "正在恢复"
        needsInput -> "输入密码"
        health.code == SessionHealthCode.TOKEN_STORAGE_FAILED -> "重试保存"
        health.code == SessionHealthCode.RENEWAL_FAILED -> "重试续期"
        else -> "恢复登录"
    }
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                message,
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            FilewayTextButton(
                onClick = action,
                enabled = !state.recoveryInProgress,
                modifier = Modifier.heightIn(min = 48.dp),
                shape = RoundedCornerShape(12.dp),
            ) { Text(actionLabel) }
        }
    }
    if (recoveryDialog) {
        val dialogError = inputError ?: state.recoveryError.takeIf { submitted }
        FilewayAlertDialog(
            onDismissRequest = {
                if (!state.recoveryInProgress) {
                    recoveryDialog = false
                    password = ""
                    inputError = null
                    submitted = false
                }
            },
            shape = RoundedCornerShape(12.dp),
            title = { Text("恢复当前登录") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("请输入当前绑定账号的密码。当前页面和未完成草稿会保留。", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it; inputError = null },
                        enabled = !state.recoveryInProgress,
                        singleLine = true,
                        isError = dialogError != null,
                        label = { Text("密码") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    )
                    dialogError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                FilewayButton(
                    onClick = {
                        if (password.isEmpty()) inputError = "请输入密码"
                        else {
                            inputError = null
                            submitted = true
                            model.recoverSessionWithPassword(password)
                        }
                    },
                    enabled = password.isNotEmpty() && !state.recoveryInProgress,
                    shape = RoundedCornerShape(12.dp),
                ) { Text(if (state.recoveryInProgress) "正在恢复" else "恢复登录") }
            },
            dismissButton = {
                FilewayTextButton(
                    onClick = {
                        recoveryDialog = false
                        password = ""
                        inputError = null
                        submitted = false
                    },
                    enabled = !state.recoveryInProgress,
                    shape = RoundedCornerShape(12.dp),
                ) { Text("取消") }
            },
        )
    }
}
