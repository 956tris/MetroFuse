package dev.metrofuse.sdk

import dev.metrofuse.api.PluginContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals

class PluginUiCatalogTest {
    @Test
    fun searchPreservesProviderOwnershipAndClampsRequestLimit() =
        runBlocking {
            var request: JsonObject? = null
            val context =
                Proxy.newProxyInstance(javaClass.classLoader, arrayOf(PluginContext::class.java)) { _, method, args ->
                    check(method.name == "callService")
                    assertEquals("ui.search/v1", args[0])
                    request = args[1] as JsonObject
                    Json.parseToJsonElement(
                        """
                        {"results":{"tracks":[{"id":"metrofuse:provider:track","title":"Song",
                        "artist":"Artist","album":"Album","duration":180000}]}}
                        """.trimIndent(),
                    )
                } as PluginContext
            val results = context.searchUiCatalog("query", 1000)
            assertEquals(100, request!!.getValue("limit").jsonPrimitive.int)
            assertEquals("query", request!!.getValue("query").jsonPrimitive.content)
            assertEquals("metrofuse:provider:track", results.tracks.single().id)
            assertEquals(180_000L, results.tracks.single().duration)
        }
}
