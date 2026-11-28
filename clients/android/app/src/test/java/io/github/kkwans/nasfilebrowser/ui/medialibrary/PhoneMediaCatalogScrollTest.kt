package io.github.kkwans.nasfilebrowser.ui.medialibrary

import io.github.kkwans.nasfilebrowser.data.MediaCatalogSort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PhoneMediaCatalogScrollTest {
    @Test
    fun catalogFilterChangesUseANewScrollScope() {
        val base = phoneMediaCatalogGridKey("libraries", null, "all", MediaCatalogSort.NEWEST)
        assertEquals(base, phoneMediaCatalogGridKey("libraries", null, "all", MediaCatalogSort.NEWEST))
        assertNotEquals(base, phoneMediaCatalogGridKey("libraries", "lib_1", "all", MediaCatalogSort.NEWEST))
        assertNotEquals(base, phoneMediaCatalogGridKey("libraries", null, "movie", MediaCatalogSort.NEWEST))
        assertNotEquals(base, phoneMediaCatalogGridKey("libraries", null, "all", MediaCatalogSort.ID))
    }

    @Test
    fun catalogTabsUseIndependentScrollScopes() {
        assertNotEquals(
            phoneMediaCatalogGridKey("libraries", null, "all", MediaCatalogSort.NEWEST),
            phoneMediaCatalogGridKey("categories", null, "all", MediaCatalogSort.NEWEST),
        )
    }
}
