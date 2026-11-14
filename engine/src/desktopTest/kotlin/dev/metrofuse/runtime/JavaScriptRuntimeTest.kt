package dev.metrofuse.runtime

import dev.metrofuse.api.AudioCapabilities
import dev.metrofuse.api.AudioQuery
import dev.metrofuse.api.AudioResult
import dev.metrofuse.api.AudioSource
import dev.metrofuse.api.HostLogger
import dev.metrofuse.api.HostNavigation
import dev.metrofuse.api.HostServices
import dev.metrofuse.api.HostSettings
import dev.metrofuse.api.HttpRequest
import dev.metrofuse.api.JsonService
import dev.metrofuse.api.LyricsRequest
import dev.metrofuse.api.Manifest
import dev.metrofuse.api.PlaybackState
import dev.metrofuse.api.Player
import dev.metrofuse.api.ProviderError
import dev.metrofuse.api.SearchRequest
import dev.metrofuse.api.SettingDefinition
import dev.metrofuse.api.SettingType
import dev.metrofuse.api.Track
import dev.metrofuse.api.TrackCatalog
import dev.metrofuse.api.TrackIdentity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import com.sun.net.httpserver.HttpServer
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Test

internal class EngineTestHost : HostServices {
    val track = Track("fixture:one", "Original", "Fixtures", "Local")
    override val playback = object : Player {
        override val state = MutableStateFlow(PlaybackState(track))
        override fun toggle() { state.value = state.value.copy(playing = !state.value.playing) }
        override fun seek(position: Long) { state.value = state.value.copy(position = position) }
        override fun next() {}
    }
    override val navigation = object : HostNavigation {
        override val destination = MutableStateFlow("home")
        override fun navigate(destination: String) { this.destination.value = destination }
    }
    override val catalog = object : TrackCatalog {
        override val tracks = listOf(track)
        override suspend fun identify(identity: TrackIdentity) = track.takeIf { it.id == identity.canonicalId }
    }
    override val settings = object : HostSettings {
        override fun get(key: String): String? = null
        override fun set(key: String, value: String?) {}
    }
    override val logger = HostLogger { _, _ -> }
    override val audioFallback = object : AudioSource {
        override val id = "builtin.unavailable"
        override val capabilities = AudioCapabilities(emptySet())
        override suspend fun resolve(request: AudioQuery) = AudioResult(error = ProviderError("NO_PROVIDER", "No provider"))
    }
}

class JavaScriptRuntimeTest {
    private fun packageBytes(manifest: Manifest, script: String, modules: Map<String, String> = emptyMap()): ByteArray {
        val entries = mapOf("manifest.json" to codec.encodeToString(manifest), manifest.script!! to script) + modules
        return ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip -> entries.forEach { (name, contents) -> zip.putNextEntry(ZipEntry(name)); zip.write(contents.toByteArray()); zip.closeEntry() } }
            output.toByteArray()
        }
    }

    private fun manifest(id: String = "test.script", settings: List<SettingDefinition> = emptyList()) = Manifest(id, "JS fixture", "1.0.0", "Tests", runtime = "javascript", script = "index.js", settings = settings)

    private suspend fun fixture(platform: String = "desktop", block: suspend (Runtime) -> Unit) {
        val data = Files.createTempDirectory("metrofuse-js-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runtime = Runtime(data, platform, scope, EngineTestHost())
        try { runtime.initialize(); block(runtime) } finally { runtime.close(); scope.cancel() }
    }

    @Test
    fun realModulesAsyncServicesAndRepeatedReloadCleanUp() = runBlocking {
        fixture { runtime ->
            runtime.registry.service("builtin", "fixture.delay/v1", object : JsonService {
                override suspend fun call(request: JsonObject): JsonObject { delay(700); return buildJsonObject { put("ok", true) } }
            })
            val script = """
                import {label} from './modules/label.js';
                globalThis.plugin={async init(host){
                  const result=await host.call('fixture.delay/v1'); if(!result.ok)throw Error('Missing result');
                  host.service('fixture.ready/v1',()=>({label}));host.on('sample',()=>{});
                  host.ui('settings.fixture',{id:'fixture',type:'Text',props:{text:label}});
                }};
            """.trimIndent()
            runtime.install(ByteArrayInputStream(packageBytes(manifest(), script, mapOf("modules/label.js" to "export const label='loaded module';"))))
            withTimeout(5000) { runtime.enable("test.script") }
            assertEquals("Enabled", runtime.plugins.value.single().state, runtime.logs.value.joinToString())
            assertEquals("loaded module", runtime.registry.invoke("fixture.ready/v1", buildJsonObject {}).getValue("label").jsonPrimitive.content)
            repeat(4) { runtime.hotReplace("test.script"); assertEquals(1, runtime.resources().eventSubscriptions) }
            runtime.disable("test.script")
            assertEquals(0, runtime.resources().sessions)
            assertEquals(0, runtime.resources().eventSubscriptions)
            assertTrue(runtime.registry.state.value.ui.isEmpty())
        }
    }

    @Test
    fun invalidExportsPromiseRejectionsAndInfiniteLoopAreVisible() = runBlocking {
        fixture { runtime ->
            val scripts = listOf(
                "globalThis.plugin={init:42};",
                "globalThis.plugin={async init(host){host.ui('settings.fixture',{id:'partial',type:'Text'});throw Error('Rejected promise')}};",
                "globalThis.plugin={init(host){host.on('sample',()=>{});while(true){}}};",
                "globalThis.plugin={init(host){host.lyrics('bad',42)}};",
            )
            scripts.forEachIndexed { index, script ->
                val id = "test.failure$index"
                runtime.install(ByteArrayInputStream(packageBytes(manifest(id), script)))
                withTimeout(5000) { runtime.enable(id) }
                assertEquals("Failed", runtime.plugins.value.first { it.manifest.id == id }.state)
                assertNotNull(runtime.plugins.value.first { it.manifest.id == id }.error)
                assertTrue(runtime.registry.state.value.ui.isEmpty())
                assertEquals(0, runtime.resources().sessions)
            }
        }
    }

    @Test
    fun callerCancellationAndDisableReleasePendingPromise() = runBlocking {
        fixture { runtime ->
            runtime.registry.service("builtin", "fixture.wait/v1", object : JsonService {
                override suspend fun call(request: JsonObject): JsonObject { delay(20000); return buildJsonObject {} }
            })
            runtime.install(ByteArrayInputStream(packageBytes(manifest(), "globalThis.plugin={init(host){host.action('wait',()=>host.call('fixture.wait/v1'));}}")))
            runtime.enable("test.script")
            val pending = async { runtime.dispatchAction("test.script", "wait") }
            delay(150)
            withTimeout(5000) { pending.cancelAndJoin(); runtime.disable("test.script") }
            assertEquals(0, runtime.resources().sessions)
            runtime.enable("test.script")
            val owned = async { runCatching { runtime.dispatchAction("test.script", "wait") } }
            delay(150)
            withTimeout(5000) { runtime.disable("test.script"); owned.await() }
            assertEquals(0, runtime.resources().eventSubscriptions)
        }
    }

    @Test
    fun isolatedSettingsAndStorageSurviveRestart() = runBlocking {
        val data = Files.createTempDirectory("metrofuse-settings-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val definitions = listOf(SettingDefinition("label", "Label", SettingType.STRING, JsonPrimitive("default")))
        val script = "globalThis.plugin={async init(host){host.service('fixture.settings/v1',async()=>({label:await host.settings.get('label'),count:await host.storage.get('count')}));host.action('change',async value=>{await host.settings.set('label',value);await host.storage.set('count',3)});}};"
        try {
            val first = Runtime(data, "desktop", scope, EngineTestHost())
            first.initialize(); first.install(ByteArrayInputStream(packageBytes(manifest(settings = definitions), script))); first.enable("test.script")
            first.dispatchAction("test.script", "change", "saved")
            assertFailsWith<IllegalArgumentException> { first.settingsFor("test.script").set("missing", JsonPrimitive(true)) }
            assertFailsWith<IllegalArgumentException> { first.settingsFor("test.script").set("label", JsonPrimitive(3)) }
            first.close()
            val second = Runtime(data, "desktop", scope, EngineTestHost())
            try {
                second.initialize()
                assertEquals("Enabled", second.plugins.value.single().state, second.logs.value.joinToString())
                val result = second.registry.invoke("fixture.settings/v1", buildJsonObject {})
                assertEquals("saved", result.getValue("label").jsonPrimitive.content)
                assertEquals("3", result.getValue("count").jsonPrimitive.content)
            } finally { second.close() }
            val separate = PersistentPluginSettings(data.resolve("data/other/settings.json"), definitions)
            separate.load()
            assertEquals(JsonPrimitive("default"), separate.values.value.getValue("label"))
        } finally { scope.cancel() }
    }

    @Test
    fun typedLyricsSearchAndMetadataRegistration() = runBlocking {
        fixture { runtime ->
            val script = """
                globalThis.plugin={init(host){
                  host.lyrics('fixture.lyrics',async request=>({lines:[{text:request.identity.canonicalId,timeMs:0}]}));
                  host.search('fixture.search',async request=>({tracks:[{id:'fixture:one',title:request.query,artist:'Fixture',album:'Local'}]}));
                  host.metadataSource('fixture.metadata',async request=>({identity:request.identity,fields:{genre:'Ambient'},confidence:1}));
                  host.catalog('fixture.catalog',{album:async id=>({id,title:'Fixture album',artist:'Fixtures'}),artist:async id=>({id,name:'Fixtures'}),playlist:async id=>({id,title:'Fixture playlist',author:'Fixtures'})});
                }};
            """.trimIndent()
            runtime.install(ByteArrayInputStream(packageBytes(manifest(), script)))
            runtime.enable("test.script")
            assertEquals("Enabled", runtime.plugins.value.single().state, runtime.logs.value.joinToString())
            val lyrics = runtime.registry.lyricsProviders().single().provider.lyrics(LyricsRequest(TrackIdentity("fixture:one")))
            assertEquals("fixture:one", lyrics!!.lines.single().text)
            val search = runtime.registry.searchProviders().single().provider.search(SearchRequest("query"))
            assertEquals("query", search.tracks.single().title)
            val catalog = runtime.registry.catalogProviders().single().provider
            assertEquals("Fixture album", catalog.album("album")!!.title)
            assertEquals("Fixtures", catalog.artist("artist")!!.name)
            assertEquals("Fixture playlist", catalog.playlist("playlist")!!.title)
            assertEquals(JsonPrimitive("Ambient"), runtime.metadataDocument.value.fields.getValue("genre").value)
            runtime.disable("test.script")
            assertTrue(runtime.registry.lyricsProviders().isEmpty())
            assertTrue(runtime.registry.searchProviders().isEmpty())
            assertTrue(runtime.registry.catalogProviders().isEmpty())
        }
    }

    @Test
    fun androidOrdinaryProcessDeathDoesNotSetRecoveryAfterInitialization() = runBlocking {
        fixture("android") { runtime ->
            assertFalse(runtime.storage.resolve("running.marker").exists())
            runtime.install(ByteArrayInputStream(packageBytes(manifest(), "globalThis.plugin={init(){}}")))
            runtime.enable("test.script")
            assertFalse(runtime.storage.resolve("running.marker").exists())
        }
    }

    @Test
    fun asyncHttpBridgeBoundedBodiesAndCancellationUseLocalServer() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool { runnable -> Thread(runnable, "metrofuse-http-test").apply { isDaemon = true } }
        server.executor = executor
        val received = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.createContext("/fixture") { exchange ->
            val bytes = "{\"ok\":true}".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/wait") { exchange ->
            received.countDown()
            release.await(5, TimeUnit.SECONDS)
            runCatching { exchange.sendResponseHeaders(204, -1); exchange.close() }
        }
        server.start()
        val baseUrl = "http://127.0.0.1:${server.address.port}"
        val network = HostPluginNetwork()
        try {
            fixture { runtime ->
                val script = "globalThis.plugin={async init(host){const response=await host.http({url:'$baseUrl/fixture'});if(response.status!==200)throw Error('HTTP status');host.service('fixture.http/v1',()=>JSON.parse(response.body));}}"
                runtime.install(ByteArrayInputStream(packageBytes(manifest(), script)))
                runtime.enable("test.script")
                assertEquals("Enabled", runtime.plugins.value.single().state, runtime.logs.value.joinToString())
                assertEquals(JsonPrimitive(true), runtime.registry.invoke("fixture.http/v1", buildJsonObject {}).getValue("ok"))
            }
            assertFailsWith<IllegalArgumentException> { network.request(HttpRequest("$baseUrl/fixture", maxBytes = 2)) }
            val pending = async(Dispatchers.IO) { network.request(HttpRequest("$baseUrl/wait")) }
            assertTrue(received.await(5, TimeUnit.SECONDS))
            withTimeout(2000) { pending.cancelAndJoin() }
        } finally {
            release.countDown(); network.close(); server.stop(0); executor.shutdownNow()
        }
    }

    @Test
    fun failedRollbackActivationNeverReportsAnActivePlugin() = runBlocking {
        fixture { runtime ->
            var activations = 0
            runtime.registry.service("builtin", "fixture.activation/v1", object : JsonService {
                override suspend fun call(request: JsonObject) = buildJsonObject { put("ok", ++activations == 1) }
            })
            val original = "globalThis.plugin={async init(host){const result=await host.call('fixture.activation/v1');if(!result.ok)throw Error('Previous activation unavailable');host.service('fixture.ready/v1',()=>({ok:true}));}}"
            runtime.install(ByteArrayInputStream(packageBytes(manifest(), original)))
            runtime.enable("test.script")
            assertEquals("Enabled", runtime.plugins.value.single().state)
            val broken = packageBytes(manifest().copy(version = "1.0.1"), "globalThis.plugin={init(){throw Error('Broken update')}}")
            assertFailsWith<IllegalArgumentException> { runtime.installOrUpdate(ByteArrayInputStream(broken)) }
            assertEquals("1.0.0", runtime.plugins.value.single().manifest.version)
            assertEquals("Failed", runtime.plugins.value.single().state)
            assertTrue(runtime.plugins.value.single().error!!.contains("previous activation failed"))
            assertEquals(0, runtime.resources().sessions)
            assertTrue(runtime.registry.service("fixture.ready/v1") == null)
            assertTrue(codec.decodeFromString<Configuration>(runtime.exportConfiguration()).enabled.isEmpty())
        }
    }

    @Test
    fun canceledUpdateRestoresPreviousActivation() = runBlocking {
        fixture { runtime ->
            val entered = CompletableDeferred<Unit>()
            runtime.registry.service("builtin", "fixture.wait/v1", object : JsonService {
                override suspend fun call(request: JsonObject): JsonObject { entered.complete(Unit); delay(20000); return buildJsonObject {} }
            })
            runtime.install(ByteArrayInputStream(packageBytes(manifest(), "globalThis.plugin={init(host){host.service('fixture.ready/v1',()=>({ok:true}));}}")))
            runtime.enable("test.script")
            val update = packageBytes(manifest().copy(version = "1.0.1"), "globalThis.plugin={async init(host){await host.call('fixture.wait/v1')}}")
            val pending = async { runtime.installOrUpdate(ByteArrayInputStream(update)) }
            withTimeout(5000) { entered.await() }
            withTimeout(5000) { pending.cancelAndJoin() }
            assertEquals("1.0.0", runtime.plugins.value.single().manifest.version)
            assertEquals("Enabled", runtime.plugins.value.single().state, runtime.logs.value.joinToString())
            assertEquals(1, runtime.resources().sessions)
            assertEquals(JsonPrimitive(true), runtime.registry.invoke("fixture.ready/v1", buildJsonObject {}).getValue("ok"))
        }
    }
}
