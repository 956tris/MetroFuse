package com.metrolist.kugou.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class AudioStreamResponse(
    val status: Int = 0,
    // The tracker returns either a plain string or an array of CDN URLs.
    val url: JsonElement? = null,
    val fileName: String? = null,
    val extName: String? = null,
    val fileSize: Long? = null,
    val bitRate: Int? = null,
    val songName: String? = null,
    val singerName: String? = null,
) {
    fun firstUrl(): String? =
        when (url) {
            is JsonArray -> url.firstOrNull()?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            else -> runCatching { url?.jsonPrimitive?.content }.getOrNull()?.takeIf { it.isNotBlank() }
        }
}

