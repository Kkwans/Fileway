package io.github.kkwans.nasfilebrowser.ui.medialibrary

import io.github.kkwans.nasfilebrowser.data.MediaMetadataRefreshJob
import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneMediaSettingsTest {
    @Test
    fun refreshJobStatusDistinguishesPartialFailure() {
        val job = MediaMetadataRefreshJob("job_1", "lib_1", "completed", "", 2, "zh-CN", "CN",
            3, 2, 0, 0, 0, 1, 0, 0)
        assertEquals("刷新完成，部分条目失败", refreshJobStatusLabel(job))
        assertEquals("刷新完成", refreshJobStatusLabel(job.copy(failed = 0)))
    }

    @Test
    fun metadataFieldCountsAreStableAndReadable() {
        assertEquals("plot ×2、title ×3", formatMetadataFieldCounts(mapOf("title" to 3, "plot" to 2)))
        assertEquals("无", formatMetadataFieldCounts(emptyMap()))
    }
}
