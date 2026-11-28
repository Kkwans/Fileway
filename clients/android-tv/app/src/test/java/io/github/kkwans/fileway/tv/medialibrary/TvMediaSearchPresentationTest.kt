package io.github.kkwans.fileway.tv.medialibrary

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvMediaSearchPresentationTest {
    @Test
    fun emptyFirstPageWithMoreResultsIsNotANoResultsState() {
        assertFalse(tvMediaSearchShowsEmpty(searched = true, empty = true, busy = false, problem = null, hasMore = true))
        assertTrue(tvMediaSearchShowsEmpty(searched = true, empty = true, busy = false, problem = null, hasMore = false))
    }

    @Test
    fun emptySearchStateWaitsForCompletionAndStopsOnFailure() {
        assertFalse(tvMediaSearchShowsEmpty(searched = false, empty = true, busy = false, problem = null, hasMore = false))
        assertFalse(tvMediaSearchShowsEmpty(searched = true, empty = true, busy = true, problem = null, hasMore = false))
        assertFalse(tvMediaSearchShowsEmpty(searched = true, empty = true, busy = false, problem = "offline", hasMore = false))
    }

    @Test
    fun emptyPageLoadsMoreOnlyWhenAnotherPageCanBeRequested() {
        assertTrue(tvMediaShouldLoadEmptyPage(hasMore = true, loading = false, error = null))
        assertFalse(tvMediaShouldLoadEmptyPage(hasMore = false, loading = false, error = null))
        assertFalse(tvMediaShouldLoadEmptyPage(hasMore = true, loading = true, error = null))
        assertFalse(tvMediaShouldLoadEmptyPage(hasMore = true, loading = false, error = "offline"))
    }
}
