package com.metrolist.paxsenix

import android.content.Context
import com.metrolist.paxsenix.models.SearchResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import timber.log.Timber

object Paxsenix {
    @Volatile
    private var client: HttpClient? = null
    private var appVersion: String = "Unknown"

    fun init(context: Context) {
        if (client != null) return // Already initialized

        synchronized(this) {
            if (client != null) return

            appVersion = try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
                    ?: "Unknown"
            } catch (e: Exception) {
                Timber.e(e, "Failed to get app version")
                "Unknown"
            }

            Timber.d("Initializing Paxsenix with version: $appVersion")

            // One shared OkHttp engine for Apple + QQ: connection pooling and
            // keep-alive are on by default, so lyric lookups reuse connections.
            val newClient = HttpClient(OkHttp) {
                install(HttpTimeout) {
                    requestTimeoutMillis = 15000
                    connectTimeoutMillis = 10000
                }
                install(ContentNegotiation) {
                    json(
                        Json {
                            isLenient = true
                            ignoreUnknownKeys = true
                        },
                    )
                }

                defaultRequest {
                    url("https://lyrics.paxsenix.org")
                    header("User-Agent", "Metrolist/$appVersion")
                }

                expectSuccess = true
            }

            client = newClient
            AppleMusicLyrics.init(newClient)
            QQMusicLyrics.init(newClient)

            Timber.d("Paxsenix HTTP client initialized")
        }
    }

    private val titleCleanupPatterns = listOf(
        Regex("""\s*\(.*?(official|video|audio|lyrics|lyric|visualizer|hd|hq|4k|remaster|remix|live|acoustic|version|edit|extended|radio|clean|explicit).*?\)""", RegexOption.IGNORE_CASE),
        Regex("""\s*\[.*?(official|video|audio|lyrics|lyric|visualizer|hd|hq|4k|remaster|remix|live|acoustic|version|edit|extended|radio|clean|explicit).*?\]""", RegexOption.IGNORE_CASE),
        Regex("""\s*【.*?】"""),
        Regex("""\s*\|.*$"""),
        Regex("""\s*-\s*(official|video|audio|lyrics|lyric|visualizer).*$""", RegexOption.IGNORE_CASE),
        Regex("""\s*\(feat\..*?\)""", RegexOption.IGNORE_CASE),
        Regex("""\s*\(ft\..*?\)""", RegexOption.IGNORE_CASE),
        Regex("""\s*feat\..*$""", RegexOption.IGNORE_CASE),
        Regex("""\s*ft\..*$""", RegexOption.IGNORE_CASE),
        Regex("""\s*\([^)]*\d{4}[^)]*\)""", RegexOption.IGNORE_CASE),
    )

    private val artistSeparators = listOf(" & ", " and ", ", ", " x ", " X ", " feat. ", " feat ", " ft. ", " ft ", " featuring ", " with ")

    private fun cleanTitle(title: String): String {
        var cleaned = title.trim()
        for (pattern in titleCleanupPatterns) {
            cleaned = cleaned.replace(pattern, "")
        }
        return cleaned.trim()
    }

    private fun cleanArtist(artist: String): String {
        var cleaned = artist.trim()
        for (separator in artistSeparators) {
            if (cleaned.contains(separator, ignoreCase = true)) {
                cleaned = cleaned.split(separator, ignoreCase = true, limit = 2)[0]
                break
            }
        }
        return cleaned.trim()
    }

    /** Standalone Apple Music-only lookup, for use as an independent lyrics provider. */
    suspend fun getAppleMusicLyrics(
        title: String,
        artist: String,
        duration: Int,
        album: String? = null,
    ): Result<String> = runCatching {
        val cleanedTitle = cleanTitle(title)
        val cleanedArtist = cleanArtist(artist)

        val searchQueries = buildList {
            add("$cleanedTitle $cleanedArtist")
            add(cleanedTitle)
            if (!album.isNullOrBlank()) {
                add("$cleanedTitle $cleanedArtist $album")
            }
        }

        var allResults: List<Pair<SearchResult, Double>> = emptyList()
        for (query in searchQueries) {
            if (allResults.isEmpty()) {
                val searchResults = AppleMusicLyrics.search(query)
                if (searchResults.isNotEmpty()) {
                    allResults = AppleMusicLyrics.scoreAndFilterResults(searchResults, title, artist, duration, ::cleanArtist)
                }
            }
        }

        if (allResults.isEmpty()) {
            throw IllegalStateException("No tracks found on Apple Music for '$title' by '$artist'")
        }

        for ((result, _) in allResults.take(10)) {
            val lrc = AppleMusicLyrics.fetchLyricsForTrack(result.id).getOrNull() ?: continue
            if (lrc.isNotEmpty()) return@runCatching lrc
        }

        throw IllegalStateException("No Apple Music lyrics content found for '$title'")
    }

    /** Disk-cache dir for QQ mid/lyrics/miss entries (see QQMusicLyrics). */
    fun initQQCacheDir(dir: java.io.File) {
        QQMusicLyrics.initCacheDir(dir)
    }

    /** Standalone QQ Music-only lookup, for use as an independent lyrics provider. */
    suspend fun getQQMusicLyrics(
        title: String,
        artist: String,
        duration: Int,
        album: String? = null,
    ): Result<String> {
        // Pass raw strings: QQ does its own tag-aware normalisation
        // (it keeps "(Live)"/"feat." as a signal when the track has them).
        return QQMusicLyrics.fetchLyrics(title, artist, album, duration)
    }

}
