package io.github.kkwans.nasfilebrowser.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaLibraryContentKindTest {
    @Test fun parsesKnownContentKinds() {
        for ((wire, kind) in listOf("mixed" to MediaLibraryKind.MIXED, "movie" to MediaLibraryKind.MOVIE, "series" to MediaLibraryKind.SERIES)) {
            val row = libraryRow().put("contentKind", wire)
            assertEquals(kind, parseMediaLibrary(row).contentKind)
        }
    }

    @Test fun olderServerWithoutContentKindRemainsMixed() {
        assertEquals(MediaLibraryKind.MIXED, parseMediaLibrary(libraryRow()).contentKind)
    }

    @Test fun unknownContentKindIsNotGuessed() {
        assertEquals(MediaLibraryKind.UNKNOWN, parseMediaLibrary(libraryRow().put("contentKind", "episode")).contentKind)
    }

    private fun libraryRow() = JSONObject()
        .put("id", "lib_fixture")
        .put("name", "Films")
        .put("revision", 1)
        .put("role", "owner")
        .put("archived", false)
}
