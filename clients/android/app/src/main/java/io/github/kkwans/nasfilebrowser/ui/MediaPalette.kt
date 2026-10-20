package io.github.kkwans.nasfilebrowser.ui

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import coil3.toBitmap
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import io.github.kkwans.nasfilebrowser.app.ClientModel
import io.github.kkwans.nasfilebrowser.app.ResourceRef
import io.github.kkwans.nasfilebrowser.data.PreviewLease
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayPlayerColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class MediaAccent(val tertiary: Color, val tertiaryContainer: Color)

/** Palette owns quantization and scoring; the page only consumes its accent roles. */
private val mediaAccentCache = object : LruCache<String, MediaAccent>(96) {}

@Composable
internal fun rememberMediaColors(model: ClientModel, file: ResourceRef): ColorScheme {
    val context = LocalContext.current
    val cacheKey = model.thumbnailKey(file)
    var accent by remember(cacheKey) { mutableStateOf(mediaAccentCache.get(cacheKey)) }

    LaunchedEffect(cacheKey) {
        if (accent != null) return@LaunchedEffect
        try {
            val loaded = loadMediaAccent(model, file, context, cacheKey)
            if (loaded != null) {
                mediaAccentCache.put(cacheKey, loaded)
                accent = loaded
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Keep the graphite player and blue fallback usable when a preview is unavailable.
        }
    }

    val selected = accent ?: MediaAccent(FilewayPlayerColors.tertiary, FilewayPlayerColors.tertiaryContainer)
    return FilewayPlayerColors.copy(tertiary = selected.tertiary, tertiaryContainer = selected.tertiaryContainer)
}

private suspend fun loadMediaAccent(model: ClientModel, file: ResourceRef, context: android.content.Context, cacheKey: String): MediaAccent? =
    withContext(Dispatchers.IO) {
        var lease: PreviewLease? = null
        try {
            val ownedLease = model.thumbnailPreview(file)
            lease = ownedLease
            val request = ImageRequest.Builder(context)
                .data(ownedLease.url)
                .diskCacheKey(cacheKey + "/contain")
                .memoryCacheKey(cacheKey + "/palette")
                .size(160, 160)
                .allowHardware(false)
                .build()
            val result = model.previewImageLoader.execute(request)
            val image = (result as? SuccessResult)?.image ?: return@withContext null
            val bitmap = image.toBitmap(image.width.coerceAtLeast(1), image.height.coerceAtLeast(1))
            withContext(Dispatchers.Default) { accentFrom(bitmap) }
        } finally {
            runCatching { lease?.release() }
        }
    }

private fun accentFrom(bitmap: Bitmap): MediaAccent {
    val palette = Palette.from(bitmap).maximumColorCount(16).generate()
    val swatch = listOfNotNull(
        palette.mutedSwatch,
        palette.darkMutedSwatch,
        palette.lightMutedSwatch,
        palette.dominantSwatch,
    ).firstOrNull(::isUsableSwatch)
    if (swatch == null) return MediaAccent(FilewayPlayerColors.tertiary, FilewayPlayerColors.tertiaryContainer)
    val rgb = swatch.rgb
    val container = Color(ColorUtils.blendARGB(FilewayPlayerColors.surfaceContainerHigh.toArgb(), rgb, .34f))
    return MediaAccent(Color(rgb), container)
}

private fun isUsableSwatch(swatch: Palette.Swatch): Boolean {
    val hsl = swatch.hsl
    val saturation = hsl[1]
    val lightness = hsl[2]
    val luminance = ColorUtils.calculateLuminance(swatch.rgb)
    val contrast = ColorUtils.calculateContrast(swatch.rgb, FilewayPlayerColors.surface.toArgb())
    val warmDarkBrown = hsl[0] in 0f..60f && saturation >= .20f && lightness < .42f
    return saturation >= .18f && lightness in .20f.. .88f && luminance >= .12f && contrast >= 2f && !warmDarkBrown
}
