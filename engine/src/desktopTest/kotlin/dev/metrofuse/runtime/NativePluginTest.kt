package dev.metrofuse.runtime

import dev.metrofuse.api.API_VERSION
import dev.metrofuse.api.AudioCapabilities
import dev.metrofuse.api.AudioQuery
import dev.metrofuse.api.AudioResult
import dev.metrofuse.api.AudioSource
import dev.metrofuse.api.COMPOSE_ABI
import dev.metrofuse.api.HostLogger
import dev.metrofuse.api.HostNavigation
import dev.metrofuse.api.HostServices
import dev.metrofuse.api.HostSettings
import dev.metrofuse.api.Manifest
import dev.metrofuse.api.NativePlugin
import dev.metrofuse.api.PlaybackState
import dev.metrofuse.api.Player
import dev.metrofuse.api.ProviderError
import dev.metrofuse.api.Track
import dev.metrofuse.api.TrackCatalog
import dev.metrofuse.api.TrackIdentity
import fixture.IsolatedDependency
import java.io.File
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.coroutines.Continuation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking

class NativePluginTest {
    @Test
    fun jarLoadsExternalEntrypointWithSharedApiAndOriginalCanBeReplaced() {
        val root = Files.createTempDirectory("metrofuse-native-test").toFile()
        try {
            val jar = fixture(root)
            val store = PackageStore(File(root, "installed"), "desktop")
            val installed = jar.inputStream().use { store.install(it, "fixture.jar") }
            val payload = File(installed, "native/plugin.jar")
            val handle = loadNative(payload.absolutePath, "fixture.RealNativePlugin")
            try {
                assertNotSame(NativePlugin::class.java.classLoader, handle.plugin.javaClass.classLoader)
                assertEquals(NativePlugin::class.java, handle.plugin.javaClass.interfaces.single())
                assertEquals("host", IsolatedDependency.value())
                assertEquals("plugin", handle.plugin.javaClass.getMethod("dependency").invoke(handle.plugin))
                store.verify(installed)
                val moved = File(payload.parentFile, "renamed.jar")
                assertTrue(payload.renameTo(moved), "Original artifact must not be locked by activation")
                assertTrue(moved.renameTo(payload))
            } finally { handle.close() }
        } finally { root.deleteRecursively() }
    }

    @Test
    fun nativeLifecycleRegistersRemovesReloadsUpdatesAndPersistsActivation() = runBlocking {
        val root = Files.createTempDirectory("metrofuse-lifecycle-test").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        lateinit var runtime: Runtime
        var runtimeCreated = false
        try {
            val artifact = fixture(root)
            runtime = Runtime(File(root, "host"), "desktop", scope, testHost())
            runtimeCreated = true
            runtime.initialize()
            assertTrue(runtime.plugins.value.isEmpty())
            runtime.install(artifact.inputStream(), "fixture.jar")
            repeat(3) {
                runtime.enable("fixture.native")
                assertEquals("Enabled", runtime.plugins.value.single().state)
                assertEquals(1, runtime.registry.metadataProviders().count { it.owner == "fixture.native" })
                runtime.disable("fixture.native")
                assertFalse(runtime.registry.metadataProviders().any { it.owner == "fixture.native" })
            }
            runtime.enable("fixture.native")
            val update = fixture(root, "1.1.0")
            runtime.installOrUpdate(update.inputStream(), "fixture.jar")
            assertEquals("1.1.0", runtime.plugins.value.single().manifest.version)
            assertEquals("Enabled", runtime.plugins.value.single().state)
            val broken = fixture(root, "1.2.0", failStart = true)
            var rejected = false
            try { runtime.installOrUpdate(broken.inputStream(), "fixture.jar") }
            catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected)
            assertEquals("1.1.0", runtime.plugins.value.single().manifest.version)
            assertEquals("Enabled", runtime.plugins.value.single().state)
            runtime.close()
            runtime = Runtime(File(root, "host"), "desktop", scope, testHost())
            runtime.initialize()
            assertFalse(runtime.recovery.value)
            assertEquals("Enabled", runtime.plugins.value.single().state)
            runtime.uninstall("fixture.native")
            assertTrue(runtime.plugins.value.isEmpty())
        } finally {
            if (runtimeCreated) runtime.close()
            scope.cancel()
            root.deleteRecursively()
        }
    }

    @Test
    fun invalidPackagesAndDependencyGraphsAreRejected() {
        val base = Manifest("fixture.native", "Fixture", "1.0.0", "MetroFuse", API_VERSION, "kotlin", entryClass = "fixture.RealNativePlugin", composeAbi = COMPOSE_ABI)
        assertFailsWith<IllegalArgumentException> { PackageStore.validateManifest(base.copy(api = "999.0.0"), "desktop") }
        assertFailsWith<IllegalArgumentException> { PackageStore.validateManifest(base.copy(runtime = "hybrid"), "desktop") }
        assertFailsWith<IllegalArgumentException> { PackageStore.validateManifest(base.copy(script = "index.js"), "desktop") }
        assertFailsWith<IllegalArgumentException> { PluginOrdering.validateDependencies(mapOf(base.id to base.copy(dependencies = mapOf("fixture.missing" to "1.0.0")))) }
        val dependency = base.copy(id = "fixture.dependency", version = "1.1.0")
        PluginOrdering.validateDependencies(mapOf(base.id to base.copy(dependencies = mapOf(dependency.id to "1.0.0")), dependency.id to dependency))
        assertFailsWith<IllegalArgumentException> { PluginOrdering.validateDependencies(mapOf(base.id to base.copy(dependencies = mapOf(dependency.id to "2.0.0")), dependency.id to dependency)) }
        assertFailsWith<IllegalArgumentException> { PluginOrdering.validateDependencies(mapOf(base.id to base.copy(dependencies = mapOf(dependency.id to "1.0.0")), dependency.id to dependency.copy(dependencies = mapOf(base.id to "1.0.0")))) }
    }

    @Test
    fun interruptedInstallationIsRemovedWithoutRemovingInstalledDirectories() {
        val root = Files.createTempDirectory("metrofuse-interrupted-test").toFile()
        try {
            val staging = File(root, ".staging-12345678-1234-1234-1234-123456789abc").apply { mkdir() }
            File(staging, "partial.jar").writeText("Interrupted write")
            val installed = File(root, "fixture.installed").apply { mkdir() }
            PackageStore(root, "desktop").recoverInterruptedInstallations()
            assertFalse(staging.exists())
            assertTrue(installed.isDirectory)
        } finally { root.deleteRecursively() }
    }

    private fun fixture(root: File, version: String = "1.0.0", failStart: Boolean = false): File {
        val classes = File(root, "classes").apply { mkdirs() }
        val source = File(root, "RealNativePlugin.java")
        source.writeText("""
            package fixture;
            import dev.metrofuse.api.*;
            import kotlin.Unit;
            import kotlin.coroutines.Continuation;
            public final class RealNativePlugin implements NativePlugin {
              public String dependency() { return IsolatedDependency.value(); }
              public Object start(PluginContext context, Continuation<? super Unit> completion) {
                if ($failStart) throw new IllegalStateException("Deliberate native initialization failure");
                context.registerMetadata(new MetadataProvider() {
                  public Object resolve(Track track, Continuation<? super Track> completion) { return track; }
                });
                return Unit.INSTANCE;
              }
              public Object stop(Continuation<? super Unit> completion) { return Unit.INSTANCE; }
            }
        """.trimIndent())
        val dependency = File(root, "IsolatedDependency.java")
        dependency.writeText("package fixture; public final class IsolatedDependency { public static String value() { return \"plugin\"; } }")
        val classpath = (System.getProperty("java.class.path").split(File.pathSeparator) + listOf(NativePlugin::class.java, Unit::class.java, Continuation::class.java).map { File(it.protectionDomain.codeSource.location.toURI()).absolutePath }).distinct().joinToString(File.pathSeparator)
        val compiler = requireNotNull(ToolProvider.getSystemJavaCompiler()) { "Native tests require a JDK" }
        assertEquals(0, compiler.run(null, null, null, "-classpath", classpath, "-d", classes.absolutePath, source.absolutePath, dependency.absolutePath))
        val artifact = File(root, "fixture-$version.jar")
        val manifest = Manifest("fixture.native", "Native fixture", version, "MetroFuse", API_VERSION, "kotlin", entryClass = "fixture.RealNativePlugin", composeAbi = COMPOSE_ABI)
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
        return artifact
    }

    private fun testHost(): HostServices = object : HostServices {
        override val playback = object : Player {
            override val state = MutableStateFlow(PlaybackState(Track("fixture.track", "Fixture", "MetroFuse", "Tests")))
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
