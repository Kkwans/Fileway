package io.github.kkwans.nasfilebrowser.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
    val gestures = state.destination.main != null && !state.searchDisplayed
    BackHandler(drawer.isOpen) { scope.launch { drawer.close() } }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val drawerWidth = minOf(320.dp, (maxWidth - 56.dp).coerceAtLeast(0.dp))
        ModalNavigationDrawer(
            drawerState = drawer,
            gesturesEnabled = gestures,
            drawerContent = {
                ModalDrawerSheet(Modifier.width(drawerWidth)) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Fileway", style = MaterialTheme.typography.titleMedium)
                            if (!state.connected) Text("尚未连接服务器", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (state.connected) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayStatusIcon(
                                io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayStatusAnimation.CONNECTION, state.previewScope, Modifier.size(22.dp), play = drawer.isOpen)
                            Text(
                                "已连接 · ${if (state.profile?.network == ConnectionMode.TAILNET) "Tailscale" else "本地网络"}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                            )
                            }
                        }
                        fun navigate(action: () -> Unit) { scope.launch { drawer.close() }; action() }
                        FilewayDrawerItem(if (state.connected) "切换服务器或账号" else "连接服务器", R.drawable.ic_server) {
                            navigate { if (state.connected) model.disconnect() else model.showConnection() }
                        }
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        ClientMainDestination.entries.forEach { destination ->
                            val icon = when (destination) {
                                ClientMainDestination.FILES -> R.drawable.ic_nav_files_077
                                ClientMainDestination.RECENT -> R.drawable.ic_nav_history_077
                                ClientMainDestination.LIBRARY -> R.drawable.ic_library
                                ClientMainDestination.TASKS -> R.drawable.ic_tasks
                                ClientMainDestination.MY -> R.drawable.ic_person
                            }
                            FilewayDrawerItem(destination.label, icon, state.destination.main == destination) {
                                navigate { model.navigateMain(destination) }
                            }
                        }
                        if (state.connected) {
                            HorizontalDivider(Modifier.padding(vertical = 8.dp))
                            listOf(Triple(LibrarySection.FAVORITES, "收藏", R.drawable.ic_bookmark),
                                Triple(LibrarySection.TAGS, "标签", R.drawable.ic_tag),
                                Triple(LibrarySection.TRASH, "回收站", R.drawable.ic_trash),
                                Triple(LibrarySection.TOOLS, "存储工具", R.drawable.ic_storage)).forEach { (section, title, icon) ->
                                FilewayDrawerItem(title, icon, state.tab == "library" && state.librarySection == section) {
                                    navigate { model.navigateMain(ClientMainDestination.LIBRARY); model.librarySection(section) }
                                }
                            }
                        }
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        Text("主题", Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.labelLarge)
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            AppTheme.entries.forEach { mode ->
                                val label = when (mode) { AppTheme.SYSTEM -> "跟随系统"; AppTheme.LIGHT -> "浅色"; AppTheme.DARK -> "深色" }
                                val icon = when (mode) { AppTheme.SYSTEM -> R.drawable.ic_theme_system; AppTheme.LIGHT -> R.drawable.ic_theme_light; AppTheme.DARK -> R.drawable.ic_theme_dark }
                                Surface(Modifier.weight(1f), shape = MaterialTheme.shapes.medium,
                                    color = if (appearance.theme == mode) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) {
                                    IconButton({ model.appearance.save(mode) }, enabled = appearance.loaded && !appearance.saving, modifier = Modifier.fillMaxWidth()) {
                                        Icon(painterResource(icon), label, tint = if (appearance.theme == mode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                        if (state.connected) {
                            FilewayDrawerItem("账户设置", R.drawable.ic_person) { navigate { model.tab("account") } }
                            if (state.permissions.admin) {
                                FilewayDrawerItem("用户管理", R.drawable.ic_person) { navigate { model.tab("admin-users") } }
                                FilewayDrawerItem("全局设置", R.drawable.ic_settings) { navigate { model.tab("server-settings") } }
                            }
                            if (state.permissions.execute) FilewayDrawerItem("命令", R.drawable.ic_terminal) { navigate(model::openShell) }
                        }
                        FilewayDrawerItem("更新", R.drawable.ic_update) { navigate(model::openUpdates) }
                        FilewayDrawerItem("关于 Fileway", R.drawable.ic_info) { navigate { model.tab("about") } }
                    }
                }
            },
        ) {
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

@Composable
private fun FilewayDrawerItem(title: String, icon: Int, selected: Boolean = false, onClick: () -> Unit) {
    NavigationDrawerItem(
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
