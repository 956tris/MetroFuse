/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.providers

import com.metrolist.music.deezer.DeezerAudioProvider
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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

    fun targets(
        deezerResolverUrl: String,
    ): List<Target> {
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
                id = "soundcloud_squid",
                group = "SoundCloud",
                name = "SoundCloud Squid",
                endpoint = "https://sc.squid.wtf/api/soundcloud/get-client-id",
                detail = "Client ID and stream helper",
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
            getTarget(
                id = "qobuz_trypt",
                group = "Qobuz",
                name = "TrypT",
                endpoint = "https://trypt-hifi-dl-456461932686.us-west1.run.app/api/get-music?q=test&offset=0",
                detail = "Qobuz search and stream backend",
                headers = mapOf("Token-Country" to "US"),
            ),
            getTarget(
                id = "qobuz_jumo",
                group = "Qobuz",
                name = "JUMO",
                endpoint = "https://jumo-dl.pages.dev/",
                detail = "Qobuz stream backend",
            ),
            getTarget(
                id = "qobuz_kenny",
                group = "Qobuz",
                name = "Kenny",
                endpoint = "https://qobuz.kennyy.com.br/api/get-music?q=test&offset=0",
                detail = "Qobuz search and stream backend",
            ),
            getTarget(
                id = "qobuz_squid",
                group = "Qobuz",
                name = "Squid",
                endpoint = "https://qobuz.squid.wtf/api/get-music?q=test&offset=0",
                detail = "Qobuz search and stream backend",
                headers = mapOf("Token-Country" to "US"),
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

    fun qobuzTargetId(value: String): String =
        "qobuz_${value.lowercase(Locale.US)}"
}
