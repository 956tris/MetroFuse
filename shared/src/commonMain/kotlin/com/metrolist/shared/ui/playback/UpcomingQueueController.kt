package com.metrolist.shared.ui.playback

import com.metrolist.shared.model.SongItem

/** Changes only the future entries; preserves the current decoder, occurrence and position. */
interface UpcomingQueueController : PlaybackController {
    fun playbackInstanceId(): String? = null

    fun replaceUpcomingQueue(songs: List<SongItem>)
}
