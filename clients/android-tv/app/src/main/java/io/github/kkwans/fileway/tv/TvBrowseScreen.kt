package io.github.kkwans.fileway.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.focus.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
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

@Composable fun TvBrowseScreen(model: TvModel, onExit: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val favorites by model.favorites.state.collectAsStateWithLifecycle()
    val recent by model.recentAccess.state.collectAsStateWithLifecycle()
    var focusedFile by remember(state.route, state.path, state.searchQuery) { mutableStateOf<ResourceRef?>(null) }
    var searchOpen by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf<ResourceRef?>(null) }
    val routes = remember { listOf(TvRoute.HOME, TvRoute.FILES, TvRoute.VIDEO, TvRoute.MUSIC, TvRoute.IMAGES, TvRoute.FAVORITES, TvRoute.SETTINGS) }
    val nav = remember { routes.associateWith { FocusRequester() } }
    val bodyFocus = remember { FocusRequester() }
    val drawer=rememberDrawerState(DrawerValue.Closed)
    val scope=rememberCoroutineScope()
    val selectRoute:(TvRoute)->Unit={route->model.navigate(route);scope.launch{withFrameNanos{};bodyFocus.requestFocus()}}
    LaunchedEffect(Unit) { withFrameNanos {}; bodyFocus.requestFocus() }
    BackHandler {
        if (model.backDirectory()) Unit
        else if (state.route != TvRoute.HOME) { model.navigate(TvRoute.HOME); nav[TvRoute.HOME]?.requestFocus() }
        else onExit()
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val horizontal = maxWidth * (state.safePercent / 100f)
        val vertical = maxHeight * (state.safePercent / 100f)
        ModalNavigationDrawer(
            modifier=Modifier.fillMaxSize().padding(horizontal=horizontal,vertical=vertical),drawerState=drawer,
            scrimBrush=SolidColor(Color.Black.copy(alpha=.5f)),
            drawerContent={value->
                Column(Modifier.fillMaxHeight().width(if(value==DrawerValue.Open)188.dp else 56.dp)
                    .background(MaterialTheme.colorScheme.background).padding(vertical=8.dp),
                    horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth().height(48.dp).padding(start=12.dp),verticalAlignment=Alignment.CenterVertically) {
                        Image(painterResource(Art.drawable.fileway_ic_launcher_foreground),null,Modifier.size(30.dp))
                        if(value==DrawerValue.Open){Spacer(Modifier.width(14.dp));Text("栖卷",style=MaterialTheme.typography.titleMedium)}
                    }
                    Spacer(Modifier.height(12.dp))
                    routes.filter{it!=TvRoute.SETTINGS}.forEach{route->
                        NavigationDrawerItem(selected=state.route==route,onClick={selectRoute(route)},
                            modifier=Modifier.fillMaxWidth().focusRequester(nav.getValue(route)).semantics{contentDescription=route.title},
                            leadingContent={Icon(painterResource(routeIcon(route)),null,Modifier.size(22.dp))},
                            shape=NavigationDrawerItemDefaults.shape(shape=RoundedCornerShape(12.dp)),
                            scale=NavigationDrawerItemDefaults.scale(focusedScale=1f),
                            colors=NavigationDrawerItemDefaults.colors(containerColor=Color.Transparent)) {
                            Text(route.title,style=MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    NavigationDrawerItem(selected=state.route==TvRoute.SETTINGS,onClick={selectRoute(TvRoute.SETTINGS)},
                        modifier=Modifier.fillMaxWidth().focusRequester(nav.getValue(TvRoute.SETTINGS)).semantics{contentDescription="设置"},
                        leadingContent={Icon(painterResource(Art.drawable.fileway_ic_settings),null,Modifier.size(22.dp))},
                        shape=NavigationDrawerItemDefaults.shape(shape=RoundedCornerShape(12.dp)),
                        scale=NavigationDrawerItemDefaults.scale(focusedScale=1f),
                        colors=NavigationDrawerItemDefaults.colors(containerColor=Color.Transparent)){Text("设置",style=MaterialTheme.typography.bodyMedium)}
                }
            },
        ) {
            Column(Modifier.fillMaxSize().padding(start=76.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(if (state.search) "搜索结果" else state.route.title, style = MaterialTheme.typography.titleLarge)
                        if (state.search) Text("${state.searchQuery} · ${state.searchScope.label}" + if(state.searchScope==SearchScope.GLOBAL) "" else " · ${state.searchBasePath}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (state.route != TvRoute.SETTINGS) {
                        TvToolbarAction("详情",Art.drawable.fileway_ic_info,{details=focusedFile},enabled=focusedFile!=null)
                        TvToolbarAction("搜索",Art.drawable.fileway_ic_search,{searchOpen=true})
                        TvToolbarAction("刷新",Art.drawable.fileway_ic_refresh,{model.refresh()},enabled=!state.busy)
                    }
                }
                state.health?.let { health->Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){Text(health.message,Modifier.weight(1f),color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall);TvButton("重新连接",model::reconnect);if(health.code==SessionHealthCode.EXPIRED||health.code==SessionHealthCode.ACCOUNT_CHANGED)TvButton("重新登录",model::disconnect)} }
                state.error?.let { error -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(error, Modifier.weight(1f), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    TvButton("重试", { model.refresh() })
                } }
                state.notice?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
                Column(Modifier.weight(1f).fillMaxWidth().focusRequester(bodyFocus).focusGroup().focusRestorer()) {
                if(state.search) TvFiles(model,model.visibleFiles(),onFocused={focusedFile=it}) else when (state.route) {
                    TvRoute.HOME -> TvHome(model, state, recent.items, onFocused = { focusedFile = it })
                    TvRoute.SETTINGS -> TvSettingsScreen(model)
                    TvRoute.RECENT -> {
                        TvSectionTitle("最近播放")
                        TvFiles(model, state.recentPlayback.map { ResourceRef(it.path,it.wirePath,it.name,false,"",0) }, onFocused = { focusedFile = it })
                    }
                    TvRoute.FAVORITES -> {
                        favorites.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        if (favorites.loading) Text("正在读取收藏…", style = MaterialTheme.typography.bodyMedium)
                        TvFiles(model, favorites.items.filter { it.openable }.map { ResourceRef(it.path,it.wirePath,it.name,false,"",0) }, onFocused = { focusedFile = it })
                    }
                    else -> {
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                            Text(state.path,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurface.copy(alpha=.6f))
                            TvQuietAction(if(state.grid)"${state.gridColumns} 列" else "列表",model::toggleLayout)
                            TvQuietAction(if(state.sortAscending)"名称 ↑" else "名称 ↓",model::toggleOrder)
                            if (delayedBusy(state.busy)) Text("正在读取…", Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.bodySmall)
                        }
                        TvFiles(model, model.visibleFiles(), enabled = !state.busy || state.search, onFocused = { focusedFile = it })
                    }
                }
            }
        }
    }
    }
    if (searchOpen) TvSearchPanel(initialScope=if(state.route in setOf(TvRoute.HOME,TvRoute.FAVORITES,TvRoute.RECENT))SearchScope.GLOBAL else SearchScope.CURRENT,onClose = { searchOpen = false }, onSearch = { text, scope -> model.search(text, scope); searchOpen = false })
    details?.let { file -> TvPanel("文件详情", { details = null }) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { Text(file.name, style = MaterialTheme.typography.titleMedium) }
            item { Text(file.path, style = MaterialTheme.typography.bodyMedium) }
            item { Text(if (file.directory) "文件夹" else tvSize(file.size), style = MaterialTheme.typography.bodySmall) }
            if (file.modified.isNotBlank()) item { Text(tvDate(file.modified), style = MaterialTheme.typography.bodySmall) }
            item { TvButton("打开", { model.open(file); details = null }, Modifier.fillMaxWidth()) }
            favorites.error?.let { failure -> item { Text(failure,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodyMedium) } }
            item { TvButton(if (model.favorites.favorite(file) != null) "取消收藏" else "加入收藏", { model.toggleFavorite(file) }, Modifier.fillMaxWidth(),enabled=!favorites.changing) }
        }
    } }
}

private fun routeIcon(route: TvRoute) = when (route) {
    TvRoute.HOME -> Art.drawable.fileway_ic_home
    TvRoute.FILES -> Art.drawable.fileway_ic_folder
    TvRoute.VIDEO -> Art.drawable.fileway_ic_video
    TvRoute.MUSIC -> Art.drawable.fileway_ic_audio
    TvRoute.IMAGES -> Art.drawable.fileway_ic_image
    TvRoute.FAVORITES -> Art.drawable.fileway_ic_bookmark
    else -> Art.drawable.fileway_ic_settings
}

@Composable fun TvSectionTitle(title: String) { Text(title, style = MaterialTheme.typography.titleMedium) }

@Composable private fun TvHome(model: TvModel, state: TvState, recent: List<RecentAccessEntry>, onFocused: (ResourceRef) -> Unit) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(20.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
        if (state.recentPlayback.isNotEmpty()) item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TvSectionTitle("继续观看"); Spacer(Modifier.weight(1f)); TvButton("全部记录", { model.navigate(TvRoute.RECENT) })
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(2.dp), modifier = Modifier.focusGroup().focusRestorer()) {
                    items(state.recentPlayback.take(12), key = { it.resourceKey }) { row ->
                        val file = remember(row.resourceKey,row.identity) { ResourceRef(row.path,row.wirePath,row.name,false,"",0) }
                        Column(Modifier.width(224.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                        TvButton(route.title, {model.navigate(route)}, Modifier.weight(1f).height(76.dp), icon = routeIcon(route))
                    }
                }
            }
        }
        if (recent.isNotEmpty()) item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TvSectionTitle("最近访问")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(2.dp), modifier = Modifier.focusGroup().focusRestorer()) {
                    items(recent.filter { it.openable }.take(12),key={it.id}) { row -> TvFileCard(model,row.resource(),{model.open(row.resource())},Modifier.width(200.dp).onFocusChanged { if(it.isFocused) onFocused(row.resource()) }) }
                }
            }
        }
        if(state.rootEntries.isNotEmpty()) item {
            Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                TvSectionTitle("文件")
                LazyRow(horizontalArrangement=Arrangement.spacedBy(18.dp),contentPadding=PaddingValues(2.dp),modifier=Modifier.focusGroup().focusRestorer()) {
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
    val anchor = remember(model.anchorKey()) { model.anchor() }
    if (files.isEmpty()) {
        Column(Modifier.fillMaxWidth().padding(vertical=40.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(if(state.busy) "正在读取内容…" else if(state.search) "没有找到匹配内容" else "这里暂时没有内容",style=MaterialTheme.typography.titleMedium)
            if (!state.busy) TvButton("刷新",model::refresh)
        }
        return
    }
    val selectedIndex=files.indexOfFirst { it.mediaKey==anchor.key }
    val restoredIndex=(if((anchor.ascending!=state.sortAscending||anchor.grid!=state.grid)&&selectedIndex>=0)selectedIndex else anchor.index).coerceIn(0,files.lastIndex)
    val focusKey=if(selectedIndex>=0)anchor.key else files[restoredIndex].mediaKey
    if (state.grid) {
        val grid = rememberLazyGridState(restoredIndex,anchor.offset)
        LazyVerticalGrid(columns=GridCells.Fixed(state.gridColumns),state=grid,horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(2.dp),modifier=Modifier.fillMaxSize().focusGroup()) {
            gridItemsIndexed(files,key={_,file->file.mediaKey}) {index,file->
                val request=remember(file.mediaKey){FocusRequester()}
                LaunchedEffect(file.mediaKey) {if(focusKey==file.mediaKey){withFrameNanos{};request.requestFocus()}}
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
                LaunchedEffect(file.mediaKey){if(focusKey==file.mediaKey){withFrameNanos{};request.requestFocus()}}
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                    TvButton(file.name,{model.open(file,files)},Modifier.weight(1f).focusRequester(request).onFocusChanged{if(it.isFocused){model.rememberAnchor(file.mediaKey,list.firstVisibleItemIndex,list.firstVisibleItemScrollOffset);onFocused(file)}},enabled=enabled) {
                        TvThumbnail(model,file,Modifier.size(72.dp,48.dp));Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)){Text(file.name,maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodyLarge);Text(if(file.directory)"文件夹" else tvSize(file.size),style=MaterialTheme.typography.bodySmall)}
                    }
                }
            }
        }
    }
}

@Composable private fun TvFileCard(model: TvModel,file:ResourceRef,onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,sourceIdentity:String=file.modified) {
    var focused by remember{mutableStateOf(false)}
    Button(onClick=onClick,enabled=enabled,modifier=modifier.onFocusChanged{focused=it.isFocused},
        contentPadding=PaddingValues(2.dp),shape=ButtonDefaults.shape(RoundedCornerShape(12.dp)),scale=ButtonDefaults.scale(focusedScale=1f),
        colors=ButtonDefaults.colors(containerColor=Color.Transparent,contentColor=MaterialTheme.colorScheme.onSurface,
            focusedContainerColor=Color.Transparent,focusedContentColor=MaterialTheme.colorScheme.onSurface)) {
        Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            TvThumbnail(model,file,Modifier.fillMaxWidth().aspectRatio(16f/9f).border(2.dp,if(focused)MaterialTheme.colorScheme.primary else Color.Transparent,RoundedCornerShape(12.dp)),sourceIdentity)
            Text(file.name,Modifier.fillMaxWidth().height(48.dp),maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodyMedium,
                color=MaterialTheme.colorScheme.onSurface.copy(alpha=if(focused)1f else .78f))
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
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha=.08f)),contentAlignment=Alignment.Center) {
        val resource=lease
        if(resource!=null&&!thumbnailFailed)AsyncImage(model=ImageRequest.Builder(LocalContext.current).data(resource.url).memoryCacheKey("${owner?.owner}/${file.mediaKey}/$sourceIdentity").size(512,if(file.mediaKind()==MediaKind.AUDIO)512 else 288).build(),imageLoader=model.imageLoader,onError={thumbnailFailed=true},contentDescription=null,contentScale=ContentScale.Fit,modifier=Modifier.fillMaxSize())
        else Icon(painterResource(if(file.directory)Art.drawable.fileway_ic_folder else when(file.mediaKind()){MediaKind.VIDEO->Art.drawable.fileway_ic_video;MediaKind.AUDIO->Art.drawable.fileway_ic_audio;MediaKind.IMAGE->Art.drawable.fileway_ic_image;else->Art.drawable.fileway_ic_file}),null,Modifier.size(36.dp),tint=MaterialTheme.colorScheme.primary)
    }
}

@Composable private fun TvSearchPanel(initialScope:SearchScope,onClose:()->Unit,onSearch:(String,SearchScope)->Unit) {
    var query by remember{mutableStateOf("")};var scope by remember{mutableStateOf(initialScope)}
    TvPanel("搜索",onClose){
        TvField("文件名或关键词",query,{query=it},options=KeyboardOptions(keyboardType=KeyboardType.Text))
        SearchScope.entries.forEach{value->TvButton(value.label,{scope=value},Modifier.fillMaxWidth(),selected=scope==value)}
        TvButton("开始搜索",{onSearch(query.trim(),scope)},Modifier.fillMaxWidth(),enabled=query.isNotBlank())
    }
}
