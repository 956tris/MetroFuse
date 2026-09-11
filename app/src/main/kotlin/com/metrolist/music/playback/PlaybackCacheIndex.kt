/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.ContentMetadata

internal object PlaybackCacheIndex {
    private val cacheKeyPrefixes =
        listOf(
            "apple-wrapper-alac-v3:",
            "apple-wrapper-alac-v2:",
            "apple-wrapper-alac:",
            "apple-music-fallback-audio:",
            "amazon-fallback-m4a:",
            "qobuz-fallback-v2:",
            "qobuz-fallback:",
            "tidal-flac-fallback-temp-v1:",
            "tidal-flac-fallback:",
            "deezer-fallback-audio:",
            "soundcloud-fallback-mp3:",
            "instagram-fallback-audio:",
            "direct-http-audio:",
            "youtube-fallback-aac:",
        )

    fun mediaIdForKey(key: String): String? {
        val normalized = key.trim()
        val mediaId = cacheKeyPrefixes.firstNotNullOfOrNull { prefix ->
            normalized.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)
        } ?: normalized
        return mediaId
            .takeIf { it.isNotBlank() }
            ?.takeUnless { it.startsWith("http://", ignoreCase = true) }
            ?.takeUnless { it.startsWith("https://", ignoreCase = true) }
            ?.takeUnless { it.startsWith("file:", ignoreCase = true) }
    }

    fun keysForMediaId(
        keys: Iterable<String>,
        mediaId: String,
    ): List<String> =
        keys
            .filter { mediaIdForKey(it) == mediaId }
            .distinct()

    fun isComplete(
        contentLength: Long,
        cachedLength: Long,
    ): Boolean = contentLength > 0L && cachedLength >= contentLength
}

internal fun Cache.isFullyCached(
    key: String,
    fallbackContentLength: Long? = null,
): Boolean =
    runCatching {
        val metadataLength = ContentMetadata.getContentLength(getContentMetadata(key))
        val contentLength =
            metadataLength.takeIf { it > 0L }
                ?: fallbackContentLength?.takeIf { it > 0L }
                ?: return@runCatching false
        val cachedLength = getCachedLength(key, 0L, contentLength)
        PlaybackCacheIndex.isComplete(contentLength, cachedLength) &&
            getCachedSpans(key).all { span -> !span.isCached || span.file?.exists() == true }
    }.getOrDefault(false)
