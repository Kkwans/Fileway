package io.github.kkwans.nasfilebrowser

import android.graphics.Outline
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.kkwans.nasfilebrowser.ui.filewayWindowGlassBackground
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** A path outline may look rounded in Compose yet produces rectangular native backdrop blur. */
@RunWith(AndroidJUnit4::class)
class WindowGlassOutlineTest {
    @Test fun floatingGlassReportsTheRoundRectRadiusRequiredByDecorView() {
        for ((width, height) in listOf(330 to 84, 528 to 444, 660 to 168)) {
            val background = filewayWindowGlassBackground(36f, bottomAttached = false)
            background.setBounds(0, 0, width, height)
            val outline = Outline()
            background.getOutline(outline)
            val bounds = Rect()
            assertTrue("Native blur only reads ROUND_RECT outlines", outline.getRect(bounds))
            assertEquals(Rect(0, 0, width, height), bounds)
            assertEquals(36f, outline.radius, 0f)
        }
    }
}
