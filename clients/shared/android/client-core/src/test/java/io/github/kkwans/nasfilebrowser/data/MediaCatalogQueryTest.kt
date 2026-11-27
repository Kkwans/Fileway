package io.github.kkwans.nasfilebrowser.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaCatalogQueryTest {
    @Test
    fun workSearchAcceptsBoundedUnicodeQuery() {
        val query = MediaCatalogQuery(query = "回声 & Echo", limit = 24)
        assertEquals("回声 & Echo", query.query)
        assertEquals(MediaCatalogSort.ID, query.sort)
    }

    @Test
    fun metadataFacetsAreBoundedAndEncoded() {
        val query = MediaCatalogQuery(genre = "剧情", country = "CN", yearFrom = 2020, yearTo = 2022)
        val endpoint = catalogQueryEndpoint(query)
        assertTrue(endpoint.contains("genre=%E5%89%A7%E6%83%85"))
        assertTrue(endpoint.contains("country=CN"))
        assertTrue(endpoint.contains("yearFrom=2020"))
        assertTrue(endpoint.contains("yearTo=2022"))
        assertThrows(IllegalArgumentException::class.java) { MediaCatalogQuery(yearFrom = 2022, yearTo = 2020) }
        assertThrows(IllegalArgumentException::class.java) { MediaCatalogQuery(genre = "x".repeat(101)) }
    }

    @Test
    fun workSearchRejectsOversizedOrControlQuery() {
        assertThrows(IllegalArgumentException::class.java) { MediaCatalogQuery(query = "x".repeat(201)) }
        assertThrows(IllegalArgumentException::class.java) { MediaCatalogQuery(query = "bad\nquery") }
        assertThrows(IllegalArgumentException::class.java) { MediaCatalogQuery(query = "Echo", sort = MediaCatalogSort.NEWEST) }
    }
}
