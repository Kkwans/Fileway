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

internal data class PlayerArtworkPalette(val scheme: ColorScheme, val atmosphere: List<Color>)
private data class MediaAccent(val tertiary: Color, val tertiaryContainer: Color, val atmosphere: List<Color>)
private val fallbackAtmosphere = listOf(Color(0xFF233D47), Color(0xFF47364E), Color(0xFF263B56), Color(0xFF181A24))

/** Palette owns quantization and scoring; the page only consumes its accent roles. */
private val mediaAccentCache = object : LruCache<String, MediaAccent>(96) {}

@Composable
internal fun rememberPlayerArtwork(model: ClientModel, file: ResourceRef): PlayerArtworkPalette {
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

    val selected = accent ?: MediaAccent(FilewayPlayerColors.tertiary, FilewayPlayerColors.tertiaryContainer, fallbackAtmosphere)
    return remember(selected) {
        PlayerArtworkPalette(FilewayPlayerColors.copy(tertiary = selected.tertiary, tertiaryContainer = selected.tertiaryContainer), selected.atmosphere)
    }
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
    // Retain cinematic/desaturated and warm artwork instead of discarding it for blue.
    val swatches = listOfNotNull(palette.dominantSwatch, palette.mutedSwatch, palette.vibrantSwatch,
        palette.darkMutedSwatch, palette.lightMutedSwatch) + palette.swatches.sortedByDescending { it.population }
    val useful = swatches.filter { it.hsl[2] in .08f.. .94f }.distinctBy { it.rgb }
    if (useful.isEmpty()) return MediaAccent(FilewayPlayerColors.tertiary, FilewayPlayerColors.tertiaryContainer, fallbackAtmosphere)
    val distinct = mutableListOf<Palette.Swatch>()
    useful.forEach { candidate ->
        if (distinct.none { existing ->
            val hue = kotlin.math.abs(existing.hsl[0] - candidate.hsl[0])
            minOf(hue, 360f - hue) < 24f && kotlin.math.abs(existing.hsl[2] - candidate.hsl[2]) < .18f
        }) distinct.add(candidate)
    }
    val atmosphere = (0..2).map { index ->
        val hsl = distinct[index % distinct.size].hsl.copyOf()
        hsl[1] = hsl[1].coerceIn(.16f, .48f)
        hsl[2] = listOf(.27f, .20f, .32f)[index]
        Color(ColorUtils.HSLToColor(hsl))
    } + Color(0xFF171922)
    val primary = useful.first().hsl.copyOf().also { it[1] = it[1].coerceIn(.18f,.55f); it[2] = .72f }
    val accent = Color(ColorUtils.HSLToColor(primary))
    val container = Color(ColorUtils.blendARGB(FilewayPlayerColors.surfaceContainerHigh.toArgb(), accent.toArgb(), .34f))
    return MediaAccent(accent, container, atmosphere)
}
