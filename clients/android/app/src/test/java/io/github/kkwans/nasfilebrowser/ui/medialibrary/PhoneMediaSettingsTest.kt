package io.github.kkwans.nasfilebrowser.ui.medialibrary

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneMediaSettingsTest {
    @Test
    fun metadataFieldCountsAreStableAndReadable() {
        assertEquals("plot ×2、title ×3", formatMetadataFieldCounts(mapOf("title" to 3, "plot" to 2)))
        assertEquals("无", formatMetadataFieldCounts(emptyMap()))
    }
}
