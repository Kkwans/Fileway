package io.github.kkwans.nasfilebrowser.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kkwans.nasfilebrowser.BuildConfig
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.update.*

@Composable internal fun AppUpdatesScreen(model: ClientModel) {
    val context = LocalContext.current
    val updates = model.updates
    val state by updates.state.collectAsStateWithLifecycle()
    val installer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    fun install() = updates.install { installer.launch(it) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (context.packageManager.canRequestPackageInstalls()) install() else updates.installPermissionDenied()
    }
    AppUpdateContent(state, back = { model.back() }, check = updates::check, download = updates::download, cancel = updates::cancel,
        install = {
            if (context.packageManager.canRequestPackageInstalls()) install()
            else try { permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))) }
            catch (_: Exception) { updates.installPermissionDenied() }
        }, select = updates::select)
}

/** Inherits the native settings palette and hierarchy; release notes stay readable. */
@Composable internal fun AppUpdateContent(state: UpdateState, back: () -> Unit, check: () -> Unit, download: () -> Unit,
    cancel: () -> Unit, install: () -> Unit, select: (Long) -> Unit = {}) {
    val colors = MaterialTheme.colorScheme
    val update = state.update
    Scaffold(containerColor = colors.background, topBar = {
        Row(Modifier.statusBarsPadding().fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) { Icon(painterResource(R.drawable.ic_arrow_back), "返回") }
            Text("应用更新", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        }
    }, bottomBar = {
        Column(Modifier.navigationBarsPadding().fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when (state.phase) {
                UpdatePhase.AVAILABLE -> {
                    Button(onClick = download, modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(min = 48.dp)) {
                        Icon(painterResource(R.drawable.ic_download), null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("下载更新 · ${readableSize(update?.asset?.size ?: 0)}")
                    }
                    TextButton(onClick = check) { Text("重新检查更新") }
                }
                UpdatePhase.READY -> {
                    Button(onClick = install, modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(min = 48.dp)) { Text("安装更新") }
                    TextButton(onClick = cancel) { Text("删除安装包") }
                }
                UpdatePhase.FAILED -> {
                    if (update != null) {
                        Button(onClick = download, modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(min = 48.dp)) { Text("重新下载") }
                        TextButton(onClick = check) { Text("重新检查更新") }
                    } else Button(onClick = check, modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(min = 48.dp)) { Text("重试检查") }
                }
                UpdatePhase.DOWNLOADING -> TextButton(onClick = cancel, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消下载") }
                UpdatePhase.IDLE, UpdatePhase.CURRENT -> Button(onClick = check, modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(min = 48.dp)) { Text("检查更新") }
                else -> Unit
            }
        }
    }) { insets ->
        Box(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)
                .semantics { contentDescription = "应用更新内容" }, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("栖卷 · Fileway", style = MaterialTheme.typography.headlineSmall)
                Text("当前版本 ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                HorizontalDivider(color = colors.outlineVariant)
                Text(when (state.phase) {
                    UpdatePhase.IDLE -> "获取最新 Android 版本"
                    UpdatePhase.RESTORING -> "正在读取更新任务"
                    UpdatePhase.CHECKING -> "正在检查更新"
                    UpdatePhase.CURRENT -> "已是最新版本"
                    UpdatePhase.AVAILABLE -> "发现新版本 ${update?.release?.displayVersion.orEmpty()}"
                    UpdatePhase.DOWNLOADING -> "正在下载 ${update?.release?.displayVersion.orEmpty()}"
                    UpdatePhase.VERIFYING -> "正在核对安装包"
                    UpdatePhase.READY -> "更新已下载，等待安装"
                    UpdatePhase.FAILED -> "更新未完成"
                }, style = MaterialTheme.typography.titleMedium)
                when (state.phase) {
                    UpdatePhase.CHECKING, UpdatePhase.RESTORING, UpdatePhase.VERIFYING -> Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(if (state.phase == UpdatePhase.VERIFYING) "核对版本、包名和签名" else state.status.ifEmpty { "请稍候" }, style = MaterialTheme.typography.bodyMedium)
                    }
                    UpdatePhase.DOWNLOADING -> {
                        val fraction = if (state.total > 0) (state.bytes.toDouble() / state.total).toFloat().coerceIn(0f, 1f) else 0f
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${readableSize(state.bytes)} / ${readableSize(state.total)}", style = MaterialTheme.typography.bodyMedium)
                            Text("${(fraction * 100).toInt()}%", style = MaterialTheme.typography.labelLarge, color = colors.primary)
                        }
                        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "更新下载进度" },
                            trackColor = colors.outlineVariant, gapSize = 0.dp, drawStopIndicator = {})
                        Text(state.status, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    }
                    UpdatePhase.READY -> Text("将通过系统覆盖安装，已有账号、文件与设置保留。", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    else -> Unit
                }
                state.error?.let { Text(it, color = colors.error, style = MaterialTheme.typography.bodyMedium) }
                if (state.phase == UpdatePhase.IDLE || state.phase == UpdatePhase.CURRENT) {
                    Text("检查正式版与预览版，自动选择适合此设备的安装包。", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    if (state.status.isNotEmpty()) Text(state.status, style = MaterialTheme.typography.bodyMedium)
                }
                if (update != null) {
                    if (state.phase == UpdatePhase.AVAILABLE && state.choices.size > 1) Column(Modifier.selectableGroup()) {
                        state.choices.forEachIndexed { index, choice ->
                            Row(Modifier.fillMaxWidth().selectable(selected = update.asset.id == choice.asset.id, role = Role.RadioButton,
                                onClick = { select(choice.asset.id) }).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                RadioButton(selected = update.asset.id == choice.asset.id, onClick = null)
                                Text(choice.release.displayVersion, style = MaterialTheme.typography.bodyLarge)
                                ReleaseLabel(choice.release.preview)
                                if (index == 0) Text("推荐", style = MaterialTheme.typography.labelMedium, color = colors.primary)
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        ReleaseLabel(update.release.preview)
                        Text(readableSize(update.asset.size), style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
                    }
                    Text("更新说明", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                    Text(update.release.notes.ifBlank { "此版本未提供更新说明。" }.replace("**", ""), style = MaterialTheme.typography.bodyMedium)
                }
                Text("更新来源：Fileway 官方 GitHub Releases", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp))
            }
        }
    }
}

@Composable private fun ReleaseLabel(preview: Boolean) {
    Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
        Text(if (preview) "预览版" else "正式版", style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}
