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
    val labelStyle = MaterialTheme.typography.labelMedium
    val barHeight = 68.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp,
        modifier = Modifier.height(barHeight)) {
        listOf(Triple(ClientMainDestination.FILES, "文件", R.drawable.ic_nav_files_077),
            Triple(ClientMainDestination.RECENT, "最近", R.drawable.ic_nav_history_077),
            Triple(ClientMainDestination.LIBRARY, "资料", R.drawable.ic_library),
            Triple(ClientMainDestination.TASKS, "任务", R.drawable.ic_tasks),
            Triple(ClientMainDestination.MY, "我的", R.drawable.ic_person)).forEach { (tab, label, icon) ->
            NavigationBarItem(selected = active == tab, onClick = { model.navigateMain(tab) },
                modifier = Modifier.semantics { contentDescription = "${label}导航" },
                icon = { Icon(painterResource(icon), null, Modifier.size(26.dp)) }, label = { Text(label, style = labelStyle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
    Scaffold(containerColor = MaterialTheme.colorScheme.background,
        bottomBar = { ClientNavigation(model, "settings") },
        topBar = { Column(Modifier.statusBarsPadding()) {
            FilewayPageHeader("我的")
        } }) { insets ->
        Box(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = 840.dp).fillMaxSize().semantics { contentDescription = "设置内容" },
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.connected) item {
                    SettingsGroup("当前服务器与账户") {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(painterResource(R.drawable.ic_server), null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(state.accountName, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(state.profile?.name.orEmpty(), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        SettingsAction("切换登录", "切换服务器或账号", R.drawable.ic_server, action = model::disconnect)
                        SettingsAction("退出登录", "清除当前登录凭据，保留服务器档案", R.drawable.ic_logout, action = model::signOut)
                        SettingsAction("账户安全", "当前账号与密码", R.drawable.ic_lock) { model.tab("account") }
                        SettingsAction("服务器档案", "${if (state.profile?.backend == BackendKind.WINDOWS) "Windows" else "NAS"} · ${if (state.profile?.network == ConnectionMode.TAILNET) "内嵌 Tailscale" else "直接连接"}", R.drawable.ic_storage) { profileDetails = true }
                    }
                }
                item {
                    SettingsGroup("本机设置") {
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
                item { CacheSettingsPanel(model) { message ->
                    android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
                } }
                if (state.permissions.admin || state.permissions.execute) item {
                    SettingsGroup("服务器管理") {
                        Text("作用于当前服务器", Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (state.permissions.admin) SettingsAction("用户管理", "账号、权限与访问规则", R.drawable.ic_person) { model.tab("admin-users") }
                        if (state.permissions.admin) SettingsAction("服务器全局设置", "影响所有用户的配置与默认策略", R.drawable.ic_storage) { model.tab("server-settings") }
                        if (state.permissions.execute) SettingsAction("服务器命令", "在当前目录运行允许的单条命令", R.drawable.ic_code, action = model::openShell)
                    }
                }
                item {
                    SettingsGroup("关于应用") {
                        SettingsAction("检查更新", "当前版本 ${BuildConfig.VERSION_NAME}", R.drawable.ic_download, action = model::openUpdates)
                        SettingsAction("关于 Fileway", "版本、来源、鸣谢与许可证", R.drawable.ic_info) { repositoryError = null; about = true }
                    }
                }
                state.error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
            }
        }
    }
    if (themeSheet) ModalBottomSheet(onDismissRequest = { themeSheet = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp).selectableGroup()) {
            Text("选择主题", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 12.dp))
            AppTheme.entries.forEach { mode ->
                Row(Modifier.fillMaxWidth().selectable(selected = appearance.theme == mode, role = Role.RadioButton,
                    enabled = !appearance.saving, onClick = { model.appearance.save(mode); themeSheet = false }).heightIn(min = 56.dp).padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    RadioButton(selected = appearance.theme == mode, onClick = null)
                    Text(mode.label(), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
    if (networkDetails) AlertDialog(onDismissRequest = { networkDetails = false }, title = { Text("应用内 Tailscale") }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())) { NetworkSection(model) }
    }, confirmButton = { FilewayTextButton(onClick = { networkDetails = false }) { Text("关闭") } })
    if (profileDetails) AlertDialog(onDismissRequest = { profileDetails = false }, title = { Text("服务器档案") }, text = {
        Column(Modifier.heightIn(max = 320.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(state.profile?.name.orEmpty()); Text(state.profile?.address.orEmpty()); Text("服务类型：${if (state.profile?.backend == BackendKind.WINDOWS) "Windows" else "NAS"}"); Text("账号：${state.accountName}")
            Text(if (state.profile?.network == ConnectionMode.TAILNET) "连接方式：内嵌 Tailscale" else "连接方式：直接连接")
        }
    }, confirmButton = { FilewayTextButton(onClick = { profileDetails = false }) { Text("关闭") } })
    if (about) AlertDialog(onDismissRequest = ::closeAbout, title = { Text("栖卷 · Fileway") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            FilewayTextButton(onClick = { about = false; licenses = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("鸣谢与许可证") }
            repositoryError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }, confirmButton = { FilewayTextButton(onClick = ::closeAbout) { Text("关闭") } })
    if (licenses) FilewayLicensesSheet { licenses = false; about = true }
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

@Composable internal fun SettingsAction(title: String, subtitle: String, icon: Int, enabled: Boolean = true, action: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = action).heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(painterResource(icon), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Icon(painterResource(R.drawable.ic_arrow_forward), null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

internal fun AppTheme.label() = when (this) {
    AppTheme.SYSTEM -> "跟随系统"
    AppTheme.LIGHT -> "明色"
    AppTheme.DARK -> "暗色"
}
