// Metrolist Project (C) 2026, GPL-3.0. See SOURCE.
package dev.metrofuse.jiosaavn

import dev.metrofuse.api.HomeItem
import dev.metrofuse.api.HomeLayout
import dev.metrofuse.api.HomeShelf
import kotlinx.serialization.json.JsonObject

internal object JioHome {
    fun shelves(data: JsonObject): List<HomeShelf> =
        data
            .obj("modules")
            .values
            .filterIsInstance<JsonObject>()
            .sortedBy { it.text("position")?.toIntOrNull() ?: Int.MAX_VALUE }
            .mapNotNull { module ->
                val title = JioCatalog.decode(module.text("title")).takeIf(String::isNotBlank) ?: return@mapNotNull null
                val source = module.text("source") ?: return@mapNotNull null
                val items =
                    data[source].objects().mapNotNull { value ->
                        when (value.text("type")) {
                            "song" -> JioCatalog.song(value)?.let { HomeItem(track = it) }
                            "album" -> JioCatalog.album(value, "")?.takeIf { it.id.isNotBlank() }?.let { HomeItem(album = it) }
                            "playlist" -> JioCatalog.playlist(value, "")?.takeIf { it.id.isNotBlank() }?.let { HomeItem(playlist = it) }
                            else -> null
                        }
                    }
                if (items.isEmpty()) {
                    null
                } else {
                    HomeShelf(
                        title,
                        items,
                        if (items.all { it.track != null }) HomeLayout.ROWS_GRID else HomeLayout.CARDS,
                    )
                }
            }
}
