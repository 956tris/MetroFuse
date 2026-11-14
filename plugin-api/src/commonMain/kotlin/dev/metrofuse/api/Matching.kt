package dev.metrofuse.api

import kotlinx.serialization.Serializable

@Serializable
data class MatchCandidate(val provider: String, val track: Track)

interface TrackMatcher {
    fun selectSafeCandidates(
        metadata: Track,
        candidates: List<MatchCandidate>,
        providerOrder: List<String>,
        maximumCandidates: Int = providerOrder.size.coerceAtLeast(1),
    ): List<MatchCandidate>
}
