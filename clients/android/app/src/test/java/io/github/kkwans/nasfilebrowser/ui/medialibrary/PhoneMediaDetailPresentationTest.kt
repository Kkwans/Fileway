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
        assertEquals("第一集", phoneMediaEpisodeTitle("第一集", "Loki.2021.EP01.4K.X264.AAC.mkv"))
        assertEquals("第二集", phoneMediaEpisodeTitle("第二集", "Show.Name.S02E03.1080p.mkv"))
    }

    @Test
    fun episodeBadgeUsesOnlyTheEpisodeNumber() {
        assertEquals("1", phoneMediaEpisodeBadge("第 1 集"))
        assertEquals("12", phoneMediaEpisodeBadge("第 12 集"))
        assertEquals("E", phoneMediaEpisodeBadge("特别篇"))
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
