package io.github.kkwans.nasfilebrowser.ui.medialibrary

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.kkwans.nasfilebrowser.ui.FilewayIcon
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayMediaSectionHeader
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayMediaSectionHeaderStyle
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayMediaSpace
import kotlinx.coroutines.delay

/** Detail roles follow the native media contract while retaining the app's system font. */
internal object PhoneDetailType {
    val title @Composable get() = PhoneMediaType.workTitle
    val section @Composable get() = PhoneMediaType.sectionTitle
    val body @Composable get() = PhoneMediaType.body
    val metadata @Composable get() = PhoneMediaType.secondary
    val item @Composable get() = PhoneMediaType.itemTitle
    val action @Composable get() = PhoneMediaType.action
}

@Composable
internal fun PhoneDetailHeading(title: String, modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {}) {
    FilewayMediaSectionHeader(
        title = title,
        modifier = modifier,
        style = FilewayMediaSectionHeaderStyle(
            titleStyle = PhoneDetailType.section,
            titleColor = MaterialTheme.colorScheme.onBackground,
            minHeight = FilewayMediaSpace.Phone.touchTarget,
        ),
        actions = actions,
    )
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
