package io.github.kkwans.fileway.tv.medialibrary

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayMediaShape
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayMediaSpace
import io.github.kkwans.fileway.tv.tvTextStyle
import kotlin.math.floor

/** TV roles are measured in window dp and user-scaled sp, never physical output pixels. */
internal object MediaLayout {
    val pageInset get() = FilewayMediaSpace.Tv.pageInset
    val sectionGap get() = FilewayMediaSpace.Tv.sectionGap
    val itemGap get() = FilewayMediaSpace.Tv.itemGap
    val focusGutter get() = FilewayMediaSpace.Tv.focusGutter
    val shape = RoundedCornerShape(FilewayMediaShape.card)
    val rowPadding get() = PaddingValues(horizontal = pageInset, vertical = focusGutter)
}

internal object MediaType {
    val workTitle get() = tvTextStyle(36, 44, FontWeight.Medium)
    val pageTitle get() = tvTextStyle(28, 36, FontWeight.Medium)
    val sectionTitle get() = tvTextStyle(24, 32, FontWeight.Medium)
    val itemTitle get() = tvTextStyle(20, 28, FontWeight.Medium)
    val body get() = tvTextStyle(18, 28)
    val action get() = tvTextStyle(18, 24, FontWeight.Medium)
    val secondary get() = tvTextStyle(16, 24)
    val badge get() = tvTextStyle(16, 22, FontWeight.Medium)
}

/** Keep complete cards in the viewport; large text reduces columns instead of shrinking text. */
@Composable
internal fun mediaCardWidth(viewport: Dp, minimum: Dp, maximumColumns: Int): Dp {
    val scale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val available = (viewport - MediaLayout.pageInset * 2).coerceAtLeast(1.dp)
    val minimumWidth = minimum * scale
    val columns = floor((available + MediaLayout.itemGap) / (minimumWidth + MediaLayout.itemGap))
        .toInt().coerceIn(1, maximumColumns)
    return (available - MediaLayout.itemGap * (columns - 1)) / columns
}

/** Missing backdrops never borrow an old work's image or stretch a portrait into scenery. */
@Composable
internal fun MediaHeroCanvas(item: TvMediaCard, imageLoader: ImageLoader, modifier: Modifier) {
    BoxWithConstraints(modifier) {
        MediaBackdrop(item.backdrop, imageLoader, Modifier.matchParentSize())
        if (item.backdrop == null && item.poster != null && maxWidth >= 760.dp) {
            MediaArtwork(item.poster, imageLoader, Modifier.align(Alignment.CenterEnd)
                .padding(end = MediaLayout.pageInset, top = 80.dp, bottom = 32.dp)
                .width(176.dp).aspectRatio(2f / 3f).clip(MediaLayout.shape))
        }
    }
}
