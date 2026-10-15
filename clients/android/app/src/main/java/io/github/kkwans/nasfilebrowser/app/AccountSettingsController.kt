package io.github.kkwans.nasfilebrowser.app

import io.github.kkwans.nasfilebrowser.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** Secrets are intentionally absent from saved state, persistence and debug strings. */
data class AccountPasswordDraft(val current: String = "", val replacement: String = "", val confirmation: String = "") {
    /** The UI and submit path share the server's UTF-8 byte contract. */
    fun validationError(capabilities: ServerCapabilities): String? = when {
        capabilities.currentPasswordRequired && current.isEmpty() -> "请填写当前密码"
        replacement.isEmpty() -> "请填写新密码"
        replacement.toByteArray(Charsets.UTF_8).size !in capabilities.minimumPasswordLength..72 ->
            "新密码须为 ${capabilities.minimumPasswordLength}–72 字节（中文通常占多个字节）"
        confirmation.isEmpty() -> "请再次填写新密码"
        replacement != confirmation -> "两次填写的新密码不一致"
        else -> null
    }
    override fun toString() = "AccountPasswordDraft(redacted)"
}
data class AccountSettingsState(val scope: String = "", val profile: AccountProfile? = null,
    val capabilities: ServerCapabilities? = null, val draft: AccountPreferences? = null,
    val password: AccountPasswordDraft = AccountPasswordDraft(), val loading: Boolean = false, val saving: Boolean = false,
    val preferencesUnknown: Boolean = false, val passwordUnknown: Boolean = false,
    val error: String? = null, val notice: String? = null) {
    val dirty get() = profile != null && draft != profile.preferences
    val canChangePassword get() = capabilities?.passwordChangesAvailable == true && profile?.let { !it.lockPassword || it.admin } == true
}

class AccountSettingsController(private val scope: CoroutineScope, private val isCurrent: (SessionContext) -> Boolean,
    private val onPasswordChanged: suspend (SessionContext, String) -> Unit = { _, _ -> },
    private val onProfileChanged: (SessionContext, AccountProfile) -> Unit = { _, _ -> }) {
    private val mutable = MutableStateFlow(AccountSettingsState())
    val state = mutable.asStateFlow()
    private var bound: SessionContext? = null
    private var read: Job? = null
    private var write: Job? = null
    private var revision = 0L
    private var visible = false
    fun bind(context: SessionContext?) {
        if (bound === context) return
        read?.cancel(); write?.cancel(); revision++
        bound = context; visible = false
        mutable.value = AccountSettingsState(scope = context?.owner.orEmpty())
    }
    private fun current(context: SessionContext, epoch: Long) = bound === context && isCurrent(context) && revision == epoch
    fun setVisible(value: Boolean) {
        if (visible == value) return
        visible = value
        if (value) refresh()
    }
    fun refresh() {
        val context = bound ?: return
        val before = mutable.value
        if (!isCurrent(context) || before.saving || before.loading) return
        read?.cancel(); val epoch = ++revision
        mutable.value = before.copy(loading = true, error = before.error.takeIf { before.passwordUnknown }, notice = null)
        read = scope.launch {
            try {
                val (profile, capabilities) = coroutineScope {
                    val account = async { context.api.accountProfile() }
                    val policy = async { context.api.clientCapabilities() }
                    account.await() to policy.await()
                }
                if (!current(context, epoch)) return@launch
                val desired = before.draft?.let { draft -> before.profile?.let { draft.rebase(it.preferences, profile.preferences) } ?: draft }
                val matches = desired != null && desired == profile.preferences
                mutable.value = mutable.value.copy(profile = profile, capabilities = capabilities,
                    draft = if (before.dirty && !matches) desired else profile.preferences, loading = false,
                    preferencesUnknown = false,
                    notice = if (before.preferencesUnknown) {
                        if (matches) "已核对：账户设置已保存" else "已读取服务器当前设置，草稿保留；确认差异后可重新保存"
                    } else null)
                onProfileChanged(context, profile)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (current(context, epoch)) mutable.value = mutable.value.copy(loading = false,
                    error = if (error is ServiceException && error.status == 404) "服务器尚未提供账户能力信息，请更新服务器后重试" else "账户资料读取失败，请重试")
            }
        }
    }
    fun editPreferences(transform: (AccountPreferences) -> AccountPreferences) {
        val before = mutable.value
        if (before.saving || before.loading || before.preferencesUnknown) return
        before.draft?.let { mutable.value = before.copy(draft = transform(it), error = null, notice = null) }
    }
    fun editPassword(transform: (AccountPasswordDraft) -> AccountPasswordDraft) {
        val before = mutable.value
        if (!before.saving && !before.passwordUnknown) mutable.value = before.copy(password = transform(before.password), error = null, notice = null)
    }
    fun clearPasswordDraft() { mutable.value = mutable.value.copy(password = AccountPasswordDraft()) }
    fun discardPreferences() {
        val before = mutable.value
        if (!before.saving && !before.preferencesUnknown) mutable.value = before.copy(draft = before.profile?.preferences, error = null)
    }
    fun savePreferences() {
        val context = bound ?: return
        val before = mutable.value
        val profile = before.profile ?: return
        val draft = before.draft ?: return
        if (!isCurrent(context) || before.saving || before.loading || before.preferencesUnknown || !before.dirty) return
        val patch = try { profile.preferencePatch(draft) } catch (_: IllegalArgumentException) {
            mutable.value = before.copy(error = "请检查播放设置的范围和特殊前缀规则"); return
        }
        read?.cancel(); val epoch = ++revision
        mutable.value = before.copy(saving = true, error = null, notice = null)
        write = scope.launch {
            var accepted = false
            try {
                val acknowledgement = context.api.updateOwnAccount(patch)
                accepted = true
                if (!current(context, epoch)) return@launch
                val fresh = context.api.accountProfile()
                if (current(context, epoch)) {
                    mutable.value = mutable.value.copy(profile = fresh, draft = fresh.preferences, saving = false,
                        notice = if (acknowledgement.tokenStorageFailed) "账户设置已保存，登录状态刷新失败，请重新连接" else "账户设置已保存")
                    onProfileChanged(context, fresh)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (current(context, epoch)) {
                    val rejected = !accepted && rejected(error)
                    mutable.value = mutable.value.copy(saving = false, preferencesUnknown = !rejected,
                        error = if (rejected) rejectionMessage(error, false) else "保存结果未完成核对，草稿已保留。请先读取服务器当前设置")
                }
            }
        }
    }
    fun savePassword() {
        val context = bound ?: return
        val before = mutable.value
        val capabilities = before.capabilities ?: return
        if (!isCurrent(context) || before.saving || before.loading || before.passwordUnknown || !before.canChangePassword) return
        val draft = before.password
        draft.validationError(capabilities)?.let { message ->
            mutable.value = before.copy(error = message); return
        }
        read?.cancel(); val epoch = ++revision
        mutable.value = before.copy(saving = true, error = null, notice = null)
        write = scope.launch {
            var accepted = false
            try {
                val acknowledgement = context.api.updateOwnAccount(JSONObject().put("password", draft.replacement),
                    draft.current.takeIf { capabilities.currentPasswordRequired })
                accepted = true
                if (!current(context, epoch)) return@launch
                onPasswordChanged(context, draft.replacement)
                if (current(context, epoch)) mutable.value = mutable.value.copy(saving = false, password = AccountPasswordDraft(),
                    notice = if (acknowledgement.tokenStorageFailed) "密码已修改，登录状态刷新失败，请重新连接" else "密码已修改")
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (current(context, epoch)) {
                    val rejected = !accepted && rejected(error)
                    mutable.value = mutable.value.copy(saving = false, passwordUnknown = !rejected,
                        password = if (accepted) AccountPasswordDraft() else draft,
                        error = when {
                            accepted -> "密码已修改，本机登录凭据未能更新，请重新连接"
                            rejected -> rejectionMessage(error, true)
                            else -> "修改结果未获确认，密码草稿已保留。请重新连接并用新密码核对，勿重复提交"
                        })
                }
            }
        }
    }
    private fun rejected(error: Exception) = error is ServiceException && error.status in setOf(400, 401, 403, 404, 409)
    private fun rejectionMessage(error: Exception, password: Boolean) = when ((error as? ServiceException)?.status) {
        401 -> "登录已过期，请重新连接"
        403 -> "当前账号无权修改，或密码已被管理员锁定"
        400 -> if (password) "当前密码不正确，或新密码不符合服务器要求；请检查后重试" else "服务器拒绝了账户设置，请检查输入"
        else -> "服务器未接受更改，请重新读取资料后再试"
    }
}
