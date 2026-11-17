package dev.metrofuse.runtime

import dev.metrofuse.api.API_VERSION
import dev.metrofuse.api.COMPOSE_ABI
import dev.metrofuse.api.HostServices
import dev.metrofuse.api.Manifest
import dev.metrofuse.api.NativePlugin
import dev.metrofuse.api.PlaybackState
import dev.metrofuse.api.Player
import dev.metrofuse.api.Track
import dev.metrofuse.api.UpcomingQueuePlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.coroutines.Continuation
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NativeUpcomingQueueTest {
    @Test
    fun nativeSessionRetainsOptionalQueueDispatchAndRejectsCallsAfterDisable() =
        runBlocking<Unit> {
            withNativePlayer(true) { runtime, delegate, session ->
                val before = delegate.state.value
                val duplicate = Track("spotify:track:duplicate", "Duplicate", "Fixture", "Fixture", 180000)
                val upcoming = listOf(duplicate, duplicate, before.track)
                val optional = assertIs<UpcomingQueuePlayer>(session)
                assertSame(delegate.state, optional.state)
                optional.replaceUpcomingQueue(upcoming)
                assertEquals(1, delegate.replacements)
                val after = delegate.state.value
                assertEquals(before.track, after.track)
                assertEquals(before.position, after.position)
                assertEquals(before.playing, after.playing)
                assertEquals(before.playbackInstanceId, after.playbackInstanceId)
                assertEquals(before.currentIndex, after.currentIndex)
                assertEquals(before.queue.take(2) + upcoming, after.queue)
                runtime.disable("fixture.upcoming")
                assertFailsWith<IllegalStateException> { optional.replaceUpcomingQueue(emptyList()) }
                assertEquals(1, delegate.replacements)
            }
        }

    @Test
    fun nativeSessionEnforcesAdvertisedQueueCapability() =
        runBlocking<Unit> {
            withNativePlayer(false) { _, delegate, session ->
                val optional = assertIs<UpcomingQueuePlayer>(session)
                assertFailsWith<IllegalArgumentException> { optional.replaceUpcomingQueue(emptyList()) }
                assertEquals(0, delegate.replacements)
            }
        }

    private suspend fun withNativePlayer(
        supported: Boolean,
        block: suspend (Runtime, QueuePlayer, Player) -> Unit,
    ) {
        val root = Files.createTempDirectory("metrofuse-native-upcoming-").toFile()
        val key = "metrofuse.test.player.${root.name}"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val delegate = QueuePlayer(supported)
        val host =
            object : HostServices by EngineTestHost() {
                override val playback: Player = delegate
            }
        val runtime = Runtime(File(root, "runtime"), "desktop", scope, host)
        try {
            runtime.initialize()
            nativeFixture(root, key).inputStream().use { runtime.install(it, "fixture.jar") }
            runtime.enable("fixture.upcoming")
            assertEquals(
                "Enabled",
                runtime.plugins.value
                    .single()
                    .state,
                runtime.logs.value.joinToString(),
            )
            val session = assertIs<Player>(System.getProperties().remove(key))
            block(runtime, delegate, session)
        } finally {
            System.getProperties().remove(key)
            runtime.close()
            scope.cancel()
            root.deleteRecursively()
        }
    }

    private class QueuePlayer(
        supported: Boolean,
    ) : UpcomingQueuePlayer {
        private val current = Track("current", "Current", "Fixture", "Fixture", 180000)
        override val state =
            MutableStateFlow(
                PlaybackState(current, true, 42000, listOf(Track("past", "Past", "Fixture", "Fixture"), current)).apply {
                    currentIndex = 1
                    playbackInstanceId = "current-occurrence"
                },
            )
        override val capabilities = if (supported) setOf("replaceUpcomingQueue") else emptySet()
        var replacements = 0

        override fun toggle() = error("Queue replacement must not toggle playback")

        override fun seek(position: Long) = error("Queue replacement must not seek")

        override fun next() = error("Queue replacement must not advance playback")

        override fun replaceUpcomingQueue(tracks: List<Track>) {
            replacements++
            val before = state.value
            state.value =
                before.copy(queue = before.queue.take(before.currentIndex!! + 1) + tracks).apply {
                    currentIndex = before.currentIndex
                    playbackInstanceId = before.playbackInstanceId
                }
        }
    }

    private fun nativeFixture(
        root: File,
        propertyKey: String,
    ): File {
        val classes = File(root, "classes").apply { mkdirs() }
        val source = File(root, "QueueFixture.java")
        source.writeText(
            """
            package fixture;
            import dev.metrofuse.api.*;
            import kotlin.Unit;
            import kotlin.coroutines.Continuation;
            public final class QueueFixture implements NativePlugin {
              public Object start(PluginContext context, Continuation<? super Unit> completion) {
                System.getProperties().put("$propertyKey", context.getPlayer());
                return Unit.INSTANCE;
              }
              public Object stop(Continuation<? super Unit> completion) { return Unit.INSTANCE; }
            }
            """.trimIndent(),
        )
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
        val compiler = requireNotNull(ToolProvider.getSystemJavaCompiler())
        assertEquals(0, compiler.run(null, null, null, "-classpath", classpath, "-d", classes.absolutePath, source.absolutePath))
        val artifact = File(root, "fixture.jar")
        val manifest =
            Manifest(
                "fixture.upcoming",
                "Native queue fixture",
                "1.0.0",
                "Tests",
                API_VERSION,
                "kotlin",
                entryClass = "fixture.QueueFixture",
                composeAbi = COMPOSE_ABI,
            )
        JarOutputStream(artifact.outputStream()).use { jar ->
            jar.putNextEntry(JarEntry("manifest.json"))
            jar.write(codec.encodeToString(manifest).toByteArray())
            jar.closeEntry()
            classes.walkTopDown().filter { it.isFile }.forEach { file ->
                jar.putNextEntry(JarEntry(file.relativeTo(classes).invariantSeparatorsPath))
                file.inputStream().use { it.copyTo(jar) }
                jar.closeEntry()
            }
        }
        assertTrue(artifact.isFile)
        return artifact
    }
}
