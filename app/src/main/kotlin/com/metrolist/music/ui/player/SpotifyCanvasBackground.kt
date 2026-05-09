/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.player

import android.view.TextureView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.utils.spotify.SpotifyCanvasClient
import com.metrolist.music.utils.spotify.SpotifyCanvasMedia
import com.metrolist.music.utils.spotify.normalizeSpotifyCookieInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

private const val CanvasCrossfadeDurationMillis = 650

private data class SpotifyCanvasVideoKey(
    val url: String,
    val headers: Map<String, String>,
)

@Composable
fun rememberSpotifyCanvasMedia(
    mediaMetadata: MediaMetadata?,
    enabled: Boolean,
    cookie: String,
    shouldLoad: Boolean,
): SpotifyCanvasMedia? {
    val normalizedCookie = normalizeSpotifyCookieInput(cookie)
    val canvasMedia by produceState<SpotifyCanvasMedia?>(
        initialValue = null,
        mediaMetadata,
        enabled,
        normalizedCookie,
        shouldLoad,
    ) {
        if (!enabled || !shouldLoad || mediaMetadata == null || normalizedCookie == null) {
            value = null
            return@produceState
        }

        value =
            withContext(Dispatchers.IO) {
                runCatching {
                    SpotifyCanvasClient.resolveBackground(mediaMetadata, normalizedCookie)
                }.getOrNull()
            }
    }

    return canvasMedia
}

@Composable
fun SpotifyCanvasVideoBackground(
    media: SpotifyCanvasMedia,
    shouldPlay: Boolean,
    modifier: Modifier = Modifier,
) {
    val canvasMedia =
        remember(media.url, media.headers) {
            SpotifyCanvasVideoKey(
                url = media.url,
                headers = media.headers,
            )
        }
    val okHttpClient =
        remember {
            OkHttpClient
                .Builder()
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        }

    var activeMedia by remember { mutableStateOf(canvasMedia) }
    var outgoingMedia by remember { mutableStateOf<SpotifyCanvasVideoKey?>(null) }

    LaunchedEffect(canvasMedia) {
        if (canvasMedia != activeMedia) {
            outgoingMedia = activeMedia
            activeMedia = canvasMedia
        }
    }

    Box(modifier = modifier) {
        outgoingMedia?.let { media ->
            key(media) {
                SpotifyCanvasVideoLayer(
                    media = media,
                    okHttpClient = okHttpClient,
                    shouldPlay = shouldPlay,
                    alpha = 1f,
                    onReady = {},
                )
            }
        }

        key(activeMedia) {
            ActiveSpotifyCanvasVideoLayer(
                media = activeMedia,
                okHttpClient = okHttpClient,
                shouldPlay = shouldPlay,
                fadeIn = outgoingMedia != null,
                onFadeComplete = {
                    if (activeMedia == it) {
                        outgoingMedia = null
                    }
                },
            )
        }

        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.16f)),
        )
    }
}

@Composable
private fun ActiveSpotifyCanvasVideoLayer(
    media: SpotifyCanvasVideoKey,
    okHttpClient: OkHttpClient,
    shouldPlay: Boolean,
    fadeIn: Boolean,
    onFadeComplete: (SpotifyCanvasVideoKey) -> Unit,
) {
    var isReady by remember(media) { mutableStateOf(!fadeIn) }
    val currentOnFadeComplete by rememberUpdatedState(onFadeComplete)
    val alpha by animateFloatAsState(
        targetValue = if (isReady) 1f else 0f,
        animationSpec = tween(CanvasCrossfadeDurationMillis),
        label = "SpotifyCanvasCrossfade",
        finishedListener = { value ->
            if (value == 1f && isReady) {
                currentOnFadeComplete(media)
            }
        },
    )

    LaunchedEffect(fadeIn) {
        if (!fadeIn) {
            isReady = true
        }
    }

    SpotifyCanvasVideoLayer(
        media = media,
        okHttpClient = okHttpClient,
        shouldPlay = shouldPlay,
        alpha = alpha,
        onReady = { isReady = true },
    )
}

@Composable
private fun SpotifyCanvasVideoLayer(
    media: SpotifyCanvasVideoKey,
    okHttpClient: OkHttpClient,
    shouldPlay: Boolean,
    alpha: Float,
    onReady: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentShouldPlay by rememberUpdatedState(shouldPlay)
    val currentOnReady by rememberUpdatedState(onReady)
    val textureView =
        remember(media) {
            TextureView(context).apply {
                isOpaque = false
                isClickable = false
                isFocusable = false
            }
        }
    val player =
        remember(media) {
            val isRemoteCanvas =
                media.url.startsWith("http://", ignoreCase = true) ||
                    media.url.startsWith("https://", ignoreCase = true)
            val mediaSourceFactory =
                if (isRemoteCanvas) {
                    val dataSourceFactory =
                        OkHttpDataSource
                            .Factory(okHttpClient)
                            .setDefaultRequestProperties(media.headers)
                    DefaultMediaSourceFactory(dataSourceFactory)
                } else {
                    DefaultMediaSourceFactory(context)
                }

            ExoPlayer
                .Builder(context)
                .setMediaSourceFactory(mediaSourceFactory)
                .build()
                .apply {
                    setAudioAttributes(AudioAttributes.DEFAULT, false)
                    repeatMode = Player.REPEAT_MODE_ONE
                    volume = 0f
                    playWhenReady = shouldPlay
                    setVideoTextureView(textureView)
                    setMediaItem(MediaItem.fromUri(media.url))
                    prepare()
                }
        }

    DisposableEffect(player, lifecycleOwner, textureView) {
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> if (currentShouldPlay) player.play()
                    Lifecycle.Event.ON_PAUSE -> player.pause()
                    else -> Unit
                }
            }
        val playerListener =
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        currentOnReady()
                    }
                }
            }
        player.addListener(playerListener)
        lifecycleOwner.lifecycle.addObserver(observer)
        if (player.playbackState == Player.STATE_READY) {
            currentOnReady()
        }
        onDispose {
            player.removeListener(playerListener)
            lifecycleOwner.lifecycle.removeObserver(observer)
            player.clearVideoTextureView(textureView)
            player.release()
        }
    }

    LaunchedEffect(player, shouldPlay) {
        player.playWhenReady = shouldPlay
        if (shouldPlay) {
            player.play()
        } else {
            player.pause()
        }
    }

    AndroidView(
        factory = { textureView },
        modifier =
            modifier
                .fillMaxSize()
                .alpha(alpha),
    )
}
