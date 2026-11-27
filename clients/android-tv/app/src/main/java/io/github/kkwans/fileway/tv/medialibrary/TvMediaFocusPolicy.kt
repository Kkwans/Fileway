package io.github.kkwans.fileway.tv.medialibrary

internal data class TvMediaFocusTarget(val id: String, val origin: String)

internal fun resolveTvSearchEntryFocusTarget(requested: TvMediaFocusTarget?): TvMediaFocusTarget =
    requested ?: TvMediaFocusTarget("search-field", "search")

internal fun resolveTvCatalogSearchFocusTarget(
    requestedId: String?,
    requestedOrigin: String?,
    availableIds: List<String>,
): TvMediaFocusTarget = if (requestedOrigin != null && requestedOrigin in setOf("person-result", "file-result", "collection-result", "poster") && requestedId != null && requestedId in availableIds) {
    TvMediaFocusTarget(requestedId, requestedOrigin)
} else {
    TvMediaFocusTarget("search-field", "search")
}

internal fun resolveTvCatalogCategoryFocusTarget(
    requestedId: String?,
    availableIds: List<String>,
): TvMediaFocusTarget {
    val ids = availableIds.distinct()
    if (ids.isEmpty()) return TvMediaFocusTarget("files", "navigation")
    val id = requestedId?.takeIf { it in ids } ?: ids.firstOrNull { it == "all" } ?: ids.first()
    return TvMediaFocusTarget(id, "category")
}

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
