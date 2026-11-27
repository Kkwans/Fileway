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
}
