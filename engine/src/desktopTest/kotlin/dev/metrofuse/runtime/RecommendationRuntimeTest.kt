package dev.metrofuse.runtime

import dev.metrofuse.api.Manifest
import dev.metrofuse.api.TrackIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RecommendationRuntimeTest {
    @Test
    fun realJavaScriptCatalogRecommendationsRoundTripWithOwnedContinuations() =
        runBlocking {
            val data = Files.createTempDirectory("recommendation-runtime").toFile()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val runtime = Runtime(data, "desktop", scope, EngineTestHost())
            try {
                runtime.initialize()
                val script =
                    """
                    globalThis.plugin={init(host){host.catalog('fixture.catalog',{
                      recommendations:async request=>({tracks:[{id:request.continuation?'next':'related',title:request.identity.canonicalId,artist:'Artist',album:''}],continuation:request.continuation?null:'service-page'})
                    });}};
                    """.trimIndent()
                val manifest =
                    Manifest(
                        "test.script",
                        "Recommendations",
                        "1.0.0",
                        "Tests",
                        runtime = "javascript",
                        script = "index.js",
                        capabilities = listOf("catalog"),
                    )
                val bytes =
                    ByteArrayOutputStream().use { output ->
                        ZipOutputStream(output).use { zip ->
                            for ((name, value) in mapOf("manifest.json" to codec.encodeToString(manifest), "index.js" to script)) {
                                zip.putNextEntry(ZipEntry(name))
                                zip.write(value.encodeToByteArray())
                                zip.closeEntry()
                            }
                        }
                        output.toByteArray()
                    }
                runtime.install(ByteArrayInputStream(bytes))
                runtime.enable("test.script")
                assertEquals(
                    "Enabled",
                    runtime.plugins.value
                        .single()
                        .state,
                    runtime.logs.value.joinToString(),
                )
                val first =
                    assertNotNull(runtime.pipelines.recommendations(TrackIdentity("metrofuse:test.script:seed"), setOf("test.script")))
                assertEquals("metrofuse:test.script:related", first.tracks.single().id)
                assertEquals("seed", first.tracks.single().title)
                val token = assertNotNull(first.continuation)
                val next = assertNotNull(runtime.pipelines.continueRecommendations(token, setOf("test.script")))
                assertEquals("metrofuse:test.script:next", next.tracks.single().id)
                runtime.disable("test.script")
                assertNull(runtime.pipelines.continueRecommendations(token, setOf("test.script")))
            } finally {
                runtime.close()
                scope.cancel()
            }
        }
}
