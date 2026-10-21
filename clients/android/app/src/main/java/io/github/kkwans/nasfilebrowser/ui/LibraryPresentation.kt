package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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

/** Tail-oriented display only. Keep libraryParent/raw paths for every operation. */
internal fun libraryLocation(path: String): String {
    val parent = libraryParent(path)
    val parts = parent.split('/').filter { it.isNotEmpty() }
    return if (parts.size <= 2) parent else "…/" + parts.takeLast(2).joinToString("/")
}

@Composable internal fun LibraryObjectCard(
    name: String, location: String, icon: Int, modifier: Modifier = Modifier, summary: String? = null,
    onOpen: (() -> Unit)? = null, enabled: Boolean = true,
    leading: @Composable RowScope.() -> Unit = {}, trailing: @Composable RowScope.() -> Unit = {},
    artwork: (@Composable () -> Unit)? = null,
    context: @Composable ColumnScope.() -> Unit = {},
) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).then(if (onOpen != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onOpen) else Modifier),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                leading()
                if (artwork != null) artwork() else Icon(painterResource(icon), null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FileNameText(name, FilewayTheme.text.fileName, MaterialTheme.colorScheme.onSurface, maxLines = 2, centered = false)
                    // Group, size and time belong to the object's information
                    // axis, not beneath an unrelated action at the right edge.
                    Text(listOfNotNull(summary?.takeIf { it.isNotBlank() }, location.takeIf { it.isNotBlank() }).joinToString(" · "),
                        style = FilewayTheme.text.metadata, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                trailing()
            }
            context()
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
