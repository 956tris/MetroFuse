package com.metrolist.shared.api.lastfm

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.metrolist.shared.youtube.playback.AndroidContextProvider
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.seconds

object LastFmGalleryBrowser {
    private val mutex = Mutex()

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun load(url: String): String? {
        require(isLastFmUrl(url))
        return withTimeoutOrNull(20.seconds) {
            mutex.withLock {
                withContext(Dispatchers.Main.immediate) {
                    val view =
                        runCatching { WebView(AndroidContextProvider.applicationContext) }.getOrNull()
                            ?: return@withContext null
                    val activeContinuation = AtomicReference<CancellableContinuation<String>?>(null)
                    var failed = false
                    try {
                        view.settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            allowFileAccess = false
                            allowContentAccess = false
                            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            javaScriptCanOpenWindowsAutomatically = false
                        }
                        view.webViewClient =
                            object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView,
                                    request: WebResourceRequest,
                                ): Boolean = !isLastFmUrl(request.url.toString())

                                override fun onReceivedError(
                                    view: WebView,
                                    request: WebResourceRequest,
                                    error: WebResourceError,
                                ) {
                                    if (request.isForMainFrame) failed = true
                                }

                                override fun onRenderProcessGone(
                                    view: WebView,
                                    detail: RenderProcessGoneDetail,
                                ): Boolean {
                                    failed = true
                                    activeContinuation.getAndSet(null)?.let { cont ->
                                        if (cont.isActive) cont.resume("null")
                                    }
                                    return true
                                }
                            }
                        view.loadUrl(url)
                        var html: String? = null
                        while (!failed && html == null) {
                            val result =
                                suspendCancellableCoroutine { continuation ->
                                    activeContinuation.set(continuation)
                                    continuation.invokeOnCancellation {
                                        activeContinuation.compareAndSet(continuation, null)
                                    }
                                    view.evaluateJavascript(
                                        """
                                        (() => {
                                            // A new WebView reports about:blank, already complete, until navigation starts.
                                            if (location.hostname !== 'www.last.fm') return null;
                                            const ready = location.pathname.endsWith('/+images')
                                                ? document.querySelector('.image-list')
                                                : document.readyState === 'complete';
                                            if (document.title === 'Client Challenge' || !ready) return null;
                                            const html = document.documentElement.outerHTML;
                                            return html.length <= 2097152 ? html : null;
                                        })()
                                        """.trimIndent(),
                                    ) { value ->
                                        activeContinuation.compareAndSet(continuation, null)
                                        if (continuation.isActive) continuation.resume(value)
                                    }
                                }
                            html = runCatching { Json.decodeFromString<String>(result) }.getOrNull()
                            if (html == null && !failed) delay(100)
                        }
                        html
                    } finally {
                        activeContinuation.getAndSet(null)
                        withContext(NonCancellable + Dispatchers.Main.immediate) {
                            view.stopLoading()
                            view.destroy()
                        }
                    }
                }
            }
        }
    }

    private fun isLastFmUrl(url: String): Boolean = Uri.parse(url).let { it.scheme == "https" && it.host == "www.last.fm" }
}
