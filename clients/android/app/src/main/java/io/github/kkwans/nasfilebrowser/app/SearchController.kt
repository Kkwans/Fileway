package io.github.kkwans.nasfilebrowser.app

import io.github.kkwans.nasfilebrowser.data.SearchEnding
import io.github.kkwans.nasfilebrowser.data.SearchResult
import io.github.kkwans.nasfilebrowser.data.SearchScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

data class SearchState(
    val open: Boolean = false, val query: String = "", val scope: SearchScope = SearchScope.RECURSIVE,
    val basePath: String = "/", val baseWirePath: String = "/",
    val items: List<SearchResult> = emptyList(), val running: Boolean = false,
    val category: FileCategory = FileCategory.ALL,
    val ending: SearchEnding? = null, val message: String? = null, val openingPath: String? = null,
) {
    // Keep the entry directory so changing scope never loses the browser context.
    val resultBasePath: String get() = if (scope == SearchScope.GLOBAL) "/" else basePath
    val resultBaseWirePath: String get() = if (scope == SearchScope.GLOBAL) "/" else baseWirePath
    val visibleItems: List<SearchResult> get() = items.filter { category.matches(it.name, it.directory) }
}

/** All entry points and state updates run on the owning ViewModel's main scope. */
class SearchController(private val scope: CoroutineScope, private val isCurrent: (SessionContext) -> Boolean,
    private val selected: (ResourceRef) -> Unit) {
    private val mutable = MutableStateFlow(SearchState())
    val state = mutable.asStateFlow()
    private var bound: SessionContext? = null
    private var epoch = 0L
    private var searchJob: Job? = null
    private var selectionJob: Job? = null

    fun mediaSnapshot(): List<ResourceRef> {
        val input = mutable.value
        return input.visibleItems.mapNotNull { it.resource(input.resultBasePath, input.resultBaseWirePath) }
    }
    fun category(value: FileCategory) { mutable.value = mutable.value.copy(category = value) }

    fun open(context: SessionContext, path: String, wirePath: String) {
        close()
        if (!isCurrent(context)) return
        bound = context
        mutable.value = SearchState(open = true, basePath = path, baseWirePath = wirePath)
    }

    fun query(value: String) {
        if (!mutable.value.open || value == mutable.value.query) return
        invalidate()
        mutable.value = mutable.value.copy(query = value, items = emptyList(), running = false, ending = null, message = null, openingPath = null)
    }

    fun scope(value: SearchScope) {
        if (!mutable.value.open || value == mutable.value.scope) return
        invalidate()
        mutable.value = mutable.value.copy(scope = value, items = emptyList(), running = false, ending = null, message = null, openingPath = null)
    }

    fun submit() = submit(false)
    fun refresh() = submit(true)
    private fun submit(preserveResults: Boolean) {
        val context = bound ?: return
        if (!mutable.value.open || !isCurrent(context)) return
        invalidate()
        val input = mutable.value
        if (input.query.isBlank()) {
            mutable.value = input.copy(running = false, ending = null, message = "请输入文件名", items = emptyList(), openingPath = null)
            return
        }
        val expected = epoch
        mutable.value = input.copy(items = if (preserveResults) input.items else emptyList(), running = true, ending = null, message = null, openingPath = null)
        searchJob = scope.launch {
            try {
                val results = linkedMapOf<String, SearchResult>()
                context.api.search(input.resultBasePath, input.resultBaseWirePath, input.query, input.scope).collect { update ->
                    if (!current(context, expected)) return@collect
                    update.items.forEach { results[it.relativePath] = it }
                    mutable.value = mutable.value.copy(items = if (preserveResults && !update.done) input.items else results.values.toList(), running = !update.done,
                        ending = update.ending, message = update.message)
                }
            } catch (error: Exception) {
                if (error !is CancellationException && current(context, expected)) {
                    mutable.value = mutable.value.copy(running = false, ending = SearchEnding.FAILED,
                        message = error.message ?: "搜索未能完成，已保留收到的结果，请重试。")
                }
            }
        }
    }

    fun cancel() {
        val wasRunning = mutable.value.running
        val wasOpening = mutable.value.openingPath != null
        if (!wasRunning && !wasOpening) return
        invalidate()
        mutable.value = mutable.value.copy(running = false, openingPath = null,
            ending = if (wasRunning) SearchEnding.CANCELED else mutable.value.ending,
            message = if (wasOpening) "已取消打开" else "搜索已取消，已保留收到的结果。")
    }

    fun openResult(result: SearchResult) {
        val context = bound ?: return
        val input = mutable.value
        if (!input.open || !isCurrent(context) || result !in input.items) return
        val candidate = result.resource(input.resultBasePath, input.resultBaseWirePath)
        if (candidate == null) {
            mutable.value = input.copy(message = "此结果的文件名编码无法确认，请从目录中打开。")
            return
        }
        cancel()
        invalidate()
        val expected = epoch
        mutable.value = mutable.value.copy(openingPath = result.relativePath, message = null)
        selectionJob = scope.launch {
            try {
                val data = context.api.request("GET", "/api/resources${candidate.wirePath}?metadata=1")
                if (!current(context, expected)) return@launch
                val path = data.get("path") as? String ?: error("文件信息缺少路径")
                val wire = data.get("wirePath") as? String ?: error("文件信息缺少原始路径")
                check(path == candidate.path && wireBytes(wire).contentEquals(wireBytes(candidate.wirePath))) {
                    "文件来源已变化，请重新搜索"
                }
                val directory = data.get("isDir") as? Boolean ?: error("文件信息缺少类型")
                check(directory == candidate.directory) { "文件类型已变化，请重新搜索" }
                val name = data.get("name") as? String ?: error("文件信息缺少名称")
                val rawSize = data.get("size")
                val size = if (rawSize is Number) rawSize.toString().toLongOrNull() else null
                require(size != null && size >= 0 && name.isNotEmpty()) { "文件信息格式无效" }
                mutable.value = mutable.value.copy(openingPath = null)
                selected(ResourceRef(path, wire, name, directory, data.optString("type"), size, data.optString("modified")))
            } catch (error: Exception) {
                if (error !is CancellationException && current(context, expected)) {
                    mutable.value = mutable.value.copy(openingPath = null, message = error.message ?: "无法确认文件，请重试打开。")
                }
            }
        }
    }

    fun close() {
        invalidate()
        bound = null
        mutable.value = SearchState()
    }
    private fun invalidate() { epoch++; searchJob?.cancel(); selectionJob?.cancel() }
    private fun current(context: SessionContext, expected: Long) = bound == context && isCurrent(context) && epoch == expected && mutable.value.open

    /** Compare original path bytes, without lossy Unicode decoding or treating '+' as space. */
    private fun wireBytes(path: String): ByteArray {
        require(path.startsWith('/') && !path.startsWith("//") && !path.contains('?') && !path.contains('#')) { "原始路径格式无效" }
        val bytes = ByteArrayOutputStream()
        var index = 0
        while (index < path.length) {
            if (path[index] == '%') {
                require(index + 2 < path.length) { "原始路径编码无效" }
                val high = path[index + 1].digitToIntOrNull(16)
                val low = path[index + 2].digitToIntOrNull(16)
                require(high != null && low != null) { "原始路径编码无效" }
                bytes.write(high * 16 + low)
                index += 3
            } else {
                val end = path.indexOf('%', index).let { if (it < 0) path.length else it }
                bytes.write(path.substring(index, end).toByteArray(Charsets.UTF_8))
                index = end
            }
        }
        return bytes.toByteArray()
    }
}
