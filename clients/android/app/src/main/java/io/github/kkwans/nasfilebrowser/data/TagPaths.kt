package io.github.kkwans.nasfilebrowser.data

import io.github.kkwans.nasfilebrowser.app.ResourceRef
import org.json.JSONObject

data class TagPathRef(val path: String, val wirePath: String = "", val openable: Boolean = false) {
    // Immutable source values make this safe to reuse; copy() gets its own cache.
    // Keep validation at first access, including for directly constructed refs.
    val identity: String? by lazy { if (openable) favoriteWireIdentity(wirePath) else null }
}

internal fun tagPathRef(path: String, wire: String? = null, verified: Boolean? = null): TagPathRef {
    val identity = favoritePathIdentity(path, wire, verified)
    check(verified != true || identity.openable) { "标签原始路径无效，请刷新后重试" }
    return TagPathRef(collectionPath(path), identity.wirePath, identity.openable)
}

internal fun tagReferences(row: JSONObject): List<TagPathRef> {
    val refs = if (row.has("pathRefs")) row.getJSONArray("pathRefs").let { values ->
        (0 until values.length()).map { values.getJSONObject(it).let { ref ->
            tagPathRef(ref.getString("path"), if (ref.has("wirePath")) ref.get("wirePath") as? String ?: error("标签原始路径格式无效") else null,
                ref.get("pathVerified") as? Boolean ?: error("标签路径状态无效"))
        } }
    } else row.optJSONArray("paths")?.let { values -> (0 until values.length()).map { tagPathRef(values.getString(it)) } }.orEmpty()
    val seen = hashSetOf<String>()
    return refs.filter { it.identity?.let(seen::add) ?: true }
}

internal fun tagAssociationBody(file: ResourceRef): JSONObject {
    val target = recentAccessRecordTarget(file)
    return JSONObject().put("wirePath", target.wirePath).apply { if (target.legacyCompatible) put("path", target.path) }
}

internal fun taggedResourceMatches(file: ResourceRef, refs: List<TagPathRef>, global: Boolean = false): Boolean {
    val target = runCatching { recentAccessWireIdentity(recentAccessRecordTarget(file).wirePath) }.getOrNull() ?: return false
    return taggedPathMatches(target, refs.mapNotNull { it.identity }, global)
}


fun taggedPathMatches(path: String, marked: Collection<String>, global: Boolean): Boolean {
    val target = collectionPath(path)
    return marked.any {
        val value = collectionPath(it)
        value == target || (global && (value == "/" || target == "/" || value.startsWith("$target/") || target.startsWith("$value/")))
    }
}

val TAG_COLORS = listOf("#E5484D", "#D95876", "#F06A5B", "#F28C28", "#DDAA1D", "#D6BE21", "#86B83E", "#35A867",
    "#2AA889", "#28AFC0", "#3A9BD9", "#3F72D8", "#5B62D9", "#7656C9", "#9B4DB5", "#C34F90", "#758195")
