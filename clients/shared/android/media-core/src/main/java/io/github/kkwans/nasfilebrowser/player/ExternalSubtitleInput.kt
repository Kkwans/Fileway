package io.github.kkwans.nasfilebrowser.player

import java.io.IOException
import java.io.OutputStream

const val EXTERNAL_SUP_BYTES_LIMIT = 500L * 1024 * 1024
const val EXTERNAL_TEXT_BYTES_LIMIT = 16L * 1024 * 1024

class ExternalSubtitleLimitException(limit: Long) : IOException(
    if (limit == EXTERNAL_SUP_BYTES_LIMIT) "SUP 字幕超过 500 MiB，请选择较小的文件"
    else "文本字幕超过 16 MiB，请选择较小的文件",
)

class ExternalSubtitleStorageException : IOException("手机可用空间不足，无法读取 SUP 字幕")

/** Bounded streaming for both unknown-length SAF providers and authenticated
 * DataSources. The caller owns the stream and destination lifetime. */
fun copyExternalSubtitle(
    read: (ByteArray, Int, Int) -> Int,
    output: OutputStream,
    limit: Long,
    checkCancelled: () -> Unit,
): Long {
    require(limit in 1..EXTERNAL_SUP_BYTES_LIMIT)
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        checkCancelled()
        // Read at most one byte beyond the cap to distinguish exact-limit EOF.
        val requested = minOf(buffer.size.toLong(), limit - total + 1).toInt()
        val count = read(buffer, 0, requested)
        if (count == -1) return total
        check(count in 1..requested) { "字幕来源没有返回有效数据" }
        if (count.toLong() > limit - total) throw ExternalSubtitleLimitException(limit)
        checkCancelled()
        output.write(buffer, 0, count)
        total += count
    }
}
