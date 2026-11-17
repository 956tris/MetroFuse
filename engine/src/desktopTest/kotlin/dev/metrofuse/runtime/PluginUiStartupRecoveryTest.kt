package dev.metrofuse.runtime

import dev.metrofuse.api.Manifest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginUiStartupRecoveryTest {
    @Test
    fun interruptedActivationStaysPausedAfterItsStartupMarkerIsConsumed() =
        runBlocking {
            val root = Files.createTempDirectory("metrofuse-interrupted-activation-").toFile()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val plugin = Manifest("test.startup.ui", "UI fixture", "1.0.0", "Tests", runtime = "javascript", script = "index.js")
            try {
                val first = Runtime(root, "android", scope, EngineTestHost())
                try {
                    first.initialize()
                    first.install(
                        ByteArrayInputStream(
                            packageBytes(plugin, "globalThis.plugin={init(host){host.ui('ui.app',{id:'shell',type:'Text'});}};"),
                        ),
                    )
                    first.enable(plugin.id)
                } finally {
                    first.close()
                }
                File(root, "running.marker").writeText("Interrupted native plugin activation")
                repeat(3) {
                    val recovered = Runtime(root, "android", scope, EngineTestHost())
                    try {
                        recovered.initialize()
                        assertTrue(recovered.uiSafeMode.value)
                        assertEquals(
                            "Installed",
                            recovered.plugins.value
                                .single()
                                .state,
                        )
                        assertTrue(recovered.registry.layers("ui.app").isEmpty())
                        assertTrue(File(root, "configuration.json").readText().contains(plugin.id))
                        assertFalse(File(root, "running.marker").exists())
                    } finally {
                        recovered.close()
                    }
                }
            } finally {
                scope.cancel()
                root.deleteRecursively()
            }
        }

    @Test
    fun crashInHostLayoutFramesStillRecoversMountedCustomUi() =
        runBlocking {
            val root = Files.createTempDirectory("metrofuse-ui-layout-crash-").toFile()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val runtime = Runtime(root, "desktop", scope, EngineTestHost())
            try {
                runtime.initialize()
                val release = runtime.watchUi("test.custom.ui")
                try {
                    runtime.reportUiCrash(IllegalStateException("host layout failed"))
                    assertTrue(PluginUiRecovery(root).isOriginalUiForced)
                    assertTrue(PluginUiRecovery(root).isPluginStartupBlocked)
                } finally {
                    release()
                }
            } finally {
                runtime.close()
                scope.cancel()
                root.deleteRecursively()
            }
        }

    @Test
    fun interruptedFirstRenderSkipsPluginActivationAcrossRestarts() =
        runBlocking {
            val root = Files.createTempDirectory("metrofuse-first-render-recovery-").toFile()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val plugin = Manifest("test.interrupted.ui", "UI fixture", "1.0.0", "Tests", runtime = "javascript", script = "index.js")
            try {
                val first = Runtime(root, "android", scope, EngineTestHost())
                try {
                    first.initialize()
                    first.install(
                        ByteArrayInputStream(
                            packageBytes(plugin, "globalThis.plugin={init(host){host.ui('ui.app',{id:'shell',type:'Text'});}};"),
                        ),
                    )
                    first.enable(plugin.id)
                    first.beginUiRender()
                } finally {
                    first.close()
                }
                repeat(2) {
                    val recovered = Runtime(root, "android", scope, EngineTestHost())
                    try {
                        assertTrue(recovered.uiSafeMode.value)
                        recovered.initialize()
                        assertEquals(
                            "Installed",
                            recovered.plugins.value
                                .single()
                                .state,
                        )
                        assertTrue(recovered.registry.layers("ui.app").isEmpty())
                    } finally {
                        recovered.close()
                    }
                }
            } finally {
                scope.cancel()
                root.deleteRecursively()
            }
            Unit
        }

    private fun packageBytes(
        manifest: Manifest,
        script: String,
    ): ByteArray =
        ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                mapOf("manifest.json" to codec.encodeToString(manifest), "index.js" to script).forEach { (name, contents) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(contents.toByteArray())
                    zip.closeEntry()
                }
            }
            output.toByteArray()
        }
}
