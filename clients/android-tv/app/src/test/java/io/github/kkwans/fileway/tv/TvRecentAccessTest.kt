package io.github.kkwans.fileway.tv

import io.github.kkwans.nasfilebrowser.data.RecentAccessEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class TvRecentAccessTest {
    @Test fun recentAccessMatchesPhoneVideoFilterAndKeepsDirectories() {
        fun entry(name: String, directory: Boolean = false, openable: Boolean = true) =
            RecentAccessEntry(name, "/$name", "/$name", name, directory, 1, openable)
        val entries = listOf(
            entry("movie.MKV"), entry("family.mp4", directory = true), entry("photo.jpg"),
            entry("music.flac"), entry("notes.txt"), entry("unsafe.txt", openable = false),
            entry("photo.jpg"),
        )
        assertEquals(listOf("family.mp4", "photo.jpg", "music.flac", "notes.txt"),
            tvRecentAccessResources(entries).map { it.name })
    }
}
