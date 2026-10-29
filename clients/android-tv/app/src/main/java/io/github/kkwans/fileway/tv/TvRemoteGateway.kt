package io.github.kkwans.fileway.tv

import android.content.Context
import android.view.KeyEvent
import io.github.kkwans.fileway.remote.*
import io.github.kkwans.nasfilebrowser.app.*
import io.github.kkwans.nasfilebrowser.data.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Runs on Main and shares the model/player's public actions with touch and
 * D-pad UI. LAN clients never get to choose a URL, token, lease or filesystem. */
class TvRemoteGateway(private val model:TvModel,context:Context){
    private val authorization=DeviceAuthorization(context)
    private var pending:DeviceGrantRequest?=null
    var key:((Int)->Unit)?=null
    var back:(()->Unit)?=null
    var input:((String)->Unit)?=null
    val imageTransform=MutableStateFlow(JSONObject().put("revision",0))
    private var imageRevision=0L
    private fun resourceId(file:ResourceRef)=MessageDigest.getInstance("SHA-256").digest(file.mediaKey.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
    private fun fileJson(file:ResourceRef)=JSONObject().put("id",resourceId(file)).put("name",file.name.take(120)).put("directory",file.directory).put("kind",file.mediaKind()?.name?:"FILE").put("size",file.size)
    private fun files():List<ResourceRef> = when(model.state.value.route){
        TvRoute.HOME->model.state.value.rootEntries
        TvRoute.RECENT->model.state.value.recentPlayback.map{ResourceRef(it.path,it.wirePath,it.name,false,"",0)}
        TvRoute.FAVORITES->model.favorites.state.value.items.filter{it.openable}.map{ResourceRef(it.path,it.wirePath,it.name,false,"",0)}
        else->model.visibleFiles()
    }
    private fun find(id:String)=files().firstOrNull{resourceId(it)==id}?:error("文件列表已变化，请刷新")
    fun snapshot():JSONObject{
        val ui=model.state.value;val media=model.media.state.value;val player=model.media.player.state.value
        val scene=if(!ui.connected)"LOGIN"else media.file?.mediaKind()?.name?.takeIf{it in setOf("VIDEO","AUDIO","IMAGE")}?:"BROWSE"
        val content=JSONObject().put("route",ui.route.name).put("directory",ui.path.take(400)).put("busy",ui.busy||media.busy)
            .put("connected",ui.connected).put("error",media.error?:player.error?:ui.error?:JSONObject.NULL)
            .put("notice",media.notice?:player.operationError?:ui.notice?:JSONObject.NULL)
            .put("files",JSONArray(files().take(24).map(::fileJson))).put("total",files().size)
            .put("preferences",JSONObject().put("dark",ui.dark).put("gridColumns",ui.gridColumns).put("safePercent",ui.safePercent).put("clockAlways",ui.clockAlways).put("speedAlways",ui.speedAlways))
        model.context?.let{content.put("session",JSONObject().put("origin",it.profile.address.take(512)).put("username",it.api.identity.username.take(120)))}
        media.file?.let{content.put("media",fileJson(it))}
        content.put("playback",JSONObject().put("playing",player.playing).put("positionMs",model.media.sourcePosition()).put("durationMs",media.sourceDurationMs)
            .put("positionLocalMs",player.positionMs).put("sourceOffsetMs",media.sourceOffsetMs).put("sdrActive",media.compatible)
            .put("bufferedMs",player.bufferedPositionMs+media.sourceOffsetMs).put("volume",player.volume).put("rate",player.rate).put("seekable",player.seekable)
            .put("firstFrameRendered",player.firstFrameRendered).put("audioDecoder",player.audioDecoder.take(120)).put("subtitleLoading",player.subtitleLoading).put("waitingForBuffer",player.waitingForBuffer)
            .put("audioRenderedBuffers",player.audioRenderedBuffers).put("audioSkippedBuffers",player.audioSkippedBuffers)
            .put("hdrMode",media.mode.name).put("sdrAvailable",media.sdrAvailable).put("subtitleDelayMs",player.subtitleDelayMs)
            .put("audio",JSONArray(if(media.compatible)media.sourceAudio.take(16).map{JSONObject().put("id",it.index).put("title",it.title.take(120)).put("codec",it.codec.take(40)).put("language",it.language.take(40)).put("source",true)} else player.audio.take(16).map{JSONObject().put("id",it.id).put("title",it.title.take(120)).put("codec",it.codec.take(40)).put("language",it.language.take(40)).put("source",false)}))
            .put("subtitles",JSONArray(player.subtitles.take(16).map{JSONObject().put("id",it.id).put("title",it.title.take(120))}))
            .put("sourceSubtitles",JSONArray(media.sourceText.takeIf{media.sourceSubtitles}.orEmpty().take(16).map{JSONObject().put("id",it.index).put("title",it.title.take(120)).put("language",it.language.take(40))}))
            .put("sidecars",JSONArray(media.sidecars.take(8).map(::fileJson))).put("selectedAudio",if(media.compatible)media.selectedSourceAudio?:-1 else player.selectedAudio)
            .put("selectedSubtitle",player.selectedSubtitle).put("selectedSourceSubtitle",media.selectedSourceSubtitle))
        media.queue?.let{q->content.put("queue",JSONObject().put("index",q.index).put("count",q.items.size).put("items",JSONArray(q.items.take(8).map(::fileJson))))}
        return JSONObject().put("sessionEpoch",model.sessionEpoch).put("mediaEpoch",model.media.controlEpoch).put("scene",scene)
            .put("capabilities",JSONObject().put("navigation",true).put("input",true).put("deviceAuth",true).put("media",true)).put("content",content)
    }
    suspend fun execute(command:JSONObject):JSONObject{
        val operation=command.getString("operation");val p=command.getJSONObject("payload")
        check(command.getLong("sessionEpoch")==model.sessionEpoch){"TV账号或服务器已变化，请刷新"}
        if(operation.startsWith("playback.")||operation.startsWith("subtitle.")||operation.startsWith("audio.")||operation.startsWith("queue.")||operation.startsWith("image.")){
            check(command.getLong("mediaEpoch")==model.media.controlEpoch){"播放内容已变化，请刷新"}
        }
        val player=model.media.player
        when(operation){
            "session.requestGrant"->{
                val request=authorization.request(p.getString("origin"),p.getJSONObject("instance"),command.getString("controllerId"))
                check(model.sessionEpoch==command.getLong("sessionEpoch")&&model.remote.authorized(command)){"TV登录或控制授权已变化"}
                pending=request
                return JSONObject().put("request",request.request).put("instance",request.instance)
            }
            "session.adopt"->{
                val request=pending?:error("授权请求已失效，请重新授权")
                check(request.controllerId==command.getString("controllerId")&&request.request.getString("id")==p.getString("requestId")){"授权请求不匹配"}
                check(request.request.getLong("expiresAt")*1000>System.currentTimeMillis()){"授权请求已过期"}
                val credential=authorization.exchange(request)
                model.adoptDeviceCredential(credential){model.sessionEpoch==command.getLong("sessionEpoch")&&model.remote.authorized(command)};pending=null
            }
            "session.cancelSwitch"->pending=null
            "app.navigate"->{model.navigate(TvRoute.valueOf(p.getString("route")))}
            "app.list"->{val start=p.optInt("offset").coerceAtLeast(0);val count=p.optInt("count",32).coerceIn(1,32);return JSONObject().put("files",JSONArray(files().drop(start).take(count).map(::fileJson))).put("total",files().size)}
            "app.back"->(back?:error("TV界面尚未就绪"))()
            "app.key"->{val code=when(p.getString("key")){"up"->KeyEvent.KEYCODE_DPAD_UP;"down"->KeyEvent.KEYCODE_DPAD_DOWN;"left"->KeyEvent.KEYCODE_DPAD_LEFT;"right"->KeyEvent.KEYCODE_DPAD_RIGHT;"ok"->KeyEvent.KEYCODE_DPAD_CENTER;else->error("按键不支持")};(key?:error("TV界面尚未就绪"))(code)}
            "app.inputText"->{val text=p.getString("text");require(text.length<=4096){"输入过长"};if(p.optString("target")=="search")model.search(text,SearchScope.valueOf(p.optString("scope","GLOBAL")))else (input?:error("请先选择TV中的输入框"))(text)}
            "app.setPreference"->when(p.getString("key")){
                "dark"->model.setDark(p.getBoolean("value"));"gridColumns"->model.setColumns(p.getInt("value"));"safePercent"->model.setSafePercent(p.getInt("value"))
                "clockAlways"->model.setClockAlways(p.getBoolean("value"));"speedAlways"->model.setSpeedAlways(p.getBoolean("value"))
                "cacheMiB"->{val limit=p.getLong("value");require(limit in 64..65536);model.media.cache.setLimit(limit*1048576)}
                else->error("设置不支持")
            }
            "media.open","image.open"->model.open(find(p.getString("id")),files())
            "playback.play"->if(!player.state.value.playing)player.toggle()
            "playback.pause"->player.pause()
            "playback.stop"->model.media.closeAndSave()
            "playback.seekTo"->model.media.seek(p.getLong("positionMs"))
            "playback.seekBy"->{val delta=p.getLong("deltaMs");require(delta in -3600000..3600000);model.media.seekBy(delta)}
            "playback.setRate"->{val rate=p.getDouble("rate").toFloat();require(rate.isFinite()&&rate in .1f..5f);player.rate(rate)}
            "playback.setVolume"->{val volume=p.getInt("volume");require(volume in 0..100);player.volume(volume)}
            "playback.setHdrMode"->model.media.switchMode(TvHdrMode.valueOf(p.getString("mode")))
            "audio.select"->{val id=p.getInt("id");if(p.optBoolean("source")){check(model.media.state.value.compatible&&model.media.state.value.sourceAudio.any{it.index==id}){"音轨已变化"};model.media.selectSourceAudio(id)}else{check(id==-1||player.state.value.audio.any{it.id==id}){"音轨已变化"};model.media.selectAudio(id)}}
            "subtitle.disable"->model.media.subtitlesOff()
            "subtitle.select"->{val id=p.getInt("id");if(p.optBoolean("source")){check(model.media.state.value.sourceText.any{it.index==id});model.media.selectSourceSubtitle(id)}else{check(player.state.value.subtitles.any{it.id==id});player.subtitle(id)}}
            "subtitle.selectNasFile"->{val file=model.media.state.value.sidecars.firstOrNull{resourceId(it)==p.getString("id")}?:error("字幕列表已变化");model.media.selectExternalSubtitle(file)}
            "subtitle.setDelay"->{val delay=p.getLong("delayMs");require(delay in -600000..600000);player.subtitleDelay(delay)}
            "subtitle.setAppearance"->{val old=model.media.playbackPreferences.textSubtitleAppearance.value;model.media.updateSubtitleAppearance(old.copy(scale=p.optDouble("scale",old.scale.toDouble()).toFloat(),bottomPadding=p.optDouble("bottomPadding",old.bottomPadding.toDouble()).toFloat(),backgroundOpacity=p.optDouble("backgroundOpacity",old.backgroundOpacity.toDouble()).toFloat()))}
            "queue.next","image.next"->model.media.navigateQueue(1)
            "queue.previous","image.previous"->model.media.navigateQueue(-1)
            "queue.select","image.selectIndex"->{val q=model.media.state.value.queue?:error("队列为空");val index=p.getInt("index");val selected=q.select(index)?:error("队列已变化");model.open(selected.current,selected.items)}
            "queue.set"->{val ids=p.getJSONArray("ids");require(ids.length() in 1..100);val items=(0 until ids.length()).map{find(ids.getString(it))};val index=p.optInt("index");require(index in items.indices);model.open(items[index],items)}
            "image.setTransform"->{val scale=p.getDouble("scale").toFloat();val x=p.optDouble("x").toFloat();val y=p.optDouble("y").toFloat();require(scale.isFinite()&&scale in 1f..3f&&x.isFinite()&&y.isFinite()&&x in -1f..1f&&y in -1f..1f);if(scale>1)model.media.requestOriginalImage();imageTransform.value=JSONObject().put("revision",++imageRevision).put("scale",scale).put("x",x).put("y",y)}
            else->error("此操作尚不支持")
        }
        return JSONObject().put("applied",true)
    }
}
