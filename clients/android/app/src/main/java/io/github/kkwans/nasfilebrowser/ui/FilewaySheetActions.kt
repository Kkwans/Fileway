package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** The footer consumes the same capability entries as the corresponding menu. */
@Composable internal fun RowScope.FilewaySheetActions(actions: List<FilewayMenuEntry>, onAction: (String) -> Unit) {
    actions.forEach { action ->
        val color = if (action.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        FilewayOutlinedButton({ onAction(action.key) }, Modifier.weight(1f).heightIn(min = 64.dp)
            .semantics { contentDescription = action.accessibilityLabel ?: action.label }, enabled = action.enabled,
            colors = ButtonDefaults.outlinedButtonColors(containerColor = filewayGlassLayerColor(), contentColor = color),
            border = filewayGlassBorder(inner = true), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                action.icon?.let { Icon(painterResource(it), null, Modifier.size(24.dp)) }
                Text(action.label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
