/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.viewmodels

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.YouTube.SearchFilter.Companion.FILTER_ALBUM
import com.metrolist.innertube.YouTube.SearchFilter.Companion.FILTER_COMMUNITY_PLAYLIST
import com.metrolist.innertube.YouTube.SearchFilter.Companion.FILTER_FEATURED_PLAYLIST
import com.metrolist.innertube.YouTube.SearchFilter.Companion.FILTER_SONG
import com.metrolist.innertube.models.YTItem
import com.metrolist.innertube.models.filterExplicit
import com.metrolist.innertube.models.filterVideoSongs
import com.metrolist.innertube.models.filterYoutubeShorts
import com.metrolist.innertube.pages.SearchSummaryPage
import com.metrolist.music.constants.HomeFeedSource
import com.metrolist.music.constants.HomeFeedSourceKey
import com.metrolist.music.constants.HideExplicitKey
import com.metrolist.music.constants.HideVideoSongsKey
import com.metrolist.music.constants.HideYoutubeShortsKey
import com.metrolist.music.constants.SoundCloudAuthTokenKey
import com.metrolist.music.models.ItemsPage
import com.metrolist.music.providers.SoundCloudHomeFeedProvider
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import com.metrolist.music.utils.reportException
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.net.URLDecoder
import javax.inject.Inject

@HiltViewModel
class OnlineSearchViewModel
@Inject
constructor(
    @ApplicationContext val context: Context,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val query = try {
        URLDecoder.decode(savedStateHandle.get<String>("query")!!, "UTF-8")
    } catch (e: IllegalArgumentException) {
        savedStateHandle.get<String>("query")!!
    }
    val filter = MutableStateFlow<YouTube.SearchFilter?>(null)
    var summaryPage by mutableStateOf<SearchSummaryPage?>(null)
        private set
    var isSoundCloudSearch by mutableStateOf(false)
        private set
    val viewStateMap = mutableStateMapOf<String, ItemsPage?>()

    private suspend fun loadSummaryPage() {
        if (summaryPage == null) {
            isSoundCloudSearch = selectedHomeFeedSource() == HomeFeedSource.SOUNDCLOUD
            if (isSoundCloudSearch) {
                val authToken = context.dataStore.get(SoundCloudAuthTokenKey, "")
                SoundCloudHomeFeedProvider
                    .search(query, authToken)
                    .onSuccess { page ->
                        summaryPage = page
                    }.onFailure {
                        reportException(it)
                    }
            } else {
                YouTube
                    .searchSummary(query)
                    .onSuccess {
                        val hideExplicit = context.dataStore.get(HideExplicitKey, false)
                        val hideVideoSongs = context.dataStore.get(HideVideoSongsKey, false)
                        val hideYoutubeShorts = context.dataStore.get(HideYoutubeShortsKey, false)
                        summaryPage =
                            it.filterExplicit(hideExplicit)
                              .filterVideoSongs(hideVideoSongs)
                              .filterYoutubeShorts(hideYoutubeShorts)
                    }.onFailure {
                        reportException(it)
                    }
            }
        }
    }

    init {
        viewModelScope.launch {
            filter.collect { filter ->
                if (filter == null) {
                    loadSummaryPage()
                } else if (selectedHomeFeedSource() == HomeFeedSource.SOUNDCLOUD) {
                    if (viewStateMap[filter.value] == null) {
                        isSoundCloudSearch = true
                        loadSummaryPage()
                        viewStateMap[filter.value] =
                            ItemsPage(
                                soundCloudItemsForFilter(filter),
                                null,
                            )
                    }
                } else if (filter == YouTube.SearchFilter.FILTER_EPISODE) {
                    // The FILTER_EPISODE API returns episodes in a format that differs from the
                    // summary search: playlistItemData is absent and the subtitle structure is
                    // different, making reliable isEpisode detection fail for many items.
                    // Reuse the "Episodes" section from the summary page instead — it is already
                    // parsed correctly by fromMusicResponsiveListItemRenderer and guaranteed to
                    // show the same results as the episodes section in the "All" filter.
                    if (viewStateMap[filter.value] == null) {
                        loadSummaryPage()
                        summaryPage?.let { page ->
                            val episodes = page.summaries
                                .firstOrNull { it.title == "Episodes" }
                                ?.items
                                .orEmpty()
                            viewStateMap[filter.value] = ItemsPage(episodes, null)
                        }
                    }
                } else {
                    if (viewStateMap[filter.value] == null) {
                        YouTube
                            .search(query, filter)
                            .onSuccess { result ->
                                val hideExplicit = context.dataStore.get(HideExplicitKey, false)
                                val hideVideoSongs = context.dataStore.get(HideVideoSongsKey, false)
                                val hideYoutubeShorts = context.dataStore.get(HideYoutubeShortsKey, false)
                                viewStateMap[filter.value] =
                                    ItemsPage(
                                        result.items
                                            .distinctBy { it.id }
                                            .filterExplicit(hideExplicit)
                                            .filterVideoSongs(hideVideoSongs)
                                            .filterYoutubeShorts(hideYoutubeShorts),
                                        result.continuation,
                                    )
                            }.onFailure {
                                reportException(it)
                            }
                    }
                }
            }
        }
    }

    private suspend fun selectedHomeFeedSource(): HomeFeedSource =
        runCatching {
            HomeFeedSource.valueOf(context.dataStore.get(HomeFeedSourceKey, HomeFeedSource.YOUTUBE_MUSIC.name))
        }.getOrDefault(HomeFeedSource.YOUTUBE_MUSIC)

    private fun soundCloudItemsForFilter(filter: YouTube.SearchFilter): List<YTItem> {
        val summaries = summaryPage?.summaries.orEmpty()
        fun section(title: String): List<YTItem> =
            summaries.firstOrNull { it.title.equals(title, ignoreCase = true) }
                ?.items
                .orEmpty()

        return when (filter) {
            FILTER_SONG -> section("Songs")
            FILTER_ALBUM -> section("Albums")
            FILTER_COMMUNITY_PLAYLIST,
            FILTER_FEATURED_PLAYLIST,
            -> (section("Playlists") + section("Mixes")).distinctBy { it.id }
            else -> emptyList()
        }
    }

    fun loadMore() {
        if (isSoundCloudSearch) return
        val currentFilter = filter.value
        val filterValue = currentFilter?.value ?: return
        viewModelScope.launch {
            val viewState = viewStateMap[filterValue] ?: return@launch
            val continuation = viewState.continuation ?: return@launch
            val searchResult =
                YouTube.searchContinuation(continuation).getOrNull() ?: return@launch
            val hideExplicit = context.dataStore.get(HideExplicitKey, false)
            val hideVideoSongs = context.dataStore.get(HideVideoSongsKey, false)
            val hideYoutubeShorts = context.dataStore.get(HideYoutubeShortsKey, false)
            val newItems = searchResult.items
                .filterExplicit(hideExplicit)
                .filterVideoSongs(hideVideoSongs)
                .filterYoutubeShorts(hideYoutubeShorts)
            viewStateMap[filterValue] = ItemsPage(
                (viewState.items + newItems).distinctBy { it.id },
                searchResult.continuation
            )
        }
    }
}
