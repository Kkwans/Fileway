package io.github.kkwans.nasfilebrowser.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.kkwans.nasfilebrowser.BuildConfig
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.app.ClientState
import io.github.kkwans.nasfilebrowser.app.ClientDestination
import io.github.kkwans.nasfilebrowser.app.ClientMainDestination
import io.github.kkwans.nasfilebrowser.app.destination
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayTopAppBar
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kkwans.nasfilebrowser.data.AppTheme
import io.github.kkwans.nasfilebrowser.data.BackendKind
import io.github.kkwans.nasfilebrowser.data.ConnectionMode
import io.github.kkwans.nasfilebrowser.update.UpdatePolicy
import kotlinx.coroutines.launch

/** Compatibility boundary: every library page now inherits the single application theme. */
@Composable internal fun LibraryTheme(content: @Composable () -> Unit) = content()

@Composable internal fun ClientNavigation(model: ClientModel, selected: String, activeOverride: ClientMainDestination? = null) {
    if (LocalSkipBottomNavigation.current) return
    val state by model.state.collectAsStateWithLifecycle()
    val active = activeOverride ?: state.destination.main ?: ClientDestination.forRoute(selected)?.main
    val navigationPulse = LocalFilewayNavigationPulse.current
    val labelStyle = MaterialTheme.typography.labelMedium
    val barHeight = 68.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp,
        modifier = Modifier.height(barHeight)) {
        listOf(Triple(ClientMainDestination.FILES, "文件", R.drawable.ic_nav_files_077),
            Triple(ClientMainDestination.RECENT, "最近", R.drawable.ic_nav_history_077),
            Triple(ClientMainDestination.LIBRARY, "资料", R.drawable.ic_library),
            Triple(ClientMainDestination.TASKS, "任务", R.drawable.ic_tasks),
            Triple(ClientMainDestination.MY, "我的", R.drawable.ic_person)).forEach { (tab, label, icon) ->
            NavigationBarItem(selected = active == tab, onClick = { navigationPulse?.let { it.value = tab.name to (it.value.second + 1) }; model.navigateMain(tab) },
                modifier = Modifier.semantics { contentDescription = "${label}导航" },
                icon = { FilewayIcon(icon, null, Modifier.size(24.dp), eventKey = if (active == tab && navigationPulse?.value?.first == tab.name) navigationPulse.value.second else 0) }, label = { Text(label, style = labelStyle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                colors = NavigationBarItemDefaults.colors(indicatorColor = Color.Transparent,
                    selectedIconColor = MaterialTheme.colorScheme.primary, selectedTextColor = MaterialTheme.colorScheme.primary,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant, unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun SettingsScreen(model: ClientModel, state: ClientState, openAbout: Boolean = false, onBack: () -> Unit = { model.back() }) {
    var profileDetails by remember { mutableStateOf(false) }
    var licenses by remember { mutableStateOf(false) }
    var about by remember(openAbout) { mutableStateOf(openAbout) }
    fun closeAbout() { about = false; if (openAbout) model.back() }
    var repositoryError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    var themeSheet by remember { mutableStateOf(false) }
    var networkDetails by remember { mutableStateOf(false) }
    val network by model.networkState.collectAsStateWithLifecycle()
    val appearance by model.appearance.state.collectAsStateWithLifecycle()
    val autoPip by model.playbackPreferences.autoPictureInPicture.collectAsStateWithLifecycle()
    val preferenceScope = rememberCoroutineScope()
    Scaffold(containerColor = MaterialTheme.colorScheme.background,
        bottomBar = { ClientNavigation(model, "settings") },
        topBar = { Column(Modifier.statusBarsPadding()) {
            if (state.connected) FilewayPageHeader("我的")
            else FilewayTopAppBar("本机设置", leadingIcon = R.drawable.ic_arrow_back, leadingDescription = "返回连接页面", onLeadingClick = onBack)
        } }) { insets ->
        Box(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = 840.dp).fillMaxSize().semantics { contentDescription = "设置内容" },
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.connected) item {
                    SettingsGroup("当前服务器与账户") {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            FilewayIcon(R.drawable.ic_server, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(state.accountName, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(state.profile?.name.orEmpty(), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        SettingsAction("切换登录", "切换服务器或账号", R.drawable.ic_server, action = model::disconnect)
                        SettingsAction("退出登录", "清除当前登录凭据，保留服务器档案", R.drawable.ic_logout, navigation = false, action = model::signOut)
                        SettingsAction("账户安全", "当前账号与密码", R.drawable.ic_lock) { model.tab("account") }
                        SettingsAction("服务器档案", "${if (state.profile?.backend == BackendKind.WINDOWS) "Windows" else "NAS"} · ${if (state.profile?.network == ConnectionMode.TAILNET) "内嵌 Tailscale" else "直接连接"}", R.drawable.ic_storage) { profileDetails = true }
                    }
                }
                item { SettingsGroup("视频播放") {
                    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FilewayIcon(R.drawable.ic_pip, null, Modifier.size(24.dp))
                        Column(Modifier.weight(1f)) {
                            Text("离开应用时自动画中画", style = MaterialTheme.typography.bodyLarge)
                            Text("仅在视频正在播放时开启", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(autoPip, { enabled -> preferenceScope.launch { try { model.playbackPreferences.saveAutoPictureInPicture(enabled) }
                            catch (error: Exception) { if (error is kotlinx.coroutines.CancellationException) throw error
                                android.widget.Toast.makeText(context, "无法保存画中画设置，请重试", android.widget.Toast.LENGTH_SHORT).show() } } })
                    }
                } }
                item { CacheSettingsPanel(model) { message ->
                    android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
                } }
                item {
                    SettingsGroup("外观与连接") {
                        SettingsAction("主题", when {
                            appearance.loading -> "正在读取"
                            appearance.saving -> "正在保存"
                            !appearance.loaded -> "读取失败"
                            else -> appearance.theme.label()
                        }, R.drawable.ic_visibility, enabled = appearance.loaded && !appearance.saving && !appearance.loading) { themeSheet = true }
                        appearance.error?.let { message ->
                            Text(message, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            if (!appearance.loaded) FilewayTextButton(onClick = model.appearance::reload) { Text("重试读取") }
                        }
                        SettingsAction("应用内 Tailscale", network.label, R.drawable.ic_network) { networkDetails = true }
                    }
                }
                if (state.permissions.admin || state.permissions.execute) item {
                    SettingsGroup("服务器管理") {
                        if (state.permissions.admin) SettingsAction("用户管理", "账号、权限与访问规则", R.drawable.ic_person) { model.tab("admin-users") }
                        if (state.permissions.admin) SettingsAction("服务器全局设置", "影响所有用户的配置与默认策略", R.drawable.ic_storage) { model.tab("server-settings") }
                        if (state.permissions.execute) SettingsAction("服务器命令", "在当前目录运行允许的单条命令", R.drawable.ic_code, action = model::openShell)
                    }
                }
                item {
                    SettingsGroup("关于应用") {
                        SettingsAction("检查更新", "当前版本 ${BuildConfig.VERSION_NAME}", R.drawable.ic_download, action = model::openUpdates)
                        SettingsAction("关于栖卷 · Fileway", "版本、来源、鸣谢与许可证", R.drawable.ic_info) { repositoryError = null; about = true }
                    }
                }
                state.error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
            }
        }
    }
    if (themeSheet) FileOperationSheet("选择主题", { themeSheet = false }, canDismiss = !appearance.saving) {
        Column(Modifier.fillMaxWidth().selectableGroup()) {
            AppTheme.entries.forEach { mode ->
                io.github.kkwans.nasfilebrowser.ui.designsystem.FilewaySelectionRow(mode.label(),
                    selected = appearance.theme == mode, enabled = !appearance.saving) {
                    model.appearance.save(mode); themeSheet = false
                }
            }
        }
    }
    if (networkDetails) FileOperationSheet("应用内 Tailscale", { networkDetails = false }) {
        Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(androidx.compose.foundation.rememberScrollState())) { NetworkSection(model) }
    }
    if (profileDetails) FileOperationSheet("服务器档案", { profileDetails = false }) {
        Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(state.profile?.name.orEmpty()); Text(state.profile?.address.orEmpty()); Text("服务类型：${if (state.profile?.backend == BackendKind.WINDOWS) "Windows" else "NAS"}"); Text("账号：${state.accountName}")
            Text(if (state.profile?.network == ConnectionMode.TAILNET) "连接方式：内嵌 Tailscale" else "连接方式：直接连接")
        }
    }
    if (about) FilewayAboutDialog(::closeAbout)

}

/** One category per card; its actions remain full-width selectable rows. */
@Composable internal fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, Modifier.padding(start = 4.dp), style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "${title}设置分组" }) {
            Column(content = content)
        }
    }
}

@Composable internal fun SettingsAction(title: String, subtitle: String, icon: Int, enabled: Boolean = true, navigation: Boolean = true, action: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = action).heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FilewayIcon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (navigation) FilewayIcon(R.drawable.ic_arrow_forward, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

internal fun AppTheme.label() = when (this) {
    AppTheme.SYSTEM -> "跟随系统"
    AppTheme.LIGHT -> "明色"
    AppTheme.DARK -> "暗色"
}

@Composable internal fun FilewayAboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var repositoryError by remember { mutableStateOf<String?>(null) }
    var licenses by remember { mutableStateOf(false) }
    if (licenses) { FilewayLicensesSheet { licenses = false }; return }
    FileOperationDialog("关于栖卷 · Fileway", onDismiss) {
        Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("版本 ${BuildConfig.VERSION_NAME} · 构建 ${BuildConfig.VERSION_CODE}")
            Text("原生 Android 客户端 · GPL-3.0-or-later", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FilewayTextButton(onClick = {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UpdatePolicy.REPOSITORY))
                        .addCategory(Intent.CATEGORY_BROWSABLE))
                    repositoryError = null
                } catch (_: ActivityNotFoundException) { repositoryError = "没有可打开链接的浏览器" }
                catch (_: SecurityException) { repositoryError = "系统未允许打开浏览器，请稍后重试" }
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .semantics { contentDescription = "打开 GitHub 仓库" }) {
                Text("GitHub · Kkwans/Fileway")
            }
            FilewayTextButton(onClick = { licenses = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("鸣谢与许可证") }
            repositoryError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
