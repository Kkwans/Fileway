package io.github.kkwans.fileway.tv

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.os.SystemClock
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import io.github.kkwans.nasfilebrowser.app.mediaKey
import io.github.kkwans.nasfilebrowser.data.TextSubtitleAppearance
import io.github.kkwans.nasfilebrowser.data.VideoDecodePolicy
import io.github.kkwans.nasfilebrowser.player.PlayerViewport
import java.io.File
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Owned fixture, actual TV Activity, input and software-decoded SDR frames.
 * Captures are for human visual review; this does not establish HDR, Q1, audio,
 * long-track, empty-state or visual-design acceptance. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class TvPlayerUiJourneyTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val movieName = InstrumentationRegistry.getArguments().getString("fixture_movie")
        ?.takeIf { it == "FilewayVisual.mp4" } ?: "Fileway owned 150s +%# fixture.mp4"
    private val subtitleName = "Fileway owned timeline.srt"
    private lateinit var evidence: File
    private var stage = "arguments"
    private val checks = mutableListOf<String>()
    private val screenshots = mutableListOf<String>()
    private val backWindows = mutableListOf<JSONObject>()
    private var backLogStatus = "not captured"
    private var model: TvModel? = null
    private var fixtureUrl = ""
    private var fixturePassword = ""

    @Test fun ownedBrowsePlayerSubtitleAndBackJourney() {
        Configurator.getInstance().waitForIdleTimeout = 0
        evidence = File(requireNotNull(instrumentation.targetContext.getExternalFilesDir(null)),
            "tv-player-ui-journey-${System.currentTimeMillis()}").apply { check(mkdirs()) }
        var scenario: ActivityScenario<TvActivity>? = null
        var originalPolicy: VideoDecodePolicy? = null
        var originalAppearance: TextSubtitleAppearance? = null
        var failure: Throwable? = null
        var failedState: JSONObject? = null
        var environmentSafe = false
        var localFixture: File? = null
        val cleanupFailures = mutableListOf<String>()

        fun cleanup(name: String, action: () -> Unit) {
            try { action() } catch (error: Throwable) {
                cleanupFailures += "$name: ${error.javaClass.simpleName}: ${safe(error.message)}"
                if (failure == null) { stage = "cleanup/$name"; failure = error; failedState = stateSnapshot() }
            }
        }

        try {
            mark("arguments")
            val args = InstrumentationRegistry.getArguments()
            check(Build.VERSION.SDK_INT == 28) { "This journey is scoped to Android 9/API 28" }
            fixtureUrl = requireNotNull(args.getString("fixture_url")) { "fixture_url is required" }
            val username = requireNotNull(args.getString("fixture_username")) { "fixture_username is required" }
            fixturePassword = requireNotNull(args.getString("fixture_password")) { "fixture_password is required" }
            val uri = URI(fixtureUrl)
            check(username == "tv-fixture" && fixturePassword.isNotEmpty()) { "Only the owned fixture account is supported" }
            check(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
                uri.rawQuery == null && uri.rawFragment == null) { "Invalid fixture endpoint" }

            mark("verify isolated account storage")
            runBlocking {
                withContext(Dispatchers.IO) {
                    val database = TvDatabase.get(instrumentation.targetContext)
                    val profiles = database.profiles().profiles().first()
                    check(profiles.all { it.address.trimEnd('/') == fixtureUrl.trimEnd('/') }) {
                        "Non-fixture profile present; use a dedicated test device"
                    }
                    val otherAccounts = database.openHelper.readableDatabase
                        .query("SELECT COUNT(*) FROM accounts WHERE username <> ?", arrayOf("tv-fixture"))
                        .use { cursor -> check(cursor.moveToFirst()); cursor.getLong(0) }
                    check(otherAccounts == 0L) { "Non-fixture account present; use a dedicated test device" }
                }
            }
            environmentSafe = true
            checks += "isolated fixture account and profile checked before Activity launch"

            mark("launch and login")
            val launched = ActivityScenario.launch(TvActivity::class.java)
            scenario = launched
            launched.onActivity { model = ViewModelProvider(it)[TvModel::class.java] }
            val tv = requireNotNull(model)
            waitFor("restore", 20_000) { !tv.state.value.restoring }
            if (tv.state.value.connected) {
                val current = requireNotNull(tv.context)
                check(current.profile.address.trimEnd('/') == fixtureUrl.trimEnd('/') && current.account.username == username) {
                    "Active session does not belong to the owned fixture"
                }
            } else {
                launched.onActivity { tv.login(fixtureUrl, username, fixturePassword) }
                waitFor("fixture login", 30_000) {
                    val login = tv.state.value
                    check(login.busy || login.connected || login.error == null) { "Fixture login failed: ${safe(login.error)}" }
                    login.connected && !login.busy
                }
            }
            check(tv.media.state.value.file == null) { "A player is already active; use a dedicated test Activity" }
            originalPolicy = tv.media.playbackPreferences.videoDecodePolicy.value
            originalAppearance = tv.media.playbackPreferences.textSubtitleAppearance.value
            launched.onActivity { tv.media.setVideoDecodePolicy(VideoDecodePolicy.SOFTWARE); tv.navigate(TvRoute.HOME) }
            waitFor("software decoder preference") { tv.media.playbackPreferences.videoDecodePolicy.value == VideoDecodePolicy.SOFTWARE }

            mark("browse fixture using touch and remote")
            touch("文件", leftmost = true)
            waitFor("file root") { tv.state.value.route == TvRoute.FILES && tv.state.value.path == "/" && !tv.state.value.busy }
            touch("Cinema")
            waitFor("owned Cinema directory") {
                tv.state.value.path == "/Cinema" && !tv.state.value.busy && tv.state.value.files.any { it.name == movieName }
            }
            waitVisible(movieName, actionable = true)
            capture("browse.png")
            activate(movieName)
            waitFor("decoded owned movie", 30_000) {
                val media = tv.media.state.value
                val player = tv.media.player.state.value
                check(media.error == null && player.error == null) { "Playback failed: ${safe(media.error ?: player.error)}" }
                media.file?.name == movieName && player.firstFrameRendered && player.playing
            }
            if (movieName == "FilewayVisual.mp4") {
                waitFor("visual fixture seekable") { tv.media.canSeek }
                launched.onActivity { tv.media.seek(15_000) }
                waitFor("visual fixture frame", 30_000) { tv.media.sourcePosition() in 14_000L..22_000L }
            }
            decodedPattern(launched)
            waitFor("owned audio track") { tv.media.player.state.value.audio.isNotEmpty() }
            val audioTitle = tv.media.player.state.value.audio.first().title
            waitFor("owned external subtitle listing") { tv.media.state.value.sidecars.any { it.name == subtitleName } }
            check(tv.media.state.value.scopedSubtitlePages) { "The fixture server must advertise scoped subtitle resources" }
            val movie = requireNotNull(tv.media.state.value.file)
            val sidecar = tv.media.state.value.sidecars.single { it.name == subtitleName }
            val mediaEpoch = tv.media.controlEpoch
            val generation = tv.media.player.state.value.mediaGeneration
            val sourceIdentity = requireNotNull(tv.media.state.value.info).getString("identity")
            val localSubtitle = File.createTempFile("Fileway local subtitle ", ".srt",
                requireNotNull(instrumentation.targetContext.getExternalFilesDir(null))).also { file ->
                localFixture = file
                file.writeText("1\n00:00:00,000 --> 00:02:30,000\nFileway local subtitle fixture\n")
            }
            fun sameSource() {
                assertEquals("media epoch changed", mediaEpoch, tv.media.controlEpoch)
                assertEquals("video generation changed", generation, tv.media.player.state.value.mediaGeneration)
                assertEquals("file identity changed", movie.mediaKey, tv.media.state.value.file?.mediaKey)
                assertEquals("source identity changed", sourceIdentity, tv.media.state.value.info?.optString("identity"))
                launched.onActivity {
                    assertEquals("browser anchor changed behind the player", movie.mediaKey, tv.anchor().key)
                }
            }
            checks += if (movieName == "FilewayVisual.mp4")
                "actual touch navigation and D-pad file opening decoded the CC-BY visual fixture"
            else "actual touch navigation and D-pad file opening decoded owned color bars"

            mark("pause and show HUD")
            assertNull("HUD must start hidden", node("播放进度"))
            observeWindows("before-hidden-confirm")
            // The owned clip can reach its natural end while screenshots and
            // accessibility inspection run on a slow emulator. At EOF the same
            // remote key correctly resumes playback; start it again before
            // checking the distinct playing -> paused transition.
            if (!tv.media.player.wantsPlay) {
                assertTrue(device.pressDPadCenter())
                waitFor("remote resumes playback") { tv.media.player.wantsPlay && tv.media.player.state.value.playing }
                checks += "hidden OK resumes playback after the owned clip ends"
            }
            val sourceDuration = tv.media.state.value.sourceDurationMs
            val endMargin = if (movieName == "FilewayVisual.mp4") 4_000L else 15_000L
            if (sourceDuration > 0 && sourceDuration - tv.media.sourcePosition() < endMargin) {
                launched.onActivity { tv.media.seek(0) }
                waitFor("playback moved away from the end") { tv.media.sourcePosition() < 10_000L }
            }
            waitFor("playing before remote pause") { tv.media.player.wantsPlay && tv.media.player.state.value.playing }
            assertTrue(device.pressDPadCenter())
            observeWindows("after-hidden-confirm")
            waitFor("remote pauses playback") { !tv.media.player.wantsPlay && !tv.media.player.state.value.playing }
            assertNull("hidden OK must not open HUD", node("播放进度"))
            assertTrue(device.pressDPadDown())
            waitVisible("播放设置")
            assertNull("Down opens the detailed menu without the HUD", node("播放进度"))
            assertTrue(device.pressBack())
            waitFor("detailed menu closes to video") { node("播放设置") == null && node("播放进度") == null }
            assertTrue(device.pressDPadUp())
            waitVisible("播放进度", actionable = true)
            waitFocus("播放")
            assertNull("HUD must not contain the removed back button", node("退出播放"))
            assertEquals("transport and actions share a center line", bounds("播放").centerY(), bounds("字幕").centerY())
            capture("hud.png")
            sameSource()

            mark("quick queue")
            touch("列表")
            waitVisible("播放列表")
            capture("quick-queue.png")
            assertTrue(device.pressBack()); waitFocus("列表")

            mark("quick subtitle selection")
            val subtitleTrigger = bounds("字幕")
            touch("字幕")
            waitFocus("关闭字幕")
            val subtitlePopup = Rect().also { waitVisible("字幕快捷菜单").getBoundsInScreen(it) }
            val popupGap = subtitleTrigger.top - subtitlePopup.bottom
            val maxGap = (instrumentation.targetContext.resources.displayMetrics.density * 24).toInt()
            assertTrue("quick popup stays near its trigger: $popupGap px", popupGap in 0..maxGap)
            assertNull("quick subtitles do not expose add or appearance", node("添加字幕"))
            assertNull("quick subtitles do not expose appearance", node("字幕调整"))
            assertNull("subtitle sources must not appear as a selectable-looking heading", node("同目录外挂"))
            capture("quick-subtitle.png")
            assertFocusOutlineVisible("关闭字幕", "quick-subtitle.png")
            moveUntil(subtitleName, "down", 20)
            assertTrue(device.pressDPadCenter()); waitFocus("字幕")
            waitFor("quick subtitle selected", 45_000) {
                val media = tv.media.state.value
                val current = tv.media.player.state.value
                check(media.subtitleError == null) { "Subtitle selection failed: ${safe(media.subtitleError)}" }
                !media.subtitleLoading && !current.subtitleLoading && current.selectedSubtitle >= 0 &&
                    tv.media.player.importedSubtitleIdentity(current.selectedSubtitle)?.sourceKey == "external:${sidecar.mediaKey}"
            }
            sameSource()
            checks += "quick subtitle choice applies to the original video and closes to its HUD trigger"
            val selectedBeforeFailure = tv.media.player.state.value.selectedSubtitle
            val sourceKey = "external:${sidecar.mediaKey}"
            val missing = File(requireNotNull(instrumentation.targetContext.getExternalFilesDir(null)),
                "missing-subtitle-${System.currentTimeMillis()}.srt")
            assertFalse(missing.exists())
            val canImport = runBlocking { withContext(Dispatchers.Main) { tv.media.player.canAddExternalSubtitle } }
            assertTrue("fixture player must accept an external subtitle request", canImport)
            val failedImport = runBlocking {
                withTimeout(15_000) {
                    withContext(Dispatchers.Main) { tv.media.player.importSubtitle(missing.toURI().toString(), missing.name, sourceKey, true) }
                }
            }
            assertFalse("a missing replacement must fail", failedImport)
            assertEquals("failed replacement keeps the previous subtitle", selectedBeforeFailure, tv.media.player.state.value.selectedSubtitle)
            assertTrue("failed replacement keeps the previous source track", tv.media.player.state.value.subtitles.any { it.id == selectedBeforeFailure })
            tv.media.player.state.value.operationError?.let { error -> launched.onActivity { tv.media.player.clearOperationError(error) } }
            checks += "a failed replacement retains the previously accepted subtitle"
            touch("音轨")
            waitVisible("音轨")
            waitVisible(audioTitle, actionable = true)
            capture("quick-audio.png")
            assertTrue(device.pressBack()); waitFocus("音轨")
            touch("倍速 1.0×")
            waitVisible("播放速度")
            waitFocus("1.0×")
            assertNull("quick speed does not offer custom input", node("自定义速度（0.1–5）"))
            capture("quick-speed.png")
            assertTrue(device.pressDPadDown()); waitFocus("1.25×")
            assertTrue(device.pressDPadCenter())
            waitFor("playback rate changed") { abs(tv.media.player.state.value.rate - 1.25f) < .001f }
            waitFocus("倍速 1.25×")
            touch("倍速 1.25×"); waitFocus("1.25×")
            assertTrue(device.pressDPadUp()); waitFocus("1.0×")
            assertTrue(device.pressDPadCenter())
            waitFor("playback rate restored") { abs(tv.media.player.state.value.rate - 1f) < .001f }
            waitFocus("倍速 1.0×")
            checks += "compact speed choice changes and restores the actual player rate"
            touch("设置")
            waitVisible("播放设置")
            capture("settings-preview.png")
            assertFocusOutlineVisible("播放速度", "settings-preview.png")
            touch("播放速度")
            waitVisible("播放速度")
            waitFocus("1.0×")
            capture("full-speed-selected.png")
            moveUntil("自定义速度（0.1–5）", "down", 12)
            waitFocus("自定义速度（0.1–5）")
            capture("full-speed.png")
            observeWindows("custom-before-back")
            assertTrue(device.pressBack())
            SystemClock.sleep(200)
            observeWindows("custom-after-back")
            if (node("自定义速度（0.1–5）", actionable = true)?.isFocused == true) {
                checks += "first Back with the custom input focused retained the popup; input-method state recorded"
                assertTrue(device.pressBack())
            }
            waitFocus("播放速度")
            assertTrue(device.pressBack()); waitFocus("设置")
            assertTrue(device.pressDPadRight()); waitFocus("列表")
            assertTrue(device.pressDPadRight())
            if (tv.media.state.value.queue?.hasPrevious == true) {
                waitFocus("上一项")
                assertTrue(device.pressDPadRight())
            }
            waitFocus("播放")

            mark("HUD spatial navigation")
            assertTrue(device.pressDPadUp()); waitFocus("播放进度")
            assertTrue(device.pressDPadDown()); waitFocus("播放")
            moveUntil("字幕", "right", 8)
            assertTrue(device.pressDPadUp()); waitFocus("播放进度")
            assertTrue(device.pressDPadDown()); waitFocus("字幕")
            checks += "HUD vertical navigation preserves transport alignment and returns from the timeline to the originating action"

            mark("subtitle track")
            assertTrue(device.pressDPadDown())
            waitVisible("播放设置")
            touch("字幕")
            waitVisible("字幕")
            waitVisible("字幕调整", actionable = true)
            waitVisible("添加字幕", actionable = true)
            waitFocus(subtitleName)
            assertNull("HUD semantics must not appear in the panel window", node("播放进度"))
            val group = bounds("选择字幕")
            val firstTrack = bounds("关闭字幕")
            assertEquals("group and track share their top edge", group.top, firstTrack.top)
            assertEquals("group and track share their bottom edge", group.bottom, firstTrack.bottom)
            capture("subtitle-preview.png")
            assertFocusOutlineVisible(subtitleName, "subtitle-preview.png")
            assertLabelCentered("选择字幕", "subtitle-preview.png")
            assertNull("subtitle source heading removed from the full panel", node("同目录外挂"))
            val selectedTrack = bounds(subtitleName)
            assertEquals("subtitle rows share the same right edge", firstTrack.right, selectedTrack.right)
            assertTrue("subtitle list must use the available panel width",
                device.displayWidth - selectedTrack.right < device.displayWidth * .12f)
            touch("音频")
            waitVisible(audioTitle, actionable = true)
            capture("full-audio.png")
            touch("列表")
            waitVisible("播放列表")
            val queue = requireNotNull(tv.media.state.value.queue)
            val currentQueueLabel = "${queue.index + 1}. ${queue.items[queue.index].name}"
            waitFocus(currentQueueLabel)
            if (queue.index > 0) {
                val previousQueueLabel = "${queue.index}. ${queue.items[queue.index - 1].name}"
                val previousBounds = bounds(previousQueueLabel)
                val currentBounds = bounds(currentQueueLabel)
                assertEquals("queue cards share one horizontal row", previousBounds.centerY(), currentBounds.centerY())
                assertTrue("queue cards follow source order", previousBounds.centerX() < currentBounds.centerX())
            }
            capture("full-queue.png")
            val firstQueueLabel = "1. ${queue.items.first().name}"
            moveUntil(firstQueueLabel, "left", queue.items.size + 1)
            val tabTouchBounds = bounds("播放列表")
            val firstCardBounds = bounds(firstQueueLabel)
            val tabVisualInset = (instrumentation.targetContext.resources.displayMetrics.density * 8).toInt()
            assertTrue("queue artwork and selected tab share a left edge",
                abs(firstCardBounds.left - (tabTouchBounds.left + tabVisualInset)) <= 1)
            capture("full-queue-first.png")
            assertTrue(device.pressDPadUp()); waitFocus("播放列表")
            capture("full-queue-tab-focused.png")
            assertTrue(device.pressDPadDown()); waitFocus(firstQueueLabel)
            moveUntil(currentQueueLabel, "right", queue.items.size + 1)
            assertTrue(device.pressDPadCenter())
            waitFocus("字幕")
            sameSource()
            checks += "reselecting the current queue card closes the menu without reopening video"
            assertTrue(device.pressDPadDown())
            waitVisible("播放设置")
            touch("字幕")
            waitVisible("字幕调整", actionable = true)
            waitFocus(subtitleName)
            moveUntil("关闭字幕", "up", 20)
            assertTrue(device.pressDPadCenter())
            waitFor("subtitle closed") { tv.media.player.state.value.selectedSubtitle == -1 }
            moveUntil(subtitleName, "down", 20)
            assertTrue(device.pressDPadCenter())
            waitFor("owned SRT selected", 45_000) {
                val media = tv.media.state.value
                val player = tv.media.player.state.value
                check(media.subtitleError == null) { "Subtitle selection failed: ${safe(media.subtitleError)}" }
                !media.subtitleLoading && !player.subtitleLoading && player.selectedSubtitle >= 0 &&
                    tv.media.player.importedSubtitleIdentity(player.selectedSubtitle)?.sourceKey == "external:${sidecar.mediaKey}"
            }
            val sourceTracks = runBlocking { withContext(Dispatchers.Main) {
                tv.media.player.state.value.subtitles.count { tv.media.player.importedSubtitleIdentity(it.id)?.sourceKey == sourceKey }
            } }
            assertEquals("reselecting a single-track subtitle must replace its old import", 1, sourceTracks)
            sameSource()
            checks += "owned SRT selected from the visible panel without changing media identity"

            mark("subtitle appearance and slider")
            waitFocus(subtitleName)
            assertTrue(device.pressDPadLeft()); waitFocus("选择字幕")
            moveUntil("字幕调整", "down", 4)
            assertTrue(device.pressDPadCenter()); waitFocus("字幕外观")
            assertTrue(device.pressDPadCenter()); waitFocus("字号")
            val oldScale = tv.media.playbackPreferences.textSubtitleAppearance.value.scale
            val sourcePosition = tv.media.sourcePosition()
            val increase = oldScale <= 1.9f
            val desired = (oldScale + if (increase) .1f else -.1f).coerceIn(.5f, 2f)
            assertTrue(if (increase) device.pressDPadRight() else device.pressDPadLeft())
            waitFor("subtitle scale committed") { abs(tv.media.playbackPreferences.textSubtitleAppearance.value.scale - desired) < .025f }
            assertTrue("Appearance D-pad must not seek video", abs(tv.media.sourcePosition() - sourcePosition) < 1000)
            capture("subtitle-appearance.png")
            assertTrue(device.pressDPadUp()); waitFocus("返回上一页")
            assertTrue(device.pressDPadDown()); waitFocus("字号")
            sameSource()

            mark("font child and panel focus restoration")
            moveUntil("字体", "down", 12)
            assertTrue(device.pressDPadCenter())
            waitVisible("字幕字体")
            capture("subtitle-font.png")
            observeWindows("before-font-back"); assertTrue(device.pressBack()); observeWindows("after-font-back"); waitFocus("字体")
            observeWindows("before-appearance-back"); assertTrue(device.pressBack()); observeWindows("after-appearance-back"); waitFocus("字幕外观")
            assertTrue(device.pressBack()); waitFocus("字幕调整")
            moveUntil("添加字幕", "up", 4)
            assertTrue(device.pressDPadCenter()); waitFocus("当前服务器")
            waitVisible("本机字幕", actionable = true)
            capture("subtitle-source.png")
            val firstBounds = bounds("当前服务器")
            val secondBounds = bounds("本机字幕")
            assertTrue(device.pressDPadDown()); waitFocus("本机字幕")
            assertEquals("focus must not resize the first choice", firstBounds, bounds("当前服务器"))
            assertEquals("focus must not resize the second choice", secondBounds, bounds("本机字幕"))
            assertTrue(device.pressDPadUp()); waitFocus("当前服务器")
            assertTrue(device.pressDPadCenter()); waitVisible("服务器字幕")
            waitFocus(subtitleName)
            assertTrue(device.pressDPadCenter())
            waitFor("scoped server subtitle selected", 45_000) {
                val media = tv.media.state.value
                val current = tv.media.player.state.value
                check(media.subtitleError == null) { "Scoped subtitle failed: ${safe(media.subtitleError)}" }
                !media.subtitleLoading && !current.subtitleLoading && current.selectedSubtitle >= 0 &&
                    tv.media.player.importedSubtitleIdentity(current.selectedSubtitle)?.sourceKey == sourceKey
            }
            waitFocus(subtitleName)
            sameSource()
            checks += "TV server browser selected a versioned scoped subtitle without reopening video"
            assertTrue(device.pressBack()); waitFocus("当前服务器")
            mark("TV local subtitle")
            activate("本机字幕")
            waitVisible("授权文件夹")
            activate("应用专属目录")
            activate(localSubtitle.name)
            waitFor("owned local SRT selected", 45_000) {
                val media = tv.media.state.value
                val player = tv.media.player.state.value
                check(media.subtitleError == null) { "Local subtitle failed: ${safe(media.subtitleError)}" }
                !media.subtitleLoading && !player.subtitleLoading && player.selectedSubtitle >= 0 &&
                    tv.media.player.importedSubtitleIdentity(player.selectedSubtitle)?.sourceKey?.startsWith("external:local:") == true
            }
            waitFocus("本机字幕")
            waitFor("local SRT reaches the actual viewport") { containsCue(launched, "Fileway local subtitle fixture") }
            sameSource()
            checks += "owned local SRT selected with D-pad and delivered to the actual viewport without reopening video"
            assertTrue(device.pressBack()); waitFocus("添加字幕")
            observeWindows("before-panel-back"); assertTrue(device.pressBack()); observeWindows("after-panel-back"); waitFocus("字幕")
            waitVisible("播放进度", actionable = true)
            assertFalse("pause intent changed during menus", tv.media.player.wantsPlay)
            sameSource()
            checks += "direct subtitle task, slider isolation, font and server-source child focus restore to the HUD"

            mark("back contract and restored browsing")
            observeWindows("before-hud-back")
            assertTrue(device.pressBack())
            observeWindows("after-hud-back")
            waitFor("HUD hidden") { node("播放进度") == null }
            assertNull("hiding HUD must not show exit prompt", node("再按一次返回退出播放"))
            capture("local-subtitle.png")
            sameSource()
            assertTrue(device.pressBack()); waitVisible("再按一次返回退出播放")
            assertTrue(device.pressBack())
            waitFor("player returns to directory", 20_000) { tv.media.state.value.file == null && tv.state.value.path == "/Cinema" }
            observeWindows("after-player-exit")
            waitFocus(movieName)
            assertEquals("browser anchor restores the same file", movie.mediaKey, tv.anchor().key)
            capture("browse-restored.png")
            checks += "HUD hides first; two hidden-state backs exit and restore the file focus"

            mark("settings exit by touch")
            activate(movieName)
            waitFor("reopened owned movie", 30_000) {
                tv.media.state.value.file?.mediaKey == movie.mediaKey && tv.media.player.state.value.firstFrameRendered
            }
            assertTrue(device.pressDPadUp()); waitVisible("播放进度", actionable = true)
            waitFocus("暂停")
            touch("设置")
            waitVisible("退出播放", actionable = true)
            moveUntil("播放信息", "down", 4)
            moveUntil("退出播放", "right", 4)
            touch("退出播放")
            waitFor("one touch exits to the original directory", 20_000) {
                tv.media.state.value.file == null && tv.state.value.path == "/Cinema"
            }
            waitFocus(movieName)
            assertEquals("touch exit restores the same file", movie.mediaKey, tv.anchor().key)
            checks += "settings exit is reachable by D-pad and one touch returns to the same file"
        } catch (error: Throwable) {
            failure = error
            failedState = stateSnapshot()
            if (environmentSafe && scenario != null) {
                runCatching { capture("failure.png") }
                runCatching { device.dumpWindowHierarchy(File(evidence, "failure.xml")) }
            }
        } finally {
            if(environmentSafe && scenario!=null) {
                backLogStatus=runCatching {
                    val output=device.executeShellCommand("logcat -d -v threadtime -s FilewayTvBack:D FilewayTvInput:D FilewayTvFocus:D")
                    val lines=output.lineSequence().filter {
                        it.contains("FilewayTvBack") || it.contains("FilewayTvInput") || it.contains("FilewayTvFocus")
                    }.toList().takeLast(200)
                    File(evidence,"back-diagnostics.log").writeText(lines.joinToString("\n"))
                    if(lines.isEmpty())"empty" else "captured"
                }.getOrDefault("unavailable")
            }
            val tv = model
            val launched = scenario
            if (tv != null && launched != null) {
                cleanup("owned player") {
                    if (tv.media.state.value.file?.name == movieName) runBlocking {
                        withTimeout(15_000) { withContext(Dispatchers.Main) { tv.media.closeAndSave() } }
                    }
                }
                cleanup("preferences") {
                    runBlocking {
                        originalAppearance?.let { tv.media.playbackPreferences.saveTextSubtitleAppearance(it) }
                        originalPolicy?.let { tv.media.playbackPreferences.saveVideoDecodePolicy(it) }
                    }
                }
            }
            if (launched != null) cleanup("activity") { launched.close() }
            localFixture?.let { file -> cleanup("owned local subtitle") { check(!file.exists() || file.delete()) } }
        }
        record(if (failure == null) "PASS" else "FAIL", failedState, cleanupFailures, failure)
        val error = failure
        if (error != null) {
            val reported = AssertionError("TV UI stage=$stage cause=${error.javaClass.simpleName}: ${safe(error.message)}; evidence=${evidence.name}")
            reported.stackTrace = error.stackTrace
            throw reported
        }
    }

    private fun safe(value: String?): String = value.orEmpty()
        .let { if (fixtureUrl.isBlank()) it else it.replace(fixtureUrl, "<fixture-url>") }
        .let { if (fixturePassword.isBlank()) it else it.replace(fixturePassword, "<redacted>") }.take(400)

    private fun stateSnapshot(): JSONObject {
        val tv = model
        val media = tv?.media?.state?.value
        val player = tv?.media?.player?.state?.value
        return JSONObject().put("connected", tv?.state?.value?.connected).put("busy", tv?.state?.value?.busy)
            .put("loginError", safe(tv?.state?.value?.error)).put("mediaError", safe(media?.error))
            .put("subtitleError", safe(media?.subtitleError)).put("playerError", safe(player?.error))
            .put("operationError", safe(player?.operationError)).put("mediaEpoch", tv?.media?.controlEpoch)
            .put("videoGeneration", player?.mediaGeneration).put("firstFrame", player?.firstFrameRendered)
            .put("playing", player?.playing).put("wantsPlay", tv?.media?.player?.wantsPlay).put("positionMs", player?.positionMs)
            .put("subtitleLoading", (media?.subtitleLoading == true || player?.subtitleLoading == true))
            .put("selectedSubtitle", player?.selectedSubtitle)
    }

    private fun record(status: String, failedState: JSONObject? = null, cleanupFailures: List<String> = emptyList(), failure: Throwable? = null) {
        File(evidence, "result.json").writeText(JSONObject().put("status", status).put("stage", stage)
            .put("api", Build.VERSION.SDK_INT).put("deviceModel", Build.MODEL).put("deviceProduct", Build.PRODUCT)
            .put("checks", JSONArray(checks)).put("screenshots", JSONArray(screenshots))
            .put("currentState", stateSnapshot()).put("failureState", failedState)
            .put("backWindows", JSONArray(backWindows))
            .put("backLogStatus",backLogStatus)
            .put("failure", failure?.let { JSONObject().put("type", it.javaClass.simpleName).put("message", safe(it.message)) })
            .put("cleanupFailures", JSONArray(cleanupFailures))
            .put("scope", if (movieName == "FilewayVisual.mp4")
                "CC-BY Blender visual fixture and owned SRT, software-decoded TV Activity; captures require human review; no HDR or audio acceptance"
            else "owned SDR color bars and SRT, software-decoded TV Activity; captures require human review; no HDR or audio acceptance")
            .toString(2))
    }

    private fun mark(value: String) { stage = value; record("RUNNING") }

    private fun waitFor(what: String, timeoutMs: Long = 15_000, test: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (test()) return
            SystemClock.sleep(80)
        }
        error("Timed out waiting for $what; ${stateSnapshot()}")
    }

    private fun node(label: String, actionable: Boolean = false, leftmost: Boolean = false): AccessibilityNodeInfo? {
        val root = instrumentation.uiAutomation.windows.filter {
            it.root?.packageName?.toString() == "io.github.kkwans.fileway.tv"
        }.maxByOrNull { it.layer }?.root ?: return null
        val found = mutableListOf<AccessibilityNodeInfo>()
        fun visit(value: AccessibilityNodeInfo?) {
            if (value == null) return
            if (value.isVisibleToUser && (value.text?.toString() == label || value.contentDescription?.toString() == label)) {
                var target: AccessibilityNodeInfo? = value
                if (actionable) while (target != null && !target.isClickable && target.rangeInfo == null) target = target.parent
                if (target != null) found += target
            }
            for (index in 0 until value.childCount) visit(value.getChild(index))
        }
        visit(root)
        if (leftmost) return found.minByOrNull { value -> Rect().also(value::getBoundsInScreen).centerX() }
        return found.firstOrNull { it.isFocused } ?: found.firstOrNull()
    }

    private fun observeWindows(event: String) {
        android.util.Log.d("FilewayTvBack","test=$event")
        val allWindows=instrumentation.uiAutomation.windows
        val windows=allWindows.filter{it.root?.packageName?.toString()=="io.github.kkwans.fileway.tv"}
        val details=windows.map { window ->
            val focused=window.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            val bounds=Rect().also{focused?.getBoundsInScreen(it)}
            JSONObject().put("layer",window.layer).put("active",window.isActive).put("focused",window.isFocused)
                .put("focusClass",focused?.className?.toString()).put("focusText",focused?.text?.toString())
                .put("focusDescription",focused?.contentDescription?.toString()).put("focusClickable",focused?.isClickable)
                .put("focusBounds",if(focused==null)"" else "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}")
        }
        val observation = JSONObject().put("event",event).put("timeMillis",System.currentTimeMillis()).put("windows",JSONArray(details))
        if (event.startsWith("custom-")) {
            val inputWindows = allWindows.filter { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }.map {
                JSONObject().put("active", it.isActive).put("focused", it.isFocused)
            }
            val dump = runCatching { device.executeShellCommand("dumpsys input_method") }.getOrDefault("")
            val flags = Regex("(?:mInputShown|mShowRequested|mInputStarted|mIsInputViewShown)=(?:true|false)")
                .findAll(dump).map { it.value }.distinct().toList()
            observation.put("inputWindows", JSONArray(inputWindows)).put("inputFlags", JSONArray(flags))
        }
        backWindows += observation
    }

    private fun waitVisible(label: String, actionable: Boolean = false): AccessibilityNodeInfo {
        waitFor("visible $label") { node(label, actionable) != null }
        return requireNotNull(node(label, actionable))
    }

    private fun waitFocus(label: String) = waitFor("focus $label") { node(label, actionable = true)?.isFocused == true }

    private fun bounds(label: String): Rect = Rect().also {
        waitVisible(label, actionable = true).getBoundsInScreen(it)
    }

    private fun touch(label: String, leftmost: Boolean = false) {
        waitFor("touch target $label") { node(label, actionable = true, leftmost = leftmost) != null }
        val bounds = Rect().also(requireNotNull(node(label, actionable = true, leftmost = leftmost))::getBoundsInScreen)
        assertFalse("empty touch bounds for $label", bounds.isEmpty)
        assertTrue("physical-coordinate click failed for $label", device.click(bounds.centerX(), bounds.centerY()))
        SystemClock.sleep(200)
    }

    private fun moveUntil(label: String, direction: String, max: Int) {
        repeat(max) {
            if (node(label, actionable = true)?.isFocused == true) return
            assertTrue(when (direction) {
                "up" -> device.pressDPadUp()
                "down" -> device.pressDPadDown()
                "left" -> device.pressDPadLeft()
                "right" -> device.pressDPadRight()
                else -> error("Unsupported direction")
            })
            // Accessibility focus can lag a delivered key on a busy emulator;
            // do not send the next key until the target has had time to publish.
            repeat(8) {
                if (node(label, actionable = true)?.isFocused == true) return
                SystemClock.sleep(80)
            }
        }
        waitFocus(label)
    }

    private fun activate(label: String) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val target = node(label, actionable = true)
            if (target?.isFocused == true) { assertTrue(device.pressDPadCenter()); return }
            val root = instrumentation.uiAutomation.windows.filter {
                it.root?.packageName?.toString() == "io.github.kkwans.fileway.tv"
            }.maxByOrNull { it.layer }?.root
            val current = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (target == null) device.pressDPadDown()
            else if (current == null) device.pressDPadRight()
            else {
                val from = Rect().also(current::getBoundsInScreen)
                val to = Rect().also(target::getBoundsInScreen)
                val dx = to.centerX() - from.centerX(); val dy = to.centerY() - from.centerY()
                when {
                    to.bottom <= from.top -> device.pressDPadUp()
                    to.top >= from.bottom -> device.pressDPadDown()
                    abs(dx) > abs(dy) -> if (dx < 0) device.pressDPadLeft() else device.pressDPadRight()
                    dy < 0 -> device.pressDPadUp()
                    else -> device.pressDPadDown()
                }
            }
            SystemClock.sleep(150)
        }
        error("D-pad could not reach $label")
    }

    private fun capture(name: String) {
        val file = File(evidence, name)
        assertTrue("screenshot failed: $name", device.takeScreenshot(file))
        assertTrue("empty screenshot: $name", file.length() > 0)
        screenshots += name
    }

    private fun assertFocusOutlineVisible(label: String, screenshot: String) {
        val rect = bounds(label)
        val bitmap = requireNotNull(BitmapFactory.decodeFile(File(evidence, screenshot).absolutePath))
        try {
            val band = (instrumentation.targetContext.resources.displayMetrics.density * 3).toInt().coerceAtLeast(2)
            val sampleCount = 40
            fun brightAlongEdge(top: Boolean): Int = (0 until sampleCount).count { sample ->
                val x = rect.left + rect.width() / 8 + (rect.width() * 3 / 4 * sample / (sampleCount - 1))
                (0..band).count { offset ->
                    val y = if (top) rect.top + offset else rect.bottom - 1 - offset
                    if (x !in 0 until bitmap.width || y !in 0 until bitmap.height) false else {
                        val pixel = bitmap.getPixel(x, y)
                        val red = Color.red(pixel); val green = Color.green(pixel); val blue = Color.blue(pixel)
                        minOf(red, green, blue) >= 175 && maxOf(red, green, blue) - minOf(red, green, blue) < 35
                    }
                } >= 2
            }
            assertTrue("focused row top outline is clipped: $label", brightAlongEdge(true) >= sampleCount * 4 / 5)
            assertTrue("focused row bottom outline is clipped: $label", brightAlongEdge(false) >= sampleCount * 4 / 5)
        } finally { bitmap.recycle() }
    }

    private fun assertLabelCentered(label: String, screenshot: String) {
        val rect = bounds(label)
        val bitmap = requireNotNull(BitmapFactory.decodeFile(File(evidence, screenshot).absolutePath))
        try {
            var first = rect.right
            var last = rect.left
            val density = instrumentation.targetContext.resources.displayMetrics.density
            val band = (density * 17).toInt()
            for (y in (rect.centerY() - band).coerceAtLeast(0) until (rect.centerY() + band).coerceAtMost(bitmap.height)) {
                for (x in (rect.left + 4).coerceAtLeast(0) until (rect.right - 4).coerceAtMost(bitmap.width)) {
                    val pixel = bitmap.getPixel(x, y)
                    val red = Color.red(pixel); val green = Color.green(pixel); val blue = Color.blue(pixel)
                    if (minOf(red, green, blue) >= 215 && maxOf(red, green, blue) - minOf(red, green, blue) < 28) {
                        first = minOf(first, x); last = maxOf(last, x)
                    }
                }
            }
            assertTrue("label not visible: $label", first <= last)
            assertTrue("section label is not horizontally centered: $label",
                abs((first + last) / 2 - rect.centerX()) <= (density * 2).toInt().coerceAtLeast(2))
        } finally { bitmap.recycle() }
    }

    private fun containsCue(scenario: ActivityScenario<TvActivity>, expected: String): Boolean {
        var found = false
        scenario.onActivity { activity ->
            fun viewport(view: View): PlayerViewport? {
                if (view is PlayerViewport) return view
                if (view is ViewGroup) for (index in 0 until view.childCount) viewport(view.getChildAt(index))?.let { return it }
                return null
            }
            val view = viewport(activity.window.decorView) ?: return@onActivity
            val field = PlayerViewport::class.java.getDeclaredField("cues").apply { isAccessible = true }
            found = (field.get(view) as? List<*>)?.any {
                (it as? androidx.media3.common.text.Cue)?.text?.toString()?.contains(expected) == true
            } == true
        }
        return found
    }

    private fun decodedPattern(scenario: ActivityScenario<TvActivity>) {
        val complete = CountDownLatch(1)
        val bitmap = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888)
        var status = -1
        try {
            scenario.onActivity { activity ->
                fun viewport(view: View): PlayerViewport? {
                    if (view is PlayerViewport) return view
                    if (view is ViewGroup) for (index in 0 until view.childCount) viewport(view.getChildAt(index))?.let { return it }
                    return null
                }
                val surface = requireNotNull(viewport(activity.window.decorView)).video
                PixelCopy.request(surface, bitmap, { result -> status = result; complete.countDown() }, Handler(Looper.getMainLooper()))
            }
            assertTrue("PixelCopy timed out", complete.await(5, TimeUnit.SECONDS))
            assertEquals("decoded surface copy", PixelCopy.SUCCESS, status)
            val pixels = IntArray(160 * 90)
            bitmap.getPixels(pixels, 0, 160, 0, 0, 160, 90)
            assertTrue("owned decoded frame has color variation", pixels.map {
                Color.rgb(Color.red(it) / 16 * 16, Color.green(it) / 16 * 16, Color.blue(it) / 16 * 16)
            }.distinct().size > 12)
        } finally { bitmap.recycle() }
    }
}
