package dev.metrofuse.runtime

import dev.metrofuse.api.AudioPolicy
import dev.metrofuse.api.AudioQuery
import dev.metrofuse.api.COMPOSE_ABI
import dev.metrofuse.api.SearchRequest
import dev.metrofuse.api.TrackIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class YouTubeMusicPluginTest {
    private val root get() = File(requireNotNull(System.getProperty("metrofuse.root")))
    private val nativeJar get() = root.resolve("plugins/providers/youtube-music/build/outputs/plugins/youtube-music.jar")
    private val universalMfp get() = root.resolve("dist/youtube-music-plugin/MetroFuse-YouTube-Music.mfp")

    @Test
    fun optionalNativeArtifactImportsDisabledAndUsesPublicRegistryAcrossReloadAndRestart() =
        runBlocking {
            assumeTrue("Build the optional module with -PbuildYoutubeMusicPlugin=true", nativeJar.isFile)
            val data = Files.createTempDirectory("metrofuse-youtube-native-").toFile()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            var runtime = Runtime(data, "desktop", scope, EngineTestHost())
            try {
                runtime.initialize()
                assertTrue(runtime.plugins.value.isEmpty())
                assertTrue(runtime.registry.audioSources().none { it.owner == OWNER })
                nativeJar.inputStream().use { runtime.install(it, nativeJar.name) }
                assertTrue(
                    runtime.plugins.value
                        .single()
                        .state != "Enabled",
                )
                assertTrue(runtime.registry.searchProviders().none { it.owner == OWNER })
                assertEquals(
                    COMPOSE_ABI,
                    runtime.plugins.value
                        .single()
                        .manifest.composeAbi,
                )
                repeat(2) {
                    runtime.enable(OWNER)
                    assertEquals(
                        "Enabled",
                        runtime.plugins.value
                            .single()
                            .state,
                        runtime.logs.value.joinToString(),
                    )
                    assertRegistered(runtime)
                    val rejected =
                        runtime.registry.audioSources().single { it.owner == OWNER }.provider.resolve(
                            AudioQuery(TrackIdentity("abcdefghijk", mapOf("pluginOwner" to "other.provider")), "desktop"),
                        )
                    assertEquals("IDENTITY_REQUIRED", rejected.error?.code)
                    runtime.disable(OWNER)
                    assertUnregistered(runtime)
                }
                runtime.enable(OWNER)
                runtime.settingsFor(OWNER).set("audioEnabled", JsonPrimitive(false))
                runtime.hotReplace(OWNER)
                assertEquals(
                    "Enabled",
                    runtime.plugins.value
                        .single()
                        .state,
                    runtime.logs.value.joinToString(),
                )
                assertRegistered(runtime)
                assertEquals(JsonPrimitive(false), runtime.settingsFor(OWNER).values.value["audioEnabled"])
                runtime.close()
                runtime = Runtime(data, "desktop", scope, EngineTestHost())
                runtime.initialize()
                assertEquals(
                    "Enabled",
                    runtime.plugins.value
                        .single()
                        .state,
                    runtime.logs.value.joinToString(),
                )
                assertRegistered(runtime)
                assertEquals(JsonPrimitive(false), runtime.settingsFor(OWNER).values.value["audioEnabled"])
                runtime.uninstall(OWNER)
                assertTrue(runtime.plugins.value.isEmpty())
                assertUnregistered(runtime)
            } finally {
                runtime.close()
                scope.cancel()
                data.deleteRecursively()
            }
        }

    @Test
    fun standaloneMfpContainsOnlyJavaScriptAndActivatesWithoutNativeDependencies() =
        runBlocking {
            val data = Files.createTempDirectory("metrofuse-youtube-mfp-").toFile()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val runtime = Runtime(data, "desktop", scope, EngineTestHost())
            try {
                runtime.initialize()
                val archive = if (universalMfp.isFile) universalMfp.readBytes() else packJavascriptSource()
                ByteArrayInputStream(archive).use { runtime.install(it, "youtube-music.mfp") }
                assertTrue(
                    runtime.plugins.value
                        .single()
                        .state != "Enabled",
                )
                assertEquals(
                    "javascript",
                    runtime.plugins.value
                        .single()
                        .manifest.runtime,
                )
                assertFalse(data.walkTopDown().any { it.isFile && it.extension in setOf("jar", "apk", "dex") })
                runtime.enable(OWNER)
                assertEquals(
                    "Enabled",
                    runtime.plugins.value
                        .single()
                        .state,
                    runtime.logs.value.joinToString(),
                )
                assertRegistered(runtime)
                runtime.disable(OWNER)
                assertUnregistered(runtime)
            } finally {
                runtime.close()
                scope.cancel()
                data.deleteRecursively()
            }
        }

    @Test
    fun productionQuickJsCallbacksSearchBrowseResolveAndRejectUnsupportedTransforms() =
        runBlocking {
            val source = root.resolve("plugins/providers/youtube-music-js")
            val fixture = root.resolve("plugins/providers/youtube-music/src/desktopTest/resources/youtube-search.json").readText()
            val hook =
                """
                const fixture=$fixture;
                const playerFixture={playabilityStatus:{status:'OK'},videoDetails:{videoId:'abcdefghijk',title:'Fixture track',author:'Fixture artist',lengthSeconds:'241'},
                  streamingData:{adaptiveFormats:[{itag:140,mimeType:'audio/mp4; codecs="mp4a.40.2"',bitrate:128000,audioSampleRate:'44100',url:'https://fixture.googlevideo.com/audio?expire=4102444800'}]}};
                const productionInit=globalThis.plugin.init;
                globalThis.plugin.init=async context=>{
                  const intercepted=Object.create(context);
                  const tuple=Array(17).fill(null);tuple[0]='pt-BR';tuple[1]='BR';tuple[15]=67;tuple[16]='1.20261114.00.00';
                  Object.defineProperty(intercepted,'http',{value:async request=>({status:200,headers:{},body:request.url.endsWith('/sw.js_data')?")]}'\n"+JSON.stringify([['yt.sw.adr',null,[[tuple]]]]):JSON.stringify(request.url.includes('/youtubei/v1/player')?playerFixture:fixture)})});
                  await productionInit(intercepted);
                  context.service('fixture.youtube-js/v1',request=>selectAudio({...playerFixture,streamingData:{adaptiveFormats:[{itag:140,mimeType:'audio/mp4',url:'https://fixture.googlevideo.com/audio?expire=4102444800&n=blocked'}]}},{}));
                };
                """.trimIndent()
            val entries =
                source.walkTopDown().filter { it.isFile }.associate { file ->
                    file.relativeTo(source).invariantSeparatorsPath to
                        if (file.name == "index.js") file.readText() + "\n" + hook else file.readText()
                }
            val bytes =
                ByteArrayOutputStream().use { output ->
                    ZipOutputStream(output).use { zip ->
                        entries.forEach { (name, value) ->
                            zip.putNextEntry(ZipEntry(name))
                            zip.write(value.toByteArray())
                            zip.closeEntry()
                        }
                    }
                    output.toByteArray()
                }
            val data = Files.createTempDirectory("metrofuse-youtube-quickjs-").toFile()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val runtime = Runtime(data, "desktop", scope, EngineTestHost())
            try {
                runtime.initialize()
                runtime.install(ByteArrayInputStream(bytes), "fixture.mfp")
                runtime.enable(OWNER)
                assertEquals(
                    "Enabled",
                    runtime.plugins.value
                        .single()
                        .state,
                    runtime.logs.value.joinToString(),
                )
                val result = runtime.pipelines.search(SearchRequest("Fixture", 10))
                val track = result.tracks.single()
                assertEquals("metrofuse:$OWNER:abcdefghijk", track.id)
                assertEquals("MPREfixture", track.extras["albumId"])
                assertEquals(1, result.albums.size)
                assertEquals(1, result.artists.size)
                assertEquals(1, result.playlists.size)
                val album = assertNotNull(runtime.pipelines.album("metrofuse:$OWNER:MPREfixture"))
                assertEquals(1, album.tracks.size)
                val playlist = assertNotNull(runtime.pipelines.playlist("metrofuse:$OWNER:PLfixture"))
                assertEquals(2, playlist.tracks.size)
                val identity = TrackIdentity("abcdefghijk", mapOf("youtube" to "abcdefghijk", "pluginOwner" to OWNER))
                val stream = assertNotNull(runtime.pipelines.audio(AudioQuery(identity, "desktop"), includeBuiltin = false).stream)
                assertEquals("audio/mp4", stream.mimeType)
                assertEquals(128000, stream.bitrate)
                assertEquals(241L, stream.durationSeconds)
                val rejected = runtime.registry.invoke("fixture.youtube-js/v1", JsonObject(emptyMap()))
                assertEquals(
                    "N_TRANSFORM_UNAVAILABLE",
                    rejected
                        .getValue("error")
                        .jsonObject
                        .getValue("code")
                        .jsonPrimitive.content,
                )
                runtime.settingsFor(OWNER).set("audioEnabled", JsonPrimitive(false))
                runtime.pipelines.invalidateCaches()
                assertEquals(
                    "NO_SOURCE",
                    runtime.pipelines
                        .audio(AudioQuery(identity, "desktop"), includeBuiltin = false)
                        .error
                        ?.code,
                )
                runtime.disable(OWNER)
                assertUnregistered(runtime)
            } finally {
                runtime.close()
                scope.cancel()
                data.deleteRecursively()
            }
        }

    @Test
    fun guestNativeCatalogAndAudioCdnLive() =
        runBlocking {
            assumeTrue(
                "Set METROFUSE_YOUTUBE_MUSIC_LIVE=1 to validate guest catalog and audio",
                System.getenv("METROFUSE_YOUTUBE_MUSIC_LIVE") == "1",
            )
            assertTrue(nativeJar.isFile, "Build the optional native provider before live validation")
            val data = Files.createTempDirectory("metrofuse-youtube-live-").toFile()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val runtime = Runtime(data, "desktop", scope, EngineTestHost())
            try {
                runtime.initialize()
                nativeJar.inputStream().use { runtime.install(it, nativeJar.name) }
                runtime.enable(OWNER)
                assertEquals(
                    "Enabled",
                    runtime.plugins.value
                        .single()
                        .state,
                    runtime.logs.value.joinToString(),
                )
                runtime.pipelines.audioPolicy.value = AudioPolicy(timeoutMillis = 30000)
                val result = runtime.pipelines.search(SearchRequest("Adele Hello", 10))
                root.resolve("build/youtube-music-native-live-catalog.json").writeText(
                    buildJsonObject {
                        put("tracks", JsonPrimitive(result.tracks.joinToString { "${it.title} / ${it.artist}" }))
                        put("trackCount", result.tracks.size)
                        put("audioAttempted", false)
                    }.toString(),
                )
                val track =
                    assertNotNull(
                        result.tracks.firstOrNull {
                            it.title.equals("Hello", ignoreCase = true) &&
                                it.artist.contains("Adele", ignoreCase = true)
                        },
                        "Guest search did not return the original recording",
                    )
                val id = track.extras.getValue("youtube")
                val audio =
                    runtime.pipelines.audio(
                        AudioQuery(TrackIdentity(id, mapOf("youtube" to id, "pluginOwner" to OWNER)), "desktop"),
                        includeBuiltin = false,
                    )
                val stream = assertNotNull(audio.stream, "Guest audio failed: ${audio.error?.code}; ${runtime.pipelines.diagnostics.value}")
                val byteCount =
                    withContext(Dispatchers.IO) {
                        val connection = URI(stream.locator).toURL().openConnection() as HttpURLConnection
                        try {
                            connection.connectTimeout = 10000
                            connection.readTimeout = 10000
                            stream.headers.forEach(connection::setRequestProperty)
                            connection.setRequestProperty("Range", "bytes=0-65535")
                            assertTrue(connection.responseCode in setOf(200, 206), "Audio CDN returned HTTP ${connection.responseCode}")
                            connection.inputStream.use { input ->
                                val bytes = input.readNBytes(65536)
                                assertTrue(bytes.size > 2048, "Audio CDN did not supply a playable media prefix")
                                root.resolve("build/youtube-music-live-media-prefix.bin").writeBytes(bytes)
                                bytes.size
                            }
                        } finally {
                            connection.disconnect()
                        }
                    }
                root.resolve("build/youtube-music-live-verification.json").writeText(
                    buildJsonObject {
                        put("title", track.title)
                        put("artist", track.artist)
                        put("mimeType", stream.mimeType)
                        put("codec", stream.codec)
                        put("transport", stream.transport.name)
                        put("cdnBytesRead", byteCount)
                        put("decoderVerified", false)
                    }.toString(),
                )
            } finally {
                runtime.close()
                scope.cancel()
                data.deleteRecursively()
            }
        }

    private fun assertRegistered(runtime: Runtime) {
        assertEquals(1, runtime.registry.audioSources().count { it.owner == OWNER })
        assertEquals(1, runtime.registry.metadataSources().count { it.owner == OWNER })
        assertEquals(1, runtime.registry.searchProviders().count { it.owner == OWNER })
        assertEquals(1, runtime.registry.catalogProviders().count { it.owner == OWNER })
    }

    private fun packJavascriptSource(): ByteArray {
        val source = root.resolve("plugins/providers/youtube-music-js")
        return ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                source.walkTopDown().filter { it.isFile }.forEach { file ->
                    zip.putNextEntry(ZipEntry(file.relativeTo(source).invariantSeparatorsPath))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            output.toByteArray()
        }
    }

    private fun assertUnregistered(runtime: Runtime) {
        assertTrue(runtime.registry.audioSources().none { it.owner == OWNER })
        assertTrue(runtime.registry.metadataSources().none { it.owner == OWNER })
        assertTrue(runtime.registry.searchProviders().none { it.owner == OWNER })
        assertTrue(runtime.registry.catalogProviders().none { it.owner == OWNER })
    }

    private companion object {
        const val OWNER = "metrofuse.youtube-music"
    }
}
