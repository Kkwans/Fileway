package io.github.kkwans.nasfilebrowser.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
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
