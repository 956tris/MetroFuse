@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.desktop.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.metrolist.desktop.state.app.UiState
import com.metrolist.desktop.state.playback.LyricsState
import com.metrolist.desktop.state.playback.PlayerState
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.close_fullscreen_player
import com.metrolist.shared.generated.resources.lyrics_menu
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.components.LocalPopupVisibilityObserver
import com.metrolist.shared.ui.components.lyrics.LyricsOffsetEditor
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.utils.verticalScrollWithDrag
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun ImmersivePlayerBackground(
    playback: AnimatedCoverPlayback,
    canvasEnabled: Boolean,
    motion: CanvasPlayerMotion,
) {
    val onRight = UiState.playerPresentation.artworkOnRight
    val videoAlpha by animateFloatAsState(
        if (canvasEnabled && playback.hasFrame) 1f else 0f,
        tween(900, easing = canvasEasing),
        label = "canvasFade",
    )
    val owner = rememberAnimatedCoverViewportOwner(playback)

    BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer { alpha = videoAlpha }) {
        val wide = maxWidth >= 760.dp
        val showLyrics = UiState.showLyricsDrawer
        val lyricsFraction by animateFloatAsState(
            if (wide && showLyrics) 1f else 0f,
            tween(600, easing = canvasEasing),
            label = "canvasSplit",
        )
        val nowbar = minOf(maxWidth * 0.30f, maxHeight * 0.52f) * 1.1f
        val feather = nowbar * 0.45f
        val centeredWidth = minOf(maxWidth, nowbar + feather * 2f)
        val splitWidth = minOf(maxWidth, maxWidth / 2f + nowbar * 0.2f)
        val stageWidth = if (wide) centeredWidth + (splitWidth - centeredWidth) * lyricsFraction else maxWidth
        val stageOffset = (maxWidth - stageWidth) / 2f * lyricsFraction * if (onRight) 1f else -1f
        if (videoAlpha > 0.001f) {
            CanvasAmbientRenderer(
                frameProvider = { playback.frame },
                playing = PlayerState.isPlaying,
                modifier = Modifier.fillMaxSize(),
            )
            VideoRenderer(
                frameProvider = { playback.frame },
                contentScale = ContentScale.Crop,
                alignment = BiasAlignment(0f, -0.2f),
                onViewportSizeChanged = { width, height -> playback.requestViewport(owner, width, height, ContentScale.Crop) },
                modifier =
                    Modifier
                        .width(stageWidth)
                        .height(if (wide || !showLyrics) maxHeight else maxHeight * 0.72f)
                        .align(Alignment.TopCenter)
                        .absoluteOffset(x = stageOffset)
                        .graphicsLayer {
                            alpha = 0.75f
                            compositingStrategy = CompositingStrategy.Offscreen
                        }.then(if (wide) Modifier.clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) else Modifier)
                        .canvasStageMask(feather, wide, showLyrics, onRight) { motion.brightness.value },
            )
        }
    }
}

private fun Modifier.canvasStageMask(
    feather: Dp,
    wide: Boolean,
    showLyrics: Boolean,
    onRight: Boolean,
    brightness: () -> Float,
): Modifier =
    drawWithCache {
        val verticalScrim =
            Brush.verticalGradient(
                0f to Color.Transparent,
                0.42f to Color.Transparent,
                0.7f to Color.Black.copy(alpha = 0.6f),
                1f to Color.Black.copy(alpha = 0.6f),
            )
        val wideHorizontalMask: Brush?
        val wideTopMask: Brush?
        val narrowLyricsMask: Brush?
        if (wide) {
            val f = (feather.toPx() / size.width).coerceIn(0f, 0.5f)
            val clear = Color.Transparent
            val solid = Color.Black
            val left =
                listOf(
                    0f to clear,
                    f * 0.18f to solid.copy(alpha = 0.1f),
                    f * 0.38f to solid.copy(alpha = 0.36f),
                    f * 0.72f to solid.copy(alpha = 0.85f),
                    f to solid,
                )
            val right = left.reversed().map { (stop, color) -> 1f - stop to color }
            val stops =
                when {
                    !showLyrics -> left + right
                    onRight -> left + (1f to solid)
                    else -> listOf(0f to solid) + right
                }
            wideHorizontalMask = Brush.horizontalGradient(*stops.toTypedArray())
            wideTopMask = Brush.verticalGradient(0f to Color.Transparent, 0.09f to Color.Black.copy(alpha = 0.28f), 0.18f to Color.Black)
            narrowLyricsMask = null
        } else if (showLyrics) {
            wideHorizontalMask = null
            wideTopMask = null
            narrowLyricsMask = Brush.verticalGradient(0f to Color.Black, 0.45f to Color.Black, 1f to Color.Transparent)
        } else {
            wideHorizontalMask = null
            wideTopMask = null
            narrowLyricsMask = null
        }
        onDrawWithContent {
            drawContent()
            drawRect(Color.Black.copy(alpha = (1f - 0.8f * (0.45f + 0.55f * brightness())).coerceIn(0f, 1f)))
            drawRect(verticalScrim)
            if (wideHorizontalMask != null && wideTopMask != null) {
                drawRect(wideHorizontalMask, blendMode = BlendMode.DstIn)
                drawRect(wideTopMask, blendMode = BlendMode.DstIn)
            } else if (narrowLyricsMask != null) {
                drawRect(narrowLyricsMask, blendMode = BlendMode.DstIn)
            }
        }
    }

@Composable
internal fun ImmersiveFullscreenContent(
    motion: CanvasPlayerMotion,
    toggleWindowFullscreen: () -> Unit,
    closePlayer: () -> Unit,
) {
    DisposableEffect(motion) {
        onDispose {
            motion.controlsHovered = false
            motion.controlsFocused = false
            motion.topControlsHovered = false
            motion.topControlsFocused = false
        }
    }
    val popupObserver = remember(motion) { motion::popupVisibilityChanged }
    CompositionLocalProvider(LocalPopupVisibilityObserver provides popupObserver) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val availableWidth = maxWidth
            val availableHeight = maxHeight
            val wide = availableWidth >= 760.dp
            val showLyrics = UiState.showLyricsDrawer
            val onRight = UiState.playerPresentation.artworkOnRight
            val nowbar = minOf(availableWidth * 0.30f, availableHeight * 0.52f) * 1.1f
            val controlsScale = (nowbar.value / 500f).coerceIn(0.8f, 1.1f)
            if (wide) {
                if (showLyrics) {
                    val outerSpacing = ((availableWidth / 2f - nowbar * 1.25f) * 0.35f).coerceAtLeast(0.dp)
                    val innerSpacing = availableWidth / 2f - nowbar * 0.075f
                    Box(
                        Modifier
                            .width(availableWidth - innerSpacing - outerSpacing)
                            .fillMaxHeight(0.9f)
                            .align(Alignment.CenterStart)
                            .absoluteOffset(x = if (onRight) outerSpacing else innerSpacing),
                    ) {
                        CanvasLyricsPane(
                            textSizeMultiplier = (availableWidth.value / 1500f).coerceIn(0.9f, 1.5f),
                            motion = motion,
                        )
                    }
                }
                val alignment =
                    when {
                        !showLyrics -> Alignment.BottomCenter
                        onRight -> Alignment.BottomEnd
                        else -> Alignment.BottomStart
                    }
                Column(
                    modifier =
                        Modifier
                            .align(alignment)
                            .absoluteOffset(x = if (!showLyrics) 0.dp else availableWidth * 0.01f * if (onRight) -1f else 1f)
                            .width(nowbar)
                            .padding(start = nowbar * 0.04f, end = nowbar * 0.04f, bottom = maxOf(20.dp, availableHeight * 0.03f))
                            .heightIn(max = (availableHeight - 76.dp).coerceAtLeast(0.dp))
                            .canvasControlsHover(motion)
                            .verticalScrollWithDrag(rememberScrollState()),
                    verticalArrangement = Arrangement.Bottom,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    DesktopPlayerControls(
                        PlayerState.currentTrack,
                        PlayerState.isPlaying,
                        controlsScale,
                        immersive = true,
                        motion = motion,
                        toggleWindowFullscreen = toggleWindowFullscreen,
                    )
                }
            } else {
                Column(Modifier.fillMaxSize().padding(top = 76.dp, start = 24.dp, end = 24.dp, bottom = 20.dp)) {
                    if (showLyrics) {
                        Box(Modifier.weight(1f).fillMaxWidth().padding(bottom = 12.dp)) { CanvasLyricsPane(0.85f, motion) }
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    Box(
                        Modifier
                            .heightIn(max = (availableHeight - 96.dp).coerceAtLeast(0.dp))
                            .canvasControlsHover(motion)
                            .verticalScrollWithDrag(rememberScrollState()),
                    ) {
                        DesktopPlayerControls(
                            PlayerState.currentTrack,
                            PlayerState.isPlaying,
                            0.8f,
                            immersive = true,
                            motion = motion,
                            toggleWindowFullscreen = toggleWindowFullscreen,
                        )
                    }
                }
            }
            IconButton(
                onClick = closePlayer,
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(20.dp)
                        .size(48.dp)
                        .canvasControlsHover(motion, topControls = true)
                        .canvasControlsVisibility(motion),
                shapes = IconButtonDefaults.shapes(),
            ) {
                Icon(
                    MetrolistIcons.Default.Close,
                    stringResource(Res.string.close_fullscreen_player),
                    tint = Color.White.copy(alpha = 0.8f),
                )
            }
        }
    }
}

@Composable
private fun CanvasLyricsPane(
    textSizeMultiplier: Float,
    motion: CanvasPlayerMotion,
) {
    var showMenu by remember { mutableStateOf(false) }
    val track = PlayerState.currentTrack
    LaunchedEffect(showMenu, LyricsState.showLyricsOffsetEditor) {
        motion.lyricsMenuOpen = showMenu || LyricsState.showLyricsOffsetEditor
    }
    DisposableEffect(motion) {
        onDispose { motion.lyricsMenuOpen = false }
    }
    LaunchedEffect(track?.id, Settings.shared.lyricsProviderOrder, Settings.shared.lyricsEnabledProviders) {
        track?.let { LyricsState.fetchLyrics(it) }
    }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            CanvasIconButton(MetrolistIcons.Outlined.MoreVert, stringResource(Res.string.lyrics_menu), { showMenu = !showMenu })
            LyricsActionsMenuCard(expanded = showMenu, colorScheme = MaterialTheme.colorScheme, onDismiss = { showMenu = false })
        }
        Box(Modifier.weight(1f)) {
            DesktopLyricsContent(
                textColor = Color.White,
                textSizeMultiplier = textSizeMultiplier,
                isVisible = !UiState.videoFullscreenObscuresPlayer,
                immersive = true,
            )
        }
        AnimatedVisibility(
            visible = LyricsState.showLyricsOffsetEditor,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            LyricsOffsetEditor(
                offsetMs = LyricsState.currentLyricsOffsetMs,
                onOffsetChange = LyricsState::updateLyricsOffset,
                onDone = { LyricsState.showLyricsOffsetEditor = false },
                contentColor = Color.White,
            )
        }
    }
}

@Composable
private fun CanvasIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    active: Boolean = false,
    filled: Boolean = false,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier =
            Modifier
                .size(44.dp)
                .background(
                    Color.White.copy(
                        alpha =
                            if (active) {
                                0.22f
                            } else if (filled) {
                                0.10f
                            } else {
                                0f
                            },
                    ),
                    CircleShape,
                ).semantics { selected = active },
        shapes = IconButtonDefaults.shapes(),
    ) {
        Icon(icon, description, tint = Color.White.copy(alpha = if (enabled) 0.85f else 0.3f), modifier = Modifier.size(24.dp))
    }
}
