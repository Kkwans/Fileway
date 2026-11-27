package io.github.kkwans.nasfilebrowser.ui.medialibrary

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import coil3.ImageLoader
import io.github.kkwans.nasfilebrowser.data.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal data class PhoneMediaFailedSearch(val query: String, val page: Int)

internal fun phoneMediaMatchRetrySearch(error: MediaProblem?, failed: PhoneMediaFailedSearch?): PhoneMediaFailedSearch? =
    failed?.takeIf { error?.title == "查询失败" }

@Composable
internal fun PhoneMediaMatchRoute(repository: MediaLibraryRepository, ref: MediaCatalogRef, imageLoader: ImageLoader,
    onBack: () -> Unit, onMatched: () -> Unit) {
    key(repository, ref) {
        val scope = rememberCoroutineScope()
        var identification by remember { mutableStateOf<MediaProviderIdentification?>(null) }
        var policy by remember { mutableStateOf<MediaProviderPolicy?>(null) }
        var query by rememberSaveable { mutableStateOf("") }
        var searchedQuery by remember { mutableStateOf("") }
        var candidates by remember { mutableStateOf<List<MediaProviderCandidate>>(emptyList()) }
        var selected by remember { mutableStateOf<MediaProviderCandidate?>(null) }
        var seriesMode by rememberSaveable { mutableStateOf(false) }
        var seasonNumber by rememberSaveable { mutableStateOf("1") }
        var episodeNumbers by rememberSaveable { mutableStateOf("1") }
        var seriesPreview by remember { mutableStateOf<MediaSeriesMappingPreview?>(null) }
        var refreshPreview by remember { mutableStateOf<MediaMetadataRefreshPreview?>(null) }
        var nextPage by remember { mutableStateOf<Int?>(null) }
        var failedSearch by remember { mutableStateOf<PhoneMediaFailedSearch?>(null) }
        var error by remember { mutableStateOf<MediaProblem?>(null) }
        var busy by remember { mutableStateOf(false) }
        var loading by remember { mutableStateOf(false) }
        var pending by remember { mutableStateOf<MediaOperation?>(null) }
        var searchJob by remember { mutableStateOf<Job?>(null) }
        var searchGeneration by remember { mutableIntStateOf(0) }
        var refresh by remember { mutableIntStateOf(0) }
        LaunchedEffect(refresh) {
            searchGeneration++; searchJob?.cancel()
            loading = true; error = null; failedSearch = null; candidates = emptyList(); selected = null; seriesPreview = null; refreshPreview = null; searchedQuery = ""
            try {
                pending = repository.pendingMatchConfirmation(ref)
                when (val result = repository.identification(ref)) {
                    is MediaReadResult.Success -> {
                        identification = result.value
                        if (query.isBlank()) query = result.value.file?.parsedTitle?.takeIf { it.isNotBlank() } ?: result.value.title
                    }
                    is MediaReadResult.Failure -> error = MediaProblem("无法读取当前识别", providerProblem(result.error), "重试")
                }
                when (val result = repository.metadataPolicy(ref.libraryId)) {
                    is MediaReadResult.Success -> policy = result.value
                    is MediaReadResult.Failure -> if (error == null) error = MediaProblem("无法读取资料设置", providerProblem(result.error), "重试")
                }
            } finally { loading = false }
        }
        fun search(value: String, page: Int) {
            val item = identification ?: return
            if (value.toByteArray(Charsets.UTF_8).size > 512 || value.any { it.isISOControl() || it == '/' || it == '\\' }) {
                error = MediaProblem("请输入有效的片名", "可使用中文或原名，不要包含目录路径。", "刷新资料")
                return
            }
            if (policy?.mode !in setOf(MediaProviderPolicyMode.LOCAL_FIRST_ONLINE, MediaProviderPolicyMode.ONLINE_FIRST)) {
                error = MediaProblem("此库未启用 TMDB", "在影视设置中为此媒体库选择 TMDB 连接和资料优先级。", "重新读取设置")
                return
            }
            searchJob?.cancel()
            val requestGeneration = ++searchGeneration
            failedSearch = null
            if (page == 1) { candidates = emptyList(); selected = null; nextPage = null; seriesPreview = null }
            searchedQuery = value
            val kind = if (item.structureKind == MediaItemKind.SERIES || seriesMode) MediaProviderMatchKind.SERIES else MediaProviderMatchKind.MOVIE
            searchJob = scope.launch {
                loading = true; error = null
                try {
                    when (val result = repository.searchMatchCandidates(item, kind, page = page, title = value.trim(), connectionId = policy?.connectionId)) {
                        is MediaReadResult.Success -> {
                            if (requestGeneration == searchGeneration) {
                                candidates = (candidates + result.value.items).distinctBy { it.externalId }
                                nextPage = if (result.value.hasMore) result.value.page + 1 else null
                            }
                        }
                        is MediaReadResult.Failure -> if (requestGeneration == searchGeneration) {
                            failedSearch = PhoneMediaFailedSearch(value, page)
                            error = MediaProblem("查询失败", providerProblem(result.error), "重试")
                        }
                    }
                } finally { if (requestGeneration == searchGeneration) loading = false }
            }
        }
        fun previewSeries() {
            val item = identification ?: return
            if (busy || pending != null || item.structureKind != MediaItemKind.VIDEO) return
            val season = seasonNumber.toIntOrNull()
            val episodes = episodeNumbers.split(',').map { it.trim().toIntOrNull() }
            if (season == null || season !in 0..999 || episodes.isEmpty() || episodes.size > 100 ||
                episodes.any { it == null || it !in 0..9999 } || query.isBlank() ||
                query.codePointCount(0, query.length) > 1024 || query.toByteArray(Charsets.UTF_8).size > 4096 ||
                query.any(Char::isISOControl) || !Charsets.UTF_8.newEncoder().canEncode(query)) {
                error = MediaProblem("请填写季集", "季号为 0–999，集号为 0–9999；多个集号用英文逗号分隔。")
                return
            }
            val numbers = episodes.filterNotNull().distinct().sorted()
            busy = true; error = null; seriesPreview = null
            scope.launch {
                try {
                    when (val result = repository.previewSeriesMapping(repository.newOperation(), item, query.trim(), season, numbers, selected)) {
                        is MediaReadResult.Success -> seriesPreview = result.value
                        is MediaReadResult.Failure -> error = MediaProblem("无法预览关联", providerProblem(result.error), "刷新资料")
                    }
                } finally { busy = false }
            }
        }
        fun previewRefresh() {
            val item = identification ?: return
            if (busy || pending != null || item.matchState != MediaCatalogMatchState.PROVIDER_CONFIRMED) return
            busy = true; error = null
            scope.launch {
                try {
                    when (val result = repository.previewMetadataRefresh(item.ref)) {
                        is MediaReadResult.Success -> refreshPreview = result.value
                        is MediaReadResult.Failure -> error = MediaProblem("无法预览资料刷新", providerProblem(result.error), "重试")
                    }
                } finally { busy = false }
            }
        }
        fun submit(candidate: MediaProviderCandidate? = null, undo: MediaProviderIdentification? = null, undoMapping: Boolean = false) {
            val previous = pending
            val mapping = seriesPreview
            if (busy || previous == null && candidate?.canConfirm != true && mapping == null &&
                undo?.undoMatchRevision == null && !(undoMapping && undo?.undoMappingRevision != null)) return
            val operation = previous ?: repository.newOperation()
            busy = true; error = null
            scope.launch {
                try {
                    val result = when {
                        previous != null -> repository.retryOperation(previous)
                        undoMapping && undo != null -> repository.undoSeriesMapping(operation, undo)
                        undo != null -> repository.undoMatch(operation, undo)
                        mapping != null -> repository.confirmSeriesMapping(operation, mapping)
                        else -> repository.confirmMatch(operation, requireNotNull(candidate))
                    }
                    when (result) {
                        is MediaWriteResult.Success -> { repository.forgetResolvedOperation(operation); onMatched() }
                        is MediaWriteResult.Failure -> {
                            pending = operation.takeIf { result.error.mutationMayHaveCommitted }
                            error = MediaProblem(if (pending != null) "结果待确认" else "无法保存匹配", providerProblem(result.error),
                                if (pending != null) "确认上次操作" else "刷新资料")
                        }
                    }
                } finally { busy = false }
            }
        }
        fun applyRefresh() {
            val item = identification ?: return
            if (pending != null) { submit(); return }
            if (busy || refreshPreview?.canRefresh != true) return
            val operation = repository.newOperation()
            busy = true; error = null
            scope.launch {
                try {
                    when (val result = repository.refreshMetadata(operation, item)) {
                        is MediaWriteResult.Success -> { repository.forgetResolvedOperation(operation); onMatched() }
                        is MediaWriteResult.Failure -> {
                            pending = operation.takeIf { result.error.mutationMayHaveCommitted }
                            error = MediaProblem(if (pending != null) "结果待确认" else "无法刷新资料", providerProblem(result.error),
                                if (pending != null) "确认上次操作" else "重试")
                        }
                    }
                } finally { busy = false }
            }
        }
        val item = identification
        val revision = item?.itemRevision?.toString().orEmpty()
        PhoneMediaMatchReview(PhoneMediaMatchState(key(ref), revision, item?.title.orEmpty(),
            listOfNotNull(item?.file?.filename, item?.file?.year?.toString()), query,
            MediaSection(candidates.map { row ->
                MediaMatchCandidate(row.externalId, row.title, listOfNotNull(row.originalTitle?.takeIf { it != row.title }, row.year?.toString()).joinToString(" · "),
                    "TMDB ${row.externalId}", row.evidence.mapNotNull { hint -> when (hint) {
                        "title_match" -> "片名相符"; "year_match" -> "年份相符"; "year_difference" -> "年份不同"
                        "explicit_id_match" -> "编号相符"; "explicit_id_difference", "explicit_id_conflict" -> "编号有冲突"
                        else -> null
                    } }.joinToString(" · ").ifBlank { null })
            }, loading = loading, hasMore = nextPage != null), selected?.externalId ?: if (seriesPreview != null) "local-series" else null,
            if (seriesMode) seriesPreview?.let { preview ->
                MediaMatchImpact(selected?.externalId ?: "local-series", revision,
                    "将此视频关联到《${preview.title}》第 ${preview.season} 季第 ${preview.episodes.joinToString("、")} 集。",
                    listOf("原视频和观看记录保留。") +
                        (if (preview.combined) listOf("这是多集合并文件，不会虚构单集起点。") else emptyList()) +
                        (if (preview.preservedFields.isNotEmpty()) listOf("已锁定和手工修改的资料会保留。") else emptyList()), true)
            } else selected?.let { MediaMatchImpact(it.externalId, revision, "为此条目使用《${it.title}》的 TMDB 资料。", listOf("原视频和已经锁定的字段会保留。"), it.canConfirm) },
            busy = busy, canApply = pending == null, problem = error ?: pending?.let { MediaProblem("结果待确认", "可以确认上次的匹配操作。", "确认上次操作") },
            canUndo = pending == null && !loading && item?.undoMatchRevision != null,
            canChooseSeries = item?.structureKind == MediaItemKind.VIDEO, seriesMode = seriesMode,
            seasonNumber = seasonNumber, episodeNumbers = episodeNumbers,
            canUndoMapping = pending == null && !loading && item?.undoMappingRevision != null,
            searched = searchedQuery.isNotBlank(), refreshPreview = refreshPreview,
            canRefresh = pending == null && item?.matchState == MediaCatalogMatchState.PROVIDER_CONFIRMED),
            imageLoader, onAction = { action ->
                when (action) {
                    PhoneMediaAction.Back -> if (!busy) onBack()
                    is PhoneMediaAction.MatchQuery -> { searchGeneration++; searchJob?.cancel(); loading = false; failedSearch = null; query = action.query; searchedQuery = ""; selected = null; candidates = emptyList(); nextPage = null; seriesPreview = null }
                    is PhoneMediaAction.MatchSeriesMode -> { searchGeneration++; searchJob?.cancel(); loading = false; failedSearch = null; seriesMode = action.series; searchedQuery = ""; selected = null; candidates = emptyList(); nextPage = null; seriesPreview = null; error = null }
                    is PhoneMediaAction.MatchSeason -> { seasonNumber = action.value; seriesPreview = null }
                    is PhoneMediaAction.MatchEpisodes -> { episodeNumbers = action.value; seriesPreview = null }
                    PhoneMediaAction.PreviewSeriesMapping -> previewSeries()
                    PhoneMediaAction.UndoSeriesMapping -> submit(undo = identification, undoMapping = true)
                    is PhoneMediaAction.SearchMatches -> if (!busy && pending == null) search(action.query, 1)
                    is PhoneMediaAction.PreviewMatch -> { selected = candidates.firstOrNull { it.externalId == action.candidateId }; seriesPreview = null }
                    is PhoneMediaAction.ApplyMatch -> if (seriesMode) submit() else submit(selected?.takeIf { it.externalId == action.candidateId })
                    PhoneMediaAction.UndoMatch -> submit(undo = identification)
                    PhoneMediaAction.PreviewMetadataRefresh -> previewRefresh()
                    PhoneMediaAction.ApplyMetadataRefresh -> applyRefresh()
                    is PhoneMediaAction.LoadMore -> nextPage?.let { search(searchedQuery, it) }
                    is PhoneMediaAction.Retry -> when {
                        pending != null -> submit()
                        phoneMediaMatchRetrySearch(error, failedSearch) != null -> phoneMediaMatchRetrySearch(error, failedSearch)?.let { search(it.query, it.page) }
                        else -> refresh++
                    }
                    is PhoneMediaAction.ReloadMatch -> refresh++
                    else -> Unit
                }
            })
    }
}
