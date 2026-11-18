/*
 * Metrolist Project (C) 2026, GPL-3.0. See Git history for contributors.
 * Ported from MetroFuse ProviderMatchSearch and MusicService at
 * 816c5543a307113bd04793934670699f227d5342. Provider discovery uses the KMP registry.
 */
package dev.metrofuse.runtime

import dev.metrofuse.api.AudioSource
import dev.metrofuse.api.MatchCandidate
import dev.metrofuse.api.SearchRequest
import dev.metrofuse.api.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

internal class ProviderMatchSearch(
    private val registry: Registry,
    private val now: () -> Long,
) {
    private data class SearchKey(
        val track: Track,
        val sources: List<Pair<String, String>>,
        val epoch: Long,
        val timeoutMillis: Long,
    )

    private data class CachedSearch(
        val until: Long,
        val candidates: List<MatchCandidate>,
    )

    private val cache = linkedMapOf<SearchKey, CachedSearch>()

    fun clear() = synchronized(cache) { cache.clear() }

    suspend fun search(
        metadata: Track,
        audioSources: List<ProviderEntry<AudioSource>>,
        timeoutMillis: Long,
    ): List<MatchCandidate> {
        if (metadata.title.isBlank() || audioSources.isEmpty()) return emptyList()
        val key = SearchKey(metadata, audioSources.map { it.owner to it.id }, registry.providerEpoch, timeoutMillis)
        val cached = synchronized(cache) { cache[key]?.takeIf { it.until > now() }?.candidates }
        val candidates =
            cached ?: searchProviders(metadata, audioSources, timeoutMillis).also { result ->
                if (registry.providerEpoch == key.epoch && result.isNotEmpty()) {
                    synchronized(cache) {
                        cache[key] = CachedSearch(now() + 90_000, result)
                        while (cache.size > 256) cache.remove(cache.keys.first())
                    }
                }
            }
        return ProviderFallbackMatcher.selectSafeCandidates(metadata, candidates, audioSources.map { it.id }, audioSources.size)
    }

    private suspend fun searchProviders(
        metadata: Track,
        audioSources: List<ProviderEntry<AudioSource>>,
        timeoutMillis: Long,
    ): List<MatchCandidate> =
        coroutineScope {
            val owners = audioSources.map { it.owner }.toSet()
            val request = SearchRequest(listOf(metadata.title, metadata.artist).filter { it.isNotBlank() }.joinToString(" "), 4)
            registry
                .searchProviders()
                .filter { it.owner in owners }
                .map { entry ->
                    async {
                        val result =
                            try {
                                withTimeoutOrNull(timeoutMillis.coerceIn(1, 8000)) { entry.provider.search(request) }
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                registry.reportFailure(entry.owner, "catalog.matchSearch", error)
                                null
                            }
                        if (registry.searchProviders().none { it.id == entry.id && it.provider === entry.provider }) {
                            emptyList()
                        } else {
                            result
                                ?.tracks
                                .orEmpty()
                                .take(request.limit)
                                .filter { track ->
                                    track.id.isNotBlank() && track.id.length <= 2048 && track.title.isNotBlank() &&
                                        track.title.length <= 8192 && track.artist.length <= 8192 && track.extras.size <= 64
                                }.flatMap { track ->
                                    audioSources.filter { it.owner == entry.owner }.map { MatchCandidate(it.id, track) }
                                }
                        }
                    }
                }.awaitAll()
                .flatten()
                .distinctBy { it.provider to it.track.id }
        }
}
