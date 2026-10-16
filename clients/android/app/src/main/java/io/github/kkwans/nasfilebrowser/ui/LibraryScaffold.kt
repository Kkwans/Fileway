package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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

@Composable internal fun LibraryScaffold(model: ClientModel, section: LibrarySection,
    actions: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    val pages = LibrarySection.entries.filter { it != LibrarySection.TASKS }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, bottomBar = { ClientNavigation(model, "library") }) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets)
            .swipeTabs(pages.indexOf(section).coerceAtLeast(0), pages.size) { model.librarySection(pages[it]) }) {
            FilewayPageHeader(section.title, actions = actions)
            @Suppress("DEPRECATION")
            TabRow(selectedTabIndex = pages.indexOf(section).coerceAtLeast(0), containerColor = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.primary, divider = {}) {
                pages.forEach { page -> Tab(selected = page == section, onClick = { model.librarySection(page) },
                    text = { Text(page.label) }, unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "${page.label}资料页" }) }
            }
            content()
        }
    }
}

@Composable internal fun LibraryMessage(error: String?, notice: String?, loading: Boolean, refresh: () -> Unit) {
    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
    error?.let { Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(it, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
        TextButton(refresh, enabled = !loading) { Text("重试") }
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
