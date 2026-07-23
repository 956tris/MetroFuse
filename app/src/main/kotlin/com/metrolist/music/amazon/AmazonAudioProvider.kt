/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.amazon

import android.content.Context
import androidx.media3.common.MimeTypes
import com.metrolist.music.constants.AmazonSearchApiUrlKey
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.min

object AmazonAudioProvider {
    const val DEFAULT_SEARCH_API_URL = "https://na.web.skill.music.a2z.com/api/showSearch"
    const val DEFAULT_RESOLVE_API_URL = "https://t2tunes.site/api/amazon-music/media-from-asin"
    internal const val SKILL_BASE_URL = "https://na.mesk.skill.music.a2z.com/api"
    internal const val MUSIC_BASE_URL = "https://music.amazon.com"
    private const val STREAM_CACHE_MS = 30 * 60 * 1000L
    // Was compiled inline via Regex(...) at two call sites, once per item, across every item in
    // the (up to 1.4MB) response tree - hoisted to compile once instead.
    private val ARTIST_SPLIT_REGEX = Regex("[,&]| and | feat\\.? | ft\\.? ")
    const val BROWSER_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36"

    private var deviceId: String? = null
    private var sessionId: String? = null
    private var csrfToken: String = ""
    private var csrfTs: String = ""
    private var csrfRnd: String = ""
    private var appVersion: String = "1.0.10905.0"
    private var musicTerritory: String = "US"
    private var isInitialized = false
    private var sessionFetchedAtMs: Long = 0L
    private val initLock = Any()

    data class Query(
        val mediaId: String,
        val title: String,
        val artists: List<String>,
        val album: String?,
        val durationMs: Long?,
        val country: String = "US",
        val quality: String = "HI_RES",
        val explicit: Boolean? = null,
    )

    data class Resolved(
        val mediaUri: String,
        val trackId: String,
        val label: String,
        val mimeType: String,
        val codecs: String,
        val bitrate: Int,
        val sampleRate: Int?,
        val expiresAtMs: Long,
        val kid: String?,
        val decryptionKey: String? = null,
    )

    data class CandidateMetadata(
        val trackId: String,
        val title: String,
        val artist: String,
        val album: String?,
        val durationMs: Long?,
    )

    class AmazonResolutionException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private data class MatchedTrack(
        val asin: String,
        val title: String,
        val artists: List<String>,
        val album: String?,
        val durationMs: Long?,
    )

    private val cookieJar = object : CookieJar {
        private val storage = ConcurrentHashMap<String, List<Cookie>>()
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            storage[url.host] = cookies
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            return storage[url.host] ?: emptyList()
        }
    }

    internal val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .cookieJar(cookieJar)
        .build()

    private val trackCache = ConcurrentHashMap<String, MatchedTrack>()
    private val streamCache = ConcurrentHashMap<String, Resolved>()
    private val decryptionKeyCache = ConcurrentHashMap<String, String>()

    fun isAmazonCdnUrl(url: String): Boolean = url.contains("amazon") && (url.contains(".com") || url.contains(".net"))

    fun invalidate(mediaId: String) {
        trackCache.entries.removeIf { it.value.asin == mediaId || it.key.startsWith("$mediaId:") }
        streamCache.entries.removeIf { it.key.startsWith("$mediaId:") }
        AmazonFfmpegDecryptor.clearCache(mediaId)
    }

    suspend fun resolve(context: Context, query: Query): Resolved {
        val cacheKey = query.cacheKey()
        val now = System.currentTimeMillis()
        streamCache[cacheKey]
            ?.takeIf { it.expiresAtMs > now + 30_000L }
            ?.let { return it }

        // INVARIANT: anything passed into playback resolution must represent a real track,
        // never an album/artist/playlist container. Amazon will happily "resolve" a container
        // ASIN too, but silently defaults playback to that container's first track - which is
        // exactly the "always plays song 1" symptom this guards against. Fail loudly here
        // rather than letting a mis-tagged mediaId silently start the wrong song.
        query.mediaId.amazonContainerKindOrNull()?.let { containerKind ->
            Timber.e(
                "Amazon resolve() REJECTED: mediaId '%s' identifies a %s, not a track (title='%s')",
                query.mediaId, containerKind, query.title
            )
            throw AmazonResolutionException(
                "Cannot resolve playback for Amazon $containerKind '${query.mediaId}': a track ASIN is required"
            )
        }

        val asin = query.mediaId.toAmazonAsinOrNull()
        Timber.d("Amazon resolve(): mediaId=%s -> direct asin=%s (title='%s')", query.mediaId, asin, query.title)

        val track = if (asin != null) {
            Timber.d("Amazon resolve(): using mediaId's own ASIN as the track (fast path), asin=$asin")
            MatchedTrack(asin, query.title, query.artists, query.album, query.durationMs)
        } else {
            trackCache[query.trackCacheKey()]
                ?.also { Timber.d("Amazon resolve(): reusing cached matched track: $it") }
                ?: findBestTrack(context, query)
                    ?.also {
                        trackCache[query.trackCacheKey()] = it
                        Timber.d("Amazon resolve(): matched track via search: $it")
                    }
                ?: throw AmazonResolutionException("Amazon Music match not found for ${query.title}")
        }

        // Final playback decision: log exactly which ASIN is about to be resolved and played,
        // and against what the user actually clicked, so any future mismatch is immediately
        // visible in logs instead of only showing up as "the wrong song played".
        Timber.i(
            "Amazon FINAL playback decision: clicked title='%s' mediaId=%s -> resolving track asin=%s (track title='%s')",
            query.title, query.mediaId, track.asin, track.title
        )
        val resolved = resolveAsin(track.asin, query.mediaId, query.country, query.quality)
        streamCache[cacheKey] = resolved
        return resolved
    }

    private suspend fun findBestTrack(context: Context, query: Query): MatchedTrack? {
        val cleanTitle = query.title.cleanSearchTitle()
        val primaryArtist = query.artists.firstOrNull() ?: ""
        val album = query.album?.trim().orEmpty()

        val explicitSuffix = if (query.explicit == true) " Explicit" else ""

        val searchTerms = buildList {
            // Tried first: title + artist + album is the narrowest query Amazon accepts here,
            // and narrower queries return dramatically smaller responses (confirmed: ~250KB for
            // a generic "title artist" search vs. a much smaller payload once the album name is
            // included too, since Amazon has far fewer plausible matches to fan out widgets for).
            // Falls through to the broader terms below if this specific a match doesn't hit.
            if (album.isNotBlank() && primaryArtist.isNotBlank()) {
                add("$cleanTitle $primaryArtist $album$explicitSuffix".trim())
            }
            if (primaryArtist.isNotBlank()) add("$cleanTitle $primaryArtist$explicitSuffix".trim())
            add(cleanTitle)
            val rawTitle = query.title.cleanSearchTitle(keepParentheses = true)
            if (rawTitle.isNotBlank() && rawTitle != cleanTitle) add(rawTitle)
        }.distinct()

        for (searchTerm in searchTerms) {
            Timber.d("Amazon search trying term: $searchTerm")
            val results = searchTracks(context, searchTerm)
            Timber.d(
                "Amazon search term '$searchTerm' returned %d candidate(s)",
                results?.length() ?: -1
            )
            if (results != null && results.length() > 0) {
                Timber.d("Amazon search first candidate for '$searchTerm': %s", results.optJSONObject(0))
                selectBestTrack(results, query)?.let { return it }
            }
        }

        return null
    }

    suspend fun searchAll(context: Context, term: String, country: String = "US"): JSONArray {
        return searchTracks(context, term, country) ?: JSONArray()
    }

    suspend fun searchCandidates(context: Context, term: String, country: String = "US", limit: Int = 10): List<CandidateMetadata> {
        val results = searchTracks(context, term, country) ?: return emptyList()
        val candidates = mutableListOf<CandidateMetadata>()
        for (i in 0 until min(results.length(), limit)) {
            val obj = results.optJSONObject(i) ?: continue
            val asin = obj.optString("asin").takeIf { it.isNotEmpty() } ?: continue
            val title = obj.optString("title").takeIf { it.isNotEmpty() } ?: ""
            val artistsArr = obj.optJSONArray("artists")
            val artistName = artistsArr?.optJSONObject(0)?.optString("name")?.takeIf { it.isNotEmpty() }
                ?: obj.optString("artist").takeIf { it.isNotEmpty() }
                ?: ""
            val albumTitle = obj.optJSONObject("album")?.optString("title")?.takeIf { it.isNotEmpty() }
                ?: obj.optString("album").takeIf { it.isNotEmpty() }
                ?: ""
            val durationMs = if (obj.has("durationMs")) obj.optLong("durationMs") else 0L

            candidates += CandidateMetadata(
                trackId = asin,
                title = title,
                artist = artistName,
                album = albumTitle,
                durationMs = durationMs
            )
        }
        return candidates
    }

    private fun searchTracks(context: Context, term: String, country: String = "US", type: String = "track"): JSONArray? {
        ensureSession()

        val pageUrl = "$MUSIC_BASE_URL/search/${java.net.URLEncoder.encode(term, "UTF-8")}"
        val searchUrl = context.dataStore[AmazonSearchApiUrlKey]?.ifBlank { DEFAULT_SEARCH_API_URL } ?: DEFAULT_SEARCH_API_URL
        Timber.d("Amazon searchTracks: term='%s' url=%s deviceId=%s sessionId=%s", term, searchUrl, deviceId, sessionId)

        val amznHeaders = buildHeaders(pageUrl)
        val bodyObj = JSONObject().apply {
            put("filter", JSONObject().put("IsLibrary", JSONArray().put("false")).toString())
            put("keyword", JSONObject().apply {
                put("interface", "Web.TemplatesInterface.v1_0.Touch.SearchTemplateInterface.SearchKeywordClientInformation")
                put("keyword", term)
            }.toString())
            put("suggestedKeyword", term)
            put("userHash", JSONObject().put("level", "LIBRARY_MEMBER").toString())
            put("headers", JSONObject(amznHeaders).toString())
        }

        val requestBuilder = Request.Builder()
            .url(searchUrl)
            .post(bodyObj.toString().toRequestBody("text/plain;charset=UTF-8".toMediaTypeOrNull()))
            .header("Accept", "*/*")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("DNT", "1")
            .header("Origin", MUSIC_BASE_URL)
            .header("Priority", "u=1, i")
            .header("Referer", pageUrl)
            .header("Sec-CH-UA", "\"Not;A=Brand\";v=\"8\", \"Chromium\";v=\"150\", \"Brave\";v=\"150\"")
            .header("Sec-CH-UA-Mobile", "?0")
            .header("Sec-CH-UA-Platform", "\"Windows\"")
            .header("Sec-Fetch-Dest", "empty")
            .header("Sec-Fetch-Mode", "cors")
            .header("Sec-Fetch-Site", "cross-site")
            .header("Sec-GPC", "1")
            .header("User-Agent", BROWSER_USER_AGENT)

        amznHeaders.forEach { (k, v) -> requestBuilder.header(k, v) }

        return runCatching {
            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    val payload = response.body?.string().orEmpty()
                    Timber.w("Amazon search failed with ${response.code}: ${payload.take(200)}")
                    return@use null
                }

                val responseBody = response.body?.string() ?: return@use null
                Timber.d(
                    "Amazon searchTracks: term='%s' HTTP %d, body length=%d, snippet=%s",
                    term, response.code, responseBody.length, responseBody.take(300)
                )
                parseSearchResults(responseBody, type, term, country)
            }
        }.onFailure { e ->
            Timber.w(e, "Amazon direct search failed for '$term'")
        }.getOrNull()
    }

    internal fun ensureSession() {
        if (isInitialized && System.currentTimeMillis() - sessionFetchedAtMs < 30 * 60 * 1000L) return
        synchronized(initLock) {
            if (isInitialized && System.currentTimeMillis() - sessionFetchedAtMs < 30 * 60 * 1000L) return
            Timber.d("Initializing Amazon Music native session...")

            runCatching {
                // First visit home page to get cookies
                val homeRequest = Request.Builder()
                    .url(MUSIC_BASE_URL)
                    .header("User-Agent", BROWSER_USER_AGENT)
                    .build()
                client.newCall(homeRequest).execute().use { }

                val configRequest = Request.Builder()
                    .url("$MUSIC_BASE_URL/config.json")
                    .header("User-Agent", BROWSER_USER_AGENT)
                    .header("Referer", MUSIC_BASE_URL)
                    .build()

                client.newCall(configRequest).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body.string()
                        val json = JSONObject(body)
                        deviceId = json.optString("deviceId")
                        sessionId = json.optString("sessionId")
                        appVersion = json.optString("version", appVersion)
                        musicTerritory = json.optString("musicTerritory", "US")

                        json.optJSONObject("csrf")?.let { csrf ->
                            csrfToken = csrf.optString("token")
                            csrfTs = csrf.optString("ts")
                            csrfRnd = csrf.optString("rnd")
                        }
                        isInitialized = true
                        sessionFetchedAtMs = System.currentTimeMillis()
                        Timber.i("Amazon session initialized: deviceId=$deviceId")
                    }
                }
            }.onFailure { e ->
                Timber.w(e, "Failed to fetch Amazon config.json, using fallback session")
                deviceId = (Math.random() * 10000000000000000L).toLong().toString()
                sessionId = "${(Math.random() * 999).toInt()}-${(Math.random() * 9999999).toInt()}-${(Math.random() * 9999999).toInt()}"
                csrfTs = (System.currentTimeMillis() / 1000).toString()
                csrfRnd = (Math.random() * 2000000000).toInt().toString()
                isInitialized = true
                sessionFetchedAtMs = System.currentTimeMillis()
            }
        }
    }

    internal fun buildHeaders(pageUrl: String): Map<String, String> {
        val csrf = JSONObject().apply {
            put("interface", "CSRFInterface.v1_0.CSRFHeaderElement")
            put("token", csrfToken)
            put("timestamp", csrfTs.ifEmpty { (System.currentTimeMillis() / 1000).toString() })
            put("rndNonce", csrfRnd.ifEmpty { (Math.random() * 2000000000).toInt().toString() })
        }.toString()

        val auth = JSONObject().apply {
            put("interface", "ClientAuthenticationInterface.v1_0.ClientTokenElement")
            put("accessToken", "")
        }.toString()

        val requestId = java.util.UUID.randomUUID().toString()

        return mapOf(
            "x-amzn-authentication" to auth,
            "x-amzn-device-model" to "WEBPLAYER",
            "x-amzn-device-id" to (deviceId ?: ""),
            "x-amzn-session-id" to (sessionId ?: ""),
            "x-amzn-user-agent" to BROWSER_USER_AGENT,
            "x-amzn-device-family" to "WebPlayer",
            "x-amzn-device-width" to "1920",
            "x-amzn-device-height" to "1080",
            "x-amzn-device-scale" to "1",
            "x-amzn-device-request-id" to requestId,
            "x-amzn-request-id" to requestId,
            "x-amzn-device-language" to Locale.getDefault().toLanguageTag().replace("-", "_").ifBlank { "en_US" },
            "x-amzn-application-version" to appVersion,
            "x-amzn-csrf" to csrf,
            "x-amzn-music-domain" to "music.amazon.com",
            "x-amzn-page-url" to pageUrl,
            "x-amzn-timestamp" to System.currentTimeMillis().toString(),
            "x-amzn-os-version" to "1.0",
            "x-amzn-device-time-zone" to java.util.TimeZone.getDefault().id,
            "x-amzn-is-24-hour-format" to "true",
            "x-amzn-referer" to "",
            "x-amzn-device-type-id" to "A1PY8Q7986S686",
            "x-amzn-hardware-device-type-id" to "A1PY8Q7986S686",
            "x-amzn-currency-of-preference" to "USD"
        )
    }

    fun searchTracks(term: String, type: String = "track", country: String = "US"): JSONArray? {
        ensureSession()
        val pageUrl = "$MUSIC_BASE_URL/search/${java.net.URLEncoder.encode(term, "UTF-8")}"
        val amznHeaders = buildHeaders(pageUrl)
        val bodyObj = JSONObject().apply {
            put("filter", JSONObject().put("IsLibrary", JSONArray().put("false")).toString())
            put("keyword", JSONObject().apply {
                put("interface", "Web.TemplatesInterface.v1_0.Touch.SearchTemplateInterface.SearchKeywordClientInformation")
                put("keyword", term)
            }.toString())
            put("suggestedKeyword", term)
            put("userHash", JSONObject().put("level", "LIBRARY_MEMBER").toString())
            put("headers", JSONObject(amznHeaders).toString())
        }

        val requestBuilder = Request.Builder()
            .url(DEFAULT_SEARCH_API_URL)
            .post(bodyObj.toString().toRequestBody("text/plain;charset=UTF-8".toMediaTypeOrNull()))
            .header("User-Agent", BROWSER_USER_AGENT)
            .header("Origin", MUSIC_BASE_URL)
            .header("Referer", pageUrl)
            .header("Accept", "*/*")

        amznHeaders.forEach { (k, v) -> requestBuilder.header(k, v) }

        return runCatching {
            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    val payload = response.body?.string().orEmpty()
                    Timber.w("Amazon search failed with ${response.code}: ${payload.take(200)}")
                    return@use null
                }
                val responseBody = response.body?.string() ?: return@use null
                parseSearchResults(responseBody, type, term, country)
            }
        }.onFailure { e ->
            Timber.w(e, "Amazon search failed for '$term'")
        }.getOrNull()
    }

    private fun parseSearchResults(responseBody: String, type: String, term: String, country: String): JSONArray? {
        val root = runCatching { JSONObject(responseBody) }.getOrNull()
        if (root == null) {
            Timber.w("Amazon parseSearchResults: response body was not valid JSON for term '%s' (length=%d)", term, responseBody.length)
            return null
        }
        val results = JSONArray()

        val methods = root.optJSONArray("methods")
        val widgets = mutableListOf<JSONObject>()
        methods
            ?.optJSONObject(0)
            ?.optJSONObject("template")
            ?.optJSONArray("widgets")
            ?.let { widgetArray ->
                for (i in 0 until widgetArray.length()) {
                    widgetArray.optJSONObject(i)?.let { widgets.add(it) }
                }
            }
        Timber.d(
            "Amazon parseSearchResults: term='%s' root.keys=%s methods=%s widgets=%d",
            term, root.keys().asSequence().toList(), methods?.length() ?: -1, widgets.size
        )

        if (widgets.isNotEmpty()) {
            collectWidgetsWithItems(widgets, results)
            Timber.d("Amazon parseSearchResults: term='%s' widget-path extracted %d item(s)", term, results.length())
            if (results.length() > 0) {
                Timber.v("Amazon direct search found ${results.length()} items for term '$term' (type=$type, country=$country)")
                return results
            }
        }

        val shovelers = mutableListOf<JSONObject>()

        // Single pass instead of 6 - each findAllByInterface() call used to re-walk the whole
        // (often 1MB+) tree from root just to check one interface string per node. Collecting
        // all target interfaces in one traversal cuts this to one walk with an O(1) set lookup
        // per node instead of up to 6 string comparisons.
        findAllByInterfaces(root, SHOVELER_INTERFACES, shovelers)

        if (shovelers.isEmpty()) {
            findWidgetsWithItems(root, shovelers)
            if (shovelers.isEmpty()) {
                findAnyItemsContainers(root, shovelers)
            }
        }
        Timber.d("Amazon parseSearchResults: term='%s' shovelers found=%d", term, shovelers.size)

        fun addTrack(item: JSONObject, deeplink: String) {
            val info = extractDeeplinkInfo(deeplink) ?: return
            // trackAsin is set for both supported playable shapes: a direct /tracks/{asin} link
            // (trackAsin == the link's own id) and an /albums/{albumAsin}?trackAsin={asin} link.
            // A bare album/artist/playlist deeplink with no trackAsin is never playable, so it's
            // correctly excluded here rather than falling back to the container's own asin.
            val asin = info.trackAsin ?: return
            val title = textValue(item.opt("primaryText")).ifBlank { item.optString("imageAltText") }
            if (asin.isBlank() || title.isBlank()) return

            val artistsArr = JSONArray()
            val artistStr = textValue(item.opt("secondaryText1")).ifBlank { textValue(item.opt("secondaryText")) }
            if (artistStr.isNotBlank()) {
                artistStr.split(ARTIST_SPLIT_REGEX).forEach {
                    val trimmed = it.trim()
                    if (trimmed.isNotEmpty()) artistsArr.put(JSONObject().put("name", trimmed))
                }
            }

            val durationSeconds = parseDurationMMSS(textValue(item.opt("secondaryText3")))
            val mapped = JSONObject().apply {
                put("asin", asin)
                put("kind", "track")
                put("title", title)
                put("artists", artistsArr)
                if (durationSeconds > 0) put("durationMs", durationSeconds * 1000L)
            }
            results.put(mapped)
        }

        for (shoveler in shovelers) {
            val items = shoveler.optJSONArray("items") ?: continue
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val iface = item.optString("interface")
                val deeplink = item.optJSONObject("primaryTextLink")?.optString("deeplink")
                    ?.takeIf { it.isNotBlank() }
                    ?: item.optJSONObject("primaryLink")?.optString("deeplink")?.takeIf { it.isNotBlank() }
                Timber.v("Amazon parseSearchResults: shoveler item interface='%s' deeplink=%s", iface, deeplink)
                when {
                    type == "track" && (iface.contains("DescriptiveRowItemElement") || iface.contains("SquareHorizontalItemElement")) -> {
                        if (deeplink != null) addTrack(item, deeplink)
                    }
                    type == "all" && deeplink != null -> addTrack(item, deeplink)
                }
            }
        }
        Timber.d("Amazon parseSearchResults: term='%s' after shoveler pass, results=%d", term, results.length())

        if (type == "track" && results.length() == 0) {
            val tables = mutableListOf<JSONObject>()
            findAllByInterface(
                root,
                "Web.TemplatesInterface.v1_0.Touch.WidgetsInterface.DescriptiveTableWidgetElement",
                tables
            )
            Timber.d("Amazon parseSearchResults: term='%s' tables found=%d", term, tables.size)
            for (table in tables) {
                val items = table.optJSONArray("items") ?: continue
                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    val deeplink = item.optJSONObject("primaryTextLink")?.optString("deeplink")
                        ?.takeIf { it.isNotBlank() }
                        ?: item.optJSONObject("primaryLink")?.optString("deeplink")?.takeIf { it.isNotBlank() }
                        ?: continue
                    addTrack(item, deeplink)
                }
            }
        }

        Timber.v("Amazon direct search found ${results.length()} items for term '$term' (type=$type, country=$country)")
        return results
    }

    private fun collectWidgetsWithItems(widgets: List<JSONObject>, results: JSONArray) {
        for (widget in widgets) {
            collectWidgetsWithItems(widget, results)
        }
    }

    private fun collectWidgetsWithItems(element: Any, results: JSONArray, depth: Int = 0) {
        if (depth > 20) return
        when (element) {
            is JSONObject -> {
                val items = element.optJSONArray("items")
                if (items != null && items.length() > 0) {
                    parseWidgetItems(items, results)
                }
                for (key in element.keys()) {
                    val child = element.opt(key)
                    if (child is JSONObject || child is JSONArray) {
                        collectWidgetsWithItems(child, results, depth + 1)
                    }
                }
            }
            is JSONArray -> {
                for (i in 0 until element.length()) {
                    val child = element.opt(i)
                    if (child is JSONObject || child is JSONArray) {
                        collectWidgetsWithItems(child, results, depth + 1)
                    }
                }
            }
        }
    }

    /**
     * Result of trying to identify the ASIN carried by a widget item, together with the
     * verified object *kind* it belongs to (track / album / artist / playlist / unknown).
     *
     * INVARIANT: callers must check [kind] before treating [asin] as a playable track ASIN.
     * Amazon ASINs are just opaque 10-character alphanumeric strings - a track ASIN and an
     * album ASIN are indistinguishable by shape alone, so the *only* trustworthy signal for
     * "is this actually a track" is the deeplink/interface type that produced it, never the
     * raw string value.
     */
    private data class IdentifiedAsin(val asin: String, val kind: String?)

    private fun parseWidgetItems(items: JSONArray, results: JSONArray) {
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val identified = extractAsinFromItem(item)
            if (identified == null) {
                Timber.v("Amazon parseWidgetItems: could not extract an ASIN for item, skipping")
                continue
            }

            // Reject container kinds outright. Genuine track rows linked through an album
            // deeplink (`/albums/{albumAsin}?trackAsin={trackAsin}`) are already promoted to
            // kind="track" by extractAsinFromItem() above, so anything still tagged
            // "album"/"albums" here has no resolved trackAsin at all - it is a bare container
            // ASIN with no exact-track identifier, and per the resolve() invariant that must
            // never be treated as playable (Amazon would silently default to its first track).
            if (identified.kind == "artist" || identified.kind == "artists" ||
                identified.kind == "playlist" || identified.kind == "playlists" ||
                identified.kind == "album" || identified.kind == "albums"
            ) {
                Timber.d(
                    "Amazon parseWidgetItems: rejecting non-track item (kind=%s, asin=%s)",
                    identified.kind, identified.asin
                )
                continue
            }

            val asin = identified.asin
            val title = textValue(item.opt("primaryText")).ifBlank { item.optString("imageAltText") }
            if (title.isBlank()) continue

            val artistsArr = JSONArray()
            val artistStr = textValue(item.opt("secondaryText1")).ifBlank { textValue(item.opt("secondaryText")) }
            if (artistStr.isNotBlank()) {
                artistStr.split(ARTIST_SPLIT_REGEX).forEach {
                    val trimmed = it.trim()
                    if (trimmed.isNotEmpty()) artistsArr.put(JSONObject().put("name", trimmed))
                }
            }

            val mapped = JSONObject().apply {
                put("asin", asin)
                put("kind", identified.kind ?: "")
                put("title", title)
                put("artists", artistsArr)
                val durationSeconds = parseDurationMMSS(textValue(item.opt("secondaryText3")))
                if (durationSeconds > 0) put("durationMs", durationSeconds * 1000L)
                item.optJSONObject("album")?.optString("title")?.takeIf { it.isNotBlank() }?.let { put("album", it) }
            }
            results.put(mapped)
        }
    }

    /**
     * Identifies the ASIN for a widget item.
     *
     * Priority: the item's own `asin` field is what the row actually represents (this is what
     * was clicked) and is trusted directly, same as Amazon's own clients do. The deeplink kind
     * is extracted alongside it purely as metadata for [selectBestTrack]'s scoring/logging and
     * for filtering out obvious non-track rows (artist/playlist) - it does not gate a direct
     * `asin` hit, since real payloads frequently link a track row into its album's deeplink.
     *
     * Only when there's no direct `asin` field at all do we fall back to a deeplink-derived id,
     * and in that case we require the deeplink to explicitly resolve to a track - with zero other
     * signal on the item, an album/artist/playlist id has no business being treated as a track.
     */
    private fun extractAsinFromItem(item: JSONObject): IdentifiedAsin? {
        val deeplink = item.optJSONObject("primaryLink")?.optString("deeplink")?.takeIf { it.isNotBlank() }
            ?: item.optJSONObject("primaryTextLink")?.optString("deeplink")?.takeIf { it.isNotBlank() }
            ?: item.optString("deeplink").takeIf { it.isNotBlank() }
        val linkInfo = extractDeeplinkInfo(deeplink)

        // A trackAsin embedded in an album deeplink (Amazon's
        // `/albums/{albumAsin}?trackAsin={trackAsin}` shape) is Amazon's own explicit statement
        // of which exact track this row is - it always wins over the item's own `asin` field,
        // which may just echo the surrounding album's ASIN. This is the fix for "clicking any
        // song plays track 1": without this branch, such rows either fell through to the raw
        // (non-playable) album asin or were dropped as non-track and never reached playback.
        if (linkInfo?.trackAsin != null) {
            return IdentifiedAsin(linkInfo.trackAsin, "track")
        }

        val direct = item.optString("asin").takeIf { it.isNotBlank() }
        if (direct != null) {
            return IdentifiedAsin(direct, linkInfo?.kind)
        }

        return null
    }

    private data class ScoredTrack(val track: MatchedTrack, val score: Double)

    /**
     * Picks the search result that actually matches the clicked track, instead of just
     * returning `results[0]`.
     *
     * Previously this returned the *first* result with a non-blank asin/title, full stop - no
     * comparison against the query's title/artist/duration at all, even though this file already
     * defines [stringSimilarity]/[tokenJaccardSimilarity] helpers for exactly this purpose (they
     * were dead code, never called). Since Amazon search results for a track query are commonly
     * ordered starting with the album's first track, "just take index 0" reproduced the exact
     * symptom reported: whichever song you clicked, playback started from track 1.
     */
    private fun selectBestTrack(results: JSONArray, query: Query): MatchedTrack? {
        Timber.d("Amazon selectBestTrack inspecting %d result(s) for '%s'", results.length(), query.title)

        val queryTitleNorm = query.title.normalized()
        val queryArtistNorm = query.artists.firstOrNull()?.normalized().orEmpty()

        val scored = mutableListOf<ScoredTrack>()

        for (i in 0 until results.length()) {
            val obj = results.optJSONObject(i) ?: continue

            // Defense in depth: by the time a candidate reaches here it should already be
            // kind="track" (parseWidgetItems/addTrack only emit candidates with a resolved
            // trackAsin), but reject any container kind that somehow slipped through - a bare
            // album/artist/playlist ASIN must never be scored as a playback candidate, since
            // Amazon would silently resolve it to that container's first track.
            val kind = obj.optString("kind").takeIf { it.isNotBlank() }
            if (kind == "artist" || kind == "artists" || kind == "playlist" || kind == "playlists" ||
                kind == "album" || kind == "albums"
            ) {
                Timber.w(
                    "Amazon selectBestTrack: rejecting non-track candidate (kind=%s, asin=%s)",
                    kind, obj.optString("asin")
                )
                continue
            }

            val asin = obj.optString("asin").takeIf { it.isNotBlank() } ?: continue
            val title = obj.optString("title").takeIf { it.isNotBlank() } ?: continue

            val artists = obj.optJSONArray("artists")
                ?.let { arr ->
                    buildList {
                        for (j in 0 until arr.length()) {
                            val artistObj = arr.optJSONObject(j) ?: continue
                            val name = artistObj.optString("name").takeIf { it.isNotBlank() } ?: continue
                            add(name)
                        }
                    }
                }
                ?: emptyList()

            val album = obj.optString("album").takeIf { it.isNotBlank() }
            val durationMs = if (obj.has("durationMs")) obj.optLong("durationMs") else 0L

            val titleScore = stringSimilarity(title.normalized(), queryTitleNorm)
            val tokenScore = tokenJaccardSimilarity(title.normalized(), queryTitleNorm)
            val artistScore = if (queryArtistNorm.isNotBlank()) {
                artists.maxOfOrNull { stringSimilarity(it.normalized(), queryArtistNorm) } ?: 0.0
            } else 0.5
            val durationScore = if (query.durationMs != null && query.durationMs > 0 && durationMs > 0) {
                1.0 - min(1.0, abs(query.durationMs - durationMs).toDouble() / query.durationMs)
            } else 0.5

            // Explicit/clean preference: a plain bonus/penalty rather than a new weighted
            // category, since title/token scoring alone can't tell the explicit and clean
            // pressings of the same track apart (their titles are identical apart from this tag).
            val candidateExplicit = EXPLICIT_TAG_REGEX.containsMatchIn(title)
            val explicitAdjustment = when (query.explicit) {
                null -> 0.0
                candidateExplicit -> 0.15
                else -> -0.15
            }

            val score = (titleScore * 0.45) + (tokenScore * 0.25) + (artistScore * 0.2) + (durationScore * 0.1) + explicitAdjustment

            Timber.v(
                "Amazon selectBestTrack candidate #%d: title='%s' artist=%s asin=%s score=%.3f",
                i, title, artists.firstOrNull() ?: "?", asin, score
            )

            scored += ScoredTrack(MatchedTrack(asin, title, artists, album, durationMs.takeIf { it > 0 }), score)
        }

        val best = scored.maxByOrNull { it.score }
        if (best == null) {
            Timber.d("Amazon selectBestTrack found no usable result for '%s'", query.title)
            return null
        }

        Timber.d(
            "Amazon selectBestTrack chose: %s - %s (ASIN: %s, score=%.3f) out of %d candidate(s)",
            best.track.artists.firstOrNull() ?: "Unknown", best.track.title, best.track.asin, best.score, scored.size
        )
        return best.track
    }

    private fun resolveAsin(asin: String, mediaId: String, country: String, quality: String): Resolved {
        val codec = when (quality) {
            "ATMOS" -> "eac3"
            "HI_RES" -> "flac"
            else -> "flac"
        }
        val url = DEFAULT_RESOLVE_API_URL.toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("asin", asin)
            ?.addQueryParameter("country", country)
            ?.addQueryParameter("codec", codec)
            ?.build() ?: throw AmazonResolutionException("Could not build resolution URL")

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", BROWSER_USER_AGENT)
            .header("Origin", "https://t2tunes.site")
            .header("Referer", "https://t2tunes.site/")
            .build()

        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw AmazonResolutionException("Resolution failed with code ${response.code}")
            val body = response.body.string()
            Timber.v("Amazon resolution response: $body")

            val jsonArr = try { JSONArray(body) } catch (e: Exception) {
                val obj = try { JSONObject(body) } catch (e2: Exception) { null }
                when {
                    obj == null -> JSONArray()
                    obj.has("data") && obj.opt("data") is JSONArray -> obj.optJSONArray("data") ?: JSONArray()
                    obj.has("data") && obj.opt("data") is JSONObject -> JSONArray().apply { put(obj.optJSONObject("data")) }
                    else -> JSONArray().apply { put(obj) }
                }
            }

            val first = jsonArr.optJSONObject(0) ?: throw AmazonResolutionException("Empty resolution response")
            Timber.d("Amazon resolution first object: $first")
            val dataObj = first.optJSONObject("data")
            val streamInfo = first.optJSONObject("streamInfo")
                ?: dataObj?.optJSONObject("streamInfo")
            val streamUrl = first.optString("streamUrl").takeIf { it.isNotEmpty() }
                ?: first.optString("url").takeIf { it.isNotEmpty() }
                ?: streamInfo?.optString("streamUrl")?.takeIf { it.isNotEmpty() }
                ?: streamInfo?.optString("url")?.takeIf { it.isNotEmpty() }
                ?: dataObj?.optString("streamUrl")?.takeIf { it.isNotEmpty() }
                ?: dataObj?.optString("url")?.takeIf { it.isNotEmpty() }
                ?: throw AmazonResolutionException("No stream URL returned")

            val decryptionKey = (first.optString("decryptionKey").takeIf { it.isNotEmpty() }
                ?: first.optString("key").takeIf { it.isNotEmpty() }
                ?: streamInfo?.optString("decryptionKey")?.takeIf { it.isNotEmpty() }
                ?: streamInfo?.optString("key")?.takeIf { it.isNotEmpty() }
                ?: first.optString("decrypt_key").takeIf { it.isNotEmpty() }
                ?: dataObj?.optString("decryptionKey")?.takeIf { it.isNotEmpty() }
                ?: dataObj?.optString("key")?.takeIf { it.isNotEmpty() }
                ?: dataObj?.optString("decrypt_key")?.takeIf { it.isNotEmpty() }
                    )?.takeIf { it.isNotEmpty() }

            Timber.d("AmazonDecrypt: Resolved key: ${decryptionKey?.take(4)}...")

            val sampleRate = streamInfo?.optInt("sampleRate")?.takeIf { it > 0 }
            val actualCodec = streamInfo?.optString("codec")?.takeIf { it.isNotEmpty() }
                ?: first.optString("codec").takeIf { it.isNotEmpty() }
                ?: first.optJSONObject("tags")?.optString("codec")?.takeIf { it.isNotEmpty() }
                ?: codec

            val kid = streamInfo?.optString("kid")?.takeIf { it.isNotEmpty() }
                ?: first.optString("kid").takeIf { it.isNotEmpty() }
                ?: streamInfo?.optString("iv")?.takeIf { it.isNotEmpty() }
                ?: first.optString("iv").takeIf { it.isNotEmpty() }

            Resolved(
                mediaUri = streamUrl,
                trackId = asin,
                label = "Amazon Music",
                mimeType = when (actualCodec.lowercase()) {
                    "opus" -> "audio/opus"
                    else -> MimeTypes.AUDIO_MP4
                },
                codecs = actualCodec,
                bitrate = streamInfo?.optInt("bitrate")?.takeIf { it > 0 } ?: 0,
                sampleRate = sampleRate ?: (if (actualCodec == "flac") 48000 else 44100),
                expiresAtMs = System.currentTimeMillis() + STREAM_CACHE_MS,
                kid = kid,
                decryptionKey = decryptionKey
            ).also {
                if (decryptionKey != null) {
                    decryptionKeyCache[asin] = decryptionKey
                }
            }
        }
    }

    fun getDecryptionKey(asin: String): String? = decryptionKeyCache[asin]

    fun registerDecryptionKey(asin: String, key: String) {
        decryptionKeyCache[asin] = key
    }

    fun extractAsinFromKey(key: String): String? {
        val cleanKey = key.substringAfterLast(":")
        if (cleanKey.length == 10 && cleanKey.all { it.isLetterOrDigit() }) {
            return cleanKey
        }
        return null
    }

    private fun Query.cacheKey() = "amazon::$mediaId::$quality::$country"
    private fun Query.trackCacheKey() = "amazon_track::$title::$artists"

    /**
     * Identifies whether a mediaId explicitly names an album/artist/playlist container.
     * Used by [resolve] to reject non-track mediaIds up front, before the "any 10-char ASIN
     * shaped string" fast path in [toAmazonAsinOrNull] gets a chance to treat it as a track.
     */
    internal fun String.amazonContainerKindOrNull(): String? = when {
        this.startsWith("amazon:album:") -> "album"
        this.startsWith("amazon:artist:") -> "artist"
        this.startsWith("amazon:playlist:") -> "playlist"
        else -> null
    }

    /**
     * Resolves a mediaId to a track ASIN.
     *
     * NOTE on the bare-ASIN branch below: an Amazon ASIN is just a 10-character alphanumeric
     * string with no embedded type information, so a track ASIN and an album ASIN look
     * identical by shape. This fast path therefore cannot itself prove the ASIN is a track -
     * it can only be trusted because callers are expected to construct mediaId with the
     * "amazon:track:" prefix (or [amazonContainerKindOrNull] would have already rejected an
     * explicitly-tagged container above, in [resolve]). Any code that builds an Amazon mediaId
     * from a search/widget result MUST use the typed "amazon:track:" / "amazon:album:" prefixes
     * rather than a bare ASIN, or this fast path has no way to tell the two apart.
     */
    public fun String.toAmazonAsinOrNull(): String? {
        if (this.startsWith("amazon:track:")) return this.substringAfter("amazon:track:")
        if (this.amazonContainerKindOrNull() != null) return null
        if (this.length == 10 && this.all { it.isLetterOrDigit() }) return this
        return null
    }

    private val SHOVELER_INTERFACES = setOf(
        "Web.TemplatesInterface.v1_0.Touch.WidgetsInterface.VisualShovelerWidgetElement",
        "Web.TemplatesInterface.v1_0.Touch.WidgetsInterface.FeaturedShovelerWidgetElement",
        "Web.TemplatesInterface.v1_0.Touch.WidgetsInterface.DescriptiveShowcaseWidgetElement",
        "VisualShovelerWidgetElement",
        "FeaturedShovelerWidgetElement",
        "DescriptiveShowcaseWidgetElement",
    )

    /** Same traversal as [findAllByInterface] but checks membership against a whole set of
     * target interfaces per node (one O(1) hash lookup) instead of requiring one full tree
     * walk per interface string. Use this instead of calling [findAllByInterface] back-to-back
     * for multiple interfaces on the same root - each extra call is a full re-walk of the tree,
     * which is expensive on large (1MB+) search response payloads. */
    internal fun findAllByInterfaces(element: Any, targetInterfaces: Set<String>, results: MutableList<JSONObject>, depth: Int = 0) {
        if (depth > 20) return
        when (element) {
            is JSONObject -> {
                val iface = element.optString("interface")
                if (iface.isNotEmpty() && iface in targetInterfaces) {
                    results.add(element)
                }
                for (key in element.keys()) {
                    val child = element.opt(key)
                    if (child is JSONObject || child is JSONArray) {
                        findAllByInterfaces(child, targetInterfaces, results, depth + 1)
                    }
                }
            }
            is JSONArray -> {
                for (i in 0 until element.length()) {
                    val child = element.opt(i)
                    if (child is JSONObject || child is JSONArray) {
                        findAllByInterfaces(child, targetInterfaces, results, depth + 1)
                    }
                }
            }
        }
    }

    internal fun findAllByInterface(element: Any, targetInterface: String, results: MutableList<JSONObject>, depth: Int = 0) {
        if (depth > 20) return
        when (element) {
            is JSONObject -> {
                val iface = element.optString("interface")
                if (iface == targetInterface) {
                    results.add(element)
                }
                for (key in element.keys()) {
                    val child = element.opt(key)
                    if (child is JSONObject || child is JSONArray) {
                        findAllByInterface(child, targetInterface, results, depth + 1)
                    }
                }
            }
            is JSONArray -> {
                for (i in 0 until element.length()) {
                    val child = element.opt(i)
                    if (child is JSONObject || child is JSONArray) {
                        findAllByInterface(child, targetInterface, results, depth + 1)
                    }
                }
            }
        }
    }

    internal fun findWidgetsWithItems(element: Any, results: MutableList<JSONObject>, depth: Int = 0) {
        if (depth > 15) return
        when (element) {
            is JSONObject -> {
                val items = element.optJSONArray("items")
                if (items != null && items.length() > 0) {
                    val hasHeader = element.has("header") || element.has("headerText") || element.has("title")
                    if (hasHeader) {
                        results.add(element)
                        return
                    }
                }
                for (key in element.keys()) {
                    val child = element.opt(key)
                    if (child is JSONObject || child is JSONArray) {
                        findWidgetsWithItems(child, results, depth + 1)
                    }
                }
            }
            is JSONArray -> {
                for (i in 0 until element.length()) {
                    val child = element.opt(i)
                    if (child is JSONObject || child is JSONArray) {
                        findWidgetsWithItems(child, results, depth + 1)
                    }
                }
            }
        }
    }

    internal fun findAnyItemsContainers(element: Any, results: MutableList<JSONObject>, depth: Int = 0) {
        if (depth > 15) return
        when (element) {
            is JSONObject -> {
                val items = element.optJSONArray("items")
                if (items != null && items.length() > 0) {
                    results.add(element)
                }
                for (key in element.keys()) {
                    val child = element.opt(key)
                    if (child is JSONObject || child is JSONArray) {
                        findAnyItemsContainers(child, results, depth + 1)
                    }
                }
            }
            is JSONArray -> {
                for (i in 0 until element.length()) {
                    val child = element.opt(i)
                    if (child is JSONObject || child is JSONArray) {
                        findAnyItemsContainers(child, results, depth + 1)
                    }
                }
            }
        }
    }

    internal fun textValue(value: Any?): String {
        if (value == null) return ""
        if (value is String) return value
        if (value is JSONObject) {
            val text = value.optString("text").takeIf { it.isNotEmpty() }
            if (text != null) return text
            val defaultVal = value.opt("defaultValue")
            if (defaultVal != null) return textValue(defaultVal)
            val observer = value.optJSONObject("observer")
            if (observer != null) {
                val obsDefault = observer.opt("defaultValue")
                if (obsDefault != null) return textValue(obsDefault)
            }
        }
        return ""
    }

    /**
     * Result of parsing an Amazon Music deeplink.
     *
     * [trackAsin] is the ONLY field that identifies a *playable* track and is the sole thing
     * that may be passed into [resolveAsin]/[resolve]. [albumAsin] is a container id and must
     * never be sent into playback resolution on its own - Amazon will "resolve" an album ASIN,
     * but silently defaults playback to that album's first track, which is exactly the
     * "clicking any song plays track 1" bug this whole file guards against.
     */
    internal data class DeeplinkInfo(
        val kind: String,
        val albumAsin: String? = null,
        val trackAsin: String? = null,
    )

    private val PATH_SLASH_TRIM_REGEX = Regex("^/|/$")

    /**
     * Parses an Amazon Music deeplink into its kind plus album/track ASINs.
     *
     * Amazon's search/widget API represents a playable track in two different deeplink shapes,
     * both of which must be handled:
     *
     *   A) Direct track links:      /tracks/{trackAsin}
     *   B) Album-scoped links:      /albums/{albumAsin}?trackAsin={trackAsin}
     *
     * (B) is extremely common - Amazon frequently links a track's row through its *album's*
     * deeplink rather than a bare /tracks/ link, with the actual clicked track only named by the
     * `trackAsin` query parameter. Previously this function only looked at the URI *path*, so for
     * shape (B) the `trackAsin` query param was silently dropped entirely and only the (non
     * playable) album ASIN came back out - which is why callers were forced to either discard the
     * whole result (0 candidates) or fall back to the album ASIN (plays track 1).
     */
    internal fun extractDeeplinkInfo(deeplink: String?): DeeplinkInfo? {
        if (deeplink.isNullOrBlank()) return null

        val (path, rawQuery) = try {
            val uri = java.net.URI(deeplink)
            (uri.path ?: deeplink) to uri.query
        } catch (e: Exception) {
            val qIdx = deeplink.indexOf('?')
            if (qIdx >= 0) deeplink.substring(0, qIdx) to deeplink.substring(qIdx + 1) else deeplink to null
        }

        val segments = path.replace(PATH_SLASH_TRIM_REGEX, "").split("/")
        if (segments.size < 2) return null
        val kind = segments[0].lowercase()
        val rawId = segments[1]
        if (rawId.length != 10 || !rawId.all { it.isLetterOrDigit() }) return null

        fun asinQueryParam(name: String): String? = rawQuery
            ?.split("&")
            ?.mapNotNull { pair ->
                val idx = pair.indexOf('=')
                if (idx < 0) null else pair.substring(0, idx) to pair.substring(idx + 1)
            }
            ?.firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }
            ?.second
            ?.takeIf { it.length == 10 && it.all { c -> c.isLetterOrDigit() } }

        val info = when (kind) {
            "tracks" -> DeeplinkInfo(kind = kind, trackAsin = rawId)
            "albums" -> DeeplinkInfo(kind = kind, albumAsin = rawId, trackAsin = asinQueryParam("trackAsin"))
            "artists", "playlists" -> DeeplinkInfo(kind = kind)
            else -> return null
        }

        Timber.d(
            "DEEPLINK: type=%s albumAsin=%s trackAsin=%s (raw=%s)",
            info.kind, info.albumAsin, info.trackAsin, deeplink
        )
        return info
    }

    internal fun parseDurationMMSS(mmss: String): Long {
        val text = mmss.trim()
        if (text.isEmpty()) return 0L
        val parts = text.split(":")
        return try {
            when (parts.size) {
                2 -> parts[0].toLong() * 60 + parts[1].toLong()
                3 -> parts[0].toLong() * 3600 + parts[1].toLong() * 60 + parts[2].toLong()
                else -> 0L
            }
        } catch (e: NumberFormatException) { 0L }
    }

    private fun levenshteinDistance(a: String, b: String): Int {
        val m = a.length
        val n = b.length
        if (m == 0) return n
        if (n == 0) return m

        val dp = IntArray(n + 1) { it }
        for (i in 1..m) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..n) {
                val temp = dp[j]
                dp[j] = minOf(
                    dp[j] + 1,
                    dp[j - 1] + 1,
                    prev + if (a[i - 1] == b[j - 1]) 0 else 1
                )
                prev = temp
            }
        }
        return dp[n]
    }

    private fun stringSimilarity(a: String, b: String): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val maxLen = maxOf(a.length, b.length)
        return 1.0 - levenshteinDistance(a, b).toDouble() / maxLen
    }

    private fun tokenJaccardSimilarity(a: String, b: String): Double {
        val tokensA = a.split(" ").filter { it.isNotEmpty() }.toSet()
        val tokensB = b.split(" ").filter { it.isNotEmpty() }.toSet()
        if (tokensA.isEmpty() || tokensB.isEmpty()) return 0.0
        val intersection = tokensA.intersect(tokensB).size
        val union = tokensA.union(tokensB).size
        return intersection.toDouble() / union
    }

    // Precompiled once instead of inline Regex(...) literals inside normalized()/removeFeatures()/
    // cleanSearchTitle() below. normalized() in particular runs once per search candidate (title
    // + each artist) in selectBestTrack()'s scoring loop - on a large search response that's
    // dozens to hundreds of regex NFA compilations per single track resolution for zero reason,
    // since none of these patterns are dynamic.
    private val COMBINING_MARKS_REGEX = Regex("""\p{Mn}+""")
    private val PAREN_TAG_REGEX = Regex("""\((official|lyric|hd|4k|video|music video|audio|full album|visualizer|explicit|clean)\)""")
    private val BRACKET_TAG_REGEX = Regex("""\[(official|lyric|hd|4k|video|music video|audio|full album|visualizer|explicit|clean)\]""")
    // Detects the [Explicit]/(Explicit) tag on the RAW (pre-normalized) title, since
    // normalized() now strips this tag out precisely so it stops skewing text-similarity scoring.
    private val EXPLICIT_TAG_REGEX = Regex("""[\[(]\s*explicit\s*[\])]""", RegexOption.IGNORE_CASE)
    private val NON_ALNUM_REGEX = Regex("""[^a-z0-9 ]""")
    private val WHITESPACE_REGEX = Regex("""\s+""")
    private val FEATURE_TAIL_REGEX = Regex("""\b(feat|ft|with|prod|originally performed by|tribute to|cover of)\b.*""")
    private val TRAILING_BY_REGEX = Regex("""\b(by)\s*$""")
    private val VERSION_SUFFIX_REGEX = Regex(
        """\s*-\s*(remaster(ed)?|radio edit|edit|version|live|acoustic|instrumental|slowed|sped up|mix|remix).*$""",
        RegexOption.IGNORE_CASE
    )
    private val PAREN_BRACKET_TAIL_REGEX = Regex("""\s*[\[(].*$""")
    private val FEAT_TAIL_REGEX = Regex("""\b(feat\.?|ft\.?|with)\b.*$""", RegexOption.IGNORE_CASE)
    private val TRAILING_DASH_REGEX = Regex("""\s+[\-–—]\s*$""")
    private val BRACKETS_REGEX = Regex("""[\[\]()]+""")

    private fun String.normalized(): String {
        return Normalizer.normalize(this.lowercase(Locale.US), Normalizer.Form.NFD)
            .replace(COMBINING_MARKS_REGEX, "")
            .replace(PAREN_TAG_REGEX, "")
            .replace(BRACKET_TAG_REGEX, "")
            .replace(NON_ALNUM_REGEX, " ")
            .replace(WHITESPACE_REGEX, " ")
            .trim()
    }

    private fun String.removeFeatures(): String {
        var s = this
        s = s.replace(FEATURE_TAIL_REGEX, "")
        s = s.replace(TRAILING_BY_REGEX, "")
        return s.replace(WHITESPACE_REGEX, " ").trim()
    }

    private fun String.cleanSearchTitle(keepParentheses: Boolean = false): String {
        var s = this.trim()
        s = s.replace(WHITESPACE_REGEX, " ")
        s = s.replace(VERSION_SUFFIX_REGEX, "")

        if (!keepParentheses) {
            s = s.replace(PAREN_BRACKET_TAIL_REGEX, "")
        }
        s = s.replace(FEAT_TAIL_REGEX, "")
        s = s.replace(TRAILING_DASH_REGEX, "")
        s = s.replace(BRACKETS_REGEX, if (keepParentheses) " " else "")
        return s.replace(WHITESPACE_REGEX, " ").trim()
    }
}