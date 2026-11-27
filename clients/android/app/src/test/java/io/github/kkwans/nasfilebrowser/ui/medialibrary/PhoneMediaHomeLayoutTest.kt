package io.github.kkwans.nasfilebrowser.ui.medialibrary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneMediaHomeLayoutTest {
    @Test
    fun homeHasNoBackAffordance() {
        assertFalse(phoneMediaShowsBack(PhoneMediaPage.HOME))
        assertTrue(phoneMediaShowsBack(PhoneMediaPage.LIBRARIES))
        assertTrue(phoneMediaShowsBack(PhoneMediaPage.CATEGORIES))
    }

    @Test
    fun systemBackUsesTheSamePageHierarchyAsTopBack() {
        assertEquals(PhoneMediaPage.HOME, phoneMediaPageForTab("home"))
        assertEquals(PhoneMediaPage.LIBRARIES, phoneMediaPageForTab("libraries"))
        assertEquals(PhoneMediaPage.CATEGORIES, phoneMediaPageForTab("categories"))
        assertEquals(PhoneMediaPage.HOME, phoneMediaPageForTab("settings"))
        assertTrue(phoneMediaShowsBack(phoneMediaPageForTab("libraries")))
        assertTrue(phoneMediaShowsBack(phoneMediaPageForTab("categories")))
        assertFalse(phoneMediaShowsBack(phoneMediaPageForTab("home")))
    }

    @Test
    fun homeShowsOnlyRecentPreview() {
        val entries = (1..10).map(::poster)
        assertEquals((1..6).map(::poster), phoneMediaCatalogForPage(entries, PhoneMediaPage.HOME))
    }

    @Test
    fun homePreviewKeepsThreeRowsOnWideScreens() {
        val entries = (1..16).map(::poster)
        assertEquals((1..12).map(::poster), phoneMediaCatalogForPage(entries, PhoneMediaPage.HOME, columns = 4))
    }

    @Test
    fun libraryAndCategoryPagesKeepTheCompleteCatalog() {
        val entries = (1..10).map(::poster)
        assertEquals(entries, phoneMediaCatalogForPage(entries, PhoneMediaPage.LIBRARIES))
        assertEquals(entries, phoneMediaCatalogForPage(entries, PhoneMediaPage.CATEGORIES))
    }

    private fun poster(number: Int) = MediaPoster(number.toString(), "影片 $number", "2026")
}
