package io.github.kkwans.nasfilebrowser.player

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoPresentationTest {
    @Test fun dolbyVisionSurvivesHevcFallbackMimeAndTakesPriorityOverPq() {
        assertEquals("杜比视界", sourceDynamicRange("video/hevc", "hvc1.2.4, dvhe.08.06", C.COLOR_TRANSFER_ST2084))
        assertEquals("杜比视界", sourceDynamicRange("video/dolby-vision", null, null))
        assertEquals("杜比视界", sourceDynamicRange("video/av01", "dav1.10.09", C.COLOR_TRANSFER_HLG))
    }
    @Test fun transferMetadataIsRequiredForHdrWithoutDolbyIdentifier() {
        assertEquals("HDR（PQ）", sourceDynamicRange("video/hevc", "hvc1.2.4", C.COLOR_TRANSFER_ST2084))
        assertEquals("HDR（HLG）", sourceDynamicRange("video/hevc", null, C.COLOR_TRANSFER_HLG))
        assertEquals("未知", sourceDynamicRange("video/hevc", "hvc1.2.4", null))
        assertEquals("SDR", sourceDynamicRange("video/avc", "avc1", C.COLOR_TRANSFER_SDR))
    }
    @Test fun ordinaryUsersSeeContainerAndOnlyKnownSpecialRange() {
        assertEquals("MKV · 杜比视界", videoPresentation("demo.mkv", "杜比视界"))
        assertEquals("MOV · HDR（HLG）", videoPresentation("demo.MOV", "HDR（HLG）"))
        assertEquals("MP4", videoPresentation("demo.mp4", "SDR"))
        assertEquals("MP4", videoPresentation("HDR10+ demo.mp4", "未知"))
        assertEquals("—", videoPresentation("no extension", "未知"))
    }
}
