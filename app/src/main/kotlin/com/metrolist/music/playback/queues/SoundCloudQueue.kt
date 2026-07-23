/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.queues

import androidx.media3.common.MediaItem
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.providers.SoundCloudHomeFeedProvider
import com.metrolist.music.extensions.toMediaItem

class SoundCloudQueue(
    private val playlistId: String,
    private val authToken: String = "",
    private val sessionClientId: String = "",
    override val preloadItem: MediaMetadata? = null,
) : Queue {
    private var hasMore = false
    
    override suspend fun getInitialStatus(): Queue.Status {
        val page = SoundCloudHomeFeedProvider.loadPlaylist(playlistId, authToken, sessionClientId).getOrNull()
        val items = page?.songs?.map { it.toMediaItem() } ?: emptyList()
        return Queue.Status(
            title = page?.playlist?.title,
            items = items,
            mediaItemIndex = 0
        )
    }

    override fun hasNextPage(): Boolean = hasMore

    override suspend fun nextPage(): List<MediaItem> = emptyList()
}
