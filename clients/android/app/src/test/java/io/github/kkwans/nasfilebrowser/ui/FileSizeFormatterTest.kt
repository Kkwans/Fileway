package io.github.kkwans.nasfilebrowser.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FileSizeFormatterTest {
    @Test fun nonEmptySmallFilesDoNotAppearEmpty() {
        assertEquals("0 B", readableSize(0))
        assertEquals("31 B", readableSize(31))
        assertEquals("33 B", readableSize(33))
        assertEquals("92 B", readableSize(92))
        assertEquals("1023 B", readableSize(1023))
    }

    @Test fun establishedLargeUnitBoundariesRemainStable() {
        assertEquals("1 KB", readableSize(1024))
        assertEquals("1.0 MB", readableSize(1L shl 20))
        assertEquals("2.0 MB", readableSize(2L shl 20))
        assertEquals("1.0 GB", readableSize(1L shl 30))
        assertEquals("大小未知", readableSize(-1))
    }
}
