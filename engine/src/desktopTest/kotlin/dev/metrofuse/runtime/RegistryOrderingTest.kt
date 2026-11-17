package dev.metrofuse.runtime

import dev.metrofuse.api.SearchProvider
import dev.metrofuse.api.SearchRequest
import dev.metrofuse.api.SearchResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RegistryOrderingTest {
    @Test
    fun visibleOrderPreservesHiddenPreferencesAndOtherScopes() {
        val registry = Registry()
        registry.reorder(null, listOf("a", "disabled", "b"))
        registry.reorder("lyrics.resolve", listOf("b.lyrics", "a.lyrics"))
        registry.priority("a", 50)
        registry.select("ui.app", "a")
        val before = registry.state.value

        registry.reorderVisible(null, listOf("b", "a", "new"))

        assertEquals(listOf("b", "disabled", "a", "new"), registry.state.value.globalOrder)
        assertEquals(before.copy(globalOrder = registry.state.value.globalOrder), registry.state.value)
        val snapshot = registry.state.value
        assertFailsWith<IllegalArgumentException> { registry.reorderVisible(null, listOf("a", "a")) }
        assertEquals(snapshot, registry.state.value)
    }

    @Test
    fun providerOrderIsScopedAndSurvivesRuntimeRestart() =
        runBlocking {
            val root = Files.createTempDirectory("metrofuse-order-").toFile()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val first = Runtime(root, "desktop", scope, EngineTestHost())
                first.initialize()
                first.registry.reorderVisible(null, listOf("a", "b"))
                first.registry.reorderVisible("search.resolve", listOf("b.search", "hidden.search", "a.search"))
                first.registry.reorderVisible("search.resolve", listOf("a.search", "b.search"))
                first.close()

                val restored = Runtime(root, "desktop", scope, EngineTestHost())
                try {
                    restored.initialize()
                    for (owner in listOf("b", "a")) {
                        restored.registry.searchProvider(
                            owner,
                            object : SearchProvider {
                                override val id = "$owner.search"

                                override suspend fun search(request: SearchRequest) = SearchResult(emptyList())
                            },
                        )
                    }
                    assertEquals(listOf("a", "b"), restored.registry.state.value.globalOrder)
                    assertEquals(
                        listOf("a.search", "hidden.search", "b.search"),
                        restored.registry.state.value.pointOrder["search.resolve"],
                    )
                    assertEquals(listOf("a.search", "b.search"), restored.registry.searchProviders().map { it.id })
                    restored.registry.reorderVisible("search.resolve", listOf("b.search", "a.search"))
                    assertEquals(listOf("b.search", "a.search"), restored.registry.searchProviders().map { it.id })
                    assertEquals(listOf("a", "b"), restored.registry.order("lyrics.resolve", listOf("b", "a")))
                    restored.registry.reorder("search.resolve", listOf("b", "a"))
                    restored.registry.reorderVisible("search.resolve", listOf("a.search", "b.search"))
                    assertEquals(listOf("a.search", "b.search"), restored.registry.searchProviders().map { it.id })
                } finally {
                    restored.close()
                }
            } finally {
                scope.cancel()
                root.deleteRecursively()
            }
        }
}
