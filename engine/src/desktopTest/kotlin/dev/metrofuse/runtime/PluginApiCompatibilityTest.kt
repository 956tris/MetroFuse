package dev.metrofuse.runtime

import com.sun.net.httpserver.HttpServer
import dev.metrofuse.api.API_VERSION
import dev.metrofuse.api.AudioQuery
import dev.metrofuse.api.COMPOSE_ABI
import dev.metrofuse.api.HttpRequest
import dev.metrofuse.api.LEGACY_API_VERSION_1_1
import dev.metrofuse.api.LEGACY_COMPOSE_ABI_1_1
import dev.metrofuse.api.LyricsRequest
import dev.metrofuse.api.Manifest
import dev.metrofuse.api.NativePlugin
import dev.metrofuse.api.PluginContext
import dev.metrofuse.api.SearchRequest
import dev.metrofuse.api.TrackIdentity
import dev.metrofuse.sdk.MetroFusePlugin
import dev.metrofuse.sdk.PluginBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.tools.ToolProvider
import kotlin.coroutines.Continuation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SimpleApi12NativeFixture : MetroFusePlugin() {
    override suspend fun PluginBuilder.setup() {
        search("compat.simple12.search") { req ->
            dev.metrofuse.api.SearchResult(
                listOf(dev.metrofuse.api.Track("s1", req.query, "MetroFuse", "1.2")),
            )
        }
        service("compat.simple12/v1") { _ -> buildJsonObject { put("api", "1.2.0") } }
        context.log("1.2.0 native DSL active")
    }
}

class PluginApiCompatibilityTest {
    private val digest = "0123456789abcdef".repeat(4)

    @Test
    fun api110And120JsAndNativePluginsBothInstallActivateAndPassMarketplaceWhileUnsupportedVersionsFail() =
        runBlocking {
            val root = Files.createTempDirectory("metrofuse-api-compat-").toFile()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val runtime = Runtime(File(root, "host"), "desktop", scope, EngineTestHost())
            try {
                runtime.initialize()

                // 1) Existing 1.1.0 JS .mfp plugin (globalThis.plugin = { init(host) })
                val js11Bytes =
                    mfpBytes(
                        Manifest("compat.js.legacy", "Legacy JS 1.1", "1.0.0", "MetroFuse", LEGACY_API_VERSION_1_1, "javascript", script = "index.js"),
                        mapOf(
                            "index.js" to """
                                globalThis.plugin = {
                                  async init(host) {
                                    host.search('compat.js11.search', q => ({ tracks: [{ id: '11', title: q.query, artist: 'A', album: 'B' }] }));
                                  }
                                };
                            """.trimIndent(),
                        ),
                    )
                runtime.install(ByteArrayInputStream(js11Bytes), "legacy-11.mfp")
                runtime.enable("compat.js.legacy")
                assertEquals("Enabled", runtime.plugins.value.first { it.manifest.id == "compat.js.legacy" }.state)
                assertEquals(1, runtime.pipelines.search(SearchRequest("hello"), setOf("compat.js.legacy")).tracks.size)

                // 2) Existing 1.1.0 native Kotlin JAR built against legacy Compose ABI 1.1.0
                val jar11 = compileJavaNativeJar(root, "legacy11", LEGACY_API_VERSION_1_1, LEGACY_COMPOSE_ABI_1_1)
                runtime.install(jar11.inputStream(), "legacy-11.jar")
                runtime.enable("compat.native.legacy11")
                assertEquals("Enabled", runtime.plugins.value.first { it.manifest.id == "compat.native.legacy11" }.state)

                // 3) New 1.2.0 JS .mfp plugin using definePlugin(...) + extra JSON fields + host.json()
                val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
                server.createContext("/data.json") { exchange ->
                    val payload = """{"ok":true,"title":"FromHostJson"}""".toByteArray()
                    exchange.sendResponseHeaders(200, payload.size.toLong())
                    exchange.responseBody.use { it.write(payload) }
                }
                server.start()
                try {
                    val url = "http://127.0.0.1:${server.address.port}/data.json"
                    val js12Bytes =
                        mfpBytes(
                            Manifest(
                                "compat.js.simple",
                                "Simple JS 1.2",
                                "1.0.0",
                                "MetroFuse",
                                API_VERSION,
                                "javascript",
                                script = "index.js",
                                capabilities = listOf("search", "lyrics", "audio"),
                            ),
                            mapOf(
                                "index.js" to """
                                    definePlugin({
                                      search: {
                                        'compat.js12.search': async req => {
                                          const remote = await host.json('$url');
                                          return { tracks: [{ id: '12', title: remote.title + ':' + req.query, artist: 'A', album: 'B', unknownExtra: 42 }] };
                                        }
                                      },
                                      lyrics: {
                                        'compat.js12.lyrics': async () => ({ lines: [{ text: 'Line 1', unknownWordProp: true }] })
                                      },
                                      audioSources: {
                                        'compat.js12.audio': async () => ({ stream: { transport: 'DIRECT', locator: 'https://example.invalid/a.mp3' }, extraField: 'ignored' })
                                      }
                                    });
                                """.trimIndent(),
                            ),
                        )
                    runtime.install(ByteArrayInputStream(js12Bytes), "simple-12.mfp")
                    runtime.enable("compat.js.simple")
                    assertEquals("Enabled", runtime.plugins.value.first { it.manifest.id == "compat.js.simple" }.state)
                    val searchRes = runtime.pipelines.search(SearchRequest("world"), setOf("compat.js.simple"))
                    assertEquals("FromHostJson:world", searchRes.tracks.single().title)
                    val lyricsRes = runtime.pipelines.lyrics(LyricsRequest(TrackIdentity("id"), "Song", "Artist"))
                    assertEquals("Line 1", lyricsRes?.lines?.single()?.text)
                    val audioRes = runtime.pipelines.audio(AudioQuery(TrackIdentity("id"), "desktop", sourceId = "compat.js12.audio"))
                    assertEquals("https://example.invalid/a.mp3", audioRes.stream?.locator)
                } finally {
                    server.stop(0)
                }

                // 4) New 1.2.0 native Kotlin JAR using MetroFusePlugin DSL compiled in host test classpath
                val jar12 = packageHostClassAsJar(root, SimpleApi12NativeFixture::class.java, "compat.native.simple12", API_VERSION, COMPOSE_ABI)
                runtime.install(jar12.inputStream(), "simple-12.jar")
                runtime.enable("compat.native.simple12")
                assertEquals("Enabled", runtime.plugins.value.first { it.manifest.id == "compat.native.simple12" }.state)
                assertEquals(1, runtime.pipelines.search(SearchRequest("q"), setOf("compat.native.simple12")).tracks.size)
                assertEquals(JsonPrimitive("1.2.0"), runtime.registry.invoke("compat.simple12/v1", buildJsonObject {})["api"])

                // 5) Marketplace accepts both 1.1.0 and 1.2.0, rejects 1.0.0 / 1.3.0 / 2.0.0
                val artifact = MarketplaceArtifact("https://example.org/plugin.mfp", digest, "plugin.mfp")
                assertNotNull(MarketplaceEntry("p11", "P11", "A", "D", "1.0.0", "1.1.0", mapOf("universal" to artifact)).artifact("android"))
                assertNotNull(MarketplaceEntry("p12", "P12", "A", "D", "1.0.0", "1.2.0", mapOf("universal" to artifact)).artifact("desktop"))
                for (badApi in listOf("1.0.0", "1.3.0", "2.0.0")) {
                    assertNull(MarketplaceEntry("bad", "Bad", "A", "D", "1.0.0", badApi, mapOf("universal" to artifact)).artifact("desktop"))
                    assertFailsWith<IllegalArgumentException> {
                        PackageStore.validateManifest(
                            Manifest("bad.js.$badApi".replace('.', '_').let { "bad.js.v" }, "Bad", "1.0.0", "A", badApi, "javascript", script = "index.js"),
                            "desktop",
                        )
                    }
                    assertFailsWith<IllegalArgumentException> {
                        PackageStore.validateManifest(
                            Manifest(
                                "bad.native.v",
                                "Bad",
                                "1.0.0",
                                "A",
                                badApi,
                                "kotlin",
                                entryClass = "fixture.Native",
                                composeAbi = "kotlin-2.4.21_compose-1.13.0-beta02_api-$badApi",
                            ),
                            "desktop",
                        )
                    }
                }
            } finally {
                runtime.close()
                scope.cancel()
                root.deleteRecursively()
            }
        }

    @Test
    fun jsEvaluateSlotIsolationAndHttpContentTypeAndAdminCapabilityChecksHold() =
        runBlocking {
            val root = Files.createTempDirectory("metrofuse-hardening-checks-").toFile()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val runtime = Runtime(File(root, "host"), "desktop", scope, EngineTestHost())
            val observedContentTypes = mutableListOf<String?>()
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/token") { exchange ->
                observedContentTypes += exchange.requestHeaders.getFirst("Content-Type")
                val largeBody = ("{\"token\":\"" + "x".repeat(2_500_000) + "\"}").toByteArray()
                exchange.sendResponseHeaders(200, largeBody.size.toLong())
                exchange.responseBody.use { it.write(largeBody) }
            }
            server.start()
            try {
                runtime.initialize()
                runtime.registry.service(
                    "builtin",
                    "ui.settings/v1",
                    object : dev.metrofuse.api.JsonService {
                        override suspend fun call(request: kotlinx.serialization.json.JsonObject) = buildJsonObject { put("ok", true) }
                    },
                )
                val url = "http://127.0.0.1:${server.address.port}/token"
                val mfp =
                    mfpBytes(
                        Manifest("compat.js.hardening", "Hardening", "1.0.0", "MetroFuse", API_VERSION, "javascript", script = "index.js"),
                        mapOf(
                            "index.js" to """
                                definePlugin({
                                  search: {
                                    'compat.race.search': async req => {
                                      if (req.query === 'slow') {
                                        const res = await host.http({
                                          url: '$url',
                                          method: 'POST',
                                          headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                                          body: 'grant_type=client_credentials',
                                          maxBytes: 4000000
                                        });
                                        return { tracks: [{ id: 'slow', title: String(JSON.parse(res.body).token.length), artist: 'A', album: 'B' }] };
                                      }
                                      return { tracks: [{ id: 'fast', title: 'fast-result', artist: 'A', album: 'B' }] };
                                    }
                                  },
                                  services: {
                                    'compat.adminprobe/v1': async () => host.call('ui.settings/v1', { action: 'snapshot' })
                                  }
                                });
                            """.trimIndent(),
                        ),
                    )
                runtime.install(ByteArrayInputStream(mfp), "hardening.mfp")
                runtime.enable("compat.js.hardening")

                // Non-UI plugin must not invoke host administration services
                assertFailsWith<IllegalArgumentException> {
                    runtime.registry.invoke("compat.adminprobe/v1", buildJsonObject {})
                }

                // Concurrent search calls preserve distinct per-call slots AND honor Content-Type + >2MB response
                val slow = async { runtime.pipelines.search(SearchRequest("slow"), setOf("compat.js.hardening")) }
                delay(10)
                val fast = async { runtime.pipelines.search(SearchRequest("fast"), setOf("compat.js.hardening")) }
                assertEquals("2500000", slow.await().tracks.single().title)
                assertEquals("fast-result", fast.await().tracks.single().title)
                assertTrue(observedContentTypes.single().orEmpty().startsWith("application/x-www-form-urlencoded"))
            } finally {
                server.stop(0)
                runtime.close()
                scope.cancel()
                root.deleteRecursively()
            }
        }

    @Test
    fun expectedAudioSkipCodesDoNotSpamFailureReporter() =
        runBlocking {
            val reports = mutableListOf<String>()
            val registry = Registry { _, _, error -> reports += (error as? PluginProviderException)?.diagnostic?.code ?: error.javaClass.simpleName }
            val pipelines = ProviderPipelines(registry)
            registry.audioSource(
                "skip.plugin",
                object : dev.metrofuse.api.AudioSource {
                    override val id = "skip.source"
                    override val capabilities = dev.metrofuse.api.AudioCapabilities(setOf(dev.metrofuse.api.StreamTransport.DIRECT))

                    override suspend fun resolve(request: AudioQuery): dev.metrofuse.api.AudioResult =
                        dev.metrofuse.api.AudioResult(error = dev.metrofuse.api.ProviderError("NO_MATCH", "Not found"))
                },
            )
            pipelines.audio(AudioQuery(TrackIdentity("id"), "desktop", sourceId = "skip.source"))
            assertTrue(reports.isEmpty(), "Expected skip codes must not report failures to Sentry: $reports")
            assertEquals("NO_MATCH", pipelines.diagnostics.value.last().outcome)
        }

    private fun mfpBytes(
        manifest: Manifest,
        files: Map<String, String>,
    ): ByteArray {
        val fullManifest = manifest.copy(integrity = files.mapValues { (_, content) -> sha256(content.toByteArray()) })
        return ByteArrayOutputStream().use { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(codec.encodeToString(fullManifest).toByteArray())
                zip.closeEntry()
                files.forEach { (name, content) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(content.toByteArray())
                    zip.closeEntry()
                }
            }
            out.toByteArray()
        }
    }

    private fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun compileJavaNativeJar(
        root: File,
        suffix: String,
        api: String,
        composeAbi: String,
    ): File {
        val classes = File(root, "classes-$suffix").apply { mkdirs() }
        val src = File(root, "src-$suffix/LegacyPlugin.java").apply { parentFile.mkdirs() }
        src.writeText(
            """
            package compat.$suffix;
            import dev.metrofuse.api.*;
            import kotlin.Unit;
            import kotlin.coroutines.Continuation;
            public final class LegacyPlugin implements NativePlugin {
              public Object start(PluginContext context, Continuation<? super Unit> completion) {
                context.log("Legacy $suffix active");
                return Unit.INSTANCE;
              }
              public Object stop(Continuation<? super Unit> completion) { return Unit.INSTANCE; }
            }
            """.trimIndent(),
        )
        val cp =
            (
                System.getProperty("java.class.path").split(File.pathSeparator) +
                    listOf(NativePlugin::class.java, Unit::class.java, Continuation::class.java)
                        .map { File(it.protectionDomain.codeSource.location.toURI()).absolutePath }
            ).distinct().joinToString(File.pathSeparator)
        val compiler = requireNotNull(ToolProvider.getSystemJavaCompiler())
        assertEquals(0, compiler.run(null, null, null, "-classpath", cp, "-d", classes.absolutePath, src.absolutePath))
        val jar = File(root, "native-$suffix.jar")
        val manifest =
            Manifest(
                id = "compat.native.$suffix",
                name = "Legacy Native $suffix",
                version = "1.0.0",
                author = "MetroFuse",
                api = api,
                runtime = "kotlin",
                entryClass = "compat.$suffix.LegacyPlugin",
                composeAbi = composeAbi,
            )
        JarOutputStream(jar.outputStream()).use { out ->
            out.putNextEntry(JarEntry("manifest.json"))
            out.write(codec.encodeToString(manifest).toByteArray())
            out.closeEntry()
            classes.walkTopDown().filter(File::isFile).forEach { file ->
                out.putNextEntry(JarEntry(file.relativeTo(classes).invariantSeparatorsPath))
                file.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        }
        return jar
    }

    private fun packageHostClassAsJar(
        root: File,
        type: Class<*>,
        id: String,
        api: String,
        composeAbi: String,
    ): File {
        val jar = File(root, "$id.jar")
        val packagePath = type.`package`.name.replace('.', '/')
        val classPrefix = type.simpleName
        val manifest =
            Manifest(
                id = id,
                name = "Simple Kotlin DSL",
                version = "1.0.0",
                author = "MetroFuse",
                api = api,
                runtime = "kotlin",
                entryClass = type.name,
                composeAbi = composeAbi,
                capabilities = listOf("search", "services"),
            )
        JarOutputStream(jar.outputStream()).use { out ->
            out.putNextEntry(JarEntry("manifest.json"))
            out.write(codec.encodeToString(manifest).toByteArray())
            out.closeEntry()
            val classesRoot = File(type.protectionDomain.codeSource.location.toURI())
            val packageDir = File(classesRoot, packagePath)
            val classFiles =
                packageDir
                    .listFiles { file ->
                        file.isFile &&
                            (file.name == "$classPrefix.class" || (file.name.startsWith("$classPrefix$") && file.name.endsWith(".class")))
                    }.orEmpty()
                    .sortedBy { it.name }
            check(classFiles.isNotEmpty()) { "Missing compiled classes for ${type.name} in $packageDir" }
            classFiles.forEach { file ->
                out.putNextEntry(JarEntry("$packagePath/${file.name}"))
                file.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        }
        return jar
    }
}
