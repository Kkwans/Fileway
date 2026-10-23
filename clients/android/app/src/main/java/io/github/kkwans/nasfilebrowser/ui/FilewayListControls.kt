package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.kkwans.nasfilebrowser.R
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun FilewayRefresh(refreshing: Boolean, onRefresh: () -> Unit, modifier: Modifier = Modifier.fillMaxSize(), content: @Composable BoxScope.() -> Unit) {
    var acknowledged by remember { mutableStateOf(false) }
    var started by remember { mutableLongStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(refreshing) {
        if (refreshing) { if (!acknowledged) started = android.os.SystemClock.uptimeMillis(); acknowledged = true }
        else if (acknowledged) { delay((500 - (android.os.SystemClock.uptimeMillis() - started)).coerceAtLeast(0)); acknowledged = false }
    }
    PullToRefreshBox(isRefreshing = refreshing || acknowledged, onRefresh = {
        if (!acknowledged && !refreshing) {
            acknowledged = true; started = android.os.SystemClock.uptimeMillis(); onRefresh()
            scope.launch { delay(500); if (!refreshing) acknowledged = false }
        }
    }, modifier = modifier, content = content)
}

@Composable internal fun FilewaySearchField(value: String, change: (String) -> Unit, hint: String, modifier: Modifier = Modifier, enabled: Boolean = true, status: String? = null,
    actionIcon: Int = R.drawable.ic_search, actionLabel: String = hint, submitEnabled: Boolean = enabled,
    keyboardOptions: KeyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), monospace: Boolean = false,
    containerColor: Color = MaterialTheme.colorScheme.surface, submit: () -> Unit) {
    BasicTextField(value, change, modifier.heightIn(min = 48.dp).background(containerColor, MaterialTheme.shapes.medium),
        enabled = enabled, singleLine = true, cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary), textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface,
            fontFamily = if (monospace) androidx.compose.ui.text.font.FontFamily.Monospace else MaterialTheme.typography.bodyMedium.fontFamily),
        keyboardOptions = keyboardOptions, keyboardActions = KeyboardActions(onSearch = { if (submitEnabled) submit() }, onGo = { if (submitEnabled) submit() }),
        decorationBox = { inner -> Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { if (value.isEmpty()) Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); inner() }
            if (status != null) Text(status, Modifier.padding(start = 8.dp).widthIn(max = 72.dp), maxLines = 1,
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            IconButton(submit, enabled = enabled && submitEnabled) { Icon(painterResource(actionIcon), actionLabel, Modifier.size(22.dp)) }
        } })
}

@Composable internal fun rememberFilewayPager(index: Int, count: Int, select: (Int) -> Unit): PagerState {
    val pager = rememberPagerState(initialPage = index) { count }
    val choose by rememberUpdatedState(select)
    val external by rememberUpdatedState(index)
    LaunchedEffect(index) { if (!pager.isScrollInProgress && pager.currentPage != index) pager.animateScrollToPage(index) }
    LaunchedEffect(pager) { snapshotFlow { pager.settledPage }.distinctUntilChanged().collect { if (it != external) choose(it) } }
    return pager
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun FilewayPagerTabs(pager: PagerState, labels: List<String>) {
    val scope = rememberCoroutineScope()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cell = maxWidth / labels.size
        PrimaryTabRow(selectedTabIndex = pager.currentPage, containerColor = MaterialTheme.colorScheme.background,
            indicator = { Box(Modifier.fillMaxWidth().height(3.dp)) {
                Box(Modifier.offset(x = cell * (pager.currentPage + pager.currentPageOffsetFraction) + cell * .25f)
                    .width(cell * .5f).height(3.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)))
            } }) {
            labels.forEachIndexed { index, label -> Tab(pager.currentPage == index, { scope.launch { pager.animateScrollToPage(index) } }, text = { Text(label) }, selectedContentColor = MaterialTheme.colorScheme.primary, unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
