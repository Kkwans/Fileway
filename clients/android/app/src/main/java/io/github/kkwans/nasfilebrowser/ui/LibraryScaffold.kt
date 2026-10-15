package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
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
    Scaffold(containerColor = MaterialTheme.colorScheme.surfaceContainer, bottomBar = { ClientNavigation(model, "library") }) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets)) {
            FilewayPageHeader(section.title, actions = actions)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val colors = libraryChipColors()
                LibrarySection.entries.filter { it != LibrarySection.TASKS }.forEach { page -> FilterChip(page == section, { model.librarySection(page) }, { Text(page.label) }, colors = colors,
                    modifier = Modifier.semantics { contentDescription = "${page.label}资料页" }) }
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
    notice?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}
