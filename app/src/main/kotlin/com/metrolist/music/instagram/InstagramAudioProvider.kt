/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.instagram

import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.text.Normalizer
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs

object InstagramAudioProvider {
    const val STREAM_MARKER_QUERY = "_metrofuse_instagram"
    const val STREAM_CLIENT_QUERY = "_metrofuse_instagram_client"
    const val STREAM_USER_AGENT_QUERY = "_metrofuse_instagram_ua"
    const val DEFAULT_USER_AGENT =
        "Instagram 385.0.0.47.74 Android (26/8.0.0; 480dpi; 1080x1920; OnePlus; 6T Dev; devitron; qcom; en_US; 378906843)"
    const val DEFAULT_APP_ID = "567067343352427"
    private const val IOS_USER_AGENT =
        "Instagram 426.0.0.0.1 (iPhone16,2; iOS 18_4_1; en_US; en; scale=3.00; 1290x2796; 762104867)"
    private const val IOS_APP_ID = "124024574287414"
    const val WEB_LOGIN_USER_AGENT =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 18_4_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.4 Mobile/15E148 Safari/604.1"

    private const val TAG = "InstagramAudioProvider"
    private const val API_BASE_URL = "https://i.instagram.com/api/v1"
    private const val WEB_APP_ID = "936619743392459"
    private const val STREAM_CLIENT_ANDROID = "android"
    private const val STREAM_CLIENT_IOS = "ios"
    private const val MAX_SEARCH_CANDIDATES = 5
    private const val MAX_DETAIL_CANDIDATES = 3
    private const val MAX_AUDIO_ASSET_IDS = 8
    private const val STREAM_CACHE_TTL_MS = 20 * 60 * 1000L
    private const val STREAM_CACHE_MAX_SIZE = 80
    private const val DEFAULT_BITRATE = 128_000
    private const val DEFAULT_SAMPLE_RATE = 44_100
    private val ISRC_REGEX = Regex("[A-Z]{2}[A-Z0-9]{3}\\d{7}")

    data class Query(
        val mediaId: String,
        val title: String,
        val artists: List<String>,
        val album: String?,
        val durationMs: Long?,
        val isrc: String? = null,
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

    class InstagramAudioResolutionException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private data class Credentials(
        val cookie: String,
        val uuid: String,
        val userAgent: String,
        val appId: String,
    ) {
        val cookies: Map<String, String> by lazy { cookie.parseCookies() }
        val userId: String get() = cookies["ds_user_id"].orEmpty()
        val csrf: String get() = cookies["csrftoken"].orEmpty()
        val mid: String get() = cookies["mid"].orEmpty()
        val sessionId: String get() = cookies["sessionid"].orEmpty()
        val streamClient: String get() = if (isIosClient()) STREAM_CLIENT_IOS else STREAM_CLIENT_ANDROID
    }

    private data class ResolvedCandidate(
        val payload: JSONObject,
        val fallbackPayload: JSONObject,
        val score: Int,
        val source: AudioSource?,
    )

    private data class AudioSource(
        val url: String,
        val mimeType: String,
        val codecs: String,
        val bitrate: Int?,
        val sampleRate: Int?,
        val contentLength: Long?,
        val priority: Int,
        val label: String,
    )

    private data class DashManifest(
        val manifest: String,
        val path: String,
    )

    private data class CacheEntry(
        val resolved: Resolved,
        val createdAtMs: Long,
    )

    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()

    private val streamCache = ConcurrentHashMap<String, CacheEntry>()

    fun resolve(
        query: Query,
        cookie: String,
        uuid: String? = null,
        userAgent: String? = null,
        appId: String? = null,
    ): Resolved {
        val baseCredentials = credentials(cookie, uuid, userAgent.orEmpty(), appId.orEmpty())
        if (baseCredentials.cookie.isBlank()) {
            throw InstagramAudioResolutionException("Instagram audio needs a saved Instagram login first")
        }
        if (baseCredentials.userId.isBlank() || baseCredentials.sessionId.isBlank()) {
            throw InstagramAudioResolutionException("Instagram session cookie is missing sessionid or ds_user_id")
        }

        var lastFailure: Throwable? = null
        baseCredentials.clientAttempts().forEach { credentials ->
            runCatching {
                return resolveWithCredentials(query, credentials)
            }.onFailure { throwable ->
                lastFailure = throwable
                Timber.tag(TAG).w(
                    throwable,
                    "Instagram ${credentials.streamClient} resolver failed for ${query.mediaId}",
                )
            }
        }
        throw InstagramAudioResolutionException(
            "Instagram audio failed for ${query.title}: ${lastFailure?.message ?: "no playable stream"}",
            lastFailure,
        )
    }

    private fun resolveWithCredentials(
        query: Query,
        credentials: Credentials,
    ): Resolved {
        val searchQuery = buildSearchQuery(query.title, query.artists)
        val cacheKey = buildCacheKey(searchQuery, query, credentials)
        getCachedStream(cacheKey)?.let { return it }

        val payloads = fetchSearchPayloads(searchQuery, credentials)
        resolveDirectSearchResult(payloads.firstOrNull(), query, credentials, cacheKey)?.let { return it }

        val candidates = payloads
            .flatMap { it.collectSearchCandidates() + it.collectTrackCandidates() }
            .distinctBy {
                it.firstString(
                    "id",
                    "pk",
                    "audio_asset_id",
                    "audioAssetId",
                    "audio_cluster_id",
                    "music_canonical_id",
                ) ?: it.toString().take(160)
            }
            .map {
                ResolvedCandidate(
                    payload = it,
                    fallbackPayload = it,
                    score = scoreCandidate(it, it, query),
                    source = null,
                )
            }
            .sortedByDescending { it.score }
            .take(MAX_SEARCH_CANDIDATES)

        if (candidates.isEmpty()) {
            throw InstagramAudioResolutionException("Instagram music search returned no tracks for $searchQuery")
        }

        val selected = candidates
            .take(MAX_DETAIL_CANDIDATES)
            .mapNotNull { candidate ->
                val detailed = buildDetailedPayload(candidate.payload, credentials)
                val source = detailed.extractAudioSource()
                    ?: candidate.payload.extractAudioSource()
                    ?: candidate.fallbackPayload.extractAudioSource()
                    ?: return@mapNotNull null
                candidate.copy(
                    payload = detailed,
                    fallbackPayload = candidate.payload,
                    score = scoreCandidate(detailed, candidate.payload, query),
                    source = source,
                )
            }.maxByOrNull { it.score }
            ?: throw InstagramAudioResolutionException("Instagram found music candidates, but no full audio URL was exposed")

        if (selected.score < 35) {
            throw InstagramAudioResolutionException("Instagram match was too weak for ${query.title}")
        }

        return buildResolved(selected, query, credentials, cacheKey)
    }

    private fun resolveDirectSearchResult(
        payload: JSONObject?,
        query: Query,
        credentials: Credentials,
        cacheKey: String,
    ): Resolved? {
        val selected =
            payload
                ?.collectSearchCandidates()
                ?.map { candidate ->
                    ResolvedCandidate(
                        payload = candidate,
                        fallbackPayload = candidate,
                        score = scoreCandidate(candidate, candidate, query),
                        source = null,
                    )
                }
                ?.sortedByDescending { it.score }
                ?.firstNotNullOfOrNull { candidate ->
                    val source = candidate.payload.extractAudioSource()
                        ?: candidate.fallbackPayload.extractAudioSource()
                        ?: return@firstNotNullOfOrNull null
                    candidate.copy(source = source)
                }
                ?: return null

        if (selected.score < 35) return null

        return buildResolved(selected, query, credentials, cacheKey)
    }

    private fun buildResolved(
        selected: ResolvedCandidate,
        query: Query,
        credentials: Credentials,
        cacheKey: String,
    ): Resolved {
        val payload = selected.payload
        val fallback = selected.fallbackPayload
        val source = selected.source
            ?: throw InstagramAudioResolutionException("Instagram selected track has no playable audio URL")
        val trackTitle = payload.firstString("title", "display_title", "displayTitle", "name")
            ?: fallback.firstString("title", "display_title", "displayTitle", "name")
            ?: query.title
        val artistTitle = payload.firstString("display_artist", "subtitle", "artist_name", "artistName")
            ?: fallback.firstString("display_artist", "subtitle", "artist_name", "artistName")
            ?: query.artists.joinToString(", ")
        val sourceId = payload.firstString("id", "pk", "audio_asset_id", "audioAssetId", "audio_cluster_id", "music_canonical_id")
            ?: UUID.randomUUID().toString()

        return Resolved(
            mediaUri = addStreamMarker(source.url, credentials),
            sourceId = "instagram_music_${sourceId}_${trackTitle}_${artistTitle}".sanitizeId(),
            title = trackTitle,
            artist = artistTitle,
            mimeType = source.mimeType,
            codecs = source.codecs,
            bitrate = source.bitrate ?: DEFAULT_BITRATE,
            sampleRate = source.sampleRate ?: DEFAULT_SAMPLE_RATE,
            contentLength = source.contentLength,
            expiresAtMs = resolveExpiryMs(source.url, System.currentTimeMillis()),
        ).also { resolved ->
            Timber.tag(TAG).i(
                "Resolved Instagram ${credentials.streamClient} audio for ${query.mediaId}: ${resolved.title} by ${resolved.artist}, " +
                    "source=${source.label}, bitrate=${resolved.bitrate}, score=${selected.score}",
            )
            cacheStream(cacheKey, resolved)
        }
    }

    fun invalidate(mediaId: String) {
        val normalizedMediaId = mediaId.normalizedSearchText()
        val prefix = "$normalizedMediaId|"
        for (key in streamCache.keys) {
            if (key.startsWith(prefix)) {
                streamCache.remove(key)
            }
        }
    }

    fun normalizeIsrc(value: String?): String? {
        val compact =
            value
                ?.uppercase(Locale.US)
                ?.replace(Regex("[^A-Z0-9]"), "")
                ?: return null
        return ISRC_REGEX.find(compact)?.value
    }

    fun addPlaybackHeaders(
        builder: Request.Builder,
        cookie: String,
        hasRangeHeader: Boolean,
        clientProfile: String?,
        userAgent: String? = null,
    ): Request.Builder {
        val isIosClient = clientProfile == STREAM_CLIENT_IOS
        val playbackUserAgent = userAgent
            ?.takeIf { it.isNotBlank() }
            ?: if (isIosClient) IOS_USER_AGENT else DEFAULT_USER_AGENT
        return builder
            .header("User-Agent", playbackUserAgent)
            .header("Accept", "audio/*,*/*")
            .header("Accept-Encoding", "identity")
            .header("Referer", "https://www.instagram.com/")
            .apply {
                if (!hasRangeHeader) {
                    header("Range", "bytes=0-")
                }
                if (cookie.isNotBlank()) {
                    header("Cookie", cookie)
                }
            }
    }

    fun isInstagramPlaybackUrl(url: HttpUrl): Boolean =
        url.queryParameter(STREAM_MARKER_QUERY) != null || url.host.isInstagramMediaHost()

    fun playbackClientProfile(url: HttpUrl): String? =
        url.queryParameter(STREAM_CLIENT_QUERY)

    fun playbackUserAgent(url: HttpUrl): String? =
        url.queryParameter(STREAM_USER_AGENT_QUERY)

    fun cleanPlaybackUrl(url: HttpUrl): HttpUrl =
        url
            .newBuilder()
            .removeAllQueryParameters(STREAM_MARKER_QUERY)
            .removeAllQueryParameters(STREAM_CLIENT_QUERY)
            .removeAllQueryParameters(STREAM_USER_AGENT_QUERY)
            .build()

    private fun credentials(
        cookie: String,
        uuid: String?,
        userAgent: String,
        appId: String,
    ): Credentials {
        return Credentials(
            cookie = cookie.trim(),
            uuid = uuid?.trim()?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
            userAgent = userAgent.ifBlank { DEFAULT_USER_AGENT },
            appId = appId.ifBlank { DEFAULT_APP_ID },
        )
    }

    private fun Credentials.clientAttempts(): List<Credentials> =
        listOf(
            copy(userAgent = userAgent.ifBlank { DEFAULT_USER_AGENT }, appId = appId.ifBlank { DEFAULT_APP_ID }),
            copy(userAgent = DEFAULT_USER_AGENT, appId = DEFAULT_APP_ID),
            copy(userAgent = IOS_USER_AGENT, appId = IOS_APP_ID),
        ).distinctBy { "${it.streamClient}|${it.userAgent}|${it.appId}" }

    private fun Credentials.isIosClient(): Boolean =
        userAgent.contains("iPhone", ignoreCase = true) ||
            userAgent.contains("iPad", ignoreCase = true) ||
            userAgent.contains("iOS", ignoreCase = true)

    private fun fetchSearchPayloads(
        query: String,
        credentials: Credentials,
    ): List<JSONObject> {
        val urls =
            listOf(
                "$API_BASE_URL/music/audio_global_search/"
                    .toHttpUrl()
                    .newBuilder()
                    .addQueryParameter("query", query)
                    .addQueryParameter("browse_session_id", credentials.uuid)
                    .addQueryParameter("count", MAX_SEARCH_CANDIDATES.toString())
                    .build(),
                "$API_BASE_URL/music/search_v2/"
                    .toHttpUrl()
                    .newBuilder()
                    .addQueryParameter("query", query)
                    .addQueryParameter("browse_session_id", credentials.uuid)
                    .addQueryParameter("search_session_id", credentials.uuid)
                    .addQueryParameter("surface", "clips")
                    .build(),
                "$API_BASE_URL/music/search_v2/"
                    .toHttpUrl()
                    .newBuilder()
                    .addQueryParameter("q", query)
                    .addQueryParameter("browse_session_id", credentials.uuid)
                    .addQueryParameter("search_session_id", credentials.uuid)
                    .addQueryParameter("surface", "clips")
                    .build(),
                "$API_BASE_URL/music/music_browser/"
                    .toHttpUrl()
                    .newBuilder()
                    .addQueryParameter("query", query)
                    .addQueryParameter("browse_session_id", credentials.uuid)
                    .build(),
            )

        val payloads = mutableListOf<JSONObject>()
        urls.forEach { url ->
            runCatching {
                requestJson(
                    Request.Builder()
                        .url(url)
                        .get()
                        .applyInstagramHeaders(credentials)
                        .build(),
                    "Instagram music search failed",
                )
            }.onSuccess {
                payloads += it
            }.onFailure {
                Timber.tag(TAG).d(it, "Instagram search endpoint failed")
            }
        }
        if (payloads.isEmpty()) {
            throw InstagramAudioResolutionException("Instagram music search failed for $query")
        }
        return payloads
    }

    private fun buildDetailedPayload(
        candidate: JSONObject,
        credentials: Credentials,
    ): JSONObject {
        val merged = JSONObject(candidate.toString())
        val audioAssetIds = candidate.collectAudioAssetIds()
        val audioPageIds = candidate.collectAudioPageIds()
        val originalMediaIds = candidate.collectOriginalMediaIds()
        val primaryAudioAssetId = audioAssetIds.firstOrNull()

        candidate.firstString("music_canonical_id")?.let { canonicalId ->
            fetchCanonicalTrack(canonicalId, primaryAudioAssetId, credentials)?.let {
                merged.put("_instagram_clips_music_payload", it)
            }
        }
        fetchAudioAssets(audioAssetIds, credentials)?.let {
            merged.put("_instagram_audio_assets_payload", it)
        }
        fetchWebAudioPage(audioPageIds, credentials)?.let {
            merged.put("_instagram_web_audio_page_payload", it)
        }
        fetchOriginalSoundAudioAssets(originalMediaIds, credentials)?.let {
            merged.put("_instagram_original_sound_payload", it)
        }

        return merged
    }

    private fun fetchCanonicalTrack(
        canonicalId: String,
        originalAudioAssetId: String?,
        credentials: Credentials,
    ): JSONObject? {
        val bodyBuilder =
            FormBody
                .Builder()
                .add("tab_type", "clips")
                .add("referrer_media_id", "")
                .add("_uuid", credentials.uuid)
                .add("music_canonical_id", canonicalId)
        if (!originalAudioAssetId.isNullOrBlank()) {
            bodyBuilder.add("original_sound_audio_asset_id", originalAudioAssetId)
        }

        return runCatching {
            val payload =
                requestJson(
                    Request.Builder()
                        .url("$API_BASE_URL/clips/music/")
                        .post(bodyBuilder.build())
                        .applyInstagramHeaders(credentials)
                        .build(),
                    "Instagram music track lookup failed",
                )
            val assetInfo = payload.optNestedObject("metadata", "music_info", "music_asset_info")
                ?: payload.optNestedObject("metadata", "music_info")
                ?: payload.optJSONObject("track")
            if (assetInfo != null) {
                JSONObject(assetInfo.toString()).apply {
                    put("_instagram_full_payload", payload)
                }
            } else {
                payload
            }
        }.getOrNull()
    }

    private fun fetchAudioAssets(
        audioAssetIds: List<String>,
        credentials: Credentials,
    ): JSONObject? {
        val ids =
            audioAssetIds
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .take(MAX_AUDIO_ASSET_IDS)
        if (ids.isEmpty()) return null

        val idsJson = ids.toJsonArrayString()
        val postAttempts =
            listOf(
                FormBody.Builder().add("audio_asset_ids", idsJson).add("_uuid", credentials.uuid).build(),
                FormBody.Builder().add("audio_asset_id", ids.first()).add("_uuid", credentials.uuid).build(),
                FormBody.Builder().add("ids", idsJson).add("_uuid", credentials.uuid).build(),
            )

        postAttempts.forEach { body ->
            runCatching {
                requestJson(
                    Request.Builder()
                        .url("$API_BASE_URL/music/audio_assets/")
                        .post(body)
                        .applyInstagramHeaders(credentials)
                        .build(),
                    "Instagram audio asset lookup failed",
                )
            }.getOrNull()?.let { return it }
        }

        return runCatching {
            requestJson(
                Request.Builder()
                    .url(
                        "$API_BASE_URL/music/audio_assets/"
                            .toHttpUrl()
                            .newBuilder()
                            .addQueryParameter("audio_asset_ids", idsJson)
                            .build(),
                    )
                    .get()
                    .applyInstagramHeaders(credentials)
                    .build(),
                "Instagram audio asset lookup failed",
            )
        }.getOrNull()
    }

    private fun fetchOriginalSoundAudioAssets(
        originalMediaIds: List<String>,
        credentials: Credentials,
    ): JSONObject? {
        val ids =
            originalMediaIds
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .take(MAX_AUDIO_ASSET_IDS)
        if (ids.isEmpty()) return null

        return runCatching {
            requestJson(
                Request.Builder()
                    .url("$API_BASE_URL/music/original_sound_audio_assets/")
                    .post(
                        FormBody
                            .Builder()
                            .add("original_media_ids", ids.toJsonArrayString())
                            .add("_uuid", credentials.uuid)
                            .build(),
                    )
                    .applyInstagramHeaders(credentials)
                    .build(),
                "Instagram original sound lookup failed",
            )
        }.getOrNull()
    }

    private fun fetchWebAudioPage(
        audioPageIds: List<String>,
        credentials: Credentials,
    ): JSONObject? {
        val ids =
            audioPageIds
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .take(3)
        if (ids.isEmpty()) return null

        ids.forEach { id ->
            listOf(
                "https://www.instagram.com/reels/audio/$id/?__a=1&__d=dis",
                "https://www.instagram.com/reels/audio/$id/",
            ).forEach { url ->
                val text =
                    runCatching {
                        requestText(
                            Request.Builder()
                                .url(url)
                                .get()
                                .applyInstagramWebHeaders(credentials)
                                .build(),
                        )
                    }.getOrNull() ?: return@forEach
                parseAudioPagePayload(id, text)?.let { return it }
            }
        }
        return null
    }

    private fun requestJson(
        request: Request,
        message: String,
    ): JSONObject {
        client.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) {
                throw InstagramAudioResolutionException(
                    "$message with HTTP ${response.code}: ${text.sanitizePreview()}",
                )
            }
            return JSONObject(text)
        }
    }

    private fun requestText(request: Request): String {
        client.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) {
                throw InstagramAudioResolutionException(
                    "Instagram web audio page failed with HTTP ${response.code}: ${text.sanitizePreview()}",
                )
            }
            return text
        }
    }

    private fun Request.Builder.applyInstagramHeaders(credentials: Credentials): Request.Builder =
        apply {
            val isIosClient = credentials.isIosClient()
            header("User-Agent", credentials.userAgent)
            header("Accept", "application/json")
            header("Accept-Language", "en-US")
            header("X-IG-App-ID", credentials.appId)
            header("X-IG-Device-ID", credentials.uuid)
            header("X-IG-Family-Device-ID", credentials.uuid)
            if (!isIosClient) {
                header("X-IG-Android-ID", "android-${credentials.uuid.take(16).replace("-", "")}")
            }
            header("X-IG-WWW-Claim", "0")
            header("X-IG-Connection-Type", "WIFI")
            header("X-IG-Capabilities", if (isIosClient) "36r/Fx8=" else "3brTv10=")
            header("X-IG-App-Locale", "en_US")
            header("X-IG-Device-Locale", "en_US")
            header("X-IG-Mapped-Locale", "en_US")
            if (isIosClient) {
                header("X-IG-Timezone-Offset", "0")
            }
            header("X-FB-HTTP-Engine", "Liger")
            header("X-FB-Client-IP", "True")
            header("X-FB-Server-Cluster", "True")
            if (credentials.cookie.isNotBlank()) header("Cookie", credentials.cookie)
            if (credentials.userId.isNotBlank()) {
                header("IG-U-DS-USER-ID", credentials.userId)
                header("IG-INTENDED-USER-ID", credentials.userId)
            }
            if (credentials.csrf.isNotBlank()) header("X-CSRFToken", credentials.csrf)
            if (credentials.mid.isNotBlank()) header("X-MID", credentials.mid)
        }

    private fun Request.Builder.applyInstagramWebHeaders(credentials: Credentials): Request.Builder =
        apply {
            header("User-Agent", WEB_LOGIN_USER_AGENT)
            header("Accept", "text/html,application/json,*/*")
            header("Accept-Language", "en-US,en;q=0.9")
            header("X-IG-App-ID", WEB_APP_ID)
            header("X-ASBD-ID", "129477")
            header("X-Requested-With", "XMLHttpRequest")
            header("Referer", "https://www.instagram.com/")
            if (credentials.cookie.isNotBlank()) header("Cookie", credentials.cookie)
            credentials.csrf.takeIf { it.isNotBlank() }?.let { header("X-CSRFToken", it) }
        }

    private fun JSONObject.extractAudioSource(): AudioSource? {
        val direct = collectDirectAudioSources()
            .maxWithOrNull(compareBy<AudioSource> { it.priority }.thenBy { it.bitrate ?: 0 })
        val dash = collectDashManifests()
            .mapNotNull { extractBestDashAudioSource(it) }
            .maxWithOrNull(compareBy<AudioSource> { it.priority }.thenBy { it.bitrate ?: 0 })

        if (direct != null && dash != null) {
            return direct.copy(
                bitrate = direct.bitrate ?: dash.bitrate,
                sampleRate = direct.sampleRate ?: dash.sampleRate,
                label = if (direct.bitrate == null || direct.sampleRate == null) {
                    "${direct.label} (${dash.label} metadata)"
                } else {
                    direct.label
                },
            )
        }

        return direct ?: dash
    }

    private fun parseAudioPagePayload(
        audioPageId: String,
        text: String,
    ): JSONObject? {
        val trimmed = text.trim()
        if (trimmed.startsWith("{")) {
            runCatching { JSONObject(trimmed) }.getOrNull()?.let { return it }
        }

        val extracted = linkedMapOf<String, String>()
        val keyRegex =
            Regex(
                """"((?:audio_file_url|audioFileUrl|audioFileURLString|audio_file_fast_start_url|audioFileFastStartUrl|audioFileFastStartURLString|dash_manifest|dashManifest|dashManifestData|progressive_download_url|progressiveDownloadURLString|fast_start_progressive_download_url|fastStartProgressiveDownloadURLString|reactive_audio_download_url|reactiveAudioDownloadURLString|web_30s_preview_download_url|web30sPreviewDownloadURLString))"\s*:\s*"((?:\\.|[^"\\])*)"""",
                RegexOption.IGNORE_CASE,
            )
        keyRegex.findAll(text).forEach { match ->
            val key = match.groupValues.getOrNull(1).orEmpty()
            val value = match.groupValues.getOrNull(2).orEmpty().decodeJsonString()
            if (key.isNotBlank() && value.isNotBlank()) {
                extracted.putIfAbsent(key, value)
            }
        }
        if (extracted.isEmpty()) return null

        return JSONObject().apply {
            put("audio_page_id", audioPageId)
            extracted.forEach { (key, value) -> put(key, value) }
        }
    }

    private fun JSONObject.collectSearchCandidates(): List<JSONObject> {
        val items = optJSONArray("items") ?: return emptyList()
        val candidates = mutableListOf<JSONObject>()
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            candidates += item.optJSONObject("track") ?: item
        }
        return candidates
    }

    private fun JSONObject.collectTrackCandidates(): List<JSONObject> {
        val candidates = linkedMapOf<String, JSONObject>()

        fun hasMusicIdentity(candidate: JSONObject): Boolean =
            candidate.firstString(
                "music_canonical_id",
                "audio_asset_id",
                "audioAssetId",
                "audio_cluster_id",
                "audioClusterId",
                "pk",
                "id",
            ) != null

        fun hasMusicTitle(candidate: JSONObject): Boolean =
            candidate.firstString(
                "title",
                "display_title",
                "displayTitle",
                "sanitized_title",
                "sanitizedTitle",
                "name",
            ) != null

        fun addCandidate(
            candidate: JSONObject,
            path: String,
        ) {
            if (!hasMusicIdentity(candidate) || !hasMusicTitle(candidate)) return
            val normalizedPath = path.normalizedJsonKey()
            if (
                !normalizedPath.contains("music") &&
                !normalizedPath.contains("audio") &&
                !candidate.toString().contains("audio", ignoreCase = true)
            ) {
                return
            }
            val key = candidate.firstString(
                "music_canonical_id",
                "audio_asset_id",
                "audioAssetId",
                "audio_cluster_id",
                "audioClusterId",
                "id",
            ) ?: candidate.toString().take(160)
            candidates.putIfAbsent(key, candidate)
        }

        fun visit(
            value: Any?,
            path: String = "",
        ) {
            when (value) {
                is JSONObject -> {
                    value.optJSONObject("track")?.let { addCandidate(it, "$path.track") }
                    addCandidate(value, path)
                    val keys = value.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        visit(value.opt(key), if (path.isBlank()) key else "$path.$key")
                    }
                }
                is JSONArray -> {
                    for (index in 0 until value.length()) {
                        visit(value.opt(index), "$path[$index]")
                    }
                }
            }
        }

        visit(this)
        return candidates.values.toList()
    }

    private fun JSONObject.collectDirectAudioSources(): List<AudioSource> {
        val sources = mutableListOf<AudioSource>()
        val fallbackDurationMs = firstLong("duration_in_ms", "duration_ms")

        fun visit(
            value: Any?,
            path: String = "",
        ) {
            when (value) {
                is JSONObject -> {
                    val keys = value.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val child = value.opt(key)
                        val direct = child.asCleanString()
                        val normalizedKey = key.normalizedJsonKey()
                        if (!direct.isNullOrBlank() && normalizedKey in directAudioKeys) {
                            val url = htmlUnescape(direct)
                            if (url.startsWith("http", ignoreCase = true)) {
                                val mimeType = mimeTypeForUrl(url)
                                val contentLength =
                                    value.firstLong(
                                        "fbContentLength",
                                        "contentLength",
                                        "content_length",
                                        "contentLengthBytes",
                                        "audioSize",
                                        "audio_size",
                                        "fileSize",
                                        "file_size",
                                        "fileSizeInBytes",
                                        "size",
                                    )
                                val bitrate =
                                    value.firstInt(
                                        "bitrate",
                                        "bit_rate",
                                        "audioBitrate",
                                        "audio_bitrate",
                                        "bandwidth",
                                    )
                                        ?.takeIf { it in 32_000..512_000 }
                                        ?: estimateBitrate(
                                            contentLength,
                                            value.firstLong("duration_in_ms", "duration_ms") ?: fallbackDurationMs,
                                        )
                                val sampleRate =
                                    value.firstInt(
                                        "sampleRate",
                                        "sample_rate",
                                        "audioSamplingRate",
                                        "audio_sampling_rate",
                                    )?.takeIf { it in 8_000..384_000 }
                                sources +=
                                    AudioSource(
                                        url = url,
                                        mimeType = mimeType,
                                        codecs = codecsFor(mimeType, null, url),
                                        bitrate = bitrate,
                                        sampleRate = sampleRate,
                                        contentLength = contentLength,
                                        priority = directAudioPriority(normalizedKey, path),
                                        label = "${directAudioLabel(path)} ${codecLabelForUrl(url)}",
                                    )
                            }
                        }
                        visit(child, if (path.isBlank()) key else "$path.$key")
                    }
                }
                is JSONArray -> {
                    for (index in 0 until value.length()) {
                        visit(value.opt(index), "$path[$index]")
                    }
                }
            }
        }

        visit(this)
        return sources.distinctBy { it.url }
    }

    private fun JSONObject.collectDashManifests(): List<DashManifest> {
        val manifests = mutableListOf<DashManifest>()

        fun visit(
            value: Any?,
            path: String = "",
        ) {
            when (value) {
                is JSONObject -> {
                    val keys = value.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val child = value.opt(key)
                        if (
                            key.equals("dash_manifest", ignoreCase = true) ||
                            key.equals("dashManifest", ignoreCase = true) ||
                            key.equals("dashManifestData", ignoreCase = true)
                        ) {
                            child.asCleanString()
                                ?.takeIf {
                                    it.contains("BaseURL", ignoreCase = true) ||
                                        it.contains("&lt;BaseURL", ignoreCase = true)
                                }?.let {
                                    manifests += DashManifest(it, if (path.isBlank()) key else "$path.$key")
                                }
                        }
                        visit(child, if (path.isBlank()) key else "$path.$key")
                    }
                }
                is JSONArray -> {
                    for (index in 0 until value.length()) {
                        visit(value.opt(index), "$path[$index]")
                    }
                }
            }
        }

        visit(this)
        return manifests.distinctBy { it.manifest }
    }

    private fun extractBestDashAudioSource(dashManifest: DashManifest): AudioSource? {
        val manifest = htmlUnescape(dashManifest.manifest)
        val priority = dashAudioPriority(dashManifest.path)
        val labelPrefix = dashAudioLabel(dashManifest.path)
        val representationRegex =
            Regex(
                """<Representation\b([^>]*)>.*?<BaseURL>(.*?)</BaseURL>""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
            )
        val representations =
            representationRegex
                .findAll(manifest)
                .mapNotNull { match ->
                    val attrs = match.groupValues.getOrNull(1).orEmpty()
                    val url =
                        match.groupValues
                            .getOrNull(2)
                            ?.takeIf { it.isNotBlank() }
                            ?: return@mapNotNull null
                    val codecs = attrs.xmlAttr("codecs")
                    val mimeType = attrs.xmlAttr("mimeType") ?: mimeTypeForUrl(url)
                    val bitrate =
                        Regex("""bandwidth=["']?(\d+)""", RegexOption.IGNORE_CASE)
                            .find(attrs)
                            ?.groupValues
                            ?.getOrNull(1)
                            ?.toIntOrNull()
                    val sampleRate = attrs.xmlAttr("audioSamplingRate")?.toIntOrNull()
                    val decodedUrl = htmlUnescape(url)
                    AudioSource(
                        url = decodedUrl,
                        mimeType = mimeType,
                        codecs = codecsFor(mimeType, codecs, decodedUrl),
                        bitrate = bitrate,
                        sampleRate = sampleRate,
                        contentLength = null,
                        priority = priority,
                        label = "$labelPrefix DASH ${codecLabelFor(codecs, mimeType, decodedUrl)}",
                    )
                }.toList()
        if (representations.isNotEmpty()) return representations.maxByOrNull { it.bitrate ?: 0 }

        return Regex("""<BaseURL>(.*?)</BaseURL>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(manifest)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { url ->
                val decodedUrl = htmlUnescape(url)
                val mimeType = mimeTypeForUrl(decodedUrl)
                AudioSource(
                    url = decodedUrl,
                    mimeType = mimeType,
                    codecs = codecsFor(mimeType, null, decodedUrl),
                    bitrate = null,
                    sampleRate = null,
                    contentLength = null,
                    priority = priority,
                    label = "$labelPrefix DASH ${codecLabelForUrl(decodedUrl)}",
                )
            }
    }

    private fun JSONObject.collectAudioAssetIds(): List<String> =
        collectIds(audioAssetIdKeys) { key, path ->
            key != "id" || path.contains("audio") || path.contains("music")
        }

    private fun JSONObject.collectAudioPageIds(): List<String> =
        collectIds(audioPageIdKeys) { key, path ->
            key != "id" || path.contains("audio") || path.contains("music")
        }

    private fun JSONObject.collectOriginalMediaIds(): List<String> =
        collectIds(originalMediaIdKeys) { _, _ -> true }

    private fun JSONObject.collectIds(
        keysToMatch: Set<String>,
        allow: (key: String, normalizedPath: String) -> Boolean,
    ): List<String> {
        val ids = linkedSetOf<String>()

        fun visit(
            value: Any?,
            path: String = "",
        ) {
            when (value) {
                is JSONObject -> {
                    val keys = value.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val child = value.opt(key)
                        val normalizedKey = key.normalizedJsonKey()
                        val normalizedPath = path.normalizedJsonKey()
                        val direct = child.asCleanString()
                        if (!direct.isNullOrBlank() && normalizedKey in keysToMatch && allow(normalizedKey, normalizedPath)) {
                            ids += direct
                        }
                        visit(child, if (path.isBlank()) key else "$path.$key")
                    }
                }
                is JSONArray -> {
                    for (index in 0 until value.length()) {
                        visit(value.opt(index), "$path[$index]")
                    }
                }
            }
        }

        visit(this)
        return ids.toList()
    }

    private fun scoreCandidate(
        payload: JSONObject,
        fallback: JSONObject,
        query: Query,
    ): Int {
        val candidateTitle =
            payload.firstString("title", "display_title", "displayTitle", "name")
                ?: fallback.firstString("title", "display_title", "displayTitle", "name")
                ?: ""
        val candidateArtist =
            payload.firstString("display_artist", "subtitle", "artist_name", "artistName")
                ?: fallback.firstString("display_artist", "subtitle", "artist_name", "artistName")
                ?: ""
        val candidateDuration =
            payload.firstLong("duration_in_ms", "duration_ms")
                ?: fallback.firstLong("duration_in_ms", "duration_ms")
        var score = 0
        val normalizedTitle = query.title.normalizedSearchText()
        val normalizedCandidateTitle = candidateTitle.normalizedSearchText()
        if (normalizedTitle == normalizedCandidateTitle) score += 120
        if (normalizedTitle.isNotBlank() &&
            (normalizedCandidateTitle.contains(normalizedTitle) || normalizedTitle.contains(normalizedCandidateTitle))
        ) {
            score += 45
        }
        val normalizedArtist = candidateArtist.normalizedSearchText()
        query.artists.forEach { artist ->
            val normalized = artist.normalizedSearchText()
            if (normalized.isNotBlank() && normalizedArtist.contains(normalized)) score += 40
        }
        if (!query.isrc.isNullOrBlank() && payload.toString().contains(query.isrc, ignoreCase = true)) score += 150
        val durationMs = query.durationMs
        if (durationMs != null && candidateDuration != null) {
            val delta = abs(candidateDuration - durationMs)
            score +=
                when {
                    delta <= 2_000L -> 40
                    delta <= 5_000L -> 24
                    delta <= 10_000L -> 10
                    else -> 0
                }
        }
        return score
    }

    private fun buildSearchQuery(
        title: String,
        artists: List<String>,
    ): String {
        val firstArtist = artists.firstOrNull().orEmpty()
        return listOf(title, firstArtist)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
    }

    private fun buildCacheKey(
        query: String,
        source: Query,
        credentials: Credentials,
    ): String =
        listOf(
            source.mediaId.normalizedSearchText(),
            query.normalizedSearchText(),
            source.artists.joinToString(",").normalizedSearchText(),
            source.isrc.orEmpty().uppercase(Locale.ROOT),
            source.durationMs?.div(1000L)?.toString().orEmpty(),
            credentials.userId,
            credentials.streamClient,
            credentials.userAgent,
            credentials.appId,
        ).joinToString("|")

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

    private fun addStreamMarker(
        url: String,
        credentials: Credentials,
    ): String {
        val parsed = url.toHttpUrlOrNull() ?: return url
        return parsed
            .newBuilder()
            .addQueryParameter(STREAM_MARKER_QUERY, "1")
            .addQueryParameter(STREAM_CLIENT_QUERY, credentials.streamClient)
            .addQueryParameter(STREAM_USER_AGENT_QUERY, credentials.userAgent)
            .build()
            .toString()
    }

    private fun resolveExpiryMs(
        url: String,
        now: Long,
    ): Long {
        val parsed = url.toHttpUrlOrNull() ?: return now + STREAM_CACHE_TTL_MS
        listOf("oe", "expires", "Expires").forEach { key ->
            parsed.queryParameter(key)?.let { value ->
                value.toLongOrNull()?.let { return it * 1000L }
                value.toLongOrNull(16)?.let { return it * 1000L }
            }
        }
        return now + STREAM_CACHE_TTL_MS
    }

    private fun JSONObject.optNestedObject(vararg keys: String): JSONObject? {
        var current: JSONObject = this
        keys.forEachIndexed { index, key ->
            val next = current.optJSONObject(key) ?: return null
            if (index == keys.lastIndex) return next
            current = next
        }
        return current
    }

    private fun JSONObject.firstString(vararg keys: String): String? {
        keys.forEach { key ->
            opt(key).asCleanString()?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    private fun JSONObject.firstLong(vararg keys: String): Long? {
        keys.forEach { key ->
            opt(key).asCleanString()?.toLongOrNull()?.let { return it }
            optLong(key, Long.MIN_VALUE)
                .takeIf { it != Long.MIN_VALUE }
                ?.let { return it }
        }
        return null
    }

    private fun JSONObject.firstInt(vararg keys: String): Int? =
        firstLong(*keys)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

    private fun Any?.asCleanString(): String? {
        return when (this) {
            null, JSONObject.NULL -> null
            is String -> this.trim()
            is Number -> toString()
            is Boolean -> toString()
            else -> null
        }
    }

    private val directAudioKeys =
        setOf(
            "audiofileurl",
            "audiofileurlstring",
            "audiofilefaststarturl",
            "audiofilefaststarturlstring",
            "audiosrc",
            "faststartprogressivedownloadurl",
            "faststartprogressivedownloadurlstring",
            "progressivedownloadurl",
            "progressivedownloadurlstring",
            "progressivedownloadfaststarturl",
            "progressivedownloadfaststarturlstring",
            "reactiveaudiodownloadurl",
            "reactiveaudiodownloadurlstring",
            "audiourlfull",
            "audiourlfullstring",
            "audiofullurl",
            "audiofullurlstring",
            "web30spreviewdownloadurl",
            "web30spreviewdownloadurlstring",
            "uri",
        )

    private val audioAssetIdKeys =
        setOf(
            "audioassetid",
            "audioassetidstr",
            "originalsoundaudioassetid",
            "originalaudioassetid",
            "audioclusterid",
            "musicassetid",
            "musiccanonicalid",
            "audiocanonicalid",
            "pk",
            "id",
        )

    private val audioPageIdKeys = audioAssetIdKeys

    private val originalMediaIdKeys =
        setOf(
            "originalmediaid",
            "originalmediaids",
            "originalmediaidstr",
            "originalmediaidstring",
            "originalmediaidlong",
            "originalmedia",
        )

    private fun directAudioPriority(
        key: String,
        path: String,
    ): Int {
        val normalizedPath = path.normalizedJsonKey()
        val base =
            when (key) {
                "audiofileurl", "audiofileurlstring", "audiofilefaststarturl", "audiofilefaststarturlstring" -> 70
                "audiosrc" -> 55
                "audiourlfull", "audiourlfullstring", "audiofullurl", "audiofullurlstring" -> 50
                "reactiveaudiodownloadurl", "reactiveaudiodownloadurlstring" -> 45
                "faststartprogressivedownloadurl", "faststartprogressivedownloadurlstring",
                "progressivedownloadfaststarturl", "progressivedownloadfaststarturlstring",
                "progressivedownloadurl", "progressivedownloadurlstring",
                -> 35
                "web30spreviewdownloadurl", "web30spreviewdownloadurlstring" -> 5
                else -> 10
            }
        return base +
            when {
                normalizedPath.contains("sundialoriginalaudioasset") -> 30
                normalizedPath.contains("originalsound") -> 25
                normalizedPath.contains("sundialmusicasset") -> 20
                normalizedPath.contains("video") -> 10
                normalizedPath.contains("web30spreview") -> -45
                normalizedPath.contains("preview") -> -35
                normalizedPath.contains("musicassetinfo") -> -10
                else -> 0
            }
    }

    private fun dashAudioPriority(path: String): Int {
        val normalizedPath = path.normalizedJsonKey()
        return 80 +
            when {
                normalizedPath.contains("sundialoriginalaudioasset") -> 35
                normalizedPath.contains("originalsound") -> 30
                normalizedPath.contains("video") -> 25
                normalizedPath.contains("sundialmusicasset") -> 15
                normalizedPath.contains("musicassetinfo") -> -20
                normalizedPath.contains("web30spreview") -> -45
                normalizedPath.contains("preview") -> -35
                else -> 0
            }
    }

    private fun dashAudioLabel(path: String): String {
        val normalizedPath = path.normalizedJsonKey()
        return when {
            normalizedPath.contains("sundialoriginalaudioasset") || normalizedPath.contains("originalsound") -> "Original"
            normalizedPath.contains("video") -> "Video Audio"
            normalizedPath.contains("sundialmusicasset") -> "Music Asset"
            normalizedPath.contains("web30spreview") || normalizedPath.contains("preview") -> "Preview"
            else -> "Audio"
        }
    }

    private fun directAudioLabel(path: String): String {
        val normalizedPath = path.normalizedJsonKey()
        val prefix =
            when {
                normalizedPath.contains("sundialoriginalaudioasset") -> "Original"
                normalizedPath.contains("originalsound") -> "Original"
                normalizedPath.contains("sundialmusicasset") -> "Music Asset"
                normalizedPath.contains("video") -> "Video Audio"
                normalizedPath.contains("web30spreview") || normalizedPath.contains("preview") -> "Preview"
                else -> "Progressive"
            }
        return "$prefix Audio"
    }

    private fun mimeTypeForUrl(url: String): String =
        when {
            url.contains(".wav", ignoreCase = true) -> "audio/wav"
            url.contains(".mp3", ignoreCase = true) -> "audio/mpeg"
            url.contains(".m4a", ignoreCase = true) || url.contains(".mp4", ignoreCase = true) -> "audio/mp4"
            else -> "audio/mp4"
        }

    private fun codecsFor(
        mimeType: String,
        codecHint: String?,
        url: String,
    ): String {
        if (!codecHint.isNullOrBlank()) return codecHint
        return when {
            mimeType.contains("wav", ignoreCase = true) || url.contains(".wav", ignoreCase = true) -> "pcm"
            mimeType.contains("mpeg", ignoreCase = true) || url.contains(".mp3", ignoreCase = true) -> "mp3"
            else -> "mp4a.40.2"
        }
    }

    private fun codecLabelForUrl(url: String): String =
        when {
            url.contains(".wav", ignoreCase = true) -> "WAV"
            url.contains(".mp3", ignoreCase = true) -> "MP3"
            url.contains(".m4a", ignoreCase = true) || url.contains(".mp4", ignoreCase = true) -> "M4A"
            else -> "Audio"
        }

    private fun codecLabelFor(
        codecs: String?,
        mimeType: String?,
        url: String,
    ): String =
        when {
            codecs?.contains("pcm", ignoreCase = true) == true -> "WAV"
            mimeType?.contains("wav", ignoreCase = true) == true -> "WAV"
            url.contains(".wav", ignoreCase = true) -> "WAV"
            codecs?.contains("mp4a", ignoreCase = true) == true -> "M4A"
            mimeType?.contains("mp4", ignoreCase = true) == true -> "M4A"
            codecs?.contains("mp3", ignoreCase = true) == true -> "MP3"
            mimeType?.contains("mpeg", ignoreCase = true) == true -> "MP3"
            else -> codecLabelForUrl(url)
        }

    private fun estimateBitrate(
        contentLength: Long?,
        durationMs: Long?,
    ): Int? {
        if (contentLength == null || durationMs == null || contentLength <= 0L || durationMs <= 0L) return null
        return ((contentLength * 8_000L) / durationMs).toInt().takeIf { it in 32_000..512_000 }
    }

    private fun String.xmlAttr(name: String): String? =
        Regex("""\b${Regex.escape(name)}=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(this)
            ?.groupValues
            ?.getOrNull(1)

    private fun List<String>.toJsonArrayString(): String =
        joinToString(prefix = "[", postfix = "]") { value ->
            "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        }

    private fun String.parseCookies(): Map<String, String> =
        split(';')
            .mapNotNull { cookie ->
                val index = cookie.indexOf('=')
                if (index <= 0) return@mapNotNull null
                cookie.substring(0, index).trim() to cookie.substring(index + 1).trim()
            }.toMap()

    private fun String.isInstagramMediaHost(): Boolean =
        equals("instagram.com", ignoreCase = true) ||
            endsWith(".instagram.com", ignoreCase = true) ||
            endsWith(".cdninstagram.com", ignoreCase = true) ||
            endsWith(".fbcdn.net", ignoreCase = true) ||
            endsWith(".fbsbx.com", ignoreCase = true)

    private fun String.normalizedJsonKey(): String =
        lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "")

    private fun String.normalizedSearchText(): String {
        val normalized =
            Normalizer
                .normalize(this, Normalizer.Form.NFD)
                .replace(Regex("\\p{Mn}+"), "")
        return normalized
            .lowercase(Locale.ROOT)
            .replace(Regex("""\([^)]*\)|\[[^]]*]"""), " ")
            .replace(Regex("""[^a-z0-9]+"""), " ")
            .trim()
    }

    private fun String.sanitizeId(): String =
        lowercase(Locale.ROOT)
            .replace(Regex("""[^a-z0-9._-]+"""), "_")
            .trim('_')
            .take(120)
            .ifBlank { UUID.randomUUID().toString() }

    private fun String.sanitizePreview(max: Int = 300): String =
        replace(Regex("\\s+"), " ")
            .trim()
            .let { if (it.length > max) it.take(max) else it }

    private fun String.decodeJsonString(): String =
        runCatching { JSONObject("""{"v":"$this"}""").optString("v", this) }
            .getOrDefault(this)

    private fun htmlUnescape(value: String): String =
        value
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
}
