package io.github.kkwans.nasfilebrowser.app

import io.github.kkwans.nasfilebrowser.core.TransportException
import io.github.kkwans.nasfilebrowser.data.ServiceException
import java.io.IOException
import java.net.SocketTimeoutException
import javax.crypto.AEADBadTagException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.*
import org.junit.Test

class LoginFailureTest {
    @Test fun definitiveAuthenticationFailureUsesTheActualLoginContext() {
        val restored = loginFailureFeedback(ServiceException(401, "private response"), LoginPhase.RESTORE, true)
        assertEquals("HTTP_401", restored.reason)
        assertEquals("保存的登录未通过验证，请重新登录。", restored.message)
        assertTrue(restored.diagnostic.contains("status=401"))
        val manual = loginFailureFeedback(ServiceException(401, "private response"), LoginPhase.SIGN_IN, false)
        assertEquals("账号或密码不正确，请检查后重试。", manual.message)
        assertFalse(manual.diagnostic.contains("private response"))
        val verification = loginFailureFeedback(ServiceException(401, "private response"), LoginPhase.VERIFY, false)
        assertFalse("An accepted login followed by a rejected read is not proof of a bad password", verification.message.contains("密码"))
    }
    @Test fun forbiddenDirectoryIsDifferentFromRejectedLogin() {
        val read = loginFailureFeedback(ServiceException(403, "private response"), LoginPhase.VERIFY, true)
        val login = loginFailureFeedback(ServiceException(403, "账号或密码不正确"), LoginPhase.RESTORE, true)
        assertTrue(read.message.contains("访问权限"))
        assertFalse(read.message.contains("密码"))
        assertTrue(login.message.contains("账号或密码"))
    }
    @Test fun serviceOutageNeverAsksForNewCredentials() {
        val result = loginFailureFeedback(ServiceException(503, "https://private.invalid"), LoginPhase.RESTORE, true)
        assertEquals("HTTP_OTHER", result.reason)
        assertFalse(result.message.contains("密码"))
        assertFalse(result.message.contains("过期"))
        assertFalse(result.diagnostic.contains("private.invalid"))
    }
    @Test fun onlyKnownTransportReasonsAreClassifiedAsNetworkFailure() {
        for (reason in listOf("cannot connect to service", "service response interrupted", "context deadline exceeded")) {
            val result = loginFailureFeedback(TransportException(reason), LoginPhase.RESTORE, true)
            assertEquals("NETWORK", result.reason)
            assertTrue(result.diagnostic.contains("native=$reason"))
        }
        val ambiguous = loginFailureFeedback(TransportException("session expired; sign in again"), LoginPhase.RESTORE, true)
        assertEquals("UNKNOWN", ambiguous.reason)
        assertFalse(ambiguous.message.contains("过期"))
        assertTrue("Safe fixed native reasons remain diagnostic evidence without proving HTTP 401", ambiguous.diagnostic.contains("native=session expired; sign in again"))
    }
    @Test fun localPersistenceAndNetworkFailuresDoNotMasqueradeAsEachOther() {
        assertEquals("LOCAL_STORAGE", loginFailureFeedback(IOException("private file"), LoginPhase.SAVE_LOGIN, true).reason)
        assertEquals("LOCAL_STORAGE", loginFailureFeedback(AEADBadTagException("private cipher"), LoginPhase.RESTORE, true).reason)
        assertEquals("LOCAL_STORAGE", loginFailureFeedback(java.sql.SQLException("private database"), LoginPhase.RESTORE, true).reason)
        assertEquals("NETWORK", loginFailureFeedback(SocketTimeoutException("private host"), LoginPhase.SAVE_LOGIN, true).reason)
        assertEquals("UNKNOWN", loginFailureFeedback(TransportException("unknown session"), LoginPhase.SAVE_LOGIN, true).reason)
        assertEquals("UNKNOWN", loginFailureFeedback(IllegalStateException("private unknown"), LoginPhase.SAVE_LOGIN, true).reason)
        assertEquals("NETWORK", loginFailureFeedback(SSLHandshakeException("private certificate"), LoginPhase.RESTORE, true).reason)
        val commit = loginFailureFeedback(IllegalStateException("本机登录凭据未能保存，请重试"), LoginPhase.SAVE_LOGIN, true)
        assertEquals("本机登录凭据未能保存，请重试。", commit.message)
    }
    @Test fun unknownMessagesCauseMessagesAndFileNamesNeverReachUiOrLogs() {
        val secret = "https://private.invalid/token?password=owned-secret"
        val error = IllegalStateException("Can't restore $secret", IOException("input=$secret"))
        error.stackTrace = arrayOf(
            StackTraceElement(secret, secret, secret, 1),
            StackTraceElement("owned.Login", "restore", secret, 42),
        )
        val result = loginFailureFeedback(error, LoginPhase.RESTORE, true)
        assertEquals("UNKNOWN", result.reason)
        assertEquals("暂时无法恢复登录，请重试或手动连接。", result.message)
        assertFalse(result.message.contains(secret))
        assertFalse(result.diagnostic.contains(secret))
        assertFalse(result.diagnostic.contains("Can't"))
        assertTrue(result.diagnostic.contains("owned.Login.restore:42"))
        assertTrue(result.diagnostic.contains("java.io.IOException"))
    }
    @Test fun knownSourceChangesRemainUsefulWithoutPassingThroughArbitraryText() {
        val known = loginFailureFeedback(IllegalStateException("服务器档案已变化，请重新连接"), LoginPhase.SAVE_LOGIN, true)
        assertEquals("服务器档案已变化，请重新连接", known.message)
        val unknown = loginFailureFeedback(IllegalStateException("本机错误 password=owned-secret"), LoginPhase.VERIFY, true)
        assertEquals("UNKNOWN", unknown.reason)
        assertFalse(unknown.message.contains("owned-secret"))
    }
    @Test fun embeddedNetworkRecoveryRemainsAnExplicitStage() {
        assertEquals("EMBEDDED_NETWORK", loginFailureFeedback(TransportException("cannot start embedded network"), LoginPhase.EMBEDDED_NETWORK, true).reason)
        assertEquals("UNKNOWN", loginFailureFeedback(TransportException("cannot start embedded network"), LoginPhase.RESTORE, true).reason)
        val notReady = loginFailureFeedback(IllegalStateException("请先连接并登录 Tailscale"), LoginPhase.EMBEDDED_NETWORK, true)
        assertEquals("请先连接并登录 Tailscale", notReady.message)
    }
}
