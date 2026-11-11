package io.github.kkwans.fileway.tv

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.OpenableColumns
import io.github.kkwans.nasfilebrowser.data.isExternalSubtitle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.util.UUID

internal const val TV_LOCAL_READ = Manifest.permission.READ_EXTERNAL_STORAGE
internal val tvLegacySubtitleStorage: Boolean get() = Build.VERSION.SDK_INT == Build.VERSION_CODES.P

internal enum class TvLocalSubtitleIssue { READ_FAILED, ACCESS_DENIED, STORAGE_REMOVED, FILE_CHANGED, DIRECTORY_CHANGED, FILE_VERSION_UNAVAILABLE }
internal class TvLocalSubtitleFailure(message: String, val issue: TvLocalSubtitleIssue = TvLocalSubtitleIssue.READ_FAILED) : Exception(message)

/** Storage owns this counter under its grant lock. URI identities stay here. */
internal class TvSubtitleStorageRevision {
    private data class Scope(val legacyRead: Boolean, val persistedReads: Set<Pair<String, Long>>?)
    private var observed: Scope? = null
    private var revision = 0L

    fun observe(legacyRead: Boolean, persistedReads: Set<Pair<String, Long>>?): Long {
        val current = Scope(legacyRead, persistedReads)
        if (observed != null && observed != current) revision++
        observed = current
        return revision
    }

    fun invalidate() {
        revision++
        observed = null
    }
}

/** The UI retains opaque nodes. Paths, document IDs and grants stay in this storage adapter. */
internal class TvLocalSubtitleStorage(private val context: Context) {
    internal sealed interface Source {
        data class Path(val root: File, val file: File, val shared: Boolean) : Source
        data class Tree(val grantId: String, val documentId: String) : Source
        class Single(val uri: Uri) : Source
    }

    class Node internal constructor(
        override val label: String,
        override val directory: Boolean,
        internal val source: Source,
        val detail: String? = null,
        val grantId: String? = null,
        internal val size: Long? = null,
        internal val modified: Long? = null,
        val id: String = UUID.randomUUID().toString(),
    ) : TvRemoteSubtitleBrowser.LocalNode {
        override val key: Any get() = RemoteKey(source, label, directory, size, modified)
    }

    private data class RemoteKey(val source: Source, val label: String, val directory: Boolean, val size: Long?, val modified: Long?)

    data class Page(val rows: List<Node>, val next: Int? = null, val afterRow: List<Int> = emptyList(),
        val anchors: Map<Int, Any> = emptyMap())

    private class RemoteScan(val previous: Any?)
    private data class PathAnchor(val version: Long, val name: String?)
    private data class TreeAnchor(val document: String?)

    private class Grant(val id: String, val account: String, val tree: Uri, val label: String, val persistent: Boolean)

    private val resolver = context.contentResolver
    private val prefs by lazy { context.getSharedPreferences("tv-local-subtitle-trees", Context.MODE_PRIVATE) }
    private val temporary = mutableMapOf<String, Grant>()
    private val readFlag = Intent.FLAG_GRANT_READ_URI_PERMISSION
    private val remoteGrantRevision = TvSubtitleStorageRevision()

    val hasLegacyRead: Boolean get() = tvLegacySubtitleStorage &&
        context.checkSelfPermission(TV_LOCAL_READ) == PackageManager.PERMISSION_GRANTED

    @Synchronized fun remoteRevision(): Long {
        val persisted = try {
            resolver.persistedUriPermissions.asSequence().filter { it.isReadPermission }
                .map { it.uri.toString() to it.persistedTime }.toSet()
        } catch (_: RuntimeException) { null }
        return remoteGrantRevision.observe(hasLegacyRead, persisted)
    }

    @Synchronized private fun grants(): List<Grant> = prefs.all.mapNotNull { (id, raw) ->
        val data = (raw as? String)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return@mapNotNull null
        val uri = Uri.parse(data.optString("tree"))
        if (uri.scheme != "content" || !DocumentsContract.isTreeUri(uri)) return@mapNotNull null
        Grant(id, data.optString("account"), uri, data.optString("name"), persistent = true)
    } + temporary.values

    private fun persistedRead(uri: Uri) = resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }

    suspend fun roots(account: String): Page = storageWork("无法读取电视存储，请重试") { _ ->
        val rows = mutableListOf<Node>()
        grants().filter { it.account == account }.forEach { grant ->
            val rootId = DocumentsContract.getTreeDocumentId(grant.tree)
            val detail = when {
                !grant.persistent -> "系统文件来源 · 本次可用"
                !persistedRead(grant.tree) -> "系统文件来源 · 授权已失效"
                else -> "系统文件来源 · 已授权"
            }
            rows += Node(grant.label, true, Source.Tree(grant.id, rootId), detail, grant.id, id = grant.id)
        }
        if (hasLegacyRead) {
            @Suppress("DEPRECATION")
            val shared = Environment.getExternalStorageDirectory().canonicalFile
            if (mounted(shared)) rows += Node("电视共享存储", true, Source.Path(shared, shared, shared = true), "Android 9 · 只读", id = "shared")
        }
        context.getExternalFilesDirs(null).filterNotNull().forEachIndexed { index, directory ->
            val root = directory.canonicalFile
            if (mounted(root)) rows += Node(
                if (index == 0) "应用专属目录" else "应用专属目录 ${index + 1}", true,
                Source.Path(root, root, shared = false), "栖卷 TV", id = "app/$index",
            )
        }
        Page(rows)
    }

    suspend fun page(account: String, directory: Node, offset: Int): Page = scanPage(account, directory, offset, null)

    suspend fun remotePage(account: String, directory: Node, offset: Int, previous: Any?): Page =
        scanPage(account, directory, offset, RemoteScan(previous))

    private suspend fun scanPage(account: String, directory: Node, offset: Int, remote: RemoteScan?): Page = storageWork("无法读取目录，存储可能已移除") { signal ->
        require(directory.directory && offset >= 0 && (remote == null || offset < TV_LOCAL_SUBTITLE_SCAN_LIMIT))
        when (val source = directory.source) {
            is Source.Path -> pathPage(source, offset, signal, remote)
            is Source.Tree -> treePage(requireTree(account, source.grantId), source.documentId, offset, signal, remote)
            is Source.Single -> error("Not a subtitle directory")
        }
    }

    suspend fun remoteRoots(account: String): List<Node> {
        val candidates = roots(account).rows
        return storageWork("无法确认电视本机授权目录") { _ ->
            candidates.filter { node ->
                displayName(node.label) != null && when (val source = node.source) {
                    is Source.Path -> mounted(source.root) && (!source.shared || hasLegacyRead)
                    is Source.Tree -> try { requireTree(account, source.grantId); true }
                        catch (_: TvLocalSubtitleFailure) { false }
                    is Source.Single -> false
                }
            }
        }
    }

    private fun pathPage(source: Source.Path, offset: Int, signal: CancellationSignal, remote: RemoteScan?): Page {
        val directory = checkedPath(source)
        if (!directory.isDirectory) throw TvLocalSubtitleFailure("目录已移动或存储已移除", TvLocalSubtitleIssue.STORAGE_REMOVED)
        val rows = mutableListOf<Node>()
        val afterRow = mutableListOf<Int>()
        val anchors = mutableMapOf<Int, Any>()
        val version = if (remote != null) directory.lastModified() else 0L
        Files.newDirectoryStream(directory.toPath()).use { stream ->
            val children = stream.iterator()
            var index = 0
            var previousName: String? = null
            while (index < offset && children.hasNext()) {
                signal.throwIfCanceled()
                val skipped = children.next()
                index++
                if (remote != null && index == offset) previousName = skipped.fileName.toString()
            }
            if (remote != null && offset > 0 && (index != offset || remote.previous != PathAnchor(version, previousName))) directoryChanged()
            val end = scanEnd(offset, remote)
            while (index < end && rows.size < PAGE_ROWS && children.hasNext()) {
                signal.throwIfCanceled()
                val file = children.next().toFile()
                index++
                previousName = file.name
                if (file.name.startsWith('.') || source.shared && directory == source.root && file.name == "Android") continue
                if (Files.isSymbolicLink(file.toPath())) continue
                val name = displayName(file.name) ?: continue
                val isDirectory = file.isDirectory
                if (isDirectory || isExternalSubtitle(name) && file.isFile) {
                    rows += Node(name, isDirectory, Source.Path(source.root, file, source.shared),
                        size = file.length().takeUnless { isDirectory }, modified = file.lastModified().takeIf { it > 0 })
                    afterRow += index
                    if (remote != null) anchors[index] = PathAnchor(version, previousName)
                }
            }
            val next = index.takeIf { children.hasNext() }
            if (remote != null) {
                if (directory.lastModified() != version) directoryChanged()
                if (next != null) anchors[next] = PathAnchor(version, previousName)
            }
            return Page(rows, next, afterRow, anchors)
        }
    }

    private fun treePage(grant: Grant, documentId: String, offset: Int, signal: CancellationSignal, remote: RemoteScan?): Page {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(grant.tree, documentId)
        val rows = mutableListOf<Node>()
        val afterRow = mutableListOf<Int>()
        val anchors = mutableMapOf<Int, Any>()
        val cursor = resolver.query(children, DOCUMENT_COLUMNS, null, null, null, signal)
            ?: throw TvLocalSubtitleFailure("系统文件来源已移除或不可访问", TvLocalSubtitleIssue.STORAGE_REMOVED)
        cursor.use {
            val idColumn = it.getColumnIndexOrThrow(Document.COLUMN_DOCUMENT_ID)
            val nameColumn = it.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME)
            val mimeColumn = it.getColumnIndexOrThrow(Document.COLUMN_MIME_TYPE)
            val flagsColumn = it.getColumnIndex(Document.COLUMN_FLAGS)
            val sizeColumn = it.getColumnIndex(Document.COLUMN_SIZE)
            val modifiedColumn = it.getColumnIndex(Document.COLUMN_LAST_MODIFIED)
            if (offset > 0) {
                if (!it.moveToPosition(offset - 1)) {
                    if (remote != null) directoryChanged()
                    return Page(emptyList())
                }
                if (remote != null && remote.previous != treeAnchor(it.getString(idColumn))) directoryChanged()
            }
            var index = offset
            var previous: String? = null
            val end = scanEnd(offset, remote)
            while (index < end && rows.size < PAGE_ROWS && it.moveToNext()) {
                signal.throwIfCanceled()
                index++
                previous = it.getString(idColumn)
                val id = previous?.takeIf { value -> value.isNotBlank() && value.length <= MAX_DOCUMENT_ID } ?: continue
                val name = displayName(it.getString(nameColumn)) ?: continue
                val isDirectory = it.getString(mimeColumn) == Document.MIME_TYPE_DIR
                val virtual = flagsColumn >= 0 && it.getInt(flagsColumn) and Document.FLAG_VIRTUAL_DOCUMENT != 0
                if (isDirectory || !virtual && isExternalSubtitle(name)) {
                    rows += Node(name, isDirectory, Source.Tree(grant.id, id), grantId = grant.id,
                        size = it.knownLong(sizeColumn), modified = it.knownLong(modifiedColumn))
                    afterRow += index
                    if (remote != null) anchors[index] = treeAnchor(id)
                }
            }
            val next = index.takeIf { it > offset && cursor.moveToNext() }
            if (remote != null && next != null) anchors[next] = treeAnchor(previous)
            return Page(rows, next, afterRow, anchors)
        }
    }

    private fun scanEnd(offset: Int, remote: RemoteScan?): Int = if (remote == null) {
        (offset.toLong() + SCAN_WINDOW).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    } else (offset + TV_LOCAL_SUBTITLE_SCAN_WINDOW).coerceAtMost(TV_LOCAL_SUBTITLE_SCAN_LIMIT)

    private fun treeAnchor(id: String?) = TreeAnchor(id?.takeIf { it.length <= MAX_DOCUMENT_ID })

    private fun directoryChanged(): Nothing =
        throw TvLocalSubtitleFailure("电视目录已变化，请重新读取", TvLocalSubtitleIssue.DIRECTORY_CHANGED)

    suspend fun inspectTree(tree: Uri): String = storageWork("无法读取所选文件夹，请重新授权") { signal ->
        if (tree.scheme != "content" || !DocumentsContract.isTreeUri(tree)) throw TvLocalSubtitleFailure("系统未返回可读取的文件夹")
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        resolver.query(root, arrayOf(Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE), null, null, null, signal)?.use {
            if (it.moveToFirst() && it.getString(1) == Document.MIME_TYPE_DIR) return@storageWork displayName(it.getString(0)) ?: "已授权文件夹"
        }
        throw TvLocalSubtitleFailure("无法读取所选文件夹，请重新授权")
    }

    /** Call after validating the originating TV session on the UI thread. */
    @Synchronized fun registerTree(account: String, uri: Uri, label: String, flags: Int): Boolean {
        val existing = grants().firstOrNull { it.account == account && it.tree == uri }
        var persistent = persistedRead(uri)
        if (!persistent && flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0) {
            persistent = try { resolver.takePersistableUriPermission(uri, readFlag); true }
            catch (_: SecurityException) { false }
        }
        val id = existing?.id ?: UUID.randomUUID().toString()
        if (persistent) {
            temporary.remove(id)
            prefs.edit().putString(id, JSONObject().put("account", account).put("tree", uri.toString()).put("name", label).toString()).apply()
        } else {
            prefs.edit().remove(id).apply()
            temporary[id] = Grant(id, account, uri, label, persistent = false)
        }
        remoteGrantRevision.invalidate()
        return persistent
    }

    @Synchronized fun revoke(account: String, grantId: String): Boolean {
        val grant = grants().singleOrNull { it.account == account && it.id == grantId } ?: return false
        temporary.remove(grantId)
        prefs.edit().remove(grantId).apply()
        remoteGrantRevision.invalidate()
        if (grant.persistent && grants().none { it.tree == grant.tree }) {
            try { resolver.releasePersistableUriPermission(grant.tree, readFlag) }
            catch (_: SecurityException) { /* The system may already have revoked it. */ }
        }
        return true
    }

    suspend fun single(uri: Uri): Node = storageWork("无法读取所选字幕，请重试") { signal ->
        if (uri.scheme != "content") throw TvLocalSubtitleFailure("系统未返回可读取的字幕文件")
        val (name, size) = querySingle(uri, signal)
        if (!isExternalSubtitle(name)) throw TvLocalSubtitleFailure("字幕格式暂不支持，请选择其他文件")
        Node(name, false, Source.Single(uri), size = size)
    }

    /** Recheck access and the selected version before handing a local URI to the native importer. */
    suspend fun readableUri(account: String, node: Node): Uri = storageWork("字幕已移动或无法读取，请重新选择") { signal ->
        if (node.directory || !isExternalSubtitle(node.label)) throw TvLocalSubtitleFailure("请选择支持的字幕文件")
        when (val source = node.source) {
            is Source.Path -> {
                val file = checkedPath(source)
                if (!file.isFile || !file.canRead()) throw TvLocalSubtitleFailure("字幕已移动或无法读取，请重新选择", TvLocalSubtitleIssue.FILE_CHANGED)
                requireVersion(node, file.name, file.length(), file.lastModified().takeIf { it > 0 })
                Uri.fromFile(file)
            }
            is Source.Tree -> {
                val grant = requireTree(account, source.grantId)
                val uri = DocumentsContract.buildDocumentUriUsingTree(grant.tree, source.documentId)
                val cursor = resolver.query(uri, DOCUMENT_COLUMNS, null, null, null, signal)
                    ?: throw TvLocalSubtitleFailure("系统文件来源已移除或不可访问", TvLocalSubtitleIssue.STORAGE_REMOVED)
                cursor.use {
                    if (!it.moveToFirst() || it.getString(2) == Document.MIME_TYPE_DIR ||
                        it.getInt(3) and Document.FLAG_VIRTUAL_DOCUMENT != 0)
                        throw TvLocalSubtitleFailure("系统文件来源中的字幕已不可用", TvLocalSubtitleIssue.FILE_CHANGED)
                    requireVersion(node, displayName(it.getString(1)), it.knownLong(4), it.knownLong(5))
                }
                uri
            }
            is Source.Single -> {
                val (name, size) = querySingle(source.uri, signal)
                requireVersion(node, name, size, null)
                source.uri
            }
        }
    }

    suspend fun remoteReadableUri(account: String, node: Node): Uri {
        if (node.source is Source.Single) throw TvLocalSubtitleFailure("此文件只在电视本机选择器中可用", TvLocalSubtitleIssue.ACCESS_DENIED)
        if (node.size == null || node.modified == null)
            throw TvLocalSubtitleFailure("系统未提供字幕文件版本，请选择其他文件", TvLocalSubtitleIssue.FILE_VERSION_UNAVAILABLE)
        return readableUri(account, node)
    }

    private fun checkedPath(source: Source.Path): File {
        if (source.shared && !hasLegacyRead) throw TvLocalSubtitleFailure("电视共享存储读取权限已失效", TvLocalSubtitleIssue.ACCESS_DENIED)
        if (!mounted(source.root)) throw TvLocalSubtitleFailure("电视存储已移除或未挂载", TvLocalSubtitleIssue.STORAGE_REMOVED)
        val file = source.file.canonicalFile
        if (file != source.file || !file.toPath().startsWith(source.root.toPath()))
            throw TvLocalSubtitleFailure("所选位置已变化，请重新浏览", TvLocalSubtitleIssue.FILE_CHANGED)
        return file
    }

    private fun mounted(root: File): Boolean = Environment.getExternalStorageState(root) in
        setOf(Environment.MEDIA_MOUNTED, Environment.MEDIA_MOUNTED_READ_ONLY)

    private fun requireTree(account: String, id: String): Grant {
        val grant = grants().singleOrNull { it.account == account && it.id == id }
            ?: throw TvLocalSubtitleFailure("文件夹授权已撤销", TvLocalSubtitleIssue.ACCESS_DENIED)
        if (grant.persistent && !persistedRead(grant.tree)) throw TvLocalSubtitleFailure("文件夹读取授权已失效，请重新授权", TvLocalSubtitleIssue.ACCESS_DENIED)
        if (!grant.persistent && context.checkUriPermission(grant.tree, android.os.Process.myPid(), android.os.Process.myUid(), readFlag) != PackageManager.PERMISSION_GRANTED)
            throw TvLocalSubtitleFailure("文件夹临时读取授权已失效，请重新授权", TvLocalSubtitleIssue.ACCESS_DENIED)
        return grant
    }

    private fun querySingle(uri: Uri, signal: CancellationSignal): Pair<String, Long?> {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null, signal)?.use {
            if (it.moveToFirst()) {
                val name = displayName(it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)))
                if (name != null) return name to it.knownLong(it.getColumnIndex(OpenableColumns.SIZE))
            }
        }
        throw TvLocalSubtitleFailure("系统未提供字幕文件名，请选择其他文件")
    }

    private fun requireVersion(node: Node, name: String?, size: Long?, modified: Long?) {
        if (node.label != name || node.size != null && node.size != size || node.modified != null && node.modified != modified)
            throw TvLocalSubtitleFailure("字幕文件已变化，请重新选择", TvLocalSubtitleIssue.FILE_CHANGED)
    }

    private suspend fun <T> storageWork(message: String, block: (CancellationSignal) -> T): T = coroutineScope {
        val signal = CancellationSignal()
        val cancellation = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { signal.cancel() }
        }
        try {
            withContext(Dispatchers.IO) { block(signal) }.also { currentCoroutineContext().ensureActive() }
        } catch (error: CancellationException) { throw error }
        catch (error: TvLocalSubtitleFailure) { throw error }
        catch (_: SecurityException) { throw TvLocalSubtitleFailure("电视未获读取权限，请重新授权", TvLocalSubtitleIssue.ACCESS_DENIED) }
        catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            throw TvLocalSubtitleFailure(message)
        } finally { cancellation.cancel() }
    }

    private fun android.database.Cursor.knownLong(column: Int): Long? =
        if (column < 0 || isNull(column)) null else getLong(column).takeIf { it >= 0 }

    private fun displayName(raw: String?): String? = raw?.takeIf {
        it.isNotBlank() && it.length <= 512 && it.none { char -> char == '/' || char == '\\' || char.isISOControl() }
    }

    private companion object {
        const val PAGE_ROWS = 32
        const val SCAN_WINDOW = 256
        const val MAX_DOCUMENT_ID = 4096
        val DOCUMENT_COLUMNS = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED)
    }
}
