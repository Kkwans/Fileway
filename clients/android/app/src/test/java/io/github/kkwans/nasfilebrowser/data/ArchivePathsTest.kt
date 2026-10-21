package io.github.kkwans.nasfilebrowser.data

import io.github.kkwans.nasfilebrowser.app.ResourceRef
import org.junit.Assert.*
import org.junit.Test

class ArchivePathsTest {
    private fun listing(entries: List<ArchiveEntry>) = ArchiveListing("/中文.zip", "/%D6%D0%CE%C4.zip", "zip", 123, 456,
        entries, entries.sumOf { it.size }, 0, emptyList(), false, "", 10000, 8L shl 30, 20L shl 30)

    @Test fun preservesOriginalBytesLiteralPercentAndSafeLinuxBackslashes() {
        assertArrayEquals("a%2Fb +?#".toByteArray(), archiveEntryWireBytes("a%252Fb%20%2B%3F%23"))
        assertArrayEquals(byteArrayOf(0xd6.toByte(), 0xd0.toByte()), archiveEntryWireBytes("%D6%D0"))
        assertEquals("/%20folder%5Cname%20", archiveAbsoluteWire("/ folder\\name ", "/%20folder%5Cname%20"))
        assertEquals("/C%3Anotes.zip", archiveAbsoluteWire("/C:notes.zip", "/C%3Anotes.zip"))
    }

    @Test fun rejectsRelativeZipSlipEncodedSlashAndNulWithoutDecodingTwice() {
        for (wire in listOf("/absolute", "../escape", "%2E%2E/escape", "..%5Cescape", "a%2Fb", "bad%00", "bad%", "C%3A/outside"))
            assertTrue("must reject $wire", runCatching { archiveEntryWireBytes(wire) }.isFailure)
        assertTrue(runCatching { archiveAbsoluteWire("/lost�.zip", "") }.isFailure)
        assertEquals("/lost%EF%BF%BD.zip", archiveAbsoluteWire("/lost�.zip", "/lost%EF%BF%BD.zip"))
    }

    @Test fun sameDisplayNameParentsRemainDistinctDuringNavigationSearchAndSelection() {
        val value = listing(listOf(
            ArchiveEntry("中文/one.txt", "%D6%D0%CE%C4/one.txt", "one.txt", false, 11, 1),
            ArchiveEntry("中文/two.txt", "%E4%B8%AD%E6%96%87/two.txt", "two.txt", false, 22, 2)))
        val roots = archiveChildren(value, "", "")
        assertEquals(2, roots.size); assertEquals(listOf("中文", "中文"), roots.map { it.name })
        assertNotEquals(roots[0].wirePath, roots[1].wirePath)
        assertEquals("one.txt", archiveChildren(value, "%D6%D0%CE%C4", "").single().name)
        assertEquals(2, archiveChildren(value, "", "中文").size)
        assertEquals(11L, archiveSelectedEntries(value, setOf("%D6%D0%CE%C4")).sumOf { it.size })
        assertFalse(archiveSelectionContains(setOf("folder"), "folder-two/file"))
        assertTrue(archiveSelectionContains(setOf("."), "%D6%D0%CE%C4/one.txt"))
    }

    @Test fun detectsOnlyExistingSupportedRemoteArchiveFamilies() {
        for (name in listOf("a.ZIP", "a.7Z", "a.RAR", "a.tar", "a.tar.gz", "a.tar.bz2", "a.tar.xz", "a.tar.zst"))
            assertTrue(isBrowsableArchive(ResourceRef("/$name", "/$name", name, false, "", 0)))
        for (name in listOf("a.iso", "a.7z.001", "a.gz")) assertFalse(isBrowsableArchive(ResourceRef("/$name", "/$name", name, false, "", 0)))
        assertFalse(isBrowsableArchive(ResourceRef("/a.zip", "/a.zip", "a.zip", true, "", 0)))
    }
}
