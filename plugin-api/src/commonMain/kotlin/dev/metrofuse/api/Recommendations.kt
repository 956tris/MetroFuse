package dev.metrofuse.api

import kotlinx.serialization.Serializable

@Serializable
data class RecommendationRequest(
    val identity: TrackIdentity,
    val limit: Int = 100,
    val continuation: String? = null,
)

@Serializable
data class RecommendationResult(
    val tracks: List<Track>,
    val continuation: String? = null,
)

/** Optional companion to a registered CatalogProvider. Continuations belong to that provider. */
interface RecommendationProvider {
    suspend fun recommendations(request: RecommendationRequest): RecommendationResult
}
