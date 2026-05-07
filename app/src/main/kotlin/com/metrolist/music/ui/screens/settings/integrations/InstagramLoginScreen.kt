/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings.integrations

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebViewClient
import android.webkit.WebView as AndroidWebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.google.accompanist.web.AccompanistWebViewClient
import com.google.accompanist.web.WebView
import com.google.accompanist.web.rememberWebViewNavigator
import com.google.accompanist.web.rememberWebViewState
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.constants.InstagramCookieKey
import com.metrolist.music.instagram.InstagramAudioProvider
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.instagram.mergeInstagramCookieInputs
import com.metrolist.music.utils.rememberPreference
import timber.log.Timber

private const val INSTAGRAM_LOGIN_URL = "https://www.instagram.com/accounts/login/?next=%2F&source=mobile_nav"
private const val INSTAGRAM_PLAIN_LOGIN_URL = "https://www.instagram.com/accounts/login/"
private const val INSTAGRAM_MOBILE_LOGIN_URL = "https://m.instagram.com/accounts/login/?next=%2F&source=mobile_nav"
private const val INSTAGRAM_MOBILE_PLAIN_LOGIN_URL = "https://m.instagram.com/accounts/login/"
private const val ECHO_ANDROID_LOGIN_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 2; Jeff Bezos) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/66.0.3359.158 Mobile Safari/537.36"
private const val MODERN_ANDROID_LOGIN_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 16; SM-F766B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Mobile Safari/537.36"

private data class InstagramLoginProfile(
    val url: String,
    val userAgent: String,
)

private fun instagramLoginProfiles(defaultUserAgent: String): List<InstagramLoginProfile> =
    listOf(
        InstagramLoginProfile(INSTAGRAM_PLAIN_LOGIN_URL, InstagramAudioProvider.WEB_LOGIN_USER_AGENT),
        InstagramLoginProfile(INSTAGRAM_LOGIN_URL, InstagramAudioProvider.WEB_LOGIN_USER_AGENT),
        InstagramLoginProfile(INSTAGRAM_LOGIN_URL, defaultUserAgent),
        InstagramLoginProfile(INSTAGRAM_PLAIN_LOGIN_URL, defaultUserAgent),
        InstagramLoginProfile(INSTAGRAM_MOBILE_LOGIN_URL, defaultUserAgent),
        InstagramLoginProfile(INSTAGRAM_MOBILE_PLAIN_LOGIN_URL, defaultUserAgent),
        InstagramLoginProfile(INSTAGRAM_LOGIN_URL, MODERN_ANDROID_LOGIN_USER_AGENT),
        InstagramLoginProfile(INSTAGRAM_LOGIN_URL, ECHO_ANDROID_LOGIN_USER_AGENT),
        InstagramLoginProfile(INSTAGRAM_MOBILE_LOGIN_URL, ECHO_ANDROID_LOGIN_USER_AGENT),
    )

private val InstagramCookieUrls =
    listOf(
        "https://www.instagram.com",
        "https://instagram.com",
        "https://i.instagram.com",
        "https://m.instagram.com",
    )

private val InstagramCookieCaptureDelaysMs = listOf(0L, 250L, 750L, 1_500L, 3_000L, 5_000L, 8_000L)

private val InstagramInitialLoginHeaders =
    mapOf(
        "User-Agent" to InstagramAudioProvider.WEB_LOGIN_USER_AGENT,
        "Accept-Language" to "en-US,en;q=0.9",
    )

private const val INSTAGRAM_COOKIE_READ_SCRIPT =
    """
    (function() {
        var rows = [];
        try {
            if (document.cookie) rows.push(document.cookie);
        } catch (error) {}

        function dumpStorage(label, storage) {
            try {
                for (var i = 0; i < storage.length; i++) {
                    var key = storage.key(i);
                    var value = storage.getItem(key);
                    if (key && value) {
                        rows.push(label + ':' + key + '=' + value);
                    }
                }
            } catch (error) {}
        }
        dumpStorage('localStorage', window.localStorage);
        dumpStorage('sessionStorage', window.sessionStorage);
        return rows.join('\n');
    })()
    """

private const val INSTAGRAM_LOGIN_VISIBLE_SCRIPT =
    """
    (function() {
        try {
            var nodes = document.querySelectorAll('input[name="username"], input[name="email"], input[name="password"], button, form');
            for (var i = 0; i < nodes.length; i++) {
                var node = nodes[i];
                var style = window.getComputedStyle(node);
                var rect = node.getBoundingClientRect();
                if (style && style.visibility !== 'hidden' && style.display !== 'none' && rect.width > 12 && rect.height > 8) {
                    return true;
                }
            }
            return false;
        } catch (error) {
            return false;
        }
    })()
    """

private fun isInstagramStopUrl(url: String?): Boolean =
    url?.let {
        it.equals("https://www.instagram.com/", ignoreCase = true) ||
            it.startsWith("https://www.instagram.com/?", ignoreCase = true) ||
            it.startsWith("https://www.instagram.com/accounts/onetap", ignoreCase = true) ||
            it.startsWith("https://www.instagram.com/direct/inbox", ignoreCase = true) ||
            it.startsWith("https://www.instagram.com/explore", ignoreCase = true) ||
            (
                it.startsWith("https://www.instagram.com/", ignoreCase = true) &&
                    !it.contains("/accounts/login", ignoreCase = true)
            )
    } == true

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun InstagramLoginScreen(
    navController: NavController,
) {
    var instagramCookie by rememberPreference(InstagramCookieKey, "")
    var webView by remember { mutableStateOf<AndroidWebView?>(null) }
    var savedVisible by remember { mutableStateOf(false) }
    var recoveryVisible by remember { mutableStateOf(false) }
    var loginProfileIndex by remember { mutableStateOf(0) }
    val handler = remember { Handler(Looper.getMainLooper()) }
    val webViewState =
        rememberWebViewState(
            url = INSTAGRAM_PLAIN_LOGIN_URL,
            additionalHttpHeaders = InstagramInitialLoginHeaders,
        )
    val webViewNavigator = rememberWebViewNavigator()

    fun saveCookie(cookie: String) {
        if (cookie != instagramCookie) {
            Timber.tag("InstagramLogin").d("Captured Instagram session cookie")
        }
        instagramCookie = cookie
        savedVisible = true
        recoveryVisible = false
    }

    fun captureNow() {
        webView?.captureInstagramCookies(onCookie = ::saveCookie)
    }

    fun scheduleCapture(showConfirmation: Boolean) {
        InstagramCookieCaptureDelaysMs.forEach { delay ->
            handler.postDelayed(
                {
                    webView?.captureInstagramCookies(
                        onCookie = { cookie ->
                            saveCookie(cookie)
                            if (!showConfirmation) savedVisible = false
                        },
                    )
                },
                delay,
            )
        }
    }

    fun hasCookie(): Boolean =
        mergeInstagramCookieInputs(
            InstagramCookieUrls.mapNotNull { CookieManager.getInstance().getCookie(it) },
        ) != null

    fun reloadLogin(
        view: AndroidWebView,
        clearSession: Boolean = false,
    ) {
        recoveryVisible = false
        if (clearSession) {
            view.clearInstagramWebSession()
        }
        val profiles = instagramLoginProfiles(WebSettings.getDefaultUserAgent(view.context))
        val profile = profiles[loginProfileIndex.coerceIn(0, profiles.lastIndex)]
        view.loadInstagramLogin(profile)
    }

    fun tryNextProfile(view: AndroidWebView) {
        val profiles = instagramLoginProfiles(WebSettings.getDefaultUserAgent(view.context))
        if (loginProfileIndex < profiles.lastIndex) {
            loginProfileIndex += 1
            Timber.tag("InstagramLogin").w("Instagram login looked blank; trying WebView profile $loginProfileIndex")
            reloadLogin(view)
        } else {
            recoveryVisible = true
        }
    }

    fun scheduleRecovery(
        view: AndroidWebView,
        profileIndex: Int,
    ) {
        handler.postDelayed(
            {
                if (profileIndex != loginProfileIndex) return@postDelayed
                val isInstagramPage = view.url?.contains("instagram.com", ignoreCase = true) == true
                if (!hasCookie() && isInstagramPage) {
                    view.evaluateJavascript(INSTAGRAM_LOGIN_VISIBLE_SCRIPT) { result ->
                        if (profileIndex != loginProfileIndex) return@evaluateJavascript
                        val loginVisible = result?.trim()?.equals("true", ignoreCase = true) == true
                        if (!loginVisible && !hasCookie()) {
                            tryNextProfile(view)
                        }
                    }
                }
            },
            6_000L,
        )
    }

    fun finish() {
        captureNow()
        navController.navigateUp()
    }

    BackHandler(onBack = ::finish)

    DisposableEffect(Unit) {
        onDispose {
            handler.removeCallbacksAndMessages(null)
            runCatching {
                webView?.stopLoading()
                webView?.destroy()
            }
        }
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(LocalPlayerAwareWindowInsets.current),
    ) {
        TopAppBar(
            title = { Text(stringResource(R.string.instagram_web_login)) },
            navigationIcon = {
                IconButton(
                    onClick = ::finish,
                    onLongClick = navController::backToMain,
                ) {
                    Icon(
                        painterResource(R.drawable.arrow_back),
                        contentDescription = null,
                    )
                }
            },
        )

        Box(modifier = Modifier.fillMaxSize()) {
            WebView(
                state = webViewState,
                modifier = Modifier.fillMaxSize(),
                captureBackPresses = false,
                navigator = webViewNavigator,
                onCreated = { view ->
                    webView = view
                    view.setBackgroundColor(Color.WHITE)
                    view.clearInstagramWebSession()
                    CookieManager.getInstance().setAcceptCookie(true)
                    runCatching {
                        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
                    }
                    view.setInitialScale(100)
                    view.settings.apply {
                        javaScriptEnabled = true
                        javaScriptCanOpenWindowsAutomatically = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        setSupportMultipleWindows(false)
                        setSupportZoom(true)
                        builtInZoomControls = true
                        displayZoomControls = false
                        useWideViewPort = false
                        loadWithOverviewMode = false
                        textZoom = 100
                        loadsImagesAutomatically = true
                        blockNetworkImage = false
                        blockNetworkLoads = false
                        cacheMode = WebSettings.LOAD_DEFAULT
                        mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        userAgentString = InstagramAudioProvider.WEB_LOGIN_USER_AGENT
                        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                            WebSettingsCompat.setForceDark(this, WebSettingsCompat.FORCE_DARK_OFF)
                        }
                        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                            WebSettingsCompat.setAlgorithmicDarkeningAllowed(this, false)
                        }
                    }
                    handler.post { reloadLogin(view, clearSession = false) }
                },
                client =
                    object : AccompanistWebViewClient() {
                        override fun onPageStarted(
                            view: AndroidWebView,
                            url: String?,
                            favicon: Bitmap?,
                        ) {
                            super.onPageStarted(view, url, favicon)
                            recoveryVisible = false
                            scheduleRecovery(view, loginProfileIndex)
                            if (isInstagramStopUrl(url)) {
                                scheduleCapture(showConfirmation = true)
                            }
                        }

                        override fun onPageFinished(
                            view: AndroidWebView,
                            url: String?,
                        ) {
                            super.onPageFinished(view, url)
                            CookieManager.getInstance().flush()
                            scheduleRecovery(view, loginProfileIndex)
                            scheduleCapture(showConfirmation = isInstagramStopUrl(url))
                        }

                        override fun shouldOverrideUrlLoading(
                            view: AndroidWebView,
                            request: WebResourceRequest,
                        ): Boolean {
                            if (isInstagramStopUrl(request.url.toString())) {
                                scheduleCapture(showConfirmation = true)
                            }
                            return false
                        }

                        override fun onReceivedError(
                            view: AndroidWebView,
                            request: WebResourceRequest?,
                            error: WebResourceError?,
                        ) {
                            super.onReceivedError(view, request, error)
                            if (request?.isForMainFrame == true) {
                                Timber.tag("InstagramLogin").w(
                                    "Instagram WebView main-frame error: ${error?.description}",
                                )
                                recoveryVisible = true
                            }
                        }
                    },
            )

            if (recoveryVisible) {
                Surface(
                    modifier =
                        Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                    shape = RoundedCornerShape(24.dp),
                    tonalElevation = 8.dp,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.instagram_webview_blank_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(R.string.instagram_webview_blank_desc),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Button(
                            onClick = {
                                recoveryVisible = false
                                webView?.let(::reloadLogin)
                            },
                        ) {
                            Text(stringResource(R.string.instagram_webview_retry))
                        }
                        OutlinedButton(
                            onClick = {
                                recoveryVisible = false
                                loginProfileIndex = 0
                                webView?.clearInstagramWebSession()
                                webView?.let(::reloadLogin)
                            },
                        ) {
                            Text(stringResource(R.string.instagram_webview_clear_cookies))
                        }
                    }
                }
            }

            if (savedVisible) {
                Surface(
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 24.dp),
                    shape = RoundedCornerShape(24.dp),
                    tonalElevation = 6.dp,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Text(
                        text = stringResource(R.string.instagram_cookie_saved),
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }
    }
}

private fun AndroidWebView.loadInstagramLogin(profile: InstagramLoginProfile) {
    settings.userAgentString = profile.userAgent
    loadUrl(
        profile.url,
        mapOf(
            "User-Agent" to profile.userAgent,
            "Accept-Language" to "en-US,en;q=0.9",
        ),
    )
}

private fun AndroidWebView.captureInstagramCookies(onCookie: (String) -> Unit) {
    val cookieInputs =
        InstagramCookieUrls
            .mapNotNull { CookieManager.getInstance().getCookie(it) }
    mergeInstagramCookieInputs(cookieInputs)?.let(onCookie)

    evaluateJavascript(INSTAGRAM_COOKIE_READ_SCRIPT) { result ->
        val storage = decodeJavascriptString(result)
        mergeInstagramCookieInputs(cookieInputs + listOfNotNull(storage))?.let(onCookie)
    }
}

private fun AndroidWebView.clearInstagramWebSession() {
    clearCache(true)
    WebStorage.getInstance().deleteAllData()
    CookieManager.getInstance().run {
        removeAllCookies(null)
        flush()
    }
}

private fun decodeJavascriptString(result: String?): String? =
    result
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it != "null" }
        ?.trim('"')
        ?.replace("\\\"", "\"")
        ?.replace("\\\\", "\\")
        ?.replace("\\n", "\n")
        ?.replace("\\r", "\r")
        ?.replace("\\u003d", "=")
        ?.replace("\\u003D", "=")
        ?.replace("\\u003b", ";")
        ?.replace("\\u003B", ";")
        ?.takeIf { it.isNotBlank() }
