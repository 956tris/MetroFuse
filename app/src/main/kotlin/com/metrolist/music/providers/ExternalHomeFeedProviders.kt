/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.providers

import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YTItem
import com.metrolist.innertube.models.Album as TubeAlbum
import com.metrolist.innertube.models.Artist as TubeArtist
import com.metrolist.innertube.pages.HomePage
import com.metrolist.music.soundcloud.SoundCloudAudioProvider
import com.metrolist.music.utils.tidal.extractTidalAccessToken
import com.metrolist.music.utils.tidal.extractTidalRefreshToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

data class ExternalPlaylistPage(
    val playlist: PlaylistItem,
    val songs: List<SongItem>,
)

object TidalHomeFeedProvider {
    private const val CLIENT_VERSION = "2025.10.16"
    private const val CLIENT_TOKEN = "txNoH4kkV41MfH25"
    private const val CLIENT_SECRET = "dQjy0MinCEvxi1O4UmxvxWnDjt4cgHBPw8ll6nYBk98="
    private const val USER_CLIENT_TOKEN = "49YxDN9a2aFV6RTG"
    private const val AUTH_TOKEN_URL = "https://auth.tidal.com/v1/oauth2/token"
    private const val PLAYLIST_PAGE_SIZE = 100
    private const val PLAYLIST_SAFETY_LIMIT = 10_000
    private val TIDAL_PERSONALIZED_IGNORED_SECTION_TYPES = setOf("SHORTCUT_LIST", "LINKS_LIST")
    private val json = Json { ignoreUnknownKeys = true }
    private val tokenMutex = Mutex()
    private val client =
        OkHttpClient
            .Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    private var cachedAccessToken: String? = null
    private var cachedRefreshToken: String? = null
    private var cachedAccessTokenExpiresAtMs: Long = 0L

    suspend fun load(cookie: String = ""): Result<HomePage> =
        runCatching {
            withContext(Dispatchers.IO) {
                val auth = tidalAuthInput(cookie)
                if (auth.hasRefreshAuth) {
                    loadPersonalizedHome(auth)
                } else {
                    loadAnonymousHome(auth)
                }
            }
        }

    suspend fun validatePersonalizedSession(cookie: String): Boolean =
        withContext(Dispatchers.IO) {
            val auth = tidalAuthInput(cookie)
            if (!auth.hasRefreshAuth) return@withContext false
            runCatching {
                loadPersonalizedHome(auth)
            }.getOrNull()?.sections?.isNotEmpty() == true
        }

    suspend fun resolveAlbumArtwork(
        title: String,
        artist: String?,
        album: String?,
        cookie: String = "",
    ): String? =
        runCatching {
            withContext(Dispatchers.IO) {
                val normalizedTitle = title.normalizedArtworkMatch()
                if (normalizedTitle.isBlank()) return@withContext null

                val auth = tidalAuthInput(cookie)
                val query =
                    listOfNotNull(
                        title.takeIf { it.isNotBlank() },
                        artist?.takeIf { it.isNotBlank() },
                        album?.takeIf { it.isNotBlank() },
                    ).joinToString(" ")

                val responseJson =
                    client.newCall(
                        tidalRequest(
                            path = "v1/search",
                            params =
                                mapOf(
                                    "query" to query,
                                    "types" to "TRACKS,ALBUMS",
                                    "limit" to "10",
                                ),
                            auth = auth,
                            authenticated = auth.hasUserAuth,
                        ),
                    ).execute().use { response ->
                        json.parseToJsonElement(response.requireTidalBody("TIDAL artwork search")).jsonObject
                    }

                responseJson.bestTidalArtworkCandidate(
                    title = title,
                    artist = artist,
                    album = album,
                )
            }
        }.getOrNull()

    suspend fun loadPlaylist(
        playlistId: String,
        cookie: String = "",
    ): Result<ExternalPlaylistPage> =
        runCatching {
            withContext(Dispatchers.IO) {
                val auth = tidalAuthInput(cookie)

                val playlistJson =
                    client.newCall(
                        tidalRequest(
                            path = "v1/playlists/$playlistId",
                            auth = auth,
                            authenticated = true,
                        ),
                    ).execute().use { response ->
                        json.parseToJsonElement(response.requireTidalBody("TIDAL playlist")).jsonObject
                    }
                val playlistData = playlistJson.tidalWrappedContent()
                val songs = loadPlaylistSongs(playlistId, auth)

                val playlist =
                    playlistData.toTidalPlaylist()
                        ?: PlaylistItem(
                            id = "tidal:playlist:$playlistId",
                            title = playlistData.tidalTitle() ?: "TIDAL playlist",
                            author = playlistData.obj("creator")?.string("name")?.let { TubeArtist(name = it, id = null) },
                            songCountText =
                                playlistData.long("numberOfTracks")
                                    ?.let { "$it songs" }
                                    ?: playlistData.long("numberOfItems")?.let { "$it songs" }
                                    ?: songs.size.takeIf { it > 0 }?.let { "$it songs" },
                            thumbnail = playlistData.tidalArtworkUrl() ?: songs.firstOrNull()?.thumbnail,
                            playEndpoint = null,
                            shuffleEndpoint = null,
                            radioEndpoint = null,
                        )

                ExternalPlaylistPage(
                    playlist = playlist,
                    songs = songs,
                )
            }
        }

    suspend fun loadCollection(
        collectionId: String,
        type: String,
        cookie: String = "",
    ): Result<ExternalPlaylistPage> =
        when (type.lowercase()) {
            "album" -> loadAlbum(collectionId, cookie)
            "mix" -> loadMix(collectionId, cookie)
            else -> loadPlaylist(collectionId, cookie)
        }

    private suspend fun loadAlbum(
        albumId: String,
        cookie: String = "",
    ): Result<ExternalPlaylistPage> =
        runCatching {
            withContext(Dispatchers.IO) {
                val auth = tidalAuthInput(cookie)
                val albumJson =
                    client.newCall(
                        tidalRequest(
                            path = "v1/pages/album",
                            params = mapOf("albumId" to albumId),
                            auth = auth,
                            authenticated = true,
                        ),
                    ).execute().use { response ->
                        json.parseToJsonElement(response.requireTidalBody("TIDAL album")).jsonObject
                    }

                val albumData = albumJson.findTidalAlbum(albumId) ?: albumJson
                val songs = albumJson.findTidalSongs()
                val playlist =
                    PlaylistItem(
                        id = "tidal:album:$albumId",
                        title = albumData.tidalTitle() ?: "TIDAL album",
                        author =
                            albumData
                                .tidalArtists()
                                .takeIf { it.isNotEmpty() }
                                ?.joinToString(", ") { it.name }
                                ?.let { TubeArtist(name = it, id = null) },
                        songCountText =
                            albumData.long("numberOfTracks")?.let { "$it songs" }
                                ?: songs.size.takeIf { it > 0 }?.let { "$it songs" },
                        thumbnail = albumData.tidalArtworkUrl() ?: songs.firstOrNull()?.thumbnail,
                        playEndpoint = null,
                        shuffleEndpoint = null,
                        radioEndpoint = null,
                    )

                ExternalPlaylistPage(
                    playlist = playlist,
                    songs = songs,
                )
            }
        }

    private suspend fun loadMix(
        mixId: String,
        cookie: String = "",
    ): Result<ExternalPlaylistPage> =
        runCatching {
            withContext(Dispatchers.IO) {
                val auth = tidalAuthInput(cookie)
                val pageJson =
                    client.newCall(
                        tidalRequest(
                            path = "v1/pages/mix",
                            params = mapOf("mixId" to mixId),
                            auth = auth,
                            authenticated = true,
                        ),
                    ).execute().use { response ->
                        json.parseToJsonElement(response.requireTidalBody("TIDAL mix")).jsonObject
                    }

                val pageSongs = pageJson.findTidalSongs()
                val songs =
                    runCatching { loadMixSongs(mixId, auth) }
                        .getOrElse { throwable ->
                            Timber.tag("TidalHome").w(throwable, "TIDAL mix items failed; using page tracks")
                            emptyList()
                        }
                        .ifEmpty { pageSongs }
                val mixData = pageJson.findTidalMix(mixId) ?: pageJson
                val playlist =
                    PlaylistItem(
                        id = "tidal:mix:$mixId",
                        title = mixData.tidalTitle() ?: "TIDAL mix",
                        author = mixData.tidalSubtitle()?.let { TubeArtist(name = it, id = null) },
                        songCountText = songs.size.takeIf { it > 0 }?.let { "$it songs" },
                        thumbnail = mixData.tidalArtworkUrl() ?: songs.firstOrNull()?.thumbnail,
                        playEndpoint = null,
                        shuffleEndpoint = null,
                        radioEndpoint = null,
                    )

                ExternalPlaylistPage(
                    playlist = playlist,
                    songs = songs,
                )
            }
        }

    private suspend fun loadPlaylistSongs(
        playlistId: String,
        auth: TidalAuthInput,
    ): List<SongItem> {
        val songs = mutableListOf<SongItem>()
        var offset = 0
        var total: Long? = null

        while (songs.size < PLAYLIST_SAFETY_LIMIT) {
            val tracksJson =
                client.newCall(
                    tidalRequest(
                        path = "v1/playlists/$playlistId/items",
                        params =
                            mapOf(
                                "limit" to PLAYLIST_PAGE_SIZE.toString(),
                                "offset" to offset.toString(),
                            ),
                        auth = auth,
                        authenticated = true,
                    ),
                ).execute().use { response ->
                    json.parseToJsonElement(response.requireTidalBody("TIDAL playlist items")).jsonObject
                }

            total = total ?: tracksJson.long("totalNumberOfItems")
            val pageSongs =
                tracksJson
                    .array("items")
                    .orEmpty()
                    .mapNotNull { it.obj?.tidalWrappedContent()?.toTidalSong() }

            if (pageSongs.isEmpty()) break

            songs += pageSongs
            offset += PLAYLIST_PAGE_SIZE

            val expectedTotal = total
            if (expectedTotal != null && offset >= expectedTotal) break
        }

        return songs.distinctBy { it.id }
    }

    private suspend fun loadMixSongs(
        mixId: String,
        auth: TidalAuthInput,
    ): List<SongItem> {
        val songs = mutableListOf<SongItem>()
        var offset = 0
        var total: Long? = null

        while (songs.size < PLAYLIST_SAFETY_LIMIT) {
            val tracksJson =
                client.newCall(
                    tidalRequest(
                        path = "v1/mixes/$mixId/items",
                        params =
                            mapOf(
                                "limit" to PLAYLIST_PAGE_SIZE.toString(),
                                "offset" to offset.toString(),
                            ),
                        auth = auth,
                        authenticated = true,
                    ),
                ).execute().use { response ->
                    json.parseToJsonElement(response.requireTidalBody("TIDAL mix items")).jsonObject
                }

            total = total ?: tracksJson.long("totalNumberOfItems")
            val pageSongs =
                tracksJson
                    .array("items")
                    .orEmpty()
                    .flatMap { item ->
                        val itemObject = item.obj ?: return@flatMap emptyList()
                        listOf(itemObject, itemObject.tidalWrappedContent())
                    }
                    .mapNotNull { it.toTidalSong() }

            if (pageSongs.isEmpty()) break

            songs += pageSongs
            offset += PLAYLIST_PAGE_SIZE

            val expectedTotal = total
            if (expectedTotal != null && offset >= expectedTotal) break
        }

        return songs.distinctBy { it.id }
    }

    private suspend fun loadAnonymousHome(auth: TidalAuthInput): HomePage {
        val request =
            tidalRequest(
                path = "v2/home/feed/static",
                auth = auth,
                authenticated = false,
            )

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            check(response.isSuccessful) {
                "TIDAL home failed: ${body.ifBlank { "${response.code} ${response.message}" }}"
            }
            return parse(json.parseToJsonElement(body).jsonObject)
        }
    }

    private suspend fun loadPersonalizedHome(
        auth: TidalAuthInput,
        cursor: String? = null,
    ): HomePage {
        check(auth.hasRefreshAuth) { "TIDAL personalized home needs a refresh token" }

        val request =
            tidalRequest(
                path = "v2/home/feed/static",
                params = cursor?.let { mapOf("cursor" to it) }.orEmpty(),
                auth = auth,
                authenticated = true,
            )

        val body =
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                check(response.isSuccessful) {
                    "TIDAL personalized home failed: ${body.ifBlank { "${response.code} ${response.message}" }}"
                }
                body
            }

        val home = parsePersonalizedHome(body)
        if (home.sections.isEmpty()) {
            Timber.tag("TidalHome").w("Personalized home parsed no sections; falling back to guest home")
            return loadAnonymousHome(auth)
        }
        return home
    }

    private suspend fun tidalRequest(
        path: String,
        params: Map<String, String> = emptyMap(),
        auth: TidalAuthInput,
        authenticated: Boolean,
    ): Request {
        val accessToken = if (authenticated) accessToken(auth) else null
        val url =
            "https://api.tidal.com/${path.trimStart('/')}"
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("deviceType", "BROWSER")
                .addQueryParameter("platform", "WEB")
                .addQueryParameter("locale", "en_US")
                .addQueryParameter("countryCode", "US")
                .apply {
                    params.forEach { (key, value) ->
                        addQueryParameter(key, value)
                    }
                }.build()

        return Request
            .Builder()
            .url(url)
            .header("x-tidal-client-version", CLIENT_VERSION)
            .header("x-tidal-token", if (auth.hasRefreshAuth) USER_CLIENT_TOKEN else CLIENT_TOKEN)
            .apply {
                if (!accessToken.isNullOrBlank()) {
                    header("Authorization", "Bearer $accessToken")
                }
            }.build()
    }

    private fun tidalAuthInput(input: String): TidalAuthInput =
        TidalAuthInput(
            refreshToken = extractTidalRefreshToken(input),
            accessToken = extractTidalAccessToken(input),
        )

    private suspend fun accessToken(auth: TidalAuthInput): String =
        when {
            !auth.refreshToken.isNullOrBlank() -> accessToken(auth.refreshToken)
            !auth.accessToken.isNullOrBlank() -> auth.accessToken
            else -> accessToken(refreshToken = null)
        }

    private suspend fun accessToken(refreshToken: String?): String =
        tokenMutex.withLock {
            val cached = cachedAccessToken
            val now = System.currentTimeMillis()
            if (
                cached != null &&
                cachedRefreshToken == refreshToken &&
                now < cachedAccessTokenExpiresAtMs - 60_000L
            ) {
                return@withLock cached
            }

            val tokenResponse = createAccessToken(refreshToken)
            val accessToken = tokenResponse.accessToken ?: error("TIDAL auth did not return an access token")
            cachedAccessToken = accessToken
            cachedRefreshToken = refreshToken
            cachedAccessTokenExpiresAtMs =
                now + (tokenResponse.expiresIn ?: 300L).coerceAtLeast(60L) * 1000L
            accessToken
        }

    private fun createAccessToken(refreshToken: String?): TidalTokenResponse {
        val form =
            if (refreshToken != null) {
                FormBody
                    .Builder()
                    .add("grant_type", "refresh_token")
                    .add("refresh_token", refreshToken)
                    .add("client_id", USER_CLIENT_TOKEN)
                    .add("scope", "r_usr w_usr")
                    .build()
            } else {
                FormBody
                    .Builder()
                    .add("grant_type", "client_credentials")
                    .add("client_id", CLIENT_TOKEN)
                    .add("client_secret", CLIENT_SECRET)
                    .build()
            }

        return client
            .newCall(
                Request
                    .Builder()
                    .url(AUTH_TOKEN_URL)
                    .post(form)
                    .build(),
            ).execute()
            .use { response ->
                json.decodeFromString<TidalTokenResponse>(response.requireTidalBody("TIDAL auth token"))
            }
    }

    private fun okhttp3.Response.requireTidalBody(step: String): String {
        val text = body?.string().orEmpty()
        check(isSuccessful) { "$step failed: ${text.ifBlank { "$code $message" }}" }
        return text.ifBlank { error("$step returned an empty body") }
    }

    @Serializable
    private data class TidalTokenResponse(
        @SerialName("access_token")
        val accessToken: String? = null,
        @SerialName("expires_in")
        val expiresIn: Long? = null,
    )

    private data class TidalAuthInput(
        val refreshToken: String?,
        val accessToken: String?,
    ) {
        val hasRefreshAuth: Boolean
            get() = !refreshToken.isNullOrBlank()

        val hasUserAuth: Boolean
            get() = !refreshToken.isNullOrBlank() || !accessToken.isNullOrBlank()
    }

    private fun parsePersonalizedHome(body: String): HomePage {
        val root = json.parseToJsonElement(body).jsonObject
        val homeRoot = root.tidalHomeRoot()
        val sections =
            homeRoot
                .array("items")
                .orEmpty()
                .mapNotNull(::parsePersonalizedSection)
                .distinctBy { it.title }

        return HomePage(
            chips = null,
            sections = sections,
            continuation = root.tidalHomeCursor(),
        )
    }

    private fun parsePersonalizedSection(element: JsonElement): HomePage.Section? {
        val section = element.obj ?: return null
        val sectionType = section.string("type")?.uppercase()
        if (sectionType in TIDAL_PERSONALIZED_IGNORED_SECTION_TYPES) return null

        val title = section.tidalTitle()?.takeIf { it.isNotBlank() } ?: return null
        val items =
            section
                .array("items")
                .orEmpty()
                .mapNotNull { item ->
                    when (sectionType) {
                        "TRACK_LIST", "ARTIST_TRACK_CREDITS_CARD" ->
                            item.obj
                                ?.obj("data")
                                ?.toTidalSong()

                        else -> parsePersonalizedItem(item)
                    }
                }
                .distinctBy { it.id }

        if (items.isEmpty()) return null

        return HomePage.Section(
            title = title,
            label = section.tidalSubtitle(),
            thumbnail = section.tidalArtworkUrl(),
            endpoint = null,
            items = items,
        )
    }

    private fun parsePersonalizedItem(element: JsonElement): YTItem? {
        val wrapper = element.obj ?: return null
        val wrapperType = wrapper.string("type")?.uppercase()
        if (wrapperType == "DEEP_LINK") return null

        val data = wrapper.obj("data") ?: wrapper.tidalWrappedContent()
        val type = data.tidalItemType(wrapperType)

        return when (type) {
            "TRACK", "VIDEO" -> data.toTidalSong()
            "ALBUM" -> data.toTidalAlbum()
            "PLAYLIST" -> data.toTidalPlaylist()
            "ARTIST" -> data.toTidalArtist()
            "MIX" -> data.toTidalMix()
            else -> null
        }
    }

    private fun JsonObject.tidalHomeRoot(): JsonObject {
        if (array("items") != null) return this

        return listOf("data", "home", "feed", "homeFeed", "content", "payload", "page")
            .firstNotNullOfOrNull { key ->
                obj(key)
                    ?.tidalHomeRoot()
                    ?.takeIf { it.array("items") != null }
            } ?: this
    }

    private fun JsonObject.tidalHomeCursor(): String? =
        obj("page")?.string("cursor")
            ?: obj("data")?.tidalHomeCursor()
            ?: obj("home")?.tidalHomeCursor()
            ?: obj("feed")?.tidalHomeCursor()

    private fun JsonObject.tidalItemType(wrapperType: String?): String? =
        string("artifactType")
            ?.uppercase()
            ?.takeIf { it == "MIX" }
            ?: wrapperType
            ?: string("type")?.uppercase()
            ?: string("artifactType")?.uppercase()
            ?: when {
                string("uuid") != null -> "PLAYLIST"
                string("mixType") != null || string("trn")?.contains(":mix:", ignoreCase = true) == true -> "MIX"
                obj("album") != null || string("isrc") != null -> "TRACK"
                string("cover") != null && (string("releaseDate") != null || long("numberOfTracks") != null) -> "ALBUM"
                string("picture") != null || array("artistTypes") != null -> "ARTIST"
                else -> null
            }

    private fun parse(root: JsonObject): HomePage {
        val homeRoot = root.tidalHomeRoot()
        val sections =
            homeRoot.array("items")
                .orEmpty()
                .mapNotNull { sectionElement ->
                    val section = sectionElement.obj ?: return@mapNotNull null
                    val title = section.string("title") ?: return@mapNotNull null
                    val items =
                        section.array("items")
                            .orEmpty()
                            .mapNotNull(::parseItem)
                            .distinctBy { it.id }
                    if (items.isEmpty()) {
                        null
                    } else {
                        HomePage.Section(
                            title = title,
                            label = section.string("subtitle"),
                            thumbnail = section.string("image")?.tidalImageUrl(),
                            endpoint = null,
                            items = items,
                        )
                    }
                }

        val continuation =
            root.obj("page")
                ?.string("cursor")

        return HomePage(chips = null, sections = sections, continuation = continuation)
    }

    private fun parseItem(element: JsonElement): YTItem? {
        val wrapper = element.obj ?: return null
        val wrapperType = wrapper.string("type")?.uppercase()
        val data = wrapper.obj("data") ?: wrapper.tidalWrappedContent()
        val type =
            data.tidalItemType(wrapperType)

        return when (type) {
            "TRACK", "VIDEO" -> data.toTidalSong()
            "ALBUM" -> data.toTidalAlbum()
            "PLAYLIST" -> data.toTidalPlaylist()
            "ARTIST" -> data.toTidalArtist()
            "MIX" -> data.toTidalMix()
            else -> null
        }
    }

    private fun JsonObject.toTidalSong(): SongItem? {
        val id = string("id") ?: return null
        val title = tidalTitle() ?: return null
        val artists = tidalArtists()
        val album = obj("album")
        val thumbnail = album?.tidalArtworkUrl() ?: tidalArtworkUrl() ?: return null

        return SongItem(
            id = "tidal:track:$id",
            title = title,
            artists = artists,
            album =
                album?.let {
                    TubeAlbum(
                        name = it.tidalTitle() ?: it.string("name") ?: return@let null,
                        id = "tidal:album:${it.string("id") ?: return@let null}",
                    )
                },
            duration = long("duration")?.tidalDurationSeconds(),
            thumbnail = thumbnail,
            explicit = boolean("explicit"),
        )
    }

    private fun JsonObject.toTidalAlbum(): AlbumItem? {
        val id = string("id") ?: return null
        val title = tidalTitle() ?: return null
        val thumbnail = tidalArtworkUrl() ?: return null

        return AlbumItem(
            browseId = "tidal:album:$id",
            playlistId = "tidal:album:$id",
            title = title,
            artists = tidalArtists().takeIf { it.isNotEmpty() },
            year = string("releaseDate")?.take(4)?.toIntOrNull(),
            thumbnail = thumbnail,
            explicit = boolean("explicit"),
        )
    }

    private fun JsonObject.toTidalPlaylist(): PlaylistItem? {
        val id = string("uuid") ?: string("id") ?: return null
        val title = tidalTitle() ?: return null
        val thumbnail = tidalArtworkUrl()
        val creatorName =
            obj("creator")?.string("name")
                ?: obj("creator")?.string("username")
                ?: obj("profile")?.string("name")
                ?: array("promotedArtists")
                    .orEmpty()
                    .mapNotNull { it.obj?.string("name") }
                    .take(2)
                    .joinToString(", ")
                    .takeIf { it.isNotBlank() }

        return PlaylistItem(
            id = "tidal:playlist:$id",
            title = title,
            author = creatorName?.let { TubeArtist(name = it, id = null) },
            songCountText =
                long("numberOfTracks")?.let { "$it songs" }
                    ?: long("numberOfItems")?.let { "$it songs" },
            thumbnail = thumbnail,
            playEndpoint = null,
            shuffleEndpoint = null,
            radioEndpoint = null,
        )
    }

    private fun JsonObject.toTidalArtist(): ArtistItem? {
        val id = string("id") ?: return null
        val title = string("name") ?: tidalTitle() ?: return null

        return ArtistItem(
            id = "tidal:artist:$id",
            title = title,
            thumbnail = tidalArtworkUrl(),
            shuffleEndpoint = null,
            radioEndpoint = null,
        )
    }

    private fun JsonObject.toTidalMix(): PlaylistItem? {
        val id =
            string("id")
                ?: string("uuid")
                ?: string("trn")?.substringAfterLast(':')
                ?: return null
        val title = tidalTitle() ?: return null
        val subtitle = tidalSubtitle()

        return PlaylistItem(
            id = "tidal:mix:$id",
            title = title,
            author = subtitle?.let { TubeArtist(name = it, id = null) },
            songCountText = null,
            thumbnail = tidalArtworkUrl() ?: return null,
            playEndpoint = null,
            shuffleEndpoint = null,
            radioEndpoint = null,
        )
    }

    private fun JsonObject.findTidalAlbum(albumId: String): JsonObject? =
        collectObjects()
            .flatMap { listOf(it, it.tidalWrappedContent()) }
            .firstOrNull { item ->
                val id = item.string("id") ?: return@firstOrNull false
                id == albumId &&
                    (
                        item.tidalItemType(null) == "ALBUM" ||
                            item.string("cover") != null ||
                            item.string("releaseDate") != null
                    )
            }

    private fun JsonObject.findTidalMix(mixId: String): JsonObject? =
        collectObjects()
            .flatMap { listOf(it, it.tidalWrappedContent()) }
            .firstOrNull { item ->
                val id =
                    item.string("id")
                        ?: item.string("uuid")
                        ?: item.string("trn")?.substringAfterLast(':')
                        ?: return@firstOrNull false
                id == mixId &&
                    (
                        item.tidalItemType(null) == "MIX" ||
                            item.array("mixImages") != null ||
                            item.string("mixType") != null
                    )
            }

    private fun JsonObject.findTidalSongs(): List<SongItem> =
        collectObjects()
            .flatMap { listOf(it, it.tidalWrappedContent()) }
            .filter { it.isTidalTrackObject() }
            .mapNotNull { it.toTidalSong() }
            .distinctBy { it.id }

    private fun JsonObject.isTidalTrackObject(): Boolean {
        val type = string("type")?.uppercase()
        val artifactType = string("artifactType")?.uppercase()
        return type == "TRACK" ||
            type == "VIDEO" ||
            artifactType == "TRACK" ||
            artifactType == "VIDEO" ||
            string("isrc") != null ||
            (obj("album") != null && (long("duration") != null || array("artists") != null))
    }

    private fun JsonObject.collectObjects(): List<JsonObject> {
        val items = mutableListOf<JsonObject>()

        fun visit(element: JsonElement) {
            when (element) {
                is JsonObject -> {
                    items += element
                    element.values.forEach(::visit)
                }

                is JsonArray -> element.forEach(::visit)
                else -> Unit
            }
        }

        visit(this)
        return items
    }

    private fun JsonObject.tidalArtists(): List<TubeArtist> =
        array("artists")
            .orEmpty()
            .mapNotNull { artist ->
                val obj = artist.obj ?: return@mapNotNull null
                val name = obj.string("name") ?: return@mapNotNull null
                TubeArtist(
                    name = name,
                    id = obj.string("id")?.let { "tidal:artist:$it" },
                )
            }.ifEmpty {
                obj("artist")
                    ?.let { artist ->
                        val name = artist.string("name") ?: artist.tidalTitle() ?: return@let null
                        listOf(
                            TubeArtist(
                                name = name,
                                id = artist.string("id")?.let { "tidal:artist:$it" },
                            ),
                        )
                    }.orEmpty()
            }

    private fun JsonObject.tidalTitle(): String? =
        string("title")
            ?: string("titleText")
            ?: obj("titleTextInfo")?.string("text")
            ?: string("name")

    private fun JsonObject.tidalSubtitle(): String? =
        string("subtitle")
            ?: string("subTitle")
            ?: string("subtitleText")
            ?: obj("subtitleTextInfo")?.string("text")
            ?: obj("shortSubtitleTextInfo")?.string("text")
            ?: string("shortSubtitle")

    private fun JsonObject.tidalWrappedContent(): JsonObject =
        obj("item")?.obj("data")
            ?: obj("item")
            ?: obj("data")
            ?: obj("resource")
            ?: obj("content")
            ?: obj("track")
            ?: obj("playlist")
            ?: obj("artist")
            ?: this

    private fun Long.tidalDurationSeconds(): Int =
        if (this > 100_000L) {
            (this / 1000L).toInt()
        } else {
            toInt()
        }

    private fun JsonObject.tidalArtworkUrl(size: String = "640x640"): String? =
        string("squareImage")?.tidalImageUrl(size)
            ?: string("image")?.tidalImageUrl(size)
            ?: string("cover")?.tidalImageUrl(size)
            ?: string("picture")?.tidalImageUrl(size)
            ?: obj("images")?.obj("MEDIUM")?.string("url")?.tidalImageUrl(size)
            ?: array("mixImages")
                ?.mapNotNull { it.obj }
                ?.firstOrNull { it.string("size") == "MEDIUM" }
                ?.string("url")
                ?.tidalImageUrl(size)
            ?: array("mixImages")
                ?.firstNotNullOfOrNull { it.obj?.string("url") }
                ?.tidalImageUrl(size)
            ?: obj("album")?.tidalArtworkUrl(size)

    private fun JsonObject.bestTidalArtworkCandidate(
        title: String,
        artist: String?,
        album: String?,
    ): String? {
        val normalizedTitle = title.normalizedArtworkMatch()
        val normalizedArtist = artist?.normalizedArtworkMatch().orEmpty()
        val normalizedAlbum = album?.normalizedArtworkMatch().orEmpty()
        val threshold = if (normalizedArtist.isBlank()) 5 else 8

        val trackCandidates =
            obj("tracks")
                ?.array("items")
                .orEmpty()
                .mapNotNull { element ->
                    val track = element.obj?.tidalWrappedContent() ?: return@mapNotNull null
                    val artwork =
                        track.obj("album")?.tidalArtworkUrl("1280x1280")
                            ?: track.tidalArtworkUrl("1280x1280")
                            ?: return@mapNotNull null
                    TidalArtworkCandidate(
                        artwork = artwork,
                        score =
                            track.tidalArtworkScore(
                                normalizedTitle = normalizedTitle,
                                normalizedArtist = normalizedArtist,
                                normalizedAlbum = normalizedAlbum,
                            ),
                    )
                }

        val albumCandidates =
            obj("albums")
                ?.array("items")
                .orEmpty()
                .mapNotNull { element ->
                    val tidalAlbum = element.obj?.tidalWrappedContent() ?: return@mapNotNull null
                    val artwork = tidalAlbum.tidalArtworkUrl("1280x1280") ?: return@mapNotNull null
                    TidalArtworkCandidate(
                        artwork = artwork,
                        score =
                            tidalAlbum.tidalArtworkScore(
                                normalizedTitle = normalizedAlbum.ifBlank { normalizedTitle },
                                normalizedArtist = normalizedArtist,
                                normalizedAlbum = normalizedAlbum,
                            ),
                    )
                }

        return (trackCandidates + albumCandidates)
            .maxByOrNull { it.score }
            ?.takeIf { it.score >= threshold }
            ?.artwork
    }

    private data class TidalArtworkCandidate(
        val artwork: String,
        val score: Int,
    )

    private fun JsonObject.tidalArtworkScore(
        normalizedTitle: String,
        normalizedArtist: String,
        normalizedAlbum: String,
    ): Int {
        val itemTitle = tidalTitle()?.normalizedArtworkMatch().orEmpty()
        val itemAlbum = obj("album")?.tidalTitle()?.normalizedArtworkMatch().orEmpty()
        val itemArtists =
            tidalArtists()
                .map { it.name.normalizedArtworkMatch() }
                .filter { it.isNotBlank() }

        var score = 0
        if (itemTitle == normalizedTitle) {
            score += 6
        } else if (
            itemTitle.isNotBlank() &&
            (itemTitle.contains(normalizedTitle) || normalizedTitle.contains(itemTitle))
        ) {
            score += 3
        }

        if (normalizedArtist.isNotBlank() && itemArtists.any { it == normalizedArtist }) {
            score += 5
        } else if (normalizedArtist.isNotBlank() && itemArtists.any { it.contains(normalizedArtist) || normalizedArtist.contains(it) }) {
            score += 3
        }

        if (normalizedAlbum.isNotBlank()) {
            if (itemAlbum == normalizedAlbum || itemTitle == normalizedAlbum) {
                score += 3
            } else if (
                itemAlbum.isNotBlank() &&
                (itemAlbum.contains(normalizedAlbum) || normalizedAlbum.contains(itemAlbum))
            ) {
                score += 1
            }
        }

        return score
    }

    private fun String.normalizedArtworkMatch(): String =
        lowercase()
            .replace(Regex("""\([^)]*\)|\[[^]]*]"""), " ")
            .replace(Regex("""[^a-z0-9]+"""), " ")
            .trim()

    private fun String.tidalImageUrl(size: String = "640x640"): String? =
        takeIf { it.isNotBlank() }?.let { value ->
            if (value.startsWith("http", ignoreCase = true)) {
                value
            } else {
                "https://resources.tidal.com/images/${value.replace("-", "/")}/$size.jpg"
            }
        }
}

object SpotifyHomeFeedParser {
    fun parse(root: JsonObject): HomePage {
        val sections =
            extractSections(root)
                .mapNotNull(::parseSection)
                .distinctBy { it.title }

        return HomePage(chips = null, sections = sections)
    }

    private fun extractSections(root: JsonObject): List<JsonElement> {
        val data = root.obj("data")
        data
            ?.obj("homeSections")
            ?.array("sections")
            ?.let { return it }

        data
            ?.obj("home")
            ?.obj("sectionContainer")
            ?.obj("sections")
            ?.array("items")
            ?.let { return it }

        root
            .obj("home")
            ?.obj("sectionContainer")
            ?.obj("sections")
            ?.array("items")
            ?.let { return it }

        return root
            .obj("sectionContainer")
            ?.obj("sections")
            ?.array("items")
            .orEmpty()
    }

    private fun parseSection(element: JsonElement): HomePage.Section? {
        val section = element.obj ?: return null
        val data = section.obj("data")
        val sectionType = data?.string("__typename")
        if (sectionType != null && sectionType != "HomeGenericSectionData") return null

        val title =
            data?.obj("title")?.spotifyLabel()
                ?: data?.string("title")
                ?: section.string("title")
                ?: return null
        val items =
            section.obj("sectionItems")
                ?.array("items")
                .orEmpty()
                .mapNotNull(::parseItem)
                .distinctBy { it.id }

        return if (items.isEmpty()) {
            null
        } else {
            HomePage.Section(
                title = title,
                label = data?.obj("subtitle")?.spotifyLabel() ?: data?.string("subtitle"),
                thumbnail = null,
                endpoint = null,
                items = items,
            )
        }
    }

    private fun parseItem(element: JsonElement): YTItem? {
        val item = element.obj ?: return null
        val content = item.obj("content") ?: item.obj("item") ?: item
        val data = content.obj("data") ?: content.obj("item")?.obj("data") ?: content
        val uri = item.string("uri") ?: content.string("uri") ?: data.string("uri")
        val wrapperType = content.string("__typename") ?: item.string("__typename")
        val contentType = data.string("__typename")
        val type =
            when {
                wrapperType == "PlaylistResponseWrapper" || contentType == "Playlist" -> "Playlist"
                wrapperType == "AlbumResponseWrapper" || contentType == "Album" -> "Album"
                wrapperType == "PreReleaseResponseWrapper" || contentType == "PreRelease" -> "PreRelease"
                wrapperType == "ArtistResponseWrapper" || contentType == "Artist" -> "Artist"
                wrapperType == "TrackResponseWrapper" || contentType == "Track" -> "Track"
                else -> uri?.substringAfter("spotify:")?.substringBefore(":")?.replaceFirstChar { it.uppercase() }
            }

        return when (type) {
            "Track" -> data.toSpotifySong(uri)
            "Album", "PreRelease" -> data.toSpotifyAlbum(uri)
            "Playlist", "PseudoPlaylist" -> data.toSpotifyPlaylist(uri)
            "Artist" -> data.toSpotifyArtist(uri)
            else -> null
        }
    }

    private fun JsonObject.toSpotifySong(uri: String?): SongItem? {
        val id = spotifyId(uri) ?: return null
        val title = string("name") ?: return null
        val album = obj("albumOfTrack") ?: obj("album")
        val thumbnail = album?.spotifyArtworkUrl() ?: spotifyArtworkUrl() ?: return null
        val artists = spotifyArtists().ifEmpty { album?.spotifyArtists().orEmpty() }

        return SongItem(
            id = "spotify:track:$id",
            title = title,
            artists = artists,
            album =
                album?.let {
                    TubeAlbum(
                        name = it.string("name") ?: return@let null,
                        id = "spotify:album:${it.spotifyId(it.string("uri")) ?: return@let null}",
                    )
                },
            duration = obj("duration")?.long("totalMilliseconds")?.div(1000)?.toInt()
                ?: obj("trackDuration")?.long("totalMilliseconds")?.div(1000)?.toInt()
                ?: long("durationMs")?.div(1000)?.toInt(),
            thumbnail = thumbnail,
            explicit = isSpotifyExplicit(),
        )
    }

    private fun JsonObject.toSpotifyAlbum(uri: String?): AlbumItem? {
        val id = spotifyId(uri) ?: return null
        val title =
            string("name")
                ?: obj("preReleaseContent")?.string("name")
                ?: return null
        val content = obj("preReleaseContent") ?: this
        val thumbnail = content.spotifyArtworkUrl() ?: spotifyArtworkUrl() ?: return null

        return AlbumItem(
            browseId = "spotify:album:$id",
            playlistId = "spotify:album:$id",
            title = title,
            artists = content.spotifyArtists().takeIf { it.isNotEmpty() },
            year = content.obj("date")?.long("year")?.toInt() ?: content.string("releaseDate")?.take(4)?.toIntOrNull(),
            thumbnail = thumbnail,
            explicit = false,
        )
    }

    private fun JsonObject.toSpotifyPlaylist(uri: String?): PlaylistItem? {
        val id = spotifyId(uri) ?: return null
        val title = string("name") ?: return null
        val owner =
            obj("ownerV2")
                ?.obj("data")
                ?.let { it.string("displayName") ?: it.string("name") ?: it.string("username") }
                ?: obj("owner")
                    ?.let { it.string("displayName") ?: it.string("name") ?: it.string("username") }

        return PlaylistItem(
            id = "spotify:playlist:$id",
            title = title,
            author = owner?.let { TubeArtist(name = it, id = null) },
            songCountText =
                obj("content")?.long("totalCount")?.let { "$it songs" }
                    ?: obj("tracks")?.long("totalCount")?.let { "$it songs" },
            thumbnail = spotifyArtworkUrl() ?: obj("images")?.findSpotifyImageUrl(),
            playEndpoint = null,
            shuffleEndpoint = null,
            radioEndpoint = null,
        )
    }

    private fun JsonObject.toSpotifyArtist(uri: String?): ArtistItem? {
        val id = spotifyId(uri) ?: return null
        val title =
            obj("profile")?.string("name")
                ?: string("name")
                ?: return null

        return ArtistItem(
            id = "spotify:artist:$id",
            title = title,
            thumbnail = spotifyArtworkUrl(),
            shuffleEndpoint = null,
            radioEndpoint = null,
        )
    }

    private fun JsonObject.spotifyId(uri: String?): String? =
        string("id")
            ?: uri
                ?.substringAfterLast(':')
                ?.takeIf { it.isNotBlank() }

    private fun JsonObject.spotifyArtists(): List<TubeArtist> =
        (
            obj("artists")?.array("items")
                ?: array("artists")
        )
            .orEmpty()
            .mapNotNull { item ->
                val artist = item.obj ?: return@mapNotNull null
                val data = artist.obj("data") ?: artist
                val name =
                    data.obj("profile")?.string("name")
                        ?: data.string("name")
                        ?: return@mapNotNull null
                val id = data.string("id") ?: data.string("uri")?.substringAfterLast(':')
                TubeArtist(name = name, id = id?.let { "spotify:artist:$it" })
            }

    private fun JsonObject.spotifyArtworkUrl(): String? =
        listOf(
            obj("coverArt"),
            obj("image"),
            obj("images"),
            obj("visuals")?.obj("avatarImage"),
            obj("profile")?.obj("avatar"),
            obj("preReleaseContent")?.obj("coverArt"),
            obj("albumOfTrack")?.obj("coverArt"),
            obj("album")?.obj("coverArt"),
        ).firstNotNullOfOrNull { it?.findSpotifyImageUrl() }

    private fun JsonObject.findSpotifyImageUrl(depth: Int = 0): String? {
        if (depth > 4) return null

        string("url")
            ?.takeIf { it.startsWith("http", ignoreCase = true) }
            ?.let { return it }

        array("sources")
            ?.mapNotNull { it.obj?.string("url") }
            ?.firstOrNull { it.startsWith("http", ignoreCase = true) }
            ?.let { return it }

        array("items")
            ?.firstNotNullOfOrNull { it.obj?.findSpotifyImageUrl(depth + 1) }
            ?.let { return it }

        return listOf("coverArt", "image", "images", "visuals", "avatarImage", "avatar", "profile", "albumOfTrack")
            .firstNotNullOfOrNull { key -> obj(key)?.findSpotifyImageUrl(depth + 1) }
    }

    private fun JsonObject.spotifyLabel(): String? =
        string("transformedLabel")
            ?: string("text")
            ?: string("label")
            ?: string("name")

    private fun JsonObject.isSpotifyExplicit(): Boolean =
        obj("contentRating")
            ?.toString()
            ?.contains("EXPLICIT", ignoreCase = true) == true
}

object ExternalHomeItemIds {
    fun isExternal(item: YTItem): Boolean =
        externalProviderId(item.id) != null ||
            (item is SongItem && SoundCloudAudioProvider.isSoundCloudUrl(item.id))

    fun isExternalPlaylistId(id: String): Boolean =
        externalProviderId(id)
            ?.let { (_, type, _) -> type == "playlist" } == true

    fun externalProviderId(id: String): Triple<String, String, String>? {
        val trimmed = id.trim()
        val parts = trimmed.split(':', limit = 3)
        if (parts.size == 3) {
            val provider = parts[0].lowercase()
            val type = parts[1].lowercase()
            val externalId = parts[2].externalIdPart() ?: return null
            if (provider in ExternalProviders && type in ExternalTypes) {
                return Triple(provider, type, externalId)
            }
        }

        return externalUrlProviderId(trimmed)
    }

    fun externalMetroRoute(item: YTItem): String? {
        val (provider, type, id) = externalProviderId(item.id) ?: return null
        return when {
            provider == "metrofuse" && type == "playlist" && id == "create_offline" -> "create_offline_playlist"
            provider == "metrofuse" && type == "playlist" && id == "local" -> "auto_playlist/local"
            provider == "metrofuse" && type == "playlist" && id == "downloaded" -> "auto_playlist/local"
            provider == "metrofuse" && type == "playlist" && id.startsWith("LP") -> "local_playlist/$id"
            type == "playlist" -> "online_playlist/$provider:playlist:$id"
            provider == "spotify" && type == "album" -> "online_playlist/$provider:album:$id"
            provider == "tidal" && type in setOf("album", "mix") -> "online_playlist/$provider:$type:$id"
            provider == "soundcloud" && type in setOf("album", "mix") -> "online_playlist/$provider:$type:$id"
            else -> null
        }
    }

    fun externalUrl(item: YTItem): String? {
        val (provider, type, id) = externalProviderId(item.id) ?: return null

        return when (provider) {
            "spotify" ->
                when (type) {
                    "track", "album", "artist", "playlist" -> "https://open.spotify.com/$type/$id"
                    else -> null
                }

            "tidal" ->
                when (type) {
                    "track", "album", "artist", "playlist" -> "https://listen.tidal.com/$type/$id"
                    "mix" -> "https://listen.tidal.com/mix/$id"
                    else -> null
                }

            else -> null
        }
    }

    private fun externalUrlProviderId(id: String): Triple<String, String, String>? {
        val lower = id.lowercase()
        val provider =
            when {
                "spotify.com/" in lower -> "spotify"
                "tidal.com/" in lower -> "tidal"
                else -> return null
            }

        ExternalTypes.forEach { type ->
            listOf("/$type/", "/browse/$type/").forEach { marker ->
                val index = lower.indexOf(marker)
                if (index >= 0) {
                    val externalId = id.substring(index + marker.length).externalIdPart() ?: return@forEach
                    return Triple(provider, type, externalId)
                }
            }
        }

        return null
    }

    private fun String.externalIdPart(): String? =
        substringBefore('?')
            .substringBefore('#')
            .trim()
            .trim('/')
            .substringBefore('/')
            .takeIf { it.isNotBlank() && it != "null" }

    private val ExternalProviders = setOf("spotify", "tidal", "soundcloud", "metrofuse")
    private val ExternalTypes = listOf("playlist", "track", "album", "artist", "mix")

    fun searchQuery(item: YTItem): String =
        when (item) {
            is SongItem ->
                listOf(
                    item.title,
                    item.artists.joinToString(" ") { it.name },
                ).joinToString(" ")

            is AlbumItem ->
                listOf(
                    item.title,
                    item.artists.orEmpty().joinToString(" ") { it.name },
                ).joinToString(" ")

            is PlaylistItem ->
                listOfNotNull(
                    item.title,
                    item.author?.name,
                ).joinToString(" ")

            is ArtistItem -> item.title
            else -> item.title
        }.trim()
}

private val JsonElement.obj: JsonObject?
    get() = this as? JsonObject

private fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject

private fun JsonObject.array(name: String): JsonArray? = this[name] as? JsonArray

private fun JsonObject.string(name: String): String? =
    (this[name] as? JsonPrimitive)
        ?.contentOrNull
        ?.takeIf { it.isNotBlank() && it != "null" }

private fun JsonObject.long(name: String): Long? =
    (this[name] as? JsonPrimitive)
        ?.longOrNull

private fun JsonObject.boolean(name: String): Boolean =
    (this[name] as? JsonPrimitive)
        ?.booleanOrNull == true
