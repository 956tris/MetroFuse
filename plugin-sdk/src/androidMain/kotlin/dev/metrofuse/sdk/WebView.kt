package dev.metrofuse.sdk

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Message
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
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
import dev.metrofuse.api.LoginUiContext
import dev.metrofuse.api.WebLoginRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
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
    var webView by remember(request) { mutableStateOf<WebView?>(null) }
    val popups = remember { mutableSetOf<WebView>() }
    var attempt by remember { mutableIntStateOf(0) }
    var pageFailed by remember { mutableStateOf(false) }
    LaunchedEffect(request, webView, attempt) {
        val view = webView ?: return@LaunchedEffect
        pageFailed = false
        try {
            val values =
                awaitBrowserCredentials {
                    val url = view.url
                    if (url == null || !request.acceptsBrowserNavigation(url) || URI(url).host != URI(request.cookieUrl).host) {
                        null
                    } else {
                        request.captureBrowserCookies(CookieManager.getInstance().getCookie(request.cookieUrl).orEmpty())
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
    DisposableEffect(request) {
        onDispose {
            popups.forEach { it.destroy() }
            popups.clear()
            webView?.apply {
                stopLoading()
                removeAllViews()
                destroy()
            }
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
                AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                    WebView(context).apply browser@{
                        webView = this
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        settings.setSupportMultipleWindows(true)
                        settings.userAgentString = WebSettings.getDefaultUserAgent(context).replace("; wv", "").replace(" Version/4.0", "")
                        CookieManager.getInstance().apply {
                            setAcceptCookie(true)
                            setAcceptThirdPartyCookies(this@browser, true)
                        }
                        webViewClient =
                            object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView,
                                    navigation: WebResourceRequest,
                                ) = request.blocksBrowserNavigation(navigation.url.toString(), navigation.isForMainFrame)

                                override fun onPageStarted(
                                    view: WebView,
                                    url: String?,
                                    favicon: Bitmap?,
                                ) {
                                    pageFailed = false
                                }

                                override fun onReceivedError(
                                    view: WebView,
                                    request: WebResourceRequest,
                                    error: WebResourceError,
                                ) {
                                    if (request.isForMainFrame) pageFailed = true
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
                                    popups += popup
                                    popup.webViewClient =
                                        object : WebViewClient() {
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
                if (context.busy) CircularProgressIndicator(Modifier.align(Alignment.Center))
                if (pageFailed || context.failed) {
                    Column(Modifier.align(Alignment.BottomCenter)) {
                        Text(labels.error)
                        TextButton(enabled = !context.busy, onClick = {
                            attempt++
                            webView?.loadUrl(request.url)
                        }) { Text(labels.retry) }
                    }
                }
            }
        }
    }
}
