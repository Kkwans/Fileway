package io.github.kkwans.nasfilebrowser.data

import java.net.URLEncoder

/** Encode a legacy ?path= argument from the authoritative escaped wire path.
 * Preserve its percent octets (including non-UTF8 bytes), but escape query
 * delimiters which are legal unescaped characters in a URL path. */
fun wireQueryPath(path: String, wirePath: String): String {
    if (wirePath.isEmpty()) return URLEncoder.encode(path, "UTF-8")
    require(wirePath.startsWith('/')) { "原始文件路径无效" }
    return buildString {
        var index = 0
        while (index < wirePath.length) {
            val char = wirePath[index]
            if (char == '%') {
                require(index + 2 < wirePath.length && wirePath[index + 1].digitToIntOrNull(16) != null && wirePath[index + 2].digitToIntOrNull(16) != null) { "原始文件路径编码无效" }
                append(wirePath, index, index + 3)
                index += 3
            } else if (char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char in "-._~/") {
                append(char)
                index++
            } else {
                val end = index + Character.charCount(wirePath.codePointAt(index))
                append(URLEncoder.encode(wirePath.substring(index, end), "UTF-8"))
                index = end
            }
        }
    }
}
