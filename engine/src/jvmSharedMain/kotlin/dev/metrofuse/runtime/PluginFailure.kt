package dev.metrofuse.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException

data class PluginFailure(
    val owner: String,
    val version: String,
    val runtime: String,
    val operation: String,
    val error: Throwable,
) {
    val audioDiagnostic: PluginAudioDiagnostic? get() = (error as? PluginProviderException)?.diagnostic
}

@ConsistentCopyVisibility
data class PluginAudioDiagnostic internal constructor(
    val code: String,
    val retryable: Boolean,
    val attempt: Int,
    val durationMillis: Long,
)

/** Plugin exceptions can echo credentials or request payloads. Keep only their type and frames. */
class PluginOperationException(
    failure: PluginFailure,
) : RuntimeException(
        "Plugin ${failure.operation} failed: ${failure.error.javaClass.name}" +
            failure.audioDiagnostic?.let { " (${it.code})" }.orEmpty(),
    ) {
    init {
        stackTrace = failure.error.stackTrace
    }
}

internal class PluginProviderException(
    code: String,
    retryable: Boolean,
    attempt: Int,
    durationMillis: Long,
) : RuntimeException("Plugin provider returned an error") {
    val diagnostic =
        PluginAudioDiagnostic(
            audioDiagnosticCode(code),
            retryable,
            attempt,
            durationMillis,
        )
}

internal fun audioDiagnosticCode(code: String) = code.takeIf(knownAudioCodes::contains) ?: "PROVIDER_ERROR"

// Provider error fields are untrusted. Only fixed protocol values may leave the process.
private val knownAudioCodes =
    setOf(
        "ACCESS_NOT_VERIFIED",
        "AUDIO_DISABLED",
        "BITRATE_UNAVAILABLE",
        "DEEZER_STREAM_FAILED",
        "DEVICE_KEY_UNAVAILABLE",
        "DEVICE_VERIFICATION_UNAVAILABLE",
        "IDENTITY_REQUIRED",
        "INVALID_DESCRIPTOR",
        "INVALID_FLAC_RESPONSE",
        "LOGIN_REQUIRED",
        "LOSSLESS_UNAVAILABLE",
        "NO_FLAC_MATCH",
        "NO_MATCH",
        "QUALITY_UNAVAILABLE",
        "RATE_LIMITED",
        "RELAY_UNAVAILABLE",
        "SABR_MEDIA_IDENTITY_MISMATCH",
        "STREAM_UNAVAILABLE",
        "TIMEOUT",
        "TRACK_METADATA_REQUIRED",
        "UPSTREAM_UNAVAILABLE",
        "WRONG_PROVIDER",
    )

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
        val key = "${failure.owner}/${failure.operation}/${failure.error.javaClass.name}/${failure.audioDiagnostic?.code.orEmpty()}"
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
