package dev.metrofuse.sdk

import dev.metrofuse.api.JsonService
import dev.metrofuse.api.PluginContext
import dev.metrofuse.api.Track
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Service name the host calls when the player's video button is used for a track owned by this plugin. */
const val VIDEO_PLAYBACK_SERVICE = "playback.video/v1"

/** Request passed to [PluginVideoRequestProvider] when the player's video button is activated. */
data class PluginVideoRequest(
    val track: Track,
    val maxHeight: Int? = null,
    val allowSabr: Boolean = true,
    val allowHls: Boolean = true,
)

/**
 * Provider-agnostic video stream returned for the player's video button. The host keeps the audio resolved by the
 * plugin's `AudioSource` and renders [url] (direct MP4/WebM or HLS `.m3u8`, with optional [headers] such as `Referer`
 * or auth tokens) or an optional [sabrBootstrapJson] session as the picture.
 */
data class PluginVideoStream(
    val url: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val mimeType: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val bitrate: Int? = null,
    val expiresAtMillis: Long? = null,
    val codecs: String? = null,
    val itag: Int? = null,
    val contentLengthBytes: Long? = null,
    val sabrBootstrapJson: String? = null,
    val availableHeights: List<Int> = emptyList(),
    val musicVideoId: String? = null,
    val mediaId: String? = null,
)

/** Resolves video for [Track]; return null when the track has no video, which hides the video for that track. */
fun interface PluginVideoProvider {
    suspend fun resolve(track: Track): PluginVideoStream?
}

/** Resolves video for [PluginVideoRequest]; return null when the track has no video. */
fun interface PluginVideoRequestProvider {
    suspend fun resolve(request: PluginVideoRequest): PluginVideoStream?
}

/**
 * Hooks the player's video button. The host calls [provider] for tracks whose audio the plugin supplies, only while the
 * plugin is the enabled music source. Register at most one provider per plugin.
 */
fun PluginContext.registerVideoProvider(provider: PluginVideoProvider) {
    registerVideoProvider(PluginVideoRequestProvider { request -> provider.resolve(request.track) })
}

/**
 * Hooks the player's video button with access to requested resolution and transport flags. The host calls [provider]
 * for tracks whose audio the plugin supplies, only while the plugin is the enabled music source.
 */
fun PluginContext.registerVideoProvider(provider: PluginVideoRequestProvider) {
    registerService(
        VIDEO_PLAYBACK_SERVICE,
        object : JsonService {
            override suspend fun call(request: JsonObject): JsonObject {
                val track =
                    request["track"]?.let {
                        runCatching { Json { ignoreUnknownKeys = true }.decodeFromJsonElement(Track.serializer(), it) }.getOrNull()
                    } ?: return JsonObject(emptyMap())
                val videoRequest =
                    PluginVideoRequest(
                        track = track,
                        maxHeight = (request["maxHeight"] as? JsonPrimitive)?.intOrNull,
                        allowSabr = (request["allowSabr"] as? JsonPrimitive)?.booleanOrNull ?: true,
                        allowHls = (request["allowHls"] as? JsonPrimitive)?.booleanOrNull ?: true,
                    )
                val video = provider.resolve(videoRequest) ?: return JsonObject(emptyMap())
                if (video.url == null && video.sabrBootstrapJson == null) return JsonObject(emptyMap())
                return buildJsonObject {
                    video.url?.let { put("url", it) }
                    if (video.headers.isNotEmpty()) {
                        put("headers", JsonObject(video.headers.mapValues { JsonPrimitive(it.value) }))
                    }
                    video.mimeType?.let { put("mimeType", it) }
                    video.codecs?.let { put("codecs", it) }
                    video.width?.let { put("width", it) }
                    video.height?.let { put("height", it) }
                    video.bitrate?.let { put("bitrate", it) }
                    video.itag?.let { put("itag", it) }
                    video.contentLengthBytes?.let { put("contentLengthBytes", it) }
                    video.expiresAtMillis?.let { put("expiresAtMillis", it) }
                    video.sabrBootstrapJson?.let { put("sabr", it) }
                    if (video.availableHeights.isNotEmpty()) {
                        put("availableHeights", JsonArray(video.availableHeights.map { JsonPrimitive(it) }))
                    }
                    video.mediaId?.let { put("mediaId", it) }
                    video.musicVideoId?.let { put("musicVideoId", it) }
                }
            }
        },
    )
}
