/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import timber.log.Timber
import kotlin.math.roundToLong

internal data class NextTrackPreloadTarget(
    val mediaItem: MediaItem,
    val queueDistance: Int,
)

internal data class PreloadByteRange(
    val position: Long,
    val length: Long,
)

internal object NextTrackPreloadPolicy {
    const val DEFAULT_COUNT = 3
    const val MIN_COUNT = 0
    const val MAX_COUNT = 10

    private const val KIB = 1024L
    private const val MIB = 1024L * KIB
    private const val FALLBACK_BITRATE = 256_000L

    fun targetBytes(
        queueDistance: Int,
        bitrate: Int,
        contentLength: Long,
    ): Long {
        val seconds = when (queueDistance) {
            1 -> 25L
            2 -> 17L
            else -> 10L
        }
        val maximum = when (queueDistance) {
            1 -> 8L * MIB
            2 -> 5L * MIB
            else -> 3L * MIB
        }
        val estimatedBitrate = bitrate.takeIf { it > 0 }?.toLong() ?: FALLBACK_BITRATE
        val estimatedBytes = (estimatedBitrate * seconds / 8.0).roundToLong()
        val bounded = estimatedBytes.coerceIn(512L * KIB, maximum)
        return contentLength.takeIf { it > 0 }?.let { bounded.coerceAtMost(it) } ?: bounded
    }

    fun missingRange(
        cachedPrefixBytes: Long,
        targetBytes: Long,
    ): PreloadByteRange? {
        val cached = cachedPrefixBytes.coerceAtLeast(0L)
        val target = targetBytes.coerceAtLeast(0L)
        if (target == 0L || cached >= target) return null
        return PreloadByteRange(position = cached, length = target - cached)
    }

    fun <T> selectNext(
        currentIndex: Int,
        windowCount: Int,
        preloadCount: Int,
        repeatMode: Int,
        itemAt: (Int) -> T,
        idOf: (T) -> String,
        nextIndex: (Int) -> Int,
    ): List<Pair<Int, T>> {
        val count = preloadCount.coerceIn(MIN_COUNT, MAX_COUNT)
        if (
            count == 0 ||
            windowCount <= 1 ||
            currentIndex !in 0 until windowCount ||
            repeatMode == Player.REPEAT_MODE_ONE
        ) {
            return emptyList()
        }

        val selected = mutableListOf<Pair<Int, T>>()
        val visitedIndices = mutableSetOf(currentIndex)
        val selectedIds = mutableSetOf<String>()
        var index = currentIndex
        var distance = 0

        while (selected.size < count && visitedIndices.size < windowCount) {
            val candidateIndex = nextIndex(index)
            if (candidateIndex == C.INDEX_UNSET || candidateIndex !in 0 until windowCount) break
            if (!visitedIndices.add(candidateIndex)) break
            index = candidateIndex
            distance++

            val item = itemAt(candidateIndex)
            if (selectedIds.add(idOf(item))) {
                selected += distance to item
            }
        }
        return selected
    }
}

internal data class PreloadWindowDiff(
    val cancel: Set<String>,
    val start: List<String>,
)

internal fun preloadWindowDiff(
    active: Set<String>,
    completed: Set<String>,
    wantedInPriorityOrder: List<String>,
): PreloadWindowDiff {
    val wanted = wantedInPriorityOrder.toSet()
    return PreloadWindowDiff(
        cancel = active - wanted,
        start = wantedInPriorityOrder.filterNot { it in active || it in completed },
    )
}

internal suspend fun runBestEffortPreload(block: suspend () -> Unit): Result<Unit> =
    try {
        block()
        Result.success(Unit)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(error)
    }

internal class NextTrackPreloadCoordinator(
    parentScope: CoroutineScope,
    private var player: Player,
    private val canPreload: () -> Boolean,
    private val isPreparedElsewhere: (String) -> Boolean,
    private val preload: suspend (NextTrackPreloadTarget) -> Unit,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : Player.Listener {
    private val coordinatorJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + coordinatorJob)
    private val updates = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val semaphore = Semaphore(permits = 1)
    private val activeJobs = linkedMapOf<String, Job>()
    private val completedInWindow = mutableSetOf<String>()
    private var preloadCount = NextTrackPreloadPolicy.DEFAULT_COUNT
    private var lastWindowIds = emptyList<String>()

    init {
        player.addListener(this)
        scope.launch {
            updates.collectLatest {
                delay(200)
                refreshWindow(ioDispatcher)
            }
        }
        requestRefresh()
    }

    fun updateCount(value: Int) {
        val normalized = value.coerceIn(NextTrackPreloadPolicy.MIN_COUNT, NextTrackPreloadPolicy.MAX_COUNT)
        if (preloadCount == normalized) return
        preloadCount = normalized
        requestRefresh()
    }

    fun requestRefresh() {
        updates.tryEmit(Unit)
    }

    fun updatePlayer(newPlayer: Player) {
        if (player === newPlayer) return
        player.removeListener(this)
        player = newPlayer
        player.addListener(this)
        activeJobs.values.forEach(Job::cancel)
        activeJobs.clear()
        completedInWindow.clear()
        lastWindowIds = emptyList()
        requestRefresh()
    }

    fun invalidateSelection(): Set<String> {
        val affected = (lastWindowIds + activeJobs.keys + completedInWindow).toSet()
        activeJobs.values.forEach(Job::cancel)
        activeJobs.clear()
        completedInWindow.clear()
        requestRefresh()
        return affected
    }

    fun destroy() {
        player.removeListener(this)
        coordinatorJob.cancel()
        activeJobs.clear()
        completedInWindow.clear()
    }

    override fun onEvents(
        player: Player,
        events: Player.Events,
    ) {
        if (
            events.containsAny(
                Player.EVENT_MEDIA_ITEM_TRANSITION,
                Player.EVENT_TIMELINE_CHANGED,
                Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                Player.EVENT_REPEAT_MODE_CHANGED,
                Player.EVENT_IS_PLAYING_CHANGED,
                Player.EVENT_PLAYBACK_STATE_CHANGED,
            )
        ) {
            requestRefresh()
        }
    }

    private fun refreshWindow(ioDispatcher: CoroutineDispatcher) {
        val targets = if (canPreload()) queueWindow() else emptyList()
        val allWindowIds = targets.map { it.mediaItem.mediaId }
        if (allWindowIds != lastWindowIds) {
            Timber.tag(TAG).d("Preload window: %s", allWindowIds.joinToString(","))
            completedInWindow.retainAll(allWindowIds.toSet())
            lastWindowIds = allWindowIds
        }

        val targetById = targets
            .filterNot { isPreparedElsewhere(it.mediaItem.mediaId) }
            .associateBy { it.mediaItem.mediaId }
        val wantedIds = targets
            .map { it.mediaItem.mediaId }
            .filter { it in targetById }
        val diff = preloadWindowDiff(activeJobs.keys, completedInWindow, wantedIds)

        diff.cancel.forEach { mediaId ->
            activeJobs.remove(mediaId)?.cancel()
            Timber.tag(TAG).d("Cancelled: %s left preload window", mediaId)
        }

        diff.start.forEach { mediaId ->
            val target = targetById.getValue(mediaId)
            val job = scope.launch(ioDispatcher) {
                val runningJob = coroutineContext[Job]
                var completed = true
                try {
                    runBestEffortPreload {
                        semaphore.withPermit {
                            preload(target)
                        }
                    }.exceptionOrNull()?.let { error ->
                        Timber.tag(TAG).w(error, "Failed: mediaId=%s distance=%d", mediaId, target.queueDistance)
                    }
                } catch (error: CancellationException) {
                    completed = false
                    Timber.tag(TAG).d("Cancelled: %s", mediaId)
                    throw error
                } finally {
                    scope.launch(Dispatchers.Main.immediate) {
                        if (activeJobs[mediaId] === runningJob) {
                            activeJobs.remove(mediaId)
                        }
                        if (completed && mediaId in lastWindowIds) {
                            completedInWindow += mediaId
                        }
                    }
                }
            }
            activeJobs[mediaId] = job
        }
    }

    private fun queueWindow(): List<NextTrackPreloadTarget> {
        val timeline = player.currentTimeline
        val currentIndex = player.currentMediaItemIndex
        val window = Timeline.Window()
        return NextTrackPreloadPolicy.selectNext(
            currentIndex = currentIndex,
            windowCount = timeline.windowCount,
            preloadCount = preloadCount,
            repeatMode = player.repeatMode,
            itemAt = { index -> timeline.getWindow(index, window).mediaItem },
            idOf = { it.mediaId },
            nextIndex = { index ->
                timeline.getNextWindowIndex(index, player.repeatMode, player.shuffleModeEnabled)
            },
        ).map { (distance, mediaItem) ->
            NextTrackPreloadTarget(mediaItem = mediaItem, queueDistance = distance)
        }
    }

    private companion object {
        const val TAG = "NextTrackPreload"
    }
}
