package io.github.kkwans.nasfilebrowser

import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.UiDevice
import io.github.kkwans.fileway.remote.*
import io.github.kkwans.nasfilebrowser.app.*
import io.github.kkwans.nasfilebrowser.ui.ClientTheme
import io.github.kkwans.nasfilebrowser.ui.TvControlScreen
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
    @Test fun approvalDenialCancellationAndComparison()=runBlocking {
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
            val state=withTimeout(10000){control.connection.first{it.phase==RemotePhase.PAIRING&&it.pairingCode!=null}}
            val code=state.pairingCode!!.chunked(3).joinToString(" ")
            visible(code,phonePackage)
            withContext(Dispatchers.Main){activity.moveTaskToBack(true)}
            visible("允许手机连接？",tvPackage);visible(code,tvPackage)
            assertFalse("TV result alone must not establish phone trust",state.pairingAwaitingConfirmation)
        }
        try {
            withContext(Dispatchers.Main){control.disconnect()}
            // Preserve/restore this exact hint. Never delete TV keys or account data.
            control.client.store.dao.saveDevice(original.copy(trusted=false))
            withContext(Dispatchers.Main){activity.setContent{ClientTheme{TvControlScreen(phone,{},control)}};control.startView(owner)}
            withTimeout(20000){control.discovery.first{it.devices.any{row->row.id==original.id&&row.fingerprint==original.fingerprint&&row.pairingVersion>=2}}}
            visible("配对待确认",phonePackage)
            step="deny";request();device.takeScreenshot(File(evidence,"pending.png"));click("拒绝",tvPackage)
            withTimeout(10000){control.connection.first{it.phase==RemotePhase.FAILED&&it.error?.contains("拒绝")==true}}
            assertFalse(control.client.store.dao.device(original.id)!!.trusted)
            checks+="matching comparison codes and TV denial; no camera or phone trust";record()
            delay(5200)
            step="restore phone after denial";record();showPhone()
            step="cancel";request();showPhone();click("取消申请",phonePackage)
            withContext(Dispatchers.Main){activity.moveTaskToBack(true)}
            withTimeout(10000){while(node("允许手机连接？",tvPackage)!=null)delay(100)}
            assertEquals(RemotePhase.OFFLINE,control.connection.value.phase)
            assertFalse(control.client.store.dao.device(original.id)!!.trusted)
            checks+="phone cancellation retires TV prompt and preserves untrusted status";record()
            delay(5200)
            showPhone()
            step="allow and compare";request();click("允许",tvPackage)
            val waiting=withTimeout(10000){control.connection.first{it.pairingAwaitingConfirmation}}
            visible("已允许连接",tvPackage);visible(waiting.pairingCode!!.chunked(3).joinToString(" "),tvPackage)
            assertEquals(RemotePhase.PAIRING,waiting.phase)
            assertNull(waiting.snapshot)
            assertFalse(control.client.store.dao.device(original.id)!!.trusted)
            device.takeScreenshot(File(evidence,"compare.png"));showPhone();click("号码一致，连接",phonePackage)
            val ready=withTimeout(15000){control.connection.first{it.phase==RemotePhase.CONNECTED&&it.snapshot!=null}}
            assertEquals(original.id,ready.device!!.id)
            assertTrue(control.client.store.dao.device(original.id)!!.trusted)
            checks+="TV durable approval alone cannot connect; phone comparison confirmation establishes exact-device trust and control";record()
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
