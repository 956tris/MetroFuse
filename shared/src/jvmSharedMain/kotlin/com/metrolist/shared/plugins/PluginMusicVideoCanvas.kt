package com.metrolist.shared.plugins

import com.metrolist.shared.youtube.playback.PlaybackResource
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URI
import kotlin.time.Instant

internal fun pluginMusicVideoCanvasResource(
    songId: String,
    response: JsonObject,
): PlaybackResource? {
    fun text(
        name: String,
        limit: Int,
    ): String? =
        (response[name] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.contentOrNull
            ?.takeIf { it.length <= limit && it.none(Char::isISOControl) }

    fun number(name: String): Long? = (response[name] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

    val mediaId = text("videoId", 128)?.takeIf(String::isNotBlank)
    val expectedVideoId = mediaId ?: songId.substringAfterLast(':').takeIf(String::isNotBlank) ?: songId
    val rawUrl = text("url", 8192)
    val directUrl =
        rawUrl?.takeIf { candidate ->
            val uri = runCatching { URI(candidate) }.getOrNull() ?: return@takeIf false
            uri.scheme == "https" &&
                uri.userInfo == null &&
                uri.fragment == null &&
                uri.port in setOf(-1, 443) &&
                isPublicDomainHost(uri.host)
        }
    val sabrRaw =
        (response["sabr"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            ?: (response["sabr"] as? JsonObject)?.toString()
    val sabrVideo =
        sabrRaw?.let { raw ->
            runCatching { pluginSabrVideoBootstrap(raw, expectedVideoId) }.getOrNull()
        }
    if (directUrl == null && sabrVideo == null) return null

    val headers =
        (response["headers"] as? JsonObject)
            ?.entries
            ?.take(32)
            ?.mapNotNull { (key, value) ->
                val header = (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return@mapNotNull null
                if (!key.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,256}")) ||
                    header.length > 4096 || header.any(Char::isISOControl)
                ) {
                    return@mapNotNull null
                }
                key to header
            }?.toMap()
            .orEmpty()
    val streamUrl = directUrl ?: "sabr-video://${sabrVideo!!.videoId}"
    return PlaybackResource(
        videoId = expectedVideoId,
        url = streamUrl,
        videoUrl = streamUrl,
        headers = headers,
        loudnessDb = null,
        expiresAt = number("expiresAtMillis")?.let { runCatching { Instant.fromEpochMilliseconds(it) }.getOrNull() },
        durationSeconds =
            number("durationSeconds")?.takeIf { it > 0 }
                ?: sabrVideo?.durationMs?.takeIf { it > 0L }?.div(1000L),
        videoMimeType = text("mimeType", 128) ?: sabrVideo?.selectedVideoMimeType,
        videoCodecs = text("codecs", 128),
        videoWidth = number("width")?.takeIf { it in 1L..7680L }?.toInt() ?: sabrVideo?.selectedVideoWidth,
        videoHeight = number("height")?.takeIf { it in 1L..4320L }?.toInt() ?: sabrVideo?.selectedVideoHeight,
        videoBitrate = number("bitrate")?.takeIf { it >= 0L }?.toInt() ?: sabrVideo?.selectedVideoBitrate,
        videoItag = number("itag")?.takeIf { it > 0L }?.toInt() ?: sabrVideo?.selectedVideoFormat?.itag,
        videoContentLengthBytes =
            number("contentLengthBytes")?.takeIf { it > 0L }
                ?: sabrVideo?.selectedVideoContentLengthBytes,
        sabrVideoBootstrap = sabrVideo,
        clientName = "plugin-canvas",
        profileId = "plugin-canvas",
    )
}

private fun isPublicDomainHost(host: String?): Boolean {
    val normalized = host?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return false
    if (normalized == "localhost" || normalized.endsWith(".localhost") || normalized.endsWith(".local")) return false
    if (normalized.contains(':') || normalized.all { it.isDigit() || it == '.' }) return false
    return normalized.matches(Regex("(?=.*[a-z])[a-z0-9-]+(?:\\.[a-z0-9-]+)+"))
}
