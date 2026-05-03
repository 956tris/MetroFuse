/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.qobuz

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

object QobuzAudioProvider {
    const val BROWSER_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Mobile Safari/537.36"

    private const val SQUID_BASE_URL = "https://qobuz.squid.wtf"
    private const val JUMO_BASE_URL = "https://jumo-dl.pages.dev"
    private const val STREAM_CACHE_MS = 5 * 60 * 1000L
    private const val REJECT_SCORE = -1_000_000

    private val JUMO_SUPPORTED_REGIONS = setOf("FR", "NL", "NZ", "JP")

    enum class ResolverBackend {
        JUMO,
        SQUID,
    }

    data class Query(
        val mediaId: String,
        val title: String,
        val artists: List<String>,
        val album: String?,
        val isrc: String?,
        val durationMs: Long?,
        val countryCode: String,
        val backend: ResolverBackend,
        val qualityCode: Int = 27,
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
    )

    class QobuzResolutionException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private data class MatchedTrack(
        val trackId: String,
        val hires: Boolean,
        val bitDepth: Int?,
        val samplingRateKhz: Double?,
    )

    private data class StreamAttempt(
        val resolved: Resolved? = null,
        val error: String? = null,
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    private val trackCache = ConcurrentHashMap<String, MatchedTrack>()
    private val streamCache = ConcurrentHashMap<String, Resolved>()

    fun normalizeResolverRegion(
        countryCode: String,
        backend: ResolverBackend,
    ): String {
        val normalized = countryCode.trim().uppercase(Locale.US)
        return when (backend) {
            ResolverBackend.JUMO -> normalized.takeIf { it in JUMO_SUPPORTED_REGIONS } ?: "FR"
            ResolverBackend.SQUID -> normalized.takeIf { it.matches(Regex("[A-Z]{2}")) } ?: "US"
        }
    }

    fun resolve(query: Query): Resolved {
        val resolverRegion = normalizeResolverRegion(query.countryCode, query.backend)
        val streamCacheKey = query.cacheKey(resolverRegion)
        val now = System.currentTimeMillis()
        streamCache[streamCacheKey]
            ?.takeIf { it.expiresAtMs > now + 20_000L }
            ?.let { return it }

        val trackCacheKey = query.trackCacheKey()
        val track = trackCache[trackCacheKey]
            ?: findBestTrack(query)
                ?.also { trackCache[trackCacheKey] = it }
            ?: throw QobuzResolutionException("Qobuz match not found for ${query.title}")

        var lastError: String? = null
        for (quality in buildQualityFallbackOrder(query.qualityCode)) {
            val attempt = when (query.backend) {
                ResolverBackend.JUMO -> requestJumoStream(track, quality, resolverRegion)
                ResolverBackend.SQUID -> requestSquidStream(track, query.countryCode, quality)
            }
            attempt.resolved?.let { resolved ->
                streamCache[streamCacheKey] = resolved
                return resolved
            }
            if (!attempt.error.isNullOrBlank()) {
                lastError = attempt.error
                if (attempt.error.contains("captcha", ignoreCase = true)) break
            }
        }

        throw QobuzResolutionException(lastError ?: "Qobuz stream not found for ${query.title}")
    }

    fun invalidate(mediaId: String) {
        streamCache.keys
            .filter { it.startsWith("$mediaId::") }
            .forEach { streamCache.remove(it) }
    }

    private fun findBestTrack(query: Query): MatchedTrack? {
        for (term in searchTerms(query)) {
            val results = searchTracks(term, query.countryCode) ?: continue
            selectBestTrack(results, query)?.let { return it }
        }
        return null
    }

    private fun searchTracks(
        term: String,
        countryCode: String,
    ): JSONArray? {
        val url = "$SQUID_BASE_URL/api/get-music".toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("q", term)
            ?.addQueryParameter("offset", "0")
            ?.build()
            ?: return null

        val request = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json")
            .header("Token-Country", countryCode)
            .header("Referer", "$SQUID_BASE_URL/")
            .header("User-Agent", "Mozilla/5.0")
            .build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val payload = response.body?.string()?.takeIf { it.isNotBlank() } ?: return@use null
                val root = JSONObject(payload)
                if (!root.optBoolean("success", false)) return@use null
                root.optJSONObject("data")
                    ?.optJSONObject("tracks")
                    ?.optJSONArray("items")
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
        val wantedIsrc = query.isrc?.trim()?.uppercase(Locale.US).orEmpty()
        val wantedDescriptorText = listOf(wantedTitle, wantedAlbum).joinToString(" ")
        val wantedDurationSec = query.durationMs?.let { (it / 1000L).toInt() }
        val wantedTitleTokens = significantTokens(wantedTitle)

        data class Candidate(
            val track: MatchedTrack,
            val score: Int,
        )

        val candidates = mutableListOf<Candidate>()
        for (index in 0 until results.length()) {
            val obj = results.optJSONObject(index) ?: continue
            if (!obj.optBoolean("downloadable", false)) continue

            val trackId = obj.stringOrNull("id") ?: continue
            val candidateTitle = obj.stringOrNull("title").normalized()
            val candidateVersion = obj.stringOrNull("version").normalized()
            val candidateCombinedTitle = listOf(candidateTitle, candidateVersion)
                .filter { it.isNotBlank() }
                .joinToString(" ")
            val candidateAlbum = obj.optJSONObject("album")
                ?.stringOrNull("title")
                .normalized()
            val candidateIsrc = obj.stringOrNull("isrc")?.trim()?.uppercase(Locale.US).orEmpty()
            val candidateDuration = obj.intOrNull("duration")
            val candidateArtists = collectArtistNames(obj).map { it.normalized() }.filter { it.isNotBlank() }
            val hires = obj.optBoolean("hires", false)
            val bitDepth = obj.intOrNull("maximum_bit_depth")
            val samplingRate = obj.doubleOrNull("maximum_sampling_rate")

            if (hasVersionMismatch(wantedDescriptorText, candidateCombinedTitle)) continue

            var score = 0

            if (wantedIsrc.isNotBlank() && candidateIsrc == wantedIsrc) {
                score += 1000
            }

            if (wantedTitle.isNotBlank()) {
                score += when {
                    candidateTitle == wantedTitle -> 320
                    candidateCombinedTitle.contains(wantedTitle) || wantedTitle.contains(candidateTitle) -> 130
                    wantedTitle.wordsOverlap(candidateCombinedTitle) >= 2 -> 60
                    else -> -80
                }
            }

            if (wantedTitleTokens.isNotEmpty()) {
                val candidateTokens = significantTokens(candidateCombinedTitle)
                val matchedTokens = wantedTitleTokens.count(candidateTokens::contains)
                when {
                    matchedTokens == wantedTitleTokens.size -> score += 120
                    matchedTokens >= wantedTitleTokens.size.coerceAtLeast(1) - 1 -> score += 40
                    wantedTitleTokens.size <= 2 -> score -= 160
                    else -> score -= 60
                }
            }

            if (wantedAlbum.isNotBlank() && candidateAlbum.isNotBlank()) {
                score += when {
                    candidateAlbum == wantedAlbum -> 180
                    candidateAlbum.contains(wantedAlbum) || wantedAlbum.contains(candidateAlbum) -> 80
                    wantedAlbum.wordsOverlap(candidateAlbum) >= 2 -> 35
                    else -> -35
                }
            }

            if (wantedArtists.isNotEmpty()) {
                val exactMatches = wantedArtists.count { it in candidateArtists }
                score += when {
                    exactMatches > 0 -> 220 + ((exactMatches - 1) * 50)
                    wantedArtists.any { wanted ->
                        candidateArtists.any { candidate -> candidate.contains(wanted) || wanted.contains(candidate) }
                    } -> 90
                    else -> REJECT_SCORE
                }
            }

            if (wantedDurationSec != null && candidateDuration != null) {
                val diff = abs(wantedDurationSec - candidateDuration)
                score += when {
                    diff <= 2 -> 160
                    diff <= 5 -> 100
                    diff <= 10 -> 45
                    diff >= 30 -> -120
                    else -> 0
                }
            }

            if (hires) score += 15

            if (score > REJECT_SCORE && (score >= 260 || wantedIsrc.isNotBlank() && candidateIsrc == wantedIsrc)) {
                candidates += Candidate(
                    track = MatchedTrack(
                        trackId = trackId,
                        hires = hires,
                        bitDepth = bitDepth,
                        samplingRateKhz = samplingRate,
                    ),
                    score = score,
                )
            }
        }

        return candidates.maxByOrNull { it.score }?.track
    }

    private fun requestSquidStream(
        track: MatchedTrack,
        countryCode: String,
        qualityCode: Int,
    ): StreamAttempt {
        val url = "$SQUID_BASE_URL/api/download-music".toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("track_id", track.trackId)
            ?.addQueryParameter("quality", qualityCode.toString())
            ?.build()
            ?: return StreamAttempt(error = "Qobuz request URL could not be built")

        val request = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Token-Country", countryCode)
            .header("Origin", SQUID_BASE_URL)
            .header("Referer", "$SQUID_BASE_URL/")
            .header("User-Agent", BROWSER_USER_AGENT)
            .build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                val payload = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@use StreamAttempt(error = "Qobuz HTTP ${response.code}: ${payload.take(160)}")
                }
                if (payload.isBlank()) {
                    return@use StreamAttempt(error = "Qobuz returned an empty response")
                }
                val root = JSONObject(payload)
                if (!root.optBoolean("success", false)) {
                    val apiError = root.stringOrNull("error")
                    val message = if (apiError.equals("Captcha required.", ignoreCase = true)) {
                        "Qobuz download API requires a browser captcha right now"
                    } else {
                        "Qobuz rejected quality $qualityCode: ${apiError ?: "unknown error"}"
                    }
                    return@use StreamAttempt(error = message)
                }

                val streamUrl = root.optJSONObject("data")?.stringOrNull("url")
                    ?: return@use StreamAttempt(error = "Qobuz did not return a stream URL for quality $qualityCode")
                val format = formatFrom(
                    mimeType = if (qualityCode == 5) "audio/mpeg" else "audio/flac",
                    bitDepth = track.bitDepth,
                    samplingRateKhz = track.samplingRateKhz,
                    bitrate = null,
                    hires = track.hires || qualityCode >= 7,
                )
                StreamAttempt(resolved = format.toResolved(streamUrl, track.trackId))
            }
        }.getOrElse { error ->
            StreamAttempt(error = "Qobuz request failed: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun requestJumoStream(
        track: MatchedTrack,
        qualityCode: Int,
        region: String,
    ): StreamAttempt {
        val url = "$JUMO_BASE_URL/fetch".toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("track_id", track.trackId)
            ?.addQueryParameter("format_id", qualityCode.toString())
            ?.addQueryParameter("region", region)
            ?.build()
            ?: return StreamAttempt(error = "JUMO request URL could not be built")

        val request = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json,text/plain,*/*")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Origin", JUMO_BASE_URL)
            .header("Referer", "$JUMO_BASE_URL/")
            .header("User-Agent", BROWSER_USER_AGENT)
            .build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                val payload = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@use StreamAttempt(error = "JUMO HTTP ${response.code}: ${payload.take(160)}")
                }
                if (payload.isBlank()) {
                    return@use StreamAttempt(error = "JUMO returned an empty response")
                }
                val root = JSONObject(payload)
                root.stringOrNull("error")?.takeIf { it.isNotBlank() }?.let { apiError ->
                    return@use StreamAttempt(error = "JUMO rejected quality $qualityCode: $apiError")
                }
                if (root.optBoolean("previewDetected", false)) {
                    return@use StreamAttempt(error = "JUMO returned a preview instead of a full Qobuz stream")
                }

                val streamUrl = root.stringOrNull("directUrl")
                    ?: root.stringOrNull("url")
                    ?: return@use StreamAttempt(error = "JUMO did not return a stream URL for quality $qualityCode")
                val bitDepth = root.intOrNull("bit_depth") ?: track.bitDepth
                val samplingRate = root.doubleOrNull("sampling_rate") ?: track.samplingRateKhz
                val mimeType = root.stringOrNull("mime_type")
                    ?: if (qualityCode == 5) "audio/mpeg" else "audio/flac"
                val bitrate = root.intOrNull("bitrate")
                    ?: root.intOrNull("bit_rate")
                    ?: root.intOrNull("average_bitrate")
                val hires = (bitDepth ?: 0) > 16 || (samplingRate ?: 0.0) > 44.1 || qualityCode >= 7
                val format = formatFrom(
                    mimeType = mimeType,
                    bitDepth = bitDepth,
                    samplingRateKhz = samplingRate,
                    bitrate = bitrate,
                    hires = hires,
                )
                StreamAttempt(
                    resolved = format.toResolved(
                        url = streamUrl,
                        trackId = root.stringOrNull("resolvedTrackId") ?: track.trackId,
                    )
                )
            }
        }.getOrElse { error ->
            StreamAttempt(error = "JUMO request failed: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private data class StreamFormat(
        val label: String,
        val mimeType: String,
        val codecs: String,
        val bitrate: Int,
        val sampleRate: Int?,
    ) {
        fun toResolved(
            url: String,
            trackId: String,
        ) = Resolved(
            mediaUri = url,
            trackId = trackId,
            label = label,
            mimeType = mimeType,
            codecs = codecs,
            bitrate = bitrate,
            sampleRate = sampleRate,
            expiresAtMs = extractExpiryMs(url),
        )
    }

    private fun formatFrom(
        mimeType: String,
        bitDepth: Int?,
        samplingRateKhz: Double?,
        bitrate: Int?,
        hires: Boolean,
    ): StreamFormat {
        val lowerMime = mimeType.lowercase(Locale.US)
        val sampleRate = samplingRateKhz
            ?.takeIf { it > 0.0 }
            ?.let { (it * 1000.0).roundToInt() }
        val normalizedBitrate = bitrate
            ?.let { if (it in 1..9999) it * 1000 else it }
            ?: 0

        return when {
            lowerMime.contains("mpeg") || lowerMime.contains("mp3") -> StreamFormat(
                label = "Qobuz MP3",
                mimeType = "audio/mpeg",
                codecs = "mp3",
                bitrate = normalizedBitrate.takeIf { it > 0 } ?: 320_000,
                sampleRate = sampleRate,
            )

            lowerMime.contains("aac") || lowerMime.contains("mp4") -> StreamFormat(
                label = "Qobuz AAC",
                mimeType = "audio/mp4",
                codecs = "mp4a.40.2",
                bitrate = normalizedBitrate,
                sampleRate = sampleRate,
            )

            else -> StreamFormat(
                label = buildFlacLabel(bitDepth, samplingRateKhz, hires),
                mimeType = "audio/flac",
                codecs = "flac",
                bitrate = normalizedBitrate,
                sampleRate = sampleRate,
            )
        }
    }

    private fun buildFlacLabel(
        bitDepth: Int?,
        samplingRateKhz: Double?,
        hires: Boolean,
    ): String = buildString {
        append("Qobuz ")
        append(if (hires) "Hi-Res FLAC" else "CD FLAC")
        if (bitDepth != null && samplingRateKhz != null) {
            append(" ")
            append(bitDepth)
            append("bit/")
            append(formatSamplingRate(samplingRateKhz))
            append("kHz")
        }
    }

    private fun buildQualityFallbackOrder(qualityCode: Int): List<Int> {
        val ladder = listOf(27, 7, 6, 5)
        val startIndex = ladder.indexOf(qualityCode)
        return if (startIndex >= 0) ladder.drop(startIndex) else listOf(qualityCode)
    }

    private fun searchTerms(query: Query): List<String> {
        val title = query.title.trim()
        val artists = query.artists.map { it.trim() }.filter { it.isNotBlank() }
        val artistPart = artists.take(3).joinToString(" ")
        val album = query.album.orEmpty().trim()
        return linkedSetOf(
            query.isrc.orEmpty().trim(),
            listOf(title, artists.firstOrNull().orEmpty(), album).filter { it.isNotBlank() }.joinToString(" "),
            listOf(title, artistPart, album).filter { it.isNotBlank() }.joinToString(" "),
            listOf(title, artists.firstOrNull().orEmpty()).filter { it.isNotBlank() }.joinToString(" "),
            listOf(title, artistPart).filter { it.isNotBlank() }.joinToString(" "),
            listOf(title, album).filter { it.isNotBlank() }.joinToString(" "),
            title,
        ).filter { it.isNotBlank() }
    }

    private fun collectArtistNames(track: JSONObject): List<String> {
        val names = mutableListOf<String>()
        track.optJSONObject("performer")?.stringOrNull("name")?.let(names::add)
        val album = track.optJSONObject("album")
        album?.optJSONObject("artist")?.stringOrNull("name")?.let(names::add)
        album?.optJSONArray("artists")?.let { artists ->
            for (index in 0 until artists.length()) {
                artists.optJSONObject(index)?.stringOrNull("name")?.let(names::add)
            }
        }
        return names.distinct()
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

    private fun Query.cacheKey(resolverRegion: String): String {
        return listOf(
            mediaId,
            trackCacheKey(),
            backend.name,
            resolverRegion,
            qualityCode.toString(),
        ).joinToString("::")
    }

    private fun Query.trackCacheKey(): String {
        return listOf(
            title.normalized(),
            artists.joinToString("|") { it.normalized() },
            album.normalized(),
            isrc?.trim()?.uppercase(Locale.US).orEmpty(),
            countryCode.uppercase(Locale.US),
        ).joinToString("|")
    }

    private fun extractExpiryMs(url: String): Long {
        val etsp = url.toHttpUrlOrNull()
            ?.queryParameter("etsp")
            ?.toLongOrNull()
        if (etsp != null) {
            return (etsp * 1000L) - 15_000L
        }
        return System.currentTimeMillis() + STREAM_CACHE_MS
    }

    private fun formatSamplingRate(value: Double): String {
        return if (value % 1.0 == 0.0) {
            value.toInt().toString()
        } else {
            value.toString()
        }
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

    private fun JSONObject.intOrNull(name: String): Int? {
        if (!has(name) || isNull(name)) return null
        return runCatching { getInt(name) }.getOrElse {
            optString(name).trim().toIntOrNull()
        }
    }

    private fun JSONObject.doubleOrNull(name: String): Double? {
        if (!has(name) || isNull(name)) return null
        return runCatching { getDouble(name) }.getOrElse {
            optString(name).trim().toDoubleOrNull()
        }
    }
}
