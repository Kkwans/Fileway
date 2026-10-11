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
import androidx.compose.ui.graphics.luminance
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kkwans.nasfilebrowser.data.AppTheme
import io.github.kkwans.nasfilebrowser.data.ConnectionMode
import io.github.kkwans.nasfilebrowser.update.UpdatePolicy
import kotlinx.coroutines.launch

/** Shared library and connection palette; the native player retains its dark theme. */
@Composable internal fun LibraryTheme(content: @Composable () -> Unit) {
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val background = if (dark) Color(0xFF141416) else Color.White
    val surface = if (dark) Color(0xFF202023) else Color(0xFFF5F5F7)
    val ink = if (dark) Color(0xFFF3F3F6) else Color(0xFF202023)
    MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(
        primary = if (dark) Color(0xFF69A8FF) else Color(0xFF1767E8),
        onPrimary = if (dark) Color(0xFF082B58) else Color.White,
        primaryContainer = if (dark) Color(0xFF162D4A) else Color(0xFFEAF2FF),
        onPrimaryContainer = if (dark) Color(0xFFB8D6FF) else Color(0xFF1553B5),
        background = background, onBackground = ink, surface = background, onSurface = ink,
        surfaceContainer = surface, surfaceContainerLow = surface, surfaceContainerHigh = surface,
        onSurfaceVariant = if (dark) Color(0xFFB5B5BE) else Color(0xFF63636D),
        outlineVariant = if (dark) Color(0xFF34343A) else Color(0xFFE9E9ED),
    ), content = content)
}

@Composable internal fun ClientNavigation(model: ClientModel, selected: String) {
    val labelStyle = MaterialTheme.typography.labelMedium
    val labelPixels = rememberTextMeasurer().measure("任务中心", style = labelStyle, maxLines = 1).size.height
    val barHeight = 64.dp + with(LocalDensity.current) { labelPixels.toDp() }
    NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp,
        modifier = Modifier.heightIn(min = barHeight)) {
        listOf(Triple("files", "文件", R.drawable.ic_folder), Triple("recent", "最近", R.drawable.ic_history),
            Triple("library", "资料库", R.drawable.ic_bookmark),
            Triple("taskcenter", "任务中心", R.drawable.ic_download),
            Triple("settings", "设置", R.drawable.ic_person)).forEach { (tab, label, icon) ->
            NavigationBarItem(selected = selected == tab, onClick = { model.tab(tab) },
                modifier = Modifier.semantics { contentDescription = "${label}导航" },
                icon = { Icon(painterResource(icon), null, Modifier.size(26.dp)) }, label = { Text(label, style = labelStyle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                colors = NavigationBarItemDefaults.colors(indicatorColor = Color.Transparent,
                    selectedIconColor = MaterialTheme.colorScheme.primary, selectedTextColor = MaterialTheme.colorScheme.primary,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant, unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun SettingsScreen(model: ClientModel, state: ClientState) {
    var profileDetails by remember { mutableStateOf(false) }
    var about by remember { mutableStateOf(false) }
    var repositoryError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    var themeSheet by remember { mutableStateOf(false) }
    var networkDetails by remember { mutableStateOf(false) }
    val network by model.networkState.collectAsStateWithLifecycle()
    val appearance by model.appearance.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    Scaffold(containerColor = MaterialTheme.colorScheme.surfaceContainer, bottomBar = { ClientNavigation(model, "settings") }, snackbarHost = { SnackbarHost(snackbar) }) { insets ->
        Box(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = 840.dp).fillMaxSize().semantics { contentDescription = "设置内容" },
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item { Text("设置", style = MaterialTheme.typography.titleLarge) }
                item {
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Box(Modifier.size(48.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                            Icon(painterResource(R.drawable.ic_person), null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(state.accountName, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(state.profile?.name.orEmpty(), style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                item {
                    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.background) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            SettingsShortcut("文件", R.drawable.ic_folder, Modifier.weight(1f)) { model.tab("files") }
                            SettingsShortcut("最近", R.drawable.ic_history, Modifier.weight(1f)) { model.tab("recent") }
                            SettingsShortcut("服务器", R.drawable.ic_network, Modifier.weight(1f), model::disconnect)
                        }
                    }
                }
                item {
                    SettingsGroup("账户") {
                        SettingsAction("账户设置", "共享偏好与修改密码", R.drawable.ic_person) { model.tab("account") }
                        if (state.permissions.admin) SettingsAction("用户管理", "账号、权限与访问规则", R.drawable.ic_person) { model.tab("admin-users") }
                        if (state.permissions.admin) SettingsAction("服务器设置", "全局配置与默认账户策略", R.drawable.ic_storage) { model.tab("server-settings") }
                        if (state.permissions.execute) SettingsAction("服务器命令", "在当前目录执行允许的单条命令", R.drawable.ic_storage, action = model::openShell)
                    }
                }
                item {
                    SettingsGroup("外观") {
                        SettingsAction("主题", when {
                            appearance.loading -> "正在读取"
                            appearance.saving -> "正在保存"
                            !appearance.loaded -> "读取失败"
                            else -> appearance.theme.label()
                        }, R.drawable.ic_visibility, enabled = appearance.loaded && !appearance.saving && !appearance.loading) { themeSheet = true }
                        appearance.error?.let { message ->
                            Text(message, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            if (!appearance.loaded) TextButton(onClick = model.appearance::reload) { Text("重试读取") }
                        }
                    }
                }
                item {
                    SettingsGroup("服务器与连接") {
                        SettingsAction("服务器档案", "NAS · ${if (state.profile?.network == ConnectionMode.TAILNET) "内嵌 Tailscale" else "直接连接"}", R.drawable.ic_storage) { profileDetails = true }
                        HorizontalDivider(Modifier.padding(start = 52.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        SettingsAction("应用内 Tailscale", network.label, R.drawable.ic_network) { networkDetails = true }
                    }
                }
                item { CacheSettingsPanel(model) { message ->
                    scope.launch {
                        snackbar.currentSnackbarData?.dismiss()
                        snackbar.showSnackbar(message, withDismissAction = true)
                    }
                } }
                item {
                    SettingsGroup("应用") {
                        SettingsAction("检查更新", "当前版本 ${BuildConfig.VERSION_NAME}", R.drawable.ic_download, action = model::openUpdates)
                        HorizontalDivider(Modifier.padding(start = 52.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        SettingsAction("关于应用", BuildConfig.VERSION_NAME, R.drawable.ic_info) { repositoryError = null; about = true }
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
    }, confirmButton = { TextButton(onClick = { networkDetails = false }) { Text("关闭") } })
    if (profileDetails) AlertDialog(onDismissRequest = { profileDetails = false }, title = { Text("服务器档案") }, text = {
        Column(Modifier.heightIn(max = 320.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(state.profile?.name.orEmpty()); Text(state.profile?.address.orEmpty()); Text("账号：${state.accountName}")
            Text(if (state.profile?.network == ConnectionMode.TAILNET) "连接方式：内嵌 Tailscale" else "连接方式：直接连接")
        }
    }, confirmButton = { TextButton(onClick = { profileDetails = false }) { Text("关闭") } })
    if (about) AlertDialog(onDismissRequest = { about = false }, title = { Text("栖卷 · Fileway") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("版本 ${BuildConfig.VERSION_NAME}\n原生 Android 客户端\nGPL-3.0-or-later")
            TextButton(onClick = {
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
            repositoryError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }, confirmButton = { TextButton(onClick = { about = false }) { Text("关闭") } })
}

/** One category per card; its actions remain full-width selectable rows. */
@Composable internal fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, Modifier.padding(start = 4.dp), style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "${title}设置分组" }) {
            Column(content = content)
        }
    }
}

@Composable private fun SettingsShortcut(label: String, icon: Int, modifier: Modifier, action: () -> Unit) {
    Column(modifier.clickable(onClick = action).heightIn(min = 76.dp).padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(painterResource(icon), null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable internal fun SettingsAction(title: String, subtitle: String, icon: Int, enabled: Boolean = true, action: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = action).heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(painterResource(icon), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
