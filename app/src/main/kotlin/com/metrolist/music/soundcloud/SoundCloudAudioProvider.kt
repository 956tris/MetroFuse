/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.soundcloud

import com.metrolist.music.utils.soundcloud.normalizeSoundCloudAuthInput
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs

object SoundCloudAudioProvider {
    const val STREAM_MARKER_QUERY = "_metrofuse_soundcloud"
    const val STREAM_HLS_MARKER_QUERY = "_metrofuse_soundcloud_hls"
    const val STREAM_SOURCE_QUERY = "_metrofuse_soundcloud_source"
    const val STREAM_SOURCE_API = "api"
    const val STREAM_SOURCE_SQUID = "squid"
    const val BROWSER_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Mobile Safari/537.36"

    private const val API_BASE_URL = "https://api-v2.soundcloud.com"
    private const val SQUID_BASE_URL = "https://sc.squid.wtf"
    private const val STREAM_MARKER_VALUE = "1"
    private const val CLIENT_ID_CACHE_MS = 60 * 60 * 1000L
    private const val STREAM_CACHE_MS = 5 * 60 * 1000L
    private const val SEARCH_LIMIT = 20
    private const val DEFAULT_BITRATE = 128_000

    data class Query(
        val mediaId: String,
        val title: String,
        val artists: List<String>,
        val album: String?,
        val durationMs: Long?,
    )

    data class Resolved(
        val mediaUri: String,
        val trackId: String,
        val permalinkUrl: String,
        val title: String,
        val artist: String,
        val artworkUrl: String?,
        val mimeType: String,
        val codecs: String,
        val bitrate: Int,
        val sampleRate: Int?,
        val contentLength: Long?,
        val expiresAtMs: Long,
    )

    data class TrackMetadata(
        val trackId: String,
        val title: String,
        val artist: String,
        val permalinkUrl: String,
        val artworkUrl: String?,
        val durationMs: Long?,
    )

    class SoundCloudResolutionException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private data class ClientId(
        val value: String,
        val expiresAtMs: Long,
    )

    private data class MatchedTrack(
        val trackId: String,
        val title: String,
        val artist: String,
        val artistNames: List<String>,
        val permalinkUrl: String,
        val artworkUrl: String?,
        val durationMs: Long?,
        val trackAuthorization: String?,
        val transcodings: JSONArray?,
    )

    private data class StreamMetadata(
        val mimeType: String,
        val contentLength: Long?,
    )

    private data class StreamCandidate(
        val url: String,
        val protocol: String,
        val mimeType: String,
        val preset: String,
        val isHls: Boolean,
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    private val streamCache = ConcurrentHashMap<String, Resolved>()

    @Volatile
    private var clientIdCache: ClientId? = null

    fun resolve(
        query: Query,
        authToken: String = "",
    ): Resolved {
        val normalizedAuthToken = normalizeSoundCloudAuthInput(authToken).orEmpty()
        val streamCacheKey = query.cacheKey(normalizedAuthToken.isNotBlank())
        val now = System.currentTimeMillis()
        streamCache[streamCacheKey]
            ?.takeIf { it.expiresAtMs > now + 20_000L }
            ?.let { return it }

        val clientId = getClientId()
        val track = query.mediaId.toSoundCloudUrlOrNull()
            ?.let { resolveApiV2Track(it, clientId, normalizedAuthToken) ?: resolveSongUrl(it, clientId) }
            ?: findBestTrack(query, clientId)
            ?: throw SoundCloudResolutionException("SoundCloud match not found for ${query.title}")

        val expectedDurationMs = query.durationMs ?: track.durationMs
        val apiTrack = resolveApiV2Track(track.permalinkUrl, clientId, normalizedAuthToken) ?: track
        runCatching {
            resolveApiV2Stream(
                track = apiTrack,
                clientId = clientId,
                authToken = normalizedAuthToken,
                expectedDurationMs = expectedDurationMs,
                now = now,
            )
        }.onFailure { error ->
            Timber.tag("SoundCloudAudio").w(error, "SoundCloud API-v2 stream resolution failed; trying Squid fallback")
        }.getOrNull()?.also { resolved ->
            streamCache[streamCacheKey] = resolved
            return resolved
        }

        return resolveSquidStream(
            track = track,
            clientId = clientId,
            expectedDurationMs = expectedDurationMs,
            now = now,
        ).also { streamCache[streamCacheKey] = it }
    }

    fun invalidate(mediaId: String) {
        streamCache.keys
            .filter { it.startsWith("$mediaId::") }
            .forEach { streamCache.remove(it) }
    }

    fun isSoundCloudUrl(value: String): Boolean =
        value.toSoundCloudUrlOrNull() != null

    fun clientId(): String = getClientId()

    fun searchMetadata(
        term: String,
        limit: Int = SEARCH_LIMIT,
    ): List<TrackMetadata> {
        if (term.isBlank()) return emptyList()
        val clientId = getClientId()
        val results = searchTracks(term, clientId, limit) ?: return emptyList()
        return buildList {
            for (index in 0 until results.length()) {
                val obj = results.optJSONObject(index) ?: continue
                if (!obj.optString("kind").equals("track", ignoreCase = true)) continue
                if (!obj.optBoolean("streamable", true)) continue
                obj.toMatchedTrack()?.toTrackMetadata()?.let(::add)
            }
        }.distinctBy { it.permalinkUrl }
    }

    fun addPlaybackHeaders(
        builder: Request.Builder,
        hasRangeHeader: Boolean,
        isApiStream: Boolean = false,
        isHlsStream: Boolean = false,
    ): Request.Builder {
        return builder
            .header("User-Agent", BROWSER_USER_AGENT)
            .header("Accept", "audio/*,*/*;q=0.8")
            .header("Accept-Encoding", "identity")
            .header("Referer", if (isApiStream) "https://soundcloud.com/" else "$SQUID_BASE_URL/")
            .apply {
                if (!isApiStream) {
                    header("Origin", SQUID_BASE_URL)
                }
                if (!hasRangeHeader && !isHlsStream) {
                    header("Range", "bytes=0-")
                }
            }
    }

    private fun getClientId(): String {
        val now = System.currentTimeMillis()
        clientIdCache
            ?.takeIf { it.expiresAtMs > now }
            ?.let { return it.value }

        val url = "$SQUID_BASE_URL/api/soundcloud/get-client-id".toHttpUrlOrNull()
            ?: throw SoundCloudResolutionException("SoundCloud client-id URL could not be built")
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json")
            .header("Referer", "$SQUID_BASE_URL/")
            .header("User-Agent", BROWSER_USER_AGENT)
            .build()

        val clientId = runCatching {
            client.newCall(request).execute().use { response ->
                val payload = response.body.string()
                if (!response.isSuccessful) {
                    throw SoundCloudResolutionException("SoundCloud client-id HTTP ${response.code}: ${payload.take(160)}")
                }
                val root = JSONObject(payload)
                root.stringOrNull("clientId")
                    ?: throw SoundCloudResolutionException(root.stringOrNull("error") ?: "SoundCloud client-id missing")
            }
        }.getOrElse { error ->
            if (error is SoundCloudResolutionException) throw error
            throw SoundCloudResolutionException("SoundCloud client-id request failed", error)
        }

        clientIdCache = ClientId(clientId, now + CLIENT_ID_CACHE_MS)
        return clientId
    }

    private fun findBestTrack(
        query: Query,
        clientId: String,
    ): MatchedTrack? {
        for (term in searchTerms(query)) {
            val results = searchTracks(term, clientId) ?: continue
            selectBestTrack(results, query)?.let { return it }
        }
        return null
    }

    private fun searchTracks(
        term: String,
        clientId: String,
        limit: Int = SEARCH_LIMIT,
    ): JSONArray? {
        val url = "$SQUID_BASE_URL/api/soundcloud/search".toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("q", term)
            ?.addQueryParameter("limit", limit.coerceIn(1, 50).toString())
            ?.addQueryParameter("client_id", clientId)
            ?.build()
            ?: return null
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json")
            .header("Referer", "$SQUID_BASE_URL/")
            .header("User-Agent", BROWSER_USER_AGENT)
            .build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val payload = response.body.string().takeIf { it.isNotBlank() } ?: return@use null
                JSONObject(payload).optJSONArray("collection")
            }
        }.getOrNull()
    }

    private fun resolveSongUrl(
        url: String,
        clientId: String,
    ): MatchedTrack? {
        val songUrl = "$SQUID_BASE_URL/api/soundcloud/song".toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("url", url.withClientId(clientId))
            ?.build()
            ?: return null
        val request = Request.Builder()
            .url(songUrl)
            .get()
            .header("Accept", "application/json")
            .header("Referer", "$SQUID_BASE_URL/")
            .header("User-Agent", BROWSER_USER_AGENT)
            .build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body.string()
                    .takeIf { it.isNotBlank() }
                    ?.let(::JSONObject)
                    ?.toMatchedTrack()
            }
        }.getOrNull()
    }

    private fun resolveApiV2Track(
        url: String,
        clientId: String,
        authToken: String,
    ): MatchedTrack? {
        val resolveUrl = "$API_BASE_URL/resolve".toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("url", url)
            ?.addQueryParameter("client_id", clientId)
            ?.addQueryParameter("app_locale", "en")
            ?.build()
            ?: return null
        val request = apiRequest(resolveUrl.toString(), authToken).build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body.string()
                    .takeIf { it.isNotBlank() }
                    ?.let(::JSONObject)
                    ?.takeIf { it.optString("kind").equals("track", ignoreCase = true) }
                    ?.toMatchedTrack()
            }
        }.getOrNull()
    }

    private fun resolveApiV2Stream(
        track: MatchedTrack,
        clientId: String,
        authToken: String,
        expectedDurationMs: Long?,
        now: Long,
    ): Resolved? {
        val candidate = selectStreamCandidate(track.transcodings) ?: return null
        val streamUrl = resolveTranscodingUrl(candidate, track.trackAuthorization, clientId, authToken) ?: return null
        val streamMetadata =
            if (candidate.isHls) {
                StreamMetadata("application/x-mpegURL", null)
            } else {
                fetchStreamMetadata(streamUrl, isApiStream = true)
            }
        validateStreamIsPlayable(track, streamMetadata, expectedDurationMs)
        val bitrate = estimateBitrate(streamMetadata.contentLength, expectedDurationMs)
            ?: DEFAULT_BITRATE

        return Resolved(
            mediaUri = addStreamMarker(
                url = streamUrl,
                isHls = candidate.isHls,
                source = STREAM_SOURCE_API,
            ),
            trackId = track.trackId,
            permalinkUrl = track.permalinkUrl,
            title = track.title,
            artist = track.artist,
            artworkUrl = track.artworkUrl,
            mimeType = streamMetadata.mimeType,
            codecs = candidate.mimeType.toCodecs(),
            bitrate = bitrate,
            sampleRate = null,
            contentLength = streamMetadata.contentLength,
            expiresAtMs = now + STREAM_CACHE_MS,
        )
    }

    private fun resolveSquidStream(
        track: MatchedTrack,
        clientId: String,
        expectedDurationMs: Long?,
        now: Long,
    ): Resolved {
        val downloadUrl = buildDownloadUrl(track, clientId)
        val streamMetadata = fetchStreamMetadata(downloadUrl, isApiStream = false)
        validateStreamIsPlayable(track, streamMetadata, expectedDurationMs)
        val bitrate = estimateBitrate(streamMetadata.contentLength, expectedDurationMs)
            ?: DEFAULT_BITRATE

        return Resolved(
            mediaUri = addStreamMarker(
                url = downloadUrl,
                isHls = false,
                source = STREAM_SOURCE_SQUID,
            ),
            trackId = track.trackId,
            permalinkUrl = track.permalinkUrl,
            title = track.title,
            artist = track.artist,
            artworkUrl = track.artworkUrl,
            mimeType = streamMetadata.mimeType,
            codecs = streamMetadata.mimeType.toCodecs(),
            bitrate = bitrate,
            sampleRate = null,
            contentLength = streamMetadata.contentLength,
            expiresAtMs = now + STREAM_CACHE_MS,
        )
    }

    private fun selectStreamCandidate(transcodings: JSONArray?): StreamCandidate? {
        if (transcodings == null) return null
        val candidates = buildList {
            for (index in 0 until transcodings.length()) {
                val transcoding = transcodings.optJSONObject(index) ?: continue
                val format = transcoding.optJSONObject("format") ?: continue
                val protocol = format.stringOrNull("protocol")?.lowercase(Locale.US) ?: continue
                val mimeType = format.stringOrNull("mime_type")?.lowercase(Locale.US) ?: continue
                if (protocol !in setOf("progressive", "hls")) continue
                val isSupportedMime =
                    mimeType.contains("mpeg") ||
                        mimeType.contains("mp3") ||
                        mimeType.contains("aac") ||
                        mimeType.contains("mp4")
                if (!isSupportedMime) continue
                val streamUrl = transcoding.stringOrNull("url") ?: continue
                add(
                    StreamCandidate(
                        url = streamUrl,
                        protocol = protocol,
                        mimeType = mimeType,
                        preset = transcoding.stringOrNull("preset").orEmpty(),
                        isHls = protocol == "hls",
                    ),
                )
            }
        }

        return candidates.maxByOrNull { candidate ->
            var score = 0
            if (!candidate.isHls) score += 1_000
            score += when (candidate.mimeType) {
                "audio/mpeg" -> 120
                "audio/aac", "audio/mp4" -> 100
                else -> 0
            }
            if ("mp3" in candidate.preset.lowercase(Locale.US)) score += 20
            score
        }
    }

    private fun resolveTranscodingUrl(
        candidate: StreamCandidate,
        trackAuthorization: String?,
        clientId: String,
        authToken: String,
    ): String? {
        val url = candidate.url.toHttpUrlOrNull()
            ?.newBuilder()
            ?.removeAllQueryParameters("client_id")
            ?.addQueryParameter("client_id", clientId)
            ?.apply {
                trackAuthorization?.takeIf { it.isNotBlank() }?.let {
                    removeAllQueryParameters("track_authorization")
                    addQueryParameter("track_authorization", it)
                }
            }
            ?.build()
            ?: return null

        return runCatching {
            client.newCall(apiRequest(url.toString(), authToken).build()).execute().use { response ->
                val payload = response.body.string()
                if (!response.isSuccessful) {
                    throw SoundCloudResolutionException("SoundCloud transcoding HTTP ${response.code}: ${payload.take(160)}")
                }
                JSONObject(payload).stringOrNull("url")
            }
        }.getOrNull()
    }

    private fun selectBestTrack(
        results: JSONArray,
        query: Query,
    ): MatchedTrack? {
        val wantedTitle = query.title.normalized()
        val wantedArtists = query.artists.map { it.normalized() }.filter { it.isNotBlank() }
        val wantedAlbum = query.album.normalized()
        val wantedDescriptorText = listOf(wantedTitle, wantedAlbum).joinToString(" ")
        val wantedDurationMs = query.durationMs?.takeIf { it > 0L }
        val wantedTitleTokens = significantTokens(wantedTitle)

        data class Candidate(
            val track: MatchedTrack,
            val score: Int,
        )

        val candidates = mutableListOf<Candidate>()
        for (index in 0 until results.length()) {
            val obj = results.optJSONObject(index) ?: continue
            if (!obj.optString("kind").equals("track", ignoreCase = true)) continue
            if (!obj.optBoolean("streamable", true)) continue

            val track = obj.toMatchedTrack() ?: continue
            val candidateTitle = track.title.normalized()
            val candidateArtists = track.artistNames
                .ifEmpty { listOf(track.artist) }
                .map { it.normalized() }
                .filter { it.isNotBlank() }
            val candidateDescriptorText = listOf(candidateTitle, candidateArtists.joinToString(" ")).joinToString(" ")

            if (hasVersionMismatch(wantedDescriptorText, candidateDescriptorText)) continue

            val candidateTokens = significantTokens(candidateTitle)
            val matchedTokens = wantedTitleTokens.count(candidateTokens::contains)
            val titleCoverage =
                if (wantedTitleTokens.isEmpty()) {
                    0.0
                } else {
                    matchedTokens.toDouble() / wantedTitleTokens.size.toDouble()
                }
            val hasTitleMatch =
                wantedTitle.isBlank() ||
                    candidateTitle == wantedTitle ||
                    (wantedTitle.length >= 4 && (candidateTitle.contains(wantedTitle) || wantedTitle.contains(candidateTitle))) ||
                    titleCoverage >= if (wantedTitleTokens.size <= 2) 1.0 else 0.75
            val hasArtistMatch =
                wantedArtists.isEmpty() ||
                    wantedArtists.any { wanted ->
                        candidateArtists.any { candidate -> artistMatches(wanted, candidate) }
                    }
            val durationDiffSeconds =
                if (wantedDurationMs != null && track.durationMs != null) {
                    abs(wantedDurationMs - track.durationMs) / 1000L
                } else {
                    null
                }
            val hasSafeDuration = durationDiffSeconds == null || durationDiffSeconds <= 20

            if (!hasTitleMatch || !hasArtistMatch || !hasSafeDuration) continue

            var score = 0
            if (wantedTitle.isNotBlank()) {
                score += when {
                    candidateTitle == wantedTitle -> 340
                    candidateTitle.contains(wantedTitle) || wantedTitle.contains(candidateTitle) -> 140
                    wantedTitle.wordsOverlap(candidateTitle) >= 2 -> 80
                    else -> -100
                }
            }

            if (wantedTitleTokens.isNotEmpty()) {
                score += when {
                    matchedTokens == wantedTitleTokens.size -> 120
                    matchedTokens >= wantedTitleTokens.size.coerceAtLeast(1) - 1 -> 45
                    wantedTitleTokens.size <= 2 -> -150
                    else -> -70
                }
            }

            if (wantedArtists.isNotEmpty()) {
                score += when {
                    wantedArtists.any { wanted -> candidateArtists.any { it == wanted } } -> 230
                    wantedArtists.any { wanted -> candidateArtists.any { it.contains(wanted) || wanted.contains(it) } } -> 120
                    wantedArtists.any { wanted -> candidateArtists.any { wanted.wordsOverlap(it) >= 1 } } -> 40
                    else -> -80
                }
            }

            if (durationDiffSeconds != null) {
                score += when {
                    durationDiffSeconds <= 2 -> 170
                    durationDiffSeconds <= 6 -> 110
                    durationDiffSeconds <= 12 -> 45
                    else -> -150
                }
            }

            if (score >= 420) {
                candidates += Candidate(track, score)
            }
        }

        return candidates.maxByOrNull { it.score }?.track
    }

    private fun buildDownloadUrl(
        track: MatchedTrack,
        clientId: String,
    ): String {
        return "$SQUID_BASE_URL/api/soundcloud/download".toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("url", track.permalinkUrl.withClientId(clientId))
            ?.addQueryParameter("title", track.title)
            ?.addQueryParameter("artist", track.artist)
            ?.addQueryParameter("client_id", clientId)
            ?.addQueryParameter("preview", "0")
            ?.build()
            ?.toString()
            ?: throw SoundCloudResolutionException("SoundCloud download URL could not be built")
    }

    private fun fetchStreamMetadata(
        url: String,
        isApiStream: Boolean,
    ): StreamMetadata {
        val httpUrl = url.toHttpUrlOrNull() ?: return StreamMetadata("audio/mpeg", null)
        val builder = Request.Builder()
            .url(httpUrl)
            .header("Accept", "audio/*,*/*;q=0.8")
            .header("Accept-Encoding", "identity")
            .header("Referer", if (isApiStream) "https://soundcloud.com/" else "$SQUID_BASE_URL/")
            .header("User-Agent", BROWSER_USER_AGENT)

        runCatching {
            client.newCall(builder.head().build()).execute().use { response ->
                if (response.code >= 500) {
                    throw SoundCloudResolutionException("SoundCloud stream HTTP ${response.code}")
                }
                if (!response.isSuccessful) return@use null
                StreamMetadata(
                    mimeType = response.header("Content-Type")?.substringBefore(';') ?: "audio/mpeg",
                    contentLength = response.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0L },
                )
            }
        }.getOrElse { error ->
            if (error is SoundCloudResolutionException) throw error
            null
        }?.let { return it }

        return runCatching {
            client.newCall(
                builder
                    .get()
                    .header("Range", "bytes=0-0")
                    .build(),
            ).execute().use { response ->
                if (response.code >= 500) {
                    throw SoundCloudResolutionException("SoundCloud stream HTTP ${response.code}")
                }
                if (!response.isSuccessful) {
                    throw SoundCloudResolutionException("SoundCloud stream HTTP ${response.code}")
                }
                StreamMetadata(
                    mimeType = response.header("Content-Type")?.substringBefore(';') ?: "audio/mpeg",
                    contentLength = response.header("Content-Range")
                        ?.substringAfterLast('/', missingDelimiterValue = "")
                        ?.toLongOrNull()
                        ?.takeIf { it > 0L }
                        ?: response.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0L && response.code == 206 },
                )
            }
        }.getOrElse { error ->
            if (error is SoundCloudResolutionException) throw error
            null
        } ?: StreamMetadata("audio/mpeg", null)
    }

    private fun addStreamMarker(
        url: String,
        isHls: Boolean,
        source: String,
    ): String =
        url.toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter(STREAM_MARKER_QUERY, STREAM_MARKER_VALUE)
            ?.addQueryParameter(STREAM_SOURCE_QUERY, source)
            ?.apply {
                if (isHls) {
                    addQueryParameter(STREAM_HLS_MARKER_QUERY, "1")
                }
            }
            ?.build()
            ?.toString()
            ?: url

    private fun apiRequest(
        url: String,
        authToken: String,
    ): Request.Builder =
        Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json")
            .header("Referer", "https://soundcloud.com/")
            .header("User-Agent", BROWSER_USER_AGENT)
            .apply {
                if (authToken.isNotBlank()) {
                    header("Authorization", "OAuth $authToken")
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
            .takeIf { it in 64_000L..512_000L }
            ?.toInt()
    }

    private fun searchTerms(query: Query): List<String> {
        val title = query.title.trim()
        val artists = query.artists.map { it.trim() }.filter { it.isNotBlank() }
        val artistPart = artists.take(3).joinToString(" ")
        val album = query.album.orEmpty().trim()
        val terms = linkedSetOf(
            listOf(title, artists.firstOrNull().orEmpty(), album).filter { it.isNotBlank() }.joinToString(" "),
            listOf(title, artistPart, album).filter { it.isNotBlank() }.joinToString(" "),
            listOf(title, artists.firstOrNull().orEmpty()).filter { it.isNotBlank() }.joinToString(" "),
            listOf(title, artistPart).filter { it.isNotBlank() }.joinToString(" "),
        )
        if (artists.isEmpty()) {
            terms += title
        }
        return terms.filter { it.isNotBlank() }
    }

    private fun JSONObject.toMatchedTrack(): MatchedTrack? {
        if (stringOrNull("policy")?.equals("SNIP", ignoreCase = true) == true) return null
        val trackId = stringOrNull("id") ?: return null
        val title = stringOrNull("title") ?: return null
        val publisherArtist = optJSONObject("publisher_metadata")?.stringOrNull("artist")
        val userName = optJSONObject("user")?.stringOrNull("username")
        val labelName = stringOrNull("label_name")
        val artistNames = listOfNotNull(publisherArtist, userName, labelName)
            .flatMap(::splitArtistNames)
            .distinctBy { it.normalized() }
        val artist = artistNames.firstOrNull() ?: "Unknown Artist"
        val permalinkUrl = stringOrNull("permalink_url") ?: return null
        val durationMs = longOrNull("full_duration") ?: longOrNull("duration")
        val artworkUrl = stringOrNull("artwork_url")
        return MatchedTrack(
            trackId = trackId,
            title = title,
            artist = artist,
            artistNames = artistNames,
            permalinkUrl = permalinkUrl,
            artworkUrl = artworkUrl,
            durationMs = durationMs,
            trackAuthorization = stringOrNull("track_authorization"),
            transcodings = optJSONObject("media")?.optJSONArray("transcodings"),
        )
    }

    private fun MatchedTrack.toTrackMetadata(): TrackMetadata =
        TrackMetadata(
            trackId = trackId,
            title = title,
            artist = artist,
            permalinkUrl = permalinkUrl,
            artworkUrl = artworkUrl,
            durationMs = durationMs,
        )

    private fun validateStreamIsPlayable(
        track: MatchedTrack,
        streamMetadata: StreamMetadata,
        expectedDurationMs: Long?,
    ) {
        val duration = expectedDurationMs?.takeIf { it >= 60_000L } ?: return
        val length = streamMetadata.contentLength?.takeIf { it > 0L } ?: return
        val minimumBytesForFullTrack = (duration / 1000.0 * 64_000.0 / 8.0 * 0.65).toLong()
        if (length < minimumBytesForFullTrack) {
            throw SoundCloudResolutionException(
                "SoundCloud returned a likely preview for ${track.title}: $length bytes for ${duration / 1000L}s",
            )
        }
    }

    private fun String.toSoundCloudUrlOrNull(): String? {
        val url = toHttpUrlOrNull() ?: return null
        val host = url.host.lowercase(Locale.US)
        return if (host == "soundcloud.com" || host.endsWith(".soundcloud.com")) {
            this
        } else {
            null
        }
    }

    private fun String.withClientId(clientId: String): String {
        val url = toHttpUrlOrNull() ?: return this
        return url.newBuilder()
            .removeAllQueryParameters("client_id")
            .addQueryParameter("client_id", clientId)
            .build()
            .toString()
    }

    private fun String.toCodecs(): String {
        val lower = lowercase(Locale.US)
        return when {
            lower.contains("mpegurl") || lower.contains("m3u8") -> "mp3"
            lower.contains("mpeg") || lower.contains("mp3") -> "mp3"
            lower.contains("aac") || lower.contains("mp4") -> "mp4a.40.2"
            lower.contains("opus") -> "opus"
            else -> ""
        }
    }

    private fun hasVersionMismatch(
        query: String,
        candidateTitle: String,
    ): Boolean {
        val versionTokens = listOf(
            "remix",
            "live",
            "edit",
            "acoustic",
            "instrumental",
            "karaoke",
            "remaster",
            "remastered",
            "sped up",
            "slowed",
        )
        val queryHasVersion = versionTokens.any { query.contains(it) }
        val candidateHasVersion = versionTokens.any { candidateTitle.contains(it) }
        return candidateHasVersion && !queryHasVersion
    }

    private fun artistMatches(
        wanted: String,
        candidate: String,
    ): Boolean {
        if (wanted.isBlank() || candidate.isBlank()) return false
        if (wanted == candidate) return true
        if ((wanted.length >= 4 && candidate.contains(wanted)) || (candidate.length >= 4 && wanted.contains(candidate))) {
            return true
        }
        val wantedTokens = significantTokens(wanted)
        val candidateTokens = significantTokens(candidate)
        if (wantedTokens.isEmpty() || candidateTokens.isEmpty()) return false
        val overlap = wantedTokens.intersect(candidateTokens).size
        return if (wantedTokens.size <= 1) {
            overlap == wantedTokens.size
        } else {
            overlap >= wantedTokens.size - 1
        }
    }

    private fun splitArtistNames(value: String): List<String> =
        value.split(Regex("""\s*(?:,|/|&|\+|\bfeat\.?\b|\bft\.?\b|\bwith\b)\s*""", RegexOption.IGNORE_CASE))
            .map { it.trim() }
            .filter { it.isNotBlank() }

    private fun Query.cacheKey(hasAuthToken: Boolean): String {
        return listOf(
            mediaId,
            title.normalized(),
            artists.joinToString("|") { it.normalized() },
            album.normalized(),
            durationMs?.toString().orEmpty(),
            if (hasAuthToken) "auth" else "anon",
        ).joinToString("::")
    }

    private fun significantTokens(value: String): Set<String> {
        val stopWords = setOf("a", "an", "and", "feat", "ft", "for", "of", "the", "with")
        return value.split(" ")
            .map { it.trim() }
            .filter { it.length > 1 && it !in stopWords }
            .toSet()
    }

    private fun String?.normalized(): String {
        val ascii = Normalizer.normalize(this.orEmpty(), Normalizer.Form.NFD)
            .replace(Regex("""\p{Mn}+"""), "")
        return ascii
            .lowercase(Locale.US)
            .replace("&", " and ")
            .replace(Regex("""\[[^]]*]"""), " ")
            .replace(Regex("""\([^)]*\)"""), " ")
            .replace(Regex("""[^a-z0-9]+"""), " ")
            .trim()
            .replace(Regex("""\s+"""), " ")
    }

    private fun String.wordsOverlap(other: String): Int {
        val first = split(' ').filter { it.length > 1 }.toSet()
        val second = other.split(' ').filter { it.length > 1 }.toSet()
        return first.intersect(second).size
    }

    private fun JSONObject.stringOrNull(name: String): String? {
        return optString(name).trim().takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
    }

    private fun JSONObject.longOrNull(name: String): Long? {
        if (!has(name) || isNull(name)) return null
        return runCatching { getLong(name) }.getOrElse {
            optString(name).trim().toLongOrNull()
        }
    }
}
