package io.github.kkwans.nasfilebrowser.ui.medialibrary

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kkwans.nasfilebrowser.ui.FilewayIcon
import kotlinx.coroutines.delay

/** Detail roles follow the native media contract while retaining the app's system font. */
internal object PhoneDetailType {
    val title @Composable get() = MaterialTheme.typography.displaySmall
    val section @Composable get() = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Medium)
    val body @Composable get() = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp)
    val metadata @Composable get() = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp)
    val item @Composable get() = body.copy(fontWeight = FontWeight.Medium)
    val action @Composable get() = metadata.copy(fontWeight = FontWeight.Medium)
}

@Composable
internal fun PhoneDetailHeading(title: String, modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f).semantics { heading() }, style = PhoneDetailType.section)
        actions()
    }
}

/** Conditional composition cancels the delay immediately when the request finishes. */
@Composable
internal fun PhoneDetailLoading(label: String, modifier: Modifier = Modifier) {
    var visible by remember(label) { mutableStateOf(false) }
    LaunchedEffect(label) { delay(300); visible = true }
    if (visible) Row(modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(label, style = PhoneDetailType.metadata, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun PhoneDetailAction(label: String, icon: Int, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true) {
    TextButton(onClick, modifier.heightIn(min = 48.dp), enabled = enabled,
        shape = MaterialTheme.shapes.medium, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)) {
        FilewayIcon(icon, null, Modifier.size(24.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = PhoneDetailType.action)
    }
}
