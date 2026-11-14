package com.metrolist.android.ui.screens

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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.metrolist.android.playlistimport.ProviderLoginWindow
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.back
import com.metrolist.shared.generated.resources.login
import com.metrolist.shared.generated.resources.plugin_login_failed
import com.metrolist.shared.generated.resources.try_again
import com.metrolist.shared.playlistimport.awaitProviderCredentials
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.utils.BlockScreenshots
import dev.metrofuse.runtime.PluginAccountState
import dev.metrofuse.runtime.PluginLoginPrompt
import dev.metrofuse.runtime.acceptsNavigation
import dev.metrofuse.runtime.captureCookies
import kotlinx.coroutines.CancellationException
import org.jetbrains.compose.resources.stringResource
import java.net.URI

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginWebLoginScreen(
    prompt: PluginLoginPrompt,
    account: PluginAccountState,
    onCredentials: (Map<String, String>) -> Unit,
    onDismiss: () -> Unit,
) {
    BlockScreenshots()
    val request = checkNotNull(prompt.web)
    val currentSubmit by rememberUpdatedState(onCredentials)
    var webView by remember(prompt.token) { mutableStateOf<WebView?>(null) }
    val popups = remember { mutableSetOf<WebView>() }
    var attempt by remember { mutableIntStateOf(0) }
    var pageFailed by remember { mutableStateOf(false) }
    LaunchedEffect(prompt.token, webView, attempt) {
        val view = webView ?: return@LaunchedEffect
        pageFailed = false
        try {
            val values =
                awaitProviderCredentials {
                    val url = view.url
                    if (url == null || !request.acceptsNavigation(url) || URI(url).host != URI(request.cookieUrl).host) {
                        null
                    } else {
                        request.captureCookies(CookieManager.getInstance().getCookie(request.cookieUrl).orEmpty())
                    }
                }
            currentSubmit(values)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            pageFailed = true
        }
    }
    DisposableEffect(prompt.token) {
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
    ProviderLoginWindow(onDismissRequest = onDismiss) {
        Scaffold(topBar = {
            TopAppBar(title = { Text(stringResource(Res.string.login)) }, navigationIcon = {
                IconButton(onClick = onDismiss) { Icon(MetrolistIcons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.back)) }
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
                                ) = !request.acceptsNavigation(navigation.url.toString())

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
                                                if (request.acceptsNavigation(url)) view.loadUrl(url)
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
                if (account.busy) CircularProgressIndicator(Modifier.align(Alignment.Center))
                if (pageFailed || account.error != null) {
                    Column(Modifier.align(Alignment.BottomCenter)) {
                        Text(stringResource(Res.string.plugin_login_failed))
                        TextButton(enabled = !account.busy, onClick = {
                            attempt++
                            webView?.loadUrl(request.url)
                        }) { Text(stringResource(Res.string.try_again)) }
                    }
                }
            }
        }
    }
}
