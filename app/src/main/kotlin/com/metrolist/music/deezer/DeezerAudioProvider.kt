/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.deezer

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.metrolist.music.constants.DeezerAudioQuality
import com.metrolist.music.constants.DeezerProxyMode
import com.metrolist.music.providers.IsrcResolver
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.MessageDigest
import java.text.Normalizer
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

object DeezerAudioProvider {
    const val DEFAULT_RESOLVER_URL = "https://yesitworkssomehow-funny-deeza-api-and-yeah.hf.space/get_url"
    const val DEFAULT_RESOLVER_URL_128 = "https://api-number-2-poopo.onrender.com/get_url"
    const val DEFAULT_PROXY_URL = ""
    const val RENDER_RESOLVER_URL = "https://dzmedia-metrofuse.onrender.com/get_url"
    const val RENDER_PROXY_BASE_URL = "https://dzmedia-metrofuse.onrender.com"

    private const val BROWSER_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Mobile Safari/537.36"
    private const val SEARCH_API_URL = "https://api.deezer.com/search/track"
    private const val STREAM_CACHE_MS = 45 * 60 * 1000L
    private const val MIN_MATCH_SCORE = 80
    private const val REJECT_SCORE = -1_000_000
    private const val SEARCH_LIMIT = 4
    private const val RESOLVER_MAX_IN_FLIGHT = 4
    private const val RESOLVER_PERMIT_TIMEOUT_MS = 4_000L
    private const val RESOLVER_MIN_INTERVAL_MS = 40L
    private const val DEFAULT_PROXY_PORT = 3128
    private const val DEFAULT_MEDIA_PROXY_PATH = "/deezer-media-proxy"
    private const val DEFAULT_API_PROXY_PATH = "/deezer-api-proxy"
    private const val GW_LIGHT_URL = "https://www.deezer.com/ajax/gw-light.php"
    private const val GET_URL_URL = "https://media.deezer.com/v1/get_url"
    private const val ACCOUNT_SESSION_TTL_MS = 30 * 60 * 1000L
    private const val TRACK_TOKEN_FALLBACK_TTL_MS = 10 * 60 * 1000L
    private const val TRACK_TOKEN_CACHE_MAX_SIZE = 200
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    private val AMAZON_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.US)

    data class Query(
        val mediaId: String,
        val title: String,
        val artists: List<String>,
        val album: String?,
        val isrc: String?,
        val durationMs: Long?,
        val resolverUrl: String,
        val quality: DeezerAudioQuality,
        val proxyUrl: String = DEFAULT_PROXY_URL,
        val cookie: String = "",
        val useAccount: Boolean = true,
    )

    data class Resolved(
        val mediaUri: String,
        val trackId: String,
        val label: String,
        val mimeType: String,
        val codecs: String,
        val bitrate: Int,
        val sampleRate: Int?,
        val contentLength: Long?,
        val expiresAtMs: Long,
    )

    data class CandidateMetadata(
        val trackId: String,
        val title: String,
        val artist: String,
        val album: String?,
        val isrc: String?,
        val durationMs: Long?,
    )

    class DeezerResolutionException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private data class MatchedTrack(
        val trackId: String,
        val title: String,
        val artistNames: List<String>,
        val album: String?,
        val isrc: String?,
        val durationMs: Long?,
    )

    private data class StreamAttempt(
        val resolved: Resolved? = null,
        val error: String? = null,
    )

    private data class ProxyConfig(
        val host: String,
        val port: Int,
    ) {
        val key: String = "$host:$port"
    }

    private data class AccountSession(
        val apiToken: String,
        val licenseToken: String,
        val cookie: String,
        val client: OkHttpClient,
        val expiresAtMs: Long,
    )

    private data class AccountTrackToken(
        val token: String,
        val expiresAtMs: Long,
        val createdAtMs: Long,
    )

    private class DeezerAccountCookieJar(initialCookieHeader: String) : CookieJar {
        private val jarCookies = ConcurrentHashMap<String, String>()

        init {
            initialCookieHeader.split(';').forEach { pair ->
                val trimmed = pair.trim()
                if (trimmed.isEmpty()) return@forEach
                val separatorIndex = trimmed.indexOf('=')
                if (separatorIndex <= 0) return@forEach
                val name = trimmed.substring(0, separatorIndex).trim()
                val value = trimmed.substring(separatorIndex + 1).trim()
                if (name.isNotEmpty()) jarCookies[name] = value
            }
        }

        override fun saveFromResponse(
            url: HttpUrl,
            cookies: List<Cookie>,
        ) {
            cookies.forEach { jarCookies[it.name] = it.value }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            jarCookies.map { (name, value) ->
                Cookie.Builder().name(name).value(value).domain(url.host).build()
            }

        fun headerValue(): String = jarCookies.entries.joinToString("; ") { (name, value) -> "$name=$value" }
    }

    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    private val fastClient =
        client
            .newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .build()

    private val trackCache = ConcurrentHashMap<String, MatchedTrack>()
    private val streamCache = ConcurrentHashMap<String, Resolved>()
    private val accountTrackTokens = ConcurrentHashMap<String, AccountTrackToken>()
    private val proxiedClients = ConcurrentHashMap<String, OkHttpClient>()
    private val accountSessions = ConcurrentHashMap<String, AccountSession>()
    private val resolverPermits = Semaphore(RESOLVER_MAX_IN_FLIGHT, true)
    private val lastResolverRequestAtMs = AtomicLong(0L)

    fun resolve(query: Query): Resolved {
        val resolverUrls = buildList {
            if (query.quality == DeezerAudioQuality.MP3_128) {
                add(DEFAULT_RESOLVER_URL_128)
            }
            add(query.resolverUrl)
        }
            .map(::normalizeResolverUrl)
            .distinct()
        var lastError: Throwable? = null
        resolverUrls.forEach { resolverUrl ->
            runCatching {
                resolveWithResolver(query.copy(resolverUrl = resolverUrl.toString()))
            }.onSuccess { return it }
                .onFailure { lastError = it }
        }
        throw DeezerResolutionException(
            "All Deezer resolvers failed for ${query.title}",
            lastError,
        )
    }

    private fun resolveWithResolver(query: Query): Resolved {
        val resolverUrl = normalizeResolverUrl(query.resolverUrl)
        val proxyUrl = normalizeProxyUrl(query.proxyUrl)
        val directTrackId = query.mediaId.toDeezerTrackIdOrNull(allowPlainNumeric = false)
        val track = if (directTrackId != null) {
            query.toDirectMatchedTrack(directTrackId)
        } else {
            val trackCacheKey = query.copy(proxyUrl = proxyUrl).trackCacheKey()
            trackCache[trackCacheKey]
                ?: findBestTrack(query)
                    ?.also { trackCache[trackCacheKey] = it }
                ?: throw DeezerResolutionException("Deezer match not found for ${query.title}")
        }
        harvestIsrc(query, track)

        val now = System.currentTimeMillis()
        val qualities = qualityFallbackOrder(query.quality)
        val cacheKey =
            listOf(
                query.mediaId,
                track.trackId,
                query.quality.name,
                resolverUrl.toString().hashCode(),
                proxyUrl.hashCode(),
            ).joinToString("::")
        streamCache[cacheKey]
            ?.takeIf { it.expiresAtMs > now + 20_000L }
            ?.let { return it }

        if (query.useAccount && query.cookie.isNotBlank()) {
            val accountCacheKey = "acct::$cacheKey"
            streamCache[accountCacheKey]
                ?.takeIf { it.expiresAtMs > now + 20_000L }
                ?.let { return it }
            val accountStartMs = System.currentTimeMillis()
            val accountAttempt = requestAccountStream(
                cookie = query.cookie,
                mediaId = query.mediaId,
                trackId = track.trackId,
                preferredQuality = query.quality,
                qualities = qualities,
                durationMs = query.durationMs ?: track.durationMs,
                proxyUrl = proxyUrl,
            )
            val accountElapsedMs = System.currentTimeMillis() - accountStartMs
            Timber.tag("DeezerLatency").d(
                "account resolve for ${track.trackId}: ${accountElapsedMs}ms (${if (accountAttempt.resolved != null) "hit" else "miss"})",
            )
            accountAttempt.resolved?.let { resolved ->
                streamCache[accountCacheKey] = resolved
                return resolved
            }
            accountAttempt.error?.takeIf { it.isNotBlank() }?.let { Timber.tag("DeezerAudioProvider").w(it) }
        }

        val resolverStartMs = System.currentTimeMillis()
        val attempt = requestResolverStream(
            resolverUrl = resolverUrl,
            mediaId = query.mediaId,
            trackId = track.trackId,
            preferredQuality = query.quality,
            qualities = qualities,
            durationMs = query.durationMs ?: track.durationMs,
            proxyUrl = proxyUrl,
        )
        attempt.resolved?.let { resolved ->
            Timber.tag("DeezerLatency").d(
                "resolver resolve for ${track.trackId}: ${System.currentTimeMillis() - resolverStartMs}ms (hit)",
            )
            streamCache[cacheKey] = resolved
            return resolved
        }
        Timber.tag("DeezerLatency").d(
            "resolver resolve for ${track.trackId}: ${System.currentTimeMillis() - resolverStartMs}ms (miss)",
        )

        throw DeezerResolutionException(
            attempt.error ?: "Deezer stream not found for ${query.title}",
        )
    }

    fun invalidate(mediaId: String) {
        val prefix = "$mediaId::"
        for (key in streamCache.keys) {
            if (key.startsWith(prefix)) {
                streamCache.remove(key)
            }
        }
    }

    fun isDeezerTrackId(value: String): Boolean =
        value.toDeezerTrackIdOrNull(allowPlainNumeric = false) != null

    fun normalizeResolverUrl(value: String): HttpUrl {
        val raw = value.trim().ifBlank { DEFAULT_RESOLVER_URL }
        val parsed = raw.toHttpUrlOrNull()
            ?: throw DeezerResolutionException("Invalid Deezer resolver URL")
        val cleanPath = parsed.encodedPath.trimEnd('/')
        if (cleanPath.endsWith("/get_url", ignoreCase = true)) {
            return parsed.newBuilder()
                .encodedPath(cleanPath)
                .build()
        }
        val path = if (cleanPath.isBlank()) "/get_url" else "$cleanPath/get_url"
        return parsed.newBuilder()
            .encodedPath(path)
            .build()
    }

    fun normalizeProxyUrl(value: String): String {
        mediaProxyTemplate(value)?.let { return it }
        val config = proxyConfig(value) ?: return DEFAULT_PROXY_URL
        return config.key
    }

    fun effectiveProxyUrl(
        configuredProxyModeValue: String?,
        configuredProxyUrl: String,
        globalProxyEnabled: Boolean,
    ): String =
        effectiveProxyUrl(
            configuredProxyMode = proxyModeFromPreference(configuredProxyModeValue, configuredProxyUrl),
            configuredProxyUrl = configuredProxyUrl,
            globalProxyEnabled = globalProxyEnabled,
        )

    fun proxyModeFromPreference(
        configuredProxyModeValue: String?,
        configuredProxyUrl: String,
    ): DeezerProxyMode {
        val normalizedProxyUrl = normalizeProxyUrl(configuredProxyUrl)
        val inferredMode =
            when {
                normalizedProxyUrl.isBlank() -> DeezerProxyMode.DIRECT
                normalizedProxyUrl == normalizeProxyUrl(RENDER_PROXY_BASE_URL) -> DeezerProxyMode.RENDER
                else -> DeezerProxyMode.CUSTOM
            }
        return runCatching {
            DeezerProxyMode.valueOf(configuredProxyModeValue.orEmpty())
        }.getOrDefault(inferredMode)
    }

    fun effectiveProxyUrl(
        configuredProxyMode: DeezerProxyMode,
        configuredProxyUrl: String,
        globalProxyEnabled: Boolean,
    ): String {
        return when (configuredProxyMode) {
            DeezerProxyMode.DIRECT -> DEFAULT_PROXY_URL
            DeezerProxyMode.RENDER -> normalizeProxyUrl(RENDER_PROXY_BASE_URL)
            DeezerProxyMode.CUSTOM -> {
                val normalized = normalizeProxyUrl(configuredProxyUrl)
                normalized.ifBlank {
                    if (globalProxyEnabled) normalizeProxyUrl(RENDER_PROXY_BASE_URL) else DEFAULT_PROXY_URL
                }
            }
        }
    }

    fun isValidProxyUrl(value: String): Boolean =
        value.isBlank() || mediaProxyTemplate(value) != null || proxyConfig(value) != null

    fun wrapMediaUrlForProxy(
        mediaUrl: String,
        proxyUrl: String,
    ): String {
        val template = mediaProxyTemplate(proxyUrl) ?: return mediaUrl
        val encodedUrl = Uri.encode(mediaUrl)
        return if (template.contains("{url}")) {
            template.replace("{url}", encodedUrl)
        } else {
            "$template$encodedUrl"
        }
    }

    private fun wrapApiUrlForProxy(
        apiUrl: HttpUrl,
        proxyUrl: String,
    ): HttpUrl {
        val template = apiProxyTemplate(proxyUrl) ?: return apiUrl
        val wrapped = if (template.contains("{url}")) {
            template.replace("{url}", Uri.encode(apiUrl.toString()))
        } else {
            "$template${Uri.encode(apiUrl.toString())}"
        }
        return wrapped.toHttpUrlOrNull() ?: apiUrl
    }

    fun searchCandidates(
        query: Query,
        limit: Int = 8,
    ): List<CandidateMetadata> {
        val results = linkedMapOf<String, CandidateMetadata>()
        resolveIsrcTrack(query)?.let { track ->
            results[track.trackId] = track.toCandidateMetadata()
        }
        for (term in searchTerms(query)) {
            val array = searchTracks(
                term = term,
                limit = SEARCH_LIMIT,
                proxyUrl = query.proxyUrl,
            ) ?: continue
            for (index in 0 until array.length()) {
                val candidate = array.optJSONObject(index)?.toCandidateMetadata() ?: continue
                results.putIfAbsent(candidate.trackId, candidate)
                if (results.size >= limit) return results.values.toList()
            }
        }
        return results.values.toList()
    }

    /**
     * Finds the best-matching Deezer track for [query] using the same
     * ISRC-first -> scored-search -> song.link fallback chain used for
     * playback resolution, without resolving a playable stream.
     *
     * This is the accurate path for cross-provider identity lookups (e.g.
     * [com.metrolist.music.providers.IsrcResolver] pulling an ISRC to feed
     * the Apple Music canvas matcher): unlike [searchCandidates], which
     * just returns raw search hits in API order, this scores every
     * candidate against title + artist + album + duration
     * ([selectBestTrack]) and only returns a match that clears
     * [MIN_MATCH_SCORE], so a same-title-different-artist or
     * different-duration-version result can't leak through as a false
     * positive.
     */
    fun findBestMatch(query: Query): CandidateMetadata? =
        findBestTrack(query)?.toCandidateMetadata()

    private fun findBestTrack(query: Query): MatchedTrack? {
        resolveIsrcTrack(query)?.let { track ->
            Timber.tag("DeezerAudio").i("Resolved Deezer track ${track.trackId} through ISRC for ${query.title}")
            return track
        }
        val term = searchTerms(query).firstOrNull() ?: return null
        val results = searchTracks(
            term = term,
            limit = SEARCH_LIMIT,
            proxyUrl = query.proxyUrl,
        ) ?: return null
        return selectBestTrack(results, query)
    }

    private fun resolveIsrcTrack(
        query: Query,
    ): MatchedTrack? {
        val isrc = query.isrc
            ?.trim()
            ?.uppercase(Locale.US)
            ?.takeIf { it.matches(Regex("[A-Z]{2}[A-Z0-9]{3}[0-9]{7}")) }
            ?: return null
        val url = "https://api.deezer.com/track/isrc:$isrc".toHttpUrl()
        val request =
            Request
                .Builder()
                .url(wrapApiUrlForProxy(url, query.proxyUrl))
                .get()
                .header("Accept", "application/json")
                .header("User-Agent", BROWSER_USER_AGENT)
                .build()
        return runCatching {
            httpClient(query.proxyUrl).newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val payload = response.body.string().takeIf { it.isNotBlank() } ?: return@use null
                val obj = JSONObject(payload)
                if (obj.has("error")) return@use null
                val trackId = obj.stringOrNull("id") ?: return@use null
                MatchedTrack(
                    trackId = trackId,
                    title = obj.stringOrNull("title").orEmpty(),
                    artistNames = obj.collectArtistNames(),
                    album = obj.optJSONObject("album")?.stringOrNull("title"),
                    isrc = isrc,
                    durationMs = obj.longOrNull("duration")?.times(1000L),
                )
            }
        }.onFailure { error ->
            Timber.tag("DeezerAudio").d(error, "Deezer ISRC lookup failed for $isrc")
        }.getOrNull()
    }

    private fun searchTracks(
        term: String,
        limit: Int,
        proxyUrl: String,
    ): JSONArray? {
        val url = SEARCH_API_URL
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("q", term)
            .addQueryParameter("limit", limit.coerceIn(1, SEARCH_LIMIT).toString())
            .build()
        val request =
            Request
                .Builder()
                .url(wrapApiUrlForProxy(url, proxyUrl))
                .get()
                .header("Accept", "application/json")
                .header("User-Agent", BROWSER_USER_AGENT)
                .build()
        return runCatching {
            httpClient(proxyUrl).newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val payload = response.body.string().takeIf { it.isNotBlank() } ?: return@use null
                JSONObject(payload).optJSONArray("data")
            }
        }.onFailure { error ->
            Timber.tag("DeezerAudio").w(error, "Deezer public search failed for $term")
        }.getOrNull()
    }

    private fun selectBestTrack(
        results: JSONArray,
        query: Query,
    ): MatchedTrack? {
        val wantedTitle = query.title.titleMatchNormalized()
        val wantedArtists = query.artists.map { it.normalized() }.filter { it.isNotBlank() }
        val wantedAlbum = query.album.normalized()
        val wantedIsrc = query.isrc?.trim()?.uppercase(Locale.US).orEmpty()
        val wantedDurationMs = query.durationMs
        val wantedTitleTokens = significantTokens(wantedTitle)
        val wantedTitleTokenCount = wantedTitleTokens.size

        data class Candidate(
            val track: MatchedTrack,
            val score: Int,
        )

        val candidates = mutableListOf<Candidate>()
        for (index in 0 until results.length()) {
            val obj = results.optJSONObject(index) ?: continue
            val trackId = obj.stringOrNull("id") ?: continue
            val candidateTitle = obj.stringOrNull("title").titleMatchNormalized()
            val candidateArtists = obj.collectArtistNames().map { it.normalized() }.filter { it.isNotBlank() }
            val candidateAlbum = obj.optJSONObject("album")?.stringOrNull("title").normalized()
            val candidateIsrc = obj.stringOrNull("isrc")?.trim()?.uppercase(Locale.US).orEmpty()
            val candidateDurationMs = obj.longOrNull("duration")?.times(1000L)
            val exactTitleMatch = wantedTitle.isNotBlank() && candidateTitle == wantedTitle

            var score = 0
            if (wantedIsrc.isNotBlank() && candidateIsrc == wantedIsrc) {
                score += 1000
            }
            score += when {
                exactTitleMatch -> 120
                candidateTitle.contains(wantedTitle) || wantedTitle.contains(candidateTitle) -> 70
                else -> (tokenOverlap(wantedTitleTokens, significantTokens(candidateTitle)) * 70).roundToInt()
            }
            if (wantedArtists.isNotEmpty()) {
                val artistOverlap = wantedArtists.any { wanted ->
                    candidateArtists.any { candidate ->
                        candidate == wanted || candidate.contains(wanted) || wanted.contains(candidate)
                    }
                }
                score += if (artistOverlap) 80 else -70
            }
            if (wantedAlbum.isNotBlank() && candidateAlbum.isNotBlank()) {
                score += when {
                    candidateAlbum == wantedAlbum -> 35
                    candidateAlbum.contains(wantedAlbum) || wantedAlbum.contains(candidateAlbum) -> 18
                    else -> 0
                }
            }
            if (wantedDurationMs != null && candidateDurationMs != null) {
                val diff = abs(wantedDurationMs - candidateDurationMs)
                score += when {
                    diff <= 5_000L -> 45
                    diff <= 20_000L -> 20
                    diff <= 45_000L -> 4
                    else -> -90
                }
            }
            val versionMismatch = hasVersionMismatch(wantedTitle, candidateTitle)
            if (versionMismatch) {
                score -= 80
            }
            if (candidateTitle.isBlank()) {
                score = REJECT_SCORE
            }

            val exactSubtitleMatch =
                exactTitleMatch &&
                    wantedTitleTokenCount >= 2 &&
                    !versionMismatch &&
                    (wantedDurationMs == null || candidateDurationMs == null || abs(wantedDurationMs - candidateDurationMs) <= 45_000L) &&
                    (
                        wantedAlbum.isBlank() ||
                            candidateAlbum.isBlank() ||
                            candidateAlbum == wantedAlbum ||
                            candidateAlbum.contains(wantedAlbum) ||
                            wantedAlbum.contains(candidateAlbum)
                    )

            if (score >= MIN_MATCH_SCORE || exactSubtitleMatch || wantedIsrc.isNotBlank() && candidateIsrc == wantedIsrc) {
                candidates += Candidate(
                    track = MatchedTrack(
                        trackId = trackId,
                        title = obj.stringOrNull("title").orEmpty(),
                        artistNames = obj.collectArtistNames(),
                        album = obj.optJSONObject("album")?.stringOrNull("title"),
                        isrc = candidateIsrc.takeIf { it.isNotBlank() },
                        durationMs = candidateDurationMs,
                    ),
                    score = score,
                )
            }
        }
        return candidates.maxByOrNull { it.score }?.track
    }

    private fun requestResolverStream(
        resolverUrl: HttpUrl,
        mediaId: String,
        trackId: String,
        preferredQuality: DeezerAudioQuality,
        qualities: List<DeezerAudioQuality>,
        durationMs: Long?,
        proxyUrl: String,
    ): StreamAttempt {
        val bodyJson = JSONObject()
            .put(
                "formats",
                JSONArray().also { formats ->
                    qualities.distinct().forEach { formats.put(it.formatId) }
                },
            )
            .put("ids", JSONArray().put(trackId.toLongOrNull() ?: trackId))
            .put("fast", false)
        val targetUrl = resolverUrl
        val request =
            Request
                .Builder()
                .url(targetUrl)
                .post(bodyJson.toString().toRequestBody(JSON_MEDIA_TYPE))
                .header("Accept", "application/json")
                .header("User-Agent", BROWSER_USER_AGENT)
                .build()

        return runCatching {
            paceResolverRequests()
            if (!resolverPermits.tryAcquire(RESOLVER_PERMIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                return StreamAttempt(error = "Deezer resolver is busy")
            }
            try {
                httpClient(proxyUrl).newCall(request).execute().use { response ->
                    val payload = response.body.string()
                    if (!response.isSuccessful) {
                        return@use StreamAttempt(error = "Deezer resolver HTTP ${response.code}: ${payload.take(160)}")
                    }
                    parseMediaEnvelope(
                        payload = payload,
                        mediaId = mediaId,
                        trackId = trackId,
                        preferredQuality = preferredQuality,
                        durationMs = durationMs,
                        proxyUrl = proxyUrl,
                        sourceLabel = "Deezer resolver",
                    )
                }
            } finally {
                resolverPermits.release()
            }
        }.getOrElse { error ->
            if (error is InterruptedException) {
                Thread.currentThread().interrupt()
            }
            StreamAttempt(error = "Deezer resolver failed: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    /**
     * Parses a Deezer `get_url`-shaped media envelope (`{"data":[{"media":[...]}]}`) into a
     * [StreamAttempt]. Shared by the resolver-proxied path and the direct account path, since
     * both ultimately surface the same response shape from Deezer's media gateway.
     */
    private fun parseMediaEnvelope(
        payload: String,
        mediaId: String,
        trackId: String,
        preferredQuality: DeezerAudioQuality,
        durationMs: Long?,
        proxyUrl: String,
        sourceLabel: String,
    ): StreamAttempt {
        val root = JSONObject(payload)
        val media = root.optJSONArray("data")
            ?.optJSONObject(0)
            ?.optJSONArray("media")
            ?.selectMedia(preferredQuality)
            ?: return StreamAttempt(error = "$sourceLabel returned no media for $trackId")
        val source = media.optJSONArray("sources")
            ?.let { sources ->
                sources.optJSONObject(1) ?: sources.optJSONObject(0)
            }
            ?: return StreamAttempt(error = "$sourceLabel returned no source for $trackId")
        val streamUrl = source.stringOrNull("url")
            ?: return StreamAttempt(error = "$sourceLabel returned an empty stream URL for $trackId")
        val cipherType = media.optJSONObject("cipher")?.stringOrNull("type")
        if (!cipherType.equals("BF_CBC_STRIPE", ignoreCase = true)) {
            return StreamAttempt(error = "Deezer stream used unsupported cipher ${cipherType ?: "none"}")
        }

        val resolvedQuality = deezerQualityFromFormatId(media.stringOrNull("format"))
            ?: preferredQuality
        val contentLength = media.longOrNull("filesize")
        val bitrate = resolvedQuality.estimatedBitrate(contentLength, durationMs)
        val expiresAtMs = media.longOrNull("exp")
            ?.times(1000L)
            ?.minus(30_000L)
            ?.coerceAtLeast(System.currentTimeMillis() + 60_000L)
            ?: extractExpiryMs(streamUrl)
        val resolved = Resolved(
            mediaUri = DeezerAudioDataSource.buildUri(
                mediaId = mediaId,
                mediaUrl = streamUrl,
                trackId = trackId,
                contentLength = contentLength,
                proxyUrl = proxyUrl,
            ),
            trackId = trackId,
            label = "Deezer ${resolvedQuality.label}",
            mimeType = resolvedQuality.mimeType,
            codecs = resolvedQuality.codecs,
            bitrate = bitrate,
            sampleRate = 44_100,
            contentLength = contentLength,
            expiresAtMs = expiresAtMs,
        )
        return StreamAttempt(resolved = resolved)
    }

    /**
     * Bootstraps (or reuses) a Deezer web-session for the given `arl` cookie: exchanges it for
     * an `api_token` + `license_token` via `deezer.getUserData`, backed by a cookie jar that
     * accumulates any `sid`/session cookies Deezer sets along the way so they get replayed to
     * both gw-light and the media CDN — mirroring the per-ARL cookie jar dzmedia uses server-side.
     */
    private fun accountSession(cookie: String): AccountSession? {
        val trimmedCookie = cookie.trim()
        if (trimmedCookie.isBlank()) return null
        val cacheKey = trimmedCookie.hashCode().toString()
        val now = System.currentTimeMillis()
        accountSessions[cacheKey]?.takeIf { it.expiresAtMs > now }?.let { return it }

        val jar = DeezerAccountCookieJar(trimmedCookie)
        val sessionClient = client.newBuilder().cookieJar(jar).build()
        val userDataUrl = GW_LIGHT_URL.toHttpUrl().newBuilder()
            .addQueryParameter("method", "deezer.getUserData")
            .addQueryParameter("input", "3")
            .addQueryParameter("api_version", "1.0")
            .addQueryParameter("api_token", "")
            .build()
        val request = Request.Builder()
            .url(userDataUrl)
            .post("".toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "application/json")
            .header("User-Agent", BROWSER_USER_AGENT)
            .header("Cookie", jar.headerValue())
            .build()

        return runCatching {
            sessionClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val payload = response.body.string().takeIf { it.isNotBlank() } ?: return@use null
                val results = JSONObject(payload).optJSONObject("results") ?: return@use null
                val apiToken = results.stringOrNull("checkForm") ?: return@use null
                val userObject = results.optJSONObject("USER")
                val userId = userObject?.let { if (it.has("USER_ID")) it.optLong("USER_ID") else 0L } ?: 0L
                if (userId <= 0L) return@use null
                val licenseToken = userObject.optJSONObject("OPTIONS")?.stringOrNull("license_token").orEmpty()
                AccountSession(
                    apiToken = apiToken,
                    licenseToken = licenseToken,
                    cookie = jar.headerValue(),
                    client = sessionClient,
                    expiresAtMs = now + ACCOUNT_SESSION_TTL_MS,
                )
            }
        }.getOrNull()?.also {
            // Track tokens are minted against this session: drop tokens from
            // the previous session so a rotated api_token can't serve stale ones.
            accountTrackTokens.clear()
            accountSessions[cacheKey] = it
        }
    }

    private fun fetchAccountTrackToken(
        session: AccountSession,
        trackId: String,
    ): String? {
        val now = System.currentTimeMillis()
        val cacheKey = "${session.apiToken.hashCode()}::$trackId"
        accountTrackTokens[cacheKey]?.takeIf { it.expiresAtMs > now + 20_000L }?.let { return it.token }
        val url = GW_LIGHT_URL.toHttpUrl().newBuilder()
            .addQueryParameter("method", "song.getListData")
            .addQueryParameter("input", "3")
            .addQueryParameter("api_version", "1.0")
            .addQueryParameter("api_token", session.apiToken)
            .build()
        val body = JSONObject().put("sng_ids", JSONArray().put(trackId.toLongOrNull() ?: trackId))
        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "application/json")
            .header("User-Agent", BROWSER_USER_AGENT)
            .header("Cookie", session.cookie)
            .build()
        return runCatching {
            session.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val payload = response.body.string().takeIf { it.isNotBlank() } ?: return@use null
                val data = JSONObject(payload)
                    .optJSONObject("results")
                    ?.optJSONArray("data")
                    ?.optJSONObject(0) ?: return@use null
                val token = data.stringOrNull("TRACK_TOKEN") ?: return@use null
                val expiresAtMs = data.optLong("TRACK_TOKEN_EXPIRE", 0L)
                    .takeIf { it > 0L }
                    ?.times(1000L)
                    ?.minus(30_000L)
                    ?: (now + TRACK_TOKEN_FALLBACK_TTL_MS)
                accountTrackTokens[cacheKey] = AccountTrackToken(token, expiresAtMs, now)
                if (accountTrackTokens.size > TRACK_TOKEN_CACHE_MAX_SIZE) {
                    repeat((accountTrackTokens.size - TRACK_TOKEN_CACHE_MAX_SIZE).coerceAtLeast(1)) {
                        val oldest = accountTrackTokens.entries.minByOrNull { it.value.createdAtMs } ?: return@repeat
                        accountTrackTokens.remove(oldest.key)
                    }
                }
                token
            }
        }.getOrNull()
    }

    /**
     * Direct-to-Deezer path: fetches a track token from the user's own session, then calls
     * Deezer's media gateway directly with it — bypassing dzmedia entirely. Decryption is
     * unchanged: this returns the same BF_CBC_STRIPE-ciphered CDN URL shape the resolver path
     * does, so it flows through the existing [DeezerAudioDataSource] decryptor untouched.
     */
    private fun requestAccountStream(
        cookie: String,
        mediaId: String,
        trackId: String,
        preferredQuality: DeezerAudioQuality,
        qualities: List<DeezerAudioQuality>,
        durationMs: Long?,
        proxyUrl: String,
    ): StreamAttempt {
        val session = run {
            val startMs = System.currentTimeMillis()
            val result = accountSession(cookie)
            Timber.tag("DeezerLatency").d(
                "  accountSession() for $trackId: ${System.currentTimeMillis() - startMs}ms (${if (result != null) "cached/ok" else "failed"})",
            )
            result
        } ?: return StreamAttempt(error = "Deezer account session unavailable (check login)")
        val trackToken = run {
            val startMs = System.currentTimeMillis()
            val result = fetchAccountTrackToken(session, trackId)
            Timber.tag("DeezerLatency").d(
                "  fetchAccountTrackToken() for $trackId: ${System.currentTimeMillis() - startMs}ms (${if (result != null) "ok" else "failed"})",
            )
            result
        } ?: return StreamAttempt(error = "Deezer account session could not fetch track token for $trackId")

        val bodyJson = JSONObject()
            .put("track_tokens", JSONArray().put(trackToken))
            .put("license_token", session.licenseToken)
            .put(
                "media",
                JSONArray().put(
                    JSONObject()
                        .put("type", "FULL")
                        .put(
                            "formats",
                            JSONArray().also { formats ->
                                qualities.distinct().forEach { quality ->
                                    formats.put(
                                        JSONObject()
                                            .put("cipher", "BF_CBC_STRIPE")
                                            .put("format", quality.formatId),
                                    )
                                }
                            },
                        ),
                ),
            )
        val request = Request.Builder()
            .url(GET_URL_URL)
            .post(bodyJson.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "application/json")
            .header("User-Agent", BROWSER_USER_AGENT)
            .header("Cookie", session.cookie)
            .build()

        return runCatching {
            val getUrlStartMs = System.currentTimeMillis()
            session.client.newCall(request).execute().use { response ->
                val payload = response.body.string()
                Timber.tag("DeezerLatency").d(
                    "  get_url() for $trackId: ${System.currentTimeMillis() - getUrlStartMs}ms (HTTP ${response.code})",
                )
                if (!response.isSuccessful) {
                    return@use StreamAttempt(error = "Deezer account media HTTP ${response.code}: ${payload.take(160)}")
                }
                parseMediaEnvelope(
                    payload = payload,
                    mediaId = mediaId,
                    trackId = trackId,
                    preferredQuality = preferredQuality,
                    durationMs = durationMs,
                    proxyUrl = proxyUrl,
                    sourceLabel = "Deezer account session",
                )
            }
        }.getOrElse { error ->
            StreamAttempt(error = "Deezer account stream failed: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun paceResolverRequests() {
        val minIntervalMs = RESOLVER_MIN_INTERVAL_MS
        while (true) {
            val now = System.currentTimeMillis()
            val previous = lastResolverRequestAtMs.get()
            val nextAllowed = previous + minIntervalMs
            if (now >= nextAllowed) {
                if (lastResolverRequestAtMs.compareAndSet(previous, now)) return
            } else if (lastResolverRequestAtMs.compareAndSet(previous, nextAllowed)) {
                Thread.sleep(nextAllowed - now)
                return
            }
        }
    }

    fun clientForProxy(
        baseClient: OkHttpClient,
        proxyUrl: String,
    ): OkHttpClient {
        val config = proxyConfig(proxyUrl) ?: return baseClient
        val cacheKey = "${System.identityHashCode(baseClient)}::${config.key}"
        return proxiedClients.getOrPut(cacheKey) {
            baseClient
                .newBuilder()
                .proxy(
                    Proxy(
                        Proxy.Type.HTTP,
                        InetSocketAddress.createUnresolved(config.host, config.port),
                    ),
                )
                .build()
        }
    }

    private fun httpClient(
        proxyUrl: String = DEFAULT_PROXY_URL,
    ): OkHttpClient =
        clientForProxy(fastClient, proxyUrl)

    private fun proxyConfig(value: String): ProxyConfig? {
        val raw = value.trim()
        if (raw.isBlank()) return null
        if (mediaProxyTemplate(raw) != null) return null

        val withScheme = if (raw.contains("://")) raw else "http://$raw"
        val parsed = withScheme.toHttpUrlOrNull() ?: return null
        val hasExplicitPort = raw.hasExplicitProxyPort()
        if (raw.contains("://") && !hasExplicitPort) return null
        val host = parsed.host.takeIf { it.isNotBlank() } ?: return null
        val port = if (hasExplicitPort) parsed.port else DEFAULT_PROXY_PORT
        return ProxyConfig(host, port.coerceIn(1, 65_535))
    }

    private fun mediaProxyTemplate(value: String): String? {
        val raw = value.trim()
        if (raw.isBlank()) return null
        if (!raw.startsWith("http://", ignoreCase = true) && !raw.startsWith("https://", ignoreCase = true)) {
            return null
        }

        val parsed = raw.toHttpUrlOrNull() ?: return null
        val hasExplicitPort = raw.hasExplicitProxyPort()
        val path = parsed.encodedPath.trimEnd('/')
        val isBaseServer = !hasExplicitPort && (path.isBlank() || path == "/")
        val hasUrlSlot =
            raw.contains("{url}") ||
                raw.endsWith("?url=", ignoreCase = true) ||
                raw.endsWith("&url=", ignoreCase = true)
        val isProxyEndpoint = path.contains("proxy", ignoreCase = true)
        val isResolverEndpoint =
            path.equals("/get_url", ignoreCase = true) ||
                path.equals("/get_url/fast", ignoreCase = true)
        if (!isBaseServer && !hasUrlSlot && !isProxyEndpoint && !isResolverEndpoint) return null

        if (hasUrlSlot) return raw

        val endpoint = if (isBaseServer || isResolverEndpoint) {
            parsed.newBuilder()
                .encodedPath(DEFAULT_MEDIA_PROXY_PATH)
                .query(null)
                .build()
                .toString()
        } else {
            parsed.toString()
        }
        return if (endpoint.contains("?")) "$endpoint&url=" else "$endpoint?url="
    }

    private fun apiProxyTemplate(value: String): String? {
        val raw = value.trim()
        if (raw.isBlank()) return null
        if (!raw.startsWith("http://", ignoreCase = true) && !raw.startsWith("https://", ignoreCase = true)) {
            return null
        }

        val parsed = raw.toHttpUrlOrNull() ?: return null
        val hasExplicitPort = raw.hasExplicitProxyPort()
        val path = parsed.encodedPath.trimEnd('/')
        val isBaseServer = !hasExplicitPort && (path.isBlank() || path == "/")
        val hasUrlSlot =
            raw.contains("{url}") ||
                raw.endsWith("?url=", ignoreCase = true) ||
                raw.endsWith("&url=", ignoreCase = true)
        val isApiProxyEndpoint = path.contains("api-proxy", ignoreCase = true) || path.contains("api_proxy", ignoreCase = true)
        val isMediaProxyEndpoint = path.contains("media-proxy", ignoreCase = true) || path.contains("media_proxy", ignoreCase = true)
        val isResolverEndpoint =
            path.equals("/get_url", ignoreCase = true) ||
                path.equals("/get_url/fast", ignoreCase = true)
        if (!isBaseServer && !hasUrlSlot && !isApiProxyEndpoint && !isMediaProxyEndpoint && !isResolverEndpoint) return null

        if (hasUrlSlot && isApiProxyEndpoint) return raw

        val endpoint =
            parsed.newBuilder()
                .encodedPath(DEFAULT_API_PROXY_PATH)
                .query(null)
                .build()
                .toString()
        return if (endpoint.contains("?")) "$endpoint&url=" else "$endpoint?url="
    }

    private fun String.hasExplicitProxyPort(): Boolean {
        val authority = substringAfter("://", this)
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
        if (authority.startsWith("[")) {
            return authority.substringAfter("]", "").startsWith(":")
        }
        return authority.contains(':') && authority.substringAfterLast(':').toIntOrNull() != null
    }

    private fun searchTerms(query: Query): List<String> =
        buildList {
            val titles = query.title.searchQueryTitles()
            val primaryArtist = query.artists.firstOrNull()?.searchQueryArtist().orEmpty()
            val allArtists = query.artists.joinToString(" ") { it.searchQueryArtist() }
            val album = query.album?.searchQueryTitle().orEmpty()
            for (title in titles) {
                add(listOf(title, primaryArtist).filter { it.isNotBlank() }.joinToString(" "))
                add(listOf(title, allArtists).filter { it.isNotBlank() }.joinToString(" "))
                add(listOf(title, primaryArtist, album).filter { it.isNotBlank() }.joinToString(" "))
                add(title)
            }
        }.map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

    private fun qualityFallbackOrder(preferred: DeezerAudioQuality): List<DeezerAudioQuality> =
        when (preferred) {
            DeezerAudioQuality.FLAC -> listOf(DeezerAudioQuality.FLAC, DeezerAudioQuality.MP3_320, DeezerAudioQuality.MP3_128)
            DeezerAudioQuality.MP3_320 -> listOf(DeezerAudioQuality.MP3_320, DeezerAudioQuality.MP3_128)
            DeezerAudioQuality.MP3_128 -> listOf(DeezerAudioQuality.MP3_128)
        }

    private val DeezerAudioQuality.formatId: String
        get() =
            when (this) {
                DeezerAudioQuality.FLAC -> "FLAC"
                DeezerAudioQuality.MP3_320 -> "MP3_320"
                DeezerAudioQuality.MP3_128 -> "MP3_128"
            }

    private val DeezerAudioQuality.mimeType: String
        get() =
            when (this) {
                DeezerAudioQuality.FLAC -> MimeTypes.AUDIO_FLAC
                DeezerAudioQuality.MP3_320,
                DeezerAudioQuality.MP3_128 -> MimeTypes.AUDIO_MPEG
            }

    private val DeezerAudioQuality.codecs: String
        get() =
            when (this) {
                DeezerAudioQuality.FLAC -> "flac"
                DeezerAudioQuality.MP3_320,
                DeezerAudioQuality.MP3_128 -> "mp3"
            }

    private val DeezerAudioQuality.label: String
        get() =
            when (this) {
                DeezerAudioQuality.FLAC -> "FLAC"
                DeezerAudioQuality.MP3_320 -> "MP3 320"
                DeezerAudioQuality.MP3_128 -> "MP3 128"
            }

    private fun DeezerAudioQuality.estimatedBitrate(
        contentLength: Long?,
        durationMs: Long?,
    ): Int {
        val estimated = estimateBitrate(contentLength, durationMs)
        return when (this) {
            DeezerAudioQuality.FLAC -> estimated ?: 1_411_000
            DeezerAudioQuality.MP3_320 -> estimated ?: 320_000
            DeezerAudioQuality.MP3_128 -> estimated ?: 128_000
        }
    }

    private fun estimateBitrate(
        contentLength: Long?,
        durationMs: Long?,
    ): Int? {
        val length = contentLength?.takeIf { it > 0L } ?: return null
        val duration = durationMs?.takeIf { it > 0L } ?: return null
        val bitrate = (length * 8L * 1000L) / duration
        return bitrate
            .takeIf { it in 32_000L..20_000_000L }
            ?.coerceAtMost(Int.MAX_VALUE.toLong())
            ?.toInt()
    }

    private fun extractExpiryMs(url: String): Long {
        val now = System.currentTimeMillis()
        val httpUrl = url.toHttpUrlOrNull() ?: return now + STREAM_CACHE_MS
        httpUrl.queryParameter("hdnea")
            ?.let { hdnea ->
                Regex("""(?:^|~)exp=(\d+)""")
                    .find(hdnea)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toLongOrNull()
                    ?.let { return (it * 1000L - 30_000L).coerceAtLeast(now + 60_000L) }
            }

        val expiresSeconds = httpUrl.queryParameterIgnoreCase("X-Amz-Expires")?.toLongOrNull()
        val date = httpUrl.queryParameterIgnoreCase("X-Amz-Date")
        if (expiresSeconds != null && date != null) {
            runCatching {
                val issuedAt = LocalDateTime.parse(date, AMAZON_DATE).toInstant(ZoneOffset.UTC).toEpochMilli()
                return (issuedAt + expiresSeconds * 1000L - 30_000L).coerceAtLeast(now + 60_000L)
            }
        }
        return now + STREAM_CACHE_MS
    }

    private fun Query.toDirectMatchedTrack(trackId: String): MatchedTrack =
        MatchedTrack(
            trackId = trackId,
            title = title,
            artistNames = artists,
            album = album,
            isrc = isrc,
            durationMs = durationMs,
        )

    /**
     * Feeds a resolved match's ISRC into the shared resolver cache — keyed
     * by both the played query (often a YTM video-style title) and the
     * canonical track form. Only scored matches reach here (MIN_MATCH_SCORE
     * in findBestTrack / selectBestTrack), so a harvested ISRC is as
     * trustworthy as a resolver lookup. Never throws.
     */
    private fun harvestIsrc(query: Query, track: MatchedTrack) {
        val isrc = track.isrc?.takeIf { it.isNotBlank() } ?: return
        runCatching {
            val durationSec = (query.durationMs ?: track.durationMs)?.let { (it / 1000L).toInt() }
            query.artists.firstOrNull()?.let { artist ->
                IsrcResolver.publish(query.title, artist, isrc, durationSec)
            }
            track.artistNames.firstOrNull()?.let { artist ->
                IsrcResolver.publish(track.title, artist, isrc, durationSec)
            }
        }
    }

    private fun MatchedTrack.toCandidateMetadata(): CandidateMetadata =
        CandidateMetadata(
            trackId = trackId,
            title = title,
            artist = artistNames.joinToString(", "),
            album = album,
            isrc = isrc,
            durationMs = durationMs,
        )

    private fun JSONObject.toCandidateMetadata(): CandidateMetadata? {
        val trackId = stringOrNull("id") ?: return null
        val title = stringOrNull("title") ?: return null
        val artists = collectArtistNames()
        return CandidateMetadata(
            trackId = trackId,
            title = title,
            artist = artists.joinToString(", "),
            album = optJSONObject("album")?.stringOrNull("title"),
            isrc = stringOrNull("isrc"),
            durationMs = longOrNull("duration")?.times(1000L),
        )
    }

    private fun Query.trackCacheKey(): String =
        listOf(
            title.normalized(),
            artists.joinToString(",") { it.normalized() },
            album.normalized(),
            isrc?.trim()?.uppercase(Locale.US).orEmpty(),
            durationMs?.div(1000L)?.toString().orEmpty(),
            "proxy=${normalizeProxyUrl(proxyUrl).hashCode()}",
        ).joinToString("::")

    private fun String.toDeezerTrackIdOrNull(allowPlainNumeric: Boolean): String? {
        val trimmed = trim()
        if (allowPlainNumeric && trimmed.matches(Regex("\\d+"))) return trimmed
        Regex("""^deezer:track:(\d+)$""", RegexOption.IGNORE_CASE)
            .matchEntire(trimmed)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { return it }
        Regex("""deezer\.com/(?:[a-z]{2}/)?track/(\d+)""", RegexOption.IGNORE_CASE)
            .find(trimmed)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { return it }
        return null
    }

    private fun JSONArray.selectMedia(preferred: DeezerAudioQuality): JSONObject? {
        val exactFormat = preferred.formatId
        for (index in 0 until length()) {
            val media = optJSONObject(index) ?: continue
            if (media.stringOrNull("format").equals(exactFormat, ignoreCase = true)) return media
        }
        return optJSONObject(0)
    }

    private fun JSONObject.collectArtistNames(): List<String> {
        val names = mutableListOf<String>()
        optJSONObject("artist")?.stringOrNull("name")?.takeIf { it.isNotBlank() }?.let(names::add)
        optJSONArray("contributors")?.let { array ->
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.stringOrNull("name")?.takeIf { it.isNotBlank() }?.let(names::add)
            }
        }
        return names.distinct()
    }

    private fun String?.normalized(): String =
        this
            ?.lowercase(Locale.US)
            ?.let { Normalizer.normalize(it, Normalizer.Form.NFD) }
            ?.replace(Regex("\\p{Mn}+"), "")
            ?.replace(Regex("[^a-z0-9]+"), " ")
            ?.trim()
            .orEmpty()

    private fun String?.titleMatchNormalized(): String =
        normalized()
            .replace(Regex("""\b(feat|ft|featuring)\b.*$"""), "")
            .replace(Regex("""\b(explicit|clean|remaster|remastered|version|audio|official)\b"""), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun String.searchQueryTitle(): String =
        trim()
            .replace(Regex("""\s*[\[(]\s*(feat\.?|ft\.?|featuring)\b.*?[\])]""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*-\s*(explicit|clean|remaster(?:ed)?|audio|official)\b.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun String.searchQueryTitles(): List<String> =
        buildList {
            val base = searchQueryTitle()
            add(base)
            add(base.titleMatchNormalized())
            add(base.replace(Regex("""[\[(]([^)\]]+)[)\]]"""), " $1 "))
            add(base.replace(Regex("""[\[(][^)\]]+[)\]]"""), " "))
        }.map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotBlank() }
            .distinct()

    private fun String.searchQueryArtist(): String =
        trim()
            .substringBefore(',')
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun significantTokens(value: String): Set<String> =
        value
            .split(' ')
            .map { it.trim() }
            .filter { it.length >= 2 && it !in STOP_WORDS }
            .toSet()

    private fun tokenOverlap(
        wanted: Set<String>,
        candidate: Set<String>,
    ): Double {
        if (wanted.isEmpty() || candidate.isEmpty()) return 0.0
        val shared = wanted.intersect(candidate).size
        return shared.toDouble() / wanted.size.coerceAtLeast(candidate.size).toDouble()
    }

    private fun hasVersionMismatch(
        wanted: String,
        candidate: String,
    ): Boolean {
        val wantedLive = wanted.contains(" live ")
        val candidateLive = candidate.contains(" live ")
        if (wantedLive != candidateLive) return true
        val wantedInstrumental = wanted.contains(" instrumental ")
        val candidateInstrumental = candidate.contains(" instrumental ")
        if (wantedInstrumental != candidateInstrumental) return true
        return VERSION_TOKENS.any { token ->
            wanted.hasToken(token) != candidate.hasToken(token)
        }
    }

    private fun String.hasToken(token: String): Boolean =
        split(' ').any { it == token }

    private fun JSONObject.stringOrNull(key: String): String? =
        optString(key).takeIf { it.isNotBlank() && it != "null" }

    private fun JSONObject.stringIgnoreCase(key: String): String? {
        val keys = keys()
        while (keys.hasNext()) {
            val candidate = keys.next()
            if (candidate.equals(key, ignoreCase = true)) {
                return stringOrNull(candidate)
            }
        }
        return null
    }

    private fun JSONObject.longOrNull(key: String): Long? =
        if (has(key)) optLong(key).takeIf { it > 0L } else null

    private fun HttpUrl.queryParameterIgnoreCase(name: String): String? {
        val key = queryParameterNames.firstOrNull { it.equals(name, ignoreCase = true) } ?: return null
        return queryParameter(key)
    }

    private fun deezerQualityFromFormatId(format: String?): DeezerAudioQuality? =
        when (format?.uppercase(Locale.US)) {
            "FLAC" -> DeezerAudioQuality.FLAC
            "MP3_320" -> DeezerAudioQuality.MP3_320
            "MP3_128" -> DeezerAudioQuality.MP3_128
            else -> null
        }

    private val STOP_WORDS = setOf("the", "a", "an", "and", "or", "of", "to", "in")
    private val VERSION_TOKENS = setOf("live", "remix", "acoustic", "instrumental", "sped", "slowed", "chopped", "choppednotslopped", "slopped")
}

@UnstableApi
class DeezerAudioDataSource(
    private val client: OkHttpClient = defaultClient,
) : BaseDataSource(true) {
    private var opened = false
    private var currentUri: Uri? = null
    private var currentStream: InputStream? = null
    private var currentResponse: okhttp3.Response? = null
    private var bytesRemaining = C.LENGTH_UNSET.toLong()

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val request = RequestParams.fromUri(dataSpec.uri)
        val position = dataSpec.position.coerceAtLeast(0L)
        val alignedPosition = position - (position % BLOCK_SIZE)
        val dropBytes = (position - alignedPosition).toInt()
        val requestBuilder =
            Request
                .Builder()
                .url(DeezerAudioProvider.wrapMediaUrlForProxy(request.mediaUrl, request.proxyUrl))
                .get()
                .header("User-Agent", USER_AGENT)
        if (alignedPosition > 0L || dataSpec.length != C.LENGTH_UNSET.toLong()) {
            requestBuilder.header("Range", "bytes=$alignedPosition-")
        }
        val requestClient = DeezerAudioProvider.clientForProxy(client, request.proxyUrl)
        val response = requestClient.newCall(requestBuilder.build()).execute()
        if (!response.isSuccessful) {
            response.close()
            throw IOException("Deezer stream HTTP ${response.code}")
        }
        if (alignedPosition > 0L && response.code == 200) {
            response.close()
            throw IOException("Deezer stream ignored range request")
        }
        val body = response.body
        currentResponse = response
        currentUri = dataSpec.uri
        val requestedLength = dataSpec.length
        bytesRemaining = requestedLength
            .takeIf { it != C.LENGTH_UNSET.toLong() }
            ?: request.contentLength
                ?.minus(position)
                ?.coerceAtLeast(0L)
            ?: C.LENGTH_UNSET.toLong()
        currentStream = DeezerDecryptingInputStream(
            encrypted = body.byteStream(),
            trackId = request.trackId,
            blockIndex = alignedPosition / BLOCK_SIZE,
            initialDropBytes = dropBytes,
            requestedLength = bytesRemaining,
        )
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val readLength = if (bytesRemaining == C.LENGTH_UNSET.toLong()) {
            length
        } else {
            min(length.toLong(), bytesRemaining).toInt()
        }
        val read = currentStream?.read(buffer, offset, readLength) ?: return C.RESULT_END_OF_INPUT
        if (read == -1) return C.RESULT_END_OF_INPUT
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
            bytesRemaining -= read.toLong()
        }
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = currentUri

    override fun close() {
        currentUri = null
        currentStream?.close()
        currentStream = null
        currentResponse?.close()
        currentResponse = null
        bytesRemaining = C.LENGTH_UNSET.toLong()
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    data class RequestParams(
        val mediaId: String,
        val mediaUrl: String,
        val trackId: String,
        val contentLength: Long?,
        val proxyUrl: String,
    ) {
        companion object {
            fun fromUri(uri: Uri): RequestParams {
                val mediaId = DeezerAudioDataSource.mediaIdFromUri(uri)
                    ?: throw IOException("Deezer stream URI is missing mediaId")
                val mediaUrl = uri.getQueryParameter(PARAM_URL)
                    ?: throw IOException("Deezer stream URI is missing URL")
                val trackId = uri.getQueryParameter(PARAM_TRACK_ID)
                    ?: throw IOException("Deezer stream URI is missing trackId")
                val contentLength = uri.getQueryParameter(PARAM_CONTENT_LENGTH)?.toLongOrNull()
                val proxyUrl = uri.getQueryParameter(PARAM_PROXY_URL)
                    ?.let(DeezerAudioProvider::normalizeProxyUrl)
                    .orEmpty()
                return RequestParams(mediaId, mediaUrl, trackId, contentLength, proxyUrl)
            }
        }
    }

    class Factory(
        private val client: OkHttpClient = defaultClient,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = DeezerAudioDataSource(client)
    }

    companion object {
        const val SCHEME = "metrofuse-deezer"
        private const val AUTHORITY = "stream"
        private const val PARAM_URL = "url"
        private const val PARAM_TRACK_ID = "trackId"
        private const val PARAM_CONTENT_LENGTH = "contentLength"
        private const val PARAM_PROXY_URL = "proxyUrl"
        private const val BLOCK_SIZE = 2048
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Mobile Safari/537.36"

        private val defaultClient =
            OkHttpClient
                .Builder()
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .build()

        fun isDeezerUri(uri: Uri): Boolean = uri.scheme == SCHEME

        fun mediaIdFromUri(uri: Uri): String? =
            uri.takeIf(::isDeezerUri)
                ?.pathSegments
                ?.firstOrNull()
                ?.takeIf { it.isNotBlank() }

        fun buildUri(
            mediaId: String,
            mediaUrl: String,
            trackId: String,
            contentLength: Long?,
            proxyUrl: String = DeezerAudioProvider.DEFAULT_PROXY_URL,
        ): String {
            val builder =
                Uri
                    .Builder()
                    .scheme(SCHEME)
                    .authority(AUTHORITY)
                    .appendPath(mediaId)
                    .appendQueryParameter(PARAM_URL, mediaUrl)
                    .appendQueryParameter(PARAM_TRACK_ID, trackId)
            contentLength?.takeIf { it > 0L }?.let {
                builder.appendQueryParameter(PARAM_CONTENT_LENGTH, it.toString())
            }
            proxyUrl.takeIf { it.isNotBlank() }?.let {
                builder.appendQueryParameter(PARAM_PROXY_URL, it)
            }
            return builder.build().toString()
        }
    }
}

private class DeezerDecryptingInputStream(
    private val encrypted: InputStream,
    private val trackId: String,
    private var blockIndex: Long,
    private var initialDropBytes: Int,
    private val requestedLength: Long,
) : InputStream() {
    private val secretKey = deezerBlowfishKey(trackId)
    private val cipher = Cipher.getInstance("Blowfish/CBC/NoPadding")
    private val encryptedBlock = ByteArray(BLOCK_SIZE)
    private val outputBlock = ByteArray(BLOCK_SIZE)
    private var outputOffset = 0
    private var outputLength = 0
    private var servedBytes = 0L

    override fun read(): Int {
        val single = ByteArray(1)
        val read = read(single, 0, 1)
        return if (read == -1) -1 else single[0].toInt() and 0xff
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (length == 0) return 0
        if (requestedLength != C.LENGTH_UNSET.toLong() && servedBytes >= requestedLength) return -1

        var totalRead = 0
        var targetOffset = offset
        var remaining = if (requestedLength == C.LENGTH_UNSET.toLong()) {
            length
        } else {
            min(length.toLong(), requestedLength - servedBytes).toInt()
        }
        while (remaining > 0) {
            if (outputOffset >= outputLength && !loadNextBlock()) break
            val toCopy = min(remaining, outputLength - outputOffset)
            System.arraycopy(outputBlock, outputOffset, buffer, targetOffset, toCopy)
            outputOffset += toCopy
            targetOffset += toCopy
            totalRead += toCopy
            remaining -= toCopy
            servedBytes += toCopy.toLong()
        }
        return if (totalRead > 0) totalRead else -1
    }

    override fun close() {
        encrypted.close()
    }

    private fun loadNextBlock(): Boolean {
        while (true) {
            val read = encrypted.readBlock(encryptedBlock)
            if (read <= 0) return false
            val outputRead =
                if (read == BLOCK_SIZE && blockIndex % 3L == 0L) {
                    cipher.init(Cipher.DECRYPT_MODE, secretKey, DEEZER_IV)
                    cipher.doFinal(encryptedBlock, 0, read, outputBlock, 0)
                } else {
                    System.arraycopy(encryptedBlock, 0, outputBlock, 0, read)
                    read
                }
            blockIndex++

            val start = if (initialDropBytes > 0) {
                val drop = min(initialDropBytes, outputRead)
                initialDropBytes -= drop
                drop
            } else {
                0
            }
            if (start >= outputRead) continue
            if (start > 0) {
                System.arraycopy(outputBlock, start, outputBlock, 0, outputRead - start)
            }
            outputOffset = 0
            outputLength = outputRead - start
            return true
        }
    }

    private fun InputStream.readBlock(buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val read = read(buffer, total, buffer.size - total)
            if (read == -1) break
            total += read
        }
        return total
    }

    private companion object {
        private const val BLOCK_SIZE = 2048
    }
}

@UnstableApi
class DeezerAudioAwareDataSourceFactory(
    private val normalFactory: DataSource.Factory,
    private val deezerFactory: DataSource.Factory = DeezerAudioDataSource.Factory(),
) : DataSource.Factory {
    override fun createDataSource(): DataSource =
        DeezerAudioAwareDataSource(
            normal = normalFactory.createDataSource(),
            deezer = deezerFactory.createDataSource(),
        )
}

@UnstableApi
private class DeezerAudioAwareDataSource(
    private val normal: DataSource,
    private val deezer: DataSource,
) : DataSource {
    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        normal.addTransferListener(transferListener)
        deezer.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val selected = if (DeezerAudioDataSource.isDeezerUri(dataSpec.uri)) deezer else normal
        active = selected
        return selected.open(dataSpec)
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int = active?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT

    override fun getUri(): Uri? = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        active?.responseHeaders ?: emptyMap()

    override fun close() {
        active?.close()
        active = null
    }
}

private fun deezerBlowfishKey(trackId: String): SecretKeySpec =
    DEEZER_KEYS.getOrPut(trackId) {
        val digest = MessageDigest
            .getInstance("MD5")
            .digest(trackId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val key = ByteArray(16) { index ->
            (
                digest[index].code xor
                    digest[index + 16].code xor
                    DEEZER_SECRET[index].code
            ).toByte()
        }
        SecretKeySpec(key, "Blowfish")
    }

private val DEEZER_KEYS = ConcurrentHashMap<String, SecretKeySpec>()
private const val DEEZER_SECRET = "g4el58wc0zvf9na1"
private val DEEZER_IV = IvParameterSpec(ByteArray(8) { it.toByte() })
