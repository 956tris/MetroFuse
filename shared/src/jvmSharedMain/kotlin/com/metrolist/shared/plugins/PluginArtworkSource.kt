package com.metrolist.shared.plugins

import java.io.File
import java.net.URI

internal fun pluginArtworkSource(value: String): String? {
    if (value.isBlank() || value.length > 16384 || value.any { it.isISOControl() }) return null
    if (value.startsWithNetworkPath()) return null
    // Desktop library artwork can be an absolute filesystem path, including spaces.
    if (File(value).isAbsolute) return File(value).toURI().toString()
    val uri = runCatching { URI(value) }.getOrNull() ?: return null
    if (uri.userInfo != null || uri.isOpaque || uri.fragment != null) return null
    val accepted =
        when (uri.scheme) {
            "https" -> {
                uri.port in setOf(-1, 443) &&
                    uri.host?.matches(Regex("(?=.*[a-zA-Z])[a-zA-Z0-9-]+(?:\\.[a-zA-Z0-9-]+)+")) == true
            }

            "file" -> {
                uri.authority.isNullOrEmpty() && uri.path?.let { it.startsWith('/') && !it.startsWithNetworkPath() } == true &&
                    uri.query == null
            }

            "content" -> {
                !uri.authority.isNullOrBlank() && uri.port == -1
            }

            // The Android host's fetcher extracts embedded covers from the selected audio file.
            "metrofuse-local-artwork" -> {
                uri.authority == "audio" && uri.rawQuery?.isNotBlank() == true
            }

            else -> {
                false
            }
        }
    return uri.toString().takeIf { accepted }
}

private fun String.startsWithNetworkPath(): Boolean = take(2).count { it == '/' || it == '\\' } == 2
