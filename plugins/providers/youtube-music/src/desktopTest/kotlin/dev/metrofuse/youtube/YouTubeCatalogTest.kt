package dev.metrofuse.youtube

import dev.metrofuse.api.TrackIdentity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class YouTubeCatalogTest {
    private fun fixture() = Json.parseToJsonElement(checkNotNull(javaClass.getResource("/youtube-search.json")).readText()).jsonObject

    @Test
    fun searchRetainsProviderLinksAndDistinctCatalogTypes() {
        val result = YouTubeCatalog.search(fixture(), 10)
        val track = result.tracks.single()
        assertEquals("abcdefghijk", track.id)
        assertEquals("Fixture track", track.title)
        assertEquals("Fixture artist", track.artist)
        assertEquals("Fixture album", track.album)
        assertEquals(241000L, track.duration)
        assertEquals("MPREfixture", track.extras["albumId"])
        assertEquals("abcdefghijk", track.extras["youtube"])
        assertEquals("https://example.org/large.jpg", track.artwork)
        assertEquals("MPREfixture", result.albums.single().id)
        assertEquals("UCfixture", result.artists.single().id)
        assertEquals("VLPLfixture", result.playlists.single().id)
    }

    @Test
    fun playlistPreservesRepeatedTrackOccurrences() {
        val playlist = assertNotNull(YouTubeCatalog.playlist(fixture(), "PLfixture"))
        assertEquals(listOf("abcdefghijk", "abcdefghijk"), playlist.tracks.map { it.id })
        assertEquals("Fixture playlist", playlist.title)
        assertEquals("Fixture artist", playlist.author)
        val album = assertNotNull(YouTubeCatalog.album(fixture(), "MPREfixture"))
        assertEquals(1, album.tracks.size)
        assertNull(YouTubeCatalog.album(Json.parseToJsonElement("{}").jsonObject, "missing"))
    }

    @Test
    fun identitiesRequireAnExplicitYouTubeLinkForForeignProviderTracks() {
        assertEquals("abcdefghijk", videoId(TrackIdentity("abcdefghijk")))
        assertEquals("abcdefghijk", videoId(TrackIdentity("abcdefghijk", mapOf("pluginOwner" to "metrofuse.youtube-music"))))
        assertNull(videoId(TrackIdentity("abcdefghijk", mapOf("pluginOwner" to "metrofuse.jiosaavn"))))
        assertEquals("abcdefghijk", videoId(TrackIdentity("other-id", mapOf("youtube" to "abcdefghijk"))))
        assertNull(videoId(TrackIdentity("../../other")))
    }
}
