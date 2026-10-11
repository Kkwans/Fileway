package io.github.kkwans.nasfilebrowser.ui

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import io.github.kkwans.nasfilebrowser.data.MarkdownMathBudget
import io.github.kkwans.nasfilebrowser.data.MarkdownMathFormula
import io.github.kkwans.nasfilebrowser.data.MarkdownMathMetrics
import io.github.kkwans.nasfilebrowser.data.MarkdownMathResult
import java.io.ByteArrayInputStream
import java.io.Closeable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One use, one document generation, one isolated TeX macro namespace. All View
 * operations run on main. No Activity, network, JS bridge or product model. */
internal class MarkdownMathSession(private val context: Context) : Closeable {
    private var view: WebView? = null
    private var used = false
    private var closed = false
    private var generation = 0
    private var pendingEvaluation: CancellableContinuation<String>? = null

    suspend fun render(formulas: List<MarkdownMathFormula>, metrics: MarkdownMathMetrics): Map<String, MarkdownMathResult> =
        withContext(Dispatchers.Main.immediate) {
            check(!used && !closed)
            used = true
            val ordered = try { MarkdownMathBudget.ordered(formulas) }
            catch (_: IllegalArgumentException) {
                return@withContext formulas.associate { it.key to MarkdownMathResult.Failed(MarkdownMathBudget.TOO_LARGE) }
            }
            if (ordered.isEmpty()) return@withContext emptyMap()
            if (!metrics.isValid()) return@withContext ordered.associate { it.key to MarkdownMathResult.Failed(MarkdownMathBudget.INVALID) }
            val ownGeneration = ++generation
            val results = linkedMapOf<String, MarkdownMathResult>()
            try {
                // A whole-document elapsed-time budget, never reset per expression.
                withTimeout(30_000) {
                    val web = createView()
                    view = web
                    web.loadUrl("$ORIGIN/index.html")
                    awaitState(web, ownGeneration, setOf("ready", "unavailable"))
                        .also { check(it.optString("kind") == "ready") }
                    var retained = 0L
                    for (formula in ordered) {
                        currentCoroutineContext().ensureActive()
                        checkCurrent(web, ownGeneration)
                        val json = JSONObject().put("emPx", metrics.emPx).put("exPx", metrics.exPx)
                            .put("containerWidthPx", metrics.containerWidthPx).put("foregroundRgb", metrics.foregroundRgb)
                        val accepted = evaluate(web, "window.NfbMath.begin(${JSONObject.quote(formula.tex)},${formula.display},$json)", ownGeneration)
                        check(accepted == "true")
                        val state = awaitState(web, ownGeneration, setOf("rendered", "failed", "unavailable"))
                        val result = if (state.optString("kind") == "rendered") {
                            val rendered = MarkdownMathResult.Rendered(state.getString("svg"), state.getDouble("widthPx").toFloat(),
                                state.getDouble("heightPx").toFloat(), state.getDouble("ascentPx").toFloat(), state.getDouble("depthPx").toFloat())
                            retained += rendered.svg.toByteArray(Charsets.UTF_8).size
                            if (MarkdownMathBudget.outputValid(rendered) && retained <= MarkdownMathBudget.DOCUMENT_SVG_BYTES) rendered
                            else MarkdownMathResult.Failed(MarkdownMathBudget.TOO_LARGE)
                        } else MarkdownMathResult.Failed(MarkdownMathBudget.INVALID)
                        results[formula.key] = result
                        evaluate(web, "window.NfbMath.consume()", ownGeneration)
                    }
                }
            } catch (cancelled: CancellationException) {
                // A parent cancellation must not publish stale results. An internal
                // timeout may become a visible error only while the parent is live.
                currentCoroutineContext().ensureActive()
                if (cancelled !is kotlinx.coroutines.TimeoutCancellationException) throw cancelled
            } catch (_: Exception) {
                // Error details can contain user TeX. Only a fixed message escapes.
            } finally {
                withContext(NonCancellable + Dispatchers.Main.immediate) { close() }
            }
            currentCoroutineContext().ensureActive()
            ordered.forEach { results.putIfAbsent(it.key, MarkdownMathResult.Failed(MarkdownMathBudget.UNAVAILABLE)) }
            results
        }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createView(): WebView = WebView(context.applicationContext).apply {
        settings.javaScriptEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.domStorageEnabled = false
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(false)
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.blockNetworkLoads = true
        isFocusable = false
        isClickable = false
        webViewClient = object : WebViewClient() {
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                // Handle only this owned view. Do not terminate shared renderers
                // or change any global browser/user state.
                close()
                return true
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                val uri = request.url
                val relative = uri.path?.removePrefix("/markdown-math/")
                if (request.method != "GET" || uri.scheme != "https" || uri.host != "appassets.androidplatform.net" ||
                    uri.port != -1 || uri.encodedQuery != null || uri.fragment != null ||
                    uri.path?.startsWith("/markdown-math/") != true || relative.isNullOrBlank() ||
                    relative.split('/').any { it == ".." || it == "." } || relative.contains('\\') ||
                    !(relative.endsWith(".js") || relative == "index.html")) return denied()
                return try {
                    WebResourceResponse(if (relative.endsWith(".js")) "application/javascript" else "text/html", "UTF-8",
                        200, "OK", mapOf("Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff"),
                        context.assets.open("markdown-math/$relative"))
                } catch (_: Exception) { denied() }
            }
        }
    }

    private fun denied() = WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    private fun checkCurrent(web: WebView, ownGeneration: Int) {
        if (closed || ownGeneration != generation || view !== web) throw CancellationException("Math session closed")
    }

    private suspend fun evaluate(web: WebView, script: String, ownGeneration: Int): String = suspendCancellableCoroutine { continuation ->
        checkCurrent(web, ownGeneration)
        pendingEvaluation = continuation
        web.evaluateJavascript(script) { result ->
            if (pendingEvaluation === continuation) pendingEvaluation = null
            if (continuation.isActive && !closed && ownGeneration == generation && view === web)
                continuation.resume(result ?: "null")
        }
    }

    private suspend fun awaitState(web: WebView, ownGeneration: Int, terminal: Set<String>): JSONObject {
        while (true) {
            currentCoroutineContext().ensureActive()
            val raw = evaluate(web, "JSON.stringify(window.NfbMath ? window.NfbMath.read() : {kind:'loading'})", ownGeneration)
            // evaluateJavascript returns a JSON string containing the JSON result.
            check(raw.length <= MarkdownMathBudget.SVG_BYTES * 8 + 4096)
            val decoded = JSONTokener(raw).nextValue() as? String
            val state = if (decoded == null) JSONObject().put("kind", "loading") else JSONObject(decoded)
            if (state.optString("kind") in terminal) return state
            delay(16)
        }
    }

    override fun close() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (closed) return
        closed = true
        generation++
        pendingEvaluation?.takeIf { it.isActive }?.resumeWithException(IllegalStateException("Math component unavailable"))
        pendingEvaluation = null
        view?.let { it.stopLoading(); it.destroy() }
        view = null
    }

    companion object { private const val ORIGIN = "https://appassets.androidplatform.net/markdown-math" }
}

@Composable
internal fun rememberMarkdownMathResults(documentKey: Any, formulas: List<MarkdownMathFormula>, metrics: MarkdownMathMetrics): Map<String, MarkdownMathResult> =
    key(documentKey, formulas, metrics) {
        val context = LocalContext.current
        val results by produceState<Map<String, MarkdownMathResult>>(emptyMap(), documentKey, formulas, metrics) {
            val session = MarkdownMathSession(context)
            try { value = session.render(formulas, metrics) }
            finally { withContext(NonCancellable + Dispatchers.Main.immediate) { session.close() } }
        }
        results
    }
