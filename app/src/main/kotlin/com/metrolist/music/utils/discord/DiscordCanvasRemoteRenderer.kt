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
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object DiscordCanvasRemoteRenderer {
    private const val TAG = "DiscordCanvasRemote"
    private const val BASE_URL = "https://tris4520-metrofuse.hf.space"
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
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
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
    ): Deferred<String?> {
        val key = cacheKey(canvasUrl, quality)
        renderedUrlCache[key]?.let { return CompletableDeferred(it) }
        return inFlightRenders.getOrPut(key) {
            rendererScope.async {
                try {
                    renderNow(canvasUrl, quality)
                } finally {
                    inFlightRenders.remove(key)
                }
            }
        }
    }

    suspend fun render(
        canvasUrl: String,
        quality: DiscordAnimatedCanvasQuality,
    ): String? = renderAsync(canvasUrl, quality).await()

    private fun renderNow(
        canvasUrl: String,
        quality: DiscordAnimatedCanvasQuality,
    ): String? {
        val key = cacheKey(canvasUrl, quality)
        renderedUrlCache[key]?.let { return it }
        renderErrorCache.remove(key)

        val targetSize = quality.sizePx
        val inputUrl = resolveFastAppleCanvasInputUrl(canvasUrl, targetSize)
        val renderUrl =
            "$BASE_URL/canvas".toHttpUrl()
                .newBuilder()
                .addQueryParameter("url", inputUrl)
                .addQueryParameter("size", targetSize.toString())
                .addQueryParameter("fps", quality.fps.toString())
                .addQueryParameter("seconds", quality.seconds.toString())
                .build()

        return runCatching {
            val request =
                Request.Builder()
                    .url(renderUrl)
                    .header("Accept", "application/json")
                    .header("User-Agent", "MetroFuse")
                    .build()
            client.newCall(request).execute().use { response ->
                val body = response.body.string()
                if (!response.isSuccessful) {
                    val message = "HF HTTP ${response.code}: ${body.take(120)}"
                    renderErrorCache[key] = message
                    Timber.tag(TAG).w("Canvas render failed: $message")
                    if (shouldRetryLowerQuality(response.code, body)) {
                        quality.fallback?.let { fallbackQuality ->
                            return@use renderNow(canvasUrl, fallbackQuality)
                                ?.also { renderedUrlCache[key] = it }
                        }
                    }
                    return@use null
                }
                JSONObject(body)
                    .optString("url")
                    .takeIf { it.startsWith("https://", ignoreCase = true) }
                    ?.takeIf { it.endsWith(".webp", ignoreCase = true) }
                    ?.also { renderedUrlCache[key] = it }
                    ?: run {
                        renderErrorCache[key] = "HF response did not include a HTTPS .webp URL"
                        null
                    }
            }
        }.onFailure { error ->
            renderErrorCache[key] = "${error.javaClass.simpleName}: ${error.message.orEmpty()}".take(140)
            Timber.tag(TAG).w(error, "Canvas render request failed")
        }.getOrNull()
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
