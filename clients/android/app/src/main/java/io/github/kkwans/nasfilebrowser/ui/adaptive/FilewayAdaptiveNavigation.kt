package io.github.kkwans.nasfilebrowser.ui.adaptive

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kkwans.fileway.design.R as Art
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.app.ClientMainDestination
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.app.ClientState
import io.github.kkwans.nasfilebrowser.app.LibrarySection
import io.github.kkwans.nasfilebrowser.app.RecentSection
import io.github.kkwans.nasfilebrowser.app.TaskCenterSection
import io.github.kkwans.nasfilebrowser.app.destination
import io.github.kkwans.nasfilebrowser.data.AppTheme
import io.github.kkwans.nasfilebrowser.ui.FilewayDrawerAccount
import io.github.kkwans.nasfilebrowser.ui.FilewayIcon
import io.github.kkwans.nasfilebrowser.ui.FilewayIconButton
import io.github.kkwans.nasfilebrowser.ui.FilewayIconFamily
import io.github.kkwans.nasfilebrowser.ui.LocalFilewayIconFamily
import io.github.kkwans.nasfilebrowser.ui.LocalFilewayNavigationPulse
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayTheme

/** Indicates that a persistent side NavigationRail or expanded sidebar is currently shown. */
internal val LocalFilewayHasSideRail = staticCompositionLocalOf { false }

/**
 * Marks whether an embedded detail pane is currently expanded to fullscreen immersive mode.
 * Used by shell to hide side navigation cleanly without unmounting content.
 */
internal val LocalFilewayDetailFullscreenOwner = staticCompositionLocalOf<(Any, Boolean) -> Unit> { { _, _ -> } }

private val MainNavEntries = listOf(
    Triple(ClientMainDestination.FILES, "文件", R.drawable.ic_nav_files_077),
    Triple(ClientMainDestination.RECENT, "最近", R.drawable.ic_nav_history_077),
    Triple(ClientMainDestination.MEDIA, "影视", R.drawable.ic_video),
    Triple(ClientMainDestination.LIBRARY, "资料", R.drawable.ic_library),
    Triple(ClientMainDestination.TASKS, "任务", R.drawable.ic_tasks),
)

/**
 * 80dp side navigation rail for MEDIUM (600-839dp), EXPANDED (840-1199dp), and collapsed LARGE windows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FilewayNavigationRail(
    model: ClientModel,
    state: ClientState,
    canToggleExpand: Boolean,
    onMenuOrExpandClick: () -> Unit,
    onOpenTvControl: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = state.destination.main
    val navigationPulse = LocalFilewayNavigationPulse.current
    val appearance by model.appearance.state.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme

    Row(modifier.fillMaxHeight()) {
        CompositionLocalProvider(
            LocalFilewayIconFamily provides FilewayIconFamily.MATERIAL_ROUNDED,
            LocalRippleConfiguration provides null,
        ) {
            NavigationRail(
                modifier = Modifier
                    .width(80.dp)
                    .fillMaxHeight(),
                containerColor = colors.background,
                header = {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                    ) {
                        FilewayIconButton(
                            onClick = onMenuOrExpandClick,
                            modifier = Modifier.size(48.dp),
                        ) {
                            FilewayIcon(
                                R.drawable.ic_menu,
                                if (canToggleExpand) "展开侧边栏" else "打开导航菜单",
                                Modifier.size(24.dp),
                                tint = colors.onSurfaceVariant,
                            )
                        }
                        if (state.connected) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (state.tab == "settings") colors.primary.copy(alpha = 0.16f)
                                        else colors.surfaceContainerHighest,
                                    )
                                    .clickable(role = Role.Button, onClickLabel = "打开我的设置", onClick = onOpenSettings),
                                contentAlignment = Alignment.Center,
                            ) {
                                FilewayIcon(
                                    R.drawable.ic_person,
                                    null,
                                    Modifier.size(20.dp),
                                    tint = if (state.tab == "settings") colors.primary else colors.onSurfaceVariant,
                                )
                            }
                        }
                    }
                },
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (state.connected) {
                        MainNavEntries.forEach { (tab, label, icon) ->
                            val selected = active == tab && !state.searchDisplayed && state.tab != "settings"
                            NavigationRailItem(
                                selected = selected,
                                onClick = {
                                    navigationPulse?.let { it.value = tab.name to (it.value.second + 1) }
                                    if (active != tab || state.searchDisplayed || state.tab == "settings") {
                                        dismissEmbeddedViewersForSwitch(model)
                                    }
                                    model.navigateMain(tab)
                                },
                                modifier = Modifier.semantics { contentDescription = "${label}导航" },
                                icon = {
                                    FilewayIcon(
                                        icon,
                                        null,
                                        Modifier.size(24.dp),
                                        eventKey = if (selected && navigationPulse?.value?.first == tab.name) {
                                            navigationPulse.value.second
                                        } else {
                                            0
                                        },
                                    )
                                },
                                label = {
                                    Text(
                                        label,
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                colors = NavigationRailItemDefaults.colors(
                                    indicatorColor = colors.primary.copy(alpha = 0.12f),
                                    selectedIconColor = colors.primary,
                                    selectedTextColor = colors.primary,
                                    unselectedIconColor = colors.onSurfaceVariant,
                                    unselectedTextColor = colors.onSurfaceVariant,
                                ),
                            )
                        }
                    }
                }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(bottom = 8.dp),
                ) {
                    HorizontalDivider(
                        modifier = Modifier.width(40.dp).padding(vertical = 4.dp),
                        color = colors.outlineVariant.copy(alpha = 0.6f),
                    )
                    FilewayIconButton(onClick = onOpenTvControl, modifier = Modifier.size(48.dp)) {
                        FilewayIcon(Art.drawable.fileway_ic_cast, "连接 TV", Modifier.size(22.dp), tint = colors.onSurfaceVariant)
                    }
                    FilewayIconButton(
                        onClick = {
                            if (appearance.loaded && !appearance.saving) {
                                val next = when (appearance.theme) {
                                    AppTheme.SYSTEM -> AppTheme.LIGHT
                                    AppTheme.LIGHT -> AppTheme.DARK
                                    AppTheme.DARK -> AppTheme.SYSTEM
                                }
                                model.appearance.save(next)
                            }
                        },
                        enabled = appearance.loaded && !appearance.saving,
                        modifier = Modifier.size(48.dp),
                    ) {
                        val icon = when (appearance.theme) {
                            AppTheme.SYSTEM -> R.drawable.ic_theme_system
                            AppTheme.LIGHT -> R.drawable.ic_theme_light
                            AppTheme.DARK -> R.drawable.ic_theme_dark
                        }
                        FilewayIcon(icon, "切换主题", Modifier.size(22.dp), tint = colors.onSurfaceVariant)
                    }
                    FilewayIconButton(onClick = onOpenSettings, modifier = Modifier.size(48.dp)) {
                        FilewayIcon(
                            R.drawable.ic_settings,
                            "设置",
                            Modifier.size(22.dp),
                            tint = if (state.tab == "settings") colors.primary else colors.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        VerticalDivider(color = colors.outlineVariant.copy(alpha = 0.65f))
    }
}

/**
 * 232dp persistent collapsible sidebar for LARGE (>= 1200dp) tablets and desktop windows.
 */
@Composable
internal fun FilewayExpandedRail(
    model: ClientModel,
    state: ClientState,
    onCollapse: () -> Unit,
    onOpenTvControl: () -> Unit,
    onOpenAbout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val appearance by model.appearance.state.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme
    val activeMain = state.destination.main

    Row(modifier.fillMaxHeight()) {
        Surface(
            modifier = Modifier
                .width(232.dp)
                .fillMaxHeight(),
            color = colors.background,
            tonalElevation = 0.dp,
        ) {
            Column(
                Modifier
                    .fillMaxHeight()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start)),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .padding(start = 16.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "栖卷 · Fileway",
                        modifier = Modifier.weight(1f),
                        style = FilewayTheme.text.appBar,
                        color = colors.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    FilewayIconButton(onClick = onCollapse, modifier = Modifier.size(44.dp)) {
                        FilewayIcon(R.drawable.ic_menu, "收起侧边栏", Modifier.size(22.dp), tint = colors.onSurfaceVariant)
                    }
                }
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 4.dp),
                ) {
                    FilewayDrawerAccount(
                        state = state,
                        enabled = true,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        dismissEmbeddedViewersForSwitch(model)
                        model.tab("settings")
                    }

                    if (state.connected) {
                        MainNavEntries.forEach { (tab, label, icon) ->
                            val selected = activeMain == tab && !state.searchDisplayed && state.tab != "settings"
                            SidebarItem(label, icon, selected) {
                                if (activeMain != tab || state.searchDisplayed || state.tab == "settings") {
                                    dismissEmbeddedViewersForSwitch(model)
                                }
                                model.navigateMain(tab)
                            }
                        }
                        HorizontalDivider(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), color = colors.outlineVariant.copy(alpha = 0.6f))
                        SidebarSectionTitle("快捷入口")
                        SidebarQuickItem("连接 TV", Art.drawable.fileway_ic_cast, false, onOpenTvControl)
                        SidebarQuickItem(
                            "收藏夹",
                            R.drawable.ic_bookmark,
                            state.tab == "library" && state.librarySection == LibrarySection.FAVORITES,
                        ) {
                            dismissEmbeddedViewersForSwitch(model)
                            model.navigateMain(ClientMainDestination.LIBRARY)
                            model.librarySection(LibrarySection.FAVORITES)
                        }
                        SidebarQuickItem(
                            "标签",
                            R.drawable.ic_tag,
                            state.tab == "library" && state.librarySection == LibrarySection.TAGS,
                        ) {
                            dismissEmbeddedViewersForSwitch(model)
                            model.navigateMain(ClientMainDestination.LIBRARY)
                            model.librarySection(LibrarySection.TAGS)
                        }
                        SidebarQuickItem(
                            "回收站",
                            R.drawable.ic_trash,
                            state.tab == "library" && state.librarySection == LibrarySection.TRASH,
                        ) {
                            dismissEmbeddedViewersForSwitch(model)
                            model.navigateMain(ClientMainDestination.LIBRARY)
                            model.librarySection(LibrarySection.TRASH)
                        }
                        SidebarQuickItem(
                            "存储工具",
                            R.drawable.ic_storage,
                            state.tab == "library" && state.librarySection == LibrarySection.TOOLS,
                        ) {
                            dismissEmbeddedViewersForSwitch(model)
                            model.navigateMain(ClientMainDestination.LIBRARY)
                            model.librarySection(LibrarySection.TOOLS)
                        }
                        HorizontalDivider(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), color = colors.outlineVariant.copy(alpha = 0.6f))
                        SidebarSectionTitle("系统与管理")
                        SidebarQuickItem("缓存图片管理", R.drawable.ic_image, state.tab == "cache-images") {
                            dismissEmbeddedViewersForSwitch(model)
                            model.tab("cache-images")
                        }
                        SidebarQuickItem("账户设置", R.drawable.ic_person, state.tab == "account") {
                            dismissEmbeddedViewersForSwitch(model)
                            model.tab("account")
                        }
                        if (state.permissions.admin) {
                            SidebarQuickItem("用户管理", R.drawable.ic_person, state.tab == "admin-users") {
                                dismissEmbeddedViewersForSwitch(model)
                                model.tab("admin-users")
                            }
                            SidebarQuickItem("全局设置", R.drawable.ic_settings, state.tab == "server-settings") {
                                dismissEmbeddedViewersForSwitch(model)
                                model.tab("server-settings")
                            }
                        }
                        if (state.permissions.execute) {
                            SidebarQuickItem("命令", R.drawable.ic_terminal, state.tab == "shell") {
                                dismissEmbeddedViewersForSwitch(model)
                                model.openShell()
                            }
                        }
                    } else {
                        SidebarItem("连接 TV", Art.drawable.fileway_ic_cast, false, onOpenTvControl)
                        SidebarItem("连接服务器", R.drawable.ic_server, false, model::showConnection)
                        SidebarItem("下载文件", R.drawable.ic_download, state.tab == "downloads", model::openDownloads)
                        SidebarItem("缓存图片管理", R.drawable.ic_image, state.tab == "cache-images") { model.tab("cache-images") }
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), color = colors.outlineVariant.copy(alpha = 0.6f))
                    SidebarItem("更新", R.drawable.ic_update, state.tab == "updates") {
                        dismissEmbeddedViewersForSwitch(model)
                        model.openUpdates()
                    }
                    SidebarItem("关于栖卷 · Fileway", R.drawable.ic_info, false, onOpenAbout)
                }
                HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.65f))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(10.dp)
                        .selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    AppTheme.entries.forEach { mode ->
                        val label = when (mode) {
                            AppTheme.SYSTEM -> "系统"
                            AppTheme.LIGHT -> "浅色"
                            AppTheme.DARK -> "深色"
                        }
                        val icon = when (mode) {
                            AppTheme.SYSTEM -> R.drawable.ic_theme_system
                            AppTheme.LIGHT -> R.drawable.ic_theme_light
                            AppTheme.DARK -> R.drawable.ic_theme_dark
                        }
                        val chosen = appearance.theme == mode
                        Surface(
                            Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .clip(MaterialTheme.shapes.medium)
                                .selectable(
                                    chosen,
                                    enabled = appearance.loaded && !appearance.saving,
                                    role = Role.RadioButton,
                                ) { model.appearance.save(mode) }
                                .semantics {
                                    contentDescription = if (mode == AppTheme.SYSTEM) "跟随系统主题" else "${label}主题"
                                },
                            shape = MaterialTheme.shapes.medium,
                            color = if (chosen) colors.primary.copy(alpha = 0.12f) else Color.Transparent,
                        ) {
                            Column(
                                Modifier.fillMaxWidth().padding(vertical = 5.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
                            ) {
                                FilewayIcon(
                                    icon,
                                    null,
                                    Modifier.size(18.dp),
                                    tint = if (chosen) colors.primary else colors.onSurfaceVariant,
                                )
                                Text(
                                    label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (chosen) colors.primary else colors.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
        VerticalDivider(color = colors.outlineVariant.copy(alpha = 0.65f))
    }
}

@Composable
private fun SidebarSectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun SidebarItem(
    title: String,
    icon: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    NavigationDrawerItem(
        colors = NavigationDrawerItemDefaults.colors(
            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.11f),
            unselectedContainerColor = Color.Transparent,
            selectedIconColor = MaterialTheme.colorScheme.primary,
            selectedTextColor = MaterialTheme.colorScheme.primary,
        ),
        label = { Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        selected = selected,
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        icon = { FilewayIcon(icon, null, Modifier.size(20.dp)) },
        modifier = Modifier.padding(horizontal = 8.dp).heightIn(min = 42.dp),
    )
}

/**
 * Secondary quick-access item in the expanded sidebar.
 * Uses a subtle accent dot + primary text when active instead of a competing full pill,
 * keeping top-level navigation hierarchy unmistakable.
 */
@Composable
private fun SidebarQuickItem(
    title: String,
    icon: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 40.dp)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FilewayIcon(
            icon,
            null,
            Modifier.size(19.dp),
            tint = if (selected) colors.primary else colors.onSurfaceVariant,
        )
        Text(
            title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) colors.primary else colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (selected) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(colors.primary),
            )
        }
    }
}

/**
 * Hardware keyboard shortcut modifier for tablet + keyboard productivity.
 * Only handles non-typing navigation/action chords so text inputs are never intercepted.
 */
fun Modifier.filewayHardwareShortcuts(
    onSearch: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    onToggleFullscreen: (() -> Unit)? = null,
    onMovePrevious: (() -> Unit)? = null,
    onMoveNext: (() -> Unit)? = null,
    onOpenSelected: (() -> Unit)? = null,
): Modifier = onPreviewKeyEvent { event ->
    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    val ctrlOrMeta = event.isCtrlPressed || event.isMetaPressed
    when {
        ctrlOrMeta && event.key == Key.F && onSearch != null -> {
            onSearch(); true
        }
        event.key == Key.Escape && onBack != null -> {
            onBack(); true
        }
        event.key == Key.F11 && onToggleFullscreen != null -> {
            onToggleFullscreen(); true
        }
        ctrlOrMeta && event.key == Key.DirectionUp && onMovePrevious != null -> {
            onMovePrevious(); true
        }
        ctrlOrMeta && event.key == Key.DirectionDown && onMoveNext != null -> {
            onMoveNext(); true
        }
        ctrlOrMeta && event.key == Key.Enter && onOpenSelected != null -> {
            onOpenSelected(); true
        }
        else -> false
    }
}
