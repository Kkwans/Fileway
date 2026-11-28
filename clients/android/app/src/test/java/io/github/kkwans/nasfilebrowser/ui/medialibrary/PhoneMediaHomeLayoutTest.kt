package io.github.kkwans.nasfilebrowser.ui.medialibrary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.kkwans.nasfilebrowser.data.MediaCatalogSort
import io.github.kkwans.nasfilebrowser.data.MediaLibraryKind
import io.github.kkwans.nasfilebrowser.data.MediaLibraryRole
import io.github.kkwans.nasfilebrowser.data.MediaLibrarySummary

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
    fun homeKeepsSearchInThePrimaryHeader() {
        assertTrue(phoneMediaHomeUsesCompactHeader(PhoneMediaPage.HOME))
        assertFalse(phoneMediaHomeUsesCompactHeader(PhoneMediaPage.LIBRARIES))
        assertFalse(phoneMediaHomeUsesCompactHeader(PhoneMediaPage.CATEGORIES))
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

    @Test
    fun selectedLibraryNamesTheCatalogContext() {
        val library = MediaLibrarySummary("lib_1", "我的电影", 1, MediaLibraryRole.OWNER, false, MediaLibraryKind.MOVIE)
        assertEquals("我的电影", phoneMediaLibraryCatalogTitle(PhoneMediaPage.LIBRARIES, "lib_1", listOf(library), "全部影视"))
        assertEquals("全部媒体库", phoneMediaLibraryCatalogTitle(PhoneMediaPage.LIBRARIES, null, listOf(library), "全部影视"))
        assertEquals("分类", phoneMediaLibraryCatalogTitle(PhoneMediaPage.CATEGORIES, "lib_1", listOf(library), "分类"))
    }

    @Test
    fun compactDensityAddsOnePosterColumnWithoutCrowdingWideScreens() {
        assertEquals(3, phoneMediaCatalogColumns(2, PhoneMediaDensity.COMPACT))
        assertEquals(2, phoneMediaCatalogColumns(2, PhoneMediaDensity.COMFORTABLE))
        assertEquals(5, phoneMediaCatalogColumns(4, PhoneMediaDensity.COMPACT))
        assertEquals(6, phoneMediaCatalogColumns(5, PhoneMediaDensity.COMPACT))
        assertEquals(6, phoneMediaCatalogColumns(6, PhoneMediaDensity.COMPACT))
    }

    @Test
    fun featuredRecommendationsAreOptionalAndNeverReplaceContinueWatching() {
        assertTrue(phoneMediaHomeShowsFeatured(showFeatured = true, hasEntries = true))
        assertFalse(phoneMediaHomeShowsFeatured(showFeatured = false, hasEntries = true))
        assertFalse(phoneMediaHomeShowsFeatured(showFeatured = true, hasEntries = false))
        assertEquals(listOf("continue", "catalog", "updated", "featured"), phoneMediaHomeSectionOrder())
    }

    @Test
    fun sortOptionsMatchServerCapabilities() {
        assertEquals(listOf(MediaCatalogSort.NEWEST, MediaCatalogSort.ID), phoneMediaCatalogSortOptions("all"))
        assertEquals(listOf(MediaCatalogSort.NEWEST, MediaCatalogSort.ID, MediaCatalogSort.UPDATED), phoneMediaCatalogSortOptions("series"))
        assertEquals("目录顺序", phoneMediaCatalogSortLabel(MediaCatalogSort.ID))
        assertEquals("最近更新", phoneMediaCatalogSortLabel(MediaCatalogSort.UPDATED))
        assertEquals("最近加入", phoneMediaCatalogHeading(MediaCatalogSort.NEWEST))
        assertEquals("全部影视", phoneMediaCatalogHeading(MediaCatalogSort.ID))
        assertEquals("最近更新", phoneMediaCatalogHeading(MediaCatalogSort.UPDATED))
    }

    @Test
    fun catalogLoadsMoreNearTheEndWithoutWaitingForTheLastRow() {
        assertTrue(phoneMediaShouldLoadMore(lastVisibleIndex = 13, itemCount = 20, hasMore = true, loading = false))
        assertFalse(phoneMediaShouldLoadMore(lastVisibleIndex = 12, itemCount = 20, hasMore = true, loading = false))
    }

    @Test
    fun catalogPaginationWaitsForContentAndStopsOnLoadingFailureOrEnd() {
        assertFalse(phoneMediaShouldLoadMore(lastVisibleIndex = 19, itemCount = 0, hasMore = true, loading = false))
        assertFalse(phoneMediaShouldLoadMore(lastVisibleIndex = 19, itemCount = 20, hasMore = true, loading = true))
        assertFalse(phoneMediaShouldLoadMore(lastVisibleIndex = 19, itemCount = 20, hasMore = true, loading = false, problem = true))
        assertFalse(phoneMediaShouldLoadMore(lastVisibleIndex = 19, itemCount = 20, hasMore = false, loading = false))
    }

    private fun poster(number: Int) = MediaPoster(number.toString(), "影片 $number", "2026")
}
