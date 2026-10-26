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
    private val screen = HdrDisplayInfo(known = true, pq = true, hlg = true, dolby = true, ratio = 4f)
    private fun output(mime: String = "video/hevc", configured: String = mime, transfer: Int? = C.COLOR_TRANSFER_ST2084,
        space: Int? = C.COLOR_SPACE_BT2020, staticHdr: Boolean = true, name: String = "decoder") =
        RenderedVideoOutput(1, name, mime, configured, "video/raw", space, transfer, C.COLOR_RANGE_LIMITED, staticHdr)

    @Test fun dolbyBadgeRequiresConfiguredDolbyAndActualHdrFrame() {
        assertEquals(ActiveVideoOutput.DOLBY_VISION, activeVideoOutput(output("video/dolby-vision"), screen, true))
        assertEquals("HDR · 杜比视界", videoPresentation("demo.mkv", ActiveVideoOutput.DOLBY_VISION))
    }
    @Test fun hdr10FallbackDoesNotAdvertiseTheDolbySourceAsActiveDolby() {
        assertEquals(ActiveVideoOutput.HDR10, activeVideoOutput(output(name = "c2.vendor.dv.decoder"), screen, true))
        assertEquals(ActiveVideoOutput.HDR10, activeVideoOutput(output("video/dolby-vision", configured = "video/hevc"), screen, true))
    }
    @Test fun unsupportedDisplayDoesNotAcquireADolbyBadge() {
        assertEquals(ActiveVideoOutput.HDR10, activeVideoOutput(output("video/dolby-vision"), screen.copy(dolby = false), true))
        assertEquals(ActiveVideoOutput.UNKNOWN, activeVideoOutput(output(), screen.copy(pq = false), true))
    }
    @Test fun firstFrameAndDecoderOutputAreRequired() {
        assertEquals(ActiveVideoOutput.UNKNOWN, activeVideoOutput(null, screen, true))
        assertEquals(ActiveVideoOutput.UNKNOWN, activeVideoOutput(output(), screen, false))
        assertEquals(ActiveVideoOutput.UNKNOWN, activeVideoOutput(output(transfer = null), screen, true))
        assertEquals(ActiveVideoOutput.UNKNOWN, activeVideoOutput(output(space = null), screen, true))
    }
    @Test fun sourceCapabilityAndMissingOrInvalidHeadroomCannotActivateABadge() {
        for (ratio in listOf(null, 1f, 0f, Float.NaN, Float.POSITIVE_INFINITY))
            assertEquals(ActiveVideoOutput.UNKNOWN, activeVideoOutput(output(), screen.copy(ratio = ratio), true))
        assertEquals(ActiveVideoOutput.UNKNOWN, activeVideoOutput(output(), screen.copy(known = false), true))
    }
    @Test fun sdrDecodedOutputNeverKeepsAnHdrBadge() {
        val sdr = activeVideoOutput(output("video/dolby-vision", transfer = C.COLOR_TRANSFER_SDR), screen, true)
        assertEquals(ActiveVideoOutput.SDR, sdr)
        assertEquals("MKV", videoPresentation("demo.mkv", sdr))
    }
    @Test fun hdr10NeedsStaticMetadataAndHlgNeedsTheActualTransfer() {
        assertEquals(ActiveVideoOutput.HDR, activeVideoOutput(output(staticHdr = false), screen, true))
        assertEquals(ActiveVideoOutput.HLG, activeVideoOutput(output(transfer = C.COLOR_TRANSFER_HLG), screen, true))
    }
    @Test fun unknownOutputShowsContainerWithoutGuessingFromFilename() {
        assertEquals("MKV", videoPresentation("Dolby Vision HDR10 demo.mkv", ActiveVideoOutput.UNKNOWN))
        assertEquals("MP4", videoPresentation("HDR10+ demo.mp4", ActiveVideoOutput.UNKNOWN))
        assertEquals("—", videoPresentation("no extension", ActiveVideoOutput.UNKNOWN))
        assertEquals("HDR10", videoPresentation("demo.mkv", ActiveVideoOutput.HDR10))
    }
}
