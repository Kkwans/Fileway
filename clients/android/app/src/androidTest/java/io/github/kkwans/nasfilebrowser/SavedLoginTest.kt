package io.github.kkwans.nasfilebrowser

import android.util.Base64
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kkwans.nasfilebrowser.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

/** Only uniquely owned vault entries and an in-memory database are modified. */
@RunWith(AndroidJUnit4::class)
class SavedLoginTest {
    private lateinit var database: ClientDatabase
    private lateinit var store: ProfileStore
    @Before fun prepare() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, ClientDatabase::class.java).build()
        store = ProfileStore(database, CredentialVault(context))
    }
    @After fun cleanup() = runBlocking { try { store.profiles.first().forEach { store.remove(it) } } finally { database.close() } }
    private fun token(id: Long = 7, issued: Long = 10): String {
        val body = JSONObject().put("iat", issued).put("user", JSONObject().put("id", id).put("username", "owned-viewer"))
        return "owned." + Base64.encodeToString(body.toString().toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) + ".signature"
    }
    private suspend fun account(address: String = "https://saved.example.test"): Pair<ServerProfile, AccountRecord> {
        val profile = store.save(ServerProfile(name = "Owned saved login", address = address))
        return profile to store.saveLogin(profile, 7, "owned-viewer", token())
    }
    private inner class NativeFixture(val oldStatus: Int = 401, val loginStatus: Int = 200, val user: Long = 7, val networkFailure: Boolean = false) {
        val tokens = mutableMapOf<String, String>()
        val closed = mutableSetOf<String>()
        var logins = 0
        suspend fun call(command: JSONObject): Any? = when (command.getString("op")) {
            "open" -> "owned-${tokens.size}".also { tokens[it] = command.optString("token") }
            "token" -> tokens.getValue(command.getString("session"))
            "login" -> {
                logins++
                assertEquals("owned-viewer", command.getString("username"))
                assertEquals("owned-password", command.getString("password"))
                tokens[command.getString("session")] = token(user, 20)
                JSONObject().put("status", loginStatus).put("body", if (loginStatus == 200) token(user, 20) else "")
            }
            "request" -> {
                if (networkFailure) error("Owned network outage")
                assertEquals("GET", command.getString("method"))
                JSONObject().put("status", if (tokens[command.getString("session")] == token()) oldStatus else 200).put("body", "{\"items\":[]}")
            }
            "close_session" -> { closed.add(command.getString("session")); null }
            else -> error("Unexpected owned operation")
        }
    }
    @Test fun expiredLoginUsesEncryptedPasswordOnceAndRetainsIdentity() = runBlocking {
        val (profile, account) = account()
        store.rememberPassword(profile, account, "owned-password")
        val native = NativeFixture()
        val restored = restoreSavedSession(store, profile, account, native = native::call)
        try {
            restored.api.persistTokens { store.refreshToken(profile, account, it); Unit }
            assertEquals(1, native.logins)
            assertEquals(7L, restored.api.identity.id)
            assertEquals(token(7, 20), store.token(profile, account))
            assertEquals("owned-password", store.password(profile, account))
            assertTrue(native.closed.contains("owned-0"))
        } finally { restored.api.close() }
    }
    @Test fun networkFailureAndForbiddenDoNotTriggerPasswordLogin() = runBlocking {
        for (native in listOf(NativeFixture(oldStatus = 403), NativeFixture(networkFailure = true))) {
            val (profile, account) = account()
            store.rememberPassword(profile, account, "owned-password")
            assertTrue(runCatching { restoreSavedSession(store, profile, account, native = native::call) }.isFailure)
            assertEquals(0, native.logins)
            assertEquals("owned-password", store.password(profile, account))
            assertEquals(1, native.closed.size)
        }
    }
    @Test fun rejectedPasswordOrChangedPrincipalStopsRecoveryAndForgetsOnlyThatSecret() = runBlocking {
        for (native in listOf(NativeFixture(loginStatus = 401), NativeFixture(user = 8))) {
            val (profile, account) = account()
            store.rememberPassword(profile, account, "owned-password")
            assertTrue(runCatching { restoreSavedSession(store, profile, account, native = native::call) }.isFailure)
            assertEquals(1, native.logins)
            assertNull(store.password(profile, account))
            assertEquals(token(), store.token(profile, account))
            assertEquals(2, native.closed.size)
        }
    }
    @Test fun optOutSourceChangesAndSignOutPreventPasswordReuse() = runBlocking {
        val (profile, account) = account()
        store.rememberPassword(profile, account, "owned-password")
        store.rememberPassword(profile, account, "new-owned-password")
        store.forgetRejectedPassword(profile, account, "owned-password")
        assertEquals("new-owned-password", store.password(profile, account))
        store.rememberPassword(profile, account, null)
        assertNull(store.password(profile, account))
        store.rememberPassword(profile, account, "owned-password")
        store.signOut(account)
        assertNull(store.password(profile, account))
        assertTrue(runCatching { restoreSavedSession(store, profile, account) { error("Signed-out source must not open") } }.isFailure)
        val second = store.saveLogin(profile, 7, "owned-viewer", token())
        store.rememberPassword(profile, second, "owned-password")
        store.save(profile.copy(address = "https://changed.example.test"))
        assertNull(store.password(profile, second))
    }
    @Test fun nativeHeaderRenewalPersistsAndReopensWithoutOldCredentials() = runBlocking {
        RenewalServer().use { server ->
            val (profile, account) = account(server.address)
            val first = NasSession.restore(profile, token(), account.userId)
            try {
                first.persistTokens { store.refreshToken(profile, account, it); Unit }
                first.request("GET", "/api/resources/")
            } finally { first.close() }
            assertEquals(token(7, 20), store.token(profile, account))
            server.rejectOld = true
            val reopened = restoreSavedSession(store, profile, account)
            reopened.api.close()
            assertEquals(1, server.renewals.get())
            assertEquals(0, server.logins.get())
        }
    }
    @Test fun nativeRejectedRenewalRecoversOnceOnlyWithRememberedPassword() = runBlocking {
        for ((status, invalid) in listOf(401 to false, 403 to false, 503 to false, 200 to true)) {
            RenewalServer(status, invalid).use { server ->
                val (profile, account) = account(server.address)
                store.rememberPassword(profile, account, "owned-password")
                val restored = runCatching { restoreSavedSession(store, profile, account) }
                if (status == 401) {
                    val saved = restored.getOrThrow()
                    try {
                        assertEquals(7L, saved.api.identity.id)
                        assertEquals(token(7, 20), store.token(profile, account))
                        assertEquals(1, server.logins.get())
                    } finally { saved.api.close() }
                } else {
                    assertTrue("Non-authentication renewal failures must remain failures", restored.isFailure)
                    assertEquals(0, server.logins.get())
                    assertEquals(token(), store.token(profile, account))
                }
                assertEquals(1, server.renewals.get())
                assertEquals("owned-password", store.password(profile, account))
            }
        }
        RenewalServer(401).use { server ->
            val (profile, account) = account(server.address)
            val failure = runCatching { restoreSavedSession(store, profile, account) }.exceptionOrNull()
            assertTrue(failure is ServiceException && failure.status == 401)
            assertEquals(0, server.logins.get())
            assertEquals(token(), store.token(profile, account))
        }
    }
    private inner class RenewalServer(private val renewalStatus: Int = 200, private val invalidRenewal: Boolean = false) : Closeable {
        private val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val address = "http://127.0.0.1:${socket.localPort}"
        val renewals = AtomicInteger()
        val logins = AtomicInteger()
        @Volatile var rejectOld = false
        private val worker = Thread {
            while (!socket.isClosed) try {
                socket.accept().use { client ->
                    client.soTimeout = 5000
                    val reader = client.getInputStream().bufferedReader()
                    val route = reader.readLine().split(' ')[1]
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                    }
                    val old = headers["x-auth"] == token()
                    val valid = old || headers["x-auth"] == token(7, 20)
                    var status = if (!valid || (old && rejectOld)) 401 else 200
                    var extra = ""
                    val body = when (route) {
                        "/api/renew" -> {
                            renewals.incrementAndGet(); status = renewalStatus
                            if (invalidRenewal) "not-a-token" else token(7, 20)
                        }
                        "/api/login" -> {
                            logins.incrementAndGet()
                            val chars = CharArray(headers["content-length"]?.toInt() ?: 0)
                            var read = 0
                            while (read < chars.size) {
                                val count = reader.read(chars, read, chars.size - read)
                                check(count > 0); read += count
                            }
                            val login = JSONObject(String(chars))
                            check(login.getString("username") == "owned-viewer" && login.getString("password") == "owned-password")
                            status = 200; token(7, 20)
                        }
                        else -> { if (old && status == 200) extra = "X-Renew-Token: true\r\n"; "{\"items\":[]}" }
                    }.toByteArray()
                    val head = "HTTP/1.1 $status Owned\r\nContent-Length: ${body.size}\r\nConnection: close\r\n$extra\r\n"
                    client.getOutputStream().apply { write(head.toByteArray()); write(body); flush() }
                }
            } catch (_: Exception) { if (socket.isClosed) break }
        }.apply { isDaemon = true; start() }
        override fun close() { socket.close(); worker.join(1000) }
    }
}
