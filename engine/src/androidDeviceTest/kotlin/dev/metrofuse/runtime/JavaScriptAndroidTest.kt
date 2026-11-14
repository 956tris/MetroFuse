package dev.metrofuse.runtime

import androidx.test.platform.app.InstrumentationRegistry
import android.os.Looper
import dev.metrofuse.api.AudioCapabilities
import dev.metrofuse.api.AudioQuery
import dev.metrofuse.api.AudioResult
import dev.metrofuse.api.AudioSource
import dev.metrofuse.api.HostLogger
import dev.metrofuse.api.HostNavigation
import dev.metrofuse.api.HostServices
import dev.metrofuse.api.HostSettings
import dev.metrofuse.api.JsonService
import dev.metrofuse.api.LyricsRequest
import dev.metrofuse.api.Manifest
import dev.metrofuse.api.PlaybackState
import dev.metrofuse.api.Player
import dev.metrofuse.api.ProviderError
import dev.metrofuse.api.SettingDefinition
import dev.metrofuse.api.SettingType
import dev.metrofuse.api.Track
import dev.metrofuse.api.TrackCatalog
import dev.metrofuse.api.TrackIdentity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JavaScriptAndroidTest {
    @Test
    fun universalArchiveExecutesAsyncQuickJsAndPersistsSettingsOnArt() = runBlocking {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val data = File(cache, "metrofuse-js-${UUID.randomUUID()}")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val host = AndroidEngineTestHost()
        val manifest = Manifest("test.androidjs", "Android JS fixture", "1.0.0", "Tests", runtime = "javascript", script = "index.js",
            settings = listOf(SettingDefinition("label", "Label", SettingType.STRING, JsonPrimitive("default"))))
        val script = """
            globalThis.plugin={async init(host){
              const ready=await host.call('fixture.delay/v1');if(!ready.ok)throw Error('Promise missing');
              host.metadataSource('android.fixture.metadata',async request=>({identity:request.identity,fields:{genre:'Ambient'},confidence:1}));
              host.lyrics('android.fixture.lyrics',async request=>({lines:[{text:'Android fixture',timeMs:0}]}));
              host.service('fixture.persist/v1',async()=>({label:await host.settings.get('label'),count:await host.storage.get('count')}));
              host.action('save',async()=>{await host.settings.set('label','saved on ART');await host.storage.set('count',7)});
              host.action('controls',async()=>{await host.player('play');await host.player('seek',123);await host.player('pause');});
              host.on('track_changed',()=>{});
              host.ui('settings.androidfixture',{id:'android-fixture',type:'Text',props:{text:'Android fixture'}});
            }};
        """.trimIndent()
        val bytes = ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                mapOf("manifest.json" to codec.encodeToString(manifest), "index.js" to script).forEach { (path, content) ->
                    zip.putNextEntry(ZipEntry(path)); zip.write(content.toByteArray()); zip.closeEntry()
                }
            }
            output.toByteArray()
        }
        fun registerDelay(runtime: Runtime) {
            runtime.registry.service("builtin", "fixture.delay/v1", object : JsonService {
                override suspend fun call(request: JsonObject): JsonObject { delay(700); return buildJsonObject { put("ok", true) } }
            })
        }
        val first = Runtime(data, "android", scope, host)
        try {
            registerDelay(first)
            first.initialize()
            first.install(ByteArrayInputStream(bytes), "fixture.mfp")
            withTimeout(10000) { first.enable(manifest.id) }
            assertEquals(first.logs.value.joinToString(), "Enabled", first.plugins.value.single().state)
            assertEquals(JsonPrimitive("Ambient"), first.metadataDocument.value.fields.getValue("genre").value)
            val lyrics = first.registry.lyricsProviders().single().provider.lyrics(LyricsRequest(TrackIdentity("fixture:one")))
            assertEquals("Android fixture", lyrics!!.lines.single().text)
            first.dispatchAction(manifest.id, "save")
            first.dispatchAction(manifest.id, "controls")
            assertEquals(123L, host.playback.state.value.position)
            assertFalse(host.playback.state.value.playing)
            repeat(3) { first.hotReplace(manifest.id); assertEquals(1, first.resources().eventSubscriptions) }
            assertFalse(data.resolve("running.marker").exists())
            first.close()
            val second = Runtime(data, "android", scope, host)
            try {
                registerDelay(second)
                second.initialize()
                assertFalse(second.recovery.value)
                assertEquals("Enabled", second.plugins.value.single().state)
                val persisted = second.registry.invoke("fixture.persist/v1", buildJsonObject {})
                assertEquals("saved on ART", persisted.getValue("label").jsonPrimitive.content)
                assertEquals("7", persisted.getValue("count").jsonPrimitive.content)
                second.disable(manifest.id)
                assertEquals(0, second.resources().sessions)
                assertEquals(0, second.resources().eventSubscriptions)
                assertTrue(second.registry.state.value.ui.isEmpty())
                assertTrue(second.registry.lyricsProviders().isEmpty())
            } finally { second.close() }
        } finally {
            first.close(); scope.cancel()
        }
    }
}

private class AndroidEngineTestHost : HostServices {
    val track = Track("fixture:one", "Android fixture", "Fixtures", "Local")
    override val playback = object : Player {
        override val state = MutableStateFlow(PlaybackState(track))
        override fun toggle() { assertEquals(Looper.getMainLooper(), Looper.myLooper()); state.value = state.value.copy(playing = !state.value.playing) }
        override fun seek(position: Long) { assertEquals(Looper.getMainLooper(), Looper.myLooper()); state.value = state.value.copy(position = position) }
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
        override suspend fun resolve(request: AudioQuery) = AudioResult(error = ProviderError("NO_PROVIDER"))
    }
}
