package io.github.kkwans.nasfilebrowser.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
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
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewaySpace
import io.github.kkwans.nasfilebrowser.ui.designsystem.FilewayTheme
import io.github.kkwans.nasfilebrowser.ui.designsystem.drawMediaProgressTrack
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
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
    val browse = remember(connection.snapshot?.connectionId, connection.snapshot?.sessionEpoch) { TvBrowseState() }
    val browsePresentation = key(connection.snapshot?.connectionId, connection.snapshot?.sessionEpoch) {
        rememberSaveableStateHolder()
    }
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
    FilewayToast(connectionNotice?.takeIf { connection.device == null || scanning })
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
            BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)).imePadding()) {
            val landscape = maxWidth >= 560.dp && maxWidth > maxHeight * 1.35f
            val portraitPad = (maxHeight * .40f).coerceIn(192.dp, 328.dp)
                .coerceAtMost((maxWidth - 36.dp).coerceAtLeast(144.dp))
            val hasRail = landscape && !scanning && connection.phase == RemotePhase.CONNECTED && connection.snapshot != null
            Column(Modifier.fillMaxSize()) {
                if (hasRail) TvPhoneLandscapeHeader(tvConnectionSubtitle(connection), ::closeControl, { devicesOpen = true })
                else FilewayTopAppBar(if(scanning)"扫描 TV 二维码" else "TV 遥控",if(scanning)null else tvConnectionSubtitle(connection),R.drawable.ic_arrow_back,"返回",{if(scanning)scanning=false else closeControl()}){
                    if(scanning)FilewayTextButton(onClick={image.launch("image/*")}){Text("从图片识别")}
                    else FilewayTextButton(onClick={devicesOpen=true}){Text("设备");FilewayIcon(R.drawable.ic_expand_more,null,Modifier.size(20.dp))}
                }
                if(scanning){
                    BackHandler{scanning=false}
                    TvQrScanner(Modifier.fillMaxWidth().weight(1f),onCode={scanning=false;model.pair(it)},onError={cameraError=it;scanning=false})
                }else{
                    val snapshot = connection.snapshot.takeIf { connection.phase == RemotePhase.CONNECTED }
                    if (snapshot != null || (!landscape && connection.device != null)) {
                        val pager=rememberFilewayPager(sections.indexOf(section).coerceAtLeast(0),sections.size){index->section=sections[index];selectedTab=true}
                        TvPhonePages(pager, sections, landscape, Modifier.weight(1f),
                            connected = snapshot != null, swipeTabs = snapshot?.scene != "IMAGE" || section != "播放") { page, compactHeight ->
                            val currentSection=sections[page]
                            if (!landscape && currentSection == "遥控") {
                                TvPhonePortraitRemote(phone, model, connection, snapshot, busy, portraitPad,
                                    onSearch = { target = "search"; inputOpen = true },
                                    onKeyboard = { inputOpen = true },
                                    onBrowse = {
                                        if (!browse.busy && browse.category != "FILES") {
                                            browse.category = "FILES"
                                            browse.pages = emptyList()
                                            browse.error = null
                                        }
                                        section = "文件"; selectedTab = true
                                    },
                                    onPlayback = { section = "播放"; selectedTab = true })
                                return@TvPhonePages
                            }
                            if (snapshot == null) {
                                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
                                    item { TvPhoneTargetState(connection, model) }
                                }
                                return@TvPhonePages
                            }
                            if (currentSection == "文件" && snapshot.capabilities.optInt("fileBrowse") > 0 &&
                                snapshot.content.optBoolean("connected")) {
                                browsePresentation.SaveableStateProvider(snapshot.connectionId + "/" + snapshot.sessionEpoch) {
                                    TvRemoteFiles(model, snapshot, browse)
                                }
                                return@TvPhonePages
                            }
                            if (currentSection == "设置") {
                                TvRemoteSettings(model, phone, snapshot, busy, connection.device?.name ?: "TV")
                                return@TvPhonePages
                            }
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
                                    item { BoxWithConstraints(
                                        Modifier.widthIn(max = 576.dp).fillMaxWidth(),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        val labelSize = MaterialTheme.typography.labelLarge.fontSize
                                        val actionWidth = with(LocalDensity.current) { labelSize.toDp() * 2 + 16.dp }.coerceAtLeast(48.dp)
                                        val padSize = if (compactHeight) 168.dp else 192.dp
                                        val splitWidth = padSize + 32.dp + actionWidth * 3
                                        if (maxWidth >= splitWidth) {
                                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(32.dp,Alignment.CenterHorizontally),
                                                verticalAlignment=Alignment.CenterVertically){
                                                TvPhoneDpad(model, Modifier.width(padSize), compact=compactHeight, landscape=true,
                                                    detailsKey=snapshot.scene=="BROWSE"&&snapshot.capabilities.optInt("fileDetailsKey",0)>0)
                                                Column(Modifier.widthIn(max=288.dp).weight(1f,fill=false),verticalArrangement=Arrangement.spacedBy(16.dp)){
                                                    if(showLogin)TvPhoneLoginAction(phone,model,busy)
                                                    if(hasQuickPlayback)TvPhoneQuickPlayback(model,snapshot,requireNotNull(mediaName),requireNotNull(playing),{section="播放";selectedTab=true},compact=true)
                                                    TvPhoneActionRow(model){inputOpen=true}
                                                }
                                            }
                                        }else{
                                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)){
                                                TvPhoneDpad(model,compact=compactRemote, diameter=portraitPad,
                                                    detailsKey=snapshot.scene=="BROWSE"&&snapshot.capabilities.optInt("fileDetailsKey",0)>0)
                                                TvPhoneActionRow(model){inputOpen=true}
                                                if(showLogin){
                                                    TvPhoneLoginAction(phone,model,busy)
                                                }
                                                if(hasQuickPlayback){
                                                    TvPhoneQuickPlayback(model,snapshot,requireNotNull(mediaName),requireNotNull(playing),{section="播放";selectedTab=true})
                                                }
                                            }
                                        }
                                    }}
                                }
                                "文件"->{
                                    item { Text("此 TV 版本的目录浏览会同步切换 TV 页面",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
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
                                "播放"->item{key(snapshot.connectionId,snapshot.sessionEpoch,snapshot.mediaEpoch){
                                    TvPhoneMediaControls(model, snapshot, compact = landscape && compactHeight) { section = "文件"; selectedTab = true }
                                }}
                            }
                            snapshot.content.optString("error").takeIf{it!="null"&&it.isNotBlank()}?.let{message->item{Text(message,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodyMedium)}}
                            }
                        }
                    }else{
                        LazyColumn(Modifier.fillMaxWidth().weight(1f),contentPadding=PaddingValues(horizontal=16.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                            if(connection.device!=null) {
                                item{TvPhoneTargetState(connection,model)}
                            }
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
private fun TvPhoneLandscapeHeader(status: String, onBack: () -> Unit, onDevices: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = FilewaySpace.appBar), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(TvPhoneRailWidth), contentAlignment = Alignment.Center) {
            FilewayIconButton(onBack, Modifier.size(FilewaySpace.target)) {
                FilewayIcon(R.drawable.ic_arrow_back, "返回")
            }
        }
        Spacer(Modifier.width(TvPhoneRailDivider))
        Row(Modifier.weight(1f).padding(horizontal = FilewaySpace.page), verticalAlignment = Alignment.CenterVertically) {
            Text("TV 遥控", Modifier.weight(1f).semantics { heading() }, style = FilewayTheme.text.appBar,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(status, Modifier.widthIn(max = 238.dp).padding(end = 8.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            FilewayTextButton(onClick = onDevices) {
                Text("设备")
                FilewayIcon(R.drawable.ic_expand_more, null, Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun TvPhonePortraitRemote(
    phone: ClientModel,
    model: TvControlModel,
    connection: RemoteConnectionState,
    snapshot: RemoteSnapshot?,
    busy: Boolean,
    diameter: Dp,
    onSearch: () -> Unit,
    onKeyboard: () -> Unit,
    onBrowse: () -> Unit,
    onPlayback: () -> Unit,
) {
    val detailsKey = snapshot?.scene == "BROWSE" && snapshot.capabilities.optInt("fileDetailsKey") > 0
    var recentSnapshot by remember(connection.device?.id) { mutableStateOf<RemoteSnapshot?>(null) }
    SideEffect {
        if (snapshot != null) recentSnapshot = snapshot
    }
    val summary: @Composable () -> Unit = {
        when {
            snapshot == null -> TvPhoneInlineConnection(connection, model)
            snapshot.scene == "LOGIN" -> TvPhoneLoginAction(phone, model, busy)
            else -> TvPhoneNavigationShortcuts(model, onSearch)
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val deckWidth = minOf(maxWidth - 32.dp, 416.dp).coerceAtLeast(0.dp)
        val padSize = minOf(diameter, (deckWidth - 28.dp).coerceAtLeast(0.dp))
        val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
        val compact = maxHeight < (462 + 96 * (fontScale - 1)).dp
        val compactPad = (maxHeight - (268 + 90 * (fontScale - 1)).dp).coerceIn(184.dp, 240.dp)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Column(Modifier.widthIn(max = 416.dp).fillMaxWidth()
                    .then(if (compact) Modifier else Modifier.fillParentMaxHeight())) {
                    Box(Modifier.fillMaxWidth().heightIn(min = (64 + 16 * (fontScale - 1)).dp),
                        contentAlignment = Alignment.Center) { summary() }
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth().then(if (compact) Modifier else Modifier.weight(1f)),
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp)) {
                            TvPhonePortraitMedia(model, snapshot ?: recentSnapshot, snapshot != null, onBrowse, onPlayback)
                            BoxWithConstraints(Modifier.fillMaxWidth().then(if (compact) Modifier else Modifier.weight(1f)),
                                contentAlignment = Alignment.Center) {
                                val availableHeight = if (compact) compactPad else (maxHeight - 16.dp).coerceAtLeast(0.dp)
                                TvPhoneDpad(model, diameter = minOf(padSize, maxWidth, availableHeight), compact = compact,
                                    detailsKey = detailsKey, enabled = snapshot != null, insetSurface = true)
                            }
                            Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                                TvPhoneVolumeRocker(model, snapshot ?: recentSnapshot, snapshot != null)
                            }
                            HorizontalDivider(Modifier.padding(top = 8.dp, bottom = 4.dp),
                                color = MaterialTheme.colorScheme.outlineVariant)
                            TvPhoneActionRow(model, enabled = snapshot != null, onKeyboard = onKeyboard)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TvPhonePortraitMedia(
    model: TvControlModel,
    snapshot: RemoteSnapshot?,
    connected: Boolean,
    onBrowse: () -> Unit,
    onPlayback: () -> Unit,
) {
    if (snapshot == null || snapshot.scene == "LOGIN") return
    val active = snapshot.scene == "VIDEO" || snapshot.scene == "AUDIO"
    val media = snapshot.content.optJSONObject("media")
    val player = snapshot.content.optJSONObject("playback")
    val name = media?.optString("name")?.takeIf { it.isNotBlank() && it != "null" }
    val playing = player?.optBoolean("playing") == true
    val ready = connected && player != null && !player.optBoolean("preparing")
    val view = LocalView.current
    var recentPage by remember(snapshot.connectionId, snapshot.sessionEpoch, snapshot.mediaEpoch, active) {
        mutableStateOf<TvBrowsePage?>(null)
    }
    var opening by remember(snapshot.connectionId, snapshot.sessionEpoch, snapshot.mediaEpoch) { mutableStateOf(false) }
    LaunchedEffect(snapshot.connectionId, snapshot.sessionEpoch, snapshot.mediaEpoch, active, connected) {
        if (!active && connected && snapshot.capabilities.optInt("fileBrowse") > 0) {
            try {
                recentPage = TvBrowsePage.decode(model.remoteRequest(snapshot, "browse.list", JSONObject().put("category", "RECENT")))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                recentPage = null
            }
        }
    }
    val recent = recentPage?.rows?.firstOrNull { it.mediaKind() in setOf(MediaKind.VIDEO, MediaKind.AUDIO) }
    val artworkId = if (active) media?.optString("id").orEmpty() else recent?.wirePath.orEmpty()
    val title = if (active) name ?: "TV 媒体" else recent?.name ?: "从电视文件选片"
    val duration = if (active) player?.optLong("durationMs") ?: 0L else 0L
    val position = player?.optLong("positionMs")?.coerceIn(0L, duration.coerceAtLeast(0L)) ?: 0L
    val activate: () -> Unit = {
        when {
            active -> onPlayback()
            recent == null -> onBrowse()
            !opening -> {
                opening = true
                model.work {
                    try {
                        val payload = JSONObject().put("browseId", recentPage?.id).put("id", recent.wirePath)
                        model.remoteRequest(snapshot, "browse.open", payload, mediaBound = true)
                    } finally {
                        opening = false
                    }
                }
            }
        }
    }
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).heightIn(min = 54.dp).clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = connected && !opening, role = Role.Button, onClick = activate),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val artwork = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp))
                    if (artworkId.isNotBlank() && snapshot.capabilities.optInt("mediaPreview") > 0) {
                        TvRemoteArtwork(model, snapshot, artworkId, title, artwork, scale = ContentScale.Crop,
                            browseId = recentPage?.id.takeUnless { active },
                            contentRevision = if (active) media?.optString("modified").orEmpty() else recent?.modified.orEmpty())
                    } else {
                        Box(artwork.background(MaterialTheme.colorScheme.surfaceContainer), contentAlignment = Alignment.Center) {
                            FilewayIcon(if (active) Art.drawable.fileway_ic_video else Art.drawable.fileway_ic_folder,
                                null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val subtitle = when {
                            opening || active && player?.optBoolean("preparing") == true -> "正在准备"
                            active && duration > 0 -> "${tvRemoteTime(position)} / ${tvRemoteTime(duration)}"
                            active && playing -> "正在播放"
                            active -> "已暂停"
                            recent != null -> "继续播放"
                            else -> "在 TV 上打开"
                        }
                        Text(subtitle, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (active && duration > 0L) {
                            val fraction = (position.toDouble() / duration).toFloat()
                            val colors = MaterialTheme.colorScheme
                            val buffered = player?.optLong("bufferedMs")?.toDouble()?.div(duration)?.toFloat()
                            val cached = remoteCacheRanges(snapshot.content.optJSONObject("cache"), duration)
                            Canvas(Modifier.padding(start = 2.dp, end = 2.dp, top = 2.dp).fillMaxWidth().height(3.dp)) {
                                drawMediaProgressTrack(fraction, colors.outlineVariant, colors.primary,
                                    buffered = buffered, bufferedColor = colors.onSurfaceVariant.copy(alpha = .55f),
                                    downloadedColor = colors.primary.copy(alpha = .35f), cachedRanges = cached,
                                    bufferedFrom = fraction)
                            }
                        }
                    }
                }
                Spacer(Modifier.width(4.dp))
                FilledIconButton(
                    onClick = {
                        if (active) {
                            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            model.commandFor(snapshot, if (playing) "playback.pause" else "playback.play")
                        } else activate()
                    },
                    enabled = if (active) ready else connected && !opening,
                    modifier = Modifier.size(48.dp),
                ) {
                    val icon = when {
                        active && playing -> Art.drawable.fileway_ic_pause
                        active || recent != null -> Art.drawable.fileway_ic_play_arrow
                        else -> R.drawable.ic_arrow_forward
                    }
                    val description = when {
                        active && playing -> "暂停 TV 播放"
                        active -> "播放 TV 媒体"
                        recent != null -> "在 TV 继续播放"
                        else -> "浏览 TV 文件"
                    }
                    FilewayIcon(icon, description, Modifier.size(22.dp))
                }
            }
        }
    }
}

@Composable
private fun TvPhoneVolumeRocker(model: TvControlModel, snapshot: RemoteSnapshot?, connected: Boolean) {
    val colors = MaterialTheme.colorScheme
    val view = LocalView.current
    val player = snapshot?.content?.optJSONObject("playback")
    val limit = snapshot?.capabilities?.optInt("volumeMax", 100)?.coerceIn(100, 200) ?: 100
    val volume = player?.optInt("volume", -1)?.takeIf { it in 0..limit }
    val enabled = connected && player != null && !player.optBoolean("preparing") &&
        snapshot.scene in setOf("VIDEO", "AUDIO")
    fun change(delta: Int) {
        if (snapshot != null && volume != null) {
            view.tvDirectionHaptic()
            adjustTvPhoneVolume(model, snapshot, volume, limit, delta)
        }
    }
    Surface(shape = RoundedCornerShape(24.dp), color = colors.surfaceContainerLow,
        border = BorderStroke(1.dp, colors.outlineVariant)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilewayIconButton({ change(-5) }, Modifier.size(48.dp).semantics { contentDescription = "降低 TV 音量 5%" },
                enabled = enabled && volume != null && volume > 0) {
                Text("−", style = MaterialTheme.typography.titleLarge, modifier = Modifier.clearAndSetSemantics {})
            }
            Text(volume?.let { "$it%" } ?: "音量", Modifier.widthIn(min = 64.dp).padding(horizontal = 6.dp),
                style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, maxLines = 1,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            FilewayIconButton({ change(5) }, Modifier.size(48.dp).semantics { contentDescription = "提高 TV 音量 5%" },
                enabled = enabled && volume != null && volume < limit) {
                FilewayIcon(Art.drawable.fileway_ic_add, null, Modifier.size(23.dp))
            }
        }
    }
}

private fun adjustTvPhoneVolume(model: TvControlModel, snapshot: RemoteSnapshot, volume: Int, limit: Int, delta: Int) {
    val payload = if (snapshot.capabilities.has("volumeMax")) JSONObject().put("delta", delta)
    else JSONObject().put("volume", (volume + delta).coerceIn(0, limit))
    model.commandFor(snapshot, "playback.setVolume", payload)
}

@Composable
internal fun TvPhoneVolumeKeys(model: TvControlModel, snapshot: RemoteSnapshot, volume: Int, limit: Int, enabled: Boolean) {
    val colors = MaterialTheme.colorScheme
    val view = LocalView.current
    fun change(delta: Int) {
        view.tvDirectionHaptic()
        adjustTvPhoneVolume(model, snapshot, volume, limit, delta)
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("音量", Modifier.padding(start = 2.dp).weight(1f), style = MaterialTheme.typography.labelMedium,
            color = colors.onSurfaceVariant)
        Surface(shape = RoundedCornerShape(12.dp), color = colors.surface,
            border = BorderStroke(1.dp, colors.outlineVariant)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilewayIconButton({ change(-5) }, Modifier.size(48.dp).semantics { contentDescription = "降低 TV 音量 5%" },
                    enabled = enabled && volume > 0) {
                    Text("−", style = MaterialTheme.typography.titleLarge, modifier = Modifier.clearAndSetSemantics {})
                }
                Text("$volume%", Modifier.widthIn(min = 56.dp), style = MaterialTheme.typography.labelLarge,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                FilewayIconButton({ change(5) }, Modifier.size(48.dp).semantics { contentDescription = "提高 TV 音量 5%" },
                    enabled = enabled && volume < limit) {
                    Text("+", style = MaterialTheme.typography.titleLarge, modifier = Modifier.clearAndSetSemantics {})
                }
            }
        }
    }
}

@Composable
private fun TvPhoneInlineConnection(connection: RemoteConnectionState, model: TvControlModel) {
    val phase = connection.phase
    val pending = phase == RemotePhase.PAIRING || phase == RemotePhase.CONNECTING || phase == RemotePhase.CONNECTED
    val message = when (phase) {
        RemotePhase.PAIRING -> "等待 TV 允许连接"
        RemotePhase.CONNECTING -> "正在连接 TV"
        RemotePhase.CONNECTED -> "正在同步 TV 状态"
        RemotePhase.FAILED, RemotePhase.BUSY -> connection.error ?: "暂时无法连接 TV"
        RemotePhase.OFFLINE -> "未连接"
    }
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (pending) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else FilewayIcon(Art.drawable.fileway_ic_cast, null, Modifier.size(20.dp))
                Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                if (phase == RemotePhase.FAILED || phase == RemotePhase.BUSY) {
                    FilewayTextButton(onClick = model::reconnectNow) { Text("重试") }
                } else {
                    FilewayTextButton(onClick = model::disconnect) { Text(if (pending) "取消" else "断开") }
                }
            }
        }
    }
}

private val TvPhoneRailWidth = 110.dp
private val TvPhoneRailDivider = 1.dp

@Composable
private fun TvPhonePages(
    pager: PagerState, sections: List<String>, landscape: Boolean, modifier: Modifier, connected: Boolean, swipeTabs: Boolean,
    page: @Composable (Int, Boolean) -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        if (!landscape) FilewayPagerTabs(pager, sections, enabled = connected, singleLine = true)
        Row(Modifier.fillMaxSize()) {
            if (landscape) {
                val scope = rememberCoroutineScope()
                Column(
                    Modifier.width(TvPhoneRailWidth).fillMaxHeight().selectableGroup().padding(horizontal = 10.dp, vertical = 8.dp),
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
                VerticalDivider(Modifier.fillMaxHeight().padding(vertical = 16.dp), thickness = TvPhoneRailDivider,
                    color = MaterialTheme.colorScheme.outlineVariant)
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                val compactHeight = maxHeight < 360.dp
                HorizontalPager(pager, Modifier.fillMaxSize(), userScrollEnabled = connected && swipeTabs) { index -> page(index, compactHeight) }
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
    return connection.device?.name?.takeIf { it.isNotBlank() }?.let { "$status · $it" } ?: status
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
    modifier:Modifier=Modifier,compact:Boolean=false,showProgress:Boolean=false,enabled:Boolean=true){
    val playback = snapshot.content.optJSONObject("playback")
    val duration = playback?.optLong("durationMs") ?: 0
    val view = LocalView.current
    val colors = MaterialTheme.colorScheme
    Surface(modifier, shape = RoundedCornerShape(12.dp),
        color = if (showProgress) colors.surfaceContainerLow else colors.surface) {
        Row(Modifier.fillMaxWidth().padding(start=12.dp,end=4.dp),verticalAlignment=Alignment.CenterVertically){
            Box(Modifier.weight(1f).heightIn(min=48.dp).clickable(enabled=enabled,role=Role.Button,onClick=onOpen),contentAlignment=Alignment.CenterStart){
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(name, style = if (showProgress) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                        maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis)
                    if (showProgress && playback != null && duration > 0) {
                        val position = playback.optLong("positionMs").coerceIn(0L, duration)
                        val progress = (position.toDouble() / duration).toFloat()
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(3.dp))
                        Text("${tvRemoteTime(position)} / ${tvRemoteTime(duration)}",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            FilewayIconButton(onClick={
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                model.commandFor(snapshot,if(playing)"playback.pause"else"playback.play")
            },modifier=Modifier.size(48.dp),enabled=enabled){
                FilewayIcon(if(playing)Art.drawable.fileway_ic_pause else Art.drawable.fileway_ic_play_arrow,if(playing)"暂停 TV 播放"else"播放 TV 媒体")
            }
        }
    }
}

@Composable private fun TvPhoneNavigationShortcuts(model: TvControlModel, onSearch: () -> Unit) {
    val view = LocalView.current
    val style = MaterialTheme.typography.labelLarge
    val measurer = rememberTextMeasurer()
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            val labelWidth = with(LocalDensity.current) {
                measurer.measure("TV 设置", style = style).size.width.toDp() + 16.dp
            }
            val showLabels = maxWidth / 3 >= labelWidth
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                @Composable fun shortcut(label: String, icon: Int, onClick: () -> Unit) {
                    if (showLabels) {
                        FilewayTextButton(onClick, Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 6.dp)) {
                            Text(label, maxLines = 1, softWrap = false)
                        }
                    } else {
                        FilewayIconButton(onClick, Modifier.size(48.dp)) {
                            FilewayIcon(icon, label, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                shortcut("TV 文件", Art.drawable.fileway_ic_folder) {
                    view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    model.command("app.navigate", JSONObject().put("route", "FILES"))
                }
                shortcut("TV 搜索", Art.drawable.fileway_ic_search) {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onSearch()
                }
                shortcut("TV 设置", Art.drawable.fileway_ic_settings) {
                    view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    model.command("app.navigate", JSONObject().put("route", "SETTINGS"))
                }
            }
        }
    }
}

@Composable
private fun TvPhoneActionRow(model: TvControlModel, enabled: Boolean = true, onKeyboard: () -> Unit) {
    val view = LocalView.current
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            TvPhoneAction("返回", R.drawable.ic_arrow_back, Modifier.weight(1f), enabled) {
                view.tvBackHaptic()
                model.command("app.back")
            }
            TvPhoneAction("首页", Art.drawable.fileway_ic_home, Modifier.weight(1f), enabled) {
                view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                model.command("app.navigate", JSONObject().put("route", "HOME"))
            }
            TvPhoneAction("键盘", Art.drawable.fileway_ic_text, Modifier.weight(1f), enabled) {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onKeyboard()
            }
        }
    }
}

@Composable
private fun TvPhoneAction(label: String, icon: Int, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else .38f)
    Column(
        modifier.heightIn(min = 56.dp).clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        FilewayIcon(icon, null, Modifier.size(22.dp), tint = color)
        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

private fun View.tvDirectionHaptic() {
    performHapticFeedback(if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK else HapticFeedbackConstants.CLOCK_TICK)
}

private fun View.tvConfirmHaptic() {
    performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
}

private fun View.tvBackHaptic() {
    performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.GESTURE_END else HapticFeedbackConstants.VIRTUAL_KEY_RELEASE)
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

@Composable private fun TvPhoneDpad(model:TvControlModel,modifier:Modifier=Modifier,compact:Boolean=false,
    detailsKey:Boolean=false,landscape:Boolean=false,diameter:Dp?=null,enabled:Boolean=true,insetSurface:Boolean=false){
    val side = diameter?.div(3) ?: when { landscape && compact -> 56.dp; landscape -> 64.dp; compact -> 48.dp; else -> 72.dp }
    val view = LocalView.current
    fun direction(key: String) {
        view.tvDirectionHaptic()
        model.command("app.key", JSONObject().put("key", key))
    }
    Box(modifier.fillMaxWidth().padding(vertical=if(compact)0.dp else 8.dp),contentAlignment=Alignment.Center){
        Surface(Modifier.size(side*3),shape=CircleShape,
            color=if(insetSurface) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surface,
            border = if (landscape || insetSurface) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null){
            if (insetSurface) {
                BoxWithConstraints(Modifier.fillMaxSize().padding(6.dp)) {
                    val keySize = (maxWidth / 4).coerceIn(48.dp, 60.dp)
                    val compactConfirm = compact || maxWidth < 224.dp
                    FilewayIconButton({ direction("up") }, Modifier.align(Alignment.TopCenter).size(keySize), enabled = enabled) {
                        FilewayIcon(R.drawable.ic_expand_less, "上", Modifier.size(28.dp))
                    }
                    FilewayIconButton({ direction("left") }, Modifier.align(Alignment.CenterStart).size(keySize), enabled = enabled) {
                        FilewayIcon(R.drawable.ic_expand_more, "左", Modifier.size(28.dp).rotate(90f))
                    }
                    FilewayIconButton({ direction("right") }, Modifier.align(Alignment.CenterEnd).size(keySize), enabled = enabled) {
                        FilewayIcon(R.drawable.ic_expand_more, "右", Modifier.size(28.dp).rotate(-90f))
                    }
                    FilewayIconButton({ direction("down") }, Modifier.align(Alignment.BottomCenter).size(keySize), enabled = enabled) {
                        FilewayIcon(R.drawable.ic_expand_more, "下", Modifier.size(28.dp))
                    }
                    Box(Modifier.align(Alignment.Center)) {
                        TvPhoneConfirmKey(compactConfirm, detailsKey,
                            onConfirm = { model.command("app.key", JSONObject().put("key", "ok")) },
                            onDetails = { model.command("app.key", JSONObject().put("key", "details")) }, enabled = enabled)
                    }
                }
            } else Column(horizontalAlignment=Alignment.CenterHorizontally){
                FilewayIconButton(onClick={direction("up")},Modifier.size(side),enabled=enabled){FilewayIcon(R.drawable.ic_expand_less,"上",Modifier.size(28.dp))}
                Row(verticalAlignment=Alignment.CenterVertically){
                    FilewayIconButton(onClick={direction("left")},Modifier.size(side),enabled=enabled){
                        FilewayIcon(R.drawable.ic_expand_more,"左",Modifier.size(28.dp).rotate(90f))
                    }
                    Box(Modifier.size(side),contentAlignment=Alignment.Center){
                        TvPhoneConfirmKey(compact, detailsKey,
                            onConfirm={model.command("app.key",JSONObject().put("key","ok"))},
                            onDetails={model.command("app.key",JSONObject().put("key","details"))}, iconOnly=landscape,enabled=enabled)
                    }
                    FilewayIconButton(onClick={direction("right")},Modifier.size(side),enabled=enabled){
                        FilewayIcon(R.drawable.ic_expand_more,"右",Modifier.size(28.dp).rotate(-90f))
                    }
                }
                FilewayIconButton(onClick={direction("down")},Modifier.size(side),enabled=enabled){FilewayIcon(R.drawable.ic_expand_more,"下",Modifier.size(28.dp))}
            }
        }
    }
}

@Composable internal fun TvPhoneConfirmKey(compact:Boolean,detailsKey:Boolean,onConfirm:()->Unit,onDetails:()->Unit,
    iconOnly:Boolean=false,enabled:Boolean=true){
    val size = when {
        iconOnly -> if (compact) 48.dp else 64.dp
        compact -> 76.dp
        else -> 104.dp
    }
    val view = LocalView.current
    val confirm = { view.tvConfirmHaptic(); onConfirm() }
    val content: @Composable () -> Unit = {
        if (iconOnly) FilewayIcon(R.drawable.ic_check, null, Modifier.size(24.dp))
        else Text("确定",modifier=Modifier.clearAndSetSemantics{},style=MaterialTheme.typography.labelLarge,
            maxLines=1,softWrap=false)
    }
    if(detailsKey && enabled) Box(Modifier.size(size).clip(CircleShape)
        .background(MaterialTheme.colorScheme.primary)
        .combinedClickable(role=Role.Button,onLongClickLabel="查看详情",onClick=confirm,onLongClick=onDetails)
        .semantics{contentDescription="确认"},contentAlignment=Alignment.Center){
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onPrimary, content = content)
    } else FilledIconButton(onClick=confirm,Modifier.size(size).semantics{contentDescription="确认"},enabled=enabled){
        content()
    }
}

@Composable private fun TvPhoneToggle(title:String,value:Boolean,onChange:(Boolean)->Unit){
    Row(Modifier.fillMaxWidth().toggleable(value=value,role=Role.Switch,onValueChange=onChange).heightIn(min=56.dp).padding(horizontal=16.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically){
        Text(title,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
        Switch(value,onCheckedChange=null,modifier=Modifier.clearAndSetSemantics{})
    }
}
