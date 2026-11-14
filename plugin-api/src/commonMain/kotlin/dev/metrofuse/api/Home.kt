package dev.metrofuse.api

import kotlinx.serialization.Serializable

@Serializable
data class HomeSourceMetadata(
    val title: String? = null,
    val icon: String? = null,
)

@Serializable
data class HomeRequest(
    val language: String,
    val country: String,
)

@Serializable
enum class HomeLayout { CARDS, ROWS_GRID }

/** Exactly one catalog value must be supplied for each item. */
@Serializable
data class HomeItem(
    val track: Track? = null,
    val album: CatalogAlbum? = null,
    val artist: CatalogArtist? = null,
    val playlist: CatalogPlaylist? = null,
)

@Serializable
data class HomeShelf(
    val title: String,
    val items: List<HomeItem>,
    val layout: HomeLayout = HomeLayout.CARDS,
)

/** Optional companion interface shared by native JAR and APK plugins. */
interface HomePlugin {
    suspend fun getHome(request: HomeRequest): List<HomeShelf> = emptyList()
}
