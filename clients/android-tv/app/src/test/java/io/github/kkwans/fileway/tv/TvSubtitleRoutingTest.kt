package io.github.kkwans.fileway.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvSubtitleRoutingTest {
    private val chinese = TvSubtitleKey("ass", "zh", "双语", true, false)
    private val english = TvSubtitleKey("subrip", "en", "English", false, false)

    @Test fun reorderedEngineTracksKeepTheOriginalSourceIndex() {
        val source = mapOf(5 to chinese, 8 to english)
        val native = linkedMapOf(90 to english, 41 to chinese)
        assertEquals(TvSubtitleMatch(5, 41), matchUniqueTvSubtitle(chinese, source, native))
        assertEquals(TvSubtitleMatch(8, 90), matchUniqueTvSubtitle(english, source, native))
    }

    @Test fun duplicatedSourceMetadataRequiresSourceExtraction() {
        assertNull(matchUniqueTvSubtitle(chinese, mapOf(5 to chinese, 6 to chinese), mapOf(41 to chinese)))
    }

    @Test fun duplicatedEngineMetadataCannotChooseTheFirstTrack() {
        assertNull(matchUniqueTvSubtitle(chinese, mapOf(5 to chinese), mapOf(41 to chinese, 42 to chinese)))
    }

    @Test fun forcedCaptionsAreNotOrdinaryCaptions() {
        assertNull(matchUniqueTvSubtitle(chinese, mapOf(5 to chinese), mapOf(41 to chinese.copy(forced = true))))
    }

    @Test fun missingTracksCannotBeReplacedByEqualListLength() {
        assertNull(matchUniqueTvSubtitle(chinese, mapOf(5 to chinese), mapOf(41 to english)))
    }
}
