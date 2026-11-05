package io.github.kkwans.nasfilebrowser

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
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** One owned-video integration pass, separate from the isolated cache IO tests. */
@RunWith(AndroidJUnit4::class)
class TvCachePlaybackTest {
    @Test fun nativeVideoCachesSeeksAndClearsOnExit()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val input=File(context.getExternalFilesDir(null),"fileway-tv-cache-acceptance.json")
        assumeTrue("Owned fixture/current account opt-in required",input.isFile)
        val config=JSONObject(input.readText());require(config.getBoolean("useCurrentLogin"))
        val directory=config.getString("directory");val name=config.getString("videoName")
        require(directory.startsWith('/')&&name.startsWith("Fileway owned"))
        val evidence=File(context.getExternalFilesDir(null),"fileway-tv-cache-evidence").apply{mkdirs()}
        val device=UiDevice.getInstance(instrumentation)
        device.executeShellCommand("am start --windowingMode 5 -n io.github.kkwans.nasfilebrowser/.MainActivity")
        val activity=withTimeout(15000){var found:MainActivity?=null;while(found==null){
            found=withContext(Dispatchers.Main){
                (ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)+
                    ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.STARTED)).filterIsInstance<MainActivity>().firstOrNull()
            };if(found==null)delay(100)
        };found}
        val phone=withContext(Dispatchers.Main){ViewModelProvider(activity)[ClientModel::class.java]}
        val control=withContext(Dispatchers.Main){ViewModelProvider(activity)[TvControlModel::class.java]}
        val owner=Any();val original=withTimeout(30000){phone.state.first{it.connected&&!it.startupPending&&!it.busy}}
        suspend fun remote(predicate:(RemoteSnapshot)->Boolean)=withTimeout(30000){
            control.connection.first{it.phase==RemotePhase.CONNECTED&&it.snapshot?.let(predicate)==true}.snapshot!!
        }
        suspend fun command(operation:String,payload:JSONObject=JSONObject()){
            val result=withContext(Dispatchers.Main){control.client.execute(operation,payload)}
            assertTrue("Rejected $operation / ${result.code}",result.completed)
        }
        try{
            withContext(Dispatchers.Main){control.startView(owner)}
            val trusted=withTimeout(15000){control.client.store.dao.devices().first().firstOrNull{it.trusted}}?:error("Existing trusted TV required")
            val discovered=withTimeout(20000){control.discovery.first{it.devices.any{d->d.id==trusted.id&&d.fingerprint==trusted.fingerprint}}}.devices.first{it.id==trusted.id}
            withContext(Dispatchers.Main){control.connect(trusted.descriptor().at(discovered.address,discovered.port))}
            remote{it.scene=="BROWSE"}
            withContext(Dispatchers.Main){phone.open(ResourceRef(directory,SearchResult.encodePath(directory),directory.substringAfterLast('/'),true,"",0))}
            val files=withTimeout(30000){phone.state.first{it.path==directory&&!it.busy&&it.files.any{f->f.name==name}}}
            withContext(Dispatchers.Main){control.handoff.begin(phone,files.files.first{it.name==name},phone.state.value.previewScope)}
            withTimeout(125000){control.connection.first{it.snapshot?.content?.optJSONObject("handoff")?.optString("status")=="READY"}}
            assertNull(phone.state.value.selected);assertFalse(phone.player.state.value.playing)
            withContext(Dispatchers.Main){control.stopView(owner);activity.moveTaskToBack(true)}
            val ready=remote{it.content.optJSONObject("playback")?.let{p->p.optBoolean("firstFrameRendered")&&p.optBoolean("playing")&&p.optLong("positionMs")>500}==true&&
                (it.content.optJSONObject("cache")?.optLong("usedBytes")?:0)>0}
            assertTrue(device.takeScreenshot(File(evidence,"tv-cache-frame.png")))
            command("playback.seekTo",JSONObject().put("positionMs",5000))
            remote{it.content.getJSONObject("playback").let{p->!p.optBoolean("seeking")&&p.optLong("positionMs") in 5000..12000}}
            val advanced=remote{it.content.getJSONObject("playback").optLong("positionMs")>7000}
            assertEquals(ready.mediaEpoch,advanced.mediaEpoch)
            command("playback.stop")
            val stopped=remote{it.scene=="BROWSE"&&it.content.getJSONObject("cache").optLong("usedBytes")==0L}
            assertTrue(stopped.content.getJSONObject("cache").isNull("error"))
            File(evidence,"result.json").writeText(JSONObject().put("status","PASS")
                .put("scope","Xiaomi14/API36 owned native video; independent TV cache/advancing clock/seek/exit zero; no Q1, HDR, audio or full-2GiB acceptance")
                .put("cacheDuringPlayback",ready.content.getJSONObject("cache"))
                .put("cacheAfterStop",stopped.content.getJSONObject("cache")).toString(2))
        }finally{
            withContext(NonCancellable+Dispatchers.Main){
                control.handoff.dismiss(cancelTv=false)
                if(control.connection.value.snapshot?.content?.optJSONObject("media")?.optString("name")?.startsWith("Fileway owned")==true)
                    runCatching{control.client.execute("playback.stop")}
                control.disconnect();control.stopView(owner)
                if(phone.state.value.path!=original.path)phone.back()
            }
            input.delete()
            device.executeShellCommand("am start --windowingMode 1 -n io.github.kkwans.nasfilebrowser/.MainActivity")
        }
    }
}
