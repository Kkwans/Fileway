package io.github.kkwans.nasfilebrowser.ui.medialibrary

import io.github.kkwans.nasfilebrowser.data.MediaScanMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaLibraryScanRecoveryTest {
    @Test
    fun scanButtonExplainsSubmittingState() {
        assertEquals("开始增量扫描", mediaLibraryScanActionLabel(MediaScanMode.INCREMENTAL, false))
        assertEquals("正在提交增量扫描…", mediaLibraryScanActionLabel(MediaScanMode.INCREMENTAL, true))
        assertEquals("扫描进行中", mediaLibraryScanActionLabel(MediaScanMode.INCREMENTAL, false, true))
    }

    @Test
    fun scanFailuresReturnToRetryInsteadOfConfirmationDeadEnd() {
        assertTrue(mediaLibraryScanOperation("scan"))
        assertTrue(mediaLibraryScanOperation("setup-scan"))
        assertFalse(mediaLibraryScanOperation("create"))
        assertFalse(mediaLibraryScanOperation(null))
    }
}
