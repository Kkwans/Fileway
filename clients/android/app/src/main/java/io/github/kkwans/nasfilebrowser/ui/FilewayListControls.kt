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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.kkwans.nasfilebrowser.R
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun FilewayRefresh(refreshing: Boolean, onRefresh: () -> Unit, modifier: Modifier = Modifier.fillMaxSize(), content: @Composable BoxScope.() -> Unit) {
    var delayed by remember { mutableStateOf(false) }
    LaunchedEffect(refreshing) { delayed = false; if (refreshing) { delay(250); delayed = true } }
    PullToRefreshBox(isRefreshing = refreshing && delayed, onRefresh = onRefresh, modifier = modifier, content = content)
}

@Composable internal fun FilewaySearchField(value: String, change: (String) -> Unit, hint: String, modifier: Modifier = Modifier, enabled: Boolean = true, submit: () -> Unit) {
    BasicTextField(value, change, modifier.heightIn(min = 48.dp).background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.medium),
        enabled = enabled, singleLine = true, textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { submit() }),
        decorationBox = { inner -> Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { if (value.isEmpty()) Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); inner() }
            IconButton(submit, enabled = enabled) { Icon(painterResource(R.drawable.ic_search), hint, Modifier.size(22.dp)) }
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
            labels.forEachIndexed { index, label -> Tab(pager.currentPage == index, { scope.launch { pager.animateScrollToPage(index) } }, text = { Text(label) }) }
        }
    }
}
