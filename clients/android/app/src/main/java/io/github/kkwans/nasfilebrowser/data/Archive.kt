package io.github.kkwans.nasfilebrowser.data

import io.github.kkwans.nasfilebrowser.app.ResourceRef
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

const val ARCHIVE_MAX_SELECTED = 500

data class ArchiveEntry(val path: String, val wirePath: String, val name: String, val isDir: Boolean, val size: Long, val modified: Long)
data class ArchiveBlockedEntry(val path: String, val reason: String)
data class ArchiveListing(val archivePath: String, val archiveWirePath: String, val format: String, val sourceSize: Long,
    val sourceModified: Long, val entries: List<ArchiveEntry>, val listedBytes: Long, val blockedCount: Long,
    val blocked: List<ArchiveBlockedEntry>, val truncated: Boolean, val limitReason: String,
    val maxEntries: Long, val maxFileBytes: Long, val maxExtractBytes: Long)
data class ArchiveReport(val archivePath: String, val archiveWirePath: String, val destination: DirectoryCrumb,
    val selectedWires: List<String>, val extractedFiles: Long, val extractedDirs: Long, val extractedBytes: Long,
    val skippedCount: Long, val skipped: List<ArchiveBlockedEntry>, val completedAt: Long)

fun isBrowsableArchive(file: ResourceRef): Boolean = !file.directory && file.downloadId.isEmpty() &&
    listOf(".zip", ".7z", ".rar", ".tar", ".tar.gz", ".tar.bz2", ".tar.xz", ".tar.zst").any { file.name.lowercase(Locale.ROOT).endsWith(it) }

/** A relative archive identity is separate from a filesystem ResourceRef. */
fun archiveEntryWireBytes(wire: String): ByteArray {
    require(wire.isNotEmpty() && !wire.startsWith('/') && !wire.contains('?') && !wire.contains('#')) { "压缩包条目原始路径无效" }
    if (wire == ".") return byteArrayOf(46)
    val parts = wire.split('/')
    require(parts.all { it.isNotEmpty() && it.all { char -> char.code in 0x21..0x7e } }) { "压缩包条目路径编码无效" }
    for (part in parts) {
        val bytes = resourceWireBytes("/$part").drop(1).toByteArray()
        require(bytes.none { it == 0.toByte() || it == '/'.code.toByte() }) { "压缩包条目包含无效分隔符" }
        val portable = bytes.toString(Charsets.ISO_8859_1).replace('\\', '/')
        require(portable.split('/').none { it == "." || it == ".." } && !portable.startsWith('/')) { "压缩包条目包含不安全路径" }
    }
    val bytes = resourceWireBytes("/$wire").drop(1).toByteArray()
    require(!bytes.toString(Charsets.ISO_8859_1).substringBefore('/').contains(':')) { "压缩包条目不能是系统绝对路径" }
    return bytes
}

internal fun archiveAbsoluteWire(path: String, wire: String): String {
    require(path.startsWith('/') && !path.contains('\u0000')) { "服务器路径无效" }
    require(wire.isNotEmpty() || !path.contains('\uFFFD')) { "服务器未提供原始路径，请更新服务器后重新选择" }
    val value = wire.ifEmpty { SearchResult.encodePath(path) }
    require(value.startsWith('/') && !value.startsWith("//")) { "服务器原始路径无效" }
    if (value != "/") for (part in value.removePrefix("/").trimEnd('/').split('/')) {
        require(part.isNotEmpty() && part.all { it.code in 0x21..0x7e }) { "服务器路径编码无效" }
        val bytes = resourceWireBytes("/$part").drop(1).toByteArray()
        require(bytes.none { it == 0.toByte() || it == '/'.code.toByte() } && !bytes.contentEquals(byteArrayOf(46)) && !bytes.contentEquals(byteArrayOf(46, 46))) { "服务器路径包含无效段" }
    }
    return value.trimEnd('/').ifEmpty { "/" }
}

internal fun archiveWireKey(wire: String): String = java.util.Base64.getEncoder().encodeToString(archiveEntryWireBytes(wire))

private fun string(row: JSONObject, key: String) = row.get(key) as? String ?: error("服务器压缩包字段 $key 格式无效")
private fun integer(row: JSONObject, key: String, nonnegative: Boolean = true): Long {
    val value = row.get(key)
    val result = if (value is Number) value.toString().toLongOrNull() else null
    require(result != null && (!nonnegative || result >= 0)) { "服务器压缩包字段 $key 格式无效" }
    return result
}
private fun flag(row: JSONObject, key: String) = row.get(key) as? Boolean ?: error("服务器压缩包字段 $key 格式无效")
private fun verified(row: JSONObject, key: String) {
    if (row.has(key)) require(row.get(key) == true) { "历史记录的原始路径无法确认，请重新打开压缩包" }
}
private fun relativeWire(row: JSONObject, path: String): String {
    verified(row, "pathVerified")
    val wire = if (row.has("wirePath")) string(row, "wirePath") else {
        require(!path.contains('\uFFFD')) { "服务器未提供条目原始路径，请更新服务器后重试" }; SearchResult.encodePath(path)
    }
    archiveEntryWireBytes(wire)
    return wire
}

fun parseArchiveListing(row: JSONObject): ArchiveListing {
    verified(row, "pathVerified")
    val path = string(row, "archivePath")
    val wire = archiveAbsoluteWire(path, row.optString("archiveWirePath"))
    val rows = row.getJSONArray("entries")
    val entries = (0 until rows.length()).map { index ->
        val entry = rows.getJSONObject(index); val relative = string(entry, "path"); val name = string(entry, "name")
        require(relative.isNotEmpty() && !relative.startsWith('/') && name.isNotEmpty()) { "压缩包条目名称无效" }
        ArchiveEntry(relative, relativeWire(entry, relative), name, flag(entry, "isDir"), integer(entry, "size"), integer(entry, "modified", false))
    }
    require(entries.map { archiveWireKey(it.wirePath) }.distinct().size == entries.size) { "压缩包返回了重复的原始条目" }
    val blocked = row.optJSONArray("blocked") ?: JSONArray()
    return ArchiveListing(path, wire, string(row, "format"), integer(row, "sourceSize"), integer(row, "sourceModified", false), entries,
        integer(row, "listedBytes"), integer(row, "blockedCount"), (0 until blocked.length()).map { blocked.getJSONObject(it).let { entry ->
            ArchiveBlockedEntry(string(entry, "path"), string(entry, "reason")) } }, flag(row, "truncated"), row.optString("limitReason"),
        integer(row, "maxEntries"), integer(row, "maxFileBytes"), integer(row, "maxExtractBytes"))
}

fun parseArchiveReport(row: JSONObject): ArchiveReport {
    verified(row, "pathsVerified")
    val archive = string(row, "archivePath"); val destination = string(row, "destination")
    val selected = row.getJSONArray("selected"); val wires = row.optJSONArray("selectedWirePaths")
    require(wires == null || wires.length() == selected.length()) { "解压结果条目身份不完整" }
    val identities = (0 until selected.length()).map { index ->
        val path = selected.getString(index)
        val wire = wires?.getString(index) ?: run { require(!path.contains('\uFFFD')) { "历史解压条目身份无法确认" }; SearchResult.encodePath(path) }
        archiveEntryWireBytes(wire); wire
    }
    val skipped = row.optJSONArray("skipped") ?: JSONArray()
    return ArchiveReport(archive, archiveAbsoluteWire(archive, row.optString("archiveWirePath")),
        DirectoryCrumb("解压目标", destination, archiveAbsoluteWire(destination, row.optString("destinationWirePath"))), identities,
        integer(row, "extractedFiles"), integer(row, "extractedDirs"), integer(row, "extractedBytes"), integer(row, "skippedCount"),
        (0 until skipped.length()).map { skipped.getJSONObject(it).let { entry -> ArchiveBlockedEntry(string(entry, "path"), string(entry, "reason")) } },
        integer(row, "completedAt"))
}

fun archiveChildren(listing: ArchiveListing, prefix: String, query: String): List<ArchiveEntry> {
    if (query.isNotBlank()) return listing.entries.filter { it.path.contains(query, true) || it.name.contains(query, true) }
        .sortedWith(compareBy<ArchiveEntry> { !it.isDir }.thenBy { it.name.lowercase(Locale.ROOT) }.thenBy { it.wirePath })
    val base = prefix.takeIf { it.isNotEmpty() }?.plus("/").orEmpty()
    val children = linkedMapOf<String, ArchiveEntry>()
    for (entry in listing.entries) {
        if (!entry.wirePath.startsWith(base) || entry.wirePath == prefix) continue
        val remainder = entry.wirePath.removePrefix(base)
        val childWire = base + remainder.substringBefore('/')
        val depth = childWire.split('/').size
        val display = entry.path.split('/').take(depth).joinToString("/")
        val child = if (!remainder.contains('/')) entry else ArchiveEntry(display, childWire, display.substringAfterLast('/'), true, 0, 0)
        if (!children.containsKey(childWire) || !remainder.contains('/')) children[childWire] = child
    }
    return children.values.sortedWith(compareBy<ArchiveEntry> { !it.isDir }.thenBy { it.name.lowercase(Locale.ROOT) }.thenBy { it.wirePath })
}

fun archiveSelectionContains(selected: Set<String>, wire: String): Boolean = selected.any { it == "." || it == wire || wire.startsWith("$it/") }
fun archiveSelectedEntries(listing: ArchiveListing, selected: Set<String>) = listing.entries.filter { archiveSelectionContains(selected, it.wirePath) }
fun archiveExtractionBody(listing: ArchiveListing, directory: DirectoryCrumb, selected: Set<String>): JSONObject {
    require(selected.isNotEmpty() && selected.size <= ARCHIVE_MAX_SELECTED && !listing.truncated) { "请选择安全范围内的 1 到 500 个条目" }
    require(archiveSelectedEntries(listing, selected).isNotEmpty()) { "所选条目已不存在" }
    selected.forEach { archiveEntryWireBytes(it) }
    val destination = archiveAbsoluteWire(directory.path, requireNotNull(directory.wirePath) { "目标目录原始路径无法确认" })
    return JSONObject().put("archiveWirePath", listing.archiveWirePath).put("destinationWirePath", destination).put("selectedWirePaths", JSONArray(selected.toList()))
}
