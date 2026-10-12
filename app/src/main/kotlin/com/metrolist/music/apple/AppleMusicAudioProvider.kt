/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.apple

import com.metrolist.music.constants.AppleMusicAudioQuality
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Apple Music audio via the LyricsFlow API.
 *
 * Endpoints (live-verified):
 * - `GET /search?term=<query>&types=songs` -> Apple catalog search JSON
 *   (`results.songs.data[]` with `id`, `attributes.name/artistName/albumName/`
 *   `durationInMillis/isrc/url`).
 * - `GET /song/<appleSongId>` -> catalog metadata JSON (`data[0].attributes`).
 * - `GET /stream?song=<appleSongId>&codec=alac&websupport=false` -> full
 *   decrypted audio bytes. Supported codecs (per `/openapi.json`):
 *   `aac` (~256k M4A, fast), `alac` (lossless M4A, slow to transcode),
 *   `atmos` / `ac3` (only for tracks mastered in spatial; otherwise HTTP 500,
 *   in which case we fall back to `aac` inside the provider).
 *
 * Known server quirks: `/stream` takes seconds to answer (on-demand
 * decrypt/transcode, so callers must use generous timeouts), and it answers
 * HTTP Range requests with 500, so seeking past the buffered window fails.
 * Linear playback and downloads work; ExoPlayer serves backward seeks from
 * cache.
 */
object AppleMusicAudioProvider {
    const val TRACK_ID_PREFIX = "apple:track:"
    const val API_BASE_URL = "https://api.trylyricsflow.com"
    const val API_HOST = "api.trylyricsflow.com"

    private const val TAG = "AppleMusicAudioProvider"
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
        val isrc: String? = null,
        val trackIdOverride: String? = null,
        val quality: AppleMusicAudioQuality = AppleMusicAudioQuality.AAC,
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
    )

    data class TrackCandidate(
        val trackId: String,
        val title: String,
        val artist: String,
        val album: String?,
        val durationMs: Long?,
        val isrc: String?,
        val shareUrl: String?,
    )

    class AppleMusicResolutionException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private data class CacheEntry(
        val resolved: Resolved,
        val createdAtMs: Long,
    )

    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()

    private val streamCache = ConcurrentHashMap<String, CacheEntry>()

    /**
     * Short-timeout client for metadata + codec probes. Playback itself goes
     * through ExoPlayer's data source (which carries per-host long timeouts
     * for the slow transcode), never through this client.
     */
    private val probeClient =
        client
            .newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(35, TimeUnit.SECONDS)
            .build()

    fun isAppleMusicTrackId(mediaId: String): Boolean =
        mediaId.startsWith(TRACK_ID_PREFIX, ignoreCase = true)

    fun isApiUrl(url: okhttp3.HttpUrl): Boolean =
        url.host.equals(API_HOST, ignoreCase = true)

    fun trackIdFromMediaId(mediaId: String): String =
        mediaId.removePrefix(TRACK_ID_PREFIX).removePrefix(TRACK_ID_PREFIX.lowercase(Locale.ROOT))

    fun streamUrl(
        appleSongId: String,
        quality: AppleMusicAudioQuality = AppleMusicAudioQuality.AAC,
    ): String {
        val encoded = URLEncoder.encode(appleSongId.trim(), "UTF-8")
        return "$API_BASE_URL/stream?song=$encoded&codec=${quality.codecParam}&websupport=false"
    }

    fun resolve(query: Query): Resolved {
        if (query.title.isBlank() && query.trackIdOverride.isNullOrBlank()) {
            throw AppleMusicResolutionException("Apple Music audio needs a track title to search for")
        }
        val cacheKey = listOf(
            query.mediaId.normalizedSearchText(),
            query.title.normalizedSearchText(),
            query.artists.joinToString(",").normalizedSearchText(),
            query.durationMs?.div(1000L)?.toString().orEmpty(),
            query.trackIdOverride.orEmpty(),
            query.quality.name,
        ).joinToString("|")
        getCachedStream(cacheKey)?.let { return it }

        query.trackIdOverride?.takeIf { it.isNotBlank() }?.let { trackId ->
            val details = songDetails(trackId.trim())
            val title = details?.title?.takeIf { it.isNotBlank() } ?: query.title
            val artist = details?.artist?.takeIf { it.isNotBlank() }
                ?: query.artists.joinToString(", ")
            return buildResolved(trackId.trim(), title, artist, null, query.quality, cacheKey)
        }

        val candidates = searchCandidates(query.title, query.artists, MAX_SEARCH_CANDIDATES)
        if (candidates.isEmpty()) {
            throw AppleMusicResolutionException(
                "Apple Music search returned nothing for ${query.title} ${query.artists.firstOrNull().orEmpty()}",
            )
        }
        val best = candidates
            .mapNotNull { candidate ->
                val score = scoreCandidate(candidate, query) ?: return@mapNotNull null
                candidate to score
            }
            .maxByOrNull { it.second }
            ?: throw AppleMusicResolutionException(
                "Apple Music found ${candidates.size} tracks for ${query.title}, " +
                    "but none matched closely enough",
            )
        return buildResolved(
            appleSongId = best.first.trackId,
            title = best.first.title.ifBlank { query.title },
            artist = best.first.artist.ifBlank { query.artists.joinToString(", ") },
            contentLength = null,
            quality = query.quality,
            cacheKey = cacheKey,
        )
    }

    fun searchCandidates(
        title: String,
        artists: List<String>,
        limit: Int,
    ): List<TrackCandidate> {
        if (title.isBlank()) return emptyList()
        val queries = linkedSetOf(
            listOf(title, artists.firstOrNull().orEmpty()).filter { it.isNotBlank() }.joinToString(" "),
            listOf(artists.firstOrNull().orEmpty(), title).filter { it.isNotBlank() }.joinToString(" "),
            title,
        ).filter { it.isNotBlank() }.take(2)
        val found = linkedMapOf<String, TrackCandidate>()
        for (text in queries) {
            runCatching { searchSongs(text, limit) }
                .onSuccess { hits -> hits.forEach { found.putIfAbsent(it.trackId, it) } }
                .onFailure { Timber.tag(TAG).w(it, "Apple Music search failed for query=$text") }
            if (found.size >= limit) break
        }
        return found.values.take(limit)
    }

    fun songDetails(appleSongId: String): TrackCandidate? {
        val id = appleSongId.trim()
        if (id.isBlank()) return null
        return runCatching {
            val url = "$API_BASE_URL/song/$id".toHttpUrl()
            val request = Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", BROWSER_USER_AGENT)
                .header("Accept", "application/json")
                .build()
            client.newCall(request).execute().use { response ->
                val payload = response.body.string()
                if (!response.isSuccessful) {
                    throw AppleMusicResolutionException(
                        "Apple Music song lookup HTTP ${response.code}: ${payload.take(200)}",
                    )
                }
                val data = JSONObject(payload).optJSONArray("data") ?: return@use null
                if (data.length() == 0) return@use null
                parseSongObject(data.optJSONObject(0))
            }
        }.onFailure {
            Timber.tag(TAG).w(it, "Apple Music song lookup failed for $id")
        }.getOrNull()
    }

    fun invalidate(mediaId: String) {
        if (mediaId.isBlank()) return
        val prefix = mediaId.normalizedSearchText() + "|"
        runCatching {
            streamCache.keys.removeIf { it.startsWith(prefix) }
        }.onFailure {
            Timber.tag(TAG).w(it, "Apple Music cache invalidate failed for $mediaId")
        }
    }

    private fun searchSongs(term: String, limit: Int): List<TrackCandidate> {
        val url = "$API_BASE_URL/search".toHttpUrl().newBuilder()
            .addQueryParameter("term", term)
            .addQueryParameter("types", "songs")
            .build()
        val request = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", BROWSER_USER_AGENT)
            .header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { response ->
            val payload = response.body.string()
            if (!response.isSuccessful) {
                throw AppleMusicResolutionException(
                    "Apple Music search HTTP ${response.code}: ${payload.take(200)}",
                )
            }
            val songs = JSONObject(payload)
                .optJSONObject("results")
                ?.optJSONObject("songs")
                ?.optJSONArray("data") ?: return emptyList()
            val out = mutableListOf<TrackCandidate>()
            for (index in 0 until songs.length()) {
                parseSongObject(songs.optJSONObject(index))?.let { out += it }
                if (out.size >= limit.coerceIn(1, MAX_SEARCH_CANDIDATES)) break
            }
            return out
        }
    }

    private fun parseSongObject(obj: JSONObject?): TrackCandidate? {
        obj ?: return null
        val id = obj.optString("id").trim().takeIf { it.isNotBlank() } ?: return null
        val attrs = obj.optJSONObject("attributes") ?: JSONObject()
        val title = attrs.optString("name").trim()
        if (title.isBlank()) return null
        val artist = attrs.optString("artistName").trim()
        val album = attrs.optString("albumName").trim().takeIf { it.isNotBlank() }
        val durationMs = attrs.optLong("durationInMillis", 0L).takeIf { it > 0L }
        val isrc = attrs.optString("isrc").trim().takeIf { it.isNotBlank() }
        val shareUrl = attrs.optString("url").trim().takeIf { it.isNotBlank() }
        return TrackCandidate(
            trackId = id,
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            isrc = isrc,
            shareUrl = shareUrl,
        )
    }

    private fun buildResolved(
        appleSongId: String,
        title: String,
        artist: String,
        contentLength: Long?,
        quality: AppleMusicAudioQuality,
        cacheKey: String,
    ): Resolved {
        // Spatial codecs only exist on spatial-mastered tracks (HTTP 500
        // otherwise), so degrade to AAC inside the provider instead of
        // skipping Apple Music entirely for the track.
        val effectiveQuality =
            if (quality.probeBeforeUse && !isCodecAvailable(appleSongId, quality)) {
                Timber.tag(TAG).i("Apple Music $quality unavailable for $appleSongId, falling back to AAC")
                AppleMusicAudioQuality.AAC
            } else {
                quality
            }
        val resolved = Resolved(
            mediaUri = streamUrl(appleSongId, effectiveQuality),
            sourceId = "apple_${appleSongId}_${title}_${artist}".sanitizeId(),
            title = title,
            artist = artist,
            mimeType = effectiveQuality.mimeType,
            codecs = effectiveQuality.codecs,
            // ALAC stores no estimate: the nominal 900 kbps is not measured and
            // the player derives the real average from the `alac` magic cookie
            // sniffed off the stream (AlacCookieBitrateDataSource), including
            // the true sample rate. Seeding estimates here would be echoed back
            // by the renderer and mistaken for measured data downstream.
            bitrate = if (effectiveQuality == AppleMusicAudioQuality.ALAC) 0 else effectiveQuality.estimatedBitrate,
            sampleRate = if (effectiveQuality == AppleMusicAudioQuality.ALAC) null else 44_100,
            contentLength = contentLength,
            expiresAtMs = System.currentTimeMillis() + 12 * 60 * 60 * 1000L,
        )
        cacheStream(cacheKey, resolved)
        Timber.tag(TAG).i("Resolved Apple Music audio ($appleSongId): $title by $artist [${effectiveQuality.name}]")
        return resolved
    }

    /**
     * Cheap availability probe for spatial codecs: opens the stream URL and
     * reads only the response headers (arrives post-transcode). The body is
     * never consumed. Any failure means "not available".
     */
    private fun isCodecAvailable(
        appleSongId: String,
        quality: AppleMusicAudioQuality,
    ): Boolean =
        runCatching {
            val request = Request.Builder()
                .url(streamUrl(appleSongId, quality))
                .get()
                .header("User-Agent", BROWSER_USER_AGENT)
                .build()
            probeClient.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        }.getOrDefault(false)

    private fun scoreCandidate(candidate: TrackCandidate, query: Query): Int? {
        val normalizedTitle = query.title.normalizedSearchText()
        val normalizedCandidateTitle = candidate.title.normalizedSearchText()
        if (normalizedTitle.isBlank() || normalizedCandidateTitle.isBlank()) return null
        if (!query.isrc.isNullOrBlank() && !candidate.isrc.isNullOrBlank() &&
            query.isrc.trim().equals(candidate.isrc.trim(), ignoreCase = true)
        ) {
            return 1000
        }
        var score = 0
        if (normalizedTitle == normalizedCandidateTitle) score += 100
        if (normalizedCandidateTitle.contains(normalizedTitle) || normalizedTitle.contains(normalizedCandidateTitle)) {
            score += 40
        } else {
            val queryTokens = normalizedTitle.split(' ').filter { it.length > 2 }.toSet()
            val candidateTokens = normalizedCandidateTitle.split(' ').toSet()
            val overlap = queryTokens.intersect(candidateTokens).size
            if (queryTokens.isEmpty() || overlap < queryTokens.size) return null
            score += 15
        }
        val normalizedArtists = candidate.artist.normalizedSearchText()
        query.artists.forEach { artist ->
            val normalized = artist.normalizedSearchText()
            if (normalized.isNotBlank() && normalizedArtists.contains(normalized)) score += 35
        }
        val normalizedAlbum = query.album?.normalizedSearchText().orEmpty()
        val normalizedCandidateAlbum = candidate.album?.normalizedSearchText().orEmpty()
        if (normalizedAlbum.isNotBlank() && normalizedCandidateAlbum.isNotBlank() &&
            (normalizedAlbum == normalizedCandidateAlbum || normalizedCandidateAlbum.contains(normalizedAlbum))
        ) {
            score += 10
        }
        val expectedMs = query.durationMs
        val actualMs = candidate.durationMs
        if (expectedMs != null && actualMs != null) {
            val delta = abs(actualMs - expectedMs)
            score += when {
                delta <= 3_000L -> 50
                delta <= 8_000L -> 20
                delta <= DURATION_HARD_REJECT_MS -> 0
                else -> return null
            }
        }
        val threshold = if (expectedMs == null) MIN_ACCEPT_SCORE_NO_DURATION else MIN_ACCEPT_SCORE
        return score.takeIf { it >= threshold }
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

    private fun cacheStream(key: String, stream: Resolved) {
        streamCache[key] = CacheEntry(stream, System.currentTimeMillis())
        if (streamCache.size > STREAM_CACHE_MAX_SIZE) {
            repeat((streamCache.size - STREAM_CACHE_MAX_SIZE).coerceAtLeast(1)) {
                val oldest = streamCache.entries.minByOrNull { it.value.createdAtMs } ?: return@repeat
                streamCache.remove(oldest.key)
            }
        }
    }

    private fun String.normalizedSearchText(): String {
        val normalized = Normalizer.normalize(this, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
        return normalized.lowercase(Locale.ROOT)
            .replace(Regex("""\([^)]*\)|\[[^]]*]"""), " ")
            .replace(Regex("""[^a-z0-9]+"""), " ")
            .trim()
    }

    private fun String.sanitizeId(): String =
        lowercase(Locale.ROOT)
            .replace(Regex("""[^a-z0-9._-]+"""), "_")
            .trim('_')
            .take(120)
            .ifBlank { "apple_${System.currentTimeMillis()}" }

    private const val BROWSER_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Mobile Safari/537.36"
}
