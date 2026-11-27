package io.github.kkwans.nasfilebrowser.data

import java.math.BigDecimal
import java.util.Locale

fun formatMediaRating(
    value: Double,
    scale: Double?,
    metric: MediaRatingMetric,
    detailed: Boolean = false,
): String {
    val validScale = scale?.takeIf { it.isFinite() && it > 0 }
    val percentage = validScale != null &&
        (metric == MediaRatingMetric.CRITIC_APPROVAL || metric == MediaRatingMetric.AUDIENCE_APPROVAL)
    val number = if (percentage) value / validScale * 100 else value
    val formatted = if (detailed) detailedRatingNumber(number) else String.format(Locale.ROOT, "%.1f", number)
    val suffix = if (percentage) "%" else validScale?.let { " / ${plainRatingNumber(it)}" }.orEmpty()
    return formatted + suffix
}

private fun detailedRatingNumber(value: Double): String {
    val decimal = BigDecimal.valueOf(value).stripTrailingZeros()
    return if (decimal.scale() <= 0) decimal.setScale(1).toPlainString() else decimal.toPlainString()
}

private fun plainRatingNumber(value: Double): String = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
