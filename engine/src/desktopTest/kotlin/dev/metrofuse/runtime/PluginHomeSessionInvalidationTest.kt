package dev.metrofuse.runtime

import dev.metrofuse.api.HomeItem
import dev.metrofuse.api.HomeRequest
import dev.metrofuse.api.HomeShelf
import dev.metrofuse.api.Track
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertTrue

class PluginHomeSessionInvalidationTest {
    @Test
    fun invalidationClearsPersonalShelvesAndRejectsLatePreviousAccountResults() =
        runTest {
            val homes = PluginHomes(backgroundScope)
            val late = CompletableDeferred<List<HomeShelf>>()
            var calls = 0
            homes.bind("fixture") {
                calls++
                if (calls == 1) withContext(NonCancellable) { late.await() } else emptyList()
            }
            homes.refresh("fixture", HomeRequest("fr", "FR"))
            runCurrent()
            homes.invalidate("fixture")
            assertTrue(
                homes.pages.value
                    .getValue("fixture")
                    .shelves
                    .isEmpty(),
            )
            runCurrent()
            assertTrue(
                homes.pages.value
                    .getValue("fixture")
                    .loaded,
            )
            late.complete(listOf(HomeShelf("Previous account", listOf(HomeItem(track = Track("one", "One", "", ""))))))
            runCurrent()
            assertTrue(
                homes.pages.value
                    .getValue("fixture")
                    .shelves
                    .isEmpty(),
            )
        }
}
