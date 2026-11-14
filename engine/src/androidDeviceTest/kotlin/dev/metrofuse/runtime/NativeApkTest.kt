package dev.metrofuse.runtime

import android.media.MediaPlayer
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import dev.metrofuse.api.AudioCapabilities
import dev.metrofuse.api.AudioQuery
import dev.metrofuse.api.AudioResult
import dev.metrofuse.api.AudioSource
import dev.metrofuse.api.HostLogger
import dev.metrofuse.api.HostNavigation
import dev.metrofuse.api.HostServices
import dev.metrofuse.api.HostSettings
import dev.metrofuse.api.NativePlugin
import dev.metrofuse.api.PlaybackState
import dev.metrofuse.api.Player
import dev.metrofuse.api.ProviderError
import dev.metrofuse.api.SearchRequest
import dev.metrofuse.api.Track
import dev.metrofuse.api.TrackCatalog
import dev.metrofuse.api.TrackIdentity
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeApkTest {
    @Test
    fun apkLifecycleResolvesSearchFixtureAndPlaysWavOnAndroid() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext
        initializeNativePlatform(application)
        val root = File(application.cacheDir, "native-lifecycle-${UUID.randomUUID()}").apply { mkdir() }
        val fixture = File(root, "fixture.wav")
        writeWav(fixture)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runtime = Runtime(File(root, "host"), "android", scope, testHost())
        try {
            runtime.initialize()
            runtime.install(instrumentation.context.assets.open("native-audio.apk"), "native-audio.apk")
            runtime.settingsFor("example.native.audio").set("fixtureUri", JsonPrimitive(fixture.toURI().toString()))
            runtime.enable("example.native.audio")
            assertEquals("Enabled", runtime.plugins.value.single().state)
            val track = runtime.pipelines.search(SearchRequest("fixture")).tracks.single()
            assertEquals(fixture.toURI().toString(), track.extras["fixtureUri"])
            val stream = requireNotNull(runtime.pipelines.audio(AudioQuery(TrackIdentity(track.id, track.extras), "android"), includeBuiltin = false).stream)
            assertEquals(fixture.toURI().toString(), stream.locator)
            val completion = CountDownLatch(1)
            val playbackError = AtomicReference<String?>()
            lateinit var mediaPlayer: MediaPlayer
            instrumentation.runOnMainSync {
                mediaPlayer = MediaPlayer()
                mediaPlayer.setOnPreparedListener { it.start() }
                mediaPlayer.setOnCompletionListener { completion.countDown() }
                mediaPlayer.setOnErrorListener { _, what, extra -> playbackError.set("$what:$extra"); completion.countDown(); true }
                mediaPlayer.setDataSource(application, Uri.parse(stream.locator))
                mediaPlayer.prepareAsync()
            }
            try {
                assertTrue("Android WAV playback did not complete", completion.await(10, TimeUnit.SECONDS))
                assertEquals(null, playbackError.get())
                assertTrue(mediaPlayer.duration >= 200)
            } finally { instrumentation.runOnMainSync { mediaPlayer.release() } }
            val oldToken = runtime.sessionToken("example.native.audio")
            runtime.hotReplace("example.native.audio")
            assertNotEquals(oldToken, runtime.sessionToken("example.native.audio"))
            assertEquals(1, runtime.registry.audioSources().count { it.owner == "example.native.audio" })
            runtime.installOrUpdate(instrumentation.context.assets.open("native-audio.apk"), "native-audio.apk")
            assertEquals("Enabled", runtime.plugins.value.single().state)
            runtime.disable("example.native.audio")
            assertFalse(runtime.registry.audioSources().any { it.owner == "example.native.audio" })
            assertTrue(runtime.registry.searchProviders().isEmpty())
            runtime.uninstall("example.native.audio")
            assertTrue(runtime.plugins.value.isEmpty())
        } finally {
            runtime.close()
            scope.cancel()
            root.deleteRecursively()
        }
    }

    @Test
    fun realApkEntrypointLoadsThroughSharedHostApi() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext
        initializeNativePlatform(application)
        val root = File(application.cacheDir, "native-test-${UUID.randomUUID()}")
        try {
            val store = PackageStore(root, "android")
            val installed = instrumentation.context.assets.open("native-minimal.apk").use { store.install(it, "native-minimal.apk") }
            val handle = loadNative(File(installed, "native/plugin.apk").absolutePath, "dev.metrofuse.examples.MinimalPlugin")
            try {
                assertSame(NativePlugin::class.java, handle.plugin.javaClass.interfaces.single())
                assertNotSame(NativePlugin::class.java.classLoader, handle.plugin.javaClass.classLoader)
                assertTrue(handle.platformResources != null)
            } finally { handle.close() }
        } finally { root.deleteRecursively() }
    }

    private fun writeWav(file: File) {
        val samples = 11025
        val sampleRate = 44100
        val buffer = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVEfmt ".toByteArray())
        buffer.putInt(16).putShort(1).putShort(1).putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
        buffer.put("data".toByteArray()).putInt(samples * 2)
        repeat(samples) { index -> buffer.putShort((4000 * sin(2 * PI * 440 * index / sampleRate)).toInt().toShort()) }
        file.writeBytes(buffer.array())
    }

    private fun testHost(): HostServices = object : HostServices {
        override val playback = object : Player {
            override val state = MutableStateFlow(PlaybackState(Track("fixture.track", "Fixture", "", "")))
            override fun toggle() { state.value = state.value.copy(playing = !state.value.playing) }
            override fun seek(position: Long) { state.value = state.value.copy(position = position) }
            override fun next() = Unit
        }
        override val navigation = object : HostNavigation {
            override val destination = MutableStateFlow("home")
            override fun navigate(destination: String) { this.destination.value = destination }
        }
        override val catalog = object : TrackCatalog {
            override val tracks = emptyList<Track>()
            override suspend fun identify(identity: TrackIdentity): Track? = null
        }
        override val settings = object : HostSettings {
            private val values = mutableMapOf<String, String>()
            override fun get(key: String): String? = values[key]
            override fun set(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
        }
        override val logger = HostLogger { _, _ -> }
        override val audioFallback = object : AudioSource {
            override val id = "builtin.none"
            override val capabilities = AudioCapabilities(emptySet())
            override suspend fun resolve(request: AudioQuery) = AudioResult(error = ProviderError("NO_PROVIDER"))
        }
    }
}
