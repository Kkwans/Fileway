package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import coil3.toBitmap
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.app.ResourceRef
import io.github.kkwans.nasfilebrowser.app.mediaKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Palette owns quantization and scoring; the page only constrains its tonal role. */
@Composable internal fun rememberMediaColors(model: ClientModel, file: ResourceRef): androidx.compose.material3.ColorScheme {
    val base = MaterialTheme.colorScheme
    val dark = base.background.luminance() < .5f
    val context = LocalContext.current
    var hue by remember(file.mediaKey) { mutableStateOf<Float?>(null) }
    LaunchedEffect(file.mediaKey, file.size, file.modified) {
        try {
            val lease = model.thumbnailPreview(file)
            try {
                val result = model.previewImageLoader.execute(ImageRequest.Builder(context).data(lease.url).diskCacheKey(model.thumbnailKey(file) + "/contain").memoryCacheKey(model.thumbnailKey(file) + "/palette").size(160, 160).allowHardware(false).build())
                val image = (result as? SuccessResult)?.image
                val bitmap = image?.toBitmap(image.width.coerceAtLeast(1), image.height.coerceAtLeast(1))
                if (bitmap != null) hue = withContext(Dispatchers.Default) {
                    val palette = Palette.from(bitmap).maximumColorCount(16).generate()
                    val swatch = palette.mutedSwatch ?: palette.darkMutedSwatch ?: palette.lightMutedSwatch ?: palette.dominantSwatch
                    swatch?.hsl?.get(0)
                }
            } finally { lease.release() }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { hue = null }
    }
    val h = hue ?: 218f
    // Media remains dark in either app theme; selected image hue stays visible rather than bleaching to white.
    val background = Color(ColorUtils.HSLToColor(floatArrayOf(h, .26f, .13f)))
    val surface = Color(ColorUtils.HSLToColor(floatArrayOf(h, .22f, .18f)))
    val foreground = Color(0xFFF3F3F3)
    val secondary = Color(ColorUtils.HSLToColor(floatArrayOf(h, .12f, .76f)))
    return base.copy(background = background, surface = surface, surfaceContainer = surface,
        surfaceContainerLow = background, surfaceContainerHigh = surface, surfaceVariant = surface,
        onBackground = foreground, onSurface = foreground, onSurfaceVariant = secondary,
        primary = Color(ColorUtils.HSLToColor(floatArrayOf(h, .42f, .78f))))
}
