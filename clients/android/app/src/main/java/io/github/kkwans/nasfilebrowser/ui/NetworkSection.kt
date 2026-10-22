package io.github.kkwans.nasfilebrowser.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.core.NetworkState

/** The page owns its gutters. This section never adds a nested card or inset. */
@Composable internal fun NetworkSection(model: ClientModel) {
    val network by model.networkState.collectAsStateWithLifecycle()
    NetworkSection(model, network)
}

@Composable internal fun NetworkSection(model: ClientModel, network: NetworkState) {
    val context = LocalContext.current
    var message by remember(network.authUrl) { mutableStateOf<String?>(null) }
    var confirmLogout by remember { mutableStateOf(false) }
    NetworkConnectionContent(network, message, model::connectNetwork, { model.stopNetwork() }, {
        val uri = Uri.parse(network.authUrl)
        val host = uri.host.orEmpty()
        if (uri.scheme != "https" || (host != "tailscale.com" && !host.endsWith(".tailscale.com"))) {
            message = "登录地址无法验证，请重新连接。"
        } else try { context.startActivity(Intent(Intent.ACTION_VIEW, uri)); message = null }
        catch (_: Exception) { message = "无法打开浏览器，可复制登录链接后手动打开。" }
    }, {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Tailscale 登录", network.authUrl)
            clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
            clipboard.setPrimaryClip(clip)
            message = null
            android.widget.Toast.makeText(context, "登录链接已复制，请勿分享。", android.widget.Toast.LENGTH_SHORT).show()
        } catch (_: Exception) { message = "无法复制链接，请检查系统权限后重试。" }
    }, { confirmLogout = true })
    if (confirmLogout) FileOperationSheet("退出 Tailscale？", { confirmLogout = false }, footer = {
        FilewayTextButton({ confirmLogout = false }) { Text("取消") }
        FilewayTextButton({ confirmLogout = false; model.stopNetwork(logout = true) }) { Text("退出账号", color = MaterialTheme.colorScheme.error) }
    }) { Text("将停止当前播放并退出内嵌节点，下次连接需要重新登录。", style = MaterialTheme.typography.bodyMedium) }
}

@Composable internal fun NetworkConnectionContent(
    network: NetworkState, message: String?, start: () -> Unit, stop: () -> Unit,
    openLogin: () -> Unit, copyLogin: () -> Unit, logout: () -> Unit,
) {
    var details by remember { mutableStateOf(false) }
    val active = network.state in listOf("Starting", "NeedsLogin", "NeedsMachineAuth", "Running")
    val canStart = !network.connected && network.authUrl.isEmpty() && network.state !in listOf("Starting", "NeedsMachineAuth")
    val hasDetails = network.ips.isNotEmpty() || network.health.isNotEmpty() || network.acceptSubnets
    val colors = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = colors.surface) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(painterResource(R.drawable.ic_network), null, Modifier.size(22.dp), tint = colors.onSurfaceVariant)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Tailscale", style = MaterialTheme.typography.bodyMedium)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (network.state == "Starting") CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Text(if (network.connected) "网络已连接" else network.label, style = MaterialTheme.typography.bodySmall,
                            color = if (network.connected) colors.primary else colors.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (active) FilewayTextButton(stop) { Text(if (network.connected) "断开" else "取消") }
                if (network.canLogout) IconButton(logout) {
                    Icon(painterResource(R.drawable.ic_logout), "退出 Tailscale 账号", Modifier.size(22.dp))
                }
                if (hasDetails) IconButton({ details = !details }) {
                    Icon(painterResource(if (details) R.drawable.ic_expand_less else R.drawable.ic_info),
                        if (details) "收起网络详情" else "网络详情", Modifier.size(22.dp))
                }
            }
            when {
                network.state == "NeedsMachineAuth" -> Text("请在 Tailscale 管理页面批准这台设备。", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                network.authUrl.isNotEmpty() -> Text("完成网络登录后，再连接服务器。", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                !network.connected && network.state != "Starting" -> Text("先连接远程网络，再登录服务器。", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            if (canStart || network.authUrl.isNotEmpty()) FilewayTonalButton(
                onClick = if (network.authUrl.isNotEmpty()) openLogin else start, enabled = network.state != "Starting",
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("network-primary-action"),
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = colors.primaryContainer, contentColor = colors.onPrimaryContainer),
            ) { Text(if (network.authUrl.isNotEmpty()) "打开登录页" else "连接 Tailscale") }
            if (network.authUrl.isNotEmpty()) FilewayOutlinedButton(copyLogin, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("复制登录链接") }
            network.error?.let { Text(it, color = colors.error, style = MaterialTheme.typography.bodySmall) }
            message?.let { Text(it, color = colors.error, style = MaterialTheme.typography.bodySmall) }
            if (hasDetails && details) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (network.ips.isNotEmpty()) Text(network.ips.joinToString("\n"), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                if (network.connected && network.acceptSubnets) Text("已启用子网路由", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                network.health.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
            }
        }
    }
}
