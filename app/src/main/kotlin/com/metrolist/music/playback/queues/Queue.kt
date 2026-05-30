/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.queues

import androidx.media3.common.MediaItem
import com.metrolist.music.extensions.metadata
import com.metrolist.music.models.MediaMetadata

interface Queue {
    val preloadItem: MediaMetadata?

    val playbackContextUri: String?
        get() = null

    suspend fun getInitialStatus(): Status

    fun hasNextPage(): Boolean

    suspend fun nextPage(): List<MediaItem>

    data class Status(
        val title: String?,
        val items: List<MediaItem>,
        val mediaItemIndex: Int,
        val position: Long = 0L,
    ) {
        fun filterExplicit(enabled: Boolean = true) =
            if (enabled) {
                copy(
                    items = items.filterExplicit(),
                )
            } else {
                this
            }

        fun filterVideoSongs(disableVideos: Boolean = false) =
            if (disableVideos) {
                copy(
                    items = items.filterVideoSongs(true),
                )
            } else {
                this
            }
    }
}

fun List<MediaItem>.filterExplicit(enabled: Boolean = true) =
    if (enabled) {
        filterNot {
            it.metadata?.explicit == true
        }
    } else {
        this
    }

fun List<MediaItem>.filterVideoSongs(disableVideos: Boolean = false) =
    if (disableVideos) {
        filterNot { it.metadata?.isVideoSong == true }
    } else {
        this
    }

fun String?.spotifyPlaybackContextUriOrNull(): String? {
    val value = this?.trim()?.trim('/')?.takeIf { it.isNotBlank() } ?: return null
    val normalized =
        when {
            value.startsWith("spotify:playlist:", ignoreCase = true) ||
                value.startsWith("spotify:playlist-format:", ignoreCase = true) ||
                value.startsWith("spotify:album:", ignoreCase = true) ||
                value.equals("spotify:collection:tracks", ignoreCase = true) -> value
            value.startsWith("spotify:user:", ignoreCase = true) &&
                value.contains(":playlist:", ignoreCase = true) -> {
                val id =
                    value
                        .substringAfterLast(":playlist:")
                        .substringBefore('?')
                        .substringBefore('#')
                        .substringBefore('/')
                        .takeIf { it.matches(Regex("^[A-Za-z0-9]{22}$")) }
                id?.let { "spotify:playlist:$it" }
            }
            value.equals("collection:tracks", ignoreCase = true) -> "spotify:collection:tracks"
            value.contains("open.spotify.com/collection/tracks", ignoreCase = true) -> "spotify:collection:tracks"
            value.contains("open.spotify.com/playlist/", ignoreCase = true) -> {
                val id =
                    value
                        .substringAfter("open.spotify.com/playlist/", "")
                        .substringBefore('?')
                        .substringBefore('#')
                        .substringBefore('/')
                        .takeIf { it.matches(Regex("^[A-Za-z0-9]{22}$")) }
                id?.let { "spotify:playlist:$it" }
            }
            value.contains("open.spotify.com/album/", ignoreCase = true) -> {
                val id =
                    value
                        .substringAfter("open.spotify.com/album/", "")
                        .substringBefore('?')
                        .substringBefore('#')
                        .substringBefore('/')
                        .takeIf { it.matches(Regex("^[A-Za-z0-9]{22}$")) }
                id?.let { "spotify:album:$it" }
            }
            else -> null
        }
    return normalized?.takeIf { uri ->
        uri.equals("spotify:collection:tracks", ignoreCase = true) ||
            uri.startsWith("spotify:playlist-format:", ignoreCase = true) ||
            uri.substringAfterLast(':').matches(Regex("^[A-Za-z0-9]{22}$"))
    }
}
