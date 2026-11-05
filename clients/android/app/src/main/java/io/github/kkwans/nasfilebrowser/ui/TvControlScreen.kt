package io.github.kkwans.nasfilebrowser.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import io.github.kkwans.fileway.remote.*
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.app.*
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayTopAppBar
import org.json.JSONObject
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun TvControlScreen(phone:ClientModel,onClose:()->Unit,controlModel:TvControlModel?=null){
    val model=controlModel?:rememberTvControlModel()
    val viewOwner=remember{Any()}
    val connection by model.connection.collectAsStateWithLifecycle()
    val paired by model.paired.collectAsStateWithLifecycle()
    val discovery by model.discovery.collectAsStateWithLifecycle()
    val notice by model.notice.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val moreFiles by model.moreFiles.collectAsStateWithLifecycle()
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var permitted by remember{mutableStateOf(Build.VERSION.SDK_INT<37||context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK)==PackageManager.PERMISSION_GRANTED)}
    var scanning by remember{mutableStateOf(false)}
    var cameraError by remember{mutableStateOf<String?>(null)}
    var input by remember{mutableStateOf("")}
    var target by remember{mutableStateOf("search")}
    var inputOpen by remember{mutableStateOf(false)}
    LaunchedEffect(connection.device?.id,connection.snapshot?.sessionEpoch){inputOpen=false}
    var section by remember{mutableStateOf(if(connection.snapshot?.scene in setOf("VIDEO","AUDIO","IMAGE"))"播放"else"遥控")}
    var selectedTab by remember{mutableStateOf(false)}
    val sectionScroll=remember(section,connection.device?.id){LazyListState()}
    val compactRemote=section=="遥控"&&LocalConfiguration.current.screenHeightDp<540
    LaunchedEffect(connection.snapshot?.scene){if(!selectedTab&&connection.snapshot?.scene in setOf("VIDEO","AUDIO","IMAGE"))section="播放"}
    var devicesOpen by remember{mutableStateOf(false)}
    var connectionNotice by remember{mutableStateOf<String?>(null)}
    LaunchedEffect(connection.phase,connection.error){
        if(connection.phase in setOf(RemotePhase.CONNECTED,RemotePhase.OFFLINE))connectionNotice=null
        else connection.error?.let{connectionNotice=it}
    }
    FilewayToast(connectionNotice)
    FilewayToast(notice?.takeUnless{it==connection.error||it==connectionNotice})
    val tvNotice=connection.snapshot?.content?.optString("notice")?.takeIf{it!="null"&&it.isNotBlank()}
    FilewayToast(tvNotice)
    val lan=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){permitted=it}
    val camera=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){if(it){scanning=true;cameraError=null}else cameraError="扫码需要相机权限"}
    val image=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()){uri->if(uri!=null)scope.launch{
        try{scanning=false;model.pair(tvQrFromImage(context,uri))}
        catch(error:Exception){if(error is kotlinx.coroutines.CancellationException)throw error;cameraError=error.message?:"二维码读取失败"}
    }}
    fun scan(){if(context.checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED){scanning=true;cameraError=null}else camera.launch(Manifest.permission.CAMERA)}
    fun closeControl(){if(connection.phase==RemotePhase.PAIRING)model.disconnect();onClose()}
    LaunchedEffect(permitted){if(!permitted)lan.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)}
    LifecycleStartEffect(permitted){
        if(permitted)model.startView(viewOwner)
        onStopOrDispose{model.stopView(viewOwner)}
    }
    Dialog(onDismissRequest=::closeControl,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)){
        Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background){
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()){
                FilewayTopAppBar(if(scanning)"扫描 TV 二维码" else "TV 遥控",if(scanning)null else connection.device?.name,R.drawable.ic_arrow_back,"返回",{if(scanning)scanning=false else closeControl()}){
                    if(scanning)FilewayTextButton(onClick={image.launch("image/*")}){Text("从图片识别")}
                    else FilewayTextButton(onClick={devicesOpen=true}){Text("设备");FilewayIcon(R.drawable.ic_expand_more,null,Modifier.size(20.dp))}
                }
                if(scanning){
                    BackHandler{scanning=false}
                    TvQrScanner(Modifier.fillMaxWidth().weight(1f),onCode={scanning=false;model.pair(it)},onError={cameraError=it;scanning=false})
                }else{
                    val snapshot=connection.snapshot
                    if(connection.phase==RemotePhase.CONNECTED&&snapshot!=null)PrimaryTabRow(selectedTabIndex=listOf("遥控","文件","播放","设置").indexOf(section),containerColor=MaterialTheme.colorScheme.background){
                        listOf("遥控","文件","播放","设置").forEach{label->Tab(selected=section==label,onClick={section=label;selectedTab=true},text={Text(label)})}
                    }
                    LazyColumn(Modifier.fillMaxWidth().weight(1f),state=sectionScroll,contentPadding=PaddingValues(horizontal=16.dp,vertical=if(compactRemote)8.dp else 12.dp),verticalArrangement=Arrangement.spacedBy(if(compactRemote)8.dp else 12.dp)){
                        if(connection.phase!=RemotePhase.CONNECTED){
                            item{Text(when(connection.phase){RemotePhase.CONNECTING->"正在连接…";RemotePhase.PAIRING->"正在配对…";else->"选择电视"},style=MaterialTheme.typography.titleMedium)}
                            if(connection.phase==RemotePhase.PAIRING)item {
                                Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                                    Text(if(connection.pairingAwaitingConfirmation)"TV 已允许，请核对号码"else"等待 ${connection.device?.name?:"TV"} 允许连接",style=MaterialTheme.typography.bodyLarge)
                                    connection.pairingCode?.let { code->
                                        Text(code.chunked(3).joinToString(" "),style=MaterialTheme.typography.headlineLarge)
                                        Text("确认号码与 TV 上显示的一致",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if(connection.pairingAwaitingConfirmation)FilewayButton(onClick=model.client::confirmPairing){Text("号码一致，连接")}
                                    FilewayOutlinedButton(onClick=model::disconnect){Text("取消申请")}
                                }
                            }
                            if(connection.phase==RemotePhase.CONNECTING)item {
                                Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                                    LinearProgressIndicator(Modifier.fillMaxWidth())
                                    FilewayOutlinedButton(onClick=model::disconnect){Text("取消连接")}
                                }
                            }
                            if(connection.phase in setOf(RemotePhase.FAILED,RemotePhase.BUSY))item{
                                Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
                                    connection.error?.let{Text(it,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.error)}
                                    FilewayOutlinedButton(onClick=model::reconnectNow){Text("重新连接")}
                                }
                            }
                            if(connection.phase !in setOf(RemotePhase.PAIRING,RemotePhase.CONNECTING)) {
                            items(paired,key={"saved/"+it.id}){device->
                                val found=discovery.devices.firstOrNull{it.id==device.id&&it.fingerprint==device.fingerprint}
                                TvPhoneDeviceRow(device.name,if(device.trusted)"已配对" else "配对待确认"){
                                    val target=if(found!=null)device.descriptor().at(found.address,found.port)else device.descriptor()
                                    if(device.trusted)model.connect(target)else model.requestPairing(target)
                                }
                            }
                            items(discovery.devices.filter{candidate->paired.none{it.id==candidate.id}},key={"found/"+it.id}){device->TvPhoneDeviceRow(device.name,"局域网 TV · 点击连接"){model.requestPairing(device)}}
                            if(discovery.searching)item{LinearProgressIndicator(Modifier.fillMaxWidth())}
                            if(!discovery.searching&&discovery.devices.isEmpty()&&paired.isEmpty())item{Text(discovery.error?:"未发现 TV，可扫描 TV 上的二维码连接",style=MaterialTheme.typography.bodyMedium)}
                            item{Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){FilewayOutlinedButton(onClick={if(permitted)model.search()else lan.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)}){Text("重新搜索")};FilewayButton(onClick={scan()}){Text("扫码配对")}}}
                            }
                        }else if(snapshot!=null){
                            when(section){
                                "遥控"->{
                                    if(snapshot.scene=="LOGIN")item{
                                        val source=phone.tvAuthorizationContext()
                                        FilewayButton(onClick={model.authorize(phone,source)},enabled=!busy&&source!=null,modifier=Modifier.fillMaxWidth()){Text(if(busy)"正在授权…" else "用手机当前账号登录 TV")}
                                    }
                                    item{TvPhoneDpad(model)}
                                    item{Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                                        FilewayOutlinedButton(onClick={model.command("app.back")},Modifier.weight(1f)){Text("返回")}
                                        FilewayOutlinedButton(onClick={model.command("app.navigate",JSONObject().put("route","HOME"))},Modifier.weight(1f)){Text("首页")}
                                        FilewayOutlinedButton(onClick={inputOpen=true},Modifier.weight(1f)){Text("键盘")}
                                    }}
                                }
                                "文件"->{
                                    item{FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("FILES" to "文件","VIDEO" to "视频","MUSIC" to "音乐","IMAGES" to "图片","FAVORITES" to "收藏","RECENT" to "最近").forEach{(route,title)->FilterChip(selected=snapshot!!.content.optString("route")==route,onClick={model.command("app.navigate",JSONObject().put("route",route))},label={Text(title)})}}}
                                    item{Text(snapshot!!.content.optString("directory"),style=MaterialTheme.typography.bodyMedium)}
                                    val files=snapshot!!.content.optJSONArray("files")
                                    if(files!=null)items(files.length(),key={files.getJSONObject(it).getString("id")}){i->val file=files.getJSONObject(i);TvPhoneDeviceRow(file.getString("name"),if(file.optBoolean("directory"))"文件夹"else file.optString("kind")){model.command("media.open",JSONObject().put("id",file.getString("id")))}}
                                    items(moreFiles,key={"more/"+it.getString("id")}){file->TvPhoneDeviceRow(file.getString("name"),if(file.optBoolean("directory"))"文件夹"else file.optString("kind")){model.command("media.open",JSONObject().put("id",file.getString("id")))}}
                                    if((files?.length()?:0)+moreFiles.size<snapshot!!.content.optInt("total"))item{FilewayOutlinedButton(onClick=model::loadMore,Modifier.fillMaxWidth()){Text("更多文件")}}
                                }
                                "播放"->item{key(snapshot.connectionId,snapshot.sessionEpoch,snapshot.mediaEpoch){TvPhoneMediaControls(model,snapshot){section="文件"}}}
                                "设置"->{
                                    item{
                                        val source=phone.tvAuthorizationContext()
                                        Text("账号与连接",style=MaterialTheme.typography.titleMedium)
                                        FilewayOutlinedButton(onClick={model.authorize(phone,source)},enabled=!busy&&source!=null,modifier=Modifier.fillMaxWidth()){Text(if(busy)"正在授权…" else "将手机当前账号用于 TV")}
                                    }
                                    item{Text("TV 设置",style=MaterialTheme.typography.titleMedium)}
                                    snapshot!!.content.optJSONObject("session")?.let{session->item{Text("TV 当前登录\n${session.optString("username")}\n${session.optString("origin")}",style=MaterialTheme.typography.bodyMedium)}}
                                    val prefs=snapshot!!.content.optJSONObject("preferences")?:JSONObject()
                                    if(prefs.has("seekStepSeconds"))item{
                                        var step by remember(snapshot.connectionId){mutableStateOf<Float?>(null)}
                                        val value=step?:prefs.optInt("seekStepSeconds",10).coerceIn(3,30).toFloat()
                                        Text("快进 / 后退 ${value.toInt()} 秒")
                                        Slider(value,onValueChange={step=it},onValueChangeFinished={step?.let{model.command("app.setPreference",JSONObject().put("key","seekStepSeconds").put("value",it.toInt()))};step=null},valueRange=3f..30f,steps=26)
                                    }
                                    if(prefs.has("controlsTimeoutSeconds"))item{
                                        var timeout by remember(snapshot.connectionId){mutableStateOf<Float?>(null)}
                                        val value=timeout?:prefs.optInt("controlsTimeoutSeconds",5).coerceIn(2,30).toFloat()
                                        Text("播放控件自动隐藏 ${value.toInt()} 秒")
                                        Slider(value,onValueChange={timeout=it},onValueChangeFinished={timeout?.let{model.command("app.setPreference",JSONObject().put("key","controlsTimeoutSeconds").put("value",it.toInt()))};timeout=null},valueRange=2f..30f,steps=27)
                                    }
                                    item{TvPhoneToggle("深色外观",prefs.optBoolean("dark")){model.command("app.setPreference",JSONObject().put("key","dark").put("value",it))}}
                                    item{TvPhoneToggle("常驻北京时间",prefs.optBoolean("clockAlways")){model.command("app.setPreference",JSONObject().put("key","clockAlways").put("value",it))}}
                                    item{TvPhoneToggle("常驻缓冲速度",prefs.optBoolean("speedAlways")){model.command("app.setPreference",JSONObject().put("key","speedAlways").put("value",it))}}
                                    item{Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){(2..6).forEach{n->FilterChip(selected=prefs.optInt("gridColumns")==n,onClick={model.command("app.setPreference",JSONObject().put("key","gridColumns").put("value",n))},label={Text("$n 列")})}}}
                                    item{FilewayOutlinedButton(onClick={model.command("device.enablePairing")},Modifier.fillMaxWidth()){Text("连接另一部手机")}}
                                }
                            }
                        }
                        (cameraError?:snapshot?.content?.optString("error")?.takeIf{it!="null"&&it.isNotBlank()})?.let{message->item{Text(message,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodyMedium)}}
                    }
                }
            }
        }
    }
    if(inputOpen)FileOperationSheet("输入到 TV",{inputOpen=false}) {
        Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){FilterChip(selected=target=="search",onClick={target="search"},label={Text("搜索")});FilterChip(selected=target=="focused",onClick={target="focused"},label={Text("当前输入框")})}
            OutlinedTextField(value=input,onValueChange={input=it.take(4096)},label={Text(if(target=="search")"搜索内容"else"输入内容")},shape=RoundedCornerShape(12.dp),modifier=Modifier.fillMaxWidth(),maxLines=4)
            FilewayButton(onClick={model.command("app.inputText",JSONObject().put("target",target).put("text",input));inputOpen=false},modifier=Modifier.fillMaxWidth()){Text(if(target=="search")"在 TV 搜索"else"发送到 TV")}
        }
    }
    val activeTarget=connection.phase in setOf(RemotePhase.CONNECTED,RemotePhase.CONNECTING,RemotePhase.PAIRING)
    if(devicesOpen)AlertDialog(onDismissRequest={devicesOpen=false},shape=RoundedCornerShape(12.dp),title={Text(if(activeTarget)"当前 TV"else"选择 TV")},text={
        LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)){
            if(activeTarget){
                item{Text(connection.device?.name.orEmpty(),style=MaterialTheme.typography.titleMedium)}
                item{FilewayOutlinedButton(onClick={model.disconnect();devicesOpen=false},modifier=Modifier.fillMaxWidth()){Text(if(connection.phase==RemotePhase.CONNECTED)"断开连接"else"取消连接")}}
            }else {
            items(paired,key={it.id}){device->
                val found=discovery.devices.firstOrNull{it.id==device.id&&it.fingerprint==device.fingerprint}
                TvPhoneDeviceRow(device.name,if(connection.device?.id==device.id&&connection.phase==RemotePhase.CONNECTED)"当前连接" else if(!device.trusted)"配对待确认"else if(found!=null)"局域网可用" else "已配对"){
                    val target=if(found!=null)device.descriptor().at(found.address,found.port)else device.descriptor()
                    if(device.trusted)model.connect(target)else model.requestPairing(target)
                    devicesOpen=false
                }
            }
            items(discovery.devices.filter{candidate->paired.none{it.id==candidate.id}},key={it.id}){device->TvPhoneDeviceRow(device.name,"新设备 · 点击连接"){model.requestPairing(device);devicesOpen=false}}
            if(paired.isEmpty()&&discovery.devices.isEmpty())item{Text(if(discovery.searching)"正在寻找局域网中的 TV…" else "未发现 TV")}
            if(connection.phase==RemotePhase.FAILED||connection.phase==RemotePhase.BUSY)item{FilewayOutlinedButton(onClick={model.reconnectNow();devicesOpen=false}){Text("重新连接")}}
            }
        }
    },confirmButton={if(activeTarget)FilewayTextButton(onClick={devicesOpen=false}){Text("关闭")}else FilewayTextButton(onClick={devicesOpen=false;scan()}){Text("扫码配对")}},dismissButton={if(!activeTarget)FilewayTextButton(onClick={model.search()}){Text("重新搜索")}})
}

@Composable internal fun rememberTvControlModel():TvControlModel {
    val owner=requireNotNull(LocalViewModelStoreOwner.current)
    return remember(owner){ViewModelProvider(owner)[TvControlModel::class.java]}
}

@Composable private fun TvPhoneDeviceRow(title:String,subtitle:String,onClick:()->Unit){
    Surface(onClick=onClick,shape=RoundedCornerShape(12.dp),color=MaterialTheme.colorScheme.surfaceContainer,modifier=Modifier.fillMaxWidth()){
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){Text(title,style=MaterialTheme.typography.bodyLarge,maxLines=3);Text(subtitle,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
    }
}
@Composable private fun TvPhoneDpad(model:TvControlModel){
    val compact=LocalConfiguration.current.screenHeightDp<540
    val side=if(compact)48.dp else 64.dp;val center=if(compact)48.dp else 76.dp
    Column(Modifier.fillMaxWidth().padding(vertical=if(compact)0.dp else 8.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(if(compact)0.dp else 8.dp)){
        FilledTonalIconButton(onClick={model.command("app.key",JSONObject().put("key","up"))},Modifier.size(side)){FilewayIcon(R.drawable.ic_expand_less,"上")}
        Row(horizontalArrangement=Arrangement.spacedBy(if(compact)12.dp else 16.dp),verticalAlignment=Alignment.CenterVertically){
            FilledTonalIconButton(onClick={model.command("app.key",JSONObject().put("key","left"))},Modifier.size(side)){FilewayIcon(R.drawable.ic_arrow_back,"左")}
            FilledIconButton(onClick={model.command("app.key",JSONObject().put("key","ok"))},Modifier.size(center)){FilewayIcon(R.drawable.ic_check,"确认")}
            FilledTonalIconButton(onClick={model.command("app.key",JSONObject().put("key","right"))},Modifier.size(side)){FilewayIcon(R.drawable.ic_arrow_forward,"右")}
        }
        FilledTonalIconButton(onClick={model.command("app.key",JSONObject().put("key","down"))},Modifier.size(side)){FilewayIcon(R.drawable.ic_expand_more,"下")}
    }
}
@Composable private fun TvPhoneToggle(title:String,value:Boolean,onChange:(Boolean)->Unit){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(title,Modifier.weight(1f));Switch(value,onChange)}}
