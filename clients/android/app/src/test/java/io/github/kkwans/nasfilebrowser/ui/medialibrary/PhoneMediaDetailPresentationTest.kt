package io.github.kkwans.nasfilebrowser.ui.medialibrary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneMediaDetailPresentationTest {
    @Test
    fun episodePrefersItsOwnArtworkAndFallsBackToBackdrop() {
        val artwork = MediaArtwork(endpoint = "/episode")
        val backdrop = MediaArtwork(endpoint = "/backdrop")
        assertEquals(artwork, phoneMediaEpisodeImage(artwork, backdrop))
        assertEquals(backdrop, phoneMediaEpisodeImage(null, backdrop))
        assertNull(phoneMediaEpisodeImage(null, null))
    }
}
