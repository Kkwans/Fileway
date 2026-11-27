package io.github.kkwans.nasfilebrowser.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import io.github.kkwans.nasfilebrowser.ui.adaptive.FilewaySplitWorkspace
import io.github.kkwans.nasfilebrowser.ui.adaptive.LocalFilewayWindowLayout
import io.github.kkwans.nasfilebrowser.ui.adaptive.LocalTabletLayoutConfig
import io.github.kkwans.nasfilebrowser.ui.adaptive.SplitPaneSpec
import io.github.kkwans.nasfilebrowser.ui.adaptive.TabletContentDensity
import io.github.kkwans.nasfilebrowser.ui.adaptive.TabletLayoutConfig
import io.github.kkwans.nasfilebrowser.ui.adaptive.TabletLayoutPreferences
import io.github.kkwans.nasfilebrowser.ui.adaptive.TabletLayoutStore
import io.github.kkwans.nasfilebrowser.ui.adaptive.TabletSidebarStyle
import io.github.kkwans.nasfilebrowser.ui.adaptive.TabletSplitMode
import io.github.kkwans.nasfilebrowser.ui.adaptive.TabletSplitPreset
import io.github.kkwans.nasfilebrowser.ui.adaptive.TabletVideoTapBehavior
import io.github.kkwans.nasfilebrowser.ui.adaptive.rememberFilewaySplitWorkspaceState
import io.github.kkwans.nasfilebrowser.update.UpdatePolicy
import kotlinx.coroutines.launch

/** Compatibility boundary: every library page now inherits the single application theme. */
@Composable internal fun LibraryTheme(content: @Composable () -> Unit) = content()

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ClientNavigation(model: ClientModel, selected: String, activeOverride: ClientMainDestination? = null) {
    if (LocalSkipBottomNavigation.current) return
    val state by model.state.collectAsStateWithLifecycle()
    val active = activeOverride ?: state.destination.main ?: ClientDestination.forRoute(selected)?.main
    val navigationPulse = LocalFilewayNavigationPulse.current
    val labelStyle = MaterialTheme.typography.labelMedium
    val barHeight = 68.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    CompositionLocalProvider(LocalFilewayIconFamily provides FilewayIconFamily.MATERIAL_ROUNDED, LocalRippleConfiguration provides null) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp,
        modifier = Modifier.height(barHeight)) {
        listOf(Triple(ClientMainDestination.FILES, "文件", R.drawable.ic_nav_files_077),
            Triple(ClientMainDestination.RECENT, "最近", R.drawable.ic_nav_history_077),
            Triple(ClientMainDestination.MEDIA, "影视", R.drawable.ic_video),
            Triple(ClientMainDestination.LIBRARY, "资料", R.drawable.ic_library),
            Triple(ClientMainDestination.TASKS, "任务", R.drawable.ic_tasks)).forEach { (tab, label, icon) ->
            NavigationBarItem(selected = active == tab, onClick = { navigationPulse?.let { it.value = tab.name to (it.value.second + 1) }; model.navigateMain(tab) },
                modifier = Modifier.semantics { contentDescription = "${label}导航" },
                icon = { FilewayIcon(icon, null, Modifier.size(24.dp), eventKey = if (active == tab && navigationPulse?.value?.first == tab.name) navigationPulse.value.second else 0) }, label = { Text(label, style = labelStyle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                colors = NavigationBarItemDefaults.colors(indicatorColor = Color.Transparent,
                    selectedIconColor = MaterialTheme.colorScheme.primary, selectedTextColor = MaterialTheme.colorScheme.primary,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant, unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant))
        }
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
    val supportsPictureInPicture = LocalPlayerPictureInPicture.current?.supported == true
    val preferenceScope = rememberCoroutineScope()
    val windowLayout = LocalFilewayWindowLayout.current
    val tabletConfig = LocalTabletLayoutConfig.current
    var activeCategory by remember(state.connected) {
        mutableStateOf(if (state.connected) "account" else "appearance")
    }
    Scaffold(containerColor = MaterialTheme.colorScheme.background,
        bottomBar = { ClientNavigation(model, "settings") },
        topBar = { Column(Modifier.statusBarsPadding()) {
            if (state.connected) FilewayPageHeader("我的")
            else FilewayTopAppBar("本机设置", leadingIcon = R.drawable.ic_arrow_back, leadingDescription = "返回连接页面", onLeadingClick = onBack)
            if (windowLayout.useDualPane) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }
        } }) { insets ->
        if (windowLayout.useDualPane) {
            val splitState = rememberFilewaySplitWorkspaceState(
                sceneKey = "settings",
                spec = SplitPaneSpec(defaultRatio = 0.34f, minLeftDp = 260f, minRightDp = 400f),
            )
            val categories = remember(state.connected, state.permissions.admin, state.permissions.execute) {
                buildList {
                    if (state.connected) add(Triple("account", "服务器与账户", R.drawable.ic_server))
                    add(Triple("appearance", "外观与平板布局", R.drawable.ic_visibility))
                    add(Triple("playback_cache", "播放与本机缓存", R.drawable.ic_storage))
                    add(Triple("network", "应用内 Tailscale", R.drawable.ic_network))
                    if (state.connected && (state.permissions.admin || state.permissions.execute)) {
                        add(Triple("admin", "服务器管理", R.drawable.ic_settings))
                    }
                    add(Triple("about", "更新与关于", R.drawable.ic_info))
                }
            }
            if (categories.none { it.first == activeCategory }) {
                activeCategory = categories.first().first
            }
            FilewaySplitWorkspace(
                state = splitState,
                modifier = Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets),
                listPane = {
                    LazyColumn(
                        Modifier.fillMaxSize().semantics { contentDescription = "设置分类" },
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(categories.size) { idx ->
                            val (key, title, icon) = categories[idx]
                            val selected = activeCategory == key
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .selectable(selected = selected, role = Role.Tab) { activeCategory = key },
                                shape = MaterialTheme.shapes.medium,
                                color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent,
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    FilewayIcon(
                                        icon,
                                        null,
                                        Modifier.size(20.dp),
                                        tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                },
                detailPane = {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                        LazyColumn(
                            Modifier.widthIn(max = 680.dp).fillMaxSize().semantics { contentDescription = "设置内容" },
                            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            when (activeCategory) {
                                "account" -> if (state.connected) {
                                    item {
                                        SettingsGroup("当前服务器与账户") {
                                            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                                FilewayIcon(R.drawable.ic_server, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                    Text(state.accountName, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
                                }
                                "appearance" -> {
                                    item {
                                        SettingsGroup("主题模式") {
                                            Column(Modifier.fillMaxWidth().selectableGroup()) {
                                                AppTheme.entries.forEach { mode ->
                                                    io.github.kkwans.nasfilebrowser.ui.designsystem.FilewaySelectionRow(
                                                        mode.label(),
                                                        selected = appearance.theme == mode,
                                                        enabled = appearance.loaded && !appearance.saving,
                                                    ) { model.appearance.save(mode) }
                                                }
                                            }
                                            appearance.error?.let { message ->
                                                Text(message, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                                if (!appearance.loaded) FilewayTextButton(onClick = model.appearance::reload) { Text("重试读取") }
                                            }
                                        }
                                    }
                                    item {
                                        TabletLayoutCustomizationGroups(context = context, config = tabletConfig)
                                    }
                                }
                                "playback_cache" -> {
                                    if (supportsPictureInPicture) item {
                                        SettingsGroup("视频播放") {
                                            Row(Modifier.fillMaxWidth().toggleable(value = autoPip, role = Role.Switch, onValueChange = { enabled ->
                                                preferenceScope.launch { try { model.playbackPreferences.saveAutoPictureInPicture(enabled) }
                                                    catch (error: Exception) { if (error is kotlinx.coroutines.CancellationException) throw error
                                                        android.widget.Toast.makeText(context, "无法保存画中画设置，请重试", android.widget.Toast.LENGTH_SHORT).show() } }
                                            }).heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                                FilewayIcon(R.drawable.ic_pip, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                    Text("离开应用时自动画中画", style = MaterialTheme.typography.bodyMedium)
                                                    Text("仅在视频正在播放时开启", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                                Box(Modifier.size(width = 48.dp, height = 40.dp), contentAlignment = Alignment.Center) {
                                                    Switch(autoPip, onCheckedChange = null, modifier = Modifier.scale(.8f).clearAndSetSemantics {})
                                                }
                                            }
                                        }
                                    }
                                    item { CacheSettingsPanel(model) { message ->
                                        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
                                    } }
                                }
                                "network" -> {
                                    item {
                                        SettingsGroup("应用内 Tailscale · ${network.label}") {
                                            Box(Modifier.fillMaxWidth().padding(12.dp)) {
                                                NetworkSection(model)
                                            }
                                        }
                                    }
                                }
                                "admin" -> if (state.permissions.admin || state.permissions.execute) {
                                    item {
                                        SettingsGroup("服务器管理") {
                                            if (state.permissions.admin) SettingsAction("用户管理", "账号、权限与访问规则", R.drawable.ic_person) { model.tab("admin-users") }
                                            if (state.permissions.admin) SettingsAction("服务器全局设置", "影响所有用户的配置与默认策略", R.drawable.ic_storage) { model.tab("server-settings") }
                                            if (state.permissions.execute) SettingsAction("服务器命令", "在当前目录运行允许的单条命令", R.drawable.ic_code, action = model::openShell)
                                        }
                                    }
                                }
                                else -> {
                                    item {
                                        SettingsGroup("关于应用") {
                                            SettingsAction("检查更新", "当前版本 ${BuildConfig.VERSION_NAME}", R.drawable.ic_download, action = model::openUpdates)
                                            SettingsAction("关于栖卷 · Fileway", "版本、来源、鸣谢与许可证", R.drawable.ic_info) { repositoryError = null; about = true }
                                        }
                                    }
                                }
                            }
                            state.error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
                        }
                    }
                },
            )
        } else Box(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets), contentAlignment = Alignment.TopCenter) {
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
                if (supportsPictureInPicture) item { SettingsGroup("视频播放") {
                    Row(Modifier.fillMaxWidth().toggleable(value = autoPip, role = Role.Switch, onValueChange = { enabled ->
                        preferenceScope.launch { try { model.playbackPreferences.saveAutoPictureInPicture(enabled) }
                            catch (error: Exception) { if (error is kotlinx.coroutines.CancellationException) throw error
                                android.widget.Toast.makeText(context, "无法保存画中画设置，请重试", android.widget.Toast.LENGTH_SHORT).show() } }
                    }).heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FilewayIcon(R.drawable.ic_pip, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("离开应用时自动画中画", style = MaterialTheme.typography.bodyMedium)
                            Text("仅在视频正在播放时开启", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        // The whole56dp row is the switch target; only its visual scales down.
                        Box(Modifier.size(width = 48.dp, height = 40.dp), contentAlignment = Alignment.Center) {
                            Switch(autoPip, onCheckedChange = null, modifier = Modifier.scale(.8f).clearAndSetSemantics {})
                        }
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
                if (!windowLayout.isCompact) item {
                    TabletLayoutCustomizationGroups(context = context, config = tabletConfig)
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

@Composable
private fun TabletLayoutCustomizationGroups(
    context: android.content.Context,
    config: TabletLayoutConfig,
) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsGroup("工作区与排版布局") {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // 1. Split Mode
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("分栏工作模式", style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
                    Row(
                        Modifier.fillMaxWidth().height(IntrinsicSize.Max).selectableGroup(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        TabletSplitMode.entries.forEach { mode ->
                            val selected = config.splitMode == mode
                            TabletOptionTile(
                                title = mode.label,
                                subtitle = mode.summary,
                                selected = selected,
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                onClick = {
                                    TabletLayoutPreferences.update(context) { it.copy(splitMode = mode) }
                                },
                            )
                        }
                    }
                }

                HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.5f))

                // 2. Default Split Preset with visual miniature ratio preview bars
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("默认分栏比例预设", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
                        Text(
                            "点击立即应用并平滑调整",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth().selectableGroup(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TabletSplitPreset.entries.forEach { preset ->
                            val selected = config.splitPreset == preset
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .selectable(selected = selected, role = Role.RadioButton) {
                                        TabletLayoutPreferences.applySplitPreset(context, preset)
                                    },
                                shape = RoundedCornerShape(12.dp),
                                color = if (selected) colors.primary.copy(alpha = 0.12f) else colors.surfaceContainer.copy(alpha = 0.65f),
                                border = BorderStroke(
                                    width = if (selected) 1.5.dp else 1.dp,
                                    color = if (selected) colors.primary else colors.outlineVariant.copy(alpha = 0.6f),
                                ),
                            ) {
                                Column(
                                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    // Miniature visual ratio bar
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .height(10.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(colors.outlineVariant.copy(alpha = 0.35f)),
                                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                                    ) {
                                        Box(
                                            Modifier
                                                .weight(preset.ratio)
                                                .fillMaxHeight()
                                                .background(if (selected) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.55f)),
                                        )
                                        Box(
                                            Modifier
                                                .weight(1f - preset.ratio)
                                                .fillMaxHeight()
                                                .background(if (selected) colors.primary.copy(alpha = 0.32f) else colors.onSurfaceVariant.copy(alpha = 0.22f)),
                                        )
                                    }
                                    Text(
                                        preset.label,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (selected) colors.primary else colors.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        preset.ratioText,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = colors.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.5f))

                // 3. Sidebar Style
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("侧边导航栏形态", style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
                    Row(
                        Modifier.fillMaxWidth().height(IntrinsicSize.Max).selectableGroup(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TabletSidebarStyle.entries.forEach { style ->
                            val selected = config.sidebarStyle == style
                            TabletOptionTile(
                                title = style.label,
                                subtitle = style.summary,
                                selected = selected,
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                onClick = {
                                    TabletLayoutPreferences.update(context) { it.copy(sidebarStyle = style) }
                                },
                            )
                        }
                    }
                }

                HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.5f))

                // 4. Content Density
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("文件网格与列表密度", style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
                    Row(
                        Modifier.fillMaxWidth().height(IntrinsicSize.Max).selectableGroup(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TabletContentDensity.entries.forEach { density ->
                            val selected = config.density == density
                            TabletOptionTile(
                                title = density.label,
                                subtitle = density.summary,
                                selected = selected,
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                onClick = {
                                    TabletLayoutPreferences.update(context) { it.copy(density = density) }
                                },
                            )
                        }
                    }
                }
            }
        }

        SettingsGroup("平板交互与分栏细节") {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("双栏单击视频文件", style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
                Row(
                    Modifier.fillMaxWidth().height(IntrinsicSize.Max).selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TabletVideoTapBehavior.entries.forEach { behavior ->
                        val selected = config.videoTapBehavior == behavior
                        TabletOptionTile(
                            title = behavior.label,
                            subtitle = behavior.summary,
                            selected = selected,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            onClick = {
                                TabletLayoutPreferences.update(context) { it.copy(videoTapBehavior = behavior) }
                            },
                        )
                    }
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = colors.outlineVariant.copy(alpha = 0.45f))
            TabletSwitchRow(
                title = "拖动分割线显示实时比例气泡",
                subtitle = "拖动中间手柄时浮现当前左右栏占比与磁吸状态",
                checked = config.showDragRatioHud,
                onCheckedChange = { enabled ->
                    TabletLayoutPreferences.update(context) { it.copy(showDragRatioHud = enabled) }
                },
            )
            TabletSwitchRow(
                title = "分栏磁吸刻度与触觉反馈",
                subtitle = "拖动经过 35% / 50% / 65% 时轻震吸附，关闭则完全自由无级调节",
                checked = config.magneticSnapEnabled,
                onCheckedChange = { enabled ->
                    TabletLayoutPreferences.update(context) { it.copy(magneticSnapEnabled = enabled) }
                },
            )
            TabletSwitchRow(
                title = "允许拖至最左折叠左侧列表",
                subtitle = "将分割线继续左拉可临时隐藏列表，随时可点左边缘拉手恢复",
                checked = config.allowEdgeCollapse,
                onCheckedChange = { enabled ->
                    TabletLayoutPreferences.update(context) { it.copy(allowEdgeCollapse = enabled) }
                },
            )
            TabletSwitchRow(
                title = "工作台显示快捷键与操作提示",
                subtitle = "在空闲目录总览页底部展示键盘快捷键与拖拽提示",
                checked = config.showWorkspaceHints,
                onCheckedChange = { enabled ->
                    TabletLayoutPreferences.update(context) { it.copy(showWorkspaceHints = enabled) }
                },
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = colors.outlineVariant.copy(alpha = 0.45f))
            SettingsAction(
                "重置全部平板布局与分栏比例",
                "恢复默认双栏模式、黄金比例、侧栏形态、网格密度与所有辅助开关",
                R.drawable.ic_refresh,
                navigation = false,
            ) {
                TabletLayoutStore.resetAll(context)
                android.widget.Toast.makeText(context, "已恢复默认平板布局与分栏比例", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
}

@Composable
private fun TabletOptionTile(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) colors.primary.copy(alpha = 0.12f) else colors.surfaceContainer.copy(alpha = 0.65f),
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) colors.primary else colors.outlineVariant.copy(alpha = 0.6f),
        ),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) colors.primary else colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TabletSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .heightIn(min = 58.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.size(width = 48.dp, height = 40.dp), contentAlignment = Alignment.Center) {
            Switch(checked = checked, onCheckedChange = null, modifier = Modifier.scale(.8f).clearAndSetSemantics {})
        }
    }
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
