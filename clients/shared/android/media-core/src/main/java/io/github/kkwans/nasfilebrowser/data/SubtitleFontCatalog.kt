package io.github.kkwans.nasfilebrowser.data

import android.os.Build
import android.graphics.Typeface
import android.graphics.fonts.FontFamily
import android.graphics.fonts.SystemFonts
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SubtitleFont(val key: String, val label: String, val typeface: Typeface?)
data class SubtitleFonts(val loading: Boolean, val options: List<SubtitleFont>, val failed: Boolean = false) {
    fun resolve(key: String): SubtitleFont = options.firstOrNull { it.key == key } ?: options.first()
    fun missing(key: String): Boolean = !loading && options.none { it.key == key }
}

data class SubtitleFontNames(val family: String, val subfamily: String, val canonicalFamily: String)

/** Bounded OpenType name metadata only. Table offsets in a TTC are file-relative.
 * See https://learn.microsoft.com/en-us/typography/opentype/spec/name .
 * No outline, cmap, glyph, shaping or filesystem parser is involved.
 */
fun subtitleFontNames(source: ByteBuffer, ttcIndex: Int): SubtitleFontNames? {
    return try {
        val buffer = source.duplicate().order(ByteOrder.BIG_ENDIAN)
        fun region(offset: Long, size: Long): Int {
            require(offset >= 0 && size >= 0 && offset <= buffer.limit().toLong() - size)
            return offset.toInt()
        }
        fun u16(offset: Long) = buffer.getShort(region(offset, 2)).toInt() and 0xffff
        fun u32(offset: Long) = buffer.getInt(region(offset, 4)).toLong() and 0xffffffffL
        val face = if (u32(0) == 0x74746366L) {
            require(u32(4) in setOf(0x00010000L, 0x00020000L))
            val count = u32(8)
            require(count in 1L..256L && ttcIndex >= 0 && ttcIndex.toLong() < count)
            region(12, count * 4)
            u32(12L + ttcIndex * 4L)
        } else {
            require(ttcIndex == 0)
            0L
        }
        require(u32(face) in setOf(0x00010000L, 0x4f54544fL, 0x74727565L))
        val tables = u16(face + 4)
        require(tables in 1..256)
        region(face, 12L + tables * 16L)
        val nameRecord = (0 until tables).map { face + 12 + it * 16L }.singleOrNull { u32(it) == 0x6e616d65L }
            ?: return null
        val start = u32(nameRecord + 8)
        val length = u32(nameRecord + 12)
        require(length in 6L..1_048_576L)
        region(start, length)
        val format = u16(start)
        require(format in 0..1)
        val count = u16(start + 2)
        require(count in 1..512)
        val recordsEnd = 6L + count * 12L
        require(recordsEnd <= length)
        val strings = u16(start + 4).toLong()
        val metadataEnd = if (format == 1) {
            require(recordsEnd + 2 <= length)
            val tags = u16(start + recordsEnd)
            require(tags <= 64)
            recordsEnd + 2 + tags * 4L
        } else recordsEnd
        require(strings in metadataEnd..length)
        data class Name(val id: Int, val value: String, val language: Int, val unicode: Boolean)
        val names = mutableListOf<Name>()
        repeat(count) { index ->
            val record = start + 6 + index * 12L
            val id = u16(record + 6)
            if (id !in setOf(1, 2, 16, 17)) return@repeat
            val platform = u16(record)
            val encoding = u16(record + 2)
            val language = u16(record + 4)
            val unicode = platform == 0 || platform == 3 && encoding in setOf(1, 10)
            if ((!unicode && !(platform == 1 && encoding == 0)) || language >= 0x8000) return@repeat
            val size = u16(record + 8)
            val offset = u16(record + 10).toLong()
            require(size in 1..1024 && (!unicode || size % 2 == 0) && offset <= length - strings - size)
            val bytes = ByteArray(size)
            buffer.duplicate().apply { position(region(start + strings + offset, size.toLong())); get(bytes) }
            if (!unicode && bytes.any { (it.toInt() and 0xff) > 0x7f }) return@repeat
            val charset = if (unicode) Charsets.UTF_16BE else Charsets.US_ASCII
            val value = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString().trim().replace(Regex("\\s+"), " ")
            require(value.isNotEmpty() && value.length <= 100 && value.none {
                Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt()
            })
            names += Name(id, value, language, platform == 0)
        }
        fun find(preferred: Int, legacy: Int, canonical: Boolean = false): String? {
            val candidates = names.filter { it.id == preferred }.ifEmpty { names.filter { it.id == legacy } }
            return candidates.minByOrNull {
                if (canonical) when { it.language == 0x0409 -> 0; it.unicode -> 1; else -> 2 }
                else when { it.language == 0x0804 -> 0; it.language == 0x0409 -> 1; it.unicode -> 2; else -> 3 }
            }?.value
        }
        SubtitleFontNames(find(16, 1) ?: return null, find(17, 2) ?: return null,
            find(16, 1, canonical = true) ?: return null)
    } catch (_: IllegalArgumentException) { null }
    catch (_: java.nio.charset.CharacterCodingException) { null }
}

/** Use declared system locale/script or conservative common-family names, never fallback glyphs. */
fun subtitleTextFamily(names: SubtitleFontNames, locales: List<Locale>): Boolean {
    if (Regex("condensed|narrow|display|caption", RegexOption.IGNORE_CASE).containsMatchIn(names.subfamily)) return false
    val explicit = locales.filter { it.language.isNotBlank() && it.language != "und" || it.script.isNotBlank() }
    if (explicit.isNotEmpty()) return explicit.any {
        it.script in setOf("Latn", "Hans", "Hant", "Hani", "Jpan", "Kore", "Hira", "Kana", "Hang") ||
            it.language in setOf("zh", "ja", "ko", "en", "fr", "de", "es", "it", "pt", "nl", "sv", "da", "no", "fi", "pl", "cs", "ro", "tr", "vi", "id", "ms")
    }
    val family = names.canonicalFamily.lowercase(Locale.ROOT).replace(Regex("[\\s_-]+"), "")
    return family in setOf("roboto", "robotoflex", "robotocondensed", "notosans", "notoserif", "sansserif", "serif", "monospace",
        "droidsans", "droidserif", "droidsansmono", "misans", "misansvf", "milanting", "milantingpro", "milanspro",
        "harmonyossans", "harmonyossanssc", "honorsans", "samsungone", "arial", "timesnewroman", "couriernew",
        "microsoftyahei", "microsoftyaheiui", "kaiti", "simkai", "simhei", "simsun", "nsimsun", "stkaiti", "stsong", "stheiti") ||
        Regex("^(noto(sans|serif)cjk|sourcehan(sans|serif))(sc|tc|hk|jp|kr)?$").matches(family) ||
        names.family.any { Character.UnicodeScript.of(it.code) in setOf(Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL) }
}

/** App-lifetime cache of public API29 system fonts; no Activity, downloaded font or file scan. */
object SubtitleFontCatalog {
    private val defaults = listOf(
        SubtitleFont("subtitle-default", "字幕默认 · 系统无衬线", Typeface.create(Typeface.SANS_SERIF, 500, false)),
        SubtitleFont("system-default", "跟随系统", null),
    )
    private val mutable = MutableStateFlow(SubtitleFonts(loading = true, options = defaults))
    val state = mutable.asStateFlow()
    private val started = AtomicBoolean()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun request() {
        if (!started.compareAndSet(false, true)) return
        if (Build.VERSION.SDK_INT < 29) {
            mutable.value = SubtitleFonts(false, defaults + listOf(
                SubtitleFont("system-serif", "系统衬线", Typeface.SERIF),
                SubtitleFont("system-monospace", "系统等宽", Typeface.MONOSPACE),
            ))
            return
        }
        scope.launch {
            try {
                data class Candidate(val family: String, val group: Int, val distance: Int, val option: SubtitleFont)
                val fonts = SystemFonts.getAvailableFonts().mapNotNull { font ->
                    // File/index/style identifies a real face across process restarts.
                    val file = font.file ?: return@mapNotNull null
                    try {
                        val style = font.style
                        if (style.weight !in setOf(400, 500, 600, 700)) return@mapNotNull null
                        val names = subtitleFontNames(font.buffer, font.ttcIndex) ?: return@mapNotNull null
                        val locales = font.localeList.let { list -> (0 until list.size()).mapNotNull { list[it] } }
                        if (!subtitleTextFamily(names, locales)) return@mapNotNull null
                        val axes = font.axes?.sortedBy { it.tag }?.joinToString { "${it.tag}=${it.styleValue}" }.orEmpty()
                        val key = "system:${file.path}:${font.ttcIndex}:${style.weight}:${style.slant}:$axes"
                        val typeface = Typeface.CustomFallbackBuilder(FontFamily.Builder(font).build())
                            .setStyle(style).setSystemFallback("sans-serif").build()
                        val weight = when (style.weight) { 500 -> "中等"; 600 -> "半粗"; 700 -> "粗体"; else -> "常规" }
                        val label = "${names.family} · $weight" + if (style.slant != 0) "斜体" else ""
                        val bold = style.weight >= 600
                        Candidate(names.canonicalFamily.lowercase(Locale.ROOT).replace(Regex("[\\s_-]+"), ""),
                            (if (bold) 2 else 0) + (if (style.slant != 0) 1 else 0),
                            kotlin.math.abs(style.weight - if (bold) 700 else 400), SubtitleFont(key, label, typeface))
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                }.sortedWith(compareBy<Candidate> { it.distance }.thenBy { it.option.key })
                    .distinctBy { it.family to it.group }.map { it.option }.sortedBy { it.label }
                mutable.value = SubtitleFonts(false, defaults + fonts)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.value = SubtitleFonts(false, defaults, failed = true) }
        }
    }
}
