package io.github.kkwans.nasfilebrowser.data

import org.json.JSONArray
import org.json.JSONObject

data class DefaultUserSettings(val scope: String = "", val locale: String = "en", val viewMode: String = "mosaic",
    val singleClick: Boolean = false, val redirectAfterCopyMove: Boolean = false, val dateFormat: Boolean = false,
    val hideDotfiles: Boolean = false, val aceEditorTheme: String = "", val sortingBy: String = "", val sortingAscending: Boolean = false,
    val permissions: ManagedPermissions = ManagedPermissions(), val commands: List<String> = emptyList())
data class ServerBranding(val name: String, val files: String, val theme: String, val color: String,
    val disableExternal: Boolean, val disableUsedPercentage: Boolean)
data class ServerSettingsDraft(val signup: Boolean, val createUserDir: Boolean, val hideLoginButton: Boolean,
    val userHomeBasePath: String, val minimumPasswordLength: Int, val sessionMinutes: Double,
    val defaults: DefaultUserSettings, val branding: ServerBranding, val chunkBytes: Long, val retryCount: Int,
    val shell: List<String>, val hooks: Map<String, List<String>>, val rules: List<ManagedRule>) {
    fun validate() {
        require(minimumPasswordLength > 0 && sessionMinutes.isFinite() && sessionMinutes in 10.0..43200.0 && chunkBytes >= 0 && retryCount in 0..65535) { "请检查密码长度、会话期限和上传设置" }
        require(!defaults.permissions.share || defaults.permissions.download) { "已有分享授权需要保留默认下载权限" }
        require(listOf(userHomeBasePath, defaults.scope, defaults.locale, branding.name, branding.files, branding.color).none { it.contains('\u0000') } &&
            (shell + defaults.commands + hooks.values.flatten()).none { it.contains('\u0000') }) { "配置不能包含空字节" }
        require(rules.none { it.path.contains('\u0000') || it.expression.contains('\u0000') }) { "路径规则不能包含空字节" }
    }
    override fun toString() = "ServerSettingsDraft(configuration=redacted)"
}

/** Full administrative snapshot. Typed edits preserve all unrecognized JSON members. */
class ServerSettings private constructor(val authMethod: String, val draft: ServerSettingsDraft, private val source: String) {
    fun payload(value: ServerSettingsDraft): JSONObject {
        value.validate()
        return JSONObject(source).apply {
            put("signup", value.signup); put("createUserDir", value.createUserDir); put("hideLoginButton", value.hideLoginButton)
            put("userHomeBasePath", value.userHomeBasePath); put("minimumPasswordLength", value.minimumPasswordLength)
            if (value.sessionMinutes != draft.sessionMinutes) put("tokenExpirationTime", sessionDurationWire(value.sessionMinutes))
            put("defaults", JSONObject(getJSONObject("defaults").toString()).apply {
                put("scope", value.defaults.scope); put("locale", value.defaults.locale); put("viewMode", value.defaults.viewMode)
                put("singleClick", value.defaults.singleClick); put("redirectAfterCopyMove", value.defaults.redirectAfterCopyMove)
                put("dateFormat", value.defaults.dateFormat); put("hideDotfiles", value.defaults.hideDotfiles); put("aceEditorTheme", value.defaults.aceEditorTheme)
                put("perm", value.defaults.permissions.json(optJSONObject("perm") ?: JSONObject()))
                put("commands", JSONArray(value.defaults.commands))
                put("sorting", JSONObject(optJSONObject("sorting")?.toString() ?: "{}").put("by", value.defaults.sortingBy).put("asc", value.defaults.sortingAscending))
            })
            put("branding", JSONObject(getJSONObject("branding").toString()).apply {
                put("name", value.branding.name); put("files", value.branding.files); put("theme", value.branding.theme); put("color", value.branding.color)
                put("disableExternal", value.branding.disableExternal); put("disableUsedPercentage", value.branding.disableUsedPercentage)
            })
            put("tus", JSONObject(getJSONObject("tus").toString()).put("chunkSize", value.chunkBytes).put("retryCount", value.retryCount))
            put("shell", JSONArray(value.shell))
            put("commands", JSONObject(optJSONObject("commands")?.toString() ?: "{}").apply {
                value.hooks.forEach { (key, commands) -> if (commands != draft.hooks[key]) put(key, JSONArray(commands)) }
            })
            // Do not repair or reinterpret an old rule on an unrelated save.
            if (!sameManagedRuleContent(value.rules, draft.rules)) put("rules", JSONArray().apply { value.rules.forEach { put(it.json()) } })
        }
    }
    fun matches(value: ServerSettingsDraft): Boolean = draft.copy(rules = emptyList()) == value.copy(rules = emptyList()) && sameManagedRuleContent(draft.rules, value.rules)
    override fun toString() = "ServerSettings(configuration=redacted)"
    companion object {
        fun from(value: JSONObject): ServerSettings {
            fun flag(data: JSONObject, key: String) = data.get(key) as? Boolean ?: error("服务器配置格式无效")
            fun strings(rows: JSONArray?) = rows?.let { (0 until it.length()).map(it::getString) } ?: emptyList()
            val defaults = value.getJSONObject("defaults"); val brand = value.getJSONObject("branding"); val tus = value.getJSONObject("tus")
            val sorting = defaults.optJSONObject("sorting") ?: JSONObject()
            val commands = value.optJSONObject("commands") ?: JSONObject()
            val rules = value.optJSONArray("rules") ?: JSONArray()
            val chunk = (tus.get("chunkSize") as? Number)?.toString()?.toLongOrNull()
            val minimum = (value.get("minimumPasswordLength") as? Number)?.toString()?.toIntOrNull()
            val retry = (tus.get("retryCount") as? Number)?.toString()?.toIntOrNull()
            require(chunk != null && minimum != null && retry != null) { "服务器配置数值超出支持范围" }
            val draft = ServerSettingsDraft(flag(value, "signup"), flag(value, "createUserDir"), flag(value, "hideLoginButton"), value.getString("userHomeBasePath"),
                minimum, sessionDurationMinutes(value.getString("tokenExpirationTime")),
                DefaultUserSettings(defaults.getString("scope"), defaults.getString("locale"), defaults.getString("viewMode"),
                    flag(defaults, "singleClick"), flag(defaults, "redirectAfterCopyMove"), flag(defaults, "dateFormat"), flag(defaults, "hideDotfiles"), defaults.optString("aceEditorTheme"),
                    sorting.optString("by"), sorting.optBoolean("asc"), ManagedPermissions.from(defaults.getJSONObject("perm")), strings(defaults.optJSONArray("commands"))),
                ServerBranding(brand.getString("name"), brand.getString("files"), brand.getString("theme"), brand.getString("color"),
                    flag(brand, "disableExternal"), flag(brand, "disableUsedPercentage")), chunk, retry, strings(value.optJSONArray("shell")),
                commands.keys().asSequence().associateWith { key -> if (commands.isNull(key)) emptyList() else strings(commands.getJSONArray(key)) },
                (0 until rules.length()).map { ManagedRule.from(rules.getJSONObject(it)) })
            draft.validate()
            return ServerSettings(value.getString("authMethod"), draft, value.toString())
        }
    }
}

/** Compare policy, not the private source snapshot used to retain opaque members. */
private fun sameManagedRuleContent(left: List<ManagedRule>, right: List<ManagedRule>): Boolean = left.size == right.size && left.zip(right).all { (a, b) ->
    a.allow == b.allow && a.regex == b.regex && a.path == b.path && a.expression == b.expression &&
        (a.json().optJSONObject("regexp") != null) == (b.json().optJSONObject("regexp") != null)
}

/** Read Go duration output without rewriting a valid compound duration on unrelated saves. */
internal fun sessionDurationMinutes(value: String): Double {
    val raw = value.removePrefix("+")
    val units = mapOf("h" to 60.0, "m" to 1.0, "s" to 1.0 / 60, "ms" to 1.0 / 60000,
        "us" to 1.0 / 60000000, "µs" to 1.0 / 60000000, "μs" to 1.0 / 60000000, "ns" to 1.0 / 60000000000)
    val matches = Regex("(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:ns|us|µs|μs|ms|s|m|h)").findAll(raw).toList()
    require(matches.isNotEmpty() && matches.joinToString("") { it.value } == raw) { "服务器会话期限无效" }
    val total = matches.sumOf { match ->
        val unit = units.keys.first { match.value.endsWith(it) && match.value.removeSuffix(it).toDoubleOrNull() != null }
        match.value.removeSuffix(unit).toDouble() * units.getValue(unit)
    }
    require(total.isFinite() && total in 10.0..43200.0) { "会话期限须为 10 分钟到 30 天" }
    return total
}

/** Same canonical hours/minutes wire shape as Web; do not emit unsupported "d". */
internal fun sessionDurationWire(minutes: Double): String {
    require(minutes.isFinite() && minutes in 10.0..43200.0)
    val whole = minutes.toLong()
    return if (whole.toDouble() == minutes) {
        if (whole % 60 == 0L) "${whole / 60}h" else "${whole}m"
    } else "${minutes}m"
}
