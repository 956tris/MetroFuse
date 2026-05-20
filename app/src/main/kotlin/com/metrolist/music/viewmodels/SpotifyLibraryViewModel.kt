/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.innertube.pages.HomePage
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
class SpotifyLibraryViewModel
@Inject
constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {
    val libraryPage = MutableStateFlow<HomePage?>(null)
    val isLoading = MutableStateFlow(false)
    val hasCookie = MutableStateFlow(false)

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            isLoading.value = true
            val cookie = context.dataStore.get(SpotifyCookieKey, "")
            hasCookie.value = isSpotifyCookieConfigured(cookie)
            libraryPage.value =
                if (hasCookie.value) {
                    runCatching {
                        SpotifyCanvasClient.resolveLibraryPage(cookie)
                    }.onFailure {
                        reportException(it)
                    }.getOrNull()
                } else {
                    null
                }
            isLoading.value = false
        }
    }
}
