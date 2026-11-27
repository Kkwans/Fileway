package io.github.kkwans.nasfilebrowser.ui.adaptive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Responsive window width buckets driven solely by real-time available window bounds,
 * never by device model or static hardware checks.
 */
enum class FilewayWindowClass {
    /** < 600dp: phone portrait, folded outer screen, narrow split-screen or freeform window. */
    COMPACT,

    /** 600dp..839dp: 8" tablet portrait, unfolded foldable, 11" tablet portrait or 50% split-screen. */
    MEDIUM,

    /** 840dp..1199dp: 10"-12" tablet landscape, primary dual-pane workspace. */
    EXPANDED,

    /** >= 1200dp: 12.4"-14.6" large tablet landscape, external monitor or desktop windowing. */
    LARGE,
}

enum class FilewayHeightClass {
    /** < 480dp: phone landscape or short split-screen. */
    COMPACT,

    /** 480dp..899dp: standard phone portrait or tablet landscape. */
    MEDIUM,

    /** >= 900dp: tablet portrait or tall desktop monitor. */
    EXPANDED,
}

@Immutable
data class FilewayWindowLayout(
    val widthDp: Int,
    val heightDp: Int,
    val windowClass: FilewayWindowClass,
    val heightClass: FilewayHeightClass,
    /** Normalized horizontal hinge position (0f..1f) when a vertical separating fold is active. */
    val hingeCenterFraction: Float? = null,
) {
    val isCompact: Boolean get() = windowClass == FilewayWindowClass.COMPACT
    val isMedium: Boolean get() = windowClass == FilewayWindowClass.MEDIUM
    val isExpanded: Boolean get() = windowClass == FilewayWindowClass.EXPANDED
    val isLarge: Boolean get() = windowClass == FilewayWindowClass.LARGE

    /** Show side NavigationRail / Permanent sidebar instead of bottom NavigationBar. */
    val useNavigationRail: Boolean get() = windowClass != FilewayWindowClass.COMPACT

    /** Default to an expanded persistent sidebar on >= 1200dp displays. */
    val usePermanentDrawerDefault: Boolean get() = windowClass == FilewayWindowClass.LARGE

    /** Enable resizable Master-Detail dual-pane workspace. */
    val useDualPane: Boolean get() = windowClass == FilewayWindowClass.EXPANDED || windowClass == FilewayWindowClass.LARGE

    /** Present action sheets as bounded centered/side panels instead of full-width bottom sheets. */
    val useLargeDialogs: Boolean get() = windowClass != FilewayWindowClass.COMPACT

    val horizontalPadding: Dp get() = if (isCompact) 16.dp else 24.dp
    val readerMaxWidth: Dp get() = 780.dp
    val formMaxWidth: Dp get() = 680.dp

    companion object {
        val CompactDefault = from(widthDp = 412, heightDp = 915, smallestScreenWidthDp = 412)

        fun from(
            widthDp: Int,
            heightDp: Int,
            smallestScreenWidthDp: Int = minOf(widthDp, heightDp),
            hingeCenterFraction: Float? = null,
        ): FilewayWindowLayout {
            val safeWidth = widthDp.coerceAtLeast(1)
            val safeHeight = heightDp.coerceAtLeast(1)
            val safeSmallestWidth = smallestScreenWidthDp.coerceAtLeast(1)
            val phoneLandscape = safeHeight < 480 || (safeSmallestWidth < 580 && safeHeight < 520)
            val wClass = when {
                phoneLandscape || safeWidth < 600 -> FilewayWindowClass.COMPACT
                safeWidth < 840 -> FilewayWindowClass.MEDIUM
                safeWidth < 1200 -> FilewayWindowClass.EXPANDED
                else -> FilewayWindowClass.LARGE
            }
            val hClass = when {
                safeHeight < 480 -> FilewayHeightClass.COMPACT
                safeHeight < 900 -> FilewayHeightClass.MEDIUM
                else -> FilewayHeightClass.EXPANDED
            }
            val validHinge = hingeCenterFraction?.takeIf { it in 0.2f..0.8f }
            return FilewayWindowLayout(
                widthDp = safeWidth,
                heightDp = safeHeight,
                windowClass = wClass,
                heightClass = hClass,
                hingeCenterFraction = validHinge,
            )
        }
    }
}

val LocalFilewayWindowLayout = staticCompositionLocalOf { FilewayWindowLayout.CompactDefault }

@Composable
fun rememberFilewayWindowLayout(): FilewayWindowLayout {
    val container = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val widthDp = if (container.width > 0) {
        with(density) { container.width.toDp().value.toInt() }
    } else {
        configuration.screenWidthDp
    }
    val heightDp = if (container.height > 0) {
        with(density) { container.height.toDp().value.toInt() }
    } else {
        configuration.screenHeightDp
    }
    val smallestWidthDp = configuration.smallestScreenWidthDp
        .takeIf { it > 0 }
        ?: minOf(widthDp, heightDp)
    return remember(widthDp, heightDp, smallestWidthDp) {
        FilewayWindowLayout.from(
            widthDp = widthDp,
            heightDp = heightDp,
            smallestScreenWidthDp = smallestWidthDp,
        )
    }
}
