package io.github.kkwans.nasfilebrowser.app

import io.github.kkwans.nasfilebrowser.core.TransportException
import io.github.kkwans.nasfilebrowser.data.ServiceException
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.InvalidKeyException
import java.security.KeyStoreException
import java.security.UnrecoverableKeyException
import javax.crypto.BadPaddingException
import javax.net.ssl.SSLException

internal enum class LoginPhase { LOCAL_RECORDS, EMBEDDED_NETWORK, RESTORE, SIGN_IN, VERIFY, SAVE_LOGIN, ACTIVATE, DIRECTORY }
internal data class LoginFailureFeedback(val message: String, val reason: String, val diagnostic: String)

// Exact, already controlled application/native reasons only. Unknown messages
// may include addresses, entered values or credentials and must never escape.
private val knownLoginMessages = mapOf(
    "请输入服务器名称" to "请输入服务器名称",
    "请输入包含 http:// 或 https:// 的完整服务器地址" to "请输入包含 http:// 或 https:// 的完整服务器地址",
    "服务器档案已变化，请重新连接" to "服务器档案已变化，请重新连接",
    "登录来源已变化，请重新连接" to "登录来源已变化，请重新连接",
    "保存的账号身份已变化，请重新确认登录" to "保存的账号身份已变化，请重新确认登录",
    "保存的账号不匹配，请重新登录" to "保存的账号不匹配，请重新登录",
    "服务器账号已变化，请重新登录" to "服务器账号已变化，请重新登录",
    "保存的登录已失效，请重新输入密码" to "保存的登录已不可用，请手动连接。",
    "请先连接并登录 Tailscale" to "请先连接并登录 Tailscale",
    "内嵌节点尚未连接，请检查网络并重试。" to "内嵌节点尚未连接，请检查网络并重试。",
)
private val storageLoginMessages = mapOf(
    "本机凭据无法读取，请重新登录" to "本机登录凭据暂时无法读取，请重试或手动连接。",
    "本机登录凭据未能保存，请重试" to "本机登录凭据未能保存，请重试。",
    "凭据无法删除，请重试" to "本机凭据操作未完成，请重试。",
)
private val nativeLoginReasons = setOf("cannot connect to service", "service response interrupted", "context deadline exceeded")
private val nativeDiagnosticReasons = nativeLoginReasons + setOf(
    "session expired; sign in again", "service response exceeds size limit",
    "login did not return a JWT text token", "cannot encode native response", "invalid native command",
)
private val safeClass = Regex("[A-Za-z0-9_.$]+")
private val safeMethod = Regex("[A-Za-z0-9_$<>-]+")

internal fun loginFailureFeedback(error: Exception, phase: LoginPhase, restoring: Boolean): LoginFailureFeedback {
    val causes = mutableListOf<Throwable>()
    var next: Throwable? = error
    while (causes.size < 5) {
        val cause = next ?: break
        if (causes.any { it === cause }) break
        causes.add(cause)
        next = cause.cause
    }
    val service = causes.filterIsInstance<ServiceException>().firstOrNull()
    val native = causes.filterIsInstance<TransportException>().firstOrNull { it.message in nativeDiagnosticReasons }?.message
    val known = causes.firstNotNullOfOrNull { knownLoginMessages[it.message] }
    val storage = causes.firstNotNullOfOrNull { storageLoginMessages[it.message] }
    val local = phase in setOf(LoginPhase.LOCAL_RECORDS, LoginPhase.SAVE_LOGIN, LoginPhase.ACTIVATE)
    val (message, reason) = when {
        service?.status == 401 -> when {
            restoring -> "保存的登录未通过验证，请重新登录。"
            phase == LoginPhase.SIGN_IN -> "账号或密码不正确，请检查后重试。"
            else -> "登录验证未通过，请重试或重新登录。"
        } to "HTTP_401"
        service?.status == 403 -> (if (phase == LoginPhase.SIGN_IN || service.message == "账号或密码不正确")
            "账号或密码未通过验证，请检查后手动连接。" else "当前账号没有访问权限，请检查账号权限后重试。") to "HTTP_403"
        service != null -> "服务器暂时无法完成连接，请稍后重试。" to "HTTP_OTHER"
        storage != null -> storage to "LOCAL_STORAGE"
        known != null -> known to "KNOWN_LOCAL_REASON"
        phase == LoginPhase.EMBEDDED_NETWORK -> "内嵌网络暂不可用，请检查网络后重试。" to "EMBEDDED_NETWORK"
        native in nativeLoginReasons || causes.any { it is SocketException || it is SocketTimeoutException || it is UnknownHostException || it is SSLException } ->
            "暂时无法连接服务器，请检查网络后重试。" to "NETWORK"
        causes.any { it is android.database.SQLException || it is java.sql.SQLException } ||
            (phase == LoginPhase.RESTORE && causes.any { it is BadPaddingException || it is KeyStoreException || it is UnrecoverableKeyException || it is InvalidKeyException }) ||
            (local && causes.any { it is IOException }) -> "本机登录记录暂时无法读取或保存，请重试。" to "LOCAL_STORAGE"
        causes.any { it is IOException } && phase == LoginPhase.SIGN_IN ->
            "暂时无法连接服务器，请检查网络后重试。" to "NETWORK"
        else -> (if (restoring) "暂时无法恢复登录，请重试或手动连接。" else "暂时无法连接，请重试。") to "UNKNOWN"
    }
    fun type(value: Throwable) = value.javaClass.name.takeIf { safeClass.matches(it) } ?: "redacted"
    val frames = causes.joinToString(";") { cause ->
        val stack = cause.stackTrace.take(6).mapNotNull { frame ->
            if (safeClass.matches(frame.className) && safeMethod.matches(frame.methodName))
                "${frame.className}.${frame.methodName}:${frame.lineNumber}" else null
        }.joinToString(",")
        "${type(cause)}[$stack]"
    }
    val diagnostic = "phase=$phase restoring=$restoring type=${type(error)} status=${service?.status ?: "none"} " +
        "reason=$reason native=${native ?: "unclassified"} causes=$frames"
    return LoginFailureFeedback(message, reason, diagnostic)
}
