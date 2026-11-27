package io.github.kkwans.nasfilebrowser.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaScanModeTest {
    @Test
    fun scanModeParsesAndDefaultsToFullCover() {
        val row = JSONObject()
            .put("id", "scan_1").put("libraryId", "lib_1").put("status", "queued")
            .put("generation", 1).put("attempt", 0).put("rulesRevision", 19)
            .put("phase", "enumerate").put("processed", 0).put("createdAt", 1).put("updatedAt", 1)
        assertEquals(MediaScanMode.INCREMENTAL, parseMediaScan(row.put("mode", "incremental")).mode)
        assertEquals(MediaScanMode.FULL_FILL, parseMediaScan(row.put("mode", "full_fill")).mode)
        assertEquals(MediaScanMode.FULL_COVER, parseMediaScan(row.put("mode", "full_cover")).mode)
        assertEquals(MediaScanMode.FULL_COVER, parseMediaScan(row.apply { remove("mode") }).mode)
    }
}
