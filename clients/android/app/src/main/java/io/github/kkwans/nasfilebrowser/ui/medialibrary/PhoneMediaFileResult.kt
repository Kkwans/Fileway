package io.github.kkwans.nasfilebrowser.ui.medialibrary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewaySpace

@Composable
internal fun PhoneMediaFileResult(fileName: String, item: MediaPoster, imageLoader: ImageLoader, onOpen: () -> Unit) {
    Card(onClick = onOpen, shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.fillMaxWidth().padding(FilewaySpace.content), horizontalArrangement = Arrangement.spacedBy(FilewaySpace.content),
            verticalAlignment = Alignment.CenterVertically) {
            MediaImage(item.poster, imageLoader, Modifier.width(64.dp).aspectRatio(2f / 3f))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(fileName, style = PhoneMediaType.itemTitle, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(item.title, style = PhoneMediaType.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (item.subtitle.isNotBlank()) Text(item.subtitle, style = PhoneMediaType.secondary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
