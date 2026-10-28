package io.github.kkwans.nasfilebrowser.player

import java.util.Locale

/** Declared container roles, not a guess based on film title or language. */
data class AudioTrackRole(val original: Boolean = false, val commentary: Boolean = false)
data class AudioSelectionCandidate(val id: Int, val language: String, val default: Boolean = false,
    val role: AudioTrackRole = AudioTrackRole(), val supported: Boolean = true)

/** One initial choice; a manual command also invalidates any queued automatic command. */
class AudioSelectionPolicy(restoring: Boolean = false) {
    private var decided = restoring
    var userOrRestoreOwnsSelection = restoring
        private set

    fun manualChoice() { decided = true; userOrRestoreOwnsSelection = true }

    fun initialChoice(tracks: List<AudioSelectionCandidate>): Int? {
        if (decided || tracks.isEmpty()) return null
        decided = true
        val playable = tracks.filter { it.supported }
        if (playable.size <= 1) return null
        fun language(track: AudioSelectionCandidate) = track.language.trim().lowercase(Locale.ROOT)
            .replace('_', '-').substringBefore('-')
        val foreign = playable.filter { language(it) !in setOf("", "und", "mul", "mis", "zxx", "zh", "zho", "chi", "cmn", "yue") }
        // All-Chinese/unknown tracks retain the source choice; never mute the video.
        if (foreign.isEmpty()) return null
        val main = foreign.filterNot { it.role.commentary }.ifEmpty { foreign }
        return main.maxWithOrNull(compareBy<AudioSelectionCandidate> { it.role.original }
            .thenBy { it.default }.thenBy { language(it) in setOf("en", "eng") })?.id
    }
}
