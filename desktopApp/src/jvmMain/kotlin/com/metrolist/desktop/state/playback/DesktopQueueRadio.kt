package com.metrolist.desktop.state.playback

import com.metrolist.shared.model.RadioQueuePage
import com.metrolist.shared.model.RadioRecommendationHistory
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.buildRadioQueueRecommendations
import com.metrolist.shared.playback.MusicQueue
import com.metrolist.shared.playback.QUEUE_SOURCE_AUTO_MIX
import com.metrolist.shared.playback.isRecommendationGeneratedQueueSource
import com.metrolist.shared.plugins.effectiveHomeSelection
import com.metrolist.shared.settings.BlockedContent
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.utils.Logger
import com.metrolist.shared.youtube.playback.PlaybackNetworkStateStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class DesktopQueueRadio(
    private val scope: CoroutineScope,
    private val queue: MusicQueue,
    private val onQueueChanged: () -> Unit,
    private val radio: suspend (String) -> RadioQueuePage = { GlobalYouTubeRepository.instance.startRadioQueue(it) },
    private val isBlocked: (SongItem) -> Boolean = BlockedContent::isBlocked,
    private val continueRadio: suspend (String) -> RadioQueuePage = { GlobalYouTubeRepository.instance.continueRadioQueue(it) },
    private val autoLoadMore: () -> Boolean = { Settings.shared.autoLoadMore },
    private val recommendationHistory: RadioRecommendationHistory = RadioRecommendationHistory(),
    private val sourceKey: () -> String = { "${effectiveHomeSelection().persisted}:${PlaybackNetworkStateStore.current.connected}" },
    private val canPlayQueuedRecommendation: suspend (SongItem) -> Boolean = { true },
) {
    private val mutex = Mutex()
    private var prefetchJob: Job? = null
    private val queuedIds = linkedSetOf<String>()
    private var continuation: String? = null
    private var pendingSongs: List<SongItem> = emptyList()
    private var currentSource: String? = null
    private var revision = 0L
    private var observedSource: String? = null

    suspend fun sourceChanged() {
        val source = sourceKey()
        if (observedSource == source) return
        observedSource = source
        cancelPrefetch()
        revision++
        mutex.withLock {
            if (sourceKey() != source) return@withLock
            continuation = null
            pendingSongs = emptyList()
            currentSource = source
            val removals = mutableSetOf<Int>()
            queue.items.value.forEachIndexed { index, song ->
                if (index > queue.currentIndex.value && song.queueSourceTitle.isRecommendationGeneratedQueueSource() &&
                    !canPlayQueuedRecommendation(song)
                ) {
                    removals += index
                }
            }
            if (sourceKey() != source) return@withLock
            if (queue.removeAt(removals) != null) {
                syncQueue()
                onQueueChanged()
            }
        }
    }

    fun reset() {
        cancelPrefetch()
        continuation = null
        pendingSongs = emptyList()
        currentSource = null
        revision++
    }

    fun cancelPrefetch() {
        prefetchJob?.cancel()
        prefetchJob = null
    }

    fun syncQueue() {
        queuedIds.clear()
        queuedIds.addAll(
            queue.items.value
                .map(SongItem::id)
                .filter(String::isNotBlank),
        )
    }

    fun prefetch(
        seed: SongItem,
        shouldCommit: () -> Boolean,
    ) {
        if (prefetchJob?.isActive == true) return
        var job: Job? = null
        job =
            scope.launch {
                try {
                    load(6, seed, playImmediately = false, shouldCommit)
                } finally {
                    if (prefetchJob === job) prefetchJob = null
                }
            }
        prefetchJob = job
    }

    suspend fun load(
        limit: Int,
        seed: SongItem,
        playImmediately: Boolean,
        shouldCommit: () -> Boolean,
    ): SongItem? {
        val requestSource = sourceKey()
        val requestRevision = revision

        fun canLoad() = autoLoadMore() && shouldCommit() && sourceKey() == requestSource && revision == requestRevision
        if (!canLoad()) return null
        return mutex.withLock {
            if (!canLoad()) return@withLock null
            if (currentSource != requestSource) {
                continuation = null
                pendingSongs = emptyList()
                currentSource = requestSource
            }
            if (playImmediately && queue.currentIndex.value + 1 < queue.items.value.size) {
                return@withLock queue.getNext()?.also { onQueueChanged() }
            }
            if (seed.id.isBlank()) return@withLock null
            try {
                fun eligible(songs: List<SongItem>) =
                    buildRadioQueueRecommendations(
                        songs,
                        queue.items.value.mapTo(mutableSetOf(), SongItem::id) + queuedIds + seed.id,
                        QUEUE_SOURCE_AUTO_MIX,
                        isBlocked,
                        songsOnly = queue.recommendationsSongsOnly,
                    )
                pendingSongs = eligible(pendingSongs)
                if (pendingSongs.isEmpty()) {
                    val token = continuation
                    val firstPage = if (token == null) radio(seed.id) else continueRadio(token)
                    val page =
                        recommendationHistory.select(
                            firstPage = firstPage,
                            filter = ::eligible,
                            isCurrent = ::canLoad,
                            loadMore = continueRadio,
                        ) ?: return@withLock null
                    if (!canLoad()) return@withLock null
                    continuation = page.continuation
                    pendingSongs = page.songs
                }
                if (!canLoad()) return@withLock null
                val candidates = eligible(pendingSongs).take(limit.coerceAtLeast(1))
                val selectedIds = candidates.mapTo(mutableSetOf(), SongItem::id)
                pendingSongs = pendingSongs.filterNot { it.id in selectedIds }
                var changed = false
                for (song in candidates) {
                    if (song.id in queuedIds || queue.items.value.any { it.id == song.id }) continue
                    queue.addToQueue(song.copy(queueSourceTitle = QUEUE_SOURCE_AUTO_MIX))
                    queuedIds.add(song.id)
                    changed = true
                }
                recommendationHistory.remember(candidates)
                val next = if (playImmediately) queue.getNext() else null
                if (changed || next != null) onQueueChanged()
                next
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Logger.w("PlayerState", "Failed to load recommendations (${error::class.simpleName})")
                null
            }
        }
    }
}
