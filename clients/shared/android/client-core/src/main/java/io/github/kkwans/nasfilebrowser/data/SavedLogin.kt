package io.github.kkwans.nasfilebrowser.data

import io.github.kkwans.nasfilebrowser.core.NativeTransport
import io.github.kkwans.nasfilebrowser.core.TransportException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class SavedSession(val api: NasSession, val verification: JSONObject)

/** Only a read may be retried, and only after an unauthenticated reply or the native renewal-expired signal.
 * A failed token renewal after a mutation never enters this recovery path. */
suspend fun restoreSavedSession(
    store: ProfileStore, profile: ServerProfile, account: AccountRecord,
    verificationEndpoint: String = "/api/resources/",
    native: suspend (JSONObject) -> Any? = NativeTransport::call,
): SavedSession {
    val token = store.token(profile, account) ?: error("保存的登录已失效，请重新输入密码")
    var saved: NasSession? = null
    try {
        val restored = NasSession.restore(profile, token, account.userId, native)
        saved = restored
        restored.refreshAuthentication()
        return SavedSession(restored, restored.request("GET", verificationEndpoint))
    } catch (failure: Exception) {
        withContext(NonCancellable) { runCatching { saved?.close() }.onFailure(failure::addSuppressed) }
        val expired = failure is ServiceException && failure.status == 401 ||
            failure is TransportException && failure.message == "session expired; sign in again" ||
            failure is IllegalStateException && failure.message == "服务器返回了不支持的账号格式"
        if (!expired) throw failure
        val password = store.password(profile, account) ?: throw failure
        val renewed = try { NasSession.login(profile, account.username, password, native) }
        catch (loginFailure: Exception) {
            if (loginFailure is ServiceException && loginFailure.status in setOf(401, 403))
                store.forgetRejectedPassword(profile, account, password)
            throw loginFailure
        }
        try {
            if (renewed.identity.id != account.userId) {
                store.forgetRejectedPassword(profile, account, password)
                error("保存的账号身份已变化，请重新确认登录")
            }
            store.refreshToken(profile, account, renewed.token())
            return SavedSession(renewed, renewed.request("GET", verificationEndpoint))
        } catch (renewFailure: Exception) {
            withContext(NonCancellable) {
                if (renewFailure is ServiceException && renewFailure.status == 401)
                    runCatching { store.forgetRejectedPassword(profile, account, password) }.onFailure(renewFailure::addSuppressed)
                runCatching { renewed.close() }.onFailure(renewFailure::addSuppressed)
            }
            throw renewFailure
        }
    }
}

/** Called only for the currently bound session; preserve page/drafts/leases. */
suspend fun recoverBoundSession(store: ProfileStore, profile: ServerProfile, account: AccountRecord, api: NasSession): Boolean {
    check(profile == api.profile && account.userId == api.identity.id) { "登录来源已变化" }
    val password = store.password(profile, account) ?: return false
    try { api.recoverAuthentication(api.identity.username, password) }
    catch (failure: Exception) {
        if (failure is ServiceException && failure.status in setOf(401, 403)) store.forgetRejectedPassword(profile, account, password)
        throw failure
    }
    return true
}
