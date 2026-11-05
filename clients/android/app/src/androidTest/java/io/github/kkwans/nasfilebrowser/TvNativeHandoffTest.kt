package io.github.kkwans.nasfilebrowser

import android.view.accessibility.AccessibilityNodeInfo
import android.view.PixelCopy
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import io.github.kkwans.fileway.remote.*
import io.github.kkwans.nasfilebrowser.app.*
import io.github.kkwans.nasfilebrowser.data.SearchResult
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** One opt-in installed-app journey. No new test package, credentials or app-data clear. */
@RunWith(AndroidJUnit4::class)
class TvNativeHandoffTest {
    @Test fun nativeVideoImageAndInterruptedTransfer()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val configFile=File(context.getExternalFilesDir(null),"fileway-handoff-acceptance.json")
        assumeTrue("Explicit owned-fixture opt-in required",configFile.isFile)
        val config=JSONObject(configFile.readText())
        require(config.getBoolean("useCurrentLogin"))
        val directory=config.getString("directory")
        val videoName=config.getString("videoName");val imageName=config.getString("imageName")
        require(directory.startsWith('/')&&videoName.startsWith("Fileway owned")&&imageName.startsWith("Fileway owned"))
        val evidence=File(context.getExternalFilesDir(null),"fileway-handoff-evidence").apply{mkdirs()}
        val checks=mutableListOf<String>()
        fun record(status:String="RUNNING") { File(evidence,"result.json").writeText(JSONObject().put("status",status).put("checks",JSONArray(checks))
            .put("scope","One Xiaomi14, separate real phone/TV processes, accessibility actions and actual native playback; no Q1/HDR/audibility acceptance").toString(2)) }
        val device=UiDevice.getInstance(instrumentation)
        device.executeShellCommand("am start --windowingMode 5 -n io.github.kkwans.nasfilebrowser/.MainActivity")
        suspend fun currentActivity()=withTimeout(15000) {
            var found:MainActivity?=null
            while(found==null) {
                found=withContext(Dispatchers.Main){(ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)+
                    ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.STARTED)).filterIsInstance<MainActivity>().firstOrNull()}
                if(found==null)delay(100)
            };found
        }
        val firstActivity=currentActivity()
        val width=device.displayWidth;val height=device.displayHeight
        val density=withContext(Dispatchers.Main){firstActivity.resources.displayMetrics.density}
        val right=width-24;val left=(right-(380*density).toInt()).coerceAtLeast(24)
        device.executeShellCommand("am task resize ${firstActivity.taskId} $left 24 $right ${height-24}")
        delay(500)
        val activity=currentActivity()
        val viewport=withContext(Dispatchers.Main){activity.window.decorView.width/activity.resources.displayMetrics.density}
        File(evidence,"viewport.json").writeText(JSONObject().put("widthDp",viewport).put("left",left).put("right",right).put("height",height-48).toString())
        require(viewport>=320){"Owned freeform viewport is too narrow for phone acceptance: ${viewport.toInt()}dp"}
        val phone=withContext(Dispatchers.Main){ViewModelProvider(activity)[ClientModel::class.java]}
        val control=withContext(Dispatchers.Main){ViewModelProvider(activity)[TvControlModel::class.java]}
        val owner=Any()
        val original=withTimeout(30000){phone.state.first{it.connected&&!it.startupPending&&!it.busy}}
        val source=withContext(Dispatchers.Main){phone.tvAuthorizationContext()}?:error("Phone has no current login")
        suspend fun remote(predicate:(RemoteSnapshot)->Boolean)=withTimeout(30000) {
            control.connection.first{it.phase==RemotePhase.CONNECTED&&it.snapshot?.let(predicate)==true}.snapshot!!
        }
        suspend fun command(operation:String,payload:JSONObject=JSONObject()):JSONObject?=withContext(Dispatchers.Main) {
            val result=control.client.execute(operation,payload)
            assertTrue("TV operation rejected: $operation / ${result.code}",result.completed);result.payload
        }
        suspend fun ready(name:String):RemoteSnapshot {
            withTimeout(125000){combine(control.handoff.state,control.connection){ui,peer->ui to peer}.first{(ui,peer)->
                ui?.stage in setOf(TvHandoffStage.FAILED,TvHandoffStage.AUTHORIZATION)||peer.snapshot?.let{snapshot->
                    val receipt=snapshot.content.optJSONObject("handoff")
                    snapshot.content.optJSONObject("media")?.optString("name")==name&&receipt?.optString("status")=="READY"&&receipt.optLong("mediaEpoch",-1)==snapshot.mediaEpoch
                }==true
            }}
            val status=control.handoff.state.value
            if(status?.stage==TvHandoffStage.AUTHORIZATION)error("Explicit grant required; this test preserves the existing TV account")
            assertTrue("Transfer failed: ${status?.error}",status?.stage!=TvHandoffStage.FAILED)
            assertTrue("READY did not open the native control page",device.wait(Until.hasObject(By.text("TV 遥控")),10000))
            return remote{it.content.optJSONObject("media")?.optString("name")==name&&it.content.optJSONObject("handoff")?.optString("status")=="READY"}
        }
        val uiProbes=JSONArray()
        suspend fun clickAccessible(description:String) {
            fun target(label:String):AccessibilityNodeInfo? {
                val roots=instrumentation.uiAutomation.windows.mapNotNull{it.root}+listOfNotNull(instrumentation.uiAutomation.rootInActiveWindow)
                fun locate(node:AccessibilityNodeInfo?):AccessibilityNodeInfo? {
                    if(node==null)return null
                    if(node.packageName?.toString()==context.packageName&&node.contentDescription?.toString()==label) {
                        var action:AccessibilityNodeInfo?=node;while(action!=null&&!action.isClickable)action=action.parent
                        return action
                    }
                    for(i in 0 until node.childCount)locate(node.getChild(i))?.let{return it}
                    return null
                }
                return roots.firstNotNullOfOrNull(::locate)
            }
            uiProbes.put(JSONObject().put("action",description).put("activePackage",instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString())
                .put("phoneActionInVisibleWindows",target(description)!=null))
            if(target(description)==null)target("视频画面")?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            val node=withTimeoutOrNull(3000) {
                var found:AccessibilityNodeInfo?=target(description)
                while(found==null){delay(100);found=target(description)}
                found
            }?:error("Native UI action absent: $description")
            File(evidence,"ui-probes.json").writeText(uiProbes.toString(2))
            assertTrue("Native UI action did not run",node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        }
        try {
            withContext(Dispatchers.Main){control.startView(owner)}
            val trusted=withTimeout(15000){control.client.store.dao.devices().first().firstOrNull{it.trusted}}?:error("Previously verified pairing absent")
            val found=withTimeout(20000){control.discovery.first{it.devices.any{d->d.id==trusted.id&&d.fingerprint==trusted.fingerprint}}}.devices.first{it.id==trusted.id}
            withContext(Dispatchers.Main){control.connect(trusted.descriptor().at(found.address,found.port))}
            val initial=remote{true}
            require(initial.scene=="BROWSE"&&initial.content.optJSONObject("session")?.optString("username")==source.api.identity.username) { "Preserve an unrelated or unauthenticated TV session" }
            checks+="trusted discovery and unchanged independent TV account";record()
            withContext(Dispatchers.Main){phone.open(ResourceRef(directory,SearchResult.encodePath(directory),directory.substringAfterLast('/'),true,"",0))}
            val listing=withTimeout(30000){phone.state.first{it.path==directory&&!it.busy&&it.files.any{f->f.name==videoName}}}
            val video=listing.files.first{it.name==videoName};val image=listing.files.first{it.name==imageName}
            withContext(Dispatchers.Main){phone.open(video)}
            withTimeout(30000){phone.player.state.first{it.firstFrameRendered&&it.canSavePosition&&it.durationMs>8000}}
            withContext(Dispatchers.Main){phone.pausePlayback();phone.player.seek(5000);phone.player.rate(1.25f);phone.player.subtitle(-1)}
            withTimeout(20000){phone.player.state.first{it.canSavePosition&&!it.playing&&kotlin.math.abs(it.positionMs-5000)<1000}}
            val queueSize=phone.state.value.mediaQueue!!.items.size
            clickAccessible("在 TV 播放")
            val playing=ready(videoName)
            val playback=playing.content.getJSONObject("playback")
            assertFalse(playback.getBoolean("playing"));assertEquals(1.25,playback.getDouble("rate"),.01)
            assertTrue(kotlin.math.abs(playback.getLong("positionMs")-5000)<1000)
            assertEquals(queueSize,playing.content.getJSONObject("queue").getInt("count"))
            assertFalse(phone.player.state.value.playing)
            checks+="actual video HUD action preserves pause, source position, speed and complete queue";record()
            val captions=withTimeout(15000){control.connection.first{state->state.snapshot?.content?.optJSONObject("playback")?.optJSONArray("sidecars")?.let{rows->
                (0 until rows.length()).any{rows.getJSONObject(it).optString("name")==config.getString("subtitleName")}}==true}}.snapshot!!.content.getJSONObject("playback").getJSONArray("sidecars")
            val caption=(0 until captions.length()).map{captions.getJSONObject(it)}.first{it.getString("name")==config.getString("subtitleName")}
            command("subtitle.selectNasFile",JSONObject().put("id",caption.getString("id")))
            remote{it.content.getJSONObject("playback").let{p->!p.optBoolean("subtitleLoading")&&p.optInt("selectedSubtitle",-1)>=0}}
            device.takeScreenshot(File(evidence,"video-caption.png"))
            checks+="one ordinary NAS subtitle selected; actual rendering screenshot retained separately";record()
            device.pressBack();delay(400)
            withContext(Dispatchers.Main){phone.togglePlayback()}
            withTimeout(10000){phone.player.state.first{it.playing}}
            withContext(Dispatchers.Main){control.handoff.begin(phone,video,phone.state.value.previewScope);control.disconnect()}
            withTimeout(15000){control.handoff.state.first{it?.stage==TvHandoffStage.FAILED}}
            assertTrue("Interrupted transfer paused the phone",phone.player.state.value.playing)
            checks+="disconnect during preparation keeps local video playing";record()
            withContext(Dispatchers.Main){control.handoff.dismiss(cancelTv=false);control.reconnectNow()}
            remote{true}
            withContext(Dispatchers.Main){phone.pausePlayback();phone.leavePlayer();phone.open(image)}
            withTimeout(15000){phone.state.first{it.image?.mediaKey==image.mediaKey}}
            delay(500);clickAccessible("在 TV 播放")
            val photo=ready(imageName)
            assertEquals(phone.state.value.mediaQueue!!.items.size,photo.content.getJSONObject("queue").getInt("count"))
            assertEquals(image.mediaKey,phone.state.value.image?.mediaKey)
            device.takeScreenshot(File(evidence,"image-native.png"))
            checks+="actual image HUD handoff keeps phone image and queue; TV decode acknowledged";record()
            checks+="no new pairing, account replacement, password, local-media URL or app-data clear";record("PASS")
        } catch(failure:Throwable) {
            runCatching {
                device.dumpWindowHierarchy(File(evidence,"failure.xml"))
                File(evidence,"ui-probes.json").writeText(uiProbes.toString(2))
                withContext(Dispatchers.Main) {
                    val view=activity.window.decorView
                    val bitmap=Bitmap.createBitmap(view.width.coerceAtLeast(1),view.height.coerceAtLeast(1),Bitmap.Config.ARGB_8888)
                    val done=CompletableDeferred<Int>()
                    PixelCopy.request(activity.window,bitmap,{done.complete(it)},Handler(Looper.getMainLooper()))
                    if(withTimeoutOrNull(3000){done.await()}==PixelCopy.SUCCESS)File(evidence,"failure-phone-window.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
                    bitmap.recycle()
                }
            }
            record("FAIL");throw failure
        } finally {
            withContext(NonCancellable+Dispatchers.Main) {
                control.handoff.dismiss(cancelTv=false)
                val media=control.connection.value.snapshot?.content?.optJSONObject("media")?.optString("name")
                if(media?.startsWith("Fileway owned")==true)runCatching{control.client.execute("playback.stop")}
                control.disconnect();control.stopView(owner)
                phone.closeImage();phone.leavePlayer()
                if(phone.tvAuthorizationContext()===source&&phone.state.value.path!=original.path)phone.back()
            }
            configFile.delete()
            device.executeShellCommand("am start --windowingMode 1 -n io.github.kkwans.nasfilebrowser/.MainActivity")
        }
    }
}
