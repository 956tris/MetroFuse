/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings.integrations

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.constants.SoundCloudAuthTokenKey
import com.metrolist.music.constants.SoundCloudSessionClientIdKey
import com.metrolist.music.soundcloud.SoundCloudAudioProvider
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.rememberPreference
import com.metrolist.music.utils.soundcloud.mergeSoundCloudAuthInputs
import timber.log.Timber

private const val SOUNDCLOUD_LOGIN_URL = "https://soundcloud.com/signin"

private val SoundCloudCookieUrls =
    listOf(
        "https://soundcloud.com",
        "https://www.soundcloud.com",
        "https://m.soundcloud.com",
        "https://secure.soundcloud.com",
        "https://api-v2.soundcloud.com",
    )

private val SoundCloudAuthCaptureDelaysMs = listOf(0L, 300L, 800L, 1_600L, 3_000L, 5_000L)

/**
 * Extracts the client_id that the SoundCloud web app loaded into its own JS
 * runtime. This is the user's session-bound client_id, which has permission to
 * access personalized endpoints (system-playlists, mixes, etc.) that a
 * scraped/proxy client_id does not.
 */
private const val SOUNDCLOUD_CLIENT_ID_READ_SCRIPT =
    """
    (function() {
        try {
            var cfg = window.__sc_hydration;
            if (cfg) {
                var s = JSON.stringify(cfg);
                var m = s.match(/client_?[Ii]d['":\s]+['"]([A-Za-z0-9]{16,})['"]/);
                if (m) return m[1];
            }
        } catch (e) {}
        try {
            if (window.SC && window.SC.__serverProperties) {
                var sp = window.SC.__serverProperties;
                if (sp.client_id) return sp.client_id;
            }
        } catch (e) {}
        try {
            if (window.SC && window.SC._config && window.SC._config.client_id) {
                return window.SC._config.client_id;
            }
        } catch (e) {}
        try {
            if (window.scHydrationPackages) {
                var s = JSON.stringify(window.scHydrationPackages);
                var m = s.match(/client_?[Ii]d['":\s]+['"]([A-Za-z0-9]{16,})['"]/);
                if (m) return m[1];
            }
        } catch (e) {}
        try {
            var scripts = document.getElementsByTagName('script');
            for (var i = scripts.length - 1; i >= 0; i--) {
                var t = scripts[i].textContent || '';
                var m = t.match(/client_?[Ii]d["']?\s*[:=]\s*["']([A-Za-z0-9]{16,})["']/i);
                if (m) return m[1];
            }
        } catch (e) {}
        return '';
    })()
    """

/**
 * Reads every localStorage + sessionStorage key plus any OAuth-shaped value we
 * can pull out of the runtime. SoundCloud's web app stashes the access token
 * under several possible keys (V1 and V2 SDK variants), so we sweep them all.
 */
private const val SOUNDCLOUD_STORAGE_READ_SCRIPT =
    """
    (function() {
        var rows = [];
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
        try {
            var sc = window.SC;
            if (sc && sc.connector && typeof sc.connector.oauth_token === 'string') {
                rows.push('runtime:oauth_token=' + sc.connector.oauth_token);
            }
        } catch (error) {}
        try {
            var accessToken = window.__sc_token;
            if (typeof accessToken === 'string') rows.push('runtime:access_token=' + accessToken);
        } catch (error) {}
        return rows.join('\n');
    })()
    """

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SoundCloudLoginScreen(
    navController: NavController,
) {
    var soundCloudAuthToken by rememberPreference(SoundCloudAuthTokenKey, "")
    var sessionClientId by rememberPreference(SoundCloudSessionClientIdKey, "")
    var webView by remember { mutableStateOf<WebView?>(null) }
    var savedVisible by remember { mutableStateOf(false) }
    val handler = remember { Handler(Looper.getMainLooper()) }

    fun saveToken(token: String) {
        soundCloudAuthToken = token
        // A new auth token means personalized endpoints open up; drop any cached
        // client id so the first authenticated call resolves cleanly.
        SoundCloudAudioProvider.invalidateClientId()
        savedVisible = true
    }

    fun captureNow() {
        webView?.captureSoundCloudAuth(
            onToken = ::saveToken,
        )
        webView?.captureSessionClientId { id ->
            if (id.isNotBlank()) {
                sessionClientId = id
                Timber.tag("SoundCloudLogin").d("Captured session client_id: $id")
            }
        }
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

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(LocalPlayerAwareWindowInsets.current),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    webView = this
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = SoundCloudAudioProvider.BROWSER_USER_AGENT
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webViewClient =
                        object : WebViewClient() {
                            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                url?.let { extractTokenFromUrl(it) }?.let(::saveToken)
                                handler.postDelayed({ view.captureSoundCloudAuth(onToken = ::saveToken) }, 150L)
                                handler.postDelayed({
                                    view.captureSessionClientId { id ->
                                        if (id.isNotBlank()) sessionClientId = id
                                    }
                                }, 200L)
                            }

                            override fun onPageFinished(view: WebView, url: String?) {
                                super.onPageFinished(view, url)
                                CookieManager.getInstance().flush()
                                url?.let { extractTokenFromUrl(it) }?.let(::saveToken)
                                SoundCloudAuthCaptureDelaysMs.forEach { delay ->
                                    handler.postDelayed(
                                        {
                                            view.captureSoundCloudAuth(
                                                onToken = ::saveToken,
                                            )
                                        },
                                        delay,
                                    )
                                }
                                // Capture session client_id on each page finish too
                                view.captureSessionClientId { id ->
                                    if (id.isNotBlank()) sessionClientId = id
                                }
                            }
                        }
                    loadUrl(SOUNDCLOUD_LOGIN_URL)
                }
            },
            update = {},
        )

        AnimatedVisibility(
            visible = savedVisible,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                tonalElevation = 6.dp,
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Text(
                    text = stringResource(R.string.soundcloud_auth_saved),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }

    TopAppBar(
        title = { Text(stringResource(R.string.soundcloud_web_login)) },
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
}

private fun WebView.captureSoundCloudAuth(onToken: (String) -> Unit) {
    val cookieInputs = SoundCloudCookieUrls
        .mapNotNull { CookieManager.getInstance().getCookie(it) }
    mergeSoundCloudAuthInputs(cookieInputs)?.let(onToken)

    evaluateJavascript(SOUNDCLOUD_STORAGE_READ_SCRIPT) { result ->
        val storage = decodeJavascriptString(result)
        if (storage.isNullOrBlank()) return@evaluateJavascript
        mergeSoundCloudAuthInputs(cookieInputs + storage)?.let(onToken)
    }
}

/**
 * Pulls an OAuth token straight out of a redirect URL's fragment or query —
 * SoundCloud's OAuth2 flow can return `access_token=...` directly in the URL
 * on some login paths. Faster than waiting for cookies/storage to populate.
 */
private fun extractTokenFromUrl(url: String): String? {
    if (url.isBlank()) return null
    val fragment = url.substringAfter('#', missingDelimiterValue = "")
    val candidates = buildList {
        add(url)
        if (fragment.isNotBlank()) {
            add("oauth_token=$fragment")
            add("access_token=$fragment")
        }
    }
    return mergeSoundCloudAuthInputs(candidates)
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
        ?.also { Timber.tag("SoundCloudLogin").d("Captured SoundCloud storage payload") }

/** Runs the client_id extraction JS and calls back with any 16+ char result. */
private fun WebView.captureSessionClientId(onClientId: (String) -> Unit) {
    evaluateJavascript(SOUNDCLOUD_CLIENT_ID_READ_SCRIPT) { result ->
        val id = result?.trim()?.trim('"')?.takeIf { it.length >= 16 && it != "null" }
        if (!id.isNullOrBlank()) onClientId(id)
    }
}
