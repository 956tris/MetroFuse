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
    require((requiredCookies.isNotEmpty() || completionPath != null) && requiredCookies.size <= 12)
    require(requiredCookies.all { it.matches(Regex("[A-Za-z0-9._-]{1,128}")) })
    completionPath?.let {
        require(it.matches(Regex("/[A-Za-z0-9/_-]{0,255}")) && !it.startsWith("//") && URI(url).path != it)
    }
}

internal fun WebLoginRequest.canCaptureBrowserCookies(value: String?): Boolean {
    if (value == null || !acceptsBrowserNavigation(value)) return false
    // Service cookies can arrive before the identity provider finishes its redirects.
    val path = completionPath ?: return requiredCookies.isNotEmpty()
    val uri = URI(value)
    return uri.host == URI(cookieUrl).host && uri.path.ifEmpty { "/" } == path
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

/** Keep watching after a rejected session so completing sign-in can replace a stale cookie. */
internal suspend fun WebLoginRequest.monitorBrowserCredentials(
    capture: () -> String?,
    isBusy: () -> Boolean,
    isLoggedIn: () -> Boolean,
    submit: (Map<String, String>) -> Unit,
) {
    var submittedSession: Map<String, String>? = null
    while (!isLoggedIn()) {
        if (!isBusy()) {
            val header = capture()
            val credentials = header?.let { captureBrowserCookies(it) }
            if (header != null && credentials != null) {
                val cookies = parseBrowserCookies(header)
                // Tracking-cookie changes must not repeatedly submit the same rejected account session.
                val session = if (requiredCookies.isEmpty()) cookies else cookies.filterKeys { it in requiredCookies }
                if (session != submittedSession) {
                    submittedSession = session
                    submit(credentials)
                }
            }
        }
        delay(500)
    }
}

fun WebLoginRequest.acceptsBrowserNavigation(value: String): Boolean {
    val uri = runCatching { URI(value) }.getOrNull() ?: return false
    return uri.scheme == "https" && (uri.port == -1 || uri.port == 443) && uri.userInfo == null && uri.host in allowedHosts
}

internal fun WebLoginRequest.blocksBrowserNavigation(
    value: String,
    isMainFrame: Boolean,
): Boolean = isMainFrame && !acceptsBrowserNavigation(value)

fun WebLoginRequest.captureBrowserCookies(header: String): Map<String, String>? {
    if (header.isBlank() || header.length > 16384 || '\r' in header || '\n' in header) return null
    val cookies = parseBrowserCookies(header)
    if (cookies.none { it.value.isNotBlank() } ||
        (requiredCookies.isNotEmpty() && requiredCookies.none { !cookies[it].isNullOrBlank() })
    ) {
        return null
    }
    return mapOf(cookieField to header)
}

private fun parseBrowserCookies(header: String): Map<String, String> =
    header.split(';').mapNotNull { part ->
        val index = part.indexOf('=')
        if (index <= 0) null else part.substring(0, index).trim() to part.substring(index + 1).trim()
    }.toMap()
