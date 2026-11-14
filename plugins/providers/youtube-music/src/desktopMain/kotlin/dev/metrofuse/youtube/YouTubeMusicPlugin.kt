package dev.metrofuse.youtube

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.bodyAsJsonObjectLimited
import com.metrolist.innertubex.cipher.PlayerConfigRepository
import com.metrolist.innertubex.cipher.RemotePlayerConfigStore
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.InnerTubeExtractor
import com.metrolist.innertubex.extraction.StreamExtractor
import com.metrolist.innertubex.extraction.StreamResolveException
import com.metrolist.innertubex.extraction.YtConfigParserImpl
import com.metrolist.innertubex.extraction.strategy.ContentAwareFallbackStrategy
import com.metrolist.innertubex.models.YouTubeLocale
import dev.metrofuse.api.AudioCapabilities
import dev.metrofuse.api.AudioQuery
import dev.metrofuse.api.AudioResult
import dev.metrofuse.api.AudioSource
import dev.metrofuse.api.CatalogProvider
import dev.metrofuse.api.MetadataContribution
import dev.metrofuse.api.MetadataRequest
import dev.metrofuse.api.MetadataSource
import dev.metrofuse.api.NativePlugin
import dev.metrofuse.api.PluginContext
import dev.metrofuse.api.PluginSettings
import dev.metrofuse.api.ProviderError
import dev.metrofuse.api.SearchProvider
import dev.metrofuse.api.SearchRequest
import dev.metrofuse.api.SearchResult
import dev.metrofuse.api.StreamDescriptor
import dev.metrofuse.api.StreamTransport
import dev.metrofuse.api.TrackIdentity
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.Locale

// Optional guest provider. Its network client and session belong to this activation.
class YouTubeMusicPlugin : NativePlugin {
    private var client: HttpClient? = null
    private var innerTube: InnerTube? = null
    private var cipherService: YouTubeCipherService? = null

    override suspend fun start(context: PluginContext) {
        val http =
            HttpClient {
                install(HttpTimeout) {
                    requestTimeoutMillis = 8000
                    connectTimeoutMillis = 4000
                    socketTimeoutMillis = 8000
                }
            }
        client = http
        val tube = InnerTube(http)
        val locale = Locale.getDefault()
        tube.locale = YouTubeLocale(gl = locale.country, hl = locale.toLanguageTag())
        innerTube = tube
        val configs = RemotePlayerConfigStore(http, PlayerConfigRepository.disabled())
        val cipher = YouTubeCipherService(http, configs)
        cipherService = cipher
        val extractor =
            InnerTubeExtractor(
                configParser = YtConfigParserImpl(http, tube, configs, cipherService = cipher),
                cipherService = cipher,
                innerTube = tube,
                fallbackStrategy = ContentAwareFallbackStrategy(),
            )
        val catalog = GuestCatalog(tube, YouTubeGuestClient(http))
        context.registerSearchProvider(catalog)
        context.registerCatalogProvider(catalog)
        context.registerMetadataSource(catalog)
        context.registerAudioSource(GuestAudio(extractor, context.settings))
    }

    override suspend fun stop() {
        try {
            cipherService?.dispose()
        } finally {
            cipherService = null
            try {
                innerTube?.close()
            } finally {
                innerTube = null
                client?.close()
                client = null
            }
        }
    }
}

private class GuestCatalog(
    private val tube: InnerTube,
    private val guestClient: YouTubeGuestClient,
) : SearchProvider,
    CatalogProvider,
    MetadataSource {
    override val id = "youtube-music.catalog"

    override suspend fun search(request: SearchRequest): SearchResult {
        val query = request.query.trim()
        if (query.isEmpty()) return SearchResult(emptyList())
        val response = tube.search(guestClient.get(), query = query, setLogin = false)
        check(response.status.isSuccess()) { "YouTube Music search failed with HTTP ${response.status.value}" }
        return YouTubeCatalog.search(response.bodyAsJsonObjectLimited(MAX_RESPONSE_BYTES), request.limit.coerceIn(1, 100))
    }

    override suspend fun album(id: String) = browse(id)?.let { YouTubeCatalog.album(it, id) }

    override suspend fun artist(id: String) = browse(id)?.let { YouTubeCatalog.artist(it, id) }

    override suspend fun playlist(id: String) = browse(if (id.startsWith("VL")) id else "VL$id")?.let { YouTubeCatalog.playlist(it, id) }

    private suspend fun browse(id: String): JsonObject? {
        if (!BROWSE_ID.matches(id)) return null
        val response = tube.browse(guestClient.get(), browseId = id, setLogin = false)
        check(response.status.isSuccess()) { "YouTube Music browse failed with HTTP ${response.status.value}" }
        return response.bodyAsJsonObjectLimited(MAX_RESPONSE_BYTES)
    }

    override suspend fun resolve(request: MetadataRequest): MetadataContribution? {
        val id = videoId(request.identity) ?: return null
        val response = tube.player(guestClient.get(), id)
        check(response.status.isSuccess()) { "YouTube Music metadata failed with HTTP ${response.status.value}" }
        val details = response.bodyAsJsonObjectLimited(MAX_RESPONSE_BYTES)["videoDetails"] as? JsonObject ?: return null
        if ((details["videoId"] as? JsonPrimitive)?.contentOrNull != id) return null
        val fields =
            buildJsonObject {
                (details["title"] as? JsonPrimitive)?.let { put("title", it) }
                (details["author"] as? JsonPrimitive)?.let { put("artist", it) }
                (details["lengthSeconds"] as? JsonPrimitive)
                    ?.longOrNull
                    ?.takeIf { it in 1..Long.MAX_VALUE / 1000 }
                    ?.let { put("duration", it * 1000) }
            }
        return fields.takeIf { it.isNotEmpty() }?.let { MetadataContribution(request.identity, it, confidence = 1.0) }
    }
}

internal class GuestAudio(
    private val extractor: StreamExtractor,
    private val settings: PluginSettings,
) : AudioSource {
    override val id = "youtube-music.audio"
    override val capabilities = AudioCapabilities(setOf(StreamTransport.DIRECT, StreamTransport.RANGE_HTTP, StreamTransport.HLS))

    override suspend fun resolve(request: AudioQuery): AudioResult {
        val id = videoId(request.identity) ?: return AudioResult(error = ProviderError("IDENTITY_REQUIRED"))
        if ((settings.values.value["audioEnabled"] as? JsonPrimitive)?.booleanOrNull == false) {
            return AudioResult(error = ProviderError("AUDIO_DISABLED"))
        }
        if (request.quality.lossless) return AudioResult(error = ProviderError("LOSSLESS_UNAVAILABLE"))
        val hints = ContentHints().withStreamCapabilities(allowHls = true, allowSabr = false, allowBoundedRange = true)
        val stream =
            try {
                extractor.extract(id, hints, audioQuality = AudioQuality.valueOf(request.quality.preset.name))
            } catch (e: StreamResolveException) {
                return AudioResult(error = ProviderError("YOUTUBE_${e.reason.name}"))
            } ?: return AudioResult(error = ProviderError("STREAM_UNAVAILABLE"))
        if (stream.sabrBootstrap != null) return AudioResult(error = ProviderError("STREAM_TRANSPORT_UNAVAILABLE"))
        if (request.quality.maxBitrate?.let { maximum -> stream.bitrate?.let { it > maximum } != false } == true) {
            return AudioResult(error = ProviderError("BITRATE_UNAVAILABLE"))
        }
        val transport =
            when {
                stream.mimeType?.contains("mpegurl", ignoreCase = true) == true ||
                    stream.audioUrl.substringBefore('?').endsWith(".m3u8", ignoreCase = true) -> StreamTransport.HLS

                stream.requireBoundedRange || stream.useRangeChunks -> StreamTransport.RANGE_HTTP

                else -> StreamTransport.DIRECT
            }
        return AudioResult(
            stream =
                StreamDescriptor(
                    transport = transport,
                    locator = stream.audioUrl,
                    mimeType = stream.mimeType,
                    codec = stream.codecs,
                    bitrate = stream.bitrate,
                    expiresAtMillis = stream.expiresAt?.toEpochMilliseconds(),
                    headers = stream.headers,
                    sampleRate = stream.sampleRate,
                    contentLength = stream.contentLengthBytes,
                    durationSeconds = stream.mediaMetadata?.durationSeconds,
                ),
        )
    }
}

internal fun videoId(identity: TrackIdentity): String? {
    val owner = identity.identifiers["pluginOwner"] ?: identity.identifiers["plugin"]
    val candidate =
        identity.identifiers["youtube"] ?: when (owner) {
            null, "metrofuse.youtube-music" -> identity.canonicalId
            else -> return null
        }
    return candidate.takeIf(VIDEO_ID::matches)
}

private const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
private val BROWSE_ID = Regex("[A-Za-z0-9_-]{1,240}")
