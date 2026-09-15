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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object AppleAudioProvider {
    private const val TAG = "AppleAudioProvider"
    private const val STREAM_API_BASE = "https://yesitworkssomehow-funi-lyric-api.hf.space/stream"
    
    private val appleUrlCache = ConcurrentHashMap<String, String>()

    data class Query(
        val song: String,
        val artist: String,
        val album: String?,
        val isrc: String?,
        val durationMs: Long?,
        val quality: AppleAudioQuality = AppleAudioQuality.AAC_WEB,
    )

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
        
        // If current quality is in fallback chain, start from it and go down.
        // If not (e.g. AAC_HE), just try it once.
        val fallbackChain = if (currentQuality in codecsToTry) {
            codecsToTry.subList(codecsToTry.indexOf(currentQuality), codecsToTry.size)
        } else {
            listOf(currentQuality)
        }

        for (quality in fallbackChain) {
            Timber.tag(TAG).d("[APPLE STREAM]\nCodec:\n${quality.toCodec()}")
            try {
                directUrl = getStreamUrl(appleUrl, quality)
                break
            } catch (e: Exception) {
                Timber.tag(TAG).w("[APPLE STREAM]\nFallback:\n${currentQuality.toCodec()} -> ${quality.toCodec()}")
                currentQuality = quality
            }
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

    private fun getStreamUrl(appleUrl: String, quality: AppleAudioQuality): String {
        val url = STREAM_API_BASE.toHttpUrl().newBuilder()
            .addQueryParameter("url", appleUrl)
            .addQueryParameter("codec", quality.toCodec())
            .build()

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw AppleResolutionException("STREAM_API_FAILED", "Non-200 response: ${response.code}")
            }
            // The API returns the stream directly, so the "directUrl" is actually the API URL itself
            // Wait, if it returns the stream directly, then the mediaUri is the API URL.
            return url.toString()
        }
    }
}
