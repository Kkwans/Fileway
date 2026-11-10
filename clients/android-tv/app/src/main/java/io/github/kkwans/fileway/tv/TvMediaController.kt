package io.github.kkwans.fileway.tv

import android.app.Application
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import io.github.kkwans.nasfilebrowser.app.*
import io.github.kkwans.nasfilebrowser.core.NativeTransport
import io.github.kkwans.nasfilebrowser.data.*
import io.github.kkwans.nasfilebrowser.player.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean

enum class TvHdrMode(val title: String) { AUTO("自动"), NATIVE("原生 HDR"), SDR("SDR 兼容") }
data class TvSourceTrack(val index: Int, val codec: String, val title: String, val language: String, val preferred: Boolean,
    val label: String = "", val forced: Boolean = false)
data class TvMediaState(
    val file: ResourceRef? = null, val queue: MediaQueue? = null, val info: JSONObject? = null,
    val busy: Boolean = false, val error: String? = null, val notice: String? = null,
    val mode: TvHdrMode = TvHdrMode.AUTO, val compatible: Boolean = false,
    val sourceOffsetMs: Long = 0, val sourceDurationMs: Long = 0, val imageUrl: String? = null,
    val sourceSubtitles: Boolean = false, val selectedSourceSubtitle: Int = -1, val sourceAudioOff: Boolean = false, val selectedSourceAudio: Int? = null,
    val sourcePGSStreaming: Boolean = false, val sourceASSStreaming: Boolean = false,
    val sourceAudio: List<TvSourceTrack> = emptyList(), val sourceText: List<TvSourceTrack> = emptyList(),
    val sidecars: List<ResourceRef> = emptyList(),
    val subtitleError: String? = null, val subtitleLoading: Boolean = false,
    val subtitleNotice: String? = null,
    val sdrAvailable: Boolean = false,
    val imageReady:Boolean=false,
    val firstConfirmedPositionMs:Long?=null,
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
    val controlEpoch:Long get()=epoch
    private var sequence = 0L
    private var operation: Job? = null
    private var subtitleJob: Job? = null
    private val subtitleChanges = MutableStateFlow(0L)
    private var subtitleRevision: Long
        get() = subtitleChanges.value
        set(value) { subtitleChanges.value = value }
    private var watcher: Job? = null
    private var prefetch: Deferred<Window>? = null
    private val lock = Mutex()
    private val leases = mutableListOf<PreviewLease>()
    private var playbackLease: PreviewLease? = null
    private data class Binding(val owner: SessionContext, val file: ResourceRef, val identity: String, var durationMs: Long, val writer: PlaybackWriter, val epoch: Long,
        val subtitleAssets: SourceSubtitleAssets = SourceSubtitleAssets())
    private var binding: Binding? = null
    private data class Window(val lease: PreviewLease, val startMs: Long, val durationMs: Long,val id:String)
    private var window: Window? = null
    private sealed interface SubtitleIntent {
        data object Auto : SubtitleIntent
        data object Off : SubtitleIntent
        data class Source(val index: Int) : SubtitleIntent
        data class Native(val identity: DecoderTrackIdentity) : SubtitleIntent
        data class External(val file: ResourceRef, val key: String, val trackKey: String? = null, val version: String? = null) : SubtitleIntent
    }
    private var subtitleIntent: SubtitleIntent = SubtitleIntent.Auto
    private var subtitleFallbackIntent: SubtitleIntent = SubtitleIntent.Auto
    private var subtitleFallbackSource = -1
    private val externalRefs = mutableMapOf<String, ResourceRef>()
    private val nativeFontKeys = mutableSetOf<String>()
    private var fontGeneration = -1L
    private var nativeSubtitleRecovery: Pair<Long, Int>? = null
    private var lastSaved: Pair<Long, Long>? = null
    private var lastSaveAt = 0L
    private var autoFallback = false
    private var switchInProgress = false
    private var requestedPresentation: Pair<TvHdrMode, Boolean>? = null
    private var presentationRevision = 0L
    private var pendingNativeAudio: Int? = null

    fun open(file: ResourceRef, candidates: List<ResourceRef>) = openMedia(file,candidates,null)
    internal fun openHandoff(file:ResourceRef,start:TvMediaStart)=openMedia(file,listOf(file),start)
    private fun openMedia(file: ResourceRef, candidates: List<ResourceRef>, requested:TvMediaStart?) {
        val owner = current() ?: return
        val queue = MediaQueue.snapshot(++sequence, owner.owner, file, candidates, MediaQueueSource.DIRECTORY)
        val mode = mutable.value.mode
        val expected = ++epoch
        pendingNativeAudio=null;switchInProgress=false;requestedPresentation=null
        subtitleIntent = when(requested?.restore?.subtitleMode) {
            DecoderSubtitleMode.OFF -> SubtitleIntent.Off
            DecoderSubtitleMode.INTERNAL -> SubtitleIntent.Native(requireNotNull(requested.restore.subtitle))
            DecoderSubtitleMode.EXTERNAL -> requireNotNull(requested.external).let { asset ->
                val track=requireNotNull(requested.restore.externalSubtitle)
                SubtitleIntent.External(asset,"external:${asset.mediaKey}",track.trackKey,track.sourceVersion)
            }
            else -> SubtitleIntent.Auto
        }
        subtitleFallbackIntent = subtitleIntent; subtitleFallbackSource = -1
        operation?.cancel(); ++subtitleRevision; subtitleJob?.cancel(); subtitleJob = null; watcher?.cancel(); prefetch?.cancel()
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
                    check(requested==null||requested.identity==remote.identity) { "接力文件版本已变化，请在手机重新打开" }
                    val local = if(requested==null)history.local(owner.account, file.mediaKey, remote.identity)else null
                    val start = requested?.restore?.positionMs?:PlaybackHistory.resume(local, remote)
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
                    binding = Binding(owner, file, remote.identity, (info.optDouble("duration", remote.durationMs / 1000.0) * 1000).toLong(), writer, expected)
                    lastSaved = null; autoFallback = false
                    val audio = tracks(info, "audioTracks"); val text = tracks(info, "subtitleTracks")
                    val sourceTextAvailable = capabilities.optBoolean("sourceSubtitles")
                    openingStage = "准备播放资源"
                    mutable.update { it.copy(info = info, sourceAudio = audio, sourceText = text, sourceSubtitles = sourceTextAvailable, sdrAvailable = capabilities.optBoolean("sdrCompatibility"),
                        sourcePGSStreaming = capabilities.optInt("sourcePGSStreaming") >= 1,
                        sourceASSStreaming = capabilities.optInt("sourceASSStreaming") >= 1,
                        sourceDurationMs = (info.optDouble("duration", remote.durationMs / 1000.0) * 1000).toLong(),
                        selectedSourceAudio = audio.firstOrNull { track -> track.preferred }?.index ?: audio.firstOrNull()?.index) }
                    val sdrAvailable = capabilities.optBoolean("sdrCompatibility")
                    val video=file.mediaKind()==MediaKind.VIDEO
                    check(!video || mode != TvHdrMode.SDR || sdrAvailable) { "当前服务端不支持 SDR 兼容，请选择原生播放" }
                    val wantsAutoSdr = video && mode == TvHdrMode.AUTO && definitelyUnsupportedHdr(info)
                    val useSdr = video && (mode == TvHdrMode.SDR || wantsAutoSdr && sdrAvailable)
                    if(useSdr&&requested!=null) {
                        if(requested.restore.audioOff)mutable.update{it.copy(sourceAudioOff=true,selectedSourceAudio=null)}
                        else requested.restore.audio?.let { wanted ->
                            val key=audioHandoffKey(wanted.mime.orEmpty(),wanted.language,wanted.label,wanted.selectionFlags and C.SELECTION_FLAG_DEFAULT!=0)
                            val selected=audio.singleOrNull{audioHandoffKey(it.codec,it.language,it.label,it.preferred)==key}
                                ?:error("接力音轨无法唯一匹配，请先在手机选择可识别的音轨")
                            mutable.update{it.copy(selectedSourceAudio=selected.index)}
                        }
                        val textChoice=subtitleIntent as? SubtitleIntent.Native
                        if(textChoice!=null) {
                            val key=nativeSubtitleKey(textChoice.identity)
                            val source=sourceSubtitleKeys().entries.singleOrNull{it.value==key}
                                ?:error("接力字幕无法唯一匹配，请先在手机重新选择字幕")
                            check(sourceTextAvailable){"服务端不支持兼容流源字幕，请升级服务端"}
                            subtitleIntent=SubtitleIntent.Source(source.key)
                        }
                    }
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
                    if(!accepts(owner,expected)){lease.release();return@withLock}
                    val localStart=if(window==null)start else(start-requireNotNull(window).startMs).coerceAtLeast(0)
                    if(requested==null)player.open(lease.url,localStart,videoDecodePolicy=playbackPreferences.videoDecodePolicy.value,dataSourceFactory=dataSource)
                    else {
                        val carry=subtitleRestore(requested.restore.copy(positionMs=localStart),subtitleIntent)
                        player.openRestoring(lease.url,if(window==null)carry else carry.copy(audio=null,audioOff=mutable.value.sourceAudioOff),
                            autoplay=requested.restore.wantsPlay,videoDecodePolicy=playbackPreferences.videoDecodePolicy.value,dataSourceFactory=dataSource)
                        if(window!=null&&mutable.value.sourceAudioOff)player.audio(-1)
                    }
                    player.subtitleSourceOffset(window?.startMs ?: 0)
                    player.textSubtitleAppearance(playbackPreferences.textSubtitleAppearance.value)
                    if (sourceTextAvailable && window != null) player.subtitle(-1)
                    mutable.update { it.copy(busy = false, compatible = window != null, sourceOffsetMs = window?.startMs ?: 0) }
                    startWatcher(owner, expected)
                    if (subtitleIntent != SubtitleIntent.Auto) selectSubtitleIntent(subtitleIntent)
                    else if (sourceTextAvailable) preferredSourceSubtitle()?.let { selectSourceSubtitle(it.index) }
                    loadSidecars(owner, file, expected)
                } catch (error: Exception) {
                    if (error is CancellationException && error !is TimeoutCancellationException) throw error
                    if (accepts(owner, expected)) mutable.update { it.copy(busy = false, error = "$openingStage 失败：${error.message ?: "请重试"}") }
                }
            }
        }
    }

    private fun audioHandoffKey(codec:String,language:String?,label:String?,preferred:Boolean):TvSubtitleKey {
        val normalized=when(codec.lowercase(java.util.Locale.ROOT)) {
            "audio/mp4a-latm"->"aac";"audio/mpeg"->"mp3";"audio/ac3"->"ac3";"audio/eac3","audio/eac3-joc"->"eac3"
            "audio/vnd.dts","audio/vnd.dts.hd"->"dts";"audio/true-hd"->"truehd";"audio/flac"->"flac";"audio/opus"->"opus";"audio/vorbis"->"vorbis"
            else->codec
        }
        return tvSubtitleKey(normalized,language,label,preferred,false)
    }

    internal fun replaceHandoffQueue(items:List<ResourceRef>,index:Int,expected:Long) {
        check(expected==epoch&&binding?.let{it.owner===current()}!=false) { "接力播放内容已变化" }
        val source=current()?:error("TV 登录已变化")
        check(items.getOrNull(index)?.mediaKey==mutable.value.file?.mediaKey) { "播放列表当前项已变化" }
        val queue=MediaQueue.snapshot(++sequence,source.owner,items[index],items,MediaQueueSource.DIRECTORY)
        check(queue?.items?.size==items.size&&queue.index==index) { "接力播放列表无法完整恢复" }
        mutable.update{it.copy(queue=queue)}
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
        val row = rows.getJSONObject(i); TvSourceTrack(row.getInt("index"), row.getString("codec"), row.optString("title").ifBlank { row.optString("language").ifBlank { "轨道 ${i+1}" } }, row.optString("language"), row.optBoolean("default"), row.optString("title"), row.optBoolean("forced"))
    } }.orEmpty()

    private fun sourceSubtitleKeys() = mutable.value.sourceText.associate { track ->
        track.index to tvSubtitleKey(track.codec, track.language, track.label, track.preferred, track.forced)
    }
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun nativeSubtitleKey(identity: DecoderTrackIdentity) = tvSubtitleKey(
        (if (identity.mime == MimeTypes.APPLICATION_MEDIA3_CUES) identity.codecs else identity.mime).orEmpty(),
        identity.language, identity.label, identity.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0,
        identity.selectionFlags and C.SELECTION_FLAG_FORCED != 0,
    )
    private fun nativeSubtitleKeys() = player.state.value.subtitles.mapNotNull { track ->
        player.nativeSubtitleIdentity(track.id)?.let { track.id to nativeSubtitleKey(it) }
    }.toMap()
    private fun sourceSubtitleMatch(index: Int): TvSubtitleMatch? {
        val source = sourceSubtitleKeys()
        return source[index]?.let { matchUniqueTvSubtitle(it, source, nativeSubtitleKeys()) }
    }
    private fun nativeSubtitleMatch(id: Int): TvSubtitleMatch? {
        val native = nativeSubtitleKeys()
        return native[id]?.let { matchUniqueTvSubtitle(it, sourceSubtitleKeys(), native) }
    }

    private fun preferredSourceSubtitle() = mutable.value.sourceText.firstOrNull {
        it.language.lowercase(java.util.Locale.ROOT) in setOf("zh", "zho", "chi", "zh-cn", "zh-tw")
    } ?: mutable.value.sourceText.firstOrNull { it.preferred }

    private fun subtitleForPath(sdr: Boolean): SubtitleIntent {
        val intent = if (subtitleIntent == SubtitleIntent.Auto && window != null && mutable.value.selectedSourceSubtitle >= 0)
            SubtitleIntent.Source(mutable.value.selectedSourceSubtitle) else subtitleIntent
        if (!sdr || intent == SubtitleIntent.Off || intent is SubtitleIntent.External) return intent
        if (intent is SubtitleIntent.Source) {
            check(mutable.value.sourceSubtitles) { "服务端未提供源字幕，已保留原播放路径" }
            return intent
        }
        val key = when (intent) {
            is SubtitleIntent.Native -> nativeSubtitleKey(intent.identity)
            SubtitleIntent.Auto -> player.nativeSubtitleIdentity(player.state.value.selectedSubtitle)?.let(::nativeSubtitleKey)
            else -> null
        } ?: return intent
        val match = matchUniqueTvSubtitle(key, sourceSubtitleKeys(), nativeSubtitleKeys())
        check(match != null && mutable.value.sourceSubtitles) { "当前字幕无法唯一匹配，已保留原生播放。请先选择源字幕或关闭字幕后重试" }
        return SubtitleIntent.Source(match.sourceIndex)
    }

    private fun subtitleRestore(snapshot: DecoderRestore, intent: SubtitleIntent): DecoderRestore = when (intent) {
        SubtitleIntent.Auto -> snapshot.copy(subtitleMode = DecoderSubtitleMode.AUTO, subtitle = null, externalSubtitle = null)
        is SubtitleIntent.Native -> snapshot.copy(subtitleMode = DecoderSubtitleMode.INTERNAL, subtitle = intent.identity, externalSubtitle = null)
        // The controller restores semantic source/external selections itself;
        // the decoder must not reselect a different old imported subtitle.
        else -> snapshot.copy(subtitleMode = DecoderSubtitleMode.OFF, subtitle = null, externalSubtitle = null)
    }

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
        check(accepts(owner, expected)) { "播放来源已切换" }
        check(response.getString("identity") == identity) { "兼容流的源文件版本已变化，请重新打开视频" }
        check(response.optString("outputDynamicRange") == "sdr") {
            when (response.optString("outputDynamicRange")) {
                "unknown" -> "兼容流的色彩信息不完整，无法确认 SDR 输出，请使用原生播放"
                "" -> "服务端未提供输出色彩信息，请更新服务端或使用原生播放"
                else -> "兼容流未转换为 SDR，请使用原生播放或重试"
            }
        }
        check(mutable.value.info?.optBoolean("hdr") != true || response.optBoolean("hdrToneMapped")) { "服务端没有确认 HDR 色调映射，已保留原播放路径" }
        val lease = owner.api.hlsLease(id)
        if (!accepts(owner, expected)) { lease.release(); error("播放来源已切换") }
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
                if(value.canSavePosition&&mutable.value.firstConfirmedPositionMs==null)
                    mutable.update{it.copy(firstConfirmedPositionMs=sourcePosition())}
                if (value.canSavePosition && SystemClock.elapsedRealtime() - lastSaveAt >= 10_000) saveProgress()
                if (window == null && !switchInProgress && value.nativeSubtitleNeedsSource && subtitleJob?.isActive != true && mutable.value.sourceSubtitles) {
                    val key = value.mediaGeneration to value.selectedSubtitle
                    nativeSubtitleMatch(value.selectedSubtitle)?.let { match ->
                        if (nativeSubtitleRecovery != key) {
                            nativeSubtitleRecovery = key
                            selectSourceSubtitle(match.sourceIndex)
                        }
                    }
                }
                if (window == null && !switchInProgress && subtitleJob?.isActive != true && value.pendingSubtitle == null) {
                    nativeSubtitleMatch(value.selectedSubtitle)?.let { match ->
                        if (mutable.value.selectedSourceSubtitle != match.sourceIndex)
                            mutable.update { it.copy(selectedSourceSubtitle = match.sourceIndex) }
                    }
                    if (value.selectedSubtitle == -1 && mutable.value.selectedSourceSubtitle != -1)
                        mutable.update { it.copy(selectedSourceSubtitle = -1) }
                }
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
                val dataSource = videoDataSource(next.lease, next)
                if (!accepts(owner, expected) || transition != presentationRevision) { next.lease.release(); return@launch }
                val rate = player.state.value.rate; val delay = player.state.value.subtitleDelayMs
                val autoplay=player.playWhenReady;val volume=player.state.value.volume
                ++subtitleRevision; subtitleJob?.cancel(); subtitleJob = null
                player.open(next.lease.url,autoplay=autoplay,videoDecodePolicy = playbackPreferences.videoDecodePolicy.value,dataSourceFactory=dataSource)
                player.volume(volume)
                player.subtitleSourceOffset(next.startMs); player.rate(rate); player.subtitleDelay(delay)
                player.textSubtitleAppearance(playbackPreferences.textSubtitleAppearance.value)
                if (mutable.value.sourceAudioOff) player.audio(-1)
                window = next; playbackLease = next.lease; prefetch = null
                mutable.update { it.copy(sourceOffsetMs = next.startMs, busy = false, selectedSourceSubtitle = -1) }
                old.lease.release(); restoreSubtitleSafely()
            } catch (error: Exception) {
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                if (accepts(owner, expected) && transition == presentationRevision)
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
        if (source.epoch != epoch || current() !== source.owner) {
            mutable.update { it.copy(notice = "当前视频尚未准备好，请稍后重试") }; return
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
        try { subtitleForPath(useSdr) } catch (error: IllegalStateException) {
            mutable.update { it.copy(subtitleError = error.message) }; return
        }
        operation?.cancel(); prefetch?.cancel(); prefetch = null
        val oldLease = playbackLease
        val oldWindow = window
        val transition = ++presentationRevision
        switchInProgress = true
        requestedPresentation = mode to useSdr
        mutable.update { it.copy(busy = true, error = null) }
        operation = scope.launch {
            var candidate: PreviewLease? = null
            var openAttempted = false
            var committed = false
            try {
                val prepared = if (useSdr) prepareWindow(source.owner, source.file, source.identity, position, expected) else null
                candidate = prepared?.lease ?: originalLease(source.owner, source.file)
                if (!accepts(source.owner, expected) || transition != presentationRevision) { candidate.release(); return@launch }
                saveProgress()
                val dataSource=videoDataSource(candidate,prepared)
                if (!accepts(source.owner, expected) || transition != presentationRevision) { candidate.release(); candidate = null; return@launch }
                val carry = player.decoderRestoreSnapshot() ?: native
                val carryPosition = targetMs ?: sourcePosition()
                val localPosition = prepared?.let { SourceTimeline(source.durationMs, it.startMs, it.durationMs).toLocal(carryPosition)
                    ?: error("准备期间播放位置已移出片段，请重试") } ?: carryPosition
                val autoplay = player.wantsPlay
                val nextSubtitle = subtitleForPath(useSdr)
                ++subtitleRevision; subtitleJob?.cancel(); subtitleJob = null
                openAttempted = true
                if (!useSdr && carry != null && !previous.compatible) player.openRestoring(candidate.url, subtitleRestore(carry.copy(positionMs = carryPosition), nextSubtitle), autoplay, playbackPreferences.videoDecodePolicy.value,dataSource)
                else player.open(candidate.url, localPosition, autoplay, playbackPreferences.videoDecodePolicy.value,dataSource)
                player.subtitleSourceOffset(prepared?.startMs ?: 0)
                if (carry != null) { player.rate(carry.baseRate); player.volume(carry.volume); player.subtitleDelay(carry.subtitleDelayMs) }
                player.textSubtitleAppearance(playbackPreferences.textSubtitleAppearance.value)
                if (previous.sourceAudioOff) player.audio(-1)
                window = prepared; playbackLease = candidate; candidate = null
                subtitleIntent = nextSubtitle
                committed = true
                if (!useSdr && previous.compatible) pendingNativeAudio = previous.selectedSourceAudio
                mutable.update { it.copy(mode = mode, compatible = useSdr, sourceOffsetMs = prepared?.startMs ?: 0, busy = false, selectedSourceSubtitle = -1) }
                prefs.edit().putString("hdr_mode", mode.name).apply()
                oldLease?.release(); restoreSubtitleSafely()
            } catch (error: Exception) {
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                if (accepts(source.owner, expected) && transition == presentationRevision) {
                    if (committed) mutable.update { it.copy(busy = false, notice = "新播放路径已打开，旧资源清理暂未完成") }
                    else if (openAttempted && oldLease != null && native != null) {
                        try {
                            player.openRestoring(oldLease.url, subtitleRestore(native, subtitleIntent), native.wantsPlay, playbackPreferences.videoDecodePolicy.value,videoDataSource(oldLease,oldWindow))
                            player.subtitleSourceOffset(oldWindow?.startMs ?: 0)
                            mutable.value = previous.copy(busy = false, notice = "切换失败，已恢复原播放路径")
                            restoreSubtitleSafely()
                        } catch (restoreError: Exception) {
                            if (restoreError is CancellationException && restoreError !is TimeoutCancellationException) throw restoreError
                            mutable.value = previous.copy(busy = false, error = "切换和恢复均未完成，请重试播放")
                        }
                    } else mutable.value = previous.copy(busy = false, error = "切换未完成，已保留原播放路径：${error.message}")
                }
            } finally { candidate?.release(); if (transition == presentationRevision) { switchInProgress = false; requestedPresentation = null } }
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
    val canSeek: Boolean get() = binding?.let { it.epoch == epoch && it.owner === current() } == true &&
        mutable.value.sourceDurationMs > 0 && player.state.value.seekable && player.state.value.durationMs > 0

    fun seek(positionMs: Long) {
        if (!canSeek) { mutable.update { it.copy(notice = "视频尚未准备好，请稍后调整进度") }; return }
        val target = positionMs.coerceIn(0, mutable.value.sourceDurationMs.coerceAtLeast(0))
        if (switchInProgress && mutable.value.busy) {
            val requested = requestedPresentation ?: (mutable.value.mode to mutable.value.compatible)
            switchMode(requested.first, forceSdr = requested.second, targetMs = target)
            return
        }
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
    fun subtitlesOff() = selectSubtitleIntent(SubtitleIntent.Off)
    fun selectPlayerSubtitle(id: Int) {
        if (id == -1) { subtitlesOff(); return }
        if (binding?.epoch != epoch || binding?.owner !== current()) {
            mutable.update { it.copy(subtitleError = "字幕尚未准备好，请稍后重试") }; return
        }
        val native = player.nativeSubtitleIdentity(id)
        if (native != null) { selectSubtitleIntent(SubtitleIntent.Native(native)); return }
        val imported = player.importedSubtitleIdentity(id)
        val source = mutable.value.sourceText.firstOrNull { imported?.sourceKey == "source:${binding?.identity}:${it.index}" }
        when {
            source != null -> selectSubtitleIntent(SubtitleIntent.Source(source.index))
            imported != null && externalRefs[imported.sourceKey] != null -> {
                val file = externalRefs.getValue(imported.sourceKey)
                selectSubtitleIntent(SubtitleIntent.External(file, imported.sourceKey, imported.trackKey, imported.sourceVersion))
            }
            else -> mutable.update { it.copy(subtitleError = "字幕轨道已变化或不受支持，请重新选择") }
        }
    }

    private suspend fun selectSubtitleAndWait(id: Int, expected: Long, selection: Long, generation: Long) {
        val previous = player.state.value.selectedSubtitle
        val owner = binding?.owner
        fun selectionCurrent() = owner != null && current() === owner && binding?.owner === owner &&
            expected == epoch && selection == subtitleRevision && generation == player.state.value.mediaGeneration
        player.subtitle(id)
        try {
            val actual = withTimeout(6_000) {
                player.state.first { it.mediaGeneration != generation || it.error != null ||
                    it.pendingSubtitle == null && (it.selectedSubtitle == id || it.operationError != null) }
            }
            if (!selectionCurrent()) throw CancellationException("Subtitle selection changed")
            check(actual.pendingSubtitle == null && actual.selectedSubtitle == id) { actual.operationError ?: "字幕切换未确认" }
        } catch (error: Exception) {
            if (selectionCurrent() && (previous == -1 || player.state.value.subtitles.any { it.id == previous })) player.subtitle(previous)
            throw error
        }
    }

    fun selectSourceSubtitle(index: Int) = selectSubtitleIntent(SubtitleIntent.Source(index))

    private fun selectSubtitleIntent(intent: SubtitleIntent) {
        if (intent == subtitleIntent && subtitleJob?.isActive == true) return
        val selection = ++subtitleRevision
        val expected = epoch
        val generation = player.state.value.mediaGeneration
        val previous = if (subtitleJob?.isActive == true) subtitleFallbackIntent else subtitleIntent
        val previousSource = if (subtitleJob?.isActive == true) subtitleFallbackSource else mutable.value.selectedSourceSubtitle
        subtitleFallbackIntent = previous; subtitleFallbackSource = previousSource
        subtitleIntent = intent
        mutable.update { it.copy(subtitleError = null, subtitleNotice = null, subtitleLoading = intent != SubtitleIntent.Off) }
        subtitleJob?.cancel(); subtitleJob = null
        if (intent == SubtitleIntent.Off) {
            player.subtitle(-1)
            mutable.update { it.copy(selectedSourceSubtitle = -1, subtitleLoading = false) }
            return
        }
        subtitleJob = scope.launch {
            try {
                restoreSubtitle(intent)
                if (selection == subtitleRevision && expected == epoch) {
                    subtitleFallbackIntent = intent; subtitleFallbackSource = mutable.value.selectedSourceSubtitle
                }
            }
            catch (error: Exception) {
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                if (selection == subtitleRevision && expected == epoch) {
                    subtitleIntent = previous
                    mutable.update { it.copy(selectedSourceSubtitle = previousSource, subtitleError = if (error is TimeoutCancellationException) "字幕加载超时，可重试" else "字幕加载失败，可重试：${error.message}") }
                }
            }
            finally {
                if (selection == subtitleRevision && expected == epoch) {
                    player.finishDecoderRestore(generation, emptyList())
                    mutable.update { it.copy(subtitleLoading = false) }
                }
            }
        }
    }

    private suspend fun restoreSubtitle(intent: SubtitleIntent = subtitleIntent) {
        when (intent) {
            SubtitleIntent.Off -> { player.subtitle(-1); mutable.update { it.copy(selectedSourceSubtitle = -1) } }
            SubtitleIntent.Auto -> if (window != null && mutable.value.sourceSubtitles) {
                preferredSourceSubtitle()?.let { importSourceSubtitle(it.index) }
            }
            is SubtitleIntent.Source -> importSourceSubtitle(intent.index)
            is SubtitleIntent.External -> importExternal(intent)
            is SubtitleIntent.Native -> {
                val generation = player.state.value.mediaGeneration
                val selection = subtitleRevision
                val expected = epoch
                withTimeoutOrNull(4_000) {
                    player.state.first { it.mediaGeneration != generation || it.subtitles.any { row -> row.id >= 0 } || it.firstFrameRendered || it.error != null }
                }
                if (generation != player.state.value.mediaGeneration || selection != subtitleRevision || expected != epoch)
                    throw CancellationException("Subtitle selection changed")
                val matches = player.state.value.subtitles.filter { player.nativeSubtitleIdentity(it.id) == intent.identity }
                check(matches.size == 1) { "原字幕轨道缺失或无法唯一匹配，请重新选择" }
                selectSubtitleAndWait(matches.single().id, expected, selection, generation)
                mutable.update { it.copy(selectedSourceSubtitle = nativeSubtitleMatch(matches.single().id)?.sourceIndex ?: -1, subtitleError = null) }
            }
        }
    }
    private suspend fun restoreSubtitleSafely() {
        val expected = epoch
        val selection = subtitleRevision
        val generation = player.state.value.mediaGeneration
        mutable.update { it.copy(subtitleLoading = subtitleIntent != SubtitleIntent.Off) }
        try {
            restoreSubtitle()
            player.finishDecoderRestore(player.state.value.mediaGeneration, emptyList())
        } catch (error: Exception) {
            if (error is CancellationException && error !is TimeoutCancellationException) throw error
            if (epoch == expected && selection == subtitleRevision && generation == player.state.value.mediaGeneration) {
                mutable.update { it.copy(subtitleError = "播放路径已切换，字幕未能恢复，可重新选择") }
                player.finishDecoderRestore(player.state.value.mediaGeneration, listOf("字幕未能恢复，请重新选择"))
            }
        }
        finally { if (expected == epoch && selection == subtitleRevision) mutable.update { it.copy(subtitleLoading = false) } }
    }

    /** Cancellation revokes the broker lease while a blocking reader is still
     * unwinding. Waiting for the reader before revocation can otherwise make
     * close/switch wait for the network timeout. Completed imports may retain
     * their lease under the controller's ordinary lifetime owner. */
    private suspend fun <T> readAsset(lease: PreviewLease, retain: Boolean = false, read: suspend () -> T): T = coroutineScope {
        val retained = AtomicBoolean(false)
        val selection = subtitleRevision
        val guard = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            try { subtitleChanges.first { it != selection } }
            finally { if (!retained.get()) withContext(NonCancellable) { lease.release() } }
        }
        try { read().also { retained.set(retain) } }
        finally { guard.cancelAndJoin() }
    }
    private suspend fun importSourceSubtitle(index: Int) {
        val source = binding ?: return
        val expected = epoch
        val selection = subtitleRevision
        val generation = player.state.value.mediaGeneration
        fun checkCurrent() {
            if (!accepts(source.owner, expected) || subtitleRevision != selection || player.state.value.mediaGeneration != generation)
                throw CancellationException("Subtitle selection changed")
        }
        if (!accepts(source.owner, expected) || source.epoch != expected) return
        val track = mutable.value.sourceText.firstOrNull { it.index == index } ?: error("字幕轨道已变化")
        val sourceKey = "source:${source.identity}:$index"
        val ext = when (track.codec) { "ass", "ssa" -> "ass"; "hdmv_pgs_subtitle" -> "sup"; "subrip" -> "srt"; else -> "vtt" }
        if (player.selectImportedSubtitle(sourceKey)) {
            if (ext == "ass") loadFonts(source, expected, selection)
            mutable.update { it.copy(selectedSourceSubtitle = index, subtitleError = null) }
            return
        }
        if (window == null) {
            withTimeoutOrNull(4_000) {
                player.state.first { it.mediaGeneration != generation || it.subtitles.any { value -> value.id >= 0 } || it.firstFrameRendered || it.error != null }
            }
            checkCurrent()
            sourceSubtitleMatch(index)?.takeIf { player.nativeSubtitleCovered(it.nativeId) }?.let { match ->
                selectSubtitleAndWait(match.nativeId, expected, selection, generation)
                checkCurrent()
                mutable.update { it.copy(selectedSourceSubtitle = index, subtitleError = null) }
                return
            }
        }
        if (ext == "ass") {
            coroutineScope {
                var prepared: SourceSubtitleAssets.Lease? = null
                val progressive = mutable.value.sourceASSStreaming
                val endpoint = "/api/subtitle${source.file.wirePath}?streamIndex=$index&format=source&identity=${encode(source.identity)}"
                val download = async {
                    (if (progressive) sourceAssStream(source, sourceKey, "$endpoint&stream=1")
                    else sourceAsset(source, sourceKey, endpoint, EXTERNAL_TEXT_BYTES_LIMIT)).also { prepared = it }
                }
                val fonts = async { loadFonts(source, expected, selection) }
                try {
                    val asset = download.await()
                    // Source bytes keep arriving while fonts are prepared. A
                    // preview must not flash with a substitute font first.
                    fonts.await()
                    withTimeout(15_000) { while (!player.canAddExternalSubtitle) delay(100) }
                    checkCurrent()
                    val imported = if (progressive) player.importAssStream(asset, "${track.title}.ass", sourceKey) {
                        checkCurrent()
                        mutable.update { it.copy(selectedSourceSubtitle = index, subtitleError = null) }
                    } else player.importSubtitle(android.net.Uri.fromFile(asset.file).toString(), "${track.title}.ass", sourceKey, true)
                    check(imported) { player.state.value.operationError ?: "字幕未能完整载入" }
                    checkCurrent()
                    subtitleFallbackIntent = subtitleIntent; subtitleFallbackSource = index
                    mutable.update { it.copy(selectedSourceSubtitle = index, subtitleError = null) }
                } finally {
                    withContext(NonCancellable) {
                        download.cancelAndJoin()
                        fonts.cancelAndJoin()
                        withContext(Dispatchers.IO) { prepared?.close() }
                    }
                }
            }
            return
        }
        val progressive = ext == "sup" && mutable.value.sourcePGSStreaming
        val endpoint = "/api/subtitle${source.file.wirePath}?streamIndex=$index&format=source&identity=${encode(source.identity)}" + if (progressive) "&stream=1" else ""
        val lease = source.owner.api.assetLease(endpoint)
        try {
            readAsset(lease, retain = true) {
                checkCurrent()
                withTimeout(15_000) { while (!player.canAddExternalSubtitle) delay(100) }
                checkCurrent()
                val imported = if (progressive) player.importPgsStream(lease.url, "${track.title}.$ext", sourceKey, source.identity) {
                    checkCurrent()
                    mutable.update { it.copy(selectedSourceSubtitle = index, subtitleError = null, subtitleLoading = false) }
                } else player.importSubtitle(lease.url, "${track.title}.$ext", sourceKey, true)
                check(imported) { player.state.value.operationError ?: "字幕未能完整载入" }
                checkCurrent()
                leases += lease; mutable.update { it.copy(selectedSourceSubtitle = index, subtitleError = null) }
            }
        } catch (error: Exception) { lease.release(); checkCurrent(); throw error }
    }
    private suspend fun sourceAsset(source: Binding, key: String, endpoint: String, limit: Long): SourceSubtitleAssets.Lease {
        if (!accepts(source.owner, source.epoch)) throw CancellationException("Media source changed")
        return source.subtitleAssets.acquire(key, limit) {
            val lease = source.owner.api.assetLease(endpoint)
            var prepared: java.io.File? = null
            try {
                try { prepared = SourceSubtitleAsset.download(application.cacheDir, lease.url, limit) }
                finally { withContext(NonCancellable) { lease.release() } }
                currentCoroutineContext().ensureActive()
                checkNotNull(prepared).also { prepared = null }
            } finally { withContext(NonCancellable + Dispatchers.IO) { prepared?.delete() } }
        }
    }
    private suspend fun sourceAssStream(source: Binding, key: String, endpoint: String): SourceSubtitleAssets.Lease {
        if (!accepts(source.owner, source.epoch)) throw CancellationException("Media source changed")
        return source.subtitleAssets.acquireGrowing(key, EXTERNAL_TEXT_BYTES_LIMIT) { writer ->
            val lease = source.owner.api.assetLease(endpoint)
            try { ProgressiveAssSource.download(application.cacheDir, lease.url, source.identity, writer) }
            finally { withContext(NonCancellable) { lease.release() } }
        }
    }
    private suspend fun loadFonts(source: Binding, expected: Long, selection: Long) {
        val fonts = mutable.value.info?.optJSONArray("attachments") ?: return
        val generation = player.state.value.mediaGeneration
        if (fontGeneration != generation) { nativeFontKeys.clear(); fontGeneration = generation }
        fun checkCurrent() {
            if (!accepts(source.owner, expected) || selection != subtitleRevision || player.state.value.mediaGeneration != generation)
                throw CancellationException("Subtitle selection changed")
        }
        checkCurrent()
        val pending = (0 until minOf(fonts.length(), 64)).map { fonts.getJSONObject(it) }
            .distinctBy { it.getString("id") }.filterNot { it.getString("id") in nativeFontKeys }
        val complete = SourceSubtitleFonts.loadPrepared(pending.map { SourceSubtitleFont(it.getString("id"), it.getString("name")) },
            prepare = { font ->
                checkCurrent()
                sourceAsset(source, "font:${font.id}", "/api/media/attachments/${font.id}?${sourceQuery(source.file)}&identity=${encode(source.identity)}",
                    SUBTITLE_FONT_BYTES_LIMIT.toLong())
            },
            register = { font, asset ->
                checkCurrent()
                val bytes = withContext(Dispatchers.IO) { asset.file.readBytes() }
                checkCurrent()
                player.addSubtitleFont(font.name, bytes).also { if (it) nativeFontKeys += font.id }
            }, release = { it.close() })
        checkCurrent()
        mutable.update { it.copy(subtitleNotice = if (complete) null else "部分字幕字体未能载入，文字可能缺失；重新选择字幕可重试") }
    }
    fun selectExternalSubtitle(file: ResourceRef) = selectSubtitleIntent(SubtitleIntent.External(file, "external:${file.mediaKey}"))

    private suspend fun importExternal(intent: SubtitleIntent.External) {
        val source = binding ?: return
        val expected = epoch
        val selection = subtitleRevision
        val generation = player.state.value.mediaGeneration
        fun checkCurrent() {
            if (!accepts(source.owner, expected) || selection != subtitleRevision || generation != player.state.value.mediaGeneration)
                throw CancellationException("Subtitle selection changed")
        }
        val file = intent.file
        val key = intent.key
        checkCurrent()
        if (intent.trackKey != null && player.selectImportedSubtitle(key, intent.trackKey, intent.version)) {
            mutable.update { it.copy(selectedSourceSubtitle = -1, subtitleError = null) }; return
        }
        val lease = originalLease(source.owner, file)
        try {
            readAsset(lease, retain = true) {
                checkCurrent()
                check(player.importSubtitle(lease.url, file.name, key, intent.trackKey == null, intent.version)) { "外挂字幕未能载入" }
                checkCurrent()
                if (intent.trackKey != null) check(player.selectImportedSubtitle(key, intent.trackKey, intent.version)) { "原外挂字幕语言已变化，请重新选择" }
                externalRefs[key] = file
                val restored = player.decoderRestoreSnapshot()
                subtitleIntent = intent.copy(trackKey = restored?.externalSubtitle?.trackKey, version = restored?.externalSources?.get(key))
                leases += lease; mutable.update { it.copy(selectedSourceSubtitle = -1, subtitleError = null) }
            }
        } catch (error: Exception) { lease.release(); checkCurrent(); throw error }
    }
    private suspend fun loadSidecars(owner: SessionContext, file: ResourceRef, expected: Long) {
        try {
            val wire = file.wirePath.substringBeforeLast('/').ifEmpty { "/" }
            val files = TvModel.directoryFiles(owner.api.request("GET", "/api/resources$wire")).filter { !it.directory && isExternalSubtitle(it.name) }
            if (accepts(owner, expected)) mutable.update { it.copy(sidecars = files) }
        } catch (error: Exception) { if (error is CancellationException) throw error }
    }
    private var originalImageEpoch: Long? = null
    fun requestOriginalImage() {
        val owner = current() ?: return
        val file = mutable.value.file ?: return
        val expected = epoch
        if (file.mediaKind() != MediaKind.IMAGE) return
        if (originalImageEpoch == expected) return
        originalImageEpoch = expected
        scope.launch {
            try {
                val lease = owner.api.image(file.path, file.wirePath, ImageQuality.HIGH)
                if (!accepts(owner, expected)) { lease.release(); return@launch }
                leases += lease
                mutable.update { it.copy(imageUrl = lease.url) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (originalImageEpoch == expected) originalImageEpoch = null
                if (accepts(owner, expected)) mutable.update { it.copy(notice = "暂时无法读取原图，已保留预览") }
            }
        }
    }
    fun imageFailed(resourceKey: String,expected:Long) {
        if (epoch==expected&&mutable.value.file?.mediaKey == resourceKey) mutable.update { it.copy(error = "无法解码这张图片，可重试或返回") }
    }
    fun imageReady(resourceKey:String,expected:Long) { if(epoch==expected&&mutable.value.file?.mediaKey==resourceKey)mutable.update{it.copy(imageReady=true)} }
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
        lock.withLock { if (epoch == expected) disposeBinding(closeUi=true) }
        if (epoch == expected) mutable.update { TvMediaState(mode = it.mode) }
    }

    fun close() { scope.launch { closeAndSave() } }
    private suspend fun disposeBinding(closeUi:Boolean=false) {
        saveProgress(); binding?.subtitleAssets?.close(); binding?.writer?.close(); binding = null; player.stop()
        // Restore browsing before waiting for lease/cache IO. The old surface
        // must not stay visible while its retired cache files are removed.
        if(closeUi)mutable.update{TvMediaState(mode=it.mode)}
        playbackLease?.release(); playbackLease = null
        cache.stop();window = null
        leases.forEach { it.release() }; leases.clear()
        externalRefs.clear()
    }
    fun release() { ++epoch; operation?.cancel(); subtitleJob?.cancel(); watcher?.cancel(); prefetch?.cancel(); binding?.subtitleAssets?.close(); player.release();cache.release(); CoroutineScope(Dispatchers.IO).launch { playbackLease?.release(); leases.forEach { it.release() }; binding?.writer?.close() } }
}
