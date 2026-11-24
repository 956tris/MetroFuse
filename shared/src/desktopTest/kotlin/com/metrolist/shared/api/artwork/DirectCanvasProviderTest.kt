package com.metrolist.shared.api.artwork

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DirectCanvasProviderTest {
    private val now = 1_800_000_000_000L
    private val jwt =
        "eyJhbGciOiJIUzI1NiJ9." +
            Base64.getUrlEncoder().withoutPadding().encodeToString("""{"iss":"AMPWebPlay","exp":4000000000}""".toByteArray()) +
            ".abcdefghijklmnop123456"

    @Test
    fun pluginCanvasWorksWithoutAnyHostSpotifyNetworkCalls() =
        runBlocking {
            HttpClient(MockEngine { error("No hardcoded Spotify network calls may run") }).use { client ->
                val provider =
                    DirectCanvasProvider(client, pluginCanvas = { title, artist, duration, album ->
                        assertEquals(listOf("Song", "Artist", 180000L, "Album"), listOf(title, artist, duration, album))
                        "https://cdn.plugin-example.com/canvas.mp4"
                    })
                assertEquals("https://cdn.plugin-example.com/canvas.mp4", provider.getSpotifyCanvas("Song", "Artist", 180000, "Album"))

                val noPluginProvider = DirectCanvasProvider(client, pluginCanvas = { _, _, _, _ -> null })
                assertNull(noPluginProvider.getSpotifyCanvas("Song", "Artist", 180000, "Album"))
            }
        }

    @Test
    fun unsafePluginCanvasReturnsNullAndCancellationPropagates() =
        runBlocking {
            HttpClient(MockEngine { error("Must not hit Spotify fallback") }).use { client ->
                val unsafe =
                    DirectCanvasProvider(client, pluginCanvas = { _, _, _, _ ->
                        "http://insecure.example.com/fixture.mp4"
                    })
                assertNull(unsafe.getSpotifyCanvas("Song", "Artist", null))

                val local =
                    DirectCanvasProvider(client, pluginCanvas = { _, _, _, _ ->
                        "https://127.0.0.1/fixture.mp4"
                    })
                assertNull(local.getSpotifyCanvas("Song", "Artist", null))

                val started = CompletableDeferred<Unit>()
                val cancellable =
                    DirectCanvasProvider(client, pluginCanvas = { _, _, _, _ ->
                        started.complete(Unit)
                        awaitCancellation()
                    })
                val job = async { cancellable.getSpotifyCanvas("Song", "Artist", null) }
                started.await()
                job.cancelAndJoin()
                assertTrue(job.isCancelled)
            }
        }

    @Test
    fun getCanvasesUsesAppleMusicOnlyForBuiltInAndCachesResults() =
        runBlocking {
            var appleSearches = 0
            val requestedHosts = mutableListOf<String>()
            val client =
                HttpClient(
                    MockEngine { request ->
                        requestedHosts += request.url.host
                        val url = request.url.toString()
                        val body =
                            when {
                                url == "https://music.apple.com/de/browse" -> {
                                    " ".repeat(3 * 1024 * 1024) + """<script type="module" crossorigin src="/assets/index.js"></script>"""
                                }

                                url.endsWith("/assets/index.js") -> {
                                    jwt
                                }

                                url.contains("/search") -> {
                                    appleSearches++
                                    """{"results":{"songs":{"data":[{"id":"123","attributes":{"name":"Song","artistName":"Artist","albumName":"Album","durationInMillis":200000}}]}}}"""
                                }

                                url.contains("/songs/123") -> {
                                    """{"data":[{"relationships":{"albums":{"data":[{"attributes":{"editorialVideo":{"motionSquareVideo1x1":{"video":"https://mvod.itunes.apple.com/art.m3u8"}}}}]}}}]}"""
                                }

                                else -> {
                                    error("Unexpected URL $url")
                                }
                            }
                        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                    },
                )
            client.use {
                val provider =
                    DirectCanvasProvider(
                        client,
                        country = { "DE" },
                        language = { "de-DE" },
                        now = { now },
                        pluginCanvas = { _, _, _, _ -> "https://canvaz.scdn.co/plugin-canvas.mp4" },
                    )
                val result = provider.getCanvases("Song", "Artist", 200_000, "Album")
                assertEquals("https://canvaz.scdn.co/plugin-canvas.mp4", result.spotify)
                assertEquals("https://mvod.itunes.apple.com/art.m3u8", result.appleMusic)
                assertEquals(result, provider.getCanvases("Song", "Artist", 200_000, "Album"))
                assertEquals(1, appleSearches)
                assertTrue(requestedHosts.none { "spotify" in it || "vercel" in it })
            }
        }

    @Test
    fun expiredAppleTokenRefreshesOnceAndRejectsUntrustedMasterUrls() =
        runBlocking {
            var searches = 0
            var bundles = 0
            val client =
                HttpClient(
                    MockEngine { request ->
                        val url = request.url.toString()
                        assertFalse("spotify" in request.url.host)
                        val status = if (url.contains("/search") && searches++ == 0) HttpStatusCode.Unauthorized else HttpStatusCode.OK
                        val body =
                            when {
                                url == "https://music.apple.com/jp/browse" -> {
                                    return@MockEngine respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/jp/new"))
                                }

                                url == "https://music.apple.com/jp/new" -> {
                                    " ".repeat(3 * 1024 * 1024) + """<script type="module" crossorigin src="/assets/index.js"></script>"""
                                }

                                url.endsWith(".js") -> {
                                    bundles++
                                    jwt
                                }

                                url.contains("/search") -> {
                                    """{"results":{"songs":{"data":[{"id":"123","attributes":{"name":"Song","artistName":"Artist"}}]}}}"""
                                }

                                else -> {
                                    """{"data":[{"relationships":{"albums":{"data":[{"attributes":{"editorialVideo":{"motionSquareVideo1x1":{"video":"https://evil.example/file"},"motionDetailSquare":{"video":"https://is1-ssl.mzstatic.com/art.m3u8"}}}}]}}}]}"""
                                }
                            }
                        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
                    },
                )
            client.use {
                val result =
                    DirectCanvasProvider(
                        client,
                        country = { "JP" },
                        language = { "ja-JP" },
                        now = { now },
                        pluginCanvas = { _, _, _, _ -> null },
                    ).getCanvases("Song", "Artist", null)
                assertNull(result.spotify)
                assertEquals("https://is1-ssl.mzstatic.com/art.m3u8", result.appleMusic)
                assertEquals(2, searches)
                assertEquals(2, bundles)
            }
        }

    @Test
    fun rejectsAppleBootstrapRedirectToUntrustedHost() =
        runBlocking {
            var calls = 0
            val client =
                HttpClient(
                    MockEngine { request ->
                        calls++
                        assertEquals("https://music.apple.com/jp/browse", request.url.toString())
                        respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://evil.example/"))
                    },
                )
            client.use {
                val result =
                    DirectCanvasProvider(
                        client,
                        country = { "JP" },
                        language = { "ja-JP" },
                        now = { now },
                        pluginCanvas = { _, _, _, _ -> null },
                    ).getCanvases("Song", "Artist", null)
                assertEquals(DirectCanvasUrls(), result)
                assertEquals(1, calls)
            }
        }

    @Test
    fun rejectsMismatchedTracksUntrustedUrlsAndCancelledWork() =
        runBlocking {
            assertFalse(DirectCanvasProvider.matches("Song", "Artist", "Album", 200_000, "Song", "Artist", "Album", 250_000))
            assertFalse(DirectCanvasProvider.matches("Song", "Artist", null, null, "Song", "Someone Else", null, null))
            assertFalse(DirectCanvasProvider.matches("Song", "DJ Snake", null, null, "Song", "DJ Khaled", null, null))
            assertTrue(DirectCanvasProvider.matches("Song", "Beyonce", null, null, "Song", "Beyoncé", null, null))
            assertFalse(DirectCanvasProvider.matches("Song", "Tyler, the Creator", null, null, "Song", "Tyler, the Composer", null, null))
            assertFalse(DirectCanvasProvider.matches("Song", "DJ Snake, Guest", null, null, "Song", "DJ Snake", null, null))
            assertTrue(
                DirectCanvasProvider.matches(
                    "Song",
                    "DJ Snake, Guest",
                    null,
                    null,
                    "Song",
                    "DJ Snake",
                    null,
                    null,
                    primaryArtist = "DJ Snake",
                    foundPrimaryArtist = "DJ Snake",
                ),
            )
            assertFalse(
                DirectCanvasProvider.matches(
                    "Song",
                    "Tyler, the Creator",
                    null,
                    null,
                    "Song",
                    "Tyler, the Composer",
                    null,
                    null,
                    primaryArtist = "Tyler, the Creator",
                    foundPrimaryArtist = "Tyler, the Composer",
                ),
            )
            assertFalse(DirectCanvasProvider.trustedMedia("https://127.0.0.1/file", false))
            assertFalse(DirectCanvasProvider.trustedMedia("http://mvod.itunes.apple.com/file", true))
            assertFalse(DirectCanvasProvider.trustedMedia("https://user:pass@mvod.itunes.apple.com/file", true))
            val started = CompletableDeferred<Unit>()
            val client =
                HttpClient(
                    MockEngine {
                        started.complete(Unit)
                        awaitCancellation()
                    },
                )
            client.use {
                val provider =
                    DirectCanvasProvider(
                        client,
                        country = { "JP" },
                        language = { "ja-JP" },
                        now = { now },
                        pluginCanvas = { _, _, _, _ -> null },
                    )
                val job = async { provider.getCanvases("Song", "Artist", null) }
                started.await()
                job.cancelAndJoin()
                assertTrue(job.isCancelled)
            }
        }
}
