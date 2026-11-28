package io.github.kkwans.nasfilebrowser.ui.medialibrary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaPosterPresentationTest {
    @Test
    fun duplicateTitleIsNotRepeatedAsSubtitle() {
        assertNull(mediaPosterSubtitle(MediaPoster("1", "电影.mkv", "电影.mkv")))
    }

    @Test
    fun blankSubtitleIsOmitted() {
        assertNull(mediaPosterSubtitle(MediaPoster("1", "电影", " ")))
    }

    @Test
    fun distinctMetadataRemainsVisible() {
        assertEquals("2026 · 电影", mediaPosterSubtitle(MediaPoster("1", "电影", "2026 · 电影")))
    }

    @Test
    fun posterRatingShowsItsSource() {
        assertEquals("豆瓣 8.1", mediaPosterRatingLabel(MediaRating("r", "豆瓣", "8.1")))
    }

    @Test
    fun detailRatingsUseTheRequestedSourceOrderWithoutDuplicates() {
        val ratings = listOf(
            MediaRating("imdb", "IMDb", "7.2"),
            MediaRating("rt", "烂番茄", "85%", "影评人"),
            MediaRating("douban", "豆瓣", "8.1"),
            MediaRating("douban-2", "豆瓣", "8.2"),
            MediaRating("tmdb", "TMDB", "6.9"),
        )
        assertEquals(listOf("douban", "imdb", "rt"), phoneMediaDetailRatings(ratings).map { it.id })
    }
}
