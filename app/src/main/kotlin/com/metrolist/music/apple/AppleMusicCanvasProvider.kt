package com.metrolist.music.apple

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

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
    private const val CACHE_TTL_MS = 1000L * 60 * 60 * 24
    private const val LOOKUP_TIMEOUT_MS = 10_000L
    private val WEB_MVOD_VIDEO_REGEX =
        Regex("""https://mvod\.itunes\.apple\.com/[^"'<>\\\s]+?\.(?:m3u8|mp4)""")

    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    private val providerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    private val cache = ConcurrentHashMap<String, CacheEntry>()

    private data class CacheEntry(
        val value: AppleCanvasArtwork,
        val expiresAtMs: Long,
    )

    fun getCached(
        song: String,
        artist: String,
        album: String? = null,
        isrc: String? = null,
        storefront: String = "us",
        preferredAspect: CanvasAspectPreference = CanvasAspectPreference.SQUARE,
    ): AppleCanvasArtwork? {
        val songKey = songCacheKey(song, artist, album, explicit, isrc, storefront, preferredAspect)
        return getCacheEntry(songKey)?.value
            ?: getCachedAlbumMotion(album, artist, storefront, preferredAspect)
    }

    suspend fun getBySongArtist(
        song: String,
        artist: String,
        album: String? = null,
        isrc: String? = null,
        storefront: String = "us",
        preferredAspect: CanvasAspectPreference = CanvasAspectPreference.SQUARE,
    ): AppleCanvasArtwork? = withTimeoutOrNull(LOOKUP_TIMEOUT_MS) {
        val songKey = songCacheKey(song, artist, album, explicit, isrc, storefront, preferredAspect)
        getCacheEntry(songKey)?.let { return@withTimeoutOrNull it.value }
        getCachedAlbumMotion(album, artist, storefront, preferredAspect)?.let { cached ->
            cacheResult(songKey, cached)
            return@withTimeoutOrNull cached
        }

        val result =
            if (!isrc.isNullOrBlank()) {
                fetchByIsrc(
                    isrc = isrc,
                    storefront = storefront,
                    preferredAspect = preferredAspect,
                    titleOverride = song,
                )
            } else {
                null
            }
                ?: searchAndFetchMotion(
                    song = song,
                    artist = artist,
                    album = album,
                    explicit = explicit,
                    storefront = storefront,
                    preferredAspect = preferredAspect,
                )

        result?.let {
            cacheResult(songKey, it)
            cacheAlbumMotion(album, artist, storefront, preferredAspect, it)
        }
        result
    }

    suspend fun getByAlbumArtist(
        album: String,
        artist: String,
        storefront: String = "us",
        preferredAspect: CanvasAspectPreference = CanvasAspectPreference.SQUARE,
    ): AppleCanvasArtwork? = withTimeoutOrNull(LOOKUP_TIMEOUT_MS) {
        getCachedAlbumMotion(album, artist, storefront, preferredAspect)?.let { return@withTimeoutOrNull it }
        searchAlbumAndFetchMotion(
            album = album,
            artist = artist,
            storefront = storefront,
            preferredAspect = preferredAspect,
            titleOverride = null,
        )?.also { cacheAlbumMotion(album, artist, storefront, preferredAspect, it) }
    }

    suspend fun getByAlbumId(
        albumId: String,
        storefront: String = "us",
        preferredAspect: CanvasAspectPreference = CanvasAspectPreference.SQUARE,
    ): AppleCanvasArtwork? = withTimeoutOrNull(LOOKUP_TIMEOUT_MS) {
        val key = cacheKey("album-id", albumId, storefront, preferredAspect.name)
        getCacheEntry(key)?.let { return@withTimeoutOrNull it.value }
        fetchMotionArtwork(
            albumId = albumId,
            storefront = storefront,
            fallbackArtist = null,
            titleOverride = null,
            artistOverride = null,
            preferredAspect = preferredAspect,
        )?.also { cacheResult(key, it) }
    }

    fun prefetchBySongArtist(
        song: String,
        artist: String,
        album: String? = null,
        isrc: String? = null,
        storefront: String = "us",
        preferredAspect: CanvasAspectPreference = CanvasAspectPreference.SQUARE,
    ) {
        val key = songCacheKey(song, artist, album, explicit, isrc, storefront, preferredAspect)
        if (getCacheEntry(key) != null) return
        providerScope.launch {
            runCatching {
                getBySongArtist(song, artist, album, isrc, storefront, preferredAspect)
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
        result?.let { cacheResult(key, it) }
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

    private suspend fun searchAndFetchMotion(
        song: String,
        artist: String,
        album: String?,
        explicit: Boolean?,
        storefront: String,
        preferredAspect: CanvasAspectPreference,
    ): AppleCanvasArtwork? = runCatching {
        if (!album.isNullOrBlank()) {
            searchAlbumAndFetchMotion(
                album = album,
                artist = artist,
                storefront = storefront,
                preferredAspect = preferredAspect,
                titleOverride = song,
            )?.let { return@runCatching it }
        }

        buildSearchQueries(song, artist, album).forEach { query ->
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

        null
    }.onFailure { error ->
        if (error is CancellationException) throw error
        Timber.tag("AppleCanvas").d(error, "Search canvas lookup failed")
    }.getOrNull()

    private suspend fun fetchByIsrc(
        isrc: String,
        storefront: String,
        preferredAspect: CanvasAspectPreference,
        titleOverride: String?,
    ): AppleCanvasArtwork? = runCatching {
        val url =
            "$AMP_BASE_URL/v1/catalog/$storefront/songs"
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("filter[isrc]", isrc)
                .addQueryParameter("extend", "editorialVideo")
                .build()

        val root = executeJson(appleRequest(url.toString())) ?: return@runCatching null
        val data = root["data"].arr() ?: return@runCatching null
        for (item in data) {
            val obj = item.obj() ?: continue
            val attributes = obj["attributes"].obj() ?: continue
            val albumId = obj.albumIdFromSong(attributes) ?: continue
            if (!albumId.isValidAppleAlbumId()) continue

            attributes["editorialVideo"].obj()
                ?.let { extractEditorialVideoUrl(it, preferredAspect) }
                ?.takeIf { it.isNotBlank() }
                ?.let { hlsUrl ->
                    return@runCatching AppleCanvasArtwork(
                        title = titleOverride ?: attributes["name"].str(),
                        artist = attributes["artistName"].str(),
                        albumId = albumId,
                        animated = hlsUrl,
                    )
                }

            fetchMotionArtwork(
                albumId = albumId,
                storefront = storefront,
                fallbackArtist = attributes["artistName"].str(),
                titleOverride = titleOverride ?: attributes["name"].str(),
                artistOverride = attributes["artistName"].str(),
                preferredAspect = preferredAspect,
            )?.let { return@runCatching it }
        }

        null
    }.onFailure { error ->
        if (error is CancellationException) throw error
        Timber.tag("AppleCanvas").d(error, "ISRC canvas lookup failed")
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
        val url =
            "$AMP_BASE_URL/v1/catalog/$storefront/search"
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("term", query)
                .addQueryParameter("types", "songs")
                .addQueryParameter("limit", "10")
                .addQueryParameter("extend", "editorialVideo")
                .build()

        val root = executeJson(appleRequest(url.toString())) ?: return null
        val results =
            root["results"].obj()
                ?.get("songs").obj()
                ?.get("data").arr()
                ?: return null
        val cleanSong = song.cleanForMatch()
        val cleanAlbum = album?.cleanForMatch().orEmpty()

        val scoredResults =
            results.mapNotNull { item ->
                val obj = item.obj() ?: return@mapNotNull null
                val attributes = obj["attributes"].obj() ?: return@mapNotNull null
                val resultArtist = attributes["artistName"].str().orEmpty()
                val resultName = attributes["name"].str().orEmpty()
                val resultAlbum = attributes.albumName()
                if (resultName.isBlacklistedAppleMotionName() || resultAlbum.isBlacklistedAppleMotionName()) {
                    return@mapNotNull null
                }
                if (!artist.matchesArtist(resultArtist)) return@mapNotNull null

                val cleanName = resultName.cleanForMatch()
                val cleanResultAlbum = resultAlbum.cleanForMatch()
                val albumMatches =
                    cleanAlbum.isBlank() ||
                        cleanResultAlbum == cleanAlbum ||
                        cleanResultAlbum.contains(cleanAlbum) ||
                        cleanAlbum.contains(cleanResultAlbum)
                if (cleanAlbum.isNotBlank() && !albumMatches) return@mapNotNull null
                if (cleanName.isBlank() || cleanSong.isBlank()) return@mapNotNull null

                var score = 0
                score += when {
                    cleanName == cleanSong -> 45
                    cleanName.contains(cleanSong) || cleanSong.contains(cleanName) -> 25
                    else -> -20
                }
                if (artist.equals(resultArtist, ignoreCase = true)) score += 20 else score += 10
                if (albumMatches && cleanAlbum.isNotBlank()) score += 40
                if (explicit == true) {
                    val resultExplicit = attributes["contentRating"].str()?.equals("explicit", ignoreCase = true) == true
                    score += if (resultExplicit) 10 else -10
                }
                score to item
            }.filter { it.first >= 35 }
                .sortedByDescending { it.first }

        for ((_, item) in scoredResults.take(5)) {
            resolveSearchResultMotion(
                item = item,
                storefront = storefront,
                preferredAspect = preferredAspect,
                songTitle = song,
            )?.let { return it }
        }

        return null
    }

    private suspend fun searchAlbumAndFetchMotion(
        album: String,
        artist: String,
        storefront: String,
        preferredAspect: CanvasAspectPreference,
        titleOverride: String?,
    ): AppleCanvasArtwork? {
        val url =
            "$AMP_BASE_URL/v1/catalog/$storefront/search"
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("term", "$artist $album")
                .addQueryParameter("types", "albums")
                .addQueryParameter("limit", "8")
                .addQueryParameter("extend", "editorialVideo")
                .build()

        val root = executeJson(appleRequest(url.toString())) ?: return null
        val results =
            root["results"].obj()
                ?.get("albums").obj()
                ?.get("data").arr()
                ?: return null
        val cleanAlbum = album.cleanForMatch()

        val scoredResults =
            results.mapNotNull { item ->
                val obj = item.obj() ?: return@mapNotNull null
                val attributes = obj["attributes"].obj() ?: return@mapNotNull null
                val resultAlbum = attributes["name"].str().orEmpty()
                val resultArtist = attributes["artistName"].str().orEmpty()
                if (resultAlbum.isBlacklistedAppleMotionName()) return@mapNotNull null
                if (!artist.matchesArtist(resultArtist)) return@mapNotNull null

                val cleanResultAlbum = resultAlbum.cleanForMatch()
                val score =
                    when {
                        cleanResultAlbum == cleanAlbum -> 70
                        cleanResultAlbum.contains(cleanAlbum) || cleanAlbum.contains(cleanResultAlbum) -> 45
                        else -> return@mapNotNull null
                    } + if (artist.equals(resultArtist, ignoreCase = true)) 20 else 10
                score to item
            }.sortedByDescending { it.first }

        for ((_, item) in scoredResults.take(4)) {
            resolveSearchResultMotion(
                item = item,
                storefront = storefront,
                preferredAspect = preferredAspect,
                songTitle = titleOverride,
            )?.let { return it }
        }

        return null
    }

    private suspend fun resolveSearchResultMotion(
        item: JsonElement,
        storefront: String,
        preferredAspect: CanvasAspectPreference,
        songTitle: String?,
    ): AppleCanvasArtwork? {
        val obj = item.obj() ?: return null
        val attributes = obj["attributes"].obj() ?: return null
        val type = obj["type"].str()
        val resultName = attributes["name"].str()
        val resultArtist = attributes["artistName"].str()
        val albumName = attributes.albumName().ifBlank { resultName.orEmpty() }
        if (resultName.orEmpty().isBlacklistedAppleMotionName() || albumName.isBlacklistedAppleMotionName()) {
            return null
        }

        val albumId =
            when (type) {
                "songs" -> obj.albumIdFromSong(attributes)
                "albums" -> obj["id"].str() ?: attributes["playParams"].obj()?.get("id").str()
                else -> null
            } ?: return null
        if (!albumId.isValidAppleAlbumId()) return null

        attributes["editorialVideo"].obj()
            ?.let { extractEditorialVideoUrl(it, preferredAspect) }
            ?.takeIf { it.isNotBlank() }
            ?.let { hlsUrl ->
                return AppleCanvasArtwork(
                    title = if (type == "songs") resultName else songTitle ?: resultName,
                    artist = resultArtist,
                    albumId = albumId,
                    animated = hlsUrl,
                )
            }

        return fetchMotionArtwork(
            albumId = albumId,
            storefront = storefront,
            fallbackArtist = resultArtist,
            titleOverride = if (type == "songs") resultName else songTitle,
            artistOverride = resultArtist,
            preferredAspect = preferredAspect,
        )
    }

    private suspend fun fetchMotionArtwork(
        albumId: String,
        storefront: String,
        fallbackArtist: String?,
        titleOverride: String?,
        artistOverride: String?,
        preferredAspect: CanvasAspectPreference,
    ): AppleCanvasArtwork? = runCatching {
        if (!albumId.isValidAppleAlbumId()) return@runCatching null
        val url =
            "$AMP_BASE_URL/v1/catalog/$storefront/albums/$albumId"
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("extend", "editorialVideo")
                .addQueryParameter("include", "tracks")
                .build()

        val root = executeJson(appleRequest(url.toString())) ?: return@runCatching null
        val albumObj = root["data"].arr()?.firstOrNull()?.obj() ?: return@runCatching null
        val attributes = albumObj["attributes"].obj() ?: return@runCatching null
        val albumName = attributes["name"].str().orEmpty()
        if (albumName.isBlacklistedAppleMotionName()) return@runCatching null

        val hlsUrl =
            attributes["editorialVideo"].obj()
                ?.let { extractEditorialVideoUrl(it, preferredAspect) }
                ?.takeIf { it.isNotBlank() }
                ?: return@runCatching null

        AppleCanvasArtwork(
            title = titleOverride ?: albumName,
            artist = artistOverride ?: attributes["artistName"].str() ?: fallbackArtist,
            albumId = albumId,
            animated = hlsUrl,
        )
    }.onFailure { error ->
        if (error is CancellationException) throw error
        Timber.tag("AppleCanvas").d(error, "Album motion lookup failed")
    }.getOrNull()

    private suspend fun searchArtistMotion(
        artist: String,
        storefront: String,
    ): AppleCanvasArtwork? = runCatching {
        val url =
            "$AMP_BASE_URL/v1/catalog/$storefront/search"
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("term", artist)
                .addQueryParameter("types", "artists")
                .addQueryParameter("limit", "6")
                .build()

        val root = executeJson(appleRequest(url.toString())) ?: return@runCatching null
        val results =
            root["results"].obj()
                ?.get("artists").obj()
                ?.get("data").arr()
                ?: return@runCatching null
        val cleanArtist = artist.cleanForMatch()

        for ((_, obj) in results.mapNotNull { item ->
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
        }.sortedByDescending { it.first }) {
            val attributes = obj["attributes"].obj() ?: continue
            val webUrl = attributes["url"].str() ?: continue
            val hlsUrl = fetchWebPageArtistMotionArtwork(webUrl) ?: continue
            return@runCatching AppleCanvasArtwork(
                title = attributes["name"].str(),
                artist = attributes["name"].str(),
                albumId = obj["id"].str(),
                animated = hlsUrl,
            )
        }

        null
    }.onFailure { error ->
        if (error is CancellationException) throw error
        Timber.tag("AppleCanvas").d(error, "Artist motion lookup failed")
    }.getOrNull()

    private fun extractEditorialVideoUrl(
        editorialVideo: JsonObject,
        preferredAspect: CanvasAspectPreference,
    ): String? {
        val fields =
            when (preferredAspect) {
                CanvasAspectPreference.TALL -> listOf("motionDetailTall", "motionTallVideo3x4", "motionDetailSquare", "motionSquareVideo1x1", "motionDetailRaw")
                CanvasAspectPreference.SQUARE -> listOf("motionDetailSquare", "motionSquareVideo1x1", "motionDetailTall", "motionTallVideo3x4", "motionDetailRaw")
                CanvasAspectPreference.RAW -> listOf("motionDetailRaw", "motionDetailSquare", "motionSquareVideo1x1", "motionDetailTall", "motionTallVideo3x4")
                CanvasAspectPreference.AUTO -> listOf("motionDetailSquare", "motionSquareVideo1x1", "motionDetailTall", "motionTallVideo3x4", "motionDetailRaw")
            } + "motionDetailStatic"

        val preferredUrls =
            fields.asSequence()
            .mapNotNull { editorialVideo[it].obj() }
            .flatMap { it.mvodUrls().asSequence() }
            .toList()

        return preferredUrls.bestAppleMvodUrl()
            ?: editorialVideo.mvodUrls().bestAppleMvodUrl()
    }

    private suspend fun executeJson(request: Request): JsonObject? = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            json.parseToJsonElement(response.body.string()).obj()
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
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/147 Safari/537.36")
            .build()

    private fun appleWebRequest(url: String): Request =
        Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/147 Safari/537.36")
            .build()

    private suspend fun fetchWebPageArtistMotionArtwork(url: String): String? = runCatching {
        val html = executeText(appleWebRequest(url)) ?: return@runCatching null
        extractWebPageArtistMotionArtworkUrl(html)
    }.onFailure { error ->
        if (error is CancellationException) throw error
        Timber.tag("AppleCanvas").d(error, "Apple Music artist motion lookup failed")
    }.getOrNull()

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
                val end = minOf(html.length, index + 4_000)
                extractMvodUrl(html.substring(start, end))?.let { return it }
                index = html.indexOf(field, startIndex = index + field.length, ignoreCase = true)
            }
        }

        return extractMvodUrl(html)
    }

    private fun extractMvodUrl(text: String): String? {
        return extractMvodUrls(text).bestAppleMvodUrl()
    }

    private fun extractMvodUrls(text: String): List<String> {
        val unescaped = text
            .replace("\\/", "/")
            .replace("\\u002F", "/")
        return listOf(text, unescaped)
            .asSequence()
            .flatMap { source -> WEB_MVOD_VIDEO_REGEX.findAll(source) }
            .mapNotNull { it.value.normalizeAppleMvodVideoUrl() }
            .distinct()
            .toList()
    }

    private fun buildSearchQueries(
        song: String,
        artist: String,
        album: String?,
    ): List<String> {
        val base = if (song.contains(artist, ignoreCase = true)) song else "$artist $song"
        return listOfNotNull(
            "$base ${album.orEmpty()}".takeIf { !album.isNullOrBlank() },
            "$song ${album.orEmpty()} $artist".takeIf { !album.isNullOrBlank() },
            base,
            "$song $artist",
        ).map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.cleanForMatch() }
    }

    private fun JsonObject.albumIdFromSong(attributes: JsonObject): String? =
        get("relationships").obj()
            ?.get("albums").obj()
            ?.get("data").arr()
            ?.firstOrNull()
            ?.obj()
            ?.get("id").str()
            ?: attributes["collectionId"].str()
            ?: albumIdFromAppleUrl(attributes["url"].str())

    private fun JsonObject.albumName(): String =
        get("albumName").str()
            ?: get("collectionName").str()
            ?: get("name").str()
            ?: ""

    private fun albumIdFromAppleUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val id = url.substringAfter("/album/", "").substringBefore("?").substringAfterLast("/")
        return id.takeIf { it.isValidAppleAlbumId() }
    }

    private fun String?.isValidAppleAlbumId(): Boolean =
        !isNullOrBlank() && !startsWith("pl.") && all(Char::isDigit)

    private fun JsonElement?.mvodUrls(): List<String> {
        val urls = mutableListOf<String>()
        fun collect(element: JsonElement?) {
            when (element) {
                is JsonArray -> element.forEach(::collect)
                is JsonObject -> element.values.forEach(::collect)
                is JsonPrimitive -> element.contentOrNull?.let { urls += extractMvodUrls(it) }
                null -> Unit
            }
        }
        collect(this)
        return urls.distinct()
    }

    private fun Iterable<String>.bestAppleMvodUrl(): String? =
        firstOrNull { it.isAppleMvodMp4Url() }
            ?: firstOrNull { it.isAppleMvodCanvasUrl() }

    private fun String?.isAppleMvodCanvasUrl(): Boolean {
        val parsed = this?.toHttpUrlOrNull() ?: return false
        val host = parsed.host
        val path = parsed.encodedPath.lowercase(Locale.US)
        return parsed.scheme == "https" &&
            (host == "mvod.itunes.apple.com" || host.endsWith(".mvod.itunes.apple.com")) &&
            (path.endsWith(".m3u8") || path.endsWith(".mp4"))
    }

    private fun String?.isAppleMvodMp4Url(): Boolean =
        isAppleMvodCanvasUrl() && this
            ?.toHttpUrlOrNull()
            ?.encodedPath
            ?.endsWith(".mp4", ignoreCase = true) == true

    private fun String.normalizeAppleMvodVideoUrl(): String? {
        if (isAppleMvodCanvasUrl()) return this
        val withoutQuery = substringBefore("?")
        if (!withoutQuery.endsWith(".mp4", ignoreCase = true)) return null
        if (!withoutQuery.contains("mvod.itunes.apple.com", ignoreCase = true)) return null
        val hlsUrl = withoutQuery
            .removeSuffix(".mp4")
            .removeSuffix("-")
            .plus(".m3u8")
        return hlsUrl.takeIf { it.isAppleMvodCanvasUrl() }
    }

    private fun String?.isBlacklistedAppleMotionName(): Boolean {
        val value = this?.cleanForMatch().orEmpty()
        if (value.isBlank()) return false
        return value.contains("playlist") ||
            value.contains("set list") ||
            value.contains("essentials") ||
            value.contains("dj mix") ||
            value.contains("mixed") ||
            value.contains("apple music") ||
            value.contains("todays hits") ||
            value.contains("rap life") ||
            value.contains("the rap roundup") ||
            value.contains("new music daily") ||
            value.contains("session")
    }

    private fun String.cleanForMatch(): String =
        lowercase(Locale.ROOT)
            .replace(Regex("""\s*\(.*?\)"""), "")
            .replace(Regex("""\s*\[.*?]"""), "")
            .replace(Regex("""['\u2019`]"""), "")
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

    private fun getCachedAlbumMotion(
        album: String?,
        artist: String,
        storefront: String,
        preferredAspect: CanvasAspectPreference,
    ): AppleCanvasArtwork? {
        if (album.isNullOrBlank()) return null
        val key = cacheKey("album", album, artist, storefront, preferredAspect.name)
        return getCacheEntry(key)?.value
    }

    private fun cacheAlbumMotion(
        album: String?,
        artist: String,
        storefront: String,
        preferredAspect: CanvasAspectPreference,
        result: AppleCanvasArtwork,
    ) {
        if (album.isNullOrBlank() || result.animated.isNullOrBlank()) return
        cacheResult(cacheKey("album", album, artist, storefront, preferredAspect.name), result)
    }

    private fun cacheResult(
        key: String,
        result: AppleCanvasArtwork,
    ) {
        if (result.animated.isNullOrBlank()) return
        cache[key] = CacheEntry(
            value = result,
            expiresAtMs = System.currentTimeMillis() + CACHE_TTL_MS,
        )
    }

    private fun getCacheEntry(key: String): CacheEntry? {
        val entry = cache[key] ?: return null
        if (entry.expiresAtMs <= System.currentTimeMillis()) {
            cache.remove(key)
            return null
        }
        return entry
    }

    private fun songCacheKey(
        song: String,
        artist: String,
        album: String?,
        explicit: Boolean?,
        isrc: String?,
        storefront: String,
        preferredAspect: CanvasAspectPreference,
    ): String =
        cacheKey("song", isrc ?: song, artist, album.orEmpty(), explicit?.toString().orEmpty(), storefront, preferredAspect.name)

    private fun cacheKey(prefix: String, vararg parts: String): String =
        "$prefix|" + parts.joinToString("|") { it.trim().lowercase(Locale.ROOT) }

    private fun JsonElement?.obj(): JsonObject? = this as? JsonObject

    private fun JsonElement?.arr(): JsonArray? = this as? JsonArray

    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull
}
