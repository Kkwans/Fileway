package io.github.kkwans.fileway.tv.medialibrary

import org.junit.Assert.assertEquals
import org.junit.Test

class TvMediaFocusPolicyTest {
    @Test
    fun existingStableTargetIsPreserved() {
        assertEquals(TvMediaFocusTarget("item_2", "poster"), resolveTvMediaFocusTarget(
            "item_2", "poster", listOf("item_1", "item_2", "item_3"), 1))
    }

    @Test
    fun revokedTargetFallsBackToAdjacentVisibleItem() {
        assertEquals(TvMediaFocusTarget("item_3", "poster"), resolveTvMediaFocusTarget(
            "item_2", "poster", listOf("item_1", "item_3", "item_4"), 1))
        assertEquals(TvMediaFocusTarget("item_3", "poster"), resolveTvMediaFocusTarget(
            "item_4", "poster", listOf("item_1", "item_3"), 2))
    }

    @Test
    fun personSearchReturnsToVisibleResultOrSearchField() {
        val id = "person:lib_1/person_1"
        assertEquals(TvMediaFocusTarget(id, "person-result"), resolveTvPersonSearchFocusTarget(
            id, "person-result", listOf(id)))
        assertEquals(TvMediaFocusTarget("search-field", "search"), resolveTvPersonSearchFocusTarget(
            id, "person-result", emptyList()))
    }

    @Test
    fun emptyHomeReturnsToNavigationWithoutTimedOverride() {
        assertEquals(TvMediaFocusTarget("files", "navigation"), resolveTvMediaFocusTarget(
            "item_1", "poster", emptyList(), 0))
        assertEquals(TvMediaFocusTarget("files", "navigation"), resolveTvMediaFocusTarget(
            "files", "navigation", listOf("item_1"), -1))
    }
}
