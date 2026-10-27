package io.github.kkwans.nasfilebrowser.app

import io.github.kkwans.nasfilebrowser.data.SearchResult
import io.github.kkwans.nasfilebrowser.data.SearchEnding
import io.github.kkwans.nasfilebrowser.ui.fileNameBreakHints
import org.junit.Assert.*
import org.junit.Test

class Patch121PresentationTest {
    private fun result(path: String, size: Long = 1, modified: String = "2026-01-01T00:00:00Z", directory: Boolean = false) =
        SearchResult(path, path.substringAfterLast('/'), directory, size, modified, "")

    @Test fun archiveFilterRecognizesCommonAndCompoundSuffixesWithoutMatchingFolders() {
        for (name in listOf("bundle.ZIP", "bundle.rar", "bundle.7z", "bundle.tar", "bundle.tar.gz", "bundle.TAR.XZ", "bundle.tgz", "bundle.tbz2", "bundle.tar.zst")) {
            assertTrue(name, FileCategory.ARCHIVE.matches(name, false))
            assertFalse(name, FileCategory.ARCHIVE.matches(name, true))
        }
        for (name in listOf("zip-notes.txt", "bundle.zip.txt", "movie.mp4", "disk.iso")) assertFalse(name, FileCategory.ARCHIVE.matches(name, false))
    }

    @Test fun allOrdersMatchTheBrowserAndKeepDirectoriesFirst() {
        val values = listOf(result("a.txt", 2, "2026-01-02T00:00:00Z"), result("b.txt", 30, "2026-01-01T00:00:00Z"),
            result("folder", directory = true), result("c.txt", 8, "2026-01-03T00:00:00Z"))
        val expected = mapOf(FileOrder.NAME to listOf("a.txt", "b.txt", "c.txt"),
            FileOrder.NAME_REVERSE to listOf("c.txt", "b.txt", "a.txt"),
            FileOrder.LARGEST to listOf("b.txt", "c.txt", "a.txt"), FileOrder.SMALLEST to listOf("a.txt", "c.txt", "b.txt"),
            FileOrder.NEWEST to listOf("c.txt", "a.txt", "b.txt"), FileOrder.OLDEST to listOf("b.txt", "a.txt", "c.txt"))
        for (order in FileOrder.entries) {
            val search = SearchState(items = values, order = order).visibleItems
            val browser = presentFiles(values.map { ResourceRef("/" + it.relativePath, "/" + it.relativePath, it.name, it.directory, "", it.size, it.modified) }, FileCategory.ALL, order)
            assertEquals(order.name, listOf("folder") + expected.getValue(order), search.map { it.name })
            assertEquals(order.name, browser.map { it.name }, search.map { it.name })
        }
    }

    @Test fun sortUsesAllReceivedResultsNotTheVisibleViewport() {
        val values = (0..600).map { result("$it.zip", it.toLong()) }
        val state = SearchState(items = values, order = FileOrder.LARGEST)
        assertEquals("600.zip", state.visibleItems.first().name)
        assertEquals(601, state.visibleItems.size)
        assertEquals("0.zip", state.copy(order = FileOrder.SMALLEST).visibleItems.first().name)
        assertEquals(values, state.items)
    }

    @Test fun duplicateNamesUseOriginalRelativePathsAsStableTieBreakers() {
        val first = result("b/same.zip"); val second = result("a/same.zip")
        val state = SearchState(items = listOf(first, second), order = FileOrder.LARGEST)
        assertEquals(listOf(second, first), state.visibleItems)
        assertSame(second, state.visibleItems.first())
    }

    @Test fun unopenableEncodedNamesAreStillVisibleAndSortable() {
        val unsafe = result("lost\uFFFD.zip", 100)
        assertNull(unsafe.resource("/", "/"))
        val state = SearchState(items = listOf(result("regular.zip", 2), unsafe), order = FileOrder.LARGEST)
        assertSame(unsafe, state.visibleItems.first())
    }

    @Test fun unknownDatesStayAfterKnownDatesInBothDirections() {
        val unknown = result("a_unknown.zip", modified = "invalid")
        val known = result("z_known.zip")
        for (order in listOf(FileOrder.NEWEST, FileOrder.OLDEST))
            assertEquals(listOf(known, unknown), SearchState(items = listOf(unknown, known), order = order).visibleItems)
    }

    @Test fun filteringAndPartialSearchStatusSurviveOrderChanges() {
        val state = SearchState(items = listOf(result("small.zip", 2), result("large.zip", 30), result("notes.txt", 99)),
            categories = setOf(FileCategory.ARCHIVE), ending = SearchEnding.LIMIT, message = "partial", order = FileOrder.LARGEST)
        assertEquals(listOf("large.zip", "small.zip"), state.visibleItems.map { it.name })
        val ascending = state.copy(order = FileOrder.SMALLEST)
        assertEquals(listOf("small.zip", "large.zip"), ascending.visibleItems.map { it.name })
        assertEquals(state.items, ascending.items); assertEquals(state.ending, ascending.ending); assertEquals("partial", ascending.message)
    }

    @Test fun filenameHintsKeepTheCjkTitleAndOriginalCodePointsIntact() {
        val name = "春山与归途.2001.BD1080P.AAC.H264.CHS.[example].mp4"
        val displayed = fileNameBreakHints(name)
        assertTrue(displayed.startsWith("春山与归途.\u200B"))
        assertEquals(name, displayed.replace("\u200B", ""))
        assertFalse(displayed.contains('\n'))
    }

    @Test fun filenameHintsDoNotSplitSurrogatesOrAddTrailingEmptySegments() {
        val name = "春山\uD83D\uDE03_片名-final.mp4"
        val displayed = fileNameBreakHints(name)
        assertTrue(displayed.contains("\uD83D\uDE03")); assertFalse(displayed.endsWith("\u200B"))
        assertEquals(name, displayed.replace("\u200B", ""))
        assertEquals("纯中文片名", fileNameBreakHints("纯中文片名"))
    }
}
