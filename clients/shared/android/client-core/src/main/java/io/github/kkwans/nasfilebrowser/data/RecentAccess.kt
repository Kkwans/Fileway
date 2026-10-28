package io.github.kkwans.nasfilebrowser.data

import io.github.kkwans.nasfilebrowser.app.ResourceRef
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

const val RECENT_ACCESS_LIMIT = 100

/** /api/recent returns a canonical JSON path, never a URI or an encoded route. */
data class RecentAccessEntry(
    val id: String, val path: String, val wirePath: String, val name: String,
    val isDir: Boolean, val accessedAt: Long,
    val openable: Boolean = true,
) {
    fun resource(): ResourceRef {
        check(openable) { "服务器未提供该路径的原始字节，无法安全打开" }
        return ResourceRef(path, wirePath, name, isDir, "", 0)
    }
}

fun parseRecentAccessEntry(row: JSONObject): RecentAccessEntry {
    val id = row.get("id") as? String ?: error("最近访问缺少记录标识")
    val path = row.get("path") as? String ?: error("最近访问缺少路径")
    val name = row.get("name") as? String ?: error("最近访问缺少名称")
    val isDir = row.get("isDir") as? Boolean ?: error("最近访问类型无效")
    val rawTime = row.get("accessedAt")
    val time = if (rawTime is Number) rawTime.toString().toLongOrNull() else null
    require(id.isNotBlank() && name.isNotEmpty() && validRecentAccessPath(path) && time != null && time > 0) {
        "服务器返回了无效的最近访问记录"
    }
    val wire = if (row.has("wirePath")) row.get("wirePath") as? String ?: error("最近访问原始路径格式无效") else null
    val verified = if (row.has("pathVerified")) row.get("pathVerified") as? Boolean ?: error("最近访问路径状态无效") else null
    if (wire != null) {
        recentAccessWireIdentity(wire)
        decodeRecentAccessWirePath(wire)?.let { require(it == path) { "最近访问路径与原始路径不一致" } }
    }
    val openable = verified != false && (wire != null || !path.contains('\uFFFD'))
    return RecentAccessEntry(id, path, if (openable) wire ?: SearchResult.encodePath(path) else "", name, isDir, time,
        openable = openable)
}

fun parseRecentAccess(rows: JSONArray): List<RecentAccessEntry> =
    (0 until rows.length()).map { parseRecentAccessEntry(rows.getJSONObject(it)) }
        .sortedWith(compareByDescending<RecentAccessEntry> { it.accessedAt }.thenByDescending { it.id })
        .take(RECENT_ACCESS_LIMIT)

private fun validRecentAccessPath(path: String): Boolean = path.startsWith('/') && !path.contains('\u0000') &&
    collectionPath(path) == path && runCatching {
        Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(path))
    }.isSuccess

data class RecentAccessTarget(val path: String, val wirePath: String, val legacyCompatible: Boolean)

/** wirePath is authoritative; display text must never replace opaque filename bytes. */
fun recentAccessRecordTarget(file: ResourceRef): RecentAccessTarget {
    require(file.downloadId.isEmpty()) { "本机下载不属于服务器最近访问" }
    val path = collectionPath(file.path)
    require(file.path.startsWith('/') && validRecentAccessPath(path)) { "最近访问路径无效" }
    val wire = file.wirePath.ifEmpty { SearchResult.encodePath(path) }
    recentAccessWireIdentity(wire)
    val rawPath = decodeRecentAccessWirePath(wire)
    require(rawPath == null || rawPath == path) { "最近访问路径与原始路径不一致" }
    require(file.wirePath.isNotEmpty() || !path.contains('\uFFFD')) { "服务器未提供原始路径，该次访问无法安全同步" }
    return RecentAccessTarget(path, wire, legacyCompatible = rawPath == path)
}

/** Canonical byte identity only; never use display names to merge entries. */
fun recentAccessWireIdentity(wire: String): String {
    val segments = wireSegments(wire)
    val hex = "0123456789ABCDEF"
    return buildString(wire.length) {
        segments.forEachIndexed { index, bytes ->
            if (index > 0) append('/')
            for (value in bytes) {
                val byte = value.toInt() and 0xff
                if (byte in 0x41..0x5a || byte in 0x61..0x7a || byte in 0x30..0x39 ||
                    byte == 0x2d || byte == 0x2e || byte == 0x5f || byte == 0x7e) {
                    append(byte.toChar())
                } else {
                    append('%'); append(hex[byte ushr 4]); append(hex[byte and 0x0f])
                }
            }
        }
    }
}

private fun wireSegments(wire: String): List<ByteArray> {
    require(wire.startsWith('/') && !wire.startsWith("//") && !wire.contains('?') && !wire.contains('#'))
    val parts = wire.trimEnd('/').ifEmpty { "/" }.split('/')
    return parts.mapIndexed { partIndex, segment ->
        require(segment.isNotEmpty() || partIndex == 0 || wire == "/") { "原始路径包含空段" }
        val bytes = ByteArrayOutputStream()
        var index = 0
        while (index < segment.length) {
            if (segment[index] == '%') {
                require(index + 2 < segment.length)
                val high = segment[index + 1].digitToIntOrNull(16) ?: error("Invalid wire path")
                val low = segment[index + 2].digitToIntOrNull(16) ?: error("Invalid wire path")
                bytes.write(high * 16 + low); index += 3
            } else {
                require(segment[index].code in 0x21..0x7e)
                bytes.write(segment[index].code); index++
            }
        }
        bytes.toByteArray().also { value ->
            require(value.none { it == 0.toByte() || it == '/'.code.toByte() }) { "原始路径包含无效分隔符" }
            require(!value.contentEquals(byteArrayOf(46)) && !value.contentEquals(byteArrayOf(46, 46))) { "原始路径不能包含相对段" }
        }
    }
}

/** Decode exactly once only to check legacy JSON compatibility; '+' remains literal. */
private fun decodeRecentAccessWirePath(wire: String): String? = runCatching {
    wireSegments(wire).joinToString("/") { bytes ->
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    }
}.getOrNull()

fun recentAccessFailure(error: Exception, recording: Boolean): String {
    val action = if (recording) "该次访问未同步。" else ""
    return action + when ((error as? ServiceException)?.status) {
        401 -> "登录已过期，请重新连接后重试"
        403 -> if (recording) "当前账号没有记录该路径的权限" else "当前账号没有读取最近访问的权限"
        404, 405, 501 -> if (recording) "服务器不支持最近访问接口，或该路径已不存在" else "服务器未提供最近访问接口，请检查服务器版本"
        else -> error.message ?: if (recording) "最近访问记录失败，请稍后重试" else "最近访问读取失败，请重试"
    }
}
