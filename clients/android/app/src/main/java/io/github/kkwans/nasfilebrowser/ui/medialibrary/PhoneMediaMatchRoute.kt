package io.github.kkwans.nasfilebrowser.ui.medialibrary

import androidx.compose.runtime.*
import coil3.ImageLoader
import io.github.kkwans.nasfilebrowser.data.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
internal fun PhoneMediaMatchRoute(repository: MediaLibraryRepository, ref: MediaCatalogRef, imageLoader: ImageLoader,
    onBack: () -> Unit, onMatched: () -> Unit) {
    key(repository, ref) {
        val scope = rememberCoroutineScope()
        var identification by remember { mutableStateOf<MediaProviderIdentification?>(null) }
        var policy by remember { mutableStateOf<MediaProviderPolicy?>(null) }
        var query by remember { mutableStateOf("") }
        var searchedQuery by remember { mutableStateOf("") }
        var candidates by remember { mutableStateOf<List<MediaProviderCandidate>>(emptyList()) }
        var selected by remember { mutableStateOf<MediaProviderCandidate?>(null) }
        var nextPage by remember { mutableStateOf<Int?>(null) }
        var error by remember { mutableStateOf<MediaProblem?>(null) }
        var busy by remember { mutableStateOf(false) }
        var loading by remember { mutableStateOf(false) }
        var pending by remember { mutableStateOf<MediaOperation?>(null) }
        var searchJob by remember { mutableStateOf<Job?>(null) }
        var refresh by remember { mutableIntStateOf(0) }
        LaunchedEffect(refresh) {
            loading = true; error = null; candidates = emptyList(); selected = null
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
            if (page == 1) { candidates = emptyList(); selected = null; nextPage = null }
            searchedQuery = value
            val kind = if (item.structureKind == MediaItemKind.SERIES) MediaProviderMatchKind.SERIES else MediaProviderMatchKind.MOVIE
            searchJob = scope.launch {
                loading = true; error = null
                try {
                    when (val result = repository.searchMatchCandidates(item, kind, page = page, title = value.trim(), connectionId = policy?.connectionId)) {
                        is MediaReadResult.Success -> {
                            candidates = (candidates + result.value.items).distinctBy { it.externalId }
                            nextPage = if (result.value.hasMore) result.value.page + 1 else null
                        }
                        is MediaReadResult.Failure -> error = MediaProblem("查询失败", providerProblem(result.error), "重试")
                    }
                } finally { loading = false }
            }
        }
        fun submit(candidate: MediaProviderCandidate? = null, undo: MediaProviderIdentification? = null) {
            val previous = pending
            if (busy || previous == null && candidate?.canConfirm != true && undo?.undoMatchRevision == null) return
            val operation = previous ?: repository.newOperation()
            busy = true; error = null
            scope.launch {
                try {
                    val result = when {
                        previous != null -> repository.retryOperation(previous)
                        undo != null -> repository.undoMatch(operation, undo)
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
            }, loading = loading, hasMore = nextPage != null), selected?.externalId,
            selected?.let { MediaMatchImpact(it.externalId, revision, "为此条目使用《${it.title}》的 TMDB 资料。", listOf("原视频和已经锁定的字段会保留。"), it.canConfirm) },
            busy = busy, canApply = pending == null, problem = error ?: pending?.let { MediaProblem("结果待确认", "可以确认上次的匹配操作。", "确认上次操作") },
            canUndo = pending == null && !loading && item?.undoMatchRevision != null),
            imageLoader, onAction = { action ->
                when (action) {
                    PhoneMediaAction.Back -> if (!busy) onBack()
                    is PhoneMediaAction.MatchQuery -> { query = action.query; selected = null }
                    is PhoneMediaAction.SearchMatches -> if (!busy && pending == null) search(action.query, 1)
                    is PhoneMediaAction.PreviewMatch -> selected = candidates.firstOrNull { it.externalId == action.candidateId }
                    is PhoneMediaAction.ApplyMatch -> submit(selected?.takeIf { it.externalId == action.candidateId })
                    PhoneMediaAction.UndoMatch -> submit(undo = identification)
                    is PhoneMediaAction.LoadMore -> nextPage?.let { search(searchedQuery, it) }
                    is PhoneMediaAction.Retry -> if (pending != null) submit() else refresh++
                    is PhoneMediaAction.ReloadMatch -> refresh++
                    else -> Unit
                }
            })
    }
}
