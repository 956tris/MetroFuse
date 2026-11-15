package dev.metrofuse.sdk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal suspend fun readSdkBrowserOutput(
    executable: File,
    requestFile: File,
    title: String,
    profile: File,
    validateOnly: Boolean = false,
): String =
    withContext(Dispatchers.IO) {
        // Windows argument parsing removes JSON quotes; pass only paths and the window title.
        val command = mutableListOf(executable.absolutePath, requestFile.absolutePath, title, profile.absolutePath)
        if (validateOnly) command += "--validate-request"
        val process = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        try {
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { process.destroyForcibly() }
                try {
                    val bytes = process.inputStream.use { it.readNBytes(131073) }
                    check(bytes.size <= 131072 && process.waitFor() == 0)
                    continuation.resume(bytes.decodeToString())
                } catch (error: Exception) {
                    continuation.resumeWithException(error)
                }
            }
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
