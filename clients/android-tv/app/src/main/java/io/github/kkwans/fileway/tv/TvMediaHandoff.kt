package io.github.kkwans.fileway.tv

import android.os.SystemClock
import androidx.media3.common.C
import io.github.kkwans.fileway.remote.*
import io.github.kkwans.nasfilebrowser.app.*
import io.github.kkwans.nasfilebrowser.data.*
import io.github.kkwans.nasfilebrowser.player.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal data class TvMediaStart(
    val identity:String,val restore:DecoderRestore,val external:ResourceRef?=null,
)

/** The gateway actor owns this transaction; player/source state stays authoritative. */
internal class TvMediaHandoff(private val model:TvModel) {
    private class NeedsLogin(val reason:String):Exception()
    private data class Pending(
        val id:String,val owner:SessionContext,val fence:HandoffFence,val item:HandoffItem,
        val queue:HandoffQueueManifest,val receiver:HandoffQueueReceiver,val targetMs:Long,
        val wantsPlay:Boolean,val startedAt:Long,val kind:MediaKind,
        var queueCommitted:Boolean=false,var ready:Boolean=false,var terminal:String?=null,var message:String?=null,
    )
    private var pending:Pending?=null
    private fun fence(connection:String)=HandoffFence(connection,model.sessionEpoch,model.media.controlEpoch)
    private suspend fun checkCommand(command:JSONObject,owner:SessionContext) {
        val authorized=model.remote.authorized(command)
        check(authorized && model.context===owner && command.getLong("sessionEpoch")==model.sessionEpoch &&
            command.getLong("mediaEpoch")==model.media.controlEpoch) { "TV 登录、播放或控制授权已变化" }
    }
    suspend fun open(command:JSONObject,payload:JSONObject):JSONObject {
        val request=payload.getJSONObject("handoff")
        val id=request.getString("id");require(Regex("[A-Za-z0-9_-]{16,96}").matches(id))
        val authorized=model.remote.authorized(command)
        check(authorized&&command.getLong("sessionEpoch")==model.sessionEpoch&&command.getLong("mediaEpoch")==model.media.controlEpoch){"TV 登录、播放或控制授权已变化"}
        return try { openBound(command,request,id) }
        catch(required:NeedsLogin) { JSONObject().put("handoffId",id).put("status","AUTH_REQUIRED").put("reason",required.reason) }
    }
    private suspend fun read(owner:SessionContext,command:JSONObject,endpoint:String):JSONObject = try {
        owner.api.request("GET",endpoint)
    }catch(error:ServiceException) {
        checkCommand(command,owner)
        if(error.status==401||error.status==403)throw NeedsLogin("ACCESS_DENIED") else throw error
    }
    private suspend fun openBound(command:JSONObject,request:JSONObject,id:String):JSONObject {
        val owner=model.context?:throw NeedsLogin("NO_SESSION")
        checkCommand(command,owner)
        val item=HandoffItem.from(request.getJSONObject("item"))
        val manifest=HandoffQueueManifest.from(request.getJSONObject("queue"))
        val instance=request.getJSONObject("instance")
        val actual=read(owner,command,"/api/instance")
        checkCommand(command,owner)
        if(actual.getString("id")!=instance.getString("id") || actual.getString("publicKey")!=instance.getString("publicKey"))throw NeedsLogin("SERVER_MISMATCH")
        // Resource lookup uses only this TV account. Never accept a phone URL,
        // base address, bearer token or a local download/content URI.
        val metadata=read(owner,command,"/api/resources${item.wirePath}?metadata=1")
        checkCommand(command,owner)
        check(!metadata.getBoolean("isDir") && metadata.getString("path")==item.path &&
            recentAccessWireIdentity(metadata.optString("wirePath",item.wirePath))==item.key) { "文件来源已变化，请在手机重新选择" }
        check(metadata.getLong("size")==item.size && metadata.getString("modified")==item.modified) { "文件版本已变化，请在手机刷新后重试" }
        val selected=item.resource().copy(type=metadata.optString("type",item.type))
        check(selected.mediaKind()==item.kind) { "媒体类型已变化" }
        val restore=decodeStart(request.optJSONObject("playback")?:JSONObject(),model.media.player.state.value.volume)
        val identity=if(item.kind==MediaKind.IMAGE)"" else request.getString("identity").also {
            require(it.isNotBlank()&&it.length<=256)
            val remote=read(owner,command,"/api/media/playback?path=${wireQueryPath(item.path,item.wirePath)}")
            checkCommand(command,owner)
            check(remote.getString("identity")==it) { "视频文件版本已变化，请在手机重新打开后接力" }
            val duration=remote.optDouble("duration",0.0)*1000
            require(duration<=0||restore.positionMs<=duration) { "接力进度超出片源时长" }
        }
        val external=request.optJSONObject("externalSubtitle")?.let { asset ->
            val file=ResourceRef(asset.getString("path"),asset.getString("wirePath"),asset.getString("name"),false,"",asset.getLong("size"),asset.getString("modified"))
            require(file.path.startsWith('/')&&file.path.length<=8192&&resourceWireBytes(file.wirePath).isNotEmpty()&&isExternalSubtitle(file.name)) { "外挂字幕来源无效" }
            val info=read(owner,command,"/api/resources${file.wirePath}?metadata=1")
            checkCommand(command,owner)
            check(!info.getBoolean("isDir")&&info.getString("path")==file.path&&info.getLong("size")==file.size&&info.getString("modified")==file.modified) { "外挂字幕版本已变化" }
            file
        }
        require((restore.subtitleMode==DecoderSubtitleMode.EXTERNAL)==(external!=null)) { "外挂字幕不能由 TV 直接访问，请选择可访问的字幕或关闭字幕后重试" }
        pending?.receiver?.cancel()
        model.openHandoff(selected,TvMediaStart(identity,restore,external))
        val bound=fence(command.getString("connectionId"))
        pending=Pending(id,owner,bound,item,manifest,HandoffQueueReceiver(id,bound,manifest,item),restore.positionMs,restore.wantsPlay,SystemClock.elapsedRealtime(),item.kind)
        return JSONObject().put("handoffId",id).put("status","PREPARING").put("mediaEpoch",bound.mediaEpoch)
    }

    private fun decodeStart(value:JSONObject,volume:Int):DecoderRestore {
        val position=value.optLong("positionMs",0);val rate=value.optDouble("rate",1.0).toFloat()
        val delay=value.optLong("subtitleDelayMs",0)
        require(position>=0 && rate.isFinite()&&rate in .1f..5f && delay in -600000..600000)
        val mode=DecoderSubtitleMode.valueOf(value.optString("subtitleMode","AUTO"))
        val audio=value.optJSONObject("audio")?.let{decoderTransferTrack(it,C.TRACK_TYPE_AUDIO)}
        val subtitle=value.optJSONObject("subtitle")?.let{decoderTransferTrack(it,C.TRACK_TYPE_TEXT)}
        require(mode!=DecoderSubtitleMode.INTERNAL||subtitle!=null)
        val external=if(mode==DecoderSubtitleMode.EXTERNAL) {
            val track=value.getString("externalTrackKey");val version=value.getString("externalVersion")
            require(track.isNotBlank()&&track.length<=512&&Regex("[a-f0-9]{64}").matches(version))
            DecoderExternalSelection("handoff",track,version)
        }else null
        return DecoderRestore(position,value.optBoolean("wantsPlay",true),rate,volume,audio,value.optBoolean("audioOff"),mode,subtitle,external,emptyMap(),delay)
    }

    suspend fun append(command:JSONObject,payload:JSONObject):JSONObject {
        val value=pending?:error("没有可接收的接力播放列表")
        checkCommand(command,value.owner)
        check(value.terminal==null&&!value.queueCommitted) { "播放列表接力已结束" }
        val current=fence(command.getString("connectionId"))
        val result=withContext(Dispatchers.Default) {
            val received=value.receiver.append(current,payload)
            val complete=if(received==value.queue.count)value.receiver.commit(current)else null
            received to complete
        }
        checkCommand(command,value.owner)
        check(pending===value && current==value.fence && value.terminal==null) { "接力内容已变化" }
        result.second?.let { rows ->
            model.media.replaceHandoffQueue(rows,value.queue.index,value.fence.mediaEpoch)
            value.queueCommitted=true
        }
        return JSONObject().put("handoffId",value.id).put("received",result.first).put("committed",value.queueCommitted)
    }

    suspend fun cancel(command:JSONObject,id:String):JSONObject {
        val value=pending?:error("接力请求已结束")
        require(value.id==id){"接力请求已变化"}
        checkCommand(command,value.owner)
        check(fence(command.getString("connectionId"))==value.fence){"播放来源已变化"}
        if(value.terminal=="READY")return JSONObject().put("handoffId",id).put("status","READY")
        value.terminal="CANCELLED";value.receiver.cancel()
        model.media.closeAndSave()
        return JSONObject().put("handoffId",id).put("status","CANCELLED")
    }

    fun snapshot():JSONObject? {
        val value=pending?:return null
        val media=model.media.state.value;val player=model.media.player.state.value
        val current=model.context===value.owner&&model.media.controlEpoch==value.fence.mediaEpoch&&model.sessionEpoch==value.fence.sessionEpoch
        if(!current&&value.terminal==null) { value.terminal="CANCELLED";value.message="TV 播放来源已切换";value.receiver.cancel() }
        if(current&&value.terminal==null) {
            val error=media.error?:player.error?:player.seekError?:media.subtitleError?:player.operationError
            if(error!=null) { value.terminal="FAILED";value.message=error;value.receiver.cancel() }
            else if(SystemClock.elapsedRealtime()-value.startedAt>120_000) { value.terminal="FAILED";value.message="接力准备超时，请查看 TV 状态后重试";value.receiver.cancel() }
            else if(!value.ready) {
                val ready=if(value.kind==MediaKind.IMAGE)media.imageReady else !media.busy&&player.canSavePosition&&
                    player.pendingAudio==null&&player.pendingSubtitle==null&&!media.subtitleLoading&&!player.subtitleLoading&&
                    handoffPlaybackMatches(value.wantsPlay,player.playing,player.ended,model.media.player.playWhenReady)&&
                    (!value.wantsPlay||player.firstFrameRendered||value.kind==MediaKind.AUDIO)&&
                    media.firstConfirmedPositionMs?.let{kotlin.math.abs(it-value.targetMs)<1000}==true
                if(ready)value.ready=true
            }
            if(value.ready&&value.queueCommitted&&value.terminal==null)value.terminal="READY"
        }
        return JSONObject().put("id",value.id).put("status",value.terminal?:if(value.ready&&value.queueCommitted)"READY"else"PREPARING")
            .put("mediaEpoch",value.fence.mediaEpoch).put("queueCommitted",value.queueCommitted)
            .put("received",if(value.queueCommitted)value.queue.count else value.receiver.received).put("total",value.queue.count)
            .put("message",value.message?:JSONObject.NULL)
    }
}

/** Natural EOF may precede the next control snapshot; it is not a user pause. */
internal fun handoffPlaybackMatches(wantsPlay:Boolean,playing:Boolean,ended:Boolean,playWhenReady:Boolean):Boolean =
    wantsPlay==playWhenReady && (playing==wantsPlay || wantsPlay&&ended)
