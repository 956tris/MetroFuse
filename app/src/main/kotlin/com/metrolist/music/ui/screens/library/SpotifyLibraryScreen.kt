/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.Indicator
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YTItem
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.constants.LibraryFilter
import com.metrolist.music.constants.MiniPlayerBottomSpacing
import com.metrolist.music.constants.MiniPlayerHeight
import com.metrolist.music.constants.NavigationBarHeight
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.playback.queues.ListQueue
import com.metrolist.music.providers.ExternalHomeItemIds
import com.metrolist.music.ui.component.EmptyPlaceholder
import com.metrolist.music.ui.component.NavigationTitle
import com.metrolist.music.ui.component.YouTubeListItem
import com.metrolist.music.ui.component.shimmer.ListItemPlaceHolder
import com.metrolist.music.ui.component.shimmer.ShimmerHost
import com.metrolist.music.viewmodels.SpotifyLibraryViewModel

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SpotifyLibraryScreen(
    navController: NavController,
    filterContent: @Composable () -> Unit,
    filterType: LibraryFilter,
    viewModel: SpotifyLibraryViewModel = hiltViewModel(),
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val uriHandler = LocalUriHandler.current
    val isPlaying by playerConnection.isEffectivelyPlaying.collectAsStateWithLifecycle()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()
    val libraryPage by viewModel.libraryPage.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val hasCookie by viewModel.hasCookie.collectAsStateWithLifecycle()
    val pullRefreshState = rememberPullToRefreshState()
    val listState = rememberLazyListState()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val scrollToTop =
        backStackEntry
            ?.savedStateHandle
            ?.getStateFlow("scrollToTop", false)
            ?.collectAsStateWithLifecycle()

    val visibleSections =
        remember(libraryPage, filterType) {
            libraryPage
                ?.sections
                .orEmpty()
                .filter { section ->
                    when (filterType) {
                        LibraryFilter.LIBRARY -> true
                        LibraryFilter.SONGS -> section.title.contains("song", ignoreCase = true)
                        LibraryFilter.PLAYLISTS -> section.title.contains("playlist", ignoreCase = true)
                        LibraryFilter.ALBUMS -> section.title.contains("album", ignoreCase = true)
                        LibraryFilter.ARTISTS -> section.title.contains("artist", ignoreCase = true)
                        LibraryFilter.PODCASTS -> false
                    }
                }
        }

    LaunchedEffect(scrollToTop?.value) {
        if (scrollToTop?.value == true) {
            listState.animateScrollToItem(0)
            backStackEntry?.savedStateHandle?.set("scrollToTop", false)
        }
    }

    fun playSong(item: SongItem) {
        if (item.id == mediaMetadata?.id) {
            playerConnection.togglePlayPause()
        } else {
            playerConnection.playQueue(
                ListQueue(
                    title = item.title,
                    items = listOf(item.toMediaMetadata().toMediaItem()),
                ),
            )
        }
    }

    fun openExternalItem(item: YTItem) {
        ExternalHomeItemIds.externalMetroRoute(item)
            ?.let { route ->
                navController.navigate(route)
                return
            }

        ExternalHomeItemIds.externalUrl(item)
            ?.let { url ->
                runCatching { uriHandler.openUri(url) }
            }
    }

    PullToRefreshBox(
        state = pullRefreshState,
        isRefreshing = isLoading,
        onRefresh = viewModel::refresh,
        indicator = {
            Indicator(
                isRefreshing = isLoading,
                state = pullRefreshState,
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top)),
            )
        },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            state = listState,
            contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "filters") {
                filterContent()
            }

            if (!hasCookie) {
                item(key = "missing_cookie") {
                    EmptyPlaceholder(
                        icon = R.drawable.login,
                        text = stringResource(R.string.spotify_library_login_required),
                    )
                }
            } else if (isLoading && libraryPage == null) {
                item(key = "loading") {
                    ShimmerHost {
                        repeat(8) {
                            ListItemPlaceHolder()
                        }
                    }
                }
            } else if (visibleSections.isEmpty()) {
                item(key = "empty") {
                    EmptyPlaceholder(
                        icon = R.drawable.library_music,
                        text = stringResource(R.string.library_playlist_empty),
                    )
                }
            } else {
                visibleSections.forEach { section ->
                    item(key = "title_${section.title}") {
                        NavigationTitle(section.title)
                    }

                    items(
                        items = section.items,
                        key = { item -> "${section.title}_${item.id}" },
                    ) { item ->
                        YouTubeListItem(
                            item = item,
                            isActive =
                                when (item) {
                                    is SongItem -> mediaMetadata?.id == item.id
                                    is AlbumItem -> mediaMetadata?.album?.id == item.id
                                    else -> false
                                },
                            isPlaying = isPlaying,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = {
                                            when (item) {
                                                is SongItem -> playSong(item)
                                                is AlbumItem,
                                                is ArtistItem,
                                                is PlaylistItem,
                                                -> openExternalItem(item)
                                                else -> Unit
                                            }
                                        },
                                    ),
                        )
                    }
                }
            }

            item(key = "bottom_spacer") {
                Spacer(modifier = Modifier.height(MiniPlayerHeight + MiniPlayerBottomSpacing + NavigationBarHeight + 8.dp))
            }
        }
    }
}
