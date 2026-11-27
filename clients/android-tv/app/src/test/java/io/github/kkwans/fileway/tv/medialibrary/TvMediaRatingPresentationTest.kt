package io.github.kkwans.fileway.tv.medialibrary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvMediaRatingPresentationTest {
    @Test
    fun doubanLowerBoundCountKeepsPlusAndUnit() {
        assertEquals("1,000+ 人评价", tvMediaRatingCountLabel(
            TvMediaRatingSource.DOUBAN, 1_000, "lower_bound"))
    }

    @Test
    fun imdbAndTmdbCountsUseVotes() {
        assertEquals("1,000 票", tvMediaRatingCountLabel(
            TvMediaRatingSource.IMDB, 1_000, "exact"))
        assertEquals("125 票", tvMediaRatingCountLabel(
            TvMediaRatingSource.TMDB, 125, "exact"))
    }

    @Test
    fun rottenTomatoesCountsUseCriticAndAudienceUnits() {
        assertEquals("125 位影评人", tvMediaRatingCountLabel(
            TvMediaRatingSource.ROTTEN_TOMATOES_CRITICS, 125, "exact"))
        assertEquals("2,500+ 位观众", tvMediaRatingCountLabel(
            TvMediaRatingSource.ROTTEN_TOMATOES_AUDIENCE, 2_500, "lower_bound"))
    }

    @Test
    fun missingCountIsNotInvented() {
        assertNull(tvMediaRatingCountLabel(TvMediaRatingSource.DOUBAN, null, null))
    }
}
