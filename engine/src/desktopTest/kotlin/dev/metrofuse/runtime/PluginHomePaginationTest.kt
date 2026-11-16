package dev.metrofuse.runtime

import dev.metrofuse.api.HomeItem
import dev.metrofuse.api.HomePage
import dev.metrofuse.api.HomeRequest
import dev.metrofuse.api.HomeShelf
import dev.metrofuse.api.Track
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PluginHomePaginationTest {
    private val request = HomeRequest("fr", "FR")

    private fun shelf(
        title: String,
        vararg ids: String,
    ) = HomeShelf(title, ids.map { HomeItem(track = Track(it, it, "Artist", "")) })

    @Test
    fun continuationAddsShelvesDeduplicatesItemsAndStopsRepeatedTokens() =
        runTest {
            val homes = PluginHomes(backgroundScope)
            val calls = mutableListOf<String?>()
            val pending = CompletableDeferred<HomePage>()
            homes.bindPaged("fixture") { locale, token ->
                assertEquals(request, locale)
                calls += token
                if (token == null) HomePage(listOf(shelf("Listen again", "one")), "more") else pending.await()
            }
            homes.refresh("fixture", request)
            runCurrent()
            homes.loadMore("fixture")
            homes.loadMore("fixture")
            runCurrent()
            assertEquals(listOf(null, "more"), calls)
            assertTrue(
                homes.pages.value
                    .getValue("fixture")
                    .loadingMore,
            )
            pending.complete(HomePage(listOf(shelf("Listen again", "one", "two"), shelf("New releases", "three")), "more"))
            runCurrent()
            val page = homes.pages.value.getValue("fixture")
            assertEquals(listOf("Listen again", "New releases"), page.shelves.map { it.title })
            assertEquals(
                listOf("one", "two"),
                page.shelves
                    .first()
                    .items
                    .map { it.track!!.id },
            )
            assertFalse(page.loadingMore)
            assertNull(page.continuation)
            homes.loadMore("fixture")
            runCurrent()
            assertEquals(2, calls.size)
        }

    @Test
    fun failedContinuationKeepsContentAndCanBeRetried() =
        runTest {
            val homes = PluginHomes(backgroundScope)
            var fail = true
            homes.bindPaged("fixture") { _, token ->
                when {
                    token == null -> HomePage(listOf(shelf("Initial", "one")), "more")
                    fail -> error("Fixture network failure")
                    else -> HomePage(listOf(shelf("Mixes", "two")))
                }
            }
            homes.refresh("fixture", request)
            runCurrent()
            homes.loadMore("fixture")
            runCurrent()
            val failed = homes.pages.value.getValue("fixture")
            assertEquals("more", failed.continuation)
            assertEquals(listOf("Initial"), failed.shelves.map { it.title })
            assertTrue(failed.loadMoreError)
            assertFalse(failed.loadingMore)
            fail = false
            homes.loadMore("fixture")
            runCurrent()
            val retried = homes.pages.value.getValue("fixture")
            assertEquals(listOf("Initial", "Mixes"), retried.shelves.map { it.title })
            assertFalse(retried.loadMoreError)
        }

    @Test
    fun accountInvalidationRejectsAnOldInFlightContinuation() =
        runTest {
            val homes = PluginHomes(backgroundScope)
            val late = CompletableDeferred<HomePage>()
            var newAccount = false
            homes.bindPaged("fixture") { _, token ->
                when {
                    newAccount -> HomePage(listOf(shelf("New account", "new")))
                    token == null -> HomePage(listOf(shelf("Old account", "old")), "more")
                    else -> withContext(NonCancellable) { late.await() }
                }
            }
            homes.refresh("fixture", request)
            runCurrent()
            homes.loadMore("fixture")
            runCurrent()
            newAccount = true
            homes.invalidate("fixture")
            runCurrent()
            late.complete(HomePage(listOf(shelf("Old recommendations", "late"))))
            runCurrent()
            val page = homes.pages.value.getValue("fixture")
            assertEquals(listOf("New account"), page.shelves.map { it.title })
            assertNull(page.continuation)
        }
}
