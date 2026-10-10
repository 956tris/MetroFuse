/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils.discord

import com.metrolist.music.constants.DiscordAnimatedCanvasQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object DiscordCanvasRemoteRenderer {
    private const val TAG = "DiscordCanvasRemote"
    private const val BASE_URL = "https://metrofuse-rpc-cdn-converter.hf.space"
    private const val CALL_URL = "$BASE_URL/gradio_api/call/canvas"
    private const val CLOUDINARY_CLOUD_NAME = "droeppomw"
    // Unsigned upload preset (Cloudinary > Settings > Upload). Must match its exact name.
    private const val CLOUDINARY_UPLOAD_PRESET = "Local g"
    private val jsonMediaType = "application/json".toMediaType()
    private val hlsResolutionRegex = Regex("""RESOLUTION=(\d+)x(\d+)""")
    private val hlsBandwidthRegex = Regex("""(?:AVERAGE-BANDWIDTH|BANDWIDTH)=(\d+)""")

    private val renderedUrlCache = ConcurrentHashMap<String, String>()
    private val renderErrorCache = ConcurrentHashMap<String, String>()
    private val fastInputUrlCache = ConcurrentHashMap<String, String>()
    private val inFlightRenders = ConcurrentHashMap<String, Deferred<String?>>()
    private val rendererScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client =
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(150, TimeUnit.SECONDS)
            .build()

    fun cachedUrl(
        canvasUrl: String,
        quality: DiscordAnimatedCanvasQuality,
    ): String? = renderedUrlCache[cacheKey(canvasUrl, quality)]

    fun lastError(
        canvasUrl: String,
        quality: DiscordAnimatedCanvasQuality,
    ): String? = renderErrorCache[cacheKey(canvasUrl, quality)]

    fun renderAsync(
        canvasUrl: String,
        quality: DiscordAnimatedCanvasQuality,
        localConversion: Boolean = false,
    ): Deferred<String?> {
        val key = cacheKey(canvasUrl, quality)
        renderedUrlCache[key]?.let { return CompletableDeferred(it) }
        return inFlightRenders.getOrPut(key) {
            rendererScope.async {
                try {
                    renderNow(canvasUrl, quality, localConversion)
                } finally {
                    inFlightRenders.remove(key)
                }
            }
        }
    }

    suspend fun render(
        canvasUrl: String,
        quality: DiscordAnimatedCanvasQuality,
        localConversion: Boolean = false,
    ): String? = renderAsync(canvasUrl, quality, localConversion).await()

    private fun renderNow(
        canvasUrl: String,
        quality: DiscordAnimatedCanvasQuality,
        localConversion: Boolean = false,
    ): String? {
        val key = cacheKey(canvasUrl, quality)
        renderedUrlCache[key]?.let { return it }
        renderErrorCache.remove(key)

        val targetSize = quality.sizePx
        val inputUrl = resolveFastAppleCanvasInputUrl(canvasUrl, targetSize)

        // Fast path: if this exact conversion already exists on Cloudinary, use it
        // directly and skip the Space entirely (no cold start, no server work).
        cloudinaryCachedUrl(inputUrl, targetSize, quality.fps, quality.seconds)?.let { hit ->
            renderedUrlCache[key] = hit
            return hit
        }

        // Optional (default off): convert on-device, upload straight to Cloudinary.
        // Falls through to the Space if it fails.
        if (localConversion) {
            renderLocally(inputUrl, targetSize, quality)?.let { local ->
                renderedUrlCache[key] = local
                return local
            }
        }

        return runCatching {
            when (val result = callCanvasApi(inputUrl, targetSize, quality)) {
                is CanvasApiResult.Success ->
                    result.url
                        .takeIf { it.startsWith("https://", ignoreCase = true) }
                        ?.takeIf { it.endsWith(".webp", ignoreCase = true) }
                        ?.also { renderedUrlCache[key] = it }
                        ?: run {
                            renderErrorCache[key] = "HF response did not include a HTTPS .webp URL"
                            null
                        }

                is CanvasApiResult.Failure -> {
                    val message = "HF HTTP ${result.code}: ${result.body.take(120)}"
                    renderErrorCache[key] = message
                    Timber.tag(TAG).w("Canvas render failed: $message")
                    if (shouldRetryLowerQuality(result.code, result.body)) {
                        quality.fallback?.let { fallbackQuality ->
                            return@runCatching renderNow(canvasUrl, fallbackQuality, localConversion)
                                ?.also { renderedUrlCache[key] = it }
                        }
                    }
                    null
                }
            }
        }.onFailure { error ->
            renderErrorCache[key] = "${error.javaClass.simpleName}: ${error.message.orEmpty()}".take(140)
            Timber.tag(TAG).w(error, "Canvas render request failed")
        }.getOrNull()
    }

    /** Must stay identical to the Space's cache key in app.py. */
    private fun canvasCacheHash(
        inputUrl: String,
        size: Int,
        fps: Int,
        seconds: Int,
    ): String =
        MessageDigest.getInstance("SHA-256")
            .digest("$inputUrl|$size|$fps|$seconds|webp".toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { "%02x".format(it) }
            .take(24)

    private fun renderLocally(
        inputUrl: String,
        size: Int,
        quality: DiscordAnimatedCanvasQuality,
    ): String? =
        runCatching {
            DiscordCanvasLocalConverter.convertToWebpAndUpload(
                sourceUrl = inputUrl,
                sizePx = size,
                fps = quality.fps,
                seconds = quality.seconds,
                publicId = "metrofuse_canvas/${canvasCacheHash(inputUrl, size, quality.fps, quality.seconds)}",
                cloudName = CLOUDINARY_CLOUD_NAME,
                uploadPreset = CLOUDINARY_UPLOAD_PRESET,
                client = client,
                headers =
                    mapOf(
                        "Origin" to "https://music.apple.com",
                        "Referer" to "https://music.apple.com/",
                    ),
            )
        }.onFailure { error ->
            Timber.tag(TAG).w(error, "Local canvas render failed")
        }.getOrNull()
            ?.takeIf { it.endsWith(".webp", ignoreCase = true) }

    private val headClient by lazy {
        client.newBuilder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Mirrors the server's cache key: sha256("url|size|fps|seconds|format")[:24],
     * stored at metrofuse_canvas/<key>. Must stay in sync with the Space's app.py.
     * Returns the Cloudinary URL if it already exists, else null.
     */
    private fun cloudinaryCachedUrl(
        inputUrl: String,
        size: Int,
        fps: Int,
        seconds: Int,
    ): String? {
        val hash = canvasCacheHash(inputUrl, size, fps, seconds)
        val url =
            "https://res.cloudinary.com/$CLOUDINARY_CLOUD_NAME/image/upload/metrofuse_canvas/$hash.webp"
        return runCatching {
            val request =
                Request.Builder()
                    .url(url)
                    .head()
                    .header("User-Agent", "MetroFuse")
                    .build()
            headClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) url else null
            }
        }.getOrNull()
    }

    private sealed class CanvasApiResult {
        data class Success(val url: String) : CanvasApiResult()
        data class Failure(val code: Int, val body: String) : CanvasApiResult()
    }

    /**
     * Calls the Gradio Space API (/canvas): POST starts the job and returns an
     * event_id, then a GET on that id streams server-sent events until
     * "complete" (data = [{"url": ..., "cached": ...}]) or "error".
     * Argument order must stay: url, size, fps, seconds, format.
     */
    private fun callCanvasApi(
        inputUrl: String,
        size: Int,
        quality: DiscordAnimatedCanvasQuality,
    ): CanvasApiResult {
        val args =
            JSONArray()
                .put(inputUrl)
                .put(size)
                .put(quality.fps)
                .put(quality.seconds)
                .put("webp")
        val startRequest =
            Request.Builder()
                .url(CALL_URL)
                .header("Accept", "application/json")
                .header("User-Agent", "MetroFuse")
                .post(JSONObject().put("data", args).toString().toRequestBody(jsonMediaType))
                .build()

        val eventId =
            client.newCall(startRequest).execute().use { response ->
                val body = response.body.string()
                if (!response.isSuccessful) {
                    return CanvasApiResult.Failure(response.code, body.take(300))
                }
                JSONObject(body).optString("event_id")
            }
        if (eventId.isBlank()) {
            return CanvasApiResult.Failure(0, "HF response did not include an event_id")
        }

        val resultRequest =
            Request.Builder()
                .url("$CALL_URL/$eventId")
                .header("Accept", "text/event-stream")
                .header("User-Agent", "MetroFuse")
                .build()
        client.newCall(resultRequest).execute().use { response ->
            if (!response.isSuccessful) {
                return CanvasApiResult.Failure(response.code, response.body.string().take(300))
            }
            val source = response.body.source()
            var event = ""
            while (true) {
                val line = source.readUtf8Line() ?: break
                when {
                    line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                    line.startsWith("data:") -> {
                        val payload = line.removePrefix("data:").trim()
                        when (event) {
                            "complete" -> {
                                val url =
                                    JSONArray(payload).optJSONObject(0)?.optString("url").orEmpty()
                                return CanvasApiResult.Success(url)
                            }
                            "error" -> return CanvasApiResult.Failure(500, payload.take(300))
                        }
                    }
                }
            }
        }
        return CanvasApiResult.Failure(0, "HF stream ended without a result")
    }

    private fun shouldRetryLowerQuality(
        responseCode: Int,
        body: String,
    ): Boolean =
        responseCode in setOf(400, 413, 422, 500, 502, 503, 504) ||
                body.contains("too large", ignoreCase = true) ||
                body.contains("less than or equal", ignoreCase = true) ||
                body.contains("validation", ignoreCase = true) ||
                body.contains("memory", ignoreCase = true)

    private data class HlsVariant(
        val url: String,
        val width: Int,
        val height: Int,
        val bandwidth: Long,
    ) {
        val shortestEdge: Int = minOf(width, height)
    }

    private fun resolveFastAppleCanvasInputUrl(
        canvasUrl: String,
        targetSize: Int,
    ): String {
        if (!canvasUrl.endsWith(".m3u8", ignoreCase = true)) return canvasUrl

        val fastInputKey = "$targetSize|$canvasUrl"
        fastInputUrlCache[fastInputKey]?.let { return it }

        val resolvedUrl = runCatching {
            val request =
                Request.Builder()
                    .url(canvasUrl)
                    .header("Accept", "application/vnd.apple.mpegurl,application/x-mpegURL,*/*")
                    .header("User-Agent", "MetroFuse")
                    .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use canvasUrl
                val manifest = response.body.string()
                if (!manifest.contains("#EXT-X-STREAM-INF")) return@use canvasUrl
                selectAppleCanvasHlsVariant(canvasUrl, manifest, targetSize) ?: canvasUrl
            }
        }.onFailure { error ->
            Timber.tag(TAG).d(error, "Could not inspect Apple canvas HLS variants")
        }.getOrNull() ?: return canvasUrl

        if (fastInputUrlCache.size > 512) {
            fastInputUrlCache.clear()
        }
        fastInputUrlCache[fastInputKey] = resolvedUrl
        return resolvedUrl
    }

    private fun selectAppleCanvasHlsVariant(
        masterUrl: String,
        manifest: String,
        targetSize: Int,
    ): String? {
        val masterHttpUrl = runCatching { masterUrl.toHttpUrl() }.getOrNull() ?: return null
        val lines = manifest.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
        val variants = buildList {
            lines.forEachIndexed { index, line ->
                if (!line.startsWith("#EXT-X-STREAM-INF")) return@forEachIndexed
                val mediaUrl = lines.drop(index + 1).firstOrNull { !it.startsWith("#") } ?: return@forEachIndexed
                val resolution = hlsResolutionRegex.find(line) ?: return@forEachIndexed
                val width = resolution.groupValues.getOrNull(1)?.toIntOrNull() ?: return@forEachIndexed
                val height = resolution.groupValues.getOrNull(2)?.toIntOrNull() ?: return@forEachIndexed
                val bandwidth = hlsBandwidthRegex.find(line)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: Long.MAX_VALUE
                val resolvedUrl = masterHttpUrl.resolve(mediaUrl)?.toString() ?: return@forEachIndexed
                add(HlsVariant(resolvedUrl, width, height, bandwidth))
            }
        }
        if (variants.isEmpty()) return null

        return variants
            .filter { it.shortestEdge >= targetSize }
            .minWithOrNull(compareBy<HlsVariant> { it.shortestEdge }.thenBy { it.bandwidth })
            ?.url
            ?: variants.maxWithOrNull(compareBy<HlsVariant> { it.shortestEdge }.thenByDescending { it.bandwidth })?.url
    }

    private fun cacheKey(
        canvasUrl: String,
        quality: DiscordAnimatedCanvasQuality,
    ): String = "${quality.name.lowercase()}|$canvasUrl"
}