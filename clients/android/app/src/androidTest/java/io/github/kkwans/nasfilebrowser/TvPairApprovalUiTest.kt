package io.github.kkwans.nasfilebrowser

import android.os.Build
import android.net.Uri
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.Until
import io.github.kkwans.fileway.remote.*
import io.github.kkwans.nasfilebrowser.app.*
import io.github.kkwans.nasfilebrowser.ui.ClientTheme
import io.github.kkwans.nasfilebrowser.ui.TvControlScreen
import io.github.kkwans.nasfilebrowser.ui.tvQrFromImage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in component journey using the existing owned installation key. New-key
 * authorization is covered by Go TLS tests, not inferred from this paired phone. */
@RunWith(AndroidJUnit4::class)
class TvPairApprovalUiTest {
    /** The host is restarted by the local runner after the phone's first DNS-SD window. */
    @Test fun trustedTvRecoversAfterInitialDiscoveryEnds() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val runId = InstrumentationRegistry.getArguments().getString("ownedTvDelayedResume")
        assumeTrue("A task-owned paired two-emulator run is required", runId?.matches(Regex("[a-z0-9]+")) == true)
        require(Build.VERSION.SDK_INT == 35 && Build.HARDWARE.contains("ranchu"))
        val context = instrumentation.targetContext
        val evidence = File(requireNotNull(context.getExternalFilesDir(null)), "fileway-delayed-tv-$runId.json")
        check(evidence.createNewFile())
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        lateinit var control: TvControlModel
        lateinit var phone: ClientModel
        val report = JSONObject()
        fun record(status: String) = evidence.writeText(report.put("status", status).toString())
        try {
            scenario.onActivity {
                val models = ViewModelProvider(it)
                control = models[TvControlModel::class.java]
                phone = models[ClientModel::class.java]
            }
            val trusted = control.client.store.dao.devices().first().single { it.trusted }.descriptor()
            report.put("savedPort", trusted.port)
            scenario.onActivity { it.setContent { ClientTheme { TvControlScreen(phone, {}, control) } } }
            val failed = withTimeout(20_000) { control.connection.first { it.phase == RemotePhase.FAILED && it.retryable } }
            assertEquals(trusted.id, failed.device?.id)
            withTimeout(20_000) { control.discovery.first { !it.searching } }
            record("START_TV")

            val restored = withTimeoutOrNull(35_000) {
                control.connection.first { it.phase == RemotePhase.CONNECTED && it.snapshot != null }
            }
            if (restored == null) {
                val phase = control.connection.value
                report.put("phaseAfterRestart", phase.phase.name).put("retryable", phase.retryable)
                // Observe the advertisement only after the product's recovery window.
                val late = withTimeoutOrNull(13_000) {
                    TvServiceDiscovery(context).scan().first { state ->
                        state.devices.any { it.id == trusted.id && it.fingerprint == trusted.fingerprint && it.port != trusted.port }
                    }.devices.first { it.id == trusted.id && it.fingerprint == trusted.fingerprint && it.port != trusted.port }
                }
                report.put("lateAdvertisedPort", late?.port ?: JSONObject.NULL)
                error("The paired TV did not reconnect after the initial DNS-SD window")
            }
            val actual = requireNotNull(restored.device)
            assertEquals(trusted.id, actual.id)
            assertEquals(trusted.fingerprint, actual.fingerprint)
            assertTrue("Exercise a changed listener port", trusted.port != actual.port)
            report.put("reconnectedPort", actual.port)
            scenario.onActivity { control.disconnect() }
            withTimeout(5_000) { control.connection.first { it.phase == RemotePhase.OFFLINE } }
            assertNull("Explicit disconnect suppresses rediscovery recovery", withTimeoutOrNull(4_000) {
                control.connection.first { it.phase != RemotePhase.OFFLINE }
            })
            record("PASS")
        } catch (failure: Throwable) {
            record("FAIL")
            throw failure
        } finally {
            scenario.close()
        }
    }

    /** A separate task-owned TV emulator must show the consent prompt and allow the request. */
    @Test fun firstConnectionWithTVConsent() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val mode = args.getString("filewayLocalConsentMode")
        assumeTrue("Local two-emulator consent opt-in required", mode in setOf("discovery", "qr", "resume"))
        require(Build.VERSION.SDK_INT == 35 && Build.HARDWARE.contains("ranchu"))
        val address = requireNotNull(args.getString("filewayLocalConsentTvAddress"))
        val name = requireNotNull(args.getString("filewayLocalConsentTvName"))
        val relay = args.getString("filewayLocalConsentRelay")
        require(localAddress(address) != null)
        require(relay == null || (mode in setOf("qr", "resume") && relay == "10.0.2.2"))
        val files = requireNotNull(instrumentation.targetContext.getExternalFilesDir(null))
        val evidence = File(files, "fileway-local-consent-$mode.json")
        check(evidence.createNewFile()) { "A fresh task-owned run is required" }
        fun record(status: String) = evidence.writeText(JSONObject().put("status", status).put("mode", mode)
            .put("route", if (relay == null) "local-network" else "owned-emulator-byte-relay").toString())
        val device = UiDevice.getInstance(instrumentation)
        val configuration = Configurator.getInstance()
        val previousIdleTimeout = configuration.waitForIdleTimeout
        configuration.waitForIdleTimeout = 0
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        lateinit var control: TvControlModel
        lateinit var phone: ClientModel
        var initialized = false
        try {
            scenario.onActivity {
                val models = ViewModelProvider(it)
                control = models[TvControlModel::class.java]
                phone = models[ClientModel::class.java]
                initialized = true
            }
            val saved = control.client.store.dao.devices().first().filter { it.trusted }
            val preferred = if (mode == "resume") {
                requireNotNull(saved.firstOrNull()).also { require(it.name == name && it.address == (relay ?: address)) }
            } else {
                require(saved.isEmpty()) { "Use an unpaired test phone" }
                null
            }
            scenario.onActivity { it.setContent { ClientTheme { TvControlScreen(phone, {}, control) } } }
            if (preferred != null) {
                val restored = withTimeout(25_000) { control.connection.first { it.phase == RemotePhase.CONNECTED && it.snapshot != null } }
                assertEquals(preferred.id, restored.device?.id)
                scenario.onActivity { control.disconnect(); control.search() }
                val unsolicited = withTimeoutOrNull(3_500) { control.connection.first { it.phase != RemotePhase.OFFLINE } }
                assertNull("Explicit disconnect must prevent another automatic connection", unsolicited)
                record("PASS")
                return@runBlocking
            }
            val target = if (mode == "discovery") {
                val seen = withTimeout(30_000) {
                    control.discovery.first { result -> result.devices.any { it.address == address && it.name == name } }
                }.devices.single { it.address == address && it.name == name }
                require(seen.pairingVersion >= 3)
                requireNotNull(device.wait(Until.findObject(By.text(name)), 10_000)).click()
                seen
            } else {
                val image = File(files, "fileway-local-consent-qr.png")
                val text = tvQrFromImage(instrumentation.targetContext, Uri.fromFile(image))
                val invite = TvInvitation.parse(text)
                require(invite.device.address == address && invite.device.name == name)
                // API 28 and API 35 images can expose different virtual networks.
                // A lab relay may change the numeric route while retaining the TV identity and invitation.
                val routed = if (relay == null) text else invite.copy(device = invite.device.at(relay, invite.device.port)).uri()
                scenario.onActivity { control.pair(routed) }
                invite.device
            }
            val pending = withTimeout(10_000) { control.connection.first { it.phase == RemotePhase.PAIRING && it.device?.id == target.id } }
            assertNull(pending.snapshot)
            assertNull(pending.pairingCode)
            assertFalse(control.client.store.dao.device(target.id)?.trusted == true)
            requireNotNull(device.wait(Until.findObject(By.text("等待 TV 允许连接")), 10_000))
            record("WAITING_FOR_TV")

            val connected = withTimeout(115_000) { control.connection.first { it.phase == RemotePhase.CONNECTED && it.snapshot != null } }
            assertEquals(target.id, connected.device?.id)
            assertTrue(control.client.store.dao.device(target.id)?.trusted == true)
            assertFalse(device.hasObject(By.text("号码一致，连接")))
            record("PASS")
        } catch (failure: Throwable) {
            record("FAIL")
            throw failure
        } finally {
            if (initialized) scenario.onActivity { control.disconnect() }
            scenario.close()
            configuration.waitForIdleTimeout = previousIdleTimeout
        }
    }

    @Test fun approvalDenialCancellationAndAutomaticConnection()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val optIn=File(context.getExternalFilesDir(null),"fileway-pairing-approval-acceptance.json")
        assumeTrue("Current pairing opt-in required",optIn.isFile)
        require(JSONObject(optIn.readText()).getBoolean("useExistingPairing"))
        val evidence=File(context.getExternalFilesDir(null),"fileway-pairing-approval-evidence").apply{mkdirs()}
        val device=UiDevice.getInstance(instrumentation)
        val phonePackage=context.packageName;val tvPackage="io.github.kkwans.fileway.tv"
        val checks=mutableListOf<String>();var step="setup"
        fun record(status:String="RUNNING")=File(evidence,"result.json").writeText(JSONObject().put("status",status).put("step",step).put("checks",JSONArray(checks))
            .put("scope","Xiaomi14 two processes; existing installation key, actual phone control component and TV dialogs; no new-key/Q1/multi-physical-device acceptance").toString(2))
        device.executeShellCommand("am start --windowingMode 5 -n io.github.kkwans.nasfilebrowser/.MainActivity")
        var activity=withTimeout(15000){var found:MainActivity?=null;while(found==null){
            found=withContext(Dispatchers.Main){(ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)+ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.STARTED)).filterIsInstance<MainActivity>().firstOrNull()}
            if(found==null)delay(100)
        };found}
        val density=withContext(Dispatchers.Main){activity.resources.displayMetrics.density}
        val right=device.displayWidth-24;val left=(right-(380*density).toInt()).coerceAtLeast(24)
        device.executeShellCommand("am task resize ${activity.taskId} $left 24 $right ${device.displayHeight-24}")
        delay(500)
        activity=withContext(Dispatchers.Main){(ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)+ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.STARTED)).filterIsInstance<MainActivity>().first()}
        val control=withContext(Dispatchers.Main){ViewModelProvider(activity)[TvControlModel::class.java]}
        val phone=withContext(Dispatchers.Main){ViewModelProvider(activity)[ClientModel::class.java]}
        val original=control.client.store.dao.devices().first().firstOrNull{it.trusted}?:error("Existing owned TV required")
        val owner=Any()
        fun node(label:String,pkg:String,actionable:Boolean=false):AccessibilityNodeInfo? {
            if(Build.VERSION.SDK_INT>=34)instrumentation.uiAutomation.clearCache()
            fun find(value:AccessibilityNodeInfo?):AccessibilityNodeInfo? {
                if(value==null)return null
                if(value.isVisibleToUser&&(value.text?.toString()==label||value.contentDescription?.toString()==label)){
                    if(!actionable)return value
                    var target:AccessibilityNodeInfo?=value
                    while(target!=null&&!target.isClickable)target=target.parent
                    if(target!=null)return target
                }
                for(i in 0 until value.childCount)find(value.getChild(i))?.let{return it}
                return null
            }
            return instrumentation.uiAutomation.windows.filter{it.root?.packageName?.toString()==pkg}.sortedByDescending{it.layer}.firstNotNullOfOrNull{find(it.root)}
        }
        suspend fun visible(label:String,pkg:String,actionable:Boolean=false)=withTimeout(10000){var result=node(label,pkg,actionable);while(result==null){delay(100);result=node(label,pkg,actionable)};result}
        suspend fun click(label:String,pkg:String){
            val target=visible(label,pkg,true)
            assertTrue("Native action missing: $label",target.performAction(AccessibilityNodeInfo.ACTION_CLICK));delay(200)
        }
        suspend fun showPhone() {
            device.executeShellCommand("am start --windowingMode 5 -n io.github.kkwans.nasfilebrowser/.MainActivity")
            // HyperOS restores a background freeform task as a tiny mini-window.
            // Reapply the owned viewport on every foreground transition.
            device.executeShellCommand("am task resize ${activity.taskId} $left 24 $right ${device.displayHeight-24}")
            delay(500)
            withContext(Dispatchers.Main){activity.setContent{ClientTheme{TvControlScreen(phone,{},control)}}}
            visible("TV 遥控",phonePackage)
        }
        suspend fun request() {
            click(original.name,phonePackage)
            val state=withTimeout(10000){control.connection.first{it.phase==RemotePhase.PAIRING}}
            visible("等待 TV 允许连接",phonePackage)
            withContext(Dispatchers.Main){activity.moveTaskToBack(true)}
            visible("允许手机连接？",tvPackage)
            assertNull("New pairing must not ask for a comparison code",state.pairingCode)
            assertNull(state.snapshot)
            assertFalse(control.client.store.dao.device(original.id)!!.trusted)
        }
        try {
            withContext(Dispatchers.Main){control.disconnect()}
            // Preserve/restore this exact hint. Never delete TV keys or account data.
            control.client.store.dao.saveDevice(original.copy(trusted=false))
            withContext(Dispatchers.Main){activity.setContent{ClientTheme{TvControlScreen(phone,{},control)}};control.startView(owner)}
            withTimeout(20000){control.discovery.first{it.devices.any{row->row.id==original.id&&row.fingerprint==original.fingerprint&&row.pairingVersion>=3}}}
            visible("配对待确认",phonePackage)
            step="deny";request();device.takeScreenshot(File(evidence,"pending.png"));click("拒绝",tvPackage)
            withTimeout(10000){control.connection.first{it.phase==RemotePhase.FAILED&&it.error?.contains("拒绝")==true}}
            assertFalse(control.client.store.dao.device(original.id)!!.trusted)
            checks+="TV denial preserves untrusted state without showing a comparison code";record()
            delay(5200)
            step="restore phone after denial";record();showPhone()
            step="cancel";request();showPhone();click("取消连接",phonePackage)
            withContext(Dispatchers.Main){activity.moveTaskToBack(true)}
            withTimeout(10000){while(node("允许手机连接？",tvPackage)!=null)delay(100)}
            assertEquals(RemotePhase.OFFLINE,control.connection.value.phase)
            assertFalse(control.client.store.dao.device(original.id)!!.trusted)
            checks+="phone cancellation retires TV prompt and preserves untrusted status";record()
            delay(5200)
            showPhone()
            step="allow on TV";request();click("允许",tvPackage)
            val ready=withTimeout(15000){control.connection.first{it.phase==RemotePhase.CONNECTED&&it.snapshot!=null}}
            assertEquals(original.id,ready.device!!.id)
            assertTrue(control.client.store.dao.device(original.id)!!.trusted)
            showPhone()
            assertNull(node("号码一致，连接",phonePackage))
            checks+="TV durable approval connects the same phone without another interaction";record()
            step="explicit disconnect required"
            val other=ready.device!!.copy(id="0".repeat(32))
            val rejected=runCatching{control.client.connect(other)}.exceptionOrNull()
            assertTrue(rejected is RemoteFailure&&rejected.code=="DISCONNECT_REQUIRED")
            withContext(Dispatchers.Main){control.connect(other)}
            assertEquals(original.id,control.connection.value.device!!.id)
            assertEquals(ready.snapshot!!.connectionId,control.connection.value.snapshot!!.connectionId)
            checks+="model and transport reject another target before disconnect, retaining the existing authoritative connection";record("PASS")
        }catch(failure:Throwable){runCatching{device.dumpWindowHierarchy(File(evidence,"failure.xml"));device.takeScreenshot(File(evidence,"failure.png"))};record("FAIL");throw failure}
        finally {
            withContext(NonCancellable){
                withContext(Dispatchers.Main){control.disconnect();control.stopView(owner)}
                // Wait out the bounded pending-request cancellation before restoring UI.
                delay(3300)
                control.client.store.dao.saveDevice(original)
                withContext(Dispatchers.Main){activity.recreate()}
            }
            optIn.delete()
            device.executeShellCommand("am start --windowingMode 1 -n io.github.kkwans.nasfilebrowser/.MainActivity")
        }
    }
}
