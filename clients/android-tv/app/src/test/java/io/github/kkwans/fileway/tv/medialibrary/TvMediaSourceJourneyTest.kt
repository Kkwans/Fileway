package io.github.kkwans.fileway.tv.medialibrary

import io.github.kkwans.nasfilebrowser.data.MediaItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvMediaSourceJourneyTest {
    @Test
    fun unavailableMovieKeepsSourceBrowserEntry() {
        assertTrue(tvCanBrowseSources(MediaItemKind.MOVIE))
        assertTrue(tvCanBrowseSources(MediaItemKind.EPISODE))
        assertTrue(tvCanBrowseSources(MediaItemKind.VIDEO))
        assertFalse(tvCanBrowseSources(MediaItemKind.SERIES))
        assertFalse(tvCanBrowseSources(MediaItemKind.SEASON))
    }

    @Test
    fun browseConfirmationStoresPendingSourceWithoutPlayback() {
        val journey = TvMediaSourceJourney()
        journey.open("lib_1/item_1", TvMediaSourceIntent.BROWSE)

        assertFalse(journey.confirm("edition_1/variant_1/binding_1"))
        assertEquals("edition_1/variant_1/binding_1", journey.pendingSourceId("lib_1/item_1"))
    }

    @Test
    fun playConfirmationStartsPlaybackAndRemembersSelectedSource() {
        val journey = TvMediaSourceJourney()
        journey.open("lib_1/item_1", TvMediaSourceIntent.BROWSE)
        journey.confirm("edition_1/variant_1/binding_1")
        journey.open("lib_1/item_1", TvMediaSourceIntent.PLAY)

        assertTrue(journey.confirm("edition_1/variant_2/binding_2"))
        assertEquals("edition_1/variant_2/binding_2", journey.pendingSourceId("lib_1/item_1"))
    }

    @Test
    fun pendingChoiceIsScopedToItsItem() {
        val journey = TvMediaSourceJourney()
        journey.open("lib_1/item_1", TvMediaSourceIntent.BROWSE)
        journey.confirm("edition_1/variant_1/binding_1")
        journey.open("lib_1/item_2", TvMediaSourceIntent.BROWSE)

        assertNull(journey.pendingSourceId("lib_1/item_1"))
        assertNull(journey.pendingSourceId("lib_1/item_2"))
    }

    @Test
    fun enteringPlayFlowDiscardsUnusablePendingChoice() {
        val journey = TvMediaSourceJourney()
        journey.open("lib_1/item_1", TvMediaSourceIntent.BROWSE)
        journey.confirm("edition_1/variant_1/binding_1")
        journey.open("lib_1/item_1", TvMediaSourceIntent.PLAY)

        assertNull(journey.pendingSourceId("lib_1/item_1"))
    }

    @Test
    fun browsePickerExplainsThatConfirmationDoesNotPlay() {
        val state = TvMediaSourcePickerState(
            scopeKey = "instance_1",
            itemId = "lib_1/item_1",
            title = "影片",
            editions = emptyList(),
            intent = TvMediaSourceIntent.BROWSE,
        )

        assertFalse(state.playsOnConfirm)
        assertEquals("设为待播放片源", state.confirmLabel)
        assertEquals("选择后返回详情，不会立即播放", state.confirmHint)
    }

    @Test
    fun pendingChoicePreselectsWithoutChangingCurrentBadge() {
        val state = TvMediaSourcePickerState(
            scopeKey = "instance_1",
            itemId = "lib_1/item_1",
            title = "影片",
            editions = emptyList(),
            currentSourceId = "edition_1/variant_1/binding_1",
            preferredSourceId = "edition_1/variant_2/binding_2",
            intent = TvMediaSourceIntent.BROWSE,
        )

        assertEquals("edition_1/variant_1/binding_1", state.currentSourceId)
        assertEquals("edition_1/variant_2/binding_2", state.initialSourceId)
    }
}
