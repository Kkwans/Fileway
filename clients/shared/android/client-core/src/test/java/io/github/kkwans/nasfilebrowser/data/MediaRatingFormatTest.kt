package io.github.kkwans.nasfilebrowser.data

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaRatingFormatTest {
    @Test
    fun defaultRatingUsesOneDecimalPlace() {
        assertEquals("8.6 / 10", formatMediaRating(8.64, 10.0, MediaRatingMetric.USER_AVERAGE))
        assertEquals("8.0 / 10", formatMediaRating(8.0, 10.0, MediaRatingMetric.USER_AVERAGE))
    }

    @Test
    fun detailedRatingKeepsAvailablePrecision() {
        assertEquals("8.64 / 10", formatMediaRating(8.64, 10.0, MediaRatingMetric.USER_AVERAGE, detailed = true))
        assertEquals("8.0 / 10", formatMediaRating(8.0, 10.0, MediaRatingMetric.USER_AVERAGE, detailed = true))
    }

    @Test
    fun percentageRatingsUseTheSamePrecisionRules() {
        assertEquals("86.3%", formatMediaRating(8.63, 10.0, MediaRatingMetric.CRITIC_APPROVAL))
        assertEquals("86.25%", formatMediaRating(86.25, 100.0, MediaRatingMetric.CRITIC_APPROVAL, detailed = true))
    }

    @Test
    fun missingScaleDoesNotInventADenominator() {
        assertEquals("8.6", formatMediaRating(8.64, null, MediaRatingMetric.USER_AVERAGE))
    }
}
