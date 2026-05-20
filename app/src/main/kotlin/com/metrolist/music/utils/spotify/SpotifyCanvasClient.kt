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
import java.nio.ByteBuffer
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
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
    private const val SEARCH_TRACKS_HASH =
        "5307479c18ff24aa1bd70691fdb0e77734bede8cce3bd7d43b6ff7314f52a6b8"
    private const val CANVAS_HASH =
        "1b1e1915481c99f4349af88268c6b49a2b601cf0db7bca8749b5dd75088486fc"
    private const val FETCH_PLAYLIST_HASH =
        "19ff1327c29e99c208c86d7a9d8f1929cfdf3d3202a0ff4253c821f1901aa94d"
    private const val SPOTUBE_HOME_HASH =
        "d62af2714f2623c923cc9eeca4b9545b4363abaa9188a9e94e2b63b823419a2c"
    private const val ECHO_HOME_HASH =
        "3357ffed7961629ba92b4e0a41514e4d5004a14355c964c23ce442205c9e44a1"
    private val HOME_HASHES = listOf(SPOTUBE_HOME_HASH, ECHO_HOME_HASH)

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
    private const val WEB_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
    private const val DESKTOP_USER_AGENT = "Spotify/126600447 Win32_x86_64/0 (PC laptop)"
    private const val CACHE_TTL_MS = 6 * 60 * 60 * 1000L
    private const val PLAYLIST_TRACK_PAGE_SIZE = 100
    private const val ALBUM_TRACK_PAGE_SIZE = 50
    private const val PLAYLIST_TRACK_SAFETY_LIMIT = 10_000
    private const val GRAPH_PLAYLIST_PAGE_SIZE = 25
    private val JSON_MEDIA_TYPE = "application/json".toMediaType()
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

    suspend fun resolveSearch(query: String, cookie: String): String? {
        return searchTracks(query, cookie).firstOrNull()?.uri
    }

    suspend fun resolveSearchSummaryPage(
        query: String,
        cookie: String,
    ): SearchSummaryPage? {
        if (query.isBlank()) return SearchSummaryPage(emptyList())
        val normalizedCookie = normalizeSpotifyCookieInput(cookie) ?: return null
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

    private data class Expectation(
        val title: String,
        val artists: List<String>,
        val album: String?,
        val durationMs: Long?,
    ) {
        val key: String
            get() =
                listOf(
                    normalizeForMatch(title),
                    artists.joinToString("|") { normalizeForMatch(it) },
                    normalizeForMatch(album.orEmpty()),
                    durationMs?.toString().orEmpty(),
                ).joinToString("::")
    }

    private val trackUriCache = ConcurrentHashMap<String, CachedString>()
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
        val expectation = buildExpectation(mediaMetadata) ?: return null
        val trackUri = resolveTrackUri(expectation, normalizedCookie) ?: return null
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

    suspend fun resolveHomePage(cookie: String): HomePage? {
        val normalizedCookie = normalizeSpotifyCookieInput(cookie) ?: return null
        val spTCookie =
            extractSpotifyCookieValue(normalizedCookie, "sp_t")
                ?: run {
                    Timber.w("Spotify home requires sp_t; ask the user to refresh the Spotify Web login cookie")
                    return null
                }

        HOME_HASHES.forEach { hash ->
            runCatching {
                postGraphQl<JsonObject>(
                    operation = "home",
                    hash = hash,
                    variables =
                        buildJsonObject {
                            put("timeZone", TimeZone.getDefault().id)
                            put("sp_t", spTCookie)
                            put("facet", "")
                            put("sectionItemsLimit", 20)
                        },
                    cookie = normalizedCookie,
                    tokenProvider = ::ensureWebToken,
                )
            }.onSuccess { response ->
                return SpotifyHomeFeedParser.parse(response)
            }.onFailure { error ->
                Timber.w(error, "Spotify home request failed for hash %s", hash)
            }
        }

        return null
    }

    suspend fun resolveLibraryPage(cookie: String): HomePage? {
        val normalizedCookie = normalizeSpotifyCookieInput(cookie) ?: return null
        val sections = mutableListOf<HomePage.Section>()

        runCatching {
            spotifyApiGet(
                url =
                    "https://api.spotify.com/v1/me/tracks"
                        .toHttpUrl()
                        .newBuilder()
                        .addQueryParameter("limit", "50")
                        .addQueryParameter("market", "from_token")
                        .build(),
                normalizedCookie = normalizedCookie,
                operation = "Spotify saved tracks",
            ).array("items")
                .orEmpty()
                .mapNotNull { it.obj?.obj("track")?.toSpotifyPlaylistSong() }
        }.onSuccess { items ->
            sections.addSpotifyHomeSection("Saved Spotify songs", items)
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
            sections.addSpotifyHomeSection("Spotify playlists", items)
        }.onFailure { error ->
            Timber.w(error, "Spotify library playlists failed")
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
        runCatching {
            resolvePlaylistFromGraphQl(playlistId, normalizedCookie)
        }.onFailure { error ->
            Timber.w(error, "Spotify playlist GraphQL load failed for %s", playlistId)
        }.getOrNull()
            ?.let { page ->
                val expectedSongs =
                    page.playlist.songCountText
                        ?.substringBefore(' ')
                        ?.toIntOrNull()
                val shouldTryFullTrackPage =
                    expectedSongs == null || page.songs.size < expectedSongs

                if (shouldTryFullTrackPage) {
                    runCatching {
                        resolvePlaylistTracksFromWebApi(playlistId, normalizedCookie)
                    }.onFailure { error ->
                        Timber.w(error, "Spotify playlist full-track GraphQL supplement failed for %s", playlistId)
                    }.getOrNull()
                        ?.takeIf { songs -> songs.size > page.songs.size }
                        ?.let { songs ->
                            return page.copy(
                                playlist =
                                    page.playlist.copy(
                                        songCountText = "${expectedSongs ?: songs.size} songs",
                                        thumbnail = page.playlist.thumbnail ?: songs.firstOrNull()?.thumbnail,
                                    ),
                                songs = songs,
                            )
                        }
                }

                return page
            }

        resolvePlaylistFromWebPage(playlistId, normalizedCookie)?.let { page ->
            val expectedSongs =
                page.playlist.songCountText
                    ?.substringBefore(' ')
                    ?.toIntOrNull()

            runCatching {
                resolvePlaylistTracksFromWebApi(playlistId, normalizedCookie)
            }.onFailure { error ->
                Timber.w(error, "Spotify playlist pagination failed for %s", playlistId)
            }.getOrNull()
                ?.takeIf { songs -> songs.size > page.songs.size }
                ?.let { songs ->
                    return page.copy(
                        playlist =
                            page.playlist.copy(
                                songCountText = "${expectedSongs ?: songs.size} songs",
                                thumbnail = page.playlist.thumbnail ?: songs.firstOrNull()?.thumbnail,
                            ),
                        songs = songs,
                    )
                }

            return page
        }

        return runCatching {
            resolvePlaylistFromWebApi(playlistId, normalizedCookie)
        }.onFailure { error ->
            Timber.w(error, "Spotify playlist Web API fallback failed for %s", playlistId)
        }.getOrNull()
    }

    suspend fun resolveAlbum(
        albumId: String,
        cookie: String,
    ): ExternalPlaylistPage? {
        val normalizedCookie = normalizeSpotifyCookieInput(cookie) ?: return null
        return runCatching {
            resolveAlbumFromWebApi(albumId, normalizedCookie)
        }.onFailure { error ->
            Timber.w(error, "Spotify album Web API load failed for %s", albumId)
        }.getOrNull()
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
                offset += ALBUM_TRACK_PAGE_SIZE
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
            postGraphQl<JsonObject>(
                operation = "fetchPlaylistWithGatedEntityRelations",
                hash = FETCH_PLAYLIST_HASH,
                variables =
                    buildJsonObject {
                        put("uri", playlistUri)
                    },
                cookie = normalizedCookie,
                tokenProvider = tokenProvider,
            )

        val playlist = root.obj("data")?.obj("playlistV2") ?: return null
        val songs = mutableListOf<SongItem>()
        val content = playlist.obj("content")
        appendSpotifyGraphPlaylistSongs(content, songs)

        var nextOffset = content?.obj("pagingInfo")?.long("nextOffset")?.toInt()
        var totalCount = content?.long("totalCount")?.toInt()

        while (nextOffset != null && songs.size < PLAYLIST_TRACK_SAFETY_LIMIT) {
            val requestedOffset = nextOffset
            val page =
                postGraphQl<JsonObject>(
                    operation = "fetchPlaylistContentsWithGatedEntityRelations",
                    hash = FETCH_PLAYLIST_HASH,
                    variables =
                        buildJsonObject {
                            put("uri", playlistUri)
                            put("offset", requestedOffset)
                            put("limit", GRAPH_PLAYLIST_PAGE_SIZE)
                        },
                    cookie = normalizedCookie,
                    tokenProvider = tokenProvider,
                )

            val pageContent = page.obj("data")?.obj("playlistV2")?.obj("content") ?: break
            val previousCount = songs.size
            appendSpotifyGraphPlaylistSongs(pageContent, songs)
            if (songs.size == previousCount) break

            val pagingInfo = pageContent.obj("pagingInfo")
            val pageOffset = pagingInfo?.long("offset")?.toInt() ?: requestedOffset
            val pageLimit = pagingInfo?.long("limit")?.toInt() ?: GRAPH_PLAYLIST_PAGE_SIZE
            val candidateOffset = pagingInfo?.long("nextOffset")?.toInt()
            totalCount = totalCount ?: pageContent.long("totalCount")?.toInt()
            nextOffset =
                candidateOffset
                    ?.takeIf { it > pageOffset }
                    ?: (pageOffset + pageLimit).takeIf { fallbackOffset ->
                        fallbackOffset > pageOffset &&
                            (totalCount == null || songs.size < totalCount!!)
                    }

            if (totalCount != null && songs.size >= totalCount) {
                break
            }
        }

        val distinctSongs = songs.distinctBy { it.id }
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
                    songCountText = (totalCount ?: distinctSongs.size).takeIf { it > 0 }?.let { "$it songs" },
                    thumbnail = playlist.spotifyInitialStateImageUrl() ?: distinctSongs.firstOrNull()?.thumbnail,
                    playEndpoint = null,
                    shuffleEndpoint = null,
                    radioEndpoint = null,
                ),
            songs = distinctSongs,
        )
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
                                songCountText = playlist.obj("content")?.long("totalCount")?.let { "$it songs" },
                                thumbnail = playlist.spotifyInitialStateImageUrl() ?: songs.firstOrNull()?.thumbnail,
                                playEndpoint = null,
                                shuffleEndpoint = null,
                                radioEndpoint = null,
                            ),
                        songs = songs,
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
            val songs = mutableListOf<SongItem>()
            var offset = 0
            var total: Int? = null

            while (songs.size < PLAYLIST_TRACK_SAFETY_LIMIT) {
                val url =
                    "https://api.spotify.com/v1/playlists/$playlistId/tracks"
                        .toHttpUrl()
                        .newBuilder()
                        .addQueryParameter("market", "from_token")
                        .addQueryParameter("limit", PLAYLIST_TRACK_PAGE_SIZE.toString())
                        .addQueryParameter("offset", offset.toString())
                        .addQueryParameter(
                            "fields",
                            "total,items(track(id,name,duration_ms,explicit,artists(id,name),album(id,name,images)))",
                        ).build()

                val page =
                    spotifyApiGet(
                        url = url,
                        normalizedCookie = normalizedCookie,
                        operation = "Spotify playlist tracks",
                    )

                total = total ?: page.long("total")?.toInt()
                val pageSongs =
                    page
                        .array("items")
                        .orEmpty()
                        .mapNotNull { it.obj?.obj("track")?.toSpotifyPlaylistSong() }

                if (pageSongs.isEmpty()) break

                songs += pageSongs
                offset += PLAYLIST_TRACK_PAGE_SIZE

                val expectedTotal = total
                if (expectedTotal != null && offset >= expectedTotal) break
            }

            songs.distinctBy { it.id }
        }

    private suspend fun resolvePlaylistFromWebApi(
        playlistId: String,
        normalizedCookie: String,
    ): ExternalPlaylistPage? {
        val url =
            "https://api.spotify.com/v1/playlists/$playlistId"
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("market", "from_token")
                .addQueryParameter(
                    "fields",
                    "id,name,owner(display_name),images,total,tracks(total,items(track(id,name,duration_ms,explicit,artists(id,name),album(id,name,images))))",
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

                ExternalPlaylistPage(
                    playlist =
                        PlaylistItem(
                            id = "spotify:playlist:$playlistId",
                            title = root.string("name") ?: "Spotify playlist",
                            author = root.obj("owner")?.string("display_name")?.let { Artist(name = it, id = null) },
                            songCountText = root.obj("tracks")?.long("total")?.let { "$it songs" },
                            thumbnail = root.spotifyWebApiImageUrl() ?: songs.firstOrNull()?.thumbnail,
                            playEndpoint = null,
                            shuffleEndpoint = null,
                            radioEndpoint = null,
                        ),
                    songs = songs,
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

    private suspend fun resolveTrackUri(
        expectation: Expectation,
        cookie: String,
    ): String? {
        val now = System.currentTimeMillis()
        trackUriCache[expectation.key]
            ?.takeIf { now - it.cachedAt < CACHE_TTL_MS }
            ?.let { return it.value.ifBlank { null } }

        val candidates =
            buildQueries(expectation)
                .flatMap { searchTracks(it, cookie) }
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

    private suspend fun resolveCanvas(
        trackUri: String,
        cookie: String,
    ): String? {
        val now = System.currentTimeMillis()
        canvasUrlCache[trackUri]
            ?.takeIf { now - it.cachedAt < CACHE_TTL_MS }
            ?.let { return it.value.ifBlank { null } }

        val response =
            postGraphQl<CanvasResponse>(
                operation = "canvas",
                hash = CANVAS_HASH,
                variables =
                    buildJsonObject {
                        put("uri", trackUri)
                    },
                cookie = cookie,
            )

        val canvasUrl =
            response.data
                ?.trackUnion
                ?.canvas
                ?.takeIf { it.type.orEmpty().startsWith("VIDEO") }
                ?.url
                ?.takeIf { it.isNotBlank() }

        canvasUrlCache[trackUri] = CachedString(canvasUrl.orEmpty(), now)
        return canvasUrl
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
                        val body = response.body?.string().orEmpty()
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
                hash = SEARCH_TRACKS_HASH,
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

    private suspend inline fun <reified T> postGraphQl(
        operation: String,
        hash: String,
        variables: JsonObject,
        cookie: String,
        noinline tokenProvider: suspend (String) -> String = ::ensureToken,
    ): T =
        withContext(Dispatchers.IO) {
            val request =
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
                    ).header("User-Agent", WEB_USER_AGENT)
                    .header("Accept", "application/json")
                    .header("App-Platform", "WebPlayer")
                    .header("Referer", WEB_REFERER)
                    .header("Origin", WEB_ORIGIN)
                    .header("Cookie", cookie)
                    .header("Authorization", "Bearer ${tokenProvider(cookie)}")
                    .build()

            client.newCall(request).execute().use { response ->
                json.decodeFromString<T>(response.requireBody(operation))
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

        Timber.w(
            webResult.exceptionOrNull(),
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
            val request =
                Request
                    .Builder()
                    .url(url)
                    .header("User-Agent", WEB_USER_AGENT)
                    .header("Accept", "application/json")
                    .header("Referer", WEB_REFERER)
                    .header("Origin", WEB_ORIGIN)
                    .header("Cookie", normalizedCookie)
                    .header("Authorization", "Bearer ${tokenProvider(normalizedCookie)}")
                    .get()
                    .build()

            client.newCall(request).execute().use { response ->
                json.parseToJsonElement(response.requireBody(operation)).jsonObject
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
        return if (artists.isEmpty() && album == null) null else Expectation(title, artists, album, durationMs)
    }

    private fun buildQueries(expectation: Expectation): List<String> {
        val title = sanitize(expectation.title)
        val artistQuery = expectation.artists.joinToString(" ").ifBlank { null }
        val albumQuery = expectation.album?.let(::sanitize)

        return listOfNotNull(
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

        return titleScore + artistScore + albumScore + durationScore - disfavoredPenalty(
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
        val text = body?.string().orEmpty()
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

    private fun appendSpotifyGraphPlaylistSongs(
        content: JsonObject?,
        sink: MutableList<SongItem>,
    ) {
        content
            ?.array("items")
            .orEmpty()
            .mapNotNull { item ->
                (
                    item.obj
                        ?.obj("itemV2")
                        ?.obj("data")
                        ?: item.obj
                            ?.obj("item")
                            ?.obj("data")
                        ?: item.obj
                            ?.obj("data")
                )
                    ?.toSpotifyInitialStatePlaylistSong()
            }.forEach(sink::add)
    }

    private fun JsonObject.toSpotifyAlbumSong(albumObject: JsonObject): SongItem? {
        val id = string("id") ?: string("uri")?.substringAfterLast(':') ?: return null
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

    private fun JsonObject.spotifyWebApiImageUrl(): String? =
        array("images")
            .orEmpty()
            .firstNotNullOfOrNull { image ->
                image.obj?.string("url")
            }

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
        val id = string("id") ?: string("uri")?.substringAfterLast(':') ?: return null
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
        val id = string("id") ?: string("uri")?.substringAfterLast(':') ?: return null
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
        val id = string("id") ?: string("uri")?.substringAfterLast(':') ?: return null
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

    private data class WebAccessToken(
        val accessToken: String,
        val expiresAtMs: Long,
    )

    private data class SpotifyNuance(
        val secret: String,
        val version: Int,
    )

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
