package io.github.kkwans.nasfilebrowser.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaProviderModelsTest {
    @Test
    fun technicalObservationRetainsCodecHdrAndTracks() {
        val observation = parseMediaTechnicalObservation(JSONObject()
            .put("durationMs", 5_400_000).put("videoCodec", "hevc").put("videoBitDepth", 10)
            .put("width", 3840).put("height", 2160).put("dynamicRange", "dolby-vision").put("formatTitle", "本杰明·巴顿奇事.2008.BD1080p")
            .put("audioTracks", JSONArray(listOf(JSONObject().put("index", 1).put("codec", "eac3").put("language", "eng")))))
        assertEquals(5_400_000L, observation.durationMs)
        assertEquals("dolby-vision", observation.dynamicRange)
        assertEquals("本杰明·巴顿奇事.2008.BD1080p", observation.formatTitle)
        assertEquals("eng", observation.audioTracks.single().language)
        assertTrue(observation.subtitleTracks.isEmpty())
    }

    @Test
    fun technicalFormatterUsesRealCodecResolutionHdrAndTrackEvidence() {
        val observation = MediaTechnicalObservation(
            durationMs = 7_938_645, videoCodec = "hevc", videoProfile = "Main 10", pixelFormat = "yuv420p10le",
            videoBitDepth = 10, width = 3840, height = 2160, colorTransfer = "smpte2084", dynamicRange = "pq",
            audioTracks = listOf(
                MediaTechnicalTrack(0, "eac3", "eng", null, true, false),
                MediaTechnicalTrack(1, "aac", "zho", null, false, false),
            ),
            subtitleTracks = listOf(MediaTechnicalTrack(2, "subrip", "zho", null, false, false)),
        )
        assertEquals(listOf("3840×2160", "HEVC", "10-bit", "YUV420P10LE", "HDR PQ"), MediaTechnicalFormatter.badges(observation))
        assertEquals(listOf(
            "片长 132 分钟",
            "音轨 2 · EAC3 / AAC · 英语 / 中文",
            "字幕 1 · SUBRIP · 中文",
        ), MediaTechnicalFormatter.lines(observation))
    }

    @Test
    fun technicalFormatterOmitsUnknownValuesAndCapsLanguageSummary() {
        val observation = MediaTechnicalObservation(
            durationMs = 60_000, videoCodec = null, videoProfile = null, pixelFormat = null,
            videoBitDepth = null, width = null, height = null, colorTransfer = null, dynamicRange = "unknown",
            audioTracks = (1..5).map { MediaTechnicalTrack(it, "aac", listOf("eng", "jpn", "kor", "fra", "deu")[it - 1], null, false, false) },
            subtitleTracks = emptyList(),
        )
        assertEquals(emptyList<String>(), MediaTechnicalFormatter.badges(observation))
        assertEquals(listOf("片长 1 分钟", "音轨 5 · AAC · 英语 / 日语 / 韩语等 5 种"), MediaTechnicalFormatter.lines(observation))
    }

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
            .put("episodeMetadataItems", 2)
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
        assertEquals(2, preview.episodeMetadataItems)
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
