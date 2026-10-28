package io.github.kkwans.nasfilebrowser.data

import io.github.kkwans.nasfilebrowser.app.ResourceRef
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.net.URLEncoder

enum class SearchScope(val wire: String, val label: String) {
    CURRENT("current", "当前目录"), RECURSIVE("recursive", "包含子目录"), GLOBAL("recursive", "全部")
}
enum class SearchEnding { COMPLETED, LIMIT, TIMEOUT, CANCELED, FAILED }
data class SearchResult(val relativePath: String, val name: String, val directory: Boolean, val size: Long,
    val modified: String, val riskLevel: String) {
    init {
        require(relativePath.isNotEmpty() && name.isNotEmpty() && !relativePath.startsWith('/') && !relativePath.contains('\u0000')) { "搜索结果路径无效" }
        require(relativePath.split('/').all { it.isNotEmpty() && it != "." && it != ".." }) { "搜索结果路径无效" }
    }
    /** Search metadata has no opaque wirePath; never guess lost filename bytes. */
    fun resource(basePath: String, baseWirePath: String): ResourceRef? {
        if (relativePath.contains('\uFFFD')) return null
        require(basePath.startsWith('/'))
        val wireBase = baseWirePath.ifEmpty { encodePath(basePath) }
        require(wireBase.startsWith('/') && !wireBase.startsWith("//") && !wireBase.contains('?') && !wireBase.contains('#'))
        return ResourceRef(basePath.trimEnd('/') + "/" + relativePath,
            wireBase.trimEnd('/') + "/" + encodePath(relativePath), name, directory, "", size, modified)
    }
    companion object {
        fun encodePath(path: String) = path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        fun parse(item: JSONObject): SearchResult {
            val path = item.get("path") as? String ?: error("搜索结果缺少路径")
            val name = item.get("name") as? String ?: error("搜索结果缺少名称")
            return SearchResult(path, name, item.get("dir") as? Boolean ?: error("搜索结果类型无效"),
                integer(item, "size"), item.optString("modified"), item.optString("riskLevel"))
        }
    }
}
data class SearchUpdate(val items: List<SearchResult>, val receivedCount: Long, val done: Boolean,
    val ending: SearchEnding? = null, val message: String? = null)

private fun integer(value: JSONObject, key: String): Long {
    val raw = value.get(key)
    require(raw is Number) { "搜索计数或大小格式无效" }
    return raw.toString().toLongOrNull() ?: error("搜索计数或大小格式无效")
}

/** JNI calls carry bounded metadata only. The immutable session is injected by NasSession. */
fun searchFlow(path: String, wirePath: String, query: String, scope: SearchScope,
    identity: suspend () -> Unit, native: suspend (JSONObject) -> Any?): Flow<SearchUpdate> = flow {
    require(query.isNotBlank()) { "请输入搜索内容" }
    var handle: String? = null
    try {
        identity()
        handle = native(JSONObject().put("op", "search_start")
            .put("path", if (scope == SearchScope.GLOBAL) "/" else path)
            .put("wirePath", if (scope == SearchScope.GLOBAL) "/" else wirePath)
            .put("query", query).put("scope", scope.wire)) as? String ?: error("无法启动搜索")
        require(handle.isNotBlank()) { "无法启动搜索" }
        while (true) {
            currentCoroutineContext().ensureActive()
            identity()
            val batch = native(JSONObject().put("op", "search_poll").put("search", handle)) as? JSONObject ?: error("无法读取搜索结果")
            // A worker may renew authentication during its HTTP stream. Check
            // again before allowing any result into this account's UI/cache.
            identity()
            val items = batch.getJSONArray("items").let { list -> (0 until list.length()).map { SearchResult.parse(list.getJSONObject(it)) } }
            val count = integer(batch, "count")
            require(count >= 0) { "搜索计数无效" }
            val done = batch.get("done") as? Boolean ?: error("搜索状态无效")
            val state = batch.get("state") as? String ?: error("搜索状态无效")
            require(state in setOf("running", "finished", "failed", "canceled")) { "搜索状态无效" }
            var ending: SearchEnding? = null
            var message: String? = null
            if (done) {
                require(state != "running") { "搜索缺少结束状态" }
                val summary = batch.optJSONObject("summary")
                ending = if (state == "failed") SearchEnding.FAILED else if (state == "canceled") SearchEnding.CANCELED
                else when (summary?.getString("reason")) {
                    "completed" -> SearchEnding.COMPLETED
                    "limit" -> SearchEnding.LIMIT
                    "timeout" -> SearchEnding.TIMEOUT
                    "canceled" -> SearchEnding.CANCELED
                    else -> error("搜索缺少有效结束摘要")
                }
                message = when (ending) {
                    SearchEnding.LIMIT -> "结果已达到服务器上限，可缩小搜索范围。"
                    SearchEnding.TIMEOUT -> "搜索超时，已保留收到的结果，可缩小范围后重试。"
                    SearchEnding.CANCELED -> "搜索已取消"
                    SearchEnding.FAILED -> when (batch.optInt("httpStatus")) {
                        401 -> "登录已过期，请重新登录"
                        403 -> "当前账号没有搜索这个目录的权限"
                        404 -> "这个目录或搜索功能已不可用"
                        else -> "搜索未能完成，已保留收到的结果，请重试。"
                    }
                    else -> null
                }
            }
            if (items.isNotEmpty() || done) emit(SearchUpdate(items, count, done, ending, message))
            if (done) break
            delay(100)
        }
    } finally {
        handle?.let { id ->
            // The final poll retires the handle; cancel remains idempotent.
            // Cleanup also runs when collection, parsing or identity checks fail.
            withContext(NonCancellable) {
                runCatching { withTimeout(2000) { native(JSONObject().put("op", "search_cancel").put("search", id)) } }
            }
        }
    }
}
