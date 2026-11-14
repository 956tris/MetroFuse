package dev.metrofuse.api

import kotlinx.serialization.Serializable

@Serializable
data class LyricsRequest(val identity: TrackIdentity, val title: String = "", val artist: String = "", val duration: Long = 0)

@Serializable
data class LyricsWord(val text: String, val startMs: Long, val endMs: Long)

@Serializable
data class LyricsLine(val text: String, val timeMs: Long? = null, val words: List<LyricsWord> = emptyList())

@Serializable
data class LyricsResult(val lines: List<LyricsLine>, val source: String = "", val language: String? = null)

interface LyricsProvider {
    val id: String
    suspend fun lyrics(request: LyricsRequest): LyricsResult?
}

@Serializable
data class SearchRequest(val query: String, val limit: Int = 30)

@Serializable
data class CatalogAlbum(val id: String, val title: String, val artist: String, val artwork: String = "", val tracks: List<Track> = emptyList())

@Serializable
data class CatalogArtist(val id: String, val name: String, val artwork: String = "", val tracks: List<Track> = emptyList(), val albums: List<CatalogAlbum> = emptyList())

@Serializable
data class CatalogPlaylist(val id: String, val title: String, val author: String, val artwork: String = "", val tracks: List<Track> = emptyList())

@Serializable
data class SearchResult(
    val tracks: List<Track>,
    val albums: List<CatalogAlbum> = emptyList(),
    val artists: List<CatalogArtist> = emptyList(),
    val nextPage: String? = null,
    val playlists: List<CatalogPlaylist> = emptyList(),
)

interface SearchProvider {
    val id: String
    suspend fun search(request: SearchRequest): SearchResult
}

interface CatalogProvider {
    val id: String
    suspend fun album(id: String): CatalogAlbum? = null
    suspend fun artist(id: String): CatalogArtist? = null
    suspend fun playlist(id: String): CatalogPlaylist? = null
}
