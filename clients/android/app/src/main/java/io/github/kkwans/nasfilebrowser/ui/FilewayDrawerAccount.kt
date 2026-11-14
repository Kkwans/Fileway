package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.app.ClientState
import io.github.kkwans.nasfilebrowser.data.ConnectionMode
import io.github.kkwans.nasfilebrowser.data.SessionHealthCode
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayTheme

/** One account entry, with session actions owned by the existing My/settings page. */
@Composable
internal fun FilewayDrawerAccount(
    state: ClientState,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val connected = state.connected
    val account = if (connected) state.accountName.ifBlank { "当前账号" } else "尚未连接"
    // Never fall back to an address or a stale profile after the session is closed.
    val server = if (connected) state.profile?.name?.takeIf { it.isNotBlank() } ?: "当前服务器" else "本机设置"
    val status = when {
        !connected -> "外观与缓存管理"
        state.recoveryInProgress -> "正在恢复登录"
        state.health?.code == SessionHealthCode.EXPIRED -> "登录已过期"
        state.health?.code == SessionHealthCode.ACCOUNT_CHANGED -> "登录账号待确认"
        state.health?.code == SessionHealthCode.TOKEN_STORAGE_FAILED -> "登录状态暂未保存"
        state.health?.code == SessionHealthCode.RENEWAL_FAILED -> "登录续期暂不可用"
        else -> "已连接 · ${if (state.profile?.network == ConnectionMode.TAILNET) "Tailscale" else "直接连接"}"
    }
    val statusColor = when {
        !connected -> colors.onSurfaceVariant
        state.health != null || state.recoveryInProgress -> FilewayTheme.status.warning
        else -> FilewayTheme.status.success
    }
    FilewayGlassSection(modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
                .clickable(enabled = enabled, role = Role.Button, onClickLabel = "打开我的设置", onClick = onClick)
                .semantics { selected = state.tab == "settings" }
                .heightIn(min = 48.dp).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(
                    modifier = Modifier.size(48.dp),
                    shape = CircleShape,
                    color = if (connected) colors.primaryContainer else colors.surfaceContainerHighest,
                    contentColor = if (connected) colors.onPrimaryContainer else colors.onSurfaceVariant,
                ) {
                    FilewayIcon(R.drawable.ic_person, null, Modifier.padding(12.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("我的", style = MaterialTheme.typography.labelLarge,
                        color = if (state.tab == "settings") colors.primary else colors.onSurfaceVariant)
                    Text(account, style = MaterialTheme.typography.titleLarge, color = colors.onSurface,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                FilewayIcon(R.drawable.ic_arrow_forward, null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
            }
            HorizontalDivider(color = colors.outlineVariant.copy(alpha = .65f))
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilewayIcon(if (connected) R.drawable.ic_server else R.drawable.ic_settings, null,
                    Modifier.padding(top = 2.dp).size(20.dp), tint = colors.onSurfaceVariant)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(server, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(status, style = MaterialTheme.typography.bodySmall, color = statusColor)
                }
            }
        }
    }
}
