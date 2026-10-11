package io.github.kkwans.nasfilebrowser.app

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import io.github.kkwans.nasfilebrowser.download.DownloadRecord
import io.github.kkwans.nasfilebrowser.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class DocumentPreviewState(val scope: String = "", val file: ResourceRef? = null, val kind: DocumentPreviewKind = DocumentPreviewKind.OTHER,
    val loading: Boolean = false, val error: String? = null, val text: DocumentText? = null, val query: String = "",
    val search: DocumentTextSearch = DocumentTextSearch(emptyList(), false), val matchIndex: Int = -1, val searching: Boolean = false, val searchError: String? = null,
    val page: Int = 0, val pageCount: Int = 0, val zoom: Int = 100, val rendering: Boolean = false,
    val pageImage: DocumentPageBitmap? = null, val pageError: String? = null)

internal class DocumentImageLease(val file: ResourceRef, val lease: PreviewLease, val data: ByteArray? = null)
internal class DocumentImageLimitException(message: String = "GIF 超过 10 MiB，暂未加载；原文件未改变。") : Exception(message)
internal class DocumentImageBinding(val key: String, val accountKey: String, val current: () -> Boolean,
    val load: suspend (String) -> DocumentImageLease, val release: suspend (PreviewLease) -> Unit)

class DocumentPreviewController internal constructor(private val context: Context, private val scope: CoroutineScope,
    private val isCurrent: (SessionContext) -> Boolean, private val onOpened: (SessionContext, ResourceRef) -> Unit = { _, _ -> },
    private val reader: DocumentPreviewReader = NativeDocumentReader) {
    private val mutable = MutableStateFlow(DocumentPreviewState())
    val state = mutable.asStateFlow()
    private var bound: SessionContext? = null
    private var loading: Job? = null
    private var rendering: Job? = null
    private var searching: Job? = null
    private var revision = 0L
    private var renderRevision = 0L
    private var searchRevision = 0L
    private var pdf: DocumentPdfSession? = null
    private var bitmap: DocumentPageBitmap? = null
    private var width = 1080
    private var opened = false
    private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val imageResources = DocumentImageResourcePool(scope, cleanup)
    private val imageMetadataSlots = Semaphore(2)
    private fun releaseImages() = imageResources.retire()
    /** Capture the document's actual owner/revision, never the current account
     * from a thumbnail callback which might now belong to another document. */
    internal fun imageBinding(file: ResourceRef, document: DocumentText, sourceScope: String): DocumentImageBinding? {
        val owner = bound ?: return null
        val epoch = revision
        val imageGeneration = imageResources.generation()
        val documentKey = owner.owner + "/" + epoch
        fun same(): Boolean = current(owner, epoch) && localSource == null && borrowedAsset == null &&
            mutable.value.scope == sourceScope && mutable.value.file == file && mutable.value.text === document && !mutable.value.loading
        if (!same()) return null
        return DocumentImageBinding(documentKey, owner.account.key, ::same, load = { wire ->
            // Every reference checks its current authority and version before
            // joining a shared read, including a ready entry.
            val image = imageMetadataSlots.withPermit {
                check(same()) { "文档来源已切换" }
                resourceWireBytes(wire)
                check(owner.api.permissions().download) { "当前账号没有读取图片权限" }
                check(same()) { "文档来源已切换" }
                val row = owner.api.request("GET", "/api/resources$wire?metadata=1")
                check(same()) { "文档来源已切换" }
                val actualWire = row.optString("wirePath").ifEmpty { SearchResult.encodePath(row.getString("path")) }
                check(!row.getBoolean("isDir") && resourceWireBytes(actualWire).contentEquals(resourceWireBytes(wire))) { "图片来源已变化" }
                ResourceRef(row.getString("path"), wire, row.getString("name"), false, row.optString("type"),
                    row.getLong("size"), row.optString("modified")).also {
                    check(it.size >= 0 && (it.type == "image" || FileCategory.IMAGE.matches(it.name, false))) { "此资源不是可预览图片" }
                }
            }
            val gif = image.name.substringAfterLast('.').equals("gif", ignoreCase = true)
            if (gif && image.size > DOCUMENT_TEXT_LIMIT) throw DocumentImageLimitException()
            check(!gif || image.size > 0) { "GIF 文件为空，暂未加载" }
            val shared = imageResources.acquire(imageGeneration, documentKey, image, if (gif) image.size else 0) {
                check(same()) { "文档来源已切换" }
                val lease = if (gif) owner.api.image(image.path, image.wirePath, ImageQuality.ORIGINAL)
                    else owner.api.preview(image.path, image.wirePath, contain = true)
                try {
                    currentCoroutineContext().ensureActive()
                    check(same() && lease.scope == owner.api.id) { "文档来源已切换" }
                    val address = java.net.URI(lease.url)
                    check(address.scheme == "http" && address.host == "127.0.0.1" && address.port > 0 && address.rawUserInfo == null &&
                        address.rawQuery == null && address.rawFragment == null && address.path?.startsWith("/stream/") == true) { "图片预览来源无效" }
                    val bytes = if (gif) NativeDocumentReader.text(lease, image.size) else null
                    currentCoroutineContext().ensureActive()
                    check(same()) { "文档来源已切换" }
                    DocumentImageLease(image, lease, bytes)
                } catch (error: Throwable) {
                    withContext(NonCancellable) { runCatching { lease.release() }.onFailure { error.addSuppressed(it) } }
                    throw error
                }
            }
            try {
                currentCoroutineContext().ensureActive()
                check(same()) { "文档来源已切换" }
                shared
            } catch (error: Throwable) {
                withContext(NonCancellable) { runCatching { shared.lease.release() }.onFailure { error.addSuppressed(it) } }
                throw error
            }
        }, release = { lease -> lease.release() })
    }
    private data class BorrowedAsset(val owner: SessionContext, val lease: PreviewLease)
    private var borrowedAsset: BorrowedAsset? = null
    private data class LocalSource(val id: String, val uri: String, val generation: Long)
    private var localSource: LocalSource? = null
    private val memoryClass = context.getSystemService(ActivityManager::class.java).memoryClass
    private fun current(owner: SessionContext, epoch: Long) = bound === owner && isCurrent(owner) && revision == epoch
    private fun currentSource(owner: SessionContext?, local: LocalSource?, epoch: Long) = revision == epoch &&
        if (local != null) localSource === local else owner != null && localSource == null && current(owner, epoch)

    fun bind(value: SessionContext?) {
        if (bound === value) return
        close(); bound = value
        mutable.value = DocumentPreviewState(scope = value?.owner.orEmpty())
    }
    fun close() {
        releaseImages()
        revision++; renderRevision++; searchRevision++
        loading?.cancel(); rendering?.cancel(); searching?.cancel()
        bitmap?.close(); bitmap = null
        val old = pdf; pdf = null
        if (old != null) cleanup.launch { old.close() }
        opened = false
        borrowedAsset = null
        localSource = null
        mutable.value = DocumentPreviewState(scope = bound?.owner.orEmpty())
    }
    fun open(file: ResourceRef, sourceScope: String) {
        val owner = bound ?: return
        if (!isCurrent(owner) || sourceScope != owner.api.id) return
        close()
        mutable.value = DocumentPreviewState(scope = owner.owner, file = file, kind = documentPreviewKind(file))
        retry()
    }
    /** Room identity and the original content URI are pinned independently of
     * whichever NAS account happens to be selected, including no account. */
    fun openLocal(record: DownloadRecord) {
        close()
        localSource = LocalSource(record.id, record.localUri, record.generation)
        val file = ResourceRef(record.path, record.wirePath, record.name, false, record.type, record.expectedSize, record.modified, record.id)
        mutable.value = DocumentPreviewState(scope = "local-downloads/${record.id}", file = file, kind = documentPreviewKind(file))
        retry()
    }
    /** Root owns/revokes this capability. This viewer owns only its existing
     * reader work/PDF resources and never records an asset as an ordinary path. */
    fun openAsset(file: ResourceRef, lease: PreviewLease, sourceScope: String) {
        val owner = bound ?: return
        if (!isCurrent(owner) || sourceScope != owner.api.id || lease.scope != owner.api.id) return
        close()
        borrowedAsset = BorrowedAsset(owner, lease)
        mutable.value = DocumentPreviewState(scope = owner.owner, file = file, kind = documentPreviewKind(file))
        retry()
    }
    fun retry() {
        val owner = bound
        val before = mutable.value
        val file = before.file ?: return
        val asset = borrowedAsset
        val local = localSource
        if (before.loading || local == null && (owner == null || !isCurrent(owner))) return
        releaseImages()
        loading?.cancel(); rendering?.cancel(); searching?.cancel(); renderRevision++; searchRevision++
        bitmap?.close(); bitmap = null
        val old = pdf; pdf = null
        if (old != null) cleanup.launch { old.close() }
        val epoch = ++revision
        opened = false
        mutable.value = before.copy(loading = true, error = null, text = null, pageImage = null, pageError = null, rendering = false, pageCount = 0,
            search = DocumentTextSearch(emptyList(), false), matchIndex = -1, searching = false, searchError = null)
        loading = scope.launch {
            var lease: PreviewLease? = null
            var working: DocumentWorkingFile? = null
            var prepared: DocumentPdfSession? = null
            try {
                var wire = ""
                val actual = if (local != null) {
                    val row = ClientDatabase.get(context).downloads().get(local.id) ?: error("下载记录已移除，本机文档未打开")
                    check(row.complete && row.downloaded == row.expectedSize && row.expectedSize >= 0) { "文档下载完成后即可内置查看" }
                    check(row.localUri == local.uri && row.generation == local.generation && row.localUri.isNotEmpty()) {
                        "本机下载来源已变化，请返回下载列表重新打开"
                    }
                    file.copy(path = row.path, wirePath = row.wirePath, name = row.name, type = row.type, size = row.expectedSize,
                        modified = row.modified, downloadId = row.id)
                } else if (asset == null) {
                    wire = documentWirePath(file)
                    val metadata = requireNotNull(owner).api.request("GET", "/api/resources$wire?metadata=1")
                    check(!metadata.getBoolean("isDir") && resourceWireBytes(metadata.optString("wirePath").ifEmpty { SearchResult.encodePath(metadata.getString("path")) })
                        .contentEquals(resourceWireBytes(wire))) { "文件来源已变化，请从文件列表重新选择" }
                    file.copy(path = metadata.getString("path"), wirePath = wire, name = metadata.getString("name"),
                        size = metadata.getLong("size"), modified = metadata.optString("modified"), type = metadata.optString("type"))
                } else {
                    val assetOwner = requireNotNull(owner)
                    check(asset.owner === assetOwner && asset.lease.scope == assetOwner.api.id && before.scope == assetOwner.owner) { "包内文件来源已切换" }
                    require(!file.directory && file.size >= 0) { "包内文件元数据无效" }
                    val uri = try { java.net.URI(asset.lease.url) } catch (_: java.net.URISyntaxException) { error("包内文件能力地址无效") }
                    require(uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port > 0 && uri.rawUserInfo == null &&
                        uri.rawQuery == null && uri.rawFragment == null && uri.path?.startsWith("/stream/") == true) { "包内文件来源不是有效的本机能力地址" }
                    file
                }
                val kind = documentPreviewKind(actual)
                check(currentSource(owner, local, epoch)) { "文档来源已切换" }
                mutable.value = mutable.value.copy(file = actual, kind = kind)
                if (kind == DocumentPreviewKind.OTHER) {
                    if (local != null) error("此下载类型暂不支持内置阅读，请从下载列表使用系统应用打开")
                    if (asset != null) check(requireNotNull(owner).api.permissions().download) { "当前账号没有读取文件内容的权限" }
                    mutable.value = mutable.value.copy(loading = false); return@launch
                }
                if (local == null) check(requireNotNull(owner).api.permissions().download) { "当前账号没有读取文件内容的权限" }
                val limit = if (kind == DocumentPreviewKind.TEXT) DOCUMENT_TEXT_LIMIT else DOCUMENT_PDF_LIMIT
                check(actual.size in 0..limit) {
                    if (local != null) {
                        if (kind == DocumentPreviewKind.TEXT) "文本超过 10 MiB 内置阅读上限，原文件未改变" else "PDF 超过 64 MiB 内置查看上限，原文件未改变"
                    } else if (kind == DocumentPreviewKind.TEXT) "文本超过 10 MiB 阅读上限，请下载后打开" else "PDF 超过 64 MiB 查看上限，请下载后打开"
                }
                if (local != null && kind == DocumentPreviewKind.PDF) check(actual.size > 0) { "PDF 文件为空，无法显示页面；原文件保留" }
                // ORIGINAL is the existing raw lease wrapper, with no image conversion.
                if (local == null) lease = if (asset == null) requireNotNull(owner).api.image(actual.path, wire, ImageQuality.ORIGINAL)
                    else PreviewLease(asset.lease.url, asset.lease.scope) { /* Borrowed: root retains revocation ownership. */ }
                currentCoroutineContext().ensureActive()
                if (kind == DocumentPreviewKind.TEXT) {
                    val bytes = if (local != null) reader.text(context, Uri.parse(local.uri), actual.size) else reader.text(requireNotNull(lease), actual.size)
                    val text = withContext(Dispatchers.Default) {
                        try { decodeDocumentText(bytes) }
                        catch (error: Exception) {
                            if (local == null || error is CancellationException) throw error
                            throw LocalDocumentReadException("本机文本编码或内容不受支持（支持 UTF-8、带 BOM 的 UTF-16）；原文件未改变", error)
                        }
                    }
                    if (currentSource(owner, local, epoch)) {
                        mutable.value = mutable.value.copy(loading = false, text = text)
                        search(mutable.value.query)
                        if (local == null) acknowledge(requireNotNull(owner), actual)
                    }
                } else {
                    working = if (local != null) reader.pdf(context, Uri.parse(local.uri), actual.size) else reader.pdf(context, requireNotNull(lease), actual.size)
                    withContext(Dispatchers.IO) {
                        prepared = try { DocumentPdfSession(working!!) }
                        catch (error: Exception) {
                            if (local == null || error is CancellationException) throw error
                            throw LocalDocumentReadException(if (error is SecurityException) "此 PDF 受密码保护，暂不支持内置查看；本机原件保留"
                                else "PDF 已损坏或格式不受支持，本机原件和下载记录保留", error)
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    check(prepared!!.pageCount > 0) { "PDF 没有可显示的页面" }
                    if (currentSource(owner, local, epoch)) {
                        pdf = prepared; prepared = null; working = null
                        mutable.value = mutable.value.copy(loading = false, page = 0, pageCount = pdf!!.pageCount, zoom = 100)
                        renderPage()
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (currentSource(owner, local, epoch)) mutable.value = mutable.value.copy(loading = false, error = when {
                    local != null -> error.message ?: "本机文档无法读取，请检查原文件或目录授权后重试；原件保留"
                    error is SecurityException -> "PDF 受密码保护或无法读取，请下载后使用合适的应用打开"
                    else -> error.message ?: "文档无法读取，请重试或下载后打开"
                })
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { prepared?.close() }; runCatching { working?.close() }; runCatching { lease?.release() }
                }
            }
        }
    }
    private fun acknowledge(owner: SessionContext, file: ResourceRef) {
        if (opened || borrowedAsset != null || localSource != null) return
        opened = true
        try { onOpened(owner, file) } catch (_: Exception) { /* Recording cannot make a readable document fail. */ }
    }
    fun search(query: String) {
        searching?.cancel()
        val epoch = ++searchRevision
        val document = mutable.value.text
        mutable.value = mutable.value.copy(query = query, search = DocumentTextSearch(emptyList(), false), matchIndex = -1, searching = document != null && query.isNotEmpty(), searchError = null)
        if (document == null || query.isEmpty()) return
        val owner = bound
        val local = localSource
        if (local == null && owner == null) return
        val documentEpoch = revision
        searching = scope.launch {
            delay(150)
            try {
                val matches = withContext(Dispatchers.Default) { searchDocumentText(document.text, query) }
                if (currentSource(owner, local, documentEpoch) && searchRevision == epoch) mutable.value = mutable.value.copy(search = matches,
                    matchIndex = if (matches.matches.isEmpty()) -1 else 0, searching = false)
            } catch (error: IllegalArgumentException) {
                if (currentSource(owner, local, documentEpoch) && searchRevision == epoch) mutable.value = mutable.value.copy(searching = false, searchError = error.message)
            }
        }
    }
    fun nextMatch(direction: Int) {
        val before = mutable.value
        if (before.search.matches.isEmpty()) return
        mutable.value = before.copy(matchIndex = Math.floorMod(before.matchIndex + direction, before.search.matches.size))
    }
    fun viewport(pixels: Int) {
        val value = pixels.coerceIn(320, 2048)
        if (value == width) return
        width = value
        if (pdf != null) renderPage()
    }
    fun selectPage(index: Int) {
        if (index !in 0 until mutable.value.pageCount || pdf == null || index == mutable.value.page) return
        mutable.value = mutable.value.copy(page = index)
        renderPage()
    }
    fun zoom(percent: Int) {
        val value = percent.coerceIn(50, 200)
        if (value == mutable.value.zoom || pdf == null) return
        mutable.value = mutable.value.copy(zoom = value)
        renderPage()
    }
    fun retryPage() = renderPage()
    private fun renderPage() {
        val owner = bound
        val local = localSource
        if (local == null && owner == null) return
        val document = pdf ?: return
        val documentEpoch = revision
        val epoch = ++renderRevision
        val before = mutable.value
        rendering?.cancel(); bitmap?.close(); bitmap = null
        mutable.value = before.copy(pageImage = null, rendering = true, pageError = null)
        rendering = scope.launch {
            var result: DocumentPageBitmap? = null
            try {
                withContext(Dispatchers.IO) { result = document.render(before.page, width * before.zoom / 100, memoryClass) }
                currentCoroutineContext().ensureActive()
                if (currentSource(owner, local, documentEpoch) && renderRevision == epoch && pdf === document) {
                    bitmap = result; result = null
                    mutable.value = mutable.value.copy(pageImage = bitmap, rendering = false)
                    if (local == null) mutable.value.file?.let { acknowledge(requireNotNull(owner), it) }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (currentSource(owner, local, documentEpoch) && renderRevision == epoch) mutable.value = mutable.value.copy(rendering = false,
                    pageError = if (local != null) "这一页无法显示，请重试；本机原件保留" else "这一页无法显示，请重试或下载后打开")
            } catch (_: OutOfMemoryError) {
                if (currentSource(owner, local, documentEpoch) && renderRevision == epoch) mutable.value = mutable.value.copy(rendering = false,
                    pageError = if (local != null) "本机内存不足，请缩小页面后重试；原件保留" else "本机内存不足，请缩小页面或下载后打开")
            } finally { result?.close() }
        }
    }
}
