package io.github.kkwans.nasfilebrowser

import io.github.kkwans.nasfilebrowser.data.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@RunWith(AndroidJUnit4::class)
class DuplicateCleanupPresentationTest {
    private fun result(files: JSONArray) = JSONObject().put("reportId", "original-report")
        .put("groups", JSONArray().put(JSONObject().put("sha256", "original-hash").put("keepPath", "/保留 %+.png").put("files", files)))
    private fun file(path: String, status: String) = JSONObject().put("path", path).put("status", status)

    @Test fun checkpointCountsUseRecordedStatusesAndKeepOpaqueValues() {
        val input = result(JSONArray().put(file("/ok", "success").put("trashId", "trash-1"))
            .put(file("/skipped", "skipped").put("reason", "already completed"))
            .put(file("/future %?#", "future-status")).put(file("/failed", "failed").put("reason", "identity changed")))
        val parsed = requireNotNull(DuplicateCleanupPresentation.from(input))
        assertEquals("original-report", parsed.reportId)
        assertEquals("/保留 %+.png", parsed.groups.single().keepPath)
        assertEquals(DuplicateCleanupCounts(1, 1, 1, 1), parsed.counts)
        assertEquals(listOf("/failed", "/future %?#", "/ok", "/skipped"), parsed.filesForDisplay.map { it.path })
        assertEquals("future-status", parsed.filesForDisplay[1].rawStatus)
        assertEquals("trash-1", parsed.groups.single().files[0].trashId)
        assertEquals("already completed", parsed.groups.single().files[1].reason)
        assertEquals(listOf("/ok", "/skipped", "/future %?#", "/failed"), parsed.groups.single().files.map { it.path })
    }

    @Test fun displayOrderIsStableWithinFailureUnknownAndOtherOutcomes() {
        val parsed = requireNotNull(DuplicateCleanupPresentation.from(result(JSONArray()
            .put(file("/s1", "skipped")).put(file("/f1", "failed")).put(file("/u1", "new"))
            .put(file("/f2", "failed")).put(file("/s2", "success")).put(file("/u2", "other")))))
        assertEquals(listOf("/f1", "/f2", "/u1", "/u2", "/s1", "/s2"), parsed.groups.single().filesForDisplay.map { it.path })
    }

    @Test fun invalidRequiredStructureIsRejectedRatherThanReportedAsZeroSuccess() {
        val malformed = listOf(JSONObject(), JSONObject().put("reportId", "x"),
            JSONObject().put("reportId", 123).put("groups", JSONArray()),
            JSONObject().put("reportId", "x").put("groups", JSONArray().put("not-a-group")),
            result(JSONArray().put(JSONObject().put("path", "/a"))),
            result(JSONArray().put(file("", "success"))),
            result(JSONArray().put(file("/a", "success").put("reason", 2))))
        malformed.forEach { assertNull(it.toString(), DuplicateCleanupPresentation.from(it)) }
    }

    @Test fun validEmptyCheckpointRemainsEmptyWithoutInventingFiles() {
        val empty = requireNotNull(DuplicateCleanupPresentation.from(JSONObject().put("reportId", "x").put("groups", JSONArray())))
        assertEquals(DuplicateCleanupCounts(0, 0, 0, 0), empty.counts)
        assertTrue(empty.filesForDisplay.isEmpty())
        assertTrue(requireNotNull(DuplicateCleanupPresentation.from(result(JSONArray()))).groups.single().files.isEmpty())
    }
}
