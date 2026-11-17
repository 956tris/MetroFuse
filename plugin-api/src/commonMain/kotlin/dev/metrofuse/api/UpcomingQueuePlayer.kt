package dev.metrofuse.api

/** Optional queue reconciliation without restarting the current playback occurrence. */
interface UpcomingQueuePlayer : Player {
    fun replaceUpcomingQueue(tracks: List<Track>)
}
