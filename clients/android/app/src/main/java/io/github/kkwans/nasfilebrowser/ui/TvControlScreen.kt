package io.github.kkwans.nasfilebrowser.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import io.github.kkwans.fileway.remote.*
import io.github.kkwans.nasfilebrowser.R
import io.github.kkwans.nasfilebrowser.app.*
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayTopAppBar
import org.json.JSONObject
import kotlinx.coroutines.launch

@Composable internal fun TvControlScreen(phone:ClientModel,onClose:()->Unit){
    val owner=requireNotNull(LocalViewModelStoreOwner.current)
    val model=remember(owner){ViewModelProvider(owner)[TvControlModel::class.java]}
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
    var section by remember{mutableStateOf("遥控")}
    val lan=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){permitted=it;if(it)model.search()}
    val camera=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){if(it){scanning=true;cameraError=null}else cameraError="扫码需要相机权限"}
    val image=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()){uri->if(uri!=null)scope.launch{
        try{scanning=false;model.pair(tvQrFromImage(context,uri))}
        catch(error:Exception){if(error is kotlinx.coroutines.CancellationException)throw error;cameraError=error.message?:"二维码读取失败"}
    }}
    fun scan(){if(context.checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED){scanning=true;cameraError=null}else camera.launch(Manifest.permission.CAMERA)}
    LaunchedEffect(permitted){if(permitted)model.search()else lan.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)}
    DisposableEffect(Unit){onDispose{model.stopSearch()}}
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)){
        Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background){
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()){
                FilewayTopAppBar(if(scanning)"扫描 TV 二维码" else "连接 TV",null,R.drawable.ic_arrow_back,"返回",{if(scanning)scanning=false else onClose()}){
                    FilewayTextButton(onClick={image.launch("image/*")}){Text("从图片识别")}
                    FilewayTextButton(onClick={scan()}){Text("扫码")}
                }
                if(scanning){
                    BackHandler{scanning=false}
                    TvQrScanner(Modifier.fillMaxWidth().weight(1f),onCode={scanning=false;model.pair(it)},onError={cameraError=it;scanning=false})
                }else{
                    val snapshot=connection.snapshot
                    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                        if(connection.phase!=RemotePhase.CONNECTED){
                            item{Text(when(connection.phase){RemotePhase.CONNECTING->"正在连接…";RemotePhase.PAIRING->"正在配对…";else->"选择电视"},style=MaterialTheme.typography.titleMedium)}
                            items(paired,key={"saved/"+it.id}){device->
                                val found=discovery.devices.firstOrNull{it.id==device.id&&it.fingerprint==device.fingerprint}
                                TvPhoneDeviceRow(device.name,if(device.trusted)"已配对" else "配对待确认"){
                                    model.connect(if(found!=null)device.descriptor().at(found.address,found.port)else device.descriptor())
                                }
                            }
                            items(discovery.devices.filter{candidate->paired.none{it.id==candidate.id}},key={"found/"+it.id}){device->TvPhoneDeviceRow(device.name,"局域网 TV"){model.requestPairing(device);scan()}}
                            if(discovery.searching)item{LinearProgressIndicator(Modifier.fillMaxWidth())}
                            if(!discovery.searching&&discovery.devices.isEmpty()&&paired.isEmpty())item{Text(discovery.error?:"未发现 TV，可扫描 TV 上的二维码连接",style=MaterialTheme.typography.bodyMedium)}
                            item{Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){FilewayOutlinedButton(onClick={if(permitted)model.search()else lan.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)}){Text("重新搜索")};FilewayButton(onClick={scan()}){Text("扫码配对")}}}
                        }else if(snapshot!=null){
                            item{Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                                Column(Modifier.weight(1f)){Text(connection.device?.name.orEmpty(),style=MaterialTheme.typography.titleMedium);Text(snapshot!!.content.optJSONObject("media")?.optString("name")?:"已连接",style=MaterialTheme.typography.bodyMedium,maxLines=2)}
                                FilewayTextButton(onClick=model::disconnect){Text("断开")}
                            }}
                            item{
                                val source=phone.tvAuthorizationContext()
                                source?.let{Text("${it.api.identity.username} · ${it.profile.name}",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                                FilewayButton(onClick={model.authorize(phone,source)},enabled=!busy&&source!=null,modifier=Modifier.fillMaxWidth()){Text(if(busy)"正在授权…" else if(snapshot!!.scene=="LOGIN")"用此账号登录 TV" else "切换 TV 到此账号")}
                            }
                            item{SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()){
                                listOf("遥控","文件","播放","设置").forEachIndexed{i,label->SegmentedButton(selected=section==label,onClick={section=label},shape=SegmentedButtonDefaults.itemShape(i,4)){Text(label)}}
                            }}
                            when(section){
                                "遥控"->{
                                    item{TvPhoneDpad(model)}
                                    item{Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){FilewayOutlinedButton(onClick={model.command("app.back")},Modifier.weight(1f)){Text("返回")};FilewayOutlinedButton(onClick={model.command("app.navigate",JSONObject().put("route","HOME"))},Modifier.weight(1f)){Text("首页")}}}
                                    item{Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){FilterChip(selected=target=="search",onClick={target="search"},label={Text("搜索")});FilterChip(selected=target=="focused",onClick={target="focused"},label={Text("当前输入框")})}}
                                    item{OutlinedTextField(value=input,onValueChange={input=it.take(4096)},label={Text(if(target=="search")"搜索内容" else "输入内容")},shape=RoundedCornerShape(12.dp),modifier=Modifier.fillMaxWidth(),maxLines=4)}
                                    item{FilewayButton(onClick={model.command("app.inputText",JSONObject().put("target",target).put("text",input))},modifier=Modifier.fillMaxWidth()){Text(if(target=="search")"在 TV 搜索" else "发送到 TV")}}
                                }
                                "文件"->{
                                    item{FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("FILES" to "文件","VIDEO" to "视频","MUSIC" to "音乐","IMAGES" to "图片","FAVORITES" to "收藏","RECENT" to "最近").forEach{(route,title)->FilterChip(selected=snapshot!!.content.optString("route")==route,onClick={model.command("app.navigate",JSONObject().put("route",route))},label={Text(title)})}}}
                                    item{Text(snapshot!!.content.optString("directory"),style=MaterialTheme.typography.bodyMedium)}
                                    val files=snapshot!!.content.optJSONArray("files")
                                    if(files!=null)items(files.length(),key={files.getJSONObject(it).getString("id")}){i->val file=files.getJSONObject(i);TvPhoneDeviceRow(file.getString("name"),if(file.optBoolean("directory"))"文件夹"else file.optString("kind")){model.command("media.open",JSONObject().put("id",file.getString("id")))}}
                                    items(moreFiles,key={"more/"+it.getString("id")}){file->TvPhoneDeviceRow(file.getString("name"),if(file.optBoolean("directory"))"文件夹"else file.optString("kind")){model.command("media.open",JSONObject().put("id",file.getString("id")))}}
                                    if((files?.length()?:0)+moreFiles.size<snapshot!!.content.optInt("total"))item{FilewayOutlinedButton(onClick=model::loadMore,Modifier.fillMaxWidth()){Text("更多文件")}}
                                }
                                "播放"->tvPhonePlayback(model,snapshot!!)
                                "设置"->{
                                    item{Text("TV 设置",style=MaterialTheme.typography.titleMedium)}
                                    snapshot!!.content.optJSONObject("session")?.let{session->item{Text("TV 当前登录\n${session.optString("username")}\n${session.optString("origin")}",style=MaterialTheme.typography.bodyMedium)}}
                                    val prefs=snapshot!!.content.optJSONObject("preferences")?:JSONObject()
                                    item{TvPhoneToggle("深色外观",prefs.optBoolean("dark")){model.command("app.setPreference",JSONObject().put("key","dark").put("value",it))}}
                                    item{TvPhoneToggle("常驻北京时间",prefs.optBoolean("clockAlways")){model.command("app.setPreference",JSONObject().put("key","clockAlways").put("value",it))}}
                                    item{TvPhoneToggle("常驻缓冲速度",prefs.optBoolean("speedAlways")){model.command("app.setPreference",JSONObject().put("key","speedAlways").put("value",it))}}
                                    item{Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){(2..6).forEach{n->FilterChip(selected=prefs.optInt("gridColumns")==n,onClick={model.command("app.setPreference",JSONObject().put("key","gridColumns").put("value",n))},label={Text("$n 列")})}}}
                                    item{FilewayOutlinedButton(onClick={model.command("device.enablePairing")},Modifier.fillMaxWidth()){Text("连接另一部手机")}}
                                }
                            }
                        }
                        (cameraError?:notice?:connection.error?:snapshot?.content?.optString("error")?.takeIf{it!="null"&&it.isNotBlank()})?.let{message->item{Text(message,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodyMedium)}}
                        snapshot?.content?.optString("notice")?.takeIf{it!="null"&&it.isNotBlank()}?.let{message->item{Text(message,style=MaterialTheme.typography.bodyMedium)}}
                    }
                }
            }
        }
    }
}

@Composable private fun TvPhoneDeviceRow(title:String,subtitle:String,onClick:()->Unit){
    Surface(onClick=onClick,shape=RoundedCornerShape(12.dp),color=MaterialTheme.colorScheme.surfaceContainer,modifier=Modifier.fillMaxWidth()){
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){Text(title,style=MaterialTheme.typography.bodyLarge,maxLines=3);Text(subtitle,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
    }
}
@Composable private fun TvPhoneDpad(model:TvControlModel){
    Column(Modifier.fillMaxWidth().padding(vertical=8.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(8.dp)){
        FilledTonalIconButton(onClick={model.command("app.key",JSONObject().put("key","up"))},Modifier.size(64.dp)){FilewayIcon(R.drawable.ic_expand_less,"上")}
        Row(horizontalArrangement=Arrangement.spacedBy(16.dp),verticalAlignment=Alignment.CenterVertically){
            FilledTonalIconButton(onClick={model.command("app.key",JSONObject().put("key","left"))},Modifier.size(64.dp)){FilewayIcon(R.drawable.ic_arrow_back,"左")}
            FilledIconButton(onClick={model.command("app.key",JSONObject().put("key","ok"))},Modifier.size(76.dp)){Text("确认",style=MaterialTheme.typography.titleMedium)}
            FilledTonalIconButton(onClick={model.command("app.key",JSONObject().put("key","right"))},Modifier.size(64.dp)){FilewayIcon(R.drawable.ic_arrow_forward,"右")}
        }
        FilledTonalIconButton(onClick={model.command("app.key",JSONObject().put("key","down"))},Modifier.size(64.dp)){FilewayIcon(R.drawable.ic_expand_more,"下")}
    }
}
@Composable private fun TvPhoneToggle(title:String,value:Boolean,onChange:(Boolean)->Unit){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(title,Modifier.weight(1f));Switch(value,onChange)}}

private fun androidx.compose.foundation.lazy.LazyListScope.tvPhonePlayback(model:TvControlModel,snapshot:RemoteSnapshot){
    val player=snapshot.content.optJSONObject("playback")?:JSONObject()
    item{
        var seeking by remember{mutableStateOf<Float?>(null)}
        val duration=player.optLong("durationMs").coerceAtLeast(1)
        val position=player.optLong("positionMs").coerceIn(0,duration)
        Text("${tvRemoteTime(position)} / ${tvRemoteTime(duration)}",style=MaterialTheme.typography.bodyMedium)
        Slider(value=seeking?:position.toFloat()/duration,onValueChange={seeking=it},onValueChangeFinished={seeking?.let{model.command("playback.seekTo",JSONObject().put("positionMs",(it*duration).toLong()))};seeking=null},enabled=player.optBoolean("seekable"))
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly,verticalAlignment=Alignment.CenterVertically){
            IconButton(onClick={model.command("queue.previous")}){FilewayIcon(R.drawable.ic_skip_previous,"上一项")}
            FilledIconButton(onClick={model.command(if(player.optBoolean("playing"))"playback.pause"else"playback.play")},Modifier.size(64.dp)){FilewayIcon(if(player.optBoolean("playing"))R.drawable.art_pause else R.drawable.art_play,if(player.optBoolean("playing"))"暂停"else"播放")}
            IconButton(onClick={model.command("queue.next")}){FilewayIcon(R.drawable.ic_skip_next,"下一项")}
        }
    }
    item{
        var volume by remember{mutableStateOf<Float?>(null)}
        Text("应用音量 ${volume?.toInt()?:player.optInt("volume")}%")
        Slider(volume?:player.optInt("volume").toFloat(),onValueChange={volume=it},onValueChangeFinished={volume?.let{model.command("playback.setVolume",JSONObject().put("volume",it.toInt()))};volume=null},valueRange=0f..100f,steps=99)
    }
    item{FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf(.5,1.0,1.25,1.5,2.0).forEach{rate->FilterChip(selected=player.optDouble("rate")==rate,onClick={model.command("playback.setRate",JSONObject().put("rate",rate))},label={Text("${rate}×")})}}}
    item{Text("音轨",style=MaterialTheme.typography.titleMedium)}
    val audio=player.optJSONArray("audio")
    if(audio!=null)items(audio.length()){i->val track=audio.getJSONObject(i);TvPhoneTrack(track.optString("title"),player.optInt("selectedAudio")==track.getInt("id")){model.command("audio.select",JSONObject().put("id",track.getInt("id")).put("source",track.optBoolean("source")))}}
    item{Text("字幕",style=MaterialTheme.typography.titleMedium);FilewayOutlinedButton(onClick={model.command("subtitle.disable")}){Text("关闭字幕")}}
    val source=player.optJSONArray("sourceSubtitles")
    val embedded=player.optJSONArray("subtitles")
    val tracks=if(source!=null&&source.length()>0)source else embedded
    if(tracks!=null)items(tracks.length()){i->val track=tracks.getJSONObject(i);val useSource=tracks===source;TvPhoneTrack(track.optString("title"),player.optInt(if(useSource)"selectedSourceSubtitle"else"selectedSubtitle")==track.getInt("id")){model.command("subtitle.select",JSONObject().put("id",track.getInt("id")).put("source",useSource))}}
    val sidecars=player.optJSONArray("sidecars")
    if(sidecars!=null)items(sidecars.length()){i->val file=sidecars.getJSONObject(i);TvPhoneTrack(file.optString("name"),false){model.command("subtitle.selectNasFile",JSONObject().put("id",file.getString("id")))}}
    item{Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){Text("字幕延迟 ${player.optLong("subtitleDelayMs")} ms",Modifier.weight(1f));FilewayOutlinedButton(onClick={model.command("subtitle.setDelay",JSONObject().put("delayMs",player.optLong("subtitleDelayMs")-100))}){Text("−100")};FilewayOutlinedButton(onClick={model.command("subtitle.setDelay",JSONObject().put("delayMs",player.optLong("subtitleDelayMs")+100))}){Text("+100")}}}
    item{Text("画面模式",style=MaterialTheme.typography.titleMedium);FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("AUTO" to "自动","NATIVE" to "原生","SDR" to "SDR 兼容").forEach{(mode,title)->FilterChip(selected=player.optString("hdrMode")==mode,onClick={model.command("playback.setHdrMode",JSONObject().put("mode",mode))},enabled=mode!="SDR"||player.optBoolean("sdrAvailable"),label={Text(title)})}}}
}
@Composable private fun TvPhoneTrack(title:String,selected:Boolean,onClick:()->Unit){Surface(onClick=onClick,shape=RoundedCornerShape(12.dp),color=if(selected)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,modifier=Modifier.fillMaxWidth()){Text(title,Modifier.padding(16.dp),style=MaterialTheme.typography.bodyMedium)}}
private fun tvRemoteTime(ms:Long):String{val seconds=ms/1000;return "%d:%02d".format(seconds/60,seconds%60)}
