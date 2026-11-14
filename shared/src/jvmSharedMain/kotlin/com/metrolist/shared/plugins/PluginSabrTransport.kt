package com.metrolist.shared.plugins

import com.metrolist.innertubex.sabr.SabrBootstrap
import com.metrolist.innertubex.sabr.SabrFormatId
import dev.metrofuse.api.StreamDescriptor
import dev.metrofuse.api.StreamTransport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.net.URI
import kotlin.io.encoding.Base64

internal fun pluginSabrBootstrap(
    descriptor: StreamDescriptor,
    expectedVideoId: String,
): SabrBootstrap? {
    if (descriptor.transport != StreamTransport.SABR_UMP) return null
    val source = requireNotNull(descriptor.attributes["metrofuse.sabr.v1"]) { "Plugin SABR session missing" }
    require(source.length <= 256 * 1024) { "Plugin SABR session too large" }
    val root = Json.parseToJsonElement(source) as? JsonObject ?: error("Invalid plugin SABR session")
    val videoId = root.text("videoId")
    require(videoId == expectedVideoId) { "Plugin SABR media identity mismatch" }
    val url = root.text("serverAbrStreamingUrl")
    val uri = URI(url)
    require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && url == descriptor.locator) {
        "Invalid plugin SABR URL"
    }
    val duration = root.number("durationMs")
    require(duration >= 0) { "Invalid plugin SABR duration" }
    val length = root.optionalNumber("contentLengthBytes")
    require(length == null || length > 0) { "Invalid plugin SABR content length" }
    val mimeType = root.text("mimeType")
    require(mimeType.startsWith("audio/")) { "Plugin SABR audio format missing" }
    return SabrBootstrap(
        videoId = videoId,
        serverAbrStreamingUrl = url,
        videoPlaybackUstreamerConfig = root.bytes("videoPlaybackUstreamerConfig", 65536),
        clientName = root.integer("clientName").also { require(it > 0) },
        clientVersion = root.text("clientVersion"),
        audioFormat = root.format("audioFormat"),
        discardVideoFormat = root.format("discardVideoFormat"),
        discardVideoHeight = root.integer("discardVideoHeight").also { require(it > 0) },
        durationMs = duration,
        contentLengthBytes = length,
        mimeType = mimeType,
        audioTrackId = root.optionalText("audioTrackId"),
        isDrc = (root["isDrc"] as? JsonPrimitive)?.booleanOrNull ?: false,
        poToken = root.optionalText("poToken")?.let { root.bytes("poToken", 8192) },
        requestUserAgent = root.optionalText("requestUserAgent"),
        requestOrigin = root.optionalText("requestOrigin"),
        tokenVisitorData = root.optionalText("tokenVisitorData"),
    )
}

private fun JsonObject.optionalText(key: String): String? =
    (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.also {
        require(it.length <= 8192 && '\r' !in it && '\n' !in it) { "Invalid plugin SABR field: $key" }
    }

private fun JsonObject.text(key: String): String =
    requireNotNull(optionalText(key)) { "Plugin SABR field missing: $key" }.also { require(it.isNotBlank()) }

private fun JsonObject.optionalNumber(key: String): Long? = (get(key) as? JsonPrimitive)?.longOrNull

private fun JsonObject.number(key: String): Long = requireNotNull(optionalNumber(key)) { "Plugin SABR field missing: $key" }

private fun JsonObject.integer(key: String): Int =
    requireNotNull((get(key) as? JsonPrimitive)?.intOrNull) { "Plugin SABR field missing: $key" }

private fun JsonObject.bytes(
    key: String,
    maximum: Int,
): ByteArray {
    val encoded = (get(key) as? JsonPrimitive)?.contentOrNull ?: error("Plugin SABR field missing: $key")
    require(encoded.length <= maximum * 2) { "Plugin SABR field too large: $key" }
    return Base64.decode(encoded).also { require(it.size in 1..maximum) { "Invalid plugin SABR field: $key" } }
}

private fun JsonObject.format(key: String): SabrFormatId {
    val value = get(key) as? JsonObject ?: error("Plugin SABR field missing: $key")
    val itag = value.integer("itag")
    val modified = value.number("lastModified")
    require(itag > 0 && modified >= 0) { "Invalid plugin SABR format" }
    return SabrFormatId(itag, modified, value.optionalText("xtags"))
}
