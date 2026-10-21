package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.app.LibrarySection

@Composable internal fun libraryChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = .10f), selectedLabelColor = MaterialTheme.colorScheme.primary,
    selectedLeadingIconColor = MaterialTheme.colorScheme.primary)

private val LocalLibraryPager = staticCompositionLocalOf<PagerState?> { null }
private class LibraryChrome {
    var owner by mutableStateOf<LibrarySection?>(null)
    var actions by mutableStateOf<@Composable RowScope.() -> Unit>({})
}
private val LocalLibraryChrome = staticCompositionLocalOf<LibraryChrome?> { null }

@Composable internal fun LibraryPagerContent(model: ClientModel, state: io.github.kkwans.nasfilebrowser.app.ClientState,
    content: @Composable (LibrarySection) -> Unit) {
    val sections = LibrarySection.entries.filter { it != LibrarySection.TASKS }
    val pager = rememberFilewayPager(sections.indexOf(state.librarySection).coerceAtLeast(0), sections.size) { model.librarySection(sections[it]) }
    val holder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val parentActive = LocalFilewayPageActive.current
    val chrome = remember { LibraryChrome() }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, bottomBar = { ClientNavigation(model, "library") }) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets)) {
            FilewayPageHeader(sections[pager.currentPage].title, actions = chrome.actions)
            FilewayPagerTabs(pager, sections.map { it.label })
            HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), beyondViewportPageCount = 1) { page ->
                holder.SaveableStateProvider(sections[page].name) {
                    CompositionLocalProvider(LocalLibraryPager provides pager, LocalLibraryChrome provides chrome,
                        LocalFilewayPageActive provides (parentActive && page == pager.settledPage)) { content(sections[page]) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun LibraryScaffold(model: ClientModel, section: LibrarySection,
    actions: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    val chrome = LocalLibraryChrome.current
    if (chrome != null) {
        val latest = rememberUpdatedState(actions)
        val slot: @Composable RowScope.() -> Unit = remember(section) { { latest.value(this) } }
        val active = LocalFilewayPageActive.current
        DisposableEffect(chrome, section, active) {
            if (active) { chrome.owner = section; chrome.actions = slot }
            onDispose { if (chrome.owner == section) { chrome.owner = null; chrome.actions = {} } }
        }
        Column(Modifier.fillMaxSize()) { LibraryRefreshContent(model, section, content) }
        return
    }
    val pages = LibrarySection.entries.filter { it != LibrarySection.TASKS }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, bottomBar = { ClientNavigation(model, "library") }) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets)) {
            FilewayPageHeader(section.title, actions = actions)
            val pager = LocalLibraryPager.current
            if (pager != null) FilewayPagerTabs(pager, pages.map { it.label })
            else PrimaryTabRow(selectedTabIndex = pages.indexOf(section).coerceAtLeast(0), containerColor = MaterialTheme.colorScheme.background) {
                pages.forEach { page -> Tab(page == section, { model.librarySection(page) }, text = { Text(page.label) }) }
            }
            LibraryRefreshContent(model, section, content)
        }
    }
}

@Composable internal fun LibraryMessage(error: String?, notice: String?, loading: Boolean, refresh: () -> Unit) {
    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
    error?.let { Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(it, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
        FilewayTextButton(refresh, enabled = !loading) { Text("重试") }
    } }
    FilewayToast(notice)
}


@Composable internal fun FilewayToast(message: String?) {
    val context = LocalContext.current.applicationContext
    var shown by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(message) {
        if (message == null) shown = null
        else if (message.isNotBlank() && message != shown) {
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
            shown = message
        }
    }
}

@Composable private fun ColumnScope.LibraryRefreshContent(model: ClientModel, section: LibrarySection, content: @Composable ColumnScope.() -> Unit) {
            val favorite by model.favorites.state.collectAsStateWithLifecycle()
            val tags by model.tags.state.collectAsStateWithLifecycle()
            val trash by model.trash.state.collectAsStateWithLifecycle()
            val tools by model.storageTools.state.collectAsStateWithLifecycle()
            val refreshing = when (section) { LibrarySection.FAVORITES -> favorite.loading; LibrarySection.TAGS -> tags.loading || tags.pathsLoading; LibrarySection.TRASH -> trash.loading; LibrarySection.TOOLS -> tools.loading; else -> false }
            FilewayRefresh(refreshing, {
                when (section) {
                    LibrarySection.FAVORITES -> model.favorites.refresh()
                    LibrarySection.TAGS -> { model.tags.refresh(); tags.pathTag?.let { model.tags.loadPaths(it, tags.pathParent, parentWire = tags.pathParentWire) } }
                    LibrarySection.TRASH -> model.trash.refresh()
                    LibrarySection.TOOLS -> model.storageTools.refresh()
                    else -> Unit
                }
            }, Modifier.weight(1f).fillMaxWidth()) { Column(Modifier.fillMaxSize()) { content() } }
}
