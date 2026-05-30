/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.Indicator
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.constants.MiniPlayerBottomSpacing
import com.metrolist.music.constants.MiniPlayerHeight
import com.metrolist.music.constants.NavigationBarHeight
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.playback.queues.ListQueue
import com.metrolist.music.ui.component.EmptyPlaceholder
import com.metrolist.music.ui.component.YouTubeListItem
import com.metrolist.music.ui.component.shimmer.ListItemPlaceHolder
import com.metrolist.music.ui.component.shimmer.ShimmerHost
import com.metrolist.music.viewmodels.SpotifyListeningHistoryViewModel

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SpotifyListeningHistoryScreen(
    navController: NavController,
    viewModel: SpotifyListeningHistoryViewModel = hiltViewModel(),
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val isPlaying by playerConnection.isEffectivelyPlaying.collectAsStateWithLifecycle()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()
    val songs by viewModel.songs.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val hasCookie by viewModel.hasCookie.collectAsStateWithLifecycle()
    val loadFailed by viewModel.loadFailed.collectAsStateWithLifecycle()
    val pullRefreshState = rememberPullToRefreshState()
    val listState = rememberLazyListState()
    val title = stringResource(R.string.spotify_listening_history_title)

    fun playSong(index: Int) {
        val song = songs.getOrNull(index) ?: return
        if (song.id == mediaMetadata?.id) {
            playerConnection.togglePlayPause()
            return
        }

        playerConnection.playQueue(
            ListQueue(
                title = title,
                items = songs.map { it.toMediaMetadata().toMediaItem() },
                startIndex = index,
            ),
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
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
                item(key = "top_spacer") {
                    Spacer(modifier = Modifier.height(64.dp))
                }

                if (!hasCookie) {
                    item(key = "missing_cookie") {
                        EmptyPlaceholder(
                            icon = R.drawable.login,
                            text = stringResource(R.string.spotify_listening_history_login_required),
                        )
                    }
                } else if (isLoading && songs.isEmpty()) {
                    item(key = "loading") {
                        ShimmerHost {
                            repeat(10) {
                                ListItemPlaceHolder()
                            }
                        }
                    }
                } else if (songs.isEmpty()) {
                    item(key = "empty") {
                        EmptyPlaceholder(
                            icon = R.drawable.history,
                            text =
                                stringResource(
                                    if (loadFailed) {
                                        R.string.spotify_listening_history_load_failed
                                    } else {
                                        R.string.spotify_listening_history_empty
                                    },
                                ),
                        )
                    }
                } else {
                    itemsIndexed(
                        items = songs,
                        key = { index, song -> "${song.id}_$index" },
                    ) { index, song ->
                        YouTubeListItem(
                            item = song,
                            isActive = song.id == mediaMetadata?.id,
                            isPlaying = isPlaying,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = { playSong(index) },
                                    ).animateItem(),
                        )
                    }
                }

                item(key = "bottom_spacer") {
                    Spacer(modifier = Modifier.height(MiniPlayerHeight + MiniPlayerBottomSpacing + NavigationBarHeight + 8.dp))
                }
            }
        }

        TopAppBar(
            title = { Text(title) },
            navigationIcon = {
                IconButton(onClick = navController::navigateUp) {
                    Icon(
                        painter = painterResource(R.drawable.arrow_back),
                        contentDescription = null,
                    )
                }
            },
        )
    }
}
