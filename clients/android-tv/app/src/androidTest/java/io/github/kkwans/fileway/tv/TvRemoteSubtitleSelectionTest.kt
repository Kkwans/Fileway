package io.github.kkwans.fileway.tv

import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kkwans.nasfilebrowser.app.mediaKey
import io.github.kkwans.nasfilebrowser.data.VideoDecodePolicy
import io.github.kkwans.nasfilebrowser.data.ScopedSubtitleResources
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

/** Actual broker and renderer, with a controllable connection check at the commit boundary. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class TvRemoteSubtitleSelectionTest {
    @Test fun remoteLocalContinuationRejectsChangedPathDirectory() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val storage = TvLocalSubtitleStorage(app)
        val root = withContext(Dispatchers.IO) { requireNotNull(app.getExternalFilesDir(null)).canonicalFile }
        val temporary = withContext(Dispatchers.IO) { Files.createTempDirectory(root.toPath(), "fileway-cursor-test-").toFile() }
        try {
            withContext(Dispatchers.IO) {
                repeat(33) { index -> File(temporary, "$index.srt").writeText("fixture") }
            }
            val node = TvLocalSubtitleStorage.Node(temporary.name, true, TvLocalSubtitleStorage.Source.Path(root, temporary, shared = false))
            val first = storage.remotePage("fixture", node, 0, null)
            val next = requireNotNull(first.next)
            assertEquals(32, first.rows.size)
            assertEquals(1, storage.remotePage("fixture", node, next, first.anchors[next]).rows.size)
            withContext(Dispatchers.IO) {
                check(temporary.setLastModified(temporary.lastModified() - 60_000))
            }
            val changed = try {
                storage.remotePage("fixture", node, next, first.anchors[next])
                null
            } catch (failure: TvLocalSubtitleFailure) { failure.issue }
            assertEquals(TvLocalSubtitleIssue.DIRECTORY_CHANGED, changed)
        } finally {
            withContext(Dispatchers.IO) { temporary.deleteRecursively() }
        }
    }

    @Test fun cancelledOrExpiredPreparationKeepsTheSelectedTrack() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val url = requireNotNull(args.getString("fixture_url"))
        val username = requireNotNull(args.getString("fixture_username"))
        val password = requireNotNull(args.getString("fixture_password"))
        check(username == "tv-fixture")
        withContext(Dispatchers.IO) {
            val database = TvDatabase.get(instrumentation.targetContext)
            check(database.profiles().profiles().first().all { it.address.trimEnd('/') == url.trimEnd('/') })
            database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM accounts WHERE username <> ?", arrayOf(username)).use {
                check(it.moveToFirst() && it.getLong(0) == 0L)
            }
        }

        ActivityScenario.launch(TvActivity::class.java).use { scenario ->
            lateinit var tv: TvModel
            scenario.onActivity { tv = ViewModelProvider(it)[TvModel::class.java] }
            waitFor { !tv.state.value.restoring }
            if (tv.state.value.connected) {
                val session = requireNotNull(tv.context)
                check(session.profile.address.trimEnd('/') == url.trimEnd('/') && session.account.username == username)
            } else {
                scenario.onActivity { tv.login(url, username, password) }
                waitFor { tv.state.value.connected }
            }
            val media = tv.media
            val oldPolicy = media.playbackPreferences.videoDecodePolicy.value
            try {
                scenario.onActivity {
                    check(media.state.value.file == null)
                    media.setVideoDecodePolicy(VideoDecodePolicy.SOFTWARE)
                    tv.navigate(TvRoute.FILES)
                    tv.browse("/Cinema", "/Cinema", false)
                }
                waitFor { !tv.state.value.busy && tv.state.value.path == "/Cinema" && media.playbackPreferences.videoDecodePolicy.value == VideoDecodePolicy.SOFTWARE }
                val movie = tv.state.value.files.single { it.name == "Fileway owned 150s +%# fixture.mp4" }
                scenario.onActivity { tv.open(movie, listOf(movie)) }
                waitFor { media.player.state.value.firstFrameRendered && media.state.value.sidecars.any { it.name == "Fileway owned timeline.srt" } }
                val file = media.state.value.sidecars.single { it.name == "Fileway owned timeline.srt" }
                assertTrue(media.state.value.scopedSubtitlePages)
                val resources = withContext(Dispatchers.Main.immediate) { ScopedSubtitleResources(requireNotNull(tv.context).api) }
                val scoped = withContext(Dispatchers.IO) {
                    val entry = resources.page("/Cinema").entries.single { it.file.name == file.name }
                    resources.verify(entry)
                    resources.close("/Cinema")
                    entry
                }
                scenario.onActivity { media.player.pause(); media.selectExternalSubtitle(file) }
                waitFor { !media.state.value.subtitleLoading && media.player.importedSubtitleIdentity(media.player.state.value.selectedSubtitle)
                    ?.sourceKey == "external:${file.mediaKey}" }
                val previous = media.player.state.value.selectedSubtitle
                val epoch = media.controlEpoch
                val generation = media.player.state.value.mediaGeneration

                fun checkUnchanged() {
                    assertEquals(previous, media.player.state.value.selectedSubtitle)
                    assertEquals(generation, media.player.state.value.mediaGeneration)
                    assertEquals(epoch, media.controlEpoch)
                    assertFalse(media.player.wantsPlay)
                    assertTrue(media.player.state.value.subtitles.none {
                        media.player.importedSubtitleIdentity(it.id)?.sourceKey?.startsWith("external:remote:") == true
                    })
                }

                suspend fun prepare(commit: CompletableDeferred<Boolean>, ready: CompletableDeferred<Unit>): TvRemoteSubtitleOperation =
                    withContext(Dispatchers.Main.immediate) {
                        var checks = 0
                        requireNotNull(media.prepareRemoteScopedSubtitle(scoped, epoch) {
                            if (++checks == 1) true else { ready.complete(Unit); commit.await() }
                        })
                    }

                val allow = CompletableDeferred<Boolean>()
                val prepared = CompletableDeferred<Unit>()
                val expired = prepare(allow, prepared)
                withTimeout(20_000) { prepared.await() }
                withContext(Dispatchers.Main.immediate) {
                    assertEquals(previous, media.player.state.value.selectedSubtitle)
                    val candidate = media.player.state.value.subtitles.single {
                        media.player.importedSubtitleIdentity(it.id)?.sourceKey?.startsWith("external:remote:") == true
                    }
                    assertFalse(media.subtitleTrackPublished(candidate.id))
                }
                allow.complete(false)
                assertEquals(TvRemoteSubtitleResult.STALE, withTimeout(20_000) { expired.result.await() })
                withContext(Dispatchers.Main.immediate) { checkUnchanged() }

                val cancelledPrepared = CompletableDeferred<Unit>()
                val cancelled = prepare(CompletableDeferred(), cancelledPrepared)
                withTimeout(20_000) { cancelledPrepared.await() }
                scenario.onActivity { cancelled.cancel() }
                assertEquals(TvRemoteSubtitleResult.CANCELLED, withTimeout(20_000) { cancelled.result.await() })
                withContext(Dispatchers.Main.immediate) { checkUnchanged() }

                val supersededPrepared = CompletableDeferred<Unit>()
                val superseded = prepare(CompletableDeferred(), supersededPrepared)
                withTimeout(20_000) { supersededPrepared.await() }
                scenario.onActivity {
                    val oldRevision = requireNotNull(media.localSubtitleFence()).selection
                    media.selectPlayerSubtitle(previous)
                    assertNull(media.prepareRemoteScopedSubtitle(scoped, epoch, expectedSelection = oldRevision) {
                        error("An obsolete click must not invoke authorization")
                    })
                }
                assertEquals(TvRemoteSubtitleResult.STALE, withTimeout(20_000) { superseded.result.await() })
                withContext(Dispatchers.Main.immediate) { checkUnchanged() }

                val allowed = withContext(Dispatchers.Main.immediate) {
                    requireNotNull(media.prepareRemoteScopedSubtitle(scoped, epoch) { true })
                }
                assertEquals(TvRemoteSubtitleResult.APPLIED, withTimeout(20_000) { allowed.result.await() })
                withContext(Dispatchers.Main.immediate) {
                    val selected = media.player.state.value.selectedSubtitle
                    assertTrue(media.player.importedSubtitleIdentity(selected)
                        ?.sourceKey?.startsWith("external:remote:") == true)
                    assertTrue(media.subtitleTrackPublished(selected))
                    assertEquals(generation, media.player.state.value.mediaGeneration)
                    assertEquals(epoch, media.controlEpoch)
                    assertFalse(media.player.wantsPlay)
                }
                checkLocalImport(tv, instrumentation.targetContext.getExternalFilesDir(null), epoch, generation)
            } finally {
                withContext(Dispatchers.Main.immediate) { media.closeAndSave(); media.setVideoDecodePolicy(oldPolicy) }
                waitFor { media.playbackPreferences.videoDecodePolicy.value == oldPolicy }
            }
        }
    }

    private suspend fun checkLocalImport(tv: TvModel, directory: File?, epoch: Long, generation: Long) {
        val media = tv.media
        val account = withContext(Dispatchers.Main.immediate) { requireNotNull(tv.context).account.key }
        val root = media.localSubtitles.remoteRoots(account).first { it.id == "app/0" }
        val subtitle = withContext(Dispatchers.IO) {
            File.createTempFile("fileway-remote-subtitle-test-", ".srt", requireNotNull(directory)).apply {
                writeText("1\n00:00:00,000 --> 00:00:15,000\nFileway TV local fixture\n\n")
            }
        }
        try {
            val node = withTimeout(5_000) {
                var offset = 0
                while (true) {
                    val page = media.localSubtitles.page(account, root, offset)
                    page.rows.firstOrNull { it.label == subtitle.name }?.let { return@withTimeout it }
                    offset = requireNotNull(page.next) { "Could not find isolated local subtitle fixture" }
                }
                error("Unreachable")
            }
            val previous = media.player.state.value.selectedSubtitle
            val prepared = CompletableDeferred<Unit>()
            val allow = CompletableDeferred<Boolean>()
            val expired = withContext(Dispatchers.Main.immediate) {
                var checks = 0
                requireNotNull(media.prepareRemoteLocalSubtitle(node, epoch) {
                    if (++checks == 1) true else { prepared.complete(Unit); allow.await() }
                })
            }
            withTimeout(20_000) { prepared.await() }
            withContext(Dispatchers.Main.immediate) {
                assertEquals(previous, media.player.state.value.selectedSubtitle)
                val candidate = media.player.state.value.subtitles.single { it.title == subtitle.name }
                assertFalse(media.subtitleTrackPublished(candidate.id))
            }
            allow.complete(false)
            assertEquals(TvRemoteSubtitleResult.STALE, withTimeout(20_000) { expired.result.await() })
            withContext(Dispatchers.Main.immediate) {
                assertEquals(previous, media.player.state.value.selectedSubtitle)
                assertTrue(media.player.state.value.subtitles.none { it.title == subtitle.name })
                assertFalse(media.player.wantsPlay)
                media.player.toggle()
            }
            waitFor { media.player.state.value.playing }
            val position = media.sourcePosition()
            val accepted = withContext(Dispatchers.Main.immediate) { requireNotNull(media.prepareRemoteLocalSubtitle(node, epoch) { true }) }
            assertEquals(TvRemoteSubtitleResult.APPLIED, withTimeout(20_000) { accepted.result.await() })
            waitFor { media.sourcePosition() > position }
            withContext(Dispatchers.Main.immediate) {
                val selected = media.player.state.value.selectedSubtitle
                assertTrue(media.player.state.value.subtitles.any { it.id == selected && it.title == subtitle.name })
                assertTrue(media.subtitleTrackPublished(selected))
                assertEquals(generation, media.player.state.value.mediaGeneration)
                assertEquals(epoch, media.controlEpoch)
                assertTrue(media.player.wantsPlay)
            }
        } finally {
            withContext(Dispatchers.IO) { subtitle.delete() }
        }
    }

    private suspend fun waitFor(condition: () -> Boolean) = withTimeout(30_000) {
        while (!withContext(Dispatchers.Main.immediate) { condition() }) delay(50)
    }
}
