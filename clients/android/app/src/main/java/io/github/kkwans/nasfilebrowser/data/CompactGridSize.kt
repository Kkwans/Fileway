package io.github.kkwans.nasfilebrowser.data

/** Device-local compact-grid tiers; phone column and filename rules are user-defined. */
enum class CompactGridSize(val label: String, val columns: Int, val nameLines: Int, val previewSizeDp: Int) {
    SMALL("小", 4, 2, 48), MEDIUM("中", 3, 2, 64), LARGE("大", 2, 1, 104);

    companion object {
        fun fromStored(value: String?): CompactGridSize = entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: MEDIUM
    }
}
