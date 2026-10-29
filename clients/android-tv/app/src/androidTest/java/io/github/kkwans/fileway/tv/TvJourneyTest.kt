package io.github.kkwans.fileway.tv

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import io.github.kkwans.nasfilebrowser.data.VideoDecodePolicy
import io.github.kkwans.nasfilebrowser.data.ProfileStore
import io.github.kkwans.nasfilebrowser.data.CredentialVault
import io.github.kkwans.nasfilebrowser.player.PlayerViewport
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Real API28 TV rendering and input against an isolated real Fileway backend.
 * Software video diagnostics, generated media and no host audio: never HDR,
 * physical-device, high-resolution performance or audible-output acceptance. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class TvJourneyTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private lateinit var scenario: ActivityScenario<TvActivity>
    private lateinit var model: TvModel
    private lateinit var evidence: File
    private val movieName = "Fileway owned 150s +%# fixture.mp4"

    @Before fun connectOwnedService() {
        Configurator.getInstance().waitForIdleTimeout = 0
        val args = InstrumentationRegistry.getArguments()
        val url = requireNotNull(args.getString("fixture_url")) { "Owned real backend is required" }
        val username = requireNotNull(args.getString("fixture_username"))
        val password = requireNotNull(args.getString("fixture_password"))
        scenario = ActivityScenario.launch(TvActivity::class.java)
        scenario.onActivity { model = ViewModelProvider(it)[TvModel::class.java] }
        evidence = File(instrumentation.targetContext.getExternalFilesDir(null), "tv-acceptance").apply { mkdirs() }
        waitFor("startup restores or offers login", 20_000) { !model.state.value.restoring }
        if (model.state.value.connected) {
            scenario.onActivity { model.disconnect() }
            waitFor("previous fixture session closes") { !model.state.value.connected && model.media.state.value.file == null }
        }
        capture("login-dark.png")
        scenario.onActivity { activity -> activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(activity.window.decorView.windowToken, 0) }
        scenario.onActivity { model.media.setVideoDecodePolicy(VideoDecodePolicy.SOFTWARE); model.login(url, username, password) }
        waitFor("real service login", 30_000) { model.state.value.connected && !model.state.value.busy }
        waitFor("software diagnostics explicitly selected") { model.media.playbackPreferences.videoDecodePolicy.value == VideoDecodePolicy.SOFTWARE }
    }

    @After fun releaseOwnedActivity() {
        if (::scenario.isInitialized) {
            scenario.onActivity { model.media.close() }
            waitFor("media releases", 15_000) { model.media.state.value.file == null }
            scenario.close()
        }
    }

    @Test fun remoteBrowsePlaybackFocusThemeAndPictures() {
        capture("home-dark.png")
        activate("文件", sidebar = true)
        waitFor("root files") { model.state.value.route == TvRoute.FILES && !model.state.value.busy && model.state.value.files.isNotEmpty() }
        activate("Cinema")
        waitFor("cinema folder") { model.state.value.path == "/Cinema" && !model.state.value.busy }
        capture("files-dark.png")
        activate(movieName)
        waitFor("actual decoded frame and advancing clock", 30_000) { model.media.player.state.value.firstFrameRendered && model.media.player.state.value.positionMs > 600 }
        assertDecodedPattern()
        waitFor("real video disk cache fills",20_000){model.media.cache.state.value.usedBytes>0 && model.media.cache.state.value.ranges.isNotEmpty()}
        device.pressDPadUp(); SystemClock.sleep(200)
        scenario.onActivity { model.media.player.pause() }
        capture("player-controls.png")
        val identity = requireNotNull(model.media.state.value.info).getString("identity")
        val file = requireNotNull(model.media.state.value.file)
        repeat(3) { if (model.media.state.value.file != null) { device.pressBack(); SystemClock.sleep(250) } }
        waitFor("back restores library") { model.media.state.value.file == null }
        assertEquals(file.wirePath, model.anchor().key)
        waitFor("same semantic card receives focus") { focusable(movieName)?.isFocused == true }
        val owner = requireNotNull(model.context)
        val saved = runBlocking { TvDatabase.get(instrumentation.targetContext).playback().snapshot(owner.account.key, file.wirePath, identity) }
        assertNotNull("original-media checkpoint persisted", saved)
        assertTrue("confirmed position is saved", requireNotNull(saved).positionMs > 0)
        activate("详情")
        if (model.favorites.favorite(file) == null) activate("加入收藏")
        waitFor("server acknowledges favorite") { model.favorites.favorite(file) != null && !model.favorites.state.value.changing }
        capture("details-favorite.png")
        device.pressBack()
        activate("设置", sidebar = true)
        activate("浅色")
        waitFor("light theme selected") { !model.state.value.dark }
        capture("settings-light.png")
        activate("深色")
        activate("图片", sidebar = true)
        waitFor("image root") { model.state.value.route == TvRoute.IMAGES && !model.state.value.busy }
        activate("Photos")
        waitFor("photo folder") { model.state.value.path == "/Photos" && !model.state.value.busy }
        activate("Fileway owned color blocks.png")
        waitFor("authenticated photo") { model.media.state.value.imageUrl != null && !model.media.state.value.busy }
        SystemClock.sleep(500)
        capture("photo-fit.png")
        device.pressDPadCenter()
        activate("放大")
        device.pressDPadDown(); device.pressDPadRight()
        capture("photo-zoom-pan.png")
        device.pressBack()
        capture("photo-return-fit.png")
        device.pressBack()
        waitFor("photo exits to original folder") { model.media.state.value.file == null && model.state.value.path == "/Photos" }
    }

    @Test fun compatibleSeekKeepsOriginalTimelineAndSourceSubtitles() {
        scenario.onActivity { model.navigate(TvRoute.FILES); model.browse("/Cinema", "/Cinema", false) }
        waitFor("owned media listing") { model.state.value.path == "/Cinema" && !model.state.value.busy }
        val movie = model.state.value.files.single { it.name == movieName }
        scenario.onActivity { model.open(movie, listOf(movie)) }
        waitFor("native pattern", 30_000) { model.media.player.state.value.firstFrameRendered }
        waitFor("sidecars loaded") { model.media.state.value.sidecars.any { it.name.endsWith("timeline.srt") } }
        val subtitle = model.media.state.value.sidecars.first { it.name.endsWith("timeline.srt") }
        scenario.onActivity { model.media.player.pause(); model.media.seek(121_000); model.media.selectExternalSubtitle(subtitle) }
        waitFor("original seek is confirmed", 25_000) { model.media.player.state.value.canSavePosition && model.media.sourcePosition() in 120_000..123_000 }
        scenario.onActivity { model.media.switchMode(TvHdrMode.SDR) }
        waitFor("real compatibility window decoded", 90_000) { !model.media.state.value.busy && model.media.state.value.compatible && model.media.player.state.value.firstFrameRendered }
        assertTrue("source offset is retained", model.media.state.value.sourceOffsetMs >= 120_000)
        assertTrue("position is original time: source=${model.media.sourcePosition()} local=${model.media.player.state.value.positionMs} offset=${model.media.state.value.sourceOffsetMs}", model.media.sourcePosition() in 120_000..124_000)
        assertFalse("pause intent survives source switching", model.media.player.wantsPlay)
        assertTrue("source subtitle restored", model.media.player.state.value.selectedSubtitle >= 0)
        capture("sdr-source-timeline.png")
        scenario.onActivity { model.media.switchMode(TvHdrMode.NATIVE) }
        waitFor("original source restores", 30_000) { !model.media.state.value.busy && !model.media.state.value.compatible && model.media.player.state.value.firstFrameRendered }
        assertTrue(model.media.sourcePosition() in 120_000..125_000)
        assertFalse(model.media.player.wantsPlay)
    }

    @Test fun savedPasswordRecoversExpiredTokenAfterAppRelaunch() {
        val store=ProfileStore(TvDatabase.get(instrumentation.targetContext),CredentialVault(instrumentation.targetContext))
        val (profile,account)=runBlocking{requireNotNull(store.active())}
        val ownedPassword=requireNotNull(InstrumentationRegistry.getArguments().getString("fixture_password"))
        assertTrue("password is retained in encrypted local vault",runBlocking{store.password(profile,account)}==ownedPassword)
        val token=runBlocking{requireNotNull(store.token(profile,account))}
        val parts=token.split('.')
        val flags=android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
        val payload=org.json.JSONObject(String(android.util.Base64.decode(parts[1],flags))).put("exp",1)
        val rejected=parts[0]+"."+android.util.Base64.encodeToString(payload.toString().toByteArray(),flags)+"."+parts[2]
        scenario.close()
        CredentialVault(instrumentation.targetContext).write(account.credentialRef,rejected.toByteArray())
        scenario=ActivityScenario.launch(TvActivity::class.java)
        scenario.onActivity{model=ViewModelProvider(it)[TvModel::class.java]}
        waitFor("saved password renews login after expired/rejected token",30_000){model.state.value.connected&&!model.state.value.restoring}
        assertEquals(account.userId,model.context?.api?.identity?.id)
        capture("restored-login-home.png")
    }

    private fun activate(label: String, sidebar: Boolean = false) {
        val end = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < end) {
            val target = focusable(label, sidebar)
            if (target == null) { device.pressDPadDown(); SystemClock.sleep(120); continue }
            if (target.isFocused) { device.pressDPadCenter(); SystemClock.sleep(150); return }
            val selected = device.findObject(By.focused(true))
            if (selected == null) device.pressDPadRight() else {
                val from = selected.visibleBounds; val to = target.visibleBounds
                val dx = to.centerX() - from.centerX(); val dy = to.centerY() - from.centerY()
                when { !sidebar && to.bottom <= from.top -> device.pressDPadUp()
                    !sidebar && to.top >= from.bottom -> device.pressDPadDown()
                    abs(dx) > abs(dy) -> if (dx < 0) device.pressDPadLeft() else device.pressDPadRight()
                    dy < 0 -> device.pressDPadUp()
                    else -> device.pressDPadDown() }
            }
            SystemClock.sleep(100)
        }
        capture("failure-focus.png")
        error("Remote target could not be reached: $label")
    }

    private fun focusable(label: String, sidebar: Boolean = false): UiObject2? {
        val nodes = (device.findObjects(By.desc(label))+device.findObjects(By.text(label))).filter { !it.visibleBounds.isEmpty }
        val candidates=nodes.mapNotNull { node ->
            var current:UiObject2?=node
            var found:UiObject2?=null
            repeat(8){if(found==null){if(current?.isFocusable==true)found=current else current=current?.parent}}
            found
        }
        return candidates.firstOrNull{it.isFocused} ?: if(sidebar)candidates.minByOrNull{it.visibleBounds.centerX()} else candidates.firstOrNull()
    }

    private fun capture(name: String) { device.waitForIdle(2000); assertTrue("native screenshot saved", device.takeScreenshot(File(evidence, name))) }
    private fun waitFor(message: String, timeout: Long = 15_000, test: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < end) { if (test()) return; SystemClock.sleep(100) }
        capture("failure-state.png")
        error("Timed out: $message; mediaError=${model.media.state.value.error}; playerError=${model.media.player.state.value.error}")
    }

    private fun assertDecodedPattern() {
        val done = CountDownLatch(1)
        val bitmap = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888)
        var status = -1
        scenario.onActivity { activity ->
            fun viewport(view: View): PlayerViewport? {
                if (view is PlayerViewport) return view
                if (view is ViewGroup) for (i in 0 until view.childCount) viewport(view.getChildAt(i))?.let { return it }
                return null
            }
            val surface = requireNotNull(viewport(activity.window.decorView)).video
            PixelCopy.request(surface, bitmap, { result -> status = result; done.countDown() }, Handler(Looper.getMainLooper()))
        }
        assertTrue(done.await(5, TimeUnit.SECONDS)); assertEquals(PixelCopy.SUCCESS, status)
        val pixels = IntArray(160 * 90); bitmap.getPixels(pixels, 0, 160, 0, 0, 160, 90); bitmap.recycle()
        assertTrue("decoded generated pattern has visible color variation", pixels.map { Color.rgb(Color.red(it)/16*16,Color.green(it)/16*16,Color.blue(it)/16*16) }.distinct().size > 12)
    }
}
