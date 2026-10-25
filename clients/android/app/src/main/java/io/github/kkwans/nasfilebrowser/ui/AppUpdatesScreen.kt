package io.github.kkwans.nasfilebrowser.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kkwans.nasfilebrowser.BuildConfig
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.update.*
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayTopAppBar

@Composable internal fun AppUpdatesScreen(model: ClientModel) {
    val context = LocalContext.current
    val updates = model.updates
    val state by updates.state.collectAsStateWithLifecycle()
    val history by updates.history.collectAsStateWithLifecycle()
    LaunchedEffect(state.update?.release?.tag) { updates.loadHistory() }
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
        }, select = updates::select, history = history, retryHistory = { updates.loadHistory(force = true) })
}

/** Inherits the native settings palette and hierarchy; release notes stay readable. */
@Composable internal fun AppUpdateContent(state: UpdateState, back: () -> Unit, check: () -> Unit, download: () -> Unit,
    cancel: () -> Unit, install: () -> Unit, select: (Long) -> Unit = {}, history: ReleaseHistoryState = ReleaseHistoryState(), retryHistory: () -> Unit = {}) {
    val colors = MaterialTheme.colorScheme
    val update = state.update
    Scaffold(containerColor = colors.background, topBar = {
        Box(Modifier.statusBarsPadding()) { FilewayTopAppBar("应用更新", null, R.drawable.ic_arrow_back, "返回", back) }
    }, bottomBar = {
        Column(Modifier.navigationBarsPadding().fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when (state.phase) {
                UpdatePhase.AVAILABLE -> {
                    FilewayButton(onClick = download, modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(min = 48.dp)) {
                        FilewayIcon(R.drawable.ic_download, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("下载更新 · ${readableSize(update?.asset?.size ?: 0)}")
                    }
                    FilewayTextButton(onClick = check) { Text("重新检查更新") }
                }
                UpdatePhase.READY -> {
                    FilewayButton(onClick = install, modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(min = 48.dp)) { Text("安装更新") }
                    FilewayTextButton(onClick = cancel) { Text("删除安装包") }
                }
                UpdatePhase.FAILED -> {
                    if (update != null) {
                        FilewayButton(onClick = download, modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(min = 48.dp)) { Text("重新下载") }
                        FilewayTextButton(onClick = check) { Text("重新检查更新") }
                    } else FilewayButton(onClick = check, modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(min = 48.dp)) { Text("重试检查") }
                }
                UpdatePhase.DOWNLOADING -> FilewayTextButton(onClick = cancel, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消下载") }
                UpdatePhase.IDLE, UpdatePhase.CURRENT -> FilewayButton(onClick = check, modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(min = 48.dp)) { Text("检查更新") }
                else -> Unit
            }
        }
    }) { insets ->
        Box(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = 720.dp).fillMaxSize().semantics { contentDescription = "应用更新内容" },
                contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item(key = "summary") { Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                UpdateVersionSummary(state)
                when (state.phase) {
                    UpdatePhase.VERIFYING -> Text("正在核对版本、包名和签名", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    UpdatePhase.DOWNLOADING -> {
                        val fraction = if (state.total > 0) (state.bytes.toDouble() / state.total).toFloat().coerceIn(0f, 1f) else 0f
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(if (state.total > 0) "${readableSize(state.bytes)} / ${readableSize(state.total)}" else "已下载 ${readableSize(state.bytes)}", style = MaterialTheme.typography.bodyMedium)
                            if (state.total > 0) Text("${(fraction * 100).toInt()}%", style = MaterialTheme.typography.labelLarge, color = colors.primary)
                        }
                        if (state.total > 0) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "更新下载进度" },
                            trackColor = colors.outlineVariant, gapSize = 0.dp, drawStopIndicator = {})
                        else LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = "更新下载进度" })
                        Text(state.status, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    }
                    UpdatePhase.READY -> Text("将通过系统覆盖安装，已有账号、文件与设置保留。", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    else -> Unit
                }
                state.error?.let { Text(it, color = colors.error, style = MaterialTheme.typography.bodyMedium) }
                if (state.phase == UpdatePhase.IDLE || state.phase == UpdatePhase.CURRENT) {
                    if (state.status.isNotEmpty()) Text(state.status, style = MaterialTheme.typography.bodyMedium)
                }
                if (update != null) {
                    if (state.phase == UpdatePhase.AVAILABLE && state.choices.size > 1) Column(Modifier.selectableGroup()) {
                        state.choices.forEachIndexed { index, choice ->
                            io.github.kkwans.nasfilebrowser.ui.designsystem.FilewaySelectionRow(
                                choice.release.displayVersion,
                                subtitle = "${if (choice.release.preview) "预览版" else "正式版"} · ${readableSize(choice.asset.size)}${if (index == 0) " · 推荐" else ""}",
                                selected = update.asset.id == choice.asset.id,
                            ) { select(choice.asset.id) }
                        }
                    }

                }
                } }
                if (update != null) {
                    val details = if (history.targetTag == update.release.tag) history else ReleaseHistoryState(update.release.tag, listOf(update.release))
                    item(key = "history-title") {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("更新说明", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            var showLoading by remember(details.targetTag) { mutableStateOf(false) }
                            LaunchedEffect(details.loading) { showLoading = false; if (details.loading) { kotlinx.coroutines.delay(350); showLoading = true } }
                            if (showLoading && details.loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        }
                    }
                    if (details.incomplete || details.message != null) item(key = "history-status") {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(details.message ?: "历史说明尚未全部读取，当前显示已获取内容。", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                            FilewayTextButton(retryHistory, enabled = !details.loading) { Text("重试读取说明") }
                        }
                    }
                    items(details.releases, key = { "release/${it.tag}" }) { release -> ReleaseNotesCard(release) }
                }
                item(key = "source") {
                    Text("更新来源：Fileway 官方 GitHub Releases", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
    }
}

/** One balanced comparison replaces the loose title/version/channel stack. */
@Composable private fun UpdateVersionSummary(state: UpdateState) {
    val colors = MaterialTheme.colorScheme
    val update = state.update
    val pending = state.phase in setOf(UpdatePhase.RESTORING, UpdatePhase.CHECKING, UpdatePhase.VERIFYING)
    var showPending by remember { mutableStateOf(false) }
    LaunchedEffect(pending) { showPending = false; if (pending) { kotlinx.coroutines.delay(350); showPending = true } }
    val label = when (state.phase) {
        UpdatePhase.IDLE -> "等待检查"
        UpdatePhase.RESTORING -> "恢复更新"
        UpdatePhase.CHECKING -> "检查更新"
        UpdatePhase.CURRENT -> "已是最新版本"
        UpdatePhase.AVAILABLE -> "发现新版本"
        UpdatePhase.DOWNLOADING -> "正在下载"
        UpdatePhase.VERIFYING -> "正在核对"
        UpdatePhase.READY -> "等待安装"
        UpdatePhase.FAILED -> "更新未完成"
    }
    Surface(Modifier.fillMaxWidth(), color = colors.surface, shape = MaterialTheme.shapes.medium) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("当前版本", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                Text(BuildConfig.VERSION_NAME.substringBefore('-'), style = MaterialTheme.typography.titleLarge)
                Text(if (BuildConfig.VERSION_NAME.contains("preview")) "预览版" else "正式版", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
            Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                if (showPending) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else FilewayIcon(if (state.phase == UpdatePhase.CURRENT) R.drawable.ic_check else R.drawable.ic_arrow_forward, null,
                    Modifier.size(22.dp), tint = colors.onSurfaceVariant)
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, textAlign = TextAlign.End)
                Text(update?.release?.displayVersion ?: if (state.phase == UpdatePhase.CURRENT) BuildConfig.VERSION_NAME.substringBefore('-') else "—",
                    style = MaterialTheme.typography.titleLarge, color = if (update != null) colors.primary else colors.onSurface, textAlign = TextAlign.End)
                if (update != null) Text("${if (update.release.preview) "预览版" else "正式版"} · ${readableSize(update.asset.size)}",
                    style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, textAlign = TextAlign.End)
            }
        }
    }
}
