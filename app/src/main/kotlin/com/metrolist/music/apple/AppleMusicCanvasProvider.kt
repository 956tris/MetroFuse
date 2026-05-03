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
    private const val CACHE_TTL_MS = 1000L * 60 * 60 * 24

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
        isrc: String? = null,
        storefront: String = "us",
        preferredAspect: CanvasAspectPreference = CanvasAspectPreference.SQUARE,
    ): AppleCanvasArtwork? {
        val key = cacheKey("song", isrc ?: song, artist, album.orEmpty(), storefront, preferredAspect.name)
        return getCacheEntry(key)?.value
    }

    suspend fun getBySongArtist(
        song: String,
        artist: String,
        album: String? = null,
        isrc: String? = null,
        storefront: String = "us",
        preferredAspect: CanvasAspectPreference = CanvasAspectPreference.SQUARE,
    ): AppleCanvasArtwork? = withTimeoutOrNull(4_000L) {
        val key = cacheKey("song", isrc ?: song, artist, album.orEmpty(), storefront, preferredAspect.name)
        getCacheEntry(key)?.let { return@withTimeoutOrNull it.value }

        val result = coroutineScope {
            val byIsrc = if (!isrc.isNullOrBlank()) {
                async { fetchByIsrc(isrc, storefront, preferredAspect) }
            } else {
                null
            }
            val bySearch = async { searchAndFetchMotion(song, artist, album, storefront, preferredAspect) }
            byIsrc?.await() ?: bySearch.await()
        }

        cache[key] = CacheEntry(result, System.currentTimeMillis() + CACHE_TTL_MS)
        result
    }

    fun prefetchBySongArtist(
        song: String,
        artist: String,
        album: String? = null,
        isrc: String? = null,
        storefront: String = "us",
        preferredAspect: CanvasAspectPreference = CanvasAspectPreference.SQUARE,
    ) {
        val key = cacheKey("song", isrc ?: song, artist, album.orEmpty(), storefront, preferredAspect.name)
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
        storefront: String,
        preferredAspect: CanvasAspectPreference,
    ): AppleCanvasArtwork? = runCatching {
        var query = if (song.contains(artist, ignoreCase = true)) song else "$artist $song"
        if (!album.isNullOrBlank() && !query.contains(album, ignoreCase = true)) {
            query = "$query $album"
        }

        val url = "$AMP_BASE_URL/v1/catalog/$storefront/search".toHttpUrl().newBuilder()
            .addQueryParameter("term", query)
            .addQueryParameter("types", "songs")
            .addQueryParameter("limit", "5")
            .addQueryParameter("extend", "editorialVideo")
            .addQueryParameter("include", "albums")
            .addQueryParameter("extend[albums]", "editorialVideo")
            .build()

        val root = executeJson(appleRequest(url.toString())) ?: return@runCatching null
        val results = root["results"].obj()
            ?.get("songs").obj()
            ?.get("data").arr()
            ?: return@runCatching null
        val included = root["included"].arr()

        val scoredResults = results.mapNotNull { item ->
            val obj = item.obj() ?: return@mapNotNull null
            val attributes = obj["attributes"].obj() ?: return@mapNotNull null
            val resultArtist = attributes["artistName"].str().orEmpty()
            val resultName = attributes["name"].str().orEmpty()
            val resultAlbum = attributes["collectionName"].str().orEmpty()

            if (!resultArtist.contains(artist, ignoreCase = true) &&
                !artist.contains(resultArtist, ignoreCase = true)
            ) {
                return@mapNotNull null
            }

            var score = 0
            val cleanSong = song.cleanForMatch()
            val cleanName = resultName.cleanForMatch()
            if (cleanName == cleanSong) score += 30
            else if (cleanName.contains(cleanSong) || cleanSong.contains(cleanName)) score += 15

            val cleanAlbum = album?.cleanForMatch().orEmpty()
            val cleanResultAlbum = resultAlbum.cleanForMatch()
            if (cleanAlbum.isNotBlank() && cleanResultAlbum.isNotBlank()) {
                score += when {
                    cleanResultAlbum == cleanAlbum -> 25
                    cleanResultAlbum.contains(cleanAlbum) || cleanAlbum.contains(cleanResultAlbum) -> 10
                    else -> -8
                }
            }

            if (resultArtist.equals(artist, ignoreCase = true)) score += 10
            score to item
        }.filter { it.first > 10 }.sortedByDescending { it.first }

        scoredResults.take(3).forEachIndexed { index, (_, item) ->
            val obj = item.obj() ?: return@forEachIndexed
            val attributes = obj["attributes"].obj() ?: return@forEachIndexed
            val resultName = attributes["name"].str()
            val resultArtist = attributes["artistName"].str()
            val albumId = obj["relationships"].obj()
                ?.get("albums").obj()
                ?.get("data").arr()
                ?.firstOrNull()
                ?.obj()
                ?.get("id").str()
                ?: attributes["collectionId"].str()
                ?: albumIdFromAppleUrl(attributes["url"].str())
                ?: return@forEachIndexed

            attributes["editorialVideo"].obj()?.let { editorialVideo ->
                val hlsUrl = extractEditorialVideoUrl(editorialVideo, preferredAspect)
                if (!hlsUrl.isNullOrBlank()) {
                    return@runCatching AppleCanvasArtwork(resultName, resultArtist, albumId, hlsUrl)
                }
            }

            included?.firstOrNull {
                it.obj()?.get("id").str() == albumId
            }?.obj()?.get("attributes").obj()
                ?.get("editorialVideo").obj()
                ?.let { editorialVideo ->
                    val hlsUrl = extractEditorialVideoUrl(editorialVideo, preferredAspect)
                    if (!hlsUrl.isNullOrBlank()) {
                        return@runCatching AppleCanvasArtwork(resultName, resultArtist, albumId, hlsUrl)
                    }
                }

            if (index == 0) {
                fetchMotionArtwork(
                    albumId = albumId,
                    storefront = storefront,
                    fallbackArtist = resultArtist,
                    titleOverride = resultName,
                    artistOverride = resultArtist,
                    preferredAspect = preferredAspect,
                )?.let { return@runCatching it }
            }
        }
        null
    }.onFailure { error ->
        if (error is CancellationException) throw error
        Timber.tag("AppleCanvas").d(error, "Search canvas lookup failed")
    }.getOrNull()

    private suspend fun fetchMotionArtwork(
        albumId: String,
        storefront: String,
        fallbackArtist: String?,
        titleOverride: String?,
        artistOverride: String?,
        preferredAspect: CanvasAspectPreference,
    ): AppleCanvasArtwork? = runCatching {
        val url = "$AMP_BASE_URL/v1/catalog/$storefront/albums/$albumId".toHttpUrl().newBuilder()
            .addQueryParameter("extend", "editorialVideo")
            .build()

        val root = executeJson(appleRequest(url.toString())) ?: return@runCatching null
        val album = root["data"].arr()?.firstOrNull()?.obj() ?: return@runCatching null
        val attributes = album["attributes"].obj() ?: return@runCatching null
        val editorialVideo = attributes["editorialVideo"].obj() ?: return@runCatching null
        val hlsUrl = extractEditorialVideoUrl(editorialVideo, preferredAspect) ?: return@runCatching null

        AppleCanvasArtwork(
            title = titleOverride ?: attributes["name"].str(),
            artist = artistOverride ?: attributes["artistName"].str() ?: fallbackArtist,
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

    private fun appleRequest(url: String): Request =
        Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $APPLE_MUSIC_TOKEN")
            .header("Origin", "https://music.apple.com")
            .header("Referer", "https://music.apple.com/")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/147 Mobile Safari/537.36")
            .build()

    private fun extractEditorialVideoUrl(
        editorialVideo: JsonObject,
        preferredAspect: CanvasAspectPreference,
    ): String? {
        val assets = when (preferredAspect) {
            CanvasAspectPreference.TALL -> listOf("motionDetailTall", "motionDetailSquare", "motionDetailRaw", "motionDetailStatic")
            CanvasAspectPreference.SQUARE -> listOf("motionDetailSquare", "motionDetailTall", "motionDetailRaw", "motionDetailStatic")
            CanvasAspectPreference.RAW -> listOf("motionDetailRaw", "motionDetailSquare", "motionDetailTall", "motionDetailStatic")
            CanvasAspectPreference.AUTO -> listOf("motionDetailSquare", "motionDetailTall", "motionDetailRaw", "motionDetailStatic")
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

    private fun String.cleanForMatch(): String =
        lowercase(Locale.ROOT)
            .replace(Regex("""\s*\(.*?\)"""), "")
            .replace(Regex("""\s*-\s*.*"""), "")
            .trim()

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
