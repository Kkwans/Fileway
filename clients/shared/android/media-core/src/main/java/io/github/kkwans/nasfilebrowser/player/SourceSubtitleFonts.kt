package io.github.kkwans.nasfilebrowser.player

import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class SourceSubtitleFont(val id: String, val name: String)

/** Two bounded disk downloads, one registration/ByteArray at a time. A failed
 * font cannot cancel its siblings or the ASS file. The source/controller owns
 * leases, identity checks and the accepted-font set. Every prepared file is
 * released here, including a result lost to cancellation before await. */
object SourceSubtitleFonts {
    suspend fun load(fonts: List<SourceSubtitleFont>,
        prepare: suspend (SourceSubtitleFont) -> File,
        register: suspend (SourceSubtitleFont, File) -> Boolean,
        timeoutMs: Long = 20_000): Boolean {
        require(fonts.size <= 64 && timeoutMs > 0)
        val registration = Mutex()
        return withTimeoutOrNull(timeoutMs) {
            var all = true
            for (batch in fonts.chunked(2)) {
                val results = coroutineScope {
                    batch.map { font -> async {
                        var prepared: File? = null
                        try {
                            require(font.id.isNotBlank() && font.name.isNotBlank() && font.name.length <= 256)
                            prepared = prepare(font)
                            registration.withLock { register(font, checkNotNull(prepared)) }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { false }
                        finally { withContext(NonCancellable + Dispatchers.IO) { prepared?.delete() } }
                    } }.awaitAll()
                }
                if (results.any { !it }) all = false
            }
            all
        } ?: false
    }
}
