package io.github.kkwans.nasfilebrowser.ui.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val BlueLightColors = lightColorScheme(
    primary = FilewayPalette.blue, onPrimary = Color.White,
    primaryContainer = Color(0xFFEAF2FF), onPrimaryContainer = Color(0xFF154EA9),
    secondary = Color(0xFF526178), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE9EEF6), onSecondaryContainer = Color(0xFF24354B),
    tertiary = Color(0xFF6B647E), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF0ECF7), onTertiaryContainer = Color(0xFF494057),
    background = FilewayPalette.lightCanvas, onBackground = FilewayPalette.lightText,
    surface = Color.White, onSurface = FilewayPalette.lightText,
    surfaceDim = Color(0xFFE1E6EE), surfaceBright = Color.White,
    surfaceContainerLowest = Color.White, surfaceContainerLow = FilewayPalette.lightCanvas,
    surfaceContainer = Color(0xFFF0F3F8), surfaceContainerHigh = Color(0xFFEAF0F7),
    surfaceContainerHighest = Color(0xFFE2E9F2),
    surfaceVariant = Color(0xFFF0F3F8), onSurfaceVariant = FilewayPalette.lightSecondary,
    outline = Color(0xFF7A8799), outlineVariant = Color(0xFFDEE5EF),
    inverseSurface = Color(0xFF243246), inverseOnSurface = Color(0xFFEEF3FA),
    inversePrimary = FilewayPalette.darkAccent, surfaceTint = FilewayPalette.blue,
    error = Color(0xFFB33835), onError = Color.White,
    errorContainer = Color(0xFFF9E6E2), onErrorContainer = Color(0xFF7E2525),
    scrim = FilewayPalette.darkCanvas,
)

private val BlueDarkColors = darkColorScheme(
    primary = FilewayPalette.darkAccent, onPrimary = Color(0xFF102D5B),
    primaryContainer = Color(0xFF203A61), onPrimaryContainer = Color(0xFFCDDEFF),
    secondary = Color(0xFFB7C6DD), onSecondary = Color(0xFF293B54),
    secondaryContainer = Color(0xFF304158), onSecondaryContainer = Color(0xFFDCE6F5),
    tertiary = Color(0xFFCFC3E1), onTertiary = Color(0xFF383047),
    tertiaryContainer = Color(0xFF4C415D), onTertiaryContainer = Color(0xFFEADFF5),
    background = FilewayPalette.darkCanvas, onBackground = FilewayPalette.darkText,
    surface = FilewayPalette.darkSurface, onSurface = FilewayPalette.darkText,
    surfaceDim = FilewayPalette.darkCanvas, surfaceBright = Color(0xFF34445B),
    surfaceContainerLowest = Color(0xFF0C1119), surfaceContainerLow = Color(0xFF151E2B),
    surfaceContainer = Color(0xFF1E2B3C), surfaceContainerHigh = Color(0xFF243246),
    surfaceContainerHighest = Color(0xFF2A3B51),
    surfaceVariant = Color(0xFF243246), onSurfaceVariant = FilewayPalette.darkSecondary,
    outline = Color(0xFF8396B1), outlineVariant = Color(0xFF354760),
    inverseSurface = FilewayPalette.darkText, inverseOnSurface = FilewayPalette.lightText,
    inversePrimary = FilewayPalette.blue, surfaceTint = FilewayPalette.darkAccent,
    error = Color(0xFFFFB4AA), onError = Color(0xFF571D19),
    errorContainer = Color(0xFF4E2926), onErrorContainer = Color(0xFFFFDAD3),
    scrim = Color.Black,
)

private val FilewayShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(12.dp),
    extraSmall = RoundedCornerShape(12.dp),
)

/** Stable neutral player roles; artwork is allowed to occupy tertiary accents only. */
internal val FilewayPlayerColors = BlueDarkColors.copy(
    background = FilewayPalette.playerCanvas, onBackground = Color(0xFFF1F3F5),
    surface = FilewayPalette.playerSurface, onSurface = Color(0xFFF1F3F5),
    surfaceDim = FilewayPalette.playerCanvas, surfaceBright = Color(0xFF242A34),
    surfaceContainerLowest = FilewayPalette.playerCanvas, surfaceContainerLow = FilewayPalette.playerSurface,
    surfaceContainer = FilewayPalette.playerSurface, surfaceContainerHigh = Color(0xFF242A34),
    surfaceContainerHighest = Color(0xFF242A34),
    surfaceVariant = Color(0xFF242A34), onSurfaceVariant = Color(0xFFADB4BE),
    outline = Color(0xFF59626E), outlineVariant = Color(0xFF39424E),
    inverseSurface = Color(0xFFF1F3F5), inverseOnSurface = FilewayPalette.playerCanvas,
    secondary = Color(0xFFADB4BE), onSecondary = FilewayPalette.playerSurface,
    secondaryContainer = Color(0xFF333B47), onSecondaryContainer = Color(0xFFDCE1E7),
    tertiary = BlueDarkColors.primary, onTertiary = BlueDarkColors.onPrimary,
    tertiaryContainer = BlueDarkColors.primaryContainer, onTertiaryContainer = BlueDarkColors.onPrimaryContainer,
)

private val FilewayTypography = Typography(
    displayLarge = filewayTextStyle(36, 44, FontWeight.SemiBold),
    displayMedium = filewayTextStyle(32, 40, FontWeight.SemiBold),
    displaySmall = filewayTextStyle(28, 36, FontWeight.SemiBold),
    headlineLarge = filewayTextStyle(26, 34, FontWeight.SemiBold),
    headlineMedium = filewayTextStyle(24, 32, FontWeight.SemiBold),
    headlineSmall = filewayTextStyle(22, 30, FontWeight.SemiBold),
    titleLarge = filewayTextStyle(20, 28, FontWeight.SemiBold),
    titleMedium = filewayTextStyle(15, 22, FontWeight.SemiBold),
    titleSmall = filewayTextStyle(14, 20, FontWeight.Medium),
    bodyLarge = filewayTextStyle(17, 28),
    bodyMedium = filewayTextStyle(15, 22),
    bodySmall = filewayTextStyle(12, 18),
    labelLarge = filewayTextStyle(14, 20, FontWeight.Medium),
    labelMedium = filewayTextStyle(12, 16, FontWeight.Medium),
    labelSmall = filewayTextStyle(11, 16, FontWeight.Medium),
)

@Immutable data class FilewayTextRoles(
    val appBar: TextStyle = filewayTextStyle(18, 26, FontWeight.SemiBold),
    val fileName: TextStyle = filewayTextStyle(15, 22, FontWeight.Medium),
    val compactName: TextStyle = filewayTextStyle(14, 20, FontWeight.Medium),
    val metadata: TextStyle = filewayTextStyle(12, 18),
    val technical: TextStyle = filewayTextStyle(12, 18, mono = true),
    val clock: TextStyle = filewayTextStyle(13, 18, mono = true),
    val reading: TextStyle = filewayTextStyle(17, 28),
)

@Immutable data class FilewayStatusColors(
    val success: Color, val successContainer: Color, val onSuccessContainer: Color,
    val warning: Color, val warningContainer: Color, val onWarningContainer: Color,
    val mediaCanvas: Color = Color(0xFF10141B),
    val mediaSurface: Color = FilewayPalette.darkSurface,
    val mediaContent: Color = FilewayPalette.darkText,
    val mediaSecondary: Color = FilewayPalette.darkSecondary,
)

private val LightStatus = FilewayStatusColors(
    Color(0xFF26774F), Color(0xFFE5F0E6), Color(0xFF1E593A),
    Color(0xFF855D1C), Color(0xFFF5EAD2), Color(0xFF694912),
)
private val DarkStatus = FilewayStatusColors(
    Color(0xFF92D2AA), Color(0xFF243F2E), Color(0xFFBCEBCA),
    Color(0xFFE7C581), Color(0xFF493B22), Color(0xFFF1D698),
)
private val LocalFilewayColors = staticCompositionLocalOf { BlueLightColors }
private val LocalFilewayText = staticCompositionLocalOf { FilewayTextRoles() }
private val LocalFilewayStatus = staticCompositionLocalOf { LightStatus }

object FilewayTheme {
    /** Root roles remain available while legacy page palettes are migrated. */
    val colors: ColorScheme @Composable get() = LocalFilewayColors.current
    val text: FilewayTextRoles @Composable get() = LocalFilewayText.current
    val status: FilewayStatusColors @Composable get() = LocalFilewayStatus.current
}

object FilewaySpace {
    val tiny = 4.dp
    val small = 8.dp
    val content = 12.dp
    val page = 16.dp
    val section = 24.dp
    val target = 48.dp
    val appBar = 56.dp
    val navigation = 56.dp
}

object FilewayMotion {
    const val press = 100
    const val selection = 140
    const val menu = 160
    const val dialog = 180
    const val sheet = 250
    const val navigation = 210
}

@Composable fun FilewayTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (darkTheme) BlueDarkColors else BlueLightColors
    CompositionLocalProvider(
        LocalFilewayColors provides colors,
        LocalFilewayText provides FilewayTextRoles(),
        LocalFilewayStatus provides if (darkTheme) DarkStatus else LightStatus,
        LocalTextSelectionColors provides TextSelectionColors(colors.primary, colors.primary.copy(alpha = .22f)),
    ) {
        MaterialTheme(
            colorScheme = colors, typography = FilewayTypography,
            shapes = FilewayShapes,
            content = content,
        )
    }
}

/** Player content uses one graphite scheme; artwork remains a restrained tertiary accent. */
@Composable fun FilewayMediaTheme(colors: ColorScheme, fullscreen: Boolean, content: @Composable () -> Unit) {
    val playerColors = remember(colors.tertiary, colors.tertiaryContainer) { FilewayPlayerColors.copy(
        tertiary = colors.tertiary,
        tertiaryContainer = colors.tertiaryContainer,
    ) }
    val parentStatus = FilewayTheme.status
    val status = remember(parentStatus, playerColors, fullscreen) { parentStatus.copy(
        mediaCanvas = playerColors.background,
        mediaSurface = playerColors.surface.copy(alpha = if (fullscreen) .88f else 1f),
        mediaContent = playerColors.onSurface,
        mediaSecondary = playerColors.onSurfaceVariant,
    ) }
    CompositionLocalProvider(
        LocalFilewayColors provides playerColors,
        LocalFilewayStatus provides status,
        LocalTextSelectionColors provides TextSelectionColors(playerColors.primary, playerColors.primary.copy(alpha = .22f)),
    ) {
        MaterialTheme(colorScheme = playerColors, typography = FilewayTypography, shapes = FilewayShapes, content = content)
    }
}

/** Translucent controls over the system compositor's live video blur. */
@Composable internal fun FilewayPlayerPanelTheme(blurred: Boolean, content: @Composable () -> Unit) {
    val colors = remember(blurred) {
        if (blurred) FilewayPlayerColors.copy(
            surface = Color(0xFF171A20).copy(alpha = .56f),
            surfaceContainerLow = Color.White.copy(alpha = .04f),
            surfaceContainer = Color.White.copy(alpha = .05f),
            surfaceContainerHigh = Color.White.copy(alpha = .08f),
            surfaceContainerHighest = Color.White.copy(alpha = .11f),
            surfaceVariant = Color.White.copy(alpha = .07f),
            primaryContainer = FilewayPlayerColors.primary.copy(alpha = .18f),
        ) else FilewayPlayerColors.copy(surface = FilewayPlayerColors.surface.copy(alpha = .96f))
    }
    val parent = FilewayTheme.status
    val status = remember(parent, colors) { parent.copy(mediaSurface = colors.surface,
        mediaContent = colors.onSurface, mediaSecondary = colors.onSurfaceVariant) }
    CompositionLocalProvider(LocalFilewayColors provides colors, LocalFilewayStatus provides status) {
        MaterialTheme(colorScheme = colors, typography = FilewayTypography, shapes = FilewayShapes, content = content)
    }
}
