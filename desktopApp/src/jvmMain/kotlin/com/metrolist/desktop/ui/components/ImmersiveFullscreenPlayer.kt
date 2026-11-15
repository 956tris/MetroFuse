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
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrolist.desktop.state.app.UiState
import com.metrolist.desktop.state.content.ArtistState
import com.metrolist.desktop.state.content.PlaylistAlbumState
import com.metrolist.desktop.state.playback.ListenTogetherState
import com.metrolist.desktop.state.playback.LyricsState
import com.metrolist.desktop.state.playback.PlayerState
import com.metrolist.desktop.storage.DownloadManager
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.action_download
import com.metrolist.shared.generated.resources.action_like
import com.metrolist.shared.generated.resources.canvas
import com.metrolist.shared.generated.resources.close_fullscreen_player
import com.metrolist.shared.generated.resources.details
import com.metrolist.shared.generated.resources.exit_fullscreen
import com.metrolist.shared.generated.resources.fullscreen
import com.metrolist.shared.generated.resources.hide_lyrics
import com.metrolist.shared.generated.resources.lyrics_menu
import com.metrolist.shared.generated.resources.more
import com.metrolist.shared.generated.resources.mute
import com.metrolist.shared.generated.resources.not_playing
import com.metrolist.shared.generated.resources.queue
import com.metrolist.shared.generated.resources.repeat
import com.metrolist.shared.generated.resources.show_lyrics
import com.metrolist.shared.generated.resources.shuffle
import com.metrolist.shared.generated.resources.swap_artwork_lyrics_sides
import com.metrolist.shared.generated.resources.unlike
import com.metrolist.shared.generated.resources.unmute
import com.metrolist.shared.generated.resources.view_album
import com.metrolist.shared.generated.resources.volume
import com.metrolist.shared.model.RepeatMode
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.components.PopupVisibility
import com.metrolist.shared.ui.components.lyrics.LyricsOffsetEditor
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.menu.cursorContextMenu
import com.metrolist.shared.ui.menu.rememberCursorContextMenuState
import com.metrolist.shared.ui.player.PlayerArtistLine
import com.metrolist.shared.ui.utils.ArtworkBlurStyle
import com.metrolist.shared.ui.utils.verticalScrollWithDrag
import org.jetbrains.compose.resources.stringResource

// Native Compose adaptation of IndiaChrome's spicy-fullscreen Canvas stage.
// Reference: https://indiachrome.pages.dev (layout and motion; no web runtime).
@Composable
internal fun ImmersivePlayerBackground(canvasEnabled: Boolean, onCanvasAvailable: (Boolean) -> Unit) {
    val url = PlayerState.animatedCoverUrl.takeIf { canvasEnabled }
    val onRight = UiState.playerPresentation.artworkOnRight
    var hasFrame by remember(PlayerState.currentTrack?.id, url) { mutableStateOf(false) }
    val videoAlpha by animateFloatAsState(if (hasFrame) 1f else 0f, tween(700), label = "canvasFade")
    LaunchedEffect(hasFrame, url) { onCanvasAvailable(hasFrame) }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        AsyncImage(
            url = PlayerState.currentArtworkUrl ?: "",
            artworkPreview = PlayerState.artworkPreview,
            modifier = Modifier.fillMaxSize(),
            extractColor = true,
            contentScale = ContentScale.Crop,
            saturation = 1.8f,
            blur =
                ArtworkBlurStyle(
                    radius = 60.dp,
                    backgroundColor = Color.Black,
                    scale = 1.35f,
                    edgeTreatment = BlurredEdgeTreatment.Unbounded,
                    scrimColor = Color.Black.copy(alpha = 0.55f),
                ),
        )
        val wide = maxWidth >= 760.dp
        val stageFraction = if (wide && UiState.showLyricsDrawer) 0.62f else 1f
        url?.let { canvasUrl ->
            AnimatedCover(
                url = canvasUrl,
                active = PlayerState.isPlaying && !UiState.videoFullscreenObscuresPlayer,
                onFrameAvailabilityChanged = { hasFrame = it },
                backgroundModifier =
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            alpha = videoAlpha * 0.55f
                            scaleX = 1.12f
                            scaleY = 1.12f
                        }.blur(36.dp, BlurredEdgeTreatment.Unbounded),
                modifier =
                    Modifier
                        .fillMaxWidth(stageFraction)
                        .fillMaxHeight()
                        .align(if (onRight) Alignment.CenterEnd else Alignment.CenterStart)
                        .graphicsLayer {
                            alpha = videoAlpha
                            compositingStrategy = CompositingStrategy.Offscreen
                        }.blur(2.dp, BlurredEdgeTreatment.Unbounded)
                        .drawWithContent {
                            drawContent()
                            if (stageFraction < 1f) {
                                val colors = listOf(Color.Black, Color.Black, Color.Black.copy(alpha = 0.85f), Color.Transparent)
                                drawRect(
                                    brush = Brush.horizontalGradient(if (onRight) colors.reversed() else colors),
                                    blendMode = BlendMode.DstIn,
                                )
                            }
                        },
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.12f),
                    0.5f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.72f),
                ),
            ),
        )
    }
}

@Composable
internal fun FullscreenCanvasToolbar(
    toggleWindowFullscreen: () -> Unit,
    closePlayer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val presentation = UiState.playerPresentation
    Row(
        modifier = modifier.fillMaxWidth().padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CanvasIconButton(MetrolistIcons.Filled.Close, stringResource(Res.string.close_fullscreen_player), closePlayer, filled = true)
        CanvasIconButton(
            MetrolistIcons.Rounded.Mic,
            stringResource(if (UiState.showLyricsDrawer) Res.string.hide_lyrics else Res.string.show_lyrics),
            {
                presentation.fullscreenLyricsEnabled = !UiState.showLyricsDrawer
                LyricsState.toggleLyricsDrawer()
            },
            active = UiState.showLyricsDrawer,
            filled = true,
        )
        TextButton(
            onClick = { presentation.canvasEnabled = !presentation.canvasEnabled },
            modifier =
                Modifier
                    .height(44.dp)
                    .background(
                        Color.White.copy(alpha = if (presentation.canvasEnabled) 0.22f else 0.10f),
                        CircleShape,
                    ).semantics { selected = presentation.canvasEnabled },
        ) {
            Text(stringResource(Res.string.canvas), color = Color.White, fontWeight = FontWeight.SemiBold)
        }
        if (presentation.canvasEnabled) {
            CanvasIconButton(
                MetrolistIcons.Outlined.Sync,
                stringResource(Res.string.swap_artwork_lyrics_sides),
                { presentation.artworkOnRight = !presentation.artworkOnRight },
                filled = true,
            )
        }
        Spacer(Modifier.weight(1f))
        val fullscreen = PlayerState.isFullscreen || UiState.isNativeFullscreen
        CanvasIconButton(
            if (fullscreen) MetrolistIcons.Outlined.FullscreenExit else MetrolistIcons.Outlined.Fullscreen,
            stringResource(if (fullscreen) Res.string.exit_fullscreen else Res.string.fullscreen),
            toggleWindowFullscreen,
            filled = true,
        )
    }
}

@Composable
internal fun ImmersiveFullscreenContent(hasCanvas: Boolean) {
    BoxWithConstraints(Modifier.fillMaxSize().padding(top = 76.dp)) {
        val availableWidth = maxWidth
        val availableHeight = maxHeight
        val wide = availableWidth >= 760.dp
        val showLyrics = UiState.showLyricsDrawer
        val onRight = UiState.playerPresentation.artworkOnRight
        val dockWidth = minOf(availableWidth * 0.46f, (availableHeight * 0.58f).coerceIn(330.dp, 540.dp))
        val dockAlignment =
            when {
                !showLyrics -> Alignment.BottomCenter
                onRight -> Alignment.BottomEnd
                else -> Alignment.BottomStart
            }
        if (wide) {
            if (showLyrics) {
                Box(
                    Modifier
                        .fillMaxWidth(0.49f)
                        .fillMaxHeight()
                        .align(if (onRight) Alignment.CenterStart else Alignment.CenterEnd)
                        .padding(start = 28.dp, end = 40.dp, top = 8.dp, bottom = 28.dp),
                ) {
                    CanvasLyricsPane(textSizeMultiplier = (availableWidth.value / 1350f).coerceIn(1.05f, 1.5f))
                }
            }
            Column(
                modifier =
                    Modifier
                        .align(dockAlignment)
                        .width(dockWidth + 48.dp)
                        .padding(horizontal = 24.dp, vertical = 20.dp)
                        .heightIn(max = availableHeight - 40.dp)
                        .verticalScrollWithDrag(rememberScrollState()),
                verticalArrangement = Arrangement.Bottom,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (!hasCanvas) {
                    AsyncImage(
                        url = PlayerState.currentArtworkUrl ?: "",
                        artworkPreview = PlayerState.artworkPreview,
                        modifier = Modifier.size(minOf(dockWidth, availableHeight * 0.42f)).clip(RoundedCornerShape(12.dp)),
                        extractColor = true,
                        contentScale = if (Settings.shared.cropAlbumArt) ContentScale.Crop else ContentScale.Fit,
                    )
                    Spacer(Modifier.height(28.dp))
                }
                CanvasPlayerControls()
            }
        } else {
            Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp)) {
                if (showLyrics) {
                    Box(Modifier.weight(1f).fillMaxWidth().padding(bottom = 12.dp)) { CanvasLyricsPane(0.9f) }
                } else {
                    Spacer(Modifier.weight(1f))
                    if (!hasCanvas) {
                        AsyncImage(
                            url = PlayerState.currentArtworkUrl ?: "",
                            artworkPreview = PlayerState.artworkPreview,
                            modifier =
                                Modifier
                                    .align(Alignment.CenterHorizontally)
                                    .size(
                                        minOf(
                                            availableWidth * 0.55f,
                                            availableHeight * 0.32f,
                                        ),
                                    ).clip(RoundedCornerShape(12.dp)),
                            contentScale = ContentScale.Fit,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                }
                Box(Modifier.heightIn(max = availableHeight * 0.55f).verticalScrollWithDrag(rememberScrollState())) {
                    CanvasPlayerControls()
                }
            }
        }
    }
}

@Composable
private fun CanvasLyricsPane(textSizeMultiplier: Float) {
    var showMenu by remember { mutableStateOf(false) }
    val track = PlayerState.currentTrack
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
private fun CanvasPlayerControls() {
    val track = PlayerState.currentTrack
    val guest = ListenTogetherState.isListenTogetherGuest()
    val menu = rememberCursorContextMenuState()
    var showDetails by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().cursorContextMenu(menu), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            track?.title ?: stringResource(Res.string.not_playing),
            color = Color.White,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(
                        enabled = track != null,
                        onClickLabel = stringResource(Res.string.view_album),
                    ) {
                        track?.let {
                            PlayerState.exitExpanded()
                            PlaylistAlbumState.openSongAlbum(it)
                        }
                    }.basicMarquee(iterations = if (UiState.videoFullscreenObscuresPlayer) 0 else 1),
        )
        PlayerArtistLine(
            artists = track?.artists,
            onNavigateToArtist = { ArtistState.fetchArtistData(it) },
            fallbackText = "",
            color = Color.White.copy(alpha = 0.75f),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val liked = track?.let { PlayerState.isLiked(it.id) } == true
            CanvasIconButton(
                if (liked) MetrolistIcons.Rounded.Favorite else MetrolistIcons.Rounded.FavoriteBorder,
                stringResource(if (liked) Res.string.unlike else Res.string.action_like),
                { track?.let(PlayerState::toggleLike) },
                enabled = track != null,
                active = liked,
            )
            CanvasIconButton(
                MetrolistIcons.Outlined.Download,
                stringResource(Res.string.action_download),
                { track?.let(DownloadManager::download) },
                enabled = track != null && !guest && !DownloadManager.isDownloaded(track.id),
            )
            CanvasIconButton(
                MetrolistIcons.AutoMirrored.Outlined.QueueMusic,
                stringResource(Res.string.queue),
                UiState::togglePersistentPlayerDrawer,
                active = UiState.showPersistentPlayerDrawer,
            )
            CanvasIconButton(
                MetrolistIcons.Outlined.Info,
                stringResource(Res.string.details),
                { showDetails = true },
                enabled =
                    track != null,
            )
            Box {
                CanvasIconButton(
                    MetrolistIcons.Outlined.MoreVert,
                    stringResource(Res.string.more),
                    menu::openAnchored,
                    active = menu.expanded,
                )
                PlayerMenuPanel(
                    expanded = menu.expanded,
                    offset = menu.offset,
                    onDismiss = menu::dismiss,
                    onShowAddToPlaylistDialog = {
                        PlayerState.showAddToPlaylistSong = track
                        menu.dismiss()
                    },
                    onShowDetailsDialog = {
                        menu.dismiss()
                        showDetails = true
                    },
                    onShowSleepTimerDialog = {
                        menu.dismiss()
                        showSleep = true
                    },
                    onToggleQueue = UiState::togglePersistentPlayerDrawer,
                    isQueueActive = UiState.showPersistentPlayerDrawer,
                )
            }
        }
        PlayerProgressSection(immersive = true)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            CanvasIconButton(
                MetrolistIcons.Rounded.Shuffle,
                stringResource(Res.string.shuffle),
                PlayerState::toggleShuffle,
                active = PlayerState.shuffleEnabled,
                enabled = !guest,
            )
            if (!guest) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    DesktopPrimaryPlaybackControls(PlayerState.isPlaying, 0.8f, Color.White)
                }
            }
            CanvasIconButton(
                if (PlayerState.repeatMode == RepeatMode.ONE) MetrolistIcons.Rounded.RepeatOne else MetrolistIcons.Rounded.Repeat,
                stringResource(Res.string.repeat),
                PlayerState::cycleRepeatMode,
                active = PlayerState.repeatMode != RepeatMode.OFF,
                enabled = !guest,
            )
        }
        CanvasVolume()
    }
    PopupVisibility(visible = showDetails) { MediaDetailsDialog(song = track, onDismiss = { showDetails = false }) }
    PopupVisibility(visible = showSleep) { SleepTimerDialog(onDismiss = { showSleep = false }) }
}

@Composable
private fun CanvasVolume() {
    var previousVolume by remember { mutableStateOf(PlayerState.volume.takeIf { it > 0f }) }
    val volumeDescription = stringResource(Res.string.volume)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        CanvasIconButton(
            if (PlayerState.volume == 0f) MetrolistIcons.AutoMirrored.Outlined.VolumeOff else MetrolistIcons.AutoMirrored.Outlined.VolumeUp,
            stringResource(if (PlayerState.volume == 0f) Res.string.unmute else Res.string.mute),
            {
                if (PlayerState.volume > 0f) {
                    previousVolume = PlayerState.volume
                    PlayerState.setVolumeLevel(0f)
                } else {
                    PlayerState.setVolumeLevel(previousVolume ?: Settings.shared.volume)
                }
            },
        )
        Slider(
            value = PlayerState.volume,
            onValueChange = PlayerState::setVolumeLevel,
            colors =
                SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White,
                    inactiveTrackColor = Color.White.copy(alpha = 0.22f),
                ),
            modifier = Modifier.width(180.dp).semantics { contentDescription = volumeDescription },
        )
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
