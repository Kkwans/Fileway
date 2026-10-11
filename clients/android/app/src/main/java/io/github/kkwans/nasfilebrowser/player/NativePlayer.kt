package io.github.kkwans.nasfilebrowser.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ViewGroup
import androidx.media3.common.*
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import io.github.kkwans.nasfilebrowser.BuildConfig
import io.github.kkwans.nasfilebrowser.data.TextSubtitleAppearance
import io.github.kkwans.nasfilebrowser.data.VideoDecodePolicy
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecDecoderException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

data class NativeTrack(val id: Int, val title: String, val codec: String = "", val language: String = "")
data class PlayerState(
    val phase: String = "准备播放", val playing: Boolean = false, val positionMs: Long = 0,
    val durationMs: Long = 0, val buffering: Float = 0f, val seekable: Boolean = false,
    val audio: List<NativeTrack> = emptyList(), val subtitles: List<NativeTrack> = emptyList(),
    val selectedAudio: Int = -1, val selectedSubtitle: Int = -1, val width: Int = 0, val height: Int = 0,
    val error: String? = null, val rate: Float = 1f, val volume: Int = 100,
    val mediaGeneration: Long = 0, val operationError: String? = null,
    val subtitleDelayMs: Long = 0, val subtitleLoading: Boolean = false,
    val firstFrameRendered: Boolean = false, val bufferedPositionMs: Long = 0,
    val videoDecoder: String = "未知", val audioDecoder: String = "未知",
    val sourceVideoCodec: String = "未知", val sourceDynamicRange: String = "未知",
    val sourceColorSpace: String = "未知",
    val waitingForBuffer: Boolean = false,
    val videoDecodePolicy: VideoDecodePolicy = VideoDecodePolicy.AUTO,
    val videoDecoderKind: String = "未知",
    val pendingAudio: Int? = null, val pendingSubtitle: Int? = null,
    val decoderRecovery: String? = null,
    /** Engine confirmed this position; a requested/buffering seek is not a checkpoint. */
    val canSavePosition: Boolean = false,
)

/** Main-thread session facade. Track/rate/subtitle commands never reopen the media or rebind video. */
@androidx.annotation.OptIn(UnstableApi::class)
class NativePlayer(context: Context) {
    private val context = context.applicationContext
    private val session = PlaybackSession()
    private val trace = PlaybackTrace(SystemClock::elapsedRealtime, enabled = BuildConfig.DEBUG)
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(PlayerState())
    val state = mutable.asStateFlow()
    var checkpoint: (() -> Unit)? = null
    private var engine: ExoPlayer? = null
    private var loadControl: PlaybackLoadControl? = null
    private var viewport: PlayerViewport? = null
    private var layer: MediaSubtitleLayer? = null
    private var released = false
    private var temporaryRate: Float? = null
    private var seekTarget: Long? = null
    private var externalJob: Job? = null
    private var externalSupCancellation: AtomicBoolean? = null
    private var externalRequest = 0L
    private var selectedExternal: Int? = null
    private var subtitleDisabled = false
    private val trackCommands = mutableMapOf<Int, Runnable>()
    private val trackTimeouts = mutableMapOf<Int, Runnable>()
    private data class Choice(val group: TrackGroup, val index: Int, val type: Int)
    private val choices = mutableMapOf<Int, Choice>()
    private val ids = mutableMapOf<Pair<TrackGroup, Int>, Int>()
    private val external = linkedMapOf<Int, NativeTrack>()
    private var nextId = 1 // IDs are never reused across media generations.
    private var textIsAss = false
    private var textIsCollected = false
    private var subtitleAppearance = TextSubtitleAppearance()
    fun textSubtitleAppearance(value: TextSubtitleAppearance) {
        subtitleAppearance = value
        viewport?.textAppearance(value)
    }
    private val ticker = object : Runnable {
        override fun run() { if (engine != null && !released) { publish(); handler.postDelayed(this, 200) } }
    }
    val canAddExternalSubtitle get() = !released && session.active && layer != null && engine?.let {
        it.playerError == null && it.playbackState in setOf(Player.STATE_BUFFERING, Player.STATE_READY, Player.STATE_ENDED)
    } == true
    fun diagnosticSnapshot() = trace.snapshot()

    fun attach(view: ViewGroup) {
        if (released) return
        detach()
        val target = if (view is PlayerViewport) view else PlayerViewport(view.context).also {
            view.addView(it, ViewGroup.LayoutParams(-1, -1))
        }
        viewport = target
        target.textAppearance(subtitleAppearance)
        target.subtitles(layer)
        engine?.let { target.videoSize(it.videoSize.width, it.videoSize.height, it.videoSize.pixelWidthHeightRatio); it.setVideoSurfaceView(target.video) }
        trace.record(PlaybackTraceAction.ATTACH)
    }
    fun detach() {
        viewport?.let { engine?.clearVideoSurfaceView(it.video); it.subtitles(null) }
        viewport = null
        trace.record(PlaybackTraceAction.DETACH)
    }
    fun open(url: String, positionMs: Long = 0, autoplay: Boolean = true, videoDecodePolicy: VideoDecodePolicy = VideoDecodePolicy.AUTO,
        dataSourceFactory: androidx.media3.datasource.DataSource.Factory? = null) {
        if (released) return
        disposeMedia()
        val epoch = session.open(autoplay)
        temporaryRate = null; seekTarget = null
        choices.clear(); ids.clear(); external.clear(); selectedExternal = null
        subtitleDisabled = false
        mutable.value = PlayerState(phase = "正在打开视频", rate = session.preferredRate, mediaGeneration = epoch, videoDecodePolicy = videoDecodePolicy)
        trace.beginOpen(positionMs)
        val subtitles = MediaSubtitleLayer(context).also { current ->
            current.failed = { if (session.accepts(epoch)) mutable.value = mutable.value.copy(subtitleLoading = false, operationError = "字幕渲染失败，请重试或选择其他字幕") }
        }
        layer = subtitles
        viewport?.subtitles(subtitles)
        val decoderKinds = java.util.concurrent.ConcurrentHashMap<String, String>()
        val decoderRequests = java.util.concurrent.ConcurrentHashMap<String, Pair<Boolean, Boolean>>()
        val recoverWithSoftware = java.util.concurrent.atomic.AtomicBoolean(false)
        var softwareDecoderReadyAt = Long.MAX_VALUE
        var recoveryCompleted = false
        val factory = object : DefaultRenderersFactory(context) {
            override fun buildMiscellaneousRenderers(context: Context, eventHandler: Handler, extensionRendererMode: Int, out: ArrayList<Renderer>) {
                super.buildMiscellaneousRenderers(context, eventHandler, extensionRendererMode, out)
                out.add(MediaSubtitleLayer.Clock(subtitles))
            }
        }.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON).setEnableDecoderFallback(true)
            .setMediaCodecSelector { mime, secure, tunneling ->
                val decoders = MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling)
                if (MimeTypes.isVideo(mime)) decoders.forEach {
                    decoderKinds[it.name] = when { it.hardwareAccelerated -> "硬件"; it.softwareOnly -> "软件"; else -> "未知" }
                    decoderRequests[it.name] = secure to tunneling
                }
                if (!MimeTypes.isVideo(mime)) decoders else when (videoDecodePolicy) {
                    VideoDecodePolicy.AUTO -> if (recoverWithSoftware.get()) decoders.filter { it.softwareOnly }
                        else decoders.sortedByDescending { it.hardwareAccelerated }
                    VideoDecodePolicy.HARDWARE -> decoders.filter { it.hardwareAccelerated }
                    VideoDecodePolicy.SOFTWARE -> decoders.filter { it.softwareOnly }
                }
            }
        val extractors = ExtractorsFactory {
            DefaultExtractorsFactory().createExtractors().map {
                if (it is MatroskaExtractor) MediaSubtitleExtractor(subtitles) else it
            }.toTypedArray()
        }
        val bufferControl = PlaybackLoadControl().also { loadControl = it }
        val mediaSources = if (dataSourceFactory == null) DefaultMediaSourceFactory(context, extractors) else DefaultMediaSourceFactory(dataSourceFactory, extractors)
        if (dataSourceFactory is io.github.kkwans.nasfilebrowser.download.DownloadDataSource.Factory)
            mediaSources.setLoadErrorHandlingPolicy(dataSourceFactory.errorPolicy())
        val player = ExoPlayer.Builder(context, factory).setLoadControl(bufferControl)
            .setMediaSourceFactory(mediaSources).build()
        engine = player
        trace.record(PlaybackTraceAction.ENGINE_READY); trace.record(PlaybackTraceAction.PLAYER_READY)
        fun current() = !released && engine === player && session.accepts(epoch)
        player.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        player.setHandleAudioBecomingNoisy(true)
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) { if (current()) publish() }
            override fun onTracksChanged(tracks: Tracks) { if (current()) refreshTracks() }
            override fun onRenderedFirstFrame() {
                if (current() && player.playerError == null) {
                    mutable.value = mutable.value.copy(firstFrameRendered = true)
                    trace.record(PlaybackTraceAction.VIDEO_OUTPUT); publish()
                }
            }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (current()) {
                    subtitles.storageWidth = videoSize.width; subtitles.storageHeight = videoSize.height
                    viewport?.videoSize(videoSize.width, videoSize.height, videoSize.pixelWidthHeightRatio)
                }
            }
            override fun onCues(cueGroup: CueGroup) {
                if (current() && !subtitleDisabled && !textIsAss && !textIsCollected && selectedExternal == null) viewport?.showCues(cueGroup.cues)
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!current()) return
                if (!playWhenReady) session.pause()
                else if (!session.wantsPlay) player.pause()
                if (!playWhenReady) { publish(); checkpoint?.invoke() }
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (current() && playbackState == Player.STATE_ENDED) { session.pause(); publish(); checkpoint?.invoke() }
            }
            override fun onPlayerError(error: PlaybackException) {
                if (!current()) return
                mutable.value = mutable.value.copy(canSavePosition = false)
                trace.record(PlaybackTraceAction.ERROR, error.errorCode.toDouble())
                val failedCodec = generateSequence(error.cause) { it.cause }.take(8)
                    .filterIsInstance<MediaCodecDecoderException>().firstOrNull()?.codecInfo
                if (videoDecodePolicy == VideoDecodePolicy.AUTO && !recoverWithSoftware.get() &&
                    failedCodec != null && failedCodec.hardwareAccelerated && MimeTypes.isVideo(failedCodec.mimeType)) {
                    val available = runCatching {
                        // CodecInfo flags describe supported features, not the
                        // secure/tunneling mode requested for this playback.
                        val requested = decoderRequests[failedCodec.name] ?: return@runCatching false
                        MediaCodecSelector.DEFAULT.getDecoderInfos(failedCodec.mimeType, requested.first, requested.second).any { it.softwareOnly }
                    }.getOrDefault(false)
                    if (available && recoverWithSoftware.compareAndSet(false, true)) {
                        mutable.value = mutable.value.copy(playing = false, firstFrameRendered = false,
                            decoderRecovery = "硬件解码失败，正在尝试软件解码；HDR 输出尚未确认")
                        handler.post {
                            if (current() && player.playerError === error) {
                                // Reprepare the same media/lease at the retained position.
                                // Keep track parameters, rate and current pause intent.
                                loadControl?.resetProgress()
                                mutable.value = mutable.value.copy(firstFrameRendered = false)
                                player.playWhenReady = session.wantsPlay
                                player.prepare()
                            }
                        }
                        return
                    }
                }
                cancelTrackRequest(C.TRACK_TYPE_AUDIO); cancelTrackRequest(C.TRACK_TYPE_TEXT)
                session.pause()
                mutable.value = mutable.value.copy(playing = false, phase = "播放失败",
                    decoderRecovery = if (recoverWithSoftware.get() && !recoveryCompleted) "软件解码未能恢复，请手动重试" else mutable.value.decoderRecovery,
                    error = when (error.errorCode) {
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "网络读取失败，请检查连接后重试"
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "这条视频或音轨暂时无法解码，请尝试其他音轨"
                    else -> "视频无法继续播放，请重试或选择其他文件"
                })
            }
        })
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                if (current()) {
                    mutable.value = mutable.value.copy(videoDecoder = decoderName, videoDecoderKind = decoderKinds[decoderName] ?: "未知")
                    if (recoverWithSoftware.get() && mutable.value.videoDecoderKind == "软件") softwareDecoderReadyAt = initializedTimestampMs
                }
            }
            override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
                if (current() && player.playerError == null && recoverWithSoftware.get() && renderTimeMs >= softwareDecoderReadyAt) {
                    recoveryCompleted = true
                    mutable.value = mutable.value.copy(decoderRecovery = "已使用软件解码恢复画面；HDR 输出尚未确认")
                }
            }
            override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                if (current()) mutable.value = mutable.value.copy(audioDecoder = decoderName)
            }
            override fun onVideoDecoderReleased(eventTime: AnalyticsListener.EventTime, decoderName: String) {
                if (current() && mutable.value.videoDecoder == decoderName) mutable.value = mutable.value.copy(videoDecoder = "未知", videoDecoderKind = "未知")
            }
            override fun onAudioDecoderReleased(eventTime: AnalyticsListener.EventTime, decoderName: String) {
                if (current() && mutable.value.audioDecoder == decoderName) mutable.value = mutable.value.copy(audioDecoder = "未知")
            }
        })
        viewport?.let { player.setVideoSurfaceView(it.video) }
        player.setPlaybackSpeed(session.preferredRate)
        player.setMediaItem(MediaItem.fromUri(url), positionMs.coerceAtLeast(0))
        trace.record(PlaybackTraceAction.MEDIA_SET)
        player.prepare(); player.playWhenReady = autoplay
        if (autoplay) trace.record(PlaybackTraceAction.PLAY_REQUEST)
        handler.post(ticker)
    }
    private fun publish() {
        val player = engine ?: return
        val previous = mutable.value
        if (previous.error != null) {
            if (previous.canSavePosition) mutable.value = previous.copy(canSavePosition = false)
            return
        }
        if (seekTarget != null && player.playbackState == Player.STATE_READY && kotlin.math.abs(player.currentPosition - requireNotNull(seekTarget)) < 1000) {
            trace.record(PlaybackTraceAction.SEEK_REACHED, player.currentPosition.toDouble()); seekTarget = null
        }
        if (previous.positionMs == 0L && player.currentPosition > 0) trace.record(PlaybackTraceAction.FIRST_CLOCK, player.currentPosition.toDouble())
        if (previous.playing != player.isPlaying) trace.record(if (player.isPlaying) PlaybackTraceAction.PLAYING else PlaybackTraceAction.PAUSED)
        val format = player.videoFormat
        val colors = format?.colorInfo
        mutable.value = previous.copy(
            phase = when {
                player.playbackState == Player.STATE_ENDED -> "播放完毕"
                seekTarget != null -> "正在跳转"
                !player.playWhenReady -> "已暂停"
                player.playbackState == Player.STATE_BUFFERING -> if (previous.firstFrameRendered) "正在缓冲" else "正在打开视频"
                player.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE -> "已暂停"
                else -> "正在播放"
            }, playing = player.isPlaying, positionMs = player.currentPosition.coerceAtLeast(0), durationMs = player.duration.coerceAtLeast(0),
            canSavePosition = (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_ENDED) &&
                seekTarget == null && player.playerError == null,
            seekable = player.isCurrentMediaItemSeekable,
            waitingForBuffer = player.playWhenReady && player.playbackState == Player.STATE_BUFFERING,
            buffering = if (player.playbackState == Player.STATE_READY) 100f else (loadControl?.percent ?: 0).coerceAtMost(99).toFloat(),
            bufferedPositionMs = player.bufferedPosition.coerceAtLeast(0),
            width = player.videoSize.width, height = player.videoSize.height, rate = player.playbackParameters.speed, volume = (player.volume * 100).toInt(),
            sourceVideoCodec = format?.codecs ?: format?.sampleMimeType ?: "未知",
            sourceDynamicRange = when {
                format?.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION -> "Dolby Vision（片源标记）"
                colors?.colorTransfer == C.COLOR_TRANSFER_ST2084 -> "PQ（片源标记）"
                colors?.colorTransfer == C.COLOR_TRANSFER_HLG -> "HLG（片源标记）"
                colors?.colorTransfer == C.COLOR_TRANSFER_SDR -> "SDR（片源标记）"
                else -> "未知"
            },
            sourceColorSpace = when (colors?.colorSpace) {
                C.COLOR_SPACE_BT2020 -> "BT.2020"
                C.COLOR_SPACE_BT709 -> "BT.709"
                C.COLOR_SPACE_BT601 -> "BT.601"
                else -> "未知"
            },
        )
    }
    private fun refreshTracks() {
        val player = engine ?: return
        val audio = mutableListOf(NativeTrack(-1, "关闭")); val text = mutableListOf(NativeTrack(-1, "关闭"))
        choices.clear()
        var selectedAudio = -1; var selectedText = -1; var actualText = -1; var selectedFormat: Format? = null
        for (group in player.currentTracks.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO && group.type != C.TRACK_TYPE_TEXT) continue
            for (index in 0 until group.length) {
                val format = group.getTrackFormat(index)
                val id = ids.getOrPut(group.mediaTrackGroup to index) { nextId++ }
                choices[id] = Choice(group.mediaTrackGroup, index, group.type)
                val mime = format.codecs.takeIf { format.sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES } ?: format.sampleMimeType.orEmpty()
                val title = format.label?.takeIf { it.isNotBlank() }
                    ?: format.language?.takeUnless { it.isBlank() || it == "und" }
                    ?: "${if (group.type == C.TRACK_TYPE_AUDIO) "音轨" else "字幕"} ${index + 1}"
                val item = NativeTrack(id, title, mime, format.language.orEmpty())
                if (group.type == C.TRACK_TYPE_AUDIO) { audio.add(item); if (group.isTrackSelected(index)) selectedAudio = id }
                else {
                    text.add(item)
                    if (group.isTrackSelected(index)) {
                        actualText = id
                        if (!subtitleDisabled) { selectedText = id; selectedFormat = format }
                    }
                }
            }
        }
        text.addAll(external.values)
        val chosen = selectedExternal ?: selectedText
        val previous = mutable.value
        mutable.value = previous.copy(audio = audio, subtitles = text, selectedAudio = selectedAudio, selectedSubtitle = chosen)
        if (C.TRACK_TYPE_AUDIO !in trackCommands && mutable.value.pendingAudio == selectedAudio) cancelTrackRequest(C.TRACK_TYPE_AUDIO)
        if (C.TRACK_TYPE_TEXT !in trackCommands && selectedExternal == null && mutable.value.pendingSubtitle == actualText) cancelTrackRequest(C.TRACK_TYPE_TEXT)
        if (previous.selectedAudio != selectedAudio) trace.record(PlaybackTraceAction.AUDIO_SELECTED, selectedAudio.toDouble())
        if (previous.selectedSubtitle != chosen) trace.record(PlaybackTraceAction.SUBTITLE_SELECTED, chosen.toDouble())
        textIsAss = selectedExternal?.let { external[it]?.codec == MimeTypes.TEXT_SSA } ?: (selectedFormat?.codecs == MimeTypes.TEXT_SSA)
        textIsCollected = selectedExternal != null || (layer?.collecting == true && selectedFormat?.sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES)
        val sourceId = selectedExternal?.let { "external:$it" } ?: selectedFormat?.id
        layer?.select(if (textIsAss) sourceId else null, if (!textIsAss && textIsCollected) sourceId else null, player.currentPosition)
    }
    fun toggle() {
        val player = engine ?: return
        val ended = player.playbackState == Player.STATE_ENDED
        if (player.playWhenReady && !ended) pause() else {
            session.play()
            if (ended) seek(0)
            trace.record(PlaybackTraceAction.PLAY_REQUEST); player.play(); publish()
        }
    }
    fun pause() {
        session.pause(); temporaryRate = null
        engine?.let { trace.record(PlaybackTraceAction.PAUSE_REQUEST); it.pause(); it.setPlaybackSpeed(session.preferredRate); publish() }
    }
    fun seek(position: Long) {
        val player = engine ?: return
        if (!player.isCurrentMediaItemSeekable) return
        val target = position.coerceIn(0, player.duration.coerceAtLeast(0))
        seekTarget = target; trace.record(PlaybackTraceAction.SEEK_REQUEST, target.toDouble())
        mutable.value = mutable.value.copy(canSavePosition = false)
        loadControl?.resetProgress()
        player.seekTo(target); trace.record(PlaybackTraceAction.SEEK_ACCEPTED, target.toDouble()); publish()
    }
    fun rate(value: Float) {
        if (released || !session.selectRate(value)) return
        temporaryRate = null; applyRate(value)
    }
    private fun applyRate(value: Float) {
        trace.record(PlaybackTraceAction.RATE_REQUEST, value.toDouble()); engine?.setPlaybackSpeed(value)
        mutable.value = mutable.value.copy(rate = engine?.playbackParameters?.speed ?: value)
        trace.record(PlaybackTraceAction.RATE_REPORTED, mutable.value.rate.toDouble())
    }
    fun beginTemporaryRate(value: Float): Long? {
        if (!session.active || !mutable.value.playing || !value.isFinite() || value !in .1f..5f) return null
        temporaryRate = value; applyRate(value); return session.generation
    }
    fun restoreRate(epoch: Long) { if (session.accepts(epoch)) { temporaryRate = null; applyRate(session.preferredRate) } }
    fun volume(value: Int) { engine?.volume = value.coerceIn(0, 100) / 100f; publish() }
    fun audio(id: Int) = selectTrack(id, C.TRACK_TYPE_AUDIO)
    fun subtitle(id: Int) {
        if (engine == null) return
        if (id != -1 && id !in external && choices[id]?.type != C.TRACK_TYPE_TEXT) {
            mutable.value = mutable.value.copy(operationError = "轨道已变化，请重新选择")
            return
        }
        // A later explicit selection supersedes any still-loading external file.
        // Invalidate queued native parse callbacks as well as the coroutine read.
        externalRequest++
        cancelExternalImport()
        mutable.value = mutable.value.copy(subtitleLoading = false, operationError = null)
        // Track notifications and cue callbacks can trail the user's command.
        // An explicit off request wins immediately, even before renderer teardown.
        subtitleDisabled = id == -1
        if (id in external) {
            cancelTrackRequest(C.TRACK_TYPE_TEXT)
            selectedExternal = id
            engine?.let { it.trackSelectionParameters = it.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build() }
            refreshTracks()
        } else {
            selectedExternal = null
            selectTrack(id, C.TRACK_TYPE_TEXT)
            // An external selection already disables TEXT. Selecting "off" can
            // therefore produce no tracks-changed event; publish the choice now.
            refreshTracks()
        }
    }
    private fun selectTrack(id: Int, type: Int) {
        val player = engine ?: return
        val choice = choices[id]
        if (id != -1 && (choice == null || choice.type != type)) { mutable.value = mutable.value.copy(operationError = "轨道已变化，请重新选择"); return }
        val audio = type == C.TRACK_TYPE_AUDIO
        cancelTrackRequest(type)
        val epoch = session.generation
        trace.record(if (audio) PlaybackTraceAction.AUDIO_REQUEST else PlaybackTraceAction.SUBTITLE_REQUEST, id.toDouble())
        mutable.value = if (audio) mutable.value.copy(pendingAudio = id, operationError = null)
            else mutable.value.copy(pendingSubtitle = id, operationError = null)
        val command = Runnable {
            trackCommands.remove(type)
            if (!session.accepts(epoch) || engine !== player) return@Runnable
            val next = player.trackSelectionParameters.buildUpon().clearOverridesOfType(type).setTrackTypeDisabled(type, id == -1)
            if (choice != null) next.addOverride(TrackSelectionOverride(choice.group, choice.index))
            player.trackSelectionParameters = next.build()
            trace.record(if (audio) PlaybackTraceAction.AUDIO_ACCEPTED else PlaybackTraceAction.SUBTITLE_ACCEPTED, 1.0)
            if ((if (audio) mutable.value.pendingAudio else mutable.value.pendingSubtitle) != id) return@Runnable
            val timeout = Runnable {
                if (session.accepts(epoch) && engine === player && (if (audio) mutable.value.pendingAudio else mutable.value.pendingSubtitle) == id) {
                    cancelTrackRequest(type)
                    mutable.value = mutable.value.copy(operationError = "${if (audio) "音轨" else "字幕"}切换未确认，请重新选择或重试")
                }
            }
            trackTimeouts[type] = timeout
            handler.postDelayed(timeout, 5000)
            refreshTracks()
        }
        trackCommands[type] = command
        handler.post(command)
        if (id == -1 && !audio) { viewport?.showCues(emptyList()); layer?.select(null, null, player.currentPosition) }
    }
    private fun cancelTrackRequest(type: Int) {
        trackCommands.remove(type)?.let(handler::removeCallbacks)
        trackTimeouts.remove(type)?.let(handler::removeCallbacks)
        mutable.value = if (type == C.TRACK_TYPE_AUDIO) mutable.value.copy(pendingAudio = null)
            else mutable.value.copy(pendingSubtitle = null)
    }
    fun subtitleDelay(valueMs: Long) {
        val value = valueMs.coerceIn(-600_000, 600_000)
        mutable.value = mutable.value.copy(subtitleDelayMs = value)
        layer?.let { it.delayMs = value; it.requestFrame(engine?.currentPosition ?: 0) }
    }
    fun addSubtitle(url: String, name: String): Boolean {
        if (!canAddExternalSubtitle) return false
        val currentLayer = layer ?: return false
        val epoch = session.generation; val request = ++externalRequest; val id = nextId++
        fun externalFailure() {
            if (!session.accepts(epoch) || externalRequest != request) return
            externalSupCancellation?.set(true); externalSupCancellation = null
            externalJob = null
            mutable.value = mutable.value.copy(subtitleLoading = false, operationError = "字幕渲染失败，请重试或选择其他字幕")
        }
        cancelExternalImport()
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val supCancellation = if (extension == "sup") AtomicBoolean(false) else null
        externalSupCancellation = supCancellation
        mutable.value = mutable.value.copy(subtitleLoading = true, operationError = null)
        externalJob = scope.launch {
            try {
                if (supCancellation != null) {
                    var ownedFile: File? = null
                    var ownedTimeline: ExternalPgs.Timeline? = null
                    try {
                        withContext(Dispatchers.IO) {
                            val ioContext = currentCoroutineContext()
                            val source = DefaultDataSource.Factory(context).createDataSource()
                            try {
                                val size = source.open(DataSpec(Uri.parse(url)))
                                if (size > EXTERNAL_SUP_BYTES_LIMIT) throw ExternalSubtitleLimitException(EXTERNAL_SUP_BYTES_LIMIT)
                                val reserve = 16L * 1024 * 1024
                                if (size >= 0 && context.cacheDir.usableSpace < size + reserve)
                                    throw ExternalSubtitleStorageException()
                                val file = File.createTempFile("external-sup-", ".sup", context.cacheDir)
                                ownedFile = file
                                file.outputStream().use { output ->
                                    copyExternalSubtitle(source::read, output, EXTERNAL_SUP_BYTES_LIMIT) { ioContext.ensureActive() }
                                }
                                ioContext.ensureActive()
                                // Assign inside IO before the cancellable dispatcher
                                // handoff, so a late result still has a cleanup owner.
                                ownedTimeline = ExternalPgs.index(file, deleteOnClose = true)
                            } finally { source.close() }
                        }
                        ensureActive()
                        if (!session.accepts(epoch) || externalRequest != request || supCancellation.get()) return@launch
                        currentLayer.externalPgsTimeline("external:$id", checkNotNull(ownedTimeline), supCancellation::get,
                            ::externalFailure) {
                            if (!session.accepts(epoch) || externalRequest != request || supCancellation.get())
                                return@externalPgsTimeline
                            // This accepted track owns its file until media close.
                            // Off can hide/reselect it without another network read.
                            externalSupCancellation = null
                            externalJob = null
                            external[id] = NativeTrack(id, name, MimeTypes.APPLICATION_PGS)
                            mutable.value = mutable.value.copy(subtitleLoading = false)
                            subtitle(id)
                        }
                        // The layer consumes ownership even when already closed.
                        ownedTimeline = null; ownedFile = null
                    } finally {
                        withContext(NonCancellable + Dispatchers.IO) {
                            try { ownedTimeline?.close() } finally { ownedFile?.delete() }
                        }
                    }
                    return@launch
                }
                val bytes = withContext(Dispatchers.IO) {
                    val source = DefaultDataSource.Factory(context).createDataSource()
                    try {
                        val size = source.open(DataSpec(Uri.parse(url)))
                        if (size > EXTERNAL_TEXT_BYTES_LIMIT) throw ExternalSubtitleLimitException(EXTERNAL_TEXT_BYTES_LIMIT)
                        val output = ByteArrayOutputStream()
                        val ioContext = currentCoroutineContext()
                        copyExternalSubtitle(source::read, output, EXTERNAL_TEXT_BYTES_LIMIT) { ioContext.ensureActive() }
                        output.toByteArray()
                    } finally { source.close() }
                }
                ensureActive()
                if (!session.accepts(epoch) || externalRequest != request) return@launch
                if (extension in setOf("smi", "sami")) {
                    val tracks = withContext(Dispatchers.IO) { SamiSubtitles.parse(bytes).map { track ->
                        track to track.captions.map { caption ->
                            val safeHtml = Regex("<img\\b[^>]*>", RegexOption.IGNORE_CASE).replace(caption.html, "")
                            val text = android.text.Html.fromHtml(safeHtml, android.text.Html.FROM_HTML_MODE_LEGACY).trim { it.isWhitespace() || it == '\u00a0' }
                            androidx.media3.extractor.text.CuesWithTiming(if (text.isEmpty()) emptyList() else listOf(androidx.media3.common.text.Cue.Builder().setText(text).build()),
                                caption.startMs * 1000, caption.durationMs?.times(1000) ?: C.TIME_UNSET)
                        }
                    } }
                    ensureActive()
                    if (!session.accepts(epoch) || externalRequest != request) return@launch
                    val ids = tracks.mapIndexed { index, _ -> if (index == 0) id else nextId++ }
                    currentLayer.externalTexts(tracks.mapIndexed { index, (_, values) -> "external:${ids[index]}" to values }.toMap(), onFailure = ::externalFailure) {
                        if (!session.accepts(epoch) || externalRequest != request) return@externalTexts
                        tracks.forEachIndexed { index, (track, _) ->
                            external[ids[index]] = NativeTrack(ids[index], if (tracks.size == 1) name else "$name · ${track.title}", "application/x-sami", track.language)
                        }
                        externalJob = null
                        mutable.value = mutable.value.copy(subtitleLoading = false)
                        subtitle(id)
                    }
                    return@launch
                }
                val mime = when (extension) {
                    "ass", "ssa" -> MimeTypes.TEXT_SSA
                    "srt" -> MimeTypes.APPLICATION_SUBRIP
                    "vtt" -> MimeTypes.TEXT_VTT
                    "ttml", "dfxp" -> MimeTypes.APPLICATION_TTML
                    else -> error("Unsupported external subtitle")
                }
                fun ready() {
                    if (!session.accepts(epoch) || externalRequest != request) return
                    externalJob = null
                    external[id] = NativeTrack(id, name, mime)
                    mutable.value = mutable.value.copy(subtitleLoading = false)
                    subtitle(id)
                }
                if (mime == MimeTypes.TEXT_SSA) currentLayer.externalAss("external:$id", bytes, ::externalFailure, ::ready)
                else {
                    val values = withContext(Dispatchers.IO) {
                        buildList {
                            val parser = DefaultSubtitleParserFactory().create(Format.Builder().setSampleMimeType(mime).build())
                            parser.parse(bytes, SubtitleParser.OutputOptions.allCues()) { add(it) }
                        }
                    }
                    ensureActive()
                    if (session.accepts(epoch) && externalRequest == request) currentLayer.externalText("external:$id", values, ::externalFailure, ::ready)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (session.accepts(epoch) && externalRequest == request) {
                    externalSupCancellation?.set(true); externalSupCancellation = null
                    val message = when (error) {
                        is ExternalSubtitleLimitException, is ExternalSubtitleStorageException -> error.message
                        else -> "字幕读取失败或格式暂不支持，请重试或选择其他字幕"
                    }
                    mutable.value = mutable.value.copy(subtitleLoading = false, operationError = message)
                }
            }
        }
        return true
    }
    fun clearOperationError(expected: String) { if (mutable.value.operationError == expected) mutable.value = mutable.value.copy(operationError = null) }
    private fun cancelExternalImport() {
        externalSupCancellation?.set(true); externalSupCancellation = null
        externalJob?.cancel(); externalJob = null
    }
    private fun disposeMedia() {
        cancelTrackRequest(C.TRACK_TYPE_AUDIO); cancelTrackRequest(C.TRACK_TYPE_TEXT)
        handler.removeCallbacks(ticker)
        externalRequest++; cancelExternalImport()
        val previous = engine; engine = null
        previous?.release()
        loadControl = null
        viewport?.subtitles(null); layer?.close(); layer = null
    }
    fun stop() {
        session.stop(); trace.record(PlaybackTraceAction.STOP_REQUEST)
        disposeMedia(); choices.clear(); ids.clear(); external.clear(); selectedExternal = null
        mutable.value = PlayerState(rate = session.preferredRate, mediaGeneration = session.generation)
        trace.record(PlaybackTraceAction.STOP_RETURNED)
    }
    fun release() {
        if (released) return
        stop(); released = true; viewport = null; scope.cancel(); trace.record(PlaybackTraceAction.RELEASE)
    }
}
