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

private val SoundCloudAuthCaptureDelaysMs = listOf(0L, 250L, 750L, 1_500L, 3_000L, 5_000L)

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
    var webView by remember { mutableStateOf<WebView?>(null) }
    var savedVisible by remember { mutableStateOf(false) }
    val handler = remember { Handler(Looper.getMainLooper()) }

    fun saveToken(token: String) {
        soundCloudAuthToken = token
        savedVisible = true
    }

    fun captureNow() {
        webView?.captureSoundCloudAuth(
            onToken = ::saveToken,
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
                            override fun onPageFinished(view: WebView, url: String?) {
                                super.onPageFinished(view, url)
                                CookieManager.getInstance().flush()
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
