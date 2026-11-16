package dev.metrofuse.runtime

import dev.metrofuse.api.CatalogAlbum
import dev.metrofuse.api.CatalogArtist
import dev.metrofuse.api.CatalogPlaylist
import dev.metrofuse.api.CatalogProvider
import dev.metrofuse.api.HomeItem
import dev.metrofuse.api.LibraryPage
import dev.metrofuse.api.LibraryProvider
import dev.metrofuse.api.LibraryRequest
import dev.metrofuse.api.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CatalogLibraryTest {
    private class Catalog(
        override val id: String,
        val respond: suspend (LibraryRequest) -> LibraryPage,
    ) : CatalogProvider,
        LibraryProvider {
        override suspend fun library(request: LibraryRequest) = respond(request)
    }

    private val request = LibraryRequest("pt-BR", "BR")

    private fun page(token: String? = null) = LibraryPage(listOf(HomeItem(track = Track("song", "Song", "Artist", "Album"))), token)

    @Test
    fun libraryKeepsAllObjectKindsAndPinsPagesToTheSelectedPlugin() =
        runTest {
            val registry = Registry()
            registry.catalogProvider("other", Catalog("other.catalog") { error("Must not call a different service") })
            val calls = mutableListOf<LibraryRequest>()
            registry.catalogProvider(
                "selected",
                Catalog("selected.catalog") {
                    calls += it
                    if (it.continuation != null) {
                        page()
                    } else {
                        LibraryPage(
                            listOf(
                                HomeItem(track = Track("song", "Song", "Artist", "Album")),
                                HomeItem(album = CatalogAlbum("album", "Album", "Artist")),
                                HomeItem(artist = CatalogArtist("artist", "Artist")),
                                HomeItem(playlist = CatalogPlaylist("playlist", "Playlist", "Author")),
                            ),
                            "server-token",
                        )
                    }
                },
            )
            val pipelines = ProviderPipelines(registry)
            val first = assertNotNull(pipelines.library("selected", request))
            assertEquals("metrofuse:selected:song", first.items[0].track?.id)
            assertEquals(
                "song",
                first.items[0]
                    .track
                    ?.extras
                    ?.get("providerTrackId"),
            )
            assertEquals("metrofuse:selected:album", first.items[1].album?.id)
            assertEquals("metrofuse:selected:artist", first.items[2].artist?.id)
            assertEquals("metrofuse:selected:playlist", first.items[3].playlist?.id)
            val token = assertNotNull(first.continuation)
            assertFalse(token == "server-token")
            assertFailsWith<CancellationException> { pipelines.library("other", request.copy(continuation = token)) }
            val next = assertNotNull(pipelines.library("selected", request.copy(continuation = token)))
            assertNull(next.continuation)
            assertEquals("server-token", calls.last().continuation)
            assertEquals(request.language, calls.last().language)
        }

    @Test
    fun cacheInvalidationRejectsBothOldPagesAndLateAccountResults() =
        runTest {
            val registry = Registry()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var blocked = false
            registry.catalogProvider(
                "selected",
                Catalog("selected.catalog") {
                    if (blocked) {
                        entered.complete(Unit)
                        release.await()
                    }
                    page("next")
                },
            )
            val pipelines = ProviderPipelines(registry)
            val first = assertNotNull(pipelines.library("selected", request)).continuation!!
            pipelines.invalidateCaches()
            assertFailsWith<CancellationException> { pipelines.library("selected", request.copy(continuation = first)) }
            blocked = true
            val pending = async { pipelines.library("selected", request) }
            entered.await()
            pipelines.invalidateCaches()
            release.complete(Unit)
            assertFailsWith<CancellationException> { pending.await() }
        }

    @Test
    fun repeatedPagesFinishAsIncompleteAndLegacyCatalogsRemainSupported() =
        runTest {
            val registry = Registry()
            registry.catalogProvider(
                "legacy",
                object : CatalogProvider {
                    override val id = "legacy.catalog"
                },
            )
            registry.catalogProvider("selected", Catalog("selected.catalog") { page("same") })
            val pipelines = ProviderPipelines(registry)
            assertNull(pipelines.library("legacy", request))
            val first = assertNotNull(pipelines.library("selected", request))
            val repeated = assertNotNull(pipelines.library("selected", request.copy(continuation = first.continuation)))
            assertFalse(repeated.complete)
            assertNull(repeated.continuation)
            registry.remove("selected")
            registry.catalogProvider("selected", Catalog("selected.catalog") { error("Old activation must not call the replacement") })
            assertFailsWith<CancellationException> { pipelines.library("selected", request.copy(continuation = first.continuation)) }
        }

    @Test
    fun invalidLibraryItemsCannotEnterTheHost() =
        runTest {
            val registry = Registry()
            registry.catalogProvider("selected", Catalog("selected.catalog") { LibraryPage(listOf(HomeItem())) })
            assertFailsWith<IllegalArgumentException> { ProviderPipelines(registry).library("selected", request) }
        }

    @Test
    fun requestTimeoutsAllowRetryAndCallerCancellationStillStopsTheProvider() =
        runBlocking<Unit> {
            val registry = Registry()
            val entered = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()
            var respond = false
            var observeCancellation = false
            registry.catalogProvider(
                "selected",
                Catalog("selected.catalog") {
                    if (respond) {
                        page()
                    } else {
                        if (observeCancellation) entered.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            if (observeCancellation) stopped.complete(Unit)
                        }
                    }
                },
            )
            val library = CatalogLibrary(registry, { 0L }, requestTimeoutMillis = 100)
            assertFailsWith<IllegalStateException> { library.page("selected", request) }
            assertTrue(isActive)
            respond = true
            assertEquals(page(), library.page("selected", request))
            respond = false
            observeCancellation = true
            val cancellableLibrary = CatalogLibrary(registry, { 0L })
            val pending = async { cancellableLibrary.page("selected", request) }
            entered.await()
            pending.cancelAndJoin()
            assertTrue(pending.isCancelled)
            assertTrue(stopped.isCompleted)
        }
}
