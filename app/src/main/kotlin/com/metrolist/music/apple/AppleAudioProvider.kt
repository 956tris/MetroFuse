/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.apple

import com.metrolist.music.constants.AppleAudioQuality
import com.metrolist.music.constants.toCodec
import com.metrolist.music.providers.IsrcResolver
import com.metrolist.music.providers.ProviderIsrc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object AppleAudioProvider {
    private const val TAG = "AppleAudioProvider"
    const val DEFAULT_STREAM_API_BASE = "https://geeked-api.onrender.com/stream"
    const val LEGACY_STREAM_API_BASE = "https://yesitworkssomehow-funi-lyric-api.hf.space/stream"

    private val appleUrlCache = ConcurrentHashMap<String, String>()

    data class Query(
        val song: String,
        val artist: String,
        val album: String?,
        val isrc: String?,
        val durationMs: Long?,
        val quality: AppleAudioQuality = AppleAudioQuality.AAC_WEB,
        val resolverEndpoints: String? = null,
    )

    /**
     * Parses user-configured custom Apple stream APIs (one per line, comma-,
     * semicolon-, space- or tab-separated). Each entry may be a full
     * `/stream` endpoint URL (e.g. `https://geeked-api.onrender.com/stream`)
     * or a bare base URL (e.g. `https://my-api.example.com`), in which case
     * `/stream` is appended. Returns the custom list, or the defaults when
     * nothing valid is configured.
     */
    fun resolverEndpointBases(customResolverEndpoints: String? = null): List<String> {
        val custom = customResolverEndpoints.normalizedResolverEndpoints()
        if (custom.isNotEmpty()) return custom
        return listOf(DEFAULT_STREAM_API_BASE, LEGACY_STREAM_API_BASE)
    }

    fun normalizeResolverEndpointsInput(value: String): String =
        value.normalizedResolverEndpoints().joinToString("\n")

    fun isResolverEndpointsInputValid(value: String): Boolean {
        val entries = value.resolverEndpointTokens()
        return entries.all { token -> token.normalizedResolverEndpointOrNull() != null }
    }

    private fun String?.normalizedResolverEndpoints(): List<String> =
        orEmpty()
            .resolverEndpointTokens()
            .mapNotNull { token -> token.normalizedResolverEndpointOrNull() }
            .distinctBy { it.lowercase(Locale.US) }

    private fun String.resolverEndpointTokens(): List<String> =
        split('\n', '\r', ',', ';', '\t', ' ')
            .map { it.trim() }
            .filter { it.isNotBlank() }

    private fun String.normalizedResolverEndpointOrNull(): String? {
        val candidate = trim()
            .trimEnd('/')
            .let { value ->
                if (value.contains("://")) value else "https://$value"
            }
        val normalized = candidate.toHttpUrlOrNull()?.toString()?.trimEnd('/') ?: return null
        // Accept either a bare host base or a full /stream endpoint.
        return if (normalized.endsWith("/stream", ignoreCase = true)) normalized else "$normalized/stream"
    }

    /**
     * Builds the exact request URL sent to a stream API:
     * `{base}/stream?url={appleMusicUrl}&codec={codec}`
     * where `codec` is the selected quality's codec string (e.g. `aac`).
     */
    fun buildStreamUrl(base: String, appleUrl: String, codec: String): String {
        val streamBase = base.trim().trimEnd('/').let { value ->
            if (value.endsWith("/stream", ignoreCase = true)) value else "$value/stream"
        }
        return streamBase.toHttpUrl().newBuilder()
            .addQueryParameter("url", appleUrl)
            .addQueryParameter("codec", codec)
            .build()
            .toString()
    }

    data class Resolved(
        val mediaUri: String,
        val trackId: String,
        val title: String,
        val artist: String,
        val mimeType: String,
        val codecs: String,
        val bitrate: Int,
        val expiresAtMs: Long,
    )

    class AppleResolutionException(val code: String, message: String, cause: Throwable? = null) : Exception(message, cause)

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun resolve(query: Query): Resolved = withContext(Dispatchers.IO) {
        Timber.tag(TAG).d("[APPLE RESOLVER] Apple URL resolution started for: ${query.song} - ${query.artist}")

        val isrc = IsrcResolver.resolveAndValidate(
            candidateIsrc = query.isrc,
            song = query.song,
            artist = query.artist,
            durationSeconds = query.durationMs?.div(1000)?.toInt()
        ) ?: throw AppleResolutionException("NO_ISRC", "Could not resolve ISRC for track")

        val appleUrl = appleUrlCache[isrc] ?: run {
            val token = AppleMusicCanvasProvider.borrowToken()
                ?: throw AppleResolutionException("NO_TOKEN", "Could not borrow Apple Music token")

            val (_, songItem) = AppleMusicCanvasProvider.fetchByIsrc(isrc, token, AppleMusicCanvasProvider.CanvasAspectPreference.SQUARE)
            
            val url = songItem?.optJSONObject("attributes")?.optString("url")
                ?: run {
                    // Try search if ISRC lookup didn't give a song item with URL
                    val searchResult = AppleMusicCanvasProvider.fetchBySearch(
                        query.song, query.artist, query.durationMs?.div(1000)?.toInt(), token, AppleMusicCanvasProvider.CanvasAspectPreference.SQUARE
                    )
                    searchResult.item?.optJSONObject("attributes")?.optString("url")
                } ?: throw AppleResolutionException("NO_APPLE_URL", "Could not find Apple Music URL for ISRC $isrc")
            
            appleUrlCache[isrc] = url
            url
        }

        Timber.tag(TAG).d("[APPLE]\nResolved track:\n$appleUrl")

        val codecsToTry = listOf(
            AppleAudioQuality.ATMOS,
            AppleAudioQuality.AC3,
            AppleAudioQuality.AAC,
            AppleAudioQuality.AAC_WEB
        )

        var currentQuality = query.quality
        var directUrl: String? = null
        val apiBases = resolverEndpointBases(query.resolverEndpoints)

        // If current quality is in fallback chain, start from it and go down.
        // If not (e.g. AAC_HE), just try it once.
        val fallbackChain = if (currentQuality in codecsToTry) {
            codecsToTry.subList(codecsToTry.indexOf(currentQuality), codecsToTry.size)
        } else {
            listOf(currentQuality)
        }

        outer@ for (quality in fallbackChain) {
            Timber.tag(TAG).d("[APPLE STREAM]\nCodec:\n${quality.toCodec()}")
            for (base in apiBases) {
                try {
                    directUrl = getStreamUrl(base, appleUrl, quality)
                    currentQuality = quality
                    break@outer
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "[APPLE STREAM] API failed: $base codec=${quality.toCodec()}")
                }
            }
            Timber.tag(TAG).w("[APPLE STREAM]\nFallback:\n${currentQuality.toCodec()} -> ${quality.toCodec()}")
        }

        if (directUrl == null) {
            throw AppleResolutionException("STREAM_URL_FAILED", "Failed to get stream URL after fallbacks")
        }

        val bitrate = when {
            currentQuality == AppleAudioQuality.ATMOS -> 768_000
            currentQuality == AppleAudioQuality.AC3 -> 384_000
            currentQuality.name.contains("HE", ignoreCase = true) -> 64_000
            else -> 256_000
        }

        Timber.tag(TAG).d("[APPLE STREAM]\nStarting direct stream")

        Resolved(
            mediaUri = directUrl,
            trackId = isrc,
            title = query.song,
            artist = query.artist,
            mimeType = "audio/mp4",
            codecs = currentQuality.toCodec(),
            bitrate = bitrate,
            expiresAtMs = System.currentTimeMillis() + 2 * 60 * 60 * 1000L // Assume 2 hours
        )
    }

    private fun getStreamUrl(base: String, appleUrl: String, quality: AppleAudioQuality): String {
        val url = buildStreamUrl(base, appleUrl, quality.toCodec())

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw AppleResolutionException("STREAM_API_FAILED", "Non-200 response: ${response.code} from $base")
            }
            // The API returns the stream directly, so the "directUrl" is actually the API URL itself
            return url
        }
    }
}
