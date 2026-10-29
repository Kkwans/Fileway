package io.github.kkwans.fileway.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.focus.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.*
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import io.github.kkwans.fileway.design.R as Art
import io.github.kkwans.nasfilebrowser.app.*
import io.github.kkwans.nasfilebrowser.data.*
import kotlinx.coroutines.*

@Composable fun TvBrowseScreen(model: TvModel, onExit: () -> Unit, active: Boolean = true) {
    val state by model.state.collectAsStateWithLifecycle()
    val favorites by model.favorites.state.collectAsStateWithLifecycle()
    val recent by model.recentAccess.state.collectAsStateWithLifecycle()
    var focusedFile by remember(state.route, state.path, state.searchQuery) { mutableStateOf<ResourceRef?>(null) }
    var searchOpen by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf<ResourceRef?>(null) }
    var locationOpen by remember { mutableStateOf(false) }
    var navigationFocused by remember { mutableStateOf(false) }
    val routes = remember { listOf(TvRoute.HOME, TvRoute.FILES, TvRoute.VIDEO, TvRoute.MUSIC, TvRoute.IMAGES, TvRoute.FAVORITES) }
    val nav = remember { (routes + TvRoute.SETTINGS).associateWith { FocusRequester() } }
    val bodyFocus = remember { FocusRequester() }
    val measurer=rememberTextMeasurer()
    val density=LocalDensity.current
    val labelStyle=MaterialTheme.typography.labelLarge
    val brandStyle=MaterialTheme.typography.titleMedium
    val expandedWidth=remember(density,labelStyle,brandStyle) {
        with(density) {
            maxOf((routes+TvRoute.SETTINGS).maxOf { measurer.measure(AnnotatedString(it.title),labelStyle).size.width }.toDp()+70.dp,
                measurer.measure(AnnotatedString("栖卷"),brandStyle).size.width.toDp()+66.dp)
        }
    }
    val scope = rememberCoroutineScope()
    val selectRoute: (TvRoute) -> Unit = { route ->
        model.navigate(route)
        navigationFocused = false
        scope.launch { withFrameNanos {}; bodyFocus.requestFocus() }
    }
    LaunchedEffect(active) {
        if (active) { withFrameNanos {}; bodyFocus.requestFocus() }
        else { searchOpen=false; details=null; locationOpen=false; navigationFocused=false }
    }
    BackHandler(enabled=active) { if (!model.backDirectory()) onExit() }
    CompositionLocalProvider(LocalTvInteractive provides active,LocalTvCompact provides (LocalConfiguration.current.screenHeightDp<480)) {
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
        .then(if(active)Modifier else Modifier.clearAndSetSemantics {})) {
        val horizontal = maxWidth * (state.safePercent / 100f)
        val vertical = maxHeight * (state.safePercent / 100f)
        Box(Modifier.fillMaxSize().padding(horizontal=horizontal,vertical=vertical)) {
            // Content never moves as navigation expands. A raised rail overlays
            // it only while the navigation focus group is active.
            Column(Modifier.fillMaxSize().padding(start=76.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                val directory = !state.search && state.route in listOf(TvRoute.FILES,TvRoute.VIDEO,TvRoute.MUSIC,TvRoute.IMAGES) && state.path!="/"
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                            Text(if(state.search)"搜索结果" else if(directory)state.path.substringAfterLast('/') else state.route.title,
                                Modifier.weight(1f),style=MaterialTheme.typography.titleLarge,maxLines=2,overflow=TextOverflow.Ellipsis)
                            if(!state.search&&state.route in listOf(TvRoute.FILES,TvRoute.VIDEO,TvRoute.MUSIC,TvRoute.IMAGES))
                                Text("${model.visibleFiles().size} 项",style=MaterialTheme.typography.bodySmall,color=LocalTvSurfaces.current.secondaryText)
                        }
                        if(state.search)Text("${state.searchQuery} · ${state.searchScope.label}",style=MaterialTheme.typography.bodySmall,color=LocalTvSurfaces.current.secondaryText,maxLines=1,overflow=TextOverflow.Ellipsis)
                        else if(directory)Text(state.path.substringBeforeLast('/').ifBlank{"/"},style=MaterialTheme.typography.bodySmall,color=LocalTvSurfaces.current.secondaryText,maxLines=1,overflow=TextOverflow.Ellipsis)
                    }
                    if(state.route!=TvRoute.SETTINGS) {
                        if(directory)TvToolbarAction("上一级",Art.drawable.fileway_ic_arrow_back,{model.backDirectory()})
                        if(directory)TvToolbarAction("当前位置",Art.drawable.fileway_ic_folder,{locationOpen=true})
                        TvToolbarAction("详情",Art.drawable.fileway_ic_info,{details=focusedFile},enabled=focusedFile!=null)
                        TvToolbarAction("搜索",Art.drawable.fileway_ic_search,{searchOpen=true})
                        TvToolbarAction("刷新",Art.drawable.fileway_ic_refresh,model::refresh,enabled=!state.busy)
                        if(state.route in listOf(TvRoute.FILES,TvRoute.VIDEO,TvRoute.MUSIC,TvRoute.IMAGES)) {
                            TvToolbarAction(if(state.grid)"切换列表" else "切换网格",if(state.grid)Art.drawable.fileway_ic_layout_list else Art.drawable.fileway_ic_layout_cover,model::toggleLayout)
                            TvToolbarAction(if(state.sortAscending)"名称正序" else "名称倒序",Art.drawable.fileway_ic_sort,model::toggleOrder)
                        }
                    }
                }
                state.health?.let { health ->
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        Text(health.message,Modifier.weight(1f),color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodyMedium)
                        TvButton("重新连接",model::reconnect)
                        if(health.code==SessionHealthCode.EXPIRED||health.code==SessionHealthCode.ACCOUNT_CHANGED)TvButton("重新登录",model::disconnect)
                    }
                }
                state.error?.let { error -> Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text(error,Modifier.weight(1f),color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodyMedium)
                    TvButton("重试",model::refresh)
                } }
                state.notice?.let { Text(it,color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.bodySmall) }
                Column(Modifier.weight(1f).fillMaxWidth().focusRequester(bodyFocus).focusGroup().focusRestorer()) {
                    if(state.search)TvFiles(model,model.visibleFiles(),onFocused={focusedFile=it}) else when(state.route) {
                        TvRoute.HOME -> TvHome(model,state,recent.items,onFocused={focusedFile=it})
                        TvRoute.SETTINGS -> TvSettingsScreen(model)
                        TvRoute.RECENT -> TvFiles(model,state.recentPlayback.map { ResourceRef(it.path,it.wirePath,it.name,false,"",0) },onFocused={focusedFile=it})
                        TvRoute.FAVORITES -> {
                            favorites.error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
                            if(favorites.loading)Text("正在读取收藏…",style=MaterialTheme.typography.bodyMedium)
                            TvFiles(model,favorites.items.filter { it.openable }.map { ResourceRef(it.path,it.wirePath,it.name,false,"",0) },onFocused={focusedFile=it})
                        }
                        else -> {
                            TvFiles(model,model.visibleFiles(),enabled=!state.busy,onFocused={focusedFile=it})
                        }
                    }
                }
            }
            Column(Modifier.align(Alignment.CenterStart).fillMaxHeight().width(if(navigationFocused)expandedWidth else 56.dp)
                .background(if(navigationFocused)LocalTvSurfaces.current.chrome else Color.Transparent,TvShape)
                .onFocusChanged { navigationFocused=it.hasFocus }.focusGroup(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Image(painterResource(Art.drawable.fileway_ic_launcher_foreground),null,Modifier.size(30.dp))
                    if(navigationFocused)Text("栖卷",style=MaterialTheme.typography.titleMedium)
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(4.dp),contentPadding=PaddingValues(vertical=2.dp)) {
                    items(routes,key={it.name}) { route -> TvNavigationItem(route,state.route==route,navigationFocused,{selectRoute(route)},Modifier.focusRequester(nav.getValue(route))) }
                }
                TvNavigationItem(TvRoute.SETTINGS,state.route==TvRoute.SETTINGS,navigationFocused,{selectRoute(TvRoute.SETTINGS)},Modifier.focusRequester(nav.getValue(TvRoute.SETTINGS)))
            }
        }
    }
    if(active&&searchOpen)TvSearchPanel(state.searchScope,{searchOpen=false}) { query,searchScope -> model.search(query,searchScope);searchOpen=false }
    if(active&&locationOpen)TvPanel("当前位置",{locationOpen=false}) {
        LazyColumn(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item { Text(state.path.substringAfterLast('/').ifBlank{"/"},style=MaterialTheme.typography.titleMedium) }
            item { Text(state.path,style=MaterialTheme.typography.bodyLarge) }
        }
    }
    if(active)details?.let { file -> TvPanel("详情",{details=null}) {
        LazyColumn(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item { Text(file.name,style=MaterialTheme.typography.titleMedium) }
            item { Text(file.path,style=MaterialTheme.typography.bodyLarge) }
            item { Text(if(file.directory)"文件夹" else tvSize(file.size),style=MaterialTheme.typography.bodySmall,color=LocalTvSurfaces.current.secondaryText) }
            if(file.modified.isNotBlank())item { Text(tvDate(file.modified),style=MaterialTheme.typography.bodySmall,color=LocalTvSurfaces.current.secondaryText) }
            item { TvOptionRow("打开",{model.open(file);details=null},icon=if(file.directory)Art.drawable.fileway_ic_folder else Art.drawable.fileway_ic_play_arrow,destination=true) }
            favorites.error?.let { failure -> item { Text(failure,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodyMedium) } }
            item { TvOptionRow(if(model.favorites.favorite(file)!=null)"取消收藏" else "加入收藏",{model.toggleFavorite(file)},icon=Art.drawable.fileway_ic_bookmark,selected=model.favorites.favorite(file)!=null,enabled=!favorites.changing) }
        }
    } }
    }
}

@Composable private fun TvNavigationItem(route:TvRoute,selected:Boolean,expanded:Boolean,onClick:()->Unit,modifier:Modifier=Modifier) {
    var focused by remember { mutableStateOf(false) }
    val interactive=LocalTvInteractive.current
    val colors=MaterialTheme.colorScheme
    val interaction=remember { MutableInteractionSource() }
    Button(onClick=onClick,enabled=interactive,interactionSource=interaction,
        modifier=modifier.fillMaxWidth().height(48.dp).tvTouch(onClick,interactive,interaction).focusProperties { canFocus=interactive }
            .semantics { contentDescription=route.title;this.selected=selected }.onFocusChanged { focused=it.isFocused }
            .border(2.dp,if(focused)colors.primary else Color.Transparent,TvShape),
        shape=ButtonDefaults.shape(TvShape),scale=ButtonDefaults.scale(focusedScale=1f),contentPadding=PaddingValues(horizontal=16.dp),
        colors=ButtonDefaults.colors(containerColor=if(selected)colors.primary.copy(alpha=.12f)else Color.Transparent,
            contentColor=if(selected)colors.primary else LocalTvSurfaces.current.secondaryText,
            focusedContainerColor=LocalTvSurfaces.current.raised,focusedContentColor=colors.onSurface)) {
        Icon(painterResource(routeIcon(route)),null,Modifier.size(24.dp))
        if(expanded) { Spacer(Modifier.width(14.dp));Text(route.title,style=MaterialTheme.typography.labelLarge) }
    }
}

private fun routeIcon(route: TvRoute) = when (route) {
    TvRoute.HOME -> Art.drawable.fileway_ic_home
    TvRoute.FILES -> Art.drawable.fileway_ic_folder
    TvRoute.VIDEO -> Art.drawable.fileway_ic_video
    TvRoute.MUSIC -> Art.drawable.fileway_ic_audio
    TvRoute.IMAGES -> Art.drawable.fileway_ic_image
    TvRoute.FAVORITES -> Art.drawable.fileway_ic_bookmark
    TvRoute.RECENT -> Art.drawable.fileway_ic_history
    TvRoute.SETTINGS -> Art.drawable.fileway_ic_settings
}

@Composable fun TvSectionTitle(title: String) { Text(title, style = MaterialTheme.typography.titleMedium) }

@Composable private fun TvHome(model: TvModel, state: TvState, recent: List<RecentAccessEntry>, onFocused: (ResourceRef) -> Unit) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(24.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
        if (state.recentPlayback.isNotEmpty()) item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TvSectionTitle("继续观看"); Spacer(Modifier.weight(1f)); TvButton("全部记录", { model.navigate(TvRoute.RECENT) })
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(2.dp), modifier = Modifier.focusGroup().focusRestorer()) {
                    items(state.recentPlayback.take(12), key = { it.resourceKey }) { row ->
                        val file = remember(row.resourceKey,row.identity) { ResourceRef(row.path,row.wirePath,row.name,false,"",0) }
                        Column(Modifier.width(200.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            TvFileCard(model,file,{model.openPlayback(row)},Modifier.fillMaxWidth().onFocusChanged { if(it.isFocused) onFocused(file) },sourceIdentity=row.identity)
                            Text("${tvTime(row.positionMs)} / ${tvTime(row.durationMs)}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TvSectionTitle("我的文件库")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf(TvRoute.VIDEO, TvRoute.MUSIC, TvRoute.IMAGES).forEach { route ->
                        TvButton(route.title, {model.navigate(route)}, Modifier.weight(1f).height(52.dp), icon = routeIcon(route))
                    }
                }
            }
        }
        if (recent.isNotEmpty()) item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TvSectionTitle("最近访问")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(2.dp), modifier = Modifier.focusGroup().focusRestorer()) {
                    items(recent.filter { it.openable }.take(12),key={it.id}) { row -> TvFileCard(model,row.resource(),{model.open(row.resource())},Modifier.width(184.dp).onFocusChanged { if(it.isFocused) onFocused(row.resource()) }) }
                }
            }
        }
        if(state.rootEntries.isNotEmpty()) item {
            Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                TvSectionTitle("文件")
                LazyRow(horizontalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(2.dp),modifier=Modifier.focusGroup().focusRestorer()) {
                    items(state.rootEntries.take(12),key={it.mediaKey}){file->
                        TvFileCard(model,file,{model.open(file,state.rootEntries)},Modifier.width(176.dp).onFocusChanged{if(it.isFocused)onFocused(file)})
                    }
                }
            }
        }
    }
}

@Composable private fun TvFiles(model: TvModel, files: List<ResourceRef>, enabled: Boolean = true, onFocused: (ResourceRef) -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    key(model.anchorKey(),state.grid,state.sortAscending) { TvFileItems(model,files,enabled,onFocused) }
}

@Composable private fun TvFileItems(model: TvModel, files: List<ResourceRef>, enabled: Boolean, onFocused: (ResourceRef) -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val interactive=LocalTvInteractive.current
    val anchor = remember(model.anchorKey(),interactive) { model.anchor() }
    if (files.isEmpty()) {
        val emptyFocus=remember { FocusRequester() }
        LaunchedEffect(interactive,state.busy) { if(interactive&&!state.busy) { withFrameNanos {};emptyFocus.requestFocus() } }
        Column(Modifier.fillMaxWidth().padding(vertical=40.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(if(state.busy) "正在读取内容…" else if(state.search) "没有找到匹配内容" else "这里暂时没有内容",style=MaterialTheme.typography.titleMedium)
            if (!state.busy) TvButton("刷新",model::refresh,Modifier.focusRequester(emptyFocus))
        }
        return
    }
    val selectedIndex=files.indexOfFirst { it.mediaKey==anchor.key }
    val restoredIndex=(if((anchor.ascending!=state.sortAscending||anchor.grid!=state.grid)&&selectedIndex>=0)selectedIndex else anchor.index).coerceIn(0,files.lastIndex)
    val focusKey=if(selectedIndex>=0)anchor.key else files[restoredIndex].mediaKey
    if (state.grid) {
        val grid = rememberLazyGridState(restoredIndex,anchor.offset)
        LazyVerticalGrid(columns=GridCells.Fixed(state.gridColumns),state=grid,horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp),contentPadding=PaddingValues(2.dp),modifier=Modifier.fillMaxSize().focusGroup()) {
            gridItemsIndexed(files,key={_,file->file.mediaKey}) {index,file->
                val request=remember(file.mediaKey){FocusRequester()}
                LaunchedEffect(file.mediaKey,interactive) {if(interactive&&focusKey==file.mediaKey){withFrameNanos{};request.requestFocus()}}
                Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    TvFileCard(model,file,{model.open(file,files)},Modifier.fillMaxWidth().focusRequester(request).onFocusChanged {
                        if(it.isFocused){model.rememberAnchor(file.mediaKey,grid.firstVisibleItemIndex,grid.firstVisibleItemScrollOffset);onFocused(file)}
                    },enabled)
                }
            }
        }
    } else {
        val list = rememberLazyListState(restoredIndex,anchor.offset)
        LazyColumn(state=list,verticalArrangement=Arrangement.spacedBy(8.dp),contentPadding=PaddingValues(2.dp),modifier=Modifier.fillMaxSize().focusGroup()) {
            itemsIndexed(files,key={_,file->file.mediaKey}) {_,file->
                val request=remember(file.mediaKey){FocusRequester()}
                LaunchedEffect(file.mediaKey,interactive){if(interactive&&focusKey==file.mediaKey){withFrameNanos{};request.requestFocus()}}
                TvOptionRow(file.name,{model.open(file,files)},
                    Modifier.focusRequester(request).onFocusChanged { if(it.isFocused) { model.rememberAnchor(file.mediaKey,list.firstVisibleItemIndex,list.firstVisibleItemScrollOffset);onFocused(file) } },
                    supporting=if(file.directory)"文件夹" else tvSize(file.size), enabled=enabled, destination=true,
                    leading={TvThumbnail(model,file,Modifier.size(80.dp,48.dp))})
            }
        }
    }
}

@Composable private fun TvFileCard(model: TvModel,file:ResourceRef,onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,sourceIdentity:String=file.modified) {
    var focused by remember { mutableStateOf(false) }
    val interactive=LocalTvInteractive.current
    val colors=MaterialTheme.colorScheme
    val filenameHeight=with(LocalDensity.current) { MaterialTheme.typography.bodyMedium.lineHeight.toDp()*2 }
    val interaction=remember { MutableInteractionSource() }
    Button(onClick=onClick,enabled=enabled&&interactive,interactionSource=interaction,
        modifier=modifier.tvTouch(onClick,enabled&&interactive,interaction).focusProperties { canFocus=interactive }.onFocusChanged { focused=it.isFocused },
        contentPadding=PaddingValues(0.dp),shape=ButtonDefaults.shape(TvShape),scale=ButtonDefaults.scale(focusedScale=1f),
        colors=ButtonDefaults.colors(containerColor=Color.Transparent,contentColor=colors.onSurface,
            focusedContainerColor=Color.Transparent,focusedContentColor=colors.onSurface)) {
        Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            // The image mask and ring have exactly the same bounds and shape.
            // Draw the ring outside the clip rather than inside another inset.
            Box(Modifier.fillMaxWidth().aspectRatio(if(LocalTvCompact.current)2.2f else 16f/9f).border(2.dp,if(focused)colors.primary else Color.Transparent,TvShape)) {
                TvThumbnail(model,file,Modifier.fillMaxSize(),sourceIdentity)
            }
            Text(file.name,Modifier.fillMaxWidth().height(filenameHeight).padding(horizontal=2.dp),maxLines=2,overflow=TextOverflow.Ellipsis,
                style=MaterialTheme.typography.bodyMedium,color=if(focused)colors.onSurface else LocalTvSurfaces.current.secondaryText)
        }
    }
}

@Composable fun TvThumbnail(model:TvModel,file:ResourceRef,modifier:Modifier=Modifier,sourceIdentity:String=file.modified) {
    val owner=model.context
    var lease by remember(owner?.owner,file.mediaKey){mutableStateOf<PreviewLease?>(null)}
    var thumbnailFailed by remember(owner?.owner,file.mediaKey,sourceIdentity){mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    LaunchedEffect(owner?.owner,file.mediaKey) {
        if(owner!=null&&!file.directory&&file.mediaKind()!=null)try{lease=owner.api.preview(file.path,file.wirePath,true)}catch(error:Exception){if(error is CancellationException)throw error}
    }
    DisposableEffect(owner?.owner,file.mediaKey){onDispose{val resource=lease;scope.launch(NonCancellable){resource?.release()}}}
    Box(modifier.clip(TvShape).background(MaterialTheme.colorScheme.surface),contentAlignment=Alignment.Center) {
        val resource=lease
        if(resource!=null&&!thumbnailFailed)AsyncImage(model=ImageRequest.Builder(LocalContext.current).data(resource.url).memoryCacheKey("${owner?.owner}/${file.mediaKey}/$sourceIdentity").size(512,if(file.mediaKind()==MediaKind.AUDIO)512 else 288).build(),imageLoader=model.imageLoader,onError={thumbnailFailed=true},contentDescription=null,contentScale=ContentScale.Fit,modifier=Modifier.fillMaxSize())
        else Icon(painterResource(if(file.directory)Art.drawable.fileway_ic_folder else when(file.mediaKind()){MediaKind.VIDEO->Art.drawable.fileway_ic_video;MediaKind.AUDIO->Art.drawable.fileway_ic_audio;MediaKind.IMAGE->Art.drawable.fileway_ic_image;else->Art.drawable.fileway_ic_file}),null,Modifier.size(40.dp),tint=LocalTvSurfaces.current.secondaryText)
    }
}

@Composable private fun TvSearchPanel(initialScope:SearchScope,onClose:()->Unit,onSearch:(String,SearchScope)->Unit) {
    var query by remember{mutableStateOf("")};var scope by remember{mutableStateOf(initialScope)}
    TvPanel("搜索",onClose){
        TvField("文件名或关键词",query,{query=it},options=KeyboardOptions(keyboardType=KeyboardType.Text))
        SearchScope.entries.forEach{value->TvOptionRow(value.label,{scope=value},selected=scope==value)}
        TvButton("开始搜索",{onSearch(query.trim(),scope)},Modifier.fillMaxWidth(),enabled=query.isNotBlank())
    }
}
