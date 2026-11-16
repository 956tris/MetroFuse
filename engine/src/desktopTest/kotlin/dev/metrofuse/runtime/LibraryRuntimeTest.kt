package dev.metrofuse.runtime

import dev.metrofuse.api.LibraryRequest
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

class LibraryRuntimeTest {
    @Test
    fun libraryAndRecommendationsBothSurviveTheJavaScriptCatalogBridge() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val runtime = Runtime(Files.createTempDirectory("library-runtime").toFile(), "desktop", scope, EngineTestHost())
            try {
                runtime.initialize()
                val manifest =
                    Manifest(
                        "test.library",
                        "Library",
                        "1.0.0",
                        "Tests",
                        runtime = "javascript",
                        script = "index.js",
                        capabilities = listOf("catalog"),
                    )
                val script =
                    """
                    globalThis.plugin={init(host){host.catalog('fixture.catalog',{
                      library:async request=>({items:[{track:{id:request.continuation?'next':'saved',title:request.language,artist:'Artist',album:''}}],continuation:request.continuation?null:'service-token'}),
                      recommendations:async request=>({tracks:[{id:'related',title:'Related',artist:'Artist',album:''}]})
                    });}};
                    """.trimIndent()
                val bytes =
                    ByteArrayOutputStream().use { output ->
                        ZipOutputStream(output).use { zip ->
                            mapOf("manifest.json" to codec.encodeToString(manifest), "index.js" to script).forEach { (name, content) ->
                                zip.putNextEntry(ZipEntry(name))
                                zip.write(content.encodeToByteArray())
                                zip.closeEntry()
                            }
                        }
                        output.toByteArray()
                    }
                runtime.install(ByteArrayInputStream(bytes))
                runtime.enable(manifest.id)
                assertEquals(
                    "Enabled",
                    runtime.plugins.value
                        .single()
                        .state,
                    runtime.logs.value.joinToString(),
                )
                val request = LibraryRequest("pt-BR", "BR")
                val first = assertNotNull(runtime.pipelines.library(manifest.id, request))
                assertEquals(
                    "metrofuse:test.library:saved",
                    first.items
                        .single()
                        .track
                        ?.id,
                )
                assertEquals(
                    "pt-BR",
                    first.items
                        .single()
                        .track
                        ?.title,
                )
                val next = assertNotNull(runtime.pipelines.library(manifest.id, request.copy(continuation = first.continuation)))
                assertEquals(
                    "metrofuse:test.library:next",
                    next.items
                        .single()
                        .track
                        ?.id,
                )
                assertNull(next.continuation)
                assertEquals(
                    "metrofuse:test.library:related",
                    runtime.pipelines
                        .recommendations(TrackIdentity("seed"), setOf(manifest.id))
                        ?.tracks
                        ?.single()
                        ?.id,
                )
            } finally {
                runtime.close()
                scope.cancel()
            }
        }
}
