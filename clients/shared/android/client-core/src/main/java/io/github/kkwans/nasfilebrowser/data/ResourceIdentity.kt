package io.github.kkwans.nasfilebrowser.data

import java.io.ByteArrayOutputStream

/** Decode wire bytes exactly once, without interpreting legacy filename bytes. */
fun resourceWireBytes(wire: String): ByteArray {
    // Reuse the shared strict segment validator before decoding once. This
    // rejects encoded slash/NUL and dot segments without changing Linux bytes.
    recentAccessWireIdentity(wire)
    require(wire.startsWith('/') && !wire.startsWith("//") && !wire.contains('?') && !wire.contains('#')) { "路径来源无效，请刷新后重试" }
    val input = wire.toByteArray(Charsets.UTF_8); val output = ByteArrayOutputStream()
    var index = 0
    while (index < input.size) {
        if (input[index] == '%'.code.toByte()) {
            require(index + 2 < input.size) { "路径编码无效" }
            val high = input[index + 1].toInt().toChar().digitToIntOrNull(16)
            val low = input[index + 2].toInt().toChar().digitToIntOrNull(16)
            require(high != null && low != null) { "路径编码无效" }
            output.write(high * 16 + low); index += 3
        } else { output.write(input[index].toInt()); index++ }
    }
    return output.toByteArray()
}


/** NAS path comparison: preserve case, Unicode and legal filename spaces. */
fun collectionPath(path: String): String {
    val segments = ArrayDeque<String>()
    path.split('/').forEach { when (it) { "", "." -> Unit; ".." -> if (segments.isNotEmpty()) segments.removeLast(); else -> segments.add(it) } }
    return "/" + segments.joinToString("/")
}
