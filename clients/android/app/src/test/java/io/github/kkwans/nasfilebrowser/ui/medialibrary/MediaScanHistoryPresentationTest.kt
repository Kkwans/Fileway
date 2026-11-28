package io.github.kkwans.nasfilebrowser.ui.medialibrary

import io.github.kkwans.nasfilebrowser.data.MediaMetadataJob
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
        assertEquals("扫描完成 · 全量覆盖 · 已处理 12 项", mediaScanHistorySubtitle(scan(MediaScanStatus.COMPLETED, 0)))
    }

    @Test
    fun realTimestampDistinguishesRepeatedScans() {
        val timestamp = 1_784_000_000_000L
        val subtitle = mediaScanHistorySubtitle(scan(MediaScanStatus.COMPLETED, timestamp))
        assertTrue(subtitle.contains(serverTime(timestamp)))
    }

    @Test
    fun resultSummaryUsesRealOutcomeTotals() {
        val summary = mediaScanResultSummary(MediaMetadataJob(
            status = MediaScanStatus.RUNNING, generation = 1, failureCode = null,
            matched = 5, review = 1, skipped = 1, failed = 1, pending = 2,
        ))
        assertEquals(10L, summary.total)
        assertEquals(8L, summary.completed)
        assertEquals(5L, summary.matched)
        assertEquals(1L, summary.review)
        assertEquals(1L, summary.failed)
        assertEquals(2L, summary.pending)
    }

    private fun scan(status: MediaScanStatus, updatedAt: Long) = MediaScan(
        id = "scan_1", libraryId = "lib_1", status = status, generation = 1, attempt = 1,
        rulesRevision = 1, phase = MediaScanPhase.READY, processed = 12,
        createdAt = updatedAt, updatedAt = updatedAt, errorCode = null, sourceLabel = "电影目录",
    )
}
