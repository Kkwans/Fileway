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
    fun emptyHomeReturnsToNavigationWithoutTimedOverride() {
        assertEquals(TvMediaFocusTarget("files", "navigation"), resolveTvMediaFocusTarget(
            "item_1", "poster", emptyList(), 0))
        assertEquals(TvMediaFocusTarget("files", "navigation"), resolveTvMediaFocusTarget(
            "files", "navigation", listOf("item_1"), -1))
    }
}
