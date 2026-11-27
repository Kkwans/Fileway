package io.github.kkwans.nasfilebrowser.ui.medialibrary

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneMediaSearchStateTest {
    @Test
    fun partialEmptyPageIsNotReportedAsNoResults() {
        assertFalse(phoneMediaSearchShowsEmpty(searched = true, empty = true, busy = false, hasProblem = false, hasMore = true))
        assertTrue(phoneMediaSearchShowsEmpty(searched = true, empty = true, busy = false, hasProblem = false, hasMore = false))
        assertFalse(phoneMediaSearchShowsEmpty(searched = false, empty = true, busy = false, hasProblem = false, hasMore = false))
        assertFalse(phoneMediaSearchShowsEmpty(searched = true, empty = true, busy = true, hasProblem = false, hasMore = false))
        assertFalse(phoneMediaSearchShowsEmpty(searched = true, empty = true, busy = false, hasProblem = true, hasMore = false))
    }
}
