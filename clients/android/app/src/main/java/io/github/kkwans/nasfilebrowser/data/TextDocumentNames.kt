package io.github.kkwans.nasfilebrowser.data

import io.github.kkwans.nasfilebrowser.app.FileCategory
import java.util.Locale

private val additionalTextExtensions = setOf(
    "txt", "text", "log", "csv", "tsv", "ipynb", "tex", "bib", "rst", "adoc", "org", "diff", "patch",
    "srt", "ass", "ssa", "vtt", "smi", "sami", "lrc", "lock", "service", "desktop",
)
private val textBasenames = setOf(
    "readme", "license", "licence", "notice", "authors", "changelog", "makefile", "dockerfile",
    "gemfile", "rakefile", "procfile", "cmakelists.txt", ".gitignore", ".gitattributes",
    ".gitmodules", ".dockerignore", ".editorconfig", ".npmrc", ".yarnrc", ".bashrc", ".zshrc",
)

/** A name only selects the text reader. Byte/encoding/size validation remains mandatory. */
internal fun isTextDocumentName(name: String): Boolean {
    val leaf = name.substringAfterLast('/').lowercase(Locale.ROOT)
    return leaf in textBasenames || leaf.substringAfterLast('.', "") in additionalTextExtensions ||
        FileCategory.MARKDOWN.matches(leaf, false) || FileCategory.CONFIG.matches(leaf, false) ||
        FileCategory.CODE.matches(leaf, false)
}
