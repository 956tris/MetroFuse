package dev.metrofuse.sdk

import dev.metrofuse.api.WebLoginRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.net.URI

actual fun WebLoginRequest.validateBrowserRequest() {
    require(allowedHosts.size in 1..12 && allowedHosts.distinct().size == allowedHosts.size)
    require(allowedHosts.all { it.length <= 253 && it.matches(Regex("[a-z0-9]+(?:[.-][a-z0-9]+)*")) })
    require(url.length <= 4096 && cookieUrl.length <= 4096 && acceptsBrowserNavigation(url) && acceptsBrowserNavigation(cookieUrl))
    require(cookieField.matches(Regex("[A-Za-z0-9._-]{1,64}")))
    require(requiredCookies.size in 1..12 && requiredCookies.all { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) })
}

internal suspend fun awaitBrowserCredentials(capture: suspend () -> Map<String, String>?): Map<String, String> =
    withTimeout(180_000) {
        var captured = capture()
        while (captured == null) {
            delay(500)
            captured = capture()
        }
        captured
    }

fun WebLoginRequest.acceptsBrowserNavigation(value: String): Boolean {
    val uri = runCatching { URI(value) }.getOrNull() ?: return false
    return uri.scheme == "https" && (uri.port == -1 || uri.port == 443) && uri.userInfo == null && uri.host in allowedHosts
}

fun WebLoginRequest.captureBrowserCookies(header: String): Map<String, String>? {
    if (header.isBlank() || header.length > 16384 || '\r' in header || '\n' in header) return null
    val cookies =
        header
            .split(';')
            .mapNotNull { part ->
                val index = part.indexOf('=')
                if (index <= 0) null else part.substring(0, index).trim() to part.substring(index + 1).trim()
            }.toMap()
    if (requiredCookies.none { !cookies[it].isNullOrBlank() }) return null
    return mapOf(cookieField to header)
}
