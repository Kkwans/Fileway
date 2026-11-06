package io.github.kkwans.nasfilebrowser.player

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.Call
import okhttp3.OkHttpClient

/** Tracks calls through body close, beyond OkHttp's async headers callback.
 * Cancellation also fences later retries from this factory. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class CancellableHttpSources(private val client: OkHttpClient,
    private val meter: TransferListener? = null) : DataSource.Factory {
    private val lock = Any()
    private var cancelled = false
    private val calls = mutableSetOf<Call>()
    override fun createDataSource(): HttpDataSource {
        val owned = mutableSetOf<Call>()
        val upstream = OkHttpDataSource.Factory { request ->
            synchronized(lock) {
                client.newCall(request).also {
                    calls += it; owned += it
                    if (cancelled) it.cancel()
                }
            }
        }.setTransferListener(meter).createDataSource()
        return object : HttpDataSource by upstream {
            override fun close() {
                try { upstream.close() }
                finally { synchronized(lock) { calls.removeAll(owned); owned.clear() } }
            }
        }
    }
    fun cancel() {
        val active = synchronized(lock) { cancelled = true; calls.toList() }
        active.forEach(Call::cancel)
    }
}
