/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import com.metrolist.music.models.MediaMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.min

internal class YouTubeMusicHistorySyncManager(
    private val scope: CoroutineScope,
    private val isEnabled: () -> Boolean,
    private val isAuthenticated: () -> Boolean,
    private val reportPlayback: suspend (String, Double) -> Boolean,
) {
    private var syncJob: Job? = null
    private var reportAfterMs = 0L
    private var remainingDelayMs = 0L
    private var delayStartedAtMs = 0L
    private var activeVideoId: String? = null
    private var reportedVideoId: String? = null

    fun onSongStart(metadata: MediaMetadata?, durationMs: Long?) {
        val videoId = metadata?.id?.let(YouTubeMusicHistorySyncPolicy::videoIdOrNull) ?: return stop()
        if (!isEnabled() || !isAuthenticated()) return stop()
        if (activeVideoId == videoId) return onSongResume()

        stop()
        activeVideoId = videoId
        reportAfterMs = YouTubeMusicHistorySyncPolicy.delayBeforeReportMs(durationMs)
        remainingDelayMs = reportAfterMs
        schedule(videoId)
    }

    fun onSongPause() {
        syncJob?.cancel()
        syncJob = null
        if (delayStartedAtMs != 0L) {
            remainingDelayMs = (remainingDelayMs - (System.currentTimeMillis() - delayStartedAtMs)).coerceAtLeast(0L)
            delayStartedAtMs = 0L
        }
    }

    fun onSongResume() {
        val videoId = activeVideoId ?: return
        if (!isEnabled() || !isAuthenticated() || reportedVideoId == videoId) return
        schedule(videoId)
    }

    fun onSongStop() = stop()

    fun destroy() = stop()

    private fun schedule(videoId: String) {
        if (syncJob != null || reportedVideoId == videoId) return
        delayStartedAtMs = System.currentTimeMillis()
        syncJob = scope.launch {
            delay(remainingDelayMs)
            delayStartedAtMs = 0L
            if (activeVideoId == videoId && isEnabled() && isAuthenticated()) {
                if (reportPlayback(videoId, reportAfterMs / 1_000.0)) {
                    reportedVideoId = videoId
                }
            }
            syncJob = null
        }
    }

    private fun stop() {
        syncJob?.cancel()
        syncJob = null
        reportAfterMs = 0L
        remainingDelayMs = 0L
        delayStartedAtMs = 0L
        activeVideoId = null
        reportedVideoId = null
    }
}

internal object YouTubeMusicHistorySyncPolicy {
    private val videoIdPattern = Regex("[A-Za-z0-9_-]{11}")
    private const val fallbackDelayMs = 30_000L
    private const val minimumDelayMs = 5_000L

    fun videoIdOrNull(value: String): String? = value.takeIf(videoIdPattern::matches)

    fun delayBeforeReportMs(durationMs: Long?): Long {
        val halfDuration = durationMs?.takeIf { it > 0L }?.div(2)
        return min(halfDuration ?: fallbackDelayMs, fallbackDelayMs).coerceAtLeast(minimumDelayMs)
    }
}
