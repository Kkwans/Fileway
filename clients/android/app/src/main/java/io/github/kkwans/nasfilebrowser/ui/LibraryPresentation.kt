package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayTheme

/** Display only: never use a shortened name/location to construct a resource identity. */
internal fun libraryBasename(path: String): String = path.trimEnd('/').substringAfterLast('/').ifEmpty { "/" }
internal fun libraryParent(path: String): String = path.trimEnd('/').substringBeforeLast('/', "").ifEmpty { "/" }

@Composable internal fun LibraryObjectCard(
    name: String, location: String, icon: Int, modifier: Modifier = Modifier,
    onOpen: (() -> Unit)? = null, enabled: Boolean = true,
    leading: @Composable RowScope.() -> Unit = {}, trailing: @Composable RowScope.() -> Unit = {},
    context: @Composable ColumnScope.() -> Unit = {},
) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.Top) {
            leading()
            Row(Modifier.weight(1f).then(if (onOpen != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onOpen) else Modifier)
                .padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.Top) {
                Icon(painterResource(icon), null, Modifier.padding(top = 2.dp).size(28.dp), tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FileNameText(name, FilewayTheme.text.fileName, MaterialTheme.colorScheme.onSurface, maxLines = 2, centered = false)
                    Text(location, style = FilewayTheme.text.metadata, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    context()
                }
            }
            trailing()
        }
    }
}

@Composable internal fun LibrarySectionLabel(title: String, subtitle: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            subtitle?.let { Text(it, style = FilewayTheme.text.metadata, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        actions()
    }
}
