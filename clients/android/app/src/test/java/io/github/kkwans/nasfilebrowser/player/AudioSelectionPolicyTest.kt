package io.github.kkwans.nasfilebrowser.player

import org.junit.Assert.*
import org.junit.Test

class AudioSelectionPolicyTest {
    private fun track(id: Int, language: String, default: Boolean = false, original: Boolean = false,
        commentary: Boolean = false, supported: Boolean = true) = AudioSelectionCandidate(id, language, default,
            AudioTrackRole(original, commentary), supported)
    private fun choose(vararg tracks: AudioSelectionCandidate) = AudioSelectionPolicy().initialChoice(tracks.toList())

    @Test fun englishWinsOverChineseEvenWhenChineseIsDefault() {
        assertEquals(2, choose(track(1, "zh", default = true), track(2, "en")))
    }
    @Test fun reliableOriginalWinsOverEnglishAndOtherForeignDefault() {
        assertEquals(3, choose(track(1, "en"), track(2, "de", default = true), track(3, "ja", original = true)))
    }
    @Test fun foreignSourceDefaultComesBeforeEnglishFallback() {
        assertEquals(2, choose(track(1, "eng"), track(2, "fr", default = true), track(3, "chi")))
    }
    @Test fun englishFallbackComesBeforeOtherUnmarkedForeignLanguages() {
        assertEquals(3, choose(track(1, "zh"), track(2, "de"), track(3, "en-US")))
    }
    @Test fun chineseTagsAndLocalesAreNormalized() {
        for (chinese in listOf("zh", "zho", "chi", "cmn", "yue", "ZH_hant", "zh-CN"))
            assertEquals(2, choose(track(1, chinese, default = true), track(2, "ENG")))
    }
    @Test fun allChineseAllUnknownAndSingleTrackKeepTheSourceSelection() {
        assertNull(choose(track(1, "zh"), track(2, "yue")))
        assertNull(choose(track(1, "und"), track(2, "")))
        assertNull(choose(track(1, "zh")))
        assertNull(choose(track(1, "fr")))
    }
    @Test fun unsupportedAndCommentaryTracksDoNotDisplacePlayableMainAudio() {
        assertEquals(3, choose(track(1, "en", original = true, supported = false),
            track(2, "en", default = true, original = true, commentary = true), track(3, "ja")))
    }
    @Test fun stableSourceOrderBreaksEqualRanks() {
        assertEquals(2, choose(track(2, "fr"), track(3, "de")))
    }
    @Test fun emptyInitialNotificationDoesNotConsumeTheChoice() {
        val policy = AudioSelectionPolicy()
        assertNull(policy.initialChoice(emptyList()))
        assertEquals(2, policy.initialChoice(listOf(track(1, "zh"), track(2, "en"))))
    }
    @Test fun trackRefreshDoesNotKeepReapplyingDefaults() {
        val policy = AudioSelectionPolicy()
        val tracks = listOf(track(1, "zh"), track(2, "en"))
        assertEquals(2, policy.initialChoice(tracks))
        assertNull(policy.initialChoice(tracks))
    }
    @Test fun manualChoiceInvalidatesAnAutomaticCommandAlreadyQueued() {
        val policy = AudioSelectionPolicy()
        assertEquals(2, policy.initialChoice(listOf(track(1, "zh"), track(2, "en"))))
        policy.manualChoice()
        assertTrue(policy.userOrRestoreOwnsSelection)
        assertNull(policy.initialChoice(listOf(track(1, "zh"), track(2, "en"))))
    }
    @Test fun decoderRestoreKeepsThePriorSelectionIncludingExplicitOff() {
        val policy = AudioSelectionPolicy(restoring = true)
        assertTrue(policy.userOrRestoreOwnsSelection)
        assertNull(policy.initialChoice(listOf(track(1, "zh"), track(2, "en"))))
    }
}
