/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.playlist

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastForEachReversed
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalListenTogetherManager
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.LocalSyncUtils
import com.metrolist.music.R
import com.metrolist.music.constants.HideExplicitKey
import com.metrolist.music.db.entities.Playlist
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.db.entities.PlaylistSongMap
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.playback.queues.YouTubePlaylistQueue
import com.metrolist.music.providers.ExternalHomeItemIds
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.LocalMenuState
import com.metrolist.music.ui.component.YouTubeListItem
import com.metrolist.music.ui.menu.YouTubePlaylistMenu
import com.metrolist.music.ui.menu.YouTubeSelectionSongMenu
import com.metrolist.music.ui.menu.YouTubeSongMenu
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.makeTimeString
import com.metrolist.music.utils.rememberPreference
import com.metrolist.music.viewmodels.OnlinePlaylistViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun OnlinePlaylistScreen(
    navController: NavController,
    viewModel: OnlinePlaylistViewModel = hiltViewModel(),
) {
    val menuState = LocalMenuState.current
    val database = LocalDatabase.current
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val listenTogetherManager = LocalListenTogetherManager.current
    val isListenTogetherGuest = listenTogetherManager?.let { it.isInRoom && !it.isHost } ?: false
    val coroutineScope = rememberCoroutineScope()

    val isPlaying by playerConnection.isEffectivelyPlaying.collectAsStateWithLifecycle()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()

    val playlist by viewModel.playlist.collectAsStateWithLifecycle()
    val songs by viewModel.playlistSongs.collectAsStateWithLifecycle()
    val suggestions by viewModel.playlistSuggestions.collectAsStateWithLifecycle()
    val dbPlaylist by viewModel.dbPlaylist.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val isLoadingMore by viewModel.isLoadingMore.collectAsStateWithLifecycle()
    val isLoadingSuggestions by viewModel.isLoadingSuggestions.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val isPodcastPlaylist = viewModel.isPodcastPlaylist
    val isExternalPlaylist = viewModel.isExternalPlaylist

    val hideExplicit by rememberPreference(key = HideExplicitKey, defaultValue = false)
    val suggestionsTitle = stringResource(R.string.playlist_suggestions)
    val refreshSuggestionsText = stringResource(R.string.playlist_suggestions_refresh)

    val lazyListState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }

    var isSearching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }

    val filteredSongs =
        remember(songs, query) {
            if (query.text.isEmpty()) {
                songs.mapIndexed { i, s -> i to s }
            } else {
                songs.mapIndexed { i, s -> i to s }.filter {
                    it.second.title.contains(query.text, true) ||
                        it.second.artists.fastAny { a -> a.name.contains(query.text, true) }
                }
            }
        }

    var inSelectMode by rememberSaveable { mutableStateOf(false) }
    val selection =
        rememberSaveable(
            saver =
                listSaver<MutableList<String>, String>(
                    save = { it.toList() },
                    restore = { it.toMutableStateList() },
                ),
        ) { mutableStateListOf() }
    var selectionAnchorSongId by rememberSaveable { mutableStateOf<String?>(null) }
    val onExitSelectionMode = {
        inSelectMode = false
        selection.clear()
        selectionAnchorSongId = null
    }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(isSearching) { if (isSearching) focusRequester.requestFocus() }

    LaunchedEffect(lazyListState, filteredSongs.size, isSearching) {
        snapshotFlow {
            lazyListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index to
                lazyListState.layoutInfo.totalItemsCount
        }.distinctUntilChanged()
            .collect { (lastVisibleIndex, totalItemsCount) ->
                if (!isSearching &&
                    lastVisibleIndex != null &&
                    totalItemsCount > 0 &&
                    lastVisibleIndex >= totalItemsCount - 4
                ) {
                    viewModel.loadMoreSongs()
                }
            }
    }

    LaunchedEffect(filteredSongs) {
        selection.fastForEachReversed { songId ->
            if (filteredSongs.find { it.second.id == songId } == null) {
                selection.remove(songId)
            }
        }

        if (selectionAnchorSongId != null && filteredSongs.none { it.second.id == selectionAnchorSongId }) {
            selectionAnchorSongId = filteredSongs.firstOrNull { it.second.id in selection }?.second?.id
        }
    }

    if (isSearching) {
        BackHandler {
            isSearching = false
            query = TextFieldValue()
        }
    } else if (inSelectMode) {
        BackHandler(onBack = onExitSelectionMode)
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = lazyListState,
            contentPadding = LocalPlayerAwareWindowInsets.current.union(WindowInsets.ime).asPaddingValues(),
        ) {
            if (playlist == null || songs.isEmpty()) {
                if (isLoading) {
                    item(key = "loading_placeholder") {
                        Box(
                            modifier =
                                Modifier
                                    .fillParentMaxSize()
                                    .padding(32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            ContainedLoadingIndicator()
                        }
                    }
                } else if (error != null) {
                    item(key = "error_placeholder") {
                        Column(
                            modifier =
                                Modifier
                                    .fillParentMaxSize()
                                    .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                text = error ?: stringResource(R.string.error_unknown),
                                style = MaterialTheme.typography.bodyLarge,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            androidx.compose.material3.TextButton(onClick = { viewModel.retry() }) {
                                Text(stringResource(R.string.retry))
                            }
                        }
                    }
                } else if (!isLoading && songs.isEmpty()) {
                    item(key = "empty_placeholder") {
                        Box(
                            modifier =
                                Modifier
                                    .fillParentMaxSize()
                                    .padding(32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.playlist_is_empty),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
            } else {
                playlist?.let { playlist ->
                    if (!isSearching) {
                        item(key = "playlist_header") {
                            OnlinePlaylistHeader(
                                playlist = playlist,
                                songs = songs,
                                dbPlaylist = dbPlaylist,
                                navController = navController,
                                coroutineScope = coroutineScope,
                                continuation = viewModel.continuation,
                                isPodcastPlaylist = isPodcastPlaylist,
                                isExternalPlaylist = isExternalPlaylist,
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }

                    itemsIndexed(filteredSongs) { index, (_, songItem) ->
                        val onCheckedChange: (Boolean) -> Unit = {
                            if (it) {
                                selection.add(songItem.id)
                            } else {
                                selection.remove(songItem.id)
                            }
                        }

                        YouTubeListItem(
                            item = songItem,
                            isActive = mediaMetadata?.id == songItem.id,
                            isPlaying = isPlaying,
                            isSelected = inSelectMode && songItem.id in selection,
                            modifier =
                                Modifier
                                    .combinedClickable(
                                        enabled = !hideExplicit || !songItem.explicit,
                                        onClick = {
                                            if (inSelectMode) {
                                                onCheckedChange(songItem.id !in selection)
                                            } else if (songItem.id == mediaMetadata?.id) {
                                                playerConnection.togglePlayPause()
                                            } else {
                                                playerConnection.playQueue(
                                                    YouTubePlaylistQueue(
                                                        playlistId = playlist.id,
                                                        playlistTitle = playlist.title,
                                                        initialSongs = filteredSongs.map { it.second },
                                                        initialContinuation = viewModel.continuation,
                                                        startIndex = index,
                                                    ),
                                                )
                                            }
                                        },
                                        onLongClick = onLongClick@{
                                            if (ExternalHomeItemIds.isExternal(songItem) && !isExternalPlaylist) {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                return@onLongClick
                                            }
                                            if (!inSelectMode) {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                inSelectMode = true
                                                onCheckedChange(true)
                                                selectionAnchorSongId = songItem.id
                                            } else {
                                                val anchorIndex =
                                                    selectionAnchorSongId?.let { anchorSongId ->
                                                        filteredSongs.indexOfFirst { it.second.id == anchorSongId }
                                                    } ?: -1

                                                if (anchorIndex == -1) {
                                                    onCheckedChange(true)
                                                    selectionAnchorSongId = songItem.id
                                                } else {
                                                    val range = if (anchorIndex <= index) anchorIndex..index else index..anchorIndex
                                                    for (rangeIndex in range) {
                                                        val rangeSongId = filteredSongs[rangeIndex].second.id
                                                        if (rangeSongId !in selection) {
                                                            selection.add(rangeSongId)
                                                        }
                                                    }
                                                }
                                            }
                                        },
                                    ).animateItem(),
                            trailingContent = {
                                if (inSelectMode) {
                                    Checkbox(
                                        checked = songItem.id in selection,
                                        onCheckedChange = onCheckedChange,
                                    )
                                } else {
                                    if (!ExternalHomeItemIds.isExternal(songItem)) {
                                        IconButton(onClick = {
                                            menuState.show {
                                                YouTubeSongMenu(songItem, navController, menuState::dismiss)
                                            }
                                        }) {
                                            Icon(painterResource(R.drawable.more_vert), null)
                                        }
                                    }
                                }
                            },
                        )
                    }

                    val visibleSuggestions =
                        if (!isSearching && playlist.isEditable && !isExternalPlaylist) {
                            suggestions.filterNot { suggestion -> songs.any { it.id == suggestion.id } }
                        } else {
                            emptyList()
                        }

                    if (visibleSuggestions.isNotEmpty() ||
                        (isLoadingSuggestions && !isSearching && playlist.isEditable && !isExternalPlaylist)
                    ) {
                        item(key = "playlist_suggestions_header") {
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(start = 24.dp, top = 28.dp, end = 24.dp, bottom = 12.dp)
                                        .animateItem(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = suggestionsTitle,
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(
                                    enabled = !isLoadingSuggestions,
                                    onClick = viewModel::refreshPlaylistSuggestions,
                                ) {
                                    if (isLoadingSuggestions) {
                                        ContainedLoadingIndicator(modifier = Modifier.size(24.dp))
                                    } else {
                                        Icon(
                                            painter = painterResource(R.drawable.refresh),
                                            contentDescription = refreshSuggestionsText,
                                        )
                                    }
                                }
                            }
                        }

                        itemsIndexed(visibleSuggestions, key = { _, song -> "playlist_suggestion_${song.id}" }) { index, songItem ->
                            YouTubeListItem(
                                item = songItem,
                                isActive = mediaMetadata?.id == songItem.id,
                                isPlaying = isPlaying,
                                modifier =
                                    Modifier
                                        .combinedClickable(
                                            enabled = !hideExplicit || !songItem.explicit,
                                            onClick = {
                                                if (songItem.id == mediaMetadata?.id) {
                                                    playerConnection.togglePlayPause()
                                                } else {
                                                    playerConnection.playQueue(
                                                        YouTubePlaylistQueue(
                                                            playlistId = playlist.id,
                                                            playlistTitle = suggestionsTitle,
                                                            initialSongs = visibleSuggestions,
                                                            startIndex = index,
                                                        ),
                                                    )
                                                }
                                            },
                                            onLongClick = {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                menuState.show {
                                                    YouTubeSongMenu(songItem, navController, menuState::dismiss)
                                                }
                                            },
                                        ).animateItem(),
                                trailingContent = {
                                    IconButton(
                                        onClick = {
                                            coroutineScope.launch {
                                                viewModel.addSuggestionToPlaylist(songItem)
                                                    .onSuccess {
                                                        snackbarHostState.showSnackbar(
                                                            message = context.getString(R.string.added_to_playlist),
                                                        )
                                                    }.onFailure {
                                                        snackbarHostState.showSnackbar(
                                                            message = context.getString(R.string.playlist_suggestion_add_failed),
                                                        )
                                                    }
                                            }
                                        },
                                    ) {
                                        Icon(painterResource(R.drawable.playlist_add), null)
                                    }
                                },
                            )
                        }
                    }

                    if (isLoadingMore) {
                        item(key = "loading_more") {
                            Box(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                ContainedLoadingIndicator()
                            }
                        }
                    }
                }
            }
        }

        TopAppBar(
            title = {
                if (inSelectMode) {
                    Text(
                        text =
                            if (isPodcastPlaylist) {
                                pluralStringResource(R.plurals.n_episode, selection.size, selection.size)
                            } else {
                                pluralStringResource(R.plurals.n_song, selection.size, selection.size)
                            },
                        style = MaterialTheme.typography.titleLarge,
                    )
                } else if (isSearching) {
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = {
                            Text(
                                text = stringResource(R.string.search),
                                style = MaterialTheme.typography.titleLarge,
                            )
                        },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.titleLarge,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        colors =
                            TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                disabledIndicatorColor = Color.Transparent,
                            ),
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester),
                    )
                } else if (lazyListState.firstVisibleItemIndex > 0) {
                    Text(playlist?.title ?: "")
                }
            },
            navigationIcon = {
                IconButton(
                    onClick = {
                        if (isSearching) {
                            isSearching = false
                            query = TextFieldValue()
                        } else if (inSelectMode) {
                            onExitSelectionMode()
                        } else {
                            navController.navigateUp()
                        }
                    },
                    onLongClick = {
                        if (!isSearching && !inSelectMode) {
                            navController.backToMain()
                        }
                    },
                ) {
                    Icon(
                        painter =
                            painterResource(
                                if (inSelectMode) R.drawable.close else R.drawable.arrow_back,
                            ),
                        contentDescription = null,
                    )
                }
            },
            actions = {
                if (inSelectMode) {
                    Checkbox(
                        checked = selection.size == filteredSongs.size && selection.isNotEmpty(),
                        onCheckedChange = {
                            if (selection.size == filteredSongs.size) {
                                selection.clear()
                            } else {
                                selection.clear()
                                selection.addAll(filteredSongs.map { it.second.id })
                            }
                        },
                    )
                    IconButton(
                        enabled = selection.isNotEmpty(),
                        onClick = {
                            menuState.show {
                                YouTubeSelectionSongMenu(
                                    songSelection =
                                        filteredSongs
                                            .filter { it.second.id in selection }
                                            .map { it.second },
                                    onDismiss = menuState::dismiss,
                                    clearAction = onExitSelectionMode,
                                )
                            }
                        },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.more_vert),
                            contentDescription = null,
                        )
                    }
                } else if (!isSearching) {
                    IconButton(
                        onClick = { isSearching = true },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.search),
                            contentDescription = null,
                        )
                    }
                }
            },
        )

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun OnlinePlaylistHeader(
    playlist: PlaylistItem,
    songs: List<SongItem>,
    dbPlaylist: Playlist?,
    navController: NavController,
    coroutineScope: CoroutineScope,
    continuation: String?,
    isPodcastPlaylist: Boolean = false,
    isExternalPlaylist: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val listenTogetherManager = LocalListenTogetherManager.current
    val isListenTogetherGuest = listenTogetherManager?.let { it.isInRoom && !it.isHost } ?: false
    val database = LocalDatabase.current
    val menuState = LocalMenuState.current
    val syncUtils = LocalSyncUtils.current

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            modifier =
                Modifier
                    .size(240.dp)
                    .shadow(
                        elevation = 24.dp,
                        shape = RoundedCornerShape(3.dp),
                        spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                    ),
            shape = RoundedCornerShape(3.dp),
        ) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(playlist.thumbnail).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = playlist.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 32.dp),
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Metadata - Song Count • Duration
        val totalDuration = songs.sumOf { it.duration ?: 0 }
        Text(
            text =
                buildString {
                    append(
                        if (isPodcastPlaylist) {
                            pluralStringResource(R.plurals.n_episode, songs.size, songs.size)
                        } else {
                            pluralStringResource(R.plurals.n_song, songs.size, songs.size)
                        },
                    )
                    if (totalDuration > 0) {
                        append(" • ")
                        append(makeTimeString(totalDuration * 1000L))
                    }
                },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
        )

        Spacer(modifier = Modifier.height(24.dp))

        if (!isExternalPlaylist) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
            // Like Button - Smaller secondary button
            Surface(
                onClick = {
                    if (dbPlaylist != null) {
                        database.transaction {
                            val currentPlaylist = dbPlaylist.playlist
                            update(currentPlaylist, playlist)
                            update(currentPlaylist.toggleLike())
                        }
                    } else {
                        coroutineScope.launch(Dispatchers.IO) {
                            val playlistEntity =
                                PlaylistEntity(
                                    name = playlist.title,
                                    browseId = playlist.id,
                                    thumbnailUrl = playlist.thumbnail,
                                    isEditable = playlist.isEditable,
                                    remoteSongCount =
                                        playlist.songCountText?.let {
                                            Regex("""\d+""").find(it)?.value?.toIntOrNull()
                                        },
                                    playEndpointParams = playlist.playEndpoint?.params,
                                    shuffleEndpointParams = playlist.shuffleEndpoint?.params,
                                    radioEndpointParams = playlist.radioEndpoint?.params,
                                ).toggleLike()
                            val songMetadata = songs.map { it.toMediaMetadata() }
                            database.withTransaction {
                                insert(playlistEntity)
                                songMetadata.onEach { insert(it) }
                                val songIds = songMetadata.map { it.id to it.setVideoId }
                                val createdPlaylist =
                                    database.playlistBlocking(playlistEntity.id)
                                        ?: throw IllegalStateException("Failed to create playlist")
                                database.addSongsToPlaylist(createdPlaylist, songIds)
                            }
                        }
                    }
                },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(48.dp),
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter =
                            painterResource(
                                if (dbPlaylist?.playlist?.bookmarkedAt != null) R.drawable.favorite else R.drawable.favorite_border,
                            ),
                        contentDescription = null,
                        tint =
                            if (dbPlaylist?.playlist?.bookmarkedAt != null) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        modifier = Modifier.size(24.dp),
                    )
                }
            }

            // Play Button - Larger primary circular button
            Surface(
                onClick = {
                    if (!isListenTogetherGuest && songs.isNotEmpty()) {
                        playerConnection.playQueue(
                            YouTubePlaylistQueue(
                                playlistId = playlist.id,
                                playlistTitle = playlist.title,
                                initialSongs = songs,
                                initialContinuation = continuation,
                            ),
                        )
                    }
                },
                color = MaterialTheme.colorScheme.primary,
                shape = CircleShape,
                modifier = Modifier.size(72.dp),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.play),
                        contentDescription = stringResource(R.string.play),
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(32.dp),
                    )
                }
            }

            // Menu Button - Smaller secondary button
            Surface(
                onClick = {
                    menuState.show {
                        YouTubePlaylistMenu(
                            playlist = playlist,
                            songs = songs,
                            coroutineScope = coroutineScope,
                            onDismiss = menuState::dismiss,
                        )
                    }
                },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(48.dp),
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.more_vert),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }
}
}
