package io.github.kkwans.nasfilebrowser.ui.medialibrary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneMediaSourceSelectionTest {
    @Test
    fun selectingSourceDoesNotAuthorizePlayback() {
        val selection = PhoneMediaSourceSelection("item_1").select("source_1", available = true)
        assertEquals("source_1", selection.selectedId)
        assertNull(selection.confirm("source_1", expectedItemId = "item_1", expectedRevision = "revision_1", revision = "revision_2"))
    }

    @Test
    fun freshSelectionCanBeConfirmedExplicitly() {
        val selection = PhoneMediaSourceSelection("item_1").select("source_1", available = true)
        assertEquals("source_1", selection.confirm("source_1", expectedItemId = "item_1", expectedRevision = "revision_1", revision = "revision_1"))
    }

    @Test
    fun unavailableOrStaleSourceCannotBeConfirmed() {
        val unavailable = PhoneMediaSourceSelection("item_1").select("source_1", available = false)
        assertNull(unavailable.selectedId)
        val selected = PhoneMediaSourceSelection("item_1").select("source_1", available = true)
        assertNull(selected.confirm("source_2", expectedItemId = "item_1", expectedRevision = "", revision = ""))
        assertNull(selected.confirm("source_1", expectedItemId = "item_2", expectedRevision = "", revision = ""))
    }
}
