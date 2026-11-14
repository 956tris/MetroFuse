package dev.metrofuse.runtime

import dev.metrofuse.api.AudioQuery
import dev.metrofuse.api.NativePlugin
import dev.metrofuse.api.SearchRequest
import dev.metrofuse.api.TrackIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class JioSaavnWesternCatalogTest {
    private val root = File(requireNotNull(System.getProperty("metrofuse.root")))

    @Test
    fun switchingPackedFormatsPreservesSettingsAndProviderRegistration() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val data = Files.createTempDirectory("metrofuse-jiosaavn-formats-").toFile()
            val runtime = Runtime(data, "desktop", scope, EngineTestHost())
            try {
                runtime.initialize()
                for ((index, extension) in listOf("mfp", "jar", "mfp").withIndex()) {
                    val archive = root.resolve("build/plugin-examples/jiosaavn.$extension")
                    archive.inputStream().use { runtime.installOrUpdate(it, archive.name) }
                    if (index == 0) {
                        runtime.enable("metrofuse.jiosaavn")
                        runtime.settingsFor("metrofuse.jiosaavn").set("previewEnabled", JsonPrimitive(false))
                        runtime.settingsFor("metrofuse.jiosaavn").set("regionalCatalog", JsonPrimitive(false))
                    }
                    val status = runtime.plugins.value.single()
                    assertEquals("Enabled", status.state, runtime.logs.value.joinToString())
                    assertEquals(if (extension == "jar") "kotlin" else "javascript", status.manifest.runtime)
                    assertEquals(JsonPrimitive(false), runtime.settingsFor("metrofuse.jiosaavn").values.value["previewEnabled"])
                    assertEquals(JsonPrimitive(false), runtime.settingsFor("metrofuse.jiosaavn").values.value["regionalCatalog"])
                    assertEquals(
                        "jiosaavn.search",
                        runtime.registry
                            .searchProviders()
                            .single()
                            .id,
                    )
                    assertEquals(1, runtime.registry.audioSources().count { it.id == "jiosaavn.audio" })
                }
            } finally {
                runtime.close()
                scope.cancel()
            }
        }

    @Test
    fun nativeJarContainsCompiledEntrypointAndLoadsThroughProductionRuntime() =
        runBlocking {
            val archive = root.resolve("build/plugin-examples/jiosaavn.jar")
            assertTrue(archive.isFile, "Build :plugin-jiosaavn-native:packagePluginJar before this test")
            ZipFile(archive).use { zip ->
                assertNotNull(zip.getEntry("dev/metrofuse/jiosaavn/JioSaavnPlugin.class"))
                assertFalse(zip.entries().asSequence().any { it.name.startsWith("dev/metrofuse/api/") || it.name.startsWith("kotlin/") })
                assertTrue(zip.getEntry("index.js") == null)
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val data = Files.createTempDirectory("metrofuse-jiosaavn-native-").toFile()
            var runtime = Runtime(data, "desktop", scope, EngineTestHost())
            try {
                runtime.initialize()
                archive.inputStream().use { runtime.install(it, archive.name) }
                repeat(2) {
                    runtime.enable("metrofuse.jiosaavn")
                    assertEquals(
                        "Enabled",
                        runtime.plugins.value
                            .single()
                            .state,
                        runtime.logs.value.joinToString(),
                    )
                    assertEquals(
                        "jiosaavn.search",
                        runtime.registry
                            .searchProviders()
                            .single()
                            .id,
                    )
                    assertEquals(
                        "jiosaavn.catalog",
                        runtime.registry
                            .catalogProviders()
                            .single()
                            .id,
                    )
                    assertEquals(
                        "jiosaavn.metadata",
                        runtime.registry
                            .metadataSources()
                            .single()
                            .id,
                    )
                    assertEquals(
                        "jiosaavn.audio",
                        runtime.registry
                            .audioSources()
                            .single { it.id == "jiosaavn.audio" }
                            .id,
                    )
                    val result =
                        runtime.registry
                            .audioSources()
                            .single { it.id == "jiosaavn.audio" }
                            .provider
                            .resolve(AudioQuery(TrackIdentity("other:track"), "desktop"))
                    assertEquals("IDENTITY_REQUIRED", result.error?.code)
                    runtime.disable("metrofuse.jiosaavn")
                    assertTrue(runtime.registry.searchProviders().isEmpty())
                    assertTrue(runtime.registry.audioSources().none { it.id == "jiosaavn.audio" })
                }
                runtime.settingsFor("metrofuse.jiosaavn").set("regionalCatalog", JsonPrimitive(false))
                runtime.enable("metrofuse.jiosaavn")
                runtime.hotReplace("metrofuse.jiosaavn")
                runtime.close()
                runtime = Runtime(data, "desktop", scope, EngineTestHost())
                runtime.initialize()
                assertEquals(
                    "Enabled",
                    runtime.plugins.value
                        .single()
                        .state,
                )
                assertEquals(JsonPrimitive(false), runtime.settingsFor("metrofuse.jiosaavn").values.value["regionalCatalog"])
                val handle = loadNative(archive.absolutePath, "dev.metrofuse.jiosaavn.JioSaavnPlugin")
                try {
                    assertNotSame(NativePlugin::class.java.classLoader, handle.plugin.javaClass.classLoader)
                    assertEquals(
                        NativePlugin::class.java,
                        handle.plugin.javaClass.interfaces
                            .single(),
                    )
                } finally {
                    handle.close()
                }
            } finally {
                runtime.close()
                scope.cancel()
            }
        }

    @Test
    fun westernOriginalsSearchMetadataAlbumsAndFullAudioLiveForBothArtifacts() =
        runBlocking {
            assumeTrue("Set METROFUSE_JIOSAAVN_LIVE=1 to run live service validation", System.getenv("METROFUSE_JIOSAAVN_LIVE") == "1")
            val songs =
                listOf(
                    "No Idea" to "Don Toliver",
                    "After Party" to "Don Toliver",
                    "Blinding Lights" to "The Weeknd",
                    "Starboy" to "The Weeknd",
                    "goosebumps" to "Travis Scott",
                    "SICKO MODE" to "Travis Scott",
                )
            val reports = mutableListOf<JsonObject>()
            val urls = mutableListOf<String>()
            val output = root.resolve("build/jiosaavn-western-live").apply { mkdirs() }
            for (extension in listOf("mfp", "jar")) {
                val archive = root.resolve("build/plugin-examples/jiosaavn.$extension")
                assertTrue(archive.isFile, "Package both production artifacts before the live test")
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val data = Files.createTempDirectory("metrofuse-jiosaavn-western-$extension-").toFile()
                var runtime = Runtime(data, "desktop", scope, EngineTestHost())
                try {
                    runtime.initialize()
                    assertTrue(runtime.plugins.value.isEmpty())
                    archive.inputStream().use { runtime.install(it, archive.name) }
                    runtime.enable("metrofuse.jiosaavn")
                    assertEquals(
                        "Enabled",
                        runtime.plugins.value
                            .single()
                            .state,
                        runtime.logs.value.joinToString(),
                    )
                    runtime.settingsFor("metrofuse.jiosaavn").set("previewEnabled", JsonPrimitive(false))
                    for ((title, artist) in songs) {
                        val results = runtime.pipelines.search(SearchRequest("$title $artist", 15))
                        val returnedTracks = results.tracks.map { it.title to it.artist }
                        val track =
                            assertNotNull(
                                results.tracks.firstOrNull {
                                    it.title.equals(title, ignoreCase = true) &&
                                        it.artist.contains(artist, ignoreCase = true)
                                },
                                "$extension did not return original $title by $artist: $returnedTracks",
                            )
                        val identity =
                            TrackIdentity(
                                track.extras.getValue("providerTrackId"),
                                mapOf("jiosaavn" to track.extras.getValue("jiosaavn")),
                            )
                        val audio = runtime.pipelines.audio(AudioQuery(identity, "desktop"), includeBuiltin = false)
                        val diagnostics = runtime.pipelines.diagnostics.value
                        val stream =
                            assertNotNull(audio.stream, "$extension $title failed: ${audio.error}; $diagnostics")
                        assertEquals("full song", stream.attributes["content"])
                        assertEquals("jiosaavn.audio", stream.attributes["providerId"])
                        assertTrue((stream.durationSeconds ?: 0) > 150)
                        assertTrue(stream.headers.isEmpty(), "Catalog routing headers must not be attached to CDN requests")
                        val bytes =
                            withContext(Dispatchers.IO) {
                                val connection = URI(stream.locator).toURL().openConnection() as HttpURLConnection
                                try {
                                    connection.connectTimeout = 10000
                                    connection.readTimeout = 10000
                                    connection.setRequestProperty("Range", "bytes=0-2047")
                                    assertTrue(
                                        connection.responseCode in listOf(200, 206),
                                        "$title CDN returned HTTP ${connection.responseCode}",
                                    )
                                    connection.inputStream.use { it.readNBytes(2048) }
                                } finally {
                                    connection.disconnect()
                                }
                            }
                        assertEquals(2048, bytes.size)
                        assertEquals("ftyp", String(bytes, 4, 4, Charsets.US_ASCII))
                        val metadata = runtime.pipelines.metadata(MetadataMapping.fromTrack(track.copy(id = identity.canonicalId)))
                        assertEquals(
                            title.lowercase(),
                            metadata.fields
                                .getValue("title")
                                .value
                                .toString()
                                .trim('"')
                                .lowercase(),
                        )
                        val album =
                            assertNotNull(runtime.pipelines.album("metrofuse:metrofuse.jiosaavn:${track.extras.getValue("albumId")}"))
                        assertTrue(album.tracks.any { it.extras["jiosaavn"] == identity.identifiers["jiosaavn"] })
                        reports +=
                            buildJsonObject {
                                put("artifact", archive.name)
                                put("title", track.title)
                                put("artist", track.artist)
                                put("album", album.title)
                                put("trackId", identity.identifiers["jiosaavn"])
                                put("durationSeconds", stream.durationSeconds)
                                put("bitrate", stream.bitrate)
                                put("mimeType", stream.mimeType)
                                put("cdnBytesRead", bytes.size)
                            }
                        urls += stream.locator
                        output.resolve("verification.json").writeText(JsonArray(reports).toString())
                        output.resolve("stream-urls.txt").writeText(urls.joinToString("\n"))
                    }
                    val identity = TrackIdentity("jiosaavn:track:${reports.last().getValue("trackId").toString().trim('"')}")
                    runtime.hotReplace("metrofuse.jiosaavn")
                    assertNotNull(runtime.pipelines.audio(AudioQuery(identity, "desktop"), includeBuiltin = false).stream)
                    runtime.close()
                    runtime = Runtime(data, "desktop", scope, EngineTestHost())
                    runtime.initialize()
                    assertEquals(
                        "Enabled",
                        runtime.plugins.value
                            .single()
                            .state,
                    )
                    assertEquals(JsonPrimitive(false), runtime.settingsFor("metrofuse.jiosaavn").values.value["previewEnabled"])
                    assertNotNull(runtime.pipelines.audio(AudioQuery(identity, "desktop"), includeBuiltin = false).stream)
                } finally {
                    runtime.close()
                    scope.cancel()
                }
            }
        }
}
