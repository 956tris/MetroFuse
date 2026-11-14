package dev.metrofuse.runtime

import dev.metrofuse.api.LoginRequest
import dev.metrofuse.api.WebLoginRequest
import java.net.URI

internal fun WebLoginRequest.validate(form: LoginRequest) {
    require(allowedHosts.size in 1..12 && allowedHosts.distinct().size == allowedHosts.size)
    require(allowedHosts.all { it.length <= 253 && it.matches(Regex("[a-z0-9]+(?:[.-][a-z0-9]+)*")) })
    require(url.length <= 4096 && cookieUrl.length <= 4096 && acceptsNavigation(url) && acceptsNavigation(cookieUrl))
    require(form.fields.map { it.id } == listOf(cookieField))
    require(requiredCookies.size in 1..12 && requiredCookies.all { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) })
}

fun WebLoginRequest.acceptsNavigation(value: String): Boolean {
    val uri = runCatching { URI(value) }.getOrNull() ?: return false
    return uri.scheme == "https" && (uri.port == -1 || uri.port == 443) && uri.userInfo == null && uri.host in allowedHosts
}

fun WebLoginRequest.captureCookies(header: String): Map<String, String>? {
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
