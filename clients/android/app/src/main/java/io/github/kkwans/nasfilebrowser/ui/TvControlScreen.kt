package io.github.kkwans.nasfilebrowser.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import io.github.kkwans.fileway.remote.*
import io.github.kkwans.fileway.design.R as Art
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.app.*
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayTopAppBar
import org.json.JSONObject
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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
    val sections=listOf("遥控","文件","播放","设置")
    var section by remember(connection.device?.id){mutableStateOf(if(connection.snapshot?.scene in setOf("VIDEO","AUDIO","IMAGE"))"播放"else"遥控")}
    var selectedTab by remember(connection.device?.id){mutableStateOf(false)}
    val sectionScroll=remember(connection.device?.id){List(sections.size){LazyListState()}}
    LaunchedEffect(connection.snapshot?.scene) {
        when (connection.snapshot?.scene) {
            "LOGIN" -> { section = "遥控"; selectedTab = false }
            "VIDEO", "AUDIO", "IMAGE" -> if (!selectedTab) section = "播放"
            else -> Unit
        }
    }
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
        try{scanning=false;cameraError=null;model.pair(tvQrFromImage(context,uri))}
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
        TvPhoneSystemBars()
        Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background){
            BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
            val landscape = maxWidth >= 560.dp && maxWidth > maxHeight * 1.35f
            Column(Modifier.fillMaxSize()) {
                FilewayTopAppBar(if(scanning)"扫描 TV 二维码" else "TV 遥控",if(scanning||landscape)null else tvConnectionSubtitle(connection),R.drawable.ic_arrow_back,"返回",{if(scanning)scanning=false else closeControl()}){
                    if(scanning)FilewayTextButton(onClick={image.launch("image/*")}){Text("从图片识别")}
                    else {
                        if (landscape) Text(
                            tvConnectionSubtitle(connection), Modifier.widthIn(max = 238.dp).padding(end = 8.dp),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        FilewayTextButton(onClick={devicesOpen=true}){Text("设备");FilewayIcon(R.drawable.ic_expand_more,null,Modifier.size(20.dp))}
                    }
                }
                if(scanning){
                    BackHandler{scanning=false}
                    TvQrScanner(Modifier.fillMaxWidth().weight(1f),onCode={scanning=false;model.pair(it)},onError={cameraError=it;scanning=false})
                }else{
                    val snapshot=connection.snapshot
                    if(connection.phase==RemotePhase.CONNECTED&&snapshot!=null){
                        val pager=rememberFilewayPager(sections.indexOf(section).coerceAtLeast(0),sections.size){index->section=sections[index];selectedTab=true}
                        TvPhonePages(pager, sections, landscape, Modifier.weight(1f)) { page, compactHeight ->
                            val currentSection=sections[page]
                            val compactRemote=currentSection=="遥控"&&compactHeight
                            val centerRemote = currentSection == "遥控" && landscape
                            val gap = if (compactRemote) 8.dp else 12.dp
                            LazyColumn(Modifier.fillMaxSize(),state=sectionScroll[page],contentPadding=PaddingValues(horizontal=16.dp,vertical=12.dp),
                                horizontalAlignment=Alignment.CenterHorizontally,
                                verticalArrangement=if(centerRemote)Arrangement.spacedBy(gap,Alignment.CenterVertically)else Arrangement.spacedBy(gap)){
                            when(currentSection){
                                "遥控"->{
                                    val showLogin=snapshot.scene=="LOGIN"
                                    val mediaName=snapshot.content.optJSONObject("media")?.optString("name")?.takeIf{it.isNotBlank()&&it!="null"}
                                    val playing=snapshot.content.optJSONObject("playback")?.opt("playing") as? Boolean
                                    val hasQuickPlayback=snapshot.scene in setOf("VIDEO","AUDIO")&&mediaName!=null&&playing!=null
                                    item{BoxWithConstraints(Modifier.widthIn(max = if (landscape) 576.dp else Dp.Unspecified).fillMaxWidth()){
                                        val labelSize = MaterialTheme.typography.labelLarge.fontSize
                                        val actionWidth = with(LocalDensity.current) { labelSize.toDp() * 2 + 16.dp }.coerceAtLeast(48.dp)
                                        val padSize = if (compactHeight) 168.dp else 192.dp
                                        val splitWidth = padSize + 32.dp + actionWidth * 3
                                        if (landscape && maxWidth >= splitWidth) {
                                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(32.dp,Alignment.CenterHorizontally),
                                                verticalAlignment=Alignment.CenterVertically){
                                                TvPhoneDpad(model, Modifier.width(padSize), compact=compactHeight, landscape=true,
                                                    detailsKey=snapshot.scene=="BROWSE"&&snapshot.capabilities.optInt("fileDetailsKey",0)>0)
                                                Column(Modifier.widthIn(max=288.dp).weight(1f,fill=false),verticalArrangement=Arrangement.spacedBy(16.dp)){
                                                    if(showLogin)TvPhoneLoginAction(phone,model,busy)
                                                    if(hasQuickPlayback)TvPhoneQuickPlayback(model,snapshot,requireNotNull(mediaName),requireNotNull(playing),{section="播放";selectedTab=true},compact=true)
                                                    TvPhoneLandscapeActions(model){inputOpen=true}
                                                }
                                            }
                                        }else{
                                            Column{
                                                if(showLogin){
                                                    TvPhoneLoginAction(phone,model,busy)
                                                    Spacer(Modifier.height(if(compactRemote)8.dp else 12.dp))
                                                }
                                                if(hasQuickPlayback){
                                                    TvPhoneQuickPlayback(model,snapshot,requireNotNull(mediaName),requireNotNull(playing),{section="播放";selectedTab=true})
                                                    Spacer(Modifier.height(8.dp))
                                                }
                                                TvPhoneDpad(model,compact=compactRemote,
                                                    detailsKey=snapshot.scene=="BROWSE"&&snapshot.capabilities.optInt("fileDetailsKey",0)>0)
                                                Spacer(Modifier.height(8.dp))
                                                TvPhoneRemoteActions(model){inputOpen=true}
                                            }
                                        }
                                    }}
                                }
                                "文件"->{
                                    item{FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                                        val routes=listOf("FILES" to "文件","VIDEO" to "视频","MUSIC" to "音乐","IMAGES" to "图片","FAVORITES" to "收藏","RECENT" to "最近播放")+
                                            if(snapshot.capabilities.optInt("recentAccessPage",0)>0)listOf("RECENT_ACCESS" to "最近访问")else emptyList()
                                        routes.forEach{(route,title)->FilterChip(selected=snapshot.content.optString("route")==route,onClick={model.command("app.navigate",JSONObject().put("route",route))},label={Text(title)},shape=RoundedCornerShape(12.dp))}
                                    }}
                                    snapshot.content.optString("directory").takeIf{it.isNotBlank()&&it!="null"}?.let{directory->item{Text(directory,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
                                    val files=snapshot.content.optJSONArray("files")
                                    if(files!=null)items(files.length(),key={files.getJSONObject(it).getString("id")}){i->val file=files.getJSONObject(i);TvPhoneDeviceRow(file.getString("name"),tvFileKind(file),tvFileIcon(file)){model.command("media.open",JSONObject().put("id",file.getString("id")))}}
                                    items(moreFiles,key={"more/"+it.getString("id")}){file->TvPhoneDeviceRow(file.getString("name"),tvFileKind(file),tvFileIcon(file)){model.command("media.open",JSONObject().put("id",file.getString("id")))}}
                                    if((files?.length()?:0)+moreFiles.size<snapshot.content.optInt("total"))item{FilewayOutlinedButton(onClick=model::loadMore,Modifier.fillMaxWidth()){Text("更多文件")}}
                                }
                                "播放"->item{key(snapshot.connectionId,snapshot.sessionEpoch,snapshot.mediaEpoch){TvPhoneMediaControls(model,snapshot){section="文件";selectedTab=true}}}
                                "设置"->{
                                    item{
                                        val source=phone.tvAuthorizationContext()
                                        SettingsGroup("账号与连接"){
                                            snapshot.content.optJSONObject("session")?.let{session->
                                                val details=listOf(session.optString("username"),session.optString("origin")).filter{it.isNotBlank()&&it!="null"}.joinToString(" · ")
                                                if(details.isNotBlank())Column(Modifier.padding(horizontal=16.dp,vertical=12.dp)){
                                                    Text("TV 当前登录",style=MaterialTheme.typography.bodyMedium)
                                                    Text(details,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                            }
                                            SettingsAction("将手机当前账号用于 TV",if(busy)"正在授权…"else if(source==null)"请先在手机登录服务器"else"在 TV 上独立登录",R.drawable.ic_person,enabled=!busy&&source!=null,navigation=false){model.authorize(phone,source)}
                                            SettingsAction("连接另一部手机","在 TV 上打开连接入口",R.drawable.ic_person){model.command("device.enablePairing")}
                                        }
                                    }
                                    val prefs=snapshot.content.optJSONObject("preferences")?:JSONObject()
                                    if(prefs.has("seekStepSeconds")||prefs.has("controlsTimeoutSeconds"))item{
                                        SettingsGroup("播放"){
                                            if(prefs.has("seekStepSeconds"))Column(Modifier.padding(horizontal=16.dp,vertical=8.dp)){
                                                var step by remember(snapshot.connectionId){mutableStateOf<Float?>(null)}
                                                val value=step?:prefs.optInt("seekStepSeconds",10).coerceIn(3,30).toFloat()
                                                Text("快进 / 后退 ${value.toInt()} 秒",style=MaterialTheme.typography.bodyMedium)
                                                Slider(value,onValueChange={step=it},onValueChangeFinished={step?.let{model.command("app.setPreference",JSONObject().put("key","seekStepSeconds").put("value",it.toInt()))};step=null},valueRange=3f..30f,steps=26)
                                            }
                                            if(prefs.has("controlsTimeoutSeconds"))Column(Modifier.padding(horizontal=16.dp,vertical=8.dp)){
                                                var timeout by remember(snapshot.connectionId){mutableStateOf<Float?>(null)}
                                                val value=timeout?:prefs.optInt("controlsTimeoutSeconds",5).coerceIn(2,30).toFloat()
                                                Text("播放控件自动隐藏 ${value.toInt()} 秒",style=MaterialTheme.typography.bodyMedium)
                                                Slider(value,onValueChange={timeout=it},onValueChangeFinished={timeout?.let{model.command("app.setPreference",JSONObject().put("key","controlsTimeoutSeconds").put("value",it.toInt()))};timeout=null},valueRange=2f..30f,steps=27)
                                            }
                                        }
                                    }
                                    if(prefs.has("detailsHoldMillis"))item{
                                        SettingsGroup("遥控操作"){
                                            Column(Modifier.padding(horizontal=16.dp,vertical=8.dp)){
                                                var hold by remember(snapshot.connectionId){mutableStateOf<Float?>(null)}
                                                val value=hold?:prefs.optInt("detailsHoldMillis",1000).coerceIn(500,2000)/1000f
                                                Text("TV 遥控器查看详情长按 ${value} 秒",style=MaterialTheme.typography.bodyMedium)
                                                Slider(value,onValueChange={hold=it},onValueChangeFinished={
                                                    hold?.let{model.command("app.setPreference",JSONObject().put("key","detailsHoldMillis").put("value",(it*4).roundToInt()*250))};hold=null
                                                },valueRange=.5f..2f,steps=5)
                                            }
                                        }
                                    }
                                    if(listOf("dark","clockAlways","speedAlways","gridColumns").any(prefs::has))item{
                                        SettingsGroup("显示"){
                                            if(prefs.has("dark"))TvPhoneToggle("深色外观",prefs.optBoolean("dark")){model.command("app.setPreference",JSONObject().put("key","dark").put("value",it))}
                                            if(prefs.has("clockAlways"))TvPhoneToggle("常驻北京时间",prefs.optBoolean("clockAlways")){model.command("app.setPreference",JSONObject().put("key","clockAlways").put("value",it))}
                                            if(prefs.has("speedAlways"))TvPhoneToggle("常驻缓冲速度",prefs.optBoolean("speedAlways")){model.command("app.setPreference",JSONObject().put("key","speedAlways").put("value",it))}
                                            if(prefs.has("gridColumns"))Column(Modifier.padding(horizontal=16.dp,vertical=8.dp)){
                                                Text("文件列数",style=MaterialTheme.typography.bodyMedium)
                                                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){(2..6).forEach{n->FilterChip(selected=prefs.optInt("gridColumns")==n,onClick={model.command("app.setPreference",JSONObject().put("key","gridColumns").put("value",n))},label={Text("$n 列")},shape=RoundedCornerShape(12.dp))}}
                                            }
                                        }
                                    }
                                }
                            }
                            snapshot.content.optString("error").takeIf{it!="null"&&it.isNotBlank()}?.let{message->item{Text(message,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodyMedium)}}
                            }
                        }
                    }else{
                        LazyColumn(Modifier.fillMaxWidth().weight(1f),contentPadding=PaddingValues(horizontal=16.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                            if(connection.device!=null)item{TvPhoneTargetState(connection,model)}
                            else {
                                item{Text("选择 TV",style=MaterialTheme.typography.titleMedium)}
                                tvDeviceChoices(paired,discovery,model,permitted,
                                    onSearch={if(permitted)model.search()else lan.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)},
                                    onScan=::scan,onImage={image.launch("image/*")})
                            }
                            cameraError?.let{message->item{Text(message,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodyMedium)}}
                        }
                    }
                }
            }
            }
        }
    }
    if(inputOpen)FileOperationSheet("输入到 TV",{inputOpen=false}) {
        Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){FilterChip(selected=target=="search",onClick={target="search"},label={Text("搜索")},shape=RoundedCornerShape(12.dp));FilterChip(selected=target=="focused",onClick={target="focused"},label={Text("当前输入框")},shape=RoundedCornerShape(12.dp))}
            OutlinedTextField(value=input,onValueChange={input=it.take(4096)},label={Text(if(target=="search")"搜索内容"else"输入内容")},shape=RoundedCornerShape(12.dp),modifier=Modifier.fillMaxWidth(),maxLines=4)
            FilewayButton(onClick={model.command("app.inputText",JSONObject().put("target",target).put("text",input));inputOpen=false},modifier=Modifier.fillMaxWidth()){Text(if(target=="search")"在 TV 搜索"else"发送到 TV")}
        }
    }
    if(devicesOpen)FileOperationSheet(if(connection.device!=null)"当前 TV"else"选择 TV",{devicesOpen=false}) {
        LazyColumn(Modifier.fillMaxWidth().weight(1f,fill=false),verticalArrangement=Arrangement.spacedBy(12.dp)){
            if(connection.device!=null)item{TvPhoneTargetState(connection,model){model.disconnect();devicesOpen=false}}
            else tvDeviceChoices(paired,discovery,model,permitted,
                onSearch={if(permitted)model.search()else lan.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)},
                onScan={devicesOpen=false;scan()},onImage={devicesOpen=false;image.launch("image/*")},onSelected={devicesOpen=false})
            cameraError?.let{message->item{Text(message,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodyMedium)}}
        }
    }
}

@Composable
private fun TvPhonePages(
    pager: PagerState, sections: List<String>, landscape: Boolean, modifier: Modifier,
    page: @Composable (Int, Boolean) -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        if (!landscape) FilewayPagerTabs(pager, sections)
        Row(Modifier.fillMaxSize()) {
            if (landscape) {
                val scope = rememberCoroutineScope()
                Column(
                    Modifier.width(110.dp).fillMaxHeight().selectableGroup().padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                ) {
                    sections.forEachIndexed { index, label ->
                        val selected = pager.currentPage == index
                        val shape = RoundedCornerShape(12.dp)
                        Box(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(shape)
                                .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.background)
                                .selectable(selected, role = Role.Tab, onClick = { scope.launch { pager.animateScrollToPage(index) } })
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(label, style = MaterialTheme.typography.labelLarge,
                                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                VerticalDivider(Modifier.fillMaxHeight().padding(vertical = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                val compactHeight = maxHeight < 360.dp
                HorizontalPager(pager, Modifier.fillMaxSize()) { index -> page(index, compactHeight) }
            }
        }
    }
}

private fun tvConnectionSubtitle(connection:RemoteConnectionState):String {
    val status=when(connection.phase){
        RemotePhase.OFFLINE->"未连接"
        RemotePhase.PAIRING->"正在配对"
        RemotePhase.CONNECTING->"正在连接"
        RemotePhase.CONNECTED->"已连接"
        RemotePhase.BUSY->"连接暂不可用"
        RemotePhase.FAILED->"连接失败"
    }
    return connection.device?.name?.let{"$it · $status"}?:status
}

private fun tvFileKind(file:JSONObject):String = if(file.optBoolean("directory"))"文件夹"else when(file.optString("kind")){
    "VIDEO"->"视频";"AUDIO"->"音乐";"IMAGE"->"图片";else->"文件"
}

private fun tvFileIcon(file:JSONObject):Int = if(file.optBoolean("directory"))R.drawable.ic_folder else when(file.optString("kind")){
    "VIDEO"->R.drawable.ic_video;"AUDIO"->R.drawable.ic_audio;"IMAGE"->R.drawable.ic_image;else->R.drawable.ic_file
}

/** Keep the current target visible until the user explicitly ends the attempt or connection. */
@Composable private fun TvPhoneTargetState(connection:RemoteConnectionState,model:TvControlModel,onDisconnect:()->Unit=model::disconnect){
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text(connection.device?.name.orEmpty(),style=MaterialTheme.typography.titleMedium)
        when(connection.phase){
            RemotePhase.PAIRING->{
                Text("等待 TV 允许连接", style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            RemotePhase.CONNECTING->{Text("正在连接…",style=MaterialTheme.typography.bodyMedium);LinearProgressIndicator(Modifier.fillMaxWidth())}
            RemotePhase.CONNECTED->{
                Text(if(connection.snapshot==null)"正在同步 TV 状态…"else"已连接",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(connection.snapshot==null)LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            RemotePhase.FAILED,RemotePhase.BUSY->{
                Text(connection.error?:"连接暂不可用",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.error)
                FilewayButton(onClick=model::reconnectNow,modifier=Modifier.fillMaxWidth()){Text("重新连接")}
            }
            RemotePhase.OFFLINE->Text("未连接",style=MaterialTheme.typography.bodyMedium)
        }
        FilewayOutlinedButton(onClick=onDisconnect,modifier=Modifier.fillMaxWidth()){
            Text(if(connection.phase in setOf(RemotePhase.PAIRING,RemotePhase.CONNECTING))"取消连接"else"断开连接")
        }
    }
}

private fun LazyListScope.tvDeviceChoices(paired:List<PairedTv>,discovery:TvDiscoveryState,model:TvControlModel,permitted:Boolean,
    onSearch:()->Unit,onScan:()->Unit,onImage:()->Unit,onSelected:()->Unit={}){
    item{Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
        FilewayOutlinedButton(onClick=onSearch,modifier=Modifier.weight(1f)){Text(if(permitted)"重新搜索"else"允许局域网访问")}
        FilewayButton(onClick=onScan,modifier=Modifier.weight(1f)){Text("扫码配对")}
    }}
    item{FilewayTextButton(onClick=onImage,modifier=Modifier.fillMaxWidth()){Text("从图片识别二维码")}}
    if(paired.isNotEmpty()){
        item{Text("已配对",style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        items(paired,key={"saved/"+it.id}){device->
            val found=discovery.devices.firstOrNull{permitted&&it.id==device.id&&it.fingerprint==device.fingerprint}
            TvPhoneDeviceRow(device.name,if(!device.trusted)"配对待确认"else if(found!=null)"局域网可用"else"已配对",Art.drawable.fileway_ic_cast){
                val target=if(found!=null)device.descriptor().at(found.address,found.port)else device.descriptor()
                if(device.trusted)model.connect(target)else model.requestPairing(target)
                onSelected()
            }
        }
    }
    val discovered=if(permitted)discovery.devices.filter{candidate->paired.none{it.id==candidate.id}}else emptyList()
    if(discovered.isNotEmpty()){
        item{Text("发现的 TV",style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        items(discovered,key={"found/"+it.id}){device->TvPhoneDeviceRow(device.name,"请求连接",Art.drawable.fileway_ic_cast){model.requestPairing(device);onSelected()}}
    }
    if(!permitted)item{Text("未获得局域网访问权限，无法搜索局域网中的 TV",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.error)}
    if(permitted&&discovery.searching)item{Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
        CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp)
        Text("正在搜索 TV…",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }}
    if(permitted&&!discovery.searching&&discovered.isEmpty()&&paired.isEmpty())item{
        Text(discovery.error?:"未发现 TV",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if(permitted&&(discovered.isNotEmpty()||paired.isNotEmpty()))discovery.error?.let{message->item{
        Text(message,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
    }}
}

@Composable internal fun rememberTvControlModel():TvControlModel {
    val owner=requireNotNull(LocalViewModelStoreOwner.current)
    return remember(owner){ViewModelProvider(owner)[TvControlModel::class.java]}
}

@Composable private fun TvPhoneLoginAction(phone:ClientModel,model:TvControlModel,busy:Boolean){
    val source=phone.tvAuthorizationContext()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("TV 尚未登录", style = MaterialTheme.typography.titleMedium)
        if (source == null) {
            Text("先在手机登录栖卷，再为 TV 授权。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else FilewayButton(onClick={model.authorize(phone,source)},enabled=!busy,
            modifier=Modifier.heightIn(min=48.dp)){
            Text(if(busy)"正在授权…" else "用手机当前账号登录 TV")
        }
    }
}

@Composable private fun TvPhoneDeviceRow(title:String,subtitle:String,leading:Int?=null,onClick:()->Unit){
    Surface(onClick=onClick,shape=RoundedCornerShape(12.dp),color=MaterialTheme.colorScheme.surface,modifier=Modifier.fillMaxWidth()){
        Row(Modifier.heightIn(min=56.dp).padding(horizontal=16.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
            if(leading!=null)FilewayIcon(leading,null,Modifier.size(24.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)){
                Text(title,style=MaterialTheme.typography.bodyMedium)
                if(subtitle.isNotBlank())Text(subtitle,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FilewayIcon(R.drawable.ic_arrow_forward,null,Modifier.size(18.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
/** The visible media and its action come from the same source-fenced snapshot. */
@Composable private fun TvPhoneQuickPlayback(model:TvControlModel,snapshot:RemoteSnapshot,name:String,playing:Boolean,onOpen:()->Unit,
    modifier:Modifier=Modifier,compact:Boolean=false){
    Surface(modifier,shape=RoundedCornerShape(12.dp),color=MaterialTheme.colorScheme.surface){
        Row(Modifier.fillMaxWidth().padding(start=12.dp,end=4.dp),verticalAlignment=Alignment.CenterVertically){
            Box(Modifier.weight(1f).heightIn(min=48.dp).clickable(role=Role.Button,onClick=onOpen),contentAlignment=Alignment.CenterStart){
                Text(name,style=MaterialTheme.typography.bodyMedium,maxLines=if(compact)1 else 2,overflow=TextOverflow.Ellipsis)
            }
            FilewayIconButton(onClick={model.commandFor(snapshot,if(playing)"playback.pause"else"playback.play")},modifier=Modifier.size(48.dp)){
                FilewayIcon(if(playing)Art.drawable.fileway_ic_pause else Art.drawable.fileway_ic_play_arrow,if(playing)"暂停 TV 播放"else"播放 TV 媒体")
            }
        }
    }
}

@Composable private fun TvPhoneRemoteActions(model:TvControlModel,onKeyboard:()->Unit){
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
        val target=Modifier.weight(1f).heightIn(min=48.dp)
        val padding=PaddingValues(horizontal=8.dp)
        FilewayOutlinedButton(onClick={model.command("app.back")},target,contentPadding=padding){Text("返回")}
        FilewayOutlinedButton(onClick={model.command("app.navigate",JSONObject().put("route","HOME"))},target,contentPadding=padding){Text("首页")}
        FilewayOutlinedButton(onClick=onKeyboard,target,contentPadding=padding){Text("键盘")}
    }
}

@Composable
private fun TvPhoneLandscapeActions(model: TvControlModel, onKeyboard: () -> Unit) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().padding(4.dp)) {
            TvPhoneLandscapeAction("返回", R.drawable.ic_arrow_back, Modifier.weight(1f)) { model.command("app.back") }
            TvPhoneLandscapeAction("首页", Art.drawable.fileway_ic_home, Modifier.weight(1f)) {
                model.command("app.navigate", JSONObject().put("route", "HOME"))
            }
            TvPhoneLandscapeAction("键盘", Art.drawable.fileway_ic_text, Modifier.weight(1f), onKeyboard)
        }
    }
}

@Composable
private fun TvPhoneLandscapeAction(label: String, icon: Int, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.heightIn(min = 52.dp).clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
    ) {
        FilewayIcon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The full-screen Dialog owns its system bars independently of the Activity. */
@Composable private fun TvPhoneSystemBars(){
    val view=LocalView.current
    val window=(view.parent as? DialogWindowProvider)?.window
    val light=MaterialTheme.colorScheme.background.luminance()>.5f
    SideEffect {
        window?.let{
            @Suppress("DEPRECATION")
            it.statusBarColor=android.graphics.Color.TRANSPARENT
            @Suppress("DEPRECATION")
            it.navigationBarColor=android.graphics.Color.TRANSPARENT
            if(Build.VERSION.SDK_INT>=29)it.isNavigationBarContrastEnforced=false
            val bars=WindowCompat.getInsetsController(it,view)
            bars.isAppearanceLightStatusBars=light
            bars.isAppearanceLightNavigationBars=light
        }
    }
}

@Composable private fun TvPhoneDpad(model:TvControlModel,modifier:Modifier=Modifier,compact:Boolean=false,detailsKey:Boolean=false,landscape:Boolean=false){
    val side = when { landscape && compact -> 56.dp; landscape -> 64.dp; compact -> 48.dp; else -> 72.dp }
    Box(modifier.fillMaxWidth().padding(vertical=if(compact)0.dp else 8.dp),contentAlignment=Alignment.Center){
        Surface(Modifier.size(side*3),shape=CircleShape,color=MaterialTheme.colorScheme.surface,
            border = if (landscape) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null){
            Column(horizontalAlignment=Alignment.CenterHorizontally){
                FilewayIconButton(onClick={model.command("app.key",JSONObject().put("key","up"))},Modifier.size(side)){FilewayIcon(R.drawable.ic_expand_less,"上")}
                Row(verticalAlignment=Alignment.CenterVertically){
                    FilewayIconButton(onClick={model.command("app.key",JSONObject().put("key","left"))},Modifier.size(side)){
                        FilewayIcon(R.drawable.ic_expand_more,"左",Modifier.rotate(90f))
                    }
                    Box(Modifier.size(side),contentAlignment=Alignment.Center){
                        TvPhoneConfirmKey(compact, detailsKey,
                            onConfirm={model.command("app.key",JSONObject().put("key","ok"))},
                            onDetails={model.command("app.key",JSONObject().put("key","details"))}, iconOnly=landscape)
                    }
                    FilewayIconButton(onClick={model.command("app.key",JSONObject().put("key","right"))},Modifier.size(side)){
                        FilewayIcon(R.drawable.ic_expand_more,"右",Modifier.rotate(-90f))
                    }
                }
                FilewayIconButton(onClick={model.command("app.key",JSONObject().put("key","down"))},Modifier.size(side)){FilewayIcon(R.drawable.ic_expand_more,"下")}
            }
        }
    }
}

@Composable internal fun TvPhoneConfirmKey(compact:Boolean,detailsKey:Boolean,onConfirm:()->Unit,onDetails:()->Unit,iconOnly:Boolean=false){
    val size=if(compact)48.dp else 64.dp
    val content: @Composable () -> Unit = {
        if (iconOnly) FilewayIcon(R.drawable.ic_check, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onPrimary)
        else Text("确定",modifier=Modifier.clearAndSetSemantics{},style=MaterialTheme.typography.labelMedium,
            color=MaterialTheme.colorScheme.onPrimary,maxLines=1,softWrap=false)
    }
    if(detailsKey) Box(Modifier.size(size).clip(CircleShape)
        .background(MaterialTheme.colorScheme.primary)
        .combinedClickable(role=Role.Button,onLongClickLabel="查看详情",onClick=onConfirm,onLongClick=onDetails)
        .semantics{contentDescription="确认"},contentAlignment=Alignment.Center){
        content()
    } else FilledIconButton(onClick=onConfirm,Modifier.size(size).semantics{contentDescription="确认"}){
        content()
    }
}

@Composable private fun TvPhoneToggle(title:String,value:Boolean,onChange:(Boolean)->Unit){
    Row(Modifier.fillMaxWidth().toggleable(value=value,role=Role.Switch,onValueChange=onChange).heightIn(min=56.dp).padding(horizontal=16.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically){
        Text(title,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
        Switch(value,onCheckedChange=null,modifier=Modifier.clearAndSetSemantics{})
    }
}
