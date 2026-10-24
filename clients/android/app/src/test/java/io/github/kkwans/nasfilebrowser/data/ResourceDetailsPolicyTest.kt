package io.github.kkwans.nasfilebrowser.data

import io.github.kkwans.nasfilebrowser.app.ResourceRef
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ResourceDetailsPolicyTest {
    private val root = ResourceRef("/root", "/root", "root", true, "", 4096)
    private fun entry(wire: String, size: Long = 0, directory: Boolean = false, link: Boolean = false) =
        JSONObject().put("wirePath", wire).put("size", size).put("isDir", directory).put("isSymlink", link)
    private fun listing(wire: String, vararg rows: JSONObject) = JSONObject().put("wirePath", wire).put("isDir", true).put("items", JSONArray(rows.toList()))

    @Test fun countsContentBytesAndKeepsOpaqueWirePathsWithoutFollowingLinks() = runBlocking {
        val calls = mutableListOf<String>()
        val result = measureDirectorySize(root, { endpoint ->
            calls.add(endpoint)
            when (endpoint) {
                "/api/resources/root" -> listing("/root", entry("/root/a", 12), entry("/root/%FF", directory = true), entry("/root/link", directory = true, link = true))
                "/api/resources/root/%FF" -> listing("/root/%FF", entry("/root/%FF/b", 30))
                else -> error("unexpected source")
            }
        }, {})
        assertEquals(42L, result.bytes)
        assertEquals(2, result.files)
        assertEquals(1, result.skippedLinks)
        assertEquals(listOf("/api/resources/root", "/api/resources/root/%FF"), calls)
    }
    @Test fun rejectsOutOfScopeChildrenRatherThanReportingPartialSize() = runBlocking {
        val result = runCatching { measureDirectorySize(root, { listing("/root", entry("/other/a", 100)) }, {}) }
        assertTrue(result.exceptionOrNull() is IllegalStateException)
    }
    @Test fun rejectsOverflowInsteadOfWrappingSize() = runBlocking {
        val result = runCatching { measureDirectorySize(root, { listing("/root", entry("/root/a", Long.MAX_VALUE), entry("/root/b", 1)) }, {}) }
        assertTrue(result.exceptionOrNull() is ArithmeticException)
    }
    @Test fun averageUsesActiveMillisecondsAndRejectsMissingHistoricalTiming() {
        assertEquals(2048L, transferAverageBytesPerSecond(1024, 500, true))
        assertEquals(512L, transferAverageBytesPerSecond(1024, 2000, true))
        assertNull(transferAverageBytesPerSecond(1024, 0, true))
        assertNull(transferAverageBytesPerSecond(1024, 1000, false))
    }
    @Test fun taskDefaultIncludesBothCategoriesAndPreservesExplicitFilters() {
        assertEquals("", TaskFilter().category)
        assertTrue(TaskFilter().query().contains("category=&"))
        assertTrue(TaskFilter(category = "file").query().contains("category=file&"))
        assertTrue(TaskFilter(category = "background").query().contains("category=background&"))
    }
}
