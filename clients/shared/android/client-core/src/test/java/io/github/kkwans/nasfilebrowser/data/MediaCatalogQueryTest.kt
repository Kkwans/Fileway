package io.github.kkwans.nasfilebrowser.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MediaCatalogQueryTest {
    @Test
    fun workSearchAcceptsBoundedUnicodeQuery() {
        val query = MediaCatalogQuery(query = "回声 & Echo", limit = 24)
        assertEquals("回声 & Echo", query.query)
        assertEquals(MediaCatalogSort.ID, query.sort)
    }

    @Test
    fun workSearchRejectsOversizedOrControlQuery() {
        assertThrows(IllegalArgumentException::class.java) { MediaCatalogQuery(query = "x".repeat(201)) }
        assertThrows(IllegalArgumentException::class.java) { MediaCatalogQuery(query = "bad\nquery") }
        assertThrows(IllegalArgumentException::class.java) { MediaCatalogQuery(query = "Echo", sort = MediaCatalogSort.NEWEST) }
    }
}
