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
import com.metrolist.innertube.pages.SearchSummary
import com.metrolist.innertube.pages.SearchSummaryPage
import com.metrolist.music.utils.deezer.normalizeDeezerCookieInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.util.Locale
import java.util.concurrent.TimeUnit

object DeezerHomeFeedProvider {
    private const val API_BASE = "https://api.deezer.com"
    private const val GATEWAY_URL = "https://www.deezer.com/ajax/gw-light.php"
    private const val BROWSER_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Mobile Safari/537.36"
    private const val HOME_SECTION_LIMIT = 30
    private const val SEARCH_SECTION_LIMIT = 50
    private const val TRACK_PAGE_LIMIT = 100
    private const val PLAYLIST_SAFETY_LIMIT = 10_000
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .build()

    suspend fun load(cookie: String = ""): Result<HomePage> =
        runCatching {
            withContext(Dispatchers.IO) {
                val normalizedCookie = normalizeDeezerCookieInput(cookie).orEmpty()
                if (normalizedCookie.isNotBlank()) {
                    runCatching { loadPersonalizedHome(normalizedCookie) }
                        .getOrElse { throwable ->
                            Timber.tag("DeezerHome").w(throwable, "Deezer logged-in home failed; using public home")
                            loadPublicHome()
                        }
                } else {
                    loadPublicHome()
                }
            }
        }

    suspend fun search(
        query: String,
        cookie: String = "",
    ): Result<SearchSummaryPage> =
        runCatching {
            withContext(Dispatchers.IO) {
                SearchSummaryPage(
                    summaries =
                        buildList {
                            // Search suggestions stay on Deezer's public APIs so the session cookie
                            // is only used for personal home/collection actions.
                            addSummary("Songs", apiData("search/track", "", mapOf("q" to query, "limit" to SEARCH_SECTION_LIMIT.toString())).mapNotNull { it.toPublicSongItem() })
                            addSummary("Albums", apiData("search/album", "", mapOf("q" to query, "limit" to SEARCH_SECTION_LIMIT.toString())).mapNotNull { it.toPublicAlbumItem() })
                            addSummary("Artists", apiData("search/artist", "", mapOf("q" to query, "limit" to SEARCH_SECTION_LIMIT.toString())).mapNotNull { it.toPublicArtistItem() })
                            addSummary("Playlists", apiData("search/playlist", "", mapOf("q" to query, "limit" to SEARCH_SECTION_LIMIT.toString())).mapNotNull { it.toPublicPlaylistItem() })
                        },
                )
            }
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

                val query =
                    listOfNotNull(
                        title.takeIf { it.isNotBlank() },
                        artist?.takeIf { it.isNotBlank() },
                        album?.takeIf { it.isNotBlank() },
                    ).joinToString(" ")

                val trackCandidates =
                    apiData(
                        "search/track",
                        "",
                        mapOf("q" to query, "limit" to "10"),
                    ).mapNotNull { track ->
                        val trackAlbum = track.optJSONObject("album")
                        val artwork = trackAlbum?.deezerCoverUrl() ?: track.deezerCoverUrl() ?: return@mapNotNull null
                        DeezerArtworkCandidate(
                            artwork = artwork,
                            score =
                                deezerArtworkScore(
                                    normalizedTitle = normalizedTitle,
                                    normalizedArtist = artist?.normalizedArtworkMatch().orEmpty(),
                                    normalizedAlbum = album?.normalizedArtworkMatch().orEmpty(),
                                    itemTitle = track.stringOrNull("title") ?: track.stringOrNull("title_short"),
                                    itemArtists = listOfNotNull(track.optJSONObject("artist")?.stringOrNull("name"), track.stringOrNull("artist")),
                                    itemAlbum = trackAlbum?.stringOrNull("title"),
                                ),
                        )
                    }

                val albumCandidates =
                    album
                        ?.takeIf { it.isNotBlank() }
                        ?.let { albumQuery ->
                            apiData(
                                "search/album",
                                "",
                                mapOf("q" to listOfNotNull(albumQuery, artist).joinToString(" "), "limit" to "10"),
                            ).mapNotNull { albumJson ->
                                val artwork = albumJson.deezerCoverUrl() ?: return@mapNotNull null
                                DeezerArtworkCandidate(
                                    artwork = artwork,
                                    score =
                                        deezerArtworkScore(
                                            normalizedTitle = albumQuery.normalizedArtworkMatch(),
                                            normalizedArtist = artist?.normalizedArtworkMatch().orEmpty(),
                                            normalizedAlbum = albumQuery.normalizedArtworkMatch(),
                                            itemTitle = albumJson.stringOrNull("title"),
                                            itemArtists = listOfNotNull(albumJson.optJSONObject("artist")?.stringOrNull("name")),
                                            itemAlbum = albumJson.stringOrNull("title"),
                                        ),
                                )
                            }
                        }.orEmpty()

                val threshold = if (artist.isNullOrBlank()) 5 else 8
                (trackCandidates + albumCandidates)
                    .maxByOrNull { it.score }
                    ?.takeIf { it.score >= threshold }
                    ?.artwork
            }
        }.getOrNull()

    suspend fun loadCollection(
        collectionId: String,
        type: String,
        cookie: String = "",
    ): Result<ExternalPlaylistPage> =
        when (type.lowercase(Locale.US)) {
            "album" -> loadAlbum(collectionId, cookie)
            "artist" -> loadArtist(collectionId, cookie)
            "mix", "flow" -> loadFlow(collectionId, cookie)
            else -> loadPlaylist(collectionId, cookie)
        }

    suspend fun setTrackLiked(
        trackUriOrId: String,
        cookie: String,
        liked: Boolean,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val trackId = trackUriOrId.deezerTrackId() ?: return@withContext false
            val normalizedCookie = normalizeDeezerCookieInput(cookie).orEmpty()
            if (normalizedCookie.isBlank()) return@withContext false
            runCatching {
                val session = gatewaySession(normalizedCookie)
                gatewayCall(
                    method = if (liked) "song.addFavorite" else "song.deleteFavorite",
                    cookie = session.cookie,
                    apiToken = session.token,
                    userId = session.userId,
                    gatewayInput = """{"SNG_ID":"$trackId"}""",
                )
                true
            }.onFailure { throwable ->
                Timber.tag("DeezerHome").w(throwable, "Deezer like request failed")
            }.getOrDefault(false)
        }

    private fun loadPersonalizedHome(cookie: String): HomePage {
        val session = gatewaySession(cookie)
        val home = gatewayCall(
            method = "page.get",
            cookie = session.cookie,
            apiToken = session.token,
            userId = session.userId,
            gatewayInput = deezerPageGatewayInput("home"),
        )
        val sections =
            home.optJSONObject("results")
                ?.optJSONArray("sections")
                .orEmpty()
                .asObjects()
                .mapNotNull { section ->
                    val title = section.stringOrNull("title") ?: return@mapNotNull null
                    val items =
                        section.optJSONArray("items")
                            .orEmpty()
                            .asObjects()
                            .mapNotNull { it.toPrivateItem() }
                            .distinctBy { it.id }
                    items.takeIf { it.isNotEmpty() }?.let {
                        HomePage.Section(
                            title = title,
                            label = "Deezer",
                            thumbnail = it.firstOrNull()?.thumbnail(),
                            endpoint = null,
                            items = it,
                        )
                    }
                }

        if (sections.isEmpty()) {
            throw IllegalStateException("Deezer home returned no sections")
        }

        val publicSections = loadPublicHome().sections
        return HomePage(
            chips = null,
            sections = (sections + publicSections.take(2)).distinctBy { it.title },
        )
    }

    private fun loadPublicHome(): HomePage {
        val sections =
            buildList {
                addHomeSection(
                    "Deezer charts",
                    apiData("chart/0/tracks", "", mapOf("limit" to HOME_SECTION_LIMIT.toString())).mapNotNull { it.toPublicSongItem() },
                )
                addHomeSection(
                    "Top albums",
                    apiData("chart/0/albums", "", mapOf("limit" to HOME_SECTION_LIMIT.toString())).mapNotNull { it.toPublicAlbumItem() },
                )
                addHomeSection(
                    "Editorial playlists",
                    apiData("chart/0/playlists", "", mapOf("limit" to HOME_SECTION_LIMIT.toString())).mapNotNull { it.toPublicPlaylistItem() },
                )
                addHomeSection(
                    "Top artists",
                    apiData("chart/0/artists", "", mapOf("limit" to HOME_SECTION_LIMIT.toString())).mapNotNull { it.toPublicArtistItem() },
                )
                addHomeSection(
                    "New on Deezer",
                    apiData("search/album", "", mapOf("q" to "new music", "limit" to HOME_SECTION_LIMIT.toString())).mapNotNull { it.toPublicAlbumItem() },
                )
                addHomeSection(
                    "Fresh tracks",
                    apiData("search/track", "", mapOf("q" to "new music", "limit" to HOME_SECTION_LIMIT.toString())).mapNotNull { it.toPublicSongItem() },
                )
            }
        return HomePage(chips = null, sections = sections)
    }

    private suspend fun loadAlbum(
        albumId: String,
        cookie: String = "",
    ): Result<ExternalPlaylistPage> =
        runCatching {
            withContext(Dispatchers.IO) {
                val normalizedCookie = normalizeDeezerCookieInput(cookie).orEmpty()
                val albumJson = apiObject("album/$albumId", normalizedCookie)
                val songs =
                    albumJson
                        .optJSONObject("tracks")
                        ?.optJSONArray("data")
                        .orEmpty()
                        .asObjects()
                        .mapNotNull { it.toPublicSongItem(albumOverride = albumJson) }
                val playlist =
                    PlaylistItem(
                        id = "deezer:album:$albumId",
                        title = albumJson.stringOrNull("title") ?: "Deezer album",
                        author =
                            albumJson.optJSONObject("artist")
                                ?.stringOrNull("name")
                                ?.let { TubeArtist(name = it, id = albumJson.optJSONObject("artist")?.stringOrNull("id")?.let { id -> "deezer:artist:$id" }) },
                        songCountText = albumJson.longOrNull("nb_tracks")?.let { "$it songs" } ?: songs.size.takeIf { it > 0 }?.let { "$it songs" },
                        thumbnail = albumJson.deezerCoverUrl() ?: songs.firstOrNull()?.thumbnail,
                        playEndpoint = null,
                        shuffleEndpoint = null,
                        radioEndpoint = null,
                    )
                ExternalPlaylistPage(playlist = playlist, songs = songs)
            }
        }

    private suspend fun loadPlaylist(
        playlistId: String,
        cookie: String = "",
    ): Result<ExternalPlaylistPage> =
        runCatching {
            withContext(Dispatchers.IO) {
                val normalizedCookie = normalizeDeezerCookieInput(cookie).orEmpty()
                val playlistJson = apiObject("playlist/$playlistId", normalizedCookie)
                val songs =
                    loadTracklistSongs(
                        firstPage = playlistJson.optJSONObject("tracks"),
                        firstPageUrl = playlistJson.stringOrNull("tracklist"),
                        cookie = normalizedCookie,
                    )
                val playlist =
                    playlistJson.toPublicPlaylistItem()
                        ?: PlaylistItem(
                            id = "deezer:playlist:$playlistId",
                            title = playlistJson.stringOrNull("title") ?: "Deezer playlist",
                            author = playlistJson.optJSONObject("creator")?.stringOrNull("name")?.let { TubeArtist(name = it, id = null) },
                            songCountText = songs.size.takeIf { it > 0 }?.let { "$it songs" },
                            thumbnail = playlistJson.deezerPictureUrl() ?: songs.firstOrNull()?.thumbnail,
                            playEndpoint = null,
                            shuffleEndpoint = null,
                            radioEndpoint = null,
                        )
                ExternalPlaylistPage(playlist = playlist, songs = songs)
            }
        }

    private suspend fun loadArtist(
        artistId: String,
        cookie: String = "",
    ): Result<ExternalPlaylistPage> =
        runCatching {
            withContext(Dispatchers.IO) {
                val normalizedCookie = normalizeDeezerCookieInput(cookie).orEmpty()
                val artistJson = apiObject("artist/$artistId", normalizedCookie)
                val songs =
                    apiData(
                        "artist/$artistId/top",
                        normalizedCookie,
                        mapOf("limit" to TRACK_PAGE_LIMIT.toString()),
                    ).mapNotNull { it.toPublicSongItem() }
                val playlist =
                    PlaylistItem(
                        id = "deezer:artist:$artistId",
                        title = artistJson.stringOrNull("name") ?: "Deezer artist",
                        author = null,
                        songCountText = songs.size.takeIf { it > 0 }?.let { "$it songs" },
                        thumbnail = artistJson.deezerPictureUrl() ?: songs.firstOrNull()?.thumbnail,
                        playEndpoint = null,
                        shuffleEndpoint = null,
                        radioEndpoint = null,
                    )
                ExternalPlaylistPage(playlist = playlist, songs = songs)
            }
        }

    private suspend fun loadFlow(
        flowId: String,
        cookie: String = "",
    ): Result<ExternalPlaylistPage> =
        runCatching {
            withContext(Dispatchers.IO) {
                val normalizedCookie = normalizeDeezerCookieInput(cookie).orEmpty()
                if (normalizedCookie.isBlank()) {
                    throw IllegalStateException("Deezer Flow needs a logged-in Deezer session")
                }
                val session = gatewaySession(normalizedCookie)
                val flowType = flowId.takeIf { it.isNotBlank() && it != "flow" } ?: "FLOW"
                val requests =
                    listOf(
                        DeezerGatewayRequest(
                            method = "song.getListByUserMood",
                            input = """{"TYPE":"$flowType","NB":$TRACK_PAGE_LIMIT}""",
                        ),
                        DeezerGatewayRequest(
                            method = "song.getListByUserMood",
                            input = """{"TYPE":"FLOW","NB":$TRACK_PAGE_LIMIT}""",
                        ),
                        DeezerGatewayRequest(
                            method = "radio.getUserRadio",
                            input = """{"USER_ID":"${session.userId}","NB":$TRACK_PAGE_LIMIT}""",
                        ),
                    )
                val songs =
                    requests
                        .firstNotNullOfOrNull { request ->
                            runCatching {
                                gatewayCall(
                                    method = request.method,
                                    cookie = session.cookie,
                                    apiToken = session.token,
                                    userId = session.userId,
                                    gatewayInput = request.input,
                                ).privateSongItems()
                                    .distinctBy { it.id }
                                    .takeIf { it.isNotEmpty() }
                            }.onFailure { throwable ->
                                Timber.tag("DeezerHome").w(throwable, "Deezer Flow request failed: ${request.method}")
                            }.getOrNull()
                        }.orEmpty()

                val playlist =
                    PlaylistItem(
                        id = "deezer:mix:$flowId",
                        title = flowId.toDeezerFlowTitle(),
                        author = TubeArtist(name = "Deezer", id = null),
                        songCountText = songs.size.takeIf { it > 0 }?.let { "$it songs" },
                        thumbnail = songs.firstOrNull()?.thumbnail,
                        playEndpoint = null,
                        shuffleEndpoint = null,
                        radioEndpoint = null,
                    )
                ExternalPlaylistPage(playlist = playlist, songs = songs)
            }
        }

    private fun loadTracklistSongs(
        firstPage: JSONObject?,
        firstPageUrl: String?,
        cookie: String,
    ): List<SongItem> {
        val songs = mutableListOf<SongItem>()
        var page = firstPage
        var nextUrl = firstPage?.stringOrNull("next") ?: firstPageUrl
        var guard = 0

        while (songs.size < PLAYLIST_SAFETY_LIMIT && guard++ < 120) {
            val data = page?.optJSONArray("data")
            if (data != null) {
                songs += data.asObjects().mapNotNull { it.toPublicSongItem() }
                nextUrl = page.stringOrNull("next")
            } else if (!nextUrl.isNullOrBlank()) {
                page = apiObjectFromUrl(nextUrl, cookie)
                continue
            }

            if (nextUrl.isNullOrBlank()) break
            page = apiObjectFromUrl(nextUrl, cookie)
        }

        return songs.distinctBy { it.id }
    }

    private fun gatewaySession(cookie: String): GatewaySession {
        val userData = gatewayCall(
            method = "deezer.getUserData",
            cookie = cookie,
            apiToken = "null",
            userId = "0",
        )
        val results = userData.optJSONObject("results")
            ?: throw IllegalStateException("Deezer login did not return user data")
        val user = results.optJSONObject("USER")
            ?: throw IllegalStateException("Deezer login did not return a user")
        val userId = user.stringOrNull("USER_ID")
            ?.takeIf { it != "0" }
            ?: throw IllegalStateException("Deezer login cookie is missing or expired")
        val token = results.stringOrNull("checkForm")
            ?: throw IllegalStateException("Deezer login token is missing")
        return GatewaySession(
            cookie = mergeSessionCookie(cookie, userData.stringOrNull("_metroSetCookie")),
            token = token,
            userId = userId,
        )
    }

    private fun gatewayCall(
        method: String,
        cookie: String,
        apiToken: String,
        userId: String,
        gatewayInput: String? = null,
    ): JSONObject {
        val url =
            GATEWAY_URL
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("method", method)
                .addQueryParameter("input", "3")
                .addQueryParameter("api_version", "1.0")
                .addQueryParameter("api_token", apiToken)
                .apply {
                    if (!gatewayInput.isNullOrBlank()) {
                        addQueryParameter("gateway_input", gatewayInput)
                    }
                }
                .build()
        val request =
            Request
                .Builder()
                .url(url)
                .post(JSONObject().toString().toRequestBody(JSON_MEDIA_TYPE))
                .headers(deezerHeaders(cookie, userId))
                .build()

        client.newCall(request).execute().use { response ->
            val payload = response.body.string()
            if (!response.isSuccessful) {
                throw IllegalStateException("Deezer gateway HTTP ${response.code}: ${payload.take(160)}")
            }
            val root = JSONObject(payload)
            response.headers("Set-Cookie")
                .firstOrNull()
                ?.let { root.put("_metroSetCookie", it) }
            root.optJSONObject("error")?.takeIf { it.length() > 0 }?.let { error ->
                throw IllegalStateException(error.toString().take(180))
            }
            return root
        }
    }

    private fun apiObject(
        path: String,
        cookie: String,
        params: Map<String, String> = emptyMap(),
    ): JSONObject {
        val url =
            API_BASE
                .toHttpUrl()
                .newBuilder()
                .addPathSegments(path.trim('/'))
                .apply {
                    params.forEach { (name, value) -> addQueryParameter(name, value) }
                }
                .build()
        return apiObjectFromUrl(url.toString(), cookie)
    }

    private fun apiData(
        path: String,
        cookie: String,
        params: Map<String, String> = emptyMap(),
    ): List<JSONObject> =
        runCatching {
            apiObject(path, cookie, params)
                .optJSONArray("data")
                .orEmpty()
                .asObjects()
        }.getOrElse { throwable ->
            Timber.tag("DeezerHome").w(throwable, "Deezer API data failed for $path")
            emptyList()
        }

    private fun apiObjectFromUrl(
        rawUrl: String,
        cookie: String,
    ): JSONObject {
        val url = rawUrl.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Invalid Deezer URL")
        val request =
            Request
                .Builder()
                .url(url)
                .get()
                .headers(deezerHeaders(cookie, userId = "0"))
                .build()
        client.newCall(request).execute().use { response ->
            val payload = response.body.string()
            if (!response.isSuccessful) {
                throw IllegalStateException("Deezer API HTTP ${response.code}: ${payload.take(160)}")
            }
            val root = JSONObject(payload)
            root.optJSONObject("error")?.let { error ->
                throw IllegalStateException(error.stringOrNull("message") ?: error.toString().take(160))
            }
            return root
        }
    }

    private fun deezerHeaders(
        cookie: String,
        userId: String,
    ) = okhttp3.Headers
        .Builder()
        .add("Accept", "application/json")
        .add("Content-Type", "application/json")
        .add("User-Agent", BROWSER_USER_AGENT)
        .add("Referer", "https://www.deezer.com/")
        .add("Accept-Language", "en-US,en;q=0.9")
        .add("Content-Language", "en")
        .apply {
            if (cookie.isNotBlank()) add("Cookie", cookie)
            if (userId.isNotBlank() && userId != "0") add("x-deezer-user", userId)
        }
        .build()

    private fun mergeSessionCookie(
        cookie: String,
        setCookie: String?,
    ): String {
        val sid = setCookie
            ?.split(';')
            ?.firstOrNull { it.trim().startsWith("sid=", ignoreCase = true) }
        return normalizeDeezerCookieInput(
            listOfNotNull(cookie, sid).joinToString("; "),
        ).orEmpty()
    }

    private fun deezerPageGatewayInput(page: String): String =
        """
        {"PAGE":"$page","VERSION":"2.5","SUPPORT":{"ads":[],"deeplink-list":["deeplink"],"event-card":["live-event"],"grid-preview-one":["album","artist","artistLineUp","channel","livestream","flow","playlist","radio","show","smarttracklist","track","user","video-link","external-link"],"grid-preview-two":["album","artist","artistLineUp","channel","livestream","flow","playlist","radio","show","smarttracklist","track","user","video-link","external-link"],"grid":["album","artist","artistLineUp","channel","livestream","flow","playlist","radio","show","smarttracklist","track","user","video-link","external-link"],"horizontal-grid":["album","artist","artistLineUp","channel","livestream","flow","playlist","radio","show","smarttracklist","track","user","video-link","external-link"],"horizontal-list":["track","song"],"item-highlight":["radio"],"large-card":["album","external-link","playlist","show","video-link"],"list":["episode"],"mini-banner":["external-link"],"slideshow":["album","artist","channel","external-link","flow","livestream","playlist","show","smarttracklist","user","video-link","external-link"],"small-horizontal-grid":["flow"],"long-card-horizontal-grid":["album","artist","artistLineUp","channel","livestream","flow","playlist","radio","show","smarttracklist","track","user","video-link","external-link"],"filterable-grid":["flow"]},"LANG":"en","OPTIONS":["deeplink_newsandentertainment","deeplink_subscribeoffer"]}
        """.trimIndent()

    private fun MutableList<HomePage.Section>.addHomeSection(
        title: String,
        items: List<YTItem>,
    ) {
        val distinctItems = items.distinctBy { it.id }
        if (distinctItems.isEmpty()) return
        add(
            HomePage.Section(
                title = title,
                label = "Deezer",
                thumbnail = distinctItems.firstOrNull()?.thumbnail(),
                endpoint = null,
                items = distinctItems,
            ),
        )
    }

    private fun MutableList<SearchSummary>.addSummary(
        title: String,
        items: List<YTItem>,
    ) {
        val distinctItems = items.distinctBy { it.id }
        if (distinctItems.isNotEmpty()) {
            add(SearchSummary(title = title, items = distinctItems))
        }
    }

    private fun JSONObject.toPrivateItem(): YTItem? {
        val data = unwrap()
        val type = data.stringOrNull("__TYPE__").orEmpty().lowercase(Locale.US)
        return when {
            "song" in type || "track" in type -> data.toPrivateSongItem()
            "album" in type -> data.toPrivateAlbumItem()
            "flow" in type -> data.toPrivateFlowItem()
            "playlist" in type -> data.toPrivatePlaylistItem()
            "artist" in type -> data.toPrivateArtistItem()
            else -> null
        }
    }

    private fun JSONObject.toPrivateSongItem(): SongItem? {
        val id = stringOrNull("SNG_ID") ?: stringOrNull("id") ?: return null
        val title =
            listOfNotNull(
                stringOrNull("SNG_TITLE") ?: stringOrNull("title"),
                stringOrNull("VERSION")?.takeIf { it.isNotBlank() },
            ).joinToString(" ").takeIf { it.isNotBlank() } ?: return null
        val albumId = stringOrNull("ALB_ID")
        val albumTitle = stringOrNull("ALB_TITLE")
        val artists = privateArtists().takeIf { it.isNotEmpty() }
            ?: stringOrNull("ART_NAME")?.let { listOf(TubeArtist(name = it, id = stringOrNull("ART_ID")?.let { artistId -> "deezer:artist:$artistId" })) }
            ?: emptyList()
        return SongItem(
            id = "deezer:track:$id",
            title = title,
            artists = artists,
            album = albumTitle?.let { TubeAlbum(name = it, id = albumId?.let { value -> "deezer:album:$value" } ?: "") },
            duration = longOrNull("DURATION")?.toInt(),
            thumbnail = deezerPrivateImageUrl(stringOrNull("ALB_PICTURE"), "cover").orEmpty(),
            explicit = stringOrNull("EXPLICIT_LYRICS") == "1",
        )
    }

    private fun JSONObject.toPrivateAlbumItem(): AlbumItem? {
        val id = stringOrNull("ALB_ID") ?: return null
        val title = stringOrNull("ALB_TITLE") ?: return null
        return AlbumItem(
            browseId = "deezer:album:$id",
            playlistId = "deezer:album:$id",
            title = title,
            artists = privateArtists().takeIf { it.isNotEmpty() },
            year =
                stringOrNull("ORIGINAL_RELEASE_DATE")
                    ?.take(4)
                    ?.toIntOrNull()
                    ?: stringOrNull("PHYSICAL_RELEASE_DATE")?.take(4)?.toIntOrNull(),
            thumbnail = deezerPrivateImageUrl(stringOrNull("ALB_PICTURE"), "cover").orEmpty(),
            explicit = false,
        )
    }

    private fun JSONObject.toPrivatePlaylistItem(): PlaylistItem? {
        val id = stringOrNull("PLAYLIST_ID") ?: stringOrNull("id") ?: return null
        val title = stringOrNull("TITLE") ?: stringOrNull("title") ?: return null
        val pictureType = stringOrNull("PICTURE_TYPE") ?: "playlist"
        return PlaylistItem(
            id = "deezer:playlist:$id",
            title = title,
            author = null,
            songCountText = longOrNull("NB_SONG")?.let { "$it songs" },
            thumbnail = deezerPrivateImageUrl(stringOrNull("PLAYLIST_PICTURE"), pictureType),
            playEndpoint = null,
            shuffleEndpoint = null,
            radioEndpoint = null,
        )
    }

    private fun JSONObject.toPrivateFlowItem(): PlaylistItem? {
        val id =
            listOfNotNull(
                stringOrNull("FLOW_ID"),
                stringOrNull("FLOW_TYPE"),
                stringOrNull("TYPE"),
                stringOrNull("id"),
            ).firstOrNull()
                ?.lowercase(Locale.US)
                ?.replace(Regex("""[^a-z0-9_-]+"""), "_")
                ?.trim('_')
                ?.takeIf { it.isNotBlank() }
                ?: "flow"
        val title =
            stringOrNull("TITLE")
                ?: stringOrNull("title")
                ?: stringOrNull("LABEL")
                ?: id.toDeezerFlowTitle()
        val pictureType = stringOrNull("PICTURE_TYPE") ?: "playlist"
        return PlaylistItem(
            id = "deezer:mix:$id",
            title = title,
            author = TubeArtist(name = "Deezer", id = null),
            songCountText = null,
            thumbnail =
                deezerPrivateImageUrl(stringOrNull("PICTURE"), pictureType)
                    ?: deezerPrivateImageUrl(stringOrNull("FLOW_PICTURE"), pictureType)
                    ?: deezerPictureUrl(),
            playEndpoint = null,
            shuffleEndpoint = null,
            radioEndpoint = null,
        )
    }

    private fun JSONObject.toPrivateArtistItem(): ArtistItem? {
        val id = stringOrNull("ART_ID") ?: return null
        val title = stringOrNull("ART_NAME") ?: return null
        return ArtistItem(
            id = "deezer:artist:$id",
            title = title,
            thumbnail = deezerPrivateImageUrl(stringOrNull("ART_PICTURE"), "artist"),
            shuffleEndpoint = null,
            radioEndpoint = null,
        )
    }

    private fun JSONObject.toPublicSongItem(albumOverride: JSONObject? = null): SongItem? {
        val id = stringOrNull("id") ?: return null
        val title = stringOrNull("title") ?: stringOrNull("title_short") ?: return null
        val artist = optJSONObject("artist")
        val album = optJSONObject("album") ?: albumOverride
        val artistName = artist?.stringOrNull("name") ?: stringOrNull("artist") ?: "Deezer"
        return SongItem(
            id = "deezer:track:$id",
            title = title,
            artists =
                listOf(
                    TubeArtist(
                        name = artistName,
                        id = artist?.stringOrNull("id")?.let { "deezer:artist:$it" },
                    ),
                ),
            album =
                album?.stringOrNull("title")?.let { albumTitle ->
                    TubeAlbum(
                        name = albumTitle,
                        id = album.stringOrNull("id")?.let { "deezer:album:$it" } ?: "",
                    )
                },
            duration = longOrNull("duration")?.toInt(),
            thumbnail = album?.deezerCoverUrl().orEmpty(),
            explicit = optBoolean("explicit_lyrics", false),
        )
    }

    private fun JSONObject.toPublicAlbumItem(): AlbumItem? {
        val id = stringOrNull("id") ?: return null
        val title = stringOrNull("title") ?: return null
        val artist = optJSONObject("artist")
        return AlbumItem(
            browseId = "deezer:album:$id",
            playlistId = "deezer:album:$id",
            title = title,
            artists =
                artist
                    ?.stringOrNull("name")
                    ?.let { listOf(TubeArtist(name = it, id = artist.stringOrNull("id")?.let { artistId -> "deezer:artist:$artistId" })) },
            year = stringOrNull("release_date")?.take(4)?.toIntOrNull(),
            thumbnail = deezerCoverUrl().orEmpty(),
            explicit = optBoolean("explicit_lyrics", false),
        )
    }

    private fun JSONObject.toPublicPlaylistItem(): PlaylistItem? {
        val id = stringOrNull("id") ?: return null
        val title = stringOrNull("title") ?: return null
        val creatorName =
            optJSONObject("creator")?.stringOrNull("name")
                ?: optJSONObject("user")?.stringOrNull("name")
        return PlaylistItem(
            id = "deezer:playlist:$id",
            title = title,
            author = creatorName?.let { TubeArtist(name = it, id = null) },
            songCountText = longOrNull("nb_tracks")?.let { "$it songs" },
            thumbnail = deezerPictureUrl(),
            playEndpoint = null,
            shuffleEndpoint = null,
            radioEndpoint = null,
        )
    }

    private fun JSONObject.toPublicArtistItem(): ArtistItem? {
        val id = stringOrNull("id") ?: return null
        val title = stringOrNull("name") ?: return null
        return ArtistItem(
            id = "deezer:artist:$id",
            title = title,
            thumbnail = deezerPictureUrl(),
            shuffleEndpoint = null,
            radioEndpoint = null,
        )
    }

    private fun JSONObject.privateArtists(): List<TubeArtist> =
        optJSONArray("ARTISTS")
            .orEmpty()
            .asObjects()
            .mapNotNull { artist ->
                val name = artist.stringOrNull("ART_NAME") ?: return@mapNotNull null
                TubeArtist(
                    name = name,
                    id = artist.stringOrNull("ART_ID")?.let { "deezer:artist:$it" },
                )
            }

    private fun JSONObject.unwrap(): JSONObject =
        optJSONObject("data") ?: optJSONObject("DATA") ?: this

    private fun JSONObject.privateSongItems(): List<SongItem> =
        buildList {
            collectPrivateSongItems(this@privateSongItems, this)
        }

    private fun collectPrivateSongItems(
        value: Any?,
        output: MutableList<SongItem>,
        depth: Int = 0,
    ) {
        if (depth > 7) return
        when (value) {
            is JSONObject -> {
                value.toPrivateSongItem()?.let {
                    output += it
                    return
                }
                val keys = value.keys()
                while (keys.hasNext()) {
                    collectPrivateSongItems(value.opt(keys.next()), output, depth + 1)
                }
            }

            is JSONArray -> {
                for (index in 0 until value.length()) {
                    collectPrivateSongItems(value.opt(index), output, depth + 1)
                }
            }
        }
    }

    private fun JSONObject.deezerCoverUrl(): String? =
        stringOrNull("cover_xl")
            ?: stringOrNull("cover_big")
            ?: stringOrNull("cover_medium")
            ?: deezerPrivateImageUrl(stringOrNull("md5_image"), "cover")

    private fun JSONObject.deezerPictureUrl(): String? =
        stringOrNull("picture_xl")
            ?: stringOrNull("picture_big")
            ?: stringOrNull("picture_medium")
            ?: deezerPrivateImageUrl(stringOrNull("md5_image"), stringOrNull("picture_type") ?: "playlist")

    private fun deezerPrivateImageUrl(
        md5: String?,
        type: String?,
    ): String? =
        if (md5.isNullOrBlank() || type.isNullOrBlank()) {
            null
        } else {
            "https://cdn-images.dzcdn.net/images/$type/$md5/1000x1000-000000-80-0-0.jpg"
        }

    private data class DeezerArtworkCandidate(
        val artwork: String,
        val score: Int,
    )

    private data class DeezerGatewayRequest(
        val method: String,
        val input: String,
    )

    private fun deezerArtworkScore(
        normalizedTitle: String,
        normalizedArtist: String,
        normalizedAlbum: String,
        itemTitle: String?,
        itemArtists: List<String>,
        itemAlbum: String?,
    ): Int {
        val candidateTitle = itemTitle?.normalizedArtworkMatch().orEmpty()
        val candidateAlbum = itemAlbum?.normalizedArtworkMatch().orEmpty()
        val candidateArtists = itemArtists.map { it.normalizedArtworkMatch() }.filter { it.isNotBlank() }

        var score = 0
        if (candidateTitle == normalizedTitle) {
            score += 6
        } else if (
            candidateTitle.isNotBlank() &&
            (candidateTitle.contains(normalizedTitle) || normalizedTitle.contains(candidateTitle))
        ) {
            score += 3
        }

        if (normalizedArtist.isNotBlank() && candidateArtists.any { it == normalizedArtist }) {
            score += 5
        } else if (normalizedArtist.isNotBlank() && candidateArtists.any { it.contains(normalizedArtist) || normalizedArtist.contains(it) }) {
            score += 3
        }

        if (normalizedAlbum.isNotBlank()) {
            if (candidateAlbum == normalizedAlbum || candidateTitle == normalizedAlbum) {
                score += 4
            } else if (
                candidateAlbum.isNotBlank() &&
                (candidateAlbum.contains(normalizedAlbum) || normalizedAlbum.contains(candidateAlbum))
            ) {
                score += 2
            }
        }

        return score
    }

    private fun String.normalizedArtworkMatch(): String =
        lowercase()
            .replace(Regex("""\([^)]*\)|\[[^]]*]"""), " ")
            .replace(Regex("""[^a-z0-9]+"""), " ")
            .trim()

    private fun String.deezerTrackId(): String? =
        trim()
            .let { value ->
                when {
                    value.startsWith("deezer:track:", ignoreCase = true) -> value.substringAfterLast(':')
                    value.contains("deezer.com/track/", ignoreCase = true) ->
                        value
                            .substringAfter("deezer.com/track/", "")
                            .substringBefore('?')
                            .substringBefore('#')
                            .substringBefore('/')
                    value.matches(Regex("^\\d+$")) -> value
                    else -> null
                }
            }?.takeIf { it.matches(Regex("^\\d+$")) }

    private fun String.toDeezerFlowTitle(): String =
        if (equals("flow", ignoreCase = true) || equals("FLOW", ignoreCase = true)) {
            "Flow"
        } else {
            split(Regex("""[_\-\s]+"""))
                .filter { it.isNotBlank() }
                .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase(Locale.US) } }
                .ifBlank { "Flow" }
        }

    private fun YTItem.thumbnail(): String? =
        when (this) {
            is SongItem -> thumbnail
            is AlbumItem -> thumbnail
            is PlaylistItem -> thumbnail
            is ArtistItem -> thumbnail
            else -> null
        }

    private val YTItem.id: String
        get() =
            when (this) {
                is SongItem -> id
                is AlbumItem -> id
                is PlaylistItem -> id
                is ArtistItem -> id
                else -> title
            }

    private fun JSONArray?.asObjects(): List<JSONObject> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                optJSONObject(index)?.let(::add)
            }
        }
    }

    private fun JSONArray?.orEmpty(): JSONArray = this ?: JSONArray()

    private fun JSONObject.stringOrNull(name: String): String? =
        when (val value = opt(name)) {
            null, JSONObject.NULL -> null
            else -> value.toString().takeIf { it.isNotBlank() && it != "null" }
        }

    private fun JSONObject.longOrNull(name: String): Long? =
        when (val value = opt(name)) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull()
            else -> null
        }

    private data class GatewaySession(
        val cookie: String,
        val token: String,
        val userId: String,
    )
}
