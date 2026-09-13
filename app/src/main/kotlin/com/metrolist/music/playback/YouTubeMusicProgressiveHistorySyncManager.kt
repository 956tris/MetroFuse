/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import com.metrolist.innertube.YouTube.ProgressivePlaybackTrackingSession
import com.metrolist.music.models.MediaMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class YouTubeMusicProgressiveHistorySyncManager(
    private val scope: CoroutineScope,
    private val isEnabled: () -> Boolean,
    private val isAuthenticated: () -> Boolean,
    private val currentPositionMs: () -> Long,
    private val startSession: suspend (String) -> ProgressivePlaybackTrackingSession?,
    private val reportProgress: suspend (ProgressivePlaybackTrackingSession, Double, Double, String) -> Boolean,
) {
    private var activeVideoId: String? = null
    private var activeDurationMs: Long? = null
    private var session: ProgressivePlaybackTrackingSession? = null
    private var lastReportedPositionMs = 0L
    private var generation = 0L
    private var sessionJob: Job? = null
    private var heartbeatJob: Job? = null

    fun onSongStart(metadata: MediaMetadata?, durationMs: Long?) {
        val videoId = metadata?.id?.let(YouTubeMusicHistorySyncPolicy::videoIdOrNull) ?: return stop()
        if (!isEnabled() || !isAuthenticated()) return stop()
        if (activeVideoId == videoId) {
            onSongResume()
            return
        }

        stop()
        activeVideoId = videoId
        activeDurationMs = durationMs?.takeIf { it > 0L }
        beginSession(videoId)
    }

    fun onSongPause() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        sendCurrentProgress("paused")
    }

    fun onSongResume() {
        if (!isEnabled() || !isAuthenticated()) return stop()
        val videoId = activeVideoId ?: return
        if (session == null) beginSession(videoId) else scheduleHeartbeats()
    }

    fun onSongStop() {
        val position = currentPositionMs().coerceAtLeast(0L)
        val state = YouTubeMusicProgressiveHistorySyncPolicy.finalState(position, activeDurationMs)
        finish(state)
    }

    fun destroy() = finish("paused")

    private fun beginSession(videoId: String) {
        if (sessionJob != null || session != null) return
        val expectedGeneration = generation
        sessionJob =
            scope.launch {
                val startedSession = startSession(videoId)
                sessionJob = null
                if (
                    startedSession != null &&
                    expectedGeneration == generation &&
                    activeVideoId == videoId &&
                    isEnabled()
                ) {
                    session = startedSession
                    lastReportedPositionMs = currentPositionMs().coerceAtLeast(0L)
                    scheduleHeartbeats()
                }
            }
    }

    private fun scheduleHeartbeats() {
        if (heartbeatJob != null || session == null) return
        val expectedGeneration = generation
        heartbeatJob =
            scope.launch {
                var heartbeatIndex = 0
                while (expectedGeneration == generation && isEnabled()) {
                    delay(YouTubeMusicProgressiveHistorySyncPolicy.heartbeatDelayMs(heartbeatIndex++))
                    if (expectedGeneration != generation || !isEnabled()) break
                    sendCurrentProgress("playing")
                }
                heartbeatJob = null
            }
    }

    private fun sendCurrentProgress(state: String) {
        val currentSession = session ?: return
        val expectedGeneration = generation
        val positionMs = currentPositionMs().coerceAtLeast(0L)
        val (fromMs, toMs) =
            YouTubeMusicProgressiveHistorySyncPolicy.progressWindow(lastReportedPositionMs, positionMs)
        scope.launch {
            if (
                reportProgress(
                    currentSession,
                    fromMs / 1_000.0,
                    toMs / 1_000.0,
                    state,
                ) && expectedGeneration == generation
            ) {
                lastReportedPositionMs = positionMs
            }
        }
    }

    private fun finish(state: String) {
        heartbeatJob?.cancel()
        heartbeatJob = null
        sessionJob?.cancel()
        sessionJob = null
        sendCurrentProgress(state)
        generation++
        activeVideoId = null
        activeDurationMs = null
        session = null
        lastReportedPositionMs = 0L
    }

    private fun stop() = finish("paused")
}

internal object YouTubeMusicProgressiveHistorySyncPolicy {
    private const val shortHeartbeatMs = 10_000L
    private const val regularHeartbeatMs = 40_000L

    fun heartbeatDelayMs(index: Int): Long = if (index < 3) shortHeartbeatMs else regularHeartbeatMs

    fun progressWindow(
        previousPositionMs: Long,
        currentPositionMs: Long,
    ): Pair<Long, Long> {
        val end = currentPositionMs.coerceAtLeast(0L)
        return previousPositionMs.coerceIn(0L, end) to end
    }

    fun finalState(
        positionMs: Long,
        durationMs: Long?,
    ): String =
        if (durationMs != null && durationMs > 0L && positionMs >= durationMs * 95 / 100) {
            "ended"
        } else {
            "paused"
        }
}
