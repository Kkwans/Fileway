package io.github.kkwans.nasfilebrowser.app

import io.github.kkwans.nasfilebrowser.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ServerSettingsState(val scope: String = "", val settings: ServerSettings? = null, val draft: ServerSettingsDraft? = null,
    val capabilities: ServerCapabilities? = null, val authorized: Boolean = false, val loading: Boolean = false, val saving: Boolean = false,
    val unknown: Boolean = false, val confirmation: Boolean = false,
    val numberDrafts: Map<String, String> = emptyMap(), val error: String? = null, val notice: String? = null) {
    val dirty get() = settings != null && (draft?.let { !settings.matches(it) } == true || numberDrafts.any { (key, text) -> !serverSettingNumberValid(key, text) })
    override fun toString() = "ServerSettingsState(scope=$scope, saving=$saving, unknown=$unknown, configuration=redacted)"
}
/** Presentation drafts never enter the server snapshot or wire JSON. */
internal fun serverSettingNumberValid(key: String, text: String): Boolean = when (key) {
    "minimum" -> text.toIntOrNull()?.let { it > 0 } == true
    "session" -> text.toDoubleOrNull()?.let { it.isFinite() && it in 10.0..43200.0 } == true
    "chunk" -> text.toLongOrNull()?.let { it >= 0 } == true
    "retry" -> text.toIntOrNull()?.let { it in 0..65535 } == true
    else -> false
}

class ServerSettingsController(private val scope: CoroutineScope, private val isCurrent: (SessionContext) -> Boolean) {
    private val mutable = MutableStateFlow(ServerSettingsState())
    val state = mutable.asStateFlow()
    private var bound: SessionContext? = null
    private var read: Job? = null
    private var write: Job? = null
    private var revision = 0L
    private var visible = false
    fun bind(context: SessionContext?) {
        if (bound === context) return
        read?.cancel(); write?.cancel(); revision++
        bound = context; visible = false; mutable.value = ServerSettingsState(scope = context?.owner.orEmpty())
    }
    private fun current(context: SessionContext, epoch: Long) = bound === context && isCurrent(context) && revision == epoch
    fun setVisible(value: Boolean) { if (visible == value) return; visible = value; if (value) refresh() }
    fun refresh() {
        val context = bound ?: return; val before = mutable.value
        if (!isCurrent(context) || before.loading || before.saving) return
        read?.cancel(); val epoch = ++revision
        mutable.value = before.copy(loading = true, error = null, notice = null)
        read = scope.launch {
            try {
                val (settings, capabilities) = coroutineScope {
                    val data = async { ServerSettings.from(context.api.request("GET", "/api/settings")) }
                    val policy = async { context.api.clientCapabilities() }
                    data.await() to policy.await()
                }
                if (current(context, epoch)) {
                    val matches = before.draft?.let(settings::matches) == true
                    val conflict = before.dirty && before.settings?.draft != settings.draft && !matches
                    // A full snapshot write is safe only from a newly read baseline.
                    // Preserve a conflicting draft for explicit review, never merge it silently.
                    mutable.value = mutable.value.copy(settings = settings, capabilities = capabilities, authorized = true, loading = false,
                        draft = if (before.dirty && !matches) before.draft else settings.draft,
                        unknown = (before.unknown || conflict) && !matches,
                        notice = when { before.unknown && matches -> "已核对：全局设置已保存"
                            before.unknown || conflict -> "服务器设置与草稿不同；请核对差异后选择重新编辑"
                            else -> null })
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (current(context, epoch)) mutable.value = mutable.value.copy(loading = false,
                    authorized = if (error is ServiceException && error.status in setOf(401, 403)) false else before.authorized,
                    error = if (error is ServiceException && error.status == 403) "当前账号没有全局设置权限" else "全局设置读取失败，请重试")
            }
        }
    }
    fun edit(transform: (ServerSettingsDraft) -> ServerSettingsDraft) {
        val before = mutable.value
        if (before.authorized && !before.loading && !before.saving && !before.unknown) before.draft?.let {
            mutable.value = before.copy(draft = transform(it), error = null, notice = null)
        }
    }
    fun editNumber(key: String, text: String) {
        val before = mutable.value
        val draft = before.draft ?: return
        if (!before.authorized || before.loading || before.saving || before.unknown || key !in setOf("minimum", "session", "chunk", "retry")) return
        val typed = if (!serverSettingNumberValid(key, text)) draft else when (key) {
            "minimum" -> draft.copy(minimumPasswordLength = text.toInt())
            "session" -> draft.copy(sessionMinutes = text.toDouble())
            "chunk" -> draft.copy(chunkBytes = text.toLong())
            else -> draft.copy(retryCount = text.toInt())
        }
        mutable.value = before.copy(draft = typed, numberDrafts = before.numberDrafts + (key to text), error = null, notice = null)
    }
    fun resetToServer() {
        val before = mutable.value
        if (!before.loading && !before.saving && before.settings != null) mutable.value = before.copy(draft = before.settings.draft,
            unknown = false, confirmation = false, numberDrafts = emptyMap(), error = null, notice = "已采用最近读取的服务器设置")
    }
    fun requestSave() {
        val before = mutable.value; val draft = before.draft ?: return
        if (!before.authorized || before.loading || before.saving || before.unknown || !before.dirty || !before.numberDrafts.all { (key, text) -> serverSettingNumberValid(key, text) }) return
        try { draft.validate() } catch (_: IllegalArgumentException) { mutable.value = before.copy(error = "请检查数值范围、配置文本和默认权限依赖"); return }
        mutable.value = before.copy(confirmation = true, error = null)
    }
    fun cancelConfirmation() { if (!mutable.value.saving) mutable.value = mutable.value.copy(confirmation = false) }
    fun confirmSave() {
        val context = bound ?: return; val before = mutable.value
        val settings = before.settings ?: return; val draft = before.draft ?: return
        if (!isCurrent(context) || !before.authorized || !before.confirmation || before.loading || before.saving || before.unknown || !before.numberDrafts.all { (key, text) -> serverSettingNumberValid(key, text) }) return
        try { draft.validate() } catch (_: IllegalArgumentException) { mutable.value = before.copy(error = "请检查设置内容"); return }
        read?.cancel(); val epoch = ++revision
        mutable.value = before.copy(saving = true, confirmation = false, error = null, notice = null)
        write = scope.launch {
            var accepted = false
            var attempted = false
            try {
                val latest = ServerSettings.from(context.api.request("GET", "/api/settings"))
                if (!current(context, epoch)) return@launch
                if (latest.draft != settings.draft) {
                    mutable.value = mutable.value.copy(settings = latest, saving = false, unknown = true,
                        error = "服务器设置已被修改，草稿保留；请采用当前服务器设置后重新编辑")
                    return@launch
                }
                // Include newly added opaque members from the freshest snapshot.
                val payload = latest.payload(draft)
                attempted = true
                context.api.action("PUT", "/api/settings", payload); accepted = true
                if (!current(context, epoch)) return@launch
                val fresh = ServerSettings.from(context.api.request("GET", "/api/settings"))
                if (current(context, epoch)) mutable.value = mutable.value.copy(settings = fresh, draft = fresh.draft,
                    saving = false, unknown = false, numberDrafts = emptyMap(), notice = "全局设置已保存并核对")
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (current(context, epoch)) {
                    val rejected = !accepted && error is ServiceException && error.status in setOf(400, 401, 403, 404, 409)
                    mutable.value = mutable.value.copy(saving = false, unknown = attempted && !rejected,
                        authorized = before.authorized && !(error is ServiceException && error.status in setOf(401, 403)),
                        error = if (!attempted) "保存前读取失败，未提交设置；草稿保留，请重新读取"
                            else if (rejected) "服务器未接受设置，请检查会话期限、规则语法和管理员权限"
                            else "保存结果未完成核对，草稿保留；请先读取服务器设置，勿重复提交")
                }
            }
        }
    }
}
