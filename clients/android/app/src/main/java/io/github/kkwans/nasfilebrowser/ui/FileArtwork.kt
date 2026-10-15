package io.github.kkwans.nasfilebrowser.ui

import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.app.FileCategory
import io.github.kkwans.nasfilebrowser.app.MediaKind
import io.github.kkwans.nasfilebrowser.app.mediaKind
import java.util.Locale

/** Display fields suffice; callers must never fabricate resource identities to choose an icon. */
internal fun fileArtworkIcon(name: String, directory: Boolean, type: String = ""): Int {
    if (directory) return R.drawable.ic_folder
    when (mediaKind(name, directory, type)) {
        MediaKind.IMAGE -> return R.drawable.ic_image
        MediaKind.VIDEO -> return R.drawable.ic_video
        MediaKind.AUDIO -> return R.drawable.ic_audio
        null -> Unit
    }
    if (FileCategory.PDF.matches(name, false)) return R.drawable.ic_pdf
    if (FileCategory.MARKDOWN.matches(name, false)) return R.drawable.ic_markdown
    if (FileCategory.CODE.matches(name, false)) return R.drawable.ic_code
    if (FileCategory.CONFIG.matches(name, false)) return R.drawable.ic_config
    return when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "csv", "tsv" -> R.drawable.ic_csv
        "zip", "7z", "rar", "tar", "gz" -> R.drawable.ic_archive
        "txt", "srt", "ass", "vtt", "log" -> R.drawable.ic_text
        else -> if (type == "text") R.drawable.ic_text else R.drawable.ic_file
    }
}
