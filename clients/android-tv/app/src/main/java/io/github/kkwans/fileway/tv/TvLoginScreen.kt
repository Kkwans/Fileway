package io.github.kkwans.fileway.tv

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.*
import io.github.kkwans.fileway.design.R as Art
import io.github.kkwans.nasfilebrowser.data.*

/** Server picker first, credentials second. No URL keyboard in the normal path. */
@Composable fun TvLoginScreen(model:TvModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val profiles by model.profiles.collectAsStateWithLifecycle()
    val context=LocalContext.current;val lifecycle=LocalLifecycleOwner.current.lifecycle
    val discovery=remember{LanServiceDiscovery(context)}
    var results by remember{mutableStateOf(LanDiscoveryState())}
    var scan by remember{mutableIntStateOf(0)}
    var page by remember{mutableStateOf("servers")}
    var address by remember{mutableStateOf("")};var name by remember{mutableStateOf("Fileway")}
    var username by remember{mutableStateOf("")};var password by remember{mutableStateOf("")}
    var showPassword by remember{mutableStateOf(false)};var selected by remember{mutableStateOf<ServerProfile?>(null)}
    var permitted by remember{mutableStateOf(Build.VERSION.SDK_INT<37 || context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK)==PackageManager.PERMISSION_GRANTED)}
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){permitted=it;if(it){scan++;model.restoreLogin()}}
    LaunchedEffect(Unit){if(!permitted)permission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)}
    LaunchedEffect(scan,permitted,page,state.restoring) {
        if(permitted && page=="servers" && !state.restoring) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            discovery.scan().collect{results=it}
        }
    }
    fun choose(serverName:String,url:String,profile:ServerProfile?=null) { name=serverName;address=url;selected=profile;page="credentials" }
    BackHandler(page!="servers"){page="servers"}
    Box(Modifier.fillMaxSize().imePadding().padding(horizontal=48.dp,vertical=28.dp),contentAlignment=Alignment.Center) {
        Column(Modifier.widthIn(max=520.dp).fillMaxHeight(),verticalArrangement=Arrangement.spacedBy(20.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text("栖卷",style=MaterialTheme.typography.titleLarge)
                Spacer(Modifier.width(10.dp));Text("TV",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.primary)
                Spacer(Modifier.weight(1f))
                if(page!="servers")TvButton("更换服务器",{page="servers"})
            }
            if(state.restoring) {
                Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center){Text("正在连接…",style=MaterialTheme.typography.bodyLarge)}
            } else LazyColumn(Modifier.weight(1f).fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(top=8.dp,bottom=12.dp)) {
                when(page) {
                    "servers" -> {
                        item { Text("选择服务器",style=MaterialTheme.typography.titleMedium) }
                        if(state.savedLogin) item { TvButton("重新连接",model::restoreLogin,Modifier.fillMaxWidth(),icon=Art.drawable.fileway_ic_server) }
                        if(profiles.isNotEmpty()) {
                            item { Text("已保存",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurface.copy(alpha=.6f)) }
                            items(profiles,key={"saved/"+it.id}){profile->TvServerRow(profile.name,profile.address){choose(profile.name,profile.address,profile)}}
                        }
                        if(results.servers.isNotEmpty()) item { Text("局域网",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurface.copy(alpha=.6f)) }
                        items(results.servers,key={"lan/"+it.address}){server->TvServerRow(server.name,server.address){choose(server.name,server.address)}}
                        if(results.searching) item { Text("正在搜索…",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurface.copy(alpha=.6f)) }
                        else if(results.servers.isEmpty()) item { Text(results.error?:"未找到服务器",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurface.copy(alpha=.65f)) }
                        if(!permitted)item { TvButton("允许访问局域网",{permission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)},Modifier.fillMaxWidth()) }
                        item { Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                            TvButton("重新搜索",{results=LanDiscoveryState();scan++},icon=Art.drawable.fileway_ic_refresh)
                            TvButton("手动添加",{page="address"},icon=Art.drawable.fileway_ic_add)
                        } }
                    }
                    "address" -> {
                        item { Text("添加服务器",style=MaterialTheme.typography.titleMedium) }
                        item { TvField("服务地址",address,{address=it;selected=null},options=KeyboardOptions(keyboardType=KeyboardType.Uri)) }
                        item { TvButton("继续",{page="credentials"},Modifier.fillMaxWidth(),enabled=address.isNotBlank()) }
                    }
                    else -> {
                        item { Text(name,style=MaterialTheme.typography.titleMedium) }
                        item { TvField("账号",username,{username=it}) }
                        item { TvField("密码",password,{password=it},password=!showPassword,options=KeyboardOptions(keyboardType=KeyboardType.Password)) }
                        item { TvButton(if(showPassword)"隐藏密码" else "显示密码",{showPassword=!showPassword}) }
                        item { TvButton(if(state.busy)"正在登录…" else "登录",{model.login(address.trim(),username.trim(),password,selected?:ServerProfile(name=name,address=address))},Modifier.fillMaxWidth(),enabled=!state.busy&&username.isNotBlank()) }
                    }
                }
                state.error?.let{message->item { Text(message,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.error) }}
            }
        }
    }
}

@Composable private fun TvServerRow(name:String,address:String,onClick:()->Unit) {
    TvButton(name,onClick,Modifier.fillMaxWidth()) {
        Icon(androidx.compose.ui.res.painterResource(Art.drawable.fileway_ic_server),null,Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
            Text(name,style=MaterialTheme.typography.bodyLarge)
            Text(address,style=MaterialTheme.typography.bodySmall,color=LocalContentColor.current.copy(alpha=.6f))
        }
    }
}
