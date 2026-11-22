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
import dev.metrofuse.api.TrackIdentity
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
        val query: String?,
        val manual: Boolean,
    )

    private data class CachedSearch(
        val until: Long,
        val candidates: List<MatchCandidate>,
    )

    private val cache = linkedMapOf<SearchKey, CachedSearch>()

    fun clear() = synchronized(cache) { cache.clear() }

    suspend fun search(
        metadata: Track,
        originalIdentity: TrackIdentity,
        audioSources: List<ProviderEntry<AudioSource>>,
        timeoutMillis: Long,
        query: String? = null,
        manual: Boolean = false,
    ): List<MatchCandidate> {
        if (metadata.title.isBlank() || audioSources.isEmpty()) return emptyList()
        val key = SearchKey(metadata, audioSources.map { it.owner to it.id }, registry.providerEpoch, timeoutMillis, query, manual)
        val cached = synchronized(cache) { cache[key]?.takeIf { it.until > now() }?.candidates }
        val candidates =
            cached ?: searchProviders(metadata, audioSources, timeoutMillis, query, manual).also { result ->
                if (registry.providerEpoch == key.epoch && result.isNotEmpty()) {
                    synchronized(cache) {
                        cache[key] = CachedSearch(now() + 90_000, result)
                        while (cache.size > 256) cache.remove(cache.keys.first())
                    }
                }
            }
        val originalOwner = originalIdentity.identifiers["pluginOwner"] ?: originalIdentity.identifiers["plugin"]
        if (manual) return candidates
        val sourcesById = audioSources.associateBy { it.id }
        val untried =
            candidates.filterNot { candidate ->
                candidate.track.id == originalIdentity.canonicalId &&
                    (originalOwner == null || originalOwner == sourcesById[candidate.provider]?.owner)
            }
        val ranked = ProviderFallbackMatcher.rankSafeCandidates(metadata, untried, audioSources.map { it.id })
        val seenProviders = mutableSetOf<String>()
        // Try each provider's best match before spending attempts on its other results.
        val (bestByProvider, alternatives) = ranked.partition { seenProviders.add(it.provider) }
        return bestByProvider + alternatives
    }

    private suspend fun searchProviders(
        metadata: Track,
        audioSources: List<ProviderEntry<AudioSource>>,
        timeoutMillis: Long,
        query: String?,
        manual: Boolean,
    ): List<MatchCandidate> =
        coroutineScope {
            val owners = audioSources.map { it.owner }.toSet()
            val queries = query?.trim()?.takeIf(String::isNotBlank)?.let(::listOf) ?: ProviderFallbackMatcher.searchQueries(metadata)
            registry
                .searchProviders()
                .filter { it.owner in owners }
                .map { entry ->
                    async {
                        val result =
                            try {
                                withTimeoutOrNull(timeoutMillis.coerceIn(1, 8000)) {
                                    var tracks = emptyList<Track>()
                                    for (term in queries) {
                                        val response =
                                            try {
                                                entry.provider.search(SearchRequest(term, 8))
                                            } catch (cancelled: CancellationException) {
                                                throw cancelled
                                            } catch (error: Exception) {
                                                registry.reportFailure(entry.owner, "catalog.matchSearch", error)
                                                continue
                                            }
                                        val found =
                                            response.tracks.take(8).filter { track ->
                                                track.id.isNotBlank() && track.id.length <= 2048 && track.title.isNotBlank() &&
                                                    track.title.length <= 8192 && track.artist.length <= 8192 && track.extras.size <= 64
                                            }
                                        tracks = (tracks + found).distinctBy { it.id }
                                        if ((manual && found.isNotEmpty()) ||
                                            ProviderFallbackMatcher
                                                .rankSafeCandidates(
                                                    metadata,
                                                    found.map { MatchCandidate(entry.id, it) },
                                                    listOf(entry.id),
                                                ).any()
                                        ) {
                                            break
                                        }
                                    }
                                    if (manual) {
                                        tracks.take(8)
                                    } else {
                                        val safe =
                                            ProviderFallbackMatcher
                                                .rankSafeCandidates(
                                                    metadata,
                                                    tracks.map { MatchCandidate(entry.id, it) },
                                                    listOf(entry.id),
                                                ).map { it.track }
                                                .take(8)
                                                .toList()
                                        safe
                                    }
                                }
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
                                .orEmpty()
                                .take(8)
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
                .take(4096)
        }
}
