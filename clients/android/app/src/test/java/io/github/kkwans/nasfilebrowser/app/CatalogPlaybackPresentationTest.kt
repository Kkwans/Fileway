package io.github.kkwans.nasfilebrowser.app

import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogPlaybackPresentationTest {
    @Test
    fun catalogPlaybackUsesWorkTitleWhenAvailable() {
        assertEquals("娼", catalogPlaybackDisplayName("Chang 1997 Dvdrip Xvid.avi", " 娼 "))
    }

    @Test
    fun catalogPlaybackFallsBackToFileNameWithoutAWorkTitle() {
        assertEquals("Movie.2026.mkv", catalogPlaybackDisplayName("Movie.2026.mkv", null))
        assertEquals("Movie.2026.mkv", catalogPlaybackDisplayName("Movie.2026.mkv", "  "))
    }
}
