package com.metrolist.music.apple

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.min

data class AppleCanvasArtwork(
    val title: String?,
    val artist: String?,
    val albumId: String?,
    val animated: String?,
)

object AppleMusicCanvasProvider {
    enum class CanvasAspectPreference {
        AUTO,
        TALL,
        SQUARE,
        RAW,
    }

    private const val APPLE_MUSIC_TOKEN =
        "eyJhbGciOiJFUzI1NiIsInR5cCI6IkpXVCIsImtpZCI6IldlYlBsYXlLaWQifQ" +
            ".eyJpc3MiOiJBTVBXZWJQbGF5IiwiaWF0IjoxNzc0NDU2MzgyLCJleHAiOjE3ODE3" +
            "MTM5ODIsInJvb3RfaHR0cHNfb3JpZ2luIjpbImFwcGxlLmNvbSJdfQ" +
            ".4n8qYF4qa18sL1E0G9A3qX35cD8wQ-IJcS9Bh8ZT8JV_yLBtVq46B-9-2ZS3EvWHuw3yK9BYFYAhAdTaDm38vQ"

    private const val AMP_BASE_URL = "https://amp-api.music.apple.com"
    private const val APPLE_MUSIC_WEB_BASE_URL = "https://music.apple.com"
    private const val POSITIVE_CACHE_TTL_MS = 1000L * 60 * 60 * 24
    private const val NEGATIVE_CACHE_TTL_MS = 1000L * 60 * 10
    private const val LOOKUP_TIMEOUT_MS = 6_500L
    private const val APPLE_MUSIC_WEB_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/147 Safari/537.36"
    private val WEB_MVOD_HLS_REGEX =
        Regex("""https://mvod\.itunes\.apple\.com/[^"'<>\\\s]+?\.m3u8""")

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()
    private val providerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = ConcurrentHashMap<String, CacheEntry>()

    private data class CacheEntry(
        val value: AppleCanvasArtwork?,
        val expiresAtMs: Long,
    )

    fun getCached(
        song: String,
        artist: String,
        album: String? = null,
        explicit: Boolean? = null,
        isrc: String? = null,
        storefront: String = "us",
        preferredAspect: CanvasAspectPreference = CanvasAspectPreference.SQUARE,
    ): AppleCanvasArtwork? {
        val key = cacheKey("song", isrc ?: song, artist, album.orEmpty(), explicit?.toString().orEmpty(), storefront, preferredAspect.name)
        return getCacheEntry(key)?.value
    }

    suspend fun getBySongArtist(
        song: String,
        artist: String,
        album: String? = null,
        explicit: Boolean? = null,
        isrc: String? = null,
        storefront: String = "us",
        preferredAspect: CanvasAspectPreference = CanvasAspectPreference.SQUARE,
    ): AppleCanvasArtwork? = withTimeoutOrNull(LOOKUP_TIMEOUT_MS) {
        val key = cacheKey("song", isrc ?: song, artist, album.orEmpty(), explicit?.toString().orEmpty(), storefront, preferredAspect.name)
        getCacheEntry(key)?.let { return@withTimeoutOrNull it.value }

        val result = coroutineScope {
            val byIsrc = if (!isrc.isNullOrBlank()) {
                async { fetchByIsrc(isrc, storefront, preferredAspect) }
            } else {
                null
            }
            val bySearch = async { searchAndFetchMotion(song, artist, album, explicit, storefront, preferredAspect) }
            byIsrc?.await() ?: bySearch.await()
        }

        cache[key] = CacheEntry(
            value = result,
            expiresAtMs = System.currentTimeMillis() +
                if (result?.animated.isNullOrBlank()) NEGATIVE_CACHE_TTL_MS else POSITIVE_CACHE_TTL_MS,
        )
        result
    }

    fun prefetchBySongArtist(
        song: String,
        artist: String,
        album: String? = null,
        explicit: Boolean? = null,
        isrc: String? = null,
        storefront: String = "us",
        preferredAspect: CanvasAspectPreference = CanvasAspectPreference.SQUARE,
    ) {
        val key = cacheKey("song", isrc ?: song, artist, album.orEmpty(), explicit?.toString().orEmpty(), storefront, preferredAspect.name)
        if (getCacheEntry(key) != null) return
        providerScope.launch {
            runCatching {
                getBySongArtist(song, artist, album, explicit, isrc, storefront, preferredAspect)
            }.onFailure { error ->
                if (error is CancellationException) throw error
                Timber.tag("AppleCanvas").d(error, "Canvas prefetch failed")
            }
        }
    }

    fun getCachedArtistMotion(
        artist: String,
        storefront: String = "us",
    ): AppleCanvasArtwork? {
        val key = cacheKey("artist", artist, storefront)
        return getCacheEntry(key)?.value
    }

    suspend fun getArtistMotionByName(
        artist: String,
        storefront: String = "us",
    ): AppleCanvasArtwork? = withTimeoutOrNull(LOOKUP_TIMEOUT_MS) {
        val key = cacheKey("artist", artist, storefront)
        getCacheEntry(key)?.let { return@withTimeoutOrNull it.value }

        val result = searchArtistMotion(artist, storefront)
        cache[key] = CacheEntry(
            value = result,
            expiresAtMs = System.currentTimeMillis() +
                if (result?.animated.isNullOrBlank()) NEGATIVE_CACHE_TTL_MS else POSITIVE_CACHE_TTL_MS,
        )
        result
    }

    fun prefetchArtistMotion(
        artist: String,
        storefront: String = "us",
    ) {
        val key = cacheKey("artist", artist, storefront)
        if (getCacheEntry(key) != null) return
        providerScope.launch {
            runCatching {
                getArtistMotionByName(artist, storefront)
            }.onFailure { error ->
                if (error is CancellationException) throw error
                Timber.tag("AppleCanvas").d(error, "Artist motion prefetch failed")
            }
        }
    }

    private suspend fun searchArtistMotion(
        artist: String,
        storefront: String,
    ): AppleCanvasArtwork? = runCatching {
        val url = "$AMP_BASE_URL/v1/catalog/$storefront/search".toHttpUrl().newBuilder()
            .addQueryParameter("term", artist)
            .addQueryParameter("types", "artists")
            .addQueryParameter("limit", "6")
            .addQueryParameter("extend", "editorialVideo")
            .build()

        val root = executeJson(appleRequest(url.toString())) ?: return@runCatching null
        val results = root["results"].obj()
            ?.get("artists").obj()
            ?.get("data").arr()
            ?: return@runCatching null
        val cleanArtist = artist.cleanForMatch()

        results.mapNotNull { item ->
            val obj = item.obj() ?: return@mapNotNull null
            val attributes = obj["attributes"].obj() ?: return@mapNotNull null
            val resultName = attributes["name"].str().orEmpty()
            if (!artist.matchesArtist(resultName)) return@mapNotNull null
            val cleanResult = resultName.cleanForMatch()
            val score =
                when {
                    cleanArtist == cleanResult -> 50
                    cleanArtist.contains(cleanResult) || cleanResult.contains(cleanArtist) -> 30
                    else -> 20
                }
            score to obj
        }.sortedByDescending { it.first }
            .forEach { (_, obj) ->
                val attributes = obj["attributes"].obj() ?: return@forEach
                val artistId = obj["id"].str()
                val webUrl = attributes["url"].str() ?: return@forEach
                val hlsUrl =
                    attributes["editorialVideo"].obj()?.let { extractArtistEditorialVideoUrl(it) }
                        ?: artistId?.let { fetchCatalogArtistMotionArtwork(it, storefront) }
                        ?: fetchWebPageArtistMotionArtwork(webUrl)
                        ?: return@forEach
                return@runCatching AppleCanvasArtwork(
                    title = attributes["name"].str(),
                    artist = attributes["name"].str(),
                    albumId = artistId,
                    animated = hlsUrl,
                )
            }

        null
    }.onFailure { error ->
        if (error is CancellationException) throw error
        Timber.tag("AppleCanvas").d(error, "Artist motion lookup failed")
    }.getOrNull()

    private suspend fun fetchByIsrc(
        isrc: String,
        storefront: String,
        preferredAspect: CanvasAspectPreference,
    ): AppleCanvasArtwork? = runCatching {
        val url = "$AMP_BASE_URL/v1/catalog/$storefront/songs".toHttpUrl().newBuilder()
            .addQueryParameter("filter[isrc]", isrc)
            .addQueryParameter("extend", "editorialVideo")
            .addQueryParameter("include", "albums")
            .addQueryParameter("extend[albums]", "editorialVideo")
            .build()

        val root = executeJson(appleRequest(url.toString())) ?: return@runCatching null
        val data = root["data"].arr() ?: return@runCatching null
        val included = root["included"].arr()

        data.forEachIndexed { index, item ->
            val obj = item.obj() ?: return@forEachIndexed
            val attributes = obj["attributes"].obj() ?: return@forEachIndexed

            attributes["editorialVideo"].obj()?.let { editorialVideo ->
                val hlsUrl = extractEditorialVideoUrl(editorialVideo, preferredAspect)
                if (!hlsUrl.isNullOrBlank()) {
                    return@runCatching AppleCanvasArtwork(
                        attributes["name"].str(),
                        attributes["artistName"].str(),
                        null,
                        hlsUrl,
                    )
                }
            }

            val albumId = obj["relationships"].obj()
                ?.get("albums").obj()
                ?.get("data").arr()
                ?.firstOrNull()
                ?.obj()
                ?.get("id").str()
                ?: attributes["collectionId"].str()

            if (albumId != null) {
                included?.firstOrNull {
                    it.obj()?.get("id").str() == albumId
                }?.obj()?.get("attributes").obj()
                    ?.get("editorialVideo").obj()
                    ?.let { editorialVideo ->
                        val hlsUrl = extractEditorialVideoUrl(editorialVideo, preferredAspect)
                        if (!hlsUrl.isNullOrBlank()) {
                            return@runCatching AppleCanvasArtwork(
                                attributes["name"].str(),
                                attributes["artistName"].str(),
                                albumId,
                                hlsUrl,
                            )
                        }
                    }

                if (index == 0) {
                    fetchMotionArtwork(
                        albumId = albumId,
                        storefront = storefront,
                        fallbackArtist = attributes["artistName"].str(),
                        titleOverride = attributes["name"].str(),
                        artistOverride = attributes["artistName"].str(),
                        webUrl = attributes["url"].str(),
                        preferredAspect = preferredAspect,
                    )?.let { return@runCatching it }
                }
            }
        }
        null
    }.onFailure { error ->
        if (error is CancellationException) throw error
        Timber.tag("AppleCanvas").d(error, "ISRC canvas lookup failed")
    }.getOrNull()

    private suspend fun searchAndFetchMotion(
        song: String,
        artist: String,
        album: String?,
        explicit: Boolean?,
        storefront: String,
        preferredAspect: CanvasAspectPreference,
    ): AppleCanvasArtwork? = runCatching {
        for (query in buildSearchQueries(song, artist, album)) {
            searchSongsAndFetchMotion(
                query = query,
                song = song,
                artist = artist,
                album = album,
                explicit = explicit,
                storefront = storefront,
                preferredAspect = preferredAspect,
            )?.let { return@runCatching it }
        }

        if (!album.isNullOrBlank()) {
            searchAlbumAndFetchMotion(
                album = album,
                artist = artist,
                storefront = storefront,
                preferredAspect = preferredAspect,
            )?.let { return@runCatching it }
        }

        null
    }.onFailure { error ->
        if (error is CancellationException) throw error
        Timber.tag("AppleCanvas").d(error, "Search canvas lookup failed")
    }.getOrNull()

    private suspend fun searchSongsAndFetchMotion(
        query: String,
        song: String,
        artist: String,
        album: String?,
        explicit: Boolean?,
        storefront: String,
        preferredAspect: CanvasAspectPreference,
    ): AppleCanvasArtwork? {
        val url = "$AMP_BASE_URL/v1/catalog/$storefront/search".toHttpUrl().newBuilder()
            .addQueryParameter("term", query)
            .addQueryParameter("types", "songs")
            .addQueryParameter("limit", "10")
            .addQueryParameter("extend", "editorialVideo")
            .addQueryParameter("include", "albums")
            .addQueryParameter("extend[albums]", "editorialVideo")
            .build()

        val root = executeJson(appleRequest(url.toString())) ?: return null
        val results = root["results"].obj()
            ?.get("songs").obj()
            ?.get("data").arr()
            ?: return null
        val included = root["included"].arr()
        val cleanSong = song.cleanForMatch()
        val cleanAlbum = album?.cleanForMatch().orEmpty()
        val sourceAllowsMixResult = song.isMixLikeTitle() || album.orEmpty().isAppleEditorialMixAlbum()

        val scoredResults = results.mapNotNull { item ->
            val obj = item.obj() ?: return@mapNotNull null
            val attributes = obj["attributes"].obj() ?: return@mapNotNull null
            val resultArtist = attributes["artistName"].str().orEmpty()
            val resultName = attributes["name"].str().orEmpty()
            val resultAlbum = attributes["albumName"].str()
                ?: attributes["collectionName"].str()
                ?: ""

            val cleanResultAlbum = resultAlbum.cleanForMatch()
            val cleanName = resultName.cleanForMatch()
            val artistMatches = artist.matchesArtist(resultArtist)
            val albumMatches =
                cleanAlbum.isBlank() ||
                    cleanResultAlbum.isBlank() ||
                    cleanResultAlbum == cleanAlbum ||
                    cleanResultAlbum.contains(cleanAlbum) ||
                    cleanAlbum.contains(cleanResultAlbum)
            if (!artistMatches && !albumMatches) {
                return@mapNotNull null
            }
            if (cleanName.isBlank() || cleanSong.isBlank()) {
                return@mapNotNull null
            }
            if (cleanName != cleanSong && !cleanName.contains(cleanSong) && !cleanSong.contains(cleanName)) {
                return@mapNotNull null
            }
            if (resultName.isMixLikeTitle() && !sourceAllowsMixResult) {
                return@mapNotNull null
            }
            if (!albumMatches && cleanAlbum.isNotBlank()) {
                return@mapNotNull null
            }
            if (resultAlbum.isAppleEditorialMixAlbum() && !sourceAllowsMixResult) {
                return@mapNotNull null
            }
            val resultExplicit = attributes["contentRating"].str()
                ?.equals("explicit", ignoreCase = true)

            var score = 0
            if (cleanName == cleanSong) score += 30
            else if (cleanName.contains(cleanSong) || cleanSong.contains(cleanName)) score += 15

            if (cleanAlbum.isNotBlank() && cleanResultAlbum.isNotBlank()) {
                score += when {
                    cleanResultAlbum == cleanAlbum -> 35
                    cleanResultAlbum.contains(cleanAlbum) || cleanAlbum.contains(cleanResultAlbum) -> 18
                    else -> -12
                }
            }

            if (artistMatches) score += 15
            if (explicit == true) {
                score += if (resultExplicit == true) 40 else -35
            }
            score to item
        }.filter { it.first >= 18 }.sortedByDescending { it.first }

        scoredResults.take(5).forEach { (_, item) ->
            val obj = item.obj() ?: return@forEach
            val attributes = obj["attributes"].obj() ?: return@forEach
            val resultName = attributes["name"].str()
            val resultArtist = attributes["artistName"].str()
            val resultAlbum = attributes["albumName"].str()
                ?: attributes["collectionName"].str()
            val albumId = obj["relationships"].obj()
                ?.get("albums").obj()
                ?.get("data").arr()
                ?.firstOrNull()
                ?.obj()
                ?.get("id").str()
                ?: attributes["collectionId"].str()
                ?: albumIdFromAppleUrl(attributes["url"].str())
                ?: return@forEach
            val webUrl = attributes["url"].str()

            attributes["editorialVideo"].obj()?.let { editorialVideo ->
                val hlsUrl = extractEditorialVideoUrl(editorialVideo, preferredAspect)
                if (!hlsUrl.isNullOrBlank()) {
                    return AppleCanvasArtwork(resultName, resultArtist, albumId, hlsUrl)
                }
            }

            included?.firstOrNull {
                it.obj()?.get("id").str() == albumId
            }?.obj()?.get("attributes").obj()
                ?.get("editorialVideo").obj()
                ?.let { editorialVideo ->
                    val hlsUrl = extractEditorialVideoUrl(editorialVideo, preferredAspect)
                    if (!hlsUrl.isNullOrBlank()) {
                        return AppleCanvasArtwork(resultName, resultArtist, albumId, hlsUrl)
                    }
                }

            fetchMotionArtwork(
                albumId = albumId,
                storefront = storefront,
                fallbackArtist = resultArtist,
                titleOverride = resultName ?: resultAlbum,
                artistOverride = resultArtist,
                webUrl = webUrl,
                preferredAspect = preferredAspect,
            )?.let { return it }
        }
        return null
    }

    private suspend fun searchAlbumAndFetchMotion(
        album: String,
        artist: String,
        storefront: String,
        preferredAspect: CanvasAspectPreference,
    ): AppleCanvasArtwork? {
        val url = "$AMP_BASE_URL/v1/catalog/$storefront/search".toHttpUrl().newBuilder()
            .addQueryParameter("term", "$artist $album")
            .addQueryParameter("types", "albums")
            .addQueryParameter("limit", "6")
            .addQueryParameter("extend", "editorialVideo")
            .build()

        val root = executeJson(appleRequest(url.toString())) ?: return null
        val results = root["results"].obj()
            ?.get("albums").obj()
            ?.get("data").arr()
            ?: return null
        val cleanAlbum = album.cleanForMatch()

        results.mapNotNull { item ->
            val obj = item.obj() ?: return@mapNotNull null
            val attributes = obj["attributes"].obj() ?: return@mapNotNull null
            val resultAlbum = attributes["name"].str().orEmpty()
            val resultArtist = attributes["artistName"].str().orEmpty()
            val cleanResultAlbum = resultAlbum.cleanForMatch()
            if (!artist.matchesArtist(resultArtist)) return@mapNotNull null
            val score = when {
                cleanResultAlbum == cleanAlbum -> 50
                cleanResultAlbum.contains(cleanAlbum) || cleanAlbum.contains(cleanResultAlbum) -> 25
                else -> return@mapNotNull null
            }
            score to obj
        }.sortedByDescending { it.first }
            .take(3)
            .forEach { (_, obj) ->
                val attributes = obj["attributes"].obj() ?: return@forEach
                val albumId = obj["id"].str()
                    ?: attributes["playParams"].obj()?.get("id").str()
                    ?: albumIdFromAppleUrl(attributes["url"].str())
                    ?: return@forEach
                attributes["editorialVideo"].obj()?.let { editorialVideo ->
                    extractEditorialVideoUrl(editorialVideo, preferredAspect)?.let { hlsUrl ->
                        return AppleCanvasArtwork(
                            title = attributes["name"].str(),
                            artist = attributes["artistName"].str(),
                            albumId = albumId,
                            animated = hlsUrl,
                        )
                    }
                }
                fetchMotionArtwork(
                    albumId = albumId,
                    storefront = storefront,
                    fallbackArtist = attributes["artistName"].str(),
                    titleOverride = attributes["name"].str(),
                    artistOverride = attributes["artistName"].str(),
                    webUrl = attributes["url"].str(),
                    preferredAspect = preferredAspect,
                )?.let { return it }
            }

        return null
    }

    private suspend fun fetchMotionArtwork(
        albumId: String,
        storefront: String,
        fallbackArtist: String?,
        titleOverride: String?,
        artistOverride: String?,
        webUrl: String? = null,
        preferredAspect: CanvasAspectPreference,
    ): AppleCanvasArtwork? = runCatching {
        val url = "$AMP_BASE_URL/v1/catalog/$storefront/albums/$albumId".toHttpUrl().newBuilder()
            .addQueryParameter("extend", "editorialVideo")
            .build()

        val root = executeJson(appleRequest(url.toString()))
        val album = root?.get("data").arr()?.firstOrNull()?.obj()
        val attributes = album?.get("attributes").obj()
        val hlsUrl =
            attributes
                ?.get("editorialVideo")
                .obj()
                ?.let { extractEditorialVideoUrl(it, preferredAspect) }
                ?: webUrl?.let { fetchWebPageMotionArtwork(it, preferredAspect) }
                ?: fetchWebPageMotionArtwork("$APPLE_MUSIC_WEB_BASE_URL/$storefront/album/$albumId", preferredAspect)
                ?: return@runCatching null

        AppleCanvasArtwork(
            title = titleOverride ?: attributes?.get("name").str(),
            artist = artistOverride ?: attributes?.get("artistName").str() ?: fallbackArtist,
            albumId = albumId,
            animated = hlsUrl,
        )
    }.onFailure { error ->
        if (error is CancellationException) throw error
        Timber.tag("AppleCanvas").d(error, "Album motion lookup failed")
    }.getOrNull()

    private suspend fun executeJson(request: Request): JsonObject? = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            val body = response.body ?: return@withContext null
            json.parseToJsonElement(body.string()).obj()
        }
    }

    private suspend fun executeText(request: Request): String? = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            response.body.string()
        }
    }

    private fun appleRequest(url: String): Request =
        Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $APPLE_MUSIC_TOKEN")
            .header("Origin", "https://music.apple.com")
            .header("Referer", "https://music.apple.com/")
            .header("User-Agent", APPLE_MUSIC_WEB_USER_AGENT)
            .build()

    private fun appleWebRequest(url: String): Request =
        Request.Builder()
            .url(url)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Referer", "https://music.apple.com/")
            .header("User-Agent", APPLE_MUSIC_WEB_USER_AGENT)
            .build()

    private suspend fun fetchWebPageMotionArtwork(
        url: String,
        preferredAspect: CanvasAspectPreference,
    ): String? = runCatching {
        val html = executeText(appleWebRequest(url))
            ?: return@runCatching null
        extractWebPageMotionArtworkUrl(html, preferredAspect)
    }.onFailure { error ->
        if (error is CancellationException) throw error
        Timber.tag("AppleCanvas").d(error, "Apple Music web canvas lookup failed")
    }.getOrNull()

    private suspend fun fetchWebPageArtistMotionArtwork(url: String): String? = runCatching {
        val html = executeText(appleWebRequest(url))
            ?: return@runCatching null
        extractWebPageArtistMotionArtworkUrl(html)
    }.onFailure { error ->
        if (error is CancellationException) throw error
        Timber.tag("AppleCanvas").d(error, "Apple Music artist motion lookup failed")
    }.getOrNull()

    private suspend fun fetchCatalogArtistMotionArtwork(
        artistId: String,
        storefront: String,
    ): String? = runCatching {
        val url = "$AMP_BASE_URL/v1/catalog/$storefront/artists/$artistId".toHttpUrl().newBuilder()
            .addQueryParameter("extend", "editorialVideo")
            .build()
        val root = executeJson(appleRequest(url.toString()))
            ?: return@runCatching null
        val attributes =
            root["data"].arr()
                ?.firstOrNull()
                ?.obj()
                ?.get("attributes")
                .obj()
                ?: return@runCatching null
        attributes["editorialVideo"].obj()?.let { extractArtistEditorialVideoUrl(it) }
    }.onFailure { error ->
        if (error is CancellationException) throw error
        Timber.tag("AppleCanvas").d(error, "Apple Music catalog artist motion lookup failed")
    }.getOrNull()

    private fun extractArtistEditorialVideoUrl(editorialVideo: JsonObject): String? {
        val fields = listOf(
            "motionArtistFullscreen16x9",
            "motionArtistWide16x9",
            "artistHero",
            "artistMotion",
            "heroVideo",
            "backgroundVideo",
            "videoArtwork",
            "editorialVideo",
            "motion",
            "motionArtistSquare1x1",
            "video",
            "videoUrl",
            "hlsUrl",
            "url",
        )

        fields.forEach { field ->
            editorialVideo[field].obj()?.let { value ->
                extractMvodUrl(value.toString())?.let { return it }
            }
            editorialVideo[field].str()?.let { value ->
                extractMvodUrl(value)?.let { return it }
            }
        }

        return extractMvodUrl(editorialVideo.toString())
    }

    private fun extractWebPageMotionArtworkUrl(
        html: String,
        preferredAspect: CanvasAspectPreference,
    ): String? {
        val fields = when (preferredAspect) {
            CanvasAspectPreference.TALL -> listOf("motionDetailTall", "motionDetailSquare", "motionDetailRaw")
            CanvasAspectPreference.SQUARE -> listOf("motionDetailSquare", "motionDetailTall", "motionDetailRaw")
            CanvasAspectPreference.RAW -> listOf("motionDetailRaw", "motionDetailSquare", "motionDetailTall")
            CanvasAspectPreference.AUTO -> listOf("motionDetailSquare", "motionDetailTall", "motionDetailRaw")
        }

        fields.forEach { field ->
            val index = html.indexOf("\"$field\"")
            if (index >= 0) {
                val end = min(html.length, index + 2_500)
                extractMvodUrl(html.substring(index, end))?.let { return it }
            }
        }

        return extractMvodUrl(html)
    }

    private fun extractWebPageArtistMotionArtworkUrl(html: String): String? {
        val fields = listOf(
            "artistHero",
            "artistMotion",
            "heroVideo",
            "backgroundVideo",
            "videoArtwork",
            "editorialVideo",
            "motion",
        )

        fields.forEach { field ->
            var index = html.indexOf(field, ignoreCase = true)
            while (index >= 0) {
                val start = maxOf(0, index - 750)
                val end = min(html.length, index + 4_000)
                extractMvodUrl(html.substring(start, end))?.let { return it }
                index = html.indexOf(field, startIndex = index + field.length, ignoreCase = true)
            }
        }

        return extractMvodUrl(html)
    }

    private fun extractMvodUrl(text: String): String? {
        WEB_MVOD_HLS_REGEX.find(text)?.value?.let { return it }
        val unescaped = text
            .replace("\\/", "/")
            .replace("\\u002F", "/")
        return WEB_MVOD_HLS_REGEX.find(unescaped)?.value
    }

    private fun extractEditorialVideoUrl(
        editorialVideo: JsonObject,
        preferredAspect: CanvasAspectPreference,
    ): String? {
        val assets = when (preferredAspect) {
            CanvasAspectPreference.TALL -> listOf("motionDetailTall", "motionTallVideo3x4", "motionDetailSquare", "motionSquareVideo1x1", "motionDetailRaw", "motionDetailStatic")
            CanvasAspectPreference.SQUARE -> listOf("motionDetailSquare", "motionSquareVideo1x1", "motionDetailTall", "motionTallVideo3x4", "motionDetailRaw", "motionDetailStatic")
            CanvasAspectPreference.RAW -> listOf("motionDetailRaw", "motionDetailSquare", "motionSquareVideo1x1", "motionDetailTall", "motionTallVideo3x4", "motionDetailStatic")
            CanvasAspectPreference.AUTO -> listOf("motionDetailSquare", "motionSquareVideo1x1", "motionDetailTall", "motionTallVideo3x4", "motionDetailRaw", "motionDetailStatic")
        }

        return assets.asSequence()
            .mapNotNull { editorialVideo[it].obj() }
            .mapNotNull { asset ->
                asset["video"].str()
                    ?: asset["videoUrl"].str()
                    ?: asset["hlsUrl"].str()
                    ?: asset["url"].str()
            }
            .firstOrNull { it.isNotBlank() }
    }

    private fun albumIdFromAppleUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val id = url.substringAfter("/album/", "").substringBefore("?").substringAfterLast("/")
        return id.takeIf { it.isNotBlank() && it.all(Char::isDigit) }
    }

    private fun buildSearchQueries(
        song: String,
        artist: String,
        album: String?,
    ): List<String> {
        val base = if (song.contains(artist, ignoreCase = true)) song else "$artist $song"
        return listOfNotNull(
            "$song ${album.orEmpty()} $artist".takeIf { !album.isNullOrBlank() },
            "$base ${album.orEmpty()}".takeIf { !album.isNullOrBlank() },
            base,
            "$song $artist",
            "$artist ${album.orEmpty()}".takeIf { !album.isNullOrBlank() },
        ).map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.cleanForMatch() }
    }

    private fun String.cleanForMatch(): String =
        Normalizer.normalize(this, Normalizer.Form.NFD)
            .replace(Regex("""\p{Mn}+"""), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("""(?i)\b(feat|ft|featuring)\.?\b.*"""), "")
            .replace(Regex("""\s*\(.*?\)"""), "")
            .replace(Regex("""\s*\[.*?]"""), "")
            .replace(Regex("""\s*-\s*.*"""), "")
            .replace(Regex("""['’`]"""), "")
            .replace("&", " and ")
            .replace(Regex("""[^a-z0-9]+"""), " ")
            .trim()

    private fun String.matchesArtist(candidate: String): Boolean {
        val expected = cleanForMatch()
        val actual = candidate.cleanForMatch()
        if (expected.isBlank() || actual.isBlank()) return false
        if (expected == actual || expected.contains(actual) || actual.contains(expected)) return true

        val expectedPrimary = primaryArtistForMatch()
        val actualPrimary = candidate.primaryArtistForMatch()
        return expectedPrimary.isNotBlank() &&
            actualPrimary.isNotBlank() &&
            (expectedPrimary == actualPrimary ||
                expectedPrimary.contains(actualPrimary) ||
                actualPrimary.contains(expectedPrimary))
    }

    private fun String.primaryArtistForMatch(): String =
        replace(Regex("""(?i)\b(feat|ft|featuring|with)\b.*"""), "")
            .split(",", "&", " x ", " X ", ";")
            .firstOrNull()
            .orEmpty()
            .cleanForMatch()

    private fun String.isMixLikeTitle(): Boolean {
        val value = lowercase(Locale.ROOT)
        return Regex("""(?i)(\(|\[)\s*(mixed|dj mix|remix)\s*(\)|])""").containsMatchIn(this) ||
            value.endsWith(" - mixed") ||
            value.endsWith(" - dj mix") ||
            value.endsWith(" - remix")
    }

    private fun String.isAppleEditorialMixAlbum(): Boolean {
        val value = lowercase(Locale.ROOT)
        return value.contains("dj mix") ||
            value.contains("rap life:") ||
            value.contains("the rap roundup") ||
            value.contains("new music daily") ||
            value.contains("today's hits") ||
            value.contains("a-list")
    }

    private fun cacheKey(prefix: String, vararg parts: String): String =
        "$prefix|" + parts.joinToString("|") { it.trim().lowercase(Locale.ROOT) }

    private fun getCacheEntry(key: String): CacheEntry? {
        val entry = cache[key] ?: return null
        if (entry.expiresAtMs <= System.currentTimeMillis()) {
            cache.remove(key)
            return null
        }
        return entry
    }

    private fun JsonElement?.obj(): JsonObject? = this as? JsonObject

    private fun JsonElement?.arr(): JsonArray? = this as? JsonArray

    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull
}
