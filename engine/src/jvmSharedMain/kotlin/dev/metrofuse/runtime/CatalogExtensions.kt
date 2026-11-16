package dev.metrofuse.runtime

import dev.metrofuse.api.CatalogProvider
import dev.metrofuse.api.LibraryProvider
import dev.metrofuse.api.RecommendationProvider

internal fun catalogWithExtensions(
    catalog: CatalogProvider,
    recommendations: RecommendationProvider?,
    library: LibraryProvider?,
): CatalogProvider =
    when {
        recommendations != null && library != null -> {
            object : CatalogProvider by catalog, RecommendationProvider by recommendations, LibraryProvider by library {}
        }

        recommendations != null -> {
            object : CatalogProvider by catalog, RecommendationProvider by recommendations {}
        }

        library != null -> {
            object : CatalogProvider by catalog, LibraryProvider by library {}
        }

        else -> {
            catalog
        }
    }
