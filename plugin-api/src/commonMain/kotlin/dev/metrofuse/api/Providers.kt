package dev.metrofuse.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable enum class ResolutionMode { REPLACE, FIRST_SUCCESS, CHAIN, MERGE, OBSERVE, DECORATE }
@Serializable data class ServiceContract(val name: String, val modes: Set<ResolutionMode> = setOf(ResolutionMode.REPLACE), val defaultMode: ResolutionMode = ResolutionMode.REPLACE)
@Serializable enum class HookPhase { BEFORE, AFTER }
fun interface Subscription { fun cancel() }
@Serializable data class FieldProvenance(val provider: String, val confidence: Double, val observedAtMillis: Long, val match: String)
@Serializable data class MetadataField(val value: JsonElement, val provenance: FieldProvenance)
@Serializable data class MetadataDocument(val identity: TrackIdentity, val fields: Map<String, MetadataField> = emptyMap())
@Serializable data class MetadataRequest(val identity: TrackIdentity, val base: MetadataDocument, val configuration: JsonObject = JsonObject(emptyMap()))
@Serializable data class MetadataContribution(val identity: TrackIdentity, val fields: Map<String, JsonElement>, val confidence: Double = .8)
interface MetadataSource {
    val id: String
    suspend fun resolve(request: MetadataRequest): MetadataContribution?
}
@Serializable data class MetadataPolicy(
    val mode: ResolutionMode = ResolutionMode.MERGE,
    val fieldProviders: Map<String, String> = emptyMap(),
    val timeoutMillis: Long = 1000,
    val cacheTtlMillis: Long = 60_000,
    val configurations: Map<String, JsonObject> = emptyMap(),
)
@Serializable enum class StreamTransport { DIRECT, RANGE_HTTP, HLS, SABR_UMP, CUSTOM }
@Serializable data class AudioCapabilities(
    val transports: Set<StreamTransport>, val codecs: Set<String> = emptySet(),
    val formats: Set<String> = emptySet(), val requirements: Set<String> = emptySet(),
)
/** Selection intent; providers choose from available formats without invented bitrate limits. */
@Serializable enum class QualityPreset { AUTO, LOW, HIGH, MP4 }
@Serializable data class QualityPreference(
    val maxBitrate: Int? = null,
    val lossless: Boolean = false,
    val preferredCodecs: List<String> = emptyList(),
    val preset: QualityPreset = QualityPreset.AUTO,
)
@Serializable data class AudioQuery(val identity: TrackIdentity, val platform: String, val quality: QualityPreference = QualityPreference(), val sourceId: String? = null, val configuration: JsonObject = JsonObject(emptyMap()))
/** Locator may be a private opaque provider handle. Never send this object on general event buses. */
@Serializable data class StreamDescriptor(
    val transport: StreamTransport, val locator: String, val mimeType: String? = null,
    val codec: String? = null, val bitrate: Int? = null, val expiresAtMillis: Long? = null,
    val attributes: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val sampleRate: Int? = null,
    val contentLength: Long? = null,
    val durationSeconds: Long? = null,
) { override fun toString() = "StreamDescriptor(transport=$transport, locator=<redacted>, mimeType=$mimeType, expiresAtMillis=$expiresAtMillis)" }
@Serializable data class ProviderError(val code: String, val message: String = "", val retryable: Boolean = false)
@Serializable data class AudioResult(val stream: StreamDescriptor? = null, val error: ProviderError? = null)
interface AudioSource {
    val id: String
    val capabilities: AudioCapabilities
    suspend fun resolve(request: AudioQuery): AudioResult
}
@Serializable data class AudioPolicy(
    val timeoutMillis: Long = 1000, val retries: Int = 0,
    val manualSourceFallback: Boolean = false, val cacheTtlMillis: Long = 30_000,
    val configurations: Map<String, JsonObject> = emptyMap(),
    val preferredSource: String? = null,
)
@Serializable data class ProviderDiagnostic(val point: String, val provider: String, val outcome: String, val elapsedMillis: Double, val attempt: Int = 0)
