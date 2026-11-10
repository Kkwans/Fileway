package io.github.kkwans.nasfilebrowser.data

import io.github.kkwans.nasfilebrowser.app.ResourceRef
import org.json.JSONObject
import java.util.UUID

/** A file version is opaque and belongs to the authenticated server session. */
data class ScopedSubtitleEntry(val file: ResourceRef, val version: String?)
data class ScopedSubtitlePage(val wirePath: String, val entries: List<ScopedSubtitleEntry>, val nextCursor: String?)

/** Versioned, account-scoped subtitle browsing; never falls back to legacy raw reads. */
class ScopedSubtitleResources(
    private val request: suspend (String, String, JSONObject?) -> JSONObject,
    private val asset: suspend (String) -> PreviewLease,
    private val nonce: String = UUID.randomUUID().toString(),
) {
    constructor(session: NasSession) : this(session::request, session::assetLease)

    init { require(opaque.matches(nonce) && nonce.length in 16..128) }

    suspend fun supported(): Boolean {
        val capabilities = try { request("GET", "/api/client-capabilities", null) }
        catch (failure: ServiceException) { if (failure.status == 404) return false else throw failure }
        val raw = capabilities.opt("resourceSubtitlePagesVersion")
        if (raw == null || raw == JSONObject.NULL) return false
        val version = (raw as? Number)?.toString()?.toIntOrNull() ?: error("服务器字幕能力格式无效")
        require(version >= 0) { "服务器字幕能力格式无效" }
        return version == 1
    }

    suspend fun page(wirePath: String, cursor: String? = null): ScopedSubtitlePage {
        val canonical = canonicalWire(wirePath)
        cursor?.let { require(it.length == 32 && opaque.matches(it)) }
        val body = JSONObject().put("action", "page").put("wirePath", canonical).put("browserNonce", nonce)
        cursor?.let { body.put("cursor", it) }
        return parsePage(canonical, cursor, request("POST", "/api/resource-directory-pages", body))
    }

    suspend fun verify(entry: ScopedSubtitleEntry) {
        val file = requireFile(entry)
        val result = request("POST", "/api/resource-directory-pages", JSONObject()
            .put("action", "verify").put("wirePath", file.wirePath).put("version", entry.version))
        require(result.getString("version") == entry.version) { "字幕文件版本已变化，请重新选择" }
    }

    suspend fun lease(entry: ScopedSubtitleEntry): PreviewLease = asset(contentEndpoint(entry))

    suspend fun close(wirePath: String) {
        request("POST", "/api/resource-directory-pages", JSONObject().put("action", "close")
            .put("wirePath", canonicalWire(wirePath)).put("browserNonce", nonce))
    }

    companion object {
        private val opaque = Regex("[A-Za-z0-9_-]+")
        private const val MAX_PATH = 16 * 1024
        private const val MAX_ROWS = 32

        fun canonicalWire(wire: String): String {
            require(wire.length in 1..MAX_PATH && (wire == "/" || !wire.endsWith('/'))) { "字幕路径无效" }
            val canonical = recentAccessWireIdentity(wire)
            require(canonical == wire) { "字幕路径编码无效" }
            return canonical
        }

        fun contentEndpoint(entry: ScopedSubtitleEntry): String {
            val file = requireFile(entry)
            return "/api/resource-subtitle-file${file.wirePath}?version=${entry.version}"
        }

        private fun requireFile(entry: ScopedSubtitleEntry): ResourceRef {
            val file = entry.file
            require(!file.directory && isExternalSubtitle(file.name) && file.size > 0) { "字幕文件不可用" }
            canonicalWire(file.wirePath)
            require(entry.version?.let { it.length == 43 && opaque.matches(it) } == true) { "字幕版本无效" }
            return file
        }

        fun parsePage(expectedWire: String, inputCursor: String?, value: JSONObject): ScopedSubtitlePage {
            val expected = canonicalWire(expectedWire)
            val version = (value.get("version") as? Number)?.toString()?.toIntOrNull()
            val responseWire = value.get("wirePath") as? String ?: error("字幕目录响应无效")
            require(version == 1 && canonicalWire(responseWire) == expected && value.has("nextCursor")) { "字幕目录响应无效" }
            val items = value.getJSONArray("items")
            require(items.length() <= MAX_ROWS) { "字幕目录页过大" }
            val seen = mutableSetOf<String>()
            val entries = (0 until items.length()).map { index ->
                val row = items.getJSONObject(index)
                val name = row.get("name") as? String ?: error("字幕名称无效")
                val path = row.get("path") as? String ?: error("字幕路径无效")
                val wire = canonicalWire(row.get("wirePath") as? String ?: error("字幕路径无效"))
                val directory = row.get("isDir") as? Boolean ?: error("字幕目录类型无效")
                val size = (row.get("size") as? Number)?.toString()?.toLongOrNull() ?: error("字幕大小无效")
                val modified = row.get("modified") as? String ?: error("字幕修改时间无效")
                require(name.isNotEmpty() && name.length <= MAX_PATH && '/' !in name && '\u0000' !in name)
                require(path.startsWith('/') && path.length <= MAX_PATH && '\u0000' !in path && size >= 0 && modified.length <= 128)
                require(wire != "/" && wire.substringBeforeLast('/').ifEmpty { "/" } == expected && seen.add(wire)) { "字幕目录条目无效" }
                val version = if (directory) null else (row.get("version") as? String ?: error("字幕版本无效"))
                val entry = ScopedSubtitleEntry(ResourceRef(path, wire, name, directory, if (directory) "directory" else "subtitle", size, modified), version)
                if (!directory) require(isExternalSubtitle(name) && version?.let { it.length == 43 && opaque.matches(it) } == true) { "字幕目录条目无效" }
                entry
            }
            val cursor = if (value.isNull("nextCursor")) null else (value.get("nextCursor") as? String ?: error("字幕分页游标无效"))
            cursor?.let { require(it.length == 32 && opaque.matches(it) && it != inputCursor) { "字幕分页游标无效" } }
            return ScopedSubtitlePage(expected, entries, cursor)
        }
    }
}
