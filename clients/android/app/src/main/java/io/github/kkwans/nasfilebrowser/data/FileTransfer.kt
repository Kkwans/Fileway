package io.github.kkwans.nasfilebrowser.data

import io.github.kkwans.nasfilebrowser.app.ResourceRef
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

enum class FileTransferAction(val label: String, val wire: String) { COPY("复制", "copy"), MOVE("移动", "move") }
enum class FileConflictChoice(val label: String) { SKIP("跳过"), KEEP_BOTH("保留两份"), REPLACE("替换") }
data class FileTransferEntry(val file: ResourceRef, val targetPath: String, val targetWire: String) {
    val sourceWire = taskResourceTarget(file.path, file.wirePath, allowOpaque = true).wirePath
    val legacyCompatible = taskResourceTarget(file.path, file.wirePath, allowOpaque = true).legacyCompatible &&
        taskResourceTarget(targetPath, targetWire, allowOpaque = true).legacyCompatible
}
data class TaskResourceTarget(val path: String, val wirePath: String, val legacyCompatible: Boolean)

/** Legacy servers persist decoded task paths in JSON and lose opaque bytes.
 * Default callers keep that protection; only an explicitly negotiated wire
 * operation may use the raw identity returned by the opt-in mode.
 */
fun taskResourceTarget(path: String, wirePath: String, allowOpaque: Boolean = false): TaskResourceTarget {
    require(path.startsWith('/') && collectionPath(path) == path && !path.contains('\u0000')) { "路径已变化，请刷新后重新选择" }
    require(wirePath.isNotEmpty() || !path.contains('\uFFFD')) { "缺少原始路径，不能安全操作此项目" }
    val wire = recentAccessWireIdentity(wirePath.ifEmpty { SearchResult.encodePath(path) })
    val decoded = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(resourceWireBytes(wire))).toString()
    } catch (_: java.nio.charset.CharacterCodingException) { null }
    require(decoded == null || decoded == path) { "路径已变化，请刷新后重新选择" }
    check(allowOpaque || decoded != null) { "服务器的复制／移动任务暂不支持这个旧编码路径，请升级服务器；未提交操作" }
    return TaskResourceTarget(path, wire, decoded == path)
}
fun taskResourcePath(path: String, wirePath: String): String = taskResourceTarget(path, wirePath).path

/** Byte-segment ancestry; display siblings never imply containment. */
fun resourceWireContains(parent: String, child: String): Boolean {
    val ancestor = recentAccessWireIdentity(parent); val target = recentAccessWireIdentity(child)
    return ancestor == target || ancestor == "/" || target.startsWith("$ancestor/")
}

/** A missing wire acknowledgement is only safe for a proven literal UTF-8 path. */
fun taskResourceAcknowledged(path: String, wire: String, responsePath: String, responseWire: String,
    pathVerified: Boolean = true): Boolean {
    if (!pathVerified) return false
    val expected = taskResourceTarget(path, wire, allowOpaque = true)
    if (responseWire.isEmpty()) return expected.legacyCompatible && responsePath == path
    val actual = taskResourceTarget(responsePath, responseWire, allowOpaque = true)
    return actual.wirePath == expected.wirePath
}


fun fileTransferEntries(files: List<ResourceRef>, directory: DirectoryCrumb, action: FileTransferAction,
    allowOpaque: Boolean = false): List<FileTransferEntry> {
    val target = taskResourceTarget(directory.path, requireNotNull(directory.wirePath) { "目标目录无法安全访问" }, allowOpaque)
    val snapshot = files.distinctBy { taskResourceTarget(it.path, it.wirePath, allowOpaque).wirePath }
    require(snapshot.isNotEmpty() && snapshot.size <= 1000) { "请选择 1 到 1000 项" }
    val entries = snapshot.map { file ->
        val source = taskResourceTarget(file.path, file.wirePath, allowOpaque)
        require(source.wirePath != "/" && file.downloadId.isEmpty()) { "只能操作服务器上的文件或文件夹，不能选择根目录" }
        require(!file.directory || !resourceWireContains(source.wirePath, target.wirePath)) { "不能把文件夹复制或移动到自身或子目录" }
        val originalName = source.path.substringAfterLast('/')
        val sameDirectory = source.wirePath.substringBeforeLast('/').ifEmpty { "/" } == target.wirePath
        require(!sameDirectory || action != FileTransferAction.MOVE) { "项目已在目标目录，请选择其他目录" }
        val dot = if (file.directory) -1 else originalName.lastIndexOf('.').takeIf { it > 0 } ?: -1
        val name = if (!sameDirectory) originalName else if (dot < 0) "$originalName（副本）"
            else originalName.substring(0, dot) + "（副本）" + originalName.substring(dot)
        val path = target.path.trimEnd('/') + "/" + name
        val basenameWire = if (sameDirectory) SearchResult.encodePath(name) else source.wirePath.substringAfterLast('/')
        FileTransferEntry(file, path, target.wirePath.trimEnd('/') + "/" + basenameWire)
    }
    require(entries.map { it.targetWire }.distinct().size == entries.size) { "不同来源有同名项目，请分批处理" }
    return entries
}
