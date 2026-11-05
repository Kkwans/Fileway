package io.github.kkwans.nasfilebrowser.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kkwans.fileway.design.R as Art
import io.github.kkwans.fileway.remote.RemotePhase
import io.github.kkwans.nasfilebrowser.app.*

internal val LocalTvHandoff=staticCompositionLocalOf<((ResourceRef,String)->Unit)?>{null}
internal val LocalOpenTvControl=staticCompositionLocalOf<()->Unit>{{}}

/** Lives above route early returns, including video, image and reconnect pages. */
@Composable internal fun FilewayTvHost(phone:ClientModel,content:@Composable ()->Unit) {
    val control=rememberTvControlModel()
    var remoteOpen by rememberSaveable{mutableStateOf(false)}
    val transfer by control.handoff.state.collectAsStateWithLifecycle()
    LaunchedEffect(transfer?.stage) {
        if(transfer?.stage==TvHandoffStage.DONE&&control.handoff.consumeReady())remoteOpen=true
    }
    if(remoteOpen)TvControlScreen(phone,{remoteOpen=false},controlModel=control)
    TvHandoffSheet(control,visible=!remoteOpen,onRemote={remoteOpen=true})
    CompositionLocalProvider(LocalOpenTvControl provides {remoteOpen=true},
        LocalTvHandoff provides {file,sourceScope->control.handoff.begin(phone,file,sourceScope)},content=content)
}

@Composable internal fun TvHandoffIcon(file:ResourceRef,sourceScope:String,enabled:Boolean=true,tint:Color=LocalContentColor.current) {
    val send=LocalTvHandoff.current
    if(send!=null&&file.downloadId.isEmpty()&&file.mediaKind()!=null)FilewayIconButton(onClick={send(file,sourceScope)},enabled=enabled) {
        FilewayIcon(Art.drawable.fileway_ic_cast,"在 TV 播放",tint=tint)
    }
}

@Composable internal fun TvHandoffSheet(control:TvControlModel,visible:Boolean,onRemote:()->Unit) {
    val ui by control.handoff.state.collectAsStateWithLifecycle()
    val value=ui?:return
    if(!visible||value.stage==TvHandoffStage.DONE)return
    val paired by control.paired.collectAsStateWithLifecycle()
    val discovery by control.discovery.collectAsStateWithLifecycle()
    val connection by control.connection.collectAsStateWithLifecycle()
    val context=LocalContext.current
    val viewOwner=remember{Any()}
    var permitted by remember{mutableStateOf(Build.VERSION.SDK_INT<37||context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK)==PackageManager.PERMISSION_GRANTED)}
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){permitted=it}
    LaunchedEffect(permitted){if(!permitted)permission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)}
    LifecycleStartEffect(permitted) {
        if(permitted)control.startView(viewOwner)
        onStopOrDispose{control.stopView(viewOwner)}
    }
    FileOperationSheet("在 TV 播放",{control.handoff.dismiss()}) {
        Column(Modifier.fillMaxWidth().weight(1f,fill=false).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(value.title,style=MaterialTheme.typography.titleMedium)
            if(value.stage!=TvHandoffStage.DEVICE)Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                Text(connection.device?.name.orEmpty(),Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                FilewayTextButton(onClick=control.handoff::changeDevice){Text("更换 TV")}
            }
            when(value.stage) {
                TvHandoffStage.DEVICE -> {
                    if(!permitted)FilewayButton(onClick={permission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)}){Text("允许局域网访问")}
                    paired.forEach { device ->
                        val found=discovery.devices.firstOrNull{it.id==device.id&&it.fingerprint==device.fingerprint}
                        FilewayOutlinedButton(onClick={control.handoff.choose(if(found!=null)device.descriptor().at(found.address,found.port)else device.descriptor())},modifier=Modifier.fillMaxWidth()) {
                            FilewayIcon(Art.drawable.fileway_ic_cast,null,Modifier.size(22.dp));Spacer(Modifier.width(12.dp));Text(device.name,Modifier.weight(1f))
                        }
                    }
                    discovery.devices.filter{found->paired.none{it.id==found.id}}.forEach { device ->
                        FilewayOutlinedButton(onClick={control.handoff.expectDevice(device.id);control.requestPairing(device);onRemote()},modifier=Modifier.fillMaxWidth()){Text("${device.name} · 配对连接")}
                    }
                    if(connection.phase in setOf(RemotePhase.CONNECTING,RemotePhase.PAIRING)||discovery.searching)LinearProgressIndicator(Modifier.fillMaxWidth())
                    if(connection.phase in setOf(RemotePhase.FAILED,RemotePhase.BUSY)) {
                        connection.error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
                        FilewayButton(onClick=control::reconnectNow){Text("重新连接")}
                    }
                    if(!discovery.searching&&paired.isEmpty()&&discovery.devices.isEmpty())Text(discovery.error?:"未发现 TV",color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        FilewayTextButton(onClick={control.search()}){Text("重新搜索")}
                        FilewayTextButton(onClick=onRemote){Text("扫码连接")}
                    }
                }
                TvHandoffStage.AUTHORIZATION -> {
                    Text("TV 当前登录无法读取此文件",style=MaterialTheme.typography.bodyLarge)
                    Text("使用 ${value.account} 在 TV 独立登录并播放",color=MaterialTheme.colorScheme.onSurfaceVariant)
                    FilewayButton(onClick=control.handoff::authorize,modifier=Modifier.fillMaxWidth()){Text("授权登录并播放")}
                }
                TvHandoffStage.FAILED -> {
                    Text(value.error?:"接力未完成，手机继续播放",color=MaterialTheme.colorScheme.error)
                    if(value.uncertain)FilewayOutlinedButton(onClick=control.handoff::reconcile,modifier=Modifier.fillMaxWidth()){Text("检查接管状态")}
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        FilewayButton(onClick=control.handoff::retry){Text("重新发起")}
                        FilewayTextButton(onClick={control.handoff.dismiss(cancelTv=false);onRemote()}){Text("查看 TV")}
                    }
                }
                TvHandoffStage.PREPARING,TvHandoffStage.TRANSFERRING,TvHandoffStage.WAITING -> {
                    Text(when(value.stage){TvHandoffStage.TRANSFERRING->"正在传输播放列表 ${value.transferred} / ${value.total}";TvHandoffStage.WAITING->"等待 TV 接管…";else->"正在准备接力…"},style=MaterialTheme.typography.bodyLarge)
                    if(value.stage==TvHandoffStage.TRANSFERRING&&value.total>0)LinearProgressIndicator(progress={value.transferred.toFloat()/value.total},modifier=Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                    if(value.stage==TvHandoffStage.WAITING)connection.snapshot?.content?.optJSONObject("playback")?.let{playback->
                        if(playback.optBoolean("waitingForBuffer"))Text("TV 缓冲 ${playback.optInt("buffering").coerceIn(0,100)}%",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                TvHandoffStage.DONE -> Unit
            }
        }
    }
}
