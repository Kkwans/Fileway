package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import coil3.BitmapImage
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
            val lease = model.preview(file, contain = true)
            try {
                val result = model.previewImageLoader.execute(ImageRequest.Builder(context).data(lease.url).size(160, 160).allowHardware(false).build())
                val bitmap = ((result as? SuccessResult)?.image as? BitmapImage)?.bitmap
                if (bitmap != null) hue = withContext(Dispatchers.Default) {
                    val palette = Palette.from(bitmap).maximumColorCount(16).generate()
                    val swatch = palette.mutedSwatch ?: palette.darkMutedSwatch ?: palette.lightMutedSwatch
                    swatch?.hsl?.takeIf { it[1] in .08f.. .65f && it[2] in .12f.. .88f }?.get(0)
                }
            } finally { lease.release() }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { hue = null }
    }
    val h = hue ?: 218f
    val background = Color(ColorUtils.HSLToColor(floatArrayOf(h, if (hue == null) .12f else .16f, if (dark) .105f else .93f)))
    val surface = Color(ColorUtils.HSLToColor(floatArrayOf(h, .12f, if (dark) .15f else .96f)))
    return base.copy(background = background, surface = surface, surfaceContainer = surface,
        surfaceContainerLow = background, surfaceContainerHigh = surface, surfaceVariant = surface)
}
