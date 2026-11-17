package dev.metrofuse.sdk

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Message
import android.util.Log
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dev.metrofuse.api.LoginUiContext
import dev.metrofuse.api.WebLoginRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import java.net.URI

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
actual fun WebViewLoginDialog(
    request: WebLoginRequest,
    labels: WebViewLabels,
    context: LoginUiContext,
) {
    val onCredentials = context.submit
    val onDismiss = context.dismiss
    val currentSubmit by rememberUpdatedState(onCredentials)
    var attempt by remember { mutableIntStateOf(0) }
    var webView by remember(request, attempt) { mutableStateOf<WebView?>(null) }
    val popups = remember(request, attempt) { mutableSetOf<WebView>() }
    var pageFailed by remember(request, attempt) { mutableStateOf(false) }
    var pageVisible by remember(request, attempt) { mutableStateOf(false) }
    var rendererGone by remember(request, attempt) { mutableStateOf(false) }
    val profileName = "metrofuse-login-v1-${URI(request.cookieUrl).host}"
    val isolated = remember { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) }

    fun cookies(view: WebView) = if (isolated) WebViewCompat.getProfile(view).cookieManager else CookieManager.getInstance()
    LaunchedEffect(request, attempt) {
        delay(15_000)
        if (!pageVisible) pageFailed = true
    }
    LaunchedEffect(request, webView, attempt) {
        val view = webView ?: return@LaunchedEffect
        try {
            val values =
                awaitBrowserCredentials {
                    val url = view.url
                    if (!request.canCaptureBrowserCookies(url)) {
                        null
                    } else {
                        request.captureBrowserCookies(cookies(view).getCookie(request.cookieUrl).orEmpty())
                    }
                }
            currentSubmit(values)
        } catch (error: CancellationException) {
            if (error !is TimeoutCancellationException) throw error
            pageFailed = true
        } catch (_: Exception) {
            pageFailed = true
        }
    }
    DisposableEffect(popups) {
        onDispose {
            popups.forEach { it.destroy() }
            popups.clear()
        }
    }
    BackHandler {
        val view = webView
        if (view?.canGoBack() == true) view.goBack() else onDismiss()
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, securePolicy = SecureFlagPolicy.SecureOn),
    ) {
        Scaffold(topBar = {
            TopAppBar(title = { Text(labels.title) }, navigationIcon = {
                TextButton(onClick = onDismiss) { Text(labels.cancel) }
            })
        }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                if (!rendererGone) {
                    key(request, attempt) {
                        AndroidView(modifier = Modifier.fillMaxSize(), onReset = null, onRelease = { view ->
                            if (webView === view) webView = null
                            if (!rendererGone) view.stopLoading()
                            view.destroy()
                        }, factory = { context ->
                            WebView(context).apply browser@{
                                if (isolated) WebViewCompat.setProfile(this, profileName)
                                webView = this
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.allowFileAccess = false
                                settings.allowContentAccess = false
                                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                if (attempt > 0) settings.cacheMode = WebSettings.LOAD_NO_CACHE
                                settings.setSupportMultipleWindows(true)
                                settings.userAgentString =
                                    WebSettings.getDefaultUserAgent(context).replace("; wv", "").replace(" Version/4.0", "")
                                cookies(this).apply {
                                    setAcceptCookie(true)
                                    setAcceptThirdPartyCookies(this@browser, true)
                                }
                                webViewClient =
                                    object : WebViewClient() {
                                        override fun shouldOverrideUrlLoading(
                                            view: WebView,
                                            navigation: WebResourceRequest,
                                        ): Boolean {
                                            val blocked =
                                                request.blocksBrowserNavigation(
                                                    navigation.url.toString(),
                                                    navigation.isForMainFrame,
                                                )
                                            if (blocked) {
                                                Log.w("MetroPluginLogin", "Blocked navigation host=${navigation.url.host}")
                                                pageFailed = true
                                            }
                                            return blocked
                                        }

                                        override fun onPageStarted(
                                            view: WebView,
                                            url: String?,
                                            favicon: Bitmap?,
                                        ) {
                                            pageFailed = false
                                        }

                                        override fun onPageCommitVisible(
                                            view: WebView,
                                            url: String?,
                                        ) {
                                            pageVisible = true
                                        }

                                        override fun onReceivedError(
                                            view: WebView,
                                            request: WebResourceRequest,
                                            error: WebResourceError,
                                        ) {
                                            if (request.isForMainFrame) {
                                                Log.w("MetroPluginLogin", "Page error code=${error.errorCode} host=${request.url.host}")
                                                pageFailed = true
                                            }
                                        }

                                        override fun onReceivedHttpError(
                                            view: WebView,
                                            request: WebResourceRequest,
                                            errorResponse: WebResourceResponse,
                                        ) {
                                            if (request.isForMainFrame) {
                                                Log.w("MetroPluginLogin", "HTTP ${errorResponse.statusCode} host=${request.url.host}")
                                                pageFailed = true
                                            }
                                        }

                                        override fun onRenderProcessGone(
                                            view: WebView,
                                            detail: RenderProcessGoneDetail,
                                        ): Boolean {
                                            if (webView === view) webView = null
                                            rendererGone = true
                                            pageFailed = true
                                            return true
                                        }
                                    }
                                webChromeClient =
                                    object : WebChromeClient() {
                                        override fun onCreateWindow(
                                            view: WebView,
                                            isDialog: Boolean,
                                            isUserGesture: Boolean,
                                            resultMsg: Message,
                                        ): Boolean {
                                            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                                            val popup = WebView(view.context)
                                            if (isolated) WebViewCompat.setProfile(popup, profileName)
                                            popups += popup
                                            popup.webViewClient =
                                                object : WebViewClient() {
                                                    override fun onRenderProcessGone(
                                                        view: WebView,
                                                        detail: RenderProcessGoneDetail,
                                                    ): Boolean {
                                                        popups -= view
                                                        view.destroy()
                                                        pageFailed = true
                                                        return true
                                                    }

                                                    override fun onPageStarted(
                                                        popupView: WebView,
                                                        url: String?,
                                                        favicon: Bitmap?,
                                                    ) {
                                                        if (url == null || url == "about:blank") return
                                                        if (request.acceptsBrowserNavigation(url)) view.loadUrl(url)
                                                        popups -= popupView
                                                        popupView.destroy()
                                                    }
                                                }
                                            transport.webView = popup
                                            resultMsg.sendToTarget()
                                            return true
                                        }
                                    }
                                loadUrl(request.url)
                            }
                        })
                    }
                }
                if (context.busy || (!pageVisible && !pageFailed)) CircularProgressIndicator(Modifier.align(Alignment.Center))
                if (pageFailed || context.failed) {
                    Column(Modifier.align(Alignment.BottomCenter)) {
                        Text(labels.error)
                        TextButton(enabled = !context.busy, onClick = {
                            attempt++
                        }) { Text(labels.retry) }
                    }
                }
            }
        }
    }
}
