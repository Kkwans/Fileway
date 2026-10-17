package io.github.kkwans.nasfilebrowser.app

import java.time.Instant
import java.util.Locale

/** Same extension groups as the web client's searchFilters.ts; this is filtering, not decoder capability. */
enum class FileCategory(val label: String, private val extensions: Set<String> = emptySet()) {
    ALL("全部"), FOLDER("文件夹"),
    IMAGE("图片", setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "avif")),
    VIDEO("视频", setOf("mp4", "mkv", "avi", "mov", "webm", "m4v", "ts")),
    AUDIO("音频", setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus")),
    PDF("PDF", setOf("pdf")), MARKDOWN("Markdown", setOf("md", "markdown", "mdown", "mkd")),
    CONFIG("配置文件", setOf("json", "json5", "yaml", "yml", "toml", "ini", "conf", "config", "env", "xml", "properties")),
    CODE("代码", setOf("java", "py", "go", "js", "jsx", "ts", "tsx", "vue", "rs", "c", "h", "cpp", "hpp", "cs", "php", "rb", "kt", "kts", "swift", "sh", "ps1", "sql"));

    fun matches(name: String, directory: Boolean): Boolean = when {
        this == ALL -> true
        this == FOLDER -> directory
        directory -> false
        else -> name.substringAfterLast('.', "").lowercase(Locale.ROOT) in extensions
    }
}

enum class FileOrder(val label: String) {
    NAME("名称 A–Z"), NAME_REVERSE("名称 Z–A"), NEWEST("最新修改"), OLDEST("最早修改"), LARGEST("最大文件"), SMALLEST("最小文件")
}

fun presentFiles(files: List<ResourceRef>, category: FileCategory, order: FileOrder): List<ResourceRef> {
    val filtered = files.filter { category.matches(it.name, it.directory) }
    val dates = if (order == FileOrder.NEWEST || order == FileOrder.OLDEST)
        filtered.associateWith { runCatching { Instant.parse(it.modified).toEpochMilli() }.getOrNull() } else emptyMap()
    return filtered.sortedWith { a, b ->
        if (a.directory != b.directory) return@sortedWith if (a.directory) -1 else 1
        val value = when (order) {
            FileOrder.NAME -> a.name.lowercase(Locale.ROOT).compareTo(b.name.lowercase(Locale.ROOT))
            FileOrder.NAME_REVERSE -> b.name.lowercase(Locale.ROOT).compareTo(a.name.lowercase(Locale.ROOT))
            FileOrder.LARGEST -> b.size.compareTo(a.size)
            FileOrder.SMALLEST -> a.size.compareTo(b.size)
            FileOrder.NEWEST, FileOrder.OLDEST -> {
                val first = dates[a]; val second = dates[b]
                when {
                    first == null && second == null -> 0
                    first == null -> 1
                    second == null -> -1
                    order == FileOrder.NEWEST -> second.compareTo(first)
                    else -> first.compareTo(second)
                }
            }
        }
        if (value != 0) value else compareValuesBy(a, b, { it.name.lowercase(Locale.ROOT) }, { it.mediaKey })
    }
}

val ClientState.selectedFileCategories: Set<FileCategory>
    get() = fileCategories.ifEmpty { if (fileCategory == FileCategory.ALL) emptySet() else setOf(fileCategory) }

fun presentFiles(files: List<ResourceRef>, categories: Set<FileCategory>, order: FileOrder): List<ResourceRef> =
    presentFiles(if (categories.isEmpty()) files else files.filter { file -> categories.any { it.matches(file.name, file.directory) } }, FileCategory.ALL, order)
