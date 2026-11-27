package io.github.kkwans.nasfilebrowser.ui.medialibrary

import io.github.kkwans.nasfilebrowser.data.MediaScanMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaScanModePresentationTest {
    @Test
    fun scanModesExplainTheirRealEffects() {
        assertEquals("增量扫描", mediaScanModeLabel(MediaScanMode.INCREMENTAL))
        assertEquals("全量补齐", mediaScanModeLabel(MediaScanMode.FULL_FILL))
        assertEquals("全量覆盖", mediaScanModeLabel(MediaScanMode.FULL_COVER))
        assertTrue(mediaScanModeDescription(MediaScanMode.INCREMENTAL).contains("不会移除"))
        assertTrue(mediaScanModeDescription(MediaScanMode.FULL_FILL).contains("不会移除"))
        assertTrue(mediaScanModeDescription(MediaScanMode.FULL_COVER).contains("标记为缺失"))
    }
}
