package io.github.kkwans.nasfilebrowser

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.UiDevice
import io.github.kkwans.fileway.remote.*
import io.github.kkwans.nasfilebrowser.app.*
import io.github.kkwans.nasfilebrowser.data.SearchResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** One opt-in TV UI journey. Phone never opens its local player; TV streams independently. */
@RunWith(AndroidJUnit4::class)
class TvPlayerUiTest {
    @Test fun nativeTvControlsMenusAndFocus()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        val configFile=File(context.getExternalFilesDir(null),"fileway-tv-player-ui-acceptance.json")
        assumeTrue("Owned fixtures and current account opt-in required",configFile.isFile)
        val config=JSONObject(configFile.readText());require(config.getBoolean("useCurrentLogin"))
        val directory=config.getString("directory");val videoName=config.getString("videoName")
        require(directory.startsWith('/')&&videoName.startsWith("Fileway owned"))
        val evidence=File(context.getExternalFilesDir(null),"fileway-tv-player-ui-evidence").apply{mkdirs()}
        val checks=mutableListOf<String>();var step="setup"
        fun record(status:String="RUNNING")=File(evidence,"result.json").writeText(JSONObject().put("status",status).put("step",step).put("checks",JSONArray(checks))
            .put("scope","Xiaomi14 separate TV/phone processes; native TV HUD/menu/key/focus and one ordinary subtitle; no Q1/HDR/audio acceptance").toString(2))
        val device=UiDevice.getInstance(instrumentation)
        device.executeShellCommand("am start --windowingMode 5 -n io.github.kkwans.nasfilebrowser/.MainActivity")
        val activity=withTimeout(15000){var found:MainActivity?=null;while(found==null){found=withContext(Dispatchers.Main){
            (ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)+ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.STARTED)).filterIsInstance<MainActivity>().firstOrNull()};if(found==null)delay(100)};found}
        val phone=withContext(Dispatchers.Main){ViewModelProvider(activity)[ClientModel::class.java]}
        val control=withContext(Dispatchers.Main){ViewModelProvider(activity)[TvControlModel::class.java]};val owner=Any()
        val original=withTimeout(30000){phone.state.first{it.connected&&!it.startupPending&&!it.busy}}
        val source=withContext(Dispatchers.Main){phone.tvAuthorizationContext()}?:error("Current phone login required")
        var originalScale:Double?=null
        suspend fun remote(predicate:(RemoteSnapshot)->Boolean={true})=withTimeout(30000){control.connection.first{it.phase==RemotePhase.CONNECTED&&it.snapshot?.let(predicate)==true}.snapshot!!}
        suspend fun command(operation:String,payload:JSONObject=JSONObject()) {
            val result=withContext(Dispatchers.Main){control.client.execute(operation,payload)}
            assertTrue("Rejected $operation / ${result.code}",result.completed)
        }
        suspend fun key(value:String){command("app.key",JSONObject().put("key",value));delay(180)}
        fun node(label:String,actionable:Boolean=false):AccessibilityNodeInfo? {
            if(Build.VERSION.SDK_INT>=34)instrumentation.uiAutomation.clearCache()
            val top=instrumentation.uiAutomation.windows.filter{it.root?.packageName?.toString()=="io.github.kkwans.fileway.tv"}.maxByOrNull{it.layer}?.root
            fun find(value:AccessibilityNodeInfo?):AccessibilityNodeInfo? {
                if(value==null)return null
                if(value.isVisibleToUser&&(value.text?.toString()==label||value.contentDescription?.toString()==label)) {
                    if(!actionable)return value
                    var target:AccessibilityNodeInfo?=value
                    while(target!=null&&!target.isClickable&&target.rangeInfo==null)target=target.parent
                    if(target!=null)return target
                }
                for(index in 0 until value.childCount)find(value.getChild(index))?.let{return it}
                return null
            }
            return find(top)
        }
        suspend fun visible(label:String,actionable:Boolean=false)=withTimeout(10000){var found=node(label,actionable);while(found==null){delay(100);found=node(label,actionable)};found}
        suspend fun focused(label:String) {
            withTimeout(10000){while(node(label,true)?.isFocused!=true)delay(100)}
        }
        suspend fun click(label:String){assertTrue("Native UI action missing: $label",visible(label,true).performAction(AccessibilityNodeInfo.ACTION_CLICK));delay(250)}
        suspend fun move(label:String,direction:String,maximum:Int=10) {
            repeat(maximum){if(node(label,true)?.isFocused==true)return;key(direction)}
            focused(label)
        }
        suspend fun back(){command("app.back");delay(250)}
        fun capture(name:String){device.takeScreenshot(File(evidence,name))}
        try {
            withContext(Dispatchers.Main){control.startView(owner)}
            val trusted=withTimeout(15000){control.client.store.dao.devices().first().firstOrNull{it.trusted}}?:error("Existing trusted TV required")
            val found=withTimeout(20000){control.discovery.first{it.devices.any{d->d.id==trusted.id&&d.fingerprint==trusted.fingerprint}}}.devices.first{it.id==trusted.id}
            withContext(Dispatchers.Main){control.connect(trusted.descriptor().at(found.address,found.port))}
            val initial=remote();require(initial.scene=="BROWSE"&&initial.content.optJSONObject("session")?.optString("username")==source.api.identity.username)
            originalScale=initial.content.getJSONObject("playback").getJSONObject("subtitleAppearance").getDouble("scale")
            withContext(Dispatchers.Main){phone.open(ResourceRef(directory,SearchResult.encodePath(directory),directory.substringAfterLast('/'),true,"",0))}
            val listing=withTimeout(30000){phone.state.first{it.path==directory&&!it.busy&&it.files.any{f->f.name==videoName}}}
            withContext(Dispatchers.Main){control.handoff.begin(phone,listing.files.first{it.name==videoName},phone.state.value.previewScope)}
            val ready=withTimeout(125000){control.connection.first{state->state.snapshot?.let{s->s.content.optJSONObject("media")?.optString("name")==videoName&&s.content.optJSONObject("handoff")?.optString("status")=="READY"}==true}}.snapshot!!
            assertNull("Phone must not open a local video for TV playback",phone.state.value.selected)
            assertFalse(phone.player.state.value.playing)
            withContext(Dispatchers.Main){control.stopView(owner);activity.moveTaskToBack(true)}
            step="independent TV playback";record()
            remote{it.content.getJSONObject("playback").optBoolean("playing")}
            command("playback.pause");remote{!it.content.getJSONObject("playback").optBoolean("playing")}
            key("ok");remote{it.content.getJSONObject("playback").optBoolean("playing")}
            assertNull("Hidden confirmation must not open the HUD",node("播放进度"))
            key("ok");remote{!it.content.getJSONObject("playback").optBoolean("playing")}
            checks+="TV plays with phone backgrounded and without a phone player; hidden OK only toggles playback";record()
            step="HUD";key("down");visible("播放进度",true);focused("播放")
            val timeline=Rect().also(visible("播放进度",true)::getBoundsInScreen)
            assertTrue("Playback HUD still occupies the middle of the picture",timeline.top>device.displayHeight*.6f)
            capture("tv-hud.png")
            step="subtitle hierarchy";move("字幕","right");key("ok");visible("字幕轨道")
            capture("tv-subtitles.png")
            move("字幕外观","up");key("ok");visible("字号",true)
            key("right")
            val wanted=(originalScale!!+.1).coerceAtMost(2.0)
            remote{kotlin.math.abs(it.content.getJSONObject("playback").getJSONObject("subtitleAppearance").getDouble("scale")-wanted)<.03}
            capture("tv-subtitle-appearance.png")
            move("字体","down",8);key("ok");visible("字幕字体")
            back();focused("字体")
            back();focused("字幕外观")
            back();focused("字幕")
            checks+="subtitle appearance/font hierarchy and parent/HUD focus restore; scale reaches real TV state";record()
            step="speed and queue";move("${ready.content.getJSONObject("playback").optDouble("rate",1.0)}×","right");key("ok");visible("播放速度")
            move("1.25×","down",5);key("ok");remote{kotlin.math.abs(it.content.getJSONObject("playback").optDouble("rate")-1.25)<.01}
            focused("1.25×")
            move("列表","right");key("ok");visible("播放列表 · ${ready.content.getJSONObject("queue").getInt("count")}")
            capture("tv-queue.png");back();focused("列表")
            step="ordinary subtitle via TV menu";move("字幕","left");key("ok");move(config.getString("subtitleName"),"down",10);key("ok")
            remote{it.content.getJSONObject("playback").let{p->!p.optBoolean("subtitleLoading")&&p.optInt("selectedSubtitle",-1)>=0}}
            command("playback.seekTo",JSONObject().put("positionMs",5000));remote{it.content.getJSONObject("playback").let{p->!p.optBoolean("seeking")&&kotlin.math.abs(p.optLong("positionMs")-5000)<1000}}
            assertEquals("Menus must not replace the media source",ready.mediaEpoch,remote().mediaEpoch)
            capture("tv-caption.png")
            checks+="TV speed/queue controls and ordinary NAS subtitle selected without replacing source media";record()
            step="timeline remote seek";move("1.25×","right");key("up");focused("播放进度")
            val before=remote().content.getJSONObject("playback").getLong("positionMs")
            val seconds=remote().content.getJSONObject("preferences").optInt("seekStepSeconds",10)
            key("right");remote{it.content.getJSONObject("playback").let{p->!p.optBoolean("seeking")&&kotlin.math.abs(p.optLong("positionMs")-(before+seconds*1000L))<1200}}
            step="return behavior";back();visible("再按一次返回退出播放")
            back();remote{it.scene=="BROWSE"}
            checks+="focused timeline uses configured remote seek; back hides HUD with prompt and second back exits";record("PASS")
        }catch(failure:Throwable){runCatching{device.dumpWindowHierarchy(File(evidence,"failure.xml"));capture("failure.png")};record("FAIL");throw failure}
        finally {
            withContext(NonCancellable+Dispatchers.Main) {
                control.handoff.dismiss(cancelTv=false)
                val snapshot=control.connection.value.snapshot
                if(snapshot!=null) {
                    originalScale?.let{value->runCatching{control.client.execute("subtitle.setAppearance",JSONObject().put("scale",value))}}
                    if(snapshot.content.optJSONObject("media")?.optString("name")?.startsWith("Fileway owned")==true)runCatching{control.client.execute("playback.stop")}
                }
                control.disconnect();control.stopView(owner)
                if(phone.tvAuthorizationContext()===source&&phone.state.value.path!=original.path)phone.back()
            }
            configFile.delete()
            device.executeShellCommand("am start --windowingMode 1 -n io.github.kkwans.nasfilebrowser/.MainActivity")
        }
    }
}
