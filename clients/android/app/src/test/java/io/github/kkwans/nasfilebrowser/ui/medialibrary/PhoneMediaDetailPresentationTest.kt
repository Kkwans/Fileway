package io.github.kkwans.nasfilebrowser.ui.medialibrary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneMediaDetailPresentationTest {
    @Test
    fun playbackBarStaysAvailableWithoutDuplicatingInlineActions() {
        assertTrue(phoneMediaDetailShowsPlaybackBar(canSelectSources = true, canHandoff = false))
        assertTrue(phoneMediaDetailShowsPlaybackBar(canSelectSources = false, canHandoff = true))
        assertFalse(phoneMediaDetailShowsPlaybackBar(canSelectSources = false, canHandoff = false))
    }

    @Test
    fun episodeTitleUsesRealNameOrNumberOnly() {
        assertEquals("第一集 · 叉叉叉叉", phoneMediaEpisodeTitle("第一集", "叉叉叉叉"))
        assertEquals("第一集", phoneMediaEpisodeTitle("第一集", " "))
        assertEquals("第二集", phoneMediaEpisodeTitle("第二集", ""))
    }

    @Test
    fun episodePrefersItsOwnArtworkAndFallsBackToBackdrop() {
        val artwork = MediaArtwork(endpoint = "/episode")
        val backdrop = MediaArtwork(endpoint = "/backdrop")
        assertEquals(artwork, phoneMediaEpisodeImage(artwork, backdrop))
        assertEquals(backdrop, phoneMediaEpisodeImage(null, backdrop))
        assertNull(phoneMediaEpisodeImage(null, null))
    }
}
