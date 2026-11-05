package io.github.kkwans.nasfilebrowser.app

import io.github.kkwans.fileway.remote.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import org.json.JSONObject

internal class TvCommandUnconfirmed(message:String="操作结果尚未确认，请先查看 TV 状态",cause:Throwable?=null):IllegalStateException(message,cause)
internal enum class TvHandoffStage { DEVICE, PREPARING, AUTHORIZATION, TRANSFERRING, WAITING, FAILED, DONE }
internal data class TvHandoffUi(
    val title:String,val stage:TvHandoffStage=TvHandoffStage.DEVICE,val error:String?=null,
    val transferred:Int=0,val total:Int=0,val account:String="",val uncertain:Boolean=false,
)

/** One explicit transfer. Reconnection reconciles state; it never replays open. */
internal class TvHandoffFlow(private val scope:CoroutineScope,private val control:TvControlModel) {
    private data class Source(val phone:ClientModel,val file:ResourceRef,val scope:String)
    private data class Attempt(val device:String,val request:PhoneTvHandoff,var mediaEpoch:Long?=null,var sessionEpoch:Long?=null)
    private val mutable=MutableStateFlow<TvHandoffUi?>(null)
    val state=mutable.asStateFlow()
    private var source:Source?=null
    private var attempt:Attempt?=null
    private var job:Job?=null
    private var version=0L
    private var wantedDevice:String?=null
    private var completedRequest:PhoneTvHandoff?=null

    fun begin(phone:ClientModel,file:ResourceRef,sourceScope:String) {
        dismiss()
        source=Source(phone,file,sourceScope)
        wantedDevice=null
        mutable.value=TvHandoffUi(file.name)
        connected()
    }
    fun connected() {
        if(mutable.value?.stage==TvHandoffStage.DEVICE&&control.connection.value.phase==RemotePhase.CONNECTED&&
            (wantedDevice==null||wantedDevice==control.connection.value.device?.id))run(false)
    }
    fun choose(device:TvDescriptor) {
        cancelAttempt();job?.cancel();++version
        mutable.value=mutable.value?.copy(stage=TvHandoffStage.DEVICE,error=null,uncertain=false)
        wantedDevice=device.id
        if(control.connection.value.device?.id==device.id&&control.connection.value.phase==RemotePhase.CONNECTED)connected()
        else control.connect(device)
    }
    fun expectDevice(id:String){cancelAttempt();job?.cancel();++version;wantedDevice=id;mutable.value=mutable.value?.copy(stage=TvHandoffStage.DEVICE,error=null)}
    fun changeDevice(){cancelAttempt();job?.cancel();++version;wantedDevice="";mutable.value=mutable.value?.copy(stage=TvHandoffStage.DEVICE,error=null,uncertain=false)}
    fun retry() {
        if(control.connection.value.phase==RemotePhase.CONNECTED&&(wantedDevice==null||wantedDevice==control.connection.value.device?.id))run(false)
        else {mutable.value=mutable.value?.copy(stage=TvHandoffStage.DEVICE,error=null);if(wantedDevice==null||wantedDevice==control.connection.value.device?.id)control.reconnectNow()}
    }
    fun authorize() { if(mutable.value?.stage==TvHandoffStage.AUTHORIZATION)run(true) }
    private fun guard(expected:Long,selection:Long,device:String,request:PhoneTvHandoff?=null) {
        check(version==expected&&control.selectionVersion==selection&&control.connection.value.device?.id==device){"控制设备已切换，请重新发起接力"}
        request?.requireCurrent()
    }
    private fun run(authorize:Boolean) {
        if(job?.isActive==true)return
        val input=source?:return
        val target=control.connection.value.takeIf{it.phase==RemotePhase.CONNECTED}?:return
        val device=target.device?:return
        if(wantedDevice!=null&&wantedDevice!=device.id){mutable.value=mutable.value?.copy(stage=TvHandoffStage.DEVICE);return}
        wantedDevice=device.id
        cancelAttempt()
        val expected=++version;val selection=control.selectionVersion
        job=scope.launch {
            var loginCompleted=false
            try {
                check(target.snapshot?.capabilities?.optInt("nativeHandoff",0)==1){"请先升级 TV 客户端，再使用原生接力"}
                mutable.value=TvHandoffUi(input.file.name,TvHandoffStage.PREPARING)
                var request=input.phone.prepareTvHandoff(input.file,input.scope)
                guard(expected,selection,device.id,request)
                if(authorize) {
                    control.authorizeBound(input.phone,request.source,device.id,selection);loginCompleted=true
                    guard(expected,selection,device.id,request)
                    request=input.phone.prepareTvHandoff(input.file,input.scope)
                    guard(expected,selection,device.id,request)
                }
                val instance=request.source.api.request("GET","/api/instance")
                guard(expected,selection,device.id,request)
                val opening=Attempt(device.id,request,sessionEpoch=control.connection.value.snapshot?.sessionEpoch);attempt=opening
                val response=control.sendBound("media.open",request.payload(instance),device.id,selection).payload?:error("TV 未返回接力状态")
                guard(expected,selection,device.id,request)
                check(response.getString("handoffId")==request.id){"TV 返回了不同的接力请求"}
                if(response.getString("status")=="AUTH_REQUIRED") {
                    attempt=null
                    mutable.value=TvHandoffUi(input.file.name,TvHandoffStage.AUTHORIZATION,
                        account="${request.source.api.identity.username} · ${request.source.profile.address}")
                    return@launch
                }
                check(response.getString("status")=="PREPARING"){"TV 尚未接受接力"}
                opening.mediaEpoch=response.getLong("mediaEpoch")
                val initial=withTimeout(8000) { control.connection.first { value ->
                    value.phase!=RemotePhase.CONNECTED||value.device?.id!=device.id||value.snapshot?.mediaEpoch==opening.mediaEpoch
                } }
                guard(expected,selection,device.id,request)
                check(initial.phase==RemotePhase.CONNECTED){"TV 连接中断，接力结果尚未确认"}
                opening.sessionEpoch=initial.snapshot?.sessionEpoch
                val pages=withContext(Dispatchers.Default){request.queue.chunks(request.id)}
                for(page in pages) {
                    guard(expected,selection,device.id,request)
                    val result=control.sendBound("queue.set",page,device.id,selection).payload?:error("TV 未确认播放列表")
                    guard(expected,selection,device.id,request)
                    check(result.getString("handoffId")==request.id){"接力播放列表已变化"}
                    mutable.value=TvHandoffUi(input.file.name,TvHandoffStage.TRANSFERRING,transferred=result.getInt("received"),total=request.queue.manifest.count)
                }
                mutable.value=TvHandoffUi(input.file.name,TvHandoffStage.WAITING,transferred=request.queue.manifest.count,total=request.queue.manifest.count)
                val final=withTimeout(125_000) { control.connection.first { value ->
                    value.phase!=RemotePhase.CONNECTED||value.device?.id!=device.id||value.snapshot?.let { snapshot ->
                        snapshot.mediaEpoch!=opening.mediaEpoch||snapshot.sessionEpoch!=opening.sessionEpoch||
                            snapshot.content.optJSONObject("handoff")?.let{it.optString("id")==request.id&&it.optString("status") in setOf("READY","FAILED","CANCELLED")}==true
                    }==true
                } }
                guard(expected,selection,device.id,request)
                confirm(opening,final)
            } catch(failure:Exception) {
                if(failure is CancellationException&&failure !is TimeoutCancellationException)throw failure
                if(version==expected)mutable.value=TvHandoffUi(input.file.name,TvHandoffStage.FAILED,
                    error=(if(loginCompleted)"TV 登录已完成；接力未完成："else "")+(failure.message?:"接力未完成，手机继续播放"),uncertain=attempt!=null)
            }
        }
    }
    private fun confirm(opening:Attempt,value:RemoteConnectionState) {
        val snapshot=value.snapshot
        check(value.phase==RemotePhase.CONNECTED&&value.device?.id==opening.device&&snapshot!=null){"连接已中断，尚未确认接管；手机继续播放"}
        val result=snapshot.content.optJSONObject("handoff")?:error("TV 没有确认接力状态")
        check(result.optString("id")==opening.request.id){"TV 接力请求已变化"}
        val expectedMedia=opening.mediaEpoch?:result.optLong("mediaEpoch",-1).takeIf{it>=0}
        check(snapshot.mediaEpoch==expectedMedia&&snapshot.sessionEpoch==opening.sessionEpoch){"TV 播放或登录来源已变化；手机继续播放"}
        check(result.optString("status")=="READY"){result.optString("message").takeIf{it.isNotBlank()&&it!="null"}?:"TV 尚未完成接管，手机继续播放"}
        opening.request.confirmReady()
        completedRequest=opening.request
        mutable.value=TvHandoffUi(opening.request.item.name,TvHandoffStage.DONE)
        attempt=null
    }
    fun reconcile() {
        if(job?.isActive==true)return
        val opening=attempt?:return
        job=scope.launch {
            try { opening.request.requireCurrent();confirm(opening,control.connection.value) }
            catch(failure:Exception){if(failure is CancellationException)throw failure;mutable.value=mutable.value?.copy(error=failure.message,uncertain=true)}
        }
    }
    private fun cancelAttempt() {
        val opening=attempt?:return
        attempt=null
        if(control.connection.value.phase==RemotePhase.CONNECTED&&control.connection.value.device?.id==opening.device)scope.launch {
            // At most one cancellation, never stop/logout an unrelated media
            // generation. The TV checks the request ID and all source fences.
            runCatching {
                val state=withTimeoutOrNull(2000){control.connection.first { value ->
                    value.phase!=RemotePhase.CONNECTED||value.device?.id!=opening.device||
                        value.snapshot?.content?.optJSONObject("handoff")?.optString("id")==opening.request.id
                }}?:return@runCatching
                val snapshot=state.snapshot?:return@runCatching
                val receipt=snapshot.content.optJSONObject("handoff")?:return@runCatching
                if(state.phase==RemotePhase.CONNECTED&&state.device?.id==opening.device&&snapshot.sessionEpoch==opening.sessionEpoch&&
                    receipt.optString("id")==opening.request.id&&receipt.optLong("mediaEpoch",-1)==snapshot.mediaEpoch)
                    control.client.execute("media.open",JSONObject().put("cancelHandoff",opening.request.id))
            }
        }
    }
    fun consumeReady():Boolean {
        if(mutable.value?.stage!=TvHandoffStage.DONE)return false
        val request=completedRequest
        val current=request!=null&&runCatching{request.requireCurrent()}.isSuccess
        dismiss(cancelTv=false)
        return current
    }
    fun dismiss(cancelTv:Boolean=true) { ++version;job?.cancel();job=null;if(cancelTv)cancelAttempt()else attempt=null;source=null;mutable.value=null;wantedDevice=null;completedRequest=null }
}
