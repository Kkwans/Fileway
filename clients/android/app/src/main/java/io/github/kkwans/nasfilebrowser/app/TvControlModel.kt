package io.github.kkwans.nasfilebrowser.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.kkwans.fileway.remote.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import kotlin.random.Random

internal enum class TvSubtitleSource(val wireName: String, private val capability: String) {
    SERVER("server", "subtitleBrowseServerCursor"),
    LOCAL("tvLocal", "subtitleBrowseLocal");

    fun supportedBy(capabilities: JSONObject): Boolean = capabilities.optInt(capability) == 1

    fun storageRevision(content: JSONObject): Long? = if (this == LOCAL) {
        content.optLong("subtitleStorageRevision", -1).takeIf { it >= 0 }
    } else null
}

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
    private var reconnect:Job?=null
    private val selectionLock=Any()
    @Volatile private var selected:TvDescriptor?=null
    @Volatile private var revision=0L
    private var retryAttempt=0
    private var visible=false
    private var automatic=true
    private val views=mutableSetOf<Any>()
    internal val selectionVersion:Long get()=revision
    internal val handoff=TvHandoffFlow(viewModelScope,this)
    fun startView(owner:Any){if(views.add(owner)&&views.size==1)search()}
    fun stopView(owner:Any){views-=owner;if(views.isEmpty())stopSearch()}
    init{viewModelScope.launch{connection.collect{state->
        val current=state.snapshot?.let{snapshot->
            val prefix=snapshot.content.optJSONArray("files")
            val ids=prefix?.let{rows->(0 until rows.length()).joinToString(","){rows.optJSONObject(it)?.optString("id").orEmpty()}}.orEmpty()
            "${state.device?.id}/${snapshot.connectionId}/${snapshot.sessionEpoch}/${snapshot.content.optString("route")}/${snapshot.content.optString("directory")}/${snapshot.content.optInt("total")}/$ids"
        }.orEmpty()
        if(listing!=current){listing=current;moreMutable.value=emptyList()}
        if(state.phase==RemotePhase.CONNECTED){retryAttempt=0;reconnect?.cancel();reconnect=null;handoff.connected()}
        else if(state.phase==RemotePhase.FAILED&&state.retryable)scheduleReconnect()
    }}}
    fun search(){
        scan?.cancel()
        visible=true
        scheduleReconnect()
        scan=viewModelScope.launch{
            val remembered = client.store.dao.devices().first().firstOrNull { it.trusted }?.descriptor()
            var attempted = false
            fun restore(target: TvDescriptor) {
                val expected = synchronized(selectionLock) {
                    revision.takeIf { visible && automatic && selected == null && connection.value.phase == RemotePhase.OFFLINE }
                }
                if (expected != null && !attempted) {
                    attempted = true
                    connect(target, true, expected)
                }
            }
            if (remembered != null) launch {
                // Give discovery a brief chance to refresh an address or port.
                delay(1_500)
                restore(remembered)
            }
            TvServiceDiscovery(getApplication()).scan().collect{result->
                discoveryMutable.value=result
                if (remembered != null) result.devices.firstOrNull {
                    it.id == remembered.id && it.fingerprint == remembered.fingerprint
                }?.let { restore(remembered.at(it.address, it.port)) }
            }
        }
    }
    fun stopSearch(){visible=false;scan?.cancel();scan=null;reconnect?.cancel();reconnect=null}
    fun connect(device:TvDescriptor)=connect(device,true)
    internal fun allowSelection(id:String):Boolean = synchronized(selectionLock) {
        if(!connection.value.blocksSelection(id)&&selected?.id?.let{it!=id}!=true)true
        else {
            noticeMutable.value="请先断开当前 TV，再连接其他 TV"
            false
        }
    }
    private fun connect(device:TvDescriptor,resetBackoff:Boolean,predecessor:Long?=null){
        select(device,resetBackoff,predecessor){client.connect(latest(device))}
    }
    private fun latest(device:TvDescriptor):TvDescriptor=discovery.value.devices
        .firstOrNull{it.id==device.id&&it.fingerprint==device.fingerprint}
        ?.let{device.at(it.address,it.port)}?:device
    fun reconnectNow(){
        val (device,expected)=synchronized(selectionLock){(connection.value.device?:selected?:return) to revision}
        viewModelScope.launch {
            runAction(expected) {
                val saved=client.store.dao.device(device.id)
                if(saved?.trusted==true&&saved.fingerprint==device.fingerprint)connect(device,true,expected)
                else select(device,predecessor=expected){client.requestTvConsent(latest(device))}
            }
        }
    }
    private fun scheduleReconnect(){
        val state=connection.value
        if(!visible||!automatic||state.phase!=RemotePhase.FAILED||!state.retryable||reconnect?.isActive==true)return
        val device=state.device?:selected?:return
        val expected=revision
        val waitMs=minOf(15_000L,1000L shl retryAttempt.coerceAtMost(4))+Random.nextLong(250)
        retryAttempt=(retryAttempt+1).coerceAtMost(4)
        reconnect=viewModelScope.launch{
            delay(waitMs)
            if(expected!=revision||!visible||!automatic||connection.value.phase!=RemotePhase.FAILED||!connection.value.retryable)return@launch
            connect(device,false,expected)
        }
    }
    fun pair(text:String){
        val invitation=try{TvInvitation.parse(text)}catch(error:Exception){noticeMutable.value=error.message?:"TV 二维码无法识别";return}
        select(invitation.device) {
            val saved = client.store.dao.device(invitation.device.id)
            if (saved?.trusted == true && saved.fingerprint == invitation.device.fingerprint) client.connect(invitation.device)
            else client.requestTvConsent(invitation.device, invitation)
        }
    }
    fun requestPairing(device:TvDescriptor){select(device){client.requestTvConsent(latest(device))}}
    private fun select(device:TvDescriptor,resetBackoff:Boolean=true,predecessor:Long?=null,action:suspend ()->Unit){
        val next=synchronized(selectionLock){
            if(predecessor!=null&&predecessor!=revision)return
            if(!allowSelection(device.id))return
            // Reserve before launching or waiting for a previous attempt's cleanup.
            automatic=true;selected=device
            val expected=++revision
            val previous=selection
            previous?.cancel();reconnect?.cancel();reconnect=null;busyMutable.value=false
            if(resetBackoff)retryAttempt=0
            viewModelScope.launch(start=CoroutineStart.LAZY){
                previous?.join()
                runAction(expected,action)
            }.also{selection=it}
        }
        next.start()
    }
    private suspend fun runAction(expected:Long,action:suspend ()->Unit){
        synchronized(selectionLock){
            if(expected!=revision)return
            noticeMutable.value=null
        }
        try{action()}
        catch(e:Exception){
            if(e is CancellationException)throw e
            synchronized(selectionLock) {
                if(expected==revision){
                    // A direct transport user may have reserved a target after
                    // the model checked; retain that actual target for retry.
                    if(e is RemoteFailure&&e.code=="DISCONNECT_REQUIRED")selected=connection.value.device
                    noticeMutable.value=e.message?:"电视操作未完成"
                }
            }
        }
    }
    fun work(action:suspend ()->Unit){val expected=revision;viewModelScope.launch{runAction(expected,action)}}
    fun command(operation:String,payload:JSONObject=JSONObject())=work{send(operation,payload)}
    private fun checkSnapshot(expected:RemoteSnapshot) {
        val actual=connection.value.snapshot
        check(actual?.connectionId==expected.connectionId&&actual.sessionEpoch==expected.sessionEpoch&&actual.mediaEpoch==expected.mediaEpoch){"TV 播放或登录来源已变化，请重新打开面板"}
    }
    internal fun commandFor(expected:RemoteSnapshot,operation:String,payload:JSONObject=JSONObject())=work {
        checkSnapshot(expected)
        send(operation,payload,expected=expected)
        // The write is acknowledged. A later refresh/scope change does not
        // turn it into an unacknowledged failure or authorize replay.
    }
    internal suspend fun subtitleBrowserCommand(
        expected: RemoteSnapshot,
        source: TvSubtitleSource,
        operation: String,
        payload: JSONObject,
    ): RemoteResult {
        require(operation in setOf("subtitle.browse.open", "subtitle.browse.page",
            "subtitle.browse.select", "subtitle.browse.close"))
        require(operation != "subtitle.browse.open" || payload.optString("source") == source.wireName)
        fun verifyScope() {
            val state = connection.value
            val actual = state.snapshot
            if (state.phase != RemotePhase.CONNECTED || actual == null)
                throw RemoteFailure("NOT_CONNECTED", "电视连接已断开")
            if (actual.connectionId != expected.connectionId || actual.sessionEpoch != expected.sessionEpoch ||
                actual.mediaEpoch != expected.mediaEpoch || actual.scene != "VIDEO" ||
                source.storageRevision(actual.content) != source.storageRevision(expected.content))
                throw RemoteFailure("SCOPE_CHANGED", "TV 播放、登录或字幕授权已变化")
            if (!source.supportedBy(expected.capabilities) || !source.supportedBy(actual.capabilities))
                throw RemoteFailure("NOT_AVAILABLE", "TV 当前未提供此字幕目录")
        }
        currentCoroutineContext().ensureActive()
        verifyScope()
        val result = client.execute(operation, payload, expected)
        currentCoroutineContext().ensureActive()
        verifyScope()
        return result
    }
    internal suspend fun mediaList(expected:RemoteSnapshot,kind:String):List<JSONObject> =withTimeout(10000) {
        val rows=mutableListOf<JSONObject>()
        var total:Int?=null
        do {
            checkSnapshot(expected)
            val page=send("app.list",JSONObject().put("kind",kind).put("offset",rows.size).put("count",32),expected=expected).payload?:error("TV 未返回列表")
            checkSnapshot(expected)
            check(page.getLong("mediaEpoch")==expected.mediaEpoch){"播放内容已变化"}
            if(page.optBoolean("loading")){delay(200);continue}
            val size=page.getInt("total")
            require(size in 0..10000&& (total==null||total==size)){"列表已变化，请重新打开"}
            total=size
            val items=page.getJSONArray("items")
            check(items.length()>0||rows.size==size){"TV 未返回完整列表"}
            for(index in 0 until items.length())rows+=items.getJSONObject(index)
            check(rows.size<=size){"TV 列表长度无效"}
        }while(total==null||rows.size<total)
        rows.toList()
    }
    fun loadMore(){work{
        val expected=listing
        val first=connection.value.snapshot?.content?.optJSONArray("files")?.length()?:0
        val result=send("app.list",JSONObject().put("offset",first+moreMutable.value.size).put("count",40)).payload?:return@work
        if(listing==expected){val rows=result.getJSONArray("files");moreMutable.value=(moreMutable.value+(0 until rows.length()).map{rows.getJSONObject(it)}).distinctBy{it.getString("id")}}
    }}
    private suspend fun send(operation:String,payload:JSONObject=JSONObject(),deviceId:String?=null,expected:RemoteSnapshot?=null):RemoteResult{
        check(deviceId==null||connection.value.device?.id==deviceId){"控制设备已切换，请重新操作"}
        val result=client.execute(operation,payload,expected)
        if(!result.completed){if(result.uncertain)throw TvCommandUnconfirmed();error(result.payload?.optString("message").orEmpty().ifBlank{"电视未执行操作（${result.code}）"})}
        return result
    }
    internal suspend fun sendBound(operation:String,payload:JSONObject,deviceId:String,version:Long):RemoteResult {
        check(version==revision){"控制设备已切换"}
        return send(operation,payload,deviceId).also{check(version==revision&&connection.value.device?.id==deviceId){"控制设备已切换"}}
    }
    fun authorize(phone:ClientModel,expected:SessionContext?){
        if(busyMutable.value)return
        val requestRevision=revision
        work{
            busyMutable.value=true
            try{
                val source=phone.tvAuthorizationContext()?:error("请先在手机登录服务器")
                check(source===expected){"手机登录已切换，请重新确认 TV 登录账号"}
                val device=connection.value.device?:error("请先连接电视")
                authorizeBound(phone,source,device.id,requestRevision)
                if(requestRevision==revision)noticeMutable.value="TV已独立登录"
            }finally{if(requestRevision==revision)busyMutable.value=false}
        }
    }
    internal suspend fun authorizeBound(phone:ClientModel,source:SessionContext,deviceId:String,version:Long){
                check(version==revision&&connection.value.device?.id==deviceId&&phone.tvAuthorizationContext()===source){"手机账号或TV已切换，请重新确认"}
                val instance=try{source.api.request("GET","/api/instance")}
                    catch(_:org.json.JSONException){error("当前服务器需要升级后才能授权 TV 登录")}
                check(phone.tvAuthorizationContext()===source){"手机登录已切换，请重新确认"}
                val response=sendBound("session.requestGrant",JSONObject().put("origin",source.profile.address).put("instance",instance),deviceId,version).payload?:error("TV未返回授权请求")
                val request=response.getJSONObject("request")
                check(request.getString("deviceId")==deviceId){"授权设备不匹配"}
                check(response.getJSONObject("instance").getString("id")==instance.getString("id")){"授权服务实例不匹配"}
                check(phone.tvAuthorizationContext()===source&&connection.value.device?.id==deviceId&&version==revision){"手机账号或TV已切换，请重新确认"}
                source.api.action("POST","/api/device-auth/requests/${request.getString("id")}/approve",JSONObject().put("deviceId",deviceId).put("publicKey",request.getString("publicKey")))
                // Approval is durable. A later phone account change cannot turn
                // it into an unacknowledged write or copy the phone's session.
                try {sendBound("session.adopt",JSONObject().put("requestId",request.getString("id")),deviceId,version)}
                catch(failure:Exception){if(failure is CancellationException)throw failure;throw TvCommandUnconfirmed("授权已批准，TV是否完成登录尚未确认，请先查看TV当前登录状态",failure)}
    }
    fun disconnect()=synchronized(selectionLock){val pairing=connection.value.phase in setOf(RemotePhase.PAIRING,RemotePhase.CONNECTING);++revision;automatic=false;selected=null;selection?.cancel();reconnect?.cancel();reconnect=null;busyMutable.value=false;client.disconnect();noticeMutable.value=if(pairing)"已取消连接申请"else"已断开 TV"}
    override fun onCleared(){scan?.cancel();reconnect?.cancel();client.disconnect()}
}
