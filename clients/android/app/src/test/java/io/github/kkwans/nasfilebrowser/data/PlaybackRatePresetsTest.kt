package io.github.kkwans.nasfilebrowser.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PlaybackRatePresetsTest {
    @Test fun savedCustomValuesRoundTripSortedWithoutDuplicates() {
        val values = normalizePlaybackRatePresets(listOf(1.8f, .1f, 1.1f, 1.8f, 5f))
        assertEquals(listOf(.1f, 1.1f, 1.8f, 5f), values)
        assertEquals(values, readPlaybackRatePresets(values.joinToString(",")))
    }

    @Test fun missingOrCorruptStorageRestoresUsableDefaults() {
        listOf(null, "", "1,garbage", "NaN", "Infinity", "0.09", "5.1")
            .forEach { assertEquals(it, DefaultPlaybackRatePresets, readPlaybackRatePresets(it)) }
    }

    @Test fun invalidWritesCannotReplaceSavedPresets() {
        listOf(emptyList(), listOf(Float.NaN), listOf(0f), listOf(6f))
            .forEach { values -> assertThrows(IllegalArgumentException::class.java) { normalizePlaybackRatePresets(values) } }
    }
}
