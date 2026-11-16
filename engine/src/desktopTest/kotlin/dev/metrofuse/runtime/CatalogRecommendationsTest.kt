package dev.metrofuse.runtime

import dev.metrofuse.api.CatalogProvider
import dev.metrofuse.api.RecommendationProvider
import dev.metrofuse.api.RecommendationRequest
import dev.metrofuse.api.RecommendationResult
import dev.metrofuse.api.Track
import dev.metrofuse.api.TrackIdentity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CatalogRecommendationsTest {
    private class Catalog(
        override val id: String,
        val respond: suspend (RecommendationRequest) -> RecommendationResult,
    ) : CatalogProvider,
        RecommendationProvider {
        override suspend fun recommendations(request: RecommendationRequest) = respond(request)
    }

    private fun result(
        id: String,
        continuation: String? = null,
    ) = RecommendationResult(listOf(Track(id, id, "Artist", "Album", extras = mapOf("youtube" to id))), continuation)

    @Test
    fun selectedCatalogOwnsTracksAndContinuationsStayWithTheirProvider() =
        runTest {
            val registry = Registry()
            val calls = mutableListOf<RecommendationRequest>()
            var foreignCalls = 0
            registry.catalogProvider(
                "foreign",
                Catalog("foreign.catalog") {
                    foreignCalls++
                    result("foreign")
                },
            )
            registry.catalogProvider(
                "selected",
                Catalog("selected.catalog") { request ->
                    calls += request
                    result(if (request.continuation == null) "one" else "two", if (request.continuation == null) "service-token" else null)
                },
            )
            val pipelines = ProviderPipelines(registry)
            val first = assertNotNull(pipelines.recommendations(TrackIdentity("metrofuse:selected:seed"), setOf("selected")))
            assertEquals("metrofuse:selected:one", first.tracks.single().id)
            assertEquals("one", first.tracks.single().extras["providerTrackId"])
            assertEquals("selected", first.tracks.single().extras["pluginOwner"])
            assertEquals("seed", calls.single().identity.canonicalId)
            val token = assertNotNull(first.continuation)
            assertFalse(token == "service-token")
            assertNull(pipelines.continueRecommendations(token, setOf("foreign")))
            val next = assertNotNull(pipelines.continueRecommendations(token, setOf("selected")))
            assertEquals("metrofuse:selected:two", next.tracks.single().id)
            assertEquals("service-token", calls.last().continuation)
            assertEquals("seed", calls.last().identity.canonicalId)
            assertEquals(0, foreignCalls)
            assertNull(pipelines.continueRecommendations("service-token", setOf("selected")))
        }

    @Test
    fun invalidatedAndDeactivatedCatalogsCannotReuseTheirContinuation() =
        runTest {
            val registry = Registry()
            registry.catalogProvider("fixture", Catalog("fixture.catalog") { result("one", "service-token") })
            val pipelines = ProviderPipelines(registry)
            val first = assertNotNull(pipelines.recommendations(TrackIdentity("seed"), setOf("fixture"))).continuation!!
            pipelines.invalidateCaches()
            assertNull(pipelines.continueRecommendations(first, setOf("fixture")))
            val next = assertNotNull(pipelines.recommendations(TrackIdentity("seed"), setOf("fixture"))).continuation!!
            registry.remove("fixture")
            registry.catalogProvider("fixture", Catalog("fixture.catalog") { error("Old cursors cannot reach a new activation") })
            assertNull(pipelines.continueRecommendations(next, setOf("fixture")))
        }

    @Test
    fun lateResultsAndCancellationNeverPublishRecommendations() =
        runTest {
            val registry = Registry()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            registry.catalogProvider(
                "fixture",
                Catalog("fixture.catalog") {
                    entered.complete(Unit)
                    release.await()
                    result("late", "token")
                },
            )
            val pipelines = ProviderPipelines(registry)
            val pending = async { pipelines.recommendations(TrackIdentity("seed"), setOf("fixture")) }
            entered.await()
            registry.remove("fixture")
            release.complete(Unit)
            assertNull(pending.await())

            val cancellationEntered = CompletableDeferred<Unit>()
            var cancelled = false
            registry.catalogProvider(
                "fixture",
                Catalog("fixture.catalog") {
                    cancellationEntered.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled = true
                    }
                },
            )
            val cancelledRequest = async { pipelines.recommendations(TrackIdentity("seed"), setOf("fixture")) }
            cancellationEntered.await()
            cancelledRequest.cancelAndJoin()
            assertTrue(cancelled)
        }

    @Test
    fun olderCatalogsAndMalformedRecommendationResultsAreSafelySkipped() =
        runTest {
            val registry = Registry()
            registry.catalogProvider(
                "fixture",
                object : CatalogProvider {
                    override val id = "fixture.legacy"
                },
            )
            registry.catalogProvider("fixture", Catalog("fixture.invalid") { result("") })
            registry.catalogProvider("fixture", Catalog("fixture.valid") { result("valid") })
            val result = assertNotNull(ProviderPipelines(registry).recommendations(TrackIdentity("seed"), setOf("fixture")))
            assertEquals("metrofuse:fixture:valid", result.tracks.single().id)
        }
}
