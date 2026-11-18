package dev.metrofuse.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException

data class PluginFailure(
    val owner: String,
    val version: String,
    val runtime: String,
    val operation: String,
    val error: Throwable,
)

/** Plugin exceptions can echo credentials or request payloads. Keep only their type and frames. */
class PluginOperationException(
    failure: PluginFailure,
) : RuntimeException("Plugin ${failure.operation} failed: ${failure.error.javaClass.name}") {
    init {
        stackTrace = failure.error.stackTrace
    }
}

internal class PluginProviderException : RuntimeException("Plugin provider returned an error")

internal fun isReportablePluginFailure(error: Throwable): Boolean = error !is CancellationException || error is TimeoutCancellationException

internal fun notifyPluginFailure(
    owner: String,
    operation: String,
    error: Throwable,
    send: (String, String, Throwable) -> Unit,
) {
    if (!isReportablePluginFailure(error)) return
    try {
        send(owner, operation, error)
    } catch (_: Exception) {
        // Diagnostics must never change plugin fallback or recovery behavior.
    }
}

internal class PluginFailureReports(
    private val now: () -> Long = System::nanoTime,
) {
    private val lastReports = linkedMapOf<String, Long>()

    @Synchronized
    fun report(
        failure: PluginFailure,
        send: (PluginFailure) -> Unit,
    ) {
        if (!isReportablePluginFailure(failure.error)) return
        val key = "${failure.owner}/${failure.operation}/${failure.error.javaClass.name}"
        val timestamp = now()
        if (lastReports[key]?.let { timestamp - it < 60_000_000_000L } == true) return
        lastReports[key] = timestamp
        while (lastReports.size > 128) lastReports.remove(lastReports.keys.first())
        try {
            send(failure)
        } catch (_: Exception) {
            // Diagnostics must never change plugin fallback or recovery behavior.
        }
    }
}
