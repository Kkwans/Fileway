package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.kkwans.nasfilebrowser.R

@Composable internal fun FilewayDirectoryRow(name: String, enabled: Boolean = true, reason: String? = null, open: () -> Unit) {
    val foreground = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else .38f)
    Surface(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
        .clickable(enabled = enabled, role = Role.Button, onClick = open).semantics { contentDescription = "目标目录：$name" },
        shape = MaterialTheme.shapes.medium, color = filewayGlassLayerColor()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                FilewayIcon(R.drawable.ic_folder, null, tint = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else .38f))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(name, color = foreground, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                reason?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { FilewayIcon(R.drawable.ic_arrow_forward, null, tint = foreground) }
        }
    }
}
