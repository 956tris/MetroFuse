package com.metrolist.music.utils.discord

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber

object DiscordCanvasServerConverter {
    private const val TAG = "DiscordCanvasServer"
    private const val POLL_ATTEMPTS = 30
    private const val POLL_DELAY_MS = 1_000L
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun prepare(
        canvasUrl: String,
        resolverUrl: String,
        client: OkHttpClient,
    ): String? = withContext(Dispatchers.IO) {
        val prepareUrl = resolverUrl
            .toHttpUrlOrNull()
            ?.newBuilder()
            ?.encodedPath("/apple_canvas/prepare")
            ?.query(null)
            ?.fragment(null)
            ?.build()
            ?.toString()
            ?: return@withContext null

        val result = runCatching {
            val body = JSONObject()
                .put("url", canvasUrl)
                .toString()
                .toRequestBody(jsonMediaType)
            val request = Request.Builder()
                .url(prepareUrl)
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.tag(TAG).w("Apple canvas server prepare failed: HTTP ${response.code}")
                    return@use null
                }
                val json = JSONObject(response.body.string())
                ServerCanvasResult(
                    url = json.optString("url").takeIf { it.isNotBlank() },
                    ready = json.optBoolean("ready", false),
                )
            }
        }.onFailure { error ->
            Timber.tag(TAG).w(error, "Apple canvas server prepare request failed")
        }.getOrNull() ?: return@withContext null

        val publicUrl = result.url ?: return@withContext null
        if (result.ready || isCanvasReady(publicUrl, client)) {
            return@withContext publicUrl
        }

        repeat(POLL_ATTEMPTS) {
            delay(POLL_DELAY_MS)
            if (isCanvasReady(publicUrl, client)) {
                Timber.tag(TAG).d("Apple canvas server result is ready")
                return@withContext publicUrl
            }
        }

        null
    }

    private fun isCanvasReady(
        url: String,
        client: OkHttpClient,
    ): Boolean =
        runCatching {
            val request = Request.Builder()
                .url(url)
                .head()
                .header("Cache-Control", "no-cache")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use false
                val contentType = response.header("Content-Type").orEmpty()
                val contentLength = response.header("Content-Length")?.toLongOrNull()
                contentType.contains("image/webp", ignoreCase = true) ||
                    contentType.contains("image/gif", ignoreCase = true) ||
                    contentType.contains("application/octet-stream", ignoreCase = true) ||
                    (contentLength != null && contentLength > 0L)
            }
        }.getOrDefault(false)

    private data class ServerCanvasResult(
        val url: String?,
        val ready: Boolean,
    )
}
