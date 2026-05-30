/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.constants.SpotifyCookieKey
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import com.metrolist.music.utils.reportException
import com.metrolist.music.utils.spotify.SpotifyCanvasClient
import com.metrolist.music.utils.spotify.isSpotifyCookieConfigured
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SpotifyListeningHistoryViewModel
@Inject
constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {
    val songs = MutableStateFlow<List<SongItem>>(emptyList())
    val isLoading = MutableStateFlow(false)
    val hasCookie = MutableStateFlow(false)
    val loadFailed = MutableStateFlow(false)

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            isLoading.value = true
            loadFailed.value = false
            val cookie = context.dataStore.get(SpotifyCookieKey, "")
            hasCookie.value = isSpotifyCookieConfigured(cookie)
            songs.value =
                if (hasCookie.value) {
                    runCatching {
                        SpotifyCanvasClient.resolveRecentlyPlayed(cookie)
                    }.onFailure {
                        loadFailed.value = true
                        reportException(it)
                    }.getOrDefault(emptyList())
                } else {
                    emptyList()
                }
            isLoading.value = false
        }
    }
}
