/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.lyrics

import com.metrolist.music.betterlyrics.TTMLParser
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * Client for the Binimum lyrics API (https://lyrics-api.binimum.org).
 *
 * The API can be queried two ways:
 *  - By ISRC: `?isrc=<isrc>` — exact match against the local library.
 *  - By text: `?track=&artist=&duration=&album=` — used whenever an ISRC isn't
 *    available, which is the normal case here since [com.metrolist.music.models.MediaMetadata]
 *    doesn't currently carry an ISRC.
 *
 * Either query returns a JSON envelope whose `results[].lyricsUrl` points to a
 * separate TTML file that has to be fetched and parsed on its own.
 */
object BiniLyrics {
    private const val TAG = "BiniLyrics"
    private const val BASE_URL = "https://lyrics-api.binimum.org"

    @Serializable
    private data class SearchResponse(
        val total: Int = 0,
        val source: String? = null,
        val results: List<TrackResult> = emptyList(),
    )

    @Serializable
    private data class TrackResult(
        val id: String? = null,
        @SerialName("track_name") val trackName: String? = null,
        @SerialName("artist_name") val artistName: String? = null,
        @SerialName("album_name") val albumName: String? = null,
        val duration: Int? = null,
        val isrc: String? = null,
        @SerialName("timing_type") val timingType: String? = null,
        val lyricsUrl: String? = null,
    )

    private val client by lazy {
        HttpClient(CIO) {
            install(ContentNegotiation) {
                json(
                    Json {
                        isLenient = true
                        ignoreUnknownKeys = true
                    },
                )
            }

            install(HttpTimeout) {
                requestTimeoutMillis = 15000
                connectTimeoutMillis = 10000
                socketTimeoutMillis = 15000
            }

            defaultRequest {
                url(BASE_URL)
                headers {
                    append("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    append("Accept", "application/json")
                }
            }

            expectSuccess = false
        }
    }

    private suspend fun search(
        artist: String,
        title: String,
        duration: Int,
        album: String?,
        isrc: String?,
    ): TrackResult? = runCatching {
        Timber.tag(TAG)
            .d("Searching: title='$title' artist='$artist' duration=$duration album=$album isrc=$isrc")

        val response = client.get(BASE_URL) {
            if (!isrc.isNullOrBlank()) {
                parameter("isrc", isrc)
            } else {
                parameter("track", title)
                parameter("artist", artist)
                if (duration > 0) {
                    parameter("duration", duration)
                }
                if (!album.isNullOrBlank()) {
                    parameter("album", album)
                }
            }
        }

        if (response.status == HttpStatusCode.OK) {
            val result = response.body<SearchResponse>().results.firstOrNull()
            if (result?.lyricsUrl.isNullOrBlank()) {
                Timber.tag(TAG).d("No usable result in response")
                null
            } else {
                result
            }
        } else {
            Timber.tag(TAG).w("Search returned status: ${response.status}")
            null
        }
    }.getOrElse { e ->
        Timber.tag(TAG).e(e, "Exception during search")
        null
    }

    private suspend fun fetchTTML(url: String): String? = runCatching {
        val response = client.get(url)
        if (response.status == HttpStatusCode.OK) {
            response.body<String>().trim().takeIf { it.isNotEmpty() }
        } else {
            Timber.tag(TAG).w("TTML fetch returned status: ${response.status}")
            null
        }
    }.getOrElse { e ->
        Timber.tag(TAG).e(e, "Exception fetching TTML file")
        null
    }

    /**
     * @param isrc Optional ISRC for an exact lookup. When null/blank, falls back to the
     * text-based search using [title]/[artist]/[duration]/[album].
     */
    suspend fun getLyrics(
        title: String,
        artist: String,
        duration: Int,
        album: String? = null,
        isrc: String? = null,
    ) = runCatching {
        val result = search(artist, title, duration, album, isrc)
            ?: throw IllegalStateException("No lyrics found")

        val lyricsUrl = result.lyricsUrl?.trim().takeUnless { it.isNullOrBlank() }
            ?: throw IllegalStateException("Result had no lyricsUrl")

        val ttml = fetchTTML(lyricsUrl)
            ?: throw IllegalStateException("Failed to fetch TTML from $lyricsUrl")

        val parsedLines = TTMLParser.parseTTML(ttml)
        if (parsedLines.isEmpty()) {
            throw IllegalStateException("Failed to parse lyrics")
        }

        TTMLParser.toLRC(parsedLines)
    }
}
