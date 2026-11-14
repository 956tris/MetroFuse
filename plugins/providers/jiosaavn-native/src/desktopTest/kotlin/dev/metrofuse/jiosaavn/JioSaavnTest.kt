package dev.metrofuse.jiosaavn

import dev.metrofuse.api.AudioQuery
import dev.metrofuse.api.HomeLayout
import dev.metrofuse.api.HttpRequest
import dev.metrofuse.api.HttpResponse
import dev.metrofuse.api.MetadataDocument
import dev.metrofuse.api.MetadataRequest
import dev.metrofuse.api.PluginNetwork
import dev.metrofuse.api.PluginSettings
import dev.metrofuse.api.QualityPreference
import dev.metrofuse.api.QualityPreset
import dev.metrofuse.api.SearchRequest
import dev.metrofuse.api.TrackIdentity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JioSaavnTest {
    @Test
    fun homePreservesServiceTitlesOrderingAndCatalogTypes() {
        val data =
            Json.parseToJsonElement(
                """
            {"modules":{"songs":{"source":"songs","title":"Tracks &amp; more","position":2},
              "mixed":{"source":"mixed","title":"Albums","position":1}},
              "songs":[{"id":"song_1","type":"song","title":"Song"}],
              "mixed":[{"id":"album_1","type":"album","title":"Album"},{"id":"radio_1","type":"radio","title":"Unsupported"}]}
        """,
            ) as JsonObject
        val shelves = JioHome.shelves(data)
        assertEquals(listOf("Albums", "Tracks & more"), shelves.map { it.title })
        assertEquals(listOf(HomeLayout.CARDS, HomeLayout.ROWS_GRID), shelves.map { it.layout })
        assertEquals(listOf(1, 1), shelves.map { it.items.size })
        assertEquals(
            "album_1",
            shelves
                .first()
                .items
                .single()
                .album
                ?.id,
        )
        assertEquals(
            "jiosaavn:track:song_1",
            shelves
                .last()
                .items
                .single()
                .track
                ?.id,
        )
    }

    private val identity = TrackIdentity("jiosaavn:track:western_1")
    private val locator = "https://aac.saavncdn.com/123/fixture_96.mp4"

    private fun encrypt(url: String): String {
        val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec("38346591".toByteArray(), "DES"))
        return Base64.getEncoder().encodeToString(cipher.doFinal(url.toByteArray()))
    }

    private fun song(
        url: String = locator,
        has320: Boolean = true,
    ) = Json.parseToJsonElement(
        """{"id":"western_1","type":"song","title":"No Idea","image":"https://fixture.invalid/150x150.jpg",
          "more_info":{"album":"HEAVEN OR HELL","album_id":"album_1","duration":"153",
            "artistMap":{"primary_artists":[{"name":"Don Toliver"}]},"encrypted_media_url":"${encrypt(url)}",
            "320kbps":$has320,"vlink":"https://jiotunepreview.jio.com/fixture.mp3"}}""",
    ) as JsonObject

    private class Settings : PluginSettings {
        override val values =
            MutableStateFlow<Map<String, JsonElement>>(
                mapOf(
                    "regionalCatalog" to JsonPrimitive(true),
                    "previewEnabled" to JsonPrimitive(true),
                ),
            )

        override suspend fun set(
            key: String,
            value: JsonElement,
        ) {
            values.value = values.value + (key to value)
        }
    }

    @Test
    fun regionalRoutingCoversSearchMetadataBrowseAudioAndDoesNotCrossCacheRegions() =
        runTest {
            val requests = mutableListOf<HttpRequest>()
            val settings = Settings()
            val fixture = song()
            val network =
                object : PluginNetwork {
                    override suspend fun request(request: HttpRequest): HttpResponse {
                        requests += request
                        val body =
                            when {
                                "song.getDetails" in request.url -> {
                                    if ("X-Real-IP" in
                                        request.headers
                                    ) {
                                        """{"songs":[$fixture]}"""
                                    } else {
                                        """{"songs":[]}"""
                                    }
                                }

                                "content.getAlbumDetails" in request.url -> {
                                    """{"id":"album_1","title":"HEAVEN OR HELL","songs":[$fixture]}"""
                                }

                                "artist.getArtistPageDetails" in request.url -> {
                                    """{"artistId":"artist_1","name":"Don Toliver","topSongs":[$fixture]}"""
                                }

                                "playlist.getDetails" in request.url -> {
                                    """{"listid":"playlist_1","listname":"Playlist","list":[$fixture]}"""
                                }

                                "search.getResults" in request.url -> {
                                    """{"results":[$fixture]}"""
                                }

                                else -> {
                                    """{"results":[]}"""
                                }
                            }
                        return HttpResponse(200, emptyMap(), body)
                    }
                }
            val api = JioApi(network, settings)
            val search = JioSearch(api).search(SearchRequest("Don Toliver & No Idea", 5))
            assertEquals("Don Toliver", search.tracks.single().artist)
            assertTrue(requests.all { "q=Don+Toliver+%26+No+Idea" in it.url })
            val metadata = JioMetadata(api).resolve(MetadataRequest(identity, MetadataDocument(identity)))
            assertEquals(JsonPrimitive("No Idea"), assertNotNull(metadata).fields["title"])
            val audio = JioAudio(api).resolve(AudioQuery(identity, "desktop"))
            val audioStream = assertNotNull(audio.stream)
            assertEquals(locator.replace("_96", "_320"), audioStream.locator)
            assertTrue(audioStream.headers.isEmpty())
            assertEquals(1, requests.count { "song.getDetails" in it.url })
            val browse = JioBrowse(api)
            assertEquals("No Idea", assertNotNull(browse.album("album_1")).tracks.single().title)
            assertEquals("Don Toliver", assertNotNull(browse.artist("artist_1")).name)
            assertEquals("No Idea", assertNotNull(browse.playlist("playlist_1")).tracks.single().title)
            val ips = requests.map { it.headers["X-Real-IP"] }.toSet()
            assertEquals(1, ips.size)
            assertTrue(Regex("103\\.57\\.226\\.\\d+").matches(assertNotNull(ips.single())))
            assertTrue(requests.all { it.headers["X-Forwarded-For"] == ips.single() })
            settings.set("regionalCatalog", JsonPrimitive(false))
            assertNull(api.song(identity), "Turning routing off must not reuse a routed cached song")
            assertTrue("X-Real-IP" !in requests.last().headers)
            assertTrue("X-Forwarded-For" !in requests.last().headers)
            settings.set("regionalCatalog", JsonPrimitive(true))
            assertNotNull(api.song(identity))
            assertEquals(2, requests.count { "song.getDetails" in it.url })
            api.clear()
            assertNotNull(api.song(identity))
            assertEquals(3, requests.count { "song.getDetails" in it.url })
        }

    @Test
    fun mediaDecoderHonorsQualityPreviewsAndValidatesCdnHosts() {
        val full = JioMedia.resolve(song(), QualityPreference(), previews = false)
        val stream = assertNotNull(full.stream)
        assertEquals(320000, stream.bitrate)
        assertEquals(153L, stream.durationSeconds)
        assertEquals("audio/mp4", stream.mimeType)
        assertNull(stream.sampleRate)
        assertNull(stream.expiresAtMillis)
        assertEquals(96000, JioMedia.full(song(), QualityPreference(preset = QualityPreset.LOW)).stream?.bitrate)
        assertEquals(48000, JioMedia.full(song(), QualityPreference(maxBitrate = 48000)).stream?.bitrate)
        assertEquals(160000, JioMedia.full(song(has320 = false), QualityPreference()).stream?.bitrate)
        assertEquals("QUALITY_UNAVAILABLE", JioMedia.resolve(song(), QualityPreference(maxBitrate = 1000), true).error?.code)
        assertEquals("LOSSLESS_UNAVAILABLE", JioMedia.resolve(song(), QualityPreference(lossless = true), true).error?.code)
        for (url in listOf(
            "https://saavncdn.com.evil.invalid/fixture_96.mp4",
            "https://saavncdn.com@evil.invalid/fixture_96.mp4",
            "https://aac.saavncdn.com/fixture_96.mp4\n",
        )) {
            assertNull(JioMedia.decrypt(encrypt(url)))
        }
        assertNull(JioMedia.decrypt("not-base64"))
        val noFull = song("https://fixture.invalid/fixture_96.mp4")
        assertEquals(
            "public JioTune preview excerpt",
            JioMedia
                .resolve(noFull, QualityPreference(), true)
                .stream
                ?.attributes
                ?.get("content"),
        )
        assertEquals("STREAM_DECODE_FAILED", JioMedia.resolve(noFull, QualityPreference(), false).error?.code)
        assertEquals("No Idea", JioCatalog.song(song())?.title)
        assertNull(JioCatalog.id(TrackIdentity("other:track")))
        assertNull(JioCatalog.id(TrackIdentity("jiosaavn:track:../escape")))
    }

    @Test
    fun unavailableSecondarySearchDoesNotHideWorkingSongs() =
        runTest {
            val fixture = song()
            val network =
                object : PluginNetwork {
                    override suspend fun request(request: HttpRequest): HttpResponse =
                        if ("search.getResults" in
                            request.url
                        ) {
                            HttpResponse(200, emptyMap(), """{"results":[$fixture]}""")
                        } else {
                            HttpResponse(503, emptyMap(), "")
                        }
                }
            assertEquals(
                "No Idea",
                JioSearch(JioApi(network, Settings()))
                    .search(SearchRequest("Don Toliver"))
                    .tracks
                    .single()
                    .title,
            )
        }
}
