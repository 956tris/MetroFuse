package dev.metrofuse.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

interface PluginStorage {
    suspend fun get(key: String): JsonElement?

    suspend fun set(
        key: String,
        value: JsonElement?,
    )
}

@Serializable
data class HttpRequest(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val maxBytes: Int = 2 * 1024 * 1024,
    val timeoutMs: Long = 15000,
) {
    // Body properties preserve the constructor ABI used by separately compiled plugins.
    var responseType: String = "text"
    var bodyEncoding: String = "text"
}

@Serializable
data class HttpResponse(
    val status: Int,
    val headers: Map<String, String>,
    val body: String,
) {
    var bodyEncoding: String = "text"
    var finalUrl: String? = null
    var setCookies: List<String> = emptyList()
}

interface PluginNetwork {
    suspend fun request(request: HttpRequest): HttpResponse
}

interface PluginResources {
    suspend fun readAsset(path: String): ByteArray

    val platformResources: Any?
}
