package io.github.kkwans.nasfilebrowser

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kkwans.fileway.remote.*
import io.github.kkwans.nasfilebrowser.data.*
import io.github.kkwans.nasfilebrowser.ui.tvQrFromImage
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit opt-in only, with both real installed APKs. No app data clear,
 * fixture credential asset, media URL injection or private token export.
 * The phone-side client runs in this instrumentation's real phone UID while
 * the separate TV process remains visible, so one physical device is enough. */
@RunWith(AndroidJUnit4::class)
class TvCompanionControlTest {
    @Test fun pairAuthorizeBrowsePlayAndReconnect()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val configFile=File(context.getExternalFilesDir(null),"fileway-companion-acceptance.json")
        assumeTrue("Explicit companion acceptance configuration required",configFile.isFile)
        val config=JSONObject(configFile.readText())
        assumeTrue("Explicit use of the phone's current login required",config.optBoolean("useCurrentLogin"))
        val qrInput=File(context.getExternalFilesDir(null),"fileway-companion-qr.png")
        val resume=config.optBoolean("resume")
        assumeTrue("Driver must capture the actual foreground TV QR first",resume||qrInput.isFile)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val client=TvRemoteClient(context,scope)
        val store=ProfileStore(ClientDatabase.get(context),CredentialVault(context))
        val active=store.active()?:error("Phone has no active login")
        assertTrue("Physical TV requires a reachable direct origin",active.first.network==ConnectionMode.DIRECT)
        val source=restoreSavedSession(store,active.first,active.second).api
        val evidence=File(context.getExternalFilesDir(null),"fileway-companion-evidence").apply{mkdirs()}
        val steps=mutableListOf<String>()
        var deviceId="";var userHash=""
        fun record(step:String){steps+=step;File(evidence,"result.json").writeText(JSONObject().put("steps",JSONArray(steps)).put("deviceId",deviceId).put("userHash",userHash).put("scope","Real separate phone/TV processes on one physical Android device; no projection/HDR/audibility acceptance").toString(2))}
        suspend fun state(predicate:(RemoteSnapshot)->Boolean):RemoteSnapshot=withTimeout(25000){client.state.first{it.phase==RemotePhase.CONNECTED&&it.snapshot?.let(predicate)==true}.snapshot!!}
        suspend fun command(operation:String,payload:JSONObject=JSONObject()):JSONObject?{
            val result=client.execute(operation,payload)
            assertTrue("TV rejected $operation (${result.status}/${result.code}): ${result.payload?.optString("message")}",result.completed)
            return result.payload
        }
        suspend fun waitFileList():RemoteSnapshot=state{it.scene=="BROWSE"&&!it.content.optBoolean("busy")}
        fun rows(snapshot:RemoteSnapshot)=snapshot.content.getJSONArray("files")
        fun find(rows:JSONArray,name:String):JSONObject?=(0 until rows.length()).map{rows.getJSONObject(it)}.firstOrNull{it.optString("name")==name}
        suspend fun findCurrent(snapshot:RemoteSnapshot,name:String):JSONObject?{
            find(rows(snapshot),name)?.let{return it}
            var offset=0
            while(offset<snapshot.content.optInt("total")&&offset<10000){
                val page=command("app.list",JSONObject().put("offset",offset).put("count",32))!!.getJSONArray("files")
                find(page,name)?.let{return it}
                if(page.length()==0)break
                offset+=page.length()
            }
            return null
        }
        try {
            val trusted=if(resume)client.store.dao.devices().first().firstOrNull{it.trusted}?.descriptor()?:error("Previously verified pairing missing") else TvInvitation.parse(tvQrFromImage(context,Uri.fromFile(qrInput))).device
            deviceId=trusted.id
            // Discovery is checked against the independently QR-trusted key.
            val discovered=withTimeout(15000){TvServiceDiscovery(context).scan().first{scan->scan.devices.any{it.id==trusted.id&&it.fingerprint==trusted.fingerprint}}}.devices.first{it.id==trusted.id}
            if(resume)client.connect(trusted.at(discovered.address,discovered.port)) else client.pair(TvInvitation.parse(tvQrFromImage(context,Uri.fromFile(qrInput))))
            val initial=state{true}
            record(if(resume)"reconnected previously verified durable pairing via real DNS-SD" else "physical DNS-SD, actual QR decode, pinned TLS, durable pairing and WSS snapshot")
            assertTrue("Refuse replacing an unrelated active TV session",initial.scene=="LOGIN"||initial.scene=="BROWSE"&&initial.content.getJSONObject("session").optString("username")==source.identity.username)
            if(initial.scene=="LOGIN"){
            val instance=source.request("GET","/api/instance")
            val request=command("session.requestGrant",JSONObject().put("origin",active.first.address).put("instance",instance))!!.getJSONObject("request")
            assertEquals(trusted.id,request.getString("deviceId"))
            source.action("POST","/api/device-auth/requests/${request.getString("id")}/approve",JSONObject().put("deviceId",trusted.id).put("publicKey",request.getString("publicKey")))
            command("session.adopt",JSONObject().put("requestId",request.getString("id")))
            }
            val bound=state{it.scene=="BROWSE"&&it.content.optBoolean("connected")}
            assertTrue("TV did not adopt the approving phone account",bound.content.getJSONObject("session").getString("username")==source.identity.username)
            userHash=hash(source.identity.username)
            record("server-approved independent TV login")

            command("app.navigate",JSONObject().put("route","FILES"))
            var listing=waitFileList()
            // Only traverse the explicitly supplied owned fixture, no NAS-wide scan.
            val segments=config.getJSONArray("fixtureSegments")
            findCurrent(listing,"volume2")?.let{volume->command("media.open",JSONObject().put("id",volume.getString("id")));listing=state{it.content.optString("directory").endsWith("/volume2")&&!it.content.optBoolean("busy")}}
            for(index in 0 until segments.length()){
                val segment=segments.getString(index)
                val folder=findCurrent(listing,segment)
                check(folder!=null&&folder.getBoolean("directory")){"Owned fixture directory $segment missing under ${listing.content.optString("directory")}"}
                val previous=listing.content.getString("directory")
                command("media.open",JSONObject().put("id",folder.getString("id")))
                listing=state{it.scene=="BROWSE"&&!it.content.optBoolean("busy")&&it.content.optString("directory")!=previous}
            }
            record("remote navigation and opaque resource lookup")
            command("app.inputText",JSONObject().put("target","search").put("scope","CURRENT").put("text",config.getString("videoName")))
            listing=state{it.scene=="BROWSE"&&!it.content.optBoolean("busy")&&find(rows(it),config.getString("videoName"))!=null}
            val movie=find(rows(listing),config.getString("videoName"))!!
            command("media.open",JSONObject().put("id",movie.getString("id")))
            state{it.scene=="VIDEO"&&it.content.getJSONObject("playback").optBoolean("firstFrameRendered")}
            command("playback.pause")
            state{!it.content.getJSONObject("playback").optBoolean("playing")}
            command("playback.seekTo",JSONObject().put("positionMs",6000))
            state{val p=it.content.getJSONObject("playback");!p.optBoolean("waitingForBuffer")&&kotlin.math.abs(p.optLong("positionMs")-6000)<1000}
            command("playback.setRate",JSONObject().put("rate",1.25))
            command("playback.setVolume",JSONObject().put("volume",37))
            state{val p=it.content.getJSONObject("playback");p.optInt("volume")==37&&p.optDouble("rate")==1.25}
            record("real video first frame, pause, source-time seek, speed and one-percent volume")
            val audio=state{it.content.getJSONObject("playback").getJSONArray("audio").length()>1}.content.getJSONObject("playback").getJSONArray("audio")
            val selected=(0 until audio.length()).map{audio.getJSONObject(it)}.first{it.getInt("id")>=0}
            command("audio.select",JSONObject().put("id",selected.getInt("id")))
            state{val p=it.content.getJSONObject("playback");p.optInt("selectedAudio")==selected.getInt("id")&&p.optString("audioDecoder")!="未知"}
            val sidecars=state{it.content.getJSONObject("playback").getJSONArray("sidecars").length()>0}.content.getJSONObject("playback").getJSONArray("sidecars")
            val caption=find(sidecars,config.getString("subtitleName"))?:error("Owned external subtitle missing")
            command("subtitle.selectNasFile",JSONObject().put("id",caption.getString("id")))
            state{val p=it.content.getJSONObject("playback");p.optInt("selectedSubtitle",-1)>=0&&!p.optBoolean("subtitleLoading")}
            command("subtitle.setDelay",JSONObject().put("delayMs",250))
            state{it.content.getJSONObject("playback").optLong("subtitleDelayMs")==250L}
            command("subtitle.disable")
            state{it.content.getJSONObject("playback").optInt("selectedSubtitle",-1)==-1}
            record("actual audio selection/decoder and external subtitle select/delay/off states; not audibility or styled-pixel proof")
            command("playback.play")
            val beforeDisconnect=state{it.content.getJSONObject("playback").optBoolean("playing")}
            val reconnect=client.store.dao.device(trusted.id)!!.descriptor()
            client.disconnect();source.close();delay(1200)
            client.connect(reconnect)
            val afterDisconnect=state{it.scene=="VIDEO"&&it.content.getJSONObject("playback").optBoolean("playing")}
            assertTrue("Phone disconnect changed TV session",afterDisconnect.sessionEpoch==beforeDisconnect.sessionEpoch)
            assertTrue("TV did not continue independently",afterDisconnect.content.getJSONObject("playback").getLong("positionMs")>beforeDisconnect.content.getJSONObject("playback").getLong("positionMs"))
            record("phone transport/session closed; TV keeps independent playback and reconnects")
            command("playback.stop")
            state{it.scene=="BROWSE"}
            record("remote close returns to browsing")
        } finally {
            client.disconnect();scope.cancel()
            withContext(NonCancellable){runCatching{source.close()}}
            // Keep the actual trusted pair/login as product state; remove only
            // the one-time opt-in config, never user accounts or media.
            configFile.delete()
        }
    }

    @Test fun reconnectSavedTvAfterRestart()=runBlocking{
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val configFile=File(context.getExternalFilesDir(null),"fileway-companion-acceptance.json")
        assumeTrue("Explicit restart acceptance configuration required",configFile.isFile)
        val config=JSONObject(configFile.readText())
        assumeTrue(config.optBoolean("restartOnly"))
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val client=TvRemoteClient(context,scope)
        try{
            // Deliberately never read ProfileStore, JWT or password in this run.
            val saved=client.store.dao.device(config.getString("deviceId"))?:error("Saved phone pairing missing")
            val trusted=saved.descriptor()
            val restored=withTimeout(20000){TvServiceDiscovery(context).scan().first{scan->scan.devices.any{it.id==trusted.id&&it.fingerprint==trusted.fingerprint}}}.devices.first{it.id==trusted.id}
            client.connect(trusted.at(restored.address,restored.port))
            val snapshot=withTimeout(25000){client.state.first{it.phase==RemotePhase.CONNECTED&&it.snapshot?.let{value->value.scene=="BROWSE"&&value.content.optBoolean("connected")}==true}.snapshot!!}
            assertTrue("TV restored a different account",hash(snapshot.content.getJSONObject("session").getString("username"))==config.getString("userHash"))
            File(context.getExternalFilesDir(null),"fileway-companion-evidence/restart.json").writeText(JSONObject().put("status","PASS").put("scope","Real TV process restart and own refresh credential; phone run reads only paired-device identity, no NAS token/password").toString(2))
        }finally{client.disconnect();scope.cancel();configFile.delete()}
    }
    private fun hash(value:String)=java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
}
