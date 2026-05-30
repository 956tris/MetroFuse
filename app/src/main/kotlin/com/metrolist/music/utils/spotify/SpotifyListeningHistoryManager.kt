/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils.spotify

import com.metrolist.music.models.MediaMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber
import kotlin.math.min

class SpotifyListeningHistoryManager(
    private val scope: CoroutineScope,
    private val cookieProvider: suspend () -> String?,
    private val deviceNameProvider: () -> String,
    private val onSpotifyPresenceActiveChanged: (Boolean) -> Unit = {},
    private val onSpotifyAutoplayQueueReady: (MediaMetadata) -> Unit = {},
    var minSongDuration: Int = 30,
    var reportDelayPercent: Float = 0.5f,
    var reportDelaySeconds: Int = 50,
) {
    private val resolveMutex = Mutex()
    private var reportJob: Job? = null
    private var startJob: Job? = null
    private var reportRemainingMillis: Long = 0L
    private var reportTimerStartedAt: Long = 0L
    @Volatile
    private var songStartedAtMs: Long = 0L
    @Volatile
    private var songStarted = false
    @Volatile
    private var activeSpotifyUri: String? = null
    @Volatile
    private var activeSpotifySessionStarted = false
    @Volatile
    private var activeMediaId: String? = null
    @Volatile
    private var activePlaybackContextUri: String? = null
    @Volatile
    private var resolvingMediaId: String? = null
    @Volatile
    private var failedResolutionMediaId: String? = null
    @Volatile
    private var activeMetadata: MediaMetadata? = null
    @Volatile
    private var activeDurationMs: Long = 0L
    @Volatile
    private var activePlaybackPositionMs: Long = 0L
    @Volatile
    private var activePlaybackPositionStartedAtMs: Long = 0L
    @Volatile
    private var lastPlaybackControlPaused: Boolean? = null
    @Volatile
    private var spotifyPresenceActive = false
    @Volatile
    private var currentStateMachineId: String = SpotifyCanvasClient.newListeningHistoryStateMachineId()
    @Volatile
    private var currentTrackStateId: String = SpotifyCanvasClient.newListeningHistoryStateId()
    @Volatile
    private var pendingFinalization: PendingSpotifyFinalization? = null
    private val reportedKeys = LinkedHashSet<String>()

    private data class PendingSpotifyFinalization(
        val trackUri: String,
        val startedAtMs: Long,
        val durationMs: Long,
        val positionMs: Long,
        val stateMachineId: String,
        val stateId: String,
    )

    fun destroy() {
        reportJob?.cancel()
        startJob?.cancel()
        reportJob = null
        startJob = null
        reportRemainingMillis = 0L
        reportTimerStartedAt = 0L
        songStartedAtMs = 0L
        songStarted = false
        activeSpotifyUri = null
        activeSpotifySessionStarted = false
        activeMediaId = null
        activePlaybackContextUri = null
        resolvingMediaId = null
        failedResolutionMediaId = null
        activeMetadata = null
        activeDurationMs = 0L
        activePlaybackPositionMs = 0L
        activePlaybackPositionStartedAtMs = 0L
        lastPlaybackControlPaused = null
        setSpotifyPresenceActive(false)
        currentStateMachineId = SpotifyCanvasClient.newListeningHistoryStateMachineId()
        currentTrackStateId = SpotifyCanvasClient.newListeningHistoryStateId()
        pendingFinalization = null
        reportedKeys.clear()
    }

    fun onSongStart(
        metadata: MediaMetadata?,
        duration: Long? = null,
        playbackContextUri: String? = null,
    ) {
        if (metadata == null) return
        if (metadata.isEpisode || metadata.isVideoSong) {
            setSpotifyPresenceActive(false)
            return
        }
        if (SpotifyCanvasClient.isListeningHistoryBackedOff()) {
            setSpotifyPresenceActive(false)
            return
        }
        val mediaId = metadata.id
        if (songStarted && activeMediaId == mediaId) {
            activeMetadata = metadata
            if (!playbackContextUri.isNullOrBlank()) {
                activePlaybackContextUri = playbackContextUri
            }
            durationSeconds(metadata, duration)
                .takeIf { it > 0 }
                ?.let { activeDurationMs = it * 1000L }
            if (!activeSpotifySessionStarted && resolvingMediaId != mediaId) {
                startSpotifyPlaybackSession(metadata, duration)
            }
            return
        }

        if (songStarted) {
            pendingFinalization = captureSpotifyFinalization()
            stopReportTimer()
            startJob?.cancel()
            startJob = null
        }

        songStartedAtMs = System.currentTimeMillis()
        songStarted = true
        activeSpotifyUri = null
        activeSpotifySessionStarted = false
        activeMediaId = mediaId
        activePlaybackContextUri = playbackContextUri
        resolvingMediaId = null
        failedResolutionMediaId = null
        activeMetadata = metadata
        activeDurationMs = durationSeconds(metadata, duration) * 1000L
        activePlaybackPositionMs = 0L
        activePlaybackPositionStartedAtMs = songStartedAtMs
        lastPlaybackControlPaused = false
        currentTrackStateId = SpotifyCanvasClient.newListeningHistoryStateId()
        startReportTimer(metadata, duration)
        startSpotifyPlaybackSession(metadata, duration)
    }

    fun onSongStop() {
        finalizePendingSpotifyPlaybackSession(nextPlaybackId = null)
        finalizeSpotifyPlaybackSession()
        stopReportTimer()
        startJob?.cancel()
        startJob = null
        songStarted = false
        activeSpotifyUri = null
        activeSpotifySessionStarted = false
        activeMediaId = null
        activePlaybackContextUri = null
        resolvingMediaId = null
        failedResolutionMediaId = null
        activeMetadata = null
        activeDurationMs = 0L
        activePlaybackPositionMs = 0L
        activePlaybackPositionStartedAtMs = 0L
        lastPlaybackControlPaused = null
        setSpotifyPresenceActive(false)
    }

    fun onPacketReceived(packet: JsonObject) {
        val debugSource = packet["debug_source"]?.jsonPrimitive?.contentOrNull
        val stateRef = packet["state_ref"]?.jsonObject ?: return
        stateRef["state_machine_id"]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?.let { currentStateMachineId = it }

        if (debugSource == "before_track_load" || debugSource == "playback_started") {
            stateRef["state_id"]
                ?.jsonPrimitive
                ?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?.let { stateId ->
                    currentTrackStateId = stateId
                    Timber.i("Spotify listening history state handoff detected")
                }
        }
    }

    fun onPlayerStateChanged(
        isPlaying: Boolean,
        metadata: MediaMetadata?,
        duration: Long? = null,
        playbackContextUri: String? = null,
    ) {
        if (metadata == null || metadata.isEpisode || metadata.isVideoSong) return
        if (SpotifyCanvasClient.isListeningHistoryBackedOff()) {
            setSpotifyPresenceActive(false)
            return
        }
        if (isPlaying) {
            if (!songStarted || activeMediaId != metadata.id) {
                onSongStart(metadata, duration, playbackContextUri)
            } else {
                if (!playbackContextUri.isNullOrBlank()) {
                    activePlaybackContextUri = playbackContextUri
                }
                if (activePlaybackPositionStartedAtMs == 0L) {
                    activePlaybackPositionStartedAtMs = System.currentTimeMillis()
                }
                resumeReportTimer(metadata)
                reportPlaybackControl(paused = false)
            }
        } else {
            freezePlaybackPosition()
            pauseReportTimer()
            reportPlaybackControl(paused = true)
        }
    }

    fun onSeek(
        positionMs: Long,
        isPlaying: Boolean,
        metadata: MediaMetadata?,
        duration: Long? = null,
        playbackContextUri: String? = null,
    ) {
        if (metadata == null || metadata.isEpisode || metadata.isVideoSong) return
        if (SpotifyCanvasClient.isListeningHistoryBackedOff()) {
            setSpotifyPresenceActive(false)
            return
        }

        if (!songStarted || activeMediaId != metadata.id) {
            if (isPlaying) {
                onSongStart(metadata, duration, playbackContextUri)
            }
            return
        }

        if (!playbackContextUri.isNullOrBlank()) {
            activePlaybackContextUri = playbackContextUri
        }
        durationSeconds(metadata, duration)
            .takeIf { it > 0 }
            ?.let { activeDurationMs = it * 1000L }

        val durationMs = activeDurationMs.takeIf { it > 0L } ?: metadata.duration * 1000L
        val previousPositionMs = currentPlaybackPositionMs(durationMs)
        val newPositionMs =
            when {
                durationMs > 0L -> positionMs.coerceIn(0L, durationMs)
                else -> positionMs.coerceAtLeast(0L)
            }
        activePlaybackPositionMs = newPositionMs
        activePlaybackPositionStartedAtMs = if (isPlaying) System.currentTimeMillis() else 0L
        val seekDeltaMs =
            if (newPositionMs >= previousPositionMs) {
                newPositionMs - previousPositionMs
            } else {
                previousPositionMs - newPositionMs
            }
        if (seekDeltaMs >= 1_000L) {
            reportSeek(
                previousPositionMs = previousPositionMs,
                positionMs = newPositionMs,
                paused = !isPlaying,
            )
        }
    }

    private fun startReportTimer(
        metadata: MediaMetadata,
        duration: Long? = null,
    ) {
        reportJob?.cancel()
        val durationSeconds = durationSeconds(metadata, duration)
        if (durationSeconds <= minSongDuration) return

        val threshold = durationSeconds * 1000L * reportDelayPercent
        reportRemainingMillis = min(threshold.toLong(), reportDelaySeconds * 1000L)
        if (reportRemainingMillis <= 0L) {
            reportSong(metadata, durationSeconds * 1000L)
            return
        }

        reportTimerStartedAt = System.currentTimeMillis()
        reportJob =
            scope.launch {
                delay(reportRemainingMillis)
                reportSong(metadata, durationSeconds * 1000L)
                reportJob = null
            }
    }

    private fun startSpotifyPlaybackSession(
        metadata: MediaMetadata,
        duration: Long? = null,
    ) {
        if (SpotifyCanvasClient.isListeningHistoryBackedOff()) return
        val mediaId = metadata.id
        if (activeMediaId != mediaId) return
        if (activeSpotifySessionStarted) return
        if (resolvingMediaId == mediaId && startJob?.isActive == true) return
        if (activeSpotifyUri == null && failedResolutionMediaId == mediaId) return
        startJob?.cancel()
        val startedAtMs = songStartedAtMs
        val durationMs = activeDurationMs.takeIf { it > 0L } ?: durationSeconds(metadata, duration) * 1000L
        val playbackContextUri = activePlaybackContextUri
        if (durationMs <= minSongDuration * 1000L) return

        resolvingMediaId = mediaId
        startJob =
            scope.launch(Dispatchers.IO) {
                try {
                    val cookie = cookieProvider()?.takeIf { it.isNotBlank() } ?: return@launch
                    val spotifyUri =
                        resolveSpotifyUriForActive(metadata, cookie, mediaId)
                    if (spotifyUri == null) {
                        failedResolutionMediaId = mediaId
                        finalizePendingSpotifyPlaybackSession(nextPlaybackId = null)
                        return@launch
                    }
                    if (failedResolutionMediaId == mediaId) {
                        failedResolutionMediaId = null
                    }
                    if (!songStarted || songStartedAtMs != startedAtMs || activeMediaId != mediaId) return@launch

                    setSpotifyPresenceActive(true)
                    val started =
                        SpotifyCanvasClient.reportListeningHistoryStartBestEffort(
                            trackUri = spotifyUri,
                            cookie = cookie,
                            startedAtMs = startedAtMs,
                            durationMs = durationMs,
                            stateMachineId = currentStateMachineId,
                            stateId = currentTrackStateId,
                            playbackContextUri = playbackContextUri,
                            deviceName = deviceNameProvider(),
                    )
                    if (!started && activeMediaId == mediaId) {
                        activeSpotifySessionStarted = false
                        setSpotifyPresenceActive(false)
                        finalizePendingSpotifyPlaybackSession(nextPlaybackId = null)
                    } else if (started && activeMediaId == mediaId) {
                        activeSpotifySessionStarted = true
                        val nextPlaybackId =
                            SpotifyCanvasClient.listeningHistoryPlaybackId(
                                trackUri = spotifyUri,
                                startedAtMs = startedAtMs,
                            )
                        finalizePendingSpotifyPlaybackSession(nextPlaybackId = nextPlaybackId)
                        onSpotifyAutoplayQueueReady(metadata)
                    }
                } finally {
                    if (resolvingMediaId == mediaId) {
                        resolvingMediaId = null
                    }
                }
            }
    }

    private fun pauseReportTimer() {
        reportJob?.cancel()
        reportJob = null
        if (reportTimerStartedAt != 0L) {
            reportRemainingMillis -= System.currentTimeMillis() - reportTimerStartedAt
            if (reportRemainingMillis < 0L) reportRemainingMillis = 0L
            reportTimerStartedAt = 0L
        }
    }

    private fun resumeReportTimer(metadata: MediaMetadata) {
        if (reportRemainingMillis <= 0L) return
        reportJob?.cancel()
        reportTimerStartedAt = System.currentTimeMillis()
        reportJob =
            scope.launch {
                delay(reportRemainingMillis)
                reportSong(metadata, metadata.duration * 1000L)
                reportJob = null
            }
    }

    private fun stopReportTimer() {
        reportJob?.cancel()
        reportJob = null
        reportRemainingMillis = 0L
        reportTimerStartedAt = 0L
    }

    private fun captureSpotifyFinalization(): PendingSpotifyFinalization? {
        val metadata = activeMetadata ?: return null
        val spotifyUri = activeSpotifyUri
        val startedAtMs = songStartedAtMs
        val durationMs = activeDurationMs.takeIf { it > 0L } ?: metadata.duration * 1000L
        val positionMs = finalizationPositionMs(durationMs)
        val stateMachineId = currentStateMachineId
        val stateId = currentTrackStateId
        if (!songStarted || startedAtMs <= 0L || durationMs <= minSongDuration * 1000L) return null
        return spotifyUri
            ?.let {
                PendingSpotifyFinalization(
                    trackUri = it,
                    startedAtMs = startedAtMs,
                    durationMs = durationMs,
                    positionMs = positionMs,
                    stateMachineId = stateMachineId,
                    stateId = stateId,
                )
            }
    }

    private fun finalizePendingSpotifyPlaybackSession(nextPlaybackId: String?) {
        val pending = pendingFinalization ?: return
        pendingFinalization = null
        finalizeSpotifyPlaybackSession(
            trackUri = pending.trackUri,
            startedAtMs = pending.startedAtMs,
            durationMs = pending.durationMs,
            positionMs = pending.positionMs,
            stateMachineId = pending.stateMachineId,
            stateId = pending.stateId,
            updateDeviceState = false,
            nextPlaybackId = nextPlaybackId,
        )
    }

    private fun finalizeSpotifyPlaybackSession(updateDeviceState: Boolean = true) {
        val pending = captureSpotifyFinalization() ?: return
        finalizeSpotifyPlaybackSession(
            trackUri = pending.trackUri,
            startedAtMs = pending.startedAtMs,
            durationMs = pending.durationMs,
            positionMs = pending.positionMs,
            stateMachineId = pending.stateMachineId,
            stateId = pending.stateId,
            updateDeviceState = updateDeviceState,
            nextPlaybackId = null,
        )
    }

    private fun finalizeSpotifyPlaybackSession(
        trackUri: String,
        startedAtMs: Long,
        durationMs: Long,
        positionMs: Long,
        stateMachineId: String,
        stateId: String,
        updateDeviceState: Boolean,
        nextPlaybackId: String?,
    ) {
        scope.launch(Dispatchers.IO) {
            val cookie = cookieProvider()?.takeIf { it.isNotBlank() } ?: return@launch
            SpotifyCanvasClient.reportListeningHistoryFinalizedBestEffort(
                trackUri = trackUri,
                cookie = cookie,
                startedAtMs = startedAtMs,
                durationMs = durationMs,
                positionMs = positionMs,
                stateMachineId = stateMachineId,
                stateId = stateId,
                updateDeviceState = updateDeviceState,
                nextPlaybackId = nextPlaybackId,
            )
        }
    }

    private fun reportPlaybackControl(paused: Boolean) {
        if (!songStarted || lastPlaybackControlPaused == paused) return
        val spotifyUri = activeSpotifyUri ?: return
        val metadata = activeMetadata ?: return
        val startedAtMs = songStartedAtMs
        val durationMs = activeDurationMs.takeIf { it > 0L } ?: metadata.duration * 1000L
        if (startedAtMs <= 0L || durationMs <= minSongDuration * 1000L) return
        val positionMs = currentPlaybackPositionMs(durationMs)
        lastPlaybackControlPaused = paused

        scope.launch(Dispatchers.IO) {
            val cookie = cookieProvider()?.takeIf { it.isNotBlank() } ?: return@launch
            SpotifyCanvasClient.reportListeningHistoryPlaybackControlBestEffort(
                trackUri = spotifyUri,
                cookie = cookie,
                startedAtMs = startedAtMs,
                durationMs = durationMs,
                positionMs = positionMs,
                paused = paused,
                stateMachineId = currentStateMachineId,
                stateId = currentTrackStateId,
                deviceName = deviceNameProvider(),
            )
        }
    }

    private fun reportSeek(
        previousPositionMs: Long,
        positionMs: Long,
        paused: Boolean,
    ) {
        if (!songStarted) return
        val spotifyUri = activeSpotifyUri ?: return
        val metadata = activeMetadata ?: return
        val startedAtMs = songStartedAtMs
        val durationMs = activeDurationMs.takeIf { it > 0L } ?: metadata.duration * 1000L
        if (startedAtMs <= 0L || durationMs <= minSongDuration * 1000L) return

        scope.launch(Dispatchers.IO) {
            val cookie = cookieProvider()?.takeIf { it.isNotBlank() } ?: return@launch
            SpotifyCanvasClient.reportListeningHistorySeekBestEffort(
                trackUri = spotifyUri,
                cookie = cookie,
                startedAtMs = startedAtMs,
                durationMs = durationMs,
                positionMs = positionMs,
                previousPositionMs = previousPositionMs,
                paused = paused,
                stateMachineId = currentStateMachineId,
                stateId = currentTrackStateId,
                deviceName = deviceNameProvider(),
            )
        }
    }

    private fun currentPlaybackPositionMs(durationMs: Long = activeDurationMs): Long {
        val now = System.currentTimeMillis()
        val elapsed =
            if (activePlaybackPositionStartedAtMs > 0L) {
                activePlaybackPositionMs + now - activePlaybackPositionStartedAtMs
            } else {
                activePlaybackPositionMs
            }
        return when {
            durationMs > 0L -> elapsed.coerceIn(0L, durationMs)
            else -> elapsed.coerceAtLeast(0L)
        }
    }

    private fun finalizationPositionMs(durationMs: Long): Long {
        val positionMs = currentPlaybackPositionMs(durationMs)
        return when {
            durationMs > 1_000L && positionMs >= durationMs - 250L -> durationMs - 250L
            else -> positionMs
        }
    }

    private fun freezePlaybackPosition() {
        activePlaybackPositionMs = currentPlaybackPositionMs()
        activePlaybackPositionStartedAtMs = 0L
    }

    private fun durationSeconds(
        metadata: MediaMetadata,
        duration: Long? = null,
    ): Int =
        duration
            ?.takeIf { it > 0L }
            ?.div(1000L)
            ?.toInt()
            ?: metadata.duration

    private fun reportSong(
        metadata: MediaMetadata,
        durationMs: Long,
    ) {
        scope.launch(Dispatchers.IO) {
            if (SpotifyCanvasClient.isListeningHistoryBackedOff()) return@launch
            val cookie = cookieProvider()?.takeIf { it.isNotBlank() } ?: return@launch
            val startedAtMs = songStartedAtMs
            val mediaId = metadata.id
            if (activeMediaId != mediaId) return@launch
            val spotifyUri =
                activeSpotifyUri
                    ?: resolveSpotifyUriForActive(metadata, cookie, mediaId)
                    ?: return@launch
            if (!songStarted || songStartedAtMs != startedAtMs || activeMediaId != mediaId) return@launch

            val reportKey = "$spotifyUri:${startedAtMs / 30_000L}"
            synchronized(reportedKeys) {
                if (!reportedKeys.add(reportKey)) return@launch
                while (reportedKeys.size > 64) {
                    reportedKeys.remove(reportedKeys.first())
                }
            }

            val wasSpotifyPresenceActive = spotifyPresenceActive
            setSpotifyPresenceActive(true)
            val reported =
                SpotifyCanvasClient.reportListeningHistoryBestEffort(
                    trackUri = spotifyUri,
                    cookie = cookie,
                    startedAtMs = startedAtMs,
                    durationMs = durationMs,
                    positionMs = currentPlaybackPositionMs(durationMs),
                    stateMachineId = currentStateMachineId,
                    stateId = currentTrackStateId,
                    playbackContextUri = activePlaybackContextUri,
                    deviceName = deviceNameProvider(),
                )
            if (!reported) {
                Timber.i("Spotify listening history write unavailable for matched track %s", spotifyUri)
                if (!wasSpotifyPresenceActive && activeMediaId == mediaId) {
                    setSpotifyPresenceActive(false)
                }
            } else if (activeMediaId == mediaId) {
                activeSpotifySessionStarted = true
                val nextPlaybackId =
                    SpotifyCanvasClient.listeningHistoryPlaybackId(
                        trackUri = spotifyUri,
                        startedAtMs = startedAtMs,
                    )
                finalizePendingSpotifyPlaybackSession(nextPlaybackId = nextPlaybackId)
            }
        }
    }

    private fun setSpotifyPresenceActive(active: Boolean) {
        if (spotifyPresenceActive == active) return
        spotifyPresenceActive = active
        onSpotifyPresenceActiveChanged(active)
    }

    private suspend fun resolveSpotifyUriForActive(
        metadata: MediaMetadata,
        cookie: String,
        mediaId: String,
    ): String? {
        activeSpotifyUri?.let { return it }
        return resolveMutex.withLock {
            if (activeMediaId != mediaId) return@withLock null
            activeSpotifyUri?.let { return@withLock it }
            val resolved = resolveSpotifyUri(metadata, cookie) ?: return@withLock null
            if (activeMediaId == mediaId) {
                activeSpotifyUri = resolved
                resolved
            } else {
                null
            }
        }
    }

    private suspend fun resolveSpotifyUri(
        metadata: MediaMetadata,
        cookie: String,
    ): String? =
        runCatching {
            SpotifyCanvasClient.resolveTrackUriForHistory(metadata, cookie)
        }.onFailure { error ->
            Timber.w(error, "Spotify listening history match failed")
            if (error.message?.contains("429") == true) {
                SpotifyCanvasClient.deferListeningHistoryRequests("Spotify listening history match rate limited")
            }
        }.getOrNull()
}
