package io.github.kkwans.nasfilebrowser.ui

internal data class BreadcrumbWidths(val firstVisible: Int, val widths: List<Int>)

/** Inputs are real measured pixels. Keep parent/current, then add ancestors that fit in full. */
internal fun planBreadcrumbWidths(desired: List<Int>, available: Int, separator: Int, overflow: Int,
    minimumTarget: Int): BreadcrumbWidths {
    if (desired.isEmpty()) return BreadcrumbWidths(0, emptyList())
    val minimum = minimumTarget.coerceAtLeast(1)
    val natural = desired.map { it.coerceAtLeast(minimum) }
    fun total(start: Int) = natural.drop(start).sum() + separator * (natural.size - start - 1) +
        if (start > 0) overflow + separator else 0
    var start = (natural.size - 2).coerceAtLeast(0)
    while (start > 0 && total(start - 1) <= available) start--
    val count = natural.size - start
    val budget = (available - separator * (count - 1) - if (start > 0) overflow + separator else 0).coerceAtLeast(count)
    if (natural.drop(start).sum() <= budget) return BreadcrumbWidths(start, natural.drop(start))
    // Reserve recognizable ancestor targets, then grant the current directory first claim.
    val base = minOf(minimum, budget / count).coerceAtLeast(1)
    val result = MutableList(count) { base }
    var left = (budget - base * count).coerceAtLeast(0)
    for (index in result.indices.reversed()) {
        val extra = minOf(left, (natural[start + index] - result[index]).coerceAtLeast(0))
        result[index] += extra
        left -= extra
    }
    return BreadcrumbWidths(start, result)
}

/** Neighbour identities are a recovery path when a refresh removes the visible object. */
internal data class CollectionAnchor(val key: String, val offset: Int, val neighbours: List<String>) {
    fun resolve(keys: List<String>): Pair<Int, Int> {
        val exact = keys.indexOf(key)
        if (exact >= 0) return exact to offset.coerceAtLeast(0)
        val near = neighbours.firstNotNullOfOrNull { candidate -> keys.indexOf(candidate).takeIf { it >= 0 } }
        return (near ?: 0) to 0
    }

    companion object {
        fun capture(keys: List<String>, key: String, offset: Int): CollectionAnchor {
            val index = keys.indexOf(key)
            val near = if (index < 0) emptyList() else (1..8).flatMap { distance ->
                listOfNotNull(keys.getOrNull(index + distance), keys.getOrNull(index - distance))
            }
            return CollectionAnchor(key, offset.coerceAtLeast(0), near)
        }
    }
}

/** A presentation-only break: resource identity and accessible text retain the original name. */
internal fun extensionOrphanBreak(name: String, secondLineStart: Int, fits: (String) -> Boolean): Int? {
    val dot = name.lastIndexOf('.')
    if (dot <= 0 || dot == name.lastIndex || name.length - dot > 13 || secondLineStart < dot) return null
    var split = dot
    while (split > 0) {
        split = name.offsetByCodePoints(split, -1)
        if (!fits(name.substring(split))) break
        if (fits(name.substring(0, split))) return split.takeIf { it > 0 }
    }
    return null
}

/** Greedy filename layout may wrap at metadata punctuation instead of moving an
 * entire CJK-tail/Latin suffix to the next line. The resource name stays intact. */
internal fun fileNameBreakHints(name: String): String = buildString(name.length) {
    name.forEachIndexed { index, character ->
        append(character)
        if (character in "._-]）)" && index < name.lastIndex && !name[index + 1].isWhitespace() && name[index + 1] != '\u200B') append('\u200B')
    }
}
