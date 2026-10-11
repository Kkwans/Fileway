package io.github.kkwans.nasfilebrowser.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.NoSampleRenderer
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.CueDecoder
import io.github.peerless2012.ass.Ass
import io.github.peerless2012.ass.AssFrame
import io.github.peerless2012.ass.AssTexType
import io.github.peerless2012.ass.AssTrack
import java.util.concurrent.Executors
import java.util.concurrent.CancellationException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** One media generation owns the native subtitle state. Video remains on its own Surface. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class MediaSubtitleLayer(context: Context) : View(context), MediaSubtitleExtractor.Sink {
    private val worker = Executors.newSingleThreadExecutor()
    private val submissions = Any()
    private val closed = AtomicBoolean()
    private val queued = AtomicBoolean()
    private val revision = AtomicLong()
    private val selection = AtomicLong()
    private val timeMs = AtomicLong()
    private var library: Ass? = null
    private var renderer: io.github.peerless2012.ass.AssRender? = null
    private val tracks = mutableMapOf<String, AssTrack>()
    private val headers = mutableMapOf<String, List<ByteArray>>()
    private val cues = mutableMapOf<String, MutableList<CuesWithTiming>>()
    private class PgsPacket(val timeUs: Long, val bytes: ByteArray = byteArrayOf(), val external: ExternalPgs.DisplaySet? = null)
    private val pgs = mutableMapOf<String, java.util.TreeMap<Long, PgsPacket>>()
    private class IndexedPgs(val timeline: ExternalPgs.Timeline, val isCancelled: () -> Boolean) {
        private val disposed = AtomicBoolean()
        @Volatile var active = false
        @Volatile var accepted = false
        fun cancelledBeforeReady() = !accepted && isCancelled()
        fun close() {
            active = false
            if (disposed.compareAndSet(false, true)) runCatching { timeline.close() }
        }
    }
    private val indexedPgs = mutableMapOf<String, IndexedPgs>()
    private var indexedBytes = 0L
    private val cueDecoder = CueDecoder()
    private var lastPgsPacket: PgsPacket? = null
    private var lastIndexedPgs: IndexedPgs? = null
    private var lastIndexedEvent = -1
    private var decodedPgs: CuesWithTiming? = null
    private var eventBytes = 0L
    private var cueBytes = 0L
    private var lastNative: AssFrame? = null
    private var lastTrack: AssTrack? = null
    private var frame: AssFrame? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    @Volatile private var assId: String? = null
    @Volatile private var textId: String? = null
    @Volatile private var frameWidth = 0
    @Volatile private var frameHeight = 0
    @Volatile var storageWidth = 0
    @Volatile var storageHeight = 0
    @Volatile var delayMs = 0L
    @Volatile var collecting = false
        private set
    var showText: (List<Cue>) -> Unit = {}
    var failed: () -> Unit = {}

    private fun submit(onFailure: (() -> Unit)? = null, action: () -> Unit) = synchronized(submissions) {
        if (!closed.get()) worker.execute {
            if (!closed.get()) try { action() }
            catch (_: Exception) { post { if (!closed.get()) (onFailure ?: failed)() } }
        }
    }
    private fun ass() = library ?: Ass().also { library = it }
    override fun active() { collecting = true }
    override fun font(name: String, bytes: ByteArray) = submit { ass().addFont(name, bytes) }
    override fun format(format: Format) = submit {
        val id = requireNotNull(format.id)
        val old = headers[id]
        if (old != null && old.size == format.initializationData.size && old.zip(format.initializationData).all { (a, b) -> a.contentEquals(b) }) return@submit
        tracks.remove(id)?.release()
        headers[id] = format.initializationData.map { it.copyOf() }
        tracks[id] = ass().createTrack().also { track ->
            track.readBuffer(requireNotNull(format.initializationData.lastOrNull { it.decodeToString().contains("[Script Info]") }))
        }
    }
    override fun dialogue(trackId: String, startMs: Long, durationMs: Long, packet: ByteArray) = submit {
        eventBytes += packet.size
        check(eventBytes <= 64L * 1024 * 1024 && durationMs >= 0)
        requireNotNull(tracks[trackId]).readChunk(startMs, durationMs, packet)
        requestFrame(timeMs.get())
    }
    private fun bytes(value: CuesWithTiming) = value.cues.sumOf { (it.text?.length?.toLong() ?: 0) * 2 + (it.bitmap?.byteCount?.toLong() ?: 0) }
    override fun textCues(trackId: String, value: CuesWithTiming) = submit {
        val list = cues.getOrPut(trackId) { mutableListOf() }
        if (list.none { it.startTimeUs == value.startTimeUs && it.durationUs == value.durationUs && it.cues == value.cues }) {
            list.add(value); cueBytes += bytes(value)
            trimCues()
        }
        requestFrame(timeMs.get())
    }
    override fun encodedPgs(trackId: String, timeUs: Long, packet: ByteArray) = submit {
        if (timeUs == C.TIME_UNSET) return@submit
        val list = pgs.getOrPut(trackId) { java.util.TreeMap() }
        val old = list[timeUs]
        if (old == null || !old.bytes.contentEquals(packet)) {
            cueBytes -= old?.bytes?.size ?: 0
            list[timeUs] = PgsPacket(timeUs, packet)
            cueBytes += packet.size
            trimCues()
        }
        requestFrame(timeMs.get())
    }
    private fun cueCount() = cues.values.sumOf { it.size } + pgs.values.sumOf { it.size }
    private class Eviction(val id: String, val timeUs: Long, val current: Boolean, val text: CuesWithTiming? = null)
    private fun trimCues() {
        val atUs = (timeMs.get() - delayMs) * 1000
        while (cueBytes + indexedBytes > 32L * 1024 * 1024 || cueCount() > 20_000) {
            val candidates = sequence {
                for ((id, values) in cues) {
                    if (id.startsWith("external:")) continue
                    val indefinite = values.filter { it.durationUs == C.TIME_UNSET && it.startTimeUs <= atUs }.maxByOrNull { it.startTimeUs }
                    for (value in values) yield(Eviction(id, value.startTimeUs,
                        value.startTimeUs <= atUs && (if (value.durationUs == C.TIME_UNSET) value === indefinite else atUs < value.endTimeUs), value))
                }
                for ((id, values) in pgs) {
                    if (id.startsWith("external:")) continue
                    // Empty packets are display sets too: retaining the floor
                    // event prevents an earlier caption surviving its clear.
                    val floor = values.floorKey(atUs)
                    for (time in values.keys) yield(Eviction(id, time, time == floor))
                }
            }
            val victim = subtitleCacheVictim(candidates, atUs, { it.current }, { it.id == textId }, { it.timeUs }) ?: break
            if (victim.text != null) {
                cues[victim.id]?.remove(victim.text)
                cueBytes -= bytes(victim.text)
            } else {
                cueBytes -= pgs[victim.id]?.remove(victim.timeUs)?.bytes?.size ?: 0
            }
        }
    }
    fun externalAss(id: String, data: ByteArray, onFailure: (() -> Unit)? = null, ready: () -> Unit) = submit(onFailure) {
        tracks.remove(id)?.release()
        tracks[id] = ass().createTrack().also { it.readBuffer(data) }
        post { if (!closed.get()) ready() }
    }
    fun externalText(id: String, values: List<CuesWithTiming>, onFailure: (() -> Unit)? = null, ready: () -> Unit) =
        externalTexts(mapOf(id to values), onFailure, ready)
    fun externalPgs(id: String, values: List<ExternalPgs.DisplaySet>, onFailure: (() -> Unit)? = null, ready: () -> Unit) = submit(onFailure) {
        val size = ExternalPgs.encodedBytes(values)
        check(size <= 32L * 1024 * 1024 && values.size <= 20_000)
        check(cueBytes + indexedBytes + size <= 32L * 1024 * 1024 && cueCount() + values.size <= 20_000)
        pgs[id] = java.util.TreeMap<Long, PgsPacket>().apply {
            values.forEach { put(it.startTimeUs, PgsPacket(it.startTimeUs, external = it)) }
        }
        cueBytes += size
        post { if (!closed.get()) ready() }
    }
    /** Ownership transfers at entry, including a closed layer or a rejected
     * submission. Only the immutable index is retained, never the whole SUP. */
    fun externalPgsTimeline(id: String, timeline: ExternalPgs.Timeline, isCancelled: () -> Boolean,
        onFailure: (() -> Unit)? = null, ready: () -> Unit) {
        val entry = IndexedPgs(timeline, isCancelled)
        synchronized(submissions) {
            if (closed.get()) { entry.close(); return }
            try {
                worker.execute {
                    try {
                        if (closed.get() || entry.cancelledBeforeReady()) { entry.close(); return@execute }
                        val size = timeline.retainedBytes
                        val old = indexedPgs[id]
                        check(size in 0..32L * 1024 * 1024 &&
                            cueBytes + indexedBytes - (old?.timeline?.retainedBytes ?: 0) + size <= 32L * 1024 * 1024)
                        old?.let { removeIndexed(id, it) }
                        indexedPgs[id] = entry; indexedBytes += size; entry.active = true
                        if (closed.get() || entry.cancelledBeforeReady()) { removeIndexed(id, entry); return@execute }
                        val posted = post {
                            if (closed.get() || entry.cancelledBeforeReady()) discardIndexed(id, entry)
                            else try {
                                ready()
                                if (closed.get() || entry.cancelledBeforeReady()) discardIndexed(id, entry)
                                else entry.accepted = true
                            } catch (_: CancellationException) { discardIndexed(id, entry) }
                            catch (_: Exception) {
                                discardIndexed(id, entry)
                                if (!closed.get() && !entry.cancelledBeforeReady()) (onFailure ?: failed)()
                            }
                        }
                        if (!posted) removeIndexed(id, entry)
                    } catch (_: CancellationException) { removeIndexed(id, entry) }
                    catch (_: Exception) {
                        removeIndexed(id, entry)
                        post { if (!closed.get() && !entry.cancelledBeforeReady()) (onFailure ?: failed)() }
                    }
                }
            } catch (_: RejectedExecutionException) {
                entry.close()
                post { if (!closed.get() && !entry.cancelledBeforeReady()) (onFailure ?: failed)() }
            }
        }
    }
    /** UI ready/cancel callbacks enqueue cleanup on the same worker as decode.
     * Once closed, the already queued media-close task owns every attached entry. */
    private fun discardIndexed(id: String, entry: IndexedPgs) = synchronized(submissions) {
        if (!closed.get()) try { worker.execute { removeIndexed(id, entry) } }
        catch (_: RejectedExecutionException) { entry.close() }
    }
    private fun removeIndexed(id: String, entry: IndexedPgs) {
        if (indexedPgs[id] === entry) {
            indexedPgs.remove(id); indexedBytes -= entry.timeline.retainedBytes
        }
        if (lastIndexedPgs === entry) {
            lastIndexedPgs = null; lastIndexedEvent = -1; decodedPgs = null
        }
        entry.close()
    }
    private fun indexedEvent(entry: IndexedPgs): Int {
        val at = timeMs.get() - delayMs
        return if (at < 0) -1 else try { entry.timeline.floorIndex(at * 1000) }
        catch (_: CancellationException) { Int.MIN_VALUE }
    }
    private fun currentIndexedFrame(entry: IndexedPgs, index: Int, selectedText: String?, epoch: Long): Boolean =
        !closed.get() && selection.get() == epoch && textId == selectedText && entry.active &&
            !entry.cancelledBeforeReady() && indexedEvent(entry) == index
    fun externalTexts(values: Map<String, List<CuesWithTiming>>, onFailure: (() -> Unit)? = null, ready: () -> Unit) = submit(onFailure) {
        val count = values.values.sumOf { it.size }
        val size = values.values.sumOf { items -> items.sumOf(::bytes) }
        check(count <= 20_000 && size <= 32L * 1024 * 1024)
        check(cueBytes + indexedBytes + size <= 32L * 1024 * 1024 && cueCount() + count <= 20_000)
        // Attach only a fully parsed file. A partial/failed read never becomes
        // a selected track, and whole-file seek does not silently lose old cues.
        values.forEach { (id, items) -> cues[id] = items.toMutableList() }; cueBytes += size
        post { if (!closed.get()) ready() }
    }
    fun select(assTrack: String?, textTrack: String?, positionMs: Long) {
        if (assId == assTrack && textId == textTrack) { requestFrame(positionMs); return }
        assId = assTrack; textId = textTrack
        selection.incrementAndGet(); frame = null; showText(emptyList()); invalidate()
        requestFrame(positionMs)
    }
    fun requestFrame(positionMs: Long) {
        timeMs.set(positionMs); revision.incrementAndGet()
        if (closed.get() || !queued.compareAndSet(false, true)) return
        submit {
            val request = revision.get(); val epoch = selection.get()
            val at = timeMs.get() - delayMs
            try {
                val selectedText = textId
                val available = cues[selectedText].orEmpty()
                val lastIndefinite = available.filter { it.durationUs == C.TIME_UNSET && it.startTimeUs <= at * 1000 }.maxByOrNull { it.startTimeUs }
                val ordinary = if (at < 0) emptyList() else available.filter {
                    it.startTimeUs <= at * 1000 && (if (it.durationUs == C.TIME_UNSET) it === lastIndefinite else at * 1000 < it.endTimeUs)
                }.flatMap { it.cues }
                // PGS replaces the previous display set, including an explicit
                // empty clear event. Only the selected display set is decoded.
                val indexed = indexedPgs[selectedText]
                val index = indexed?.let(::indexedEvent) ?: -1
                if (indexed != null) {
                    if (!currentIndexedFrame(indexed, index, selectedText, epoch)) throw CancellationException()
                    if (indexed !== lastIndexedPgs || index != lastIndexedEvent) {
                        decodedPgs = if (index < 0) null else try {
                            indexed.timeline.decode(index) { !currentIndexedFrame(indexed, index, selectedText, epoch) }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) {
                            removeIndexed(requireNotNull(selectedText), indexed)
                            post { if (!closed.get() && selection.get() == epoch && textId == selectedText) failed() }
                            return@submit
                        }
                        lastIndexedPgs = indexed; lastIndexedEvent = index; lastPgsPacket = null
                    }
                } else {
                    val packet = if (at < 0) null else pgs[selectedText]?.floorEntry(at * 1000)?.value
                    if (lastIndexedPgs != null || packet !== lastPgsPacket) {
                        decodedPgs = packet?.let { value -> value.external?.let(ExternalPgs::decode)
                            ?: cueDecoder.decode(value.timeUs, value.bytes, 0, value.bytes.size) }
                        lastPgsPacket = packet; lastIndexedPgs = null; lastIndexedEvent = -1
                    }
                }
                val text = decodedPgs?.takeIf { it.durationUs == C.TIME_UNSET || at * 1000 < it.endTimeUs }?.cues ?: ordinary
                val track = tracks[assId]
                val output = if (track == null || at < 0 || frameWidth <= 0 || frameHeight <= 0) null else {
                    val render = renderer ?: ass().createRender().also { renderer = it; it.setCacheLimit(1000, 64) }
                    if (lastTrack !== track) { lastNative = null; lastTrack = track }
                    render.setStorageSize(storageWidth.takeIf { it > 0 } ?: frameWidth, storageHeight.takeIf { it > 0 } ?: frameHeight)
                    render.setFrameSize(frameWidth, frameHeight); render.setTrack(track)
                    val next = render.renderFrame(at, AssTexType.BITMAP_RGBA)
                    if (next == null || next.changed != 0) lastNative = next
                    lastNative
                }
                post {
                    if (!closed.get() && selection.get() == epoch &&
                        (indexed == null || currentIndexedFrame(indexed, index, selectedText, epoch))) {
                        frame = output
                        if (selectedText != null) showText(text)
                        invalidate()
                    }
                }
            } catch (_: CancellationException) {
                // Seek, delay, selection and media close invalidate an event,
                // not the subtitle track. The latest request is queued below.
            } finally {
                queued.set(false)
                if (!closed.get() && revision.get() != request) requestFrame(timeMs.get())
            }
        }
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        frameWidth = w; frameHeight = h; requestFrame(timeMs.get())
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        frame?.images?.forEach { entry -> entry.bitmap?.let { canvas.drawBitmap(it, entry.x.toFloat(), entry.y.toFloat(), paint) } }
    }
    fun close() = synchronized(submissions) {
        if (!closed.compareAndSet(false, true)) return@synchronized
        selection.incrementAndGet(); frame = null; invalidate()
        worker.execute {
            indexedPgs.values.forEach { it.close() }; indexedPgs.clear(); indexedBytes = 0
            renderer?.release(); tracks.values.forEach { it.release() }; library?.release()
            tracks.clear(); cues.clear(); pgs.clear(); headers.clear(); lastNative = null
            lastPgsPacket = null; lastIndexedPgs = null; lastIndexedEvent = -1; decodedPgs = null
        }
        worker.shutdown()
    }
    class Clock(private val layer: MediaSubtitleLayer) : NoSampleRenderer() {
        private var offsetUs = 0L
        override fun getName() = "FilewaySubtitleClock"
        override fun onRendererOffsetChanged(offsetUs: Long) { this.offsetUs = offsetUs }
        override fun render(positionUs: Long, elapsedRealtimeUs: Long) = layer.requestFrame((positionUs - offsetUs) / 1000)
        override fun onPositionReset(positionUs: Long, joining: Boolean, sampleStreamIsResetToKeyFrame: Boolean) = layer.requestFrame((positionUs - offsetUs) / 1000)
    }
}
