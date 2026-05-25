/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils.spotify

import com.metrolist.innertube.models.Album
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YTItem
import com.metrolist.innertube.pages.HomePage
import com.metrolist.innertube.pages.SearchSummary
import com.metrolist.innertube.pages.SearchSummaryPage
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.providers.ExternalPlaylistPage
import com.metrolist.music.providers.ProviderIsrc
import com.metrolist.music.providers.SpotifyHomeFeedParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.Cookie
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.util.Base64
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

data class SpotifyCanvasMedia(
    val url: String,
    val headers: Map<String, String>,
)

data class SpotifyMixMetadata(
    val bpm: Float? = null,
    val keySignature: String? = null,
    val timeSignature: Int? = null,
)

private data class SpotifyArtistPageMetadata(
    val imageUrl: String?,
    val statsText: String?,
)

data class SpotifyAccountInfo(
    val name: String,
    val thumbnailUrl: String?,
)

fun normalizeSpotifyCookieInput(input: String): String? {
    val trimmedInput =
        input
            .trim()
            .removePrefix("Cookie:")
            .removePrefix("cookie:")
            .trim()
            .trim(';')
    if (trimmedInput.isBlank()) return null

    return canonicalizeSpotifyCookies(listOf(trimmedInput)) ?: normalizeRawSpDc(trimmedInput)
}

fun mergeSpotifyCookieInputs(inputs: Iterable<String>): String? = canonicalizeSpotifyCookies(inputs)

fun isSpotifyCookieConfigured(value: String): Boolean = normalizeSpotifyCookieInput(value) != null

fun hasSpotifyPersonalizationCookie(value: String): Boolean =
    extractSpotifyCookieValue(value, "sp_t") != null

fun extractSpotifyCookieValue(
    cookie: String,
    name: String,
): String? =
    parseCookiePairs(cookie)
        .lastOrNull { it.name.equals(name, ignoreCase = true) }
        ?.value

private data class SpotifyCookiePair(
    val name: String,
    val value: String,
)

private val SpotifyCookieNameRegex = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")
private val CookieAttributeNames =
    setOf(
        "domain",
        "expires",
        "max-age",
        "path",
        "priority",
        "samesite",
        "secure",
        "httponly",
    )

private fun canonicalizeSpotifyCookies(inputs: Iterable<String>): String? {
    val cookies = linkedMapOf<String, String>()
    inputs
        .flatMap(::parseCookiePairs)
        .forEach { pair ->
            cookies[pair.name] = pair.value
        }

    val spDc =
        cookies.entries
            .firstOrNull { it.key.equals("sp_dc", ignoreCase = true) }
            ?: return null

    val ordered = linkedMapOf<String, String>()
    ordered[spDc.key] = spDc.value
    cookies.entries
        .firstOrNull { it.key.equals("sp_t", ignoreCase = true) }
        ?.let { ordered[it.key] = it.value }
    cookies.forEach { (name, value) ->
        ordered.putIfAbsent(name, value)
    }

    return ordered.entries.joinToString("; ") { (name, value) -> "$name=$value" }
}

private fun normalizeRawSpDc(input: String): String? =
    input
        .takeUnless {
            it.contains(';') ||
                it.contains('\n') ||
                it.contains('\r') ||
                it.contains(' ') ||
                it.contains('=')
        }?.trim()
        ?.trim('"')
        ?.takeIf { it.isNotBlank() }
        ?.let { "sp_dc=$it" }

private fun parseCookiePairs(input: String): List<SpotifyCookiePair> {
    val header =
        input
            .trim()
            .removePrefix("Cookie:")
            .removePrefix("cookie:")
            .trim()
            .trim(';')
    if (header.isBlank()) return emptyList()

    return header
        .split(';', '\n', '\r')
        .mapNotNull { part ->
            val trimmed = part.trim()
            val separator = trimmed.indexOf('=')
            if (separator <= 0) return@mapNotNull null

            val name = trimmed.substring(0, separator).trim()
            val value =
                trimmed
                    .substring(separator + 1)
                    .trim()
                    .trim('"')

            SpotifyCookiePair(
                name = name,
                value = value,
            ).takeIf {
                it.name.matches(SpotifyCookieNameRegex) &&
                    it.name.lowercase() !in CookieAttributeNames &&
                    it.value.isNotBlank()
            }
        }
}

object SpotifyCanvasClient {
    private const val DEVICE_AUTH_URL = "https://accounts.spotify.com/oauth2/device/authorize"
    private const val DEVICE_TOKEN_URL = "https://accounts.spotify.com/api/token"
    private const val DEVICE_RESOLVE_URL = "https://accounts.spotify.com/pair/api/resolve"
    private const val DEVICE_CLIENT_ID = "65b708073fc0480ea92a077233ca87bd"
    private const val DEVICE_GRANT_TYPE = "urn:ietf:params:oauth:grant-type:device_code"
    private const val DEVICE_SCOPE =
        "app-remote-control,playlist-modify,playlist-modify-private,playlist-modify-public," +
            "playlist-read,playlist-read-collaborative,playlist-read-private,streaming," +
            "transfer-auth-session,ugc-image-upload,user-follow-modify,user-follow-read," +
            "user-library-modify,user-library-read,user-modify,user-modify-playback-state," +
            "user-modify-private,user-personalized,user-read-birthdate," +
            "user-read-currently-playing,user-read-email,user-read-play-history," +
            "user-read-playback-position,user-read-playback-state,user-read-private," +
            "user-read-recently-played,user-top-read"

    private const val SERVER_TIME_URL = "https://open.spotify.com/api/server-time"
    private const val WEB_TOKEN_URL = "https://open.spotify.com/api/token"
    private const val NUANCE_GIST_URL = "https://api.github.com/gists/22ed9c6ba463899e933427f7de1f0eef"
    private const val WEB_REFERER = "https://open.spotify.com/"
    private const val WEB_ORIGIN = "https://open.spotify.com"
    private const val SPOTIFY_WEBGATE_URL = "https://spclient.wg.spotify.com/"
    private const val SPOTIFY_CANVAZ_URL = "https://spclient.wg.spotify.com/canvaz-cache/v0/canvases"
    private const val SPOTIFY_LEGACY_GRAPHQL_URL = "https://api-partner.spotify.com/pathfinder/v1/query"
    private const val CASITA_HOME_PATH = "casita/v1/home"
    private const val CASITA_FEEDS_PATH = "casita/v1/feeds"
    private const val CASITA_DEFAULT_HOME_FEED_ID = "default"
    private const val CASITA_PAGE_LAYOUT_PATH = "casita/v1-beta/page-layout"
    private const val CASITA_SLOT_CONTENT_PATH = "casita/v1-beta/slot-content"
    private const val SPOTIFY_IMAGE_CDN_URL = "https://i.scdn.co/image/"
    private const val WEB_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
    private const val SPOTIFY_ANDROID_USER_AGENT = "Spotify/9.1.48.1663 Android/35 (Pixel 7)"
    private const val SPOTIFY_CANVAZ_USER_AGENT = "Spotify/9.0.34.593 iOS/18.4 (iPhone15,3)"
    private const val DESKTOP_USER_AGENT = "Spotify/126600447 Win32_x86_64/0 (PC laptop)"
    private const val DESKTOP_WEB_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"
    private const val CACHE_TTL_MS = 6 * 60 * 60 * 1000L
    private const val CANVAS_MISS_CACHE_TTL_MS = 5 * 60 * 1000L
    private const val PAGE_CACHE_TTL_MS = 90_000L
    private const val GRAPH_HASH_CACHE_TTL_MS = 24 * 60 * 60 * 1000L
    private const val PLAYLIST_TRACK_PAGE_SIZE = 50
    private const val ALBUM_TRACK_PAGE_SIZE = 50
    private const val PLAYLIST_TRACK_SAFETY_LIMIT = 10_000
    private const val SPOTIFY_SUSPICIOUS_PLAYLIST_PAGE_SIZE = 30
    private const val LIKED_TRACKS_OPEN_LIMIT = 1_000
    private const val SPOTIFY_HOME_SECTION_LIMIT = 10
    private const val SPOTIFY_HOME_GRAPHQL_SECTION_LIMIT = 20
    private const val SPOTIFY_HOME_GRAPHQL_HASH =
        "d62af2714f2623c923cc9eeca4b9545b4363abaa9188a9e94e2b63b823419a2c"
    private const val GRAPH_PLAYLIST_PAGE_SIZE = 100
    private const val SPOTIFY_FETCH_PLAYLIST_METADATA_HASH =
        "a65e12194ed5fc443a1cdebed5fabe33ca5b07b987185d63c72483867ad13cb4"
    private const val SPOTIFY_DECORATE_CONTEXT_TRACKS_HASH =
        "383de00240775c39a6afe0b1055dc562b2a3930894201f9762f3fc32a74971c7"
    private const val SPOTIFY_GET_ALBUM_NAME_AND_TRACKS_HASH =
        "8628ad33de3267d7bef516c76a746979a5f98891a2c9eaff3dfec828abdcd983"
    private const val SPOTIFY_GET_ARTIST_NAME_AND_TRACKS_HASH =
        "0adaf1a1a8a94c7ed095639c4d9456d2b1cfac16ac511d5dd2b01b6dd89f748a"
    private const val LIBRARY_ITEM_PAGE_SIZE = 50
    private const val LIBRARY_ITEM_SAFETY_LIMIT = 1_000
    private const val WEB_PLAYER_URL = "https://open.spotify.com/"
    private val CASITA_SLOT_TYPES = setOf(1, 2, 3)
    private val CASITA_EAGERLOAD_COMPONENT_TYPES =
        listOf(2, 3, 4, 6, 7, 8, 11, 12, 13, 15, 16, 17, 18, 23, 24, 25, 27, 28, 31, 33, 35, 36, 37, 39)
    private val CASITA_EAGERLOAD_EXTENSION_KINDS = listOf(8, 9, 10)
    private val CASITA_EAGERLOAD_QUERY = spotifyCasitaEagerloadQuery()
    private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    private val PROTOBUF_MEDIA_TYPE = "application/x-www-form-urlencoded".toMediaType()
    private val SPOTIFY_HOME_URI_REGEX =
        Regex(
            """^spotify:(track|album|artist|playlist|collection):[A-Za-z0-9:_-]+$""",
            RegexOption.IGNORE_CASE,
        )
    private val SPOTIFY_OPEN_URL_REGEX =
        Regex(
            """https?://open\.spotify\.com/(track|album|artist|playlist)/([A-Za-z0-9]{22})""",
            RegexOption.IGNORE_CASE,
        )
    private val SPOTIFY_BASE62_ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray()
    private val WEB_PLAYER_SCRIPT_REGEX = Regex("""<script[^>]+src="([^"]+)"""")
    private val WEBPACK_CHUNK_MAP_REGEX = Regex("""\{(?:\d+:"[^"]+",?)+\}""")
    private val WEBPACK_CHUNK_ID_REGEX = Regex("""(\d+):""")
    private val NEXT_DATA_REGEX =
        Regex(
            """<script id="__NEXT_DATA__" type="application/json"[^>]*>(.*?)</script>""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )
    private val INITIAL_STATE_REGEX =
        Regex(
            """<script id="initialState" type="text/plain"[^>]*>(.*?)</script>""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )

    private val json = Json { ignoreUnknownKeys = true }
    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    private val tokenMutex = Mutex()
    private var token: String? = null
    private var tokenExpiryMs = 0L
    private var activeCookie: String? = null
    private val webTokenMutex = Mutex()
    private var webToken: String? = null
    private var webTokenExpiryMs = 0L
    private var activeWebCookie: String? = null
    private var cachedNuance: SpotifyNuance? = null
    private val graphHashMutex = Mutex()
    private val graphHashCache = ConcurrentHashMap<String, CachedString>()
    private val homePageCache = ConcurrentHashMap<String, CachedValue<HomePage>>()
    private val libraryPageCache = ConcurrentHashMap<String, CachedValue<HomePage>>()
    private val externalPlaylistCache = ConcurrentHashMap<String, CachedValue<ExternalPlaylistPage>>()
    private val recommendationCache = ConcurrentHashMap<String, CachedValue<List<SongItem>>>()
    private val artistPageMetadataCache = ConcurrentHashMap<String, CachedValue<SpotifyArtistPageMetadata>>()

    suspend fun resolveSearch(query: String, cookie: String): String? {
        return searchTracks(query, cookie).firstOrNull()?.uri
    }

    suspend fun resolveSearchSummaryPage(
        query: String,
        cookie: String,
    ): SearchSummaryPage? {
        if (query.isBlank()) return SearchSummaryPage(emptyList())
        val normalizedCookie = normalizeSpotifyCookieInput(cookie) ?: return null

        runCatching {
            resolveSearchSummaryPageFromGraphQl(query, normalizedCookie)
        }.onSuccess { page ->
            if (page.summaries.isNotEmpty()) return page
        }.onFailure { error ->
            Timber.w(error, "Spotify internal search failed; retrying public web API")
        }

        return resolveSearchSummaryPageFromWebApi(query, normalizedCookie)
    }

    private suspend fun resolveSearchSummaryPageFromGraphQl(
        query: String,
        normalizedCookie: String,
    ): SearchSummaryPage {
        val root =
            postGraphQl<JsonObject>(
                operation = "searchDesktop",
                variables =
                    buildJsonObject {
                        put("searchTerm", query)
                        put("offset", 0)
                        put("limit", 12)
                        put("numberOfTopResults", 5)
                        put("includeAudiobooks", true)
                        put("includePreReleases", true)
                        put("includeLocalConcertsField", false)
                        put("includeArtistHasConcertsField", false)
                    },
                cookie = normalizedCookie,
                tokenProvider = ::ensureToken,
            )

        return SearchSummaryPage(
            buildList {
                addSpotifySummary(
                    "Songs",
                    root.spotifySearchItems("tracksV2", "tracks")
                        .mapNotNull { it.spotifyWrappedData()?.toSpotifyInitialStatePlaylistSong() },
                )
                addSpotifySummary(
                    "Albums",
                    root.spotifySearchItems("albumsV2", "albums")
                        .mapNotNull { it.spotifyWrappedData()?.toSpotifyGraphAlbumItem() },
                )
                addSpotifySummary(
                    "Artists",
                    root.spotifySearchItems("artists")
                        .mapNotNull { it.spotifyWrappedData()?.toSpotifyGraphArtistItem() },
                )
                addSpotifySummary(
                    "Playlists",
                    root.spotifySearchItems("playlists")
                        .mapNotNull { it.spotifyWrappedData()?.toSpotifyGraphPlaylistItem() },
                )
            },
        )
    }

    private suspend fun resolveSearchSummaryPageFromWebApi(
        query: String,
        normalizedCookie: String,
    ): SearchSummaryPage {
        val root =
            spotifyApiGet(
                url =
                    "https://api.spotify.com/v1/search"
                        .toHttpUrl()
                        .newBuilder()
                        .addQueryParameter("q", query)
                        .addQueryParameter("type", "track,album,artist,playlist")
                        .addQueryParameter("limit", "12")
                        .addQueryParameter("market", "from_token")
                        .build(),
                normalizedCookie = normalizedCookie,
                operation = "Spotify search",
            )

        return SearchSummaryPage(
            buildList {
                addSpotifySummary(
                    "Songs",
                    root.obj("tracks")
                        ?.array("items")
                        .orEmpty()
                        .mapNotNull { it.obj?.toSpotifyPlaylistSong() },
                )
                addSpotifySummary(
                    "Albums",
                    root.obj("albums")
                        ?.array("items")
                        .orEmpty()
                        .mapNotNull { it.obj?.toSpotifyAlbumItem() },
                )
                addSpotifySummary(
                    "Artists",
                    root.obj("artists")
                        ?.array("items")
                        .orEmpty()
                        .mapNotNull { it.obj?.toSpotifyArtistItem() },
                )
                addSpotifySummary(
                    "Playlists",
                    root.obj("playlists")
                        ?.array("items")
                        .orEmpty()
                        .mapNotNull { it.obj?.toSpotifyPlaylistItem() },
                )
            },
        )
    }

    private data class CachedString(
        val value: String,
        val cachedAt: Long,
    )

    private data class CachedValue<T>(
        val value: T,
        val cachedAt: Long,
    )

    private class SpotifyApiException(
        val statusCode: Int,
        message: String,
    ) : IllegalStateException(message)

    private fun spotifyCacheKey(
        cookie: String,
        vararg parts: String,
    ): String =
        buildString {
            append(cookie.hashCode())
            parts.forEach { part ->
                append('|')
                append(part)
            }
        }

    private fun <T> ConcurrentHashMap<String, CachedValue<T>>.fresh(key: String): T? =
        get(key)
            ?.takeIf { System.currentTimeMillis() - it.cachedAt < PAGE_CACHE_TTL_MS }
            ?.value

    private fun <T> ConcurrentHashMap<String, CachedValue<T>>.putFresh(
        key: String,
        value: T,
    ) {
        put(key, CachedValue(value, System.currentTimeMillis()))
    }

    private data class Expectation(
        val title: String,
        val artists: List<String>,
        val album: String?,
        val durationMs: Long?,
        val isrc: String?,
    ) {
        val key: String
            get() =
                listOf(
                    isrc.orEmpty(),
                    normalizeForMatch(title),
                    artists.joinToString("|") { normalizeForMatch(it) },
                    normalizeForMatch(album.orEmpty()),
                    durationMs?.toString().orEmpty(),
                ).joinToString("::")
    }

    private val trackUriCache = ConcurrentHashMap<String, CachedString>()
    private val trackIsrcCache = ConcurrentHashMap<String, CachedString>()
    private val canvasUrlCache = ConcurrentHashMap<String, CachedString>()
    private val audioFeaturesCache = ConcurrentHashMap<String, CachedMixMetadata>()

    private data class CachedMixMetadata(
        val value: SpotifyMixMetadata?,
        val cachedAt: Long,
    )

    suspend fun resolveBackground(
        mediaMetadata: MediaMetadata,
        cookie: String,
    ): SpotifyCanvasMedia? {
        if (mediaMetadata.isEpisode || mediaMetadata.isVideoSong) return null
        val normalizedCookie = normalizeSpotifyCookieInput(cookie) ?: return null
        val trackUri =
            mediaMetadata.id.spotifyTrackUri()
                ?: buildExpectation(mediaMetadata)?.let { resolveTrackUri(it, normalizedCookie) }
                ?: return null
        val canvasUrl = resolveCanvas(trackUri, normalizedCookie) ?: return null
        return SpotifyCanvasMedia(
            url = canvasUrl,
            headers = buildCanvasHeaders(trackUri),
        )
    }

    suspend fun resolveMixMetadata(
        mediaMetadata: MediaMetadata,
        cookie: String,
    ): SpotifyMixMetadata? {
        if (mediaMetadata.isEpisode || mediaMetadata.isVideoSong) return null
        val normalizedCookie = normalizeSpotifyCookieInput(cookie) ?: return null
        val trackUri =
            mediaMetadata.id.spotifyTrackUri()
                ?: buildExpectation(mediaMetadata)?.let { resolveTrackUri(it, normalizedCookie) }
                ?: return null

        return resolveAudioFeatures(trackUri, normalizedCookie)
    }

    suspend fun resolveTrackIsrc(
        trackIdOrUri: String,
        cookie: String,
    ): String? {
        val normalizedCookie = normalizeSpotifyCookieInput(cookie) ?: return null
        val trackId = spotifyEntityId(trackIdOrUri, "track") ?: return null
        val now = System.currentTimeMillis()
        trackIsrcCache[trackId]
            ?.takeIf { now - it.cachedAt < CACHE_TTL_MS }
            ?.let { return it.value.ifBlank { null } }

        val isrc =
            runCatching {
                spotifyApiGet(
                    url =
                        "https://api.spotify.com/v1/tracks/$trackId"
                            .toHttpUrl()
                            .newBuilder()
                            .addQueryParameter("market", "from_token")
                            .build(),
                    normalizedCookie = normalizedCookie,
                    operation = "Spotify track ISRC",
                ).obj("external_ids")
                    ?.string("isrc")
                    ?.let(ProviderIsrc::normalize)
            }.onFailure { error ->
                Timber.w(error, "Spotify track ISRC lookup failed for %s", trackId)
            }.getOrNull()

        trackIsrcCache[trackId] = CachedString(isrc.orEmpty(), now)
        return isrc
    }

    private fun cacheTrackIsrc(
        trackId: String,
        isrc: String?,
    ) {
        val normalizedIsrc = isrc?.let(ProviderIsrc::normalize) ?: return
        trackIsrcCache[trackId] = CachedString(normalizedIsrc, System.currentTimeMillis())
    }

    suspend fun resolveTrackUriForMix(
        mediaMetadata: MediaMetadata,
        cookie: String,
    ): String? {
        if (mediaMetadata.isEpisode || mediaMetadata.isVideoSong) return null
        mediaMetadata.id.spotifyTrackUri()?.let { return it }
        val normalizedCookie = normalizeSpotifyCookieInput(cookie) ?: return null
        val expectation = buildExpectation(mediaMetadata) ?: return null
        return resolveTrackUri(expectation, normalizedCookie)
    }

    suspend fun resolveAutoplayRecommendations(
        mediaMetadata: MediaMetadata,
        cookie: String,
        context: List<MediaMetadata> = emptyList(),
        title: String? = null,
        limit: Int = 25,
    ): List<SongItem> {
        if (mediaMetadata.isEpisode || mediaMetadata.isVideoSong || limit <= 0) return emptyList()
        val normalizedCookie = normalizeSpotifyCookieInput(cookie) ?: return emptyList()
        val seedUri =
            mediaMetadata.id.spotifyTrackUri()
                ?: buildExpectation(mediaMetadata)?.let { resolveTrackUri(it, normalizedCookie) }
                ?: return emptyList()
        val contextUris =
            buildList {
                for (metadata in context.asReversed()) {
                    if (size >= 12) break
                    if (metadata.isEpisode || metadata.isVideoSong) continue
                    val uri =
                        metadata.id.spotifyTrackUri()
                            ?: buildExpectation(metadata)?.let { resolveTrackUri(it, normalizedCookie) }
                            ?: continue
                    if (uri !in this) add(uri)
                }
            }

        return resolveAutoplayRecommendations(
            seedUri = seedUri,
            contextUris = (listOf(seedUri) + contextUris).distinct(),
            normalizedCookie = normalizedCookie,
            title = title,
            limit = limit,
        )
    }

    private suspend fun resolveAutoplayRecommendations(
        seedUri: String,
        contextUris: List<String>,
        normalizedCookie: String,
        title: String?,
        limit: Int,
    ): List<SongItem> {
        val seedId = seedUri.spotifyTrackId() ?: return emptyList()
        val trackIds =
            contextUris
                .mapNotNull { it.spotifyTrackId() }
                .ifEmpty { listOf(seedId) }
                .toSet()
        val skipTrackIds = trackIds + seedId
        val targetLimit = limit.coerceIn(1, 50)
        val cacheKey =
            spotifyCacheKey(
                normalizedCookie,
                "autoplay",
                seedId,
                contextUris.joinToString(","),
                targetLimit.toString(),
            )
        recommendationCache.fresh(cacheKey)?.let { return it.take(targetLimit) }

        val candidates = mutableListOf<SongItem>()

        suspend fun appendRecommendations(
            source: String,
            block: suspend () -> List<SongItem>,
        ) {
            if (candidates.size >= targetLimit) return
            val results =
                runCatching { block() }
                    .onFailure { error ->
                        Timber.w(error, "Spotify recommendation source failed: %s", source)
                    }.getOrDefault(emptyList())

            if (results.isNotEmpty()) {
                val existingTrackIds = candidates.mapNotNullTo(mutableSetOf()) { it.id.spotifyTrackId() }
                val fresh =
                    results
                        .asSequence()
                        .filterNot { it.id.spotifyTrackId() in skipTrackIds }
                        .distinctBy { it.id.spotifyTrackId() ?: it.id }
                        .filter { item ->
                            val trackId = item.id.spotifyTrackId()
                            trackId == null || existingTrackIds.add(trackId)
                        }
                        .take(targetLimit - candidates.size)
                        .toList()
                candidates += fresh
            }
        }

        appendRecommendations("playlist extender") {
            resolvePlaylistExtenderRecommendations(
                seedUri = seedUri,
                trackIds = trackIds,
                normalizedCookie = normalizedCookie,
                title = title,
                limit = targetLimit,
            )
        }
        appendRecommendations("assisted curation") {
            resolveAssistedCurationRecommendations(
                seedUri = seedUri,
                normalizedCookie = normalizedCookie,
                limit = targetLimit,
            )
        }
        appendRecommendations("assisted curation search") {
            resolveAssistedCurationSearchRecommendations(
                seedUri = seedUri,
                normalizedCookie = normalizedCookie,
                limit = targetLimit,
            )
        }
        appendRecommendations("radio apollo") {
            resolveRadioApolloRecommendations(
                seedUri = seedUri,
                normalizedCookie = normalizedCookie,
                limit = targetLimit,
            )
        }
        appendRecommendations("external integration recs") {
            resolveExternalIntegrationRecommendations(
                seedUri = seedUri,
                normalizedCookie = normalizedCookie,
                limit = targetLimit,
            )
        }
        appendRecommendations("recommendations api") {
            resolveSpotifyWebRecommendations(
                seedId = seedId,
                trackIds = trackIds,
                normalizedCookie = normalizedCookie,
                limit = targetLimit,
            )
        }
        appendRecommendations("related artists") {
            resolveRelatedArtistRecommendations(
                seedId = seedId,
                normalizedCookie = normalizedCookie,
                limit = targetLimit,
            )
        }
        appendRecommendations("taste fallback") {
            resolveTasteFallbackRecommendations(
                normalizedCookie = normalizedCookie,
                limit = targetLimit,
            )
        }

        return candidates
            .asSequence()
            .filterNot { it.id.spotifyTrackId() in skipTrackIds }
            .distinctBy { it.id.spotifyTrackId() ?: it.id }
            .take(targetLimit)
            .toList()
            .also { recommendationCache.putFresh(cacheKey, it) }
    }

    private suspend fun resolvePlaylistExtenderRecommendations(
        seedUri: String,
        trackIds: Set<String>,
        normalizedCookie: String,
        title: String?,
        limit: Int,
    ): List<SongItem> {
        val response =
            withContext(Dispatchers.IO) {
                val requestBody =
                    json
                        .encodeToString(
                            SpotifyPlaylistExtenderRequest(
                                numResults = limit.coerceIn(1, 50),
                                trackSkipIds = trackIds,
                                trackIds = trackIds,
                                title = title,
                            ),
                        ).toRequestBody(JSON_MEDIA_TYPE)
                val request =
                    Request
                        .Builder()
                        .url("https://spclient.wg.spotify.com/playlistextender/ft/v2/assist-curation")
                        .header("User-Agent", WEB_USER_AGENT)
                        .header("Accept", "application/json")
                        .header("App-Platform", "WebPlayer")
                        .header("Referer", WEB_REFERER)
                        .header("Origin", WEB_ORIGIN)
                        .header("Cookie", normalizedCookie)
                        .header("Authorization", "Bearer ${ensureToken(normalizedCookie)}")
                        .post(requestBody)
                        .build()

                client.newCall(request).execute().use { response ->
                    json.decodeFromString<SpotifyPlaylistExtenderResponse>(
                        response.requireBody("Spotify playlist extender recommendations"),
                    )
                }
            }

        return response
            .recommendedTracks
            .mapNotNull { it.toSongItem() }
            .filterNot { it.id.equals(seedUri, ignoreCase = true) }
            .distinctBy { it.id }
            .take(limit)
    }

    private suspend fun resolveAssistedCurationRecommendations(
        seedUri: String,
        normalizedCookie: String,
        limit: Int,
    ): List<SongItem> {
        val paths =
            listOf(
                "assisted-curation/v1/recommendations/item/uri",
                "assisted-curation/v1/recommendations/curation/uri",
            )
        return buildList {
            for (path in paths) {
                if (size >= limit) break
                val page =
                    runCatching {
                        spotifySpClientGet(
                            url =
                                SPOTIFY_WEBGATE_URL
                                    .toHttpUrl()
                                    .newBuilder()
                                    .addPathSegments(path)
                                    .addQueryParameter("uri", seedUri)
                                    .addQueryParameter("limit", limit.coerceIn(1, 50).toString())
                                    .addQueryParameter("market", "from_token")
                                    .build(),
                            normalizedCookie = normalizedCookie,
                            operation = "Spotify assisted curation recommendations",
                        )
                    }.onFailure { error ->
                        Timber.w(error, "Spotify assisted curation endpoint failed: %s", path)
                    }.getOrNull()

                page
                    ?.spotifyTrackSongs()
                    .orEmpty()
                    .forEach { song ->
                        if (size < limit && none { it.id == song.id }) add(song)
                    }
            }
        }
    }

    private suspend fun resolveAssistedCurationSearchRecommendations(
        seedUri: String,
        normalizedCookie: String,
        limit: Int,
    ): List<SongItem> {
        val paths =
            listOf(
                "assisted-curation/v1/search/entity/uri",
                "assisted-curation/v1/search/uri",
            )
        return buildList {
            for (path in paths) {
                if (size >= limit) break
                val page =
                    runCatching {
                        spotifySpClientGet(
                            url =
                                SPOTIFY_WEBGATE_URL
                                    .toHttpUrl()
                                    .newBuilder()
                                    .addPathSegments(path)
                                    .addQueryParameter("uri", seedUri)
                                    .addQueryParameter("entity_uri", seedUri)
                                    .addQueryParameter("context", "spotify:assisted-curation?context=$seedUri")
                                    .addQueryParameter("limit", limit.coerceIn(1, 50).toString())
                                    .addQueryParameter("market", "from_token")
                                    .build(),
                            normalizedCookie = normalizedCookie,
                            operation = "Spotify assisted curation search recommendations",
                        )
                    }.onFailure { error ->
                        Timber.w(error, "Spotify assisted curation search endpoint failed: %s", path)
                    }.getOrNull()

                page
                    ?.spotifyTrackSongs()
                    .orEmpty()
                    .forEach { song ->
                        if (size < limit && none { it.id == song.id }) add(song)
                    }
            }
        }
    }

    private suspend fun resolveRadioApolloRecommendations(
        seedUri: String,
        normalizedCookie: String,
        limit: Int,
    ): List<SongItem> {
        val paths = listOf("radio-apollo/v5/all", "radio-apollo/v3/all")
        val seedUris =
            listOf(
                seedUri,
                seedUri.spotifyTrackId()?.let { trackId -> "spotify:station:track:$trackId" },
            ).filterNotNull()
                .distinct()
        return buildList {
            for (path in paths) {
                for (radioSeedUri in seedUris) {
                    if (size >= limit) break
                    val page =
                        runCatching {
                            spotifySpClientGet(
                                url =
                                    SPOTIFY_WEBGATE_URL
                                        .toHttpUrl()
                                        .newBuilder()
                                        .addPathSegments(path)
                                        .addQueryParameter("uri", radioSeedUri)
                                        .addQueryParameter("seed_uri", radioSeedUri)
                                        .addQueryParameter("count", limit.coerceIn(1, 50).toString())
                                        .addQueryParameter("limit", limit.coerceIn(1, 50).toString())
                                        .addQueryParameter("market", "from_token")
                                        .build(),
                                normalizedCookie = normalizedCookie,
                                operation = "Spotify radio recommendations",
                            )
                        }.onFailure { error ->
                            Timber.w(error, "Spotify radio endpoint failed: %s", path)
                        }.getOrNull()

                    page
                        ?.spotifyTrackSongs()
                        .orEmpty()
                        .forEach { song ->
                            if (size < limit && none { it.id == song.id }) add(song)
                        }
                }
            }
        }
    }

    private suspend fun resolveExternalIntegrationRecommendations(
        seedUri: String,
        normalizedCookie: String,
        limit: Int,
    ): List<SongItem> {
        val page =
            spotifySpClientGet(
                url =
                    SPOTIFY_WEBGATE_URL
                        .toHttpUrl()
                        .newBuilder()
                        .addPathSegments("external-integration-recs/v2/personalized-recommendations")
                        .addQueryParameter("uri", seedUri)
                        .addQueryParameter("seed_uri", seedUri)
                        .addQueryParameter("limit", limit.coerceIn(1, 50).toString())
                        .addQueryParameter("market", "from_token")
                        .build(),
                normalizedCookie = normalizedCookie,
                operation = "Spotify external integration recommendations",
            )

        return page.spotifyTrackSongs(limit)
    }

    private suspend fun resolveSpotifyWebRecommendations(
        seedId: String,
        trackIds: Set<String>,
        normalizedCookie: String,
        limit: Int,
    ): List<SongItem> {
        val seedTracks =
            (listOf(seedId) + trackIds)
                .distinct()
                .take(5)
                .joinToString(",")
                .takeIf { it.isNotBlank() }
                ?: return emptyList()

        return spotifyApiGet(
            url =
                "https://api.spotify.com/v1/recommendations"
                    .toHttpUrl()
                    .newBuilder()
                    .addQueryParameter("limit", limit.coerceIn(1, 50).toString())
                    .addQueryParameter("market", "from_token")
                    .addQueryParameter("seed_tracks", seedTracks)
                    .build(),
            normalizedCookie = normalizedCookie,
            operation = "Spotify recommendations",
        ).array("tracks")
            .orEmpty()
            .mapNotNull { it.obj?.toSpotifyPlaylistSong() }
    }

    private suspend fun resolveRelatedArtistRecommendations(
        seedId: String,
        normalizedCookie: String,
        limit: Int,
    ): List<SongItem> {
        val seedTrack =
            spotifyApiGet(
                url =
                    "https://api.spotify.com/v1/tracks/$seedId"
                        .toHttpUrl()
                        .newBuilder()
                        .addQueryParameter("market", "from_token")
                        .build(),
                normalizedCookie = normalizedCookie,
                operation = "Spotify seed track",
            )
        val seedArtistId =
            seedTrack
                .array("artists")
                .orEmpty()
                .firstNotNullOfOrNull { it.obj?.string("id") }
                ?: return emptyList()
        val relatedArtistIds =
            runCatching {
                spotifyApiGet(
                    url =
                        "https://api.spotify.com/v1/artists/$seedArtistId/related-artists"
                            .toHttpUrl(),
                    normalizedCookie = normalizedCookie,
                    operation = "Spotify related artists",
                ).array("artists")
                    .orEmpty()
                    .mapNotNull { it.obj?.string("id") }
            }.onFailure { error ->
                Timber.w(error, "Spotify related artists fallback failed")
            }.getOrDefault(emptyList())

        return buildList {
            for (artistId in (listOf(seedArtistId) + relatedArtistIds).distinct().take(3)) {
                if (size >= limit) break
                val tracks =
                    runCatching {
                        spotifyApiGet(
                            url =
                                "https://api.spotify.com/v1/artists/$artistId/top-tracks"
                                    .toHttpUrl()
                                    .newBuilder()
                                    .addQueryParameter("market", "from_token")
                                    .build(),
                            normalizedCookie = normalizedCookie,
                            operation = "Spotify artist top tracks",
                        ).array("tracks")
                            .orEmpty()
                            .mapNotNull { it.obj?.toSpotifyPlaylistSong() }
                    }.onFailure { error ->
                        Timber.w(error, "Spotify artist top tracks failed")
                    }.getOrDefault(emptyList())
                addAll(tracks)
            }
        }.distinctBy { it.id.spotifyTrackId() ?: it.id }
            .take(limit)
    }

    private suspend fun resolveTasteFallbackRecommendations(
        normalizedCookie: String,
        limit: Int,
    ): List<SongItem> {
        val topTracks =
            runCatching {
                spotifyApiGet(
                    url =
                        "https://api.spotify.com/v1/me/top/tracks"
                            .toHttpUrl()
                            .newBuilder()
                            .addQueryParameter("limit", limit.coerceIn(1, 50).toString())
                            .addQueryParameter("time_range", "short_term")
                            .addQueryParameter("market", "from_token")
                            .build(),
                    normalizedCookie = normalizedCookie,
                    operation = "Spotify recommendation top tracks",
                ).array("items")
                    .orEmpty()
                    .mapNotNull { it.obj?.toSpotifyPlaylistSong() }
            }.onFailure { error ->
                Timber.w(error, "Spotify recommendation top tracks failed")
            }.getOrDefault(emptyList())

        if (topTracks.isNotEmpty()) return topTracks

        return spotifyApiGet(
            url =
                "https://api.spotify.com/v1/me/player/recently-played"
                    .toHttpUrl()
                    .newBuilder()
                    .addQueryParameter("limit", limit.coerceIn(1, 50).toString())
                    .build(),
            normalizedCookie = normalizedCookie,
            operation = "Spotify recommendation recently played",
        ).array("items")
            .orEmpty()
            .mapNotNull { it.obj?.obj("track")?.toSpotifyPlaylistSong() }
    }

    suspend fun resolveHomePage(cookie: String): HomePage? {
        val normalizedCookie = normalizeSpotifyCookieInput(cookie) ?: return null
        val cacheKey = spotifyCacheKey(normalizedCookie, "home:spotube-gql")
        homePageCache.fresh(cacheKey)?.let { return it }

        return runCatching {
            resolveHomePageFromSpotubeGraphQl(normalizedCookie)
        }.onFailure { error ->
            Timber.w(error, "Spotify GraphQL home request failed")
        }.getOrNull()
            ?.takeIf { it.sections.isNotEmpty() }
            ?.let { page ->
                homePageCache.putFresh(cacheKey, page)
                page
            }
    }

    private suspend fun resolveHomePageFromSpotubeGraphQl(normalizedCookie: String): HomePage {
        val spTCookie =
            extractSpotifyCookieValue(normalizedCookie, "sp_t")
                ?: error("Spotify home requires the sp_t cookie")
        val root =
            postGraphQl<JsonObject>(
                operation = "home",
                variables =
                    buildJsonObject {
                        put("timeZone", TimeZone.getDefault().id)
                        put("sp_t", spTCookie)
                        put("facet", "")
                        put("sectionItemsLimit", SPOTIFY_HOME_GRAPHQL_SECTION_LIMIT)
                    },
                cookie = normalizedCookie,
                hashOverride = SPOTIFY_HOME_GRAPHQL_HASH,
                tokenProvider = ::ensureWebToken,
            )
        val page = SpotifyHomeFeedParser.parse(root)
        check(page.sections.isNotEmpty()) { "Spotify GraphQL home returned no renderable sections" }
        return page
    }

    private suspend fun resolveHomePageFromCasita(normalizedCookie: String): HomePage {
        runCatching {
            resolveHomePageFromCasitaDefaultFeed(normalizedCookie)
        }.onFailure { error ->
            Timber.w(error, "Spotify Casita v1 home feed failed")
        }.getOrNull()
            ?.takeIf { it.sections.isNotEmpty() }
            ?.let { return it }

        return resolveHomePageFromCasitaSlots(normalizedCookie)
    }

    private suspend fun resolveHomePageFromCasitaDefaultFeed(normalizedCookie: String): HomePage {
        val defaultPage = resolveCasitaHomeFeed(normalizedCookie, CASITA_DEFAULT_HOME_FEED_ID)
        val sections = defaultPage.sections
        check(sections.isNotEmpty()) { "Spotify Casita v1 home returned no renderable sections" }

        return HomePage(chips = null, sections = sections)
    }

    private suspend fun resolveCasitaHomeFeed(
        normalizedCookie: String,
        feedId: String,
    ): HomePage {
        val root =
            parseProtoMessage(
                spotifyCasitaGet(
                    url =
                        SPOTIFY_WEBGATE_URL
                            .toHttpUrl()
                            .newBuilder()
                            .addPathSegments(CASITA_HOME_PATH)
                            .addPathSegment(feedId)
                            .addEncodedQueryParameter("eagerload", CASITA_EAGERLOAD_QUERY)
                            .addQueryParameter("timezone", TimeZone.getDefault().id)
                            .addQueryParameter("mobile-only-dsa-enabled", "false")
                            .addQueryParameter("locale", Locale.getDefault().toLanguageTag().ifBlank { "en-US" })
                            .addQueryParameter("slot-based-loading-enabled", "true")
                            .build(),
                    normalizedCookie = normalizedCookie,
                    cacheControl = "no-cache",
                    headers =
                        mapOf(
                            "X-Target-Device" to "",
                            "X-Incremental-Home-Enabled" to "true",
                            "X-Is-Tablet" to "false",
                        ),
                    operation = "Spotify Casita home $feedId",
                ),
            )

        val metadata =
            root.firstMessage(2)
                ?.let(::parseCasitaMetadata)
                .orEmpty()
        val sections =
            root.firstMessage(1)
                ?.messages(1)
                .orEmpty()
                .mapNotNull { section -> section.toCasitaHomeSection(metadata) }
                .distinctBy { section -> section.title to section.items.joinToString("|") { it.id } }
        check(sections.isNotEmpty()) { "Spotify Casita v1 home $feedId returned no renderable sections" }

        return HomePage(chips = null, sections = sections)
    }

    private suspend fun resolveHomePageFromCasitaSlots(normalizedCookie: String): HomePage {
        val layout =
            parseProtoMessage(
                spotifyCasitaGet(
                    url =
                        SPOTIFY_WEBGATE_URL
                            .toHttpUrl()
                            .newBuilder()
                            .addPathSegments(CASITA_PAGE_LAYOUT_PATH)
                            .build(),
                    normalizedCookie = normalizedCookie,
                    operation = "Spotify Casita page layout",
                ),
            )
        val slotTypes =
            layout.messages(1)
                .mapNotNull { slot -> slot.int(1) }
                .filter { it in CASITA_SLOT_TYPES }
                .distinct()
        check(slotTypes.isNotEmpty()) { "Spotify Casita page layout returned no home slots" }

        val metadata = linkedMapOf<String, SpotifyCasitaEntity>()
        val sections = mutableListOf<HomePage.Section>()
        for (slotType in slotTypes) {
            val response =
                parseProtoMessage(
                    spotifyCasitaGet(
                        url =
                            SPOTIFY_WEBGATE_URL
                                .toHttpUrl()
                                .newBuilder()
                                .addPathSegments(CASITA_SLOT_CONTENT_PATH)
                                .addQueryParameter("slotType", slotType.toString())
                                .addEncodedQueryParameter("eagerload", CASITA_EAGERLOAD_QUERY)
                                .build(),
                        normalizedCookie = normalizedCookie,
                        cacheControl = "no-cache",
                        operation = "Spotify Casita slot $slotType",
                    ),
                )
            response.firstMessage(4)
                ?.let(::parseCasitaMetadata)
                ?.forEach { (uri, entity) -> metadata[uri] = entity }

            response.messages(1)
                .mapNotNull { section -> section.toCasitaHomeSection(metadata) }
                .forEach(sections::add)
        }

        val distinctSections =
            sections.distinctBy { section -> section.title to section.items.joinToString("|") { it.id } }
        check(distinctSections.isNotEmpty()) { "Spotify Casita home returned no renderable sections" }

        return HomePage(chips = null, sections = distinctSections)
    }

    private suspend fun resolveHomePageFromWebApi(normalizedCookie: String): HomePage {
        val sections = mutableListOf<HomePage.Section>()

        val playlists =
            runCatching {
                spotifyApiGet(
                    url =
                        "https://api.spotify.com/v1/me/playlists"
                            .toHttpUrl()
                            .newBuilder()
                            .addQueryParameter("limit", SPOTIFY_HOME_SECTION_LIMIT.toString())
                            .build(),
                    normalizedCookie = normalizedCookie,
                    operation = "Spotify home playlists",
                ).array("items")
                    .orEmpty()
                    .mapNotNull { it.obj?.toSpotifyPlaylistItem() }
            }.onFailure { error ->
                Timber.w(error, "Spotify home playlists failed")
            }.getOrDefault(emptyList())

        val topTracks =
            runCatching {
                spotifyApiGet(
                    url =
                        "https://api.spotify.com/v1/me/top/tracks"
                            .toHttpUrl()
                            .newBuilder()
                            .addQueryParameter("limit", SPOTIFY_HOME_SECTION_LIMIT.toString())
                            .addQueryParameter("time_range", "short_term")
                            .addQueryParameter("market", "from_token")
                            .build(),
                    normalizedCookie = normalizedCookie,
                    operation = "Spotify top tracks",
                ).array("items")
                    .orEmpty()
                    .mapNotNull { it.obj?.toSpotifyPlaylistSong() }
            }.onFailure { error ->
                Timber.w(error, "Spotify top tracks failed")
            }.getOrDefault(emptyList())

        val recentlyPlayed =
            runCatching {
                spotifyApiGet(
                    url =
                        "https://api.spotify.com/v1/me/player/recently-played"
                            .toHttpUrl()
                            .newBuilder()
                            .addQueryParameter("limit", SPOTIFY_HOME_SECTION_LIMIT.toString())
                            .build(),
                    normalizedCookie = normalizedCookie,
                    operation = "Spotify recently played",
                ).array("items")
                    .orEmpty()
                    .mapNotNull { it.obj?.obj("track")?.toSpotifyPlaylistSong() }
                    .distinctBy { it.id }
            }.onFailure { error ->
                Timber.w(error, "Spotify recently played failed")
            }.getOrDefault(emptyList())

        val featuredPlaylists =
            runCatching {
                loadSpotifyFeaturedPlaylists(normalizedCookie)
            }.onFailure { error ->
                Timber.w(error, "Spotify featured playlists failed")
            }.getOrDefault(emptyList())

        val madeForYouItems: List<YTItem> =
            (
                playlists.filter { it.isSpotifyMadeForYouPlaylist() } +
                    featuredPlaylists
            ).map { it as YTItem }
                .distinctBy { it.id }
                .ifEmpty { topTracks.map { it as YTItem } }

        val quickPicks =
            (
                recentlyPlayed.take(4) +
                    madeForYouItems.take(4) +
                    topTracks.take(2)
            ).distinctBy { it.id }

        sections.addSpotifyHomeSection("Good evening", quickPicks)
        sections.addSpotifyHomeSection("Made for you", madeForYouItems)
        sections.addSpotifyHomeSection("Jump back in", recentlyPlayed)
        sections.addSpotifyHomeSection("Your top songs", topTracks)
        sections.addSpotifyHomeSection("Featured playlists", featuredPlaylists)

        return HomePage(chips = null, sections = sections)
    }

    private suspend fun loadSpotifyFeaturedPlaylists(normalizedCookie: String): List<PlaylistItem> =
        spotifyApiGet(
            url =
                "https://api.spotify.com/v1/browse/featured-playlists"
                    .toHttpUrl()
                    .newBuilder()
                    .addQueryParameter("limit", SPOTIFY_HOME_SECTION_LIMIT.toString())
                    .build(),
            normalizedCookie = normalizedCookie,
            operation = "Spotify featured playlists",
        ).obj("playlists")
            ?.array("items")
            .orEmpty()
            .mapNotNull { it.obj?.toSpotifyPlaylistItem() }

    private fun PlaylistItem.isSpotifyMadeForYouPlaylist(): Boolean {
        val normalizedTitle = title.lowercase()
        val spotifyOwner = author?.name?.contains("spotify", ignoreCase = true) == true
        return spotifyOwner &&
            listOf(
                "daily mix",
                "discover weekly",
                "release radar",
                "on repeat",
                "repeat rewind",
                "daylist",
                "blend",
                "radio",
                "mix",
            ).any(normalizedTitle::contains)
    }

    private suspend fun resolveHomePageFromLibraryGraphQl(normalizedCookie: String): HomePage {
        val libraryPage = resolveLibraryPageFromGraphQl(normalizedCookie)
        val sections = libraryPage.sections
        val quickPicks =
            sections
                .flatMap { it.items.take(4) }
                .distinctBy { it.id }
                .take(8)

        return libraryPage.copy(
            sections =
                buildList {
                    addSpotifyHomeSection("Good evening", quickPicks)
                    addAll(sections)
                },
        )
    }

    suspend fun resolveLibraryPage(cookie: String): HomePage? {
        val normalizedCookie = normalizeSpotifyCookieInput(cookie) ?: return null
        val cacheKey = spotifyCacheKey(normalizedCookie, "library")
        libraryPageCache.fresh(cacheKey)?.let { return it }

        runCatching {
            resolveLibraryPageFromGraphQl(normalizedCookie)
        }.onSuccess { page ->
            if (page.sections.isNotEmpty()) {
                libraryPageCache.putFresh(cacheKey, page)
                return page
            }
        }.onFailure { error ->
            Timber.w(error, "Spotify internal library failed; retrying public web API")
        }

        return resolveLibraryPageFromWebApi(normalizedCookie)
            .also { libraryPageCache.putFresh(cacheKey, it) }
    }

    private suspend fun resolveLibraryPageFromGraphQl(normalizedCookie: String): HomePage {
        val sections = mutableListOf<HomePage.Section>()
        var likedSongsPlaylist: PlaylistItem? = null

        runCatching {
            val root = postGraphQl<JsonObject>(
                operation = "fetchLibraryTracks",
                variables =
                    buildJsonObject {
                        put("uri", "spotify:collection:tracks")
                        put("offset", 0)
                        put("limit", 50)
                    },
                cookie = normalizedCookie,
                tokenProvider = ::ensureToken,
            )
            val items = root.spotifyLibraryTrackItems()
                .mapNotNull { it.toSpotifyInitialStatePlaylistSong() }
            likedSongsPlaylist = spotifyLikedSongsPlaylist(
                songs = items,
                total = root.spotifyLibraryTracksTotal(),
            )
        }.onFailure { error ->
            Timber.w(error, "Spotify internal saved tracks failed")
        }

        runCatching {
            loadSpotifyLibraryV3Items(
                filter = "Playlists",
                normalizedCookie = normalizedCookie,
            ).mapNotNull { it.toSpotifyGraphPlaylistItem() }
        }.onSuccess { items ->
            sections.addSpotifyHomeSection("Spotify playlists", listOfNotNull(likedSongsPlaylist) + items.withoutLikedSongsDuplicate())
        }.onFailure { error ->
            Timber.w(error, "Spotify internal playlists failed")
            sections.addSpotifyHomeSection("Spotify playlists", listOfNotNull(likedSongsPlaylist))
        }

        runCatching {
            loadSpotifyLibraryV3Items(
                filter = "Albums",
                normalizedCookie = normalizedCookie,
            ).mapNotNull { it.toSpotifyGraphAlbumItem() }
        }.onSuccess { items ->
            sections.addSpotifyHomeSection("Saved Spotify albums", items)
        }.onFailure { error ->
            Timber.w(error, "Spotify internal albums failed")
        }

        runCatching {
            loadSpotifyLibraryV3Items(
                filter = "Artists",
                normalizedCookie = normalizedCookie,
            ).mapNotNull { it.toSpotifyGraphArtistItem() }
        }.onSuccess { items ->
            sections.addSpotifyHomeSection("Followed Spotify artists", items)
        }.onFailure { error ->
            Timber.w(error, "Spotify internal artists failed")
        }

        return HomePage(
            chips = null,
            sections = sections,
        )
    }

    private suspend fun loadSpotifyLibraryV3Items(
        filter: String,
        normalizedCookie: String,
    ): List<JsonObject> {
        val items = mutableListOf<JsonObject>()
        var offset = 0

        while (items.size < LIBRARY_ITEM_SAFETY_LIMIT) {
            val pageItems =
                postGraphQl<JsonObject>(
                    operation = "libraryV3",
                    variables = spotifyLibraryV3Variables(
                        filter = filter,
                        offset = offset,
                        limit = minOf(LIBRARY_ITEM_PAGE_SIZE, LIBRARY_ITEM_SAFETY_LIMIT - items.size),
                    ),
                    cookie = normalizedCookie,
                    tokenProvider = ::ensureToken,
                ).spotifyLibraryV3Items()

            if (pageItems.isEmpty()) break

            items += pageItems
            offset += pageItems.size

            if (pageItems.size < LIBRARY_ITEM_PAGE_SIZE) break
        }

        return items.distinctBy { it.spotifyEntityKey() }
    }

    private fun spotifyLibraryV3Variables(
        filter: String,
        offset: Int,
        limit: Int,
    ): JsonObject =
        buildJsonObject {
            put("filters", JsonArray(listOf(JsonPrimitive(filter))))
            put("order", JsonNull)
            put("textFilter", "")
            put("features", JsonArray(listOf(JsonPrimitive("LIKED_SONGS"))))
            put("limit", limit)
            put("offset", offset)
            put("flatten", false)
            put("expandedFolders", JsonArray(emptyList()))
            put("folderUri", JsonNull)
            put("includeFoldersWhenFlattening", true)
            put("withCuration", false)
        }

    private suspend fun resolveLibraryPageFromWebApi(normalizedCookie: String): HomePage {
        val sections = mutableListOf<HomePage.Section>()
        var likedSongsPlaylist: PlaylistItem? = null

        runCatching {
            val root = spotifyApiGet(
                url =
                    "https://api.spotify.com/v1/me/tracks"
                        .toHttpUrl()
                        .newBuilder()
                        .addQueryParameter("limit", "50")
                        .addQueryParameter("market", "from_token")
                        .build(),
                normalizedCookie = normalizedCookie,
                operation = "Spotify saved tracks",
            )
            val items = root.array("items")
                .orEmpty()
                .mapNotNull { it.obj?.obj("track")?.toSpotifyPlaylistSong() }
            likedSongsPlaylist = spotifyLikedSongsPlaylist(
                songs = items,
                total = root.long("total")?.toInt(),
            )
        }.onFailure { error ->
            Timber.w(error, "Spotify library saved tracks failed")
        }

        runCatching {
            spotifyApiGet(
                url =
                    "https://api.spotify.com/v1/me/playlists"
                        .toHttpUrl()
                        .newBuilder()
                        .addQueryParameter("limit", "50")
                        .build(),
                normalizedCookie = normalizedCookie,
                operation = "Spotify playlists",
            ).array("items")
                .orEmpty()
                .mapNotNull { it.obj?.toSpotifyPlaylistItem() }
        }.onSuccess { items ->
            sections.addSpotifyHomeSection("Spotify playlists", listOfNotNull(likedSongsPlaylist) + items.withoutLikedSongsDuplicate())
        }.onFailure { error ->
            Timber.w(error, "Spotify library playlists failed")
            sections.addSpotifyHomeSection("Spotify playlists", listOfNotNull(likedSongsPlaylist))
        }

        runCatching {
            spotifyApiGet(
                url =
                    "https://api.spotify.com/v1/me/albums"
                        .toHttpUrl()
                        .newBuilder()
                        .addQueryParameter("limit", "50")
                        .addQueryParameter("market", "from_token")
                        .build(),
                normalizedCookie = normalizedCookie,
                operation = "Spotify saved albums",
            ).array("items")
                .orEmpty()
                .mapNotNull { it.obj?.obj("album")?.toSpotifyAlbumItem() }
        }.onSuccess { items ->
            sections.addSpotifyHomeSection("Saved Spotify albums", items)
        }.onFailure { error ->
            Timber.w(error, "Spotify library albums failed")
        }

        runCatching {
            spotifyApiGet(
                url =
                    "https://api.spotify.com/v1/me/following"
                        .toHttpUrl()
                        .newBuilder()
                        .addQueryParameter("type", "artist")
                        .addQueryParameter("limit", "50")
                        .build(),
                normalizedCookie = normalizedCookie,
                operation = "Spotify followed artists",
            ).obj("artists")
                ?.array("items")
                .orEmpty()
                .mapNotNull { it.obj?.toSpotifyArtistItem() }
        }.onSuccess { items ->
            sections.addSpotifyHomeSection("Followed Spotify artists", items)
        }.onFailure { error ->
            Timber.w(error, "Spotify library artists failed")
        }

        return HomePage(
            chips = null,
            sections = sections,
        )
    }

    suspend fun resolvePlaylist(
        playlistId: String,
        cookie: String,
    ): ExternalPlaylistPage? {
        val normalizedCookie = normalizeSpotifyCookieInput(cookie) ?: return null
        if (
            playlistId == "collection:tracks" ||
            playlistId == "tracks" ||
            playlistId.equals("spotify:collection:tracks", ignoreCase = true)
        ) {
            return resolveSavedTracksCollection(normalizedCookie)
        }
        val normalizedPlaylistId = spotifyEntityId(playlistId, "playlist") ?: return null
        val cacheKey = spotifyCacheKey(normalizedCookie, "playlist", normalizedPlaylistId)
        externalPlaylistCache.fresh(cacheKey)?.let { return it }

        runCatching {
            resolvePlaylistFromGraphQl(normalizedPlaylistId, normalizedCookie)
        }.onFailure { error ->
            Timber.w(error, "Spotify playlist GraphQL load failed for %s", normalizedPlaylistId)
        }.getOrNull()
            ?.let { page ->
                val expectedSongs = page.playlist.songCountText.spotifySongCount()
                if (page.isCompleteSpotifyPlaylistPage()) {
                    externalPlaylistCache.putFresh(cacheKey, page)
                    return page
                }

                Timber.w(
                    "Spotify playlist GraphQL returned suspicious partial page %d/%s for %s; retrying Web API",
                    page.songs.size,
                    expectedSongs?.toString() ?: "?",
                    normalizedPlaylistId,
                )
            }

        runCatching {
            resolvePlaylistFromWebApi(normalizedPlaylistId, normalizedCookie)
        }.onFailure { error ->
            Timber.w(error, "Spotify playlist Web API load failed for %s", normalizedPlaylistId)
        }.getOrNull()
            ?.let { page ->
                if (page.isCompleteSpotifyPlaylistPage()) {
                    externalPlaylistCache.putFresh(cacheKey, page)
                    return page
                }

                Timber.w(
                    "Spotify playlist Web API returned partial page %d/%s for %s; retrying web page",
                    page.songs.size,
                    page.playlist.songCountText.spotifySongCount()?.toString() ?: "?",
                    normalizedPlaylistId,
                )
            }

        val page = resolvePlaylistFromWebPage(normalizedPlaylistId, normalizedCookie) ?: return null
        if (page.isCompleteSpotifyPlaylistPage()) {
            externalPlaylistCache.putFresh(cacheKey, page)
        } else {
            Timber.w(
                "Spotify playlist web page returned unresolved partial page %d/%s for %s",
                page.songs.size,
                page.playlist.songCountText.spotifySongCount()?.toString() ?: "?",
                normalizedPlaylistId,
            )
        }
        return page.takeUnless { it.isSuspiciousThirtySongSpotifyPlaylistPage() }
    }

    suspend fun resolvePlaylistPage(
        playlistId: String,
        cookie: String,
        offset: Int = 0,
        limit: Int = PLAYLIST_TRACK_PAGE_SIZE,
    ): ExternalPlaylistPage? {
        val normalizedCookie = normalizeSpotifyCookieInput(cookie) ?: return null
        val normalizedPlaylistId = spotifyEntityId(playlistId, "playlist") ?: return null
        return runCatching {
            resolvePlaylistPageFromGraphQl(
                playlistId = normalizedPlaylistId,
                normalizedCookie = normalizedCookie,
                offset = offset.coerceAtLeast(0),
                limit = limit.coerceIn(1, GRAPH_PLAYLIST_PAGE_SIZE),
            )
        }.onFailure { error ->
            Timber.w(error, "Spotify playlist GraphQL page load failed for %s at %d", normalizedPlaylistId, offset)
        }.getOrNull()
            ?.takeIf { it.songs.isNotEmpty() && !it.isSuspiciousThirtySongSpotifyPlaylistPage() }
            ?: runCatching {
                resolvePlaylistPageFromWebApi(
                    playlistId = normalizedPlaylistId,
                    normalizedCookie = normalizedCookie,
                    offset = offset.coerceAtLeast(0),
                    limit = limit.coerceIn(1, GRAPH_PLAYLIST_PAGE_SIZE),
                )
            }.onFailure { error ->
                Timber.w(error, "Spotify playlist page load failed for %s at %d", normalizedPlaylistId, offset)
            }.getOrNull()
                ?.takeIf { it.songs.isNotEmpty() && !it.isSuspiciousThirtySongSpotifyPlaylistPage() }
            ?: runCatching {
                resolvePlaylistPageFromMobileApi(
                    playlistId = normalizedPlaylistId,
                    normalizedCookie = normalizedCookie,
                    offset = offset.coerceAtLeast(0),
                    limit = limit.coerceIn(1, 50),
                )
            }.onFailure { error ->
                Timber.w(error, "Spotify playlist mobile page load failed for %s at %d", normalizedPlaylistId, offset)
            }.getOrNull()
                ?.takeIf { it.songs.isNotEmpty() && !it.isSuspiciousThirtySongSpotifyPlaylistPage() }
            ?: if (offset.coerceAtLeast(0) == 0) {
                resolvePlaylist(normalizedPlaylistId, normalizedCookie)
            } else {
                null
            }
    }

    private suspend fun resolvePlaylistPageFromGraphQl(
        playlistId: String,
        normalizedCookie: String,
        offset: Int,
        limit: Int,
    ): ExternalPlaylistPage? {
        runCatching {
            resolvePlaylistPageFromGraphQl(
                playlistId = playlistId,
                normalizedCookie = normalizedCookie,
                offset = offset,
                limit = limit,
                tokenProvider = ::ensureWebToken,
            )
        }.getOrNull()
            ?.let { return it }

        return runCatching {
            resolvePlaylistPageFromGraphQl(
                playlistId = playlistId,
                normalizedCookie = normalizedCookie,
                offset = offset,
                limit = limit,
                tokenProvider = ::ensureToken,
            )
        }.getOrNull()
    }

    private suspend fun resolvePlaylistPageFromGraphQl(
        playlistId: String,
        normalizedCookie: String,
        offset: Int,
        limit: Int,
        tokenProvider: suspend (String) -> String,
    ): ExternalPlaylistPage? {
        val playlistUri = "spotify:playlist:$playlistId"
        val root =
            postPlaylistMetadataGraphQl(
                playlistUri = playlistUri,
                offset = offset.coerceAtLeast(0),
                limit = limit.coerceIn(1, GRAPH_PLAYLIST_PAGE_SIZE),
                cookie = normalizedCookie,
                tokenProvider = tokenProvider,
            )

        val playlist = root.obj("data")?.obj("playlistV2") ?: return null
        val content = playlist.obj("content")
        val trackPage = resolveSpotifyGraphPlaylistTrackPage(content, normalizedCookie, tokenProvider)
        val pagingInfo = content?.obj("pagingInfo")
        val pageOffset = pagingInfo?.long("offset")?.toInt() ?: offset
        val total =
            content?.spotifyGraphContentTotal()
                ?: playlist.spotifyPlaylistTotal()
        val nextOffset =
            pagingInfo?.long("nextOffset")?.toInt()
                ?.takeIf { it > pageOffset }
                ?: spotifyPagedNextOffset(
                    nextUrl = null,
                    pageOffset = pageOffset,
                    itemCount = trackPage.rawItemCount,
                    total = total,
                )

        return ExternalPlaylistPage(
            playlist =
                PlaylistItem(
                    id = playlistUri,
                    title = playlist.string("name") ?: "Spotify playlist",
                    author =
                        playlist
                            .obj("ownerV2")
                            ?.obj("data")
                            ?.let { owner ->
                                owner.string("name")
                                    ?: owner.string("displayName")
                                    ?: owner.string("username")
                            }?.let { Artist(name = it, id = null) },
                    songCountText = (total ?: trackPage.songs.size).takeIf { it > 0 }?.let { "$it songs" },
                    thumbnail = playlist.spotifyInitialStateImageUrl() ?: trackPage.songs.firstOrNull()?.thumbnail,
                    playEndpoint = null,
                    shuffleEndpoint = null,
                    radioEndpoint = null,
                ),
            songs = trackPage.songs,
            continuation = nextOffset?.toString(),
        )
    }

    private suspend fun resolvePlaylistPageFromMobileApi(
        playlistId: String,
        normalizedCookie: String,
        offset: Int,
        limit: Int,
    ): ExternalPlaylistPage =
        withContext(Dispatchers.IO) {
            val page =
                spotifySpClientGet(
                    url =
                        "https://spclient.wg.spotify.com/playlist/v2/playlist/$playlistId/items"
                            .toHttpUrl()
                            .newBuilder()
                            .addQueryParameter("market", "from_token")
                            .addQueryParameter("limit", limit.toString())
                            .addQueryParameter("offset", offset.toString())
                            .build(),
                    normalizedCookie = normalizedCookie,
                    operation = "Spotify mobile playlist tracks page",
                )
            val songs = page.spotifyMobilePlaylistTracks()
            val total = page.spotifyMobilePlaylistTotal()
            val pageOffset = page.long("offset")?.toInt() ?: offset
            val nextOffset =
                spotifyPagedNextOffset(
                    nextUrl = null,
                    pageOffset = pageOffset,
                    itemCount = songs.size,
                    total = total,
                )

            ExternalPlaylistPage(
                playlist =
                    PlaylistItem(
                        id = "spotify:playlist:$playlistId",
                        title = "Spotify playlist",
                        author = Artist(name = "Spotify", id = null),
                        songCountText = total?.let { "$it songs" },
                        thumbnail = songs.firstOrNull()?.thumbnail,
                        playEndpoint = null,
                        shuffleEndpoint = null,
                        radioEndpoint = null,
                    ),
                songs = songs,
                continuation = nextOffset?.toString(),
            )
        }

    suspend fun resolveAlbum(
        albumId: String,
        cookie: String,
    ): ExternalPlaylistPage? {
        val normalizedCookie = normalizeSpotifyCookieInput(cookie) ?: return null
        val normalizedAlbumId = spotifyEntityId(albumId, "album") ?: return null
        return runCatching {
            resolveAlbumFromGraphQl(normalizedAlbumId, normalizedCookie)
        }.onFailure { error ->
            Timber.w(error, "Spotify album GraphQL load failed for %s", normalizedAlbumId)
        }.getOrNull()
            ?.takeIf { it.songs.isNotEmpty() }
            ?: runCatching {
                resolveAlbumFromWebApi(normalizedAlbumId, normalizedCookie)
            }.onFailure { error ->
                Timber.w(error, "Spotify album Web API load failed for %s", normalizedAlbumId)
            }.getOrNull()
            ?: runCatching {
                resolveAlbumFromBatchWebApi(normalizedAlbumId, normalizedCookie)
            }.onFailure { error ->
                Timber.w(error, "Spotify album batch Web API load failed for %s", normalizedAlbumId)
            }.getOrNull()
    }

    suspend fun resolveAlbumPage(
        albumId: String,
        cookie: String,
        offset: Int = 0,
        limit: Int = PLAYLIST_TRACK_PAGE_SIZE,
    ): ExternalPlaylistPage? {
        val normalizedCookie = normalizeSpotifyCookieInput(cookie) ?: return null
        val normalizedAlbumId = spotifyEntityId(albumId, "album") ?: return null
        return runCatching {
            resolveAlbumPageFromGraphQl(
                albumId = normalizedAlbumId,
                normalizedCookie = normalizedCookie,
                offset = offset.coerceAtLeast(0),
                limit = limit.coerceIn(1, GRAPH_PLAYLIST_PAGE_SIZE),
            )
        }.onFailure { error ->
            Timber.w(error, "Spotify album GraphQL page load failed for %s at %d", normalizedAlbumId, offset)
        }.getOrNull()
            ?.takeIf { it.songs.isNotEmpty() }
            ?: runCatching {
                resolveAlbumPageFromWebApi(
                    albumId = normalizedAlbumId,
                    normalizedCookie = normalizedCookie,
                    offset = offset.coerceAtLeast(0),
                    limit = limit.coerceIn(1, 50),
                )
            }.onFailure { error ->
                Timber.w(error, "Spotify album page load failed for %s at %d", normalizedAlbumId, offset)
            }.getOrElse {
                if (offset.coerceAtLeast(0) == 0) {
                    resolveAlbum(normalizedAlbumId, normalizedCookie)
                } else {
                    null
                }
            }
    }

    suspend fun resolveArtist(
        artistId: String,
        cookie: String,
    ): ExternalPlaylistPage? {
        val normalizedCookie = normalizeSpotifyCookieInput(cookie) ?: return null
        val normalizedArtistId = spotifyEntityId(artistId, "artist") ?: return null
        return runCatching {
            resolveArtistFromGraphQl(normalizedArtistId, normalizedCookie)
        }.onFailure { error ->
            Timber.w(error, "Spotify artist GraphQL load failed for %s", normalizedArtistId)
        }.getOrNull()
            ?.takeIf { it.songs.isNotEmpty() }
            ?: runCatching {
                resolveArtistFromWebApi(normalizedArtistId, normalizedCookie)
            }.onFailure { error ->
                Timber.w(error, "Spotify artist Web API load failed for %s", normalizedArtistId)
            }.getOrNull()
            ?: runCatching {
                resolveArtistFromBatchWebApi(normalizedArtistId, normalizedCookie)
            }.onFailure { error ->
                Timber.w(error, "Spotify artist batch Web API load failed for %s", normalizedArtistId)
            }.getOrNull()
    }

    private fun tokenOverlapScore(
        expected: String,
        candidate: String,
        maxScore: Int,
    ): Int {
        val expectedTokens = expected.split(' ').filter { it.length > 2 }.toSet()
        if (expectedTokens.isEmpty()) return 0
        val candidateTokens = candidate.split(' ').filter { it.length > 2 }.toSet()
        val overlap = expectedTokens.intersect(candidateTokens).size
        return (overlap * maxScore) / expectedTokens.size.coerceAtLeast(1)
    }

    private suspend fun resolvePlaylistPageFromWebApi(
        playlistId: String,
        normalizedCookie: String,
        offset: Int,
        limit: Int,
    ): ExternalPlaylistPage =
        withContext(Dispatchers.IO) {
            val playlistRoot =
                spotifyApiGet(
                    url =
                        "https://api.spotify.com/v1/playlists/$playlistId"
                            .toHttpUrl()
                            .newBuilder()
                            .addQueryParameter("fields", "id,name,owner(id,display_name),images,tracks(total)")
                            .build(),
                    normalizedCookie = normalizedCookie,
                    operation = "Spotify playlist metadata",
                )
            val page =
                spotifyApiGet(
                    url =
                        "https://api.spotify.com/v1/playlists/$playlistId/tracks"
                            .toHttpUrl()
                            .newBuilder()
                            .addQueryParameter("limit", limit.toString())
                            .addQueryParameter("offset", offset.toString())
                            .build(),
                    normalizedCookie = normalizedCookie,
                    operation = "Spotify playlist tracks page",
                )
            val total = page.long("total")?.toInt() ?: playlistRoot.obj("tracks")?.long("total")?.toInt()
            val pageOffset = page.long("offset")?.toInt() ?: offset
            val rawItems = page.array("items").orEmpty()
            val songs =
                rawItems
                    .mapNotNull { item ->
                        item.obj?.obj("track")
                            ?: item.obj?.obj("item")
                    }.mapNotNull { it.toSpotifyPlaylistSong() }
            val hasMore =
                page.string("next") != null ||
                    total?.let { expected -> expected > pageOffset + rawItems.size } == true
            val nextOffset =
                spotifyPagedNextOffset(
                    nextUrl = page.string("next"),
                    pageOffset = pageOffset,
                    itemCount = rawItems.size,
                    total = total,
                ).takeIf { hasMore || total == null }

            ExternalPlaylistPage(
                playlist =
                    playlistRoot.toSpotifyPlaylistItem()
                        ?: PlaylistItem(
                            id = "spotify:playlist:$playlistId",
                            title = playlistRoot.string("name") ?: "Spotify playlist",
                            author = playlistRoot.obj("owner")?.string("display_name")?.let { Artist(name = it, id = null) },
                            songCountText = total?.let { "$it songs" },
                            thumbnail = playlistRoot.spotifyWebApiImageUrl() ?: songs.firstOrNull()?.thumbnail,
                            playEndpoint = null,
                            shuffleEndpoint = null,
                            radioEndpoint = null,
                        ),
                songs = songs,
                continuation = nextOffset?.toString(),
            )
        }

    private suspend fun resolveAlbumFromGraphQl(
        albumId: String,
        normalizedCookie: String,
    ): ExternalPlaylistPage? {
        val firstPage =
            resolveAlbumPageFromGraphQl(
                albumId = albumId,
                normalizedCookie = normalizedCookie,
                offset = 0,
                limit = GRAPH_PLAYLIST_PAGE_SIZE,
            ) ?: return null
        val songs = firstPage.songs.toMutableList()
        var nextOffset = firstPage.continuation?.toIntOrNull()

        while (nextOffset != null && songs.size < PLAYLIST_TRACK_SAFETY_LIMIT) {
            val page =
                resolveAlbumPageFromGraphQl(
                    albumId = albumId,
                    normalizedCookie = normalizedCookie,
                    offset = nextOffset,
                    limit = GRAPH_PLAYLIST_PAGE_SIZE,
                ) ?: break
            if (page.songs.isEmpty()) break
            songs += page.songs
            nextOffset = page.continuation?.toIntOrNull()?.takeIf { it > nextOffset }
        }

        return firstPage.copy(
            songs = songs.distinctBy { it.id },
            continuation = nextOffset?.toString(),
        )
    }

    private suspend fun resolveAlbumPageFromGraphQl(
        albumId: String,
        normalizedCookie: String,
        offset: Int,
        limit: Int,
    ): ExternalPlaylistPage? {
        runCatching {
            resolveAlbumPageFromGraphQl(
                albumId = albumId,
                normalizedCookie = normalizedCookie,
                offset = offset,
                limit = limit,
                tokenProvider = ::ensureWebToken,
            )
        }.getOrNull()
            ?.let { return it }

        return runCatching {
            resolveAlbumPageFromGraphQl(
                albumId = albumId,
                normalizedCookie = normalizedCookie,
                offset = offset,
                limit = limit,
                tokenProvider = ::ensureToken,
            )
        }.getOrNull()
    }

    private suspend fun resolveAlbumPageFromGraphQl(
        albumId: String,
        normalizedCookie: String,
        offset: Int,
        limit: Int,
        tokenProvider: suspend (String) -> String,
    ): ExternalPlaylistPage? =
        withContext(Dispatchers.IO) {
            val albumUri = "spotify:album:$albumId"
            val root =
                postGraphQlWithHashFallback(
                    operation = "getAlbumNameAndTracks",
                    variables =
                        buildJsonObject {
                            put("uri", albumUri)
                            put("offset", offset.coerceAtLeast(0))
                            put("limit", limit.coerceIn(1, GRAPH_PLAYLIST_PAGE_SIZE))
                        },
                    cookie = normalizedCookie,
                    hashOverride = SPOTIFY_GET_ALBUM_NAME_AND_TRACKS_HASH,
                    tokenProvider = tokenProvider,
                )
            val albumRoot = root.obj("data")?.obj("albumUnion") ?: return@withContext null
            val trackPage = albumRoot.obj("tracksV2")
            val rawItems = trackPage?.array("items").orEmpty()
            val trackIds =
                rawItems.mapNotNull { item ->
                    item.obj?.obj("track")?.spotifyGraphTrackId()
                }
            val hydratedTracks = hydrateSpotifyTrackIds(trackIds, normalizedCookie, tokenProvider)
            val albumTitle = albumRoot.string("name") ?: "Spotify album"
            val albumThumbnail = albumRoot.spotifyInitialStateImageUrl()
            val albumModel = Album(name = albumTitle, id = albumUri)
            val songs =
                trackIds.mapNotNull { trackId ->
                    hydratedTracks[trackId]?.let { song ->
                        song.copy(
                            album = song.album ?: albumModel,
                            thumbnail = song.thumbnail.ifBlank { albumThumbnail.orEmpty() },
                        )
                    }
                }
            val total = trackPage?.long("totalCount")?.toInt() ?: trackPage?.long("total")?.toInt()
            val pageOffset = offset.coerceAtLeast(0)
            val nextOffset =
                spotifyPagedNextOffset(
                    nextUrl = null,
                    pageOffset = pageOffset,
                    itemCount = rawItems.size,
                    total = total,
                )
            val artists =
                albumRoot
                    .obj("artists")
                    ?.array("items")
                    .orEmpty()
                    .mapNotNull { artist ->
                        val obj = artist.obj ?: return@mapNotNull null
                        val name =
                            obj.obj("profile")?.string("name")
                                ?: obj.string("name")
                                ?: return@mapNotNull null
                        Artist(
                            name = name,
                            id = obj.string("uri") ?: obj.string("id")?.let { "spotify:artist:$it" },
                        )
                    }

            ExternalPlaylistPage(
                playlist =
                    PlaylistItem(
                        id = albumUri,
                        title = albumTitle,
                        author = artists.joinToString(", ") { it.name }.takeIf { it.isNotBlank() }?.let { Artist(name = it, id = null) },
                        songCountText = (total ?: songs.size).takeIf { it > 0 }?.let { "$it songs" },
                        thumbnail = albumThumbnail ?: songs.firstOrNull()?.thumbnail,
                        playEndpoint = null,
                        shuffleEndpoint = null,
                        radioEndpoint = null,
                    ),
                songs = songs,
                continuation = nextOffset?.toString(),
            )
        }

    private suspend fun resolveAlbumPageFromWebApi(
        albumId: String,
        normalizedCookie: String,
        offset: Int,
        limit: Int,
    ): ExternalPlaylistPage =
        withContext(Dispatchers.IO) {
            val albumRoot =
                spotifyApiGet(
                    url =
                        "https://api.spotify.com/v1/albums/$albumId"
                            .toHttpUrl()
                            .newBuilder()
                            .addQueryParameter("market", "from_token")
                            .build(),
                    normalizedCookie = normalizedCookie,
                    operation = "Spotify album",
                )
            val page =
                spotifyApiGet(
                    url =
                        "https://api.spotify.com/v1/albums/$albumId/tracks"
                            .toHttpUrl()
                            .newBuilder()
                            .addQueryParameter("market", "from_token")
                            .addQueryParameter("limit", limit.toString())
                            .addQueryParameter("offset", offset.toString())
                            .build(),
                    normalizedCookie = normalizedCookie,
                    operation = "Spotify album tracks page",
                )

            val totalTracks =
                page.long("total")?.toInt()
                    ?: albumRoot.long("total_tracks")?.toInt()
            val pageOffset = page.long("offset")?.toInt() ?: offset
            val pageLimit = page.long("limit")?.toInt()?.takeIf { it > 0 } ?: limit
            val songs =
                page.array("items")
                    .orEmpty()
                    .mapNotNull { it.obj?.toSpotifyAlbumSong(albumRoot) }
                    .distinctBy { it.id }
            val nextOffset =
                spotifyPagedNextOffset(
                    nextUrl = page.string("next"),
                    pageOffset = pageOffset,
                    itemCount = songs.size,
                    total = totalTracks,
                )
            val albumTitle = albumRoot.string("name") ?: "Spotify album"
            val artists =
                albumRoot
                    .array("artists")
                    .orEmpty()
                    .mapNotNull { artist ->
                        val obj = artist.obj ?: return@mapNotNull null
                        val name = obj.string("name") ?: return@mapNotNull null
                        Artist(
                            name = name,
                            id = obj.string("id")?.let { "spotify:artist:$it" },
                        )
                    }

            ExternalPlaylistPage(
                playlist =
                    PlaylistItem(
                        id = "spotify:album:$albumId",
                        title = albumTitle,
                        author = artists.joinToString(", ") { it.name }.takeIf { it.isNotBlank() }?.let { Artist(name = it, id = null) },
                        songCountText = (totalTracks ?: songs.size).takeIf { it > 0 }?.let { "$it songs" },
                        thumbnail = albumRoot.spotifyWebApiImageUrl() ?: songs.firstOrNull()?.thumbnail,
                        playEndpoint = null,
                        shuffleEndpoint = null,
                        radioEndpoint = null,
                    ),
                songs = songs,
                continuation = nextOffset?.toString(),
            )
        }

    private suspend fun resolveAlbumFromWebApi(
        albumId: String,
        normalizedCookie: String,
    ): ExternalPlaylistPage =
        withContext(Dispatchers.IO) {
            val albumRoot =
                spotifyApiGet(
                    url =
                        "https://api.spotify.com/v1/albums/$albumId"
                            .toHttpUrl()
                            .newBuilder()
                            .addQueryParameter("market", "from_token")
                            .build(),
                    normalizedCookie = normalizedCookie,
                    operation = "Spotify album",
                )

            val songs = mutableListOf<SongItem>()
            val firstTrackPage = albumRoot.obj("tracks")
            firstTrackPage
                ?.array("items")
                .orEmpty()
                .mapNotNull { it.obj?.toSpotifyAlbumSong(albumRoot) }
                .forEach(songs::add)

            val totalTracks =
                firstTrackPage?.long("total")?.toInt()
                    ?: albumRoot.long("total_tracks")?.toInt()
            var offset = songs.size

            while (
                totalTracks != null &&
                offset < totalTracks &&
                songs.size < PLAYLIST_TRACK_SAFETY_LIMIT
            ) {
                val page =
                    spotifyApiGet(
                        url =
                            "https://api.spotify.com/v1/albums/$albumId/tracks"
                                .toHttpUrl()
                                .newBuilder()
                                .addQueryParameter("market", "from_token")
                                .addQueryParameter("limit", ALBUM_TRACK_PAGE_SIZE.toString())
                                .addQueryParameter("offset", offset.toString())
                                .build(),
                        normalizedCookie = normalizedCookie,
                        operation = "Spotify album tracks",
                    )

                val pageSongs =
                    page
                        .array("items")
                        .orEmpty()
                        .mapNotNull { it.obj?.toSpotifyAlbumSong(albumRoot) }

                if (pageSongs.isEmpty()) break

                songs += pageSongs
                offset += pageSongs.size
            }

            val distinctSongs = songs.distinctBy { it.id }
            val albumTitle = albumRoot.string("name") ?: "Spotify album"
            val artists =
                albumRoot
                    .array("artists")
                    .orEmpty()
                    .mapNotNull { artist ->
                        val obj = artist.obj ?: return@mapNotNull null
                        val name = obj.string("name") ?: return@mapNotNull null
                        Artist(
                            name = name,
                            id = obj.string("id")?.let { "spotify:artist:$it" },
                        )
                    }

            ExternalPlaylistPage(
                playlist =
                    PlaylistItem(
                        id = "spotify:album:$albumId",
                        title = albumTitle,
                        author = artists.joinToString(", ") { it.name }.takeIf { it.isNotBlank() }?.let { Artist(name = it, id = null) },
                        songCountText = (totalTracks ?: distinctSongs.size).takeIf { it > 0 }?.let { "$it songs" },
                        thumbnail = albumRoot.spotifyWebApiImageUrl() ?: distinctSongs.firstOrNull()?.thumbnail,
                        playEndpoint = null,
                        shuffleEndpoint = null,
                        radioEndpoint = null,
                    ),
                songs = distinctSongs,
            )
        }

    private suspend fun resolveAlbumFromBatchWebApi(
        albumId: String,
        normalizedCookie: String,
    ): ExternalPlaylistPage =
        withContext(Dispatchers.IO) {
            val albumRoot =
                spotifyApiGet(
                    url =
                        "https://api.spotify.com/v1/albums"
                            .toHttpUrl()
                            .newBuilder()
                            .addQueryParameter("ids", albumId)
                            .addQueryParameter("market", "from_token")
                            .build(),
                    normalizedCookie = normalizedCookie,
                    operation = "Spotify album batch",
                ).array("albums")
                    .orEmpty()
                    .firstNotNullOfOrNull { it.obj }
                    ?: error("Spotify album batch returned no album")

            val songs = mutableListOf<SongItem>()
            val firstTrackPage = albumRoot.obj("tracks")
            firstTrackPage
                ?.array("items")
                .orEmpty()
                .mapNotNull { it.obj?.toSpotifyAlbumSong(albumRoot) }
                .forEach(songs::add)

            val totalTracks =
                firstTrackPage?.long("total")?.toInt()
                    ?: albumRoot.long("total_tracks")?.toInt()
            var offset = songs.size

            while (
                totalTracks != null &&
                offset < totalTracks &&
                songs.size < PLAYLIST_TRACK_SAFETY_LIMIT
            ) {
                val page =
                    spotifyApiGet(
                        url =
                            "https://api.spotify.com/v1/albums/$albumId/tracks"
                                .toHttpUrl()
                                .newBuilder()
                                .addQueryParameter("market", "from_token")
                                .addQueryParameter("limit", ALBUM_TRACK_PAGE_SIZE.toString())
                                .addQueryParameter("offset", offset.toString())
                                .build(),
                        normalizedCookie = normalizedCookie,
                        operation = "Spotify album batch tracks",
                    )

                val pageSongs =
                    page
                        .array("items")
                        .orEmpty()
                        .mapNotNull { it.obj?.toSpotifyAlbumSong(albumRoot) }

                if (pageSongs.isEmpty()) break

                songs += pageSongs
                offset += pageSongs.size
            }

            val distinctSongs = songs.distinctBy { it.id }
            val albumTitle = albumRoot.string("name") ?: "Spotify album"
            val artists =
                albumRoot
                    .array("artists")
                    .orEmpty()
                    .mapNotNull { artist ->
                        val obj = artist.obj ?: return@mapNotNull null
                        val name = obj.string("name") ?: return@mapNotNull null
                        Artist(
                            name = name,
                            id = obj.string("id")?.let { "spotify:artist:$it" },
                        )
                    }

            ExternalPlaylistPage(
                playlist =
                    PlaylistItem(
                        id = "spotify:album:$albumId",
                        title = albumTitle,
                        author = artists.joinToString(", ") { it.name }.takeIf { it.isNotBlank() }?.let { Artist(name = it, id = null) },
                        songCountText = (totalTracks ?: distinctSongs.size).takeIf { it > 0 }?.let { "$it songs" },
                        thumbnail = albumRoot.spotifyWebApiImageUrl() ?: distinctSongs.firstOrNull()?.thumbnail,
                        playEndpoint = null,
                        shuffleEndpoint = null,
                        radioEndpoint = null,
                    ),
                songs = distinctSongs,
            )
        }

    private suspend fun resolveArtistFromGraphQl(
        artistId: String,
        normalizedCookie: String,
    ): ExternalPlaylistPage? {
        runCatching {
            resolveArtistFromGraphQl(
                artistId = artistId,
                normalizedCookie = normalizedCookie,
                tokenProvider = ::ensureWebToken,
            )
        }.getOrNull()
            ?.let { return it }

        return runCatching {
            resolveArtistFromGraphQl(
                artistId = artistId,
                normalizedCookie = normalizedCookie,
                tokenProvider = ::ensureToken,
            )
        }.getOrNull()
    }

    private suspend fun resolveArtistFromGraphQl(
        artistId: String,
        normalizedCookie: String,
        tokenProvider: suspend (String) -> String,
    ): ExternalPlaylistPage? =
        withContext(Dispatchers.IO) {
            val artistUri = "spotify:artist:$artistId"
            val root =
                postGraphQlWithHashFallback(
                    operation = "getArtistNameAndTracks",
                    variables =
                        buildJsonObject {
                            put("uri", artistUri)
                            put("offset", 0)
                            put("limit", PLAYLIST_TRACK_PAGE_SIZE)
                        },
                    cookie = normalizedCookie,
                    hashOverride = SPOTIFY_GET_ARTIST_NAME_AND_TRACKS_HASH,
                    tokenProvider = tokenProvider,
                )
            val artistRoot = root.obj("data")?.obj("artistUnion") ?: return@withContext null
            val artistName =
                artistRoot.obj("profile")?.string("name")
                    ?: artistRoot.string("name")
                    ?: "Spotify artist"
            val trackIds =
                artistRoot
                    .obj("discography")
                    ?.obj("topTracks")
                    ?.array("items")
                    .orEmpty()
                    .mapNotNull { item -> item.obj?.obj("track")?.spotifyGraphTrackId() }
            val hydratedTracks = hydrateSpotifyTrackIds(trackIds, normalizedCookie, tokenProvider)
            val songs = trackIds.mapNotNull { trackId -> hydratedTracks[trackId] }.distinctBy { it.id }
            val pageMetadata = resolveSpotifyArtistPageMetadata(artistId, normalizedCookie)

            ExternalPlaylistPage(
                playlist =
                    PlaylistItem(
                        id = artistUri,
                        title = artistName,
                        author = Artist(name = "Spotify", id = null),
                        songCountText =
                            pageMetadata?.statsText
                                ?: artistRoot.spotifyArtistStatsText()
                                ?: songs.takeIf { it.isNotEmpty() }?.let { "${it.size} top songs" },
                        thumbnail = pageMetadata?.imageUrl ?: artistRoot.spotifyArtistImageUrl() ?: songs.firstOrNull()?.thumbnail,
                        playEndpoint = null,
                        shuffleEndpoint = null,
                        radioEndpoint = null,
                    ),
                songs = songs,
            )
        }

    private suspend fun resolveArtistFromWebApi(
        artistId: String,
        normalizedCookie: String,
    ): ExternalPlaylistPage =
        withContext(Dispatchers.IO) {
            val artistRoot =
                spotifyApiGet(
                    url =
                        "https://api.spotify.com/v1/artists/$artistId"
                            .toHttpUrl(),
                    normalizedCookie = normalizedCookie,
                    operation = "Spotify artist",
                )
            val artistName = artistRoot.string("name") ?: "Spotify artist"
            val pageMetadata = resolveSpotifyArtistPageMetadata(artistId, normalizedCookie)
            val songs =
                loadSpotifyArtistTracks(
                    artistId = artistId,
                    artistName = artistName,
                    normalizedCookie = normalizedCookie,
                )

            ExternalPlaylistPage(
                playlist =
                    PlaylistItem(
                        id = "spotify:artist:$artistId",
                        title = artistName,
                        author = Artist(name = "Spotify", id = null),
                        songCountText =
                            pageMetadata?.statsText
                                ?: artistRoot.spotifyArtistStatsText()
                                ?: songs.takeIf { it.isNotEmpty() }?.let { "${it.size} top songs" },
                        thumbnail =
                            pageMetadata?.imageUrl
                                ?: artistRoot.spotifyArtistImageUrl()
                                ?: artistRoot.spotifyWebApiImageUrl()
                                ?: songs.firstOrNull()?.thumbnail,
                        playEndpoint = null,
                        shuffleEndpoint = null,
                        radioEndpoint = null,
                    ),
                songs = songs,
            )
        }

    private suspend fun resolveArtistFromBatchWebApi(
        artistId: String,
        normalizedCookie: String,
    ): ExternalPlaylistPage =
        withContext(Dispatchers.IO) {
            val artistRoot =
                spotifyApiGet(
                    url =
                        "https://api.spotify.com/v1/artists"
                            .toHttpUrl()
                            .newBuilder()
                            .addQueryParameter("ids", artistId)
                            .build(),
                    normalizedCookie = normalizedCookie,
                    operation = "Spotify artist batch",
                ).array("artists")
                    .orEmpty()
                    .firstNotNullOfOrNull { it.obj }
                    ?: error("Spotify artist batch returned no artist")
            val artistName = artistRoot.string("name") ?: "Spotify artist"
            val pageMetadata = resolveSpotifyArtistPageMetadata(artistId, normalizedCookie)
            val songs =
                loadSpotifyArtistTracks(
                    artistId = artistId,
                    artistName = artistName,
                    normalizedCookie = normalizedCookie,
                )

            ExternalPlaylistPage(
                playlist =
                    PlaylistItem(
                        id = "spotify:artist:$artistId",
                        title = artistName,
                        author = Artist(name = "Spotify", id = null),
                        songCountText =
                            pageMetadata?.statsText
                                ?: artistRoot.spotifyArtistStatsText()
                                ?: songs.takeIf { it.isNotEmpty() }?.let { "${it.size} top songs" },
                        thumbnail =
                            pageMetadata?.imageUrl
                                ?: artistRoot.spotifyArtistImageUrl()
                                ?: artistRoot.spotifyWebApiImageUrl()
                                ?: songs.firstOrNull()?.thumbnail,
                        playEndpoint = null,
                        shuffleEndpoint = null,
                        radioEndpoint = null,
                    ),
                songs = songs,
            )
        }

    private suspend fun resolveSpotifyArtistPageMetadata(
        artistId: String,
        normalizedCookie: String,
    ): SpotifyArtistPageMetadata? {
        val cacheKey = spotifyCacheKey(normalizedCookie, "artist-page", artistId)
        artistPageMetadataCache.fresh(cacheKey)?.let { return it }

        return withContext(Dispatchers.IO) {
            runCatching {
                val request =
                    Request
                        .Builder()
                        .url("https://open.spotify.com/artist/$artistId")
                        .header("User-Agent", WEB_USER_AGENT)
                        .header("Accept", "text/html,application/xhtml+xml")
                        .header("Referer", WEB_REFERER)
                        .apply {
                            if (normalizedCookie.isNotBlank()) {
                                header("Cookie", normalizedCookie)
                            }
                        }.get()
                        .build()

                client.newCall(request).execute().use { response ->
                    val html = response.requireBody("Spotify artist page")
                    val root = html.spotifyInitialStateJson()
                    val artist =
                        root
                            .obj("entities")
                            ?.obj("items")
                            ?.obj("spotify:artist:$artistId")
                            ?: return@use null
                    SpotifyArtistPageMetadata(
                        imageUrl = artist.spotifyArtistImageUrl(),
                        statsText = artist.spotifyArtistStatsText(),
                    ).takeIf { metadata ->
                        !metadata.imageUrl.isNullOrBlank() || !metadata.statsText.isNullOrBlank()
                    }
                }
            }.onFailure { error ->
                Timber.w(error, "Spotify artist page metadata failed for %s", artistId)
            }.getOrNull()
                ?.also { metadata -> artistPageMetadataCache.putFresh(cacheKey, metadata) }
        }
    }

    private suspend fun loadSpotifyArtistTracks(
        artistId: String,
        artistName: String,
        normalizedCookie: String,
    ): List<SongItem> {
        val topTracks =
            listOf(
                mapOf("market" to "from_token"),
                mapOf("market" to Locale.getDefault().country.takeIf { it.length == 2 }.orEmpty().ifBlank { "US" }),
                emptyMap(),
            ).firstNotNullOfOrNull { query ->
                runCatching {
                    spotifyApiGet(
                        url =
                            "https://api.spotify.com/v1/artists/$artistId/top-tracks"
                                .toHttpUrl()
                                .newBuilder()
                                .apply {
                                    query.forEach { (name, value) -> addQueryParameter(name, value) }
                                }.build(),
                        normalizedCookie = normalizedCookie,
                        operation = "Spotify artist top tracks",
                    ).array("tracks")
                        .orEmpty()
                        .mapNotNull { it.obj?.toSpotifyPlaylistSong() }
                        .distinctBy { it.id }
                        .takeIf { it.isNotEmpty() }
                }.onFailure { error ->
                    Timber.w(error, "Spotify artist top tracks failed for %s with %s", artistId, query)
                }.getOrNull()
            }
        if (!topTracks.isNullOrEmpty()) return topTracks

        return runCatching {
            spotifyApiGet(
                url =
                    "https://api.spotify.com/v1/search"
                        .toHttpUrl()
                        .newBuilder()
                        .addQueryParameter("q", "artist:\"$artistName\"")
                        .addQueryParameter("type", "track")
                        .addQueryParameter("market", "from_token")
                        .addQueryParameter("limit", PLAYLIST_TRACK_PAGE_SIZE.toString())
                        .build(),
                normalizedCookie = normalizedCookie,
                operation = "Spotify artist search tracks",
            ).obj("tracks")
                ?.array("items")
                .orEmpty()
                .mapNotNull { it.obj?.toSpotifyPlaylistSong() }
                .distinctBy { it.id }
        }.onFailure { error ->
            Timber.w(error, "Spotify artist search fallback failed for %s", artistId)
        }.getOrDefault(emptyList())
    }

    private suspend fun resolvePlaylistFromGraphQl(
        playlistId: String,
        normalizedCookie: String,
    ): ExternalPlaylistPage? {
        resolvePlaylistFromGraphQl(
            playlistId = playlistId,
            normalizedCookie = normalizedCookie,
            tokenProvider = ::ensureWebToken,
        )?.let { return it }

        return resolvePlaylistFromGraphQl(
            playlistId = playlistId,
            normalizedCookie = normalizedCookie,
            tokenProvider = ::ensureToken,
        )
    }

    private suspend fun resolvePlaylistFromGraphQl(
        playlistId: String,
        normalizedCookie: String,
        tokenProvider: suspend (String) -> String,
    ): ExternalPlaylistPage? {
        val playlistUri = "spotify:playlist:$playlistId"
        val root =
            postPlaylistMetadataGraphQl(
                playlistUri = playlistUri,
                offset = 0,
                limit = GRAPH_PLAYLIST_PAGE_SIZE,
                cookie = normalizedCookie,
                tokenProvider = tokenProvider,
            )

        val playlist = root.obj("data")?.obj("playlistV2") ?: return null
        val songs = mutableListOf<SongItem>()
        val content = playlist.obj("content")
        val firstTrackPage = resolveSpotifyGraphPlaylistTrackPage(content, normalizedCookie, tokenProvider)
        songs += firstTrackPage.songs

        var totalCount =
            content?.spotifyGraphContentTotal()
                ?: playlist.spotifyPlaylistTotal()
        val initialTotalCount = totalCount
        val firstPagingInfo = content?.obj("pagingInfo")
        val firstPageOffset = firstPagingInfo?.long("offset")?.toInt() ?: 0
        var nextOffset =
            firstPagingInfo?.long("nextOffset")?.toInt()
                ?.takeIf { it > firstPageOffset }
                ?: spotifyPagedNextOffset(
                    nextUrl = null,
                    pageOffset = firstPageOffset,
                    itemCount = firstTrackPage.rawItemCount,
                    total = initialTotalCount,
                )

        while (nextOffset != null && songs.size < PLAYLIST_TRACK_SAFETY_LIMIT) {
            val requestedOffset = nextOffset
            val page =
                postPlaylistMetadataGraphQl(
                    playlistUri = playlistUri,
                    offset = requestedOffset,
                    limit = GRAPH_PLAYLIST_PAGE_SIZE,
                    cookie = normalizedCookie,
                    tokenProvider = tokenProvider,
                )

            val pageContent = page.obj("data")?.obj("playlistV2")?.obj("content") ?: break
            val trackPage = resolveSpotifyGraphPlaylistTrackPage(pageContent, normalizedCookie, tokenProvider)
            if (trackPage.rawItemCount <= 0) break
            if (trackPage.songs.isEmpty() && trackPage.trackIds.isNotEmpty()) break
            songs += trackPage.songs

            val pagingInfo = pageContent.obj("pagingInfo")
            val pageOffset = pagingInfo?.long("offset")?.toInt() ?: requestedOffset
            val candidateOffset = pagingInfo?.long("nextOffset")?.toInt()
            totalCount = totalCount ?: pageContent.spotifyGraphContentTotal()
            val expectedTotal = totalCount
            nextOffset =
                candidateOffset
                    ?.takeIf { it > pageOffset }
                    ?: spotifyPagedNextOffset(
                        nextUrl = null,
                        pageOffset = pageOffset,
                        itemCount = trackPage.rawItemCount,
                        total = expectedTotal,
                    )

            if (totalCount != null && songs.size >= totalCount) {
                break
            }
        }

        val graphSongs = songs.toList()
        val expectedTotal = totalCount
        val resolvedSongs =
            if (
                expectedTotal != null &&
                graphSongs.size < expectedTotal &&
                graphSongs.size < PLAYLIST_TRACK_SAFETY_LIMIT
            ) {
                runCatching {
                    resolvePlaylistTracksFromWebApi(playlistId, normalizedCookie)
                }.onFailure { error ->
                    Timber.w(error, "Spotify GraphQL playlist partial; full-track fallback failed for %s", playlistId)
                }.getOrNull()
                    ?.takeIf { it.size > graphSongs.size }
                    ?: graphSongs
            } else {
                graphSongs
            }
        return ExternalPlaylistPage(
            playlist =
                PlaylistItem(
                    id = playlistUri,
                    title = playlist.string("name") ?: "Spotify playlist",
                    author =
                        playlist
                            .obj("ownerV2")
                            ?.obj("data")
                            ?.let { owner ->
                                owner.string("name")
                                    ?: owner.string("displayName")
                                    ?: owner.string("username")
                            }?.let { Artist(name = it, id = null) },
                    songCountText = (totalCount ?: resolvedSongs.size).takeIf { it > 0 }?.let { "$it songs" },
                    thumbnail = playlist.spotifyInitialStateImageUrl() ?: resolvedSongs.firstOrNull()?.thumbnail,
                    playEndpoint = null,
                    shuffleEndpoint = null,
                    radioEndpoint = null,
                ),
            songs = resolvedSongs,
            continuation =
                resolvedSongs.size
                    .takeIf { count ->
                        count > 0 &&
                            count < PLAYLIST_TRACK_SAFETY_LIMIT &&
                            (totalCount == null || count < totalCount)
                    }?.toString(),
        )
    }

    private fun ExternalPlaylistPage.isCompleteSpotifyPlaylistPage(): Boolean {
        if (songs.isEmpty()) return false
        val expectedSongs = playlist.songCountText.spotifySongCount()
        return when {
            isSuspiciousThirtySongSpotifyPlaylistPage() -> false
            expectedSongs != null -> songs.size >= expectedSongs || songs.size >= PLAYLIST_TRACK_SAFETY_LIMIT
            songs.size <= SPOTIFY_SUSPICIOUS_PLAYLIST_PAGE_SIZE -> false
            else -> true
        }
    }

    private fun ExternalPlaylistPage.isSuspiciousThirtySongSpotifyPlaylistPage(): Boolean {
        val expectedSongs = playlist.songCountText.spotifySongCount()
        return songs.size == SPOTIFY_SUSPICIOUS_PLAYLIST_PAGE_SIZE &&
            (expectedSongs == null || expectedSongs <= SPOTIFY_SUSPICIOUS_PLAYLIST_PAGE_SIZE)
    }

    private fun String?.spotifySongCount(): Int? {
        if (this.isNullOrBlank()) return null
        return Regex("""(\d[\d,]*)\s+songs?""", RegexOption.IGNORE_CASE)
            .find(this)
            ?.groupValues
            ?.getOrNull(1)
            ?.replace(",", "")
            ?.toIntOrNull()
    }

    private suspend fun resolvePlaylistFromWebPage(
        playlistId: String,
        normalizedCookie: String,
    ): ExternalPlaylistPage? =
        withContext(Dispatchers.IO) {
            val request =
                Request
                    .Builder()
                    .url("https://open.spotify.com/playlist/$playlistId")
                    .header("User-Agent", WEB_USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml")
                    .header("Referer", WEB_REFERER)
                    .header("Cookie", normalizedCookie)
                    .get()
                    .build()

            runCatching {
                client.newCall(request).execute().use { response ->
                    val html = response.requireBody("Spotify playlist page")
                    val root = html.spotifyInitialStateJson()
                    val playlistUri = "spotify:playlist:$playlistId"
                    val playlist =
                        root
                            .obj("entities")
                            ?.obj("items")
                            ?.obj(playlistUri)
                            ?: return@use null
                    val songs =
                        playlist
                            .obj("content")
                            ?.array("items")
                            .orEmpty()
                            .mapNotNull { item ->
                                val wrapper = item.obj ?: return@mapNotNull null
                                (
                                    wrapper
                                        .obj("itemV2")
                                        ?.obj("data")
                                        ?: wrapper.obj("data")
                                        ?: wrapper
                                ).toSpotifyInitialStatePlaylistSong()
                            }
                    val totalTracks = playlist.obj("content")?.long("totalCount")?.toInt()
                    val shouldResolveFullTracks =
                        (totalTracks != null && songs.size < totalTracks) ||
                            (totalTracks == null && songs.size <= SPOTIFY_SUSPICIOUS_PLAYLIST_PAGE_SIZE)
                    val resolvedSongs =
                        if (shouldResolveFullTracks) {
                            runCatching {
                                resolvePlaylistTracksFromWebApi(playlistId, normalizedCookie)
                            }.onFailure { error ->
                                Timber.w(error, "Spotify playlist page partial; full-track fallback failed for %s", playlistId)
                            }.getOrNull()
                                ?.takeIf { it.size > songs.size }
                                ?: songs
                        } else {
                            songs
                        }
                    val nextOffset =
                        resolvedSongs.size
                            .takeIf { count ->
                                count > 0 &&
                                    count < PLAYLIST_TRACK_SAFETY_LIMIT &&
                                    (totalTracks == null || count < totalTracks)
                            }

                    ExternalPlaylistPage(
                        playlist =
                            PlaylistItem(
                                id = playlistUri,
                                title = playlist.string("name") ?: "Spotify playlist",
                                author =
                                    playlist
                                        .obj("ownerV2")
                                        ?.obj("data")
                                        ?.let { owner ->
                                            owner.string("name")
                                                ?: owner.string("displayName")
                                                ?: owner.string("username")
                                        }?.let { Artist(name = it, id = null) },
                                songCountText = totalTracks?.let { "$it songs" },
                                thumbnail = playlist.spotifyInitialStateImageUrl() ?: resolvedSongs.firstOrNull()?.thumbnail,
                                playEndpoint = null,
                                shuffleEndpoint = null,
                                radioEndpoint = null,
                            ),
                        songs = resolvedSongs,
                        continuation = nextOffset?.toString(),
                    )
                }
            }.onFailure { error ->
                Timber.w(error, "Spotify playlist page parse failed for %s", playlistId)
            }.getOrNull()
        }

    private suspend fun resolvePlaylistTracksFromWebApi(
        playlistId: String,
        normalizedCookie: String,
    ): List<SongItem> =
        withContext(Dispatchers.IO) {
            resolvePlaylistTracksFromSpotifyWebApi(playlistId, normalizedCookie)
        }

    private suspend fun postPlaylistMetadataGraphQl(
        playlistUri: String,
        offset: Int,
        limit: Int,
        cookie: String,
        tokenProvider: suspend (String) -> String,
    ): JsonObject {
        val variables =
            buildJsonObject {
                put("uri", playlistUri)
                put("offset", offset.coerceAtLeast(0))
                put("limit", limit.coerceIn(1, GRAPH_PLAYLIST_PAGE_SIZE))
                put("enableWatchFeedEntrypoint", true)
            }

        return runCatching {
            postGraphQl<JsonObject>(
                operation = "fetchPlaylistMetadata",
                variables = variables,
                cookie = cookie,
                tokenProvider = tokenProvider,
            )
        }.getOrElse { firstError ->
            runCatching {
                postGraphQl<JsonObject>(
                    operation = "fetchPlaylistMetadata",
                    variables = variables,
                    cookie = cookie,
                    hashOverride = SPOTIFY_FETCH_PLAYLIST_METADATA_HASH,
                    tokenProvider = tokenProvider,
                )
            }.getOrElse { fallbackError ->
                fallbackError.addSuppressed(firstError)
                throw fallbackError
            }
        }
    }

    private suspend fun postGraphQlWithHashFallback(
        operation: String,
        variables: JsonObject,
        cookie: String,
        hashOverride: String,
        tokenProvider: suspend (String) -> String,
    ): JsonObject =
        runCatching {
            postGraphQl<JsonObject>(
                operation = operation,
                variables = variables,
                cookie = cookie,
                tokenProvider = tokenProvider,
            )
        }.getOrElse { firstError ->
            runCatching {
                postGraphQl<JsonObject>(
                    operation = operation,
                    variables = variables,
                    cookie = cookie,
                    hashOverride = hashOverride,
                    tokenProvider = tokenProvider,
                )
            }.getOrElse { fallbackError ->
                fallbackError.addSuppressed(firstError)
                throw fallbackError
            }
        }

    private data class SpotifyPlaylistTrackLoad(
        val songs: List<SongItem>,
        val total: Int?,
    )

    private data class SpotifyGraphPlaylistTrackPage(
        val songs: List<SongItem>,
        val rawItemCount: Int,
        val trackIds: List<String>,
    )

    private data class SpotifyGraphPlaylistTrackEntry(
        val song: SongItem?,
        val ref: SpotifyGraphPlaylistTrackRef?,
    )

    private data class SpotifyGraphPlaylistTrackRef(
        val id: String,
    )

    private suspend fun resolvePlaylistTracksFromSpotifyWebApi(
        playlistId: String,
        normalizedCookie: String,
    ): List<SongItem> =
        withContext(Dispatchers.IO) {
            val webLoad =
                runCatching {
                    resolvePlaylistTracksFromSpotifyWebApiWithToken(
                        playlistId = playlistId,
                        normalizedCookie = normalizedCookie,
                        tokenProvider = ::ensureWebToken,
                        tokenLabel = "web",
                    )
                }.onFailure { error ->
                    Timber.w(error, "Spotify playlist Web API tracks failed with web token for %s", playlistId)
                }.getOrNull()
            if (webLoad?.isCompleteSpotifyPlaylistTrackLoad() == true) {
                return@withContext webLoad.songs
            }

            val deviceLoad =
                runCatching {
                    resolvePlaylistTracksFromSpotifyWebApiWithToken(
                        playlistId = playlistId,
                        normalizedCookie = normalizedCookie,
                        tokenProvider = ::ensureToken,
                        tokenLabel = "device",
                    )
                }.onFailure { error ->
                    Timber.w(error, "Spotify playlist Web API tracks failed with device token for %s", playlistId)
                }.getOrNull()

            listOfNotNull(webLoad, deviceLoad)
                .maxByOrNull { load -> load.songs.size }
                ?.takeIf { it.isCompleteSpotifyPlaylistTrackLoad() }
                ?.songs
                ?: error("Spotify playlist tracks returned no songs")
        }

    private fun SpotifyPlaylistTrackLoad.isCompleteSpotifyPlaylistTrackLoad(): Boolean =
        songs.isNotEmpty() &&
            when {
                songs.size == SPOTIFY_SUSPICIOUS_PLAYLIST_PAGE_SIZE &&
                    (total == null || total <= SPOTIFY_SUSPICIOUS_PLAYLIST_PAGE_SIZE) -> false
                total != null -> songs.size >= total || songs.size >= PLAYLIST_TRACK_SAFETY_LIMIT
                songs.size <= SPOTIFY_SUSPICIOUS_PLAYLIST_PAGE_SIZE -> false
                else -> true
            }

    private suspend fun resolvePlaylistTracksFromSpotifyWebApiWithToken(
        playlistId: String,
        normalizedCookie: String,
        tokenProvider: suspend (String) -> String,
        tokenLabel: String,
    ): SpotifyPlaylistTrackLoad =
        withContext(Dispatchers.IO) {
            val songs = mutableListOf<SongItem>()
            var offset = 0
            var total: Int? = null

            while (songs.size < PLAYLIST_TRACK_SAFETY_LIMIT) {
                val url =
                    "https://api.spotify.com/v1/playlists/$playlistId/tracks"
                        .toHttpUrl()
                        .newBuilder()
                        .addQueryParameter("limit", GRAPH_PLAYLIST_PAGE_SIZE.toString())
                        .addQueryParameter("offset", offset.toString())
                        .build()

                val page =
                    spotifyApiGet(
                        url = url,
                        normalizedCookie = normalizedCookie,
                        operation = "Spotify playlist tracks ($tokenLabel)",
                        tokenProvider = tokenProvider,
                    )

                total = total ?: page.long("total")?.toInt()
                val pageOffset = page.long("offset")?.toInt() ?: offset
                val rawItems = page.array("items").orEmpty()
                val pageSongs =
                    rawItems
                        .mapNotNull { item ->
                            item.obj?.obj("track")
                                ?: item.obj?.obj("item")
                        }.mapNotNull { it.toSpotifyPlaylistSong() }

                if (rawItems.isEmpty()) break

                songs += pageSongs
                if (pageSongs.isEmpty() && offset > 0) break
                val nextOffset =
                    spotifyPagedNextOffset(
                        nextUrl = page.string("next"),
                        pageOffset = pageOffset,
                        itemCount = rawItems.size,
                        total = total,
                    ) ?: break
                offset = nextOffset
            }

            SpotifyPlaylistTrackLoad(
                songs = songs,
                total = total,
            )
        }

    private suspend fun resolvePlaylistTracksFromSpotifyMobileApi(
        playlistId: String,
        normalizedCookie: String,
    ): List<SongItem> =
        withContext(Dispatchers.IO) {
            val songs = mutableListOf<SongItem>()
            var offset = 0
            var total: Int? = null

            while (songs.size < PLAYLIST_TRACK_SAFETY_LIMIT) {
                val page =
                    spotifySpClientGet(
                        url =
                            "https://spclient.wg.spotify.com/playlist/v2/playlist/$playlistId/items"
                                .toHttpUrl()
                                .newBuilder()
                                .addQueryParameter("market", "from_token")
                                .addQueryParameter("limit", PLAYLIST_TRACK_PAGE_SIZE.toString())
                                .addQueryParameter("offset", offset.toString())
                                .build(),
                        normalizedCookie = normalizedCookie,
                        operation = "Spotify mobile playlist tracks",
                    )

                total = total ?: page.spotifyMobilePlaylistTotal()
                val pageOffset = page.long("offset")?.toInt() ?: offset
                val pageSongs = page.spotifyMobilePlaylistTracks()
                if (pageSongs.isEmpty()) break

                songs += pageSongs
                val nextOffset =
                    spotifyPagedNextOffset(
                        nextUrl = null,
                        pageOffset = pageOffset,
                        itemCount = pageSongs.size,
                        total = total,
                    ) ?: break
                offset = nextOffset
            }

            songs
        }

    private suspend fun spotifySpClientGet(
        url: HttpUrl,
        normalizedCookie: String,
        operation: String,
    ): JsonObject =
        withContext(Dispatchers.IO) {
            val request =
                Request
                    .Builder()
                    .url(url)
                    .header("User-Agent", DESKTOP_USER_AGENT)
                    .header("Accept", "application/json")
                    .header("Cookie", normalizedCookie)
                    .header("Authorization", "Bearer ${ensureToken(normalizedCookie)}")
                    .get()
                    .build()

            client.newCall(request).execute().use { response ->
                json.parseToJsonElement(response.requireBody(operation)).jsonObject
            }
        }

    private suspend fun resolveSavedTracksFromWebApi(
        normalizedCookie: String,
        maxTracks: Int,
    ): Pair<List<SongItem>, Int?> =
        withContext(Dispatchers.IO) {
            val songs = mutableListOf<SongItem>()
            var offset = 0
            var total: Int? = null

            while (songs.size < maxTracks) {
                val url =
                    "https://api.spotify.com/v1/me/tracks"
                        .toHttpUrl()
                        .newBuilder()
                        .addQueryParameter("market", "from_token")
                        .addQueryParameter("limit", "50")
                        .addQueryParameter("offset", offset.toString())
                        .build()

                val page =
                    spotifyApiGet(
                        url = url,
                        normalizedCookie = normalizedCookie,
                        operation = "Spotify saved tracks",
                    )

                total = total ?: page.long("total")?.toInt()
                val pageOffset = page.long("offset")?.toInt() ?: offset
                val pageLimit =
                    page.long("limit")
                        ?.toInt()
                        ?.takeIf { it > 0 }
                        ?: 50
                val pageSongs =
                    page
                        .array("items")
                        .orEmpty()
                        .mapNotNull { it.obj?.obj("track")?.toSpotifyPlaylistSong() }

                if (pageSongs.isEmpty()) break

                songs += pageSongs
                val nextOffset = pageOffset + pageLimit
                if (nextOffset <= offset) break
                offset = nextOffset

                val expectedTotal = total
                if (expectedTotal != null && offset >= expectedTotal) break
            }

            songs.distinctBy { it.id } to total
        }

    private suspend fun resolvePlaylistFromWebApi(
        playlistId: String,
        normalizedCookie: String,
    ): ExternalPlaylistPage? {
        val url =
            "https://api.spotify.com/v1/playlists/$playlistId"
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter(
                    "fields",
                    "id,name,owner(display_name),images,total,tracks(total,items(track(id,name,duration_ms,explicit,external_ids(isrc),artists(id,name),album(id,name,images))))",
                ).build()

        return withContext(Dispatchers.IO) {
            val root =
                spotifyApiGet(
                    url = url,
                    normalizedCookie = normalizedCookie,
                    operation = "Spotify playlist",
                )
                val songs =
                    runCatching {
                        resolvePlaylistTracksFromWebApi(playlistId, normalizedCookie)
                    }.onFailure { error ->
                        Timber.w(error, "Spotify playlist full-track fallback failed for %s", playlistId)
                    }.getOrNull()
                        ?.takeIf { it.isNotEmpty() }
                        ?: root.obj("tracks")
                            ?.array("items")
                            .orEmpty()
                            .mapNotNull { it.obj?.obj("track")?.toSpotifyPlaylistSong() }
                val total = root.obj("tracks")?.long("total")?.toInt()
                val nextOffset =
                    songs.size
                        .takeIf { count ->
                            count > 0 &&
                                count < PLAYLIST_TRACK_SAFETY_LIMIT &&
                                (total == null || count < total)
                        }

                ExternalPlaylistPage(
                    playlist =
                        PlaylistItem(
                            id = "spotify:playlist:$playlistId",
                            title = root.string("name") ?: "Spotify playlist",
                            author = root.obj("owner")?.string("display_name")?.let { Artist(name = it, id = null) },
                            songCountText = total?.let { "$it songs" },
                            thumbnail = root.spotifyWebApiImageUrl() ?: songs.firstOrNull()?.thumbnail,
                            playEndpoint = null,
                            shuffleEndpoint = null,
                            radioEndpoint = null,
                        ),
                    songs = songs,
                    continuation = nextOffset?.toString(),
                )
        }
    }

    private fun String.spotifyInitialStateJson(): JsonObject {
        val encoded =
            INITIAL_STATE_REGEX
                .find(this)
                ?.groupValues
                ?.getOrNull(1)
                ?.replace(Regex("\\s"), "")
                ?: error("Spotify playlist page missing initialState")
        val decoded = String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
        return json.parseToJsonElement(decoded).jsonObject
    }

    private fun spotifyPagedNextOffset(
        nextUrl: String?,
        pageOffset: Int,
        itemCount: Int,
        total: Int?,
    ): Int? {
        if (itemCount <= 0) return null
        spotifyNextOffset(nextUrl)
            ?.takeIf { it > pageOffset }
            ?.let { return it }

        val nextOffset = pageOffset + itemCount
        if (nextOffset <= pageOffset) return null
        return nextOffset.takeIf { total == null || it < total }
    }

    private fun spotifyNextOffset(nextUrl: String?): Int? =
        nextUrl
            ?.takeIf { it.isNotBlank() }
            ?.let { url ->
                runCatching {
                    url.toHttpUrl().queryParameter("offset")?.toIntOrNull()
                }.getOrNull()
            }

    private suspend fun resolveTrackUri(
        expectation: Expectation,
        cookie: String,
    ): String? {
        val now = System.currentTimeMillis()
        trackUriCache[expectation.key]
            ?.takeIf { now - it.cachedAt < CACHE_TTL_MS }
            ?.let { return it.value.ifBlank { null } }

        val candidates =
            resolveTrackCandidates(expectation, cookie)
                .distinctBy { it.uri }

        val bestMatch =
            candidates
                .map { it to scoreTrack(it, expectation) }
                .maxByOrNull { it.second }
                ?.takeIf { it.second >= 55 }
                ?.first
                ?.uri

        trackUriCache[expectation.key] = CachedString(bestMatch.orEmpty(), now)
        return bestMatch
    }

    suspend fun resolveAccountInfo(cookie: String): SpotifyAccountInfo? {
        val normalizedCookie = normalizeSpotifyCookieInput(cookie) ?: return null
        val root =
            spotifyApiGet(
                url = "https://api.spotify.com/v1/me".toHttpUrl(),
                normalizedCookie = normalizedCookie,
                operation = "Spotify account",
            )

        val name =
            root.string("display_name")
                ?: root.string("id")
                ?: return null

        return SpotifyAccountInfo(
            name = name,
            thumbnailUrl = root.spotifyWebApiImageUrl(),
        )
    }

    private suspend fun resolveCanvas(
        trackUri: String,
        cookie: String,
    ): String? {
        val now = System.currentTimeMillis()
        canvasUrlCache[trackUri]
            ?.takeIf { cached ->
                now - cached.cachedAt < if (cached.value.isBlank()) CANVAS_MISS_CACHE_TTL_MS else CACHE_TTL_MS
            }
            ?.let { return it.value.ifBlank { null } }

        val canvazUrl =
            runCatching {
                resolveCanvasFromCanvaz(trackUri, cookie)
            }.onFailure { error ->
                Timber.w(error, "Spotify Canvaz lookup failed for %s; retrying GraphQL canvas", trackUri)
            }.getOrNull()

        if (!canvazUrl.isNullOrBlank()) {
            canvasUrlCache[trackUri] = CachedString(canvazUrl, now)
            return canvazUrl
        }

        val response =
            runCatching {
                postGraphQl<CanvasResponse>(
                    operation = "canvas",
                    variables =
                        buildJsonObject {
                            put("uri", trackUri)
                        },
                    cookie = cookie,
                    tokenProvider = ::ensureWebToken,
                )
            }.onFailure { error ->
                Timber.w(error, "Spotify canvas Web token lookup failed for %s; retrying device token", trackUri)
            }.getOrElse {
                postGraphQl<CanvasResponse>(
                    operation = "canvas",
                    variables =
                        buildJsonObject {
                            put("uri", trackUri)
                        },
                    cookie = cookie,
                    tokenProvider = ::ensureToken,
                )
            }

        val canvasUrl =
            response.data
                ?.trackUnion
                ?.canvas
                ?.takeIf { it.type.orEmpty().startsWith("VIDEO", ignoreCase = true) }
                ?.url
                ?.takeIf { it.isNotBlank() }
                ?.takeIf(::isUsableSpotifyCanvasUrl)

        canvasUrlCache[trackUri] = CachedString(canvasUrl.orEmpty(), now)
        return canvasUrl
    }

    private suspend fun resolveCanvasFromCanvaz(
        trackUri: String,
        cookie: String,
    ): String? =
        withContext(Dispatchers.IO) {
            val requestBody = buildCanvazRequest(trackUri).toRequestBody(PROTOBUF_MEDIA_TYPE)
            val token =
                runCatching { ensureWebToken(cookie) }
                    .onFailure { error -> Timber.w(error, "Spotify web token failed for Canvaz; retrying device token") }
                    .getOrElse { ensureToken(cookie) }
            val request =
                Request
                    .Builder()
                    .url(SPOTIFY_CANVAZ_URL)
                    .header("Accept", "application/protobuf")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept-Language", "en")
                    .header("User-Agent", SPOTIFY_CANVAZ_USER_AGENT)
                    .header("Authorization", "Bearer $token")
                    .post(requestBody)
                    .build()

            client.newCall(request).execute().use { response ->
                val body = response.body.bytes()
                if (!response.isSuccessful) {
                    throw SpotifyApiException(
                        statusCode = response.code,
                        message =
                            "Canvaz failed: ${
                                body.decodeToStringOrNull()
                                    ?.takeIf { it.isNotBlank() }
                                    ?: "${response.code} ${response.message}"
                            }",
                    )
                }
                parseCanvazResponse(body, trackUri)
                    .firstOrNull()
                    ?.takeIf(::isUsableSpotifyCanvasUrl)
            }
        }

    private fun buildCanvazRequest(trackUri: String): ByteArray {
        val track =
            ByteArrayOutputStream().apply {
                writeProtoString(1, trackUri)
            }.toByteArray()
        return ByteArrayOutputStream().apply {
            writeProtoBytes(1, track)
        }.toByteArray()
    }

    private fun parseCanvazResponse(
        bytes: ByteArray,
        requestedTrackUri: String,
    ): List<String> {
        val root = parseProtoMessageOrNull(bytes) ?: return emptyList()
        return root.messages(1)
            .filter { canvas ->
                canvas.string(5)?.equals(requestedTrackUri, ignoreCase = true) != false
            }
            .mapNotNull { canvas ->
                canvas.string(2)
                    ?.takeIf { it.isNotBlank() }
            }
    }

    private fun ByteArrayOutputStream.writeProtoString(
        number: Int,
        value: String,
    ) {
        writeProtoBytes(number, value.toByteArray(Charsets.UTF_8))
    }

    private fun ByteArrayOutputStream.writeProtoBytes(
        number: Int,
        value: ByteArray,
    ) {
        writeProtoVarint(((number shl 3) or 2).toLong())
        writeProtoVarint(value.size.toLong())
        write(value)
    }

    private fun ByteArrayOutputStream.writeProtoVarint(value: Long) {
        var current = value
        while (true) {
            if ((current and 0x7f.inv().toLong()) == 0L) {
                write(current.toInt())
                return
            }
            write(((current and 0x7f) or 0x80).toInt())
            current = current ushr 7
        }
    }

    private fun isUsableSpotifyCanvasUrl(url: String): Boolean {
        val trimmed = url.trim()
        val lower = trimmed.lowercase(Locale.US)
        if (!lower.startsWith("https://")) return false
        if (
            listOf(
                "widevine",
                "license",
                ".m3u8",
                ".mpd",
                "manifest",
                "drm",
            ).any { it in lower }
        ) {
            return false
        }
        return "canvaz.scdn.co" in lower ||
            lower.substringBefore('?').endsWith(".mp4") ||
            ".cnvs." in lower
    }

    private suspend fun resolveAudioFeatures(
        trackUri: String,
        cookie: String,
    ): SpotifyMixMetadata? {
        val trackId = trackUri.spotifyTrackId() ?: return null
        val now = System.currentTimeMillis()
        audioFeaturesCache[trackId]
            ?.takeIf { now - it.cachedAt < CACHE_TTL_MS }
            ?.let { return it.value }

        val result =
            runCatching {
                withContext(Dispatchers.IO) {
                    val request =
                        Request
                            .Builder()
                            .url("https://api.spotify.com/v1/audio-features/$trackId")
                            .header("User-Agent", WEB_USER_AGENT)
                            .header("Accept", "application/json")
                            .header("Authorization", "Bearer ${ensureWebToken(cookie)}")
                            .get()
                            .build()

                    client.newCall(request).execute().use { response ->
                        val body = response.body.string()
                        if (!response.isSuccessful) {
                            Timber.tag("SpotifyMix").d(
                                "Spotify audio-features unavailable for $trackId: ${response.code} ${body.take(120)}",
                            )
                            return@withContext null
                        }

                        val root = json.parseToJsonElement(body).jsonObject
                        SpotifyMixMetadata(
                            bpm = root.double("tempo")?.toFloat()?.takeIf { it in 40f..240f },
                            keySignature = spotifyKeySignature(root.int("key"), root.int("mode")),
                            timeSignature = root.int("time_signature")?.takeIf { it in 1..12 },
                        ).takeIf { it.bpm != null || it.keySignature != null || it.timeSignature != null }
                    }
                }
            }.onFailure { error ->
                Timber.tag("SpotifyMix").d(error, "Spotify audio-features lookup failed for $trackId")
            }.getOrNull()

        audioFeaturesCache[trackId] = CachedMixMetadata(result, now)
        return result
    }

    private suspend fun searchTracks(
        query: String,
        cookie: String,
    ): List<SearchTrack> {
        if (query.isBlank()) return emptyList()

        val response =
            postGraphQl<SearchTracksResponse>(
                operation = "searchTracks",
                variables =
                    buildJsonObject {
                        put("searchTerm", query)
                        put("offset", 0)
                        put("limit", 10)
                        put("numberOfTopResults", 5)
                        put("includeAudiobooks", false)
                        put("includePreReleases", true)
                    },
                cookie = cookie,
            )

        return response.data
            ?.searchV2
            ?.tracksV2
            ?.items
            .orEmpty()
            .mapNotNull { it.item?.data?.takeIf { track -> !track.uri.isNullOrBlank() } }
    }

    private suspend fun resolveTrackCandidates(
        expectation: Expectation,
        cookie: String,
    ): List<SearchTrack> {
        val graphCandidates =
            buildQueries(expectation)
                .flatMap { query ->
                    runCatching { searchTracks(query, cookie) }
                        .onFailure { error -> Timber.w(error, "Spotify canvas GraphQL search failed for %s", query) }
                        .getOrDefault(emptyList())
                }

        graphCandidates
            .map { it to scoreTrack(it, expectation) }
            .maxByOrNull { it.second }
            ?.takeIf { it.second >= 85 }
            ?.let { return graphCandidates }

        val webQueries =
            buildQueries(expectation)
                .take(if (expectation.isrc != null) 3 else 2)
        val webCandidates =
            webQueries.flatMap { query ->
                runCatching { searchTracksFromWebApi(query, cookie) }
                    .onFailure { error -> Timber.w(error, "Spotify canvas Web API search failed for %s", query) }
                    .getOrDefault(emptyList())
            }

        return graphCandidates + webCandidates
    }

    private suspend fun searchTracksFromWebApi(
        query: String,
        cookie: String,
    ): List<SearchTrack> {
        if (query.isBlank()) return emptyList()

        val root =
            spotifyApiGet(
                url =
                    "https://api.spotify.com/v1/search"
                        .toHttpUrl()
                        .newBuilder()
                        .addQueryParameter("q", query)
                        .addQueryParameter("type", "track")
                        .addQueryParameter("limit", "10")
                        .addQueryParameter("market", "from_token")
                        .build(),
                normalizedCookie = cookie,
                operation = "Spotify canvas track search",
            )

        return root
            .obj("tracks")
            ?.array("items")
            .orEmpty()
            .mapNotNull { it.obj?.toSearchTrackCandidate() }
    }

    private suspend inline fun <reified T> postGraphQl(
        operation: String,
        variables: JsonObject,
        cookie: String,
        hashOverride: String? = null,
        noinline tokenProvider: suspend (String) -> String = ::ensureToken,
    ): T =
        withContext(Dispatchers.IO) {
            val token = tokenProvider(cookie)
            val resolvedHash = hashOverride ?: resolveGraphQlHash(operation)
            runCatching {
                executeGraphQlRequest<T>(
                    operation = operation,
                    hash = resolvedHash,
                    variables = variables,
                    cookie = cookie,
                    token = token,
                )
            }.getOrElse { firstError ->
                if (hashOverride != null) throw firstError
                invalidateGraphQlHash(operation)
                val refreshedHash = resolveGraphQlHash(operation, forceRefresh = true)
                if (refreshedHash == resolvedHash) {
                    throw firstError
                }
                runCatching {
                    executeGraphQlRequest<T>(
                        operation = operation,
                        hash = refreshedHash,
                        variables = variables,
                        cookie = cookie,
                        token = token,
                    )
                }.getOrElse { retryError ->
                    retryError.addSuppressed(firstError)
                    throw retryError
                }
            }
        }

    private suspend fun spotifyLegacyGraphQlGet(
        operation: String,
        hash: String,
        variables: JsonObject,
        normalizedCookie: String,
    ): JsonObject =
        withContext(Dispatchers.IO) {
            val url =
                SPOTIFY_LEGACY_GRAPHQL_URL
                    .toHttpUrl()
                    .newBuilder()
                    .addQueryParameter("operationName", "query$operation")
                    .addQueryParameter("variables", variables.toString())
                    .addQueryParameter(
                        "extensions",
                        buildJsonObject {
                            putJsonObject("persistedQuery") {
                                put("version", 1)
                                put("sha256Hash", hash)
                            }
                        }.toString(),
                    ).build()
            val requestBuilder =
                Request
                    .Builder()
                    .url(url)
                    .header("User-Agent", WEB_USER_AGENT)
                    .header("Accept", "application/json")
                    .header("App-Platform", "WebPlayer")
                    .header("Referer", WEB_REFERER)
                    .header("Origin", WEB_ORIGIN)
                    .header("Authorization", "Bearer ${ensureWebToken(normalizedCookie)}")
                    .get()
            if (normalizedCookie.isNotBlank()) {
                requestBuilder.header("Cookie", normalizedCookie)
            }

            client.newCall(requestBuilder.build()).execute().use { response ->
                json.parseToJsonElement(response.requireBody("Spotify legacy $operation")).jsonObject
            }
        }

    private inline fun <reified T> executeGraphQlRequest(
        operation: String,
        hash: String,
        variables: JsonObject,
        cookie: String,
        token: String,
    ): T {
        val requestBuilder =
            Request
                .Builder()
                .url("https://api-partner.spotify.com/pathfinder/v2/query")
                .post(
                    buildJsonObject {
                        put("operationName", operation)
                        put("variables", variables)
                        putJsonObject("extensions") {
                            putJsonObject("persistedQuery") {
                                put("version", 1)
                                put("sha256Hash", hash)
                            }
                        }
                    }.toString().toRequestBody(JSON_MEDIA_TYPE),
                ).header("User-Agent", DESKTOP_WEB_USER_AGENT)
                .header("Accept", "application/json")
                .header("Accept-Language", "en-GB")
                .header("App-Platform", "WebPlayer")
                .header("Referer", WEB_REFERER)
                .header("Origin", WEB_ORIGIN)
                .header("Authorization", "Bearer $token")
        if (cookie.isNotBlank()) {
            requestBuilder.header("Cookie", cookie)
        }
        val request = requestBuilder.build()

        client.newCall(request).execute().use { response ->
            return json.decodeFromString<T>(response.requireBody(operation))
        }
    }

    private suspend fun resolveGraphQlHash(
        operation: String,
        forceRefresh: Boolean = false,
    ): String {
        val now = System.currentTimeMillis()
        val hashOperation = graphQlHashOperation(operation)
        if (!forceRefresh) {
            graphHashCache[hashOperation]
                ?.takeIf { now - it.cachedAt < GRAPH_HASH_CACHE_TTL_MS }
                ?.let { return it.value }
        }

        return graphHashMutex.withLock {
            if (!forceRefresh) {
                graphHashCache[hashOperation]
                    ?.takeIf { now - it.cachedAt < GRAPH_HASH_CACHE_TTL_MS }
                    ?.let { return@withLock it.value }
            }

            val resolved =
                runCatching {
                loadCurrentGraphQlHashes(setOf(hashOperation))[hashOperation]
                }.onFailure { error ->
                    Timber.w(error, "Spotify GraphQL hash resolver failed for %s", hashOperation)
                }.getOrNull()
                    ?: error("Spotify GraphQL hash resolver failed for $hashOperation")

            graphHashCache[hashOperation] = CachedString(resolved, System.currentTimeMillis())
            resolved
        }
    }

    private fun invalidateGraphQlHash(operation: String) {
        graphHashCache.remove(graphQlHashOperation(operation))
    }

    private fun graphQlHashOperation(operation: String): String =
        when (operation) {
            "fetchPlaylistWithGatedEntityRelations",
            "fetchPlaylistContentsWithGatedEntityRelations",
            -> "fetchPlaylist"
            else -> operation
        }

    private suspend fun loadCurrentGraphQlHashes(operations: Set<String>): Map<String, String> =
        withContext(Dispatchers.IO) {
            val found = linkedMapOf<String, String>()
            val html = fetchSpotifyHashResolverText(WEB_PLAYER_URL, "Spotify web player")
            val bundles = pickWebPlayerBundles(html)
            check(bundles.isNotEmpty()) { "Spotify web-player bundle not found" }

            for (bundle in bundles) {
                if (operations.all { found[it] != null }) break
                val mainBody =
                    runCatching { fetchSpotifyHashResolverText(bundle, "Spotify web-player bundle") }
                        .onFailure { error ->
                            Timber.d(error, "Spotify hash resolver skipped bundle %s", bundle)
                        }.getOrNull()
                        ?: continue

                found.putAllMissing(findOperationHashes(mainBody, operations - found.keys))
                if (operations.all { found[it] != null }) break

                val baseUrl = bundle.substringBeforeLast('/', missingDelimiterValue = bundle) + "/"
                parseWebpackChunks(mainBody).forEach { chunk ->
                    if (operations.all { found[it] != null }) return@forEach
                    val body =
                        runCatching { fetchSpotifyHashResolverText(baseUrl + chunk, "Spotify web-player chunk") }
                            .getOrNull()
                            ?: return@forEach
                    found.putAllMissing(findOperationHashes(body, operations - found.keys))
                }
            }

            found
        }

    private fun fetchSpotifyHashResolverText(
        url: String,
        step: String,
    ): String {
        val request =
            Request
                .Builder()
                .url(url)
                .header("User-Agent", DESKTOP_WEB_USER_AGENT)
                .header("Accept", "text/html,application/javascript,*/*")
                .header("Referer", WEB_REFERER)
                .get()
                .build()

        client.newCall(request).execute().use { response ->
            return response.requireBody(step)
        }
    }

    private fun pickWebPlayerBundles(html: String): List<String> =
        WEB_PLAYER_SCRIPT_REGEX
            .findAll(html)
            .mapNotNull { match -> match.groupValues.getOrNull(1) }
            .map { src ->
                when {
                    src.startsWith("//") -> "https:$src"
                    src.startsWith("/") -> "https://open.spotify.com$src"
                    else -> src
                }
            }.filter { src ->
                src.endsWith(".js") && (src.contains("/web-player/") || src.contains("/mobile-web-player/"))
            }.sortedBy { src ->
                when {
                    src.contains("/web-player/web-player.") -> 0
                    src.contains("/mobile-web-player/mobile-web-player.") -> 1
                    else -> 2
                }
            }.toList()

    private fun parseWebpackChunks(js: String): List<String> {
        val parsedMaps =
            WEBPACK_CHUNK_MAP_REGEX
                .findAll(js)
                .mapNotNull { parseWebpackMap(it.value) }
                .toList()
        val nameMap = parsedMaps.maxByOrNull(::scoreWebpackNameMap) ?: return emptyList()
        val hashMap = parsedMaps.maxByOrNull(::scoreWebpackHashMap) ?: return emptyList()
        if (scoreWebpackNameMap(nameMap) <= 0.4 || scoreWebpackHashMap(hashMap) <= 0.4) return emptyList()

        return nameMap.keys
            .filter { key -> hashMap[key] != null }
            .sorted()
            .mapNotNull { key ->
                val name = nameMap[key].orEmpty()
                val hash = hashMap[key].orEmpty()
                if (name.isBlank() || hash.isBlank()) {
                    null
                } else {
                    "$name.$hash.js"
                }
            }
    }

    private fun parseWebpackMap(raw: String): Map<Int, String>? {
        val mapped = WEBPACK_CHUNK_ID_REGEX.replace(raw) { match -> "\"${match.groupValues[1]}\":" }
        return runCatching {
            json
                .parseToJsonElement(mapped)
                .jsonObject
                .mapNotNull { (key, value) ->
                    val id = key.toIntOrNull() ?: return@mapNotNull null
                    val text = value.stringValueOrNull() ?: return@mapNotNull null
                    id to text
                }.toMap()
                .takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    private suspend fun resolveSavedTracksCollection(normalizedCookie: String): ExternalPlaylistPage {
        val cacheKey = spotifyCacheKey(normalizedCookie, "playlist", "collection:tracks")
        externalPlaylistCache.fresh(cacheKey)?.let { return it }
        val (songs, total) =
            runCatching {
                resolveSavedTracksFromGraphQl(normalizedCookie, LIKED_TRACKS_OPEN_LIMIT)
            }.onFailure { error ->
                Timber.w(error, "Spotify internal saved tracks open failed; retrying public Web API")
            }.getOrNull()
                ?.takeIf { (songs, _) -> songs.isNotEmpty() }
                ?: resolveSavedTracksFromWebApi(normalizedCookie, LIKED_TRACKS_OPEN_LIMIT)
        return ExternalPlaylistPage(
            playlist =
                spotifyLikedSongsPlaylist(
                    songs = songs,
                    total = total,
                ) ?: PlaylistItem(
                    id = "spotify:collection:tracks",
                    title = "Liked Songs",
                    author = Artist(name = "Spotify", id = null),
                    songCountText = total?.takeIf { it > 0 }?.let { "$it songs" },
                    thumbnail = songs.firstOrNull()?.thumbnail,
                    playEndpoint = null,
                    shuffleEndpoint = null,
                    radioEndpoint = null,
                ),
            songs = songs,
        ).also { externalPlaylistCache.putFresh(cacheKey, it) }
    }

    private suspend fun resolveSavedTracksFromGraphQl(
        normalizedCookie: String,
        maxTracks: Int,
    ): Pair<List<SongItem>, Int?> {
        val songs = mutableListOf<SongItem>()
        var offset = 0
        var total: Int? = null

        while (songs.size < maxTracks) {
            val page =
                postGraphQl<JsonObject>(
                    operation = "fetchLibraryTracks",
                    variables =
                        buildJsonObject {
                            put("uri", "spotify:collection:tracks")
                            put("offset", offset)
                            put("limit", minOf(50, maxTracks - songs.size))
                        },
                    cookie = normalizedCookie,
                    tokenProvider = ::ensureToken,
                )

            total = total ?: page.spotifyLibraryTracksTotal()
            val pageSongs =
                page.spotifyLibraryTrackItems()
                    .mapNotNull { it.toSpotifyInitialStatePlaylistSong() }

            if (pageSongs.isEmpty()) break

            songs += pageSongs
            offset += pageSongs.size

            val expectedTotal = total
            if (expectedTotal != null && offset >= expectedTotal) break
        }

        return songs.distinctBy { it.id } to total
    }

    private fun scoreWebpackHashMap(map: Map<Int, String>): Double =
        if (map.isEmpty()) {
            0.0
        } else {
            map.values.count { it.matches(Regex("^[a-f0-9]{6,12}$")) }.toDouble() / map.size.toDouble()
        }

    private fun scoreWebpackNameMap(map: Map<Int, String>): Double =
        if (map.isEmpty()) {
            0.0
        } else {
            map.values.count { it.contains('-') || it.contains('/') }.toDouble() / map.size.toDouble()
        }

    private fun findOperationHashes(
        body: String,
        operations: Set<String>,
    ): Map<String, String> =
        buildMap {
            operations.forEach { operation ->
                val escaped = Regex.escape(operation)
                val patterns =
                    listOf(
                        Regex("""$escaped[\s\S]{0,800}?sha256Hash\\?":\\?"([a-f0-9]{64})"""),
                        Regex("\"$escaped\",\"(?:query|mutation)\",\"([a-f0-9]{64})\""),
                        Regex("""operationName:"$escaped"[\s\S]{0,1000}?sha256Hash:"([a-f0-9]{64})"""),
                    )
                patterns
                    .firstNotNullOfOrNull { pattern ->
                        pattern.find(body)?.groupValues?.getOrNull(1)
                    }?.let { hash -> put(operation, hash) }
            }
        }

    private fun MutableMap<String, String>.putAllMissing(values: Map<String, String>) {
        values.forEach { (key, value) ->
            putIfAbsent(key, value)
        }
    }

    private suspend fun spotifyApiGet(
        url: HttpUrl,
        normalizedCookie: String,
        operation: String,
    ): JsonObject {
        val webResult =
            runCatching {
                spotifyApiGet(
                    url = url,
                    normalizedCookie = normalizedCookie,
                    operation = operation,
                    tokenProvider = ::ensureWebToken,
                )
            }

        webResult.onSuccess { return it }

        val webError = webResult.exceptionOrNull()
        if (webError is SpotifyApiException && webError.statusCode == 429) {
            throw webError
        }

        Timber.w(
            webError,
            "%s failed with Spotify web token; retrying device token",
            operation,
        )

        return spotifyApiGet(
            url = url,
            normalizedCookie = normalizedCookie,
            operation = operation,
            tokenProvider = ::ensureToken,
        )
    }

    private suspend fun spotifyApiGet(
        url: HttpUrl,
        normalizedCookie: String,
        operation: String,
        tokenProvider: suspend (String) -> String,
    ): JsonObject =
        withContext(Dispatchers.IO) {
            val requestBuilder =
                Request
                    .Builder()
                    .url(url)
                    .header("User-Agent", WEB_USER_AGENT)
                    .header("Accept", "application/json")
                    .header("Referer", WEB_REFERER)
                    .header("Origin", WEB_ORIGIN)
                    .header("Authorization", "Bearer ${tokenProvider(normalizedCookie)}")
            if (normalizedCookie.isNotBlank()) {
                requestBuilder.header("Cookie", normalizedCookie)
            }
            val request = requestBuilder.get().build()

            client.newCall(request).execute().use { response ->
                val body = response.body.string()
                if (!response.isSuccessful) {
                    throw SpotifyApiException(
                        statusCode = response.code,
                        message = "$operation failed: ${body.ifBlank { "${response.code} ${response.message}" }}",
                    )
                }
                json.parseToJsonElement(body.ifBlank { error("$operation returned an empty response") }).jsonObject
            }
        }

    private suspend fun spotifyCasitaGet(
        url: HttpUrl,
        normalizedCookie: String,
        cacheControl: String? = null,
        headers: Map<String, String> = emptyMap(),
        operation: String,
    ): ByteArray {
        val tokenProviders: List<suspend (String) -> String> = listOf(::ensureToken, ::ensureWebToken)
        var lastError: Throwable? = null
        tokenProviders.forEach { tokenProvider ->
            val result =
                runCatching {
                    spotifyCasitaGetWithToken(
                        url = url,
                        normalizedCookie = normalizedCookie,
                        cacheControl = cacheControl,
                        headers = headers,
                        operation = operation,
                        bearerToken = tokenProvider(normalizedCookie),
                    )
                }
            result.onSuccess { return it }
            val error = result.exceptionOrNull() ?: return@forEach
            lastError = error
            if (error !is SpotifyApiException || error.statusCode !in setOf(401, 403)) {
                throw error
            }
        }
        throw (lastError ?: IllegalStateException("$operation failed"))
    }

    private suspend fun spotifyCasitaGetWithToken(
        url: HttpUrl,
        normalizedCookie: String,
        cacheControl: String?,
        headers: Map<String, String>,
        operation: String,
        bearerToken: String,
    ): ByteArray =
        withContext(Dispatchers.IO) {
            val requestBuilder =
                Request
                    .Builder()
                    .url(url)
                    .header("User-Agent", SPOTIFY_ANDROID_USER_AGENT)
                    .header("Accept", "application/protobuf")
                    .header("Content-Type", "application/protobuf")
                    .header("Referer", WEB_REFERER)
                    .header("Authorization", "Bearer $bearerToken")
            cacheControl?.takeIf { it.isNotBlank() }?.let { requestBuilder.header("Cache-Control", it) }
            headers.forEach { (name, value) -> requestBuilder.header(name, value) }
            if (normalizedCookie.isNotBlank()) {
                requestBuilder.header("Cookie", normalizedCookie)
            }
            val request = requestBuilder.get().build()

            client.newCall(request).execute().use { response ->
                val body = response.body.bytes()
                if (!response.isSuccessful) {
                    throw SpotifyApiException(
                        statusCode = response.code,
                        message =
                            "$operation failed: ${
                                body.decodeToStringOrNull()
                                    ?.takeIf { it.isNotBlank() }
                                    ?: "${response.code} ${response.message}"
                            }",
                    )
                }
                if (body.isEmpty()) error("$operation returned an empty response")
                body
            }
        }

    private suspend fun ensureToken(cookie: String): String =
        tokenMutex.withLock {
            if (activeCookie != cookie) {
                activeCookie = cookie
                token = null
                tokenExpiryMs = 0L
            }

            token
                ?.takeIf { System.currentTimeMillis() < tokenExpiryMs }
                ?.let { return it }

            val freshToken =
                createDesktopAccessToken(
                    extractSpDc(cookie) ?: error("Spotify cookie must include sp_dc"),
                )

            token = freshToken.accessToken
            tokenExpiryMs = System.currentTimeMillis() + freshToken.expiresIn * 1000L - 60_000L
            freshToken.accessToken
        }

    private suspend fun ensureWebToken(cookie: String): String =
        webTokenMutex.withLock {
            if (activeWebCookie != cookie) {
                activeWebCookie = cookie
                webToken = null
                webTokenExpiryMs = 0L
            }

            webToken
                ?.takeIf { System.currentTimeMillis() < webTokenExpiryMs }
                ?.let { return it }

            val freshToken =
                runCatching {
                    createWebPlayerAccessToken(
                        extractSpDc(cookie) ?: error("Spotify cookie must include sp_dc"),
                    )
                }.onFailure { error ->
                    Timber.w(error, "Spotify web-player token failed; falling back to desktop token")
                }.getOrNull()
                    ?: return ensureToken(cookie)

            webToken = freshToken.accessToken
            webTokenExpiryMs = freshToken.expiresAtMs - 60_000L
            freshToken.accessToken
        }

    private suspend fun createWebPlayerAccessToken(spDc: String): WebAccessToken =
        withContext(Dispatchers.IO) {
            val nuance = getLatestNuance()
            val serverTimeSeconds = getSpotifyServerTimeSeconds()
            val totp = generateTotp(nuance.secret, serverTimeSeconds)
            val url =
                WEB_TOKEN_URL
                    .toHttpUrl()
                    .newBuilder()
                    .addQueryParameter("reason", "transport")
                    .addQueryParameter("productType", "web-player")
                    .addQueryParameter("totp", totp)
                    .addQueryParameter("totpServer", totp)
                    .addQueryParameter("totpVer", nuance.version.toString())
                    .build()

            val request =
                Request
                    .Builder()
                    .url(url)
                    .header("User-Agent", WEB_USER_AGENT)
                    .header("Accept", "application/json")
                    .header("Referer", WEB_REFERER)
                    .header("Cookie", "sp_dc=$spDc")
                    .get()
                    .build()

            client.newCall(request).execute().use { response ->
                json.decodeFromString<WebTokenResponse>(
                    response.requireBody("Spotify web token"),
                ).toToken()
            }
        }

    private suspend fun getLatestNuance(): SpotifyNuance =
        cachedNuance ?: withContext(Dispatchers.IO) {
            val request =
                Request
                    .Builder()
                    .url(NUANCE_GIST_URL)
                    .header("User-Agent", "MetroFuse")
                    .header("Accept", "application/vnd.github+json")
                    .get()
                    .build()

            client.newCall(request).execute().use { response ->
                val root = json.parseToJsonElement(response.requireBody("Spotify nuance")).jsonObject
                val content =
                    root.obj("files")
                        ?.obj("nuances.json")
                        ?.string("content")
                        ?: error("Spotify nuance gist missing nuances.json")
                val nuance =
                    (json.parseToJsonElement(content) as? JsonArray)
                        .orEmpty()
                        .mapNotNull { element ->
                            val obj = element as? JsonObject ?: return@mapNotNull null
                            SpotifyNuance(
                                secret = obj.string("s") ?: return@mapNotNull null,
                                version = obj.int("v") ?: return@mapNotNull null,
                            )
                        }.maxByOrNull { it.version }
                        ?: error("Spotify nuance gist returned no usable entries")

                cachedNuance = nuance
                nuance
            }
        }

    private suspend fun getSpotifyServerTimeSeconds(): Long =
        withContext(Dispatchers.IO) {
            val request =
                Request
                    .Builder()
                    .url(SERVER_TIME_URL)
                    .header("User-Agent", WEB_USER_AGENT)
                    .header("Accept", "application/json")
                    .get()
                    .build()

            client.newCall(request).execute().use { response ->
                val root = json.parseToJsonElement(response.requireBody("Spotify server time")).jsonObject
                root.long("serverTime") ?: System.currentTimeMillis() / 1000L
            }
        }

    private fun generateTotp(
        base32Secret: String,
        timestampSeconds: Long,
    ): String {
        val counter = timestampSeconds / 30L
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(decodeBase32(base32Secret), "HmacSHA1"))
        val hash = mac.doFinal(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(counter).array())
        val offset = hash.last().toInt() and 0x0f
        val binary =
            ((hash[offset].toInt() and 0x7f) shl 24) or
                ((hash[offset + 1].toInt() and 0xff) shl 16) or
                ((hash[offset + 2].toInt() and 0xff) shl 8) or
                (hash[offset + 3].toInt() and 0xff)
        return (binary % 1_000_000).toString().padStart(6, '0')
    }

    private fun decodeBase32(value: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        var buffer = 0
        var bitsLeft = 0
        val output = mutableListOf<Byte>()
        value
            .uppercase()
            .filter { it != '=' && !it.isWhitespace() }
            .forEach { char ->
                val index = alphabet.indexOf(char)
                require(index >= 0) { "Invalid base32 character in Spotify nuance" }
                buffer = (buffer shl 5) or index
                bitsLeft += 5
                if (bitsLeft >= 8) {
                    bitsLeft -= 8
                    output += ((buffer shr bitsLeft) and 0xff).toByte()
                }
            }
        return output.toByteArray()
    }

    private suspend fun createDesktopAccessToken(spDc: String): DesktopAccessToken {
        val authorization = initiateDesktopDeviceAuthorization()
        val flowClient = newDesktopDeviceFlowClient(spDc)
        val verification =
            parseDesktopVerificationPage(
                flowClient = flowClient,
                url = authorization.verificationUriComplete,
            )
        submitDesktopUserCode(
            flowClient = flowClient,
            userCode = authorization.userCode,
            flowContext = verification.flowContext,
            csrfToken = verification.csrfToken,
            refererUrl = authorization.verificationUriComplete,
        )
        return exchangeDesktopDeviceCode(authorization.deviceCode)
    }

    private suspend fun initiateDesktopDeviceAuthorization(): DesktopDeviceAuthorization =
        withContext(Dispatchers.IO) {
            val request =
                Request
                    .Builder()
                    .url(DEVICE_AUTH_URL)
                    .header("User-Agent", DESKTOP_USER_AGENT)
                    .post(
                        FormBody
                            .Builder()
                            .add("client_id", DEVICE_CLIENT_ID)
                            .add("scope", DEVICE_SCOPE)
                            .build(),
                    ).build()

            client.newCall(request).execute().use { response ->
                json.decodeFromString<DesktopDeviceAuthorizationResponse>(
                    response.requireBody("device authorization"),
                ).toAuth()
            }
        }

    private suspend fun parseDesktopVerificationPage(
        flowClient: OkHttpClient,
        url: String,
    ): DesktopVerificationContext =
        withContext(Dispatchers.IO) {
            val request =
                Request
                    .Builder()
                    .url(url)
                    .header("User-Agent", DESKTOP_USER_AGENT)
                    .get()
                    .build()

            flowClient.newCall(request).execute().use { response ->
                val flowContext =
                    response.request.url
                        .queryParameter("flow_ctx")
                        ?.substringBefore(':')
                        ?: error("Spotify verification page missing flow_ctx")

                DesktopVerificationContext(
                    flowContext = flowContext,
                    csrfToken = extractDesktopCsrfToken(response.requireBody("verification page")),
                )
            }
        }

    private suspend fun submitDesktopUserCode(
        flowClient: OkHttpClient,
        userCode: String,
        flowContext: String,
        csrfToken: String,
        refererUrl: String,
    ) = withContext(Dispatchers.IO) {
        val url =
            DEVICE_RESOLVE_URL
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("flow_ctx", "$flowContext:${System.currentTimeMillis() / 1000}")
                .build()

        val request =
            Request
                .Builder()
                .url(url)
                .header("User-Agent", DESKTOP_USER_AGENT)
                .header("x-csrf-token", csrfToken)
                .header("referer", refererUrl)
                .header("origin", "https://accounts.spotify.com")
                .post(
                    json
                        .encodeToString(DesktopResolveRequest(userCode))
                        .toRequestBody(JSON_MEDIA_TYPE),
                ).build()

        flowClient.newCall(request).execute().use { response ->
            val result =
                json.decodeFromString<DesktopResolveResponse>(
                    response.requireBody("device confirmation"),
                ).result
            check(result == "ok") { "Spotify device confirmation failed: $result" }
        }
    }

    private suspend fun exchangeDesktopDeviceCode(deviceCode: String): DesktopAccessToken =
        withContext(Dispatchers.IO) {
            val request =
                Request
                    .Builder()
                    .url(DEVICE_TOKEN_URL)
                    .header("User-Agent", DESKTOP_USER_AGENT)
                    .post(
                        FormBody
                            .Builder()
                            .add("client_id", DEVICE_CLIENT_ID)
                            .add("device_code", deviceCode)
                            .add("grant_type", DEVICE_GRANT_TYPE)
                            .build(),
                    ).build()

            client.newCall(request).execute().use { response ->
                json.decodeFromString<DesktopTokenResponse>(
                    response.requireBody("token exchange"),
                ).toToken()
            }
        }

    private fun newDesktopDeviceFlowClient(spDc: String): OkHttpClient {
        val cookieStore =
            DesktopCookieStore().apply {
                seed(
                    Cookie
                        .Builder()
                        .name("sp_dc")
                        .value(spDc)
                        .domain("accounts.spotify.com")
                        .path("/")
                        .secure()
                        .httpOnly()
                        .build(),
                )
                seed(
                    Cookie
                        .Builder()
                        .name("sp_dc")
                        .value(spDc)
                        .domain("spotify.com")
                        .path("/")
                        .secure()
                        .httpOnly()
                        .build(),
                )
            }

        return client
            .newBuilder()
            .addNetworkInterceptor { chain ->
                val originalRequest = chain.request()
                val mergedCookie =
                    mergeCookieHeader(
                        originalRequest.header("Cookie"),
                        cookieStore.loadForRequest(originalRequest.url),
                    )

                val request =
                    originalRequest
                        .newBuilder()
                        .header("User-Agent", DESKTOP_USER_AGENT)
                        .apply {
                            if (mergedCookie.isNotBlank()) {
                                header("Cookie", mergedCookie)
                            }
                            if (originalRequest.header("Referer").isNullOrBlank()) {
                                header("Referer", WEB_REFERER)
                            }
                        }.build()

                val response = chain.proceed(request)
                response
                    .headers("Set-Cookie")
                    .forEach { rawCookie ->
                        Cookie.parse(request.url, rawCookie)?.let(cookieStore::store)
                    }
                response
            }.build()
    }

    private fun extractDesktopCsrfToken(html: String): String {
        val nextData =
            NEXT_DATA_REGEX
                .find(html)
                ?.groupValues
                ?.get(1)
                ?: error("Spotify verification page missing __NEXT_DATA__")

        return json.decodeFromString<DesktopNextData>(nextData).props?.initialToken
            ?: error("Spotify verification page missing CSRF token")
    }

    private fun buildExpectation(mediaMetadata: MediaMetadata): Expectation? {
        val title = mediaMetadata.title.trim().takeIf { it.isNotBlank() } ?: return null
        val artists =
            mediaMetadata.artists
                .map { it.name.trim() }
                .filter { it.isNotBlank() }
        val album = mediaMetadata.album?.title?.trim()?.takeIf { it.isNotBlank() }
        val durationMs = mediaMetadata.duration.takeIf { it > 0 }?.times(1000L)
        val isrc = ProviderIsrc.firstOf(mediaMetadata.id)
        return if (artists.isEmpty() && album == null && isrc == null) {
            null
        } else {
            Expectation(title, artists, album, durationMs, isrc)
        }
    }

    private fun buildQueries(expectation: Expectation): List<String> {
        val title = sanitize(expectation.title)
        val artistQuery = expectation.artists.joinToString(" ").ifBlank { null }
        val albumQuery = expectation.album?.let(::sanitize)

        return listOfNotNull(
            expectation.isrc?.let { "isrc:$it" },
            listOfNotNull(artistQuery, title, albumQuery).joinToString(" ").trim().takeIf { it.isNotBlank() },
            listOfNotNull(artistQuery, title).joinToString(" ").trim().takeIf { it.isNotBlank() },
            title.takeIf { it.isNotBlank() },
        ).distinct()
    }

    private fun scoreTrack(
        candidate: SearchTrack,
        expectation: Expectation,
    ): Int {
        val title = normalizeForMatch(candidate.name.orEmpty())
        val expectedTitle = normalizeForMatch(expectation.title)
        val artists =
            candidate.artists
                ?.items
                .orEmpty()
                .mapNotNull { it.profile?.name }
                .map(::normalizeForMatch)
        val expectedArtists = expectation.artists.map(::normalizeForMatch)
        val album = normalizeForMatch(candidate.albumOfTrack?.name.orEmpty())
        val expectedAlbum = normalizeForMatch(expectation.album.orEmpty())
        val expectedIsrc = expectation.isrc
        val candidateIsrc = ProviderIsrc.normalize(candidate.isrc)
        val durationPenalty =
            expectation.durationMs?.let { expectedDuration ->
                abs((candidate.duration?.totalMilliseconds ?: expectedDuration) - expectedDuration)
            } ?: 0L

        val titleScore =
            when {
                title == expectedTitle -> 40
                title.contains(expectedTitle) -> 30
                expectedTitle.contains(title) -> 24
                overlap(title, expectedTitle) >= 0.75 -> 18
                overlap(title, expectedTitle) >= 0.5 -> 10
                else -> 0
            }

        val artistScore =
            expectedArtists.sumOf { expectedArtist ->
                when {
                    artists.any { it == expectedArtist } -> 18
                    artists.any { it.contains(expectedArtist) || expectedArtist.contains(it) } -> 10
                    else -> 0
                }
            }

        val albumScore =
            when {
                expectedAlbum.isBlank() -> 0
                album == expectedAlbum -> 10
                album.contains(expectedAlbum) || expectedAlbum.contains(album) -> 6
                else -> 0
            }

        val durationScore =
            when {
                durationPenalty <= 2_000L -> 20
                durationPenalty <= 5_000L -> 14
                durationPenalty <= 10_000L -> 8
                durationPenalty >= 30_000L -> -12
                else -> 0
            }
        val isrcScore =
            when {
                expectedIsrc == null || candidateIsrc == null -> 0
                expectedIsrc.equals(candidateIsrc, ignoreCase = true) -> 110
                else -> -120
            }

        return isrcScore + titleScore + artistScore + albumScore + durationScore - disfavoredPenalty(
            candidateTitle = candidate.name.orEmpty(),
            expectedTitle = expectation.title,
        )
    }

    private fun buildCanvasHeaders(trackUri: String): Map<String, String> =
        mapOf(
            "User-Agent" to WEB_USER_AGENT,
            "Referer" to "https://open.spotify.com/track/${trackUri.substringAfterLast(':')}",
            "Origin" to WEB_ORIGIN,
            "Accept" to "*/*",
        )

    private fun extractSpDc(cookie: String): String? = extractSpotifyCookieValue(cookie, "sp_dc")

    private fun spotifyEntityId(
        rawId: String,
        type: String,
    ): String? {
        val trimmed =
            runCatching { URLDecoder.decode(rawId.trim(), "UTF-8") }
                .getOrDefault(rawId.trim())
        if (trimmed.isBlank()) return null

        val lower = trimmed.lowercase()
        val urlMarker = "/$type/"
        val fromUrl =
            lower
                .indexOf(urlMarker)
                .takeIf { it >= 0 }
                ?.let { index -> trimmed.substring(index + urlMarker.length) }
        val candidate =
            (fromUrl ?: trimmed)
                .substringBefore('?')
                .substringBefore('#')
                .trim()
                .trim('/')
                .substringBefore('/')
                .substringAfterLast(':')

        return candidate.takeIf { it.isNotBlank() && it != "null" }
    }

    private fun mergeCookieHeader(
        original: String?,
        scoped: List<Cookie>,
    ): String {
        val cookies = linkedMapOf<String, String>()
        original
            ?.split(';')
            ?.map { it.trim() }
            ?.filter { it.contains('=') }
            ?.forEach { cookie ->
                cookies[cookie.substringBefore('=')] = cookie.substringAfter('=')
            }
        scoped.forEach { cookie ->
            cookies[cookie.name] = cookie.value
        }
        return cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun Response.requireBody(step: String): String {
        val text = body.string()
        check(isSuccessful) { "$step failed: ${text.ifBlank { "$code $message" }}" }
        return text.ifBlank { error("$step returned an empty body") }
    }

    private class DesktopCookieStore {
        private val cookies = mutableListOf<Cookie>()

        @Synchronized
        fun seed(cookie: Cookie) {
            store(cookie)
        }

        @Synchronized
        fun store(cookie: Cookie) {
            cookies.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
            if (cookie.expiresAt > System.currentTimeMillis()) {
                cookies += cookie
            }
        }

        @Synchronized
        fun loadForRequest(url: HttpUrl): List<Cookie> =
            cookies.filter { cookie ->
                cookie.expiresAt > System.currentTimeMillis() && cookie.matches(url)
            }
    }

    @Serializable
    private data class DesktopDeviceAuthorizationResponse(
        @SerialName("device_code") val deviceCode: String,
        @SerialName("user_code") val userCode: String,
        @SerialName("verification_uri_complete") val verificationUriComplete: String,
    ) {
        fun toAuth() = DesktopDeviceAuthorization(deviceCode, userCode, verificationUriComplete)
    }

    @Serializable
    private data class DesktopResolveRequest(
        val code: String,
    )

    @Serializable
    private data class DesktopResolveResponse(
        val result: String,
    )

    @Serializable
    private data class DesktopTokenResponse(
        @SerialName("access_token") val accessToken: String,
        @SerialName("expires_in") val expiresIn: Int,
    ) {
        fun toToken() = DesktopAccessToken(accessToken, expiresIn)
    }

    @Serializable
    private data class DesktopNextData(
        val props: DesktopNextDataProps? = null,
    )

    @Serializable
    private data class DesktopNextDataProps(
        val initialToken: String? = null,
    )

    private data class DesktopDeviceAuthorization(
        val deviceCode: String,
        val userCode: String,
        val verificationUriComplete: String,
    )

    private data class DesktopVerificationContext(
        val flowContext: String,
        val csrfToken: String,
    )

    private data class DesktopAccessToken(
        val accessToken: String,
        val expiresIn: Int,
    )

    @Serializable
    private data class WebTokenResponse(
        val accessToken: String? = null,
        val accessTokenExpirationTimestampMs: Long? = null,
    ) {
        fun toToken(): WebAccessToken =
            WebAccessToken(
                accessToken = accessToken ?: error("Spotify web token response missing accessToken"),
                expiresAtMs = accessTokenExpirationTimestampMs ?: (System.currentTimeMillis() + 3_000_000L),
            )
    }

    private suspend fun resolveSpotifyGraphPlaylistTrackPage(
        content: JsonObject?,
        normalizedCookie: String,
        tokenProvider: suspend (String) -> String,
    ): SpotifyGraphPlaylistTrackPage {
        val rawItems = content?.array("items").orEmpty()
        val entries =
            rawItems.mapNotNull { item ->
                val data = item.obj?.spotifyGraphPlaylistTrackData() ?: return@mapNotNull null
                val parsedSong = data.toSpotifyInitialStatePlaylistSong()
                val ref = data.spotifyGraphPlaylistTrackRef()
                if (parsedSong == null && ref == null) {
                    null
                } else {
                    SpotifyGraphPlaylistTrackEntry(parsedSong, ref)
                }
            }
        val parsedTrackIds = entries.mapNotNullTo(mutableSetOf()) { it.song?.id?.spotifyTrackId() }
        val refsToHydrate =
            entries
                .mapNotNull { it.ref }
                .filterNot { it.id in parsedTrackIds }
                .distinctBy { it.id }
        val hydratedTracks = hydrateSpotifyGraphPlaylistTracks(refsToHydrate, normalizedCookie, tokenProvider)
        val songs =
            entries.mapNotNull { entry ->
                entry.song ?: entry.ref?.let { hydratedTracks[it.id] }
            }

        return SpotifyGraphPlaylistTrackPage(
            songs = songs,
            rawItemCount = rawItems.size,
            trackIds =
                entries.mapNotNull { entry ->
                    entry.song?.id?.spotifyTrackId() ?: entry.ref?.id
                },
        )
    }

    private suspend fun hydrateSpotifyGraphPlaylistTracks(
        refs: List<SpotifyGraphPlaylistTrackRef>,
        normalizedCookie: String,
        tokenProvider: suspend (String) -> String,
    ): Map<String, SongItem> {
        if (refs.isEmpty()) return emptyMap()
        return withContext(Dispatchers.IO) {
            val decoratedTracks =
                runCatching {
                    hydrateSpotifyGraphPlaylistTracksFromGraphQl(refs, normalizedCookie, tokenProvider)
                }.onFailure { error ->
                    Timber.w(error, "Spotify GraphQL playlist track decoration failed")
                }.getOrDefault(emptyMap())

            val missingRefs =
                refs
                    .filterNot { it.id in decoratedTracks }
                    .distinctBy { it.id }
            if (missingRefs.isEmpty()) return@withContext decoratedTracks

            val webApiTracks =
                runCatching {
                    missingRefs
                        .chunked(50)
                        .flatMap { chunk ->
                            val root =
                                spotifyApiGet(
                                    url =
                                        "https://api.spotify.com/v1/tracks"
                                            .toHttpUrl()
                                            .newBuilder()
                                            .addQueryParameter("ids", chunk.joinToString(",") { it.id })
                                            .addQueryParameter("market", "from_token")
                                            .build(),
                                    normalizedCookie = normalizedCookie,
                                    operation = "Spotify GraphQL playlist track metadata",
                                    tokenProvider = tokenProvider,
                                )
                            root
                                .array("tracks")
                                .orEmpty()
                                .mapNotNull { it.obj?.toSpotifyPlaylistSong() }
                        }.associateBy { it.id.spotifyTrackId() ?: it.id }
                }.onFailure { error ->
                    Timber.w(error, "Spotify playlist Web API track metadata fallback failed")
                }.getOrDefault(emptyMap())

            decoratedTracks + webApiTracks
        }
    }

    private suspend fun hydrateSpotifyTrackIds(
        trackIds: List<String>,
        normalizedCookie: String,
        tokenProvider: suspend (String) -> String,
    ): Map<String, SongItem> =
        hydrateSpotifyGraphPlaylistTracks(
            refs =
                trackIds
                    .filter { it.isNotBlank() }
                    .distinct()
                    .map(::SpotifyGraphPlaylistTrackRef),
            normalizedCookie = normalizedCookie,
            tokenProvider = tokenProvider,
        )

    private suspend fun hydrateSpotifyGraphPlaylistTracksFromGraphQl(
        refs: List<SpotifyGraphPlaylistTrackRef>,
        normalizedCookie: String,
        tokenProvider: suspend (String) -> String,
    ): Map<String, SongItem> =
        refs
            .distinctBy { it.id }
            .chunked(50)
            .flatMap { chunk ->
                postGraphQl<JsonObject>(
                    operation = "decorateContextTracks",
                    variables =
                        buildJsonObject {
                            put(
                                "uris",
                                JsonArray(
                                    chunk.map { ref ->
                                        JsonPrimitive("spotify:track:${ref.id}")
                                    },
                                ),
                            )
                        },
                    cookie = normalizedCookie,
                    hashOverride = SPOTIFY_DECORATE_CONTEXT_TRACKS_HASH,
                    tokenProvider = tokenProvider,
                ).obj("data")
                    ?.array("tracks")
                    .orEmpty()
                    .mapNotNull { it.obj?.toSpotifyInitialStatePlaylistSong() }
            }.associateBy { it.id.spotifyTrackId() ?: it.id }

    private fun JsonObject.spotifyGraphPlaylistTrackData(): JsonObject? {
        val wrapper =
            obj("itemV2")
                ?: obj("item")
                ?: obj("entity")
                ?: obj("track")
                ?: obj("data")
                ?: this
        return wrapper.obj("data") ?: wrapper
    }

    private fun JsonObject.spotifyGraphPlaylistTrackRef(): SpotifyGraphPlaylistTrackRef? {
        val id =
            string("uri")?.spotifyTrackId()
                ?: obj("track")?.string("uri")?.spotifyTrackId()
                ?: obj("item")?.string("uri")?.spotifyTrackId()
                ?: string("id")?.spotifyTrackId()
        return id?.let(::SpotifyGraphPlaylistTrackRef)
    }

    private fun JsonObject.spotifyGraphTrackId(): String? =
        string("uri")?.spotifyTrackId()
            ?: string("id")?.spotifyTrackId()
            ?: obj("data")?.spotifyGraphTrackId()
            ?: obj("track")?.spotifyGraphTrackId()
            ?: obj("item")?.spotifyGraphTrackId()

    private fun JsonObject.spotifyGraphContentTotal(): Int? =
        long("totalCount")?.toInt()
            ?: long("total")?.toInt()
            ?: obj("pagingInfo")?.long("totalCount")?.toInt()
            ?: obj("pagingInfo")?.long("total")?.toInt()

    private fun JsonObject.spotifyPlaylistTotal(): Int? =
        obj("attributes")?.long("totalTrackCount")?.toInt()
            ?: obj("attributes")?.long("totalTracks")?.toInt()
            ?: obj("content")?.spotifyGraphContentTotal()
            ?: obj("tracks")?.long("total")?.toInt()

    private fun JsonObject.toSpotifyAlbumSong(albumObject: JsonObject): SongItem? {
        val id = string("id") ?: string("uri")?.substringAfterLast(':') ?: return null
        val title = string("name") ?: return null
        cacheTrackIsrc(id, obj("external_ids")?.string("isrc"))
        val artists =
            array("artists")
                .orEmpty()
                .mapNotNull { artist ->
                    val obj = artist.obj ?: return@mapNotNull null
                    val name = obj.string("name") ?: return@mapNotNull null
                    Artist(
                        name = name,
                        id = obj.string("id")?.let { "spotify:artist:$it" },
                    )
                }

        return SongItem(
            id = "spotify:track:$id",
            title = title,
            artists = artists,
            album =
                albumObject.string("name")?.let { name ->
                    Album(
                        name = name,
                        id = albumObject.string("id")?.let { "spotify:album:$it" } ?: "",
                    )
                },
            duration = long("duration_ms")?.div(1000)?.toInt(),
            thumbnail = albumObject.spotifyWebApiImageUrl().orEmpty(),
            explicit = boolean("explicit"),
        )
    }

    private fun JsonObject.toSpotifyPlaylistSong(): SongItem? {
        val id = string("id") ?: string("uri")?.substringAfterLast(':') ?: return null
        val title = string("name") ?: return null
        cacheTrackIsrc(id, obj("external_ids")?.string("isrc"))
        val albumObject = obj("album")
        val artists =
            array("artists")
                .orEmpty()
                .mapNotNull { artist ->
                    val obj = artist.obj ?: return@mapNotNull null
                    val name = obj.string("name") ?: return@mapNotNull null
                    Artist(
                        name = name,
                        id = obj.string("id")?.let { "spotify:artist:$it" },
                    )
                }

        return SongItem(
            id = "spotify:track:$id",
            title = title,
            artists = artists,
            album =
                albumObject?.string("name")?.let { name ->
                    Album(
                        name = name,
                        id = albumObject.string("id")?.let { "spotify:album:$it" } ?: "",
                    )
                },
            duration = long("duration_ms")?.div(1000)?.toInt(),
            thumbnail = albumObject?.spotifyWebApiImageUrl().orEmpty(),
            explicit = boolean("explicit"),
        )
    }

    private fun JsonObject.toSearchTrackCandidate(): SearchTrack? {
        val id = spotifyEntityId(string("id") ?: string("uri") ?: return null, "track") ?: return null
        val isrc = obj("external_ids")?.string("isrc")?.let(ProviderIsrc::normalize)
        cacheTrackIsrc(id, isrc)
        return SearchTrack(
            uri = "spotify:track:$id",
            name = string("name"),
            duration = SearchDuration(long("duration_ms")),
            artists =
                SearchArtists(
                    array("artists")
                        .orEmpty()
                        .mapNotNull { artist ->
                            val name = artist.obj?.string("name") ?: return@mapNotNull null
                            SearchArtist(SearchArtistProfile(name))
                        },
                ),
            albumOfTrack = obj("album")?.string("name")?.let(::SearchAlbum),
            isrc = isrc,
        )
    }

    private fun JsonObject.toSpotifyInitialStatePlaylistSong(): SongItem? {
        val id = string("id") ?: string("uri")?.substringAfterLast(':') ?: return null
        val title = string("name") ?: return null
        val albumObject = obj("albumOfTrack") ?: obj("album")
        val artists =
            (
                obj("artists")?.array("items")
                    ?: array("artists")
            )
                .orEmpty()
                .mapNotNull { artist ->
                    val data = artist.obj?.obj("data") ?: artist.obj ?: return@mapNotNull null
                    val name =
                        data.obj("profile")?.string("name")
                            ?: data.string("name")
                            ?: return@mapNotNull null
                    val artistId = data.string("id") ?: data.string("uri")?.substringAfterLast(':')
                    Artist(
                        name = name,
                        id = artistId?.let { "spotify:artist:$it" },
                    )
                }

        return SongItem(
            id = "spotify:track:$id",
            title = title,
            artists = artists,
            album =
                albumObject?.string("name")?.let { name ->
                    Album(
                        name = name,
                        id =
                            albumObject.string("id")?.let { "spotify:album:$it" }
                                ?: albumObject.string("uri")
                                ?: "",
                    )
                },
            duration =
                obj("duration")
                    ?.long("totalMilliseconds")
                    ?.div(1000)
                    ?.toInt()
                    ?: long("duration_ms")?.div(1000)?.toInt(),
            thumbnail = albumObject?.spotifyInitialStateImageUrl().orEmpty(),
            explicit =
                obj("contentRating")
                    ?.toString()
                    ?.contains("EXPLICIT", ignoreCase = true) == true ||
                    boolean("explicit"),
        )
    }

    private fun JsonObject.spotifyMobilePlaylistTotal(): Int? =
        int("total")
            ?: long("total")?.toInt()
            ?: obj("tracks")?.long("total")?.toInt()
            ?: obj("playlist")?.long("total")?.toInt()
            ?: obj("contents")?.long("total")?.toInt()

    private fun JsonObject.spotifyMobilePlaylistTracks(): List<SongItem> =
        listOfNotNull(
            array("items"),
            obj("tracks")?.array("items"),
            obj("playlist")?.array("items"),
            obj("contents")?.array("items"),
        ).firstOrNull { it.isNotEmpty() }
            .orEmpty()
            .mapNotNull { item ->
                val root = item.obj ?: return@mapNotNull null
                (
                    root.obj("track")
                        ?: root.obj("item")
                        ?: root.obj("entity")
                        ?: root.obj("track_metadata")
                        ?: root.obj("metadata")
                        ?: root
                ).spotifyWrappedData()?.toSpotifyMobilePlaylistSong(root)
            }

    private fun JsonObject.toSpotifyMobilePlaylistSong(wrapper: JsonObject? = null): SongItem? {
        val track =
            obj("track")
                ?: obj("item")
                ?: obj("entity")
                ?: obj("track_metadata")
                ?: this
        val id =
            track.string("id")
                ?: track.string("uri")?.substringAfterLast(':')
                ?: wrapper?.string("uri")?.substringAfterLast(':')
                ?: return null
        val title =
            track.string("name")
                ?: track.string("title")
                ?: wrapper?.string("name")
                ?: wrapper?.string("title")
                ?: return null
        val albumObject = track.obj("album") ?: track.obj("albumOfTrack") ?: wrapper?.obj("album")
        val artists =
            track.spotifyAnyArtists()
                .ifEmpty { wrapper?.spotifyAnyArtists().orEmpty() }

        return SongItem(
            id = "spotify:track:$id",
            title = title,
            artists = artists,
            album =
                albumObject?.string("name")?.let { name ->
                    Album(
                        name = name,
                        id = albumObject.string("id")?.let { "spotify:album:$it" } ?: albumObject.string("uri").orEmpty(),
                    )
                },
            duration =
                track.spotifyDurationSeconds()
                    ?: wrapper?.spotifyDurationSeconds(),
            thumbnail =
                albumObject?.spotifyInitialStateImageUrl()
                    ?: track.spotifyInitialStateImageUrl()
                    ?: wrapper?.spotifyInitialStateImageUrl()
                    ?: "",
            explicit =
                track.boolean("explicit") ||
                    wrapper?.boolean("explicit") == true ||
                    track.obj("contentRating")?.toString()?.contains("EXPLICIT", ignoreCase = true) == true,
        )
    }

    private fun JsonObject.spotifyTrackSongs(limit: Int = 75): List<SongItem> {
        val songs = linkedMapOf<String, SongItem>()

        fun addSong(song: SongItem?) {
            val item = song ?: return
            val key = item.id.spotifyTrackId() ?: item.id
            if (key.isNotBlank()) songs.putIfAbsent(key, item)
        }

        fun collect(
            element: JsonElement?,
            depth: Int,
        ) {
            if (element == null || depth > 8 || songs.size >= limit) return

            when (element) {
                is JsonArray -> {
                    element.forEach { child ->
                        if (songs.size < limit) collect(child, depth + 1)
                    }
                }
                is JsonObject -> {
                    val directTrack =
                        element.obj("track")
                            ?: element.obj("item")
                            ?: element.obj("entity")
                            ?: element.obj("track_metadata")
                            ?: element.obj("metadata")
                            ?: element.obj("data")
                            ?: element
                    val wrappedTrack = directTrack.spotifyWrappedData()
                    val parseTarget = wrappedTrack ?: directTrack
                    if (parseTarget.isProbablySpotifyTrack(element)) {
                        addSong(parseTarget.toSpotifyMobilePlaylistSong(element))
                        addSong(parseTarget.toSpotifyPlaylistSong())
                        addSong(parseTarget.toSpotifyInitialStatePlaylistSong())
                    }

                    val priorityKeys =
                        listOf(
                            "tracks",
                            "recommended_tracks",
                            "recommendedTracks",
                            "recommendations",
                            "items",
                            "contents",
                            "content",
                            "entities",
                            "results",
                            "children",
                            "rows",
                            "data",
                            "item",
                            "track",
                            "entity",
                            "metadata",
                            "track_metadata",
                        )
                    priorityKeys.forEach { key ->
                        if (songs.size < limit) collect(element[key], depth + 1)
                    }
                    if (depth <= 3) {
                        element.values.forEach { child ->
                            if (songs.size < limit) collect(child, depth + 1)
                        }
                    }
                }
                else -> Unit
            }
        }

        collect(this, 0)
        return songs.values.take(limit)
    }

    private fun JsonObject.isProbablySpotifyTrack(wrapper: JsonObject? = null): Boolean {
        val uri = string("uri") ?: wrapper?.string("uri")
        if (uri?.startsWith("spotify:track:", ignoreCase = true) == true) return true

        val type = string("type") ?: obj("data")?.string("type")
        if (type.equals("track", ignoreCase = true)) return true

        val typename = string("__typename") ?: obj("data")?.string("__typename")
        if (typename?.contains("Track", ignoreCase = true) == true) return true

        val hasTitle = string("name") != null || string("title") != null || wrapper?.string("name") != null
        val hasArtists =
            obj("artists") != null ||
                array("artists") != null ||
                obj("artist") != null ||
                string("artist") != null ||
                wrapper?.obj("artists") != null ||
                wrapper?.array("artists") != null
        val hasTrackShape =
            obj("album") != null ||
                obj("albumOfTrack") != null ||
                long("duration_ms") != null ||
                long("durationMs") != null ||
                long("length_ms") != null ||
                obj("duration") != null

        return hasTitle && hasArtists && hasTrackShape
    }

    private fun JsonObject.spotifyAnyArtists(): List<Artist> {
        val fromArray =
            (
                obj("artists")?.array("items")
                    ?: array("artists")
                    ?: obj("artist")?.array("items")
            ).orEmpty()
                .mapNotNull { artist ->
                    val data = artist.obj?.obj("data") ?: artist.obj
                    val name =
                        data?.obj("profile")?.string("name")
                            ?: data?.string("name")
                            ?: artist.stringValueOrNull()
                            ?: return@mapNotNull null
                    val artistId = data?.string("id") ?: data?.string("uri")?.substringAfterLast(':')
                    Artist(name = name, id = artistId?.let { "spotify:artist:$it" })
                }

        return fromArray.ifEmpty {
            val name = obj("artist")?.string("name") ?: string("artist")
            listOfNotNull(name?.let { Artist(name = it, id = obj("artist")?.string("id")?.let { id -> "spotify:artist:$id" }) })
        }
    }

    private fun JsonObject.spotifyDurationSeconds(): Int? {
        val durationMs =
            obj("duration")?.long("totalMilliseconds")
                ?: long("duration_ms")
                ?: long("durationMs")
                ?: long("length_ms")
        if (durationMs != null) return durationMs.div(1000).toInt()

        return long("duration")
            ?.let { duration ->
                if (duration > 1000) duration.div(1000).toInt() else duration.toInt()
            }
    }

    private fun JsonObject.spotifyWebApiImageUrl(): String? =
        array("images")
            .orEmpty()
            .firstNotNullOfOrNull { image ->
                image.obj?.string("url")
            }

    private fun JsonObject.spotifyArtistImageUrl(): String? =
        listOf(
            obj("visuals")?.obj("avatarImage"),
            obj("avatarImage"),
            obj("profile")?.obj("avatarImage"),
            obj("profile")?.obj("avatar"),
            obj("avatar"),
            obj("image"),
            obj("images"),
        ).firstNotNullOfOrNull { it?.spotifyInitialStateImageUrl() }
            ?: spotifyWebApiImageUrl()

    private fun JsonObject.spotifyArtistStatsText(): String? {
        spotifyArtistMonthlyListeners()
            ?.let { return "${it.spotifyGroupedCount()} monthly listeners" }

        spotifyArtistStatsString()
            ?.let { return it }

        spotifyArtistFollowers()
            ?.let { return "${it.spotifyGroupedCount()} followers" }

        return null
    }

    private fun JsonObject.spotifyArtistStatsString(): String? =
        findSpotifyStringValue(valueMatches = { value ->
            val normalized = value.lowercase(Locale.US)
            "monthly" in normalized && "listener" in normalized
        })

    private fun JsonObject.spotifyArtistMonthlyListeners(): Long? =
        listOfNotNull(
            obj("stats")?.long("monthlyListeners"),
            obj("stats")?.long("monthlyListenerCount"),
            obj("stats")?.obj("monthlyListeners")?.long("total"),
            obj("stats")?.obj("monthlyListenerCount")?.long("total"),
            long("monthlyListeners"),
            long("monthlyListenerCount"),
        ).firstOrNull { it > 0L }
            ?: findSpotifyLongValue(keyMatches = { key ->
                val normalized = key.lowercase(Locale.US)
                "monthly" in normalized && "listener" in normalized
            })

    private fun JsonObject.spotifyArtistFollowers(): Long? =
        listOfNotNull(
            obj("stats")?.long("followers"),
            obj("stats")?.long("followerCount"),
            obj("followers")?.long("total"),
            long("followers"),
            long("followerCount"),
        ).firstOrNull { it > 0L }
            ?: findSpotifyLongValue(keyMatches = { key ->
                val normalized = key.lowercase(Locale.US)
                "follower" in normalized
            })

    private fun JsonObject.findSpotifyLongValue(
        keyMatches: (String) -> Boolean,
        depth: Int = 0,
    ): Long? {
        if (depth > 5) return null
        entries.forEach { (key, value) ->
            if (keyMatches(key)) {
                when (value) {
                    is JsonPrimitive -> value.longOrNull?.takeIf { it > 0L }?.let { return it }
                    is JsonObject -> value.long("total")?.takeIf { it > 0L }?.let { return it }
                    else -> Unit
                }
            }
            when (value) {
                is JsonObject -> value.findSpotifyLongValue(keyMatches, depth + 1)?.let { return it }
                is JsonArray -> value.firstNotNullOfOrNull { it.obj?.findSpotifyLongValue(keyMatches, depth + 1) }?.let { return it }
                else -> Unit
            }
        }
        return null
    }

    private fun JsonObject.findSpotifyStringValue(
        valueMatches: (String) -> Boolean,
        depth: Int = 0,
    ): String? {
        if (depth > 5) return null
        entries.forEach { (_, value) ->
            when (value) {
                is JsonPrimitive -> value.contentOrNull?.takeIf(valueMatches)?.let { return it }
                is JsonObject -> value.findSpotifyStringValue(valueMatches, depth + 1)?.let { return it }
                is JsonArray -> value.firstNotNullOfOrNull { it.obj?.findSpotifyStringValue(valueMatches, depth + 1) }?.let { return it }
                else -> Unit
            }
        }
        return null
    }

    private fun Long.spotifyGroupedCount(): String = String.format(Locale.US, "%,d", this)

    private fun JsonObject.spotifyLegacyCoverArtUrl(): String? = spotifyInitialStateImageUrl()

    private fun JsonObject.spotifyInitialStateImageUrl(depth: Int = 0): String? {
        if (depth > 4) return null

        string("url")
            ?.takeIf { it.startsWith("http", ignoreCase = true) }
            ?.let { return it }

        array("sources")
            ?.firstNotNullOfOrNull { image ->
                image.obj?.string("url")?.takeIf { it.startsWith("http", ignoreCase = true) }
            }?.let { return it }

        array("items")
            ?.firstNotNullOfOrNull { item ->
                item.obj?.spotifyInitialStateImageUrl(depth + 1)
            }?.let { return it }

        return listOf("coverArt", "image", "images", "visuals", "avatarImage", "avatar", "profile", "albumOfTrack", "album")
            .firstNotNullOfOrNull { key ->
                obj(key)?.spotifyInitialStateImageUrl(depth + 1)
            }
    }

    private fun SpotifyPlaylistExtenderTrack.toSongItem(): SongItem? {
        val trackId = uri.spotifyTrackId() ?: return null
        return SongItem(
            id = "spotify:track:$trackId",
            title = name.takeIf { it.isNotBlank() } ?: return null,
            artists =
                artists.mapNotNull { artist ->
                    artist.name
                        ?.takeIf { it.isNotBlank() }
                        ?.let { name ->
                            Artist(
                                name = name,
                                id = artist.id?.takeIf { it.isNotBlank() }?.let { "spotify:artist:$it" },
                            )
                        }
                },
            album =
                album
                    ?.name
                    ?.takeIf { it.isNotBlank() }
                    ?.let { name ->
                        Album(
                            name = name,
                            id = album.id?.takeIf { it.isNotBlank() }?.let { "spotify:album:$it" } ?: "",
                        )
                    },
            thumbnail = album?.largeImageUrl ?: album?.imageUrl ?: "",
            explicit = explicit,
        )
    }

    private fun JsonObject.toSpotifyAlbumItem(): AlbumItem? {
        val id = spotifyEntityId(string("id") ?: string("uri") ?: return null, "album") ?: return null
        val title = string("name") ?: return null
        val artists =
            array("artists")
                .orEmpty()
                .mapNotNull { artist ->
                    val obj = artist.obj ?: return@mapNotNull null
                    val name = obj.string("name") ?: return@mapNotNull null
                    Artist(
                        name = name,
                        id = obj.string("id")?.let { "spotify:artist:$it" },
                    )
                }

        return AlbumItem(
            browseId = "spotify:album:$id",
            playlistId = "spotify:album:$id",
            title = title,
            artists = artists,
            year = string("release_date")?.take(4)?.toIntOrNull(),
            thumbnail = spotifyWebApiImageUrl().orEmpty(),
            explicit = false,
        )
    }

    private fun JsonObject.toSpotifyArtistItem(): ArtistItem? {
        val id = spotifyEntityId(string("id") ?: string("uri") ?: return null, "artist") ?: return null
        val title = string("name") ?: return null
        return ArtistItem(
            id = "spotify:artist:$id",
            title = title,
            thumbnail = spotifyWebApiImageUrl(),
            shuffleEndpoint = null,
            radioEndpoint = null,
        )
    }

    private fun JsonObject.toSpotifyPlaylistItem(): PlaylistItem? {
        val id = spotifyEntityId(string("id") ?: string("uri") ?: return null, "playlist") ?: return null
        val title = string("name") ?: return null
        return PlaylistItem(
            id = "spotify:playlist:$id",
            title = title,
            author =
                obj("owner")
                    ?.string("display_name")
                    ?.let { Artist(name = it, id = obj("owner")?.string("id")) },
            songCountText =
                obj("tracks")
                    ?.long("total")
                    ?.let { "$it songs" },
            thumbnail = spotifyWebApiImageUrl(),
            playEndpoint = null,
            shuffleEndpoint = null,
            radioEndpoint = null,
        )
    }

    private fun MutableList<SearchSummary>.addSpotifySummary(
        title: String,
        items: List<YTItem>,
    ) {
        val distinctItems = items.distinctBy { it.id }
        if (distinctItems.isNotEmpty()) {
            add(SearchSummary(title = title, items = distinctItems))
        }
    }

    private fun MutableList<HomePage.Section>.addSpotifyHomeSection(
        title: String,
        items: List<YTItem>,
    ) {
        val distinctItems = items.distinctBy { it.id }
        if (distinctItems.isNotEmpty()) {
            add(
                HomePage.Section(
                    title = title,
                    label = null,
                    thumbnail = distinctItems.firstOrNull()?.thumbnail,
                    endpoint = null,
                    items = distinctItems,
                ),
            )
        }
    }

    private fun spotifyLikedSongsPlaylist(
        songs: List<SongItem>,
        total: Int?,
    ): PlaylistItem? {
        val count = total ?: songs.size
        if (count <= 0 && songs.isEmpty()) return null

        return PlaylistItem(
            id = "spotify:collection:tracks",
            title = "Liked Songs",
            author = Artist(name = "Spotify", id = null),
            songCountText = count.takeIf { it > 0 }?.let { "$it songs" },
            thumbnail = songs.firstOrNull()?.thumbnail,
            playEndpoint = null,
            shuffleEndpoint = null,
            radioEndpoint = null,
        )
    }

    private fun parseCasitaMetadata(traits: ProtoMessage): Map<String, SpotifyCasitaEntity> {
        val batched = traits.firstMessage(1) ?: return emptyMap()
        return buildMap {
            batched.messages(2).forEach { extensionArray ->
                val extensionKind = extensionArray.int(2)
                extensionArray.messages(3).forEach { data ->
                    val uri = data.string(2)?.spotifyCanonicalHomeUri() ?: return@forEach
                    val any = data.firstMessage(3) ?: return@forEach
                    val typeUrl = any.string(1).orEmpty()
                    val bytes = any.firstBytes(2) ?: return@forEach
                    val entity =
                        when {
                            extensionKind == 10 || typeUrl.contains("Metadata\$Track") ->
                                parseCasitaTrackMetadata(uri, bytes)
                            extensionKind == 9 || typeUrl.contains("Metadata\$Album") ->
                                parseCasitaAlbumMetadata(uri, bytes)
                            extensionKind == 8 || typeUrl.contains("Metadata\$Artist") ->
                                parseCasitaArtistMetadata(uri, bytes)
                            extensionKind == 11 || extensionKind == 12 -> null
                            uri.spotifyHomeType() == "playlist" ||
                                typeUrl.contains("PlaylistMetadata", ignoreCase = true) ->
                                parseCasitaPlaylistMetadata(uri, bytes)
                            else -> parseGenericCasitaMetadata(uri, bytes)
                        } ?: return@forEach
                    put(entity.uri, entity)
                    if (entity.uri != uri) put(uri, entity.copy(uri = uri))
                }
            }
        }
    }

    private fun spotifyCasitaEagerloadQuery(): String {
        val requestedKinds = CASITA_EAGERLOAD_EXTENSION_KINDS.joinToString(prefix = "[", postfix = "]")
        val componentMapping =
            CASITA_EAGERLOAD_COMPONENT_TYPES.joinToString(",") { componentType ->
                """"$componentType":{"1":$requestedKinds,"2":$requestedKinds}"""
            }
        val payload = """{"mapping":{$componentMapping}}"""
        return Base64.getEncoder().encodeToString(payload.toByteArray(Charsets.UTF_8))
    }

    private fun ProtoMessage.toCasitaHomeSection(metadata: Map<String, SpotifyCasitaEntity>): HomePage.Section? {
        val itemEntities =
            collectCasitaItemEntities(metadata)
                .distinctBy { it.uri }
                .ifEmpty {
                    collectCasitaItemUris()
                        .distinct()
                        .mapNotNull { uri -> metadata[uri] ?: fallbackCasitaEntity(uri) }
                }
        if (itemEntities.isEmpty()) return null

        val items = itemEntities.mapNotNull { entity -> entity.toCasitaItem(entity.uri) }.distinctBy { it.id }
        if (items.isEmpty()) return null

        val title =
            casitaSectionTitle()
                ?: firstMessage(1)?.string(1)?.humanizeCasitaSectionId()
                ?: return null

        return HomePage.Section(
            title = title,
            label = null,
            thumbnail = items.firstOrNull()?.thumbnail,
            endpoint = null,
            items = items,
        )
    }

    private fun ProtoMessage.casitaSectionTitle(): String? =
        featureMessages()
            .firstNotNullOfOrNull { feature ->
                feature.messages(1)
                    .firstNotNullOfOrNull(::casitaHeadingTitle)
            }

    private fun casitaHeadingTitle(heading: ProtoMessage): String? =
        heading.firstMessage(2)?.string(1)?.takeIf(::isUsableCasitaText)
            ?: heading.firstMessage(5)?.string(1)?.takeIf(::isUsableCasitaText)
            ?: heading.firstMessage(6)?.collectCasitaStrings()?.firstOrNull(::isUsableCasitaText)
            ?: heading.firstMessage(4)?.collectCasitaStrings()?.firstOrNull(::isUsableCasitaText)
            ?: heading.firstMessage(3)?.collectCasitaStrings()?.firstOrNull(::isUsableCasitaText)

    private fun ProtoMessage.featureMessages(): List<ProtoMessage> =
        fields
            .filter { field -> field.number in 2..39 }
            .mapNotNull { field -> field.bytes?.let(::parseProtoMessageOrNull) }

    private fun ProtoMessage.collectCasitaItemUris(depth: Int = 0): List<String> {
        if (depth > 12) return emptyList()
        return buildList {
            string(1)?.spotifyCanonicalHomeUri()?.let(::add)
            fields.forEach { field ->
                val bytes = field.bytes ?: return@forEach
                parseProtoMessageOrNull(bytes)
                    ?.let { child -> addAll(child.collectCasitaItemUris(depth + 1)) }
            }
        }
    }

    private fun ProtoMessage.collectCasitaItemEntities(
        metadata: Map<String, SpotifyCasitaEntity>,
        depth: Int = 0,
    ): List<SpotifyCasitaEntity> {
        if (depth > 12) return emptyList()
        return buildList {
            val directStrings = directCasitaStrings()
            val directUris = directStrings.mapNotNull { it.spotifyCanonicalHomeUri() }.distinct()
            val directImages = directStrings.mapNotNull { it.spotifyImageUrlOrNull() }
            val directTexts =
                directStrings
                    .filter(::isUsableCasitaText)
                    .filterNot { text -> text.spotifyCanonicalHomeUri() != null || text.spotifyImageUrlOrNull() != null }

            directUris.forEach { uri ->
                add(
                    metadata[uri]
                        ?: fallbackCasitaEntity(uri)
                        ?: inferCasitaEntityFromCard(uri, directTexts, directImages)
                        ?: return@forEach
                )
            }

            fields.forEach { field ->
                val bytes = field.bytes ?: return@forEach
                parseProtoMessageOrNull(bytes)
                    ?.let { child -> addAll(child.collectCasitaItemEntities(metadata, depth + 1)) }
            }
        }
    }

    private fun ProtoMessage.directCasitaStrings(): List<String> =
        fields
            .mapNotNull { field -> field.bytes?.decodeToStringOrNull() }
            .distinct()

    private fun inferCasitaEntityFromCard(
        uri: String,
        texts: List<String>,
        images: List<String>,
    ): SpotifyCasitaEntity? {
        val type = uri.spotifyHomeType() ?: return null
        val title =
            texts
                .firstOrNull { text ->
                    !text.equals(type, ignoreCase = true) &&
                        !text.equals("spotify", ignoreCase = true)
                } ?: return null
        return SpotifyCasitaEntity(
            uri = uri,
            type = type,
            title = title,
            subtitle = texts.dropWhile { it != title }.drop(1).firstOrNull(),
            thumbnail = images.firstOrNull(),
        )
    }

    private fun parseCasitaTrackMetadata(
        fallbackUri: String,
        bytes: ByteArray,
    ): SpotifyCasitaEntity? {
        val message = parseProtoMessageOrNull(bytes) ?: return null
        val title = message.string(2)?.takeIf { it.isNotBlank() } ?: return null
        val albumEntity = message.firstMessage(3)?.let { parseCasitaAlbumMessage(null, it) }
        val artists =
            message.messages(4)
                .mapNotNull(::parseCasitaArtistSummary)
                .ifEmpty { albumEntity?.artists.orEmpty() }
        val uri = message.firstBytes(1)?.spotifyGidUri("track") ?: fallbackUri
        val explicit =
            message.bool(9) ||
                message.messages(25)
                    .flatMap { it.strings(2) }
                    .any { it.contains("explicit", ignoreCase = true) || it.equals("E", ignoreCase = true) }

        return SpotifyCasitaEntity(
            uri = uri,
            type = "track",
            title = title,
            subtitle = artists.joinToString(", ") { it.name }.ifBlank { albumEntity?.title },
            thumbnail = albumEntity?.thumbnail,
            artists = artists,
            album =
                albumEntity
                    ?.title
                    ?.let { albumTitle ->
                        Album(
                            name = albumTitle,
                            id = albumEntity.uri.takeIf { it.startsWith("spotify:album:", ignoreCase = true) }.orEmpty(),
                        )
                    },
            durationSeconds = message.int(7)?.takeIf { it > 0 }?.div(1000),
            explicit = explicit,
        )
    }

    private fun parseCasitaAlbumMetadata(
        fallbackUri: String,
        bytes: ByteArray,
    ): SpotifyCasitaEntity? =
        parseProtoMessageOrNull(bytes)?.let { parseCasitaAlbumMessage(fallbackUri, it) }

    private fun parseCasitaAlbumMessage(
        fallbackUri: String?,
        message: ProtoMessage,
    ): SpotifyCasitaEntity? {
        val title = message.string(2)?.takeIf { it.isNotBlank() } ?: return null
        val artists = message.messages(3).mapNotNull(::parseCasitaArtistSummary)
        val uri = message.firstBytes(1)?.spotifyGidUri("album") ?: fallbackUri ?: ""
        val thumbnail =
            message.firstMessage(17)?.casitaClassicImageGroupUrl()
                ?: message.messages(9).casitaClassicImageUrl()

        return SpotifyCasitaEntity(
            uri = uri,
            type = "album",
            title = title,
            subtitle = artists.joinToString(", ") { it.name }.ifBlank { null },
            thumbnail = thumbnail,
            artists = artists,
            year = message.firstMessage(6)?.int(1)?.takeIf { it > 0 },
        )
    }

    private fun parseCasitaArtistMetadata(
        fallbackUri: String,
        bytes: ByteArray,
    ): SpotifyCasitaEntity? =
        parseProtoMessageOrNull(bytes)?.let { message ->
            val title = message.string(2)?.takeIf { it.isNotBlank() } ?: return null
            SpotifyCasitaEntity(
                uri = message.firstBytes(1)?.spotifyGidUri("artist") ?: fallbackUri,
                type = "artist",
                title = title,
                subtitle = null,
                thumbnail =
                    message.firstMessage(17)?.casitaClassicImageGroupUrl()
                        ?: message.messages(11).casitaClassicImageUrl(),
            )
        }

    private fun parseCasitaPlaylistMetadata(
        fallbackUri: String,
        bytes: ByteArray,
    ): SpotifyCasitaEntity? {
        val message = parseProtoMessageOrNull(bytes) ?: return null
        val title = message.string(2)?.takeIf { it.isNotBlank() } ?: return null
        val owner =
            message.firstMessage(3)
                ?.let { user ->
                    user.string(3)
                        ?: user.string(2)
                }?.takeIf { it.isNotBlank() }
        return SpotifyCasitaEntity(
            uri = message.string(1)?.spotifyCanonicalHomeUri() ?: fallbackUri,
            type = "playlist",
            title = title,
            subtitle = owner,
            thumbnail = message.firstMessage(8)?.cosmosImageGroupUrl(),
            songCountText = message.int(6)?.takeIf { it > 0 }?.let { "$it songs" },
        )
    }

    private fun parseGenericCasitaMetadata(
        fallbackUri: String,
        bytes: ByteArray,
    ): SpotifyCasitaEntity? {
        val message = parseProtoMessageOrNull(bytes) ?: return null
        val strings = message.collectCasitaStrings()
        val title =
            strings.firstOrNull { text ->
                isUsableCasitaText(text) &&
                    text.spotifyCanonicalHomeUri() == null &&
                    !text.startsWith("type.googleapis.com/", ignoreCase = true)
            } ?: return null
        val thumbnail =
            strings
                .firstNotNullOfOrNull { it.spotifyImageUrlOrNull() }

        return SpotifyCasitaEntity(
            uri = fallbackUri,
            type = fallbackUri.spotifyHomeType().orEmpty(),
            title = title,
            subtitle =
                strings
                    .dropWhile { it != title }
                    .drop(1)
                    .firstOrNull(::isUsableCasitaText),
            thumbnail = thumbnail,
        )
    }

    private fun parseCasitaArtistSummary(message: ProtoMessage): Artist? {
        val name = message.string(2)?.takeIf { it.isNotBlank() } ?: return null
        return Artist(
            name = name,
            id = message.firstBytes(1)?.spotifyGidUri("artist"),
        )
    }

    private fun SpotifyCasitaEntity.toCasitaItem(itemUri: String = uri): YTItem? {
        val canonicalUri = itemUri.spotifyCanonicalHomeUri() ?: itemUri
        return when (canonicalUri.spotifyHomeType()) {
            "track" ->
                SongItem(
                    id = canonicalUri,
                    title = title,
                    artists = artists,
                    album = album,
                    duration = durationSeconds ?: 0,
                    thumbnail = thumbnail.orEmpty(),
                    explicit = explicit,
                )
            "album" ->
                AlbumItem(
                    browseId = canonicalUri,
                    playlistId = canonicalUri,
                    title = title,
                    artists = artists,
                    year = year,
                    thumbnail = thumbnail.orEmpty(),
                    explicit = explicit,
                )
            "artist" ->
                ArtistItem(
                    id = canonicalUri,
                    title = title,
                    thumbnail = thumbnail,
                    shuffleEndpoint = null,
                    radioEndpoint = null,
                )
            "playlist",
            "collection",
            ->
                PlaylistItem(
                    id = canonicalUri,
                    title = title,
                    author = subtitle?.let { Artist(name = it, id = null) },
                    songCountText = songCountText,
                    thumbnail = thumbnail,
                    playEndpoint = null,
                    shuffleEndpoint = null,
                    radioEndpoint = null,
                )
            else -> null
        }
    }

    private fun fallbackCasitaEntity(uri: String): SpotifyCasitaEntity? =
        when (uri.lowercase()) {
            "spotify:collection:tracks" ->
                SpotifyCasitaEntity(
                    uri = uri,
                    type = "collection",
                    title = "Liked Songs",
                    subtitle = "Spotify",
                    thumbnail = null,
                )
            else -> null
        }

    private fun List<ProtoMessage>.casitaClassicImageUrl(): String? =
        mapNotNull(::casitaClassicImage)
            .maxByOrNull { image -> image.width * image.height }
            ?.url

    private fun ProtoMessage.casitaClassicImageGroupUrl(): String? =
        messages(1).casitaClassicImageUrl()

    private fun ProtoMessage.cosmosImageGroupUrl(): String? =
        listOfNotNull(string(4), string(3), string(1), string(2))
            .firstNotNullOfOrNull { it.spotifyImageUrlOrNull() }

    private fun casitaClassicImage(message: ProtoMessage): SpotifyCasitaImage? {
        val fileId = message.firstBytes(1)?.toHex().orEmpty()
        if (fileId.isBlank()) return null
        return SpotifyCasitaImage(
            url = SPOTIFY_IMAGE_CDN_URL + fileId,
            width = message.int(3) ?: 0,
            height = message.int(4) ?: 0,
        )
    }

    private fun ProtoMessage.collectCasitaStrings(depth: Int = 0): List<String> {
        if (depth > 8) return emptyList()
        return buildList {
            fields.forEach { field ->
                val bytes = field.bytes ?: return@forEach
                bytes.decodeToStringOrNull()?.let(::add)
                parseProtoMessageOrNull(bytes)
                    ?.let { child -> addAll(child.collectCasitaStrings(depth + 1)) }
            }
        }.distinct()
    }

    private fun String.spotifyCanonicalHomeUri(): String? {
        val trimmed = trim()
        if (trimmed.matches(SPOTIFY_HOME_URI_REGEX)) return trimmed
        val match = SPOTIFY_OPEN_URL_REGEX.find(trimmed) ?: return null
        val type = match.groupValues[1].lowercase()
        val id = match.groupValues[2]
        return "spotify:$type:$id"
    }

    private fun String.spotifyHomeType(): String? =
        spotifyCanonicalHomeUri()
            ?.substringAfter("spotify:", "")
            ?.substringBefore(':')
            ?.lowercase()

    private fun String.spotifyImageUrlOrNull(): String? {
        val trimmed = trim()
        return when {
            trimmed.startsWith("https://", ignoreCase = true) ||
                trimmed.startsWith("http://", ignoreCase = true) -> trimmed
            trimmed.startsWith("spotify:image:", ignoreCase = true) ->
                SPOTIFY_IMAGE_CDN_URL + trimmed.substringAfterLast(':')
            else -> null
        }
    }

    private fun String.humanizeCasitaSectionId(): String? {
        val cleaned =
            replace(Regex("""[_\-.]+"""), " ")
                .replace(Regex("""\b(home|section|shelf|row|slot|casita)\b""", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("""\b[a-f0-9]{8,}\b""", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("""\s+"""), " ")
                .trim()
        if (!isUsableCasitaText(cleaned)) return null
        return cleaned
            .split(' ')
            .joinToString(" ") { word ->
                word.lowercase().replaceFirstChar { char -> char.uppercase() }
            }
    }

    private fun isUsableCasitaText(text: String): Boolean {
        val trimmed = text.trim()
        return trimmed.length in 2..90 &&
            trimmed.spotifyCanonicalHomeUri() == null &&
            trimmed.spotifyImageUrlOrNull() == null &&
            !trimmed.contains('\u0000') &&
            !trimmed.startsWith("http", ignoreCase = true) &&
            !trimmed.startsWith("type.googleapis.com/", ignoreCase = true) &&
            !trimmed.matches(Regex("""^[A-Za-z0-9_-]{16,}$"""))
    }

    private fun ByteArray.spotifyGidUri(type: String): String? =
        spotifyBase62Id()
            ?.let { id -> "spotify:$type:$id" }

    private fun ByteArray.spotifyBase62Id(): String? {
        if (size != 16) return null
        var value = BigInteger(1, this)
        val base = BigInteger.valueOf(62)
        if (value == BigInteger.ZERO) return "0".repeat(22)

        val chars = StringBuilder()
        while (value > BigInteger.ZERO) {
            val divRem = value.divideAndRemainder(base)
            chars.append(SPOTIFY_BASE62_ALPHABET[divRem[1].toInt()])
            value = divRem[0]
        }
        return chars.reverse().toString().padStart(22, '0')
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun ByteArray.decodeToStringOrNull(): String? {
        if (isEmpty()) return null
        val value =
            runCatching { toString(Charsets.UTF_8) }
                .getOrNull()
                ?.trim()
                ?: return null
        if (value.isBlank() || value.contains('\u0000')) return null
        return value.takeIf { text ->
            text.all { char ->
                char == '\n' ||
                    char == '\r' ||
                    char == '\t' ||
                    char.code in 0x20..0xD7FF
            }
        }
    }

    private fun parseProtoMessage(bytes: ByteArray): ProtoMessage = ProtoReader(bytes).readMessage()

    private fun parseProtoMessageOrNull(bytes: ByteArray): ProtoMessage? =
        runCatching { parseProtoMessage(bytes) }
            .getOrNull()
            ?.takeIf { it.fields.isNotEmpty() }

    private data class SpotifyCasitaEntity(
        val uri: String,
        val type: String,
        val title: String,
        val subtitle: String?,
        val thumbnail: String?,
        val artists: List<Artist> = emptyList(),
        val album: Album? = null,
        val year: Int? = null,
        val durationSeconds: Int? = null,
        val explicit: Boolean = false,
        val songCountText: String? = null,
    )

    private data class SpotifyCasitaImage(
        val url: String,
        val width: Int,
        val height: Int,
    )

    private data class ProtoField(
        val number: Int,
        val wireType: Int,
        val varint: Long? = null,
        val bytes: ByteArray? = null,
    )

    private class ProtoMessage(
        val fields: List<ProtoField>,
    ) {
        private val fieldsByNumber = fields.groupBy { it.number }

        fun firstMessage(number: Int): ProtoMessage? =
            messages(number).firstOrNull()

        fun messages(number: Int): List<ProtoMessage> =
            bytes(number).mapNotNull(::parseProtoMessageOrNull)

        fun firstBytes(number: Int): ByteArray? =
            bytes(number).firstOrNull()

        fun bytes(number: Int): List<ByteArray> =
            fieldsByNumber[number].orEmpty().mapNotNull { it.bytes }

        fun string(number: Int): String? =
            strings(number).firstOrNull()

        fun strings(number: Int): List<String> =
            bytes(number).mapNotNull { it.decodeToStringOrNull() }

        fun int(number: Int): Int? =
            long(number)?.toInt()

        fun long(number: Int): Long? =
            fieldsByNumber[number].orEmpty().firstNotNullOfOrNull { it.varint }

        fun bool(number: Int): Boolean =
            long(number) == 1L
    }

    private class ProtoReader(
        private val bytes: ByteArray,
    ) {
        private var index = 0

        fun readMessage(): ProtoMessage {
            val fields = mutableListOf<ProtoField>()
            while (index < bytes.size) {
                val tag = readVarint()
                if (tag == 0L) break
                val number = (tag ushr 3).toInt()
                val wireType = (tag and 0x7).toInt()
                if (number <= 0) error("Invalid protobuf field number $number")
                fields.add(
                    when (wireType) {
                        0 ->
                            ProtoField(
                                number = number,
                                wireType = wireType,
                                varint = readVarint(),
                            )
                        1 -> {
                            skip(8)
                            ProtoField(number = number, wireType = wireType)
                        }
                        2 -> {
                            val length = readVarint().toInt()
                            ProtoField(
                                number = number,
                                wireType = wireType,
                                bytes = readBytes(length),
                            )
                        }
                        5 -> {
                            skip(4)
                            ProtoField(number = number, wireType = wireType)
                        }
                        else -> error("Unsupported protobuf wire type $wireType")
                    },
                )
            }
            return ProtoMessage(fields)
        }

        private fun readVarint(): Long {
            var shift = 0
            var result = 0L
            while (shift < 64) {
                if (index >= bytes.size) error("Truncated protobuf varint")
                val byte = bytes[index++].toInt() and 0xff
                result = result or ((byte and 0x7f).toLong() shl shift)
                if ((byte and 0x80) == 0) return result
                shift += 7
            }
            error("Malformed protobuf varint")
        }

        private fun readBytes(length: Int): ByteArray {
            if (length < 0 || index + length > bytes.size) error("Truncated protobuf field")
            return bytes.copyOfRange(index, index + length)
                .also { index += length }
        }

        private fun skip(length: Int) {
            if (length < 0 || index + length > bytes.size) error("Truncated protobuf fixed field")
            index += length
        }
    }

    private data class WebAccessToken(
        val accessToken: String,
        val expiresAtMs: Long,
    )

    private data class SpotifyNuance(
        val secret: String,
        val version: Int,
    )

    private fun JsonObject.spotifySearchItems(vararg sectionNames: String): List<JsonObject> {
        val search = obj("data")?.obj("searchV2") ?: return emptyList()
        return sectionNames.firstNotNullOfOrNull { sectionName ->
            search.obj(sectionName)
                ?.array("items")
                ?.mapNotNull { it.obj }
                ?.takeIf { it.isNotEmpty() }
        }.orEmpty()
    }

    private fun JsonObject.spotifyLibraryV3Items(): List<JsonObject> =
        obj("data")
            ?.obj("me")
            ?.obj("libraryV3")
            ?.array("items")
            .orEmpty()
            .mapNotNull { it.obj?.obj("item")?.obj("data") }

    private fun JsonObject.spotifyLibraryTrackItems(): List<JsonObject> =
        obj("data")
            ?.obj("me")
            ?.obj("library")
            ?.obj("tracks")
            ?.array("items")
            .orEmpty()
            .mapNotNull { item ->
                val wrapper = item.obj?.obj("track") ?: return@mapNotNull null
                val data = wrapper.obj("data") ?: return@mapNotNull null
                val uri = wrapper.string("_uri")
                if (data.string("uri") != null || uri == null) {
                    data
                } else {
                    buildJsonObject {
                        data.forEach { (key, value) -> put(key, value) }
                        put("uri", uri)
                    }
                }
            }

    private fun JsonObject.spotifyLibraryTracksTotal(): Int? =
        obj("data")
            ?.obj("me")
            ?.obj("library")
            ?.obj("tracks")
            ?.long("totalCount")
            ?.toInt()

    private fun JsonObject.spotifyWrappedData(): JsonObject? =
        obj("itemV2")?.obj("data")
            ?: obj("item")?.obj("data")
            ?: obj("data")
            ?: this

    private fun JsonObject.spotifyGraphArtists(): List<Artist> =
        (
            obj("artists")?.array("items")
                ?: array("artists")
        ).orEmpty()
            .mapNotNull { artist ->
                val data = artist.obj?.obj("data") ?: artist.obj ?: return@mapNotNull null
                val name =
                    data.obj("profile")?.string("name")
                        ?: data.string("name")
                        ?: return@mapNotNull null
                val artistId = data.string("id") ?: data.string("uri")?.substringAfterLast(':')
                Artist(
                    name = name,
                    id = artistId?.let { "spotify:artist:$it" },
                )
            }

    private fun JsonObject.toSpotifyGraphAlbumItem(): AlbumItem? {
        val id = spotifyEntityId(string("id") ?: string("uri") ?: return null, "album") ?: return null
        val title = string("name") ?: return null
        return AlbumItem(
            browseId = "spotify:album:$id",
            playlistId = "spotify:album:$id",
            title = title,
            artists = spotifyGraphArtists(),
            year =
                obj("date")
                    ?.string("isoString")
                    ?.take(4)
                    ?.toIntOrNull()
                    ?: obj("releaseDate")
                        ?.string("isoString")
                        ?.take(4)
                        ?.toIntOrNull(),
            thumbnail = spotifyInitialStateImageUrl().orEmpty(),
            explicit = false,
        )
    }

    private fun JsonObject.toSpotifyGraphArtistItem(): ArtistItem? {
        val id = spotifyEntityId(string("id") ?: string("uri") ?: return null, "artist") ?: return null
        val title =
            obj("profile")?.string("name")
                ?: string("name")
                ?: return null
        return ArtistItem(
            id = "spotify:artist:$id",
            title = title,
            thumbnail = spotifyInitialStateImageUrl(),
            shuffleEndpoint = null,
            radioEndpoint = null,
        )
    }

    private fun JsonObject.toSpotifyGraphPlaylistItem(): PlaylistItem? {
        val id = spotifyEntityId(string("id") ?: string("uri") ?: return null, "playlist") ?: return null
        val title = string("name") ?: return null
        val owner =
            obj("ownerV2")
                ?.obj("data")
                ?: obj("owner")
                ?: obj("creator")
        val ownerName =
            owner?.string("displayName")
                ?: owner?.string("display_name")
                ?: owner?.string("name")
                ?: owner?.string("username")
        val totalTracks =
            obj("attributes")?.long("totalTrackCount")
                ?: obj("attributes")?.long("totalTracks")
                ?: obj("content")?.long("totalCount")
                ?: obj("tracks")?.long("total")
        return PlaylistItem(
            id = "spotify:playlist:$id",
            title = title,
            author = ownerName?.let { Artist(name = it, id = owner?.string("id")) },
            songCountText = totalTracks?.let { "$it songs" },
            thumbnail = spotifyInitialStateImageUrl(),
            playEndpoint = null,
            shuffleEndpoint = null,
            radioEndpoint = null,
        )
    }

    private fun List<PlaylistItem>.withoutLikedSongsDuplicate(): List<PlaylistItem> =
        filterNot { item ->
            item.id.equals("spotify:collection:tracks", ignoreCase = true) ||
                item.title.equals("Liked Songs", ignoreCase = true)
        }

    private fun JsonObject.spotifyEntityKey(): String =
        string("uri")
            ?: string("id")
            ?: obj("item")?.obj("data")?.string("uri")
            ?: obj("item")?.obj("data")?.string("id")
            ?: toString()

    @Serializable
    private data class SpotifyPlaylistExtenderRequest(
        @SerialName("playlistURI") val playlistUri: String? = null,
        @SerialName("numResults") val numResults: Int,
        @SerialName("trackSkipIDs") val trackSkipIds: Set<String> = emptySet(),
        @SerialName("trackIDs") val trackIds: Set<String> = emptySet(),
        val title: String? = null,
        val condensed: Boolean = true,
    )

    @Serializable
    private data class SpotifyPlaylistExtenderResponse(
        @SerialName("recommended_tracks") val recommendedTracks: List<SpotifyPlaylistExtenderTrack> = emptyList(),
    )

    @Serializable
    private data class SpotifyPlaylistExtenderTrack(
        val uri: String,
        val name: String,
        @SerialName("preview_id") val previewId: String? = null,
        val album: SpotifyPlaylistExtenderItem? = null,
        val artists: List<SpotifyPlaylistExtenderItem> = emptyList(),
        @SerialName("explicit") val explicit: Boolean = false,
    )

    @Serializable
    private data class SpotifyPlaylistExtenderItem(
        val id: String? = null,
        val name: String? = null,
        @SerialName("image_url") val imageUrl: String? = null,
        @SerialName("large_image_url") val largeImageUrl: String? = null,
    )

    @Serializable
    private data class SearchTracksResponse(
        val data: SearchTracksData? = null,
    )

    @Serializable
    private data class SearchTracksData(
        @SerialName("searchV2") val searchV2: SearchV2? = null,
    )

    @Serializable
    private data class SearchV2(
        @SerialName("tracksV2") val tracksV2: SearchTracksContainer? = null,
    )

    @Serializable
    private data class SearchTracksContainer(
        val items: List<SearchTrackWrapperWrapper> = emptyList(),
    )

    @Serializable
    private data class SearchTrackWrapperWrapper(
        val item: SearchTrackWrapper? = null,
    )

    @Serializable
    private data class SearchTrackWrapper(
        val data: SearchTrack? = null,
    )

    @Serializable
    private data class SearchTrack(
        val uri: String? = null,
        val name: String? = null,
        val duration: SearchDuration? = null,
        val artists: SearchArtists? = null,
        val albumOfTrack: SearchAlbum? = null,
        val isrc: String? = null,
    )

    @Serializable
    private data class SearchDuration(
        val totalMilliseconds: Long? = null,
    )

    @Serializable
    private data class SearchArtists(
        val items: List<SearchArtist> = emptyList(),
    )

    @Serializable
    private data class SearchArtist(
        val profile: SearchArtistProfile? = null,
    )

    @Serializable
    private data class SearchArtistProfile(
        val name: String? = null,
    )

    @Serializable
    private data class SearchAlbum(
        val name: String? = null,
    )

    @Serializable
    private data class CanvasResponse(
        val data: CanvasResponseData? = null,
    )

    @Serializable
    private data class CanvasResponseData(
        val trackUnion: CanvasTrackUnion? = null,
    )

    @Serializable
    private data class CanvasTrackUnion(
        val canvas: CanvasData? = null,
    )

    @Serializable
    private data class CanvasData(
        val type: String? = null,
        val url: String? = null,
    )
}

private val JsonElement.obj: JsonObject?
    get() = this as? JsonObject

private fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject

private fun JsonObject.array(name: String): JsonArray? = this[name] as? JsonArray

private fun JsonObject.string(name: String): String? =
    (this[name] as? kotlinx.serialization.json.JsonPrimitive)
        ?.contentOrNull
        ?.takeIf { it.isNotBlank() && it != "null" }

private fun JsonElement.stringValueOrNull(): String? =
    (this as? kotlinx.serialization.json.JsonPrimitive)
        ?.contentOrNull
        ?.takeIf { it.isNotBlank() && it != "null" }

private fun JsonObject.int(name: String): Int? =
    (this[name] as? kotlinx.serialization.json.JsonPrimitive)
        ?.intOrNull

private fun JsonObject.double(name: String): Double? =
    (this[name] as? kotlinx.serialization.json.JsonPrimitive)
        ?.doubleOrNull

private fun JsonObject.long(name: String): Long? =
    (this[name] as? kotlinx.serialization.json.JsonPrimitive)
        ?.longOrNull

private fun JsonObject.boolean(name: String): Boolean =
    (this[name] as? kotlinx.serialization.json.JsonPrimitive)
        ?.booleanOrNull == true

private fun normalizeForMatch(value: String): String =
    value
        .lowercase()
        .replace(Regex("\\(.*?\\)|\\[.*?\\]"), " ")
        .replace(Regex("\\b(feat\\.?|ft\\.?|with)\\b"), " ")
        .replace(Regex("\\b(official|video|music|lyric|lyrics|audio|hd|4k|remastered|remaster)\\b"), " ")
        .replace(Regex("[^a-z0-9 ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

private fun sanitize(value: String): String =
    value
        .replace(
            Regex("\\(feat\\.?[^)]*\\)|\\[feat\\.?[^]]*\\]", RegexOption.IGNORE_CASE),
            " ",
        ).replace(Regex("\\b(feat\\.?|ft\\.?)\\b", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

private fun String.spotifyTrackUri(): String? {
    val trackId = spotifyTrackId() ?: return null
    return "spotify:track:$trackId"
}

private fun String.spotifyTrackId(): String? =
    when {
        startsWith("spotify:track:", ignoreCase = true) -> substringAfterLast(':')
        contains("open.spotify.com/track/", ignoreCase = true) ->
            substringAfter("open.spotify.com/track/", "")
                .substringBefore('?')
                .substringBefore('/')
        matches(Regex("^[A-Za-z0-9]{22}$")) -> this
        else -> null
    }?.takeIf { it.matches(Regex("^[A-Za-z0-9]{22}$")) }

private fun spotifyKeySignature(
    key: Int?,
    mode: Int?,
): String? {
    if (key == null || key !in 0..11) return null
    val pitch =
        when (key) {
            0 -> "C"
            1 -> "C#"
            2 -> "D"
            3 -> "D#"
            4 -> "E"
            5 -> "F"
            6 -> "F#"
            7 -> "G"
            8 -> "G#"
            9 -> "A"
            10 -> "A#"
            11 -> "B"
            else -> return null
        }
    return if (mode == 0) "${pitch}m" else pitch
}

private fun overlap(
    left: String,
    right: String,
): Double {
    val leftTokens = left.split(" ").filter { it.isNotBlank() }.toSet()
    val rightTokens = right.split(" ").filter { it.isNotBlank() }.toSet()
    if (leftTokens.isEmpty() || rightTokens.isEmpty()) return 0.0
    return leftTokens.intersect(rightTokens).size.toDouble() / maxOf(leftTokens.size, rightTokens.size).toDouble()
}

private fun disfavoredPenalty(
    candidateTitle: String,
    expectedTitle: String,
): Int {
    val candidate = candidateTitle.lowercase()
    val expected = expectedTitle.lowercase()
    val penalties =
        mapOf(
            "live" to 12,
            "karaoke" to 18,
            "cover" to 14,
            "tribute" to 14,
            "instrumental" to 10,
            "sped up" to 18,
            "speed up" to 18,
            "slowed" to 18,
            "reverb" to 10,
        )
    return penalties.entries.sumOf { (token, penalty) ->
        if (candidate.contains(token) && !expected.contains(token)) {
            penalty
        } else {
            0
        }
    }
}
