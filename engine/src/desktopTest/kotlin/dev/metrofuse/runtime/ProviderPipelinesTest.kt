package dev.metrofuse.runtime

import dev.metrofuse.api.AUDIO_TRACK_METADATA
import dev.metrofuse.api.AudioCapabilities
import dev.metrofuse.api.AudioPolicy
import dev.metrofuse.api.AudioQuery
import dev.metrofuse.api.AudioResult
import dev.metrofuse.api.AudioSource
import dev.metrofuse.api.CatalogAlbum
import dev.metrofuse.api.CatalogProvider
import dev.metrofuse.api.LyricsLine
import dev.metrofuse.api.LyricsProvider
import dev.metrofuse.api.LyricsRequest
import dev.metrofuse.api.LyricsResult
import dev.metrofuse.api.SearchProvider
import dev.metrofuse.api.SearchRequest
import dev.metrofuse.api.SearchResult
import dev.metrofuse.api.StreamDescriptor
import dev.metrofuse.api.StreamTransport
import dev.metrofuse.api.Track
import dev.metrofuse.api.TrackIdentity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProviderPipelinesTest {
    @Test
    fun metadataKeepsUnknownDurationAsZeroAndPreservesKnownPlaybackDuration() {
        val track = Track("fixture", "Song", "Artist", "Album")
        val document = MetadataMapping.fromTrack(track)
        assertEquals(0L, MetadataMapping.toTrack(document, track).duration)
        val measured = track.copy(duration = 145_678)
        assertEquals(145_678L, MetadataMapping.toTrack(document, measured).duration)
    }

    private val query = AudioQuery(TrackIdentity("fixture"), "desktop")
    private fun source(id: String, resolver: suspend () -> AudioResult) = object : AudioSource {
        override val id = id
        override val capabilities = AudioCapabilities(setOf(StreamTransport.DIRECT, StreamTransport.HLS))
        override suspend fun resolve(request: AudioQuery) = resolver()
    }
    private fun stream(headers: Map<String, String> = emptyMap()) = AudioResult(
        stream = StreamDescriptor(StreamTransport.DIRECT, "file:///fixture.wav", headers = headers),
    )

    @Test
    fun trackMetadataIsOptInAndCannotBeOverriddenByProviderSettings() = runTest {
        val registry = Registry()
        val observed = mutableMapOf<String, AudioQuery>()
        fun source(id: String, requirements: Set<String>) =
            object : AudioSource {
                override val id = id
                override val capabilities = AudioCapabilities(setOf(StreamTransport.DIRECT), requirements = requirements)

                override suspend fun resolve(request: AudioQuery): AudioResult {
                    observed[id] = request
                    return stream()
                }
            }
        registry.audioSource("legacy", source("legacy.audio", emptySet()))
        registry.audioSource("metadata", source("metadata.audio", setOf(AUDIO_TRACK_METADATA)))
        val settings = buildJsonObject {
            put("track", "untrusted setting")
            put("option", "enabled")
        }
        var lookups = 0
        val pipeline = ProviderPipelines(registry, trackLookup = {
            lookups++
            assertEquals("fixture", it.canonicalId)
            Track("fixture", "Song", "Artist", "Album", 145_678, extras = mapOf("isrc" to "USAAA1234567"))
        })
        pipeline.audioPolicy.value = AudioPolicy(configurations = mapOf("legacy.audio" to settings, "metadata.audio" to settings))
        pipeline.audioExact(query.copy(sourceId = "legacy.audio"))
        assertEquals(0, lookups)
        assertEquals(settings, observed.getValue("legacy.audio").configuration)
        pipeline.audioExact(query.copy(sourceId = "metadata.audio"))
        assertEquals(1, lookups)
        val actual = observed.getValue("metadata.audio").configuration
        assertEquals(settings["option"], actual["option"])
        val expected = buildJsonObject {
            put("title", "Song")
            put("artist", "Artist")
            put("album", "Album")
            put("durationMillis", 145_678L)
            put("isrc", "USAAA1234567")
        }
        assertEquals(expected, actual["track"])
    }

    @Test fun priorityFailureFallbackAndHeaderPreservation() = runTest {
        val registry = Registry()
        val calls = mutableListOf<String>()
        registry.audioSource("a", source("a.audio") { calls += "a"; error("Unavailable") })
        registry.audioSource("b", source("b.audio") { calls += "b"; stream(mapOf("X-Fixture" to "authorized")) })
        registry.priority("a", 10)
        val result = ProviderPipelines(registry).audio(query)
        assertEquals(listOf("a", "b"), calls)
        assertEquals(mapOf("X-Fixture" to "authorized"), result.stream?.headers)
        assertEquals("b.audio", result.stream?.attributes?.get("providerId"))
    }

    @Test fun invalidHeaderFallsBackAndExcludedProviderIsNotCalled() = runTest {
        val registry = Registry()
        registry.audioSource("a", source("a.audio") { stream(mapOf("X-Test" to "value\r\nInjected: yes")) })
        registry.audioSource("b", source("b.audio") { stream() })
        val pipelines = ProviderPipelines(registry)
        assertEquals("b.audio", pipelines.audio(query).stream?.attributes?.get("providerId"))
        assertNull(pipelines.audio(query, excludedProviders = setOf("b.audio")).stream)
    }

    @Test fun parentCancellationStopsProviderAndDoesNotTryFallback() = runTest {
        val registry = Registry()
        val entered = CompletableDeferred<Unit>()
        var cancelled = false
        var fallback = false
        registry.audioSource("a", source("a.audio") {
            entered.complete(Unit)
            try { awaitCancellation() } finally { cancelled = true }
        })
        registry.audioSource("b", source("b.audio") { fallback = true; stream() })
        val job = async { ProviderPipelines(registry).audio(query) }
        entered.await()
        job.cancelAndJoin()
        assertTrue(cancelled)
        assertFalse(fallback)
    }

    @Test fun deactivatedProviderCannotPublishLateResult() = runTest {
        val registry = Registry()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        registry.audioSource("a", source("a.audio") { entered.complete(Unit); release.await(); stream() })
        val result = async { ProviderPipelines(registry).audio(query) }
        entered.await()
        registry.remove("a")
        release.complete(Unit)
        assertNull(result.await().stream)
    }

    @Test fun typedLyricsAndSearchRegistrationsAreRemovedWithOwner() = runTest {
        val registry = Registry()
        val staged = Registry()
        staged.lyricsProvider("fixture", object : LyricsProvider {
            override val id = "fixture.lyrics"
            override suspend fun lyrics(request: LyricsRequest) = LyricsResult(listOf(LyricsLine("Fixture line", 0)))
        })
        staged.searchProvider("fixture", object : SearchProvider {
            override val id = "fixture.search"
            override suspend fun search(request: SearchRequest) = SearchResult(listOf(Track("song", "Fixture", "Artist", "Album")))
        })
        registry.publish(staged, "fixture")
        val pipelines = ProviderPipelines(registry)
        assertEquals("fixture.lyrics", pipelines.lyrics(LyricsRequest(TrackIdentity("song")))?.source)
        assertEquals("metrofuse:fixture:song", pipelines.search(SearchRequest("Fixture")).tracks.single().id)
        registry.remove("fixture")
        assertNull(pipelines.lyrics(LyricsRequest(TrackIdentity("song"))))
        assertTrue(pipelines.search(SearchRequest("Fixture")).tracks.isEmpty())
    }

    @Test fun catalogOwnershipAndTrackIdentifiersSurviveNavigation() = runTest {
        val registry = Registry()
        registry.catalogProvider("fixture", object : CatalogProvider {
            override val id = "fixture.catalog"
            override suspend fun album(id: String) = CatalogAlbum(id, "Fixture album", "Artist", tracks = listOf(
                Track("song", "Fixture", "Artist", "Album", extras = mapOf("fixtureUri" to "file:///fixture.wav")),
            ))
        })
        val pipelines = ProviderPipelines(registry)
        val album = pipelines.album("metrofuse:fixture:album")
        assertEquals("metrofuse:fixture:album", album?.id)
        assertEquals("metrofuse:fixture:song", album?.tracks?.single()?.id)
        assertEquals("file:///fixture.wav", album?.tracks?.single()?.extras?.get("fixtureUri"))
        assertNull(pipelines.album("metrofuse:another:album"))
        registry.remove("fixture")
        assertNull(pipelines.album("metrofuse:fixture:album"))
    }

    @Test fun topPriorityMetadataAudioResolvesWithoutHostSearchWhileLowerPriorityDefersToHigherSearchableProvider() = runTest {
        val foreignTrack = Track("ytm:abc", "Fixture Song", "Fixture Artist", "Album", 180_000)
        val foreignQuery = AudioQuery(TrackIdentity("abc", mapOf("youtube" to "abc")), "desktop")

        // Case 1: AUDIO_TRACK_METADATA source is top priority -> resolves directly without invoking searchProviders().
        run {
            val registry = Registry()
            var searchCalled = false
            registry.searchProvider("fast", object : SearchProvider {
                override val id = "fast.search"
                override suspend fun search(request: SearchRequest): SearchResult {
                    searchCalled = true
                    return SearchResult(emptyList())
                }
            })
            registry.audioSource("fast", object : AudioSource {
                override val id = "fast.audio"
                override val capabilities = AudioCapabilities(setOf(StreamTransport.DIRECT), requirements = setOf(AUDIO_TRACK_METADATA))
                override suspend fun resolve(request: AudioQuery): AudioResult {
                    val hasTrack = request.configuration["track"] != null
                    return if (hasTrack) stream() else AudioResult(error = dev.metrofuse.api.ProviderError("IDENTITY_REQUIRED"))
                }
            })
            registry.priority("fast", 10)
            val pipelines = ProviderPipelines(registry, trackLookup = { foreignTrack })
            val result = pipelines.audio(foreignQuery)
            assertEquals("fast.audio", result.stream?.attributes?.get("providerId"))
            assertFalse(searchCalled)
        }

        // Case 2: Searchable higher-priority provider ahead of AUDIO_TRACK_METADATA source tries matchSearch before fallback.
        run {
            val registry = Registry()
            val calls = mutableListOf<String>()
            registry.searchProvider("primary", object : SearchProvider {
                override val id = "primary.search"
                override suspend fun search(request: SearchRequest): SearchResult {
                    calls += "primary.search"
                    return SearchResult(listOf(foreignTrack.copy(id = "matched-primary")))
                }
            })
            registry.audioSource("primary", object : AudioSource {
                override val id = "primary.audio"
                override val capabilities = AudioCapabilities(setOf(StreamTransport.DIRECT))
                override suspend fun resolve(request: AudioQuery): AudioResult {
                    calls += "primary.audio:${request.identity.canonicalId}"
                    return if (request.identity.canonicalId == "matched-primary") {
                        stream()
                    } else {
                        AudioResult(error = dev.metrofuse.api.ProviderError("IDENTITY_REQUIRED"))
                    }
                }
            })
            registry.audioSource("secondary", object : AudioSource {
                override val id = "secondary.audio"
                override val capabilities = AudioCapabilities(setOf(StreamTransport.DIRECT), requirements = setOf(AUDIO_TRACK_METADATA))
                override suspend fun resolve(request: AudioQuery): AudioResult {
                    calls += "secondary.audio:${request.configuration["track"] != null}"
                    return if (request.configuration["track"] != null) {
                        stream()
                    } else {
                        AudioResult(error = dev.metrofuse.api.ProviderError("IDENTITY_REQUIRED"))
                    }
                }
            })
            registry.priority("primary", 10)
            registry.priority("secondary", 1)
            val pipelines = ProviderPipelines(registry, trackLookup = { foreignTrack })
            val result = pipelines.audio(foreignQuery)
            assertEquals("primary.audio", result.stream?.attributes?.get("providerId"))
            assertEquals(listOf("primary.audio:abc", "secondary.audio:false", "primary.search", "primary.audio:matched-primary"), calls)
        }
    }
}
