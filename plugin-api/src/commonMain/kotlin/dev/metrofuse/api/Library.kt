package dev.metrofuse.api

import kotlinx.serialization.Serializable

@Serializable
data class LibraryRequest(
    val language: String,
    val country: String,
    val continuation: String? = null,
    val likedSongsOnly: Boolean = false,
)

@Serializable
data class LibraryPage(
    val items: List<HomeItem> = emptyList(),
    val continuation: String? = null,
    val complete: Boolean = true,
)

/** Optional account library companion to a registered CatalogProvider. */
interface LibraryProvider {
    suspend fun library(request: LibraryRequest): LibraryPage

    val supportsSongVotes: Boolean get() = false

    val supportsLikedSongs: Boolean get() = false

    suspend fun setSongVote(request: SongVoteRequest): Boolean = false
}

@Serializable
enum class TrackVote { LIKE, DISLIKE, INDIFFERENT }

@Serializable
data class SongVoteRequest(
    val trackId: String,
    val vote: TrackVote,
)
