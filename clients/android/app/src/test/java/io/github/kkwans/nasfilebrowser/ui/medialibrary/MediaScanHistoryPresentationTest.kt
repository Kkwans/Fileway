package io.github.kkwans.nasfilebrowser.ui.medialibrary

import io.github.kkwans.nasfilebrowser.data.MediaScan
import io.github.kkwans.nasfilebrowser.data.MediaScanPhase
import io.github.kkwans.nasfilebrowser.data.MediaScanStatus
import io.github.kkwans.nasfilebrowser.ui.serverTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaScanHistoryPresentationTest {
    @Test
    fun completedScanShowsResultAction() {
        assertEquals("查看结果", mediaScanHistoryActionLabel(MediaScanStatus.COMPLETED))
    }

    @Test
    fun activeOrFailedScanShowsProgressAction() {
        assertEquals("查看进度", mediaScanHistoryActionLabel(MediaScanStatus.RUNNING))
        assertEquals("查看进度", mediaScanHistoryActionLabel(MediaScanStatus.FAILED))
    }

    @Test
    fun missingTimestampIsNotInvented() {
        assertEquals("扫描完成 · 已处理 12 项", mediaScanHistorySubtitle(scan(MediaScanStatus.COMPLETED, 0)))
    }

    @Test
    fun realTimestampDistinguishesRepeatedScans() {
        val timestamp = 1_784_000_000_000L
        val subtitle = mediaScanHistorySubtitle(scan(MediaScanStatus.COMPLETED, timestamp))
        assertTrue(subtitle.contains(serverTime(timestamp)))
    }

    private fun scan(status: MediaScanStatus, updatedAt: Long) = MediaScan(
        id = "scan_1", libraryId = "lib_1", status = status, generation = 1, attempt = 1,
        rulesRevision = 1, phase = MediaScanPhase.READY, processed = 12,
        createdAt = updatedAt, updatedAt = updatedAt, errorCode = null, sourceLabel = "电影目录",
    )
}
