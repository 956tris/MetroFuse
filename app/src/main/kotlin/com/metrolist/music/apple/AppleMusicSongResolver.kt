/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.apple

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs

object AppleMusicSongResolver {
    private const val TAG = "AppleALAC"
    private const val DEFAULT_STOREFRONT = "US"
    private const val AMP_BASE_URL = "https://amp-api.music.apple.com"
    private const val APPLE_MUSIC_TOKEN =
        "eyJhbGciOiJFUzI1NiIsInR5cCI6IkpXVCIsImtpZCI6IldlYlBsYXlLaWQifQ" +
            ".eyJpc3MiOiJBTVBXZWJQbGF5IiwiaWF0IjoxNzc0NDU2MzgyLCJleHAiOjE3ODE3" +
            "MTM5ODIsInJvb3RfaHR0cHNfb3JpZ2luIjpbImFwcGxlLmNvbSJdfQ" +
            ".4n8qYF4qa18sL1E0G9A3qX35cD8wQ-IJcS9Bh8ZT8JV_yLBtVq46B-9-2ZS3EvWHuw3yK9BYFYAhAdTaDm38vQ"
    private const val STREAM_CACHE_MS = 25 * 60 * 1000L
    private const val REJECT_SCORE = -1_000_000

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS)
        .build()
    private val resolvedCache = ConcurrentHashMap<String, Resolved>()

    data class Query(
        val mediaId: String,
        val title: String,
        val artists: List<String>,
        val album: String?,
        val isrc: String?,
        val durationMs: Long?,
        val explicit: Boolean?,
        val storefront: String = DEFAULT_STOREFRONT,
        val wrapperHost: String = AppleMusicWrapperManagerProvider.DEFAULT_HOST,
        val wrapperSecure: Boolean = true,
        val audioMode: AppleMusicWrapperManagerProvider.WrapperMode = AppleMusicWrapperManagerProvider.WrapperMode.ALAC,
        val highWorkerMode: Boolean = false,
    )

    data class Resolved(
        val mediaUri: String,
        val adamId: String,
        val title: String,
        val artist: String,
        val album: String?,
        val durationMs: Long?,
        val bitrate: Int,
        val sampleRate: Int?,
        val audioMode: AppleMusicWrapperManagerProvider.WrapperMode,
        val expiresAtMs: Long,
    )

    data class CandidateMetadata(
        val adamId: String,
        val title: String,
        val artist: String,
        val album: String?,
        val isrc: String?,
        val durationMs: Long?,
        val url: String?,
    )

    private data class Candidate(
        val adamId: String,
        val title: String,
        val artist: String,
        val album: String?,
        val isrc: String?,
        val durationMs: Long?,
        val explicit: Boolean?,
        val url: String?,
    )

    fun resolve(query: Query): Resolved {
        val cacheKey = query.cacheKey()
        val now = System.currentTimeMillis()
        resolvedCache[cacheKey]
            ?.takeIf { it.expiresAtMs > now + 30_000L }
            ?.let { return it }

        val track = query.mediaId.toAppleAdamIdOrNull()
            ?.let { query.toDirectCandidate(it) }
            ?: findBestTrack(query)
            ?: throw AppleMusicWrapperManagerProvider.WrapperManagerException(
                "No Apple Music match found for ${query.title}"
            )
        val wrapper = AppleMusicWrapperManagerProvider.getM3u8WithFallback(
            adamId = track.adamId,
            preferredHost = query.wrapperHost,
            preferredSecure = query.wrapperSecure,
            mode = query.audioMode,
        )
        val quality =
            if (query.audioMode == AppleMusicWrapperManagerProvider.WrapperMode.ALAC) {
                runCatching {
                    AppleMusicDecryptPipeline.readAlacQualityMetadata(
                        client = client,
                        initialUrl = wrapper.url,
                        preferFast = false,
                    )
                }.onFailure { error ->
                    Timber.tag(TAG).w(error, "Failed to read ALAC quality metadata for adamId=${track.adamId}")
                }.getOrNull()
            } else {
                null
            }

        val mediaUri = AppleMusicWrapperDataSource.buildUri(
            mediaId = query.mediaId,
            adamId = track.adamId,
            m3u8Url = wrapper.url,
            host = wrapper.host,
            secure = wrapper.secure,
            mode = query.audioMode,
            durationMs = query.durationMs ?: track.durationMs,
            title = track.title,
            highWorkerMode = query.highWorkerMode,
        )
        return Resolved(
            mediaUri = mediaUri,
            adamId = track.adamId,
            title = track.title,
            artist = track.artist,
            album = track.album,
            durationMs = query.durationMs ?: track.durationMs,
            bitrate = quality?.bitrate ?: query.audioMode.defaultBitrate(),
            sampleRate = quality?.sampleRate,
            audioMode = query.audioMode,
            expiresAtMs = now + STREAM_CACHE_MS,
        ).also { resolvedCache[cacheKey] = it }
    }

    fun searchCandidates(
        query: Query,
        limit: Int = 8,
    ): List<CandidateMetadata> {
        val results = linkedMapOf<String, CandidateMetadata>()
        for (term in searchTerms(query)) {
            fetchAppleCatalogCandidates(query, term).forEach { candidate ->
                results.putIfAbsent(candidate.adamId, candidate.toCandidateMetadata())
                if (results.size >= limit) return results.values.toList()
            }
            fetchItunesCandidates(query, term).forEach { candidate ->
                results.putIfAbsent(candidate.adamId, candidate.toCandidateMetadata())
                if (results.size >= limit) return results.values.toList()
            }
        }
        return results.values.toList()
    }

    fun invalidate(mediaId: String) {
        resolvedCache.keys
            .filter { it.startsWith("$mediaId::") }
            .forEach { resolvedCache.remove(it) }
    }

    private fun findBestTrack(query: Query): Candidate? {
        findAppleCatalogTrackByIsrc(query)?.let { return it }
        findBestAppleCatalogTrack(query)?.let { return it }
        return findBestItunesTrack(query)
    }

    private fun findAppleCatalogTrackByIsrc(query: Query): Candidate? {
        val isrc = query.isrc?.trim()?.uppercase(Locale.US)?.takeIf { it.isNotBlank() } ?: return null
        val storefront = query.storefront.lowercase(Locale.US)
        val url = "$AMP_BASE_URL/v1/catalog/$storefront/songs".toHttpUrl().newBuilder()
            .addQueryParameter("filter[isrc]", isrc)
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $APPLE_MUSIC_TOKEN")
            .header("Origin", "https://music.apple.com")
            .header("Referer", "https://music.apple.com/")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/147 Mobile Safari/537.36")
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body ?: return@use null
                val data = JSONObject(body.string()).optJSONArray("data") ?: return@use null
                (0 until data.length())
                    .mapNotNull { index -> data.optJSONObject(index)?.toAppleCatalogCandidate() }
                    .firstOrNull { candidate ->
                        candidate.isrc?.equals(isrc, ignoreCase = true) == true &&
                            score(candidate, query) > REJECT_SCORE
                    }
            }
        }.getOrNull()
    }

    private fun findBestAppleCatalogTrack(query: Query): Candidate? {
        val candidates = searchTerms(query).asSequence()
            .flatMap { term -> fetchAppleCatalogCandidates(query, term).asSequence() }
            .distinctBy { it.adamId }
            .toList()
        return selectBestCandidate(candidates, query)
    }

    private fun findBestItunesTrack(query: Query): Candidate? {
        val candidates = searchTerms(query).asSequence()
            .flatMap { term -> fetchItunesCandidates(query, term).asSequence() }
            .distinctBy { it.adamId }
            .toList()
        return selectBestCandidate(candidates, query)
    }

    private fun searchTerms(query: Query): List<String> {
        val artistPart = query.artists.take(2).joinToString(" ").trim()
        val terms = buildList {
            add(listOf(query.title, artistPart, query.album.orEmpty()).filter { it.isNotBlank() }.joinToString(" "))
            add(listOf(query.title, artistPart).filter { it.isNotBlank() }.joinToString(" "))
            add(query.title)
        }
        return terms.map { it.trim() }.filter { it.isNotBlank() }.distinct()
    }

    private fun fetchAppleCatalogCandidates(
        query: Query,
        term: String,
    ): List<Candidate> {
        val storefront = query.storefront.lowercase(Locale.US)
        val url = "$AMP_BASE_URL/v1/catalog/$storefront/search".toHttpUrl().newBuilder()
            .addQueryParameter("term", term)
            .addQueryParameter("types", "songs")
            .addQueryParameter("limit", "25")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $APPLE_MUSIC_TOKEN")
            .header("Origin", "https://music.apple.com")
            .header("Referer", "https://music.apple.com/")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/147 Mobile Safari/537.36")
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()
                val body = response.body ?: return@use emptyList()
                val root = JSONObject(body.string())
                val data = root.optJSONObject("results")
                    ?.optJSONObject("songs")
                    ?.optJSONArray("data")
                    ?: return@use emptyList()
                (0 until data.length()).mapNotNull { index ->
                    data.optJSONObject(index)?.toAppleCatalogCandidate()
                }
            }
        }.getOrElse { emptyList() }
    }

    private fun fetchItunesCandidates(
        query: Query,
        term: String,
    ): List<Candidate> {
        val url = "https://itunes.apple.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("term", term)
            .addQueryParameter("media", "music")
            .addQueryParameter("entity", "song")
            .addQueryParameter("country", query.storefront)
            .addQueryParameter("limit", "25")
            .apply {
                if (query.explicit == true) {
                    addQueryParameter("explicit", "Yes")
                }
            }
            .build()
        val request = Request.Builder()
            .url(url)
                    .header("User-Agent", "MetroFuse/AppleMusicWrapper")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw AppleMusicWrapperManagerProvider.WrapperManagerException(
                    "iTunes lookup failed with HTTP ${response.code}"
                )
            }
            val body = response.body
                ?: throw AppleMusicWrapperManagerProvider.WrapperManagerException("iTunes lookup returned no body")
            val root = JSONObject(body.string())
            val results = root.optJSONArray("results") ?: return emptyList()
            return (0 until results.length()).mapNotNull { index ->
                results.optJSONObject(index)?.toItunesCandidate()
            }
        }
    }

    private fun selectBestCandidate(
        candidates: List<Candidate>,
        query: Query,
    ): Candidate? {
        return candidates.asSequence()
            .map { candidate -> candidate to score(candidate, query) }
            .filter { (_, score) -> score > REJECT_SCORE }
            .maxWithOrNull(
                compareBy<Pair<Candidate, Int>> { it.second }
                    .thenBy { if (it.first.explicit == true) 1 else 0 }
            )
            ?.takeIf { (_, score) -> score >= 60 }
            ?.first
    }

    private fun JSONObject.toAppleCatalogCandidate(): Candidate? {
        val adamId = optString("id").takeIf { it.isNotBlank() } ?: return null
        val attributes = optJSONObject("attributes") ?: return null
        return Candidate(
            adamId = adamId,
            title = attributes.optString("name").takeIf { it.isNotBlank() } ?: return null,
            artist = attributes.optString("artistName"),
            album = attributes.optString("albumName").takeIf { it.isNotBlank() },
            isrc = attributes.optString("isrc").takeIf { it.isNotBlank() },
            durationMs = attributes.optLong("durationInMillis").takeIf { it > 0L },
            explicit = when (attributes.optString("contentRating").lowercase(Locale.US)) {
                "explicit" -> true
                "clean" -> false
                else -> null
            },
            url = attributes.optString("url").takeIf { it.isNotBlank() },
        )
    }

    private fun JSONObject.toItunesCandidate(): Candidate? {
        val adamId = optLong("trackId").takeIf { it > 0L }?.toString() ?: return null
        return Candidate(
            adamId = adamId,
            title = optString("trackName").takeIf { it.isNotBlank() } ?: return null,
            artist = optString("artistName"),
            album = optString("collectionName").takeIf { it.isNotBlank() },
            isrc = null,
            durationMs = optLong("trackTimeMillis").takeIf { it > 0L },
            explicit = when (optString("trackExplicitness").lowercase(Locale.US)) {
                "explicit" -> true
                "notexplicit", "cleaned" -> false
                else -> when (optString("collectionExplicitness").lowercase(Locale.US)) {
                    "explicit" -> true
                    "notexplicit", "cleaned" -> false
                    else -> null
                }
            },
            url = optString("trackViewUrl").takeIf { it.isNotBlank() },
        )
    }

    private fun score(
        candidate: Candidate,
        query: Query,
    ): Int {
        val wantedTitle = query.title.normalized()
        val candidateTitle = candidate.title.normalized()
        if (hasBlockedVariant(candidate, query)) return REJECT_SCORE
        if (!query.isrc.isNullOrBlank() && candidate.isrc.equals(query.isrc, ignoreCase = true)) {
            return 260
        }

        var score = when {
            wantedTitle == candidateTitle -> 80
            candidateTitle.contains(wantedTitle) || wantedTitle.contains(candidateTitle) -> 55
            wantedTitle.wordsOverlap(candidateTitle) >= 2 -> 35
            else -> 0
        }

        val wantedArtists = query.artists.map { it.normalized() }.filter { it.isNotBlank() }
        val candidateArtist = candidate.artist.normalized()
        if (wantedArtists.any { candidateArtist.contains(it) || it.contains(candidateArtist) }) {
            score += 30
        } else if (wantedArtists.any { it.wordsOverlap(candidateArtist) >= 1 }) {
            score += 12
        } else if (wantedArtists.isNotEmpty()) {
            score -= 55
        }

        val wantedAlbum = query.album?.normalized().orEmpty()
        val candidateAlbum = candidate.album?.normalized().orEmpty()
        if (wantedAlbum.isNotBlank() && candidateAlbum.isNotBlank()) {
            score += when {
                wantedAlbum == candidateAlbum -> 18
                wantedAlbum.wordsOverlap(candidateAlbum) >= 2 -> 8
                else -> 0
            }
        }

        val wantedDuration = query.durationMs
        val candidateDuration = candidate.durationMs
        if (wantedDuration != null && candidateDuration != null) {
            val diff = abs(wantedDuration - candidateDuration)
            score += when {
                diff <= 2_000L -> 25
                diff <= 7_000L -> 14
                diff <= 15_000L -> 5
                else -> -25
            }
        }

        if (query.explicit == true) {
            when (candidate.explicit) {
                true -> score += 25
                false -> score -= 65
                null -> score -= 20
            }
        } else if (query.explicit == false) {
            when (candidate.explicit) {
                true -> score -= 35
                false -> score += 8
                null -> Unit
            }
        }
        return score
    }

    private fun hasBlockedVariant(
        candidate: Candidate,
        query: Query,
    ): Boolean {
        val wanted = listOf(query.title, query.album.orEmpty())
            .joinToString(" ")
            .descriptorNormalized()
        val offered = listOf(candidate.title, candidate.album.orEmpty(), candidate.artist)
            .joinToString(" ")
            .descriptorNormalized()
        return BLOCKED_VARIANTS.any { variants ->
            variants.any { offered.hasPhrase(it) } && variants.none { wanted.hasPhrase(it) }
        }
    }

    private val BLOCKED_VARIANTS = listOf(
        listOf("instrumental", "instrumentals", "backing track", "karaoke"),
        listOf("tribute", "made famous by"),
        listOf("cover", "covers"),
        listOf("clean version", "clean edit", "radio edit"),
        listOf("acapella", "a cappella"),
        listOf("sped up", "speed up", "nightcore"),
        listOf("slowed", "slowed reverb"),
        listOf("remix"),
        listOf("live", "live version"),
        listOf("demo"),
    )

    private fun Query.cacheKey(): String {
        return listOf(
            mediaId,
            title.normalized(),
            artists.joinToString("|") { it.normalized() },
            album?.normalized().orEmpty(),
            isrc?.uppercase(Locale.US).orEmpty(),
            durationMs?.toString().orEmpty(),
            explicit?.toString().orEmpty(),
            storefront.uppercase(Locale.US),
            AppleMusicWrapperManagerProvider.normalizeHost(wrapperHost),
            wrapperSecure.toString(),
            audioMode.name,
            highWorkerMode.toString(),
        ).joinToString("::")
    }

    private fun AppleMusicWrapperManagerProvider.WrapperMode.defaultBitrate(): Int =
        when (this) {
            AppleMusicWrapperManagerProvider.WrapperMode.ALAC -> 0
            AppleMusicWrapperManagerProvider.WrapperMode.AAC -> 256_000
            AppleMusicWrapperManagerProvider.WrapperMode.DOLBY_ATMOS -> 768_000
        }

    private fun Query.toDirectCandidate(adamId: String): Candidate =
        Candidate(
            adamId = adamId,
            title = title,
            artist = artists.joinToString(", "),
            album = album,
            isrc = isrc,
            durationMs = durationMs,
            explicit = explicit,
            url = null,
        )

    private fun Candidate.toCandidateMetadata(): CandidateMetadata =
        CandidateMetadata(
            adamId = adamId,
            title = title,
            artist = artist,
            album = album,
            isrc = isrc,
            durationMs = durationMs,
            url = url,
        )

    private fun String.toAppleAdamIdOrNull(): String? {
        val trimmed = trim()
        if (trimmed.matches(Regex("\\d{4,}"))) return trimmed
        Regex("""^apple(?::track)?:([0-9]{4,})$""", RegexOption.IGNORE_CASE)
            .matchEntire(trimmed)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { return it }
        Regex("""music\.apple\.com/(?:[a-z]{2}/)?(?:album/[^/]+/)?(?:[^?]+)?\?i=([0-9]{4,})""", RegexOption.IGNORE_CASE)
            .find(trimmed)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { return it }
        return null
    }

    private fun String.normalized(): String {
        val ascii = Normalizer.normalize(this, Normalizer.Form.NFD)
            .replace(Regex("""\p{Mn}+"""), "")
        return ascii
            .lowercase(Locale.US)
            .replace(Regex("""\([^)]*\)|\[[^]]*]"""), " ")
            .replace("&", " and ")
            .replace(Regex("""[^a-z0-9]+"""), " ")
            .trim()
            .replace(Regex("""\s+"""), " ")
    }

    private fun String.descriptorNormalized(): String {
        val ascii = Normalizer.normalize(this, Normalizer.Form.NFD)
            .replace(Regex("""\p{Mn}+"""), "")
        return ascii
            .lowercase(Locale.US)
            .replace("&", " and ")
            .replace(Regex("""[^a-z0-9]+"""), " ")
            .trim()
            .replace(Regex("""\s+"""), " ")
    }

    private fun String.hasPhrase(phrase: String): Boolean {
        return Regex("""(^|\s)${Regex.escape(phrase)}(\s|$)""").containsMatchIn(this)
    }

    private fun String.wordsOverlap(other: String): Int {
        val first = split(' ').filter { it.length > 1 }.toSet()
        val second = other.split(' ').filter { it.length > 1 }.toSet()
        return first.intersect(second).size
    }
}
