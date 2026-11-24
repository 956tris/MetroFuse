/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.desktop.state.playback

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.metrolist.desktop.state.app.AppState
import com.metrolist.desktop.state.app.UiState
import com.metrolist.shared.api.artwork.CanvasContentSource
import com.metrolist.shared.api.artwork.youtubeCanvasPlaybackClips
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.artworkExplicitness
import com.metrolist.shared.plugins.PluginServicesStore
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.viewmodels.PlayerViewModel.MusicVideoCanvas
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun desktopAnimatedArtworkEnabled(
    animatedCoversEnabled: Boolean,
    expanded: Boolean,
    canvasEnabled: Boolean,
): Boolean = animatedCoversEnabled || (expanded && canvasEnabled)

internal class DesktopAnimatedArtwork(
    private val scope: CoroutineScope,
) {
    private val directCanvasProvider get() = AppState.directCanvasProvider
    private var currentTrack: SongItem? = null
    private var _animatedCoverUrl: String? by mutableStateOf(null)
    private var _musicVideoCanvas: MusicVideoCanvas? by mutableStateOf(null)
    private val artworkEnabled: Boolean
        get() =
            desktopAnimatedArtworkEnabled(
                Settings.shared.enableAnimatedCovers,
                UiState.playerPresentation.isExpanded,
                UiState.playerPresentation.canvasEnabled,
            )
    var animatedCoverSongId: String? by mutableStateOf(null)
        private set
    val animatedCoverUrl: String?
        get() = resolvedAnimatedCoverUrl(currentTrack, _animatedCoverUrl, animatedCoverSongId)
    val musicVideoCanvas: MusicVideoCanvas?
        get() = _musicVideoCanvas?.takeIf { it.url == animatedCoverUrl }
    private var animatedCoverFetchJob: Job? = null

    init {
        scope.launch {
            snapshotFlow {
                Triple(
                    artworkEnabled,
                    Settings.shared.animatedCoverResolution,
                    Settings.shared.canvasContentOrder,
                ) to
                    listOf(
                        Settings.shared.canvasEnabledSources,
                        Settings.shared.youtubeCanvasMode,
                        Settings.shared.youtubeCanvasQuality,
                        UiState.playerPresentation.isExpanded,
                        UiState.playerPresentation.canvasEnabled,
                    )
            }.distinctUntilChanged().collectLatest { (options, _) ->
                val (enabled, _, _) = options
                if (enabled) {
                    currentTrack?.let(::fetchAnimatedCover)
                } else {
                    animatedCoverFetchJob?.cancel()
                    _animatedCoverUrl = null
                    _musicVideoCanvas = null
                    animatedCoverSongId = null
                }
            }
        }
        scope.launch {
            PluginServicesStore.current?.musicVideoCanvasChanges?.collectLatest {
                currentTrack?.let(::fetchAnimatedCover)
            }
        }
    }

    fun updateTrack(
        effectiveTrack: SongItem?,
        startArtworkFetch: Boolean = true,
    ) {
        val previousTrack = currentTrack
        val retainedCoverUrl =
            if (_musicVideoCanvas != null && previousTrack?.id != effectiveTrack?.id) {
                null
            } else {
                retainedAnimatedCoverUrl(
                    previousSongId = previousTrack?.id,
                    nextSongId = effectiveTrack?.id,
                    previousAlbumId = previousTrack?.album?.id,
                    nextAlbumId = effectiveTrack?.album?.id,
                    animatedCoverUrl = _animatedCoverUrl,
                    animatedCoversEnabled = artworkEnabled,
                )
            }

        if (retainedCoverUrl == null) {
            animatedCoverFetchJob?.cancel()
        }

        currentTrack = effectiveTrack
        _animatedCoverUrl = retainedCoverUrl
        if (retainedCoverUrl == null) _musicVideoCanvas = null
        animatedCoverSongId = if (retainedCoverUrl != null) effectiveTrack?.id else null

        if (effectiveTrack != null && startArtworkFetch && retainedCoverUrl == null) fetchAnimatedCover(effectiveTrack)
    }

    private fun fetchAnimatedCover(song: SongItem) {
        if (!artworkEnabled) return
        val enabledSources = CanvasContentSource.parseEnabled(Settings.shared.canvasEnabledSources)
        if (enabledSources.isEmpty()) {
            animatedCoverFetchJob?.cancel()
            _animatedCoverUrl = null
            _musicVideoCanvas = null
            animatedCoverSongId = null
            return
        }
        val artist = song.artists.joinToString(", ") { it.name }
        val songId = song.id

        animatedCoverFetchJob?.cancel()
        animatedCoverFetchJob =
            scope.launch {
                val result =
                    try {
                        withContext(Dispatchers.IO) {
                            coroutineScope {
                                val spotify =
                                    async {
                                        if (CanvasContentSource.SPOTIFY_CANVAS !in enabledSources || artist.isBlank()) return@async null
                                        directCanvasProvider.getSpotifyCanvas(
                                            title = song.title,
                                            artist = artist,
                                            album = song.album?.name,
                                            duration = song.duration?.times(1_000),
                                            primaryArtist = song.artists.firstOrNull()?.name,
                                        )
                                    }
                                val appleMusic =
                                    async {
                                        if (CanvasContentSource.APPLE_MUSIC !in enabledSources || artist.isBlank()) return@async null
                                        AppState.animatedArtworkService
                                            .fetchAnimatedCover(
                                                song.title,
                                                artist,
                                                song.album?.name,
                                                song.artworkExplicitness,
                                            )?.url
                                    }
                                val musicVideo =
                                    async {
                                        if (CanvasContentSource.YOUTUBE_MUSIC_VIDEO !in enabledSources ||
                                            !UiState.playerPresentation.isExpanded || !UiState.playerPresentation.canvasEnabled ||
                                            !Settings.shared.isAnimatedArtworkAllowedOnNetwork()
                                        ) {
                                            return@async null
                                        }
                                        val services = PluginServicesStore.current ?: return@async null
                                        services.awaitReady()
                                        if (!services.supportsMusicVideoCanvas) return@async null
                                        val resource =
                                            services
                                                .musicVideoCanvas(song, Settings.shared.youtubeCanvasQuality.targetHeight)
                                                ?.takeIf { it.hasVideo } ?: return@async null
                                        MusicVideoCanvas(resource, youtubeCanvasPlaybackClips(Settings.shared.youtubeCanvasMode, resource))
                                    }
                                Triple(spotify.await(), appleMusic.await(), musicVideo.await())
                            }
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        null
                    }
                if (artworkEnabled && currentTrack?.id == songId) {
                    _animatedCoverUrl =
                        result?.let { (spotify, appleMusic, musicVideo) ->
                            CanvasContentSource
                                .parseOrder(Settings.shared.canvasContentOrder)
                                .filter { it in CanvasContentSource.parseEnabled(Settings.shared.canvasEnabledSources) }
                                .firstNotNullOfOrNull { source ->
                                    when (source) {
                                        CanvasContentSource.SPOTIFY_CANVAS -> spotify
                                        CanvasContentSource.APPLE_MUSIC -> appleMusic
                                        CanvasContentSource.YOUTUBE_MUSIC_VIDEO -> musicVideo?.url
                                    }
                                }
                        }
                    _musicVideoCanvas = result?.third?.takeIf { it.url == _animatedCoverUrl }
                    animatedCoverSongId = songId
                }
            }
    }
}
