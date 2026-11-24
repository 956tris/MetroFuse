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

    val url = text("url", 8192) ?: return null
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    if (uri.scheme != "https" || uri.userInfo != null || uri.fragment != null || uri.port !in setOf(-1, 443) ||
        uri.host?.let { it == "googlevideo.com" || it.endsWith(".googlevideo.com") } != true
    ) {
        return null
    }
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
    return PlaybackResource(
        videoId = text("videoId", 128)?.takeIf(String::isNotBlank) ?: songId,
        url = url,
        videoUrl = url,
        headers = headers,
        loudnessDb = null,
        expiresAt = number("expiresAtMillis")?.let { runCatching { Instant.fromEpochMilliseconds(it) }.getOrNull() },
        durationSeconds = number("durationSeconds")?.takeIf { it > 0 },
        videoMimeType = text("mimeType", 128),
        videoWidth = number("width")?.takeIf { it in 1L..7680L }?.toInt(),
        videoHeight = number("height")?.takeIf { it in 1L..4320L }?.toInt(),
        clientName = "plugin-canvas",
        profileId = "plugin-canvas",
    )
}
