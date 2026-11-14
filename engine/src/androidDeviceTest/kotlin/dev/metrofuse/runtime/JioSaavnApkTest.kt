package dev.metrofuse.runtime

import android.media.MediaPlayer
import androidx.test.platform.app.InstrumentationRegistry
import dev.metrofuse.api.AudioQuery
import dev.metrofuse.api.HostLogger
import dev.metrofuse.api.HostNavigation
import dev.metrofuse.api.HostServices
import dev.metrofuse.api.HostSettings
import dev.metrofuse.api.PlaybackState
import dev.metrofuse.api.Player
import dev.metrofuse.api.SearchRequest
import dev.metrofuse.api.Track
import dev.metrofuse.api.TrackCatalog
import dev.metrofuse.api.TrackIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class JioSaavnApkTest {
    @Test
    fun packagedDexRegistersProvidersAndPersistsSettingsAcrossReloadAndRestart() =
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val application = instrumentation.targetContext
            initializeNativePlatform(application)
            val data = File(application.cacheDir, "jiosaavn-apk-${UUID.randomUUID()}")
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            var runtime = Runtime(data, "android", scope, ApkTestHost())
            try {
                runtime.initialize()
                instrumentation.context.assets
                    .open("jiosaavn.apk")
                    .use { runtime.install(it, "jiosaavn.apk") }
                runtime.enable("metrofuse.jiosaavn")
                assertEquals(
                    runtime.logs.value.joinToString(),
                    "Enabled",
                    runtime.plugins.value
                        .single()
                        .state,
                )
                assertEquals(
                    "kotlin",
                    runtime.plugins.value
                        .single()
                        .manifest.runtime,
                )
                assertEquals(
                    "jiosaavn.search",
                    runtime.registry
                        .searchProviders()
                        .single()
                        .id,
                )
                val result =
                    runtime.registry
                        .audioSources()
                        .single()
                        .provider
                        .resolve(AudioQuery(TrackIdentity("other:track"), "android"))
                assertEquals("IDENTITY_REQUIRED", result.error?.code)
                runtime.settingsFor("metrofuse.jiosaavn").set("previewEnabled", JsonPrimitive(false))
                runtime.settingsFor("metrofuse.jiosaavn").set("regionalCatalog", JsonPrimitive(false))
                runtime.hotReplace("metrofuse.jiosaavn")
                assertEquals(
                    "Enabled",
                    runtime.plugins.value
                        .single()
                        .state,
                )
                instrumentation.context.assets
                    .open("jiosaavn.apk")
                    .use { runtime.installOrUpdate(it, "jiosaavn.apk") }
                assertEquals(
                    "Enabled",
                    runtime.plugins.value
                        .single()
                        .state,
                )
                runtime.close()
                runtime = Runtime(data, "android", scope, ApkTestHost())
                runtime.initialize()
                assertEquals(
                    "Enabled",
                    runtime.plugins.value
                        .single()
                        .state,
                )
                assertEquals(JsonPrimitive(false), runtime.settingsFor("metrofuse.jiosaavn").values.value["previewEnabled"])
                assertEquals(JsonPrimitive(false), runtime.settingsFor("metrofuse.jiosaavn").values.value["regionalCatalog"])
                runtime.disable("metrofuse.jiosaavn")
                assertTrue(runtime.registry.audioSources().isEmpty())
                assertTrue(runtime.registry.searchProviders().isEmpty())
            } finally {
                runtime.close()
                scope.cancel()
                data.deleteRecursively()
            }
        }

    @Test
    fun westernOriginalsResolveAndDecodeThroughAndroidMediaPlayerLive() =
        runBlocking {
            assumeTrue(
                "Pass instrumentation argument jiosaavnLive=true for live service checks",
                InstrumentationRegistry.getArguments().getString("jiosaavnLive") == "true",
            )
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val application = instrumentation.targetContext
            initializeNativePlatform(application)
            val data = File(application.cacheDir, "jiosaavn-apk-live-${UUID.randomUUID()}")
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val runtime = Runtime(data, "android", scope, ApkTestHost())
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
            try {
                runtime.initialize()
                instrumentation.context.assets
                    .open("jiosaavn.apk")
                    .use { runtime.install(it, "jiosaavn.apk") }
                runtime.enable("metrofuse.jiosaavn")
                assertEquals(
                    runtime.logs.value.joinToString(),
                    "Enabled",
                    runtime.plugins.value
                        .single()
                        .state,
                )
                runtime.settingsFor("metrofuse.jiosaavn").set("previewEnabled", JsonPrimitive(false))
                for ((title, artist) in songs) {
                    val results = runtime.pipelines.search(SearchRequest("$title $artist", 15))
                    val track =
                        requireNotNull(
                            results.tracks.firstOrNull {
                                it.title.equals(title, true) && it.artist.contains(artist, true)
                            },
                        ) { "Original $title by $artist was not found" }
                    val identity =
                        TrackIdentity(track.extras.getValue("providerTrackId"), mapOf("jiosaavn" to track.extras.getValue("jiosaavn")))
                    val audio = runtime.pipelines.audio(AudioQuery(identity, "android"), includeBuiltin = false)
                    val stream = requireNotNull(audio.stream) { "Audio failed for $title: ${audio.error}" }
                    assertEquals("full song", stream.attributes["content"])
                    assertEquals(320000, stream.bitrate)
                    assertTrue(stream.headers.isEmpty())
                    val prepared = CountDownLatch(1)
                    val playbackError = AtomicReference<String?>()
                    lateinit var player: MediaPlayer
                    instrumentation.runOnMainSync {
                        player = MediaPlayer()
                        player.setVolume(0f, 0f)
                        player.setOnPreparedListener {
                            it.start()
                            prepared.countDown()
                        }
                        player.setOnErrorListener { _, what, extra ->
                            playbackError.set("$what:$extra")
                            prepared.countDown()
                            true
                        }
                        player.setDataSource(stream.locator)
                        player.prepareAsync()
                    }
                    try {
                        assertTrue("Android prepare timed out for $title", prepared.await(20, TimeUnit.SECONDS))
                        assertEquals(null, playbackError.get())
                        delay(2000)
                        var position = 0
                        var duration = 0
                        instrumentation.runOnMainSync {
                            position = player.currentPosition
                            duration = player.duration
                        }
                        assertEquals(null, playbackError.get())
                        assertTrue("Android audio did not advance for $title", position > 500)
                        assertTrue("Android decoder returned an excerpt for $title", duration > 150000)
                        reports +=
                            buildJsonObject {
                                put("title", track.title)
                                put("artist", track.artist)
                                put("bitrate", stream.bitrate)
                                put("durationMs", duration)
                                put("decodedPositionMs", position)
                            }
                        File(application.filesDir, "jiosaavn-android-live-verification.json").writeText(JsonArray(reports).toString())
                    } finally {
                        instrumentation.runOnMainSync { player.release() }
                    }
                }
            } finally {
                runtime.close()
                scope.cancel()
                data.deleteRecursively()
            }
        }
}

private class ApkTestHost : HostServices {
    override val playback =
        object : Player {
            override val state = MutableStateFlow(PlaybackState(Track("fixture", "", "", "")))

            override fun toggle() = Unit

            override fun seek(position: Long) = Unit

            override fun next() = Unit
        }
    override val navigation =
        object : HostNavigation {
            override val destination = MutableStateFlow("home")

            override fun navigate(destination: String) {
                this.destination.value = destination
            }
        }
    override val catalog =
        object : TrackCatalog {
            override val tracks = emptyList<Track>()

            override suspend fun identify(identity: TrackIdentity): Track? = null
        }
    override val settings =
        object : HostSettings {
            override fun get(key: String): String? = null

            override fun set(
                key: String,
                value: String?,
            ) = Unit
        }
    override val logger = HostLogger { _, _ -> }
}
