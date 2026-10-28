package io.github.kkwans.nasfilebrowser.data

enum class ImageQuality(val label: String, val pixels: Int?) { ORIGINAL("原图", null), HIGH("高", 2560), MEDIUM("中", 1080), LOW("低", 512) }
