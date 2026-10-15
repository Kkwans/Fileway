package io.github.kkwans.nasfilebrowser.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.app.*
import io.github.kkwans.nasfilebrowser.data.BackendKind
import io.github.kkwans.nasfilebrowser.data.ConnectionMode
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayTopAppBar
import kotlinx.coroutines.launch

internal val LocalFilewayDrawer = staticCompositionLocalOf<() -> Unit> { {} }
internal val LocalFilewayBack = staticCompositionLocalOf<(() -> Unit)?> { null }

/** Drawer chrome exists only around library pages, never around a native content surface. */
@Composable
internal fun FilewayAppShell(model: ClientModel, state: ClientState, onBack: () -> Unit, content: @Composable () -> Unit) {
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
                            Text(state.profile?.name?.ifBlank { "当前服务器" } ?: "Fileway", style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (state.connected) "${if (state.profile?.backend == BackendKind.WINDOWS) "Windows" else "NAS"} · ${state.accountName}"
                                else "尚未连接服务器",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (state.connected) Text(
                                "已连接 · ${if (state.profile?.network == ConnectionMode.TAILNET) "Tailscale" else "本地网络"}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                            )
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
                        FilewayDrawerItem("本机设置", R.drawable.ic_settings, state.tab == "settings") { navigate { model.tab("settings") } }
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
            CompositionLocalProvider(LocalFilewayDrawer provides { scope.launch { drawer.open() }; Unit }, LocalFilewayBack provides onBack) { content() }
        }
    }
}

@Composable
private fun FilewayDrawerItem(title: String, icon: Int, selected: Boolean = false, onClick: () -> Unit) {
    NavigationDrawerItem(
        label = { Text(title, style = MaterialTheme.typography.bodyMedium) }, selected = selected, onClick = onClick,
        icon = { Icon(painterResource(icon), null, Modifier.size(22.dp)) },
        modifier = Modifier.padding(horizontal = 8.dp).heightIn(min = 48.dp),
    )
}

@Composable
internal fun FilewayPageHeader(title: String, subtitle: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    val openDrawer = LocalFilewayDrawer.current
    FilewayTopAppBar(title, subtitle, R.drawable.ic_menu, "打开导航菜单", openDrawer, actions)
}
