package dev.metrofuse.runtime

import dev.metrofuse.api.HomeItem
import dev.metrofuse.api.HomeLayout
import dev.metrofuse.api.HomeRequest
import dev.metrofuse.api.HomeShelf
import dev.metrofuse.api.HomeSourceMetadata
import dev.metrofuse.api.Manifest
import dev.metrofuse.api.Track
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginHomeTest {
    private val request = HomeRequest(Locale.getDefault().language, Locale.getDefault().country)
    private val shelf = HomeShelf("Rows", listOf(HomeItem(track = Track("one", "One", "", ""))), HomeLayout.ROWS_GRID)

    @Test
    fun sourcesLoadIndependentlyKeepCachedContentAndRetry() =
        runTest {
            val homes = PluginHomes(backgroundScope, timeoutMs = 100)
            val slow = CompletableDeferred<List<HomeShelf>>()
            var fail = false
            var calls = 0
            homes.bind("slow") { slow.await() }
            homes.bind("fast") {
                calls++
                if (fail) error("fixture failure") else listOf(shelf)
            }
            homes.refresh("slow", request)
            homes.refresh("fast", request)
            runCurrent()
            assertTrue(
                homes.pages.value
                    .getValue("slow")
                    .loading,
            )
            assertEquals(
                listOf(shelf),
                homes.pages.value
                    .getValue("fast")
                    .shelves,
            )
            homes.refresh("fast", request)
            runCurrent()
            assertEquals(1, calls)
            fail = true
            homes.refresh("fast", request, force = true)
            runCurrent()
            assertTrue(
                homes.pages.value
                    .getValue("fast")
                    .error,
            )
            assertEquals(
                listOf(shelf),
                homes.pages.value
                    .getValue("fast")
                    .shelves,
            )
            fail = false
            homes.refresh("fast", request, force = true)
            runCurrent()
            assertFalse(
                homes.pages.value
                    .getValue("fast")
                    .error,
            )
            advanceTimeBy(101)
            runCurrent()
            assertTrue(
                homes.pages.value
                    .getValue("slow")
                    .error,
            )
            assertFalse(
                homes.pages.value
                    .getValue("slow")
                    .loading,
            )
        }

    @Test
    fun lateResultsCannotRestoreAnUnloadedOrReplacedSource() =
        runTest {
            val homes = PluginHomes(backgroundScope)
            val late = CompletableDeferred<List<HomeShelf>>()
            homes.bind("source") { withContext(NonCancellable) { late.await() } }
            homes.refresh("source", request)
            runCurrent()
            homes.unbind("source")
            homes.bind("source") { emptyList() }
            homes.refresh("source", request)
            runCurrent()
            late.complete(listOf(shelf))
            runCurrent()
            assertTrue(
                homes.pages.value
                    .getValue("source")
                    .loaded,
            )
            assertTrue(
                homes.pages.value
                    .getValue("source")
                    .shelves
                    .isEmpty(),
            )
            homes.unbind("source")
            assertFalse("source" in homes.pages.value)
        }

    @Test
    fun realQuickJsHomeUsesManifestCapabilityOwnershipAndHotUnload() =
        runBlocking {
            fixture { runtime ->
                install(runtime, "fixture.catalog", "return []", capability = false)
                install(
                    runtime,
                    "fixture.home",
                    "return [{title:'Plugin rows',layout:'ROWS_GRID',items:[{track:{id:'one',title:'One',artist:'Artist',album:''}}]}]",
                )
                runtime.enable("fixture.catalog")
                runtime.enable("fixture.home")
                val sources = withTimeout(5000) { runtime.homeSources.first { it.isNotEmpty() } }
                assertEquals(listOf("fixture.home"), sources.map { it.owner })
                assertEquals("Home fixture", sources.single().title)
                runtime.homes.refresh("fixture.home", request)
                val page = withTimeout(5000) { runtime.homes.pages.first { it["fixture.home"]?.loaded == true } }.getValue("fixture.home")
                assertEquals(HomeLayout.ROWS_GRID, page.shelves.single().layout)
                val track =
                    page.shelves
                        .single()
                        .items
                        .single()
                        .track!!
                assertEquals("metrofuse:fixture.home:one", track.id)
                assertEquals("fixture.home", track.extras["pluginOwner"])
                assertEquals("one", track.extras["providerTrackId"])
                runtime.action("fixture.home", "crash")
                withTimeout(5000) {
                    runtime.plugins.first { values ->
                        values.any { it.manifest.id == "fixture.home" && it.state == "Failed" }
                    }
                }
                withTimeout(5000) { runtime.homeSources.first { it.isEmpty() } }
                assertFalse("fixture.home" in runtime.homes.pages.value)
                runtime.enable("fixture.home")
                withTimeout(5000) { runtime.homeSources.first { it.isNotEmpty() } }
                runtime.uninstall("fixture.home")
                withTimeout(5000) { runtime.homeSources.first { it.isEmpty() } }
                assertFalse("fixture.home" in runtime.homes.pages.value)
            }
        }

    @Test
    fun quickJsExceptionsAndLoopsDoNotBlockOtherSources() =
        runBlocking {
            for (body in listOf("throw Error('fixture failure')", "while(true){}")) {
                fixture { runtime ->
                    install(runtime, "fixture.failed", body)
                    install(runtime, "fixture.good", "return []")
                    runtime.enable("fixture.failed")
                    runtime.enable("fixture.good")
                    runtime.homes.refresh("fixture.failed", request)
                    runtime.homes.refresh("fixture.good", request)
                    withTimeout(
                        5000,
                    ) { runtime.homes.pages.first { it["fixture.good"]?.loaded == true && it["fixture.failed"]?.error == true } }
                    assertEquals(
                        "Enabled",
                        runtime.plugins.value
                            .first { it.manifest.id == "fixture.good" }
                            .state,
                    )
                    runtime.disable("fixture.failed")
                    assertFalse("fixture.failed" in runtime.homes.pages.value)
                }
            }
        }

    private suspend fun fixture(block: suspend (Runtime) -> Unit) {
        val directory = Files.createTempDirectory("plugin-home-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runtime = Runtime(directory, "desktop", scope, EngineTestHost())
        try {
            runtime.initialize()
            block(runtime)
        } finally {
            runtime.close()
            scope.cancel()
            directory.deleteRecursively()
        }
    }

    private suspend fun install(
        runtime: Runtime,
        id: String,
        body: String,
        capability: Boolean = true,
    ) {
        val manifest =
            Manifest(
                id,
                "Fixture",
                "1.0.0",
                "Tests",
                runtime = "javascript",
                script = "index.js",
                capabilities = if (capability) listOf("home") else listOf("catalog"),
                home = HomeSourceMetadata(title = "Home fixture"),
            )
        val script = "globalThis.plugin={init(host){host.home(async request=>{$body});host.action('crash',()=>{throw Error('fixture crash')});}};"
        val bytes =
            ByteArrayOutputStream().use { output ->
                ZipOutputStream(output).use { zip ->
                    for ((name, source) in mapOf("manifest.json" to codec.encodeToString(manifest), "index.js" to script)) {
                        zip.putNextEntry(ZipEntry(name))
                        zip.write(source.encodeToByteArray())
                        zip.closeEntry()
                    }
                }
                output.toByteArray()
            }
        runtime.install(ByteArrayInputStream(bytes))
    }
}
