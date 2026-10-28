package io.github.kkwans.nasfilebrowser.data

import org.json.JSONArray
import org.json.JSONObject

val ACCOUNT_PREFERENCE_FIELDS = setOf("singleClick", "redirectAfterCopyMove", "dateFormat", "aceEditorTheme", "playerPreferences", "listingPreferences")
val BUILT_IN_ACCOUNT_PREFIXES = listOf(".", "@", "#", "~", "$")

data class AccountPrefixRule(val prefix: String, val visible: Boolean = true, val expanded: Boolean = true)
data class AccountPlayerPreferences(val controlsTimeoutSec: Int = 4, val playbackMode: String = "native",
    val playbackRate: Double = 1.0, val resumeMode: String = "resume", val resumeMinSec: Int = 10)
data class AccountPreferences(val singleClick: Boolean = false, val redirectAfterCopyMove: Boolean = false,
    val dateFormat: Boolean = false, val aceEditorTheme: String = "", val player: AccountPlayerPreferences = AccountPlayerPreferences(),
    val prefixes: List<AccountPrefixRule> = BUILT_IN_ACCOUNT_PREFIXES.map { AccountPrefixRule(it) }) {
    /** Preserve concurrent server changes to fields the user did not edit. */
    fun rebase(previous: AccountPreferences, latest: AccountPreferences): AccountPreferences = latest.copy(
        singleClick = if (singleClick != previous.singleClick) singleClick else latest.singleClick,
        redirectAfterCopyMove = if (redirectAfterCopyMove != previous.redirectAfterCopyMove) redirectAfterCopyMove else latest.redirectAfterCopyMove,
        dateFormat = if (dateFormat != previous.dateFormat) dateFormat else latest.dateFormat,
        aceEditorTheme = if (aceEditorTheme != previous.aceEditorTheme) aceEditorTheme else latest.aceEditorTheme,
        player = latest.player.copy(
            controlsTimeoutSec = if (player.controlsTimeoutSec != previous.player.controlsTimeoutSec) player.controlsTimeoutSec else latest.player.controlsTimeoutSec,
            playbackMode = if (player.playbackMode != previous.player.playbackMode) player.playbackMode else latest.player.playbackMode,
            playbackRate = if (player.playbackRate != previous.player.playbackRate) player.playbackRate else latest.player.playbackRate,
            resumeMode = if (player.resumeMode != previous.player.resumeMode) player.resumeMode else latest.player.resumeMode,
            resumeMinSec = if (player.resumeMinSec != previous.player.resumeMinSec) player.resumeMinSec else latest.player.resumeMinSec),
        prefixes = if (prefixes != previous.prefixes) prefixes else latest.prefixes)
    fun validate() {
        require(player.controlsTimeoutSec in 0..20 && player.playbackRate.isFinite() && player.playbackRate in .1..5.0 &&
            player.resumeMinSec in 5..600 && player.playbackMode in setOf("native", "compat", "ask") &&
            player.resumeMode in setOf("resume", "from-start", "ask")) { "请检查播放设置的范围" }
        require(prefixes.map { it.prefix }.distinct().size == prefixes.size &&
            prefixes.count { it.prefix !in BUILT_IN_ACCOUNT_PREFIXES } <= 20 &&
            BUILT_IN_ACCOUNT_PREFIXES.all { prefix -> prefixes.any { it.prefix == prefix } }) { "特殊前缀规则无效" }
        prefixes.forEach { rule ->
            require(rule.prefix.codePointCount(0, rule.prefix.length) in 1..8 && rule.prefix.codePoints().toArray().none {
                it == '/'.code || it == '\\'.code || Character.isWhitespace(it) || Character.isSpaceChar(it) || Character.isISOControl(it) || it in 0xD800..0xDFFF
            }) { "前缀须为 1–8 个可见字符，不能包含空白或路径分隔符" }
        }
    }
}

/** Keep a complete, password-free server snapshot; send only explicitly changed fields. */
class AccountProfile private constructor(val id: Long, val username: String, val lockPassword: Boolean,
    val admin: Boolean, val preferences: AccountPreferences, private val source: String) {
    fun snapshot(): JSONObject = JSONObject(source)
    fun preferencePatch(draft: AccountPreferences): JSONObject {
        draft.validate()
        val original = snapshot()
        return JSONObject().apply {
            if (draft.singleClick != preferences.singleClick) put("singleClick", draft.singleClick)
            if (draft.redirectAfterCopyMove != preferences.redirectAfterCopyMove) put("redirectAfterCopyMove", draft.redirectAfterCopyMove)
            if (draft.dateFormat != preferences.dateFormat) put("dateFormat", draft.dateFormat)
            if (draft.aceEditorTheme != preferences.aceEditorTheme) put("aceEditorTheme", draft.aceEditorTheme)
            if (draft.player != preferences.player) put("playerPreferences", JSONObject(original.optJSONObject("playerPreferences")?.toString() ?: "{}").apply {
                put("controlsTimeoutSec", draft.player.controlsTimeoutSec); put("playbackMode", draft.player.playbackMode)
                put("playbackRate", draft.player.playbackRate); put("resumeMode", draft.player.resumeMode); put("resumeMinSec", draft.player.resumeMinSec)
            })
            if (draft.prefixes != preferences.prefixes) put("listingPreferences", JSONObject(original.optJSONObject("listingPreferences")?.toString() ?: "{}").apply {
                val old = original.optJSONObject("listingPreferences")?.optJSONArray("prefixRules") ?: JSONArray()
                put("version", 1)
                put("prefixRules", JSONArray().apply { draft.prefixes.forEachIndexed { index, rule ->
                    val previous = (0 until old.length()).map { old.getJSONObject(it) }.firstOrNull { it.optString("prefix") == rule.prefix }
                    put(JSONObject(previous?.toString() ?: "{}").put("prefix", rule.prefix).put("visible", rule.visible)
                        .put("expanded", rule.expanded).put("order", index))
                } })
            })
        }
    }
    override fun toString() = "AccountProfile(id=$id)"
    companion object {
        fun from(value: JSONObject): AccountProfile {
            val id = (value.get("id") as? Number)?.toString()?.toLongOrNull()
            val name = value.get("username") as? String
            require(id != null && id >= 0 && !name.isNullOrBlank()) { "服务器账号资料无效" }
            val lock = value.get("lockPassword") as? Boolean ?: error("服务器密码锁定状态无效")
            val admin = value.getJSONObject("perm").get("admin") as? Boolean ?: error("服务器账号权限无效")
            fun flag(key: String) = value.get(key) as? Boolean ?: error("服务器账号偏好无效")
            val player = value.optJSONObject("playerPreferences") ?: JSONObject()
            val listing = value.optJSONObject("listingPreferences")
            val prefixes = if (listing == null || listing.optInt("version") == 0) BUILT_IN_ACCOUNT_PREFIXES.map {
                AccountPrefixRule(it, it != "." || !value.optBoolean("hideDotfiles"))
            } else {
                require(listing.getInt("version") == 1) { "服务器列表偏好版本暂不支持" }
                val rows = listing.getJSONArray("prefixRules")
                (0 until rows.length()).map { rows.getJSONObject(it) }.sortedBy { it.getInt("order") }.map {
                    AccountPrefixRule(it.getString("prefix"), it.get("visible") as? Boolean ?: error("前缀状态无效"),
                        it.get("expanded") as? Boolean ?: error("前缀状态无效"))
                }
            }
            val preferences = AccountPreferences(flag("singleClick"), flag("redirectAfterCopyMove"), flag("dateFormat"),
                value.optString("aceEditorTheme"), AccountPlayerPreferences(player.optInt("controlsTimeoutSec", 4),
                    player.optString("playbackMode", "native").ifEmpty { "native" }, player.optDouble("playbackRate", 1.0),
                    player.optString("resumeMode", "resume").ifEmpty { "resume" }, player.optInt("resumeMinSec", 10)), prefixes)
            preferences.validate()
            val safe = JSONObject(value.toString()).apply { remove("password") }
            return AccountProfile(id, name, lock, admin, preferences, safe.toString())
        }
    }
}

data class AccountWriteAcknowledgement(val tokenStorageFailed: Boolean = false)
