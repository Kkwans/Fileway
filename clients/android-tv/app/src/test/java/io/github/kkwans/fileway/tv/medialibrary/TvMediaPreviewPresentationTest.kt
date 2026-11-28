package io.github.kkwans.fileway.tv.medialibrary

import io.github.kkwans.nasfilebrowser.data.MediaCatalogCard
import org.junit.Assert.assertNull
import org.junit.Test

class TvMediaPreviewPresentationTest {
    @Test
    fun personWithoutPreviewHasNoWorkTitle() {
        assertNull(tvMediaPersonPreviewTitle(emptyList<MediaCatalogCard>()))
    }

    @Test
    fun emptyPreviewCanBeMappedWithoutCrashing() {
        assertNull(tvMediaFirstPreview(emptyList<String>()) { it })
    }

    @Test
    fun featuredCarouselWithoutEntriesHasNoInitialSelection() {
        assertNull(tvMediaFeaturedInitialId(emptyList<TvMediaCard>()))
    }
}
