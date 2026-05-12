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
import com.metrolist.music.constants.DeezerCookieKey
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.deezer.mergeDeezerCookieInputs
import com.metrolist.music.utils.rememberPreference

private const val DEEZER_LOGIN_URL = "https://www.deezer.com/login"
private const val DEEZER_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Mobile Safari/537.36"

private val DeezerCookieUrls =
    listOf(
        "https://www.deezer.com",
        "https://deezer.com",
        "https://auth.deezer.com",
        "https://connect.deezer.com",
    )

private val DeezerCookieCaptureDelaysMs = listOf(0L, 250L, 750L, 1_500L, 3_000L, 5_000L)

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DeezerLoginScreen(
    navController: NavController,
) {
    var deezerCookie by rememberPreference(DeezerCookieKey, "")
    var webView by remember { mutableStateOf<WebView?>(null) }
    var savedVisible by remember { mutableStateOf(false) }
    val handler = remember { Handler(Looper.getMainLooper()) }

    fun saveCookie(cookie: String) {
        deezerCookie = cookie
        savedVisible = true
    }

    fun captureNow() {
        webView?.captureDeezerCookie(::saveCookie)
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
                    settings.userAgentString = DEEZER_USER_AGENT
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webViewClient =
                        object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String?) {
                                super.onPageFinished(view, url)
                                CookieManager.getInstance().flush()
                                DeezerCookieCaptureDelaysMs.forEach { delay ->
                                    handler.postDelayed(
                                        {
                                            view.captureDeezerCookie(::saveCookie)
                                        },
                                        delay,
                                    )
                                }
                            }
                        }
                    loadUrl(DEEZER_LOGIN_URL)
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
                    text = stringResource(R.string.deezer_cookie_saved),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }

    TopAppBar(
        title = { Text(stringResource(R.string.deezer_web_login)) },
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

private fun WebView.captureDeezerCookie(onCookie: (String) -> Unit) {
    DeezerCookieUrls
        .mapNotNull { CookieManager.getInstance().getCookie(it) }
        .let(::mergeDeezerCookieInputs)
        ?.let(onCookie)
}
