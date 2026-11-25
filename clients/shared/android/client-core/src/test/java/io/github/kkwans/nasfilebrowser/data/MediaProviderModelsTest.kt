package io.github.kkwans.nasfilebrowser.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaProviderModelsTest {
    @Test
    fun emptyPolicyLanguageUsesChineseDefaults() {
        val row = JSONObject()
            .put("libraryId", "lib_fixture")
            .put("mode", "local_only")
            .put("language", "")
            .put("region", "")
            .put("allowEditorJobs", false)
            .put("revision", 1)
        val policy = parseProviderPolicy(row)
        assertEquals("zh-CN", policy.language)
        assertEquals("CN", policy.region)
    }

    @Test
    fun policyDurationThresholdsUseServerValuesOrCompatibleDefaults() {
        val legacy = parseProviderPolicy(JSONObject()
            .put("libraryId", "lib_fixture")
            .put("mode", "local_only")
            .put("language", "zh-CN")
            .put("region", "CN")
            .put("allowEditorJobs", false)
            .put("revision", 1))
        assertEquals(60, legacy.movieMinMinutes)
        assertEquals(10, legacy.seriesMinMinutes)
        val custom = parseProviderPolicy(JSONObject()
            .put("libraryId", "lib_fixture")
            .put("mode", "local_only")
            .put("language", "zh-CN")
            .put("region", "CN")
            .put("movieMinMinutes", 75)
            .put("seriesMinMinutes", 15)
            .put("allowEditorJobs", false)
            .put("revision", 2))
        assertEquals(75, custom.movieMinMinutes)
        assertEquals(15, custom.seriesMinMinutes)
    }

    @Test
    fun refreshPreviewPreservesProtectedFields() {
        val preview = parseMetadataRefreshPreview(JSONObject()
            .put("currentLanguage", "en-US")
            .put("targetLanguage", "zh-CN")
            .put("changedFields", JSONArray(listOf("title", "plot")))
            .put("protectedFields", JSONArray(listOf("title")))
            .put("preservedFields", JSONArray(listOf("plot")))
            .put("canRefresh", true))
        assertEquals("zh-CN", preview.targetLanguage)
        assertEquals(listOf("title", "plot"), preview.changedFields)
        assertEquals(listOf("title"), preview.protectedFields)
        assertEquals(listOf("plot"), preview.preservedFields)
    }

    @Test
    fun libraryRefreshPreviewPreservesCountsAndFieldHistograms() {
        val preview = parseMetadataRefreshLibraryPreview(JSONObject()
            .put("libraryId", "lib_fixture")
            .put("targetLanguage", "zh-CN")
            .put("region", "CN")
            .put("mode", "local_first_online")
            .put("policyRevision", 4)
            .put("matchedItems", 12)
            .put("languageMismatchItems", 7)
            .put("alreadyCurrentItems", 5)
            .put("lockedItems", 3)
            .put("explicitClearItems", 1)
            .put("nfoPreservedItems", 2)
            .put("protectedFields", JSONObject().put("title", 3))
            .put("preservedFields", JSONObject().put("plot", 2))
            .put("currentLanguages", JSONObject().put("en-US", 7).put("zh-CN", 5))
            .put("fieldChangesRequireItemPreview", true)
            .put("canRefresh", true))
        assertEquals(12, preview.matchedItems)
        assertEquals(7, preview.languageMismatchItems)
        assertEquals(mapOf("title" to 3), preview.protectedFields)
        assertEquals(mapOf("en-US" to 7, "zh-CN" to 5), preview.currentLanguages)
        assertEquals(true, preview.fieldChangesRequireItemPreview)
    }

    @Test
    fun metadataRefreshJobAndActionsUseStableContracts() {
        val job = parseMetadataRefreshJob(JSONObject()
            .put("jobId", "job_fixture")
            .put("libraryId", "lib_fixture")
            .put("status", "completed")
            .put("generation", 3)
            .put("language", "zh-CN")
            .put("region", "CN")
            .put("total", 4)
            .put("refreshed", 2)
            .put("alreadyCurrent", 1)
            .put("preserved", 1)
            .put("sourceChanged", 0)
            .put("failed", 0)
            .put("pending", 0)
            .put("undone", 0))
        assertEquals("job_fixture", job.jobId)
        assertEquals(4L, job.total)
        assertEquals(2L, job.refreshed)
        val operation = MediaOperation(MediaAccountScope("profile", 1, "account", 7), "op_fixture")
        val enqueue = MediaMutationRequest(operation, MediaMutationKind.ENQUEUE_METADATA_REFRESH_JOB, "lib_fixture",
            body = JSONObject().put("operationId", operation.id).put("mode", "refresh_unlocked").put("expectedPolicyRevision", 2))
        assertEquals("/api/media-library/v1/libraries/lib_fixture/metadata-refresh-jobs", enqueue.endpoint)
        val cancel = MediaMutationRequest(operation, MediaMutationKind.CANCEL_METADATA_REFRESH_JOB, "lib_fixture",
            body = JSONObject().put("operationId", operation.id).put("generation", 3), jobId = "job_fixture")
        assertEquals("DELETE", cancel.method)
        assertEquals("/api/media-library/v1/libraries/lib_fixture/metadata-refresh-jobs/job_fixture", cancel.endpoint)
        val restored = MediaMutationRequest.fromJson(operation, cancel.toJson())
        assertEquals(cancel.jobId, restored.jobId)
        assertTrue(restored.sameRequest(cancel))
    }

    @Test
    fun legacyMatchJournalWithoutCandidateTicketStillRestores() {
        val operation = MediaOperation(MediaAccountScope("profile", 1, "account", 7), "op_fixture")
        val request = MediaMutationRequest.fromJson(operation, JSONObject()
            .put("kind", "CONFIRM_PROVIDER_MATCH")
            .put("libraryId", "lib_fixture")
            .put("itemId", "item_fixture")
            .put("body", JSONObject()
                .put("operationId", operation.id)
                .put("itemRevision", 2)
                .put("structureRevision", 1)
                .put("kind", "movie")
                .put("externalId", "55")
                .put("connectionId", "")))
        assertEquals("/api/media-library/v1/libraries/lib_fixture/items/item_fixture/match-confirmation", request.endpoint)
    }

    @Test
    fun refreshMutationUsesDurableMetadataEndpoint() {
        val operation = MediaOperation(MediaAccountScope("profile", 1, "account", 7), "op_fixture")
        val request = MediaMutationRequest(operation, MediaMutationKind.REFRESH_METADATA, "lib_fixture", itemId = "item_fixture",
            body = JSONObject().put("operationId", operation.id).put("itemRevision", 2).put("structureRevision", 1))
        assertEquals("/api/media-library/v1/libraries/lib_fixture/items/item_fixture/metadata-refresh", request.endpoint)
    }
}
