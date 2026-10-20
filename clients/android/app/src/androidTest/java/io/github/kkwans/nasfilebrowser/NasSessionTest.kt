package io.github.kkwans.nasfilebrowser

import android.util.Base64
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kkwans.nasfilebrowser.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NasSessionTest {
    private fun token(id: Long, issued: Long = 0, username: String = "viewer"): String {
        val payload = JSONObject().put("iat", issued).put("user", JSONObject().put("id", id).put("username", username))
        return "header." + Base64.encodeToString(payload.toString().toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) + ".signature"
    }
    @Test fun renewedTokensArePersistedOnceAndAnotherIdentityCannotReachStorage() = runBlocking {
        val original = token(7, 10)
        val renewed = token(7, 20)
        var current = original
        val saved = mutableListOf<String>()
        val session = NasSession.restore(ServerProfile(name = "Owned login", address = "https://login.example.test"), original, 7) { command ->
            when (command.getString("op")) {
                "open" -> "owned-login"
                "token" -> current
                "request" -> { current = renewed; JSONObject().put("status", 200).put("body", "{}") }
                else -> null
            }
        }
        session.persistTokens { saved.add(it) }
        session.request("GET", "/api/resources/")
        session.token(); session.token()
        assertEquals(listOf(original, renewed), saved)
        current = token(8, 30)
        assertTrue(runCatching { session.token() }.isFailure)
        assertEquals(listOf(original, renewed), saved)
        session.close()
    }
    @Test fun renewedPrincipalMetadataKeepsRestoredAccountActivationConsistent() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, ClientDatabase::class.java).build()
        val store = ProfileStore(database, CredentialVault(context))
        val profile = store.save(ServerProfile(name = "Owned renamed login", address = "https://login.example.test"))
        val old = token(7, 10)
        val renewed = token(7, 20, "viewer-renamed")
        var current = old
        var session: NasSession? = null
        try {
            val previous = store.saveLogin(profile, 7, "viewer", old)
            val saved = restoreSavedSession(store, profile, previous) { command ->
                when (command.getString("op")) {
                    "open" -> "owned-renamed-session"
                    "token" -> current
                    "request" -> {
                        assertEquals("GET", command.getString("method"))
                        current = renewed
                        JSONObject().put("status", 200).put("body", "{\"items\":[]}")
                    }
                    "close_session" -> null
                    else -> error("Unexpected owned authentication call")
                }
            }
            val api = saved.api.also { session = it }
            // Match ClientModel's verified read -> save -> persist -> activate.
            val account = store.saveLogin(profile, api.identity.id, api.identity.username, api.token())
            api.persistTokens { store.refreshToken(profile, account, it); Unit }
            store.activate(profile, account, "owned-renamed-login")
            assertEquals("viewer-renamed", store.active()?.second?.username)
            assertEquals("viewer-renamed", api.identity.username)
            assertEquals(previous.key, account.key)
            assertEquals(previous.credentialRef, account.credentialRef)
            assertEquals(renewed, store.token(profile, account))
            val verified = api.identity
            current = token(8, 30, "different-viewer")
            try { api.token(); fail("Renewal must not change the bound user ID") }
            catch (_: IllegalStateException) { }
            assertEquals(verified, api.identity)
            assertEquals(renewed, store.token(profile, account))
        } finally {
            try { session?.close() }
            finally { try { store.remove(profile) } finally { database.close() } }
        }
    }
    @Test fun previewCapabilityPreservesWirePathAndReleasesOnce() = runBlocking {
        var account = 7L
        var previewCalls = 0
        var revoked = 0
        val wire = "/%D6%D0/a%252F.mkv"
        val native: suspend (JSONObject) -> Any? = { request ->
            when (request.getString("op")) {
                "open" -> "preview-session"
                "login" -> JSONObject().put("status", 200).put("body", token(account))
                "token" -> token(account)
                "preview" -> {
                    previewCalls++
                    assertEquals("preview-session", request.getString("session"))
                    assertEquals(wire, request.getString("wirePath"))
                    "http://127.0.0.1:12345/stream/owned-preview"
                }
                "revoke" -> { revoked++; assertEquals("http://127.0.0.1:12345/stream/owned-preview", request.getString("url")); null }
                else -> null
            }
        }
        val session = NasSession.login(ServerProfile(name = "NAS", address = "https://nas.example.test"), "viewer", "fixture", native)
        val asset = session.preview("/display.mkv", wire)
        assertEquals("preview-session", asset.scope)
        asset.release(); asset.release()
        assertEquals(1, revoked)
        account = 8
        try { session.preview("/display.mkv", wire); fail("Changed account cannot allocate a preview") }
        catch (_: IllegalStateException) { }
        assertEquals(1, previewCalls)
    }
    @Test fun textJwtLoginAndOpaqueEndpointRemainBoundToSession() = runBlocking {
        val profile = ServerProfile(name = "NAS", address = "https://nas.example.test:8443/base")
        val calls = mutableListOf<JSONObject>()
        val native: suspend (JSONObject) -> Any? = { command ->
            calls += command
            when (command.getString("op")) {
                "open" -> { assertEquals(profile.address, command.getString("baseUrl")); "session-a" }
                "login" -> JSONObject().put("status", 200).put("body", token(7))
                "token" -> token(7)
                "request" -> JSONObject().put("status", 200).put("body", "{\"items\":[]}")
                else -> null
            }
        }
        val session = NasSession.login(profile, "viewer", "test-fixture", native)
        assertEquals(7L, session.identity.id)
        session.request("GET", "/api/resources/%ed%a0%80%2B")
        assertEquals("/api/resources/%ed%a0%80%2B", calls.first { it.optString("op") == "request" }.getString("endpoint"))
        assertEquals("session-a", calls.first { it.optString("op") == "request" }.getString("session"))
    }
    @Test fun failedLoginClosesHandleAndForbiddenMeansIncorrectCredentials() = runBlocking {
        var closed = false
        val native: suspend (JSONObject) -> Any? = {
            when (it.getString("op")) {
                "open" -> "fresh"
                "login" -> JSONObject().put("status", 403).put("body", "")
                "close_session" -> { closed = true; null }
                else -> null
            }
        }
        val failure = runCatching { NasSession.login(ServerProfile(name = "NAS", address = "http://nas.example.test"), "viewer", "test-fixture", native) }.exceptionOrNull()
        assertEquals("账号或密码不正确", failure?.message)
        assertTrue(closed)
    }
    @Test fun renewedIdentityCannotSilentlyChangeAccount() = runBlocking {
        val profile = ServerProfile(name = "NAS", address = "https://nas.example.test")
        val session = NasSession.restore(profile, token(7), 7) { if (it.getString("op") == "open") "handle" else token(8) }
        assertTrue(runCatching { session.token() }.isFailure)
        assertTrue(runCatching { NasSession.restore(profile, token(7), 8) { error("must not open") } }.isFailure)
    }

    @Test fun windowsLoginRestoreAndOpaqueDrivePathsUseTheSharedNativeContract() = runBlocking {
        val profile = ServerProfile(name = "Windows", address = "https://windows.example.test/base", backend = BackendKind.WINDOWS)
        val calls = mutableListOf<JSONObject>()
        var opened = 0
        val native: suspend (JSONObject) -> Any? = { request ->
            calls.add(request)
            when (request.getString("op")) {
                "open" -> { assertEquals(profile.address, request.getString("baseUrl")); "windows-${++opened}" }
                "login" -> JSONObject().put("status", 200).put("body", token(7))
                "token" -> token(7)
                "request" -> JSONObject().put("status", 200).put("body", "{\"items\":[]}")
                "lease" -> "http://127.0.0.1:12345/stream/owned-windows"
                else -> null
            }
        }
        val session = NasSession.login(profile, "viewer", "owned-fixture", native)
        val endpoint = "/api/resources/C/%E7%94%B5%E5%BD%B1/a%252F%2B.mkv"
        session.request("GET", endpoint)
        val wire = "/C/%E7%94%B5%E5%BD%B1/a%252F%2B.mkv"
        session.lease("/C/电影/a%2F+.mkv", wire)
        assertEquals(endpoint, calls.single { it.optString("op") == "request" }.getString("endpoint"))
        assertEquals(wire, calls.single { it.optString("op") == "lease" }.getString("wirePath"))
        session.close()
        val restored = NasSession.restore(profile, token(7), 7, native)
        assertEquals(BackendKind.WINDOWS, restored.profile.backend)
        assertEquals(7L, restored.identity.id)
        restored.close()
        assertEquals(2, calls.count { it.optString("op") == "close_session" })
    }
    @Test fun malformedPrincipalIsNotCoercedIntoAnotherAccount() {
        for (id in listOf("7", 7.5, -1)) {
            val payload = JSONObject().put("user", JSONObject().put("id", id).put("username", "viewer"))
            val encoded = Base64.encodeToString(payload.toString().toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            assertTrue(runCatching { NasSession.parseIdentity("header.$encoded.signature") }.isFailure)
        }
    }
    @Test fun acknowledgedWriteSurvivesTokenStorageFailureAndSameTokenRetries() = runBlocking {
        var writes = 0
        var saves = 0
        var storageAvailable = true
        val profile = ServerProfile(name = "Owned", address = "https://owned.example.test")
        val original = token(7)
        var current = original
        val next = original.replace("signature", "renewed-signature")
        val session = NasSession.restore(profile, original, 7) { command ->
            when (command.getString("op")) {
                "open" -> "owned-storage"
                "token" -> current
                "request" -> { writes++; current = next; JSONObject().put("status", 202).put("body", "{\"id\":\"accepted-once\"}").put("sessionHealth", "renewal_failed") }
                else -> null
            }
        }
        session.persistTokens { saves++; check(storageAvailable) }
        storageAvailable = false
        val result = session.action("POST", "/api/owned")
        assertEquals("accepted-once", result.getString("id"))
        assertEquals(1, writes)
        assertEquals(SessionHealthCode.TOKEN_STORAGE_FAILED, session.sessionHealth.value?.code)
        val beforeRetry = saves
        storageAvailable = true
        assertEquals(next, session.token())
        assertEquals(beforeRetry + 1, saves)
        assertEquals(SessionHealthCode.RENEWAL_FAILED, session.sessionHealth.value?.code)
    }

}
