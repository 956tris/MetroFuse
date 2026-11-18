package dev.metrofuse.sdk

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Message
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dev.metrofuse.api.LoginUiContext
import dev.metrofuse.api.WebLoginRequest
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import android.app.Dialog as NativeDialog

private data class NativeLoginViews(
    val footer: ComposeView,
)

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
    val currentDismiss by rememberUpdatedState(onDismiss)
    val currentBusy by rememberUpdatedState(context.busy)
    val currentLabels by rememberUpdatedState(labels)
    val currentContext by rememberUpdatedState(context)
    val parentView = LocalView.current
    val parentComposition = rememberCompositionContext()
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val shapes = MaterialTheme.shapes
    val androidContext = LocalContext.current
    var attempt by remember { mutableIntStateOf(0) }
    var nativeViews by remember(request, attempt) { mutableStateOf<NativeLoginViews?>(null) }
    var webView by remember(request, attempt) { mutableStateOf<WebView?>(null) }
    val popups = remember(request, attempt) { mutableSetOf<WebView>() }
    var pageFailed by remember(request, attempt) { mutableStateOf(false) }
    var pageVisible by remember(request, attempt) { mutableStateOf(false) }
    var pageLoading by remember(request, attempt) { mutableStateOf(true) }
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
            request.monitorBrowserCredentials(
                capture = {
                    val url = view.url
                    if (!request.canCaptureBrowserCookies(url)) {
                        null
                    } else {
                        cookies(view).getCookie(request.cookieUrl)
                    }
                },
                isBusy = { currentBusy },
                isLoggedIn = { currentContext.account.loggedIn },
                submit = { values ->
                    Log.i("MetroPluginLogin", "Session cookies captured; submitting for account verification")
                    currentSubmit(values)
                },
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            pageFailed = true
        }
    }
    LaunchedEffect(context.failed) {
        if (context.failed) Log.w("MetroPluginLogin", "Account verification failed; watching for an updated browser session")
    }
    DisposableEffect(popups) {
        onDispose {
            popups.forEach { it.destroy() }
            popups.clear()
        }
    }
    // Keep the browser in the native window hierarchy, outside Compose's dialog compositor.
    DisposableEffect(request, attempt) {
        val nativeContext = ContextThemeWrapper(androidContext, android.R.style.Theme_DeviceDefault_NoActionBar)
        val dialog = NativeDialog(nativeContext)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnCancelListener { currentDismiss() }
        val root = LinearLayout(nativeContext).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colors.surface.toArgb())
            setViewTreeLifecycleOwner(parentView.findViewTreeLifecycleOwner())
            setViewTreeSavedStateRegistryOwner(parentView.findViewTreeSavedStateRegistryOwner())
        }
        fun composeChrome(content: @Composable () -> Unit) =
            ComposeView(nativeContext).apply {
                id = View.generateViewId()
                setParentCompositionContext(parentComposition)
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
                setContent {
                    MaterialTheme(colorScheme = colors, typography = typography, shapes = shapes) { content() }
                }
            }
        val header = composeChrome {
            TopAppBar(
                title = { Text(currentLabels.title) },
                navigationIcon = {
                    TextButton(onClick = currentDismiss) { Text(currentLabels.cancel) }
                },
                actions = {
                    if (currentContext.busy || (pageLoading && !pageFailed)) {
                        CircularProgressIndicator(Modifier.padding(end = 16.dp).size(20.dp), strokeWidth = 2.dp)
                    }
                },
            )
        }
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val browserFrame = FrameLayout(nativeContext)
        root.addView(browserFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val browser = WebView(nativeContext).apply browser@{
            layoutParams =
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
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
                WebSettings.getDefaultUserAgent(nativeContext).replace("; wv", "").replace(" Version/4.0", "")
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
                        pageLoading = true
                    }

                    override fun onPageCommitVisible(
                        view: WebView,
                        url: String?,
                    ) {
                        pageVisible = true
                        pageLoading = false
                    }

                    override fun onPageFinished(
                        view: WebView,
                        url: String?,
                    ) {
                        pageVisible = true
                        pageLoading = false
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
                        browserFrame.removeView(view)
                        view.destroy()
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
        }
        browserFrame.addView(browser, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val footer = composeChrome {
            Column(Modifier.padding(16.dp)) {
                Text(currentLabels.error)
                TextButton(enabled = !currentBusy, onClick = { attempt++ }) { Text(currentLabels.retry) }
            }
        }.apply {
            visibility = if (pageFailed || currentContext.failed) View.VISIBLE else View.GONE
        }
        root.addView(footer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        dialog.setContentView(root)
        dialog.window?.apply {
            addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) {
                    if (!rendererGone && browser.canGoBack()) browser.goBack() else currentDismiss()
                }
                true
            } else false
        }
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        val views = NativeLoginViews(footer)
        nativeViews = views
        browser.post { if (webView === browser && !rendererGone) browser.loadUrl(request.url) }
        onDispose {
            if (nativeViews === views) nativeViews = null
            if (webView === browser) webView = null
            dialog.setOnCancelListener(null)
            dialog.dismiss()
            header.disposeComposition()
            footer.disposeComposition()
            browserFrame.removeAllViews()
            if (!rendererGone) {
                browser.stopLoading()
                browser.destroy()
            }
        }
    }
    SideEffect {
        nativeViews?.footer?.visibility = if (pageFailed || context.failed) View.VISIBLE else View.GONE
    }
}
