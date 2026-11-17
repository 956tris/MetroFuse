package dev.metrofuse.runtime

import java.io.File
import java.nio.file.Files

/** Recovery controls that are safe to use without creating or initializing the plugin runtime. */
class PluginUiRecovery(
    private val storage: File,
) {
    private val originalUi = File(storage, "original-ui.marker")
    private val crashRecovery = File(storage, "ui-recovery.marker")

    val isOriginalUiForced: Boolean
        get() = originalUi.isFile || crashRecovery.isFile || File(storage, "ui-failure.json").isFile

    val isPluginStartupBlocked: Boolean
        get() = crashRecovery.isFile

    fun recoverOriginalUi() {
        // Persist first: recovery must survive a restart even if no runtime ever initialized.
        persist(crashRecovery)
        persist(originalUi)
    }

    fun setOriginalUi(enabled: Boolean) {
        if (enabled) {
            persist(originalUi)
        } else {
            Files.deleteIfExists(originalUi.toPath())
            Files.deleteIfExists(File(storage, "ui-failure.json").toPath())
            Files.deleteIfExists(crashRecovery.toPath())
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
