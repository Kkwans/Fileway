package io.github.kkwans.nasfilebrowser.ui.medialibrary

import coil3.request.ImageRequest

/** Presentation only. IDs are opaque; adapters own authorization, formatting and wire versions.
 * Image requests must already be authorized and scoped to account/instance/asset revision.
 * Never put credentials, upstream URLs or private paths into display strings. */
internal data class MediaArtwork(val request: ImageRequest?)
internal data class MediaProblem(val title: String, val message: String, val recoveryLabel: String? = null)
internal data class MediaSection<T>(val entries: List<T> = emptyList(), val loading: Boolean = false,
    val problem: MediaProblem? = null, val hasMore: Boolean = false)
/** Values/counts include their actual unit. RT metric must say critics/audience; no synthesized
 * aggregate or zero score. Poster priority (Douban/IMDb/RT) belongs in the presentation mapper. */
internal data class MediaRating(val id: String, val source: String, val value: String,
    val metric: String? = null, val count: String? = null, val scope: String? = null)
internal data class MediaPoster(val id: String, val title: String, val subtitle: String,
    val poster: MediaArtwork? = null, val backdrop: MediaArtwork? = null,
    val primaryRating: MediaRating? = null, val availability: String? = null)
internal data class MediaContinue(val id: String, val item: MediaPoster, val progressLabel: String,
    val progress: Float? = null, val playable: Boolean = true)
internal data class MediaCategory(val id: String, val label: String)
internal data class PhoneMediaHomeState(val scopeKey: String, val featured: MediaSection<MediaPoster> = MediaSection(),
    val continuing: MediaSection<MediaContinue> = MediaSection(), val catalog: MediaSection<MediaPoster> = MediaSection(),
    val categories: List<MediaCategory> = emptyList(), val selectedCategory: String? = null,
    val catalogTitle: String = "全部影视", val filterSummary: String? = null, val libraryExists: Boolean = true,
    val canManage: Boolean = false, val carouselAutoAdvance: Boolean = true, val interactionBlocked: Boolean = false,
    val problem: MediaProblem? = null)

internal data class MediaCredit(val id: String, val name: String, val role: String,
    val portrait: MediaArtwork? = null, val personId: String = id)
internal data class MediaSeason(val id: String, val label: String)
internal data class MediaEpisode(val id: String, val numberLabel: String, val title: String,
    val subtitle: String, val image: MediaArtwork? = null, val progress: Float? = null,
    val playable: Boolean = true, val unavailableReason: String? = null)
internal data class PhoneMediaDetailState(val scopeKey: String, val id: String, val revision: String,
    val title: String, val originalTitle: String? = null, val metadata: String = "",
    val backdrop: MediaArtwork? = null, val ratings: List<MediaRating> = emptyList(), val overview: String? = null,
    val playLabel: String = "播放", val canPlay: Boolean = false, val canHandoff: Boolean = false,
    val canChangeUserState: Boolean = false, val canReviewMatch: Boolean = false,
    val favorite: Boolean = false, val watched: Boolean = false, val busy: Boolean = false,
    val sourceCount: Int = 0, val sourceSummary: String? = null, val playProblem: MediaProblem? = null,
    val credits: MediaSection<MediaCredit> = MediaSection(), val seasons: List<MediaSeason> = emptyList(),
    val selectedSeason: String? = null, val episodes: MediaSection<MediaEpisode> = MediaSection(),
    val episodeSummary: String? = null, val loading: Boolean = false, val problem: MediaProblem? = null)

internal data class MediaSourceOption(val id: String, val editionId: String, val edition: String,
    val name: String, val library: String, val specifications: String, val audio: String? = null,
    val available: Boolean, val unavailableReason: String? = null, val recoveryLabel: String? = null)
internal data class PhoneMediaSourcesState(val itemId: String, val revision: String,
    val entries: List<MediaSourceOption>, val selectedId: String? = null, val loading: Boolean = false,
    val busy: Boolean = false, val problem: MediaProblem? = null)

internal data class MediaMatchCandidate(val id: String, val title: String, val metadata: String,
    val externalIds: String, val evidence: String? = null, val poster: MediaArtwork? = null)
internal data class MediaMatchImpact(val candidateId: String, val expectedRevision: String,
    val summary: String, val consequences: List<String>, val canApply: Boolean)
internal data class PhoneMediaMatchState(val itemId: String, val revision: String, val currentTitle: String,
    val evidence: List<String>, val query: String, val candidates: MediaSection<MediaMatchCandidate>,
    val selectedCandidate: String? = null, val impact: MediaMatchImpact? = null, val busy: Boolean = false,
    val canApply: Boolean = false, val suggestionOnly: Boolean = false, val conflict: MediaProblem? = null,
    val problem: MediaProblem? = null)

internal data class MediaSourceRoot(val id: String, val name: String, val description: String,
    val available: Boolean, val unavailableReason: String? = null)
internal data class MediaSetupOption(val id: String, val label: String, val description: String? = null,
    val enabled: Boolean = true)
internal data class MediaLibraryDraft(val name: String = "", val sourceRootId: String? = null,
    val languageId: String? = null, val networkPolicyId: String? = null)
internal data class PhoneMediaSetupState(val draft: MediaLibraryDraft, val roots: List<MediaSourceRoot>,
    val languages: List<MediaSetupOption>, val networkPolicies: List<MediaSetupOption>,
    val canCreate: Boolean, val busy: Boolean = false, val loading: Boolean = false,
    val nameError: String? = null, val sourceError: String? = null, val problem: MediaProblem? = null)

/** Each mutation must be dispatched once by the route; never implement persistence in composables.
 * Routes retain drafts, queries, selected seasons and stable-ID list anchors across destinations.
 * Conflicts retain the user's choice and require a fresh preview before another mutation. */
internal enum class MediaTargetKind { ITEM, EPISODE, CONTINUE }

internal sealed interface PhoneMediaAction {
    data object Back : PhoneMediaAction
    data object Search : PhoneMediaAction
    data object ManageLibraries : PhoneMediaAction
    data object CreateLibrary : PhoneMediaAction
    data object Filters : PhoneMediaAction
    data object Sort : PhoneMediaAction
    data class OpenItem(val id: String) : PhoneMediaAction
    data class Category(val id: String) : PhoneMediaAction
    data class Retry(val section: String) : PhoneMediaAction
    data class LoadMore(val section: String) : PhoneMediaAction
    data class SetCarouselAutoAdvance(val enabled: Boolean) : PhoneMediaAction
    data class Play(val id: String, val fromStart: Boolean = false, val kind: MediaTargetKind = MediaTargetKind.ITEM) : PhoneMediaAction
    data class RemoveContinue(val id: String) : PhoneMediaAction
    data class Handoff(val id: String) : PhoneMediaAction
    data class Sources(val id: String, val kind: MediaTargetKind = MediaTargetKind.ITEM) : PhoneMediaAction
    data class SelectSource(val id: String, val expectedRevision: String) : PhoneMediaAction
    data class RecoverSource(val id: String) : PhoneMediaAction
    data class Favorite(val id: String, val value: Boolean, val expectedRevision: String) : PhoneMediaAction
    data class Watched(val id: String, val value: Boolean, val expectedRevision: String) : PhoneMediaAction
    data class OpenPerson(val id: String) : PhoneMediaAction
    data class AllCredits(val id: String) : PhoneMediaAction
    data class SelectSeason(val id: String) : PhoneMediaAction
    data class JumpToEpisode(val seasonId: String, val number: Int) : PhoneMediaAction
    data class ReviewMatch(val id: String) : PhoneMediaAction
    data class MatchQuery(val query: String) : PhoneMediaAction
    data class SearchMatches(val query: String) : PhoneMediaAction
    data class PreviewMatch(val candidateId: String, val expectedRevision: String) : PhoneMediaAction
    data class ApplyMatch(val candidateId: String, val expectedRevision: String, val suggestion: Boolean) : PhoneMediaAction
    data class ReloadMatch(val itemId: String) : PhoneMediaAction
    data class SetupDraft(val value: MediaLibraryDraft) : PhoneMediaAction
    data class SubmitLibrary(val value: MediaLibraryDraft) : PhoneMediaAction
}
