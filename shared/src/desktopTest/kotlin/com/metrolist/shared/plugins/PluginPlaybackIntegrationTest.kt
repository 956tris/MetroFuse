package com.metrolist.shared.plugins

import androidx.compose.runtime.snapshots.Snapshot

import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.MusicPlayer
import com.metrolist.shared.playback.NoPlayableSourceException
import com.metrolist.shared.playback.TestPlaybackLine
import com.metrolist.shared.playback.ffmpeg.AudioRenderer
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import dev.metrofuse.api.COMPOSE_ABI
import dev.metrofuse.api.Manifest
import dev.metrofuse.api.NativePlugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.tools.ToolProvider
import kotlin.Unit
import kotlin.coroutines.Continuation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PluginPlaybackIntegrationTest {
    private val packageJson = Json { encodeDefaults = true }

    @Test
    fun dynamicNativeAndQuickJsProvidersFeedExistingFfmpegPlayerAndCleanUp() =
        runBlocking {
            val directory = Files.createTempDirectory("metrofuse-plugin-playback-").toFile()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            Settings.replaceSharedForTest(Settings(MapSettings()))
            lateinit var output: TestPlaybackLine
            val player =
                MusicPlayer(
                    playbackCacheCapture = { null },
                    mediaHttpClient = OkHttpClient.Builder().addInterceptor { error("Fixture playback must remain offline") }.build(),
                    rendererFactory = { rate, channels ->
                        output = TestPlaybackLine()
                        AudioRenderer(rate, channels, output.line)
                    },
                )
            try {
                val wave = File(directory, "fixture space %.wav").apply { writeBytes(waveBytes()) }
                MetroFuseHost.configure(File(directory, "host"), "desktop", scope)
                assertNull(withTimeout(5_000) { MetroFuseHost.resolve("startup", null, AudioQuality.AUTO, false, emptySet()) })
                val resolver = PluginStreamResolver()
                assertFailsWith<NoPlayableSourceException> { resolver.resolve("legacyTrack") }
                val artifacts = listOf(nativeArtifact(directory, wave), javascriptArtifact(directory, wave))
                for ((artifact, pluginId) in artifacts) {
                    artifact.inputStream().use { MetroFuseHost.runtime.install(it, artifact.name) }
                    withTimeout(10_000) { MetroFuseHost.runtime.enable(pluginId) }
                    assertEquals(
                        "Enabled",
                        MetroFuseHost.runtime.plugins.value
                            .single { it.manifest.id == pluginId }
                            .state,
                    )
                    withTimeout(5_000) { PluginServicesStore.frontends.first { values -> values.any { it.owner == pluginId } } }
                    Settings.shared.musicFrontendOwner = pluginId
                    awaitSelection("plugin:$pluginId")
                    selectMusicFrontend(pluginId)
                    assertEquals("plugin:$pluginId", Settings.shared.musicFrontendOwner)
                    val song = SongItem("metrofuse:$pluginId:wave", "Local plugin fixture", null, emptyList(), null, duration = 10)
                    for (quality in AudioQuality.entries) {
                        val resource = withTimeout(5_000) { resolver.resolve(song.id, audioQuality = quality, sourceSong = song) }
                        assertEquals(quality.name, resource.headers["X-Fixture-Preset"], "Both runtimes must receive the requested quality")
                        assertEquals("$pluginId.audio", resource.clientName)
                        assertEquals(wave.absolutePath, resource.url)
                    }
                    val resource = resolver.refresh(song.id, audioQuality = AudioQuality.HIGH, sourceSong = song)
                    val prepared = player.playPaused(song, resource)
                    assertTrue(
                        prepared,
                        "Actual FFmpeg preparation failed for $pluginId: ${player.loadFailure.value?.cause?.stackTraceToString()}",
                    )
                    assertTrue(player.playbackReady)
                    assertEquals(song.id, player.currentSong.value?.id)
                    assertEquals(resource, player.currentPlaybackResource.value)
                    assertFalse(player.isPlaying.value)
                    player.resume()
                    withTimeout(5_000) { player.isPlaying.first { it } }
                    output.awaitBuffered()
                    output.advanceUntil(800)
                    withTimeout(5_000) { player.currentPosition.first { it > 0 } }
                    assertTrue(player.currentPlaybackPositionMs() >= 100, "Decoded PCM must advance the existing player")
                    player.pause()
                    withTimeout(5_000) { player.isPlaying.first { !it } }
                    assertFalse(player.wantsPlayback)
                    player.stop()
                    MetroFuseHost.runtime.disable(pluginId)
                    awaitSelection("offline")
                    assertFalse(
                        MetroFuseHost.runtime.registry
                            .audioSources()
                            .any { it.owner == pluginId },
                    )
                    assertEquals(0, MetroFuseHost.runtime.resources().sessions)
                    assertFailsWith<NoPlayableSourceException> { resolver.resolve(song.id, sourceSong = song) }
                    MetroFuseHost.runtime.uninstall(pluginId)
                }
                assertTrue(
                    MetroFuseHost.runtime.plugins.value
                        .isEmpty(),
                )
                assertNull(MetroFuseHost.resolve("after-disable", null, AudioQuality.AUTO, false, emptySet()))
            } finally {
                player.release()
                MetroFuseHost.close()
                scope.cancel()
                Settings.replaceSharedForTest(null)
                directory.deleteRecursively()
            }
        }

    private suspend fun awaitSelection(expected: String) {
        withTimeout(5000) {
            while (Settings.shared.musicFrontendOwner != expected) {
                Snapshot.sendApplyNotifications()
                delay(10)
            }
        }
    }

    private fun nativeArtifact(
        directory: File,
        wave: File,
    ): Pair<File, String> {
        val pluginId = "playback.native"
        val classes = File(directory, "classes").apply { mkdirs() }
        val source =
            File(directory, "PlaybackPlugin.java").apply {
                writeText(
                    """
                    package fixture;
                    import dev.metrofuse.api.*;
                    import java.util.Map;
                    import java.util.Set;
                    import kotlin.Unit;
                    import kotlin.coroutines.Continuation;
                    public final class PlaybackPlugin implements NativePlugin {
                      public Object start(PluginContext context, Continuation<? super Unit> completion) {
                        context.registerSearchProvider(new SearchProvider() {
                          public String getId() { return "$pluginId.search"; }
                          public Object search(SearchRequest request, Continuation<? super SearchResult> completion) {
                            return new SearchResult(java.util.List.of(), java.util.List.of(), java.util.List.of(), null, java.util.List.of());
                          }
                        });
                        context.registerAudioSource(new AudioSource() {
                          public String getId() { return "$pluginId.audio"; }
                          public AudioCapabilities getCapabilities() {
                            return new AudioCapabilities(Set.of(StreamTransport.DIRECT), Set.of("pcm_s16le"), Set.of("audio/wav"), Set.of());
                          }
                          public Object resolve(AudioQuery request, Continuation<? super AudioResult> completion) {
                            if (!"$pluginId".equals(request.getIdentity().getIdentifiers().get("plugin")))
                              return new AudioResult(null, new ProviderError("NOT_FOUND", "", false));
                            return new AudioResult(new StreamDescriptor(StreamTransport.DIRECT, ${Json.encodeToString(
                        wave.toURI().toString(),
                    )},
                              "audio/wav", "pcm_s16le", 128000, null, Map.of(),
                              Map.of("X-Fixture-Preset", request.getQuality().getPreset().name()), 8000, ${wave.length()}L, 10L), null);
                          }
                        });
                        return Unit.INSTANCE;
                      }
                      public Object stop(Continuation<? super Unit> completion) { return Unit.INSTANCE; }
                    }
                    """.trimIndent(),
                )
            }
        val classpath =
            (
                System.getProperty("java.class.path").split(File.pathSeparator) +
                    listOf(NativePlugin::class.java, Unit::class.java, Continuation::class.java).map {
                        File(
                            it.protectionDomain.codeSource.location
                                .toURI(),
                        ).absolutePath
                    }
            ).distinct()
                .joinToString(File.pathSeparator)
        val compiler = requireNotNull(ToolProvider.getSystemJavaCompiler()) { "Dynamic JAR fixture requires a JDK" }
        assertEquals(0, compiler.run(null, null, null, "-classpath", classpath, "-d", classes.absolutePath, source.absolutePath))
        val artifact = File(directory, "fixture.jar")
        val manifest =
            Manifest(
                pluginId,
                "Playback fixture",
                "1.0.0",
                "Tests",
                runtime = "kotlin",
                entryClass = "fixture.PlaybackPlugin",
                composeAbi = COMPOSE_ABI,
                capabilities = listOf("home", "search", "audio"),
            )
        JarOutputStream(artifact.outputStream()).use { jar ->
            jar.putNextEntry(JarEntry("manifest.json"))
            jar.write(packageJson.encodeToString(manifest).toByteArray())
            jar.closeEntry()
            classes.walkTopDown().filter { it.isFile }.forEach { file ->
                jar.putNextEntry(JarEntry(file.relativeTo(classes).invariantSeparatorsPath))
                file.inputStream().use { it.copyTo(jar) }
                jar.closeEntry()
            }
        }
        return artifact to pluginId
    }

    private fun javascriptArtifact(
        directory: File,
        wave: File,
    ): Pair<File, String> {
        val pluginId = "playback.javascript"
        val script =
            """
            globalThis.plugin = {init(host) {
              host.search('$pluginId.search', async request => ({tracks:[]}));
              host.audioSource('$pluginId.audio', {transports:['DIRECT'],codecs:['pcm_s16le'],formats:['audio/wav']}, async request => {
                if(request.identity.identifiers.plugin !== '$pluginId') return {error:{code:'NOT_FOUND'}};
                return {stream:{transport:'DIRECT',locator:${Json.encodeToString(wave.toURI().toString())},
                  mimeType:'audio/wav',codec:'pcm_s16le',sampleRate:8000,contentLength:${wave.length()},durationSeconds:10,
                  headers:{'X-Fixture-Preset':request.quality.preset}}};
              });
            }};
            """.trimIndent()
        val manifest =
            Manifest(
                pluginId,
                "JS playback fixture",
                "1.0.0",
                "Tests",
                runtime = "javascript",
                script = "index.js",
                capabilities = listOf("home", "search", "audio"),
            )
        val artifact = File(directory, "fixture.mfp")
        ZipOutputStream(artifact.outputStream()).use { zip ->
            mapOf("manifest.json" to packageJson.encodeToString(manifest), "index.js" to script).forEach { (name, contents) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(contents.toByteArray())
                zip.closeEntry()
            }
        }
        return artifact to pluginId
    }

    private fun waveBytes(): ByteArray {
        val samples = 8_000 * 10
        val dataSize = samples * 2
        val bytes = ByteArray(44 + dataSize)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(bytes.size - 8).put("WAVEfmt ".toByteArray())
        buffer
            .putInt(16)
            .putShort(1)
            .putShort(1)
            .putInt(8_000)
            .putInt(16_000)
            .putShort(2)
            .putShort(16)
        buffer.put("data".toByteArray()).putInt(dataSize)
        repeat(samples) { index -> buffer.putShort(if (index % 32 < 16) 2_000 else -2_000) }
        return bytes
    }
}
