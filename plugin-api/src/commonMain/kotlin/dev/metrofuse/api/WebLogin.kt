package dev.metrofuse.api

import kotlinx.serialization.Serializable

/** Browser session capture. The host passes the cookie header only to this activation's login callback. */
@Serializable
data class WebLoginRequest(
    val url: String,
    val cookieUrl: String,
    val cookieField: String,
    val requiredCookies: List<String>,
    val allowedHosts: List<String>,
    /** Exact path on cookieUrl's origin that the browser must reach before capturing cookies. */
    val completionPath: String? = null,
) {
    constructor(
        url: String,
        cookieUrl: String,
        cookieField: String,
        requiredCookies: List<String>,
        allowedHosts: List<String>,
    ) : this(url, cookieUrl, cookieField, requiredCookies, allowedHosts, null)
}

/** Optional interface so existing LoginPlugin and LoginRequest binaries retain their ABI. */
interface WebLoginPlugin : LoginPlugin {
    suspend fun webLoginRequest(): WebLoginRequest
}
