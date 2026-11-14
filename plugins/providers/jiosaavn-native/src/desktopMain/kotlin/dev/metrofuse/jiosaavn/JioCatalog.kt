// Metrolist Project (C) 2026, GPL-3.0. See SOURCE.
package dev.metrofuse.jiosaavn

import dev.metrofuse.api.CatalogAlbum
import dev.metrofuse.api.CatalogArtist
import dev.metrofuse.api.CatalogPlaylist
import dev.metrofuse.api.Track
import dev.metrofuse.api.TrackIdentity
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.obj(key: String): JsonObject = get(key) as? JsonObject ?: JsonObject(emptyMap())

internal fun JsonElement?.objects(): List<JsonObject> = (this as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()

internal object JioCatalog {
    private const val PREFIX = "jiosaavn:track:"
    private val identifier = Regex("[A-Za-z0-9_-]{1,80}")
    private val entities = mapOf("&amp;" to "&", "&quot;" to "\"", "&lt;" to "<", "&gt;" to ">", "&#039;" to "'", "&#39;" to "'")
    private val entity = Regex("&(amp|quot|lt|gt|#039|#39);")

    fun decode(value: String?): String = entity.replace(value.orEmpty()) { entities.getValue(it.value) }

    fun id(identity: TrackIdentity): String? =
        (identity.identifiers["jiosaavn"] ?: identity.canonicalId.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX))
            ?.takeIf(identifier::matches)

    fun song(value: JsonObject?): Track? {
        value ?: return null
        if (value.text("type")?.let { it != "song" } == true) return null
        val id = value.text("id")?.takeIf(identifier::matches) ?: return null
        val title = decode(value.text("title") ?: value.text("song")).takeIf(String::isNotBlank) ?: return null
        val more = value.obj("more_info")
        val names =
            value.text("primary_artists")?.split(',') ?: more.obj("artistMap")["primary_artists"].objects().mapNotNull { it.text("name") }
        val artist =
            if (names.isEmpty()) {
                decode(more.text("music"))
            } else {
                names
                    .map {
                        decode(it).trim()
                    }.filter(String::isNotEmpty)
                    .joinToString(", ")
            }
        val albumId = value.text("albumid") ?: more.text("album_id").orEmpty()
        val seconds = (value.text("duration") ?: more.text("duration"))?.toDoubleOrNull()
        val duration = seconds?.takeIf { it.isFinite() && it > 0 && it < Long.MAX_VALUE / 1000 }?.let { (it * 1000 + 0.5).toLong() } ?: 0
        return Track(
            id = PREFIX + id,
            title = title,
            artist = artist,
            album = decode(value.text("album") ?: more.text("album")),
            duration = duration,
            artwork = value.text("image").orEmpty().replace("150x150", "500x500"),
            extras =
                mapOf("jiosaavn" to id, "jiosaavnAlbumId" to albumId) + if (albumId.isEmpty()) emptyMap() else mapOf("albumId" to albumId),
        )
    }

    fun songs(value: JsonElement?): List<Track> = value.objects().mapNotNull(::song)

    fun album(
        value: JsonObject,
        id: String,
    ): CatalogAlbum? {
        val title = value.text("title") ?: value.text("name") ?: return null
        val tracks = songs(value["songs"] as? JsonArray ?: value["list"])
        return CatalogAlbum(
            value.text("albumid") ?: value.text("id") ?: id,
            decode(title),
            decode(value.text("subtitle") ?: value.obj("more_info").text("music") ?: tracks.firstOrNull()?.artist),
            value.text("image").orEmpty(),
            tracks,
        )
    }

    fun artist(
        value: JsonObject,
        id: String,
    ): CatalogArtist? {
        val name = value.text("name") ?: value.text("title") ?: return null
        val songs = value["topSongs"] as? JsonArray ?: value.obj("topSongs")["songs"]
        return CatalogArtist(value.text("artistId") ?: value.text("id") ?: id, decode(name), value.text("image").orEmpty(), songs(songs))
    }

    fun playlist(
        value: JsonObject,
        id: String,
    ): CatalogPlaylist? {
        val title = value.text("listname") ?: value.text("title") ?: return null
        return CatalogPlaylist(
            value.text("listid") ?: value.text("id") ?: id,
            decode(title),
            decode(value.text("firstname") ?: value.obj("more_info").text("firstname") ?: value.text("subtitle")),
            value.text("image").orEmpty(),
            songs(value["list"]),
        )
    }
}
