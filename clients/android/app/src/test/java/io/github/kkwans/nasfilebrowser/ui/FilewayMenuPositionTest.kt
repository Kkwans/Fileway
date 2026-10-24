package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Test

class FilewayMenuPositionTest {
    private val safe = IntRect(48, 48, 1152, 2500)

    @Test fun leftToolbarWithSmallerInsetStaysNearItsButton() {
        assertEquals(IntOffset(48, 264), filewayMenuPosition(
            IntRect(24, 120, 240, 264), safe, IntSize(400, 900), LayoutDirection.Ltr))
    }

    @Test fun rightToolbarStillAlignsToItsButtonEnd() {
        assertEquals(IntOffset(680, 264), filewayMenuPosition(
            IntRect(936, 120, 1080, 264), safe, IntSize(400, 900), LayoutDirection.Ltr))
    }

    @Test fun rtlAnchorNearLeftEdgeClampsLocally() {
        assertEquals(IntOffset(48, 264), filewayMenuPosition(
            IntRect(24, 120, 240, 264), safe, IntSize(400, 900), LayoutDirection.Rtl))
    }
}
