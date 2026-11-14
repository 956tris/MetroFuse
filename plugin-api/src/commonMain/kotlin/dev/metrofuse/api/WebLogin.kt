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
)

/** Optional interface so existing LoginPlugin and LoginRequest binaries retain their ABI. */
interface WebLoginPlugin : LoginPlugin {
    suspend fun webLoginRequest(): WebLoginRequest
}
