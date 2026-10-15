package io.github.kkwans.nasfilebrowser

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.kkwans.nasfilebrowser.app.*
import io.github.kkwans.nasfilebrowser.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

internal class ServerSettingsAuthority {
    var settings = initialSettings()
    var allowed = true
    var writes = 0
    var reads = 0
    var lastBody: JSONObject? = null
    var commitThenFail = false
    var reject = false
    var hold: CompletableDeferred<Unit>? = null
    val entered = CompletableDeferred<Unit>(); val finished = CompletableDeferred<Unit>()
    suspend fun context(owner: String): SessionContext {
        val payload = Base64.encodeToString("{\"user\":{\"id\":1,\"username\":\"operator\"}}".toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val token = "e30.$payload.fixture"
        val profile = ServerProfile(id = "owned-$owner", name = "Owned settings fixture", address = "https://settings.invalid")
        val api = NasSession.restore(profile, token, 1) { command -> when (command.getString("op")) {
            "open" -> "owned-settings-$owner"
            "token" -> token
            "request" -> {
                var status = 200
                val body = if (command.getString("method") == "GET") {
                    if (command.getString("endpoint") == "/api/client-capabilities") JSONObject().put("authMethod", "json").put("enableExec", false).put("minimumPasswordLength", 6).toString()
                    else {
                        check(command.getString("endpoint") == "/api/settings"); reads++
                        if (allowed) settings.toString() else { status = 403; "forbidden" }
                    }
                } else {
                    check(command.getString("method") == "PUT" && command.getString("endpoint") == "/api/settings")
                    writes++; lastBody = JSONObject(command.getJSONObject("body").toString()); entered.complete(Unit)
                    hold?.let { withContext(NonCancellable) { it.await(); finished.complete(Unit) } }
                    if (!allowed) { status = 403; "forbidden" }
                    else if (reject) { status = 400; "owned invalid rule" }
                    else {
                        settings = JSONObject(lastBody!!.toString())
                        if (commitThenFail) error("Owned response loss after settings commit")
                        "200 OK\n"
                    }
                }
                JSONObject().put("status", status).put("body", body)
            }
            else -> error("Unexpected settings operation")
        } }
        return SessionContext(profile, AccountRecord(owner, profile.id, 0, 1, "operator", "fixture-only", 0), api, 1, owner)
    }
    companion object {
        fun initialSettings(): JSONObject = JSONObject().put("signup", false).put("createUserDir", true).put("hideLoginButton", false)
            .put("minimumPasswordLength", 6).put("userHomeBasePath", "/users").put("authMethod", "json").put("tokenExpirationTime", "2h0m0s")
            .put("futureRoot", JSONObject().put("preserved", true))
            .put("defaults", JSONObject().put("scope", "/default").put("locale", "en").put("viewMode", "mosaic")
                .put("singleClick", false).put("redirectAfterCopyMove", false).put("dateFormat", false).put("hideDotfiles", false).put("aceEditorTheme", "")
                .put("futureDefault", "preserved").put("sorting", JSONObject().put("by", "name").put("asc", true).put("futureSorting", true))
                .put("perm", ManagedPermissions(share = true, download = true).json().put("futurePermission", true))
                .put("commands", JSONArray(listOf("owned-default-command"))))
            .put("branding", JSONObject().put("name", "Owned instance").put("files", "/owned-branding").put("theme", "light").put("color", "#1767e8")
                .put("disableExternal", false).put("disableUsedPercentage", false).put("futureBrand", true))
            .put("tus", JSONObject().put("chunkSize", 10485760).put("retryCount", 5).put("futureTus", true))
            .put("shell", JSONArray(listOf("owned-shell", "-c")))
            .put("commands", JSONObject().put("before_copy", JSONArray(listOf("owned-before-command"))).put("future_event", JSONArray(listOf("owned-future-command"))).put("future_null_event", JSONObject.NULL))
            .put("rules", JSONArray().put(JSONObject().put("allow", false).put("regex", false).put("path", "/blocked").put("futureRule", true)))
    }
}

@RunWith(AndroidJUnit4::class)
class ServerSettingsControllerTest {
    private suspend fun main(action: () -> Unit) = withContext(Dispatchers.Main) { action() }
    private suspend fun loaded(controller: ServerSettingsController) = withTimeout(5000) { controller.state.first { !it.loading && (it.settings != null || it.error != null) } }
    private suspend fun settled(controller: ServerSettingsController) = withTimeout(5000) { controller.state.first { !it.saving && (it.notice != null || it.error != null) } }

    @Test fun unchangedLegacyRuleArrayKeepsNullAndMissingRegexpShape() {
        val source = ServerSettingsAuthority.initialSettings()
        val missing = JSONObject().put("allow", true).put("regex", true).put("path", "/old")
        source.put("rules", JSONArray().put(missing).put(JSONObject(missing.toString()).put("regexp", JSONObject.NULL)))
        val settings = ServerSettings.from(source)
        val body = settings.payload(settings.draft.copy(signup = true))
        assertFalse(body.getJSONArray("rules").getJSONObject(0).has("regexp"))
        assertTrue(body.getJSONArray("rules").getJSONObject(1).isNull("regexp"))
    }

    @Test fun invalidNumericDraftSurvivesLeavingAndReadingWithoutWriting(): Unit = runBlocking {
        val authority = ServerSettingsAuthority(); val context = authority.context("numeric")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = ServerSettingsController(scope) { it === context }
        try {
            main { controller.bind(context); controller.setVisible(true) }; loaded(controller)
            val original = controller.state.value.draft!!.sessionMinutes
            main { controller.editNumber("session", "bad") }
            assertTrue(controller.state.value.dirty)
            main { controller.setVisible(false); controller.setVisible(true) }; loaded(controller)
            assertEquals("bad", controller.state.value.numberDrafts["session"])
            assertEquals(original, controller.state.value.draft!!.sessionMinutes, 0.0)
            main { controller.requestSave() }
            assertFalse(controller.state.value.confirmation); assertEquals(0, authority.writes)
            main { controller.editNumber("session", "25"); controller.requestSave(); controller.confirmSave() }; settled(controller)
            assertEquals(1, authority.writes)
            assertTrue(controller.state.value.numberDrafts.isEmpty())
        } finally { scope.cancel() }
    }

    @Test fun completePayloadKeepsReadonlyUnknownHooksRulesAndDefaultShareFields(): Unit = runBlocking {
        val authority = ServerSettingsAuthority(); val context = authority.context("preserve")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main); val controller = ServerSettingsController(scope) { it === context }
        try {
            main { controller.bind(context); controller.refresh() }; loaded(controller)
            main { controller.edit { it.copy(branding = it.branding.copy(name = "Updated instance")) }; controller.requestSave(); controller.confirmSave() }; settled(controller)
            val body = authority.lastBody!!
            assertEquals("json", body.getString("authMethod")); assertFalse(body.has("enableExec")); assertFalse(body.has("current_password"))
            assertEquals("2h0m0s", body.getString("tokenExpirationTime"))
            assertTrue(body.getJSONObject("futureRoot").getBoolean("preserved")); assertTrue(body.getJSONObject("branding").getBoolean("futureBrand"))
            assertTrue(body.getJSONObject("tus").getBoolean("futureTus")); assertEquals("preserved", body.getJSONObject("defaults").getString("futureDefault"))
            assertTrue(body.getJSONObject("defaults").getJSONObject("perm").getBoolean("share")); assertTrue(body.getJSONObject("defaults").getJSONObject("perm").getBoolean("futurePermission"))
            assertEquals("owned-future-command", body.getJSONObject("commands").getJSONArray("future_event").getString(0))
            assertTrue(body.getJSONObject("commands").isNull("future_null_event")); assertTrue(body.getJSONArray("rules").getJSONObject(0).getBoolean("futureRule"))
            assertFalse(controller.state.value.dirty); assertEquals(1, authority.writes)
            assertEquals(45.0, sessionDurationMinutes("45m0s"), .00001); assertEquals(90.0, sessionDurationMinutes("1h30m"), .00001)
            assertTrue(runCatching { sessionDurationMinutes("1m") }.isFailure)
        } finally { scope.cancel() }
    }

    @Test fun unknownSaveBlocksRepeatAndIsResolvedByAuthoritativeReadOnly(): Unit = runBlocking {
        val authority = ServerSettingsAuthority(); authority.commitThenFail = true
        val context = authority.context("unknown"); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = ServerSettingsController(scope) { it === context }
        try {
            main { controller.bind(context); controller.refresh() }; loaded(controller)
            main { controller.edit { it.copy(signup = true, rules = it.rules + ManagedRule(regex = true, expression = "^/owned/")) }; controller.requestSave(); controller.confirmSave() }; settled(controller)
            assertTrue(controller.state.value.unknown); assertTrue(controller.state.value.draft!!.signup)
            main { controller.requestSave(); controller.confirmSave() }; assertEquals(1, authority.writes)
            main { controller.refresh() }; loaded(controller)
            assertFalse(controller.state.value.unknown); assertFalse(controller.state.value.dirty)
            assertTrue(controller.state.value.notice!!.contains("已核对")); assertEquals(1, authority.writes)
        } finally { scope.cancel() }
    }

    @Test fun changedServerBaselinePreventsFullSnapshotOverwriteBeforePut(): Unit = runBlocking {
        val authority = ServerSettingsAuthority(); val context = authority.context("conflict")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main); val controller = ServerSettingsController(scope) { it === context }
        try {
            main { controller.bind(context); controller.refresh() }; loaded(controller)
            main { controller.edit { it.copy(signup = true) } }
            authority.settings.getJSONObject("branding").put("name", "Changed by another administrator")
            main { controller.requestSave(); controller.confirmSave() }; settled(controller)
            assertTrue(controller.state.value.unknown); assertEquals(0, authority.writes)
            assertTrue(controller.state.value.draft!!.signup)
            assertEquals("Changed by another administrator", authority.settings.getJSONObject("branding").getString("name"))
        } finally { scope.cancel() }
    }

    @Test fun explicitRejectionAndDeniedReadNeverBecomeSuccess(): Unit = runBlocking {
        val authority = ServerSettingsAuthority(); val context = authority.context("reject")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main); val controller = ServerSettingsController(scope) { it === context }
        try {
            main { controller.bind(context); controller.refresh() }; loaded(controller)
            authority.reject = true
            main { controller.edit { it.copy(signup = true) }; controller.requestSave(); controller.confirmSave() }; settled(controller)
            assertFalse(controller.state.value.unknown); assertTrue(controller.state.value.dirty); assertFalse(authority.settings.getBoolean("signup"))
            authority.allowed = false
            main { controller.refresh() }; loaded(controller)
            assertFalse(controller.state.value.authorized)
            main { controller.requestSave(); controller.confirmSave() }; assertEquals(1, authority.writes)
        } finally { scope.cancel() }
    }

    @Test fun lateSaveCannotReplaceNextAccountSettings(): Unit = runBlocking {
        val authority = ServerSettingsAuthority(); authority.hold = CompletableDeferred(); val old = authority.context("old")
        val next = ServerSettingsAuthority().context("next"); var current = old
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main); val controller = ServerSettingsController(scope) { it === current }
        try {
            main { controller.bind(old); controller.refresh() }; loaded(controller)
            main { controller.edit { it.copy(signup = true) }; controller.requestSave(); controller.confirmSave() }
            withTimeout(5000) { authority.entered.await() }
            main { current = next; controller.bind(next); controller.refresh() }; loaded(controller)
            authority.hold!!.complete(Unit); withTimeout(5000) { authority.finished.await() }; main { }
            assertEquals(next.owner, controller.state.value.scope); assertFalse(controller.state.value.draft!!.signup); assertFalse(controller.state.value.unknown)
        } finally { authority.hold?.complete(Unit); scope.cancel() }
    }
}
