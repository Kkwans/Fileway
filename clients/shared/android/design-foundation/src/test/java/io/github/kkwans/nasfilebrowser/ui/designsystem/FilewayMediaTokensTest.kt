package io.github.kkwans.nasfilebrowser.ui.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class FilewayMediaTokensTest {
    @Test
    fun mediaTextAndFocusMeetContrastTargets() {
        listOf(FilewayMediaPalette.dark, FilewayMediaPalette.light).forEach { colors ->
            assertTrue("primary text", contrast(colors.text, colors.canvas) >= 4.5)
            assertTrue("secondary text", contrast(colors.secondary, colors.canvas) >= 4.5)
            assertTrue("surface text", contrast(colors.text, colors.surface) >= 4.5)
            assertTrue("focus", contrast(colors.focus, colors.canvas) >= 3.0)
            assertTrue("accent content", contrast(colors.onAccent, colors.accent) >= 4.5)
            assertTrue("play content", contrast(colors.onPlay, colors.play) >= 4.5)
        }
    }

    @Test
    fun phoneAndTvKeepDistinctDensityAndSafeTargets() {
        assertEquals(16.dp, FilewayMediaSpace.Phone.pageInset)
        assertEquals(48.dp, FilewayMediaSpace.Tv.pageInset)
        assertEquals(48.dp, FilewayMediaSpace.Phone.touchTarget)
        assertEquals(48.dp, FilewayMediaSpace.Tv.remoteTarget)
        assertEquals(2.dp, FilewayMediaShape.focusRing)
        assertEquals(8.dp, FilewayMediaShape.focusOverflow)
    }

    private fun contrast(first: Color, second: Color): Double {
        val lighter = maxOf(luminance(first), luminance(second))
        val darker = minOf(luminance(first), luminance(second))
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun luminance(color: Color): Double {
        fun channel(value: Float): Double = if (value <= 0.04045f) {
            value / 12.92
        } else {
            ((value + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }
}
