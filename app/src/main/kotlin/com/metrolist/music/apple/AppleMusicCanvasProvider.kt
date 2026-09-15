/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.apple

import com.metrolist.music.providers.IsrcResolver
import com.metrolist.music.providers.ProviderIsrc
import com.metrolist.music.utils.CanvasQueryCleaner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Fetches Apple Music animated canvas (motion cover) URLs via the
 * amp-api.music.apple.com AMP API.
 *
 *
 * Token is obtained from the self hosted JWT endpoint so it never expires.
 * Results are cached in-memory by ISRC (or song+artist if no ISRC available).
 *
 * NOTE: The [AppleMusicCanvas.animated] URL is an HLS m3u8 stream — if
 * [downloadCanvas] expects a direct MP4 byte-stream, you will need to resolve
 * the highest-quality segment playlist and reassemble the fMP4 fragments.
 */
object AppleMusicCanvasProvider {

    private const val TAG = "AppleMusicCanvasProvider"

    // Your own JWT server — token never expires
    private const val TOKEN_URL =
        "https://yesitworkssomehow-funny-deeza-api-and-yeah.hf.space/apple/token"

    private const val AMP_BASE = "https://amp-api.music.apple.com"
    private const val STOREFRONT = "us"

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    // How long (ms) to reuse a fetched token before refreshing
    private const val TOKEN_TTL_MS = 30 * 60 * 1_000L

    private val VIDEO_URL_REGEX = Regex("""\.(m3u8|mp4)(\?|$)""", RegexOption.IGNORE_CASE)

    // ---------- Public API ----------

    enum class CanvasAspectPreference { TALL, SQUARE }

    /**
     * Animated canvas result.
     * [animated] is the HLS m3u8 master playlist URL, or null if unavailable.
     */
    data class AppleMusicCanvas(val animated: String?)

    // ---------- Cache ----------

    private val cache = ConcurrentHashMap<String, AppleMusicCanvas>()

    /** Maps cache keys to the timestamp we confirmed "no canvas exists". */
    private val negativeCache = ConcurrentHashMap<String, Long>()
    private const val NEGATIVE_CACHE_TTL_MS = 5 * 60 * 1_000L

    // Token + its fetch timestamp
    @Volatile private var cachedToken: String? = null
    @Volatile private var tokenFetchedAt: Long = 0L

    // ---------- HTTP client ----------

    private val http = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    // Serializes token fetches so two concurrent requests (e.g. the SQUARE and
    // TALL lookups fired together from MusicService) don't both race to hit
    // the token endpoint at once on a cache-miss/cold-start — the second
    // caller just waits and reuses whatever the first one fetched.
    private val tokenMutex = Mutex()

    // ---------- Matching debug ----------

    private const val DEBUG_MATCHING = true

    private fun logMatchDecision(
        song: String,
        artist: String,
        queryIsrc: String?,
        tier: CanvasMatchTier?,
        confidence: Int,
        matchedTitle: String?,
    ) {
        if (!DEBUG_MATCHING) return
        val header = buildString {
            appendLine("Song:")
            appendLine("  Title: $song")
            appendLine("  Artist: $artist")
            appendLine("  ISRC: ${queryIsrc ?: "none"}")
            if (tier == null) {
                appendLine("Selected: NONE (no canvas found at any confidence tier)")
            } else {
                appendLine("Selected: ${matchedTitle ?: song}")
                appendLine("  Match tier: $tier")
                appendLine("  Confidence: $confidence%")
            }
        }
        Timber.tag(TAG).d(header)
    }

    // ---------- Public methods ----------

    /**
     * Synchronous in-memory cache lookup — no network, no suspend.
     * Returns null when not cached; caller should then call [getBySongArtist].
     */
    fun getCached(
        song: String,
        artist: String,
        album: String?,
        explicit: Boolean?,
        isrc: String?,
        durationSeconds: Int?,
        preferredAspect: CanvasAspectPreference,
    ): AppleMusicCanvas? {
        val key = cacheKey(isrc, song, artist, preferredAspect)
        cache[key]?.let { return it }
        // Negative cache: we previously confirmed no canvas exists.
        val neg = negativeCache[key]
        if (neg != null && System.currentTimeMillis() - neg < NEGATIVE_CACHE_TTL_MS) return null
        return null
    }

    /**
     * Fetches an Apple Music animated canvas for the given track.
     * Strategy:
     *  1. ISRC lookup via `/v1/catalog/{storefront}/songs?filter[isrc]=...`
     *  2. Text search fallback via `/v1/catalog/{storefront}/search?term=...`
     * If the first attempt comes back empty, retries once with a force-refreshed
     * token (a stale/rejected token looks identical to "no canvas exists"
     * otherwise).
     *
     * Results are stored in the in-memory cache for subsequent [getCached] hits.
     */
    suspend fun getBySongArtist(
        song: String,
        artist: String,
        album: String?,
        explicit: Boolean?,
        isrc: String?,
        durationSeconds: Int?,
        preferredAspect: CanvasAspectPreference,
    ): AppleMusicCanvas? = withContext(Dispatchers.IO) {
        // Clean YTM video-style titles + "- Topic" artists up front so the
        // cache key, ISRC discovery, and catalog search all see the real
        // song name. Idempotent for already-clean catalog sources.
        val cleanSong = CanvasQueryCleaner.cleanTitle(song).ifBlank { song }
        val cleanArtist = CanvasQueryCleaner.cleanArtist(artist).ifBlank { artist }
        val validDuration = durationSeconds?.takeIf { it > 30 }
        val key = cacheKey(isrc, cleanSong, cleanArtist, preferredAspect)
        cache[key]?.let { return@withContext it }
        // Respect negative cache so we don't re-query AMP every play.
        val neg = negativeCache[key]
        if (neg != null && System.currentTimeMillis() - neg < NEGATIVE_CACHE_TTL_MS) {
            return@withContext null
        }

        runCatching {
            var token = getToken() ?: run {
                Timber.tag(TAG).w("No Apple Music token — skipping canvas fetch")
                return@withContext null
            }

            // Resolve + VALIDATE a trusted ISRC via the shared multi-source
            // resolver. A caller-supplied ISRC that fails ProviderIsrc's
            // shape check is treated as absent, never passed through —
            // a malformed tag must never be used as a lookup key.
            //
            // Once resolved, this ISRC is the single highest-priority
            // identifier for the rest of this call: a later, lower-tier
            // match (catalog search / fuzzy) is only ever used to fill in
            // a canvas URL for *this same* ISRC-identified track, never to
            // silently swap to a different track.
            var resolvedIsrc = ProviderIsrc.normalize(isrc)
            if (resolvedIsrc == null) {
                resolvedIsrc = IsrcResolver.resolveAndValidate(
                    candidateIsrc = null,
                    song = cleanSong,
                    artist = cleanArtist,
                    durationSeconds = validDuration,
                )
                if (resolvedIsrc != null) {
                    Timber.tag(TAG).d("Using resolver ISRC $resolvedIsrc for \"$cleanSong\" by $cleanArtist")
                }
            }

            // Index short-circuit: an existing higher-or-equal confidence
            // entry for this exact ISRC means we don't need to hit the
            // network at all (separate from the raw HLS-URL [cache] above,
            // this also captures the *reason* the match was made).
            resolvedIsrc?.let { CanvasIndex.getByIsrc(it) }?.let { indexed ->
                logMatchDecision(cleanSong, cleanArtist, resolvedIsrc, indexed.matchTier, indexed.confidence, indexed.title)
                return@withContext AppleMusicCanvas(animated = indexed.sourceUrl).also {
                    cache[key] = it
                }
            }

            var searchTransportFailed = false
            fun attempt(): Triple<AppleMusicCanvas?, CanvasMatchTier?, JSONObject?> {
                // Tier 1: exact ISRC match — the most accurate identifier.
                if (resolvedIsrc != null) {
                    val (canvasResult, songItem) = fetchByIsrc(resolvedIsrc, token, preferredAspect)
                    if (canvasResult != null) return Triple(canvasResult, CanvasMatchTier.ISRC_EXACT, songItem)
                    if (songItem != null) {
                        // ISRC matched a real catalog track but it has no
                        // canvas — this is a correct negative result and
                        // must NOT fall through to search/fuzzy matching,
                        // which could otherwise attach a different track's
                        // canvas to this ISRC.
                        return Triple(null, CanvasMatchTier.ISRC_EXACT, songItem)
                    }
                }
                // Tier 3/4: catalog search fallback — scored against title +
                // artist + duration (Tier 4 = FUZZY) unless the returned
                // catalog item itself carries a matching ISRC (Tier 2 style
                // confirmation), or album+artist+title line up exactly
                // (Tier 3).
                val search = fetchBySearch(cleanSong, cleanArtist, validDuration, token, preferredAspect)
                if (search.transportFailed) searchTransportFailed = true
                // Raw-title retry: if cleaning overshot (rare), one attempt
                // with the original query before giving up.
                if (search.canvas == null && search.item == null &&
                    (cleanSong != song || cleanArtist != artist)
                ) {
                    val retry = fetchBySearch(song, artist, validDuration, token, preferredAspect)
                    if (retry.transportFailed) searchTransportFailed = true
                    return Triple(retry.canvas, retry.tier, retry.item)
                }
                return Triple(search.canvas, search.tier, search.item)
            }

            var (canvas, tier, matchedItem) = attempt()

            // An empty result might mean the cached token is stale/rejected
            // server-side, not that Apple has no canvas. Force-refresh + retry.
            if (canvas == null && tier != CanvasMatchTier.ISRC_EXACT && !searchTransportFailed) {
                token = getToken(forceRefresh = true) ?: token
                val retry = attempt()
                canvas = retry.first
                tier = retry.second
                matchedItem = retry.third
            }

            val matchedAttrs = matchedItem?.optJSONObject("attributes")
            val matchedTitle = matchedAttrs?.optString("name")
            val matchedCatalogId = matchedItem?.optString("id")?.takeIf { it.isNotBlank() }
            val matchedIsrcRaw = matchedAttrs?.optString("isrc")?.takeIf { it.isNotBlank() }
            val effectiveIsrc = resolvedIsrc ?: ProviderIsrc.normalize(matchedIsrcRaw)

            logMatchDecision(cleanSong, cleanArtist, resolvedIsrc, tier, tier?.baseConfidence ?: 0, matchedTitle)

            if (canvas != null) {
                cache[key] = canvas
                negativeCache.remove(key)
                if (tier != null) {
                    CanvasIndex.put(
                        CanvasMatchEntry(
                            isrc = effectiveIsrc,
                            appleCatalogId = matchedCatalogId,
                            title = matchedTitle ?: cleanSong,
                            artist = cleanArtist,
                            album = null,
                            durationMs = validDuration?.toLong()?.times(1000L),
                            sourceUrl = canvas.animated.orEmpty(),
                            matchTier = tier,
                            confidence = tier.baseConfidence,
                            lastMatchedAtMs = System.currentTimeMillis(),
                        ),
                    )
                }
            } else if (!searchTransportFailed) {
                // Only negative-cache definitive misses. Transport failures
                // (HTTP errors, timeouts, token cold-start) must retry next
                // play instead of blocking for NEGATIVE_CACHE_TTL_MS.
                negativeCache[key] = System.currentTimeMillis()
            }
            canvas
        }.onFailure {
            Timber.tag(TAG).e(it, "Canvas fetch failed for \"$cleanSong\" by $cleanArtist")
        }.getOrNull()
    }

    /**
     * ISRC-only catalog search — used by [IsrcResolver] to discover an ISRC
     * for a track (e.g. one only available via YouTube Music) without
     * needing a canvas URL. Returns the first validated ISRC from the
     * best-scoring search candidate, or null.
     */
    internal fun searchIsrcOnly(song: String, artist: String, durationSeconds: Int?, token: String): String? {
        val cleanSong = CanvasQueryCleaner.cleanTitle(song).ifBlank { song }
        val cleanArtist = CanvasQueryCleaner.cleanArtist(artist).ifBlank { artist }
        val url = buildAmpUrl(
            "$AMP_BASE/v1/catalog/$STOREFRONT/search",
            mapOf("term" to "$cleanSong $cleanArtist", "types" to "songs", "limit" to "10"),
        )
        val body = runCatching {
            http.newCall(ampRequest(url, token)).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.string()
            }
        }.getOrNull() ?: return null
        val root = JSONObject(body)
        val candidates = collectSearchSongs(root)
        val best = pickBestCandidate(candidates, cleanSong, cleanArtist, durationSeconds) ?: return null
        return ProviderIsrc.normalize(best.optJSONObject("attributes")?.optString("isrc"))
    }

    // ---------- Internal ----------

    private fun cacheKey(
        isrc: String?,
        song: String,
        artist: String,
        aspect: CanvasAspectPreference,
    ): String =
        ((isrc?.takeIf { it.isNotBlank() } ?: "$song\u001F$artist") + "\u001F$aspect").lowercase()

    /**
     * Borrow the current Apple Music JWT (fetching one if needed) so other
     * components can reuse the project's token endpoint instead of duplicating
     * the auth flow. Used by [com.metrolist.music.providers.IsrcResolver] to
     * validate ISRCs against the Apple Music catalog.
     */
    internal suspend fun borrowToken(forceRefresh: Boolean = false): String? =
        getToken(forceRefresh)

    /** Fetch (or return cached) Bearer token from the project JWT endpoint. */
    internal suspend fun getToken(forceRefresh: Boolean = false): String? {
        // Fast path: don't even take the lock if we already have a fresh token.
        if (!forceRefresh) {
            val fresh = cachedToken
            if (fresh != null && (System.currentTimeMillis() - tokenFetchedAt) < TOKEN_TTL_MS) return fresh
        }

        return tokenMutex.withLock {
            val now = System.currentTimeMillis()
            if (!forceRefresh) {
                val cached = cachedToken
                if (cached != null && (now - tokenFetchedAt) < TOKEN_TTL_MS) return@withLock cached
            }

            runCatching {
                val req = Request.Builder().url(TOKEN_URL).header("User-Agent", USER_AGENT).get().build()
                val body = http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Timber.tag(TAG).w("Token endpoint returned ${resp.code}")
                        return@runCatching null
                    }
                    resp.body?.string()?.trim()
                } ?: return@runCatching null

                // Handle both a raw JWT string and a JSON wrapper
                val token = when {
                    body.startsWith("eyJ") -> body
                    else -> {
                        val json = JSONObject(body)
                        json.optString("token").takeIf { it.startsWith("eyJ") }
                            ?: json.optString("jwt").takeIf { it.startsWith("eyJ") }
                            ?: json.optString("access_token").takeIf { it.startsWith("eyJ") }
                    }
                }
                if (token != null) {
                    cachedToken = token
                    tokenFetchedAt = now
                }
                token
            }.onFailure { Timber.tag(TAG).e(it, "Failed to fetch Apple Music token") }
                .getOrNull()
        }
    }

    /**
     * Builds an AMP catalog URL with the exact query-param shape confirmed to
     * return motion/editorialVideo data. Critically this requests the fields
     * explicitly via `fields[songs]`/`fields[albums]` and `format[resources]=map`
     * rather than relying on a bare `extend=editorialVideo`, which returns 200 OK
     * responses that simply omit the motion attributes.
     */
    internal fun buildAmpUrl(base: String, extraParams: Map<String, String> = emptyMap()): String {
        val builder = base.toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("art[url]", "f")
            .addQueryParameter("fields[albums]", "name,url,editorialVideo,motionArtwork,editorialArtwork")
            .addQueryParameter("fields[songs]", "name,url,isrc,editorialVideo,motionArtwork,editorialArtwork,hasLyrics")
            .addQueryParameter("fields[artists]", "name,url")
            .addQueryParameter("format[resources]", "map")
            .addQueryParameter("include[songs]", "albums,artists")
            .addQueryParameter("l", "en-GB")
            .addQueryParameter("omit[resource]", "autos")
            .addQueryParameter("platform", "web")
        for ((k, v) in extraParams) builder.addQueryParameter(k, v)
        return builder.build().toString()
    }

    /** Builds an authenticated AMP API request (headers mirrored from the working reference impl). */
    internal fun ampRequest(url: String, token: String): Request =
        Request.Builder()
            .url(url)
            .get()
            .header("Authorization", "Bearer $token")
            .header("Origin", "https://music.apple.com")
            .header("Referer", "https://music.apple.com/")
            .header("User-Agent", USER_AGENT)
            .build()

    /**
     * ISRC-based lookup — the most accurate path. Only extracts motion for the
     * ISRC-matched song (and its direct album relationship). Does NOT fall
     * through to a broad scan of other resources in the response, which is what
     * caused wrong-track canvases.
     */
    internal fun fetchByIsrc(
        isrc: String,
        token: String,
        aspect: CanvasAspectPreference,
    ): Pair<AppleMusicCanvas?, JSONObject?> {
        val url = buildAmpUrl("$AMP_BASE/v1/catalog/$STOREFRONT/songs", mapOf("filter[isrc]" to isrc))

        val body = http.newCall(ampRequest(url, token)).execute().use { resp ->
            if (!resp.isSuccessful) return null to null
            resp.body?.string()
        } ?: return null to null

        val root = JSONObject(body)

        // Resolve the matched song ID from the response.
        val foundId = root.optJSONArray("data")?.optJSONObject(0)?.optString("id")?.takeIf { it.isNotBlank() }
            ?: root.optJSONObject("resources")?.optJSONObject("songs")?.keys()?.asSequence()?.firstOrNull()
            ?: return null to null

        // STRICT: only look at the exact song that matched this ISRC.
        val songItem = getResourceById(root, foundId) ?: return null to null

        // extractMotionFromData checks the matched song, then its directly
        // linked album, then broad-scans every other resource already
        // present in *this same* ISRC-filtered response (e.g. resources
        // pulled in via include[songs]=albums,artists) before giving up.
        // Still scoped to this one response payload — never a different
        // track from a search — so it can't attach the wrong canvas.
        extractMotionFromData(root, foundId, aspect)?.let {
            Timber.tag(TAG).d("AMP ISRC hit for $isrc -> $it")
            return AppleMusicCanvas(animated = it) to songItem
        }

        // ISRC matched but this track genuinely has no canvas. Still return
        // the matched songItem (non-null) so the caller can tell "matched,
        // no canvas" apart from "no ISRC match at all" and correctly avoid
        // falling through to search/fuzzy matching for a different track.
        Timber.tag(TAG).d("AMP ISRC match for $isrc but no motion artwork found")
        return null to songItem
    }

    /**
     * Song + artist search fallback — scores every search result against the
     * query (title + artist) and only returns a canvas from the best-matching
     * result. This prevents the "wrong song's canvas" bug where a different
     * track in the same album/playlist gets picked.
     *
     * Returns [SearchResult] with [SearchResult.transportFailed] true when the
     * HTTP call itself failed, so callers can distinguish "no match" (safe to
     * negative-cache) from transient errors (must retry next play).
     */
    internal data class SearchResult(
        val canvas: AppleMusicCanvas?,
        val item: JSONObject?,
        val tier: CanvasMatchTier?,
        val transportFailed: Boolean = false,
    )

    internal fun fetchBySearch(
        song: String,
        artist: String,
        durationSeconds: Int?,
        token: String,
        aspect: CanvasAspectPreference,
    ): SearchResult {
        val url = buildAmpUrl(
            "$AMP_BASE/v1/catalog/$STOREFRONT/search",
            mapOf("term" to "$song $artist", "types" to "songs", "limit" to "10"),
        )

        val body = runCatching {
            http.newCall(ampRequest(url, token)).execute().use { resp ->
                if (!resp.isSuccessful) return SearchResult(null, null, CanvasMatchTier.FUZZY, transportFailed = true)
                resp.body?.string()
            }
        }.getOrNull() ?: return SearchResult(null, null, CanvasMatchTier.FUZZY, transportFailed = true)

        val root = JSONObject(body)

        // Collect all song results from the search response so we can score
        // them rather than blindly taking the first with a motion video.
        val candidates = collectSearchSongs(root)
        if (candidates.isEmpty()) return SearchResult(null, null, CanvasMatchTier.FUZZY)

        val best = pickBestCandidate(candidates, song, artist, durationSeconds)
            ?: return SearchResult(null, null, CanvasMatchTier.FUZZY)

        // Tier 2 (catalog-ID style confirmation): the search result itself
        // carries an ISRC that matches what we already know about the
        // track (title/artist exact after normalization). Tier 3: title +
        // artist normalize to an exact match without an ISRC to confirm it.
        // Anything weaker that still cleared pickBestCandidate's score
        // threshold is Tier 4 (FUZZY).
        val bestAttrs = best.optJSONObject("attributes")
        val bestTitleNorm = normalize(bestAttrs?.optString("name").orEmpty())
        val bestArtistNorm = normalize(bestAttrs?.optString("artistName").orEmpty())
        val tier = when {
            bestTitleNorm == normalize(song) && bestArtistNorm == normalize(artist) ->
                CanvasMatchTier.ALBUM_ARTIST_TITLE
            else -> CanvasMatchTier.FUZZY
        }

        val motion = searchItem(best, aspect)
        if (motion != null) {
            val bestTitle = bestAttrs?.optString("name") ?: "?"
            Timber.tag(TAG).d("Search validated hit: \"$song\" -> matched \"$bestTitle\" -> $motion")
            return SearchResult(AppleMusicCanvas(animated = motion), best, tier)
        }

        // Album-level canvas for the best match's album.
        val albumId = best.optJSONObject("relationships")
            ?.optJSONObject("albums")?.optJSONArray("data")
            ?.optJSONObject(0)?.optString("id")
        if (!albumId.isNullOrBlank()) {
            val album = getResourceById(root, albumId)
            if (album != null) {
                val albumMotion = searchItem(album, aspect)
                if (albumMotion != null) {
                    Timber.tag(TAG).d("Album canvas hit for \"$song\" via album $albumId -> $albumMotion")
                    return SearchResult(AppleMusicCanvas(animated = albumMotion), best, tier)
                }
            }
        }
        return SearchResult(null, best, tier)
    }

    /** Extracts all song JSONObjects from a search response (handles both map and array formats). */
    private fun collectSearchSongs(root: JSONObject): List<JSONObject> {
        val results = mutableListOf<JSONObject>()

        // format[resources]=map nesting: results.songs.data[]
        root.optJSONObject("results")?.optJSONObject("songs")?.let { songs ->
            songs.optJSONArray("data")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { results.add(it) }
                }
            }
            songs.optJSONObject("resources")?.optJSONObject("songs")?.let { bucket ->
                for (key in bucket.keys()) {
                    bucket.optJSONObject(key)?.let { results.add(it) }
                }
            }
        }

        // Top-level resources map
        root.optJSONObject("resources")?.optJSONObject("songs")?.let { bucket ->
            for (key in bucket.keys()) {
                bucket.optJSONObject(key)?.let { results.add(it) }
            }
        }

        // Flat data array
        root.optJSONArray("data")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { results.add(it) }
            }
        }

        return results
    }

    /**
     * Scores search candidates against the query to find the most-likely-correct
     * match. Uses normalized title + artist similarity. Returns null if no
     * candidate scores high enough (avoids returning a canvas for a different song).
     */
    private fun pickBestCandidate(
        candidates: List<JSONObject>,
        querySong: String,
        queryArtist: String,
        queryDurationSeconds: Int?,
    ): JSONObject? {
        data class Scored(val item: JSONObject, val score: Int)

        val normQuerySong = normalize(querySong)
        val normQueryArtist = normalize(queryArtist)

        var best: Scored? = null
        for (candidate in candidates) {
            val attrs = candidate.optJSONObject("attributes") ?: continue
            val title = normalize(attrs.optString("name"))
            if (title.isBlank()) continue

            val artistName = normalize(attrs.optString("artistName"))

            var score = 0

            // Title matching
            score += when {
                title == normQuerySong -> 100
                title.contains(normQuerySong) || normQuerySong.contains(title) -> 80
                else -> {
                    val queryWords = normQuerySong.split(" ").filter { it.length > 3 }
                    if (queryWords.isNotEmpty()) {
                        val matchCount = queryWords.count { title.contains(it) }
                        (matchCount.toFloat() / queryWords.size * 60).toInt()
                    } else 0
                }
            }

            // Artist matching
            if (artistName.isNotBlank()) {
                score += when {
                    artistName == normQueryArtist -> 60
                    artistName.contains(normQueryArtist) || normQueryArtist.contains(artistName) -> 40
                    else -> {
                        val artistWords = normQueryArtist.split(" ").filter { it.length > 3 }
                        if (artistWords.isNotEmpty()) {
                            val matchCount = artistWords.count { artistName.contains(it) }
                            (matchCount.toFloat() / artistWords.size * 30).toInt()
                        } else -20
                    }
                }
            }

            // Duration verification — catches remixes/live versions with same name.
            // A big diff is only trusted as "wrong song" when title/artist aren't
            // already an exact match — otherwise it's more likely a radio edit,
            // remaster, or rip with extra silence, and shouldn't nuke a correct hit.
            // Unknown durations (YTM reports -1) skip verification entirely.
            if (queryDurationSeconds != null && queryDurationSeconds > 30) {
                val trackDurMs = attrs.optLong("durationInMillis")
                if (trackDurMs > 30_000) {
                    val diff = kotlin.math.abs(queryDurationSeconds * 1000L - trackDurMs)
                    val exactTitleAndArtist = title == normQuerySong && artistName == normQueryArtist
                    when {
                        diff < 4_000 -> score += 60
                        diff < 12_000 -> score += 30
                        diff > 25_000 -> score -= if (exactTitleAndArtist) 20 else 80
                    }
                }
            }

            // Minimum confidence: 55. Title-exact (100) + artist-contains (40)
            // clears it without duration; word-overlap + artist-exact can too.
            // 70 was systematically rejecting cleaned YTM queries whose only
            // evidence is title-words + artist-exact and no duration bonus.
            if (score >= 55 && (best == null || score > best!!.score)) {
                best = Scored(candidate, score)
            }
        }
        return best?.item
    }

    private val STRIP_REGEX = Regex(
        """\s*(\(|\[)[^)\]]*(remaster|edition|version|feat\.?|ft\.?|with|prod\.?|explicit|clean|live|expanded|single|ep)[^)\]]*(\)|\]).*""",
        RegexOption.IGNORE_CASE
    )

    private fun normalize(s: String): String {
        // Clean YTM video junk + "- Topic" first so both query and catalog
        // sides compare the same form; idempotent for clean titles.
        var result = CanvasQueryCleaner.cleanArtist(CanvasQueryCleaner.cleanTitle(s)).lowercase()
        // Strip common suffixes in parentheses/brackets
        result = result.replace(STRIP_REGEX, "")
        // Remove special chars and normalize spaces
        return result.replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
    }

    // ---------- Response parsing ----------
    //
    // AMP responses requested with format[resources]=map nest everything under
    // resources.<type>.<id> instead of a flat `data` array, and included/related
    // resources can show up in several different places depending on the
    // endpoint (catalog lookup vs search vs relationship). This walks all of
    // them defensively, same as the confirmed-working reference implementation.

    private fun isVideoUrl(value: String?): Boolean =
        value != null && value.startsWith("http") && VIDEO_URL_REGEX.containsMatchIn(value)

    /** Recursively hunts an attribute subtree for the best-matching motion video URL. */
    private fun pickBestVideo(obj: JSONObject?, aspect: CanvasAspectPreference): String? {
        if (obj == null) return null

        val primaryKey = if (aspect == CanvasAspectPreference.SQUARE) "motionDetailSquare" else "motionDetailTall"
        val secondaryKey = if (aspect == CanvasAspectPreference.SQUARE) "motionSquareVideo1x1" else "motionTallVideo3x4"
        val oppositePrimaryKey = if (aspect == CanvasAspectPreference.SQUARE) "motionDetailTall" else "motionDetailSquare"
        val oppositeSecondaryKey = if (aspect == CanvasAspectPreference.SQUARE) "motionTallVideo3x4" else "motionSquareVideo1x1"

        val hq = obj.optJSONObject(primaryKey)?.optString("video")?.takeIf { isVideoUrl(it) }
            ?: obj.optJSONObject(secondaryKey)?.optString("video")?.takeIf { isVideoUrl(it) }
            ?: obj.optJSONObject(oppositePrimaryKey)?.optString("video")?.takeIf { isVideoUrl(it) }
            ?: obj.optJSONObject(oppositeSecondaryKey)?.optString("video")?.takeIf { isVideoUrl(it) }
            ?: obj.optJSONObject("motionArtistSquare1x1")?.optString("video")?.takeIf { isVideoUrl(it) }
            ?: obj.optString("video").takeIf { isVideoUrl(it) }
            ?: obj.optString("url").takeIf { isVideoUrl(it) }
        if (hq != null) return hq

        val keys = obj.keys()
        for (k in keys) {
            val child = obj.opt(k)
            if (child is JSONObject) {
                pickBestVideo(child, aspect)?.let { return it }
            }
        }
        return null
    }

    private val MOTION_ATTRIBUTE_FIELDS = listOf(
        "editorialVideo", "motionArtwork", "editorialArtwork",
        "motionArtwork1x1", "motionArtworkTall",
        "motionDetailSquare", "motionDetailTall", "motionVideo",
    )

    private fun searchItem(item: JSONObject?, aspect: CanvasAspectPreference): String? {
        val attrs = item?.optJSONObject("attributes") ?: return null
        for (field in MOTION_ATTRIBUTE_FIELDS) {
            pickBestVideo(attrs.optJSONObject(field), aspect)?.let { return it }
        }
        return null
    }

    private fun getResourceById(root: JSONObject, id: String?): JSONObject? {
        if (id.isNullOrBlank()) return null

        // 1. resources map (format[resources]=map)
        root.optJSONObject("resources")?.let { resources ->
            for (type in resources.keys()) {
                resources.optJSONObject(type)?.optJSONObject(id)?.let { return it }
            }
        }

        // 2. classic data array/object
        val dataAny = root.opt("data")
        if (dataAny is JSONArray) {
            for (i in 0 until dataAny.length()) {
                val item = dataAny.optJSONObject(i)
                if (item?.optString("id") == id) return item
            }
        } else if (dataAny is JSONObject && dataAny.optString("id") == id) {
            return dataAny
        }

        // 3. included array
        root.optJSONArray("included")?.let { included ->
            for (i in 0 until included.length()) {
                val item = included.optJSONObject(i)
                if (item?.optString("id") == id) return item
            }
        }

        // 4. results (search responses)
        root.optJSONObject("results")?.let { results ->
            for (type in results.keys()) {
                val typeObj = results.optJSONObject(type) ?: continue
                typeObj.optJSONObject("resources")?.let { res ->
                    for (resType in res.keys()) {
                        res.optJSONObject(resType)?.optJSONObject(id)?.let { return it }
                    }
                }
                typeObj.optJSONArray("data")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i)
                        if (item?.optString("id") == id) return item
                    }
                }
            }
        }

        return null
    }

    private val RESOURCE_SCAN_TYPES = listOf("songs", "albums", "playlists", "music-videos")

    /**
     * Finds the best motion video URL in an AMP response, preferring [targetId]
     * (and its related albums) before falling back to scanning every resource
     * in the payload.
     */
    private fun extractMotionFromData(
        root: JSONObject,
        targetId: String?,
        aspect: CanvasAspectPreference,
    ): String? {
        // 1. Target priority
        if (!targetId.isNullOrBlank()) {
            val item = getResourceById(root, targetId)
            if (item != null) {
                searchItem(item, aspect)?.let { return it }

                if (item.optString("type") == "songs") {
                    val albumRefs = item.optJSONObject("relationships")
                        ?.optJSONObject("albums")?.optJSONArray("data")
                    if (albumRefs != null) {
                        for (i in 0 until albumRefs.length()) {
                            val albumId = albumRefs.optJSONObject(i)?.optString("id")
                            val album = getResourceById(root, albumId)
                            if (album != null) {
                                searchItem(album, aspect)?.let { return it }
                            }
                        }
                    }
                }
            }
        }

        // 2. Broad scan of resources map
        root.optJSONObject("resources")?.let { resources ->
            for (type in RESOURCE_SCAN_TYPES) {
                val bucket = resources.optJSONObject(type) ?: continue
                for (id in bucket.keys()) {
                    searchItem(bucket.optJSONObject(id), aspect)?.let { return it }
                }
            }
        }

        // 3. included array directly
        root.optJSONArray("included")?.let { included ->
            for (i in 0 until included.length()) {
                searchItem(included.optJSONObject(i), aspect)?.let { return it }
            }
        }

        // 4. results (search responses), recursive
        root.optJSONObject("results")?.let { results ->
            for (type in results.keys()) {
                val sub = results.optJSONObject(type) ?: continue
                extractMotionFromData(sub, targetId, aspect)?.let { return it }
            }
        }

        return null
    }
}