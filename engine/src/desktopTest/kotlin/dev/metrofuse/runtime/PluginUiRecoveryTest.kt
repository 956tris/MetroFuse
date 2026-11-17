package dev.metrofuse.runtime

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginUiRecoveryTest {
    @Test
    fun recoveryCreatesStorageWithoutRuntimeAndExplicitReenableClearsFailureMarkers() {
        val root = Files.createTempDirectory("metrofuse-ui-marker-").toFile()
        try {
            val storage = File(root, "plugins")
            PluginUiRecovery(storage).recoverOriginalUi()
            val fresh = PluginUiRecovery(storage)
            assertTrue(fresh.isOriginalUiForced)
            assertTrue(fresh.isPluginStartupBlocked)
            File(storage, "ui-failure.json").writeText("malformed interrupted failure report")
            fresh.setOriginalUi(false)
            assertFalse(PluginUiRecovery(storage).isOriginalUiForced)
            assertFalse(PluginUiRecovery(storage).isPluginStartupBlocked)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun storageFailureIsReportedBeforeCallerCanRestart() {
        val root = Files.createTempDirectory("metrofuse-ui-write-failure-").toFile()
        try {
            val storage = File(root, "plugins").apply { writeText("not a directory") }
            var restarted = false
            assertFailsWith<IllegalArgumentException> {
                PluginUiRecovery(storage).recoverOriginalUi()
                restarted = true
            }
            assertFalse(restarted)
        } finally {
            root.deleteRecursively()
        }
    }
}
