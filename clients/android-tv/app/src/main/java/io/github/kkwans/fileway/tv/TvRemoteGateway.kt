package io.github.kkwans.fileway.tv

import android.content.Context
import android.os.SystemClock
import android.view.KeyEvent
import androidx.lifecycle.viewModelScope
import io.github.kkwans.fileway.remote.*
import io.github.kkwans.nasfilebrowser.app.*
import io.github.kkwans.nasfilebrowser.data.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Runs on Main and shares the model/player's public actions with touch and
 * D-pad UI. LAN clients never get to choose a URL, token, lease or filesystem. */
class TvRemoteGateway(private val model:TvModel,context:Context){
    private val browser = TvRemoteBrowse(model)
    private val handoff=TvMediaHandoff(model)
    private val authorization=DeviceAuthorization(context)
    private val subtitleBrowser = TvRemoteSubtitleBrowser(
        scope = model.viewModelScope,
        source = ::subtitleSource,
        authorize = { peer -> model.remote.authorized(JSONObject().put("controllerId", peer.controller).put("connectionId", peer.connection)) },
        elapsed = SystemClock::elapsedRealtime,
        prepare = { entry, epoch, selection, authorized ->
            model.media.prepareRemoteScopedSubtitle(entry, epoch, selection, authorized)?.let { operation ->
                TvRemoteSubtitleBrowser.Import({ operation.result.await() }, operation::cancel)
            }
        },
    )
    private fun subtitleSource(): TvRemoteSubtitleBrowser.Source? {
        val (owner, file) = model.media.serverSubtitleSource() ?: return null
        if (owner !== model.context || owner.api.sessionHealth.value?.code in setOf(SessionHealthCode.EXPIRED, SessionHealthCode.ACCOUNT_CHANGED)) return null
        val account = owner.account.key
        val storage = model.media.localSubtitles
        val local = TvRemoteSubtitleBrowser.LocalSource(
            revision = storage::remoteRevision,
            list = { node, offset, anchor ->
                if (node == null) {
                    val roots = storage.remoteRoots(account).sortedWith(compareBy({ it.label }, { it.id }))
                    if (offset > 0 && roots.getOrNull(offset - 1)?.key != anchor)
                        throw TvLocalSubtitleFailure("电视目录已变化，请重新读取", TvLocalSubtitleIssue.DIRECTORY_CHANGED)
                    val rows = roots.drop(offset).take(32)
                    val next = offset + rows.size
                    val after = (offset + 1..next).toList()
                    TvRemoteSubtitleBrowser.LocalPage(rows, after, next.takeIf { it < roots.size },
                        after.zip(rows) { cursor, row -> cursor to row.key }.toMap())
                } else {
                    val page = storage.remotePage(account, node as TvLocalSubtitleStorage.Node, offset, anchor)
                    TvRemoteSubtitleBrowser.LocalPage(page.rows, page.afterRow, page.next, page.anchors)
                }
            },
            verify = { storage.remoteReadableUri(account, it as TvLocalSubtitleStorage.Node) },
            prepare = { node, epoch, selection, authorized ->
                model.media.prepareRemoteLocalSubtitle(node as TvLocalSubtitleStorage.Node, epoch, selection, authorized)?.let { operation ->
                    TvRemoteSubtitleBrowser.Import({ operation.result.await() }, operation::cancel)
                }
            },
        )
        return TvRemoteSubtitleBrowser.Source(owner, owner.api.identity.id, model.sessionEpoch, model.media.controlEpoch,
            model.media.player.state.value.mediaGeneration, file, model.media.localSubtitleFence()?.selection, local,
            scoped = if (model.media.state.value.scopedSubtitlePages) ({ ScopedSubtitleResources(owner.api) }) else null)
    }
    private var pending:DeviceGrantRequest?=null
    var key:((Int)->Unit)?=null
    var back:(()->Unit)?=null
    var input:((String)->Unit)?=null
    val imageTransform=MutableStateFlow(JSONObject().put("revision",0))
    private var imageEpoch=-1L
    private var imageView=JSONObject().put("scale",1).put("x",0).put("y",0)
    private var imageRevision=0L
    private fun fileJson(file:ResourceRef)=remoteFileJson(file, file.name.take(120))
    private fun files():List<ResourceRef> = when(model.state.value.route){
        TvRoute.HOME->model.state.value.rootEntries
        TvRoute.RECENT->model.state.value.recentPlayback.map{ResourceRef(it.path,it.wirePath,it.name,false,"",0)}
        TvRoute.RECENT_ACCESS->tvRecentAccessResources(model.recentAccess.state.value.items)
        TvRoute.FAVORITES->model.favorites.state.value.items.filter{it.openable}.map{ResourceRef(it.path,it.wirePath,it.name,false,"",0)}
        else->model.visibleFiles()
    }
    private fun find(id:String)=files().firstOrNull{remoteFileId(it)==id}?:error("文件列表已变化，请刷新")
    private fun fontId(key:String)=MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8)).take(16).joinToString(""){"%02x".format(it)}
    private fun audioRows():List<JSONObject> {
        val media=model.media.state.value
        return if(media.compatible)media.sourceAudio.map{JSONObject().put("id",it.index).put("title",it.title.take(240)).put("codec",it.codec.take(40)).put("language",it.language.take(40)).put("source",true)}
        else model.media.player.state.value.audio.map{JSONObject().put("id",it.id).put("title",it.title.take(240)).put("codec",it.codec.take(40)).put("language",it.language.take(40)).put("source",false)}
    }
    private fun subtitleRows():List<JSONObject> {
        val media=model.media.state.value;val player=model.media.player
        val source=media.sourceText.takeIf{media.sourceSubtitles}.orEmpty()
        val native=player.state.value.subtitles.filter{it.id>=0&&model.media.subtitleTrackPublished(it.id)&&(source.isEmpty()||player.importedSubtitleIdentity(it.id)!=null)}
        return source.map{JSONObject().put("id",it.index).put("title",it.title.take(240)).put("codec",it.codec.take(40)).put("language",it.language.take(40)).put("source",true)}+
            native.map{JSONObject().put("id",it.id).put("title",it.title.take(240)).put("codec",it.codec.take(40)).put("language",it.language.take(40)).put("source",false)}
    }
    fun reportImageView(epoch:Long,scale:Float,x:Float,y:Float) {
        if(epoch==model.media.controlEpoch){resetImageEpoch();imageView=JSONObject().put("scale",scale).put("x",x).put("y",y)}
    }
    private fun resetImageEpoch() {
        if(imageEpoch==model.media.controlEpoch)return
        imageEpoch=model.media.controlEpoch;imageView=JSONObject().put("scale",1).put("x",0).put("y",0)
        imageTransform.value=JSONObject(imageView.toString()).put("revision",++imageRevision)
    }
    private fun listMedia(kind:String,offset:Int,count:Int):JSONObject {
        if(kind=="queue") {
            val items=model.media.state.value.queue?.items.orEmpty()
            return JSONObject().put("items",JSONArray(items.drop(offset).take(count).mapIndexed{index,file->fileJson(file).put("index",offset+index)}))
                .put("total",items.size).put("mediaEpoch",model.media.controlEpoch)
        }
        val rows=when(kind) {
            "audio"->audioRows();"subtitles"->subtitleRows();"sidecars"->model.media.state.value.sidecars.map(::fileJson)
            "fonts"->{SubtitleFontCatalog.request();SubtitleFontCatalog.state.value.options.map{JSONObject().put("id",fontId(it.key)).put("title",it.label.take(240))}}
            else->error("列表类型不支持")
        }
        return JSONObject().put("items",JSONArray(rows.drop(offset).take(count))).put("total",rows.size)
            .put("loading",kind=="fonts"&&SubtitleFontCatalog.state.value.loading).put("mediaEpoch",model.media.controlEpoch)
    }
    fun snapshot():JSONObject{
        val ui=model.state.value;val media=model.media.state.value;val player=model.media.player.state.value
        val scene=if(!ui.connected)"LOGIN"else media.file?.mediaKind()?.name?.takeIf{it in setOf("VIDEO","AUDIO","IMAGE")}?:"BROWSE"
        resetImageEpoch()
        val filePreview = remoteBrowsePage(files(), offset = 0, count = 24)
        val content=JSONObject().put("route",ui.route.name).put("directory",ui.path.take(400)).put("busy",ui.busy||media.busy)
            .put("connected",ui.connected).put("error",media.error?:player.error?:ui.error?:JSONObject.NULL)
            .put("notice",media.subtitleError?:media.subtitleNotice?:player.subtitleFontNotice?:media.notice?:player.operationError?:ui.notice?:JSONObject.NULL)
            .put("files",filePreview.getJSONArray("files")).put("total",filePreview.getInt("total"))
            .put("preferences",JSONObject().put("dark",ui.dark).put("gridColumns",ui.gridColumns).put("safePercent",ui.safePercent).put("clockAlways",ui.clockAlways).put("speedAlways",ui.speedAlways).put("seekStepSeconds",ui.seekStepSeconds).put("controlsTimeoutSeconds",ui.controlsTimeoutSeconds).put("detailsHoldMillis",ui.detailsHoldMillis))
        if (scene == "VIDEO") content.put("subtitleStorageRevision", model.media.localSubtitles.remoteRevision())
        model.context?.let{content.put("session",JSONObject().put("origin",it.profile.address.take(512)).put("username",it.api.identity.username.take(120)))}
        media.file?.let{content.put("media",fileJson(it).put("name",it.name.take(1024)))}
        if(scene=="IMAGE")content.put("image",JSONObject(imageView.toString()))
        val appearance=model.media.playbackPreferences.textSubtitleAppearance.value
        val cache=model.media.cache.state.value
        content.put("cache",JSONObject().put("limitBytes",cache.limitBytes).put("usedBytes",cache.usedBytes)
            .put("bytesPerSecond",cache.bytesPerSecond).put("error",cache.error?:JSONObject.NULL)
            .put("clearing",cache.clearing || cache.releasing).put("prefetchPaused",cache.prefetchPaused)
            .put("minMiB",TvVideoCache.MIN_MIB).put("maxMiB",TvVideoCache.MAX_MIB)
            .put("ranges",JSONArray(cache.ranges.take(40).map{JSONObject().put("startMs",it.startMs).put("endMs",it.endMs)})))
        content.getJSONObject("preferences").put("decoder", model.media.playbackPreferences.videoDecodePolicy.value.name)
        val updates = model.updates.state.value
        content.put("updates", JSONObject().put("phase", updates.phase.name).put("received", updates.received)
            .put("total", updates.total ?: JSONObject.NULL).put("message", updates.message ?: JSONObject.NULL)
            .put("offers", JSONArray(updates.offers.take(8).map {
                JSONObject().put("id", it.id).put("version", it.manifest.versionName)
            })))
        content.put("playback",JSONObject().put("playing",player.playing).put("positionMs",model.media.sourcePosition()).put("durationMs",media.sourceDurationMs)
            .put("positionLocalMs",player.positionMs).put("sourceOffsetMs",media.sourceOffsetMs).put("sdrActive",media.compatible)
            .put("bufferedMs",player.bufferedPositionMs+media.sourceOffsetMs).put("volume",player.volume).put("rate",player.rate).put("seekable",model.media.canSeek)
            .put("ratio",model.media.ratioIndex.value)
            .put("seeking",player.seeking).put("seekError",player.seekError).put("buffering",player.buffering).put("preparing",media.busy)
            .put("firstFrameRendered",player.firstFrameRendered).put("audioDecoder",player.audioDecoder.take(120)).put("subtitleLoading",player.subtitleLoading||media.subtitleLoading).put("waitingForBuffer",player.waitingForBuffer)
            .put("subtitleLoadDetail", media.subtitleLoadDetail.takeIf { media.subtitleLoading })
            .put("audioRenderedBuffers",player.audioRenderedBuffers).put("audioSkippedBuffers",player.audioSkippedBuffers)
            .put("hdrMode",media.mode.name).put("sdrAvailable",media.sdrAvailable).put("subtitleDelayMs",player.subtitleDelayMs)
            .put("audioCount",audioRows().size).put("subtitleCount",subtitleRows().size).put("sidecarCount",media.sidecars.size)
            .put("subtitleAppearance",JSONObject().put("scale",appearance.scale).put("bottomPadding",appearance.bottomPadding).put("backgroundOpacity",appearance.backgroundOpacity).put("area",appearance.area.name).put("fontId",fontId(appearance.fontKey)).put("fontLabel",SubtitleFontCatalog.state.value.resolve(appearance.fontKey).label.take(120)))
            .put("audio",JSONArray(if(media.compatible)media.sourceAudio.take(16).map{JSONObject().put("id",it.index).put("title",it.title.take(120)).put("codec",it.codec.take(40)).put("language",it.language.take(40)).put("source",true)} else player.audio.take(16).map{JSONObject().put("id",it.id).put("title",it.title.take(120)).put("codec",it.codec.take(40)).put("language",it.language.take(40)).put("source",false)}))
            .put("subtitles",JSONArray(player.subtitles.filter{model.media.subtitleTrackPublished(it.id)}.take(16).map{JSONObject().put("id",it.id).put("title",it.title.take(120))}))
            .put("sourceSubtitles",JSONArray(media.sourceText.takeIf{media.sourceSubtitles}.orEmpty().take(16).map{JSONObject().put("id",it.index).put("title",it.title.take(120)).put("language",it.language.take(40))}))
            .put("sidecars",JSONArray(media.sidecars.take(8).map(::fileJson))).put("selectedAudio",if(media.compatible)media.selectedSourceAudio?:-1 else player.selectedAudio)
            .put("selectedSubtitle",player.selectedSubtitle).put("selectedSourceSubtitle",media.selectedSourceSubtitle))
        media.queue?.let { q ->
            val queue = JSONObject().put("index", q.index).put("count", q.items.size)
                .put("items", JSONArray(q.items.take(8).map(::fileJson)))
            if (scene == "IMAGE") queue.put("imageWindow", JSONArray(
                (maxOf(0, q.index - 1)..minOf(q.items.lastIndex, q.index + 1)).map { index ->
                    fileJson(q.items[index]).put("index", index)
                }))
            content.put("queue", queue)
        }
        handoff.snapshot()?.let{content.put("handoff",it)}
        val capabilities = JSONObject().put("navigation",true).put("input",true).put("deviceAuth",true).put("media",true).put("nativeHandoff",1).put("mediaPanels",1).put("fileDetailsKey",1).put("recentAccessPage",1)
            .put("fileBrowse", 1).put("mediaPreview", 1).put("cacheControl", 1).put("settingsControl", 1)
        if (scene == "VIDEO" && subtitleBrowser.available) capabilities.put("subtitleBrowseServer", 1)
        if (scene == "VIDEO" && subtitleBrowser.available) capabilities.put("subtitleBrowseServerCursor", 1)
        if (scene == "VIDEO" && subtitleBrowser.serverUpgradeRequired) capabilities.put("subtitleBrowseServerReason", "SERVER_UPGRADE_REQUIRED")
        if (scene == "VIDEO" && subtitleBrowser.localAvailable) capabilities.put("subtitleBrowseLocal", 1)
        return JSONObject().put("sessionEpoch",model.sessionEpoch).put("mediaEpoch",model.media.controlEpoch).put("scene",scene)
            .put("capabilities",capabilities).put("content",content).also(::boundRemoteSnapshot)
    }
    suspend fun execute(command:JSONObject):JSONObject{
        val operation=command.getString("operation");val p=command.getJSONObject("payload")
        if (operation.startsWith("subtitle.browse.")) return subtitleBrowser.execute(operation, command, p)
        if (operation.startsWith("browse.") || operation.startsWith("preview.")) return browser.execute(operation, command, p)
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
            "app.list"->{val start=p.optInt("offset").coerceAtLeast(0);val count=p.optInt("count",32).coerceIn(1,32);val kind=p.optString("kind","files");if(kind!="files"){check(command.getLong("mediaEpoch")==model.media.controlEpoch){"播放内容已变化"};return listMedia(kind,start,count)};return remoteBrowsePage(files(),start,count)}
            "app.back"->(back?:error("TV界面尚未就绪"))()
            "app.key"->{val code=when(p.getString("key")){"up"->KeyEvent.KEYCODE_DPAD_UP;"down"->KeyEvent.KEYCODE_DPAD_DOWN;"left"->KeyEvent.KEYCODE_DPAD_LEFT;"right"->KeyEvent.KEYCODE_DPAD_RIGHT;"ok"->KeyEvent.KEYCODE_DPAD_CENTER;"details"->KeyEvent.KEYCODE_INFO;else->error("按键不支持")};(key?:error("TV界面尚未就绪"))(code)}
            "app.inputText"->{val text=p.getString("text");require(text.length<=4096){"输入过长"};if(p.optString("target")=="search")model.search(text,SearchScope.valueOf(p.optString("scope","GLOBAL")))else (input?:error("请先选择TV中的输入框"))(text)}
            "app.setPreference"->when(p.getString("key")){
                "dark"->model.setDark(p.getBoolean("value"));"gridColumns"->model.setColumns(p.getInt("value"));"safePercent"->model.setSafePercent(p.getInt("value"))
                "clockAlways"->model.setClockAlways(p.getBoolean("value"));"speedAlways"->model.setSpeedAlways(p.getBoolean("value"))
                "seekStepSeconds"->{val value=p.getInt("value");require(value in 3..30);model.setSeekStep(value)}
                "controlsTimeoutSeconds"->{val value=p.getInt("value");require(value in 2..30);model.setControlsTimeout(value)}
                "detailsHoldMillis"->{val value=p.getInt("value");require(value in 500..2000);model.setDetailsHold(value)}
                "cacheMiB"->model.media.cache.setLimitConfirmed(p.getLong("value"))
                "decoder"->model.media.setVideoDecodePolicy(VideoDecodePolicy.valueOf(p.getString("value")))
                else->error("设置不支持")
            }
            "media.open","image.open"->when {p.has("cancelHandoff")->return handoff.cancel(command,p.getString("cancelHandoff"));p.has("handoff")->return handoff.open(command,p);else->model.open(find(p.getString("id")),files())}
            "playback.play"->if(!player.state.value.playing)player.toggle()
            "playback.pause"->player.pause()
            "playback.stop"->model.media.closeAndSave()
            "playback.seekTo"->{check(model.media.canSeek){"视频尚未准备好，请稍后调整进度"};model.media.seek(p.getLong("positionMs"))}
            "playback.seekBy"->{check(model.media.canSeek){"视频尚未准备好，请稍后调整进度"};val delta=p.getLong("deltaMs");require(delta in -3600000..3600000);model.media.seekBy(delta)}
            "playback.setRate"->{val rate=p.getDouble("rate").toFloat();require(rate.isFinite()&&rate in .1f..5f);player.rate(rate)}
            "playback.setVolume"->{val volume=p.getInt("volume");require(volume in 0..100);player.volume(volume)}
            "playback.setHdrMode"->model.media.switchMode(TvHdrMode.valueOf(p.getString("mode")))
            "playback.setRatio"->model.media.setRatio(p.getInt("index"))
            "cache.clear"->{ require(p.getBoolean("confirmed")); model.media.cache.clear() }
            "cache.resume"->model.media.cache.resumePrefetch()
            "settings.deviceInfo"->return JSONObject().put("details", withContext(Dispatchers.IO) {
                tvDeviceInfo(model.getApplication()).take(4096)
            })
            "settings.updates"->when(p.getString("action")) {
                "check"->model.updates.check()
                "download"->model.updates.download(model.updates.state.value.offers.firstOrNull {
                    it.id == p.getString("id")
                } ?: error("更新列表已变化，请重新检查"))
                "cancel"->model.updates.cancelDownload()
                else->error("请在 TV 上确认系统安装")
            }
            "audio.select"->{val id=p.getInt("id");if(p.optBoolean("source")){check(model.media.state.value.compatible&&model.media.state.value.sourceAudio.any{it.index==id}){"音轨已变化"};model.media.selectSourceAudio(id)}else{check(id==-1||player.state.value.audio.any{it.id==id}){"音轨已变化"};model.media.selectAudio(id)}}
            "subtitle.disable"->model.media.subtitlesOff()
            "subtitle.select"->{val id=p.getInt("id");if(p.optBoolean("source")){check(model.media.state.value.sourceText.any{it.index==id});model.media.selectSourceSubtitle(id)}else{check(model.media.subtitleTrackPublished(id)&&player.state.value.subtitles.any{it.id==id});model.media.selectPlayerSubtitle(id)}}
            "subtitle.selectNasFile"->{val file=model.media.state.value.sidecars.firstOrNull{remoteFileId(it)==p.getString("id")}?:error("字幕列表已变化");model.media.selectExternalSubtitle(file)}
            "subtitle.setDelay"->{val delay=p.getLong("delayMs");require(delay in -600000..600000);player.subtitleDelay(delay)}
            "subtitle.setAppearance"->{
                val old=if(p.optBoolean("reset"))TextSubtitleAppearance()else model.media.playbackPreferences.textSubtitleAppearance.value
                val font=if(p.has("fontId"))SubtitleFontCatalog.state.value.options.singleOrNull{fontId(it.key)==p.getString("fontId")}?.key?:error("字体列表已变化，请重新选择")else old.fontKey
                model.media.updateSubtitleAppearance(old.copy(scale=p.optDouble("scale",old.scale.toDouble()).toFloat(),bottomPadding=p.optDouble("bottomPadding",old.bottomPadding.toDouble()).toFloat(),backgroundOpacity=p.optDouble("backgroundOpacity",old.backgroundOpacity.toDouble()).toFloat(),
                    fontKey=font,area=TextSubtitleArea.valueOf(p.optString("area",old.area.name))))
            }
            "queue.next","image.next"->model.media.navigateQueue(1)
            "queue.previous","image.previous"->model.media.navigateQueue(-1)
            "queue.select","image.selectIndex"->{val q=model.media.state.value.queue?:error("队列为空");val index=p.getInt("index");val selected=q.select(index)?:error("队列已变化");model.open(selected.current,selected.items)}
            "queue.set"->{if(p.has("handoffId"))return handoff.append(command,p);val ids=p.getJSONArray("ids");require(ids.length() in 1..100);val items=(0 until ids.length()).map{find(ids.getString(it))};val index=p.optInt("index");require(index in items.indices);model.open(items[index],items)}
            "image.setTransform"->{val scale=p.getDouble("scale").toFloat();val x=p.optDouble("x").toFloat();val y=p.optDouble("y").toFloat();require(scale.isFinite()&&scale in 1f..3f&&x.isFinite()&&y.isFinite()&&x in -1f..1f&&y in -1f..1f);resetImageEpoch();if(scale>1)model.media.requestOriginalImage();imageTransform.value=JSONObject().put("revision",++imageRevision).put("scale",scale).put("x",x).put("y",y)}
            else->error("此操作尚不支持")
        }
        return JSONObject().put("applied",true)
    }
}
