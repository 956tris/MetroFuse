/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.apple

import com.metrolist.music.providers.IsrcResolver
import com.metrolist.music.providers.ProviderIsrc
import com.metrolist.music.utils.CanvasQueryCleaner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
 * Auth uses a hardcoded Apple Music JWT (see [HARDCODED_JWT]).
 * Results are cached in-memory by ISRC (or song+artist if no ISRC available).
 *
 * NOTE: The [AppleMusicCanvas.animated] URL is an HLS m3u8 stream — if
 * [downloadCanvas] expects a direct MP4 byte-stream, you will need to resolve
 * the highest-quality segment playlist and reassemble the fMP4 fragments.
 */
object AppleMusicCanvasProvider {

    private const val TAG = "AppleMusicCanvasProvider"

    // Hardcoded Apple Music JWT for AMP API search + animated covers.
    private const val HARDCODED_JWT =
        "eyJ0eXAiOiJKV1QiLCJhbGciOiJFUzI1NiIsImtpZCI6IldlYlBsYXlLaWQifQ.eyJpc3MiOiJBTVBXZWJQbGF5IiwiaWF0IjoxNzg5Njg4NzA5LCJleHAiOjE3OTU3MzY3MDksInJvb3RfaHR0cHNfb3JpZ2luIjpbImFwcGxlLmNvbSJdfQ.y0gd6YWyrUrZx-YZNZS0xVHkDHGr-kGZ9RrsWRfApGc2-_NNC968VsD36hRU33s5BBs4KdB7LIZTmYqPra097Q"

    private const val AMP_BASE = "https://amp-api.music.apple.com"
    private const val STOREFRONT = "us"

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

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

    // ---------- HTTP client ----------

    private val http = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    // ---------- Matching debug ----------

    private const val DEBUG_MATCHING = true

    /** Title/artist matches only count as duration-confirmed within this delta. */
    private const val DURATION_CONFIRM_TOLERANCE_MS = 3_000L

    /** Minimum score a search candidate must reach to be accepted. */
    private const val MIN_ACCEPT_SCORE = 80

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
        val key = cacheKey(isrc, song, artist, preferredAspect, durationSeconds)
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
        val key = cacheKey(isrc, cleanSong, cleanArtist, preferredAspect, validDuration)
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

            // Matching strategy (AMP is the source of truth — its search
            // responses already carry motion data):
            // - A synchronously-known ISRC (caller tag, harvested or
            //   disk-learned cache hit) takes the precise ISRC-first route
            //   with zero discovery cost.
            // - Otherwise slow ISRC discovery races the direct AMP search;
            //   a confident search hit needs no ISRC round-trip, while a
            //   landed ISRC only ever confirms via fetchByIsrc.
            // A caller-supplied ISRC that fails ProviderIsrc's shape check
            // is treated as absent, never passed through.
            var resolvedIsrc: String? = ProviderIsrc.normalize(isrc)
                ?: IsrcResolver.peek(cleanSong, cleanArtist, validDuration)
            var searchTransportFailed = false

            // Tier 1: exact ISRC match. Returns (canvas, ISRC_EXACT, item)
            // on a hit, (null, null, item) + variant hunt when the ISRC
            // identifies the right recording but it carries no canvas (the
            // same recording may exist under a variant ISRC that does),
            // (null, null, item-or-null) when the ISRC matches nothing or
            // points at a different artist's track (fall through to search).
            var huntVariants = false
            fun tier1(activeToken: String): Triple<AppleMusicCanvas?, CanvasMatchTier?, JSONObject?> {
                val current = resolvedIsrc ?: return Triple(null, null, null)
                val (canvasResult, songItem) = fetchByIsrc(current, activeToken, preferredAspect)
                if (canvasResult != null) return Triple(canvasResult, CanvasMatchTier.ISRC_EXACT, songItem)
                if (songItem != null) {
                    val matchedArtist =
                        normalize(songItem.optJSONObject("attributes")?.optString("artistName").orEmpty())
                    val wantArtist = normalize(cleanArtist)
                    if (matchedArtist.isBlank() || matchedArtist == wantArtist ||
                        matchedArtist.contains(wantArtist) || wantArtist.contains(matchedArtist)
                    ) {
                        // Right recording, no canvas on this ISRC variant —
                        // hunt the same recording under its sibling ISRCs
                        // instead of stopping or fuzzy-matching neighbors.
                        huntVariants = true
                        return Triple(null, null, songItem)
                    }
                    // Resolver ISRC belongs to another artist's recording
                    // (common for single-word YTM titles) — drop it and let
                    // the scored search pick the right track instead of
                    // hard-blocking on the wrong one.
                    Timber.tag(TAG).w("ISRC $current matched artist \"$matchedArtist\", want \"$cleanArtist\" — ignoring ISRC")
                    resolvedIsrc = null
                }
                return Triple(null, null, songItem)
            }

            // Tier 3/4: scored catalog search fallback (variant-constrained
            // when Tier 1 identified the right recording without canvas).
            fun searchLeg(
                activeToken: String,
                variantHunt: Boolean = false,
            ): Triple<AppleMusicCanvas?, CanvasMatchTier?, JSONObject?> {
                // Pass the ISRC we already hold so the scorer can confirm the
                // exact catalog entry (accept immediately) or require variant
                // proof (exact + duration) for a differing ISRC.
                val search = fetchBySearch(cleanSong, cleanArtist, validDuration, resolvedIsrc, activeToken, preferredAspect, variantHunt)
                if (search.transportFailed) searchTransportFailed = true
                // Raw-title retry: if cleaning overshot (rare), one attempt
                // with the original query before giving up.
                if (search.canvas == null && search.item == null &&
                    (cleanSong != song || cleanArtist != artist)
                ) {
                    val retry = fetchBySearch(song, artist, validDuration, resolvedIsrc, activeToken, preferredAspect, variantHunt)
                    if (retry.transportFailed) searchTransportFailed = true
                    return Triple(retry.canvas, retry.tier, retry.item)
                }
                return Triple(search.canvas, search.tier, search.item)
            }

            fun indexedHit(isrc: String): Triple<AppleMusicCanvas?, CanvasMatchTier?, JSONObject?>? =
                CanvasIndex.getByIsrc(isrc)?.let { indexed ->
                    logMatchDecision(cleanSong, cleanArtist, isrc, indexed.matchTier, indexed.confidence, indexed.title)
                    val hit = AppleMusicCanvas(animated = indexed.sourceUrl)
                    cache[key] = hit
                    Triple(hit, indexed.matchTier, null)
                }

            suspend fun runRound(activeToken: String): Triple<AppleMusicCanvas?, CanvasMatchTier?, JSONObject?> {
                huntVariants = false
                // Precise leg first whenever an ISRC is already in hand.
                resolvedIsrc?.let { isrc ->
                    indexedHit(isrc)?.let { return it }
                    val t1 = tier1(activeToken)
                    if (t1.first != null || t1.second == CanvasMatchTier.ISRC_EXACT) return t1
                    return searchLeg(activeToken, huntVariants)
                }

                // No ISRC yet: race slow discovery against the direct AMP
                // search instead of paying both sequentially.
                val (discovered, search) = coroutineScope {
                    val isrcD = async { IsrcResolver.resolveAndValidate(null, cleanSong, cleanArtist, validDuration) }
                    val searchD = async { searchLeg(activeToken) }
                    isrcD.await() to searchD.await()
                }
                if (discovered != null) {
                    Timber.tag(TAG).d("Using resolver ISRC $discovered for \"$cleanSong\" by $cleanArtist")
                    resolvedIsrc = discovered
                    indexedHit(discovered)?.let { return it }
                    val t1 = tier1(activeToken)
                    if (t1.first != null || t1.second == CanvasMatchTier.ISRC_EXACT) return t1
                }
                // Search result was already awaited above — zero extra wait.
                return search
            }

            var (canvas, tier, matchedItem) = runRound(token)

            // An empty result might mean the cached token is stale/rejected
            // server-side, not that Apple has no canvas. Force-refresh +
            // retry (discovery hits memory cache on retry, so this costs
            // one search).
            if (canvas == null && tier != CanvasMatchTier.ISRC_EXACT && !searchTransportFailed) {
                token = getToken(forceRefresh = true) ?: token
                val retry = runRound(token)
                canvas = retry.first
                tier = retry.second
                matchedItem = retry.third
            }

            val matchedAttrs = matchedItem?.optJSONObject("attributes")
            val matchedTitle = matchedAttrs?.optString("name")
            val matchedCatalogId = matchedItem?.optString("id")?.takeIf { it.isNotBlank() }
            val matchedIsrc = ProviderIsrc.normalize(matchedAttrs?.optString("isrc"))

            // Duration agreement between the queried track and the matched
            // catalog entry — the only evidence that can confirm a text-only
            // match as the same recording.
            val matchedDurationMs = matchedAttrs?.optLong("durationInMillis")?.takeIf { it > 30_000L }
            val durationDeltaMs =
                if (validDuration != null && matchedDurationMs != null) {
                    kotlin.math.abs(validDuration * 1000L - matchedDurationMs)
                } else {
                    null
                }
            val durationConfirmed = durationDeltaMs != null && durationDeltaMs <= DURATION_CONFIRM_TOLERANCE_MS

            // A match may only teach the shared ISRC map / CanvasIndex when it
            // is precise: either the ISRC itself confirmed the catalog entry,
            // or title + artist + duration agree. A FUZZY/metadata-only hit
            // must never publish, or a wrong ISRC poisons every later
            // ISRC-first lookup and gets persisted to disk.
            val confirmedTier: CanvasMatchTier? =
                tier?.takeIf { it == CanvasMatchTier.ISRC_EXACT || it == CanvasMatchTier.ISRC_VARIANT || durationConfirmed }
            val publishableIsrc = when {
                tier == CanvasMatchTier.ISRC_EXACT -> matchedIsrc ?: resolvedIsrc
                tier == CanvasMatchTier.ISRC_VARIANT -> matchedIsrc
                confirmedTier != null -> matchedIsrc
                else -> null
            }

            logMatchDecision(cleanSong, cleanArtist, resolvedIsrc, tier, tier?.baseConfidence ?: 0, matchedTitle)

            if (canvas != null) {
                cache[key] = canvas
                negativeCache.remove(key)
                // Harvest: a confirmed canvas match teaches the shared ISRC
                // map, so YTM-frontend plays skip discovery next time.
                runCatching {
                    publishableIsrc?.let { IsrcResolver.publish(cleanSong, cleanArtist, it, validDuration) }
                }
                if (confirmedTier != null) {
                    CanvasIndex.put(
                        CanvasMatchEntry(
                            isrc = publishableIsrc,
                            appleCatalogId = matchedCatalogId,
                            title = matchedTitle ?: cleanSong,
                            artist = cleanArtist,
                            album = null,
                            durationMs = validDuration?.toLong()?.times(1000L),
                            sourceUrl = canvas.animated.orEmpty(),
                            matchTier = confirmedTier,
                            confidence = confirmedTier.baseConfidence,
                            lastMatchedAtMs = System.currentTimeMillis(),
                        ),
                    )
                    // Variant win: also index under the queried (variant)
                    // ISRC so the next play skips straight to the canvas via
                    // indexedHit instead of re-hunting.
                    if (tier == CanvasMatchTier.ISRC_VARIANT && resolvedIsrc != null &&
                        !ProviderIsrc.equal(resolvedIsrc, publishableIsrc)
                    ) {
                        CanvasIndex.put(
                            CanvasMatchEntry(
                                isrc = resolvedIsrc,
                                appleCatalogId = matchedCatalogId,
                                title = matchedTitle ?: cleanSong,
                                artist = cleanArtist,
                                album = null,
                                durationMs = validDuration?.toLong()?.times(1000L),
                                sourceUrl = canvas.animated.orEmpty(),
                                matchTier = confirmedTier,
                                confidence = confirmedTier.baseConfidence,
                                lastMatchedAtMs = System.currentTimeMillis(),
                            ),
                        )
                    }
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
    internal fun searchIsrcOnly(
        song: String,
        artist: String,
        durationSeconds: Int?,
        token: String,
        knownIsrc: String? = null,
    ): String? {
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
        val best = pickBestCandidate(candidates, cleanSong, cleanArtist, durationSeconds, knownIsrc) ?: return null
        return ProviderIsrc.normalize(best.optJSONObject("attributes")?.optString("isrc"))
    }

    // ---------- Internal ----------

    private fun cacheKey(
        isrc: String?,
        song: String,
        artist: String,
        aspect: CanvasAspectPreference,
        durationSeconds: Int?,
    ): String {
        // When no ISRC is available the song+artist key is additionally scoped
        // by duration bucket so a remix/edit of the same name can't be served
        // the original recording's cached canvas.
        val fallback = "$song\u001F$artist\u001F${durationBucket(durationSeconds)}"
        return ((isrc?.takeIf { it.isNotBlank() } ?: fallback) + "\u001F$aspect").lowercase()
    }

    /** 15s duration window, matching [com.metrolist.music.providers.IsrcResolver]. */
    private fun durationBucket(durationSeconds: Int?): Int =
        durationSeconds?.takeIf { it > 30 }?.let { it / 15 } ?: 0

    /**
     * Returns the hardcoded Apple Music JWT so other components can reuse it
     * instead of duplicating the auth flow. Used by [com.metrolist.music.providers.IsrcResolver] to
     * validate ISRCs against the Apple Music catalog.
     */
    internal suspend fun borrowToken(forceRefresh: Boolean = false): String? =
        getToken(forceRefresh)

    /** Returns the hardcoded Apple Music Bearer token for the AMP API. */
    internal suspend fun getToken(forceRefresh: Boolean = false): String? = HARDCODED_JWT

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

        // extractMotionFromData only inspects the matched song and its
        // directly linked album relationships — never a broad scan of the
        // other resources the response happens to include, which is what
        // previously attached an unrelated track's canvas.
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
        knownIsrc: String?,
        token: String,
        aspect: CanvasAspectPreference,
        variantHunt: Boolean = false,
    ): SearchResult {
        val url = buildAmpUrl(
            "$AMP_BASE/v1/catalog/$STOREFRONT/search",
            mapOf("term" to "$song $artist", "types" to "songs", "limit" to "25"),
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

        val best = pickBestCandidate(candidates, song, artist, durationSeconds, knownIsrc, variantHunt)
            ?: return SearchResult(null, null, CanvasMatchTier.FUZZY)

        // Tier 2 (ISRC confirmation): the picked result carries the same ISRC
        // we already know for this track — the strongest possible evidence.
        // ISRC_VARIANT: same recording re-issued under a different ISRC —
        // accepted only with exact title+artist and duration-confirmed same
        // recording (enforced by pickBestCandidate, rechecked here).
        // ALBUM_ARTIST_TITLE: title + artist normalize to an exact match.
        // Anything weaker that still cleared pickBestCandidate's gates is
        // Tier 4 (FUZZY).
        val bestAttrs = best.optJSONObject("attributes")
        val bestTitleNorm = normalize(bestAttrs?.optString("name").orEmpty())
        val bestArtistNorm = normalize(bestAttrs?.optString("artistName").orEmpty())
        val bestIsrc = ProviderIsrc.normalize(bestAttrs?.optString("isrc"))
        val bestDurMs = bestAttrs?.optLong("durationInMillis") ?: 0L
        val queryDurMs = durationSeconds?.takeIf { it > 30 }?.toLong()?.times(1000L)
        val variantConfirmed = bestIsrc != null && knownIsrc != null &&
            !ProviderIsrc.equal(bestIsrc, knownIsrc) &&
            bestTitleNorm == normalize(song) && bestArtistNorm == normalize(artist) &&
            queryDurMs != null && bestDurMs > 30_000L &&
            kotlin.math.abs(queryDurMs - bestDurMs) <= DURATION_CONFIRM_TOLERANCE_MS
        val tier = when {
            ProviderIsrc.equal(bestIsrc, knownIsrc) ->
                CanvasMatchTier.ISRC_EXACT
            variantConfirmed ->
                CanvasMatchTier.ISRC_VARIANT
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
     * A scored search candidate. Ordered for deterministic tie-breaking:
     * artist score first, then smallest duration delta. ISRC confirmation is
     * handled by an immediate early return (see [pickBestCandidate]).
     */
    private data class ScoredCandidate(
        val item: JSONObject,
        val score: Int,
        val artistScore: Int,
        val durationDeltaMs: Long?,
    )

    /**
     * Scores search candidates against the query and returns the single
     * best-matching item, or null when nothing clears the confidence gates.
     *
     * Gates, in order:
     *  1. ISRC — a candidate carrying [knownIsrc] wins immediately; a
     *     candidate carrying a *different* ISRC is accepted only as an
     *     ISRC_VARIANT (exact title+artist, duration-confirmed same
     *     recording — same track re-issued under another ISRC), and only
     *     then; anything weaker with a contradicting ISRC is rejected.
     *     In [variantHunt] mode (Tier 1 found the right recording with no
     *     canvas) only such variant-confirmed candidates may win at all.
     *  2. Artist — at least one artist token must match, so a same-titled
     *     track by another artist can never be selected.
     *  3. Variant — a cover/remix/karaoke/tribute/live candidate is rejected
     *     unless the query is that variant too, or its duration agrees within
     *     [DURATION_CONFIRM_TOLERANCE_MS].
     *  4. Score must reach [MIN_ACCEPT_SCORE].
     *
     * Remaining ties break on artist score, then smallest duration delta; a
     * full tie keeps the first candidate (stable, deterministic).
     */
    private fun pickBestCandidate(
        candidates: List<JSONObject>,
        querySong: String,
        queryArtist: String,
        queryDurationSeconds: Int?,
        knownIsrc: String?,
        variantHunt: Boolean = false,
    ): JSONObject? {
        val normQuerySong = normalize(querySong)
        val normQueryArtist = normalize(queryArtist)
        val queryDurationMs = queryDurationSeconds?.takeIf { it > 30 }?.toLong()?.times(1000L)
        val queryIsVariant =
            CanvasQueryCleaner.hasVariantMarker(querySong) || CanvasQueryCleaner.hasVariantMarker(queryArtist)

        var best: ScoredCandidate? = null
        for (candidate in candidates) {
            val attrs = candidate.optJSONObject("attributes") ?: continue
            val rawTitle = attrs.optString("name")
            val title = normalize(rawTitle)
            if (title.isBlank()) continue

            val rawArtist = attrs.optString("artistName")
            val artistName = normalize(rawArtist)

            val exactTitleArtist = title == normQuerySong && artistName == normQueryArtist
            val trackDurMs = attrs.optLong("durationInMillis")
            val durationDeltaMs: Long? =
                if (queryDurationMs != null && trackDurMs > 30_000) {
                    kotlin.math.abs(queryDurationMs - trackDurMs)
                } else {
                    null
                }
            val durationConfirms =
                durationDeltaMs != null && durationDeltaMs <= DURATION_CONFIRM_TOLERANCE_MS

            // ---- ISRC gate ------------------------------------------------
            val candidateIsrc = ProviderIsrc.normalize(attrs.optString("isrc"))
            // Exact confirmation of a known ISRC accepts this candidate now.
            if (knownIsrc != null && ProviderIsrc.equal(candidateIsrc, knownIsrc)) return candidate
            // Same recording re-issued under a different ISRC: acceptable
            // only on exact title+artist with duration-confirmed same
            // recording. Anything weaker stays rejected — a bare ISRC
            // mismatch must never pull in a different track.
            if (knownIsrc != null && candidateIsrc != null && !durationConfirms) continue
            if (knownIsrc != null && candidateIsrc != null && !exactTitleArtist) continue
            // Variant hunt (known ISRC hit a canvas-less entry): only an
            // exact, duration-confirmed same recording may win — never a
            // fuzzy/neighboring track.
            if (variantHunt && !(exactTitleArtist && durationConfirms)) continue

            // ---- Artist gate ----------------------------------------------
            if (!artistTokensMatch(normQueryArtist, artistName)) continue

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

            // Artist matching (the gate above guarantees a non-zero match;
            // the exact figure only feeds tie-breaking and the final score).
            val artistScore = when {
                artistName.isBlank() -> 0
                artistName == normQueryArtist -> 60
                artistName.contains(normQueryArtist) || normQueryArtist.contains(artistName) -> 40
                else -> {
                    val artistWords = normQueryArtist.split(" ").filter { it.length > 3 }
                    if (artistWords.isNotEmpty()) {
                        val matchCount = artistWords.count { artistName.contains(it) }
                        (matchCount.toFloat() / artistWords.size * 30).toInt()
                    } else 0
                }
            }
            score += artistScore

            // Duration verification — catches remixes/live versions with same name.
            // A big diff is only trusted as "wrong song" when title/artist aren't
            // already an exact match — otherwise it's more likely a radio edit,
            // remaster, or rip with extra silence, and shouldn't nuke a correct hit.
            // Unknown durations (YTM reports -1) skip verification entirely.
            // (durationDeltaMs computed up-front for the ISRC gate; reused here.)
            if (durationDeltaMs != null) {
                when {
                    durationDeltaMs < 4_000 -> score += 60
                    durationDeltaMs < 12_000 -> score += 30
                    durationDeltaMs > 25_000 -> score -= if (exactTitleArtist) 20 else 80
                }
            }

            // ---- Variant gate ---------------------------------------------
            // A remix/cover/live/karaoke/tribute result is a different
            // recording (different canvas): only accept when the query asked
            // for that variant, or duration confirms the same recording.
            val candidateIsVariant =
                CanvasQueryCleaner.hasVariantMarker(rawTitle) || CanvasQueryCleaner.hasVariantMarker(rawArtist)
            val durationConfirmsVariant =
                durationDeltaMs != null && durationDeltaMs <= DURATION_CONFIRM_TOLERANCE_MS
            if (candidateIsVariant && !queryIsVariant && !durationConfirmsVariant) continue

            if (score < MIN_ACCEPT_SCORE) continue

            val scored = ScoredCandidate(candidate, score, artistScore, durationDeltaMs)
            val current = best
            if (current == null || isBetterMatch(scored, current)) best = scored
        }
        return best?.item
    }

    /**
     * True when at least one meaningful artist token from [normQueryArtist]
     * appears in [artistName]. Singleton/short names ("U2", "Sia") fall back
     * to a substring check; a blank query artist (nothing to match) passes.
     */
    private fun artistTokensMatch(normQueryArtist: String, artistName: String): Boolean {
        if (normQueryArtist.isBlank()) return true
        if (artistName.isBlank()) return false
        if (artistName == normQueryArtist) return true
        if (artistName.contains(normQueryArtist) || normQueryArtist.contains(artistName)) return true
        val tokens = normQueryArtist.split(" ").filter { it.length > 3 }
        return tokens.any { artistName.contains(it) }
    }

    /** Deterministic ordering: higher artist score, then smaller duration delta, then first-seen. */
    private fun isBetterMatch(candidate: ScoredCandidate, current: ScoredCandidate): Boolean {
        if (candidate.artistScore != current.artistScore) return candidate.artistScore > current.artistScore
        val candidateDelta = candidate.durationDeltaMs ?: Long.MAX_VALUE
        val currentDelta = current.durationDeltaMs ?: Long.MAX_VALUE
        return candidateDelta < currentDelta
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

    /**
     * Finds the motion video URL for the ISRC-matched song [targetId] and its
     * directly linked album relationships only. Deliberately does NOT scan
     * other resources present in the payload — that broad scan attached
     * unrelated tracks' canvases.
     */
    private fun extractMotionFromData(
        root: JSONObject,
        targetId: String?,
        aspect: CanvasAspectPreference,
    ): String? {
        if (targetId.isNullOrBlank()) return null
        val item = getResourceById(root, targetId) ?: return null

        // 1. The matched song itself.
        searchItem(item, aspect)?.let { return it }

        // 2. Only the album(s) directly linked from the matched song.
        if (item.optString("type") == "songs") {
            val albumRefs = item.optJSONObject("relationships")
                ?.optJSONObject("albums")?.optJSONArray("data")
            if (albumRefs != null) {
                for (i in 0 until albumRefs.length()) {
                    val albumId = albumRefs.optJSONObject(i)?.optString("id")
                    val album = getResourceById(root, albumId) ?: continue
                    searchItem(album, aspect)?.let { return it }
                }
            }
        }

        return null
    }
}