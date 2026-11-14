package dev.metrofuse.runtime

import dev.metrofuse.api.SearchProvider
import dev.metrofuse.api.SearchRequest
import dev.metrofuse.api.SearchResult
import dev.metrofuse.api.Track
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MusicFrontendRoutingTest {
    @Test
    fun selectedFrontendIsolatesCatalogCallsWithoutChangingProviderPriority() =
        runBlocking<Unit> {
            val registry = Registry()
            val calls = mutableListOf<String>()
            for (owner in listOf("first", "second")) {
                registry.searchProvider(
                    owner,
                    object : SearchProvider {
                        override val id = "$owner.search"

                        override suspend fun search(request: SearchRequest): SearchResult {
                            calls += owner
                            return SearchResult(listOf(Track("track", request.query, owner, "")))
                        }
                    },
                )
            }
            val pipelines = ProviderPipelines(registry)
            val before = registry.state.value
            val first = pipelines.search(SearchRequest("Query"), allowedOwners = setOf("first"))
            assertEquals(listOf("first"), calls)
            assertEquals(listOf("metrofuse:first:track"), first.tracks.map { it.id })
            calls.clear()
            val second = pipelines.search(SearchRequest("Query"), allowedOwners = setOf("second"))
            assertEquals(listOf("second"), calls)
            assertEquals(listOf("metrofuse:second:track"), second.tracks.map { it.id })
            calls.clear()
            assertTrue(pipelines.search(SearchRequest("Query"), allowedOwners = emptySet()).tracks.isEmpty())
            assertTrue(calls.isEmpty())
            assertEquals(before, registry.state.value)
        }
}
