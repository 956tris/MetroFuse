package com.metrolist.desktop.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import com.metrolist.shared.api.artwork.runYouTubeCanvasPlaybackClock
import com.metrolist.shared.playback.VideoFrame
import com.metrolist.shared.playback.VideoPlayer
import com.metrolist.shared.ui.components.LocalVisualWorkActive
import com.metrolist.shared.viewmodels.PlayerViewModel.MusicVideoCanvas
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal val LocalAnimatedCoverPlayback = staticCompositionLocalOf<AnimatedCoverPlayback?> { null }

internal data class AnimatedCoverViewport(
    val width: Int,
    val height: Int,
    val fillViewport: Boolean,
)

@Stable
internal class AnimatedCoverPlayback(
    val url: String,
    val canvas: MusicVideoCanvas? = null,
) {
    val playWhenReady = MutableStateFlow(false)

    @Volatile var ended = false
    var frame by mutableStateOf<VideoFrame?>(null, neverEqualPolicy())
        private set
    var hasFrame by mutableStateOf(false)
        private set
    private val viewports = mutableStateMapOf<Any, AnimatedCoverViewport>()
    val targetViewport: AnimatedCoverViewport?
        get() = viewports.values.maxByOrNull { it.width.toLong() * it.height }

    val player =
        VideoPlayer(
            onFrame = {
                frame = it
                hasFrame = true
                ended = false
            },
            // Keep the outgoing frame until the Canvas dissolve has finished.
            onEnded = {
                hasFrame = false
                ended = true
            },
            looping = true,
        ).apply {
            onStreamExpired = { hasFrame = false }
        }

    fun requestViewport(
        owner: Any,
        width: Int,
        height: Int,
        contentScale: ContentScale,
    ) {
        if (width > 0 && height > 0) {
            viewports[owner] = AnimatedCoverViewport(width, height, contentScale == ContentScale.Crop)
        }
    }

    fun releaseViewport(owner: Any) {
        viewports.remove(owner)
    }
}

@Composable
internal fun rememberAnimatedCoverPlayback(
    url: String?,
    canvas: MusicVideoCanvas? = null,
): AnimatedCoverPlayback? = remember(url, canvas) { url?.let { AnimatedCoverPlayback(it, canvas) } }

@Composable
internal fun AnimatedCoverPlaybackEffect(
    playback: AnimatedCoverPlayback,
    active: Boolean,
    frameRequested: Boolean = true,
) {
    val visible = LocalVisualWorkActive.current
    val latestActive by rememberUpdatedState(active)
    val latestFrameRequested by rememberUpdatedState(frameRequested)
    LaunchedEffect(playback, visible) {
        val loaded =
            withContext(Dispatchers.IO) {
                playback.player.load(
                    url = playback.url,
                    headers =
                        playback.canvas
                            ?.resource
                            ?.headers
                            .orEmpty(),
                    positionProvider = { 0L },
                )
            }
        if (loaded && latestFrameRequested) {
            playback.player.setVolume(0f)
            // A paused track still needs one decoded frame to determine Canvas availability.
            playback.player.play(startPaused = true)
            coroutineScope {
                playback.canvas?.clips?.takeIf { it.isNotEmpty() }?.let { clips ->
                    launch {
                        runYouTubeCanvasPlaybackClock(
                            clips = clips,
                            currentPositionMs = { playback.player.getStats().videoPtsMs },
                            isPlaying = { playback.playWhenReady.value && playback.hasFrame },
                            playWhenReady = playback.playWhenReady,
                            isEnded = { playback.ended },
                            updatePlaybackPosition = {},
                            seekTo = {
                                playback.ended = false
                                playback.player.seekTo(it)
                            },
                        )
                    }
                }
                combine(snapshotFlow { latestActive }, visible) { playing, shown -> playing && shown }
                    .distinctUntilChanged()
                    .collect { playing ->
                        playback.playWhenReady.value = playing
                        if (playing) playback.player.resume() else playback.player.pause()
                    }
            }
        }
    }
    LaunchedEffect(playback) {
        snapshotFlow { playback.targetViewport }
            .filterNotNull()
            .distinctUntilChanged()
            .collectLatest { viewport ->
                delay(320)
                playback.player.setTargetSize(viewport.width, viewport.height, fillViewport = viewport.fillViewport)
            }
    }
    DisposableEffect(playback) {
        onDispose { playback.player.dispose() }
    }
}

@Composable
internal fun rememberAnimatedCoverViewportOwner(playback: AnimatedCoverPlayback): Any {
    val owner = remember(playback) { Any() }
    DisposableEffect(playback, owner) {
        onDispose { playback.releaseViewport(owner) }
    }
    return owner
}

@Composable
fun AnimatedCover(
    url: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    active: Boolean = true,
    onFrameAvailabilityChanged: (Boolean) -> Unit = {},
    backgroundModifier: Modifier? = null,
    alignment: Alignment = Alignment.Center,
    backgroundSaturation: Float = 1f,
    backgroundBrightness: Float = 1f,
    canvas: MusicVideoCanvas? = null,
) {
    val sharedPlayback = LocalAnimatedCoverPlayback.current?.takeIf { it.url == url }
    val playback = sharedPlayback ?: checkNotNull(rememberAnimatedCoverPlayback(url, canvas))
    if (sharedPlayback == null) AnimatedCoverPlaybackEffect(playback, active)
    val owner = rememberAnimatedCoverViewportOwner(playback)
    val latestOnFrameAvailabilityChanged by rememberUpdatedState(onFrameAvailabilityChanged)
    LaunchedEffect(playback, playback.hasFrame) { latestOnFrameAvailabilityChanged(playback.hasFrame) }

    backgroundModifier?.let {
        VideoRenderer(
            modifier = it,
            contentScale = ContentScale.Crop,
            frameProvider = { playback.frame.takeIf { playback.hasFrame } },
            saturation = backgroundSaturation,
            brightness = backgroundBrightness,
        )
    }
    VideoRenderer(
        modifier = modifier,
        contentScale = contentScale,
        alignment = alignment,
        frameProvider = { playback.frame.takeIf { playback.hasFrame } },
        onViewportSizeChanged = { width, height -> playback.requestViewport(owner, width, height, contentScale) },
    )
}
