package dev.metrofuse.runtime

import java.io.File
import java.nio.file.Files

/** Recovery controls that are safe to use without creating or initializing the plugin runtime. */
class PluginUiRecovery(
    private val storage: File,
) {
    private val originalUi = File(storage, "original-ui.marker")
    private val crashRecovery = File(storage, "ui-recovery.marker")
    private val rendering = File(storage, "ui-rendering.marker")
    private val pendingRenders = mutableSetOf<Any>()

    val isOriginalUiForced: Boolean
        get() = originalUi.isFile || isPluginStartupBlocked

    val isPluginStartupBlocked: Boolean
        get() = crashRecovery.isFile || rendering.isFile || File(storage, "ui-failure.json").isFile

    @Synchronized
    fun beginRender(): () -> Unit {
        // Arm before entering plugin code: the first composition can freeze before effects or the watchdog start.
        if (pendingRenders.isEmpty() && !rendering.isFile) {
            persist(rendering)
        }
        val token = Any()
        pendingRenders += token
        return {
            synchronized(this) {
                if (pendingRenders.remove(token) && pendingRenders.isEmpty()) Files.deleteIfExists(rendering.toPath())
            }
        }
    }

    fun recoverOriginalUi() {
        // Persist first: recovery must survive a restart even if no runtime ever initialized.
        persist(crashRecovery)
        persist(originalUi)
    }

    @Synchronized
    fun setOriginalUi(enabled: Boolean) {
        if (enabled) {
            persist(originalUi)
        } else {
            Files.deleteIfExists(originalUi.toPath())
            Files.deleteIfExists(File(storage, "ui-failure.json").toPath())
            Files.deleteIfExists(crashRecovery.toPath())
            Files.deleteIfExists(rendering.toPath())
            pendingRenders.clear()
        }
    }

    private fun persist(marker: File) {
        require(storage.mkdirs() || storage.isDirectory)
        marker.outputStream().use {
            it.write(byteArrayOf(1))
            it.fd.sync()
        }
    }
}
