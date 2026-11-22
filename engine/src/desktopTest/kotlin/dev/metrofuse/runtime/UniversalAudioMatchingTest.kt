package dev.metrofuse.runtime

import dev.metrofuse.api.AudioCapabilities
import dev.metrofuse.api.AudioPolicy
import dev.metrofuse.api.AudioQuery
import dev.metrofuse.api.AudioResult
import dev.metrofuse.api.AudioSource
import dev.metrofuse.api.ProviderError
import dev.metrofuse.api.QualityPreference
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

class UniversalAudioMatchingTest {
    @Test
    fun aFailedStreamCanTryAnotherRecordingFromTheSameProvider() =
        runTest {
            val registry = Registry()
            registry.audioSource(
                "destination",
                source("destination.audio") { request ->
                    if (request.identity.canonicalId in setOf("first", "second")) stream() else unavailable()
                },
            )
            registry.searchProvider(
                "destination",
                search("destination.search") {
                    listOf(metadata.copy(id = "first"), metadata.copy(id = "second", duration = metadata.duration + 1000))
                },
            )
            val pipelines = ProviderPipelines(registry, trackLookup = { metadata })
            val first = pipelines.audio(query).stream!!
            assertEquals("first", first.attributes["resolvedMediaId"])
            val second = pipelines.audio(query, excludedProviders = setOf(first.attributes.getValue("matchProfile"))).stream!!
            assertEquals("destination.audio", second.attributes["providerId"])
            assertEquals("second", second.attributes["resolvedMediaId"])
        }

    @Test
    fun manualSelectionDoesNotSilentlyTryOtherProvidersOrRecordings() =
        runTest {
            val registry = Registry()
            var otherSourceCalled = false
            var searchCalled = false
            registry.audioSource("selected", source("selected.audio") { unavailable() })
            registry.audioSource(
                "other",
                source("other.audio") {
                    otherSourceCalled = true
                    stream()
                },
            )
            registry.searchProvider(
                "selected",
                search("selected.search") {
                    searchCalled = true
                    listOf(metadata.copy(id = "another-recording"))
                },
            )
            val pipelines = ProviderPipelines(registry, trackLookup = { metadata })
            pipelines.audioPolicy.value = AudioPolicy(manualSourceFallback = true)
            val selected =
                AudioQuery(TrackIdentity("manual-id", mapOf("pluginOwner" to "selected")), "desktop", sourceId = "selected.audio")
            assertNull(pipelines.audioExact(selected).stream)
            assertFalse(otherSourceCalled)
            assertFalse(searchCalled)
        }

    @Test
    fun cleanedQueriesRecoverDecoratedUploadsAndUnsupportedRecordingCodeSearches() =
        runTest {
            val registry = Registry()
            val terms = mutableListOf<String>()
            val upload =
                Track(
                    "upload",
                    "Artist - Song (Official Audio)",
                    "Uploader",
                    "Album",
                    180_000,
                    extras = mapOf("isrc" to "USUM72401234"),
                )
            registry.audioSource(
                "destination",
                source("destination.audio") { request ->
                    if (request.identity.canonicalId == "recording") stream() else unavailable()
                },
            )
            registry.searchProvider(
                "destination",
                search("destination.search") { request ->
                    terms += request.query
                    when {
                        request.query.startsWith("isrc:") -> error("Recording-code search unsupported")
                        request.query == "song Artist" -> listOf(Track("recording", "Song", "Artist", "Album", 181_000))
                        else -> emptyList()
                    }
                },
            )
            val pipelines = ProviderPipelines(registry, trackLookup = { upload })
            val resolved = pipelines.audio(AudioQuery(TrackIdentity("upload"), "desktop"))
            assertEquals("recording", resolved.stream?.attributes?.get("resolvedMediaId"))
            assertTrue("isrc:USUM72401234" in terms)
            assertTrue("song Artist" in terms)
        }

    @Test
    fun manualSearchAllowsUserChosenVariantsAndKeepsCustomQueriesSeparate() =
        runTest {
            val registry = Registry()
            val terms = mutableListOf<String>()
            registry.audioSource("destination", source("destination.audio") { stream() })
            registry.searchProvider(
                "destination",
                search("destination.search") { request ->
                    terms += request.query
                    listOf(metadata.copy(id = request.query, title = "Different recording (Live)", artist = "Other artist"))
                },
            )
            val pipelines = ProviderPipelines(registry, trackLookup = { metadata })
            val first = pipelines.audioMatches(metadata, original, "desktop", "first query")
            val second = pipelines.audioMatches(metadata, original, "desktop", "second query")
            assertEquals(listOf("first query"), first.map { it.track.id })
            assertEquals(listOf("second query"), second.map { it.track.id })
            pipelines.audioMatches(metadata, original, "desktop", "first query")
            assertEquals(listOf("first query", "second query"), terms)
        }

    private val metadata = Track("ytm:original", "Café del Mar", "José", "Album", 180_000)
    private val original = TrackIdentity("abcdefghijk", mapOf("youtube" to "abcdefghijk", "pluginOwner" to "ytm", "host" to metadata.id))
    private val query = AudioQuery(original, "desktop", QualityPreference(maxBitrate = 160_000))

    private fun source(
        id: String,
        resolve: suspend (AudioQuery) -> AudioResult,
    ) = object : AudioSource {
        override val id = id
        override val capabilities = AudioCapabilities(setOf(StreamTransport.DIRECT))

        override suspend fun resolve(request: AudioQuery) = resolve(request)
    }

    private fun search(
        id: String,
        response: suspend (SearchRequest) -> List<Track>,
    ) = object : SearchProvider {
        override val id = id

        override suspend fun search(request: SearchRequest) = SearchResult(response(request))
    }

    private fun stream() = AudioResult(stream = StreamDescriptor(StreamTransport.DIRECT, "https://audio.example.com/song.mp4"))

    private fun unavailable() = AudioResult(error = ProviderError("IDENTITY_REQUIRED"))

    @Test
    fun foreignMetadataIsMatchedCentrallyAndOnlyTheDestinationIdentityIsUsed() =
        runTest {
            val registry = Registry()
            val calls = mutableListOf<AudioQuery>()
            var searches = 0
            registry.audioSource(
                "destination",
                source("destination.audio") { request ->
                    calls += request
                    if (request.identity.canonicalId == "matched-id") stream() else unavailable()
                },
            )
            registry.searchProvider(
                "destination",
                search("destination.catalog") { request ->
                    searches++
                    assertEquals("Café del Mar José", request.query)
                    listOf(
                        metadata.copy(id = "wrong-artist", artist = "Other"),
                        metadata.copy(id = "matched-id", title = "Cafe del Mar", artist = "Jose"),
                    )
                },
            )
            val pipelines =
                ProviderPipelines(registry, trackLookup = { identity ->
                    assertEquals(original, identity)
                    metadata
                })
            val configuration = buildJsonObject { put("fixture", true) }
            pipelines.audioPolicy.value = AudioPolicy(configurations = mapOf("destination.audio" to configuration))
            assertEquals(
                "destination.audio",
                pipelines
                    .audio(query)
                    .stream
                    ?.attributes
                    ?.get("providerId"),
            )
            assertEquals(
                "matched-id",
                pipelines
                    .audio(query)
                    .stream
                    ?.attributes
                    ?.get("resolvedMediaId"),
            )
            val matched = calls.last()
            assertEquals("matched-id", matched.identity.canonicalId)
            assertEquals("destination", matched.identity.identifiers["pluginOwner"])
            assertNull(matched.identity.identifiers["youtube"])
            assertNull(matched.identity.identifiers["host"])
            assertEquals(query.quality, matched.quality)
            assertEquals(configuration, matched.configuration)
            pipelines.audio(query)
            assertEquals(1, searches)
            pipelines.invalidateCaches()
            pipelines.audio(query)
            assertEquals(2, searches)
        }

    @Test
    fun unrelatedRecordingIsNeverResolvedAfterTheSearch() =
        runTest {
            val registry = Registry()
            val calls = mutableListOf<TrackIdentity>()
            registry.audioSource(
                "destination",
                source("destination.audio") {
                    calls += it.identity
                    unavailable()
                },
            )
            registry.searchProvider(
                "destination",
                search("destination.search") {
                    listOf(metadata.copy(id = "wrong-artist", artist = "Other"), metadata.copy(id = "too-long", duration = 360_000))
                },
            )
            val pipelines = ProviderPipelines(registry, trackLookup = { metadata })
            assertNull(pipelines.audio(query).stream)
            assertEquals(listOf(original), calls)
        }

    @Test
    fun preferredAndExcludedSourcesConstrainTheUniversalSearch() =
        runTest {
            val registry = Registry()
            val searches = mutableListOf<String>()
            for (owner in listOf("a", "b")) {
                registry.audioSource(
                    owner,
                    source("$owner.audio") {
                        if (it.identity.canonicalId ==
                            "$owner-match"
                        ) {
                            stream()
                        } else {
                            unavailable()
                        }
                    },
                )
                registry.searchProvider(
                    owner,
                    search("$owner.search") {
                        searches += owner
                        listOf(metadata.copy(id = "$owner-match"))
                    },
                )
            }
            val pipelines = ProviderPipelines(registry, trackLookup = { metadata })
            pipelines.audioPolicy.value = AudioPolicy(preferredSource = "b.audio")
            assertEquals(
                "b.audio",
                pipelines
                    .audio(query)
                    .stream
                    ?.attributes
                    ?.get("providerId"),
            )
            assertEquals(listOf("b"), searches)
            assertNull(pipelines.audio(query, excludedProviders = setOf("b.audio")).stream)
            assertEquals(listOf("b"), searches)
        }

    @Test
    fun confidenceThenProviderOrderControlFallbackAndFailuresContinueToTheNextMatch() =
        runTest {
            val registry = Registry()
            val matchedAttempts = mutableListOf<String>()
            for (owner in listOf("a", "b")) {
                registry.audioSource(
                    owner,
                    source("$owner.audio") {
                        if (it.identity != original) matchedAttempts += owner
                        if (owner == "b" && it.identity.canonicalId == "b-match") stream() else unavailable()
                    },
                )
                registry.searchProvider(owner, search("$owner.search") { listOf(metadata.copy(id = "$owner-match")) })
            }
            registry.priority("a", 10)
            assertEquals(
                "b.audio",
                ProviderPipelines(registry, trackLookup = { metadata })
                    .audio(query)
                    .stream
                    ?.attributes
                    ?.get("providerId"),
            )
            assertEquals(listOf("a", "b"), matchedAttempts)
        }

    @Test
    fun flacFailureFallsBackToAnotherSafeYouTubeMusicMatch() =
        runTest {
            val registry = Registry()
            val attempts = mutableListOf<Pair<String, String>>()
            registry.audioSource(
                "flac",
                source("flac.audio") {
                    attempts += "flac.audio" to it.identity.canonicalId
                    AudioResult(error = ProviderError("RELAY_UNAVAILABLE"))
                },
            )
            registry.priority("flac", 10)
            registry.audioSource(
                "ytm",
                source("ytm.audio") {
                    val id = it.identity.canonicalId
                    attempts += "ytm.audio" to id
                    if (id == "playable") stream() else unavailable()
                },
            )
            registry.searchProvider(
                "ytm",
                search("ytm.search") {
                    listOf(
                        metadata.copy(id = original.canonicalId),
                        metadata.copy(id = "unplayable"),
                        metadata.copy(id = "playable"),
                    )
                },
            )
            val result = ProviderPipelines(registry, trackLookup = { metadata }).audio(query)
            assertEquals("ytm.audio", result.stream?.attributes?.get("providerId"))
            assertEquals("playable", result.stream?.attributes?.get("resolvedMediaId"))
            assertEquals(
                listOf(
                    "flac.audio" to original.canonicalId,
                    "ytm.audio" to original.canonicalId,
                    "ytm.audio" to "unplayable",
                    "ytm.audio" to "playable",
                ),
                attempts,
            )
        }

    @Test
    fun directResolutionDoesNotSearchOrLookUpMetadata() =
        runTest {
            val registry = Registry()
            registry.audioSource("direct", source("direct.audio") { stream() })
            registry.searchProvider("direct", search("direct.search") { error("Direct playback must not search") })
            val pipelines = ProviderPipelines(registry, trackLookup = { error("Direct playback must not look up metadata") })
            assertEquals(
                "direct.audio",
                pipelines
                    .audio(query)
                    .stream
                    ?.attributes
                    ?.get("providerId"),
            )
        }

    @Test
    fun oneSearchFailureDoesNotBlockOtherProvidersAndMissingMetadataNeverSearches() =
        runTest {
            val registry = Registry()
            registry.audioSource("broken", source("broken.audio") { unavailable() })
            registry.audioSource(
                "working",
                source("working.audio") {
                    if (it.identity.canonicalId ==
                        "matched"
                    ) {
                        stream()
                    } else {
                        unavailable()
                    }
                },
            )
            registry.searchProvider("broken", search("broken.search") { error("Service unavailable") })
            var searches = 0
            registry.searchProvider(
                "working",
                search("working.search") {
                    searches++
                    listOf(metadata.copy(id = "matched"))
                },
            )
            assertEquals(
                "working.audio",
                ProviderPipelines(registry, trackLookup = { metadata })
                    .audio(query)
                    .stream
                    ?.attributes
                    ?.get("providerId"),
            )
            assertNull(ProviderPipelines(registry).audio(query).stream)
            assertEquals(1, searches)
        }

    @Test
    fun cancellationStopsCentralSearchWithoutResolvingTheCandidate() =
        runTest {
            val registry = Registry()
            val entered = CompletableDeferred<Unit>()
            var cancelled = false
            var matched = false
            registry.audioSource(
                "destination",
                source("destination.audio") {
                    if (it.identity != original) matched = true
                    unavailable()
                },
            )
            registry.searchProvider(
                "destination",
                search("destination.search") {
                    entered.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled = true
                    }
                },
            )
            val task = async { ProviderPipelines(registry, trackLookup = { metadata }).audio(query) }
            entered.await()
            task.cancelAndJoin()
            assertTrue(cancelled)
            assertFalse(matched)
        }

    @Test
    fun searchingTheOriginalProviderDoesNotRetryItsAlreadyFailedIdentity() =
        runTest {
            val registry = Registry()
            var attempts = 0
            registry.audioSource(
                "ytm",
                source("ytm.audio") {
                    attempts++
                    unavailable()
                },
            )
            registry.searchProvider("ytm", search("ytm.search") { listOf(metadata.copy(id = original.canonicalId)) })
            assertNull(ProviderPipelines(registry, trackLookup = { metadata }).audio(query).stream)
            assertEquals(1, attempts)
        }

    @Test
    fun aTimedOutSearchAllowsAnotherProvidersMatch() =
        runTest {
            val registry = Registry()
            var timedOut = false
            registry.audioSource("slow", source("slow.audio") { unavailable() })
            registry.audioSource(
                "working",
                source("working.audio") {
                    if (it.identity.canonicalId ==
                        "matched"
                    ) {
                        stream()
                    } else {
                        unavailable()
                    }
                },
            )
            registry.searchProvider(
                "slow",
                search("slow.search") {
                    try {
                        awaitCancellation()
                    } finally {
                        timedOut = true
                    }
                },
            )
            registry.searchProvider("working", search("working.search") { listOf(metadata.copy(id = "matched")) })
            val pipelines = ProviderPipelines(registry, trackLookup = { metadata })
            pipelines.audioPolicy.value = AudioPolicy(timeoutMillis = 50)
            assertEquals(
                "working.audio",
                pipelines
                    .audio(query)
                    .stream
                    ?.attributes
                    ?.get("providerId"),
            )
            assertTrue(timedOut)
        }
}
