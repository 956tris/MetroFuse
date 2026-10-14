/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.apple

import com.metrolist.music.constants.AppleMusicAudioQuality
import com.metrolist.music.providers.ProviderIsrc
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
 *
 * Spatial fast path (live-verified against `/openapi.json`): cheap
 * `GET /api/music/audio-traits?id=` reports `dolby_atmos` before any
 * transcode starts, so atmos/ac3 availability resolves in milliseconds
 * instead of waiting out a full transcode for headers. Results are cached
 * per track+codec. Note `fallback=true` does NOT rescue non-spatial tracks
 * (still HTTP 500) — the client-side AAC fallback stays.
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
    /** Same recording under a variant ISRC must agree this tightly. */
    private const val VARIANT_DURATION_CONFIRM_MS = 3_000L
    private const val PROBE_CACHE_TTL_MS = 60 * 60 * 1000L

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

    /** Spatial probe results per track+codec: transcode-latency, so never re-probed within TTL. */
    private data class CodecProbeEntry(
        val available: Boolean,
        val atMs: Long,
    )

    private val codecProbeCache = ConcurrentHashMap<String, CodecProbeEntry>()

    /**
     * Short-timeout client for the geeked route check. Render free-tier hosts
     * sleep when idle: a cold HEAD can hang for tens of seconds, so this
     * fails fast (8s) and the resolver drops back to LyricsFlow.
     */
    private val routeClient =
        client
            .newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()

    /**
     * Fast route check: HEADs the geeked stream URL. 2xx = serve from geeked
     * (main path); anything else (sleeping host, unknown codec, bad URL) =
     * LyricsFlow. Never throws.
     */
    private fun geekedRouteOk(streamUrl: String): Boolean =
        runCatching {
            val request = Request.Builder()
                .url(streamUrl)
                .head()
                .header("User-Agent", BROWSER_USER_AGENT)
                .build()
            routeClient.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        }.getOrDefault(false)

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

    /**
     * Main transport: geeked-api serves AAC/Atmos/AC3 from an Apple Music
     * share URL with instant HEAD responses and Range support; ALAC is not
     * served there (HTTP 422) and stays on LyricsFlow. Contract verified
     * live: `/stream?url=<shareUrl>&codec=aac|atmos|ac3`, HEAD-safe.
     */
    const val GEEKED_BASE_URL = "https://geeked-api.onrender.com"

    private fun AppleMusicAudioQuality.geekedCodec(): String? =
        when (this) {
            AppleMusicAudioQuality.AAC -> "aac"
            AppleMusicAudioQuality.DOLBY_ATMOS -> "atmos"
            AppleMusicAudioQuality.AC3 -> "ac3"
            AppleMusicAudioQuality.ALAC -> null
        }

    private fun geekedStreamUrl(
        shareUrl: String,
        quality: AppleMusicAudioQuality,
    ): String? {
        val codec = quality.geekedCodec() ?: return null
        if (shareUrl.isBlank()) return null
        val encoded = URLEncoder.encode(shareUrl.trim(), "UTF-8")
        return "$GEEKED_BASE_URL/stream?url=$encoded&codec=$codec"
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
            // Canonical metadata is one extra round trip: skip it when the
            // query already carries a usable title+artist (the common case),
            // saving ~a full RTT on every direct-ID play.
            val hasIdentity = query.title.isNotBlank() && query.artists.any { it.isNotBlank() }
            val details = if (hasIdentity) null else songDetails(trackId.trim())
            val title = details?.title?.takeIf { it.isNotBlank() } ?: query.title
            val artist = details?.artist?.takeIf { it.isNotBlank() }
                ?: query.artists.joinToString(", ")
            return buildResolved(trackId.trim(), title, artist, null, query.quality, cacheKey, details?.shareUrl)
        }

        val candidates = searchCandidates(query.title, query.artists, MAX_SEARCH_CANDIDATES, query.isrc)
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
            shareUrl = best.first.shareUrl,
        )
    }

    fun searchCandidates(
        title: String,
        artists: List<String>,
        limit: Int,
        isrc: String? = null,
    ): List<TrackCandidate> {
        if (title.isBlank()) return emptyList()
        val queries = linkedSetOf(
            // ISRC-first: catalog search matches exact ISRC strings, landing
            // the precise recording (any version) in one round trip.
            ProviderIsrc.normalize(isrc)?.takeIf { it.isNotBlank() },
            listOf(title, artists.firstOrNull().orEmpty()).filter { it.isNotBlank() }.joinToString(" "),
            listOf(artists.firstOrNull().orEmpty(), title).filter { it.isNotBlank() }.joinToString(" "),
            title,
        ).filterNotNull().filter { it.isNotBlank() }.take(3)
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
        shareUrl: String? = null,
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
        // Main transport is geeked-api (AAC/Atmos/AC3 from the share URL);
        // ALAC is not served there, and a failed route check (sleeping host,
        // unknown codec) drops back to LyricsFlow with zero playback impact.
        val geekedUrl = geekedStreamUrl(shareUrl.orEmpty(), effectiveQuality)
        val mediaUri = if (geekedUrl != null && geekedRouteOk(geekedUrl)) {
            geekedUrl
        } else {
            if (geekedUrl != null) {
                Timber.tag(TAG).i("Geeked route unavailable for $appleSongId, using LyricsFlow")
            }
            streamUrl(appleSongId, effectiveQuality)
        }
        val resolved = Resolved(
            mediaUri = mediaUri,
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
            sampleRate = when (effectiveQuality) {
                AppleMusicAudioQuality.ALAC -> null
                AppleMusicAudioQuality.DOLBY_ATMOS, AppleMusicAudioQuality.AC3 -> 48_000
                else -> 44_100
            },
            contentLength = contentLength,
            expiresAtMs = System.currentTimeMillis() + 12 * 60 * 60 * 1000L,
        )
        cacheStream(cacheKey, resolved)
        Timber.tag(TAG).i("Resolved Apple Music audio ($appleSongId): $title by $artist [${effectiveQuality.name}]")
        return resolved
    }

    /**
     * Cheap availability probe for spatial codecs. `GET /api/music/audio-traits`
     * reports `dolby_atmos` in milliseconds (no transcode wait); results are
     * cached per track+codec. Falls back to the legacy header-sniff probe only
     * when the traits call itself fails, so an API change degrades to old
     * behavior instead of blocking spatial entirely. Any failure means
     * "not available".
     */
    private fun isCodecAvailable(
        appleSongId: String,
        quality: AppleMusicAudioQuality,
    ): Boolean {
        val key = "$appleSongId::${quality.name}"
        val now = System.currentTimeMillis()
        codecProbeCache[key]?.takeIf { now - it.atMs < PROBE_CACHE_TTL_MS }?.let { return it.available }
        // Traits only speak Dolby Atmos; AC3 keeps the legacy header probe.
        val available = if (quality == AppleMusicAudioQuality.DOLBY_ATMOS) {
            audioTraitsDolbyAtmos(appleSongId) ?: legacyStreamProbe(appleSongId, quality)
        } else {
            legacyStreamProbe(appleSongId, quality)
        }
        codecProbeCache[key] = CodecProbeEntry(available, now)
        if (codecProbeCache.size > 500) {
            val oldest = codecProbeCache.entries.minByOrNull { it.value.atMs }
            if (oldest != null) codecProbeCache.remove(oldest.key)
        }
        return available
    }

    /** Returns dolby_atmos for [appleSongId], or null when unknowable (probe legacy path). */
    private fun audioTraitsDolbyAtmos(appleSongId: String): Boolean? =
        runCatching {
            val url = "$API_BASE_URL/api/music/audio-traits".toHttpUrl().newBuilder()
                .addQueryParameter("id", appleSongId)
                .build()
            val request = Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", BROWSER_USER_AGENT)
                .header("Accept", "application/json")
                .build()
            probeClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val payload = response.body.string().takeIf { it.isNotBlank() } ?: return@use null
                val obj = JSONObject(payload)
                if (!obj.has("dolby_atmos")) return@use null
                obj.optBoolean("dolby_atmos", false)
            }
        }.getOrNull()

    /**
     * Legacy availability probe: opens the stream URL and reads only the
     * response headers (arrives post-transcode). The body is never consumed.
     */
    private fun legacyStreamProbe(
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
        val normalizedQueryArtists = query.artists.map { it.normalizedSearchText() }.filter { it.isNotBlank() }
        val normalizedArtists = candidate.artist.normalizedSearchText()
        val exactTitle = normalizedTitle == normalizedCandidateTitle
        val exactArtist = normalizedQueryArtists.isNotEmpty() &&
            normalizedQueryArtists.any { it == normalizedArtists }
        val durationDeltaMs = if (query.durationMs != null && candidate.durationMs != null) {
            abs(candidate.durationMs - query.durationMs)
        } else {
            null
        }

        // ISRC: exact match wins immediately. A differing ISRC is the same
        // recording re-issued (another version/region) when title+artist are
        // exact and duration confirms it — accept those at near-max score.
        // Any other ISRC contradiction rejects: a cover with ISRC metadata
        // must never outscore the real track on text alone.
        val queryIsrc = ProviderIsrc.normalize(query.isrc)
        val candidateIsrc = ProviderIsrc.normalize(candidate.isrc)
        if (queryIsrc != null && candidateIsrc != null) {
            if (queryIsrc == candidateIsrc) return 1000
            val sameRecording = exactTitle && exactArtist &&
                durationDeltaMs != null && durationDeltaMs <= VARIANT_DURATION_CONFIRM_MS
            if (!sameRecording) return null
            return 900
        }

        // Artist gate: with named artists, at least one must appear in the
        // candidate — a same-titled track by someone else can never win.
        if (normalizedQueryArtists.isNotEmpty() &&
            normalizedQueryArtists.none { queryArtist ->
                normalizedArtists == queryArtist ||
                    normalizedArtists.contains(queryArtist) ||
                    queryArtist.contains(normalizedArtists)
            }
        ) {
            return null
        }

        var score = 0
        if (exactTitle) score += 100
        if (normalizedCandidateTitle.contains(normalizedTitle) || normalizedTitle.contains(normalizedCandidateTitle)) {
            score += 40
        } else {
            val queryTokens = normalizedTitle.split(' ').filter { it.length > 2 }.toSet()
            val candidateTokens = normalizedCandidateTitle.split(' ').toSet()
            val overlap = queryTokens.intersect(candidateTokens).size
            if (queryTokens.isEmpty() || overlap < queryTokens.size) return null
            score += 15
        }
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
                // Same recording, longer edition (extended/deluxe): don't
                // nuke an exact title+artist hit on duration alone.
                exactTitle && normalizedQueryArtists.isNotEmpty() &&
                    normalizedQueryArtists.any { it == normalizedArtists } -> 0
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
            // Edition suffixes ("Song - 2023 Remaster", "Song - Live") describe
            // the version, not the recording: strip them so deluxe/anniversary
            // editions match the canonical title. Only known markers strip —
            // a bare " - " with other text is left intact.
            .replace(
                Regex(
                    """\s+-\s+(remaster(?:ed)?|remix|live|version|edition|explicit|clean|acoustic|instrumental|cover|tribute|karaoke|sped|slowed|audio|official|single|ep|deluxe|anniversary|expanded|bonus|demo|mono|stereo|dts|atmos)\b.*$""",
                ),
                " ",
            )
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
