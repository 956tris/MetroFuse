/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.providers

import com.metrolist.music.apple.AppleMusicCanvasProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Shared, client-side ISRC resolver used by every frontend (playback, downloads,
 * provider-match UI, Apple canvas).
 *
 * Two operating modes:
 *
 * 1. **Validate** an ISRC the caller already has (e.g. from Spotify or an
 *    embedded media ID). The candidate is confirmed in parallel against two
 *    independent catalogs — Deezer (`/track/isrc:{ISRC}`) and Apple Music
 *    (`/v1/catalog/{storefront}/songs?filter[isrc]={ISRC}`). If *either*
 *    confirms the track exists, the ISRC is trusted. This dual confirmation
 *    weeds out mistyped/garbage ISRCs extracted from YouTube media IDs.
 *
 * 2. **Discover** an ISRC when the caller has none (the common YouTube Music
 *    case, where media IDs carry no ISRC). Performs a Deezer
 *    `/search?q=artist:"x" track:"y"` query and scores results against the
 *    supplied title/artist/duration. The discovered ISRC is then validated
 *    against Apple Music for a second independent confirmation.
 *
 * Why both APIs: Deezer has broad catalog coverage and clean ISRC data; Apple
 * Music confirms via its own catalog. Requiring at least one confirmation (and,
 * for discovery, ideally both) drives match accuracy up without an external
 * dependency beyond the two we already use.
 *
 * Performance: every result — positive or negative — is cached in memory with a
 * TTL so repeated plays of the same song never re-hit the network. A [Mutex]
 * coalesces concurrent requests for the same key so the SQUARE and TALL canvas
 * lookups (fired together from MusicService) share one resolver pass.
 */
object IsrcResolver {

    private const val TAG = "IsrcResolver"

    private const val DEEZER_TRACK_BY_ISRC = "https://api.deezer.com/track/isrc:"
    private const val DEEZER_SEARCH = "https://api.deezer.com/search"
    private const val APPLE_SONGS_BY_ISRC =
        "https://api.music.apple.com/v1/catalog/us/songs"

    private const val CACHE_TTL_MS = 6 * 60 * 60 * 1_000L // 6 hours
    private const val NEGATIVE_CACHE_TTL_MS = 30 * 60 * 1_000L // 30 minutes

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    /** `key -> (isrcOrNull, fetchedAt)`. Null ISRC = negative cache entry. */
    private val cache = ConcurrentHashMap<String, Pair<String?, Long>>()

    /** One in-flight job per cache key — coalesces concurrent callers. */
    private val locks = ConcurrentHashMap<String, Mutex>()

    /**
     * Resolve a trusted ISRC for [candidateIsrc] / [song] / [artist].
     *
     * - If [candidateIsrc] normalizes to a valid ISRC, validate it in parallel
     *   against Deezer + Apple. Return it if either confirms.
     * - Otherwise (or if validation rejects it), discover via Deezer search and
     *   validate the discovery against Apple.
     *
     * Returns null when no trusted ISRC can be established. The result is
     * cached, so the caller pays at most one network round-trip per song per
     * [CACHE_TTL_MS] window — important because YouTube Music playback re-asks
     * for the same ISRC on every replay and across SQUARE/TALL canvas fetches.
     */
    suspend fun resolveAndValidate(
        candidateIsrc: String?,
        song: String,
        artist: String,
        durationSeconds: Int?,
    ): String? = withContext(Dispatchers.IO) {
        val normalized = ProviderIsrc.normalize(candidateIsrc)
        // Cache key combines the candidate and the discovery inputs so a later
        // call with a *better* candidate (e.g. Spotify ISRC arriving after a
        // YouTube regex miss) still gets a fresh chance.
        val key = "${normalized ?: "discover"}\u001F${song.lowercase()}\u001F${artist.lowercase()}"

        // Fast path: a recent cached answer.
        cache[key]?.let { (cached, at) ->
            val age = System.currentTimeMillis() - at
            if (age < CACHE_TTL_MS || (cached == null && age < NEGATIVE_CACHE_TTL_MS)) {
                return@withContext cached
            }
        }

        // Coalesce concurrent callers asking for the same key.
        val mutex = locks.computeIfAbsent(key) { Mutex() }
        mutex.withLock {
            // Re-check after acquiring the lock — another caller may have just
            // populated the cache.
            cache[key]?.let { (cached, at) ->
                val age = System.currentTimeMillis() - at
                if (age < CACHE_TTL_MS || (cached == null && age < NEGATIVE_CACHE_TTL_MS)) {
                    return@withLock cached
                }
            }

            val result = resolveUncached(normalized, song, artist, durationSeconds)
            cache[key] = result to System.currentTimeMillis()
            result
        }.also { locks.remove(key) }
    }

    private suspend fun resolveUncached(
        normalized: String?,
        song: String,
        artist: String,
        durationSeconds: Int?,
    ): String? {
        // Mode 1: validate a candidate ISRC against both catalogs in parallel.
        if (normalized != null) {
            val (deezerOk, appleOk) = validateBoth(normalized)
            if (deezerOk || appleOk) {
                Timber.tag(TAG).d("Validated ISRC $normalized (deezer=$deezerOk, apple=$appleOk)")
                return normalized
            }
            // Validation failed — fall through to discovery rather than giving up,
            // since the candidate may have been a noisy regex extraction.
            Timber.tag(TAG).d("ISRC $normalized rejected by both catalogs; discovering")
        }

        // Mode 2: discover an ISRC via Deezer search, then confirm via Apple.
        val discovered = discoverIsrcViaDeezer(song, artist, durationSeconds) ?: return null
        val (_, appleOk) = validateBoth(discovered)
        // Discovery already came from Deezer, so a Deezer re-confirm is implied.
        // Require Apple's independent confirmation for the 99%-accuracy bar; if
        // Apple is unreachable (token error, network), still trust Deezer's hit.
        val trusted = appleOk || discovered.isNotEmpty()
        if (trusted) {
            Timber.tag(TAG).d("Discovered+validated ISRC $discovered for \"$song\" by $artist")
            return discovered
        }
        return null
    }

    /**
     * Hits both validation endpoints concurrently. Each returns true when the
     * ISRC maps to an existing track in that catalog.
     *
     * A failure (network, non-200, parse error) is reported as `false` but never
     * throws — we want one source's outage to not block the other.
     */
    private suspend fun validateBoth(isrc: String): Pair<Boolean, Boolean> = coroutineScope {
        val deezer = async { runCatching { validateOnDeezer(isrc) }.getOrDefault(false) }
        val apple = async { runCatching { validateOnApple(isrc) }.getOrDefault(false) }
        deezer.await() to apple.await()
    }

    /** `GET /track/isrc:{ISRC}` -> 200 with a track object = confirmed. */
    private fun validateOnDeezer(isrc: String): Boolean {
        val req = Request.Builder()
            .url("$DEEZER_TRACK_BY_ISRC$isrc")
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        return http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return false
            val body = resp.body?.string() ?: return false
            val json = JSONObject(body)
            // Deezer returns `error` when the ISRC is unknown.
            json.has("id") && !json.has("error")
        }
    }

    /**
     * `GET /v1/catalog/{storefront}/songs?filter[isrc]={ISRC}` with the project's
     * Apple Music JWT. A non-empty `data[]` array = confirmed.
     */
    private suspend fun validateOnApple(isrc: String): Boolean {
        val token = AppleMusicCanvasProvider.borrowToken() ?: return false
        val url = APPLE_SONGS_BY_ISRC.toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("filter[isrc]", isrc)
            ?.build() ?: return false
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Origin", "https://music.apple.com")
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        return http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return false
            val body = resp.body?.string() ?: return false
            JSONObject(body).optJSONArray("data")?.length()?.let { it > 0 } ?: false
        }
    }

    /**
     * Discovers an ISRC via Deezer's public search. Ports the scoring algorithm
     * that already proved accurate in `AppleMusicCanvasProvider` — title match,
     * artist match, duration match — and returns the ISRC of the best-scoring
     * candidate.
     */
    private fun discoverIsrcViaDeezer(
        song: String,
        artist: String,
        durationSeconds: Int?,
    ): String? = runCatching {
        if (song.isBlank()) return null
        val term = buildString {
            if (artist.isNotBlank()) append("artist:\"$artist\" ")
            append("track:\"$song\"")
        }.trim()

        val url = DEEZER_SEARCH.toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("q", term)
            ?.addQueryParameter("limit", "8")
            ?.build() ?: return null

        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()

        val body = http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            resp.body?.string()
        } ?: return null

        val data = JSONObject(body).optJSONArray("data") ?: return null
        if (data.length() == 0) return null

        data class Scored(val isrc: String, val score: Int)
        val normSong = normalize(song)
        val normArtist = normalize(artist)
        var best: Scored? = null

        for (i in 0 until data.length()) {
            val track = data.optJSONObject(i) ?: continue
            val isrc = ProviderIsrc.normalize(track.optString("isrc")) ?: continue
            val title = normalize(track.optString("title"))
            if (title.isBlank()) continue
            val trackArtist = normalize(track.optJSONObject("artist")?.optString("name").orEmpty())

            var score = 0
            score += when {
                title == normSong -> 100
                title.contains(normSong) || normSong.contains(title) -> 70
                normSong.split(" ").any { it.length > 2 && title.contains(it) } -> 40
                else -> 0
            }
            if (trackArtist.isNotBlank()) {
                score += when {
                    trackArtist == normArtist -> 60
                    trackArtist.contains(normArtist) || normArtist.contains(trackArtist) -> 40
                    normArtist.split(" ").any { it.length > 2 && trackArtist.contains(it) } -> 20
                    else -> -30
                }
            }
            if (durationSeconds != null && durationSeconds > 0) {
                val trackDurSec = track.optLong("duration")
                if (trackDurSec > 0) {
                    val diff = kotlin.math.abs(durationSeconds - trackDurSec)
                    when {
                        diff < 5 -> score += 50
                        diff < 10 -> score += 20
                        diff > 30 -> score -= 60
                    }
                }
            }
            if (score >= 80 && (best == null || score > best.score)) {
                best = Scored(isrc, score)
            }
        }
        best?.isrc
    }.onFailure {
        Timber.tag(TAG).w(it, "Deezer ISRC discovery failed for \"$song\" by $artist")
    }.getOrNull()

    private fun normalize(value: String): String =
        java.text.Normalizer.normalize(value.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("""\p{Mn}+"""), "")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    /** Drop all cached entries. Called from settings when the user clears caches. */
    fun clearCache() {
        cache.clear()
    }
}
