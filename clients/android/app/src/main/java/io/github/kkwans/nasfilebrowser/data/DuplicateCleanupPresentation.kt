package io.github.kkwans.nasfilebrowser.data

import org.json.JSONObject

/** A recorded result is independent of task progress and may be only a checkpoint. */
internal enum class DuplicateCleanupOutcome { SUCCESS, SKIPPED, FAILED, UNKNOWN }

internal data class DuplicateCleanupCounts(val success: Int, val skipped: Int, val failed: Int, val unknown: Int)

internal data class DuplicateCleanupFilePresentation(
    val path: String, val rawStatus: String, val trashId: String?, val reason: String?,
) {
    val outcome: DuplicateCleanupOutcome get() = when (rawStatus) {
        "success" -> DuplicateCleanupOutcome.SUCCESS
        "skipped" -> DuplicateCleanupOutcome.SKIPPED
        "failed" -> DuplicateCleanupOutcome.FAILED
        else -> DuplicateCleanupOutcome.UNKNOWN
    }
}

internal data class DuplicateCleanupGroupPresentation(
    val sha256: String, val keepPath: String, val files: List<DuplicateCleanupFilePresentation>,
) {
    val counts get() = cleanupCounts(files)
    val filesForDisplay get() = cleanupDisplayOrder(files)
}

internal data class DuplicateCleanupPresentation(val reportId: String, val groups: List<DuplicateCleanupGroupPresentation>) {
    val counts get() = cleanupCounts(groups.flatMap { it.files })
    val filesForDisplay get() = cleanupDisplayOrder(groups.flatMap { it.files })

    companion object {
        /** Missing/malformed required fields never become an empty successful result. */
        fun from(result: JSONObject): DuplicateCleanupPresentation? {
            val reportId = result.requiredText("reportId") ?: return null
            val rows = result.optJSONArray("groups") ?: return null
            val groups = ArrayList<DuplicateCleanupGroupPresentation>(rows.length())
            for (index in 0 until rows.length()) {
                val group = rows.optJSONObject(index) ?: return null
                val hash = group.requiredText("sha256") ?: return null
                val keep = group.requiredText("keepPath") ?: return null
                val entries = group.optJSONArray("files") ?: return null
                val files = ArrayList<DuplicateCleanupFilePresentation>(entries.length())
                for (fileIndex in 0 until entries.length()) {
                    val file = entries.optJSONObject(fileIndex) ?: return null
                    val path = file.requiredText("path") ?: return null
                    val status = file.requiredText("status") ?: return null
                    if (!file.optionalTextValid("trashId") || !file.optionalTextValid("reason")) return null
                    files += DuplicateCleanupFilePresentation(path, status, file.optionalText("trashId"), file.optionalText("reason"))
                }
                groups += DuplicateCleanupGroupPresentation(hash, keep, files)
            }
            return DuplicateCleanupPresentation(reportId, groups)
        }
    }
}

private fun JSONObject.requiredText(key: String) = (opt(key) as? String)?.takeIf { it.isNotBlank() }
private fun JSONObject.optionalTextValid(key: String) = !has(key) || isNull(key) || opt(key) is String
private fun JSONObject.optionalText(key: String) = (opt(key) as? String)?.takeIf { it.isNotEmpty() }
private fun cleanupCounts(files: List<DuplicateCleanupFilePresentation>) = DuplicateCleanupCounts(
    files.count { it.outcome == DuplicateCleanupOutcome.SUCCESS }, files.count { it.outcome == DuplicateCleanupOutcome.SKIPPED },
    files.count { it.outcome == DuplicateCleanupOutcome.FAILED }, files.count { it.outcome == DuplicateCleanupOutcome.UNKNOWN },
)
private fun cleanupDisplayOrder(files: List<DuplicateCleanupFilePresentation>) = files.sortedBy {
    when (it.outcome) { DuplicateCleanupOutcome.FAILED -> 0; DuplicateCleanupOutcome.UNKNOWN -> 1; else -> 2 }
}
