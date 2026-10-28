package io.github.kkwans.fileway.tv

import android.app.Application
import android.os.SystemClock
import io.github.kkwans.nasfilebrowser.app.*
import io.github.kkwans.nasfilebrowser.core.NativeTransport
import io.github.kkwans.nasfilebrowser.data.*
import io.github.kkwans.nasfilebrowser.player.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder

enum class TvHdrMode(val title: String) { AUTO("自动"), NATIVE("原生 HDR"), SDR("SDR 兼容") }
data class TvSourceTrack(val index: Int, val codec: String, val title: String, val language: String, val preferred: Boolean)
data class TvMediaState(
    val file: ResourceRef? = null, val queue: MediaQueue? = null, val info: JSONObject? = null,
    val busy: Boolean = false, val error: String? = null, val notice: String? = null,
    val mode: TvHdrMode = TvHdrMode.AUTO, val compatible: Boolean = false,
    val sourceOffsetMs: Long = 0, val sourceDurationMs: Long = 0, val imageUrl: String? = null,
    val sourceSubtitles: Boolean = false, val selectedSourceSubtitle: Int = -1, val sourceAudioOff: Boolean = false, val selectedSourceAudio: Int? = null,
    val sourceAudio: List<TvSourceTrack> = emptyList(), val sourceText: List<TvSourceTrack> = emptyList(),
    val sidecars: List<ResourceRef> = emptyList(),
    val sdrAvailable: Boolean = false,
)

/** Owns media resources independently of the TV's composition and focus tree. */
class TvMediaController(
    private val application: Application, private val scope: CoroutineScope, private val history: PlaybackHistory,
    private val current: () -> SessionContext?,
) {
    internal val cache=TvVideoCache(application,scope)
    val player = NativePlayer(application, MediaResourcePolicy.LowMemoryTv)
    val playbackPreferences = PlaybackPreferences(application, VideoDecodePolicy.HARDWARE)
    private val prefs = application.getSharedPreferences("tv-playback", 0)
    private val mutable = MutableStateFlow(TvMediaState(mode = TvHdrMode.entries.firstOrNull { it.name == prefs.getString("hdr_mode", "AUTO") } ?: TvHdrMode.AUTO))
    val state = mutable.asStateFlow()
    private var epoch = 0L
    private var sequence = 0L
    private var operation: Job? = null
    private var subtitleJob: Job? = null
    private var watcher: Job? = null
    private var prefetch: Deferred<Window>? = null
    private val lock = Mutex()
    private val leases = mutableListOf<PreviewLease>()
    private var playbackLease: PreviewLease? = null
    private data class Binding(val owner: SessionContext, val file: ResourceRef, val identity: String, var durationMs: Long, val writer: PlaybackWriter)
    private var binding: Binding? = null
    private data class Window(val lease: PreviewLease, val startMs: Long, val durationMs: Long,val id:String)
    private var window: Window? = null
    private var external: Pair<ResourceRef, String>? = null
    private val nativeFontKeys = mutableSetOf<String>()
    private var fontGeneration = -1L
    private var fontBytes = 0L
    private var sourceSubtitle: Int = -1
    private var userSelectedSubtitle = false
    private var lastSaved: Pair<Long, Long>? = null
    private var lastSaveAt = 0L
    private var autoFallback = false
    private var switchInProgress = false
    private var presentationRevision = 0L
    private var pendingNativeAudio: Int? = null

    fun open(file: ResourceRef, candidates: List<ResourceRef>) {
        val owner = current() ?: return
        val queue = MediaQueue.snapshot(++sequence, owner.owner, file, candidates, MediaQueueSource.DIRECTORY)
        val mode = mutable.value.mode
        val expected = ++epoch
        operation?.cancel(); subtitleJob?.cancel(); watcher?.cancel(); prefetch?.cancel()
        mutable.value = TvMediaState(file = file, queue = queue, mode = mode, busy = true)
        operation = scope.launch {
            lock.withLock {
                var openingStage = "准备媒体"
                try {
                    disposeBinding()
                    if (!accepts(owner, expected)) return@withLock
                    if (file.mediaKind() == MediaKind.IMAGE) {
                        val lease = owner.api.image(file.path, file.wirePath, ImageQuality.MEDIUM)
                        if (!accepts(owner, expected)) { lease.release(); return@withLock }
                        leases += lease
                        mutable.update { it.copy(imageUrl = lease.url, busy = false) }
                        return@withLock
                    }
                    val query = sourceQuery(file)
                    openingStage = "读取媒体信息"
                    val info = owner.api.request("GET", "/api/media/info?$query")
                    openingStage = "读取续播进度"
                    val remote = history.remote(owner.api, file.path, file.wirePath)
                    val local = history.local(owner.account, file.mediaKey, remote.identity)
                    val start = PlaybackHistory.resume(local, remote)
                    openingStage = "读取服务端能力"
                    val capabilities = try { owner.api.request("GET", "/api/client-capabilities") }
                        catch (error: ServiceException) { if (error.status == 404) JSONObject() else throw error }
                    if (!accepts(owner, expected)) return@withLock
                    val writer = PlaybackWriter(history, owner.api) { snapshot, failure ->
                        scope.launch {
                            if (accepts(owner, expected)) {
                                if (failure != null) mutable.update { it.copy(notice = if (snapshot == null) "续播未能保存到本机，请检查存储后重试" else "续播已保存本机，暂未同步") }
                                else if (snapshot?.sync == ProgressSync.SYNCED && lastSaved == (snapshot.positionMs to snapshot.durationMs))
                                    mutable.update { it.copy(notice = it.notice.takeUnless { message -> message == "续播已保存本机，暂未同步" || message == "续播未能保存到本机，请检查存储后重试" }) }
                            }
                        }
                    }
                    binding = Binding(owner, file, remote.identity, (info.optDouble("duration", remote.durationMs / 1000.0) * 1000).toLong(), writer)
                    sourceSubtitle = -1; userSelectedSubtitle = false; external = null; lastSaved = null; autoFallback = false
                    val audio = tracks(info, "audioTracks"); val text = tracks(info, "subtitleTracks")
                    val sourceTextAvailable = capabilities.optBoolean("sourceSubtitles")
                    openingStage = "准备播放资源"
                    mutable.update { it.copy(info = info, sourceAudio = audio, sourceText = text, sourceSubtitles = sourceTextAvailable, sdrAvailable = capabilities.optBoolean("sdrCompatibility"),
                        sourceDurationMs = (info.optDouble("duration", remote.durationMs / 1000.0) * 1000).toLong(),
                        selectedSourceAudio = audio.firstOrNull { track -> track.preferred }?.index ?: audio.firstOrNull()?.index) }
                    val sdrAvailable = capabilities.optBoolean("sdrCompatibility")
                    check(mode != TvHdrMode.SDR || sdrAvailable) { "当前服务端不支持 SDR 兼容，请选择原生播放" }
                    val wantsAutoSdr = mode == TvHdrMode.AUTO && definitelyUnsupportedHdr(info)
                    val useSdr = mode == TvHdrMode.SDR || wantsAutoSdr && sdrAvailable
                    if (wantsAutoSdr && !sdrAvailable) mutable.update { it.copy(notice = "服务端暂不支持 SDR 兼容，当前使用原生播放") }
                    val lease = if (useSdr && file.mediaKind() == MediaKind.VIDEO) {
                        check(capabilities.optBoolean("sdrCompatibility")) { "服务器尚未提供 SDR 兼容播放，请升级服务端" }
                        val prepared = prepareWindow(owner, file, remote.identity, start, expected)
                        window = prepared
                        prepared.lease
                    } else originalLease(owner, file)
                    if (!accepts(owner, expected)) { lease.release(); return@withLock }
                    playbackLease = lease
                    val dataSource=videoDataSource(lease,window)
                    player.open(lease.url, if (window == null) start else (start - requireNotNull(window).startMs).coerceAtLeast(0), videoDecodePolicy = playbackPreferences.videoDecodePolicy.value,dataSourceFactory=dataSource)
                    player.subtitleSourceOffset(window?.startMs ?: 0)
                    player.textSubtitleAppearance(playbackPreferences.textSubtitleAppearance.value)
                    if (sourceTextAvailable) player.subtitle(-1)
                    mutable.update { it.copy(busy = false, compatible = window != null, sourceOffsetMs = window?.startMs ?: 0) }
                    startWatcher(owner, expected)
                    if (sourceTextAvailable) {
                        val preferred = text.firstOrNull { track -> track.language.lowercase() in setOf("zh", "zho", "chi", "zh-cn", "zh-tw") }
                            ?: text.firstOrNull { track -> track.preferred }
                        preferred?.let { selectSourceSubtitle(it.index) }
                    }
                    loadSidecars(owner, file, expected)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    if (accepts(owner, expected)) mutable.update { it.copy(busy = false, error = "$openingStage 失败：${error.message ?: "请重试"}") }
                }
            }
        }
    }

    private suspend fun originalLease(owner: SessionContext, file: ResourceRef): PreviewLease {
        val url = owner.api.lease(file.path, file.wirePath)
        return PreviewLease(url, owner.api.id) { NativeTransport.call(JSONObject().put("op", "revoke").put("url", url)); Unit }
    }
    private fun accepts(owner: SessionContext, expected: Long) = current() === owner && epoch == expected
    private fun sourceQuery(file: ResourceRef): String {
        val wire = file.wirePath.ifEmpty { SearchResult.encodePath(file.path) }
        // Old servers require path; new servers prefer the original wirePath.
        // Both names are supplied without lossy decoding or double escaping.
        return "path=${wireQueryPath(file.path, wire)}&wirePath=${encode(wire)}"
    }
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
    private fun definitelyUnsupportedHdr(info: JSONObject): Boolean {
        val range = info.optString("dynamicRange", if (info.optBoolean("hdr")) "pq" else "unknown")
        if (range !in setOf("pq", "hlg", "dolby-vision")) return false
        val display = application.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(android.view.Display.DEFAULT_DISPLAY) ?: return false
        val types = display.hdrCapabilities?.supportedHdrTypes ?: return false
        val wanted = when (range) { "hlg" -> android.view.Display.HdrCapabilities.HDR_TYPE_HLG; "dolby-vision" -> android.view.Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION; else -> android.view.Display.HdrCapabilities.HDR_TYPE_HDR10 }
        return wanted !in types
    }
    private fun tracks(info: JSONObject, key: String): List<TvSourceTrack> = info.optJSONArray(key)?.let { rows -> (0 until rows.length()).map { i ->
        val row = rows.getJSONObject(i); TvSourceTrack(row.getInt("index"), row.getString("codec"), row.optString("title").ifBlank { row.optString("language").ifBlank { "轨道 ${i+1}" } }, row.optString("language"), row.optBoolean("default"))
    } }.orEmpty()

    private suspend fun prepareWindow(owner: SessionContext, file: ResourceRef, identity: String, startMs: Long, expected: Long): Window {
        val request = JSONObject().put("path", file.path).put("wirePath", file.wirePath).put("quality", "1080p")
            .put("format", "hls").put("startSeconds", startMs / 1000.0).put("windowSeconds", 120)
            .put("sessionId", "tv-${owner.owner}-$expected")
        mutable.value.selectedSourceAudio?.let { request.put("audioStreamIndex", it) }
        var response = owner.api.action("POST", "/api/media/hls", request)
        val id = response.getString("id")
        withTimeout(120_000) {
            while (response.getString("state") !in setOf("streamable", "completed")) {
                check(response.getString("state") !in setOf("failed", "canceled")) { "SDR 兼容准备失败：${response.optString("error")}" }
                check(accepts(owner, expected)) { "播放来源已切换" }
                delay(500)
                response = owner.api.request("GET", "/api/media/hls/$id")
            }
        }
        check(response.getString("identity") == identity && response.optString("outputDynamicRange") == "sdr" && (mutable.value.info?.optBoolean("hdr") != true || response.optBoolean("hdrToneMapped"))) { "兼容流的源版本或动态范围无法确认" }
        val lease = owner.api.hlsLease(id)
        leases += lease
        return Window(lease, (response.optDouble("startSeconds", startMs / 1000.0) * 1000).toLong(), (response.getDouble("durationSeconds") * 1000).toLong(),id)
    }

    private fun startWatcher(owner: SessionContext, expected: Long) {
        watcher?.cancel()
        watcher = scope.launch {
            player.state.collect { value ->
                if (!accepts(owner, expected) || value.mediaGeneration != player.state.value.mediaGeneration) return@collect
                binding?.takeIf { it.durationMs <= 0 && window == null && value.durationMs > 0 }?.let { source ->
                    source.durationMs = value.durationMs
                    mutable.update { it.copy(sourceDurationMs = value.durationMs) }
                }
                if (value.canSavePosition && SystemClock.elapsedRealtime() - lastSaveAt >= 10_000) saveProgress()
                if (window == null && !switchInProgress && pendingNativeAudio == null && value.pendingAudio == null && (value.firstFrameRendered || value.playing) && value.audio.count { it.id >= 0 } == mutable.value.sourceAudio.size) {
                    val native = value.audio.filter { it.id >= 0 }
                    val ordinal = native.indexOfFirst { it.id == value.selectedAudio }
                    val selected = mutable.value.sourceAudio.getOrNull(ordinal)?.index
                    if (selected != mutable.value.selectedSourceAudio || mutable.value.sourceAudioOff != (value.selectedAudio == -1))
                        mutable.update { it.copy(selectedSourceAudio = selected, sourceAudioOff = value.selectedAudio == -1) }
                }
                if (window == null && pendingNativeAudio != null && value.audio.count { it.id >= 0 } == mutable.value.sourceAudio.size) {
                    val ordinal = mutable.value.sourceAudio.indexOfFirst { it.index == pendingNativeAudio }
                    pendingNativeAudio = null
                    value.audio.filter { it.id >= 0 }.getOrNull(ordinal)?.let { player.audio(it.id) }
                }
                if (window == null && pendingNativeAudio != null && value.firstFrameRendered && value.audio.count { it.id >= 0 } != mutable.value.sourceAudio.size) {
                    pendingNativeAudio = null
                    player.audio(-1)
                    mutable.update { it.copy(sourceAudioOff = true, notice = "原音轨无法确认，已关闭音轨，请重新选择") }
                }
                val currentWindow = window
                if (!switchInProgress && currentWindow != null && currentWindow.startMs + currentWindow.durationMs < mutable.value.sourceDurationMs - 1000) {
                    if (value.positionMs >= currentWindow.durationMs - 20_000 && prefetch == null) {
                        val source = binding ?: return@collect
                        prefetch = scope.async { prepareWindow(owner, source.file, source.identity, currentWindow.startMs + currentWindow.durationMs, expected) }
                    }
                    if (value.ended) advanceWindow(owner, expected)
                }
                if (value.error != null && mutable.value.sdrAvailable && mutable.value.mode == TvHdrMode.AUTO && !mutable.value.compatible && !autoFallback && mutable.value.file?.mediaKind() == MediaKind.VIDEO) {
                    autoFallback = true
                    mutable.update { it.copy(notice = "原生播放失败，正在尝试 SDR 兼容") }
                    switchMode(TvHdrMode.AUTO, forceSdr = true)
                }
            }
        }
    }

    private fun advanceWindow(owner: SessionContext, expected: Long) {
        if (switchInProgress) return
        switchInProgress = true
        val transition = ++presentationRevision
        mutable.update { it.copy(busy = true) }
        operation = scope.launch {
            try {
                val old = requireNotNull(window)
                val source = requireNotNull(binding)
                val next = prefetch?.await() ?: prepareWindow(owner, source.file, source.identity, old.startMs + old.durationMs, expected)
                if (!accepts(owner, expected) || transition != presentationRevision) { next.lease.release(); return@launch }
                val rate = player.state.value.rate; val delay = player.state.value.subtitleDelayMs
                player.open(next.lease.url, videoDecodePolicy = playbackPreferences.videoDecodePolicy.value,dataSourceFactory=videoDataSource(next.lease,next))
                player.subtitleSourceOffset(next.startMs); player.rate(rate); player.subtitleDelay(delay)
                player.textSubtitleAppearance(playbackPreferences.textSubtitleAppearance.value)
                if (mutable.value.sourceAudioOff) player.audio(-1)
                window = next; playbackLease = next.lease; prefetch = null
                mutable.update { it.copy(sourceOffsetMs = next.startMs, busy = false) }
                old.lease.release(); restoreSubtitleSafely()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.update { it.copy(busy = false, error = "下一段兼容播放准备失败，可重试或切回原生播放") }
            } finally { if (transition == presentationRevision) switchInProgress = false }
        }
    }

    fun switchMode(mode: TvHdrMode, forceSdr: Boolean = false, targetMs: Long? = null) {
        val source = binding
        if (source == null) {
            val previous = mutable.value
            val file = previous.file ?: return
            mutable.update { it.copy(mode = mode) }
            open(file, previous.queue?.items ?: listOf(file))
            return
        }
        val expected = epoch
        val previous = mutable.value
        val native = player.decoderRestoreSnapshot()
        val position = targetMs ?: sourcePosition()
        val wantsPlay = native?.wantsPlay ?: true
        val useSdr = forceSdr || mode == TvHdrMode.SDR || mode == TvHdrMode.AUTO && previous.sdrAvailable && previous.info?.let(::definitelyUnsupportedHdr) == true
        if ((forceSdr || mode == TvHdrMode.SDR) && !previous.sdrAvailable) {
            mutable.update { it.copy(notice = "当前服务端不支持 SDR 兼容，请选择原生播放") }
            return
        }
        operation?.cancel(); prefetch?.cancel(); prefetch = null
        val oldLease = playbackLease
        val oldWindow = window
        val transition = ++presentationRevision
        switchInProgress = true
        mutable.update { it.copy(busy = true, error = null) }
        operation = scope.launch {
            var candidate: PreviewLease? = null
            var openAttempted = false
            var committed = false
            try {
                val prepared = if (useSdr) prepareWindow(source.owner, source.file, source.identity, position, expected) else null
                candidate = prepared?.lease ?: originalLease(source.owner, source.file)
                if (!accepts(source.owner, expected) || transition != presentationRevision) { candidate.release(); return@launch }
                val carry = player.decoderRestoreSnapshot() ?: native
                val carryPosition = targetMs ?: sourcePosition()
                val localPosition = prepared?.let { SourceTimeline(source.durationMs, it.startMs, it.durationMs).toLocal(carryPosition)
                    ?: error("准备期间播放位置已移出片段，请重试") } ?: carryPosition
                val autoplay = player.wantsPlay
                saveProgress()
                val dataSource=videoDataSource(candidate,prepared)
                openAttempted = true
                if (!useSdr && carry != null && !previous.compatible) player.openRestoring(candidate.url, carry.copy(positionMs = carryPosition), autoplay, playbackPreferences.videoDecodePolicy.value,dataSource)
                else player.open(candidate.url, localPosition, autoplay, playbackPreferences.videoDecodePolicy.value,dataSource)
                player.subtitleSourceOffset(prepared?.startMs ?: 0)
                if (carry != null) { player.rate(carry.baseRate); player.volume(carry.volume); player.subtitleDelay(carry.subtitleDelayMs) }
                player.textSubtitleAppearance(playbackPreferences.textSubtitleAppearance.value)
                if (previous.sourceAudioOff) player.audio(-1)
                window = prepared; playbackLease = candidate; candidate = null
                committed = true
                if (!useSdr && previous.compatible) pendingNativeAudio = previous.selectedSourceAudio
                mutable.update { it.copy(mode = mode, compatible = useSdr, sourceOffsetMs = prepared?.startMs ?: 0, busy = false) }
                prefs.edit().putString("hdr_mode", mode.name).apply()
                oldLease?.release(); restoreSubtitleSafely()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (accepts(source.owner, expected) && transition == presentationRevision) {
                    if (committed) mutable.update { it.copy(busy = false, notice = "新播放路径已打开，旧资源清理暂未完成") }
                    else if (openAttempted && oldLease != null && native != null) {
                        try {
                            player.openRestoring(oldLease.url, native, native.wantsPlay, playbackPreferences.videoDecodePolicy.value,videoDataSource(oldLease,oldWindow))
                            player.subtitleSourceOffset(oldWindow?.startMs ?: 0)
                            mutable.value = previous.copy(busy = false, notice = "切换失败，已恢复原播放路径")
                            restoreSubtitleSafely()
                        } catch (restoreError: Exception) {
                            if (restoreError is CancellationException) throw restoreError
                            mutable.value = previous.copy(busy = false, error = "切换和恢复均未完成，请重试播放")
                        }
                    } else mutable.value = previous.copy(busy = false, error = "切换未完成，已保留原播放路径：${error.message}")
                }
            } finally { candidate?.release(); if (transition == presentationRevision) switchInProgress = false }
        }
    }

    private suspend fun videoDataSource(lease:PreviewLease,window:Window?):androidx.media3.datasource.DataSource.Factory? {
        val source=binding?:return null
        if(source.file.mediaKind()!=MediaKind.VIDEO)return null
        return cache.prepare(source.owner.account.key,source.identity,source.file.wirePath,lease.url,
            mutable.value.info?.optLong("size",source.file.size)?:source.file.size,window?.id,window?.startMs?:0,
            window?.durationMs?:source.durationMs,ready={player.state.value.firstFrameRendered},playbackPosition={player.state.value.positionMs})
    }

    fun sourcePosition(): Long {
        val duration = binding?.durationMs ?: mutable.value.sourceDurationMs
        val active = window
        return SourceTimeline(duration, active?.startMs ?: 0, active?.durationMs ?: duration).toSource(player.state.value.positionMs)
    }
    fun seek(positionMs: Long) {
        val target = positionMs.coerceIn(0, mutable.value.sourceDurationMs.coerceAtLeast(0))
        val activeWindow = window
        val local = SourceTimeline(mutable.value.sourceDurationMs, activeWindow?.startMs ?: 0, activeWindow?.durationMs ?: mutable.value.sourceDurationMs).toLocal(target)
        if (local != null && (activeWindow == null || local <= player.state.value.durationMs)) {player.seek(local);cache.seek(local)}
        else switchMode(mutable.value.mode, forceSdr = true, targetMs = target)
    }
    fun seekBy(deltaMs: Long) = seek(sourcePosition() + deltaMs)
    fun selectAudio(id: Int) {
        player.audio(id)
        mutable.update { it.copy(sourceAudioOff = id == -1) }
        val native = player.state.value.audio.filter { it.id >= 0 }
        val source = mutable.value.sourceAudio
        if (native.size == source.size) {
            val ordinal = native.indexOfFirst { it.id == id }
            mutable.update { it.copy(selectedSourceAudio = source.getOrNull(ordinal)?.index) }
        }
    }
    fun selectSourceAudio(index: Int) {
        mutable.update { it.copy(selectedSourceAudio = index.takeIf { value -> value >= 0 }, sourceAudioOff = index < 0) }
        if (index < 0) player.audio(-1) else if (window != null) switchMode(mutable.value.mode, forceSdr = true)
    }
    fun subtitlesOff() { userSelectedSubtitle = true; sourceSubtitle = -1; external = null; subtitleJob?.cancel(); player.subtitle(-1); mutable.update { it.copy(selectedSourceSubtitle = -1) } }
    fun selectSourceSubtitle(index: Int) {
        userSelectedSubtitle = true; sourceSubtitle = index; external = null
        subtitleJob?.cancel()
        subtitleJob = scope.launch { try { importSourceSubtitle(index) } catch (error: Exception) { if (error is CancellationException) throw error; mutable.update { it.copy(notice = "字幕加载失败，可重试：${error.message}") } } }
    }
    private suspend fun restoreSubtitle() {
        if (sourceSubtitle >= 0) importSourceSubtitle(sourceSubtitle)
        else if (external != null) { val (file, key) = external!!; importExternal(file, key) }
        else if (userSelectedSubtitle) player.subtitle(-1)
    }
    private suspend fun restoreSubtitleSafely() {
        val expected = epoch
        try {
            restoreSubtitle()
            player.finishDecoderRestore(player.state.value.mediaGeneration, emptyList())
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (epoch == expected) {
                mutable.update { it.copy(notice = "播放路径已切换，字幕未能恢复，可重新选择") }
                player.finishDecoderRestore(player.state.value.mediaGeneration, listOf("字幕未能恢复，请重新选择"))
            }
        }
    }
    private suspend fun importSourceSubtitle(index: Int) {
        val source = binding ?: return
        val expected = epoch
        val track = mutable.value.sourceText.firstOrNull { it.index == index } ?: error("字幕轨道已变化")
        val ext = when (track.codec) { "ass", "ssa" -> "ass"; "hdmv_pgs_subtitle" -> "sup"; "subrip" -> "srt"; else -> "vtt" }
        val endpoint = "/api/subtitle${source.file.wirePath}?streamIndex=$index&format=source&identity=${encode(source.identity)}"
        val lease = source.owner.api.assetLease(endpoint)
        if (!accepts(source.owner, expected)) { lease.release(); return }
        try {
            if (ext == "ass") loadFonts(source, expected)
            withTimeout(15_000) { while (!player.canAddExternalSubtitle) delay(100) }
            check(player.importSubtitle(lease.url, "${track.title}.$ext", "source:$index", true)) { "字幕未能完整载入" }
            if (accepts(source.owner, expected)) { leases += lease; mutable.update { it.copy(selectedSourceSubtitle = index) } } else lease.release()
        } catch (error: Exception) { lease.release(); throw error }
    }
    private suspend fun loadFonts(source: Binding, expected: Long) {
        val fonts = mutable.value.info?.optJSONArray("attachments") ?: return
        val generation = player.state.value.mediaGeneration
        if (fontGeneration != generation) { nativeFontKeys.clear(); fontBytes = 0; fontGeneration = generation }
        for (i in 0 until fonts.length()) {
            val font = fonts.getJSONObject(i)
            val fontKey = font.getString("id")
            if (fontKey in nativeFontKeys) continue
            val lease = source.owner.api.assetLease("/api/media/attachments/${font.getString("id")}?${sourceQuery(source.file)}&identity=${encode(source.identity)}")
            try {
                val bytes = withContext(Dispatchers.IO) { URL(lease.url).openConnection().apply { connectTimeout = 15000; readTimeout = 30000 }.getInputStream().use { input -> val result = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(32 * 1024)
                    while (result.size() <= 8 * 1024 * 1024) {
                        val size = input.read(chunk, 0, minOf(chunk.size, 8 * 1024 * 1024 + 1 - result.size()))
                        if (size < 0) break
                        result.write(chunk, 0, size)
                    }
                    result.toByteArray() } }
                if (!accepts(source.owner, expected) || player.state.value.mediaGeneration != generation) return
                val totalBytes=fontBytes+bytes.size
                check(bytes.size <= 8 * 1024 * 1024 && totalBytes <= 32L * 1024 * 1024) { "字幕字体超出内存预算" }
                if (!accepts(source.owner, expected)) return
                val name = font.getString("name")
                require(name.isNotBlank() && name.length <= 256)
                if (player.state.value.mediaGeneration != generation) return
                player.addSubtitleFont(name, bytes)
                fontBytes=totalBytes
                nativeFontKeys += fontKey
            } finally { lease.release() }
        }
    }
    fun selectExternalSubtitle(file: ResourceRef) {
        sourceSubtitle = -1; userSelectedSubtitle = true; external = file to "external:${file.mediaKey}"
        subtitleJob?.cancel(); subtitleJob = scope.launch { try { importExternal(file, external!!.second) } catch (error: Exception) { if (error is CancellationException) throw error; mutable.update { it.copy(notice = "外挂字幕加载失败：${error.message}") } } }
    }
    private suspend fun importExternal(file: ResourceRef, key: String) {
        val source = binding ?: return
        val expected = epoch
        val lease = originalLease(source.owner, file)
        try {
            check(player.importSubtitle(lease.url, file.name, key, true)) { "外挂字幕未能载入" }
            if (accepts(source.owner, expected)) { leases += lease; mutable.update { it.copy(selectedSourceSubtitle = -1) } } else lease.release()
        } catch (error: Exception) { lease.release(); throw error }
    }
    private suspend fun loadSidecars(owner: SessionContext, file: ResourceRef, expected: Long) {
        try {
            val wire = file.wirePath.substringBeforeLast('/').ifEmpty { "/" }
            val files = TvModel.directoryFiles(owner.api.request("GET", "/api/resources$wire")).filter { !it.directory && isExternalSubtitle(it.name) }
            if (accepts(owner, expected)) mutable.update { it.copy(sidecars = files) }
        } catch (error: Exception) { if (error is CancellationException) throw error }
    }
    fun requestOriginalImage() {
        val owner = current() ?: return
        val file = mutable.value.file ?: return
        val expected = epoch
        if (file.mediaKind() != MediaKind.IMAGE) return
        scope.launch {
            try {
                val lease = owner.api.image(file.path, file.wirePath, ImageQuality.HIGH)
                if (!accepts(owner, expected)) { lease.release(); return@launch }
                leases += lease
                mutable.update { it.copy(imageUrl = lease.url) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (accepts(owner, expected)) mutable.update { it.copy(notice = "暂时无法读取原图，已保留预览") }
            }
        }
    }
    fun imageFailed(resourceKey: String) {
        if (mutable.value.file?.mediaKey == resourceKey) mutable.update { it.copy(error = "无法解码这张图片，可重试或返回") }
    }
    fun navigateQueue(delta: Int) { val queue = state.value.queue ?: return; queue.select(queue.index + delta)?.let { open(it.current, it.items) } }
    fun retry() { val value = state.value; value.file?.let { open(it, value.queue?.items ?: listOf(it)) } }
    private fun saveProgress() {
        val source = binding ?: return
        val value = player.state.value
        if (!value.canSavePosition) return
        val position = sourcePosition(); val duration = source.durationMs
        if (lastSaved == (position to duration)) return
        source.writer.submit(PlaybackSnapshot(source.owner.account.key, source.file.mediaKey, source.identity, source.file.path, source.file.wirePath, source.file.name,
            position, duration, System.currentTimeMillis(), ProgressSync.PENDING))
        lastSaved = position to duration; lastSaveAt = SystemClock.elapsedRealtime()
    }
    fun setVideoDecodePolicy(value: VideoDecodePolicy) {
        val source = binding
        scope.launch {
            try {
                playbackPreferences.saveVideoDecodePolicy(value)
                if (source != null && binding === source) switchMode(mutable.value.mode, forceSdr = mutable.value.compatible)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.update { it.copy(notice = "解码设置未能保存，请重试") }
            }
        }
    }
    fun updateSubtitleAppearance(value: TextSubtitleAppearance) {
        scope.launch {
            try {
                playbackPreferences.saveTextSubtitleAppearance(value)
                player.textSubtitleAppearance(value)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.update { it.copy(notice = "字幕设置未能保存，请重试") }
            }
        }
    }
    fun pause() { saveProgress(); player.pause() }
    suspend fun closeAndSave() {
        val expected = ++epoch
        ++presentationRevision
        val oldOperation = operation; val oldSubtitle = subtitleJob; val oldWatcher = watcher; val oldPrefetch = prefetch
        oldOperation?.cancelAndJoin(); oldSubtitle?.cancelAndJoin(); oldWatcher?.cancelAndJoin(); oldPrefetch?.cancel()
        lock.withLock { if (epoch == expected) disposeBinding() }
        if (epoch == expected) mutable.update { TvMediaState(mode = it.mode) }
    }

    fun close() { scope.launch { closeAndSave() } }
    private suspend fun disposeBinding() {
        saveProgress(); binding?.writer?.close(); binding = null; player.stop(); cache.stop();window = null
        playbackLease?.release(); playbackLease = null
        leases.forEach { it.release() }; leases.clear()
    }
    fun release() { ++epoch; operation?.cancel(); subtitleJob?.cancel(); watcher?.cancel(); prefetch?.cancel(); player.release();cache.release(); CoroutineScope(Dispatchers.IO).launch { playbackLease?.release(); leases.forEach { it.release() }; binding?.writer?.close() } }
}
