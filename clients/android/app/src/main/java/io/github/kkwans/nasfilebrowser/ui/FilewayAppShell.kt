package io.github.kkwans.nasfilebrowser.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.app.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kkwans.nasfilebrowser.data.AppTheme
import io.github.kkwans.nasfilebrowser.data.BackendKind
import io.github.kkwans.nasfilebrowser.data.ConnectionMode
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayTopAppBar
import kotlinx.coroutines.launch
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import dev.chrisbanes.haze.blur.hazeBlur

internal val LocalFilewayPageActive = staticCompositionLocalOf { true }
internal val LocalSkipBottomNavigation = staticCompositionLocalOf { false }
internal val LocalFilewayDrawer = staticCompositionLocalOf<() -> Unit> { {} }
internal val LocalFilewayBack = staticCompositionLocalOf<(() -> Unit)?> { null }
internal val LocalFilewaySessionModel = staticCompositionLocalOf<ClientModel?> { null }
internal val LocalFilewayDarkCanvasOwner = staticCompositionLocalOf<(Any, Boolean) -> Unit> { { _, _ -> } }

/** Drawer chrome exists only around library pages, never around a native content surface. */
@Composable
internal fun FilewayAppShell(model: ClientModel, state: ClientState, onBack: () -> Unit, content: @Composable (ClientMainDestination?) -> Unit) {
    val appearance by model.appearance.state.collectAsStateWithLifecycle()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val glass = rememberHazeState()
    val glassActive = drawer.isOpen || drawer.targetValue == DrawerValue.Open
    val glassStyle = filewayDrawerGlassStyle()
    val gestures = state.destination.main != null && !state.searchDisplayed
    var about by remember { mutableStateOf(false) }
    if (about) FilewayAboutDialog { about = false }
    BackHandler(drawer.isOpen && !about) { scope.launch { drawer.close() } }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val drawerWidth = minOf(320.dp, (maxWidth - 56.dp).coerceAtLeast(0.dp))
        ModalNavigationDrawer(
            drawerState = drawer,
            gesturesEnabled = gestures,
            drawerContent = {
                val drawerShape = RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp)
                ModalDrawerSheet(Modifier.width(drawerWidth).clip(drawerShape)
                    .then(if (glassActive) Modifier.hazeBlur(HazeInput.Sources(glass), glassStyle) else Modifier)
                    .filewayGlassFrame(drawerShape), drawerShape = drawerShape,
                    drawerContainerColor = Color.Transparent, drawerTonalElevation = 0.dp) {
                    var navigating by remember { mutableStateOf(false) }
                    fun navigate(action: () -> Unit) {
                        if (navigating) return
                        navigating = true
                        scope.launch { try { drawer.close(); action() } finally { navigating = false } }
                    }
                    Column(Modifier.fillMaxSize()) {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayStatusIcon(
                                        io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayStatusAnimation.CONNECTION, state.previewScope,
                                        Modifier.size(22.dp), play = drawer.isOpen && state.connected)
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text("栖卷 · Fileway", style = MaterialTheme.typography.titleMedium)
                                        Text(if (!state.connected) "尚未连接" else "已连接 · ${if (state.profile?.network == ConnectionMode.TAILNET) "Tailscale" else "本地网络"}",
                                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                FilewayGlassSection(Modifier.fillMaxWidth()) {
                                    Row(Modifier.fillMaxWidth().clickable { navigate { if (state.connected) model.disconnect() else model.showConnection() } }
                                        .heightIn(min = 48.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Icon(painterResource(R.drawable.ic_server), null, Modifier.size(22.dp))
                                        Text(if (state.connected) "切换服务器或账号" else "连接服务器", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                        Icon(painterResource(R.drawable.ic_arrow_forward), null, Modifier.size(18.dp))
                                    }
                                }
                            }
                            if (state.connected) {
                                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                                RecentSection.entries.forEach { section ->
                                    FilewayDrawerItem(section.label, if (section == RecentSection.PLAYBACK) R.drawable.ic_playlist_play else R.drawable.ic_nav_history_077,
                                        state.tab == "recent" && state.recentSection == section) {
                                        navigate { model.navigateMain(ClientMainDestination.RECENT); model.recentSection(section) }
                                    }
                                }
                                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                                listOf(Triple(LibrarySection.FAVORITES, "收藏", R.drawable.ic_bookmark),
                                    Triple(LibrarySection.TAGS, "标签", R.drawable.ic_tag),
                                    Triple(LibrarySection.TRASH, "回收站", R.drawable.ic_trash),
                                    Triple(LibrarySection.TOOLS, "存储工具", R.drawable.ic_storage)).forEach { (section, title, icon) ->
                                    FilewayDrawerItem(title, icon, state.tab == "library" && state.librarySection == section) {
                                        navigate { model.navigateMain(ClientMainDestination.LIBRARY); model.librarySection(section) }
                                    }
                                }
                                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                                TaskCenterSection.entries.forEach { section ->
                                    val icon = when (section) {
                                        TaskCenterSection.DOWNLOADS -> R.drawable.ic_download
                                        TaskCenterSection.UPLOADS -> R.drawable.ic_upload
                                        TaskCenterSection.BACKGROUND -> R.drawable.ic_tasks
                                        TaskCenterSection.HISTORY -> R.drawable.ic_history
                                    }
                                    FilewayDrawerItem(section.label, icon, state.destination.main == ClientMainDestination.TASKS && state.taskCenterSection == section) {
                                        navigate { model.navigateMain(ClientMainDestination.TASKS); model.taskCenterSection(section) }
                                    }
                                }
                            } else {
                                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                                FilewayDrawerItem("下载文件", R.drawable.ic_download) { navigate(model::openDownloads) }
                            }
                            HorizontalDivider(Modifier.padding(vertical = 8.dp))
                            FilewayDrawerItem("缓存图片管理", R.drawable.ic_image, state.tab == "cache-images") { navigate { model.tab("cache-images") } }
                            if (!state.connected) FilewayDrawerItem("本机缓存设置", R.drawable.ic_settings) { navigate { model.tab("settings") } }
                            if (state.connected) {
                                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                                FilewayDrawerItem("账户设置", R.drawable.ic_person) { navigate { model.tab("account") } }
                                if (state.permissions.admin) {
                                    FilewayDrawerItem("用户管理", R.drawable.ic_person) { navigate { model.tab("admin-users") } }
                                    FilewayDrawerItem("全局设置", R.drawable.ic_settings) { navigate { model.tab("server-settings") } }
                                }
                                if (state.permissions.execute) FilewayDrawerItem("命令", R.drawable.ic_terminal) { navigate(model::openShell) }
                            }
                            HorizontalDivider(Modifier.padding(vertical = 8.dp))
                            FilewayDrawerItem("更新", R.drawable.ic_update) { navigate(model::openUpdates) }
                            FilewayDrawerItem("关于栖卷 · Fileway", R.drawable.ic_info) { about = true }
                        }
                        HorizontalDivider()
                        // ModalDrawerSheet already owns the system-bar insets.
                        Row(Modifier.fillMaxWidth().padding(12.dp).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AppTheme.entries.forEach { mode ->
                                val label = when (mode) { AppTheme.SYSTEM -> "系统"; AppTheme.LIGHT -> "浅色"; AppTheme.DARK -> "深色" }
                                val icon = when (mode) { AppTheme.SYSTEM -> R.drawable.ic_theme_system; AppTheme.LIGHT -> R.drawable.ic_theme_light; AppTheme.DARK -> R.drawable.ic_theme_dark }
                                val chosen = appearance.theme == mode
                                Surface(Modifier.weight(1f).heightIn(min = 56.dp).clip(MaterialTheme.shapes.medium)
                                    .selectable(chosen, enabled = appearance.loaded && !appearance.saving, role = Role.RadioButton) { model.appearance.save(mode) }
                                    .semantics { contentDescription = if (mode == AppTheme.SYSTEM) "跟随系统主题" else "${label}主题" },
                                    shape = MaterialTheme.shapes.medium,
                                    color = if (chosen) MaterialTheme.colorScheme.primary.copy(alpha = .12f) else Color.Transparent) {
                                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)) {
                                        Icon(painterResource(icon), null, Modifier.size(22.dp), tint = if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(label, style = MaterialTheme.typography.labelSmall, color = if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            },
        ) {
            Box(Modifier.fillMaxSize().then(if (glassActive) Modifier.hazeSource(glass) else Modifier)) {
            CompositionLocalProvider(LocalFilewayDrawer provides { scope.launch { drawer.open() }; Unit }, LocalFilewayBack provides onBack,
                LocalFilewaySessionModel provides model) {
                if (state.connected && state.destination.main != null && !state.searchDisplayed) {
                    val destination = state.destination.main!!
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxWidth().consumeWindowInsets(WindowInsets.navigationBars)) {
                            CompositionLocalProvider(LocalSkipBottomNavigation provides true, LocalFilewayPageActive provides true) { content(destination) }
                        }
                        ClientNavigation(model, destination.route, activeOverride = destination)
                    }
                } else CompositionLocalProvider(LocalSkipBottomNavigation provides !state.connected) { content(null) }
            }
            }
        }
    }
}

@Composable
private fun FilewayDrawerItem(title: String, icon: Int, selected: Boolean = false, onClick: () -> Unit) {
    NavigationDrawerItem(
        colors = NavigationDrawerItemDefaults.colors(selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = .10f),
            unselectedContainerColor = Color.Transparent, selectedIconColor = MaterialTheme.colorScheme.primary, selectedTextColor = MaterialTheme.colorScheme.primary),
        label = { Text(title, style = MaterialTheme.typography.bodyMedium) }, selected = selected, onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        icon = { Icon(painterResource(icon), null, Modifier.size(22.dp)) },
        modifier = Modifier.padding(horizontal = 8.dp).heightIn(min = 48.dp),
    )
}

@Composable
internal fun FilewayPageHeader(title: String, subtitle: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    val openDrawer = LocalFilewayDrawer.current
    val model = LocalFilewaySessionModel.current
    Column(Modifier.fillMaxWidth()) {
        FilewayTopAppBar(title, subtitle, R.drawable.ic_menu, "打开导航菜单", openDrawer, actions)
        model?.let { FilewaySessionNotice(it) }
    }
}
