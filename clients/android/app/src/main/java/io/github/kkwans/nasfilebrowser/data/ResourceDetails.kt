package io.github.kkwans.nasfilebrowser.data

import io.github.kkwans.nasfilebrowser.app.ResourceRef
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

internal data class DirectorySize(val bytes: Long, val files: Int, val skippedLinks: Int)

/** Read only, cancellable and scoped to returned opaque child identities. Never follows symlinks. */
internal suspend fun measureDirectorySize(root: ResourceRef, read: suspend (String) -> JSONObject,
    progress: (Int) -> Unit): DirectorySize {
    val pending = ArrayDeque<Pair<String, Int>>()
    pending.add(root.wirePath.ifEmpty { SearchResult.encodePath(root.path) } to 0)
    val seen = hashSetOf<String>()
    var bytes = 0L; var files = 0; var links = 0
    while (pending.isNotEmpty()) {
        currentCoroutineContext().ensureActive()
        val (wire, depth) = pending.removeLast()
        check(depth <= 64 && seen.size < 2000) { "目录过深或项目过多，请选择更小的子目录统计" }
        check(seen.add(java.util.Base64.getEncoder().encodeToString(resourceWireBytes(wire)))) { "目录返回了重复路径，统计已停止" }
        val listing = read("/api/resources$wire")
        check(!listing.optBoolean("isSymlink")) { "符号链接目录请打开原目录后统计" }
        check(resourceWireBytes(listing.optString("wirePath")).contentEquals(resourceWireBytes(wire))) { "目录来源已变化，请重新打开详情" }
        val rows = listing.getJSONArray("items")
        val prefix = resourceWireBytes(wire.trimEnd('/') + "/")
        for (index in 0 until rows.length()) {
            currentCoroutineContext().ensureActive()
            val row = rows.getJSONObject(index)
            val child = row.getString("wirePath")
            val identity = resourceWireBytes(child)
            check(identity.size > prefix.size && identity.copyOfRange(0, prefix.size).contentEquals(prefix) &&
                identity.drop(prefix.size).none { it == '/'.code.toByte() }) { "目录返回了其他来源的项目，统计已停止" }
            if (row.optBoolean("isSymlink")) { links++; continue }
            if (row.getBoolean("isDir")) {
                check(pending.size + seen.size < 2000) { "文件夹过多，请选择更小的子目录统计" }
                pending.add(child to depth + 1)
            }
            else {
                check(++files <= 100_000) { "文件过多，请选择更小的子目录统计" }
                val size = row.getLong("size"); check(size >= 0) { "文件大小未确认" }
                bytes = Math.addExact(bytes, size)
            }
        }
        progress(seen.size)
    }
    return DirectorySize(bytes, files, links)
}
