package com.metrolist.shared.playback.ffmpeg

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

internal object DirectMediaRedirectInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        repeat(6) {
            val url = request.url
            if (!isTrustedDirectMediaEndpoint(url)) {
                throw IOException("Direct media redirect is not a trusted HTTPS endpoint")
            }
            val response = chain.proceed(request)
            if (!response.isRedirect) return response
            val target = response.header("Location")?.let(url::resolve) ?: return response
            response.close()
            val builder = request.newBuilder().url(target)
            if (!target.host.equals(url.host, ignoreCase = true) || target.port != url.port) {
                builder.removeHeader("Authorization")
                builder.removeHeader("Cookie")
            }
            request = builder.build()
        }
        throw IOException("Direct media exceeded the redirect limit")
    }
}

internal fun isTrustedDirectMediaEndpoint(url: HttpUrl): Boolean =
    url.isHttps && url.username.isEmpty() && url.password.isEmpty() && !isBlockedDirectMediaHost(url.host)

private fun isBlockedDirectMediaHost(host: String): Boolean {
    val normalized = host.trim().trimStart('[').trimEnd(']').lowercase()
    if (normalized.isEmpty() || normalized == "localhost" || normalized.endsWith(".localhost")) {
        return true
    }
    val ipv4Suffix = normalized.removePrefix("::ffff:")
    val octets = ipv4Suffix.split('.')
    if (octets.size == 4) {
        val bytes = octets.map { it.toIntOrNull() }
        if (bytes.all { it != null && it in 0..255 }) {
            val first = bytes[0]!!
            val second = bytes[1]!!
            return first == 127 || first == 0 || (first == 169 && second == 254)
        }
    }
    if (normalized == "::1" || normalized == "::" || normalized == "0:0:0:0:0:0:0:1" || normalized == "0:0:0:0:0:0:0:0") {
        return true
    }
    if (normalized.contains(':')) {
        val head = normalized.substringBefore(':').toIntOrNull(16)
        if (head != null && (head and 0xffc0) == 0xfe80) {
            return true
        }
    }
    return false
}
