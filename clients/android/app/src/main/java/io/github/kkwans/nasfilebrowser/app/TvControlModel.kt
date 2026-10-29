package io.github.kkwans.nasfilebrowser.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.kkwans.fileway.remote.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject

class TvControlModel(application:Application):AndroidViewModel(application){
    val client=TvRemoteClient(application,viewModelScope)
    val connection=client.state
    val paired=client.store.dao.devices().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    private val discoveryMutable=MutableStateFlow(TvDiscoveryState())
    val discovery=discoveryMutable.asStateFlow()
    private val noticeMutable=MutableStateFlow<String?>(null)
    val notice=noticeMutable.asStateFlow()
    private val busyMutable=MutableStateFlow(false)
    val busy=busyMutable.asStateFlow()
    private val moreMutable=MutableStateFlow<List<JSONObject>>(emptyList())
    val moreFiles=moreMutable.asStateFlow()
    private var listing=""
    private var scan:Job?=null
    private var selection:Job?=null
    private var automatic=true
    init{viewModelScope.launch{connection.collect{state->
        val current=state.snapshot?.let{"${it.sessionEpoch}/${it.content.optString("route")}/${it.content.optString("directory")}/${it.content.optInt("total")}"}.orEmpty()
        if(listing!=current){listing=current;moreMutable.value=emptyList()}
    }}}
    fun search(){
        scan?.cancel()
        automatic=true
        scan=viewModelScope.launch{
            val attempted=mutableSetOf<String>()
            TvServiceDiscovery(getApplication()).scan().collect{result->
                discoveryMutable.value=result
                if(automatic&&connection.value.phase in setOf(RemotePhase.OFFLINE,RemotePhase.FAILED)){
                    val known=result.devices.mapNotNull{found->client.store.dao.device(found.id)?.takeIf{it.trusted&&it.fingerprint==found.fingerprint}?.descriptor()?.at(found.address,found.port)}
                    if(known.size==1&&attempted.add(known.single().id))connect(known.single())
                }
            }
        }
    }
    fun stopSearch(){scan?.cancel();scan=null}
    fun connect(device:TvDescriptor){select{client.connect(device)}}
    fun pair(text:String){select{client.pair(TvInvitation.parse(text))}}
    fun requestPairing(device:TvDescriptor){work{client.requestPairing(device);noticeMutable.value="请扫描TV显示的二维码"}}
    private fun select(action:suspend ()->Unit){selection?.cancel();selection=viewModelScope.launch{runAction(action)}}
    private suspend fun runAction(action:suspend ()->Unit){try{noticeMutable.value=null;action()}catch(e:Exception){if(e is CancellationException)throw e;noticeMutable.value=e.message?:"电视操作未完成"}}
    fun work(action:suspend ()->Unit){viewModelScope.launch{runAction(action)}}
    fun command(operation:String,payload:JSONObject=JSONObject())=work{send(operation,payload)}
    fun loadMore(){work{
        val expected=listing
        val first=connection.value.snapshot?.content?.optJSONArray("files")?.length()?:0
        val result=send("app.list",JSONObject().put("offset",first+moreMutable.value.size).put("count",40)).payload?:return@work
        if(listing==expected){val rows=result.getJSONArray("files");moreMutable.value=(moreMutable.value+(0 until rows.length()).map{rows.getJSONObject(it)}).distinctBy{it.getString("id")}}
    }}
    private suspend fun send(operation:String,payload:JSONObject=JSONObject()):RemoteResult{
        val result=client.execute(operation,payload)
        if(!result.completed)error(if(result.uncertain)"操作结果尚未确认，请刷新电视状态" else result.payload?.optString("message").orEmpty().ifBlank{"电视未执行操作（${result.code}）"})
        return result
    }
    fun authorize(phone:ClientModel,expected:SessionContext?){
        if(busyMutable.value)return
        work{
            busyMutable.value=true
            try{
                val source=phone.tvAuthorizationContext()?:error("请先在手机登录服务器")
                check(source===expected){"手机登录已切换，请重新确认 TV 登录账号"}
                val device=connection.value.device?:error("请先连接电视")
                val instance=try{source.api.request("GET","/api/instance")}
                    catch(_:org.json.JSONException){error("当前服务器需要升级后才能授权 TV 登录")}
                check(phone.tvAuthorizationContext()===source){"手机登录已切换，请重新确认"}
                val response=send("session.requestGrant",JSONObject().put("origin",source.profile.address).put("instance",instance)).payload?:error("TV未返回授权请求")
                val request=response.getJSONObject("request")
                check(request.getString("deviceId")==device.id){"授权设备不匹配"}
                check(response.getJSONObject("instance").getString("id")==instance.getString("id")){"授权服务实例不匹配"}
                check(phone.tvAuthorizationContext()===source&&connection.value.device?.id==device.id){"手机账号或TV已切换，请重新确认"}
                source.api.action("POST","/api/device-auth/requests/${request.getString("id")}/approve",JSONObject().put("deviceId",device.id).put("publicKey",request.getString("publicKey")))
                // Approval is durable. A later phone account change cannot turn
                // it into an unacknowledged write or copy the phone's session.
                send("session.adopt",JSONObject().put("requestId",request.getString("id")))
                noticeMutable.value="TV已独立登录"
            }finally{busyMutable.value=false}
        }
    }
    fun disconnect(){automatic=false;selection?.cancel();client.disconnect()}
    override fun onCleared(){scan?.cancel();client.disconnect()}
}
