package io.github.kkwans.fileway.tv.medialibrary

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.tv.material3.Text
import coil3.ImageLoader

class TvMediaScrollTestActivity : ComponentActivity() {
    private var screen by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screen = intent.getStringExtra("screen")
        val loader = ImageLoader(this)
        val cards = List(30) { index -> TvMediaCard("item_$index", "测试影片 $index") }
        setContent {
            Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                CompositionLocalProvider(LocalTvMediaFocusBinding provides TvMediaFocusBinding("fixture", "poster", { _, _ -> }, {})) {
                    var mode by remember { mutableStateOf(TvMediaSearchMode.WORKS) }
                    var more by remember { mutableStateOf(false) }
                    var category by remember { mutableStateOf("movie") }
                    TvMediaTheme {
                        if (screen == "home") {
                            TvMediaHome(
                                state = TvMediaHomeState(
                                    scopeKey = "empty-category-fixture",
                                    shelves = listOf(TvMediaShelf("catalog", "我的片库",
                                        items = if (category == "movie") cards.take(6) else emptyList())),
                                    categories = listOf(TvMediaCategory("movie", "电影"), TvMediaCategory("series", "剧集")),
                                    selectedCategory = category,
                                ),
                                imageLoader = loader,
                                onItem = {},
                                onContinue = {},
                                onCategory = { category = it },
                                navigation = {},
                            )
                        } else if (screen == "detail-busy") {
                            TvMediaDetail(
                                state = TvMediaDetailState(
                                    scopeKey = "busy-detail-fixture",
                                    item = TvMediaCard("lib_1/item_1", "测试影视"),
                                    userStateBusy = true,
                                    episodes = listOf(TvMediaEpisode("episode_1", "测试分集", "第 1 集")),
                                    episodesLoading = true,
                                ),
                                imageLoader = loader,
                                onBack = {},
                                onToggleFavorite = {},
                                onToggleWatched = {},
                                onMoreEpisodes = {},
                            )
                        } else if (screen == "works") {
                            TvMediaWorksPage(
                                header = { Text("测试作品页") },
                                cards = cards,
                                imageLoader = loader,
                                loading = false,
                                error = null,
                                emptyMessage = "暂无作品",
                                hasMore = true,
                                onItem = {},
                                onRetry = {},
                                onMore = {},
                            )
                        } else {
                            TvMediaSearchScreen("测试", true, mode,
                                entries = cards, loading = false, error = null, hasMore = !more,
                                people = emptyList(), peopleLoading = false, peopleError = null, peopleHasMore = false,
                                files = emptyList(), filesLoading = false, filesError = null, filesHasMore = false,
                                collections = emptyList(), collectionsLoading = false, collectionsError = null, collectionsHasMore = false,
                                canSearchFiles = false, canSearchCollections = false, imageLoader = loader, personPortrait = { null },
                                onQuery = {}, onSubmit = {}, onMode = { mode = it }, onBack = {}, onItem = {},
                                onPerson = { _, _ -> }, onCollection = { _, _ -> }, onRetry = {}, onMore = { more = true })
                        }
                    }
                }
            }
        }
    }
}
