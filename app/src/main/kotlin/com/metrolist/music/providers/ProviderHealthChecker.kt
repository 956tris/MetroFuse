/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.providers

import com.metrolist.music.deezer.DeezerAudioProvider
import com.metrolist.music.soundcloud.SoundCloudAudioProvider
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

object ProviderHealthChecker {
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Mobile Safari/537.36"
    private const val TIDAL_PUBLIC_TOKEN = "49YxDN9a2aFV6RTG"
    private const val QOBUZ_HEALTH_QUERY = "yes and ariana grande"
    private const val QOBUZ_HEALTH_TRACK_ID = "256170850"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    private val TIDAL_RESOLVERS =
        listOf(
            TidalResolver("tidal_resolver_hifi_isback", "HiFi is Back v2.7", "https://hifi-isback.peridotclient.com"),
            TidalResolver("tidal_resolver_maus", "Maus QQDL v2.6", "https://maus.qqdl.site"),
            TidalResolver("tidal_resolver_vogel", "Vogel QQDL v2.6", "https://vogel.qqdl.site"),
            TidalResolver("tidal_resolver_katze", "Katze QQDL v2.6", "https://katze.qqdl.site"),
            TidalResolver("tidal_resolver_hund", "Hund QQDL v2.6", "https://hund.qqdl.site"),
            TidalResolver("tidal_resolver_wolf", "Wolf QQDL v2.6", "https://wolf.qqdl.site"),
        )

    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()

    enum class Status {
        ONLINE,
        REACHABLE,
        OFFLINE,
    }

    data class Target(
        val id: String,
        val group: String,
        val name: String,
        val endpoint: String,
        val detail: String,
        val requestFactory: () -> Request?,
        val customCheck: ((Target, Long) -> Result)? = null,
    )

    data class Result(
        val target: Target,
        val status: Status,
        val latencyMs: Long?,
        val message: String,
    )

    private data class TidalResolver(
        val id: String,
        val name: String,
        val baseUrl: String,
    ) {
        val endpoint: String = baseUrl.trimEnd('/') + "/"
    }

    fun targets(deezerResolverUrl: String): List<Target> {
        val deezerResolver = normalizeDeezerResolverUrl(deezerResolverUrl)
        return listOf(
            getTarget(
                id = "youtube_music",
                group = "YouTube Music",
                name = "YouTube Music",
                endpoint = "https://music.youtube.com/",
                detail = "Home and playback metadata",
            ),
            getTarget(
                id = "soundcloud_web",
                group = "SoundCloud",
                name = "SoundCloud",
                endpoint = "https://soundcloud.com/",
                detail = "Public SoundCloud frontend",
            ),
            getTarget(
                id = "soundcloud_maid",
                group = "SoundCloud",
                name = "SoundCloud Maid",
                endpoint = "${SoundCloudAudioProvider.MAID_BASE_URL}/search?q=test&type=tracks",
                detail = "Primary SoundCloud frontend metadata backend",
            ),
            getTarget(
                id = "soundcloud_squid",
                group = "SoundCloud",
                name = "SoundCloud Squid",
                endpoint = "${SoundCloudAudioProvider.SQUID_BASE_URL}/api/soundcloud/get-client-id",
                detail = "Secondary SoundCloud client ID and stream helper",
            ),
            getTarget(
                id = "deezer_api",
                group = "Deezer",
                name = "Deezer API",
                endpoint = "https://api.deezer.com/infos",
                detail = "Search and homepage metadata",
            ),
            postJsonTarget(
                id = "deezer_resolver",
                group = "Deezer",
                name = "dzmedia resolver",
                endpoint = deezerResolver,
                detail = "Configured Deezer audio resolver",
                body = """{"formats":["MP3_128"],"ids":[]}""",
            ),
            getTarget(
                id = "tidal_api",
                group = "TIDAL",
                name = "TIDAL API",
                endpoint = "https://tidal.com/v1/search/tracks?query=test&countryCode=US&limit=1",
                detail = "Search and catalog metadata",
                headers = mapOf("x-tidal-token" to TIDAL_PUBLIC_TOKEN),
            ),
            *TIDAL_RESOLVERS
                .map { resolver ->
                    getTarget(
                        id = resolver.id,
                        group = "TIDAL",
                        name = resolver.name,
                        endpoint = resolver.endpoint,
                        detail = "Lossless stream resolver reachability",
                    )
                }
                .toTypedArray(),
            qobuzSearchAndStreamTarget(
                id = "qobuz_trypt",
                name = "TrypT",
                baseUrl = "https://trypt-hifi-dl-456461932686.us-west1.run.app",
                requiresCountry = true,
            ),
            qobuzJumoTarget(
                id = "qobuz_jumo",
                name = "JUMO",
                baseUrl = "https://jumo-dl.pages.dev",
            ),
            qobuzSearchAndStreamTarget(
                id = "qobuz_monochrome",
                name = "Monochrome v1.0",
                baseUrl = "https://qdl-api.monochrome.tf",
                requiresCountry = true,
            ),
            qobuzSearchAndStreamTarget(
                id = "qobuz_scavenger",
                name = "Scavenger v1.0",
                baseUrl = "https://mono.scavengerfurs.net",
                requiresCountry = true,
            ),
            qobuzSearchAndStreamTarget(
                id = "qobuz_kenny",
                name = "Kenny",
                baseUrl = "https://qobuz.kennyy.com.br",
                requiresCountry = false,
            ),
            qobuzSearchAndStreamTarget(
                id = "qobuz_squid",
                name = "Squid",
                baseUrl = "https://qobuz.squid.wtf",
                requiresCountry = true,
            ),
        )
    }

    suspend fun checkAll(targets: List<Target>): List<Result> =
        coroutineScope {
            targets.map { target ->
                async(Dispatchers.IO) {
                    check(target)
                }
            }.awaitAll()
        }

    suspend fun check(target: Target): Result =
        withContext(Dispatchers.IO) {
            target.customCheck?.let { customCheck ->
                val startedAt = System.nanoTime()
                return@withContext runCatching {
                    customCheck(target, startedAt)
                }.getOrElse { error ->
                    Result(
                        target = target,
                        status = Status.OFFLINE,
                        latencyMs = null,
                        message = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName,
                    )
                }
            }
            val request = target.requestFactory()
                ?: return@withContext Result(
                    target = target,
                    status = Status.OFFLINE,
                    latencyMs = null,
                    message = "Invalid URL",
                )
            val startedAt = System.nanoTime()
            runCatching {
                client.newCall(request).execute().use { response ->
                    val latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
                    Result(
                        target = target,
                        status = response.code.toHealthStatus(),
                        latencyMs = latencyMs,
                        message = response.code.toHealthMessage(),
                    )
                }
            }.getOrElse { error ->
                Result(
                    target = target,
                    status = Status.OFFLINE,
                    latencyMs = null,
                    message = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName,
                )
            }
        }

    private fun qobuzSearchAndStreamTarget(
        id: String,
        name: String,
        baseUrl: String,
        requiresCountry: Boolean,
    ): Target {
        val headers =
            if (requiresCountry) {
                mapOf("Token-Country" to "US")
            } else {
                emptyMap()
            }
        val endpoint =
            "$baseUrl/api/get-music".toHttpUrlOrNull()
                ?.newBuilder()
                ?.addQueryParameter("q", QOBUZ_HEALTH_QUERY)
                ?.addQueryParameter("offset", "0")
                ?.build()
                ?.toString()
                ?: baseUrl
        return Target(
            id = id,
            group = "Qobuz",
            name = name,
            endpoint = endpoint,
            detail = "Qobuz search and stream backend",
            requestFactory = { qobuzGetRequest(endpoint, baseUrl, headers) },
            customCheck = { target, startedAt ->
                checkQobuzSearchAndStream(
                    target = target,
                    startedAt = startedAt,
                    baseUrl = baseUrl,
                    headers = headers,
                )
            },
        )
    }

    private fun qobuzJumoTarget(
        id: String,
        name: String,
        baseUrl: String,
    ): Target {
        val endpoint =
            "$baseUrl/fetch".toHttpUrlOrNull()
                ?.newBuilder()
                ?.addQueryParameter("track_id", QOBUZ_HEALTH_TRACK_ID)
                ?.addQueryParameter("format_id", "5")
                ?.addQueryParameter("region", "US")
                ?.build()
                ?.toString()
                ?: baseUrl
        return Target(
            id = id,
            group = "Qobuz",
            name = name,
            endpoint = endpoint,
            detail = "Qobuz stream backend",
            requestFactory = { qobuzGetRequest(endpoint, baseUrl, emptyMap()) },
            customCheck = { target, startedAt ->
                checkQobuzDownload(
                    target = target,
                    startedAt = startedAt,
                    request = qobuzGetRequest(endpoint, baseUrl, emptyMap()),
                    streamName = name,
                )
            },
        )
    }

    private fun checkQobuzSearchAndStream(
        target: Target,
        startedAt: Long,
        baseUrl: String,
        headers: Map<String, String>,
    ): Result {
        val searchUrl =
            "$baseUrl/api/get-music".toHttpUrlOrNull()
                ?.newBuilder()
                ?.addQueryParameter("q", QOBUZ_HEALTH_QUERY)
                ?.addQueryParameter("offset", "0")
                ?.build()
                ?: return Result(target, Status.OFFLINE, null, "Invalid search URL")
        val searchRequest = qobuzGetRequest(searchUrl.toString(), baseUrl, headers)
            ?: return Result(target, Status.OFFLINE, null, "Invalid search request")

        val trackId =
            client.newCall(searchRequest).execute().use { response ->
                val payload = response.body.string()
                if (!response.isSuccessful) {
                    return Result(
                        target = target,
                        status = response.code.toQobuzProbeStatus(),
                        latencyMs = elapsedMs(startedAt),
                        message = "Search HTTP ${response.code}: ${payload.compactHealthBody()}",
                    )
                }

                val root =
                    runCatching { JSONObject(payload) }
                        .getOrElse {
                            return Result(
                                target = target,
                                status = Status.REACHABLE,
                                latencyMs = elapsedMs(startedAt),
                                message = "Search answered without JSON",
                            )
                        }
                if (!root.optBoolean("success", false)) {
                    return Result(
                        target = target,
                        status = Status.REACHABLE,
                        latencyMs = elapsedMs(startedAt),
                        message = "Search rejected: ${root.stringOrNull("error") ?: "unknown error"}",
                    )
                }

                root.optJSONObject("data")
                    ?.optJSONObject("tracks")
                    ?.optJSONArray("items")
                    ?.firstDownloadableQobuzTrackId()
                    ?: return Result(
                        target = target,
                        status = Status.REACHABLE,
                        latencyMs = elapsedMs(startedAt),
                        message = "Search OK, no downloadable test track",
                    )
            }

        val downloadUrl =
            "$baseUrl/api/download-music".toHttpUrlOrNull()
                ?.newBuilder()
                ?.addQueryParameter("track_id", trackId)
                ?.addQueryParameter("quality", "5")
                ?.build()
                ?: return Result(target, Status.REACHABLE, elapsedMs(startedAt), "Invalid stream URL")
        return checkQobuzDownload(
            target = target,
            startedAt = startedAt,
            request = qobuzGetRequest(downloadUrl.toString(), baseUrl, headers),
            streamName = target.name,
        )
    }

    private fun checkQobuzDownload(
        target: Target,
        startedAt: Long,
        request: Request?,
        streamName: String,
    ): Result {
        if (request == null) {
            return Result(target, Status.OFFLINE, null, "Invalid stream request")
        }

        return client.newCall(request).execute().use { response ->
            val payload = response.body.string()
            if (!response.isSuccessful) {
                return@use Result(
                    target = target,
                    status = response.code.toQobuzProbeStatus(),
                    latencyMs = elapsedMs(startedAt),
                    message = "Stream HTTP ${response.code}: ${payload.compactHealthBody()}",
                )
            }

            val root =
                runCatching { JSONObject(payload) }
                    .getOrElse {
                        return@use Result(
                            target = target,
                            status = Status.REACHABLE,
                            latencyMs = elapsedMs(startedAt),
                            message = "$streamName answered without stream JSON",
                        )
                    }
            if (!root.optBoolean("success", true)) {
                val error = root.stringOrNull("error") ?: root.stringOrNull("message") ?: "unknown error"
                return@use Result(
                    target = target,
                    status = Status.REACHABLE,
                    latencyMs = elapsedMs(startedAt),
                    message = "Stream blocked: $error",
                )
            }

            val streamUrl =
                root.optJSONObject("data")?.stringOrNull("url")
                    ?: root.stringOrNull("url")
                    ?: root.stringOrNull("directUrl")
            Result(
                target = target,
                status = if (streamUrl.isNullOrBlank()) Status.REACHABLE else Status.ONLINE,
                latencyMs = elapsedMs(startedAt),
                message = if (streamUrl.isNullOrBlank()) "Stream response missing URL" else "Search and stream OK",
            )
        }
    }

    private fun qobuzGetRequest(
        endpoint: String,
        baseUrl: String,
        headers: Map<String, String>,
    ): Request? =
        endpoint.toHttpUrlOrNull()?.let { url ->
            Request.Builder()
                .url(url)
                .get()
                .header("Accept", "application/json,text/plain,*/*")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Origin", baseUrl)
                .header("Referer", "$baseUrl/")
                .header("User-Agent", USER_AGENT)
                .apply {
                    headers.forEach { (name, value) -> header(name, value) }
                }
                .build()
        }

    private fun getTarget(
        id: String,
        group: String,
        name: String,
        endpoint: String,
        detail: String,
        headers: Map<String, String> = emptyMap(),
    ): Target =
        Target(
            id = id,
            group = group,
            name = name,
            endpoint = endpoint,
            detail = detail,
            requestFactory = {
                endpoint.toHttpUrlOrNull()?.let { url ->
                    Request.Builder()
                        .url(url)
                        .get()
                        .header("Accept", "application/json,text/html,*/*")
                        .header("User-Agent", USER_AGENT)
                        .apply {
                            headers.forEach { (name, value) -> header(name, value) }
                        }
                        .build()
                }
            },
        )

    private fun postJsonTarget(
        id: String,
        group: String,
        name: String,
        endpoint: String,
        detail: String,
        body: String,
    ): Target =
        Target(
            id = id,
            group = group,
            name = name,
            endpoint = endpoint,
            detail = detail,
            requestFactory = {
                endpoint.toHttpUrlOrNull()?.let { url ->
                    Request.Builder()
                        .url(url)
                        .post(body.toRequestBody(JSON_MEDIA_TYPE))
                        .header("Accept", "application/json")
                        .header("User-Agent", USER_AGENT)
                        .build()
                }
            },
        )

    private fun normalizeDeezerResolverUrl(value: String): String =
        runCatching { DeezerAudioProvider.normalizeResolverUrl(value).toString() }
            .getOrElse { DeezerAudioProvider.DEFAULT_RESOLVER_URL }

    private fun Int.toHealthStatus(): Status =
        when (this) {
            in 200..299 -> Status.ONLINE
            in 300..499 -> Status.REACHABLE
            else -> Status.OFFLINE
        }

    private fun Int.toHealthMessage(): String =
        when (this) {
            in 200..299 -> "HTTP $this"
            401, 403 -> "HTTP $this, auth required"
            404, 405 -> "HTTP $this, endpoint answered"
            429 -> "HTTP 429, rate limited"
            in 300..499 -> "HTTP $this, reachable"
            in 500..599 -> "HTTP $this, server error"
            else -> "HTTP $this"
        }

    private fun Int.toQobuzProbeStatus(): Status =
        when (this) {
            in 200..299 -> Status.ONLINE
            in 400..499 -> Status.REACHABLE
            else -> Status.OFFLINE
        }

    private fun elapsedMs(startedAt: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

    private fun String.compactHealthBody(): String =
        replace(Regex("\\s+"), " ")
            .trim()
            .take(120)
            .ifBlank { "empty body" }

    private fun JSONObject.stringOrNull(name: String): String? =
        optString(name)
            .takeIf { it.isNotBlank() && it != "null" }

    private fun org.json.JSONArray.firstDownloadableQobuzTrackId(): String? {
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            if (!item.optBoolean("downloadable", false) && !item.optBoolean("streamable", false)) continue
            item.stringOrNull("id")?.let { return it }
        }
        return null
    }

    fun qobuzTargetId(value: String): String =
        "qobuz_${value.lowercase(Locale.US)}"
}
