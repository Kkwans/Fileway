package io.github.kkwans.fileway.tv.medialibrary

internal data class TvMediaFocusTarget(val id: String, val origin: String)

internal fun resolveTvMediaFocusTarget(
    requestedId: String?,
    requestedOrigin: String?,
    availableIds: List<String>,
    anchorIndex: Int,
): TvMediaFocusTarget? {
    val id = requestedId ?: return null
    if (requestedOrigin == "navigation") return TvMediaFocusTarget(id, requestedOrigin)
    val ids = availableIds.distinct()
    if (id in ids) return TvMediaFocusTarget(id, requestedOrigin.orEmpty())
    if (ids.isEmpty()) return TvMediaFocusTarget("files", "navigation")
    val fallback = ids.getOrNull(anchorIndex.coerceIn(ids.indices)) ?: ids.first()
    return TvMediaFocusTarget(fallback, requestedOrigin.orEmpty())
}
