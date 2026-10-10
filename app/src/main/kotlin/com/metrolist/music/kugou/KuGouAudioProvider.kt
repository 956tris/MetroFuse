/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.kugou

import com.metrolist.kugou.KuGou
import com.metrolist.kugou.KuGouAudio
import com.metrolist.music.constants.KuGouAudioQuality
import timber.log.Timber
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

object KuGouAudioProvider {
    const val TRACK_ID_PREFIX = "kugou:track:"

    private const val TAG = "KuGouAudioProvider"
    private const val MAX_SEARCH_CANDIDATES = 6
    private const val STREAM_CACHE_TTL_MS = 30 * 60 * 1000L
    private const val STREAM_CACHE_MAX_SIZE = 80
    private const val MIN_ACCEPT_SCORE = 80
    private const val MIN_ACCEPT_SCORE_NO_DURATION = 90
    private const val DURATION_HARD_REJECT_MS = 10_000L

    data class Query(
        val mediaId: String,
        val title: String,
        val artists: List<String>,
        val album: String?,
        val durationMs: Long?,
        val quality: KuGouAudioQuality = KuGouAudioQuality.HIGH,
        val hashOverride: String? = null,
    )

    data class Resolved(
        val mediaUri: String,
        val sourceId: String,
        val title: String,
        val artist: String,
        val mimeType: String,
        val codecs: String,
        val bitrate: Int,
        val sampleRate: Int?,
        val contentLength: Long?,
        val expiresAtMs: Long,
        // True when the stream is an official MV's MP4 (video edit, not the
        // album cut) used as a last-resort fallback for gated tracks.
        val isVideoAudio: Boolean = false,
    )

    data class TrackCandidate(
        val trackId: String,
        val title: String,
        val artist: String,
        val album: String?,
        val durationMs: Long?,
    )

    class KuGouResolutionException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private data class CacheEntry(
        val resolved: Resolved,
        val createdAtMs: Long,
    )

    private val streamCache = ConcurrentHashMap<String, CacheEntry>()

    fun isKuGouTrackId(mediaId: String): Boolean =
        mediaId.startsWith(TRACK_ID_PREFIX, ignoreCase = true)

    fun trackIdFromMediaId(mediaId: String): String =
        mediaId.removePrefix(TRACK_ID_PREFIX).removePrefix(TRACK_ID_PREFIX.lowercase(Locale.ROOT))

    suspend fun resolve(query: Query): Resolved {
        if (query.title.isBlank()) {
            throw KuGouResolutionException("KuGou audio needs a track title to search for")
        }
        val cacheKey = listOf(
            query.mediaId.normalizedSearchText(),
            query.title.normalizedSearchText(),
            query.artists.joinToString(",").normalizedSearchText(),
            query.durationMs?.div(1000L)?.toString().orEmpty(),
            query.quality.name,
            query.hashOverride.orEmpty(),
        ).joinToString("|")
        getCachedStream(cacheKey)?.let { return it }

        query.hashOverride?.takeIf { it.isNotBlank() }?.let { hash ->
            runCatching {
                return buildResolved(hash, query.title, query.artists.joinToString(", "), query, cacheKey)
            }.onFailure {
                Timber.tag(TAG).w(it, "KuGou override audio failed, trying MV fallback")
            }
        }

        val keyword = KuGou.generateKeyword(query.title, query.artists.firstOrNull().orEmpty(), query.album)
        val queryText = listOf(keyword.title, keyword.artist).filter { it.isNotBlank() }.joinToString(" ")
        val hits = KuGouAudio.searchSongs(queryText)
        if (hits.isEmpty()) {
            throw KuGouResolutionException(
                "KuGou search returned nothing for ${query.title} ${query.artists.firstOrNull().orEmpty()}",
            )
        }
        runCatching {
            val best = hits
                .mapNotNull { hit ->
                    val score = scoreCandidate(hit, query) ?: return@mapNotNull null
                    hit to score
                }
                .maxByOrNull { it.second }
                ?: throw KuGouResolutionException(
                    "KuGou found ${hits.size} tracks for ${query.title}, but none matched closely enough",
                )
            return buildResolved(
                fileHash = KuGouAudio.pickHash(best.first, query.quality.toAudioQuality()),
                title = best.first.title.ifBlank { query.title },
                artist = best.first.artist.ifBlank { query.artists.joinToString(", ") },
                query = query,
                cacheKey = cacheKey,
            )
        }.onFailure {
            Timber.tag(TAG).w(it, "KuGou audio resolve failed, trying MV fallback")
        }
        // Last resort: official-MV audio. The trackermv endpoint does not
        // enforce the audio privilege flags, so gated originals play when
        // they have a video. MV runtimes routinely differ from the album
        // cut (skits, clean edits), so duration is advisory only here.
        val videoBest = hits
            .filter { it.mvhash.isNotBlank() }
            .mapNotNull { hit ->
                val score = scoreVideoCandidate(hit, query) ?: return@mapNotNull null
                hit to score
            }
            .maxByOrNull { it.second }
            ?: throw KuGouResolutionException(
                "KuGou found ${hits.size} tracks for ${query.title}, but none playable (no free audio, no MV)",
            )
        return buildResolvedVideo(
            mvhash = videoBest.first.mvhash,
            title = videoBest.first.title.ifBlank { query.title },
            artist = videoBest.first.artist.ifBlank { query.artists.joinToString(", ") },
            query = query,
            cacheKey = cacheKey,
        )
    }

    suspend fun searchCandidates(
        title: String,
        artists: List<String>,
        limit: Int,
    ): List<TrackCandidate> {
        if (title.isBlank()) return emptyList()
        return runCatching {
            KuGouAudio.searchSongs(
                listOf(title, artists.firstOrNull().orEmpty()).filter { it.isNotBlank() }.joinToString(" "),
            ).take(limit).map { hit ->
                TrackCandidate(
                    trackId = hit.hash,
                    title = hit.title,
                    artist = hit.artist,
                    album = hit.album.takeIf { it.isNotBlank() },
                    durationMs = hit.durationSec.takeIf { it > 0 }?.toLong()?.times(1000L),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun invalidate(mediaId: String) {
        if (mediaId.isBlank()) return
        val prefix = mediaId.normalizedSearchText() + "|"
        runCatching {
            streamCache.keys.removeIf { it.startsWith(prefix) }
        }.onFailure {
            Timber.tag(TAG).w(it, "KuGou cache invalidate failed for $mediaId")
        }
    }

    private suspend fun buildResolved(
        fileHash: String,
        title: String,
        artist: String,
        query: Query,
        cacheKey: String,
    ): Resolved {
        val stream = KuGouAudio.streamUrl(fileHash)
        val (mimeType, bitrate) = mimeAndBitrate(stream.extName, query.quality)
        val resolved = Resolved(
            mediaUri = stream.url,
            sourceId = "kugou_${fileHash}_${title}_${artist}".sanitizeId(),
            title = title,
            artist = artist,
            mimeType = mimeType,
            codecs = "",
            bitrate = bitrate,
            sampleRate = null,
            contentLength = stream.fileSize,
            // Legacy CDN URLs carry no visible expiry; re-resolve periodically anyway.
            expiresAtMs = System.currentTimeMillis() + 6 * 60 * 60 * 1000L,
        )
        cacheStream(cacheKey, resolved)
        Timber.tag(TAG).i(
            "Resolved KuGou audio for ${query.mediaId}: $title by $artist, quality=${query.quality}",
        )
        return resolved
    }

    private suspend fun buildResolvedVideo(
        mvhash: String,
        title: String,
        artist: String,
        query: Query,
        cacheKey: String,
    ): Resolved {
        val stream = KuGouAudio.videoUrl(mvhash)
        val resolved = Resolved(
            mediaUri = stream.url,
            sourceId = "kugou_mv_${mvhash}_${title}_${artist}".sanitizeId(),
            title = title,
            artist = artist,
            mimeType = "video/mp4",
            codecs = "",
            // MV audio tracks are typically ~128k AAC inside the MP4.
            bitrate = 128_000,
            sampleRate = null,
            contentLength = stream.fileSize,
            expiresAtMs = System.currentTimeMillis() + 6 * 60 * 60 * 1000L,
            isVideoAudio = true,
        )
        cacheStream(cacheKey, resolved)
        Timber.tag(TAG).i(
            "Resolved KuGou MV audio for ${query.mediaId}: $title by $artist",
        )
        return resolved
    }

    private fun mimeAndBitrate(
        extName: String,
        quality: KuGouAudioQuality,
    ): Pair<String, Int> {
        val mime = when (extName) {
            "flac" -> "audio/flac"
            "ogg", "oga" -> "audio/ogg"
            "m4a" -> "audio/mp4"
            "aac" -> "audio/aac"
            else -> "audio/mpeg"
        }
        val bitrate = when (quality) {
            KuGouAudioQuality.LOSSLESS -> 1411_000
            KuGouAudioQuality.HIGH -> 320_000
            KuGouAudioQuality.STANDARD -> 128_000
        }
        return mime to bitrate
    }

    private fun scoreCandidate(
        hit: KuGouAudio.SongHit,
        query: Query,
    ): Int? {
        // Fail fast on paid/region-gated tiers: the tracker answers those
        // with status 2 / errcode 20028, so skip them before resolving.
        // Live-verified: privilege 0 tracks stream 128/320/FLAC with no
        // login, while gated tracks fail on every tier.
        if (!hit.isFreeFor(query.quality.toAudioQuality())) return null
        val expectedTitle = query.title.normalizedSearchText()
        val actualTitle = hit.title.normalizedSearchText()
        if (expectedTitle.isBlank() || actualTitle.isBlank()) return null
        var score = 0
        when {
            actualTitle == expectedTitle -> score += 100
            actualTitle.contains(expectedTitle) || expectedTitle.contains(actualTitle) -> score += 40
            else -> return null
        }
        val expectedArtist = query.artists.firstOrNull()?.normalizedSearchText().orEmpty()
        val actualArtist = hit.artist.normalizedSearchText()
        if (expectedArtist.isNotBlank() && actualArtist.isNotBlank()) {
            if (actualArtist.contains(expectedArtist) || expectedArtist.contains(actualArtist)) score += 35
        }
        val expectedMs = query.durationMs?.takeIf { it > 0 }
        val actualMs = hit.durationSec.takeIf { it > 0 }?.toLong()?.times(1000L)
        if (expectedMs != null && actualMs != null) {
            val delta = abs(actualMs - expectedMs)
            if (delta > DURATION_HARD_REJECT_MS) return null
            if (delta <= 2_000L) score += 25
        }
        val threshold = if (expectedMs == null) MIN_ACCEPT_SCORE_NO_DURATION else MIN_ACCEPT_SCORE
        return score.takeIf { it >= threshold }
    }

    /**
     * Looser match for the MV-audio fallback. Video edits routinely differ
     * from the album cut (skits, clean lyrics, longer runtime), so duration
     * is advisory only and the bar is title + artist.
     */
    private fun scoreVideoCandidate(
        hit: KuGouAudio.SongHit,
        query: Query,
    ): Int? {
        val expectedTitle = query.title.normalizedSearchText()
        val actualTitle = hit.title.normalizedSearchText()
        if (expectedTitle.isBlank() || actualTitle.isBlank()) return null
        var score = 0
        when {
            actualTitle == expectedTitle -> score += 100
            actualTitle.contains(expectedTitle) || expectedTitle.contains(actualTitle) -> score += 40
            else -> return null
        }
        val expectedArtist = query.artists.firstOrNull()?.normalizedSearchText().orEmpty()
        val actualArtist = hit.artist.normalizedSearchText()
        if (expectedArtist.isNotBlank() && actualArtist.isNotBlank()) {
            if (actualArtist.contains(expectedArtist) || expectedArtist.contains(actualArtist)) score += 35
        }
        val expectedMs = query.durationMs?.takeIf { it > 0 }
        val actualMs = hit.durationSec.takeIf { it > 0 }?.toLong()?.times(1000L)
        if (expectedMs != null && actualMs != null && abs(actualMs - expectedMs) <= 30_000L) {
            score += 10
        }
        return score.takeIf { it >= 60 }
    }

    private fun getCachedStream(key: String): Resolved? {
        val now = System.currentTimeMillis()
        val entry = streamCache[key] ?: return null
        if (now - entry.createdAtMs > STREAM_CACHE_TTL_MS || entry.resolved.expiresAtMs <= now + 20_000L) {
            streamCache.remove(key)
            return null
        }
        return entry.resolved
    }

    private fun cacheStream(
        key: String,
        stream: Resolved,
    ) {
        streamCache[key] = CacheEntry(stream, System.currentTimeMillis())
        if (streamCache.size > STREAM_CACHE_MAX_SIZE) {
            repeat((streamCache.size - STREAM_CACHE_MAX_SIZE).coerceAtLeast(1)) {
                val oldest = streamCache.entries.minByOrNull { it.value.createdAtMs } ?: return@repeat
                streamCache.remove(oldest.key)
            }
        }
    }

    private fun String.normalizedSearchText(): String =
        lowercase(Locale.ROOT).trim().replace("\\s+".toRegex(), " ")

    private fun String.sanitizeId(): String =
        replace("[^a-zA-Z0-9_-]".toRegex(), "_").take(120)

    private fun KuGouAudioQuality.toAudioQuality(): KuGouAudio.Quality =
        when (this) {
            KuGouAudioQuality.LOSSLESS -> KuGouAudio.Quality.LOSSLESS_FLAC
            KuGouAudioQuality.HIGH -> KuGouAudio.Quality.HIGH_320
            KuGouAudioQuality.STANDARD -> KuGouAudio.Quality.STANDARD_128
        }
}
